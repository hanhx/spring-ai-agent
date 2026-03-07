# Windsurf Web 版 AI Agent - 项目规划

## 项目概述

目标：构建一个 Web 版的 AI Agent，类似 Windsurf/Cursor 的协作编程体验

## 阶段划分

### Phase 1: 基础框架 (当前)
- [ ] 登录/注册功能
- [ ] 短期记忆 + 长期记忆
- [ ] 基础对话界面

### Phase 2: 代码编辑器
- [ ] Monaco Editor 集成
- [ ] 文件浏览器
- [ ] 项目结构感知

### Phase 3: AI 协作功能
- [ ] 代码生成
- [ ] Inline 编辑
- [ ] 代码补全

### Phase 4: 高级功能
- [ ] 终端集成
- [ ] LSP 支持
- [ ] 多用户协作

---

## 进度追踪

| 日期 | 阶段 | 状态 | 备注 |
|------|------|------|------|
| 2026-03-07 | Phase 1 规划 | in_progress | 登录+记忆系统设计 |

## 当前任务

- 分析现有代码结构
- 设计登录模块
- 设计记忆系统增强方案

---

# Phase 1: 登录 + 短期/长期记忆

## 1.1 登录模块

### 方案: Spring Security + JWT

### 需要创建/修改的表

```sql
-- 用户表
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    nickname VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 用户的会话/对话（关联 user_id）
ALTER TABLE chat_memory ADD COLUMN user_id VARCHAR(64);
ALTER TABLE chat_memory ADD INDEX idx_chat_memory_user (user_id);
```

### 需要创建的接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/register | 注册 |
| POST | /api/auth/login | 登录，返回 JWT |
| GET | /api/auth/me | 获取当前用户信息 |
| POST | /api/auth/refresh | 刷新 token |

### 安全配置

- 密码 BCrypt 加密
- JWT Token 过期时间: 7 天
- 需要认证的接口: 所有 `/api/chat/**`, `/api/agent/**`

### 工作量估算

| 模块 | 时间 |
|------|------|
| 后端 | 1-2 天 |
| 前端 | 1 天 |
| 测试 | 0.5 天 |
| **合计** | **2.5 天** |

---

## 1.2 记忆系统增强

### 短期记忆（当前对话）

- 保持现有 `chat_memory` 表
- 增加 `user_id` 字段，按用户隔离
- 容量: 最近 50 条消息

### 长期记忆（跨会话）

#### 方案 A: 简单版（当前可做）

- 增强 `user_profile` 表
- 支持存储记忆分类: `preference`(偏好), `knowledge`(知识), `context`(上下文)
- LLM 提取 + 手动编辑

#### 方案 B: 向量版（后续扩展）

- 接入向量数据库（Milvus / PgVector / Qdrant）
- 对历史对话进行 embedding
- RAG 检索相关记忆

### 记忆调用流程

```
用户消息 → 短期记忆（最近50条） + 长期记忆（RAG检索）
       → LLM 处理
       → 响应 + 提取新记忆（异步保存到长期记忆）
```

### 需要修改的代码

1. `ChatApplicationService` - 添加 userId 参数
2. `ChatMemoryRepository` - 按 user_id 查询
3. 新增 `LongTermMemoryService` - 长期记忆服务
4. 新增 `MemoryExtractor` - LLM 提取记忆

### 工作量估算

| 模块 | 时间 |
|------|------|
| 短期记忆改造 | 0.5 天 |
| 长期记忆基础版 | 1-2 天 |
| 向量版（可选） | 2-3 天 |
| **合计** | **1.5-4.5 天** |

---

## 1.3 前端改造

### 需要修改

1. 登录/注册页面
2. 登录状态保持（localStorage + Token）
3. 请求拦截器（自动带上 Token）
4. 登出功能

### 工作量估算

**1-2 天**

---

## Phase 1 总计

| 模块 | 工作量 |
|------|--------|
| 登录模块 | 2.5 天 |
| 记忆系统 | 1.5-4.5 天 |
| 前端改造 | 1-2 天 |
| **合计** | **5-9 天** |

### 里程碑

- [ ] 用户可以注册/登录
- [ ] 每个用户有独立的对话历史
- [ ] 长期记忆可以跨会话记住用户偏好
- [ ]（可选）向量检索历史对话

---

## 规划文件

- `task_plan.md` - 本文件，阶段规划和进度追踪
- `findings.md` - 研究发现和技术分析
- `progress.md` - 详细进度日志

---

# Skill 动态管理 + 向量化检索

## 需求分析

1. Skills 从数据库读取（已有基础）
2. 向量持久化存储到 MySQL 8.4
3. 动态扩展 skills（添加/编辑/删除/启用）
4. 向量化匹配能力（已有运行时计算，需持久化）
5. 维护页面（前端 CRUD）

## 技术方案

### 1. 数据库设计

- 修改 `skill_registry` 表，添加向量字段
- 使用 MySQL 8.4 的向量数据类型 `VECTOR(1536)` (DashScope embedding 维度)

### 2. 后端改动

#### 2.1 实体类 SkillRegistryPO
- 添加 `vector` 字段 (String 类型，存储 JSON 格式的向量)

#### 2.2 SkillRegistryMapper
- 添加向量相关查询方法

#### 2.3 新增 SkillService
- CRUD 操作
- 向量生成和存储
- 索引刷新逻辑

#### 2.4 新增 SkillController
- REST API: GET/POST/PUT/DELETE /api/skills
- POST /api/skills/refresh 手动刷新索引

#### 2.5 修改 SkillEmbeddingIndex
- 支持从数据库加载向量
- 添加刷新索引方法
- 支持动态添加/更新 skill 时重建索引

### 3. 前端改动
- 添加 skills 管理页面 (`skills.html`)
- Skill 列表展示
- 添加/编辑 Skill 表单
- 测试向量匹配功能

---

## 阶段划分

### Phase 1: 数据库和实体类
- [x] 修改 schema.sql，添加 skill_registry 表和向量字段
- [x] 修改 SkillRegistryPO，添加 vector 字段
- [x] 更新 SkillRegistryMapper

### Phase 2: 后端 CRUD API
- [x] 创建 SkillService 业务层
- [x] 创建 SkillController 接口层
- [x] 实现向量自动生成逻辑

### Phase 3: 向量存储和检索优化
- [x] 修改 SkillEmbeddingIndex，从数据库加载/存储向量
- [x] 实现动态刷新索引功能

### Phase 4: 前端页面
- [x] 创建 skills.html 维护页面
- [x] 实现 CRUD 交互
- [x] 测试向量匹配效果

## 进度追踪

| 日期 | 阶段 | 状态 | 备注 |
|------|------|------|------|
| 2026-03-07 | Phase 1 数据库设计 | completed | - |
| 2026-03-07 | Phase 2 后端 API | completed | - |
| 2026-03-07 | Phase 3 向量优化 | completed | - |
| 2026-03-07 | Phase 4 前端页面 | completed | - |