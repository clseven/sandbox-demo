package com.example.sandbox.web.service.tool;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器截图工具
 */
@Component
public class BrowserScreenshotTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BrowserScreenshotTool.class);
    private static final String NAME = "browser_screenshot";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", new LinkedHashMap<>(),
                "required", java.util.List.of()
        );

        return new ToolDefinition(
                NAME,
                "截取浏览器当前页面的截图。截图会保存到沙箱 /tmp/screenshot.png，返回文件路径和查看链接。",
                parameters,
                "AIO"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            var client = factory.getAioClient(sessionId);

            // 等待 AIO 服务就绪
            if (!client.waitForReady()) {
                return "错误：AIO 服务未就绪（等待 30 秒后仍无法连接），请稍后重试";
            }

            byte[] screenshot = client.screenshot();
            if (screenshot == null || screenshot.length == 0) {
                return "错误：截图为空";
            }

            // 保存截图到沙箱文件
            String filePath = "/tmp/screenshot_" + Instant.now().toEpochMilli() + ".png";
            String base64 = Base64.getEncoder().encodeToString(screenshot);

            // 用 shell 执行命令，将 base64 解码写入文件
            String saveCmd = "echo '" + base64 + "' | base64 -d > " + filePath;
            AioSandboxClient.ShellExecResult result = client.shellExec(saveCmd);

            if (result.isSuccess() && result.getExitCode() == 0) {
                log.info("截图成功，大小: {} bytes，路径: {}", screenshot.length, filePath);
                String encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8);
                return "截图成功！文件路径: " + filePath + "，大小: " + screenshot.length + " bytes\n\n" +
                       "查看图片: /api/sessions/" + sessionId + "/aio/download?path=" + encodedPath;
            } else {
                return "截图保存失败：" + result.getMessage();
            }
        } catch (Exception e) {
            log.error("截图失败", e);
            return "截图失败：" + e.getMessage();
        }
    }
}