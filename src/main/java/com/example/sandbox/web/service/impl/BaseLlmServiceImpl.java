package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 服务抽象基类，提供 OpenAI 兼容 API 的通用实现。
 *
 * <p>子类只需提供 apiUrl、apiKey、model，无需重复编写请求构建、ReAct 解析、Token 统计等逻辑。</p>
 *
 * @author example
 * @date 2026/05/26
 */
public abstract class BaseLlmServiceImpl implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(BaseLlmServiceImpl.class);

    /** LLM API 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    /** LLM API 响应超时（秒） */
    private static final int RESPONSE_TIMEOUT_SECONDS = 300;

    private static final Pattern ACTION_PATTERN = Pattern.compile("Action:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT_PATTERN = Pattern.compile("Action\\s*Input:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern KV_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*(.*)");
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String model;

    protected BaseLlmServiceImpl(String apiUrl, String apiKey, String model, ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS));

        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();

        log.info("Initialized {} with model: {}", getClass().getSimpleName(), model);
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        return chatWithSystem("", messages);
    }

    @Override
    public String chatWithSystem(String systemPrompt, List<ChatMessage> messages) {
        try {
            Map<String, Object> requestBody = buildRequestBody(systemPrompt, messages, null);
            logRequest(messages.size(), 0);

            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode responseJson = objectMapper.readTree(response);
            String content = responseJson.path("choices").path(0).path("message").path("content").asText();
            logTokenUsage(extractTokenUsage(responseJson));
            return content;

        } catch (Exception e) {
            log.error("LLM call failed", e);
            return "抱歉，AI 服务暂时不可用，请稍后重试。";
        }
    }

    @Override
    public LlmResponse chatWithTools(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
        try {
            Map<String, Object> requestBody = buildRequestBody(systemPrompt, messages, tools);
            logRequest(messages.size(), tools != null ? tools.size() : 0);

            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode responseJson = objectMapper.readTree(response);
            TokenUsage tokenUsage = extractTokenUsage(responseJson);

            JsonNode messageNode = responseJson.path("choices").path(0).path("message");
            String content = messageNode.path("content").asText();

            // 优先解析原生 tool_calls
            JsonNode toolCallsNode = messageNode.path("tool_calls");
            if (!toolCallsNode.isMissingNode() && toolCallsNode.isArray() && toolCallsNode.size() > 0) {
                JsonNode toolCallNode = toolCallsNode.get(0);
                String toolName = toolCallNode.path("function").path("name").asText();
                String argumentsStr = toolCallNode.path("function").path("arguments").asText();

                if (isValidToolName(toolName)) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> arguments = objectMapper.readValue(argumentsStr, Map.class);
                        log.info("LLM 工具调用: {} 参数: {}", toolName, arguments);
                        return LlmResponse.toolCall(new ToolCall(toolName, arguments), content, tokenUsage);
                    } catch (Exception e) {
                        log.debug("Failed to parse tool arguments: {}", argumentsStr);
                    }
                }
            }

            // 回退：ReAct 文本解析
            LlmResponse reactResponse = parseReActToolCall(content, tokenUsage);
            if (reactResponse != null) {
                return reactResponse;
            }

            return LlmResponse.text(content, tokenUsage);

        } catch (Exception e) {
            log.error("LLM call with tools failed", e);
            return LlmResponse.text("抱歉，AI 服务暂时不可用，请稍后重试。");
        }
    }

    // ==================== 请求构建 ====================

    private Map<String, Object> buildRequestBody(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);

        List<Map<String, Object>> chatMessages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            chatMessages.add(systemMsg);
        }

        for (ChatMessage msg : messages) {
            Map<String, Object> chatMsg = new HashMap<>();
            chatMsg.put("role", msg.getRole());
            chatMsg.put("content", msg.getContent());
            chatMessages.add(chatMsg);
        }

        requestBody.put("messages", chatMessages);

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolsApi = new ArrayList<>();
            for (ToolDefinition tool : tools) {
                toolsApi.add(tool.toApiFormat());
            }
            requestBody.put("tools", toolsApi);
        }

        return requestBody;
    }

    // ==================== ReAct 文本解析（回退机制） ====================

    private LlmResponse parseReActToolCall(String content, TokenUsage tokenUsage) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        String thinking = null;
        int actionIndex = content.toLowerCase().indexOf("action:");
        if (actionIndex > 0) {
            thinking = content.substring(0, actionIndex).trim();
        } else {
            thinking = content;
        }

        Matcher actionMatcher = ACTION_PATTERN.matcher(content);
        if (!actionMatcher.find()) {
            return null;
        }

        String toolName = actionMatcher.group(1).trim();

        Matcher inputMatcher = INPUT_PATTERN.matcher(content);
        if (!inputMatcher.find()) {
            return null;
        }

        int inputStart = inputMatcher.end();
        String remaining = content.substring(inputStart);

        Map<String, Object> arguments = null;

        if (remaining.trim().startsWith("{")) {
            int braceStart = remaining.indexOf('{');
            String inputContent = extractBalancedBraces(remaining, braceStart);
            if (inputContent != null) {
                arguments = parseJsonOrKeyValue(inputContent);
            }
        }

        if (arguments == null) {
            arguments = parseKeyValueLines(remaining);
        }

        if (arguments != null && !arguments.isEmpty()) {
            log.debug("解析 ReAct 工具调用: {} 参数: {} 思考: {}", toolName, arguments, thinking);
            return LlmResponse.toolCall(new ToolCall(toolName, arguments), thinking, tokenUsage);
        }

        log.warn("Failed to parse Action Input for tool: {}", toolName);
        return null;
    }

    // ==================== 参数解析 ====================

    private Map<String, Object> parseJsonOrKeyValue(String content) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue("{" + content + "}", Map.class);
            return result;
        } catch (Exception e) {
            log.debug("JSON 格式无效，尝试 key=value 格式");
        }

        Map<String, Object> arguments = new HashMap<>();
        Matcher kvMatcher = KV_PATTERN.matcher(content);

        int lastEnd = 0;
        String lastKey = null;

        while (kvMatcher.find()) {
            if (lastKey != null) {
                String value = content.substring(lastEnd, kvMatcher.start()).trim();
                value = cleanValue(value);
                arguments.put(lastKey, value);
            }
            lastKey = kvMatcher.group(1);
            lastEnd = kvMatcher.end();
        }

        if (lastKey != null && lastEnd < content.length()) {
            String value = content.substring(lastEnd).trim();
            value = cleanValue(value);
            arguments.put(lastKey, value);
        }

        return arguments.isEmpty() ? null : arguments;
    }

    private Map<String, Object> parseKeyValueLines(String content) {
        Map<String, Object> arguments = new HashMap<>();
        String[] lines = content.split("\\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = KV_PATTERN.matcher(line);
            if (matcher.matches()) {
                arguments.put(matcher.group(1), matcher.group(2).trim());
            }
        }

        return arguments.isEmpty() ? null : arguments;
    }

    // ==================== 工具方法 ====================

    private boolean isValidToolName(String toolName) {
        return toolName != null && TOOL_NAME_PATTERN.matcher(toolName).matches();
    }

    private String cleanValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        value = value.trim();
        if (value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

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
        return content.substring(start + 1, end);
    }

    // ==================== Token 追踪 ====================

    private TokenUsage extractTokenUsage(JsonNode responseJson) {
        JsonNode usageNode = responseJson.path("usage");
        if (usageNode.isMissingNode()) {
            return null;
        }
        int promptTokens = usageNode.path("prompt_tokens").asInt(0);
        int completionTokens = usageNode.path("completion_tokens").asInt(0);
        int totalTokens = usageNode.path("total_tokens").asInt(0);
        int cacheHitTokens = usageNode.path("prompt_cache_hit_tokens").asInt(0);
        return new TokenUsage(promptTokens, completionTokens, totalTokens, cacheHitTokens);
    }

    private void logTokenUsage(TokenUsage usage) {
        if (usage != null) {
            log.info("Token 消耗: prompt={}, completion={}, total={}, cacheHit={}",
                    usage.getPromptTokens(), usage.getCompletionTokens(),
                    usage.getTotalTokens(), usage.getCacheHitTokens());
        }
    }

    private void logRequest(int messageCount, int toolCount) {
        if (toolCount > 0) {
            log.info("【LLM 请求】messages: {} tools: {}", messageCount, toolCount);
        } else {
            log.info("【LLM 请求】messages: {}", messageCount);
        }
    }
}
