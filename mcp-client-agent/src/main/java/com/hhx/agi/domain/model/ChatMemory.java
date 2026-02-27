package com.hhx.agi.domain.model;

import lombok.Getter;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
public class ChatMemory {
    private static final int MAX_MESSAGES = 50;
    
    private final ConversationId conversationId;
    private final List<Message> messages;
    
    public ChatMemory(ConversationId conversationId) {
        this.conversationId = conversationId;
        this.messages = new ArrayList<>();
    }
    
    public ChatMemory(ConversationId conversationId, List<Message> messages) {
        this.conversationId = conversationId;
        this.messages = new ArrayList<>(CollectionUtils.emptyIfNull(messages));
    }
    
    public void addMessage(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        
        if (messages.size() >= MAX_MESSAGES) {
            messages.remove(0);
        }
        messages.add(message);
    }
    
    public void clearHistory() {
        messages.clear();
    }
    
    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }
    
    public List<Message> getRecentMessages(int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        
        int size = messages.size();
        int start = Math.max(0, size - count);
        return Collections.unmodifiableList(messages.subList(start, size));
    }
    
    public boolean isEmpty() {
        return CollectionUtils.isEmpty(messages);
    }
    
    public int getMessageCount() {
        return messages.size();
    }
}
