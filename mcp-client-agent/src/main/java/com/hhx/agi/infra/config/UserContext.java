package com.hhx.agi.infra.config;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 用户上下文工具类
 * 支持 ThreadLocal（同步调用）+ Reactor Context（响应式调用）
 */
@Component
public class UserContext {

    private static final String DEFAULT_USER_ID = "anonymous";
    public static final String USER_ID_KEY = "userId";

    private static final ThreadLocal<String> userIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> usernameHolder = new ThreadLocal<>();

    public static void setUserId(String userId) {
        userIdHolder.set(userId != null ? userId : DEFAULT_USER_ID);
    }

    public static String getUserId() {
        String userId = userIdHolder.get();
        return userId != null ? userId : DEFAULT_USER_ID;
    }

    public static void setUsername(String username) {
        usernameHolder.set(username);
    }

    public static String getUsername() {
        return usernameHolder.get();
    }

    public static void clear() {
        userIdHolder.remove();
        usernameHolder.remove();
    }

    /**
     * 从 ServerWebExchange 设置用户上下文（供 Filter 调用）
     */
    public static void setUserContext(String userId, String username) {
        setUserId(userId);
        setUsername(username);
    }

    // ==================== Reactor Context 支持 ====================

    /**
     * 从 Reactor Context 读取 userId（响应式调用）
     * 如果 Context 中没有，回退到 ThreadLocal
     */
    public static Mono<String> getUserIdReactive() {
        return Mono.deferContextual(ctx -> {
            String userId = ctx.getOrDefault(USER_ID_KEY, (String) null);
            if (userId != null) {
                return Mono.just(userId);
            }
            // 回退到 ThreadLocal
            return Mono.just(getUserId());
        });
    }

    /**
     * 同步获取 userId，优先从 Reactor Context，否则从 ThreadLocal
     * 注意：此方法只能在 Reactor 链中调用，且需要通过 deferContextual 捕获 Context
     */
    public static String getUserIdFromContext(reactor.util.context.ContextView ctx) {
        String userId = ctx.getOrDefault(USER_ID_KEY, (String) null);
        if (userId != null) {
            return userId;
        }
        return getUserId();
    }
}