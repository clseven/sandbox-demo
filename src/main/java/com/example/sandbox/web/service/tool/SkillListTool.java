package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.SkillService;
import com.example.sandbox.web.service.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 技能列表工具（第一层：简历模式）
 *
 * <p>只列出当前会话已启用的技能元数据</p>
 * <p>每个技能约 30-50 token，让 agent 快速了解有哪些能力可用</p>
 *
 * @author example
 * @date 2026/05/19
 */
@Component
public class SkillListTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SkillListTool.class);
    private static final String NAME = "skill_list";

    private final SkillService skillService;
    private final ConversationService conversationService;

    public SkillListTool(SkillService skillService, ConversationService conversationService) {
        this.skillService = skillService;
        this.conversationService = conversationService;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of()
        );

        return new ToolDefinition(
                NAME,
                "列出当前会话已启用的技能。返回每个技能的 ID 和一句话描述。",
                parameters
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        // 获取已启用的技能 ID
        Set<String> enabledSkillIds = conversationService.getEnabledSkillIds(sessionId);

        if (enabledSkillIds.isEmpty()) {
            return "当前会话没有启用任何技能。请通过 Web 界面启用需要的技能。";
        }

        StringBuilder response = new StringBuilder();
        response.append("## 已启用技能 (").append(enabledSkillIds.size()).append(" 个)\n\n");
        response.append("以下是当前会话可用的技能。每个技能后面有一句话描述其用途。\n");
        response.append("当你判断某个技能与当前任务相关时，使用 `skill_activate` 加载详细指令。\n\n");

        for (String skillId : enabledSkillIds) {
            try {
                Skill skill = skillService.getSkill(skillId);
                response.append(skill.toMetadataLine()).append("\n");
            } catch (Exception e) {
                log.warn("获取技能 {} 失败: {}", skillId, e.getMessage());
            }
        }

        response.append("\n**使用方式**：`skill_activate(skill_id=\"技能ID\")` 加载完整指令。");

        log.debug("列出已启用技能 {} 个，会话 {}", enabledSkillIds.size(), sessionId);
        return response.toString();
    }
}
