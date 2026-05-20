package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private AgentConfigProperties configProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private WebClient webClient;
    private String model;

    /**
     * 初始化 WebClient
     */
    @Autowired
    public void initWebClient() {
        String apiUrl = configProperties.getLlm().getApiUrl();
        String apiKey = configProperties.getLlm().getApiKey();
        this.model = configProperties.getLlm().getModel() != null
                ? configProperties.getLlm().getModel()
                : "glm-4";

        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("Initialized Zhipu LLM service with model: {}", this.model);
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        return chatWithSystem("", messages);
    }

    @Override
    public String chatWithSystem(String systemPrompt, List<ChatMessage> messages) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);

            List<Map<String, String>> chatMessages = new ArrayList<>();

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", systemPrompt);
                chatMessages.add(systemMsg);
            }

            for (ChatMessage msg : messages) {
                Map<String, String> chatMsg = new HashMap<>();
                chatMsg.put("role", msg.getRole());
                chatMsg.put("content", msg.getContent());
                chatMessages.add(chatMsg);
            }

            requestBody.put("messages", chatMessages);

            log.info("【LLM 请求】messages 数量: {}", chatMessages.size());
            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

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
                log.info("【LLM 请求】messages: {} tools: {}", chatMessages.size(), toolsApi.size());
            } else {
                log.info("【LLM 请求】messages: {} tools: 0", chatMessages.size());
            }

            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode responseJson = objectMapper.readTree(response);
            log.info("【LLM 响应】原始: {}", response);

            JsonNode messageNode = responseJson.path("choices").path(0).path("message");
            String content = messageNode.path("content").asText();
            log.debug("LLM text response: {}", content);

            JsonNode toolCallsNode = messageNode.path("tool_calls");
            if (!toolCallsNode.isMissingNode() && toolCallsNode.isArray() && toolCallsNode.size() > 0) {
                JsonNode toolCallNode = toolCallsNode.get(0);
                String toolName = toolCallNode.path("function").path("name").asText();
                String argumentsStr = toolCallNode.path("function").path("arguments").asText();
                String thinking = content;
                log.info("工具调用检测 - tool_name: {}, content: {}", toolName, content);

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

    private boolean isValidToolName(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return false;
        }
        return toolName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    private LlmResponse parseReActToolCall(String content) {
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

        Pattern actionPattern = Pattern.compile("Action:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher actionMatcher = actionPattern.matcher(content);

        if (!actionMatcher.find()) {
            return null;
        }

        String toolName = actionMatcher.group(1).trim();
        log.debug("检测到 ReAct Action: {}", toolName);

        Pattern inputPattern = Pattern.compile("Action\\s*Input:\\s*", Pattern.CASE_INSENSITIVE);
        Matcher inputMatcher = inputPattern.matcher(content);

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
                arguments = parseActionInput(inputContent);
            }
        }

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

    private Map<String, Object> parseKeyValueLines(String content) {
        Map<String, Object> arguments = new HashMap<>();
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

    private Map<String, Object> parseActionInput(String inputContent) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = objectMapper.readValue("{" + inputContent + "}", Map.class);
            return arguments;
        } catch (Exception e) {
            log.debug("JSON 格式无效，尝试 key=value 格式");
        }

        Map<String, Object> arguments = new HashMap<>();
        Pattern kvPattern = Pattern.compile("(\\w+)\\s*=\\s*");
        Matcher kvMatcher = kvPattern.matcher(inputContent);

        int lastEnd = 0;
        String lastKey = null;

        while (kvMatcher.find()) {
            if (lastKey != null) {
                String value = inputContent.substring(lastEnd, kvMatcher.start()).trim();
                value = cleanValue(value);
                arguments.put(lastKey, value);
            }
            lastKey = kvMatcher.group(1);
            lastEnd = kvMatcher.end();
        }

        if (lastKey != null && lastEnd < inputContent.length()) {
            String value = inputContent.substring(lastEnd).trim();
            value = cleanValue(value);
            arguments.put(lastKey, value);
        }

        return arguments.isEmpty() ? null : arguments;
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
}
