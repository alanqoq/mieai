package com.mieai.store.config

import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 存储配置
 */
data class StorageConfig(
    val storage: StorageBackend = StorageBackend(),
    val messageLimits: MessageLimits = MessageLimits()
)

data class StorageBackend(
    val enabled: Boolean = true,
    val type: String = "sqlite",
    val sqlite: SqliteConfig = SqliteConfig(),
    val mysql: MysqlConfig = MysqlConfig()
)

data class SqliteConfig(
    val path: String = ""  // 空字符串表示使用插件数据目录
)

data class MysqlConfig(
    val host: String = "localhost",
    val port: Int = 3306,
    val database: String = "mieai_messages",
    val username: String = "root",
    val password: String = "",
    val autoInit: Boolean = true,
    val connectionPool: ConnectionPoolConfig = ConnectionPoolConfig()
) {
    /**
     * 获取密码：优先从环境变量 MIEAI_MYSQL_PASSWORD 读取，
     * 为空则回退到配置文件中的值
     */
    fun resolvedPassword(): String {
        return System.getenv("MIEAI_MYSQL_PASSWORD")?.takeIf { it.isNotBlank() } ?: password
    }
}

data class ConnectionPoolConfig(
    val maxSize: Int = 10,
    val minIdle: Int = 2,
    val connectionTimeout: Long = 30000
)

data class MessageLimits(
    val maxPerGroup: Long = 100000,
    val retentionDays: Int = 0,
    val cleanupIntervalHours: Int = 24,
    val minCleanupCount: Int = 1000
)

/**
 * 配置管理器
 */
object ConfigManager {

    private var _config: StorageConfig = StorageConfig()
    val config: StorageConfig get() = _config
    
    // 由 MessageStorePlugin 设置的路径
    private var configFile: File? = null
    private var dataDir: File? = null

    fun setPaths(configFile: File, dataDir: File) {
        this.configFile = configFile
        this.dataDir = dataDir
    }

    fun getConfigFile(): File {
        return configFile ?: throw IllegalStateException("ConfigManager 未初始化，请先调用 setPaths()")
    }
    
    fun getDataDir(): File {
        return dataDir ?: throw IllegalStateException("ConfigManager 未初始化，请先调用 setPaths()")
    }

    fun load(): StorageConfig {
        val file = getConfigFile()
        _config = if (file.exists()) {
            try {
                parseConfig(file.readText())
            } catch (e: Exception) {
                System.err.println("[mieai-store] 配置文件解析失败，使用默认配置: ${e.message}")
                StorageConfig()
            }
        } else {
            val default = StorageConfig()
            save(default)
            default
        }
        return _config
    }

    fun save(config: StorageConfig) {
        val file = getConfigFile()
        file.parentFile?.mkdirs()
        file.writeText(toYaml(config))
        _config = config
    }

    fun reload(): StorageConfig = load()

    /**
     * 展开路径
     * - 如果以 ~ 开头，使用插件数据目录
     * - 如果是相对路径，使用插件数据目录
     * - 否则返回绝对路径
     */
    fun expandPath(path: String): String {
        val data = getDataDir()
        return when {
            path.isBlank() -> {
                // 空路径使用默认位置
                File(data, "messages.db").absolutePath
            }
            path.startsWith("~") -> {
                // ~ 开头，替换为插件数据目录
                File(data, path.removePrefix("~")).absolutePath
            }
            !File(path).isAbsolute -> {
                // 相对路径，基于插件数据目录
                File(data, path).absolutePath
            }
            else -> path
        }
    }

    // ── YAML 解析 ──

    private fun parseConfig(yamlStr: String): StorageConfig {
        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        val map = yaml.load<Map<String, Any?>>(yamlStr) ?: return StorageConfig()

        val storage = if (map.containsKey("storage")) {
            @Suppress("UNCHECKED_CAST")
            parseStorageBackend(map["storage"] as? Map<String, Any?> ?: emptyMap())
        } else StorageBackend()

        val limits = if (map.containsKey("messageLimits")) {
            @Suppress("UNCHECKED_CAST")
            parseMessageLimits(map["messageLimits"] as? Map<String, Any?> ?: emptyMap())
        } else MessageLimits()

        return StorageConfig(storage, limits)
    }

    private fun parseStorageBackend(map: Map<String, Any?>): StorageBackend {
        return StorageBackend(
            enabled = map["enabled"]?.toString()?.toBooleanStrictOrNull() ?: true,
            type = map["type"]?.toString() ?: "sqlite",
            sqlite = if (map.containsKey("sqlite")) {
                @Suppress("UNCHECKED_CAST")
                parseSqlite(map["sqlite"] as? Map<String, Any?> ?: emptyMap())
            } else SqliteConfig(),
            mysql = if (map.containsKey("mysql")) {
                @Suppress("UNCHECKED_CAST")
                parseMysql(map["mysql"] as? Map<String, Any?> ?: emptyMap())
            } else MysqlConfig()
        )
    }

    private fun parseSqlite(map: Map<String, Any?>): SqliteConfig {
        return SqliteConfig(path = map["path"]?.toString() ?: "")
    }

    private fun parseMysql(map: Map<String, Any?>): MysqlConfig {
        @Suppress("UNCHECKED_CAST")
        val poolMap = map["connectionPool"] as? Map<String, Any?> ?: emptyMap()
        val pool = ConnectionPoolConfig(
            maxSize = poolMap["maxSize"]?.toString()?.toIntOrNull() ?: 10,
            minIdle = poolMap["minIdle"]?.toString()?.toIntOrNull() ?: 2,
            connectionTimeout = poolMap["connectionTimeout"]?.toString()?.toLongOrNull() ?: 30000
        )

        return MysqlConfig(
            host = map["host"]?.toString() ?: "localhost",
            port = map["port"]?.toString()?.toIntOrNull() ?: 3306,
            database = map["database"]?.toString() ?: "mieai_messages",
            username = map["username"]?.toString() ?: "root",
            password = map["password"]?.toString() ?: "",
            autoInit = map["autoInit"]?.toString()?.toBooleanStrictOrNull() ?: true,
            connectionPool = pool
        )
    }

    private fun parseMessageLimits(map: Map<String, Any?>): MessageLimits {
        return MessageLimits(
            maxPerGroup = map["maxPerGroup"]?.toString()?.toLongOrNull() ?: 100000,
            retentionDays = map["retentionDays"]?.toString()?.toIntOrNull() ?: 0,
            cleanupIntervalHours = map["cleanupIntervalHours"]?.toString()?.toIntOrNull() ?: 24,
            minCleanupCount = map["minCleanupCount"]?.toString()?.toIntOrNull() ?: 1000
        )
    }

    // ── YAML 输出 ──

    private fun toYaml(config: StorageConfig): String {
        return buildString {
            appendLine("# MieAI 消息存储配置")
            appendLine("# 详细说明请参考 README.md")
            appendLine()
            appendLine("# 存储配置")
            appendLine("storage:")
            appendLine("  # 是否启用消息存储功能")
            appendLine("  enabled: ${config.storage.enabled}")
            appendLine("  # 存储类型: sqlite 或 mysql")
            appendLine("  type: ${config.storage.type}")
            appendLine()
            appendLine("  # SQLite 配置")
            appendLine("  sqlite:")
            appendLine("    # 数据库文件路径 (空字符串表示使用插件数据目录)")
            appendLine("    path: \"${config.storage.sqlite.path}\"")
            appendLine()
            appendLine("  # MySQL 配置")
            appendLine("  mysql:")
            appendLine("    host: ${config.storage.mysql.host}")
            appendLine("    port: ${config.storage.mysql.port}")
            appendLine("    database: ${config.storage.mysql.database}")
            appendLine("    username: ${config.storage.mysql.username}")
            appendLine("    password: \"****\"")
            appendLine("    # 首次使用自动初始化数据库")
            appendLine("    autoInit: ${config.storage.mysql.autoInit}")
            appendLine("    connectionPool:")
            appendLine("      maxSize: ${config.storage.mysql.connectionPool.maxSize}")
            appendLine("      minIdle: ${config.storage.mysql.connectionPool.minIdle}")
            appendLine("      connectionTimeout: ${config.storage.mysql.connectionPool.connectionTimeout}")
            appendLine()
            appendLine("# 消息限制配置")
            appendLine("messageLimits:")
            appendLine("  # 每群最多存储消息数")
            appendLine("  maxPerGroup: ${config.messageLimits.maxPerGroup}")
            appendLine("  # 消息保留天数 (0 表示永久保留)")
            appendLine("  retentionDays: ${config.messageLimits.retentionDays}")
            appendLine("  # 清理检查间隔 (小时)")
            appendLine("  cleanupIntervalHours: ${config.messageLimits.cleanupIntervalHours}")
            appendLine("  # 每次清理最少条数")
            appendLine("  minCleanupCount: ${config.messageLimits.minCleanupCount}")
        }
    }
}
