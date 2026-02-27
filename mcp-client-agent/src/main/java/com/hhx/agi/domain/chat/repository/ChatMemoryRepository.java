package com.hhx.agi.domain.chat.repository;

import com.hhx.agi.domain.chat.model.ChatMemory;
import com.hhx.agi.domain.chat.model.ConversationId;

import java.util.List;
import java.util.Optional;

public interface ChatMemoryRepository {
    
    Optional<ChatMemory> findByConversationId(ConversationId conversationId);
    
    void save(ChatMemory chatMemory);
    
    void deleteByConversationId(ConversationId conversationId);
    
    List<ConversationId> findAllConversationIds();
}
