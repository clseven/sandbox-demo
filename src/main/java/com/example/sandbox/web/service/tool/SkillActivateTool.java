package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.exception.SkillNotFoundException;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.SkillService;
import com.example.sandbox.web.service.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技能激活工具（第二层：按需加载）
 *
 * <p>实现渐进式披露：</p>
 * <ul>
 *   <li>启动时 agent 只看到技能元数据列表（id + description）</li>
 *   <li>当 agent 判断某个技能相关时，调用此工具加载完整内容</li>
 *   <li>可选择是否同时加载引用文件列表</li>
 * </ul>
 *
 * <p>注意：此工具只读取内容，不修改会话状态。</p>
 *
 * @author example
 * @date 2026/05/19
 */
@Component
public class SkillActivateTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SkillActivateTool.class);
    private static final String NAME = "skill_activate";

    private final SkillService skillService;

    public SkillActivateTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("skill_id", Map.of(
                "type", "string",
                "description", "要激活的技能 ID，如 brainstorming, test-driven-development"
        ));
        properties.put("include_references", Map.of(
                "type", "boolean",
                "description", "是否同时列出可用的引用文件列表（默认 false）"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("skill_id")
        );

        return new ToolDefinition(
                NAME,
                "激活一个技能：加载该技能的完整指令内容。当你判断某个技能与当前任务相关时，调用此工具获取详细指导。",
                parameters
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String skillId = (String) arguments.get("skill_id");
        if (skillId == null || skillId.isBlank()) {
            return "错误：技能 ID 不能为空";
        }

        boolean includeReferences = Boolean.TRUE.equals(arguments.get("include_references"));

        try {
            // 1. 获取技能（只读，不修改会话状态）
            Skill skill = skillService.getSkill(skillId);

            // 2. 构建响应
            StringBuilder response = new StringBuilder();
            response.append("✅ 技能已激活: ").append(skillId).append("\n\n");
            response.append("## ").append(skill.getName()).append("\n\n");
            response.append(skill.getContent());

            // 3. 可选：列出引用文件
            if (includeReferences) {
                String refs = skill.listAvailableReferences();
                if (!refs.isEmpty()) {
                    response.append("\n\n").append(refs);
                }
            }

            log.info("技能 {} 已激活（仅读取内容），会话 {}", skillId, sessionId);
            return response.toString();

        } catch (SkillNotFoundException e) {
            log.warn("技能不存在: {}", skillId);
            return "错误：技能 '" + skillId + "' 不存在。使用 skill_list 查看可用技能。";
        } catch (IOException e) {
            log.error("读取技能内容失败: {}", skillId, e);
            return "错误：读取技能内容失败 - " + e.getMessage();
        }
    }
}
