package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取文件工具
 *
 * @author example
 * @date 2026/05/15
 */
@Component
public class ReadFileTool implements Tool {

    private static final String NAME = "read_file";

    private final SandboxService sandboxService;

    public ReadFileTool(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "沙箱中的文件路径"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("path")
        );

        return new ToolDefinition(
                NAME,
                "读取沙箱环境中的文件内容",
                parameters
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String path = (String) arguments.get("path");
        if (path == null || path.isBlank()) {
            return "错误：文件路径不能为空";
        }

        try {
            var result = sandboxService.readFile(sessionId, path);
            if (result.isSuccess()) {
                return result.getBody();
            } else {
                return "读取失败：" + result.getBody();
            }
        } catch (Exception e) {
            return "读取出错：" + e.getMessage();
        }
    }
}
