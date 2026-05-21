package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 编排服务实现（使用 ReAct 模式）
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    @Autowired
    private ConversationServiceImpl conversationService;

    @Autowired
    private LlmService llmService;

    @Autowired
    private List<Tool> tools;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.example.sandbox.web.service.SandboxService sandboxService;

    @Override
    public ConversationSession createSession() {
        ConversationSession session = conversationService.createSession();
        log.info("Created session: {}", session.getSessionId());

        // 异步创建沙箱，不阻塞前端响应
        String sessionId = session.getSessionId();
        CompletableFuture.runAsync(() -> {
            try {
                // 如果数据库中已有沙箱记录，跳过创建
                if (sandboxService.hasSandbox(sessionId)) {
                    log.info("Sandbox already exists for session {}, skipping", sessionId);
                    return;
                }
                sandboxService.createSandbox(sessionId);
                log.info("Sandbox created async for session: {}", sessionId);
            } catch (Exception e) {
                log.warn("Async create sandbox failed for session {}: {}", sessionId, e.getMessage());
            }
        });

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
        // 0. 确保沙箱已创建（异步可能还没完成）
        if (!sandboxService.hasSandbox(sessionId)) {
            log.info("Sandbox not ready for session {}, creating now...", sessionId);
            sandboxService.createSandbox(sessionId);
        }

        // 1. 存储用户消息
        conversationService.addUserMessage(sessionId, userMessage);
        log.info("【用户输入】会话: {} 内容: {}", sessionId, userMessage);

        // 2. 提取消息中提到的上传文件，追加到系统提示
        String extraContext = extractFileContext(userMessage);

        // 3. 构建系统提示（包含启用的技能内容）
        String systemPrompt = conversationService.buildPrompt(sessionId);
        if (extraContext != null) {
            systemPrompt = extraContext + "\n\n" + systemPrompt;
        }
        log.info("【系统提示】会话: {} 长度: {} 字符", sessionId, systemPrompt.length());

        // 4. 根据沙箱类型过滤工具
        boolean isAio = sandboxService.isAioSandbox(sessionId);
        String targetType = isAio ? "AIO" : "COMMON";
        List<Tool> filteredTools = tools.stream()
                .filter(t -> {
                    String type = t.getDefinition().getSandboxType();
                    return "ALL".equals(type) || targetType.equals(type);
                })
                .toList();
        log.info("【工具过滤】沙箱类型: {}, 可用工具: {}", targetType, filteredTools.stream().map(t -> t.getDefinition().getName()).toList());

        // 5. 创建 ReAct Agent 并执行
        ReactAgent reactAgent = new ReactAgent(llmService, filteredTools, systemPrompt);
        String response = reactAgent.run(sessionId, userMessage);

        // 6. 存储助手响应
        conversationService.addAssistantMessage(sessionId, response);
        log.info("【助手输出】会话: {} 内容: {}", sessionId, response);

        return ChatMessage.assistantMessage(response);
    }

    /**
     * 从用户消息中提取文件上传信息，生成上下文提示
     */
    private String extractFileContext(String userMessage) {
        // 检测用户消息中是否提到【上传的文件】段落
        if (!userMessage.contains("【上传的文件】")) {
            return null;
        }

        // 提取文件名列表
        StringBuilder context = new StringBuilder();
        context.append("## 用户已上传的文件\n");
        context.append("文件已同步到沙盒 `/workspace/uploads/` 目录，可直接读取：\n\n");

        // 用换行和 📎 标记来解析文件列表
        String[] lines = userMessage.split("\n");
        for (String line : lines) {
            if (line.contains("📎")) {
                // 提取文件名（📎 后面的内容，去掉大小信息）
                String filename = line.replace("📎", "").trim();
                int sizeIdx = filename.lastIndexOf(" (");
                if (sizeIdx > 0) {
                    filename = filename.substring(0, sizeIdx);
                }
                // 去掉可能的前缀符号
                filename = filename.replaceFirst("^[^a-zA-Z0-9\\u4e00-\\u9fa5]+", "");
                if (!filename.isEmpty()) {
                    context.append("- ").append(filename).append("\n");
                }
            }
        }

        return context.toString();
    }
}
