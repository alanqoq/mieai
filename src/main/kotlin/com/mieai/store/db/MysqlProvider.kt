package com.mieai.store.db

import com.mieai.store.config.ConfigManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException

/**
 * MySQL 数据库提供者
 *
 * 特点：
 * - 每群独立建表：messages_{groupId}
 * - HikariCP 连接池
 * - autoInit：首次连接自动创建数据库和表
 * - BIGINT + DATETIME 原生类型
 */
class MysqlProvider : DatabaseProvider {

    private var dataSource: HikariDataSource? = null
    private val tableCache = mutableSetOf<Long>()

    override fun initialize() {
        val config = ConfigManager.config.storage.mysql

        // 如果 autoInit，先确保数据库存在
        if (config.autoInit) {
            ensureDatabase(config)
        }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = "jdbc:mysql://${config.host}:${config.port}/${config.database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4"
            username = config.username
            password = config.resolvedPassword()
            maximumPoolSize = config.connectionPool.maxSize
            minimumIdle = config.connectionPool.minIdle
            connectionTimeout = config.connectionPool.connectionTimeout
            driverClassName = "com.mysql.cj.jdbc.Driver"
            connectionTestQuery = "SELECT 1"
            // 连接池健康检查
            idleTimeout = 600000      // 空闲连接 10 分钟回收
            maxLifetime = 1800000     // 最大连接生命周期 30 分钟
            leakDetectionThreshold = 60000  // 泄漏检测 60 秒
            // 自动重连
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }

        dataSource = HikariDataSource(hikariConfig)
        println("[mieai-store] MySQL 数据库已连接: ${config.host}:${config.port}/${config.database}")
    }

    /**
     * autoInit: 确保数据库存在
     */
    private fun ensureDatabase(config: com.mieai.store.config.MysqlConfig) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver")
            val url = "jdbc:mysql://${config.host}:${config.port}/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4"
            java.sql.DriverManager.getConnection(url, config.username, config.resolvedPassword()).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        CREATE DATABASE IF NOT EXISTS `${config.database}` 
                        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                    """.trimIndent())
                }
            }
            println("[mieai-store] MySQL 数据库已确保存在: ${config.database}")
        } catch (e: SQLException) {
            System.err.println("[mieai-store] MySQL autoInit 失败: ${e.message}")
            throw e
        }
    }

    private fun getConnection(): Connection {
        return dataSource?.connection ?: throw SQLException("数据库未初始化")
    }

    override fun ensureGroupTable(groupId: Long) {
        if (tableCache.contains(groupId)) return

        val conn = getConnection()
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS `messages_$groupId` (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        message_id BIGINT NOT NULL,
                        content TEXT NOT NULL,
                        user_qq BIGINT NOT NULL,
                        timestamp BIGINT NOT NULL,
                        stored_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        analyzed_by_group_ai BOOLEAN DEFAULT FALSE,
                        analyzed_by_user_ai BOOLEAN DEFAULT FALSE,
                        analyzed_at DATETIME NULL,
                        metadata TEXT,
                        INDEX idx_${groupId}_timestamp (timestamp),
                        INDEX idx_${groupId}_user (user_qq),
                        INDEX idx_${groupId}_analyzed (analyzed_by_group_ai, analyzed_by_user_ai),
                        INDEX idx_${groupId}_cleanup (analyzed_by_group_ai, analyzed_by_user_ai, timestamp)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent())
            }
        }

        tableCache.add(groupId)
    }

    override fun close() {
        try {
            dataSource?.close()
            dataSource = null
            tableCache.clear()
            println("[mieai-store] MySQL 连接池已关闭")
        } catch (e: Exception) {
            System.err.println("[mieai-store] 关闭 MySQL 连接失败: ${e.message}")
        }
    }

    override fun isAlive(): Boolean {
        return try {
            dataSource?.connection?.use { it.isValid(5) } ?: false
        } catch (e: SQLException) {
            false
        }
    }

    override fun saveMessage(groupId: Long, message: MessageEntity): Boolean {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.prepareStatement("""
                    INSERT IGNORE INTO `messages_$groupId` 
                    (message_id, content, user_qq, timestamp, metadata)
                    VALUES (?, ?, ?, ?, ?)
                """.trimIndent()).use { stmt ->
                    stmt.setLong(1, message.messageId)
                    stmt.setString(2, message.content)
                    stmt.setLong(3, message.userQq)
                    stmt.setLong(4, message.timestamp)
                    stmt.setString(5, message.metadata)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 保存消息失败: ${e.message}")
            false
        }
    }

    override fun saveMessages(groupId: Long, messages: List<MessageEntity>): Int {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.autoCommit = false
                var count = 0
                try {
                    c.prepareStatement("""
                        INSERT IGNORE INTO `messages_$groupId` 
                        (message_id, content, user_qq, timestamp, metadata)
                        VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()).use { stmt ->
                        for (msg in messages) {
                            stmt.setLong(1, msg.messageId)
                            stmt.setString(2, msg.content)
                            stmt.setLong(3, msg.userQq)
                            stmt.setLong(4, msg.timestamp)
                            stmt.setString(5, msg.metadata)
                            stmt.addBatch()
                        }
                        val results = stmt.executeBatch()
                        count = results.size
                    }
                    c.commit()
                    count
                } catch (e: SQLException) {
                    c.rollback()
                    throw e
                } finally {
                    c.autoCommit = true
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 批量保存失败: ${e.message}")
            0
        }
    }

    override fun getHistory(groupId: Long, limit: Int, offset: Int): List<MessageEntity> {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.prepareStatement("""
                    SELECT id, message_id, content, user_qq, timestamp, stored_at,
                           analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at, metadata
                    FROM `messages_$groupId` 
                    ORDER BY timestamp DESC 
                    LIMIT ? OFFSET ?
                """.trimIndent()).use { stmt ->
                    stmt.setInt(1, limit)
                    stmt.setInt(2, offset)
                    val rs = stmt.executeQuery()
                    val results = mutableListOf<MessageEntity>()
                    while (rs.next()) {
                        results.add(mapRow(rs))
                    }
                    results.reversed()
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 查询历史失败: ${e.message}")
            emptyList()
        }
    }

    override fun searchByKeyword(groupId: Long, keyword: String, limit: Int): List<MessageEntity> {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.prepareStatement("""
                    SELECT id, message_id, content, user_qq, timestamp, stored_at,
                           analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at, metadata
                    FROM `messages_$groupId` 
                    WHERE content LIKE ? 
                    ORDER BY timestamp DESC 
                    LIMIT ?
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, "%$keyword%")
                    stmt.setInt(2, limit)
                    val rs = stmt.executeQuery()
                    val results = mutableListOf<MessageEntity>()
                    while (rs.next()) {
                        results.add(mapRow(rs))
                    }
                    results
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 关键词搜索失败: ${e.message}")
            emptyList()
        }
    }

    override fun searchByUser(groupId: Long, userQq: Long, limit: Int): List<MessageEntity> {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.prepareStatement("""
                    SELECT id, message_id, content, user_qq, timestamp, stored_at,
                           analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at, metadata
                    FROM `messages_$groupId` 
                    WHERE user_qq = ? 
                    ORDER BY timestamp DESC 
                    LIMIT ?
                """.trimIndent()).use { stmt ->
                    stmt.setLong(1, userQq)
                    stmt.setInt(2, limit)
                    val rs = stmt.executeQuery()
                    val results = mutableListOf<MessageEntity>()
                    while (rs.next()) {
                        results.add(mapRow(rs))
                    }
                    results
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 用户搜索失败: ${e.message}")
            emptyList()
        }
    }

    override fun searchByTimeRange(groupId: Long, startTime: Long, endTime: Long, limit: Int): List<MessageEntity> {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.prepareStatement("""
                    SELECT id, message_id, content, user_qq, timestamp, stored_at,
                           analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at, metadata
                    FROM `messages_$groupId` 
                    WHERE timestamp >= ? AND timestamp <= ? 
                    ORDER BY timestamp DESC 
                    LIMIT ?
                """.trimIndent()).use { stmt ->
                    stmt.setLong(1, startTime)
                    stmt.setLong(2, endTime)
                    stmt.setInt(3, limit)
                    val rs = stmt.executeQuery()
                    val results = mutableListOf<MessageEntity>()
                    while (rs.next()) {
                        results.add(mapRow(rs))
                    }
                    results
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 时间范围搜索失败: ${e.message}")
            emptyList()
        }
    }

    override fun getUnanalyzedMessages(groupId: Long, limit: Int): List<MessageEntity> {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.prepareStatement("""
                    SELECT id, message_id, content, user_qq, timestamp, stored_at,
                           analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at, metadata
                    FROM `messages_$groupId` 
                    WHERE analyzed_by_group_ai = 0 OR analyzed_by_user_ai = 0 
                    ORDER BY timestamp ASC 
                    LIMIT ?
                """.trimIndent()).use { stmt ->
                    stmt.setInt(1, limit)
                    val rs = stmt.executeQuery()
                    val results = mutableListOf<MessageEntity>()
                    while (rs.next()) {
                        results.add(mapRow(rs))
                    }
                    results
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 查询未分析消息失败: ${e.message}")
            emptyList()
        }
    }

    override fun markAnalyzed(groupId: Long, messageIds: List<Long>, analyzedBy: String) {
        ensureGroupTable(groupId)
        val conn = getConnection()

        val column = when (analyzedBy) {
            "group_ai" -> "analyzed_by_group_ai"
            "user_ai" -> "analyzed_by_user_ai"
            else -> return
        }

        try {
            conn.use { c ->
                c.autoCommit = false
                try {
                    c.prepareStatement("""
                        UPDATE `messages_$groupId` 
                        SET $column = TRUE, analyzed_at = NOW() 
                        WHERE id = ?
                    """.trimIndent()).use { stmt ->
                        for (id in messageIds) {
                            stmt.setLong(1, id)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    c.commit()
                } catch (e: SQLException) {
                    c.rollback()
                    throw e
                } finally {
                    c.autoCommit = true
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 标记已分析失败: ${e.message}")
        }
    }

    override fun getMessageCount(groupId: Long): Long {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT COUNT(*) FROM `messages_$groupId`")
                    if (rs.next()) rs.getLong(1) else 0
                }
            }
        } catch (e: SQLException) {
            0
        }
    }

    override fun deleteBeforeTimestamp(groupId: Long, beforeTimestamp: Long): Int {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.prepareStatement("DELETE FROM `messages_$groupId` WHERE timestamp < ?").use { stmt ->
                    stmt.setLong(1, beforeTimestamp)
                    stmt.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 清理过期消息失败: ${e.message}")
            0
        }
    }

    override fun deleteAnalyzed(groupId: Long): Int {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.prepareStatement("""
                    DELETE FROM `messages_$groupId` 
                    WHERE analyzed_by_group_ai = TRUE AND analyzed_by_user_ai = TRUE
                """.trimIndent()).use { stmt ->
                    stmt.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 清理已分析消息失败: ${e.message}")
            0
        }
    }

    override fun keepLatest(groupId: Long, keepCount: Long): Int {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.prepareStatement("""
                    DELETE FROM `messages_$groupId` 
                    WHERE id NOT IN (
                        SELECT id FROM (
                            SELECT id FROM `messages_$groupId` 
                            ORDER BY timestamp DESC 
                            LIMIT ?
                        ) AS tmp
                    )
                """.trimIndent()).use { stmt ->
                    stmt.setLong(1, keepCount)
                    stmt.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 保留最新消息失败: ${e.message}")
            0
        }
    }

    override fun deleteAll(groupId: Long): Int {
        ensureGroupTable(groupId)
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM `messages_$groupId`")
                }
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 清空消息失败: ${e.message}")
            0
        }
    }

    override fun listGroupIds(): List<Long> {
        val conn = getConnection()

        return try {
            conn.use { c ->
                c.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("""
                        SELECT table_name FROM information_schema.tables 
                        WHERE table_schema = '${ConfigManager.config.storage.mysql.database}' 
                        AND table_name LIKE 'messages_%'
                    """.trimIndent())
                    val groups = mutableListOf<Long>()
                    while (rs.next()) {
                        val name = rs.getString("table_name")
                        val groupId = name.removePrefix("messages_").toLongOrNull()
                        if (groupId != null) groups.add(groupId)
                    }
                    groups
                }
            }
        } catch (e: SQLException) {
            emptyList()
        }
    }

    override fun getDatabaseSize(): Long {
        val conn = getConnection()
        val dbName = ConfigManager.config.storage.mysql.database

        return try {
            conn.use { c ->
                c.prepareStatement("""
                    SELECT SUM(data_length + index_length) 
                    FROM information_schema.tables 
                    WHERE table_schema = ?
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, dbName)
                    val rs = stmt.executeQuery()
                    if (rs.next()) rs.getLong(1) else 0
                }
            }
        } catch (e: SQLException) {
            0
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): MessageEntity {
        return MessageEntity(
            id = rs.getLong("id"),
            messageId = rs.getLong("message_id"),
            content = rs.getString("content"),
            userQq = rs.getLong("user_qq"),
            timestamp = rs.getLong("timestamp"),
            storedAt = rs.getString("stored_at"),
            analyzedByGroupAi = rs.getBoolean("analyzed_by_group_ai"),
            analyzedByUserAi = rs.getBoolean("analyzed_by_user_ai"),
            analyzedAt = rs.getString("analyzed_at"),
            metadata = rs.getString("metadata")
        )
    }
}
