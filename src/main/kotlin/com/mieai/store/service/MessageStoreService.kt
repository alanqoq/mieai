package com.mieai.store.service

import com.mieai.store.config.ConfigManager
import com.mieai.store.db.DatabaseFactory
import com.mieai.store.db.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 消息存储服务
 * 提供异步消息存储、批量提交
 */
object MessageStoreService {

    private val pendingMessages = mutableListOf<Pair<Long, MessageEntity>>()
    private val lock = Any()
    private var lastFlushTime = System.currentTimeMillis()

    private const val BATCH_SIZE = 100
    private const val FLUSH_INTERVAL_MS = 5000L  // 5 秒

    /**
     * 初始化存储服务
     */
    fun init() {
        val config = ConfigManager.load()
        if (config.storage.enabled) {
            DatabaseFactory.initialize()
            println("[mieai-store] 消息存储服务已启用，类型: ${config.storage.type}")
        } else {
            println("[mieai-store] 消息存储服务已禁用")
        }
    }

    /**
     * 异步保存消息（协程中调用）
     */
    suspend fun saveMessage(
        groupId: Long,
        messageId: Long,
        content: String,
        userQq: Long,
        timestamp: Long,
        metadata: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            if (!ConfigManager.config.storage.enabled) return@withContext

            val message = MessageEntity(
                messageId = messageId,
                content = content,
                userQq = userQq,
                timestamp = timestamp,
                metadata = metadata
            )

            val shouldFlush: Boolean
            synchronized(lock) {
                pendingMessages.add(groupId to message)
                shouldFlush = pendingMessages.size >= BATCH_SIZE ||
                        (System.currentTimeMillis() - lastFlushTime) >= FLUSH_INTERVAL_MS
            }

            if (shouldFlush) {
                flushPending()
            }
        } catch (e: Exception) {
            System.err.println("[mieai-store] 保存消息异常: ${e.message}")
        }
    }

    /**
     * 批量提交待写入消息
     */
    fun flushPending() {
        val toFlush: List<Pair<Long, MessageEntity>>
        synchronized(lock) {
            if (pendingMessages.isEmpty()) return
            toFlush = pendingMessages.toList()
            pendingMessages.clear()
            lastFlushTime = System.currentTimeMillis()
        }

        try {
            val provider = DatabaseFactory.getProvider()
            // 按群分组批量写入
            val grouped = toFlush.groupBy { it.first }
            for ((groupId, messages) in grouped) {
                val entities = messages.map { it.second }
                val count = provider.saveMessages(groupId, entities)
                if (count > 0) {
                    println("[mieai-store] 批量写入群 $groupId: $count 条消息")
                }
            }
        } catch (e: Exception) {
            System.err.println("[mieai-store] 批量提交失败: ${e.message}")
        }
    }

    /**
     * 获取历史消息
     */
    suspend fun getHistory(groupId: Long, limit: Int = 20, offset: Int = 0): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            try {
                DatabaseFactory.getProvider().getHistory(groupId, limit, offset)
            } catch (e: Exception) {
                System.err.println("[mieai-store] 查询历史失败: ${e.message}")
                emptyList()
            }
        }

    /**
     * 关键词搜索
     */
    suspend fun searchByKeyword(groupId: Long, keyword: String, limit: Int = 20): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            try {
                DatabaseFactory.getProvider().searchByKeyword(groupId, keyword, limit)
            } catch (e: Exception) {
                System.err.println("[mieai-store] 搜索失败: ${e.message}")
                emptyList()
            }
        }

    /**
     * 按用户搜索
     */
    suspend fun searchByUser(groupId: Long, userQq: Long, limit: Int = 20): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            try {
                DatabaseFactory.getProvider().searchByUser(groupId, userQq, limit)
            } catch (e: Exception) {
                System.err.println("[mieai-store] 搜索失败: ${e.message}")
                emptyList()
            }
        }

    /**
     * 按时间范围搜索
     */
    suspend fun searchByTimeRange(groupId: Long, startTime: Long, endTime: Long, limit: Int = 20): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            try {
                DatabaseFactory.getProvider().searchByTimeRange(groupId, startTime, endTime, limit)
            } catch (e: Exception) {
                System.err.println("[mieai-store] 搜索失败: ${e.message}")
                emptyList()
            }
        }

    /**
     * 获取未分析消息
     */
    suspend fun getUnanalyzedMessages(groupId: Long, limit: Int = 100): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            try {
                DatabaseFactory.getProvider().getUnanalyzedMessages(groupId, limit)
            } catch (e: Exception) {
                System.err.println("[mieai-store] 查询未分析消息失败: ${e.message}")
                emptyList()
            }
        }

    /**
     * 标记消息为已分析
     */
    suspend fun markAnalyzed(groupId: Long, messageIds: List<Long>, analyzedBy: String) =
        withContext(Dispatchers.IO) {
            try {
                DatabaseFactory.getProvider().markAnalyzed(groupId, messageIds, analyzedBy)
            } catch (e: Exception) {
                System.err.println("[mieai-store] 标记已分析失败: ${e.message}")
            }
        }

    /**
     * 获取消息统计
     */
    suspend fun getStats(groupId: Long): Map<String, Any> = withContext(Dispatchers.IO) {
        try {
            val provider = DatabaseFactory.getProvider()
            mapOf(
                "count" to provider.getMessageCount(groupId),
                "dbSize" to provider.getDatabaseSize()
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "unknown"))
        }
    }

    /**
     * 关闭服务
     */
    fun close() {
        flushPending()
        DatabaseFactory.close()
    }
}
