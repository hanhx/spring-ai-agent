package com.hhx.agi.infra.converter;

import com.hhx.agi.domain.chat.model.ChatMemory;
import com.hhx.agi.domain.chat.model.ConversationId;
import com.hhx.agi.domain.chat.model.Message;
import com.hhx.agi.infra.po.ChatMemoryPO;
import org.apache.commons.collections4.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ChatMemoryConverter {
    
    public static ChatMemory toDomain(List<ChatMemoryPO> pos) {
        if (CollectionUtils.isEmpty(pos)) {
            return null;
        }
        
        String conversationId = pos.get(0).getConversationId();
        List<Message> messages = pos.stream()
                .map(po -> new Message(po.getMessageType(), po.getContent(), po.getCreatedAt()))
                .collect(Collectors.toList());
        
        return new ChatMemory(new ConversationId(conversationId), messages);
    }
    
    public static List<ChatMemoryPO> toPOs(ChatMemory chatMemory) {
        if (chatMemory == null || chatMemory.isEmpty()) {
            return Collections.emptyList();
        }
        
        return chatMemory.getMessages().stream()
                .map(message -> {
                    ChatMemoryPO po = new ChatMemoryPO();
                    po.setConversationId(chatMemory.getConversationId().getValue());
                    po.setMessageType(message.getMessageType());
                    po.setContent(message.getContent());
                    po.setCreatedAt(message.getCreatedAt());
                    return po;
                })
                .collect(Collectors.toList());
    }
}
