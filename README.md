# MieAI

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.10-blue.svg)](https://kotlinlang.org)
[![Mirai](https://img.shields.io/badge/Mirai-2.16.0-green.svg)](https://github.com/mamoe/mirai)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Mirai Console 群聊机器人插件，接入 ChatGPT API 实现智能群聊对话。

## 作者

超级龙虾

## 功能特性

- 🤖 **AI 群聊对话**：通过 @机器人 或关键词触发，与 ChatGPT API 交互
- 📊 **概率插嘴**：可配置的概率触发机制，AI 会主动参与群聊
- 🧠 **上下文感知**：支持多轮对话记忆和群消息上下文
- 🖼️ **图片识别**：支持群聊图片识别（需 mimo-v2.5 模型）
- ⚙️ **灵活配置**：每个群可独立配置聊天概率、系统提示词
- 🗂️ **消息存储**：可选的群消息持久化存储（SQLite / MySQL）
- 🔧 **管理指令**：丰富的控制台和群内管理指令

## 环境要求

- JDK 17+
- Mirai Console 2.16.0+
- Overflow 1.1.0+

## 快速开始

### 1. 构建

```bash
./gradlew build
```

### 2. 部署

将 `build/libs/mieai-1.0.2.jar` 复制到 `overflow/plugins/` 目录。

### 3. 配置

插件首次启动后会在 `config/com.mieai.mieai/mieai.yml` 生成默认配置文件：

```yaml
apiUrl: "https://api.openai.com/v1/chat/completions"
apiKey: "****"
model: "gpt-3.5-turbo"
maxTokens: 4096
temperature: 0.7
globalProbability: 1.0
```

API Key 支持通过环境变量 `MIEAI_API_KEY` 设置（优先级高于配置文件）。

### 4. 重启

重启 Overflow 使插件生效。

## 指令列表

| 指令 | 说明 | 权限 |
|------|------|------|
| `/mieai config` | 查看当前群配置 | 群聊 |
| `/mieai probability <0.0-1.0>` | 设置聊天概率 | 管理员 |
| `/mieai prompt <提示词>` | 设置当前群系统提示词 | 管理员 |
| `/mieai showPrompt` | 查看当前群系统提示词 | 群聊 |
| `/mieai setGlobalPrompt <提示词>` | 设置全局默认提示词 | Admin |
| `/mieai showGlobalPrompt` | 查看全局默认提示词 | Admin |
| `/mieai disable` | 禁用当前群聊天 | 管理员 |
| `/mieai enable` | 启用当前群聊天 | 管理员 |
| `/mieai clear` | 清空对话历史 | 群聊 |
| `/mieai contextOn` | 开启上下文消息 | Admin |
| `/mieai contextOff` | 关闭上下文消息 | Admin |
| `/mieai contextCount <1-50>` | 设置上下文消息条数 | Admin |
| `/mieai contextInfo` | 查看上下文配置 | 群聊 |

## 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `apiUrl` | OpenAI API 地址 | API 端点 |
| `apiKey` | - | API Key（也可用环境变量） |
| `model` | `gpt-3.5-turbo` | 使用的模型 |
| `maxTokens` | 4096 | 最大输出 token 数 |
| `temperature` | 0.7 | 生成温度 |
| `globalProbability` | 1.0 | 全局聊天概率 |
| `historySize` | 10 | 对话历史保留轮数 |
| `enableContext` | true | 是否启用多轮对话 |
| `enableContextMessages` | true | 概率触发时携带群消息上下文 |
| `contextMessageCount` | 5 | 上下文消息条数 |
| `enableImageRecognition` | false | 是否启用图片识别 |
| `maxImageSizeKB` | 2048 | 图片识别最大尺寸 |

## 触发机制

1. **@触发**：群成员 @机器人 必定触发
2. **关键词触发**：消息包含配置的关键词即可触发
3. **概率触发**：随机概率主动参与群聊（可每个群独立配置）

## 权限系统

- `mieai:chat` - 聊天权限
- `mieai:admin` - 管理权限（可执行全局指令）

## 项目结构

```
src/main/kotlin/com/mieai/
├── plugin/MieAiPlugin.kt        # 插件入口
├── config/MieAiConfig.kt        # 配置管理
├── command/MieAiCommand.kt      # 指令处理
├── service/ChatGptService.kt    # ChatGPT API 调用
└── store/                       # 消息存储模块
    ├── MessageStorePlugin.kt    # 存储插件入口
    ├── db/                      # 数据库工厂
    ├── config/                  # 存储配置
    ├── service/                 # 存储服务
    ├── listener/                # 消息监听
    └── util/                    # 工具类
```

## 技术栈

- Kotlin + Gradle
- Mirai Console 2.16.0
- OkHttp 4.x
- SnakeYAML
- SQLite / MySQL（可选消息存储）
- HikariCP（数据库连接池）
