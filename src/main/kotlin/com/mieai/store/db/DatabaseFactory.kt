package com.mieai.store.db

import com.mieai.store.config.ConfigManager

/**
 * 数据库工厂
 * 根据配置创建对应的 DatabaseProvider 实例
 */
object DatabaseFactory {

    private var provider: DatabaseProvider? = null

    /**
     * 获取数据库提供者（单例）
     */
    fun getProvider(): DatabaseProvider {
        return provider ?: throw IllegalStateException("数据库未初始化，请先调用 initialize()")
    }

    /**
     * 初始化数据库
     * 根据配置自动选择 SQLite 或 MySQL
     */
    fun initialize(): DatabaseProvider {
        val config = ConfigManager.config.storage

        if (!config.enabled) {
            throw IllegalStateException("消息存储功能已禁用")
        }

        val newProvider = when (config.type.lowercase()) {
            "sqlite" -> SqliteProvider()
            "mysql" -> MysqlProvider()
            else -> throw IllegalArgumentException("不支持的存储类型: ${config.type}，仅支持 sqlite 或 mysql")
        }

        newProvider.initialize()
        provider = newProvider

        println("[mieai-store] 数据库已初始化，类型: ${config.type}")
        return newProvider
    }

    /**
     * 关闭数据库
     */
    fun close() {
        provider?.close()
        provider = null
    }
}
