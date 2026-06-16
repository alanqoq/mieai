package com.mieai.store.parser

import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.MessageSource.Key.quote

/**
 * 消息解析器
 * 从 Mirai 消息中提取纯文本，过滤多媒体元素
 */
object MessageParser {

    /**
     * 解析结果
     */
    data class ParseResult(
        val text: String,           // 纯文本内容
        val metadata: String?       // JSON 元数据
    )

    /**
     * 从 Mirai 消息中提取纯文本内容
     * 过滤图片、文件、语音等多媒体元素
     */
    fun parsePlainText(message: MessageChain): ParseResult {
        val textParts = mutableListOf<String>()
        var replyMessageId: Long? = null
        val atUsers = mutableListOf<Long>()
        val imageIds = mutableListOf<String>()

        for (element in message) {
            when (element) {
                // ✅ 纯文本 → 存入 content
                is PlainText -> {
                    val text = element.content.trim()
                    if (text.isNotBlank()) {
                        textParts.add(text)
                    }
                }

                // ✅ @提及 → 存入 content + metadata
                is At -> {
                    atUsers.add(element.target)
                    textParts.add("@${element.target}")
                }

                // ✅ 引用回复 → metadata 记录引用消息 ID
                is QuoteReply -> {
                    replyMessageId = element.source.ids.firstOrNull()?.toLong()
                }

                // ❌ 图片 → 过滤，metadata 记录 image_id
                is Image -> {
                    imageIds.add(element.imageId)
                }

                // ❌ 闪照 → 过滤
                is FlashImage -> {
                    imageIds.add(element.image.imageId)
                }

                // ❌ 文件消息 → 过滤
                is FileMessage -> {
                    // 不存储文件内容
                }

                // ❌ 表情 → 过滤
                is Face -> {
                    // 不存储表情
                }

                // ❌ 语音 → 过滤
                // is Voice -> { } // Voice 可能不在当前 Mirai 版本中

                // ❌ 富文本 → 过滤
                is RichMessage -> {
                    // 不存储富文本
                }

                // 其他元素忽略
                else -> {}
            }
        }

        val text = textParts.joinToString(" ").trim()

        // 构建 metadata JSON
        val metadata = buildMetadata(replyMessageId, atUsers, imageIds)

        return ParseResult(text, metadata)
    }

    /**
     * 构建元数据 JSON
     */
    private fun buildMetadata(
        replyMessageId: Long?,
        atUsers: List<Long>,
        imageIds: List<String>
    ): String? {
        val parts = mutableListOf<String>()

        if (replyMessageId != null) {
            parts.add("\"replyTo\":$replyMessageId")
        }
        if (atUsers.isNotEmpty()) {
            parts.add("\"atUsers\":[${atUsers.joinToString(",")}]")
        }
        if (imageIds.isNotEmpty()) {
            parts.add("\"imageIds\":[${imageIds.joinToString(",") { "\"$it\"" }}]")
        }

        return if (parts.isEmpty()) null else "{${parts.joinToString(",")}}"
    }

    /**
     * 检查消息是否包含可存储的文字内容
     */
    fun hasTextContent(message: MessageChain): Boolean {
        return message.any { it is PlainText && it.content.trim().isNotBlank() }
    }
}
