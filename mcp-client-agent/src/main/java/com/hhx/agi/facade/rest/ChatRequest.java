package com.hhx.agi.facade.rest;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "conversationId 不能为空") String conversationId,
        @NotBlank(message = "消息不能为空") String message,
        String model,  // 可选：前端指定模型，如 MiniMax-M2.5, qwen-plus 等
        String userId  // 用户ID，从JWT解析或前端传递
) {
}
