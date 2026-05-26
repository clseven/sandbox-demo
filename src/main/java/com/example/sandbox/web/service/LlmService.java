package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * LLM 服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface LlmService {

    /**
     * 聊天补全
     *
     * @param messages 消息列表
     * @return 助手回复
     */
    String chat(List<ChatMessage> messages);

    /**
     * 带系统提示的聊天补全
     *
     * @param systemPrompt 系统提示
     * @param messages     消息列表
     * @return 助手回复
     */
    String chatWithSystem(String systemPrompt, List<ChatMessage> messages);

    /**
     * 带工具的聊天补全（ReAct 模式）
     *
     * @param systemPrompt 系统提示
     * @param messages     消息列表
     * @param tools        可用工具定义
     * @return LLM 响应（可能包含工具调用）
     */
    LlmResponse chatWithTools(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools);

    /**
     * LLM 响应
     */
    class LlmResponse {
        private final String content;
        private final ToolCall toolCall;
        private final boolean finished;
        private final TokenUsage tokenUsage;

        public LlmResponse(String content, ToolCall toolCall, boolean finished, TokenUsage tokenUsage) {
            this.content = content;
            this.toolCall = toolCall;
            this.finished = finished;
            this.tokenUsage = tokenUsage;
        }

        public static LlmResponse text(String content) {
            return new LlmResponse(content, null, true, null);
        }

        public static LlmResponse text(String content, TokenUsage tokenUsage) {
            return new LlmResponse(content, null, true, tokenUsage);
        }

        public static LlmResponse toolCall(ToolCall toolCall, String thinking) {
            return new LlmResponse(thinking, toolCall, false, null);
        }

        public static LlmResponse toolCall(ToolCall toolCall, String thinking, TokenUsage tokenUsage) {
            return new LlmResponse(thinking, toolCall, false, tokenUsage);
        }

        public String getContent() { return content; }
        public ToolCall getToolCall() { return toolCall; }
        public boolean isFinished() { return finished; }
        public boolean hasToolCall() { return toolCall != null; }
        public TokenUsage getTokenUsage() { return tokenUsage; }
    }

    /**
     * 工具调用
     */
    class ToolCall {
        private final String name;
        private final Map<String, Object> arguments;

        public ToolCall(String name, Map<String, Object> arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public String getName() { return name; }
        public Map<String, Object> getArguments() { return arguments; }
    }

    /**
     * Token 消耗统计
     */
    class TokenUsage {
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;
        private final int cacheHitTokens;

        public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
            this(promptTokens, completionTokens, totalTokens, 0);
        }

        public TokenUsage(int promptTokens, int completionTokens, int totalTokens, int cacheHitTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.cacheHitTokens = cacheHitTokens;
        }

        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return totalTokens; }
        public int getCacheHitTokens() { return cacheHitTokens; }

        @Override
        public String toString() {
            return "TokenUsage{prompt=" + promptTokens + ", completion=" + completionTokens
                    + ", total=" + totalTokens + ", cacheHit=" + cacheHitTokens + '}';
        }
    }
}
