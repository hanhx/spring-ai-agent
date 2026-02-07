package com.example.mcp.client.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP 连接管理器 —— 完全自管理 SSE Transport + McpSyncClient 生命周期
 *
 * 启动时自动连接，工具调用失败时自动重连，也支持手动重连。
 * 需要排除 Spring AI MCP 自动配置（见 McpClientAgentApplication）。
 */
@Component
public class McpConnectionManager implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);
    private static final long FAST_TIMEOUT_SECONDS = 5;

    @Value("${spring.ai.mcp.client.sse.connections.business-server.url:http://localhost:8081}")
    private String serverUrl;

    @Value("${spring.ai.mcp.client.request-timeout:30s}")
    private Duration requestTimeout;

    private volatile McpSyncClient mcpClient;
    private final Object lock = new Object();

    @PostConstruct
    public void init() {
        doConnect();
    }

    @PreDestroy
    public void destroy() {
        doDisconnect();
    }

    private void doConnect() {
        synchronized (lock) {
            try {
                log.info("[MCP] 正在连接 MCP Server: {}", serverUrl);
                HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(serverUrl).build();
                this.mcpClient = McpClient.sync(transport)
                        .requestTimeout(requestTimeout)
                        .build();
                mcpClient.initialize();
                log.info("[MCP] 连接成功");
            } catch (Exception e) {
                log.error("[MCP] 连接失败: {}", e.getMessage());
                this.mcpClient = null;
            }
        }
    }

    private void doDisconnect() {
        synchronized (lock) {
            if (mcpClient != null) {
                try { mcpClient.close(); } catch (Exception e) {
                    log.warn("[MCP] 关闭异常: {}", e.getMessage());
                }
                mcpClient = null;
            }
        }
    }

    /**
     * 重连：关闭旧连接 → 建立新连接
     */
    public boolean reconnect() {
        log.info("[MCP] 开始重连...");
        doDisconnect();
        doConnect();
        boolean ok = mcpClient != null;
        log.info("[MCP] 重连{}", ok ? "成功" : "失败");
        return ok;
    }

    /**
     * 获取工具列表 —— 用 5 秒快速超时检测死连接，失败立即重连
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        try {
            return getToolCallbacksWithTimeout();
        } catch (TimeoutException e) {
            log.warn("[MCP] 获取工具超时（{}s），连接已死，立即重连", FAST_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("[MCP] 获取工具失败，尝试重连: {}", e.getMessage());
        }

        if (reconnect()) {
            try {
                return getToolCallbacksWithTimeout();
            } catch (Exception e) {
                throw new RuntimeException("MCP Server 重连后仍无法获取工具", e);
            }
        }
        throw new RuntimeException("MCP Server 不可用，重连失败");
    }

    private ToolCallback[] getToolCallbacksWithTimeout() throws Exception {
        McpSyncClient client = mcpClient;
        if (client == null) {
            throw new RuntimeException("MCP Client 未连接");
        }
        try {
            return CompletableFuture.supplyAsync(
                    () -> new SyncMcpToolCallbackProvider(List.of(client)).getToolCallbacks()
            ).get(FAST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }
}
