package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 编排服务实现（使用 ReAct 模式）
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private final ConversationServiceImpl conversationService;
    private final LlmService llmService;
    private final List<Tool> tools;

    public AgentServiceImpl(ConversationServiceImpl conversationService,
                           LlmService llmService,
                           List<Tool> tools) {
        this.conversationService = conversationService;
        this.llmService = llmService;
        this.tools = tools;
    }

    @Override
    public ConversationSession createSession() {
        ConversationSession session = conversationService.createSession();
        log.info("Created session: {}", session.getSessionId());
        return session;
    }

    @Override
    public void closeSession(String sessionId) {
        conversationService.deleteSession(sessionId);
        log.info("Closed session: {}", sessionId);
    }

    @Override
    public ConversationSession getSession(String sessionId) {
        return conversationService.getSession(sessionId);
    }

    @Override
    public ChatMessage chat(String sessionId, String userMessage) {
        // 1. 存储用户消息
        conversationService.addUserMessage(sessionId, userMessage);
        log.info("【用户输入】会话: {} 内容: {}", sessionId, userMessage);

        // 2. 构建系统提示（包含启用的技能内容）
        String systemPrompt = conversationService.buildPrompt(sessionId);
        log.info("【系统提示】会话: {} 长度: {} 字符", sessionId, systemPrompt.length());

        // 3. 创建 ReAct Agent 并执行
        ReactAgent agent = new ReactAgent(llmService, tools, systemPrompt);
        String response = agent.run(sessionId, userMessage);

        // 4. 存储助手响应
        conversationService.addAssistantMessage(sessionId, response);
        log.info("【助手输出】会话: {} 内容: {}", sessionId, response);

        return ChatMessage.assistantMessage(response);
    }
}
