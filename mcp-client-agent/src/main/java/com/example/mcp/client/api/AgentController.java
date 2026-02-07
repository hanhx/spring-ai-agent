package com.example.mcp.client.api;

import com.example.mcp.client.config.McpConnectionManager;
import com.example.mcp.client.skill.PlanActionEvent;
import com.example.mcp.client.skill.SkillResponse;
import com.example.mcp.client.skill.SkillRouter;
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

    private final SkillRouter skillRouter;
    private final McpConnectionManager mcpManager;

    @Autowired
    public AgentController(SkillRouter skillRouter, McpConnectionManager mcpManager) {
        this.skillRouter = skillRouter;
        this.mcpManager = mcpManager;
    }

    /**
     * 智能对话 —— LLM + MCP 工具自动决策
     */
    @PostMapping("/chat")
    public Mono<ResponseEntity<ChatResponse>> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Chat request: conversationId={}, message={}", request.conversationId(), request.message());
        return Mono.fromCallable(() -> {
            SkillResponse response = skillRouter.route(request.conversationId(), request.message());
            return ResponseEntity.ok(ChatResponse.from(request.conversationId(), response));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 流式对话 —— Plan & Action 模式，Flux SSE 逐个推送事件（实时）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<PlanActionEvent> chatStream(@Valid @RequestBody ChatRequest request) {
        log.info("Stream chat request: message={}", request.message());
        return skillRouter.streamRoute(request.conversationId(), request.message());
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
}
