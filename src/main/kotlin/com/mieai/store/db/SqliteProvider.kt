package com.mieai.store.db

import com.mieai.store.config.ConfigManager
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * SQLite 数据库提供者
 *
 * 特点：
 * - 每群独立建表：messages_{groupId}
 * - WAL 模式，读写并发不阻塞
 * - 自动创建表和索引
 * - 适配 SQLite 语法（AUTOINCREMENT, INTEGER, TEXT）
 */
class SqliteProvider : DatabaseProvider {

    private var connection: Connection? = null
    private val tableCache = mutableSetOf<Long>()  // 已创建表的群号缓存

    override fun initialize() {
        val config = ConfigManager.config.storage.sqlite
        val dbPath = ConfigManager.expandPath(config.path)

        // 确保目录存在
        val dbFile = File(dbPath)
        dbFile.parentFile?.mkdirs()

        // 加载 SQLite JDBC 驱动
        Class.forName("org.sqlite.JDBC")

        // 建立连接
        val url = "jdbc:sqlite:$dbPath"
        connection = DriverManager.getConnection(url).also { conn ->
            // 启用 WAL 模式
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA synchronous=NORMAL")
                stmt.execute("PRAGMA cache_size=-64000")  // 64MB cache
                stmt.execute("PRAGMA temp_store=MEMORY")
                stmt.execute("PRAGMA mmap_size=268435456")  // 256MB mmap
            }
        }

        println("[mieai-store] SQLite 数据库已连接: $dbPath")
    }

    override fun ensureGroupTable(groupId: Long) {
        if (tableCache.contains(groupId)) return

        val conn = connection ?: throw SQLException("数据库未初始化")

        // 创建群消息表（SQLite 语法适配）
        conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages_$groupId (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_id INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    user_qq INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    stored_at TEXT DEFAULT (datetime('now', 'localtime')),
                    analyzed_by_group_ai INTEGER DEFAULT 0,
                    analyzed_by_user_ai INTEGER DEFAULT 0,
                    analyzed_at TEXT,
                    metadata TEXT
                )
            """.trimIndent())

            // 创建索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_${groupId}_timestamp ON messages_$groupId(timestamp)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_${groupId}_user ON messages_$groupId(user_qq)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_${groupId}_analyzed ON messages_$groupId(analyzed_by_group_ai, analyzed_by_user_ai)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_${groupId}_cleanup ON messages_$groupId(analyzed_by_group_ai, analyzed_by_user_ai, timestamp)")
        }

        tableCache.add(groupId)
    }

    override fun close() {
        try {
            connection?.close()
            connection = null
            tableCache.clear()
            println("[mieai-store] SQLite 数据库已关闭")
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 关闭 SQLite 连接失败: ${e.message}")
        }
    }

    override fun isAlive(): Boolean {
        return try {
            connection?.isValid(5) ?: false
        } catch (e: SQLException) {
            false
        }
    }

    override fun saveMessage(groupId: Long, message: MessageEntity): Boolean {
        ensureGroupTable(groupId)
        val conn = connection ?: return false

        return try {
            conn.prepareStatement("""
                INSERT OR IGNORE INTO messages_$groupId 
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
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 保存消息失败: ${e.message}")
            false
        }
    }

    override fun saveMessages(groupId: Long, messages: List<MessageEntity>): Int {
        ensureGroupTable(groupId)
        val conn = connection ?: return 0

        return try {
            conn.autoCommit = false
            var count = 0
            conn.prepareStatement("""
                INSERT OR IGNORE INTO messages_$groupId 
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
                count = results.count { it >= 0 }
            }
            conn.commit()
            count
        } catch (e: SQLException) {
            conn.rollback()
            System.err.println("[mieai-store] 批量保存失败: ${e.message}")
            0
        } finally {
            conn.autoCommit = true
        }
    }

    override fun getHistory(groupId: Long, limit: Int, offset: Int): List<MessageEntity> {
        ensureGroupTable(groupId)
        val conn = connection ?: return emptyList()

        return try {
            conn.prepareStatement("""
                SELECT id, message_id, content, user_qq, timestamp, stored_at,
                       analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at, metadata
                FROM messages_$groupId 
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
                results.reversed()  // 返回正序
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 查询历史失败: ${e.message}")
            emptyList()
        }
    }

    override fun searchByKeyword(groupId: Long, keyword: String, limit: Int): List<MessageEntity> {
        ensureGroupTable(groupId)
        val conn = connection ?: return emptyList()

        return try {
            conn.prepareStatement("""
                SELECT id, message_id, content, user_qq, timestamp, stored_at,
                       analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at, metadata
                FROM messages_$groupId 
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
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 关键词搜索失败: ${e.message}")
            emptyList()
        }
    }

    override fun searchByUser(groupId: Long, userQq: Long, limit: Int): List<MessageEntity> {
        ensureGroupTable(groupId)
        val conn = connection ?: return emptyList()

        return try {
            conn.prepareStatement("""
                SELECT id, message_id, content, user_qq, timestamp, stored_at,
                       analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at, metadata
                FROM messages_$groupId 
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
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 用户搜索失败: ${e.message}")
            emptyList()
        }
    }

    override fun searchByTimeRange(groupId: Long, startTime: Long, endTime: Long, limit: Int): List<MessageEntity> {
        ensureGroupTable(groupId)
        val conn = connection ?: return emptyList()

        return try {
            conn.prepareStatement("""
                SELECT id, message_id, content, user_qq, timestamp, stored_at,
                       analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at, metadata
                FROM messages_$groupId 
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
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 时间范围搜索失败: ${e.message}")
            emptyList()
        }
    }

    override fun getUnanalyzedMessages(groupId: Long, limit: Int): List<MessageEntity> {
        ensureGroupTable(groupId)
        val conn = connection ?: return emptyList()

        return try {
            conn.prepareStatement("""
                SELECT id, message_id, content, user_qq, timestamp, stored_at,
                       analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at, metadata
                FROM messages_$groupId 
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
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 查询未分析消息失败: ${e.message}")
            emptyList()
        }
    }

    override fun markAnalyzed(groupId: Long, messageIds: List<Long>, analyzedBy: String) {
        ensureGroupTable(groupId)
        val conn = connection ?: return

        val column = when (analyzedBy) {
            "group_ai" -> "analyzed_by_group_ai"
            "user_ai" -> "analyzed_by_user_ai"
            else -> return
        }

        try {
            conn.autoCommit = false
            conn.prepareStatement("""
                UPDATE messages_$groupId 
                SET $column = 1, analyzed_at = datetime('now', 'localtime') 
                WHERE id = ?
            """.trimIndent()).use { stmt ->
                for (id in messageIds) {
                    stmt.setLong(1, id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        } catch (e: SQLException) {
            conn.rollback()
            System.err.println("[mieai-store] 标记已分析失败: ${e.message}")
        } finally {
            conn.autoCommit = true
        }
    }

    override fun getMessageCount(groupId: Long): Long {
        ensureGroupTable(groupId)
        val conn = connection ?: return 0

        return try {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM messages_$groupId")
                if (rs.next()) rs.getLong(1) else 0
            }
        } catch (e: SQLException) {
            0
        }
    }

    override fun deleteBeforeTimestamp(groupId: Long, beforeTimestamp: Long): Int {
        ensureGroupTable(groupId)
        val conn = connection ?: return 0

        return try {
            conn.prepareStatement("DELETE FROM messages_$groupId WHERE timestamp < ?").use { stmt ->
                stmt.setLong(1, beforeTimestamp)
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 清理过期消息失败: ${e.message}")
            0
        }
    }

    override fun deleteAnalyzed(groupId: Long): Int {
        ensureGroupTable(groupId)
        val conn = connection ?: return 0

        return try {
            conn.prepareStatement("""
                DELETE FROM messages_$groupId 
                WHERE analyzed_by_group_ai = 1 AND analyzed_by_user_ai = 1
            """.trimIndent()).use { stmt ->
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 清理已分析消息失败: ${e.message}")
            0
        }
    }

    override fun keepLatest(groupId: Long, keepCount: Long): Int {
        ensureGroupTable(groupId)
        val conn = connection ?: return 0

        return try {
            conn.prepareStatement("""
                DELETE FROM messages_$groupId 
                WHERE id NOT IN (
                    SELECT id FROM messages_$groupId 
                    ORDER BY timestamp DESC 
                    LIMIT ?
                )
            """.trimIndent()).use { stmt ->
                stmt.setLong(1, keepCount)
                stmt.executeUpdate()
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 保留最新消息失败: ${e.message}")
            0
        }
    }

    override fun deleteAll(groupId: Long): Int {
        ensureGroupTable(groupId)
        val conn = connection ?: return 0

        return try {
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("DELETE FROM messages_$groupId")
            }
        } catch (e: SQLException) {
            System.err.println("[mieai-store] 清空消息失败: ${e.message}")
            0
        }
    }

    override fun listGroupIds(): List<Long> {
        val conn = connection ?: return emptyList()

        return try {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT name FROM sqlite_master 
                    WHERE type='table' AND name LIKE 'messages_%'
                """.trimIndent())
                val groups = mutableListOf<Long>()
                while (rs.next()) {
                    val name = rs.getString("name")
                    val groupId = name.removePrefix("messages_").toLongOrNull()
                    if (groupId != null) groups.add(groupId)
                }
                groups
            }
        } catch (e: SQLException) {
            emptyList()
        }
    }

    override fun getDatabaseSize(): Long {
        val config = ConfigManager.config.storage.sqlite
        val dbPath = ConfigManager.expandPath(config.path)
        return try {
            File(dbPath).length()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 执行 VACUUM 释放空间
     */
    fun vacuum() {
        val conn = connection ?: return
        try {
            conn.createStatement().use { it.execute("VACUUM") }
            println("[mieai-store] SQLite VACUUM 完成")
        } catch (e: SQLException) {
            System.err.println("[mieai-store] VACUUM 失败: ${e.message}")
        }
    }

    /**
     * 结果集映射
     */
    private fun mapRow(rs: java.sql.ResultSet): MessageEntity {
        return MessageEntity(
            id = rs.getLong("id"),
            messageId = rs.getLong("message_id"),
            content = rs.getString("content"),
            userQq = rs.getLong("user_qq"),
            timestamp = rs.getLong("timestamp"),
            storedAt = rs.getString("stored_at"),
            analyzedByGroupAi = rs.getInt("analyzed_by_group_ai") == 1,
            analyzedByUserAi = rs.getInt("analyzed_by_user_ai") == 1,
            analyzedAt = rs.getString("analyzed_at"),
            metadata = rs.getString("metadata")
        )
    }
}
