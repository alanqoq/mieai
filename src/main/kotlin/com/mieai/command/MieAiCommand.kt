package com.mieai.command

import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.command.getGroupOrNull
import net.mamoe.mirai.console.permission.AbstractPermitteeId
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.contact.MemberPermission
import com.mieai.plugin.MieAiPlugin
import com.mieai.config.MieAiConfig

object MieAiCommand : CompositeCommand(
    owner = MieAiPlugin,
    primaryName = "mieai",
    description = "MieAI 插件指令"
) {

    @SubCommand
    @Description("查看当前群配置")
    suspend fun CommandSender.config() {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        val groupId = group.id
        val configInfo = buildString {
            append("=== MieAI 群配置 ===\n")
            append("群号: $groupId\n")
            append("聊天概率: ${MieAiConfig.getGroupProbability(groupId)}\n")
            append("系统提示词: ${MieAiConfig.getGroupSystemPrompt(groupId)}\n")
            append("聊天功能: ${if (MieAiConfig.isGroupDisabled(groupId)) "已禁用" else "已启用"}\n")
        }
        sendMessage(configInfo)
    }

    @SubCommand
    @Description("设置当前群聊天概率 (0.0-1.0)")
    suspend fun CommandSender.probability(value: Double) {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        if (!checkSenderPermission(this)) {
            sendMessage("没有权限使用此指令")
            return
        }

        MieAiConfig.setGroupProbability(group.id, value)
        sendMessage("已设置群 ${group.id} 的聊天概率为 $value")
    }

    @SubCommand
    @Description("设置当前群系统提示词")
    suspend fun CommandSender.prompt(vararg args: String) {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        if (!checkSenderPermission(this)) {
            sendMessage("没有权限使用此指令")
            return
        }

        val prompt = args.joinToString(" ")
        if (prompt.isBlank()) {
            sendMessage("提示词不能为空")
            return
        }

        MieAiConfig.setGroupSystemPrompt(group.id, prompt)
        sendMessage("已设置群 ${group.id} 的系统提示词")
    }

    @SubCommand
    @Description("查看当前群系统提示词")
    suspend fun CommandSender.showPrompt() {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        val prompt = MieAiConfig.getGroupSystemPrompt(group.id)
        sendMessage("当前群系统提示词:\n$prompt")
    }

    @SubCommand
    @Description("设置全局默认系统提示词")
    suspend fun CommandSender.setGlobalPrompt(vararg args: String) {
        if (!checkAdminPermission(this)) {
            sendMessage("没有权限使用此指令")
            return
        }

        val prompt = args.joinToString(" ")
        if (prompt.isBlank()) {
            sendMessage("提示词不能为空")
            return
        }

        MieAiConfig.updateDefaultSystemPrompt(prompt)
        sendMessage("已设置全局默认系统提示词")
    }

    @SubCommand
    @Description("查看全局默认系统提示词")
    suspend fun CommandSender.showGlobalPrompt() {
        if (!checkAdminPermission(this)) {
            sendMessage("没有权限使用此指令")
            return
        }

        sendMessage("全局默认系统提示词:\n${MieAiConfig.defaultSystemPrompt}")
    }

    @SubCommand
    @Description("禁用当前群聊天功能")
    suspend fun CommandSender.disable() {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        if (!checkSenderPermission(this)) {
            sendMessage("没有权限使用此指令")
            return
        }

        MieAiConfig.disableGroup(group.id)
        sendMessage("已禁用群 ${group.id} 的聊天功能")
    }

    @SubCommand
    @Description("启用当前群聊天功能")
    suspend fun CommandSender.enable() {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        if (!checkSenderPermission(this)) {
            sendMessage("没有权限使用此指令")
            return
        }

        MieAiConfig.enableGroup(group.id)
        sendMessage("已启用群 ${group.id} 的聊天功能")
    }

    @SubCommand
    @Description("清空当前群对话历史")
    suspend fun CommandSender.clear() {
        val group = getGroupOrNull() ?: run {
            sendMessage("请在群聊中使用此指令")
            return
        }

        com.mieai.service.ChatGptService.clearHistory(group.id)
        sendMessage("已清空群 ${group.id} 的对话历史")
    }

    @SubCommand
    @Description("开启概率触发携带上下文消息")
    suspend fun CommandSender.contextOn() {
        if (!checkAdminPermission(this)) {
            sendMessage("没有权限使用此指令")
            return
        }

        MieAiConfig.enableContextMessages = true
        sendMessage("已开启概率触发时携带前几条群消息功能")
    }

    @SubCommand
    @Description("关闭概率触发携带上下文消息")
    suspend fun CommandSender.contextOff() {
        if (!checkAdminPermission(this)) {
            sendMessage("没有权限使用此指令")
            return
        }

        MieAiConfig.enableContextMessages = false
        sendMessage("已关闭概率触发时携带前几条群消息功能")
    }

    @SubCommand
    @Description("设置概率触发时携带的群消息数量")
    suspend fun CommandSender.contextCount(count: Int) {
        if (!checkAdminPermission(this)) {
            sendMessage("没有权限使用此指令")
            return
        }

        if (count < 1 || count > 50) {
            sendMessage("数量必须在 1-50 之间")
            return
        }

        MieAiConfig.contextMessageCount = count
        sendMessage("已设置概率触发时携带前 $count 条群消息")
    }

    @SubCommand
    @Description("查看上下文消息配置")
    suspend fun CommandSender.contextInfo() {
        val group = getGroupOrNull()
        val info = buildString {
            append("=== 上下文消息配置 ===\n")
            append("携带上下文消息: ${if (MieAiConfig.enableContextMessages) "已开启" else "已关闭"}\n")
            append("携带消息数量: ${MieAiConfig.contextMessageCount}\n")
            if (group != null) {
                append("当前群聊天概率: ${MieAiConfig.getGroupProbability(group.id)}\n")
            }
        }
        sendMessage(info)
    }

    private suspend fun checkSenderPermission(sender: CommandSender): Boolean {
        // 控制台直接放行
        if (sender.user == null && sender.getGroupOrNull() == null) return true
        val user = sender.user ?: return false
        val group = sender.getGroupOrNull() ?: return false

        // 检查是否有管理权限
        val permitteeId = AbstractPermitteeId.ExactUser(user.id)
        if (permitteeId.hasPermission(MieAiPlugin.adminPermission)) {
            return true
        }

        // 检查是否是群管理员或群主
        val member = group[user.id] ?: return false
        return member.permission >= MemberPermission.ADMINISTRATOR
    }

    private suspend fun checkAdminPermission(sender: CommandSender): Boolean {
        // 控制台直接放行
        if (sender.user == null && sender.getGroupOrNull() == null) return true
        val user = sender.user ?: return false
        val permitteeId = AbstractPermitteeId.ExactUser(user.id)
        return permitteeId.hasPermission(MieAiPlugin.adminPermission)
    }
}
