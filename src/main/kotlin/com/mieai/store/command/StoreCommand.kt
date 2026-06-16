package com.mieai.store.command

import com.mieai.store.MessageStorePlugin
import com.mieai.store.service.MessageStoreService
import com.mieai.store.util.TimeUtils
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.command.getGroupOrNull

/**
 * 消息查询命令
 *
 * /store history [n]        - 查看最近 n 条消息
 * /store search <关键词>    - 搜索群内消息
 * /store search @某人       - 查看某人消息
 * /store search 日期范围    - 按时间范围搜索
 * /store context [n]        - 加载最近 n 条作为上下文
 * /store stats              - 查看群消息统计
 */
object StoreCommand : CompositeCommand(
    owner = MessageStorePlugin,
    primaryName = "store",
    description = "MieAI 消息存储查询命令"
) {

    @SubCommand
    @Description("查看最近 n 条消息（默认 20）")
    suspend fun CommandSender.history(count: Int = 20) {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        val limit = count.coerceIn(1, 100)
        val messages = MessageStoreService.getHistory(group.id, limit)

        if (messages.isEmpty()) {
            sendMessage("暂无历史消息")
            return
        }

        val response = buildString {
            appendLine("=== 最近 ${messages.size} 条消息 ===")
            for (msg in messages) {
                val time = TimeUtils.formatTimestamp(msg.timestamp, "MM-dd HH:mm")
                appendLine("[$time] ${msg.userQq}: ${msg.content.take(100)}")
            }
        }
        sendMessage(response)
    }

    @SubCommand
    @Description("搜索群内消息")
    suspend fun CommandSender.search(vararg args: String) {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        if (args.isEmpty()) {
            sendMessage("用法: /store search <关键词> 或 /store search @某人 或 /store search 日期范围")
            return
        }

        val query = args.joinToString(" ")

        // 判断查询类型
        when {
            // @某人
            query.startsWith("@") -> {
                val qqStr = query.removePrefix("@").trim()
                val qq = qqStr.toLongOrNull()
                if (qq == null) {
                    sendMessage("请输入有效的 QQ 号，如: /store search @123456")
                    return
                }
                val messages = MessageStoreService.searchByUser(group.id, qq)
                if (messages.isEmpty()) {
                    sendMessage("未找到用户 $qq 的消息")
                    return
                }
                val response = buildString {
                    appendLine("=== 用户 $qq 的最近 ${messages.size} 条消息 ===")
                    for (msg in messages) {
                        val time = TimeUtils.formatTimestamp(msg.timestamp, "MM-dd HH:mm")
                        appendLine("[$time] ${msg.content.take(100)}")
                    }
                }
                sendMessage(response)
            }

            // 日期范围 (yyyy-MM-dd~yyyy-MM-dd)
            query.contains("~") -> {
                val range = TimeUtils.parseTimeRange(query)
                if (range == null) {
                    sendMessage("日期格式错误，请使用: yyyy-MM-dd~yyyy-MM-dd")
                    return
                }
                val messages = MessageStoreService.searchByTimeRange(group.id, range.first, range.second)
                if (messages.isEmpty()) {
                    sendMessage("该时间范围内无消息")
                    return
                }
                val response = buildString {
                    appendLine("=== ${query} 范围内的 ${messages.size} 条消息 ===")
                    for (msg in messages) {
                        val time = TimeUtils.formatTimestamp(msg.timestamp, "MM-dd HH:mm")
                        appendLine("[$time] ${msg.userQq}: ${msg.content.take(100)}")
                    }
                }
                sendMessage(response)
            }

            // 关键词搜索
            else -> {
                val messages = MessageStoreService.searchByKeyword(group.id, query)
                if (messages.isEmpty()) {
                    sendMessage("未找到包含「$query」的消息")
                    return
                }
                val response = buildString {
                    appendLine("=== 搜索「$query」找到 ${messages.size} 条 ===")
                    for (msg in messages) {
                        val time = TimeUtils.formatTimestamp(msg.timestamp, "MM-dd HH:mm")
                        appendLine("[$time] ${msg.userQq}: ${msg.content.take(100)}")
                    }
                }
                sendMessage(response)
            }
        }
    }

    @SubCommand
    @Description("加载最近 n 条消息作为上下文")
    suspend fun CommandSender.context(count: Int = 20) {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        val limit = count.coerceIn(1, 100)
        val messages = MessageStoreService.getHistory(group.id, limit)

        if (messages.isEmpty()) {
            sendMessage("暂无历史消息可作为上下文")
            return
        }

        val contextText = buildString {
            appendLine("【最近 ${messages.size} 条群聊上下文】")
            for (msg in messages) {
                appendLine("${msg.userQq}: ${msg.content}")
            }
            appendLine("【上下文结束】")
        }

        sendMessage(contextText)
    }

    @SubCommand
    @Description("查看群消息统计")
    suspend fun CommandSender.stats() {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        val stats = MessageStoreService.getStats(group.id)
        val count = stats["count"] ?: 0
        val dbSize = stats["dbSize"] ?: 0

        val response = buildString {
            appendLine("=== 群 ${group.id} 消息统计 ===")
            appendLine("消息总数: $count")
            if (dbSize is Long && dbSize > 0) {
                val sizeMB = dbSize / 1024.0 / 1024.0
                appendLine("数据库大小: ${"%.2f".format(sizeMB)} MB")
            }
        }
        sendMessage(response)
    }
}
