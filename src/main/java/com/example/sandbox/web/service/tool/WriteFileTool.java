package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写入文件工具
 *
 * @author example
 * @date 2026/05/15
 */
@Component
public class WriteFileTool implements Tool {

    private static final String NAME = "write_file";

    private final SandboxService sandboxService;

    public WriteFileTool(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "沙箱中的文件路径"
        ));
        properties.put("content", Map.of(
                "type", "string",
                "description", "要写入的文件内容"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("path", "content")
        );

        return new ToolDefinition(
                NAME,
                "在沙箱环境中写入或创建文件",
                parameters
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String path = (String) arguments.get("path");
        String content = (String) arguments.get("content");
        if (path == null || path.isBlank()) {
            return "错误：文件路径不能为空";
        }
        if (content == null) {
            content = "";
        }

        try {
            var result = sandboxService.writeFile(sessionId, path, content);
            if (result.isSuccess()) {
                return "文件写入成功：" + path;
            } else {
                return "写入失败：" + result.getBody();
            }
        } catch (Exception e) {
            return "写入出错：" + e.getMessage();
        }
    }
}
