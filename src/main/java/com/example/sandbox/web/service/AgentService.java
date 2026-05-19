package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;

/**
 * Agent 编排服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface AgentService {

    /**
     * 创建新会话
     *
     * @return 新创建的会话
     */
    ConversationSession createSession();

    /**
     * 关闭会话
     *
     * @param sessionId 会话 ID
     */
    void closeSession(String sessionId);

    /**
     * 获取会话
     *
     * @param sessionId 会话 ID
     * @return 会话信息
     */
    ConversationSession getSession(String sessionId);

    /**
     * 对话
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return 助手响应
     */
    ChatMessage chat(String sessionId, String userMessage);
}
