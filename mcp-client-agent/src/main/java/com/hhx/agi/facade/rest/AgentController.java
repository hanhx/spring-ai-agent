package com.hhx.agi.facade.rest;

import com.hhx.agi.infra.config.McpConnectionManager;
import com.hhx.agi.infra.config.UserContext;
import com.hhx.agi.application.agent.PlanActionEvent;
import com.hhx.agi.application.agent.SkillEmbeddingIndex;
import com.hhx.agi.application.agent.SkillResponse;
import com.hhx.agi.application.agent.SkillRouter;
import com.hhx.agi.application.service.ChatApplicationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Agent REST API 控制器
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final String DEFAULT_USER_ID = "anonymous";

    private final SkillRouter skillRouter;
    private final McpConnectionManager mcpManager;
    private final SkillEmbeddingIndex embeddingIndex;
    private final ChatApplicationService chatApplicationService;

    @Autowired
    public AgentController(SkillRouter skillRouter, McpConnectionManager mcpManager,
                           SkillEmbeddingIndex embeddingIndex, ChatApplicationService chatApplicationService) {
        this.skillRouter = skillRouter;
        this.mcpManager = mcpManager;
        this.embeddingIndex = embeddingIndex;
        this.chatApplicationService = chatApplicationService;
    }

    /**
     * 智能对话 —— LLM + MCP 工具自动决策
     */
    @PostMapping("/chat")
    public Mono<ResponseEntity<ChatResponse>> chat(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = DEFAULT_USER_ID) String userId,
            @Valid @RequestBody ChatRequest request) {
        log.info("Chat request: userId={}, conversationId={}", userId, request.conversationId());
        return Mono.fromCallable(() -> {
            UserContext.setUserId(userId);
            try {
                SkillResponse response = skillRouter.route(request.conversationId(), request.message());
                return ResponseEntity.ok(ChatResponse.from(request.conversationId(), response));
            } finally {
                UserContext.clear();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 流式对话 —— Plan & Action 模式，Flux SSE 逐个推送事件（实时）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<PlanActionEvent> chatStream(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = DEFAULT_USER_ID) String userId,
            @Valid @RequestBody ChatRequest request) {
        log.info("Stream chat request: userId={}, message={}", userId, request.message());
        UserContext.setUserId(userId);
        return skillRouter.streamRoute(request.conversationId(), request.message(), request.model(), userId)
                .contextWrite(ctx -> ctx.put(UserContext.USER_ID_KEY, userId))
                .doFinally(signal -> UserContext.clear());
    }

    /**
     * 列出 MCP Server 提供的所有可用工具
     */
    @GetMapping("/tools")
    public Mono<ResponseEntity<List<Map<String, String>>>> listTools() {
        return Mono.fromCallable(() -> {
            ToolCallback[] callbacks = mcpManager.getToolCallbacks();
            List<Map<String, String>> tools = Arrays.stream(callbacks)
                    .map(cb -> Map.of(
                            "name", cb.getToolDefinition().name(),
                            "description", cb.getToolDefinition().description()
                    ))
                    .toList();
            return ResponseEntity.ok(tools);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 列出所有已注册的 Skills
     */
    @GetMapping("/skills")
    public Mono<ResponseEntity<Map<String, String>>> listSkills() {
        return Mono.fromCallable(() -> ResponseEntity.ok(skillRouter.getSkillInfo()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> health() {
        return Mono.fromCallable(() -> {
            ToolCallback[] callbacks = mcpManager.getToolCallbacks();
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "skills", skillRouter.getSkillInfo(),
                    "mcpToolsCount", callbacks.length,
                    "mcpTools", Arrays.stream(callbacks)
                            .map(cb -> cb.getToolDefinition().name())
                            .toList()
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 手动重连 MCP Server（Server 重启后调用）
     * 可选参数 server：指定重连某个 Server，不传则重连所有
     */
    @PostMapping("/reconnect")
    public Mono<ResponseEntity<Map<String, Object>>> reconnect(
            @RequestParam(required = false) String server) {
        return Mono.fromCallable(() -> {
            if (server != null && !server.isBlank()) {
                boolean ok = mcpManager.reconnect(server);
                return ResponseEntity.ok(Map.<String, Object>of(
                        "success", ok,
                        "message", ok ? "Server [" + server + "] 重连成功" : "Server [" + server + "] 重连失败"
                ));
            } else {
                mcpManager.reconnectAll();
                return ResponseEntity.ok(Map.<String, Object>of(
                        "success", true,
                        "message", "所有 MCP Server 已重连"
                ));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 测试 Embedding 是否生效
     * 返回 Embedding 索引状态和测试结果
     */
    @GetMapping("/embedding/test")
    public Mono<ResponseEntity<Map<String, Object>>> testEmbedding() {
        return Mono.fromCallable(() -> {
            boolean indexReady = embeddingIndex.isIndexReady();
            return ResponseEntity.ok(Map.<String, Object>of(
                    "indexReady", indexReady,
                    "status", indexReady ? "Embedding 索引已就绪，使用百炼向量检索" : "Embedding 未启用或失败，使用词法检索",
                    "skills", skillRouter.getSkillInfo()
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 测试 Embedding 相似度检索
     */
    @GetMapping("/embedding/search")
    public Mono<ResponseEntity<Map<String, Object>>> testEmbeddingSearch(
            @RequestParam String query) {
        return Mono.fromCallable(() -> {
            var topSkills = embeddingIndex.retrieveTopK(query);
            var scores = embeddingIndex.retrieveTopKWithScores(query, 10);
            return ResponseEntity.ok(Map.<String, Object>of(
                    "query", query,
                    "indexReady", embeddingIndex.isIndexReady(),
                    "topSkills", topSkills.stream()
                            .map(s -> Map.of("name", s.name(), "description", s.description()))
                            .toList(),
                    "allScores", scores.stream()
                            .map(ss -> Map.of(
                                    "name", ss.skill().name(),
                                    "score", ss.score(),
                                    "method", ss.method()
                            ))
                            .toList()
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ==================== 会话历史管理 ====================

    /**
     * 获取当前用户的所有会话 ID 列表
     */
    @GetMapping("/conversations")
    public Mono<ResponseEntity<List<String>>> getConversations(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = DEFAULT_USER_ID) String userId) {
        return Mono.fromCallable(() -> {
            UserContext.setUserId(userId);
            try {
                List<String> conversationIds = chatApplicationService.getAllConversationIds();
                return ResponseEntity.ok(conversationIds);
            } finally {
                UserContext.clear();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取指定会话的历史消息
     */
    @GetMapping("/conversations/{conversationId}/history")
    public Mono<ResponseEntity<Map<String, Object>>> getConversationHistory(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = DEFAULT_USER_ID) String userId,
            @PathVariable String conversationId) {
        return Mono.fromCallable(() -> {
            UserContext.setUserId(userId);
            try {
                var history = chatApplicationService.getConversationHistory(conversationId);
                return ResponseEntity.<Map<String, Object>>ok(Map.of(
                        "conversationId", conversationId,
                        "messages", history.getMessages()
                ));
            } finally {
                UserContext.clear();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 删除指定会话的历史记录
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteConversation(
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = DEFAULT_USER_ID) String userId,
            @PathVariable String conversationId) {
        return Mono.fromCallable(() -> {
            UserContext.setUserId(userId);
            try {
                chatApplicationService.clearHistory(conversationId);
                return ResponseEntity.ok(Map.<String, Object>of(
                        "success", true,
                        "message", "会话已删除"
                ));
            } finally {
                UserContext.clear();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
