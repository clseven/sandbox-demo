package com.example.sandbox.aio;

import com.example.sandbox.web.service.SandboxClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
            String uri = org.springframework.web.util.UriComponentsBuilder
                    .fromPath("/v1/file/download")
                    .queryParam("path", path)
                    .build()
                    .toUriString();
            log.debug("File: download {}", uri);

            return webClient.get()
                    .uri(uri)
                    .accept(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (Exception e) {
            log.warn("文件下载失败: {} - {}", path, e.getMessage());
            return null;
        }
    }

    @Override
    public byte[] screenshot() {
        try {
            log.debug("Browser: GET /v1/browser/screenshot");
            var response = webClient.get()
                    .uri("/v1/browser/screenshot")
                    .accept(org.springframework.http.MediaType.IMAGE_PNG)
                    .retrieve()
                    .toEntity(byte[].class)
                    .block();

            if (response != null && response.getBody() != null) {
                log.info("截图成功，大小: {} bytes", response.getBody().length);
                return response.getBody();
            }
            log.warn("截图响应为空");
            return null;
        } catch (Exception e) {
            log.error("截图失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 保存文件（二进制，使用 base64 编码）
     */
    public boolean writeFile(String path, byte[] content) {
        try {
            String base64 = java.util.Base64.getEncoder().encodeToString(content);
            Map<String, Object> body = Map.of(
                    "file", path,
                    "content", base64,
                    "encoding", "base64"
            );
            log.debug("File: write {} ({} bytes)", path, content.length);

            Map<String, Object> result = webClient.post()
                    .uri("/v1/file/write")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            boolean success = result != null && Boolean.TRUE.equals(result.get("success"));
            if (success) {
                log.info("文件写入成功: {}", path);
            } else {
                log.warn("文件写入失败: {}", result);
            }
            return success;
        } catch (Exception e) {
            log.error("文件写入失败: {} - {}", path, e.getMessage());
            return false;
        }
    }

    /**
     * 浏览器导航到指定 URL
     * 使用键鼠模拟操作：Ctrl+L → 输入 URL → Enter
     *
     * @param url 目标 URL
     * @return 是否成功
     */
    public boolean navigate(String url) {
        // 确保 URL 有协议前缀
        String normalizedUrl = url;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            normalizedUrl = "https://" + url;
        }

        try {
            // 1. Ctrl+L 选中地址栏
            log.debug("Browser: HOTKEY ctrl+l");
            Map<String, Object> hotkeyBody = Map.of(
                    "action_type", "HOTKEY",
                    "keys", List.of("ctrl", "l")
            );
            webClient.post()
                    .uri("/v1/browser/actions")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(hotkeyBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            // 2. 输入 URL（Linux 环境需要 use_clipboard: false）
            log.debug("Browser: TYPING {}", normalizedUrl);
            Map<String, Object> typingBody = Map.of(
                    "action_type", "TYPING",
                    "text", normalizedUrl,
                    "use_clipboard", false
            );
            webClient.post()
                    .uri("/v1/browser/actions")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(typingBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            // 3. 按 Enter
            log.debug("Browser: PRESS enter");
            Map<String, Object> pressBody = Map.of(
                    "action_type", "PRESS",
                    "key", "enter"
            );
            webClient.post()
                    .uri("/v1/browser/actions")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(pressBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("浏览器导航成功: {}", normalizedUrl);
            return true;
        } catch (Exception e) {
            log.error("浏览器导航失败: {} - {}", normalizedUrl, e.getMessage());
            return false;
        }
    }

    /**
     * 等待页面加载完成
     * 轮询 browser/info，最多等待 maxWaitSeconds 秒
     *
     * @param maxWaitSeconds 最大等待秒数
     */
    public void waitForPageLoad(int maxWaitSeconds) {
        try {
            // 先等 1 秒让导航请求发出
            Thread.sleep(1000);

            for (int i = 0; i < maxWaitSeconds; i++) {
                // 用 WAIT 操作让浏览器等待 1 秒
                browserAction(Map.of("action_type", "WAIT", "wait", 1000));

                // 检查浏览器是否可响应
                Map<String, Object> info = browserInfo();
                if (info != null && Boolean.TRUE.equals(info.get("success"))) {
                    log.info("页面加载完成，等待了 {} 秒", i + 1);
                    return;
                }
            }
            log.info("页面加载等待超时（{}秒），继续执行", maxWaitSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行浏览器操作（通用方法）
     *
     * @param action 操作参数
     * @return 是否成功
     */
    public boolean browserAction(Map<String, Object> action) {
        String actionType = (String) action.get("action_type");
        try {
            // 构建请求体，过滤掉空值
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("action_type", actionType);

            // 根据操作类型添加参数
            switch (actionType) {
                case "HOTKEY" -> {
                    List<String> keys = (List<String>) action.get("keys");
                    body.put("keys", keys);
                    log.debug("Browser: HOTKEY {}", keys);
                }
                case "PRESS" -> {
                    String key = (String) action.get("key");
                    body.put("key", key);
                    log.debug("Browser: PRESS {}", key);
                }
                case "TYPING" -> {
                    String text = (String) action.get("text");
                    Boolean useClipboard = (Boolean) action.get("use_clipboard");
                    body.put("text", text);
                    body.put("use_clipboard", useClipboard != null ? useClipboard : false);
                    log.debug("Browser: TYPING {} chars", text.length());
                }
                case "CLICK", "MOVE_TO" -> {
                    body.put("x", action.get("x"));
                    body.put("y", action.get("y"));
                    log.debug("Browser: {} ({}, {})", actionType, action.get("x"), action.get("y"));
                }
                case "SCROLL" -> {
                    body.put("x", action.get("x"));
                    body.put("y", action.get("y"));
                    body.put("scroll_x", action.get("scroll_x"));
                    body.put("scroll_y", action.get("scroll_y"));
                    log.debug("Browser: SCROLL");
                }
                case "WAIT" -> {
                    body.put("wait", action.get("wait"));
                    log.debug("Browser: WAIT {}ms", action.get("wait"));
                }
                default -> {
                    log.warn("未知的浏览器操作类型: {}", actionType);
                    return false;
                }
            }

            webClient.post()
                    .uri("/v1/browser/actions")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("浏览器操作成功: {}", actionType);
            return true;
        } catch (Exception e) {
            log.error("浏览器操作失败: {} - {}", actionType, e.getMessage());
            return false;
        }
    }

    /**
     * 获取浏览器信息
     */
    public Map<String, Object> browserInfo() {
        try {
            return webClient.get()
                    .uri("/v1/browser/info")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception e) {
            log.warn("获取浏览器信息失败: {}", e.getMessage());
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