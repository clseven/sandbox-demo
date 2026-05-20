package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能列表工具
 */
@Component
public class SkillListTool implements Tool {

    private static final String NAME = "skill_list";

    @Autowired
    private ConversationService conversationService;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", new LinkedHashMap<>(),
                "required", java.util.List.of()
        );

        return new ToolDefinition(
                NAME,
                "列出当前会话已启用的技能。返回每个技能的 ID 和一句话描述。",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            List<Skill> skills = conversationService.getEnabledSkills(sessionId);
            if (skills == null || skills.isEmpty()) {
                return "当前会话未启用任何技能";
            }

            StringBuilder sb = new StringBuilder();
            for (Skill skill : skills) {
                sb.append("- ").append(skill.getId());
                if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                    sb.append(": ").append(skill.getDescription());
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "获取技能列表失败：" + e.getMessage();
        }
    }
}