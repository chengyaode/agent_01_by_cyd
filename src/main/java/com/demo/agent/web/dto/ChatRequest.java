package com.demo.agent.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 对话请求体。
 *
 * @param message   用户输入(必填)
 * @param sessionId 会话 ID,不传则服务端生成并在响应中返回;同一 sessionId 延续记忆
 * @param userId    用户标识,不传默认 "api";用于会话归属隔离
 */
public record ChatRequest(
        @NotBlank(message = "message 不能为空") String message,
        String sessionId,
        String userId) {}
