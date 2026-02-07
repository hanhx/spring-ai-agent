# Spring AI Agent — MCP Server + Skills Agent

基于 **Spring Boot 3.4 + Spring AI 1.0** 的 MCP (Model Context Protocol) + **Skills 架构**实践，包含 **MCP Server** 和 **MCP Client + Skills Agent** 两个独立模块。

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│              MCP Client Agent (端口 8080)                     │
│                                                             │
│  用户请求 → REST API → SkillRouter (LLM 意图识别)             │
│                            │                                │
│              ┌─────────────┼─────────────┐                  │
│              ▼             ▼             ▼                   │
│        WeatherSkill   OrderSkill   DataAnalysisSkill        │
│        (专属prompt     (专属prompt    (专属prompt             │
│         +getWeather)   +订单工具)     +queryDatabase)        │
│              │             │             │                   │
│              └─────────────┼─────────────┘                  │
│                   ToolCallbackProvider                       │
│                   (MCP 自动发现工具)                          │
└────────────────────────┬────────────────────────────────────┘
                         │ Streamable-HTTP (MCP 协议)
                         ▼
┌─────────────────────────────────────────────────────────────┐
│               MCP Server (端口 8081)                          │
│                                                             │
│  ┌──────────────┐ ┌───────────────┐ ┌──────────────────┐    │
│  │ WeatherTools │ │ DatabaseTools │ │   OrderTools     │    │
│  │ (天气查询)    │ │ (SQL 查询)    │ │ 订单/退款/物流   │    │
│  └──────┬───────┘ └──────┬────────┘ └────────┬─────────┘    │
│         │                │                   │              │
│    wttr.in API      H2 数据库           H2 数据库            │
└─────────────────────────────────────────────────────────────┘
```

## Skills 架构说明

**流程编排型 Skills** —— 每个 Skill 是一个独立的业务流程，内部包含多个确定性步骤。Skill 自己编排"何时调用 MCP 工具"和"何时使用 LLM"，而不是把所有决策都交给 LLM。

核心组件：
- **`McpToolCaller`** — 直接调用 MCP 工具（不经过 LLM），实现确定性的工具调用
- **`LlmHelper`** — LLM 作为"工具人"：提取参数、润色结果
- **`SkillRouter`** — LLM 意图识别，分发到对应 Skill

| Skill | 调用的 MCP 工具 | 流程 |
|-------|----------------|------|
| `WeatherSkill` | getWeather | LLM提取城市 → 调用工具 → LLM润色结果 |
| `OrderQuerySkill` | queryOrder | LLM提取订单号/用户名 → 参数校验 → 调用工具 → LLM润色 |
| `RefundSkill` | queryOrder, applyRefund | LLM提取参数 → 校验 → 查订单确认状态 → 调用退款 → LLM润色 |
| `LogisticsSkill` | trackLogistics | LLM提取订单号 → 校验 → 调用工具 → LLM润色 |
| `DataAnalysisSkill` | queryDatabase | LLM生成SQL → 代码校验安全性 → 调用工具 → LLM分析总结 |
| `ChitChatSkill` | 无 | LLM 直接回复（兜底） |

## 模块说明

| 模块 | 端口 | 说明 |
|------|------|------|
| `mcp-server` | 8081 | MCP Server，通过 Streamable-HTTP 暴露工具能力 |
| `mcp-client-agent` | 8080 | MCP Client + Skills Agent，意图路由 + 专业 Skill 执行 |

### MCP Server 工具列表

| 工具 | 说明 |
|------|------|
| `getWeather` | 调用 wttr.in API 查询实时天气 |
| `queryDatabase` | 执行 SQL SELECT 查询业务数据库 |
| `queryOrder` | 根据订单号/用户名查询订单详情 |
| `applyRefund` | 发起退款申请（含状态校验） |
| `trackLogistics` | 物流追踪（快递公司识别+时间线） |

## 快速开始

### 环境要求

- Java 21+
- Maven 3.9+
- OpenAI API Key（或兼容 API）

### 1. 配置 API Key

```bash
export OPENAI_API_KEY=sk-your-key-here
# 可选
export OPENAI_BASE_URL=https://api.openai.com
export OPENAI_MODEL=gpt-4o
```

### 2. 启动 MCP Server

```bash
cd mcp-server
mvn spring-boot:run
# 启动在 8081 端口，H2 控制台: http://localhost:8081/h2-console
```

### 3. 启动 MCP Client Agent

```bash
cd mcp-client-agent
mvn spring-boot:run
# 启动在 8080 端口
```

### 4. API 使用

```bash
# 天气查询
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "北京今天天气怎么样？"}'

# 数据库查询
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "帮我查一下有哪些商品"}'

# 订单查询 (Skill)
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "查一下张三的所有订单"}'

# 物流追踪 (Skill)
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "帮我查订单 ORD20250203002 的物流"}'

# 退款申请 (Skill)
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "我要退款，订单号 ORD20250205003，不想要了"}'

# 查看 MCP Server 暴露的所有工具
curl http://localhost:8080/api/agent/tools

# 健康检查
curl http://localhost:8080/api/agent/health
```

## 项目结构

```
spring-ai-agent/
├── pom.xml                              # Parent POM (多模块)
├── mcp-server/                          # MCP Server
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/mcp/server/
│       │   ├── McpServerApplication.java
│       │   ├── config/
│       │   │   └── McpToolConfig.java          # ToolCallbackProvider 注册
│       │   └── tool/
│       │       ├── WeatherTools.java           # @Tool: 天气查询
│       │       ├── DatabaseTools.java          # @Tool: SQL 查询
│       │       └── OrderTools.java             # @Tool: 订单/退款/物流
│       └── resources/
│           ├── application.yml
│           ├── schema.sql                      # 建表脚本
│           └── data.sql                        # 模拟数据
│
└── mcp-client-agent/                    # MCP Client + Skills Agent
    ├── pom.xml
    └── src/main/
        ├── java/com/example/mcp/client/
        │   ├── McpClientAgentApplication.java
        │   ├── skill/                          # Skills 架构核心
        │   │   ├── Skill.java                  # Skill 接口（流程编排型）
        │   │   ├── SkillRequest.java           # Skill 请求对象
        │   │   ├── SkillResponse.java          # Skill 响应对象
        │   │   ├── SkillRouter.java            # LLM 意图识别 + Skill 路由分发
        │   │   ├── McpToolCaller.java          # MCP 工具直接调用器（不经过 LLM）
        │   │   ├── LlmHelper.java              # LLM 辅助：参数提取 + 结果润色
        │   │   ├── WeatherSkill.java           # 天气查询 Skill
        │   │   ├── OrderQuerySkill.java        # 订单查询 Skill
        │   │   ├── RefundSkill.java            # 退款申请 Skill（含订单状态校验）
        │   │   ├── LogisticsSkill.java         # 物流追踪 Skill
        │   │   ├── DataAnalysisSkill.java      # 数据分析 Skill（NL→SQL）
        │   │   └── ChitChatSkill.java          # 闲聊兜底 Skill
        │   └── api/
        │       ├── AgentController.java        # REST API
        │       ├── ChatRequest.java
        │       ├── ChatResponse.java
        │       └── GlobalExceptionHandler.java
        └── resources/
            └── application.yml
```

## 扩展指南

### 在 MCP Server 添加新工具

1. 创建 `@Service` 类，方法加 `@Tool` + `@ToolParam` 注解
2. 在 `McpToolConfig` 中注册为 `ToolCallbackProvider`

```java
@Service
public class MyTools {
    @Tool(description = "工具描述")
    public String myTool(@ToolParam(description = "参数描述") String input) {
        return "result";
    }
}

// McpToolConfig 中添加:
@Bean
public ToolCallbackProvider myToolProvider(MyTools myTools) {
    return MethodToolCallbackProvider.builder().toolObjects(myTools).build();
}
```

### 拆分为独立项目

两个模块已完全独立，可直接拆成两个 Git 仓库分别部署，只需修改 Client 的 `application.yml` 中 MCP Server 地址即可。

## 技术栈

- **Spring Boot 3.4.1** + **Spring AI 1.0.0**
- **MCP Server**: `spring-ai-starter-mcp-server-webmvc` (Streamable-HTTP)
- **MCP Client**: `spring-ai-starter-mcp-client`
- **LLM**: OpenAI (gpt-4o) 或兼容 API
- **数据库**: H2 内存数据库（模拟电商数据）
