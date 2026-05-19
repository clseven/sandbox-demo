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
        /**
         * 文本回复（如果 LLM 不调用工具）
         */
        private final String content;

        /**
         * 工具调用（如果 LLM 选择调用工具）
         */
        private final ToolCall toolCall;

        /**
         * 是否完成（没有更多工具调用）
         */
        private final boolean finished;

        public LlmResponse(String content, ToolCall toolCall, boolean finished) {
            this.content = content;
            this.toolCall = toolCall;
            this.finished = finished;
        }

        /**
         * 纯文本回复
         */
        public static LlmResponse text(String content) {
            return new LlmResponse(content, null, true);
        }

        /**
         * 工具调用回复（保留思考内容）
         */
        public static LlmResponse toolCall(ToolCall toolCall, String thinking) {
            return new LlmResponse(thinking, toolCall, false);
        }

        public String getContent() {
            return content;
        }

        public ToolCall getToolCall() {
            return toolCall;
        }

        public boolean isFinished() {
            return finished;
        }

        public boolean hasToolCall() {
            return toolCall != null;
        }
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

        public String getName() {
            return name;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }
    }
}
