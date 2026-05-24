package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 规划 Agent — 理解用户意图，拆解任务，产出执行计划
 *
 * <p>职责单一：调用一次 LLM（不带工具），产出结构化 Plan 文本，
 * 注入给 ReactAgent 逐步执行。</p>
 */
public class PlanAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanAgent.class);

    private static final String PLANNER_SYSTEM_PROMPT = """
            你是一个任务规划专家。你的职责只有三件事：
            1. 准确理解用户意图
            2. 把任务拆成可执行的原子步骤
            3. 为每步匹配正确的工具和参数

            你不是执行者——你不知道运行时会发生什么，所以不要瞎猜失败处理。
            执行 Agent 自己有 ReAct 循环兜底能力，遇到错误会自己调整。

            ## 可用工具
            %s

            ## 可用技能
            %s

            ## 规划原则

            1. **意图优先** — 先搞清楚用户到底要什么，不要急着列步骤
            2. **发现缺口** — 用户没提供的信息就是缺口，列在「前置确认」，不要编造
            3. **用户便利** — 站在用户角度想：怎么减少等待？怎么让结果一目了然？完事了要不要主动清理？
            4. **步骤原子化** — 一步只做一件事，输入输出清晰
            5. **按需激活技能** — 如果某技能与任务相关，在步骤中建议先激活它获取详细指导

            ## 输出格式

            ### 意图
            [一句话说清用户想干什么]

            ### 前置确认
            [缺什么信息？没有就写"无"]

            ### 执行计划
            1. **[步骤名]**
               - 工具: `tool_name`
               - 参数: {key: value}
               - 目的: [为什么要做这一步，期望得到什么]

            2. **[步骤名]**
               ...

            ### 对用户的贴心建议
            [有没有能让用户更省心的事？比如结果汇总、临时文件清理等]
            """;

    private final LlmService llmService;
    private final String toolsDescription;
    private final String skillsDescription;

    public PlanAgent(LlmService llmService, List<ToolDefinition> toolDefinitions, List<Skill> skills) {
        this.llmService = llmService;
        this.toolsDescription = buildToolsDescription(toolDefinitions);
        this.skillsDescription = buildSkillsDescription(skills);
        log.info("PlanAgent 初始化完成，工具: {} 个，技能: {} 个", toolDefinitions.size(), skills.size());
    }

    /**
     * 产出执行计划
     *
     * @param userMessage 用户原始消息
     * @return 结构化 Plan 文本
     */
    public String plan(String userMessage) {
        log.info("PlanAgent 开始规划，用户消息长度: {} 字符", userMessage.length());

        String systemPrompt = String.format(PLANNER_SYSTEM_PROMPT, toolsDescription, skillsDescription);

        String plan = llmService.chatWithSystem(systemPrompt,
                List.of(com.example.sandbox.web.model.entity.ChatMessage.userMessage(userMessage)));

        log.info("PlanAgent 规划完成，Plan 长度: {} 字符", plan != null ? plan.length() : 0);
        return plan;
    }

    private String buildToolsDescription(List<ToolDefinition> tools) {
        return tools.stream()
                .map(t -> String.format("- **%s**: %s\n  参数: %s",
                        t.getName(), t.getDescription(), t.getParameters()))
                .collect(Collectors.joining("\n"));
    }

    private String buildSkillsDescription(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) return "（无可用技能）";
        return skills.stream()
                .map(s -> String.format("- **%s**: %s", s.getId(), s.getDescription()))
                .collect(Collectors.joining("\n"));
    }
}
