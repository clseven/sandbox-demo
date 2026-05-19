package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求沙箱工具
 *
 * <p>当 Agent 需要执行命令、读写文件时，调用此工具请求沙箱。</p>
 *
 * @author example
 * @date 2026/05/15
 */
@Component
public class RequestSandboxTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RequestSandboxTool.class);

    private static final String NAME = "request_sandbox";

    private final SandboxService sandboxService;

    public RequestSandboxTool(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reason", Map.of(
                "type", "string",
                "description", "申请沙箱的原因"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("reason")
        );

        return new ToolDefinition(
                NAME,
                "请求创建沙箱环境。当需要执行命令、运行代码、读写文件时必须先调用此工具申请沙箱。",
                parameters
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String reason = (String) arguments.get("reason");
        log.info("Sandbox requested for session {}: {}", sessionId, reason);

        try {
            sandboxService.createSandbox(sessionId);
            return "沙箱创建成功";
        } catch (Exception e) {
            log.error("Failed to create sandbox for session {}", sessionId, e);
            return "沙箱创建失败：" + e.getMessage();
        }
    }
}
