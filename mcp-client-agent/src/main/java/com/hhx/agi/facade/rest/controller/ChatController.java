package com.hhx.agi.facade.rest.controller;

import com.hhx.agi.application.dto.ChatHistoryDTO;
import com.hhx.agi.application.dto.ChatMessageDTO;
import com.hhx.agi.application.service.ChatApplicationService;
import com.hhx.agi.facade.rest.request.AddMessageRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    
    @Autowired
    private ChatApplicationService chatApplicationService;
    
    @GetMapping("/history/{conversationId}")
    public ChatHistoryDTO getHistory(@PathVariable String conversationId) {
        return chatApplicationService.getConversationHistory(conversationId);
    }
    
    @GetMapping("/recent/{conversationId}")
    public List<ChatMessageDTO> getRecentMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "10") int count) {
        return chatApplicationService.getRecentMessages(conversationId, count);
    }
    
    @PostMapping("/message")
    public void addMessage(@RequestBody @Valid AddMessageRequest request) {
        chatApplicationService.addMessage(
                request.getConversationId(),
                request.getMessageType(),
                request.getContent()
        );
    }
    
    @DeleteMapping("/history/{conversationId}")
    public void clearHistory(@PathVariable String conversationId) {
        chatApplicationService.clearHistory(conversationId);
    }
    
    @GetMapping("/conversations")
    public List<String> getAllConversations() {
        return chatApplicationService.getAllConversationIds();
    }
}
