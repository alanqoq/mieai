package com.mieai.store.service

import com.mieai.store.config.ConfigManager
import com.mieai.store.db.DatabaseFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 清理服务
 * 定期检查并清理过期/超额/已分析消息
 */
object CleanupService {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mieai-cleanup").apply { isDaemon = true }
    }

    private var running = false

    /**
     * 启动清理定时任务
     */
    fun start() {
        if (running) return
        running = true

        val config = ConfigManager.config.messageLimits
        val intervalHours = config.cleanupIntervalHours.toLong()

        scheduler.scheduleAtFixedRate(
            { runCleanup() },
            intervalHours,  // 首次延迟
            intervalHours,
            TimeUnit.HOURS
        )

        println("[mieai-store] 清理服务已启动，间隔 ${intervalHours} 小时")
    }

    /**
     * 停止清理服务
     */
    fun stop() {
        running = false
        scheduler.shutdown()
        println("[mieai-store] 清理服务已停止")
    }

    /**
     * 执行清理
     */
    private fun runCleanup() {
        try {
            val config = ConfigManager.config.messageLimits
            val provider = DatabaseFactory.getProvider()
            val groups = provider.listGroupIds()

            for (groupId in groups) {
                try {
                    cleanupGroup(groupId, config)
                } catch (e: Exception) {
                    System.err.println("[mieai-store] 清理群 $groupId 失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            System.err.println("[mieai-store] 清理任务异常: ${e.message}")
        }
    }

    /**
     * 清理单个群
     */
    private fun cleanupGroup(groupId: Long, config: com.mieai.store.config.MessageLimits) {
        val provider = DatabaseFactory.getProvider()
        val count = provider.getMessageCount(groupId)
        var totalDeleted = 0

        // 1. 过期清理
        if (config.retentionDays > 0) {
            val beforeTimestamp = System.currentTimeMillis() - config.retentionDays.toLong() * 24 * 60 * 60 * 1000
            val deleted = provider.deleteBeforeTimestamp(groupId, beforeTimestamp)
            if (deleted > 0) {
                println("[mieai-store] 群 $groupId 过期清理: $deleted 条")
                totalDeleted += deleted
            }
        }

        // 2. 已分析清理（如果同时满足过期条件）
        if (config.retentionDays > 0) {
            val deleted = provider.deleteAnalyzed(groupId)
            if (deleted > 0) {
                println("[mieai-store] 群 $groupId 已分析清理: $deleted 条")
                totalDeleted += deleted
            }
        }

        // 3. 超额清理
        val currentCount = provider.getMessageCount(groupId)
        if (currentCount > config.maxPerGroup) {
            val deleted = provider.keepLatest(groupId, config.maxPerGroup)
            if (deleted > 0) {
                println("[mieai-store] 群 $groupId 超额清理: $deleted 条 (保留 ${config.maxPerGroup})")
                totalDeleted += deleted
            }
        }

        // 4. SQLite VACUUM
        if (totalDeleted >= config.minCleanupCount) {
            try {
                val dbProvider = provider
                if (dbProvider is com.mieai.store.db.SqliteProvider) {
                    dbProvider.vacuum()
                }
            } catch (_: Exception) {}
        }

        if (totalDeleted > 0) {
            println("[mieai-store] 群 $groupId 清理完成: 共 $totalDeleted 条")
        }
    }

    /**
     * 手动触发清理
     */
    fun triggerCleanup() {
        Thread { runCleanup() }.start()
    }
}
