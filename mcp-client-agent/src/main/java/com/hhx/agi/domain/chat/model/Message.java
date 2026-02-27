package com.hhx.agi.domain.chat.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class Message {
    private String messageType;
    private String content;
    private LocalDateTime createdAt;
    
    public Message(String messageType, String content) {
        this(messageType, content, LocalDateTime.now());
        validateMessage();
    }
    
    private void validateMessage() {
        if (StringUtils.isBlank(messageType)) {
            throw new IllegalArgumentException("Message type cannot be null or empty");
        }
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("Message content cannot be null or empty");
        }
    }
    
    public boolean isUserMessage() {
        return StringUtils.equalsIgnoreCase("user", messageType);
    }
    
    public boolean isAssistantMessage() {
        return StringUtils.equalsIgnoreCase("assistant", messageType);
    }
    
    public boolean isSystemMessage() {
        return StringUtils.equalsIgnoreCase("system", messageType);
    }
}
