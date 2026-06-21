package com.mieai.config

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * MieAI 配置管理器
 *
 * 纯手动 YAML 管理，不再使用 AutoSavePluginConfig，
 * 避免两套持久化机制冲突导致配置丢失。
 */
object MieAiConfig {

    // 配置文件路径（由 MieAiPlugin.onEnable 设置）
    private var configFile: File? = null

    fun setConfigFile(file: File) {
        configFile = file
    }

    // ── 配置字段 ──

    var apiUrl: String = "https://api.openai.com/v1/chat/completions"

    var apiKey: String = ""

    /**
     * 获取 API Key：优先从环境变量 MIEAI_API_KEY 读取，
     * 为空则回退到配置文件中的值
     */
    val resolvedApiKey: String
        get() = System.getenv("MIEAI_API_KEY")?.takeIf { it.isNotBlank() } ?: apiKey

    var model: String = "gpt-3.5-turbo"

    var maxTokens: Int = 4096

    var temperature: Double = 0.7

    var globalProbability: Double = 1.0

    var groupProbabilities: MutableMap<Long, Double> = mutableMapOf()

    var defaultSystemPrompt: String = "你是一个友好的AI助手，正在群聊中与大家交流。请用简洁友好的方式回答问题。"

    var groupSystemPrompts: MutableMap<Long, String> = mutableMapOf()

    var disabledGroups: MutableSet<Long> = mutableSetOf()

    var triggerKeywords: MutableList<String> = mutableListOf("ai", "AI", "机器人")

    var historySize: Int = 10

    var enableContext: Boolean = true

    var enableContextMessages: Boolean = true

    var contextMessageCount: Int = 5

    var enableImageRecognition: Boolean = false

    var maxImageSizeKB: Int = 2048

    /**
     * 是否在发给 OpenAI 的消息中添加群友标识
     * 开启后发送给 AI 的消息按 messageFormatTemplate 格式化
     */
    var enableMessageFormat: Boolean = false

    /**
     * 消息格式化模板（enableMessageFormat 开启时生效）
     * 占位符：{name}=群友名, {qq}=QQ号, {msg}=消息内容
     * 默认：[{name}]-[{qq}]：{msg}
     * 示例：调整为 {msg}：{qq}-{name} → 消息内容：QQ号-群友名
     */
    var messageFormatTemplate: String = "[{name}]-[{qq}]：{msg}"

    // ── 加载/保存 ──

    /**
     * 从 YAML 文件加载配置
     */
    fun reload() {
        val file = configFile ?: return
        if (!file.exists()) {
            save()  // 文件不存在时写入默认值
            return
        }
        try {
            val yaml = Yaml()
            @Suppress("UNCHECKED_CAST")
            val map = yaml.load<Map<String, Any?>>(file.readText()) ?: return

            map["apiUrl"]?.toString()?.let { apiUrl = it }
            map["apiKey"]?.toString()?.let { apiKey = it }
            map["model"]?.toString()?.let { model = it }
            map["maxTokens"]?.toString()?.toIntOrNull()?.let { maxTokens = it }
            map["temperature"]?.toString()?.toDoubleOrNull()?.let { temperature = it }
            map["globalProbability"]?.toString()?.toDoubleOrNull()?.let { globalProbability = it }
            map["defaultSystemPrompt"]?.toString()?.let { defaultSystemPrompt = it }
            map["historySize"]?.toString()?.toIntOrNull()?.let { historySize = it }
            map["enableContext"]?.toString()?.toBooleanStrictOrNull()?.let { enableContext = it }
            map["enableContextMessages"]?.toString()?.toBooleanStrictOrNull()?.let { enableContextMessages = it }
            map["contextMessageCount"]?.toString()?.toIntOrNull()?.let { contextMessageCount = it }
            map["enableImageRecognition"]?.toString()?.toBooleanStrictOrNull()?.let { enableImageRecognition = it }
            map["maxImageSizeKB"]?.toString()?.toIntOrNull()?.let { maxImageSizeKB = it }
            map["enableMessageFormat"]?.toString()?.toBooleanStrictOrNull()?.let { enableMessageFormat = it }
            map["messageFormatTemplate"]?.toString()?.let { messageFormatTemplate = it }

            // Map 类型
            @Suppress("UNCHECKED_CAST")
            (map["groupProbabilities"] as? Map<String, Any?>)?.let { m ->
                groupProbabilities = mutableMapOf()
                m.forEach { (k, v) ->
                    k.toLongOrNull()?.let { groupId ->
                        v?.toString()?.toDoubleOrNull()?.let { groupProbabilities[groupId] = it }
                    }
                }
            }
            @Suppress("UNCHECKED_CAST")
            (map["groupSystemPrompts"] as? Map<String, Any?>)?.let { m ->
                groupSystemPrompts = mutableMapOf()
                m.forEach { (k, v) ->
                    k.toLongOrNull()?.let { groupId ->
                        v?.toString()?.let { groupSystemPrompts[groupId] = it }
                    }
                }
            }

            // Set 类型
            @Suppress("UNCHECKED_CAST")
            (map["disabledGroups"] as? List<Any?>)?.let { list ->
                disabledGroups = mutableSetOf()
                list.forEach { it?.toString()?.toLongOrNull()?.let { id -> disabledGroups.add(id) } }
            }

            // List 类型
            @Suppress("UNCHECKED_CAST")
            (map["triggerKeywords"] as? List<Any?>)?.let { list ->
                triggerKeywords = mutableListOf()
                list.forEach { it?.toString()?.let { s -> triggerKeywords.add(s) } }
            }
        } catch (e: Exception) {
            System.err.println("[mieai] 配置文件解析失败，使用默认值: ${e.message}")
        }
    }

    /**
     * 保存当前配置到 YAML 文件
     */
    fun save() {
        val file = configFile ?: return
        file.parentFile?.mkdirs()
        val content = buildString {
            append("# MieAI 插件配置\n")
            append("# API Key 建议通过环境变量 MIEAI_API_KEY 设置\n\n")
            append("# ChatGPT API 地址\n")
            append("apiUrl: \"$apiUrl\"\n\n")
            append("# ChatGPT API Key (留空则从环境变量读取)\n")
            val masked = if (apiKey.length > 8) "${apiKey.take(4)}****${apiKey.takeLast(4)}" else "****"
            append("apiKey: \"$masked\"\n\n")
            append("# 使用的模型\n")
            append("model: \"$model\"\n\n")
            append("# 最大 token 数\n")
            append("maxTokens: $maxTokens\n\n")
            append("# 温度参数\n")
            append("temperature: $temperature\n\n")
            append("# 全局聊天概率 (0.0-1.0)\n")
            append("globalProbability: $globalProbability\n\n")
            append("# 每个群的聊天概率设置 (群号 -> 概率)\n")
            append("groupProbabilities:\n")
            groupProbabilities.forEach { (k, v) -> append("  $k: $v\n") }
            append("\n# 全局默认系统提示词\n")
            append("defaultSystemPrompt: >-\n  ${defaultSystemPrompt.replace("\n", "\n  ")}\n\n")
            append("# 每个群的系统提示词 (群号 -> 提示词)\n")
            append("groupSystemPrompts:\n")
            groupSystemPrompts.forEach { (k, v) -> append("  $k: >-\n    ${v.replace("\n", "\n    ")}\n") }
            append("\n# 禁用聊天功能的群号列表\n")
            append("disabledGroups:\n")
            disabledGroups.forEach { append("  - $it\n") }
            append("\n# 触发关键词列表（包含任一关键词触发）\n")
            append("triggerKeywords:\n")
            triggerKeywords.forEach { append("  - \"$it\"\n") }
            append("\n# 对话历史保留条数\n")
            append("historySize: $historySize\n\n")
            append("# 是否启用上下文对话\n")
            append("enableContext: $enableContext\n\n")
            append("# 是否启用概率触发时携带前几条群消息\n")
            append("enableContextMessages: $enableContextMessages\n\n")
            append("# 概率触发时携带前几条群消息的数量\n")
            append("contextMessageCount: $contextMessageCount\n\n")
            append("# 是否启用图片识别\n")
            append("enableImageRecognition: $enableImageRecognition\n\n")
            append("# 图片识别最大图片大小 (KB)\n")
            append("maxImageSizeKB: $maxImageSizeKB\n\n")
            append("# 是否启用群消息格式化输出\n")
            append("# 开启后群聊消息会被格式化为: [群友名]-[QQ号]：[消息]\n")
            append("enableMessageFormat: $enableMessageFormat\n")
            append("messageFormatTemplate: $messageFormatTemplate\n")
        }
        file.writeText(content)
    }

    // ── 业务方法 ──

    fun getGroupProbability(groupId: Long): Double {
        return groupProbabilities[groupId] ?: globalProbability
    }

    fun getGroupSystemPrompt(groupId: Long): String {
        return groupSystemPrompts[groupId] ?: defaultSystemPrompt
    }

    fun isGroupDisabled(groupId: Long): Boolean {
        return disabledGroups.contains(groupId)
    }

    fun disableGroup(groupId: Long) {
        disabledGroups.add(groupId)
        save()
    }

    fun enableGroup(groupId: Long) {
        disabledGroups.remove(groupId)
        save()
    }

    fun setGroupProbability(groupId: Long, probability: Double) {
        groupProbabilities[groupId] = probability.coerceIn(0.0, 1.0)
        save()
    }

    fun setGroupSystemPrompt(groupId: Long, prompt: String) {
        groupSystemPrompts[groupId] = prompt
        save()
    }

    fun updateDefaultSystemPrompt(prompt: String) {
        defaultSystemPrompt = prompt
        save()
    }
}
