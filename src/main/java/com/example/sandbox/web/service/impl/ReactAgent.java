package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ReAct Agent 实现
 *
 * <p>ReAct = Reasoning + Acting，通过循环推理完成任务。</p>
 *
 * <p>Prompt Caching 设计：</p>
 * <ul>
 *   <li>system prompt 在会话内不变 → 服务端缓存常驻</li>
 *   <li>历史消息（最近 20 条）作为 messages 数组的固定前缀 → 每轮缓存命中</li>
 *   <li>新增的 Thought/Action/Observation 只追加到末尾 → 仅新内容计费</li>
 * </ul>
 *
 * @author example
 * @date 2026/05/15
 */
public class ReactAgent {

    private static final Logger log = LoggerFactory.getLogger(ReactAgent.class);

    private static final int MAX_ITERATIONS = 20;
    private static final int SUMMARIZE_THRESHOLD = 24_000;
    private static final int TOKEN_CHARS_RATIO = 3;
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(120);

    private static final String SUMMARIZE_PROMPT = """
            请用中文将以下对话历史压缩为一段简洁摘要（不超过 500 字），保留：
            - 用户的核心目标和意图
            - 已完成的关键操作和结果
            - 重要的发现或结论
            不要逐条复述，只提取关键信息。

            %s

            对话历史：
            %s""";

    private static final String REACT_SYSTEM_PROMPT = """
            你是一个智能助手。你必须通过调用工具来完成任务。

            ## 技能系统（渐进式披露）

            你拥有一个技能系统，通过三层方式使用：

            1. **skill_list** - 列出所有可用技能（简历模式）
               每个技能只显示 ID 和一句话描述，让你快速了解有哪些能力可用。

            2. **skill_activate** - 激活技能，加载完整指令
               当你判断某个技能与当前任务相关时，调用此工具获取详细指导。
               例如：`skill_activate(skill_id="brainstorming")`

            3. **skill_reference** - 读取技能的引用文件
               当技能指令中提到某个参考文档、模板时，使用此工具获取内容。

            **重要**：只有在判断某技能相关时才激活它，不相关的技能不要加载，以节省 token。

            ## 文件处理

            - 用户上传的文件在沙盒 `/workspace/uploads/` 目录
            - Skill 文件在 `/mounted-skills/{skillId}/` 目录
            - 用 `list_files` 查看沙盒里有哪些文件
            - 用 `read_file` 读取文件内容，用 `write_file` 保存处理结果

            ## 重要规则

            1. **必须调用工具** — 当需要执行命令、读写文件、激活技能时，必须调用对应工具
            2. **每次只调用一个工具** — 工具执行后你会收到结果，再决定下一步
            3. **沙箱是隔离环境** — 执行命令、运行代码、读写文件都需要先调用 request_sandbox 创建沙箱
            4. **不能编造结果** — 如果工具返回错误，根据错误信息调整，不要假装成功

            ## 可用工具

            %s

            ## 工作流程

            1. 分析用户需求
            2. 调用 skill_list 了解可用能力
            3. 激活相关技能获取详细指导
            4. 选择合适的工具执行任务
            5. 根据结果继续或给出最终答案
            """;

    private final LlmService llmService;
    private final Map<String, Tool> tools;
    private final List<ToolDefinition> toolDefinitions;
    private final String systemPrompt;
    private final String plan;

    /** 对话摘要（超出 token 预算时压缩旧消息生成），追加到 system prompt 前 */
    private String conversationSummary;

    public ReactAgent(LlmService llmService, List<Tool> toolList) {
        this(llmService, toolList, null, null);
    }

    public ReactAgent(LlmService llmService, List<Tool> toolList, String skillPrompt) {
        this(llmService, toolList, skillPrompt, null);
    }

    public ReactAgent(LlmService llmService, List<Tool> toolList, String skillPrompt, String plan) {
        this.llmService = llmService;
        this.tools = new ConcurrentHashMap<>();
        this.toolDefinitions = new ArrayList<>();
        this.plan = plan;

        for (Tool tool : toolList) {
            tools.put(tool.getDefinition().getName(), tool);
            toolDefinitions.add(tool.getDefinition());
        }

        this.systemPrompt = buildSystemPrompt(skillPrompt);
    }

    /**
     * 执行 ReAct 循环
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息（作为本轮第一条 user 消息）
     * @param history     历史消息（不含当前用户消息），作为固定前缀以利用 prompt caching
     * @return 最终响应
     */
    public String run(String sessionId, String userMessage, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.addAll(trimHistory(history));
        messages.add(ChatMessage.userMessage(userMessage));

        log.info("ReAct 消息构建完成，历史 {} 条（截取后 {} 条），当前消息 1 条",
                history.size(), messages.size() - 1);

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            // 每次 LLM 调用前检查 token 预算，超出则压缩旧消息为摘要
            compressIfNeeded(messages);

            String prompt = effectiveSystemPrompt();
            LlmService.LlmResponse response = llmService.chatWithTools(prompt, messages, toolDefinitions);

            if (response.isFinished()) {
                log.info("ReAct 完成，共 {} 次迭代", iteration);
                return response.getContent();
            }

            if (response.hasToolCall()) {
                LlmService.ToolCall toolCall = response.getToolCall();
                String toolName = toolCall.getName();
                Map<String, Object> arguments = toolCall.getArguments();

                String llmContent = response.getContent();
                if (llmContent != null && !llmContent.isEmpty()) {
                    log.debug("LLM 思考: {}", llmContent.length() > 500 ? llmContent.substring(0, 500) + "..." : llmContent);
                }

                log.info("执行工具: {} 参数: {}", toolName, arguments);
                String observation = executeTool(sessionId, toolName, arguments);
                log.debug("工具结果: {}", observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

                messages.add(ChatMessage.assistantMessage(String.format(
                        "Thought: 执行工具 %s\nAction: %s\nAction Input: %s",
                        toolName, toolName, arguments)));
                messages.add(ChatMessage.userMessage("Observation: " + observation));
            } else {
                String finalContent = response.getContent();
                if (finalContent != null && !finalContent.isEmpty()) {
                    log.debug("LLM 最终回复长度: {}", finalContent.length());
                }
            }
        }

        log.warn("ReAct 达到最大迭代次数 ({})，会话 {}", MAX_ITERATIONS, sessionId);
        return "抱歉，我尝试了多次但仍未能完成任务。请尝试简化您的要求或提供更多信息。";
    }

    private String effectiveSystemPrompt() {
        if (conversationSummary == null || conversationSummary.isEmpty()) {
            return systemPrompt;
        }
        return "## 早期对话摘要\n" + conversationSummary + "\n\n" + systemPrompt;
    }

    /**
     * 如果消息总 token 超过摘要阈值，把最旧的消息压缩为摘要。
     * 保留最近 ~40% 的消息作为原始上下文，被压缩的消息从数组中移除。
     */
    private void compressIfNeeded(List<ChatMessage> messages) {
        int totalTokens = estimateTokens(messages);
        if (totalTokens <= SUMMARIZE_THRESHOLD) {
            return;
        }

        // 找到 60% token 位置，之前的消息将被压缩
        int threshold = 0;
        int splitAt = 0;
        for (int i = 0; i < messages.size(); i++) {
            threshold += messages.get(i).getContent().length() / TOKEN_CHARS_RATIO;
            if (threshold > totalTokens * 0.6) {
                splitAt = i;
                break;
            }
        }

        if (splitAt <= 2) {
            return; // 太少消息不值得压缩
        }

        List<ChatMessage> oldMessages = new ArrayList<>(messages.subList(0, splitAt));
        messages.subList(0, splitAt).clear();

        String newSummary = summarizeMessages(oldMessages);
        conversationSummary = newSummary;

        log.info("压缩 {} 条旧消息为摘要 ({} 字符)，剩余 {} 条",
                oldMessages.size(), newSummary.length(), messages.size());
    }

    private String summarizeMessages(List<ChatMessage> oldMessages) {
        StringBuilder history = new StringBuilder();
        for (ChatMessage msg : oldMessages) {
            String shortContent = msg.getContent().length() > 500
                    ? msg.getContent().substring(0, 500) + "..."
                    : msg.getContent();
            history.append(msg.getRole()).append(": ").append(shortContent).append("\n\n");
        }

        String existingNote = conversationSummary != null
                ? "已有摘要（请合并更新）：\n" + conversationSummary
                : "（首次摘要）";

        String prompt = String.format(SUMMARIZE_PROMPT, existingNote, history);

        try {
            String summary = llmService.chat(List.of(ChatMessage.userMessage(prompt)));
            return summary != null ? summary : history.toString();
        } catch (Exception e) {
            log.warn("摘要生成失败，保留原始文本", e);
            return history.toString();
        }
    }

    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += msg.getContent().length() / TOKEN_CHARS_RATIO;
        }
        return total;
    }

    /**
     * 限制历史消息条数（token 预算由循环内的 compressIfNeeded 动态管理）
     */
    private List<ChatMessage> trimHistory(List<ChatMessage> history) {
        if (history.size() <= 20) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(history.size() - 20, history.size()));
    }

    private String executeTool(String sessionId, String toolName, Map<String, Object> arguments) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "错误：未知工具 '" + toolName + "'";
        }
        try {
            return CompletableFuture.supplyAsync(() -> tool.execute(sessionId, arguments))
                    .orTimeout(TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        if (ex instanceof TimeoutException) {
                            log.error("工具执行超时 ({}s): {}", TOOL_TIMEOUT.getSeconds(), toolName);
                            return "工具执行超时（" + TOOL_TIMEOUT.getSeconds() + "秒），请重试或换一种方式";
                        }
                        log.error("Tool execution failed: {}", toolName, ex);
                        return "工具执行出错：" + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                    })
                    .join();
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolName, e);
            return "工具执行出错：" + e.getMessage();
        }
    }

    private String buildSystemPrompt(String skillPrompt) {
        StringBuilder toolsDesc = new StringBuilder();
        for (ToolDefinition tool : toolDefinitions) {
            toolsDesc.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }

        String basePrompt = String.format(REACT_SYSTEM_PROMPT, toolsDesc);

        StringBuilder fullPrompt = new StringBuilder();

        if (plan != null && !plan.isEmpty()) {
            fullPrompt.append("## 执行计划（参考）\n\n");
            fullPrompt.append("以下是一份规划建议，用于指引方向，但你不必死板照做：\n");
            fullPrompt.append("- 优先参考计划的步骤和目的来推进任务\n");
            fullPrompt.append("- 如果某步失败了，先用其他工具排查原因，再决定重试、换方案还是跳过\n");
            fullPrompt.append("- 遇到计划外的情况，大胆调用计划里没写的工具来诊断和解决\n");
            fullPrompt.append("- 记住计划的最终目标，但到达目标的路径你可以灵活调整\n\n");
            fullPrompt.append(plan).append("\n\n");
        }

        if (skillPrompt != null && !skillPrompt.isEmpty()) {
            fullPrompt.append(skillPrompt).append("\n\n");
        }

        fullPrompt.append(basePrompt);
        return fullPrompt.toString();
    }

    public List<ToolDefinition> getAvailableTools() {
        return new ArrayList<>(toolDefinitions);
    }
}
