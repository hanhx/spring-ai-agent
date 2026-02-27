package com.hhx.agi.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryDTO {
    private String conversationId;
    private List<ChatMessageDTO> messages;
    private Integer totalCount;
}
