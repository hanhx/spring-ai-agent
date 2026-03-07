# 进度日志

## 2026-03-07

### 会话 1: 项目规划启动

**开始时间**: 2026-03-07

**任务**: 规划 Windsurf Web 版 AI Agent 项目

**操作**:
- [x] 创建规划文件目录 `/planning/`
- [x] 创建 task_plan.md
- [x] 创建 findings.md
- [x] 创建 progress.md
- [x] 分析现有代码结构

**发现**:
1. 项目已有基础对话功能 (`ChatApplicationService`)
2. 短期记忆使用 MySQL `chat_memory` 表，按 conversation_id 分组
3. 长期记忆只有用户画像 (`UserProfile`)，无语义检索
4. 目前无登录功能，通过 header `X-User-Id` 可选传递用户 ID
5. 默认用户: "anonymous"

---

### 会话 2: Phase 1 实现

**开始时间**: 2026-03-07

**任务**: 实现登录 + 短期/长期记忆

**完成内容**:

#### 1. 登录模块
- [x] 添加 Spring Security + JWT 依赖 (`pom.xml`)
- [x] 创建 `users` 数据库表 (`schema.sql`)
- [x] 创建 `UserPO` 实体 + `UserMapper`
- [x] 创建 `AuthService` 服务 (注册/登录)
- [x] 创建 `AuthController` (REST API)
- [x] 创建 `JwtUtils` 工具类
- [x] 创建 `SecurityConfig` 安全配置
- [x] 创建 `JwtAuthenticationFilter` JWT 过滤器
- [x] 创建 `UserContext` 用户上下文工具类

#### 2. 短期记忆改造
- [x] `chat_memory` 表增加 `user_id` 字段
- [x] `ChatMemoryPO` 增加 `userId` 字段
- [x] `ChatMemoryMapper` 更新查询方法（按 user_id）
- [x] `ChatMemoryRepositoryImpl` 使用 UserContext

#### 3. 长期记忆增强
- [x] `user_profile` 表增加 `category` 字段
- [x] `UserProfilePO` 增加 `category` 字段

#### 4. 前端改造
- [x] 创建 `login.html` 登录/注册页面
- [x] 改造 `index.html`:
  - 登录检查，未登录跳转 login.html
  - 显示当前用户昵称
  - 登出功能
  - API 请求带上 JWT Token

**创建的文件**:
- `AuthService.java`, `AuthController.java`, `JwtUtils.java`
- `SecurityConfig.java`, `JwtAuthenticationFilter.java`, `UserContext.java`
- `UserPO.java`, `UserMapper.java`, `RegisterRequest.java`, `LoginRequest.java`, `AuthResponse.java`
- `login.html`

**修改的文件**:
- `pom.xml` - 添加 Security + JWT 依赖
- `schema.sql` - users 表 + user_id + category
- `ChatMemoryPO.java`, `ChatMemoryMapper.java`, `ChatMemoryMapper.xml`
- `ChatMemoryRepositoryImpl.java`, `UserProfilePO.java`, `UserProfileMapper.java`
- `index.html` - 登录检查 + Token

**下一步**:
- 编译测试
- （可选）长期记忆向量版