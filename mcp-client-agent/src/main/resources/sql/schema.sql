-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    nickname VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_username (username)
);

-- Skill 注册表（支持动态管理和向量检索）
CREATE TABLE IF NOT EXISTS skill_registry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    description TEXT,
    allowed_tools VARCHAR(512),
    prompt_body LONGTEXT,
    enabled BOOLEAN DEFAULT TRUE,
    priority INT DEFAULT 0,
    -- 向量字段：存储 float[] 的二进制数据
    -- 1024 维 * 4 字节/float = 4096 字节
    embedding VARBINARY(4096),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_skill_enabled (enabled),
    INDEX idx_skill_priority (priority)
);

-- 对话记忆（增加 user_id 字段支持多用户）
CREATE TABLE IF NOT EXISTS chat_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL DEFAULT 'anonymous',
    conversation_id VARCHAR(128) NOT NULL,
    message_type VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat_memory_user (user_id),
    INDEX idx_chat_memory_conversation (conversation_id)
);

-- 用户画像/长期记忆（增加分类字段）
CREATE TABLE IF NOT EXISTS user_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL DEFAULT 'preference', -- preference(偏好), knowledge(知识), context(上下文)
    profile_key VARCHAR(128) NOT NULL,
    profile_value TEXT,
    source_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_profile_key (user_id, profile_key),
    INDEX idx_user_profile_user (user_id),
    INDEX idx_user_profile_category (user_id, category)
);