package com.hhx.agi.application.assembler;

import com.hhx.agi.application.dto.ChatHistoryDTO;
import com.hhx.agi.application.dto.ChatMessageDTO;
import com.hhx.agi.domain.model.ChatMemory;
import com.hhx.agi.domain.model.Message;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ChatMemoryAssembler {
    
    ChatMessageDTO toDTO(Message message);
    
    List<ChatMessageDTO> toDTOList(List<Message> messages);
    
    @Mapping(source = "conversationId.value", target = "conversationId")
    @Mapping(source = "messages", target = "messages")
    @Mapping(expression = "java(chatMemory.getMessageCount())", target = "totalCount")
    ChatHistoryDTO toHistoryDTO(ChatMemory chatMemory);
}
