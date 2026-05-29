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
 * 文件内容替换工具
 */
@Component
public class FileReplaceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileReplaceTool.class);
    private static final String NAME = "file_replace";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "file", Map.of(
                        "type", "string",
                        "description", "文件路径"
                ),
                "old_str", Map.of(
                        "type", "string",
                        "description", "要替换的原始文本（必须精确匹配）"
                ),
                "new_str", Map.of(
                        "type", "string",
                        "description", "替换后的新文本"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("file", "old_str", "new_str")
        );

        return new ToolDefinition(
                NAME,
                "替换文件中的指定文本。old_str 必须与文件中的内容精确匹配（包括缩进）。比 read_file + write_file 更高效。",
                parameters,
                "AIO"
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String sessionId, Map<String, Object> arguments) {
        String file = (String) arguments.get("file");
        String oldStr = (String) arguments.get("old_str");
        String newStr = (String) arguments.get("new_str");

        if (file == null || file.isBlank()) return "错误：file 不能为空";
        if (oldStr == null) return "错误：old_str 不能为空";
        if (newStr == null) return "错误：new_str 不能为空";

        try {
            AioSandboxClient client = factory.getAioClient(sessionId);
            Map<String, Object> result = client.fileReplace(file, oldStr, newStr);

            if (result == null) {
                return "错误：替换失败，无响应";
            }

            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (success) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                Number count = data != null ? (Number) data.get("replaced_count") : 0;
                log.info("文件替换成功: {} ({} 处)", file, count);
                return "替换成功，共替换 " + count + " 处";
            } else {
                return "错误：替换失败 - " + result.get("message");
            }
        } catch (Exception e) {
            log.error("文件替换失败: {}", file, e);
            return "错误：替换失败 - " + e.getMessage();
        }
    }
}
