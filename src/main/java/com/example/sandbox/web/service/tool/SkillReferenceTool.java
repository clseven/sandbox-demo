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
 * 技能引用文件工具（第三层：按需取用）
 *
 * <p>读取技能目录下的引用文件（如 references/、examples/ 等）</p>
 * <p>只有当技能指令中明确提到某个引用文件时才需要调用</p>
 *
 * @author example
 * @date 2026/05/19
 */
@Component
public class SkillReferenceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SkillReferenceTool.class);
    private static final String NAME = "skill_reference";

    private final SkillService skillService;

    public SkillReferenceTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("skill_id", Map.of(
                "type", "string",
                "description", "技能 ID"
        ));
        properties.put("path", Map.of(
                "type", "string",
                "description", "相对于技能目录的文件路径，如 references/testing-anti-patterns.md"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("skill_id", "path")
        );

        return new ToolDefinition(
                NAME,
                "读取技能的引用文件。当技能指令中提到某个参考文档、模板或示例时，使用此工具获取内容。",
                parameters
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String skillId = (String) arguments.get("skill_id");
        String path = (String) arguments.get("path");

        if (skillId == null || skillId.isBlank()) {
            return "错误：技能 ID 不能为空";
        }
        if (path == null || path.isBlank()) {
            return "错误：文件路径不能为空";
        }

        try {
            Skill skill = skillService.getSkill(skillId);
            String content = skill.getReferenceFile(path);

            log.info("读取技能引用: {} / {}, 会话 {}", skillId, path, sessionId);
            return content;

        } catch (SkillNotFoundException e) {
            return "错误：技能 '" + skillId + "' 不存在";
        } catch (IOException e) {
            return "错误：文件 '" + path + "' 不存在或无法读取。\n" +
                    "可用的引用文件请使用 skill_activate(skill_id=\"" + skillId + "\", include_references=true) 查看。";
        }
    }
}
