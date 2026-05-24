package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReAct Agent 实现
 *
 * <p>ReAct = Reasoning + Acting，通过循环推理完成任务：</p>
 * <ol>
 *   <li>Thought: 分析当前情况，决定下一步行动</li>
 *   <li>Action: 选择工具并执行</li>
 *   <li>Observation: 观察工具执行结果</li>
 *   <li>重复直到得出最终答案</li>
 * </ol>
 *
 * @author example
 * @date 2026/05/15
 */
public class ReactAgent {

    private static final Logger log = LoggerFactory.getLogger(ReactAgent.class);

    /**
     * 最大迭代次数，防止无限循环
     */
    private static final int MAX_ITERATIONS = 20;

    /**
     * ReAct 系统提示模板
     */
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

    /**
     * 创建 ReactAgent（无技能）
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList) {
        this(llmService, toolList, null, null);
    }

    /**
     * 创建 ReactAgent（带技能内容）
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList, String skillPrompt) {
        this(llmService, toolList, skillPrompt, null);
    }

    /**
     * 创建 ReactAgent（带技能 + 执行计划）
     *
     * @param llmService   LLM 服务
     * @param toolList     工具列表
     * @param skillPrompt  技能内容
     * @param plan         执行计划（PlanAgent 输出）
     */
    public ReactAgent(LlmService llmService, List<Tool> toolList, String skillPrompt, String plan) {
        this.llmService = llmService;
        this.tools = new ConcurrentHashMap<>();
        this.toolDefinitions = new ArrayList<>();
        this.plan = plan;

        for (Tool tool : toolList) {
            tools.put(tool.getDefinition().getName(), tool);
            toolDefinitions.add(tool.getDefinition());
            log.info("已注册工具: {} - {}", tool.getDefinition().getName(), tool.getDefinition().getDescription());
        }

        log.info("工具注册完成，共 {} 个工具: {}", toolList.size(), tools.keySet());

        this.systemPrompt = buildSystemPrompt(skillPrompt);
        log.info("系统提示长度: {} 字符", systemPrompt.length());
    }

    /**
     * 执行 ReAct 循环
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return 最终响应
     */
    public String run(String sessionId, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.userMessage(userMessage));

        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;
            log.info("ReAct 第 {} 次迭代，会话 {}", iteration, sessionId);

            // 调用 LLM
            LlmService.LlmResponse response = llmService.chatWithTools(
                    systemPrompt,
                    messages,
                    toolDefinitions
            );

            // 记录发送给 LLM 的消息
            log.info("=== 第 {} 次迭代，发送消息 {} 条 ===", iteration, messages.size());
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                String preview = msg.getContent().length() > 300
                        ? msg.getContent().substring(0, 300) + "..."
                        : msg.getContent();
                log.debug("消息[{}] {}: {}", i, msg.getRole(), preview);
            }

            // 如果没有工具调用，返回最终答案
            if (response.isFinished()) {
                log.info("ReAct 完成，共 {} 次迭代", iteration);
                return response.getContent();
            }

            // 执行工具调用
            if (response.hasToolCall()) {
                LlmService.ToolCall toolCall = response.getToolCall();
                String toolName = toolCall.getName();
                Map<String, Object> arguments = toolCall.getArguments();

                // 记录 LLM 的思考过程（如果有）
                String llmContent = response.getContent();
                if (llmContent != null && !llmContent.isEmpty()) {
                    log.info("LLM 思考: {}", llmContent.length() > 500 ? llmContent.substring(0, 500) + "..." : llmContent);
                }

                log.info("执行工具: {} 参数: {}", toolName, arguments);

                // 执行工具
                String observation = executeTool(sessionId, toolName, arguments);

                log.info("工具结果: {}", observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);

                // 将工具调用结果添加到消息历史
                // 格式化为 LLM 能理解的格式
                String assistantMsg = String.format(
                        "Thought: 执行工具 %s\nAction: %s\nAction Input: %s",
                        toolName, toolName, arguments
                );
                messages.add(ChatMessage.assistantMessage(assistantMsg));

                String userMsg = "Observation: " + observation;
                messages.add(ChatMessage.userMessage(userMsg));
            } else {
                // 没有工具调用，记录 LLM 最终回复
                String finalContent = response.getContent();
                if (finalContent != null && !finalContent.isEmpty()) {
                    log.info("LLM 最终回复: {}", finalContent.length() > 500 ? finalContent.substring(0, 500) + "..." : finalContent);
                }
            }
        }

        // 超过最大迭代次数
        log.warn("ReAct 达到最大迭代次数 ({})，会话 {}", MAX_ITERATIONS, sessionId);
        return "抱歉，我尝试了多次但仍未能完成任务。请尝试简化您的要求或提供更多信息。";
    }

    /**
     * 执行工具
     */
    private String executeTool(String sessionId, String toolName, Map<String, Object> arguments) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "错误：未知工具 '" + toolName + "'";
        }

        try {
            return tool.execute(sessionId, arguments);
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolName, e);
            return "工具执行出错：" + e.getMessage();
        }
    }

    /**
     * 构建系统提示
     */
    private String buildSystemPrompt(String skillPrompt) {
        StringBuilder toolsDesc = new StringBuilder();
        for (ToolDefinition tool : toolDefinitions) {
            toolsDesc.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }

        String basePrompt = String.format(REACT_SYSTEM_PROMPT, toolsDesc);

        StringBuilder fullPrompt = new StringBuilder();

        // 1. 执行计划（参考指南，非死命令）
        if (plan != null && !plan.isEmpty()) {
            fullPrompt.append("## 执行计划（参考）\n\n");
            fullPrompt.append("以下是一份规划建议，用于指引方向，但你不必死板照做：\n");
            fullPrompt.append("- 优先参考计划的步骤和目的来推进任务\n");
            fullPrompt.append("- 如果某步失败了，先用其他工具排查原因，再决定重试、换方案还是跳过\n");
            fullPrompt.append("- 遇到计划外的情况，大胆调用计划里没写的工具来诊断和解决\n");
            fullPrompt.append("- 记住计划的最终目标，但到达目标的路径你可以灵活调整\n\n");
            fullPrompt.append(plan).append("\n\n");
        }

        // 2. 技能内容
        if (skillPrompt != null && !skillPrompt.isEmpty()) {
            fullPrompt.append(skillPrompt).append("\n\n");
        }

        fullPrompt.append(basePrompt);
        return fullPrompt.toString();
    }

    /**
     * 获取可用工具列表
     */
    public List<ToolDefinition> getAvailableTools() {
        return new ArrayList<>(toolDefinitions);
    }
}
