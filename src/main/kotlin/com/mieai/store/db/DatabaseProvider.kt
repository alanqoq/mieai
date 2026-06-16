package com.mieai.store.db

/**
 * 消息实体
 */
data class MessageEntity(
    val id: Long = 0,
    val messageId: Long,
    val content: String,
    val userQq: Long,
    val timestamp: Long,
    val storedAt: String? = null,
    val analyzedByGroupAi: Boolean = false,
    val analyzedByUserAi: Boolean = false,
    val analyzedAt: String? = null,
    val metadata: String? = null
)

/**
 * 数据库提供者接口
 * 支持 SQLite 和 MySQL 两种后端
 */
interface DatabaseProvider {

    /**
     * 初始化数据库连接
     */
    fun initialize()

    /**
     * 确保群消息表存在（autoInit）
     * @param groupId 群号
     */
    fun ensureGroupTable(groupId: Long)

    /**
     * 关闭数据库连接
     */
    fun close()

    /**
     * 检查连接是否存活
     */
    fun isAlive(): Boolean

    // ── CRUD 操作 ──

    /**
     * 保存一条消息
     * @param groupId 群号
     * @param message 消息实体
     * @return 是否成功（去重时返回 false）
     */
    fun saveMessage(groupId: Long, message: MessageEntity): Boolean

    /**
     * 批量保存消息
     * @param groupId 群号
     * @param messages 消息列表
     * @return 成功保存的数量
     */
    fun saveMessages(groupId: Long, messages: List<MessageEntity>): Int

    /**
     * 查询历史消息
     * @param groupId 群号
     * @param limit 返回条数
     * @param offset 偏移量
     * @return 消息列表（按时间正序）
     */
    fun getHistory(groupId: Long, limit: Int = 20, offset: Int = 0): List<MessageEntity>

    /**
     * 关键词搜索
     * @param groupId 群号
     * @param keyword 关键词
     * @param limit 返回条数
     * @return 匹配的消息列表
     */
    fun searchByKeyword(groupId: Long, keyword: String, limit: Int = 20): List<MessageEntity>

    /**
     * 按用户搜索
     * @param groupId 群号
     * @param userQq QQ 号
     * @param limit 返回条数
     * @return 该用户的消息列表
     */
    fun searchByUser(groupId: Long, userQq: Long, limit: Int = 20): List<MessageEntity>

    /**
     * 按时间范围搜索
     * @param groupId 群号
     * @param startTime 起始时间戳 (ms)
     * @param endTime 结束时间戳 (ms)
     * @param limit 返回条数
     * @return 范围内的消息列表
     */
    fun searchByTimeRange(groupId: Long, startTime: Long, endTime: Long, limit: Int = 20): List<MessageEntity>

    /**
     * 获取未分析的消息
     * @param groupId 群号
     * @param limit 返回条数
     * @return 未分析的消息列表
     */
    fun getUnanalyzedMessages(groupId: Long, limit: Int = 100): List<MessageEntity>

    /**
     * 标记消息为已分析
     * @param groupId 群号
     * @param messageIds 消息 ID 列表
     * @param analyzedBy 分析者类型 ("group_ai" 或 "user_ai")
     */
    fun markAnalyzed(groupId: Long, messageIds: List<Long>, analyzedBy: String)

    /**
     * 获取群消息总数
     * @param groupId 群号
     * @return 消息总数
     */
    fun getMessageCount(groupId: Long): Long

    /**
     * 清理过期消息
     * @param groupId 群号
     * @param beforeTimestamp 时间戳阈值 (ms)
     * @return 删除的消息数
     */
    fun deleteBeforeTimestamp(groupId: Long, beforeTimestamp: Long): Int

    /**
     * 清理已分析消息
     * @param groupId 群号
     * @return 删除的消息数
     */
    fun deleteAnalyzed(groupId: Long): Int

    /**
     * 保留最新 N 条，删除多余的
     * @param groupId 群号
     * @param keepCount 保留条数
     * @return 删除的消息数
     */
    fun keepLatest(groupId: Long, keepCount: Long): Int

    /**
     * 清空群所有消息
     * @param groupId 群号
     * @return 删除的消息数
     */
    fun deleteAll(groupId: Long): Int

    /**
     * 获取所有群号列表
     * @return 群号列表
     */
    fun listGroupIds(): List<Long>

    /**
     * 获取数据库文件大小 (bytes)
     * 仅 SQLite 适用，MySQL 返回 0
     */
    fun getDatabaseSize(): Long
}
