package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行命令工具
 *
 * @author example
 * @date 2026/05/15
 */
@Component
public class ExecuteCommandTool implements Tool {

    private static final String NAME = "execute_command";

    private final SandboxService sandboxService;

    public ExecuteCommandTool(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of(
                "type", "string",
                "description", "要执行的 shell 命令"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("command")
        );

        return new ToolDefinition(
                NAME,
                "在沙箱环境中执行 shell 命令。可用于运行脚本、查看文件、安装依赖等。",
                parameters
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return "错误：命令不能为空";
        }

        try {
            var result = sandboxService.executeCommand(sessionId, command);
            if (result.isSuccess()) {
                return "执行成功：\n" + result.getBody();
            } else {
                return "执行失败：" + result.getBody();
            }
        } catch (Exception e) {
            return "执行出错：" + e.getMessage();
        }
    }
}
