package com.mieai.store.util

import com.mieai.store.config.ConfigManager
import com.mieai.store.db.DatabaseFactory
import com.mieai.store.db.MessageEntity
import com.mieai.store.db.MysqlProvider
import com.mieai.store.db.SqliteProvider
import java.sql.DriverManager

/**
 * 数据库迁移工具
 * 支持 SQLite → MySQL 数据迁移
 */
object MigrationTool {

    /**
     * 迁移结果
     */
    data class MigrationResult(
        val totalGroups: Int,
        val migratedGroups: Int,
        val totalMessages: Long,
        val migratedMessages: Long,
        val errors: List<String>
    ) {
        val success: Boolean get() = errors.isEmpty()
        override fun toString(): String = buildString {
            appendLine("=== 迁移结果 ===")
            appendLine("群组: $migratedGroups/$totalGroups 成功")
            appendLine("消息: $migratedMessages/$totalMessages 成功")
            if (errors.isNotEmpty()) {
                appendLine("错误 (${errors.size}):")
                errors.forEach { appendLine("  - $it") }
            } else {
                appendLine("状态: 全部成功 ✅")
            }
        }
    }

    /**
     * 从当前 SQLite 迁移到 MySQL
     * 前提：当前配置为 sqlite，目标 MySQL 配置已就绪
     */
    fun migrateSqliteToMysql(): MigrationResult {
        val config = ConfigManager.config
        if (config.storage.type != "sqlite") {
            return MigrationResult(0, 0, 0, 0, listOf("当前存储类型不是 SQLite"))
        }

        println("[mieai-migration] 开始 SQLite → MySQL 连接...")

        // 建立 SQLite 连接
        val sqlitePath = ConfigManager.expandPath(config.storage.sqlite.path)
        Class.forName("org.sqlite.JDBC")
        val sqliteConn = DriverManager.getConnection("jdbc:sqlite:$sqlitePath")

        // 建立 MySQL 连接
        val mysqlConfig = config.storage.mysql
        Class.forName("com.mysql.cj.jdbc.Driver")
        val mysqlUrl = "jdbc:mysql://${mysqlConfig.host}:${mysqlConfig.port}/${mysqlConfig.database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4"
        val mysqlConn = DriverManager.getConnection(mysqlUrl, mysqlConfig.username, mysqlConfig.password)

        var totalGroups = 0
        var migratedGroups = 0
        var totalMessages = 0L
        var migratedMessages = 0L
        val errors = mutableListOf<String>()

        try {
            // 获取所有群表
            val groups = mutableListOf<Long>()
            sqliteConn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'messages_%'")
                while (rs.next()) {
                    val name = rs.getString("name")
                    val groupId = name.removePrefix("messages_").toLongOrNull()
                    if (groupId != null) groups.add(groupId)
                }
            }
            totalGroups = groups.size
            println("[mieai-migration] 发现 $totalGroups 个群表")

            for (groupId in groups) {
                try {
                    val count = migrateGroup(sqliteConn, mysqlConn, groupId)
                    totalMessages += count
                    migratedMessages += count
                    migratedGroups++
                    println("[mieai-migration] 群 $groupId: 迁移 $count 条消息 ✓")
                } catch (e: Exception) {
                    errors.add("群 $groupId: ${e.message}")
                    System.err.println("[mieai-migration] 群 $groupId 迁移失败: ${e.message}")
                }
            }
        } finally {
            sqliteConn.close()
            mysqlConn.close()
        }

        return MigrationResult(totalGroups, migratedGroups, totalMessages, migratedMessages, errors)
    }

    /**
     * 迁移单个群的消息
     */
    private fun migrateGroup(sqliteConn: java.sql.Connection, mysqlConn: java.sql.Connection, groupId: Long): Long {
        // 从 SQLite 读取
        val messages = mutableListOf<MessageEntity>()
        sqliteConn.prepareStatement("""
            SELECT message_id, content, user_qq, timestamp, metadata,
                   analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at
            FROM messages_$groupId ORDER BY timestamp ASC
        """.trimIndent()).use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                messages.add(MessageEntity(
                    messageId = rs.getLong("message_id"),
                    content = rs.getString("content"),
                    userQq = rs.getLong("user_qq"),
                    timestamp = rs.getLong("timestamp"),
                    analyzedByGroupAi = rs.getInt("analyzed_by_group_ai") == 1,
                    analyzedByUserAi = rs.getInt("analyzed_by_user_ai") == 1,
                    analyzedAt = rs.getString("analyzed_at"),
                    metadata = rs.getString("metadata")
                ))
            }
        }

        if (messages.isEmpty()) return 0

        // 确保 MySQL 表存在
        mysqlConn.createStatement().use { stmt ->
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

        // 写入 MySQL（批量）
        mysqlConn.autoCommit = false
        try {
            mysqlConn.prepareStatement("""
                INSERT IGNORE INTO `messages_$groupId` 
                (message_id, content, user_qq, timestamp, metadata, analyzed_by_group_ai, analyzed_by_user_ai, analyzed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                for (msg in messages) {
                    stmt.setLong(1, msg.messageId)
                    stmt.setString(2, msg.content)
                    stmt.setLong(3, msg.userQq)
                    stmt.setLong(4, msg.timestamp)
                    stmt.setString(5, msg.metadata)
                    stmt.setBoolean(6, msg.analyzedByGroupAi)
                    stmt.setBoolean(7, msg.analyzedByUserAi)
                    stmt.setString(8, msg.analyzedAt)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            mysqlConn.commit()
        } catch (e: Exception) {
            mysqlConn.rollback()
            throw e
        } finally {
            mysqlConn.autoCommit = true
        }

        return messages.size.toLong()
    }
}
