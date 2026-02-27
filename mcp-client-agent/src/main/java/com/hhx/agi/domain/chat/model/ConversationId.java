package com.hhx.agi.domain.chat.model;

import lombok.Value;
import org.apache.commons.lang3.StringUtils;

@Value
public class ConversationId {
    String value;
    
    public ConversationId(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("ConversationId cannot be null or empty");
        }
        this.value = value;
    }
}
