package com.hhx.agi.infra.config;

import org.springframework.stereotype.Component;

/**
 * 用户上下文工具类
 * 注意：WebFlux 中对同步操作（JDBC）可用
 */
@Component
public class UserContext {

    private static final String DEFAULT_USER_ID = "anonymous";

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
}