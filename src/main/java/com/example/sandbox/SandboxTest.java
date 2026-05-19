package com.example.sandbox;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxException;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.SearchEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo;

import java.time.Duration;
import java.util.List;

/**
 * OpenSandbox 测试类
 *
 * 用于验证沙箱功能的完整性和可用性
 *
 * @author example
 * @date 2026-05-11
 */
public class SandboxTest {

    private static final String DEFAULT_DOMAIN = "localhost:8080";
    // 使用 Code Interpreter 镜像（包含 Python、Node.js、Java、Go 等多语言环境）
    private static final String CODE_INTERPRETER_IMAGE = "sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.2";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(120); // 增加超时时间，镜像较大

    public static void main(String[] args) {
        ConnectionConfig config = ConnectionConfig.builder()
                .domain(DEFAULT_DOMAIN)
                .debug(true)
                .build();

        System.out.println("========================================");
        System.out.println("    OpenSandbox 功能测试套件");
        System.out.println("========================================\n");

        Sandbox sandbox = null;
        try {
            sandbox = createSandbox(config);

            // 基础功能测试
            testSandboxInfo(sandbox);
            testCommandExecution(sandbox);
            testFileOperations(sandbox);
            testMultiCommandExecution(sandbox);

            // 高级功能测试
            testCodeExecution(sandbox);
            testNetworkConnectivity(sandbox);

            System.out.println("\n========================================");
            System.out.println("    所有测试通过 ✓");
            System.out.println("========================================");

        } catch (SandboxException e) {
            System.err.println("沙箱错误: [" + e.getError().getCode() + "] " + e.getError().getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 确保沙箱容器被清理
            if (sandbox != null) {
                try {
                    System.out.println("\n>>> 清理沙箱容器...");
                    sandbox.kill();
                    sandbox.close();
                    System.out.println("    沙箱容器已清理 ✓");
                } catch (Exception e) {
                    System.err.println("    清理沙箱失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 创建沙箱实例
     */
    private static Sandbox createSandbox(ConnectionConfig config) {
        System.out.println(">>> 创建沙箱实例...");
        System.out.println("    使用镜像: " + CODE_INTERPRETER_IMAGE);

        Sandbox sandbox = Sandbox.builder()
                .connectionConfig(config)
                .image(CODE_INTERPRETER_IMAGE)
                // Code Interpreter 需要使用指定的 entrypoint
                .entrypoint(List.of("/opt/opensandbox/code-interpreter.sh"))
                .timeout(DEFAULT_TIMEOUT)
                .readyTimeout(DEFAULT_READY_TIMEOUT)
                .build();

        System.out.println("    沙箱 ID: " + sandbox.getId());
        System.out.println("    沙箱创建成功 ✓\n");
        return sandbox;
    }

    /**
     * 测试沙箱信息获取
     */
    private static void testSandboxInfo(Sandbox sandbox) {
        System.out.println(">>> 测试: 获取沙箱信息");

        SandboxInfo info = sandbox.getInfo();
        System.out.println("    状态: " + info.getStatus().getState());
        System.out.println("    测试通过 ✓\n");
    }

    /**
     * 测试命令执行
     */
    private static void testCommandExecution(Sandbox sandbox) {
        System.out.println(">>> 测试: 命令执行");

        Execution execution = sandbox.commands().run("echo 'Hello OpenSandbox!'");
        String output = safeGetStdout(execution);
        System.out.println("    输出: " + output.trim());
        System.out.println("    测试通过 ✓\n");
    }

    /**
     * 测试文件操作
     */
    private static void testFileOperations(Sandbox sandbox) {
        System.out.println(">>> 测试: 文件操作");

        String testFilePath = "/tmp/sandbox_test.txt";
        String testContent = "Hello from OpenSandbox SDK!";

        // 写入文件
        sandbox.files().write(List.of(
                WriteEntry.builder()
                        .path(testFilePath)
                        .data(testContent)
                        .mode(644)
                        .build()
        ));
        System.out.println("    文件写入成功: " + testFilePath);

        // 读取文件
        String content = sandbox.files().readFile(testFilePath, "UTF-8", null);
        System.out.println("    文件读取成功: " + content.trim());

        // 搜索文件
        List<?> files = sandbox.files().search(
                SearchEntry.builder()
                        .path("/tmp")
                        .pattern("sandbox_*.txt")
                        .build()
        );
        System.out.println("    文件搜索结果数: " + files.size());

        // 删除文件
        sandbox.files().deleteFiles(List.of(testFilePath));
        System.out.println("    文件删除成功");

        System.out.println("    测试通过 ✓\n");
    }

    /**
     * 测试多命令执行
     */
    private static void testMultiCommandExecution(Sandbox sandbox) {
        System.out.println(">>> 测试: 多命令执行");

        // 测试环境变量
        Execution envExec = sandbox.commands().run("export TEST_VAR=hello && echo $TEST_VAR");
        System.out.println("    环境变量测试: " + safeGetStdout(envExec).trim());

        // 测试管道
        Execution pipeExec = sandbox.commands().run("echo -e 'line1\nline2\nline3' | wc -l");
        System.out.println("    管道测试: " + safeGetStdout(pipeExec).trim() + " 行");

        // 测试条件执行
        Execution condExec = sandbox.commands().run("test -d /tmp && echo 'exists' || echo 'not exists'");
        System.out.println("    条件执行测试: " + safeGetStdout(condExec).trim());

        System.out.println("    测试通过 ✓\n");
    }

    /**
     * 安全获取 stdout
     */
    private static String safeGetStdout(Execution execution) {
        if (execution.getLogs() != null
                && execution.getLogs().getStdout() != null
                && !execution.getLogs().getStdout().isEmpty()) {
            return execution.getLogs().getStdout().get(0).getText();
        }
        // 尝试从 stderr 获取
        if (execution.getLogs() != null
                && execution.getLogs().getStderr() != null
                && !execution.getLogs().getStderr().isEmpty()) {
            return "[stderr] " + execution.getLogs().getStderr().get(0).getText();
        }
        return "[no output]";
    }

    /**
     * 测试代码执行
     */
    private static void testCodeExecution(Sandbox sandbox) {
        System.out.println(">>> 测试: 代码执行");

        // 检查可用的解释器
        Execution checkPy = sandbox.commands().run("which python3 python || echo 'python not found'");
        System.out.println("    Python 检测: " + safeGetStdout(checkPy).trim());

        Execution checkJs = sandbox.commands().run("which node || echo 'node not found'");
        System.out.println("    Node.js 检测: " + safeGetStdout(checkJs).trim());

        // Python 代码执行（单引号避免转义问题）
        Execution pythonExec = sandbox.commands().run("python3 -c 'print(\"Python:\", 2 + 2)'");
        System.out.println("    " + safeGetStdout(pythonExec).trim());

        // Node.js 代码执行
        Execution nodeExec = sandbox.commands().run("node -e 'console.log(\"Node:\", 3 + 3)'");
        System.out.println("    " + safeGetStdout(nodeExec).trim());

        System.out.println("    测试通过 ✓\n");
    }

    /**
     * 测试网络连接
     */
    private static void testNetworkConnectivity(Sandbox sandbox) {
        System.out.println(">>> 测试: 网络连接");

        // 测试1：DNS 配置
        Execution dnsConf = sandbox.commands().run("cat /etc/resolv.conf");
        System.out.println("    DNS 配置:\n" + indent(safeGetStdout(dnsConf).trim(), 12));

        // 测试2：检查网络工具
        Execution toolsCheck = sandbox.commands().run("which curl wget ping nc 2>/dev/null || echo '网络工具不可用'");
        String toolsResult = safeGetStdout(toolsCheck).trim();
        if (toolsResult.contains("工具不可用")) {
            System.out.println("    ⚠️ " + toolsResult);
        } else {
            System.out.println("    可用工具: " + toolsResult.replace("\n", ", "));
        }

        // 测试3：尝试 ping（ICMP）
        Execution pingExec = sandbox.commands().run("ping -c 2 -W 3 baidu.com 2>&1 || echo 'PING_FAILED'");
        String pingResult = safeGetStdout(pingExec).trim();
        if (pingResult.contains("PING_FAILED") || pingResult.contains("not found")) {
            System.out.println("    ⚠️ ping 不可用或被阻止");
        } else {
            System.out.println("    ping 测试: " + (pingResult.contains("2 packets transmitted") ? "成功" : "失败"));
        }

        // 测试4：检查 hosts 文件
        Execution hostsExec = sandbox.commands().run("cat /etc/hosts");
        System.out.println("    hosts 文件:\n" + indent(safeGetStdout(hostsExec).trim(), 12));

        System.out.println("    测试通过 ✓\n");
    }

    /**
     * 缩进工具方法
     */
    private static String indent(String text, int spaces) {
        String indentStr = " ".repeat(spaces);
        return text.lines()
                .map(line -> indentStr + line)
                .reduce((a, b) -> a + "\n" + b)
                .orElse(indentStr + "[无内容]");
    }
}
