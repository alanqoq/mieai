package com.mieai.service

import com.mieai.config.MieAiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

object ChatGptService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 群对话历史: groupId -> List<Message>
    private val conversationHistory = ConcurrentHashMap<Long, CopyOnWriteArrayList<JSONObject>>()

    fun init() {
        // 初始化时清空历史
        conversationHistory.clear()
    }

    // 图片识别模型白名单
    private val IMAGE_RECOGNITION_MODELS = setOf("mimo-v2.5")

    fun isImageRecognitionEnabled(model: String): Boolean {
        return MieAiConfig.enableImageRecognition && IMAGE_RECOGNITION_MODELS.contains(model)
    }

    suspend fun chat(
        groupId: Long,
        userId: Long,
        userMessage: String,
        systemPrompt: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()

            // 添加系统提示词
            val systemMsg = JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            }
            messages.put(systemMsg)

            // 添加历史对话
            if (MieAiConfig.enableContext) {
                val history = conversationHistory.getOrPut(groupId) { CopyOnWriteArrayList() }
                history.takeLast(MieAiConfig.historySize).forEach { messages.put(it) }
            }

            // 添加用户消息
            val userMsg = JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            }
            messages.put(userMsg)

            // 构建请求体
            val requestBody = JSONObject().apply {
                put("model", MieAiConfig.model)
                put("messages", messages)
                put("max_tokens", MieAiConfig.maxTokens)
                put("temperature", MieAiConfig.temperature)
            }

            val request = Request.Builder()
                .url(MieAiConfig.apiUrl)
                .addHeader("Authorization", "Bearer ${MieAiConfig.resolvedApiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                System.err.println("[mieai] API 请求失败: code=${response.code}, body=$responseBody")
                return@withContext null
            }

            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() == 0) return@withContext null

            val assistantMessage = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            // 保存对话历史
            if (MieAiConfig.enableContext) {
                val history = conversationHistory.getOrPut(groupId) { CopyOnWriteArrayList() }
                history.add(userMsg)
                history.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", assistantMessage)
                })
                // 限制历史大小
                while (history.size > MieAiConfig.historySize * 2) {
                    history.removeAt(0)
                }
            }

            return@withContext assistantMessage
        } catch (e: Exception) {
            System.err.println("[mieai] 文字 API 调用异常: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace(System.err)
            return@withContext null
        }
    }

    /**
     * 带图片的 chat 方法重载
     * @param imageBase64 图片的 Base64 编码（不含 data: 前缀）
     * @param mimeType 图片 MIME 类型，如 "image/png"
     */
    suspend fun chat(
        groupId: Long,
        userId: Long,
        userMessage: String,
        systemPrompt: String,
        imageBase64: String,
        mimeType: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()

            // 添加系统提示词
            val systemMsg = JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            }
            messages.put(systemMsg)

            // 添加历史对话
            if (MieAiConfig.enableContext) {
                val history = conversationHistory.getOrPut(groupId) { CopyOnWriteArrayList() }
                history.takeLast(MieAiConfig.historySize).forEach { messages.put(it) }
            }

            // 构建带图片的 user content 数组
            val imageUrlObj = JSONObject().apply {
                put("url", "data:$mimeType;base64,$imageBase64")
            }
            val imageContentItem = JSONObject().apply {
                put("type", "image_url")
                put("image_url", imageUrlObj)
            }
            val textContentItem = JSONObject().apply {
                put("type", "text")
                put("text", userMessage)
            }
            val contentArray = JSONArray().apply {
                put(imageContentItem)
                put(textContentItem)
            }
            val userMsg = JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            }
            messages.put(userMsg)

            // 构建请求体
            val requestBody = JSONObject().apply {
                put("model", MieAiConfig.model)
                put("messages", messages)
                put("max_tokens", MieAiConfig.maxTokens)
                put("temperature", MieAiConfig.temperature)
            }

            val request = Request.Builder()
                .url(MieAiConfig.apiUrl)
                .addHeader("Authorization", "Bearer ${MieAiConfig.resolvedApiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                System.err.println("[mieai] 图片识别 API 请求失败: code=${response.code}, body=$responseBody")
                return@withContext null
            }

            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() == 0) {
                System.err.println("[mieai] 图片识别 API 返回空 choices, responseBody=$responseBody")
                return@withContext null
            }

            val assistantMessage = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            // 保存对话历史（保存纯文本版本，避免历史中图片过大）
            if (MieAiConfig.enableContext) {
                val history = conversationHistory.getOrPut(groupId) { CopyOnWriteArrayList() }
                val historyUserMsg = JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                }
                history.add(historyUserMsg)
                history.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", assistantMessage)
                })
                while (history.size > MieAiConfig.historySize * 2) {
                    history.removeAt(0)
                }
            }

            return@withContext assistantMessage
        } catch (e: Exception) {
            System.err.println("[mieai] 图片识别 API 调用异常: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace(System.err)
            return@withContext null
        }
    }

    fun clearHistory(groupId: Long) {
        conversationHistory.remove(groupId)
    }
}
