package com.example.sandbox.web.model.entity;

import java.time.Instant;

/**
 * 对话消息
 *
 * @author example
 * @date 2026/05/14
 */
public class ChatMessage {

    /**
     * 消息角色
     */
    private final String role;

    /**
     * 消息内容
     */
    private final String content;

    /**
     * 时间戳（毫秒）
     */
    private final Long timestamp;

    private ChatMessage(String role, String content, Long timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    /**
     * 创建用户消息
     */
    public static ChatMessage userMessage(String content) {
        return new ChatMessage("user", content, Instant.now().toEpochMilli());
    }

    /**
     * 创建助手消息
     */
    public static ChatMessage assistantMessage(String content) {
        return new ChatMessage("assistant", content, Instant.now().toEpochMilli());
    }

    /**
     * 创建系统消息
     */
    public static ChatMessage systemMessage(String content) {
        return new ChatMessage("system", content, Instant.now().toEpochMilli());
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "ChatMessage{" +
                "role='" + role + '\'' +
                ", content='" + content + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
