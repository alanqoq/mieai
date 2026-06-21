package com.mieai.format

/**
 * 群消息格式化工具
 *
 * 将原始消息格式化为结构化输出：
 * [群友名]-[QQ号]：[消息]
 */
object MessageFormatter {

    /**
     * 格式化群消息
     *
     * @param senderName 群友名称（群名片或昵称）
     * @param qqId 发送者 QQ 号
     * @param content 消息内容
     * @return 格式化后的字符串
     */
    fun formatGroupMessage(senderName: String, qqId: Long, content: String): String {
        val safeName = senderName.take(30).replace("\n", " ").replace("\r", "")
        return "[$safeName]-[$qqId]：$content"
    }
}
