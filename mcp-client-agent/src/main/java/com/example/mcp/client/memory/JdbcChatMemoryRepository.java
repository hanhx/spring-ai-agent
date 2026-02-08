package com.example.mcp.client.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * JDBC 기반 ChatMemoryRepository —— 使用 H2 内存数据库持久化对话记录
 *
 * 注意：保存时会覆盖同一 conversationId 的全部消息列表（与 InMemoryChatMemoryRepository 行为一致）
 */
@Repository
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public JdbcChatMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT conversation_id FROM chat_memory",
                String.class
        );
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        return jdbcTemplate.query(
                "SELECT message_type, content FROM chat_memory WHERE conversation_id = ? ORDER BY id",
                (rs, rowNum) -> toMessage(rs.getString("message_type"), rs.getString("content")),
                conversationId
        );
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");

        jdbcTemplate.update("DELETE FROM chat_memory WHERE conversation_id = ?", conversationId);
        if (messages.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO chat_memory (conversation_id, message_type, content) VALUES (?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Message message = messages.get(i);
                        ps.setString(1, conversationId);
                        ps.setString(2, toType(message));
                        ps.setString(3, toContent(message));
                    }

                    @Override
                    public int getBatchSize() {
                        return messages.size();
                    }
                }
        );
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        jdbcTemplate.update("DELETE FROM chat_memory WHERE conversation_id = ?", conversationId);
    }

    private Message toMessage(String type, String content) {
        String safeContent = content != null ? content : "";
        if (type == null || type.isBlank()) {
            return new AssistantMessage(safeContent);
        }
        MessageType messageType;
        try {
            messageType = MessageType.fromValue(type);
        } catch (IllegalArgumentException e) {
            messageType = MessageType.ASSISTANT;
        }

        return switch (messageType) {
            case USER -> new UserMessage(safeContent);
            case SYSTEM -> new SystemMessage(safeContent);
            case ASSISTANT, TOOL -> new AssistantMessage(safeContent);
        };
    }

    private String toType(Message message) {
        MessageType type = message.getMessageType();
        return type != null ? type.getValue() : MessageType.ASSISTANT.getValue();
    }

    private String toContent(Message message) {
        if (message instanceof AbstractMessage am) {
            return am.getText();
        }
        return message.toString();
    }
}
