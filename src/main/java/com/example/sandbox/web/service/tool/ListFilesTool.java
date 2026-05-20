package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.SandboxClient;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 列出文件工具
 */
@Component
public class ListFilesTool implements Tool {

    private static final String NAME = "list_files";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "要查看的目录路径，如 /workspace 或 /tmp"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of()
        );

        return new ToolDefinition(
                NAME,
                "列出沙盒中指定目录下的所有文件和子目录，帮助你了解沙盒里有哪些文件可用。",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String path = (String) arguments.get("path");
        if (path == null || path.isBlank()) {
            path = ".";
        }

        try {
            SandboxClient client = factory.getClient(sessionId);
            return client.execCommand("ls -la " + path);
        } catch (Exception e) {
            return "列出失败：" + e.getMessage();
        }
    }
}