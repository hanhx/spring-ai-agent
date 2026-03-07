# Skill 动态管理功能包

## 功能概述

实现 Skills 的动态管理，支持：
- 从数据库读取 Skills
- 向量持久化存储到 MySQL 8.4
- 动态 CRUD 操作
- 向量化语义匹配
- Web 管理界面

## 目录结构

```
planning/skill-management/
├── plan.md          # 本文件 - 实现计划和进度
├── findings.md      # 技术发现和决策记录
└── progress.md      # 测试结果和问题日志
```

## 实现状态

**状态: ✅ 已完成**

## 相关文件

| 文件 | 操作 |
|------|------|
| `mcp-client-agent/src/main/resources/schema.sql` | 添加 `skill_registry` 表 |
| `mcp-client-agent/src/main/java/.../infra/po/SkillRegistryPO.java` | 添加 `vector` 字段 |
| `mcp-client-agent/src/main/java/.../infra/dao/SkillRegistryMapper.java` | 添加查询方法 |
| `mcp-client-agent/src/main/resources/mapper/SkillRegistryMapper.xml` | 添加 SQL |
| `mcp-client-agent/src/main/java/.../application/service/SkillService.java` | 新建 |
| `mcp-client-agent/src/main/java/.../facade/rest/SkillController.java` | 新建 |
| `mcp-client-agent/src/main/java/.../application/agent/SkillLoader.java` | 添加 `reload()` |
| `mcp-client-agent/src/main/java/.../application/agent/SkillEmbeddingIndex.java` | 支持数据库向量 |
| `mcp-client-agent/src/main/resources/static/skills.html` | 新建管理页面 |