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

## 文件改动清单

| 文件 | 操作 |
|------|------|
| `schema.sql` | 添加 `skill_registry` 表 |
| `SkillRegistryPO.java` | 添加 `vector` 字段 |
| `SkillRegistryMapper.java` | 添加 `selectEnabledWithVector()` |
| `SkillRegistryMapper.xml` | 添加 SQL |
| `SkillService.java` | 新建 |
| `SkillController.java` | 新建 |
| `SkillLoader.java` | 添加 `reload()` 方法 |
| `SkillEmbeddingIndex.java` | 支持从数据库加载向量 |
| `skills.html` | 新建前端管理页面 |