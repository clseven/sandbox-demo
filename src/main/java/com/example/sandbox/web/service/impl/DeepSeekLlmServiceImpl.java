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
 * DeepSeek LLM 服务实现（OpenAI 兼容 API）
 */
@Service
public class DeepSeekLlmServiceImpl implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmServiceImpl.class);

    @Autowired
    private AgentConfigProperties configProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private WebClient webClient;
    private String model;

    @Autowired
    public void initWebClient() {
        var executor = configProperties.getLlm().getExecutor();
        this.model = executor.getModel() != null ? executor.getModel() : "deepseek-v4-flash";

        this.webClient = WebClient.builder()
                .baseUrl(executor.getApiUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + executor.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("Initialized DeepSeek LLM service with model: {}", this.model);
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

            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode responseJson = objectMapper.readTree(response);
            return responseJson.path("choices").path(0).path("message").path("content").asText();

        } catch (Exception e) {
            log.error("DeepSeek LLM call failed", e);
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
            }

            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode responseJson = objectMapper.readTree(response);
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
                        log.info("DeepSeek 工具调用: {} 参数: {}", toolName, arguments);
                        return LlmResponse.toolCall(new ToolCall(toolName, arguments), content);
                    } catch (Exception e) {
                        log.debug("Failed to parse tool arguments: {}", argumentsStr);
                    }
                }
            }

            // 回退：ReAct 文本解析
            LlmResponse reactResponse = parseReActToolCall(content);
            if (reactResponse != null) {
                return reactResponse;
            }

            return LlmResponse.text(content);

        } catch (Exception e) {
            log.error("DeepSeek LLM call with tools failed", e);
            return LlmResponse.text("抱歉，发生了错误：" + e.getMessage());
        }
    }

    private boolean isValidToolName(String toolName) {
        if (toolName == null || toolName.isEmpty()) return false;
        return toolName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    private LlmResponse parseReActToolCall(String content) {
        if (content == null || content.isEmpty()) return null;

        Pattern actionPattern = Pattern.compile("Action:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher actionMatcher = actionPattern.matcher(content);
        if (!actionMatcher.find()) return null;

        String toolName = actionMatcher.group(1).trim();

        Pattern inputPattern = Pattern.compile("Action\\s*Input:\\s*", Pattern.CASE_INSENSITIVE);
        Matcher inputMatcher = inputPattern.matcher(content);
        if (!inputMatcher.find()) return null;

        int inputStart = inputMatcher.end();
        String remaining = content.substring(inputStart);

        Map<String, Object> arguments = null;

        if (remaining.trim().startsWith("{")) {
            String inputContent = extractBalancedBraces(remaining, remaining.indexOf('{'));
            if (inputContent != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue("{" + inputContent + "}", Map.class);
                    arguments = parsed;
                } catch (Exception e) {
                    arguments = parseKeyValueLines(inputContent);
                }
            }
        }

        if (arguments == null) {
            arguments = parseKeyValueLines(remaining);
        }

        if (arguments != null && !arguments.isEmpty()) {
            log.debug("ReAct 工具调用: {} 参数: {}", toolName, arguments);
            return LlmResponse.toolCall(new ToolCall(toolName, arguments), content);
        }

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
                arguments.put(matcher.group(1), matcher.group(2).trim());
            }
        }
        return arguments.isEmpty() ? null : arguments;
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
        if (depth != 0) return null;
        return content.substring(start + 1, end);
    }
}
