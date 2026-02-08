package com.example.mcp.client.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatMemory 配置 —— 基于 Spring AI 的多轮对话记忆
 *
 * 使用 MessageWindowChatMemory：
 * - 按 conversationId 隔离对话
 * - 滑动窗口保留最近 20 条消息（超出自动淘汰旧消息，SystemMessage 优先保留）
 * - 使用 JDBC 存储（当前为 H2 内存库，可替换为持久化数据库）
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(50)
                .build();
    }
}
