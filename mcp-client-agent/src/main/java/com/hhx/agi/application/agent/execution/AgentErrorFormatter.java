package com.hhx.agi.application.agent.execution;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

public final class AgentErrorFormatter {

    private AgentErrorFormatter() {
    }

    public static String userFacing(Throwable throwable) {
        if (throwable == null) {
            return "执行失败：未知错误。";
        }

        Throwable matched = findCause(throwable);
        String text = exceptionText(matched);
        String lower = text.toLowerCase();

        if (lower.contains("readtimeoutexception") || matched instanceof SocketTimeoutException) {
            return "请求超时：模型或工具服务长时间未返回，请稍后重试。";
        }
        if (lower.contains("connecttimeoutexception") || matched instanceof TimeoutException) {
            return "连接超时：模型或工具服务暂时不可达，请稍后重试。";
        }
        if (matched instanceof ConnectException || lower.contains("connection refused")) {
            return "连接失败：模型或工具服务当前不可用。";
        }
        if (matched instanceof UnknownHostException || lower.contains("unknownhost")) {
            return "连接失败：无法解析模型或工具服务地址。";
        }

        String message = firstMessage(throwable);
        if (message != null) {
            return message;
        }
        return "执行失败：" + matched.getClass().getSimpleName();
    }

    public static String technical(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        String message = firstMessage(throwable);
        if (message == null) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private static Throwable findCause(Throwable throwable) {
        Throwable current = throwable;
        Throwable best = throwable;
        int depth = 0;
        while (current != null && depth < 12) {
            String text = exceptionText(current).toLowerCase();
            if (text.contains("readtimeoutexception")
                    || text.contains("connecttimeoutexception")
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException) {
                return current;
            }
            best = current;
            current = current.getCause();
            depth++;
        }
        return best;
    }

    private static String exceptionText(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getName();
        }
        return throwable.getClass().getName() + " " + message;
    }

    private static String firstMessage(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 12) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }
}
