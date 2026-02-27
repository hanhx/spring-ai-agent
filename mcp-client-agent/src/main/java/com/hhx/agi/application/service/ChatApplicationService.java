package com.hhx.agi.application.service;

import com.hhx.agi.application.assembler.ChatMemoryAssembler;
import com.hhx.agi.application.dto.ChatHistoryDTO;
import com.hhx.agi.application.dto.ChatMessageDTO;
import com.hhx.agi.domain.chat.model.ChatMemory;
import com.hhx.agi.domain.chat.model.ConversationId;
import com.hhx.agi.domain.chat.model.Message;
import com.hhx.agi.domain.chat.repository.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChatApplicationService {
    
    @Autowired
    private ChatMemoryRepository chatMemoryRepository;
    
    @Autowired
    private ChatMemoryAssembler assembler;
    
    public ChatHistoryDTO getConversationHistory(String conversationId) {
        ConversationId id = new ConversationId(conversationId);
        ChatMemory chatMemory = chatMemoryRepository.findByConversationId(id)
                .orElseGet(() -> new ChatMemory(id));
        return assembler.toHistoryDTO(chatMemory);
    }
    
    public List<ChatMessageDTO> getRecentMessages(String conversationId, int count) {
        ConversationId id = new ConversationId(conversationId);
        ChatMemory chatMemory = chatMemoryRepository.findByConversationId(id)
                .orElseGet(() -> new ChatMemory(id));
        return assembler.toDTOList(chatMemory.getRecentMessages(count));
    }
    
    @Transactional
    public void addMessage(String conversationId, String messageType, String content) {
        ConversationId id = new ConversationId(conversationId);
        ChatMemory chatMemory = chatMemoryRepository.findByConversationId(id)
                .orElseGet(() -> new ChatMemory(id));
        
        Message message = new Message(messageType, content);
        chatMemory.addMessage(message);
        
        chatMemoryRepository.save(chatMemory);
    }
    
    @Transactional
    public void clearHistory(String conversationId) {
        ConversationId id = new ConversationId(conversationId);
        chatMemoryRepository.deleteByConversationId(id);
    }
    
    public List<String> getAllConversationIds() {
        return chatMemoryRepository.findAllConversationIds().stream()
                .map(ConversationId::getValue)
                .toList();
    }
}
