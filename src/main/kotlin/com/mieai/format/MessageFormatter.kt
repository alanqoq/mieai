package com.mieai.format

/**
 * 群消息格式化工具
 *
 * 按模板将原始消息格式化为结构化输出。
 * 模板占位符：
 *   {name} = 群友名（已做安全截断）
 *   {qq}   = QQ号
 *   {msg}  = 消息内容
 *
 * 示例模板：
 *   [{name}]-[{qq}]：{msg}   → [群友名]-[QQ号]：消息内容
 *   {msg}：{qq}-{name}       → 消息内容：QQ号-群友名
 */
object MessageFormatter {

    /**
     * 按模板格式化群消息
     *
     * @param senderName 群友名称（群名片或昵称）
     * @param qqId 发送者 QQ 号
     * @param content 消息内容
     * @param template 格式化模板，默认为 [{name}]-[{qq}]：{msg}
     * @return 格式化后的字符串
     */
    fun formatGroupMessage(
        senderName: String,
        qqId: Long,
        content: String,
        template: String = "[{name}]-[{qq}]：{msg}"
    ): String {
        val safeName = senderName.take(30).replace("\n", " ").replace("\r", "")
        return template
            .replace("{name}", safeName)
            .replace("{qq}", qqId.toString())
            .replace("{msg}", content)
    }
}