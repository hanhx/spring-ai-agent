# MCP Client Agent

> Spring AI Agent with DDD Architecture + MyBatis Plus + Multi-Database Support

**版本**: v1.0  
**最后更新**: 2026-02-27

---

## 📑 目录

- [项目简介](#项目简介)
- [快速开始](#快速开始)
- [架构设计](#架构设计)
- [技术栈](#技术栈)
- [数据库配置](#数据库配置)
- [API 文档](#api-文档)
- [开发指南](#开发指南)
- [测试](#测试)
- [迁移指南](#迁移指南)
- [参考资料](#参考资料)

---

## 🎯 项目简介

MCP Client Agent 是一个基于 **Spring AI** 的智能代理系统，采用 **DDD（领域驱动设计）** 四层架构，集成 **MyBatis Plus** 和 **MapStruct**，支持多数据库切换。

### 核心特性

- ✅ **DDD 四层架构** - facade/application/domain/infra 清晰分层
- ✅ **MyBatis Plus** - 简化数据访问，支持复杂查询
- ✅ **MapStruct** - 自动生成 DTO/DO 转换代码
- ✅ **多数据库支持** - H2 内存数据库 + MySQL，一键切换
- ✅ **依赖倒置** - Domain 层定义接口，Infra 层实现
- ✅ **聚合根设计** - 封装业务规则，提高内聚性
- ✅ **Apache Commons** - 统一使用 StringUtils/CollectionUtils

---

## 🚀 快速开始

### 前置要求

- JDK 21+
- Maven 3.6+
- MySQL 8.0+（可选，默认使用 H2）

### 启动应用

#### 方式 1: 使用 H2 内存数据库（推荐本地开发）

```bash
# 默认使用 H2，无需任何配置
mvn spring-boot:run

# 访问 H2 Web 控制台
open http://localhost:8080/h2-console
```

**H2 控制台连接信息：**
- JDBC URL: `jdbc:h2:mem:mcp_client_agent`
- User Name: `sa`
- Password: (留空)

#### 方式 2: 使用 MySQL

```bash
# 设置环境变量
export SPRING_PROFILE=dev
export MYSQL_PASSWORD=your_password

# 启动应用
mvn spring-boot:run
```

### 测试 API

```bash
# 添加消息
curl -X POST http://localhost:8080/api/chat/message \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"test","messageType":"user","content":"Hello!"}'

# 查询历史
curl http://localhost:8080/api/chat/history/test
```

---

## 🏗️ 架构设计

### DDD 四层架构

```
com.hhx.agi/
├── facade/                           【门面层】对外接口
│   └── rest/
│       ├── controller/               REST API 控制器
│       │   └── ChatController.java
│       └── request/                  请求对象
│           └── AddMessageRequest.java
│
├── application/                      【应用层】编排与转换
│   ├── service/                      应用服务
│   │   └── ChatApplicationService.java
│   ├── dto/                          数据传输对象
│   │   ├── ChatMessageDTO.java
│   │   └── ChatHistoryDTO.java
│   └── assembler/                    DTO/DO 转换器 (MapStruct)
│       └── ChatMemoryAssembler.java
│
├── domain/                           【领域层】核心业务逻辑
│   └── chat/                         聊天聚合
│       ├── model/                    领域模型
│       │   ├── ChatMemory.java       (聚合根)
│       │   ├── Message.java          (实体)
│       │   └── ConversationId.java   (值对象)
│       └── repository/               仓储接口
│           └── ChatMemoryRepository.java
│
├── infra/                            【基础设施层】技术实现
│   ├── po/                           持久化对象
│   │   └── ChatMemoryPO.java
│   ├── dao/                          MyBatis Mapper
│   │   └── ChatMemoryMapper.java
│   ├── repository/                   仓储实现
│   │   └── ChatMemoryRepositoryImpl.java
│   ├── converter/                    PO/DO 转换器
│   │   └── ChatMemoryConverter.java
│   └── config/                       基础设施配置
│       └── MyBatisConfig.java
│
└── shared/                           【共享层】业务无关工具
    └── (待扩展)
```

### 架构特点

#### 1. 依赖倒置原则
- **Domain 层**定义仓储接口
- **Infra 层**实现仓储接口
- Domain 层不依赖 Infra 层

#### 2. 聚合根设计
- `ChatMemory` 是聚合根，封装业务规则
- 消息数量限制（MAX_MESSAGES = 50）
- 自动淘汰旧消息

#### 3. 值对象
- `ConversationId` 封装会话 ID，保证不为空
- 不可变对象，线程安全

#### 4. 统一工具类
- 所有空值检查使用 `StringUtils.isBlank()`
- 集合操作使用 `CollectionUtils.isEmpty()`

---

## 🔧 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.4.1 | 应用框架 |
| Spring AI | 1.0.3 | AI 集成 |
| MyBatis Plus | 3.5.5 | ORM 框架，简化 CRUD |
| MapStruct | 1.5.5.Final | DTO/DO 转换 |
| Lombok | Spring Boot 默认 | 减少样板代码 |
| H2 Database | Spring Boot 默认 | 内存数据库 |
| MySQL | 8.0+ | 关系型数据库 |
| Apache Commons Lang3 | Spring Boot 默认 | 字符串/集合工具 |
| Apache Commons Collections4 | 4.4 | 集合工具增强 |

---

## 💾 数据库配置

### 支持的数据库

| Profile | 数据库 | 用途 | 配置文件 |
|---------|--------|------|---------|
| **`local`** | H2 内存数据库 | 本地开发/测试 | `application-local.yml` |
| **`dev`** | MySQL | 开发环境 | `application-dev.yml` |

**默认 Profile**: `local` (H2)

### 切换数据库

#### 方式 1: 环境变量（推荐）

```bash
# H2（默认）
mvn spring-boot:run

# MySQL
export SPRING_PROFILE=dev
mvn spring-boot:run
```

#### 方式 2: 命令行参数

```bash
# H2
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"

# MySQL
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"
```

#### 方式 3: IDE 配置

**IntelliJ IDEA**
1. 打开 `Run/Debug Configurations`
2. 在 `Environment variables` 中添加：
   - H2: `SPRING_PROFILE=local`
   - MySQL: `SPRING_PROFILE=dev;MYSQL_PASSWORD=yourpassword`

**VS Code**
```json
{
  "configurations": [
    {
      "type": "java",
      "name": "Spring Boot (H2)",
      "env": {
        "SPRING_PROFILE": "local"
      }
    },
    {
      "type": "java",
      "name": "Spring Boot (MySQL)",
      "env": {
        "SPRING_PROFILE": "dev",
        "MYSQL_PASSWORD": "yourpassword"
      }
    }
  ]
}
```

### H2 数据库特性

#### 优点
- ✅ **零配置** - 无需安装 MySQL
- ✅ **快速启动** - 内存数据库，启动秒级
- ✅ **隔离测试** - 每次重启数据清空
- ✅ **Web 控制台** - 可视化查看数据（`http://localhost:8080/h2-console`）
- ✅ **MySQL 兼容** - 使用 `MODE=MySQL`，SQL 语法兼容

#### H2 数据持久化（可选）

默认配置下，H2 数据在应用重启后会丢失。如需持久化：

```yaml
# application-local.yml
spring:
  datasource:
    url: jdbc:h2:file:./data/mcp_client_agent;MODE=MySQL
```

### 性能对比

| 指标 | H2 内存 | MySQL |
|------|---------|-------|
| 启动时间 | ~2 秒 | ~5 秒 |
| 查询速度 | 极快 | 快 |
| 数据持久化 | ❌ | ✅ |
| 并发支持 | 低 | 高 |
| 生产可用 | ❌ | ✅ |

### 推荐使用场景

| 场景 | 推荐 Profile |
|------|-------------|
| 本地开发 | `local` (H2) - 默认 |
| 单元测试 | `local` (H2) |
| 集成测试 | `local` (H2) 或 `dev` (MySQL) |
| CI/CD | `local` (H2) |
| 开发环境 | `dev` (MySQL) |
| Demo 演示 | `local` (H2) |

---

## 📡 API 文档

### 基础路径: `/api/chat`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/history/{conversationId}` | 获取完整对话历史 |
| GET | `/recent/{conversationId}?count=10` | 获取最近 N 条消息 |
| POST | `/message` | 添加消息 |
| DELETE | `/history/{conversationId}` | 清空对话历史 |
| GET | `/conversations` | 获取所有会话 ID |

### 示例请求

#### 1. 添加消息
```bash
curl -X POST http://localhost:8080/api/chat/message \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "test-001",
    "messageType": "user",
    "content": "Hello, world!"
  }'
```

#### 2. 获取对话历史
```bash
curl http://localhost:8080/api/chat/history/test-001
```

**响应：**
```json
{
  "conversationId": "test-001",
  "messages": [
    {
      "messageType": "user",
      "content": "Hello, world!",
      "createdAt": "2026-02-27T15:30:00"
    }
  ],
  "totalCount": 1
}
```

#### 3. 获取最近 5 条消息
```bash
curl http://localhost:8080/api/chat/recent/test-001?count=5
```

#### 4. 清空对话历史
```bash
curl -X DELETE http://localhost:8080/api/chat/history/test-001
```

#### 5. 获取所有会话
```bash
curl http://localhost:8080/api/chat/conversations
```

---

## 👨‍💻 开发指南

### 编码规范

#### 1. 空值检查
```java
// ✅ 推荐
if (StringUtils.isBlank(value)) { ... }
if (CollectionUtils.isEmpty(list)) { ... }

// ❌ 不推荐
if (value == null || value.isEmpty()) { ... }
if (list == null || list.isEmpty()) { ... }
```

#### 2. 字符串比较
```java
// ✅ 推荐
if (StringUtils.equalsIgnoreCase("user", messageType)) { ... }

// ❌ 不推荐
if ("user".equalsIgnoreCase(messageType)) { ... }
```

#### 3. 集合处理
```java
// ✅ 推荐
List<Message> messages = new ArrayList<>(CollectionUtils.emptyIfNull(input));

// ❌ 不推荐
List<Message> messages = input != null ? new ArrayList<>(input) : new ArrayList<>();
```

### 与旧架构对比

| 特性 | 旧架构 (com.example.mcp.client) | 新架构 (com.hhx.agi) |
|------|--------------------------------|---------------------|
| 数据访问 | JdbcTemplate + 手写 SQL | MyBatis Plus + Mapper |
| 分层 | 混合（api/config/skill） | DDD 四层（facade/application/domain/infra） |
| DTO 转换 | 手写 | MapStruct 自动生成 |
| 业务逻辑 | 分散在 Service 中 | 聚合根封装 |
| 依赖方向 | 混乱 | 清晰（依赖倒置） |
| 可测试性 | 低 | 高（领域层纯 Java） |

---

## 🧪 测试

### 单元测试（领域层）

```java
@Test
void testChatMemoryAddMessage() {
    ConversationId id = new ConversationId("test");
    ChatMemory chatMemory = new ChatMemory(id);
    
    Message message = new Message("user", "Hello");
    chatMemory.addMessage(message);
    
    assertEquals(1, chatMemory.getMessageCount());
}
```

### 集成测试（应用层）

```java
@SpringBootTest
@Transactional
class ChatApplicationServiceTest {
    
    @Autowired
    private ChatApplicationService service;
    
    @Test
    void testAddAndGetMessage() {
        service.addMessage("test", "user", "Hello");
        ChatHistoryDTO history = service.getConversationHistory("test");
        
        assertEquals(1, history.getTotalCount());
    }
}
```

### 测试不同数据库

#### 测试 H2
```bash
# 启动应用（H2）- 默认
mvn spring-boot:run

# 测试 API
curl -X POST http://localhost:8080/api/chat/message \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"test-h2","messageType":"user","content":"Hello H2!"}'

# 查看 H2 控制台
open http://localhost:8080/h2-console
```

#### 测试 MySQL
```bash
# 启动应用（MySQL）
SPRING_PROFILE=dev mvn spring-boot:run

# 测试 API
curl -X POST http://localhost:8080/api/chat/message \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"test-mysql","messageType":"user","content":"Hello MySQL!"}'
```

---

## 🔄 迁移指南

### 旧代码保留策略

- ✅ 旧代码（`com.example.mcp.client`）完全保留
- ✅ 旧 API（`/api/agent/*`）继续可用
- ✅ 新旧架构并行运行，零风险迁移

### 后续迁移步骤

#### 阶段 1: Skill 模块迁移（推荐）
1. 创建 `domain/skill/` 聚合
2. 定义 `Skill` 聚合根、`SkillDefinition` 实体
3. 创建 `SkillRepository` 接口
4. 实现 MyBatis Mapper（`SkillMapper`、`PendingIntentMapper`）
5. 迁移 `SkillLoader`、`SkillRouter`、`SkillExecutor` 到应用层

#### 阶段 2: MCP 连接管理迁移
1. 将 `McpConnectionManager` 移到 `infra/adapter/`
2. 创建 `McpClientAdapter` 适配器
3. 定义领域服务接口

#### 阶段 3: 删除旧代码
1. 确认新架构稳定运行
2. 逐步删除 `com.example.mcp.client` 包
3. 更新所有引用

### 数据迁移

#### 从 H2 导出到 MySQL

1. 在 H2 控制台执行：
```sql
SCRIPT TO 'backup.sql'
```

2. 修改 `backup.sql` 兼容 MySQL 语法

3. 在 MySQL 中导入：
```bash
mysql -u root -p mcp_client_agent < backup.sql
```

---

## 🎯 下一步计划

1. ✅ **ChatMemory 模块**已完成（试点）
2. ⏳ **Skill 模块**迁移（参考 ChatMemory 模式）
3. ⏳ **PendingIntent 模块**迁移
4. ⏳ **Rules 系统**实现（全局规则管理）
5. ⏳ **知识图谱**实现（跨会话智能）

---

## 📚 参考资料

- [MyBatis Plus 官方文档](https://baomidou.com/)
- [MapStruct 官方文档](https://mapstruct.org/)
- [DDD 领域驱动设计](https://domain-driven-design.org/)
- [Apache Commons Lang](https://commons.apache.org/proper/commons-lang/)
- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- [H2 Database 官方文档](https://www.h2database.com/)

---

## 📄 许可证

本项目采用 MIT 许可证。

---

**项目维护**: Cascade AI  
**最后更新**: 2026-02-27  
**架构版本**: v1.0
