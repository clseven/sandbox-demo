package com.example.sandbox.aio;

import com.example.sandbox.web.service.SandboxClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * AIO Sandbox 客户端实现
 *
 * <p>封装 All-in-One Sandbox 的 REST API，实现 SandboxClient 接口。</p>
 *
 * @author example
 * @date 2026/05/20
 */
public class AioSandboxClient implements SandboxClient {

    private static final Logger log = LoggerFactory.getLogger(AioSandboxClient.class);

    private final WebClient webClient;
    private String shellSessionId;

    public AioSandboxClient(String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public AioSandboxClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String execCommand(String command) {
        ShellExecResult result = shellExec(command);
        if (result.isSuccess()) {
            String output = result.getOutput();
            return result.getExitCode() == 0 ? output : "执行失败：" + output;
        }
        return "执行失败：" + result.getMessage();
    }

    @Override
    public String readFile(String path) {
        ShellExecResult result = shellExec("cat " + path);
        if (result.isSuccess() && result.getExitCode() == 0) {
            return result.getOutput();
        }
        return "读取失败：" + (result.isSuccess() ? result.getOutput() : result.getMessage());
    }

    @Override
    public void writeFile(String path, String content) {
        // 使用 base64 编码避免特殊字符问题
        String encoded = java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        ShellExecResult result = shellExec("echo '" + encoded + "' | base64 -d > " + path);
        if (!result.isSuccess() || result.getExitCode() != 0) {
            throw new RuntimeException("写入失败：" + (result.isSuccess() ? result.getOutput() : result.getMessage()));
        }
    }

    @Override
    public byte[] downloadFile(String path) {
        try {
            return webClient.get()
                    .uri("/v1/file/download?path=" + java.net.URLEncoder.encode(path, StandardCharsets.UTF_8))
                    .accept(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (Exception e) {
            log.warn("文件下载失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public byte[] screenshot() {
        try {
            return webClient.get()
                    .uri("/v1/browser/screenshot")
                    .accept(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (Exception e) {
            log.warn("截图失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public SandboxContext getContext() {
        try {
            // 调用 /v1/sandbox 获取环境信息
            Map<String, Object> resp = webClient.get()
                    .uri("/v1/sandbox")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (resp != null) {
                SandboxContext result = new SandboxContext();
                result.setHomeDir((String) resp.get("homeDir"));
                result.setWorkspace((String) resp.get("workspace"));
                return result;
            }
        } catch (Exception e) {
            log.warn("获取沙箱环境信息失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public boolean isReady() {
        return waitForReady(Duration.ofSeconds(5));
    }

    public boolean waitForReady() {
        return waitForReady(Duration.ofSeconds(30));
    }

    public boolean waitForReady(Duration timeout) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeout.toMillis();

        // 初始延迟，等待容器内部服务完全启动
        try {
            log.info("等待 AIO 服务启动（初始延迟 10 秒）...");
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        int attempts = 0;
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            attempts++;
            try {
                webClient.get()
                        .uri("/v1/shell/sessions")
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(5))
                        .block();
                log.info("AIO 服务就绪！耗时 {} ms，第 {} 次尝试", System.currentTimeMillis() - startTime, attempts);
                return true;
            } catch (Exception e) {
                log.debug("AIO 服务就绪检查失败（第 {} 次）: {}", attempts, e.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("AIO 服务未就绪，超时 {} ms，共尝试 {} 次", timeoutMs, attempts);
        return false;
    }

    // ==================== 内部方法 ====================

    public ShellExecResult shellExec(String command) {
        return shellExec(command, shellSessionId);
    }

    public ShellExecResult shellExec(String command, String sessionId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("command", command);
        if (sessionId != null && !sessionId.isEmpty()) {
            body.put("id", sessionId);
        }

        ShellExecResult result = webClient.post()
                .uri("/v1/shell/exec")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ShellExecResult.class)
                .block();

        // 保存 sessionId 供后续使用
        if (result != null && result.getData() != null && result.getData().getSessionId() != null) {
            this.shellSessionId = result.getData().getSessionId();
        }

        return result;
    }

    public ShellExecResult shellView(String sessionId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("id", sessionId);

        return webClient.post()
                .uri("/v1/shell/view")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ShellExecResult.class)
                .block();
    }

    // ==================== 响应模型 ====================

    public static class ShellExecResult {
        private boolean success;
        private String message;
        private ShellData data;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public ShellData getData() { return data; }
        public void setData(ShellData data) { this.data = data; }

        public String getOutput() {
            return data != null ? data.getOutput() : "";
        }

        public int getExitCode() {
            return data != null && data.getExitCode() != null ? data.getExitCode() : -1;
        }

        public static class ShellData {
            private String sessionId;
            private String command;
            private String status;
            private String output;
            private Integer exitCode;

            public String getSessionId() { return sessionId; }
            public void setSessionId(String sessionId) { this.sessionId = sessionId; }
            public String getCommand() { return command; }
            public void setCommand(String command) { this.command = command; }
            public String getStatus() { return status; }
            public void setStatus(String status) { this.status = status; }
            public String getOutput() { return output; }
            public void setOutput(String output) { this.output = output; }
            public Integer getExitCode() { return exitCode; }
            public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
        }
    }
}