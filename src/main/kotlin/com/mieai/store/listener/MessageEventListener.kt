package com.mieai.store.listener

import com.mieai.store.parser.MessageParser
import com.mieai.store.service.MessageStoreService
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageRecallEvent

/**
 * Mirai 事件监听器
 * 负责捕获群消息事件，解析并存储
 */
object MessageEventListener {

    /**
     * 注册事件监听
     * 在插件 onEnable 时调用
     */
    fun register() {
        // 监听群消息
        GlobalEventChannel.subscribeAlways<GroupMessageEvent> { event ->
            try {
                handleGroupMessage(event)
            } catch (e: Exception) {
                System.err.println("[mieai-store] 处理群消息异常: ${e.message}")
            }
        }

        // 监听消息撤回（可选：标记撤回）
        GlobalEventChannel.subscribeAlways<MessageRecallEvent.GroupRecall> { event ->
            try {
                handleRecall(event)
            } catch (e: Exception) {
                System.err.println("[mieai-store] 处理撤回异常: ${e.message}")
            }
        }

        println("[mieai-store] 事件监听器已注册")
    }

    /**
     * 处理群消息
     */
    private suspend fun handleGroupMessage(event: GroupMessageEvent) {
        val message = event.message

        // 解析消息，过滤多媒体
        val result = MessageParser.parsePlainText(message)

        // 仅存储有文字内容的消息
        if (result.text.isBlank()) return

        // 异步存储
        val messageId = try {
            event.message[net.mamoe.mirai.message.data.MessageSource]?.ids?.firstOrNull()?.toLong() ?: 0L
        } catch (_: Exception) { 0L }

        MessageStoreService.saveMessage(
            groupId = event.group.id,
            messageId = messageId,
            content = result.text,
            userQq = event.sender.id,
            timestamp = event.time.toLong() * 1000,  // Mirai time 是秒级
            metadata = result.metadata
        )
    }

    /**
     * 处理消息撤回
     * TODO: 可在 metadata 中标记撤回状态
     */
    private fun handleRecall(event: MessageRecallEvent.GroupRecall) {
        // 暂不处理，后续可扩展
        // println("[mieai-store] 消息撤回: group=${event.group.id}, messageId=${event.messageId}")
    }
}
