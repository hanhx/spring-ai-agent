-- 为 skill_registry 表添加向量字段
-- 请用数据库客户端执行此 SQL

ALTER TABLE skill_registry ADD COLUMN vector VARCHAR(8192);

-- 如果表不存在，可以创建（可选）
-- CREATE TABLE IF NOT EXISTS skill_registry (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     name VARCHAR(128) NOT NULL UNIQUE,
--     description TEXT,
--     allowed_tools VARCHAR(512),
--     prompt_body LONGTEXT,
--     enabled BOOLEAN DEFAULT TRUE,
--     priority INT DEFAULT 0,
--     vector VARCHAR(8192),
--     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
-- );