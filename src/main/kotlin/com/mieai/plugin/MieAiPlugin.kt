package com.mieai.plugin

import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.command.CommandManager
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.MessageContent
import net.mamoe.mirai.message.code.MiraiCode
import net.mamoe.mirai.Mirai
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import com.mieai.config.MieAiConfig
import com.mieai.service.ChatGptService
import com.mieai.command.MieAiCommand
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object MieAiPlugin : KotlinPlugin(
    JvmPluginDescription(
        id = "com.mieai.mieai",
        name = "mieai",
        version = "1.0.2"
    ) {
        author("超级龙虾")
        info("ChatGPT 群聊机器人插件")
    }
) {

    lateinit var chatPermission: Permission
        private set

    lateinit var adminPermission: Permission
        private set

    // 每个群最近消息的缓存: groupId -> List<Pair<senderName, messageContent>>
    private val recentMessages = ConcurrentHashMap<Long, CopyOnWriteArrayList<Pair<String, String>>>()

    // 图片下载用的共享 HttpClient（不再每次新建，避免连接池/线程池泄漏）
    private val imageHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    override fun onEnable() {
        println("[mieai-debug] >>> MieAiPlugin.onEnable() ENTERED <<<")

        // Step 1: 注册权限
        println("[mieai-debug] Step 1: registering chatPermission...")
        try {
            chatPermission = PermissionService.INSTANCE.register(
                PermissionId("mieai", "chat"),
                "MieAI 聊天权限",
                parentPermission
            )
            println("[mieai-debug] Step 1: chatPermission registered OK")
        } catch (e: Exception) {
            println("[mieai-debug] Step 1 FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            throw e
        }

        println("[mieai-debug] Step 2: registering adminPermission...")
        try {
            adminPermission = PermissionService.INSTANCE.register(
                PermissionId("mieai", "admin"),
                "MieAI 管理权限",
                parentPermission
            )
            println("[mieai-debug] Step 2: adminPermission registered OK")
        } catch (e: Exception) {
            println("[mieai-debug] Step 2 FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            throw e
        }

        // 将指令权限设为 admin 的子权限，这样拥有 admin 权限就自动拥有指令权限
        println("[mieai-debug] Step 3: registering command permission...")
        try {
            PermissionService.INSTANCE.register(
                PermissionId("com.mieai.mieai", "command.mieai"),
                "MieAI 插件指令",
                adminPermission
            )
            println("[mieai-debug] Step 3: command permission registered OK")
        } catch (e: Exception) {
            println("[mieai-debug] Step 3 FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            throw e
        }

        // Step 4: 设置配置文件路径
        println("[mieai-debug] Step 4: resolving config file...")
        try {
            val configFile = resolveConfigFile("mieai.yml")
            println("[mieai-debug] Step 4: configFile = ${configFile.absolutePath}")
            MieAiConfig.setConfigFile(configFile)
            println("[mieai-debug] Step 4: config file set OK")
        } catch (e: Exception) {
            println("[mieai-debug] Step 4 FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            throw e
        }

        // Step 5: 加载配置
        println("[mieai-debug] Step 5: reloading config...")
        try {
            MieAiConfig.reload()
            println("[mieai-debug] Step 5: config reloaded OK")
        } catch (e: Exception) {
            println("[mieai-debug] Step 5 FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            throw e
        }

        // Step 6: 注册指令
        println("[mieai-debug] Step 6: registering command...")
        try {
            CommandManager.INSTANCE.registerCommand(MieAiCommand)
            println("[mieai-debug] Step 6: command registered OK")
        } catch (e: Exception) {
            println("[mieai-debug] Step 6 FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            throw e
        }

        // Step 7: 初始化 ChatGPT 服务
        println("[mieai-debug] Step 7: initializing ChatGptService...")
        try {
            ChatGptService.init()
            println("[mieai-debug] Step 7: ChatGptService initialized OK")
        } catch (e: Exception) {
            println("[mieai-debug] Step 7 FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            throw e
        }

        // 清空消息缓存
        println("[mieai-debug] Step 8: clearing recentMessages...")
        recentMessages.clear()
        println("[mieai-debug] Step 8: recentMessages cleared OK")

        // Step 9: 监听群消息
        println("[mieai-debug] Step 9: subscribing to events...")
        try {
            GlobalEventChannel.subscribeAlways<GroupMessageEvent> { event ->
                try {
                    // 先缓存消息（无论是否触发AI）
                    cacheMessage(event)
                    handleGroupMessage(event)
                } catch (e: Exception) {
                    logger.error("处理群消息异常", e)
                }
            }
            println("[mieai-debug] Step 9: event subscription OK")
        } catch (e: Exception) {
            println("[mieai-debug] Step 9 FAILED: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            throw e
        }

        println("[mieai-debug] >>> MieAiPlugin.onEnable() COMPLETED <<<")
        logger.info("MieAI 插件已加载")
    }

    private fun cacheMessage(event: GroupMessageEvent) {
        val groupId = event.group.id
        val senderName = event.senderName
        val content = event.message.contentToString()
            .replace("[mirai:at:${event.bot.id}]", "")
            .replace("[mirai:at:${event.bot.id} ]", "")
            .trim()

        if (content.isBlank()) return

        val history = recentMessages.getOrPut(groupId) { CopyOnWriteArrayList() }
        history.add(senderName to content)
        // 保留最近 50 条消息作为缓存池
        while (history.size > 50) {
            history.removeAt(0)
        }
    }

    private fun getRecentContextMessages(groupId: Long): String? {
        if (!MieAiConfig.enableContextMessages) return null

        val count = MieAiConfig.contextMessageCount
        if (count <= 0) return null

        val history = recentMessages[groupId] ?: return null
        // 取最近 count 条（不包括当前消息，当前消息已经在最后被添加了）
        val contextMsgs = history.dropLast(1).takeLast(count)
        if (contextMsgs.isEmpty()) return null

        return buildString {
            append("【以下是最新的 ${contextMsgs.size} 条群聊消息，供你参考上下文】\n")
            contextMsgs.forEach { (name, msg) ->
                append("$name: $msg\n")
            }
            append("【以上是群聊历史消息，以下是当前消息】")
        }
    }

    private suspend fun handleGroupMessage(event: GroupMessageEvent) {
        val groupId = event.group.id
        val sender = event.sender
        val message = event.message.contentToString()

        // 检查群是否被禁用
        if (MieAiConfig.isGroupDisabled(groupId)) return

        // 检查是否被 @机器人
        val isAtBot = event.message.any { it is net.mamoe.mirai.message.data.At && it.target == event.bot.id }
        // 检查是否包含触发关键词
        val hasKeyword = MieAiConfig.triggerKeywords.any { message.contains(it) }
        // 是否被明确触发（@或关键词）
        val isExplicitTrigger = isAtBot || hasKeyword

        // 非明确触发时，按聊天概率随机决定是否主动插嘴
        if (!isExplicitTrigger) {
            val probability = MieAiConfig.getGroupProbability(groupId)
            val random = Math.random()
            logger.info("群 $groupId 概率触发检查: probability=$probability, random=$random, globalProbability=${MieAiConfig.globalProbability}")
            if (random > probability) return
        }

        logger.info("收到群 $groupId 消息: $message | isAtBot=$isAtBot, hasKeyword=$hasKeyword")

        // 清理消息（移除@）
        var cleanMessage = message
            .replace("[mirai:at:${event.bot.id}]", "")
            .replace("[mirai:at:${event.bot.id} ]", "")
            .trim()

        // 如果是概率触发且启用了上下文消息，把前几条消息拼接进去
        val contextPrefix = if (!isExplicitTrigger) getRecentContextMessages(groupId) else null
        val finalMessage = if (contextPrefix != null) {
            "$contextPrefix\n\n当前消息: $cleanMessage"
        } else {
            cleanMessage
        }

        // 获取系统提示词
        val systemPrompt = MieAiConfig.getGroupSystemPrompt(groupId)

        // --- 图片识别分支 ---
        // 从消息中提取图片
        val image = event.message.filterIsInstance<Image>().firstOrNull()

        if (image != null && ChatGptService.isImageRecognitionEnabled(MieAiConfig.model)) {
            try {
                // 获取图片 URL：优先用 Overflow WrappedImage 的 getUrl()（不过期）
                val imageUrl: String? = try {
                    val getUrlMethod = image.javaClass.getMethod("getUrl")
                    getUrlMethod.invoke(image) as? String
                } catch (_: Exception) { null } ?: run {
                    // fallback: 通过 Mirai 实例查询（URL 可能很快过期）
                    val miraiInstance = Mirai::class.java.getMethod("getInstance").invoke(null) as net.mamoe.mirai.IMirai
                    miraiInstance.queryImageUrl(event.bot, image)
                }
                if (imageUrl == null) {
                    logger.error("图片识别: 无法获取图片 URL")
                } else {
                    logger.info("图片识别: 获取到图片 URL，等待5秒让CDN准备...")
                    kotlinx.coroutines.delay(5000L)
                    logger.info("图片识别: 开始下载图片 $imageUrl")

                    val imgRequest = Request.Builder()
                        .url(imageUrl)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .build()
                    imageHttpClient.newCall(imgRequest).execute().use { imgResponse ->
                        logger.info("图片识别: HTTP code=${imgResponse.code}, contentLength=${imgResponse.body?.contentLength()}")
                        val imageBytes = imgResponse.body?.bytes()
                        logger.info("图片识别: 下载完成, bytes=${imageBytes?.size ?: 0}")

                        if (imageBytes != null) {
                            val base64Image = Base64.getEncoder().encodeToString(imageBytes)
                            val base64SizeKB = base64Image.toByteArray(Charsets.UTF_8).size / 1024
                            logger.info("图片识别: Base64大小 ${base64SizeKB}KB, 限制 ${MieAiConfig.maxImageSizeKB}KB")

                            if (base64SizeKB <= MieAiConfig.maxImageSizeKB) {
                                val mimeType = guessMimeType(image.imageId)
                                logger.info("图片识别: 调用带图片的 chat 方法, mime=$mimeType")

                                val response = ChatGptService.chat(
                                    groupId, sender.id, finalMessage, systemPrompt,
                                    base64Image, mimeType
                                )
                                if (response != null) {
                                    logger.info("ChatGPT 回复(图片): $response")
                                    event.group.sendMessage(response)
                                } else {
                                    logger.error("ChatGPT 返回 null（图片模式），回退到纯文字")
                                    val fallbackResponse = ChatGptService.chat(groupId, sender.id, finalMessage, systemPrompt)
                                    if (fallbackResponse != null) event.group.sendMessage(fallbackResponse)
                                }
                                return
                            } else {
                                logger.info("图片识别: Base64过大 ($base64SizeKB KB > ${MieAiConfig.maxImageSizeKB} KB)，丢弃图片")
                            }
                        } else {
                            logger.error("图片识别: 下载图片失败")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("图片识别异常，回退到纯文字", e)
            }
        }

        // --- 纯文字分支（原有逻辑） ---
        if (cleanMessage.isBlank()) return

        logger.info("调用 ChatGPT API: $finalMessage")
        val response = ChatGptService.chat(groupId, sender.id, finalMessage, systemPrompt)

        if (response != null) {
            logger.info("ChatGPT 回复: $response")
            event.group.sendMessage(response)
        } else {
            logger.error("ChatGPT 返回 null，可能是 API 调用失败")
        }
    }

    /**
     * 根据 Mirai 图片 ID 猜测 MIME 类型
     */
    private fun guessMimeType(imageId: String): String {
        val lower = imageId.lowercase()
        return when {
            lower.endsWith(".gif") || lower.contains("gif") -> "image/gif"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.contains("jpg") || lower.contains("jpeg") -> "image/jpeg"
            lower.endsWith(".webp") || lower.contains("webp") -> "image/webp"
            else -> "image/png"
        }
    }

    override fun onDisable() {
        // 清理图片下载 HttpClient 资源
        try {
            imageHttpClient.dispatcher.executorService.shutdown()
            imageHttpClient.connectionPool.evictAll()
        } catch (_: Exception) {}
        logger.info("MieAI 插件已卸载")
    }
}
