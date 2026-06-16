package com.mieai.store

import net.mamoe.mirai.console.command.CommandManager
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import com.mieai.store.command.CleanupCommand
import com.mieai.store.command.StoreCommand
import com.mieai.store.listener.MessageEventListener
import com.mieai.store.service.CleanupService
import com.mieai.store.service.MessageStoreService

/**
 * MieAI 消息存储插件入口
 */
object MessageStorePlugin : KotlinPlugin(
    JvmPluginDescription(
        id = "com.mieai.store",
        name = "mieai-store",
        version = "1.0.2"
    ) {
        author("超级龙虾")
        info("MieAI 群消息持久存储插件")
    }
) {

    lateinit var storePermission: Permission
        private set

    override fun onEnable() {
        // 设置配置文件路径
        val configFile = resolveConfigFile("storage.yml")
        val dataDir = resolveDataFile(".")
        
        // 初始化配置管理器
        com.mieai.store.config.ConfigManager.setPaths(configFile, dataDir)

        // 注册权限
        storePermission = PermissionService.INSTANCE.register(
            PermissionId("mieai", "store"),
            "MieAI 消息存储权限",
            parentPermission
        )

        // 注册命令
        CommandManager.INSTANCE.registerCommand(StoreCommand)
        CommandManager.INSTANCE.registerCommand(CleanupCommand)

        // 初始化存储服务
        MessageStoreService.init()

        // 启动清理服务
        CleanupService.start()

        // 注册事件监听
        MessageEventListener.register()

        logger.info("MieAI 消息存储插件已加载")
    }

    override fun onDisable() {
        // 停止清理服务
        CleanupService.stop()

        // 关闭存储服务
        MessageStoreService.close()

        logger.info("MieAI 消息存储插件已卸载")
    }
}
