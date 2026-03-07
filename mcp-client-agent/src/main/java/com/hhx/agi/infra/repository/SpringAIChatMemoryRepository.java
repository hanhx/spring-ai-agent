package com.hhx.agi.infra.repository;

import com.hhx.agi.infra.config.UserContext;
import com.hhx.agi.infra.dao.ChatMemoryMapper;
import com.hhx.agi.infra.po.ChatMemoryPO;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Spring AI ChatMemory 存储实现 —— 用于 MessageWindowChatMemory
 */
@Repository
public class SpringAIChatMemoryRepository implements org.springframework.ai.chat.memory.ChatMemoryRepository {

    @Autowired
    private ChatMemoryMapper chatMemoryMapper;

    @Override
    public List<String> findConversationIds() {
        String userId = UserContext.getUserId();
        List<String> ids = chatMemoryMapper.selectAllConversationIdsByUserId(userId);
        return ids != null ? ids : List.of();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String userId = UserContext.getUserId();
        List<ChatMemoryPO> pos = chatMemoryMapper.selectByUserIdAndConversationId(userId, conversationId);
        if (CollectionUtils.isEmpty(pos)) {
            return List.of();
        }
        return toMessages(pos);
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        String userId = UserContext.getUserId();

        // 先删除旧消息
        chatMemoryMapper.deleteByUserIdAndConversationId(userId, conversationId);

        // 保存新消息
        if (CollectionUtils.isNotEmpty(messages)) {
            for (Message msg : messages) {
                ChatMemoryPO po = new ChatMemoryPO();
                po.setUserId(userId);
                po.setConversationId(conversationId);
                po.setMessageType(msg.getMessageType().name());
                po.setContent(msg.getText());
                po.setCreatedAt(java.time.LocalDateTime.now());
                chatMemoryMapper.insert(po);
            }
        }
    }

    @Override
    @Transactional
    public void deleteByConversationId(String conversationId) {
        String userId = UserContext.getUserId();
        chatMemoryMapper.deleteByUserIdAndConversationId(userId, conversationId);
    }

    private List<Message> toMessages(List<ChatMemoryPO> pos) {
        return pos.stream()
                .map(po -> {
                    String type = po.getMessageType();
                    String content = po.getContent();
                    switch (type) {
                        case "USER":
                            return (Message) new UserMessage(content);
                        case "ASSISTANT":
                            return (Message) new AssistantMessage(content);
                        case "SYSTEM":
                            return (Message) new SystemMessage(content);
                        default:
                            return (Message) new UserMessage(content);
                    }
                })
                .toList();
    }
}