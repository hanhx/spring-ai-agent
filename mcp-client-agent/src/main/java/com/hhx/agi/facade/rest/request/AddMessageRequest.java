package com.hhx.agi.facade.rest.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddMessageRequest {
    
    @NotBlank(message = "conversationId cannot be empty")
    private String conversationId;
    
    @NotBlank(message = "messageType cannot be empty")
    private String messageType;
    
    @NotBlank(message = "content cannot be empty")
    private String content;
}
