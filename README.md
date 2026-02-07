# Spring AI Agent — 多 MCP Server + Skills Agent

基于 **Spring Boot 3.4 + Spring AI 1.0** 的 MCP (Model Context Protocol) 多 Server 架构实践。  
Client 自动连接多个 MCP Server，聚合所有工具，通过 **Skills + Plan-and-Execute** 模式智能执行用户请求。

## 架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                MCP Client Agent (端口 8080)                       │
│                                                                  │
│  用户请求 → REST API → SkillRouter (LLM 意图识别)                 │
│                              │                                   │
│                ┌─────────────┼─────────────┐                     │
│                ▼             ▼             ▼                      │
│          WeatherSkill   OrderSkill   DataAnalysisSkill  ...      │
│                │             │             │                      │
│                └─────────────┼─────────────┘                     │
│                    McpConnectionManager                           │
│              (多 Server 连接管理 + 自动重连)                       │
└───────────────┬──────────────┴──────────────┬────────────────────┘
                │ SSE (MCP 协议)               │ SSE (MCP 协议)
                ▼                              ▼
┌──────────────────────────┐   ┌──────────────────────────┐
│  MCP Server (端口 8081)   │   │ MCP Weather Server (8082)│
│                          │   │                          │
│  ┌───────────────┐       │   │  ┌──────────────┐        │
│  │ DatabaseTools │       │   │  │ WeatherTools │        │
│  │ (SQL 查询)    │       │   │  │ (天气查询)    │        │
│  ├───────────────┤       │   │  └──────┬───────┘        │
│  │  OrderTools   │       │   │         │                │
│  │ 订单/退款/物流 │       │   │    wttr.in API           │
│  └──────┬────────┘       │   └──────────────────────────┘
│         │                │
│    H2 数据库              │
└──────────────────────────┘
```

## 模块说明

| 模块 | 端口 | 说明 |
|------|------|------|
| `mcp-server` | 8081 | 业务 MCP Server — 数据库查询、订单管理 |
| `mcp-weather-server` | 8082 | 天气 MCP Server — 天气查询 |
| `mcp-client-agent` | 8080 | MCP Client + Skills Agent — 自动连接所有 Server |

### 工具列表

| Server | 工具 | 说明 |
|--------|------|------|
| `mcp-server` | `queryDatabase` | 执行 SQL SELECT 查询业务数据库 |
| `mcp-server` | `queryOrder` | 根据订单号/用户名查询订单详情 |
| `mcp-server` | `applyRefund` | 发起退款申请（含状态校验） |
| `mcp-server` | `trackLogistics` | 物流追踪（快递公司识别+时间线） |
| `mcp-weather-server` | `getWeather` | 调用 wttr.in API 查询实时天气 |

## Skills 架构

每个 Skill 由一个 `SKILL.md` 文件定义（零 Java 代码），包含：
- **frontmatter** — 名称、描述、可用工具列表
- **正文** — System Prompt（SOP 指令）

Skill 通过 **Plan-and-Execute** 模式执行：Plan → Execute Step → Observe → (RePlan?) → Final Answer

| Skill | 工具 | 说明 |
|-------|------|------|
| `weather` | getWeather | 天气查询 + 穿衣建议 |
| `order_query` | queryOrder | 订单查询 |
| `refund` | queryOrder, applyRefund | 退款申请（含状态校验） |
| `logistics` | trackLogistics | 物流追踪 |
| `data_analysis` | queryDatabase | 自然语言转 SQL 查询 |
| `chitchat` | 无 | 闲聊兜底 |

新增 Skill 只需在 `resources/skills/` 下创建目录和 `SKILL.md` 文件。

## 快速开始

### 环境要求

- Java 21+
- Maven 3.9+
- OpenAI 兼容 API Key

### 1. 配置 API Key

```bash
export OPENAI_API_KEY=sk-your-key-here
# 可选（默认使用阿里云通义千问）
export OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export OPENAI_MODEL=qwen-plus
```

### 2. 启动服务（按顺序）

```bash
# 1) 业务 Server
cd mcp-server && mvn spring-boot:run

# 2) 天气 Server
cd mcp-weather-server && mvn spring-boot:run

# 3) Client Agent（自动连接两个 Server）
cd mcp-client-agent && mvn spring-boot:run
```

### 3. API 使用

```bash
# 天气查询
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "北京今天天气怎么样？"}'

# 订单查询
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "查一下张三的所有订单"}'

# 流式对话（SSE）
curl -X POST http://localhost:8080/api/agent/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "帮我查一下有哪些商品"}'

# 查看所有可用工具
curl http://localhost:8080/api/agent/tools

# 健康检查
curl http://localhost:8080/api/agent/health

# 手动重连所有 Server
curl -X POST http://localhost:8080/api/agent/reconnect

# 重连指定 Server
curl -X POST "http://localhost:8080/api/agent/reconnect?server=weather-server"
```

## 项目结构

```
spring-ai-agent/
├── pom.xml                                 # Parent POM
├── mcp-server/                             # 业务 MCP Server (8081)
│   └── src/main/
│       ├── java/.../server/
│       │   ├── McpServerApplication.java
│       │   ├── config/McpToolConfig.java
│       │   └── tool/
│       │       ├── DatabaseTools.java
│       │       └── OrderTools.java
│       └── resources/
│           ├── application.yml
│           ├── schema.sql
│           └── data.sql
├── mcp-weather-server/                     # 天气 MCP Server (8082)
│   └── src/main/
│       ├── java/.../weather/
│       │   ├── McpWeatherServerApplication.java
│       │   ├── config/McpToolConfig.java
│       │   └── tool/WeatherTools.java
│       └── resources/application.yml
└── mcp-client-agent/                       # Client Agent (8080)
    └── src/main/
        ├── java/.../client/
        │   ├── McpClientAgentApplication.java
        │   ├── config/
        │   │   └── McpConnectionManager.java   # 多 Server 连接管理 + 自动重连
        │   ├── skill/
        │   │   ├── SkillDefinition.java
        │   │   ├── SkillLoader.java            # 自动扫描 SKILL.md
        │   │   ├── SkillRouter.java            # LLM 意图路由
        │   │   ├── SkillExecutor.java          # Plan-and-Execute 执行器
        │   │   ├── PlanActionEvent.java        # SSE 事件
        │   │   └── SkillResponse.java
        │   └── api/
        │       ├── AgentController.java
        │       ├── ChatRequest.java
        │       ├── ChatResponse.java
        │       └── GlobalExceptionHandler.java
        └── resources/
            ├── application.yml
            ├── skills/                         # Skill 定义（零代码扩展）
            │   ├── weather/SKILL.md
            │   ├── order_query/SKILL.md
            │   ├── refund/SKILL.md
            │   ├── logistics/SKILL.md
            │   ├── data_analysis/SKILL.md
            │   └── chitchat/SKILL.md
            └── static/index.html
```

## 核心特性

- **多 Server 连接** — `McpConnectionManager` 管理多个 MCP Server，工具自动聚合
- **自动重连** — 5 秒快速超时检测死连接，自动重连，无需重启 Client
- **Skills 架构** — 基于 SKILL.md 文件定义，零 Java 代码扩展新技能
- **Plan-and-Execute** — 参考 LangGraph，支持 Plan → Execute → Observe → RePlan 循环
- **流式 SSE** — 实时推送执行进度到前端

## 扩展指南

### 添加新 MCP Server

1. 创建新模块，参考 `mcp-weather-server`
2. 在 `mcp-client-agent/application.yml` 的 `mcp.servers` 中添加连接：

```yaml
mcp:
  servers: "{'business-server': 'http://localhost:8081', 'weather-server': 'http://localhost:8082', 'new-server': 'http://localhost:8083'}"
```

### 添加新工具

在 MCP Server 中创建 `@Service` 类，方法加 `@Tool` 注解，在 `McpToolConfig` 中注册：

```java
@Service
public class MyTools {
    @Tool(description = "工具描述")
    public String myTool(@ToolParam(description = "参数描述") String input) {
        return "result";
    }
}
```

### 添加新 Skill

在 `resources/skills/` 下创建目录和 `SKILL.md`：

```markdown
---
name: my_skill
description: "我的新技能描述"
tools:
  - myTool
---

你是一个专业助手。请使用 myTool 工具来帮助用户。
```

## 技术栈

- **Spring Boot 3.4** + **Spring AI 1.0**
- **MCP Server**: `spring-ai-starter-mcp-server-webmvc` (SSE)
- **MCP Client**: `spring-ai-starter-mcp-client` (自管理连接)
- **LLM**: OpenAI 兼容 API（默认通义千问 qwen-plus）
- **数据库**: H2 内存数据库（模拟电商数据）
