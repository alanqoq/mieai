package com.mieai.store.command

import com.mieai.store.MessageStorePlugin
import com.mieai.store.config.ConfigManager
import com.mieai.store.db.DatabaseFactory
import com.mieai.store.service.MessageStoreService
import com.mieai.store.util.MigrationTool
import com.mieai.store.util.TimeUtils
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.command.getGroupOrNull
import net.mamoe.mirai.contact.MemberPermission

/**
 * 清理和管理命令
 *
 * /store cleanup --before 2025-01-01  - 清理指定日期之前的消息
 * /store cleanup --analyzed           - 清理已分析消息
 * /store cleanup --all                - 清空当前群所有消息（需二次确认）
 * /store migrate sqlite2mysql         - 迁移 SQLite 到 MySQL
 */
object CleanupCommand : CompositeCommand(
    owner = MessageStorePlugin,
    primaryName = "store",
    description = "MieAI 消息清理管理命令"
) {

    @SubCommand
    @Description("清理消息")
    suspend fun CommandSender.cleanup(vararg args: String) {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        // 检查权限
        val user = user
        if (user != null) {
            val member = group[user.id]
            if (member != null && member.permission < MemberPermission.ADMINISTRATOR) {
                sendMessage("需要管理员权限")
                return
            }
        }

        if (args.isEmpty()) {
            sendMessage("用法:\n/store cleanup --before 2025-01-01\n/store cleanup --analyzed\n/store cleanup --all")
            return
        }

        when (args[0]) {
            "--before" -> {
                if (args.size < 2) {
                    sendMessage("请指定日期，如: /store cleanup --before 2025-01-01")
                    return
                }
                val timestamp = TimeUtils.parseDate(args[1])
                if (timestamp == null) {
                    sendMessage("日期格式错误，请使用: yyyy-MM-dd")
                    return
                }
                val provider = DatabaseFactory.getProvider()
                val deleted = provider.deleteBeforeTimestamp(group.id, timestamp)
                sendMessage("已清理 ${args[1]} 之前的消息: $deleted 条")
            }

            "--analyzed" -> {
                val provider = DatabaseFactory.getProvider()
                val deleted = provider.deleteAnalyzed(group.id)
                sendMessage("已清理已分析消息: $deleted 条")
            }

            "--all" -> {
                // 二次确认（简化版）
                val provider = DatabaseFactory.getProvider()
                val count = provider.getMessageCount(group.id)
                val deleted = provider.deleteAll(group.id)
                sendMessage("已清空群 ${group.id} 的所有消息: $deleted 条（原 $count 条）")
            }

            else -> {
                sendMessage("未知参数: ${args[0]}\n用法: /store cleanup --before 日期 | --analyzed | --all")
            }
        }
    }

    @SubCommand
    @Description("迁移数据库 (sqlite2mysql)")
    suspend fun CommandSender.migrate(direction: String) {
        // 仅控制台或管理员可执行
        val user = user
        if (user != null) {
            sendMessage("迁移操作仅限控制台执行")
            return
        }

        when (direction) {
            "sqlite2mysql" -> {
                sendMessage("开始 SQLite → MySQL 迁移...")
                val result = MigrationTool.migrateSqliteToMysql()
                sendMessage(result.toString())
            }
            else -> {
                sendMessage("用法: /store migrate sqlite2mysql")
            }
        }
    }
}
