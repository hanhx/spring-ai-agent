# 研究发现

## 现有代码分析

### 1. 项目模块结构
- mcp-client-agent: 主应用（Spring AI + MCP）
- mcp-server: MCP 工具服务
- mcp-weather-server: 天气 MCP 服务

### 2. 现有数据库表
```
- chat_memory: 短期对话记忆（按 conversation_id 分组）
- user_profile: 用户偏好（长期记忆初级版）
- skill_registry: 技能注册表
- pending_intents: 待处理意图
```

### 3. 现有 API 接口
```
GET  /api/chat/history/{conversationId}
GET  /api/chat/recent/{conversationId}?count=10
POST /api/chat/message
DELETE /api/chat/history/{conversationId}
GET  /api/chat/conversations
```

### 4. 记忆系统现状

#### 短期记忆
- 实现: `ChatApplicationService` + `ChatMemoryRepository`
- 存储: MySQL `chat_memory` 表
- 问题: 按 conversation_id 隔离，但无用户概念

#### 长期记忆
- 实现: `UserProfileService` + `UserProfileRepository`
- 存储: MySQL `user_profile` 表
- 功能: 存储用户偏好 key-value
- 问题: 无语义检索能力，只是简单 key-value

### 5. 当前认证
- 目前没有登录功能
- 通过 header `X-User-Id` 传递用户 ID（可选）
- 默认用户: "anonymous"