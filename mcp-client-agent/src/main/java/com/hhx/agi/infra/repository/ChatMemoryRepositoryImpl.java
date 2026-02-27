package com.hhx.agi.infra.repository;

import com.hhx.agi.domain.model.ChatMemory;
import com.hhx.agi.domain.model.ConversationId;
import com.hhx.agi.domain.repository.ChatMemoryRepository;
import com.hhx.agi.infra.converter.ChatMemoryConverter;
import com.hhx.agi.infra.dao.ChatMemoryMapper;
import com.hhx.agi.infra.po.ChatMemoryPO;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class ChatMemoryRepositoryImpl implements ChatMemoryRepository {
    
    @Autowired
    private ChatMemoryMapper chatMemoryMapper;
    
    @Override
    public Optional<ChatMemory> findByConversationId(ConversationId conversationId) {
        List<ChatMemoryPO> pos = chatMemoryMapper.selectByConversationId(conversationId.getValue());
        if (CollectionUtils.isEmpty(pos)) {
            return Optional.of(new ChatMemory(conversationId));
        }
        return Optional.ofNullable(ChatMemoryConverter.toDomain(pos));
    }
    
    @Override
    @Transactional
    public void save(ChatMemory chatMemory) {
        chatMemoryMapper.deleteByConversationId(chatMemory.getConversationId().getValue());
        
        List<ChatMemoryPO> pos = ChatMemoryConverter.toPOs(chatMemory);
        if (CollectionUtils.isNotEmpty(pos)) {
            pos.forEach(chatMemoryMapper::insert);
        }
    }
    
    @Override
    @Transactional
    public void deleteByConversationId(ConversationId conversationId) {
        chatMemoryMapper.deleteByConversationId(conversationId.getValue());
    }
    
    @Override
    public List<ConversationId> findAllConversationIds() {
        List<String> ids = chatMemoryMapper.selectAllConversationIds();
        if (CollectionUtils.isEmpty(ids)) {
            return List.of();
        }
        return ids.stream()
                .map(ConversationId::new)
                .collect(Collectors.toList());
    }
}
