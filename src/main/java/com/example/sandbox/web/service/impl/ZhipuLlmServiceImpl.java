package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智谱 AI LLM 服务实现
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class ZhipuLlmServiceImpl implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(ZhipuLlmServiceImpl.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public ZhipuLlmServiceImpl(AgentConfigProperties configProperties, ObjectMapper objectMapper) {
        this.configProperties = configProperties;
        this.objectMapper = objectMapper;

        String apiUrl = configProperties.getLlm().getApiUrl();
        String apiKey = configProperties.getLlm().getApiKey();

        // 默认使用智谱 AI GLM-4
        this.model = configProperties.getLlm().getModel() != null
                ? configProperties.getLlm().getModel()
                : "glm-4";

        // 构建 WebClient
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("Initialized Zhipu LLM service with model: {}", this.model);
    }

    private final AgentConfigProperties configProperties;

    @Override
    public String chat(List<ChatMessage> messages) {
        return chatWithSystem("", messages);
    }

    @Override
    public String chatWithSystem(String systemPrompt, List<ChatMessage> messages) {
        try {
            // 构建请求
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);

            List<Map<String, String>> chatMessages = new ArrayList<>();

            // 添加系统提示
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", systemPrompt);
                chatMessages.add(systemMsg);
            }

            // 添加对话历史
            for (ChatMessage msg : messages) {
                Map<String, String> chatMsg = new HashMap<>();
                chatMsg.put("role", msg.getRole());
                chatMsg.put("content", msg.getContent());
                chatMessages.add(chatMsg);
            }

            requestBody.put("messages", chatMessages);

            // 发送请求
            log.info("【LLM 请求】messages 数量: {}", chatMessages.size());
            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // 解析响应
            JsonNode responseJson = objectMapper.readTree(response);
            String assistantMessage = responseJson
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();

            log.info("【LLM 响应】内容: {}", assistantMessage);
            return assistantMessage;

        } catch (Exception e) {
            log.error("LLM call failed", e);
            return "抱歉，发生了错误：" + e.getMessage();
        }
    }

    @Override
    public LlmResponse chatWithTools(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
        try {
            // 构建请求
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);

            List<Map<String, Object>> chatMessages = new ArrayList<>();

            // 添加系统提示
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                Map<String, Object> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", systemPrompt);
                chatMessages.add(systemMsg);
            }

            // 添加对话历史
            for (ChatMessage msg : messages) {
                Map<String, Object> chatMsg = new HashMap<>();
                chatMsg.put("role", msg.getRole());
                chatMsg.put("content", msg.getContent());
                chatMessages.add(chatMsg);
            }

            requestBody.put("messages", chatMessages);

            // 添加工具定义
            if (tools != null && !tools.isEmpty()) {
                List<Map<String, Object>> toolsApi = new ArrayList<>();
                for (ToolDefinition tool : tools) {
                    toolsApi.add(tool.toApiFormat());
                }
                requestBody.put("tools", toolsApi);
                log.info("【LLM 请求】messages: {} tools: {}", chatMessages.size(), toolsApi.size());
            } else {
                log.info("【LLM 请求】messages: {} tools: 0", chatMessages.size());
            }

            // 发送请求
            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // 解析响应
            JsonNode responseJson = objectMapper.readTree(response);
            log.info("【LLM 响应】原始: {}", response);

            JsonNode messageNode = responseJson.path("choices").path(0).path("message");

            // 普通文本回复（先获取，后续可能需要）
            String content = messageNode.path("content").asText();
            log.debug("LLM text response: {}", content);

            // 检查是否有工具调用 (OpenAI 格式)
            JsonNode toolCallsNode = messageNode.path("tool_calls");
            if (!toolCallsNode.isMissingNode() && toolCallsNode.isArray() && toolCallsNode.size() > 0) {
                // 解析第一个工具调用
                JsonNode toolCallNode = toolCallsNode.get(0);
                String toolName = toolCallNode.path("function").path("name").asText();
                String argumentsStr = toolCallNode.path("function").path("arguments").asText();

                // 提取思考内容
                // content 中可能包含思考内容，也可能是空的
                String thinking = content;
                log.info("工具调用检测 - tool_name: {}, content: {}", toolName, content);

                // 检查 toolName 是否是有效的工具名（不含换行符等异常字符）
                // GLM-4 有时会把参数名误当成工具名，如 "reason\n\nxxx"
                if (isValidToolName(toolName)) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> arguments = objectMapper.readValue(argumentsStr, Map.class);

                        log.info("LLM 工具调用: {} 参数: {} 思考: {}", toolName, arguments, thinking);
                        return LlmResponse.toolCall(new ToolCall(toolName, arguments), thinking);
                    } catch (Exception e) {
                        log.debug("Failed to parse tool arguments: {}", argumentsStr);
                    }
                } else {
                    log.debug("Invalid tool name detected: {}, falling back to ReAct parsing", toolName);
                }
            }

            // 尝试解析 ReAct 格式的工具调用（从 content 中）
            LlmResponse reactResponse = parseReActToolCall(content);
            if (reactResponse != null) {
                return reactResponse;
            }

            return LlmResponse.text(content);

        } catch (Exception e) {
            log.error("LLM call with tools failed", e);
            return LlmResponse.text("抱歉，发生了错误：" + e.getMessage());
        }
    }

    /**
     * 检查工具名称是否有效
     *
     * <p>有效的工具名应该：</p>
     * <ul>
     *   <li>只包含字母、数字、下划线</li>
     *   <li>不含换行符、空格等异常字符</li>
     * </ul>
     *
     * @param toolName 工具名称
     * @return 是否有效
     */
    private boolean isValidToolName(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return false;
        }
        // 工具名应该只包含字母、数字、下划线
        return toolName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    /**
     * 解析 ReAct 格式的工具调用
     *
     * <p>ReAct 格式示例：</p>
     * <pre>
     * Thought: 分析当前情况...
     * Action: tool_name
     * Action Input: {"param1": "value1"}
     * </pre>
     *
     * <p>也支持简化格式：</p>
     * <pre>
     * Action Input: {param1=value1, param2=value2}
     * </pre>
     *
     * @param content LLM 返回的文本内容
     * @return 如果解析成功返回工具调用，否则返回 null
     */
    private LlmResponse parseReActToolCall(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        // 提取思考内容（Action: 之前的部分）
        String thinking = null;
        int actionIndex = content.toLowerCase().indexOf("action:");
        if (actionIndex > 0) {
            thinking = content.substring(0, actionIndex).trim();
        } else {
            thinking = content;
        }

        // 匹配 Action: xxx 模式
        Pattern actionPattern = Pattern.compile("Action:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher actionMatcher = actionPattern.matcher(content);

        if (!actionMatcher.find()) {
            return null;
        }

        String toolName = actionMatcher.group(1).trim();
        log.debug("检测到 ReAct Action: {}", toolName);

        // 匹配 Action Input: 后的内容
        Pattern inputPattern = Pattern.compile("Action\\s*Input:\\s*", Pattern.CASE_INSENSITIVE);
        Matcher inputMatcher = inputPattern.matcher(content);

        if (!inputMatcher.find()) {
            return null;
        }

        int inputStart = inputMatcher.end();
        String remaining = content.substring(inputStart);

        Map<String, Object> arguments = null;

        // 尝试三种格式

        // 格式1: {...} 带花括号
        if (remaining.trim().startsWith("{")) {
            int braceStart = remaining.indexOf('{');
            String inputContent = extractBalancedBraces(remaining, braceStart);
            if (inputContent != null) {
                arguments = parseActionInput(inputContent);
            }
        }

        // 格式2: key=value 每行一个（无花括号）
        if (arguments == null) {
            arguments = parseKeyValueLines(remaining);
        }

        if (arguments != null && !arguments.isEmpty()) {
            log.debug("解析 ReAct 工具调用: {} 参数: {} 思考: {}", toolName, arguments, thinking);
            return LlmResponse.toolCall(new ToolCall(toolName, arguments), thinking);
        }

        log.warn("Failed to parse Action Input for tool: {}", toolName);
        return null;
    }

    /**
     * 解析每行一个 key=value 的格式（无花括号）
     *
     * <p>格式示例：</p>
     * <pre>
     * session_id=xxx
     * reason=yyy
     * </pre>
     *
     * @param content Action Input 后的内容
     * @return 解析后的参数 Map
     */
    private Map<String, Object> parseKeyValueLines(String content) {
        Map<String, Object> arguments = new HashMap<>();

        // 简单的 key=value 解析
        // 匹配 key=value 格式，value 到行尾或下一个 key= 为止
        Pattern kvPattern = Pattern.compile("(\\w+)\\s*=\\s*(.*)");
        String[] lines = content.split("\\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher matcher = kvPattern.matcher(line);
            if (matcher.matches()) {
                String key = matcher.group(1);
                String value = matcher.group(2).trim();
                arguments.put(key, value);
            }
        }

        return arguments.isEmpty() ? null : arguments;
    }

    /**
     * 解析 Action Input 内容，支持 JSON 和 key=value 两种格式
     *
     * @param inputContent 花括号内的内容
     * @return 解析后的参数 Map
     */
    private Map<String, Object> parseActionInput(String inputContent) {
        // 先尝试作为 JSON 解析
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = objectMapper.readValue("{" + inputContent + "}", Map.class);
            return arguments;
        } catch (Exception e) {
            log.debug("JSON 格式无效，尝试 key=value 格式");
        }

        // 尝试 key=value 格式
        Map<String, Object> arguments = new HashMap<>();
        Pattern kvPattern = Pattern.compile("(\\w+)\\s*=\\s*");
        Matcher kvMatcher = kvPattern.matcher(inputContent);

        int lastEnd = 0;
        String lastKey = null;

        while (kvMatcher.find()) {
            if (lastKey != null) {
                // 提取上一个 key 的值
                String value = inputContent.substring(lastEnd, kvMatcher.start()).trim();
                value = cleanValue(value);
                arguments.put(lastKey, value);
            }
            lastKey = kvMatcher.group(1);
            lastEnd = kvMatcher.end();
        }

        // 处理最后一个 key-value 对
        if (lastKey != null && lastEnd < inputContent.length()) {
            String value = inputContent.substring(lastEnd).trim();
            value = cleanValue(value);
            arguments.put(lastKey, value);
        }

        return arguments.isEmpty() ? null : arguments;
    }

    /**
     * 清理值字符串（移除尾部逗号等）
     */
    private String cleanValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // 移除尾部逗号和空白
        value = value.trim();
        if (value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    /**
     * 提取平衡的花括号内容
     *
     * @param content 完整内容
     * @param start   左花括号位置
     * @return 花括号内的内容（不含花括号本身）
     */
    private String extractBalancedBraces(String content, int start) {
        int depth = 0;
        int end = start;

        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }

        if (depth != 0) {
            return null;
        }

        // 返回花括号内的内容（不含花括号）
        return content.substring(start + 1, end);
    }
}
