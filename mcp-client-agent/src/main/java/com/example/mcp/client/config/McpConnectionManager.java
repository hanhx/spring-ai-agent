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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP 连接管理器 —— 支持多 Server，完全自管理 SSE Transport + McpSyncClient 生命周期
 *
 * 启动时自动连接所有配置的 Server，工具调用失败时自动重连，也支持手动重连。
 * 需要排除 Spring AI MCP 自动配置（见 application.yml）。
 */
@Component
public class McpConnectionManager implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);
    private static final long FAST_TIMEOUT_SECONDS = 5;

    @Value("#{${mcp.servers}}")
    private Map<String, String> servers;

    @Value("${spring.ai.mcp.client.request-timeout:30s}")
    private Duration requestTimeout;

    private final ConcurrentHashMap<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    @PostConstruct
    public void init() {
        servers.forEach(this::doConnect);
    }

    @PreDestroy
    public void destroy() {
        clients.keySet().forEach(this::doDisconnect);
    }

    private void doConnect(String name, String url) {
        synchronized (lock) {
            try {
                log.info("[MCP] 正在连接 Server [{}]: {}", name, url);
                HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(url).build();
                McpSyncClient client = McpClient.sync(transport)
                        .requestTimeout(requestTimeout)
                        .build();
                client.initialize();
                clients.put(name, client);
                log.info("[MCP] Server [{}] 连接成功", name);
            } catch (Exception e) {
                log.error("[MCP] Server [{}] 连接失败: {}", name, e.getMessage());
            }
        }
    }

    private void doDisconnect(String name) {
        synchronized (lock) {
            McpSyncClient client = clients.remove(name);
            if (client != null) {
                try { client.close(); } catch (Exception e) {
                    log.warn("[MCP] Server [{}] 关闭异常: {}", name, e.getMessage());
                }
            }
        }
    }

    /**
     * 重连指定 Server
     */
    public boolean reconnect(String name) {
        String url = servers.get(name);
        if (url == null) {
            log.warn("[MCP] 未知 Server: {}", name);
            return false;
        }
        log.info("[MCP] Server [{}] 开始重连...", name);
        doDisconnect(name);
        doConnect(name, url);
        boolean ok = clients.containsKey(name);
        log.info("[MCP] Server [{}] 重连{}", name, ok ? "成功" : "失败");
        return ok;
    }

    /**
     * 重连所有 Server
     */
    public void reconnectAll() {
        servers.forEach((name, url) -> reconnect(name));
    }

    /**
     * 获取所有 Server 的工具列表 —— 每个 Server 独立 5 秒快速超时检测，失败自动重连
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        List<ToolCallback> allTools = new ArrayList<>();

        for (Map.Entry<String, String> entry : servers.entrySet()) {
            String name = entry.getKey();
            try {
                ToolCallback[] tools = getToolCallbacksFromServer(name);
                if (tools != null) {
                    allTools.addAll(List.of(tools));
                    continue;
                }
            } catch (TimeoutException e) {
                log.warn("[MCP] Server [{}] 获取工具超时（{}s），连接已死", name, FAST_TIMEOUT_SECONDS);
            } catch (Exception e) {
                log.warn("[MCP] Server [{}] 获取工具失败: {}", name, e.getMessage());
            }

            // 重连后重试
            if (reconnect(name)) {
                try {
                    ToolCallback[] tools = getToolCallbacksFromServer(name);
                    if (tools != null) {
                        allTools.addAll(List.of(tools));
                    }
                } catch (Exception e) {
                    log.error("[MCP] Server [{}] 重连后仍无法获取工具: {}", name, e.getMessage());
                }
            }
        }

        if (allTools.isEmpty()) {
            throw new RuntimeException("所有 MCP Server 均不可用");
        }
        return allTools.toArray(new ToolCallback[0]);
    }

    private ToolCallback[] getToolCallbacksFromServer(String name) throws Exception {
        McpSyncClient client = clients.get(name);
        if (client == null) {
            return null;
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
