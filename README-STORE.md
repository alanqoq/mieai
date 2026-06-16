# MieAI 消息存储插件

Mirai 框架群消息持久存储插件，支持 SQLite/MySQL 双后端。

## 功能

- **消息持久化**：自动存储所有群文字消息，过滤多媒体
- **双存储后端**：SQLite（本地零配置）/ MySQL（生产环境）
- **每群独立建表**：`messages_{groupId}`，互不干扰
- **多维搜索**：关键词、@用户、时间范围
- **智能清理**：过期、已分析、超额、文件超限
- **AI 分析标记**：支持 group_ai / user_ai 双标记
- **迁移工具**：SQLite → MySQL 一键迁移

## 快速开始

### 1. 安装

将 `mieai-1.0.0.jar` 放入 Mirai Console 的 `plugins/` 目录。

### 2. 配置

首次运行后编辑 `~/.mieai/config/storage.json`：

```json
{
  "storage": {
    "enabled": true,
    "type": "sqlite",
    "sqlite": {
      "path": "~/.mieai/data/messages.db"
    }
  },
  "messageLimits": {
    "maxPerGroup": 100000,
    "retentionDays": 0,
    "cleanupIntervalHours": 24
  }
}
```

### 3. 使用

| 命令 | 说明 |
|------|------|
| `/store history [n]` | 查看最近 n 条消息 |
| `/store search <关键词>` | 搜索群内消息 |
| `/store search @123456` | 查看某人消息 |
| `/store search 2025-07-01~2025-07-11` | 按时间范围搜索 |
| `/store context [n]` | 加载最近 n 条作为上下文 |
| `/store stats` | 查看群消息统计 |
| `/store cleanup --before 2025-01-01` | 清理指定日期之前 |
| `/store cleanup --analyzed` | 清理已分析消息 |
| `/store cleanup --all` | 清空当前群消息 |
| `/store migrate sqlite2mysql` | 迁移数据库 |

## 配置说明

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `storage.enabled` | `true` | 是否启用存储 |
| `storage.type` | `"sqlite"` | 存储后端 |
| `messageLimits.maxPerGroup` | `100000` | 单群最大消息数 |
| `messageLimits.retentionDays` | `0` | 保留天数（0=永久） |
| `messageLimits.cleanupIntervalHours` | `24` | 清理检查间隔 |

## 项目结构

```
com/mieai/store/
├── MessageStorePlugin.kt          # 插件入口
├── config/StoreConfig.kt          # 配置管理
├── db/
│   ├── DatabaseProvider.kt        # 数据库接口
│   ├── DatabaseFactory.kt         # 工厂
│   ├── SqliteProvider.kt          # SQLite 实现
│   └── MysqlProvider.kt           # MySQL 实现
├── listener/MessageEventListener.kt # 事件监听
├── service/
│   ├── MessageStoreService.kt     # 存储服务
│   └── CleanupService.kt          # 清理服务
├── command/
│   ├── StoreCommand.kt            # 查询命令
│   └── CleanupCommand.kt          # 清理命令
├── parser/MessageParser.kt        # 消息解析
└── util/
    ├── TimeUtils.kt               # 时间工具
    └── MigrationTool.kt           # 迁移工具
```

## 技术栈

- Kotlin 1.9.10
- Mirai Console 2.16.0
- SQLite JDBC 3.45.1.0
- MySQL Connector/J 8.3.0
- HikariCP 5.1.0
