package com.example.sandbox.web.model.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /**
     * 文件附件列表（agent 回复时附带文件）
     */
    private final List<FileAttachment> files;

    private ChatMessage(String role, String content, Long timestamp, List<FileAttachment> files) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
        this.files = files != null ? List.copyOf(files) : List.of();
    }

    /**
     * 创建用户消息
     */
    public static ChatMessage userMessage(String content) {
        return new ChatMessage("user", content, Instant.now().toEpochMilli(), null);
    }

    /**
     * 创建助手消息
     */
    public static ChatMessage assistantMessage(String content) {
        return new ChatMessage("assistant", content, Instant.now().toEpochMilli(), null);
    }

    /**
     * 创建助手消息（带文件附件）
     */
    public static ChatMessage assistantMessage(String content, List<FileAttachment> files) {
        return new ChatMessage("assistant", content, Instant.now().toEpochMilli(), files);
    }

    /**
     * 创建系统消息
     */
    public static ChatMessage systemMessage(String content) {
        return new ChatMessage("system", content, Instant.now().toEpochMilli(), null);
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

    public List<FileAttachment> getFiles() {
        return files;
    }

    public boolean hasFiles() {
        return !files.isEmpty();
    }

    @Override
    public String toString() {
        return "ChatMessage{" +
                "role='" + role + '\'' +
                ", content='" + content + '\'' +
                ", timestamp=" + timestamp +
                ", files=" + files.size() +
                '}';
    }

    /**
     * 文件附件
     */
    public record FileAttachment(
            /** 文件名 */
            String filename,
            /** 下载路径 */
            String url,
            /** 文件大小（字节） */
            long size,
            /** MIME 类型 */
            String mimeType
    ) {}
}
