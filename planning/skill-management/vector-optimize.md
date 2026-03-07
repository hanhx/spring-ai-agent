# 向量检索优化：数据库 Top-N 查询

## 问题分析

**当前方案（问题）：**
1. 启动时加载所有 skills 到内存
2. 向量全部存储在内存的 `Map<String, float[]>` 中
3. 用户查询时，遍历所有向量计算相似度
4. 内存占用高，不支持大规模数据

**目标方案：**
1. 向量存储在数据库中
2. 用户输入 → 生成查询向量
3. 数据库层面计算相似度，直接返回 Top-N
4. 内存占用低，支持大规模数据

## MySQL 8.4 向量支持

MySQL 8.4 支持 VECTOR 类型和向量函数：

```sql
-- 创建表
CREATE TABLE skill_registry (
    ...
    embedding VECTOR(1024)  -- 1024 维向量
);

-- 查询 Top-N（使用余弦相似度或欧几里得距离）
SELECT id, name, description,
       1 - (DOT(embedding, @query_vector) /
            (SQRT(DOT(embedding, embedding)) * SQRT(DOT(@query_vector, @query_vector)))) AS cosine_sim
FROM skill_registry
WHERE enabled = TRUE
ORDER BY cosine_sim DESC
LIMIT 10;
```

## 实现计划

### Phase 1: 数据库层改造
- [ ] 修改 vector 字段类型为 VECTOR(1024)
- [ ] 创建自定义 TypeHandler 处理 Java float[] ↔ MySQL VECTOR
- [ ] 添加向量相似度查询的 Mapper 方法

### Phase 2: 服务层改造
- [ ] 修改 SkillEmbeddingIndex，移除内存缓存
- [ ] 实现数据库向量查询 Top-N
- [ ] 用户输入时生成查询向量，数据库计算相似度

### Phase 3: 测试验证
- [ ] 测试向量存储
- [ ] 测试 Top-N 查询
- [ ] 性能对比

## 文件改动清单

| 文件 | 改动 |
|------|------|
| `schema.sql` | vector 字段类型改为 VECTOR(1024) |
| `VectorTypeHandler.java` | 新建，处理 float[] ↔ VECTOR 转换 |
| `SkillRegistryMapper.xml` | 添加向量相似度查询 SQL |
| `SkillEmbeddingIndex.java` | 移除内存缓存，改为数据库查询 |