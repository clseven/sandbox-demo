package com.example.sandbox.web.service.tool;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 终止进程工具
 */
@Component
public class ShellKillTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ShellKillTool.class);
    private static final String NAME = "shell_kill";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "session_id", Map.of(
                        "type", "string",
                        "description", "要终止的 Shell 会话 ID（由 execute_command 返回）"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("session_id")
        );

        return new ToolDefinition(
                NAME,
                "终止沙箱中指定 shell 会话的进程。适用于命令卡死或需要中途停止的场景。",
                parameters,
                "AIO"
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String sessionId, Map<String, Object> arguments) {
        String shellSessionId = (String) arguments.get("session_id");
        if (shellSessionId == null || shellSessionId.isBlank()) {
            return "错误：session_id 不能为空";
        }

        try {
            AioSandboxClient client = factory.getAioClient(sessionId);
            Map<String, Object> result = client.shellKill(shellSessionId);

            if (result == null) {
                return "错误：终止进程失败，无响应";
            }

            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (success) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                String status = data != null ? (String) data.get("status") : "unknown";
                Number returncode = data != null ? (Number) data.get("returncode") : null;
                log.info("进程已终止: session={} status={} returncode={}", shellSessionId, status, returncode);
                return "进程已终止，状态: " + status + (returncode != null ? "，退出码: " + returncode : "");
            } else {
                return "错误：终止进程失败 - " + result.get("message");
            }
        } catch (Exception e) {
            log.error("终止进程失败: session={}", shellSessionId, e);
            return "错误：终止进程失败 - " + e.getMessage();
        }
    }
}
