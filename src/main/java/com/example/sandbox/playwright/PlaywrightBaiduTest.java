package com.example.sandbox.playwright;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxException;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Playwright 沙箱测试 - 百度搜索
 *
 * 功能：访问百度 → 搜索 claude → 提取搜索结果 → 截图
 *
 * 前置条件：
 * 1. 拉取镜像：docker pull sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/playwright:latest
 * 2. 启动服务：opensandbox-server
 * 3. 构建项目：mvn clean package
 * 4. 运行：java -cp target/sandbox-1.0-SNAPSHOT.jar com.example.sandbox.playwright.PlaywrightBaiduTest
 *
 * @author example
 * @date 2026-05-12
 */
public class PlaywrightBaiduTest {

    /** OpenSandbox 服务地址 */
    private static final String DEFAULT_DOMAIN = "localhost:8080";

    /** Playwright 镜像 */
    private static final String PLAYWRIGHT_IMAGE = "sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/playwright:latest";

    /** 沙箱超时时间 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    /** 沙箱就绪超时 */
    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(120);

    /** 搜索关键词 */
    private static final String SEARCH_KEYWORD = "claude";

    /** 沙箱工作目录 */
    private static final String WORK_DIR = "/home/playwright";

    /** 搜索脚本文件名 */
    private static final String SEARCH_SCRIPT_NAME = "baidu_search.py";

    /** 截图保存路径 */
    private static final String SCREENSHOT_PATH = "/home/playwright/baidu_search_result.png";

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║  Playwright 沙箱测试 - 百度搜索 Claude         ║");
        System.out.println("╚════════════════════════════════════════════════╝\n");

        // 1. 先测试服务是否可达
        testServerConnection();

        ConnectionConfig config = ConnectionConfig.builder()
                .domain(DEFAULT_DOMAIN)
                .debug(true)
                .requestTimeout(Duration.ofMinutes(5))  // 增加请求超时，首次拉镜像需要时间
                .build();

        Sandbox sandbox = null;
        try {
            // 创建沙箱
            sandbox = createSandbox(config);

            // 检查环境
            checkEnvironment(sandbox);

            // 执行搜索测试
            executeSearchTest(sandbox, SEARCH_KEYWORD);

            // 下载截图到本地
            downloadScreenshot(sandbox);

            System.out.println("\n╔════════════════════════════════════════════════╗");
            System.out.println("║  测试完成 ✓                                   ║");
            System.out.println("╚════════════════════════════════════════════════╝");

        } catch (SandboxException e) {
            System.err.println("\n❌ 沙箱错误: [" + e.getError().getCode() + "] " + e.getError().getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("\n❌ 异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (sandbox != null) {
                cleanupSandbox(sandbox);
            }
        }
    }

    /**
     * 测试服务器连接
     */
    private static void testServerConnection() {
        System.out.println(">>> 测试服务器连接...");
        System.out.println("    目标: " + DEFAULT_DOMAIN);

        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("http://" + DEFAULT_DOMAIN + "/health").openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.connect();

            int code = conn.getResponseCode();
            if (code == 200 || code == 404) {
                System.out.println("    ✓ 服务器可达 (HTTP " + code + ")\n");
            } else {
                System.out.println("    ⚠️ 服务器响应异常 (HTTP " + code + ")\n");
            }
            conn.disconnect();
        } catch (Exception e) {
            System.err.println("    ❌ 无法连接服务器: " + e.getMessage());
            System.err.println("    请确认 opensandbox-server 正在运行！");
            System.err.println("    启动命令: uvx opensandbox-server\n");
            System.exit(1);
        }
    }

    /**
     * 创建沙箱实例
     */
    private static Sandbox createSandbox(ConnectionConfig config) {
        System.out.println(">>> 创建沙箱实例...");
        System.out.println("    镜像: " + PLAYWRIGHT_IMAGE);
        System.out.println("    服务: " + DEFAULT_DOMAIN);
        System.out.println("    [开始构建 Sandbox...]");

        Sandbox sandbox = Sandbox.builder()
                .connectionConfig(config)
                .image(PLAYWRIGHT_IMAGE)
                .timeout(DEFAULT_TIMEOUT)
                .readyTimeout(DEFAULT_READY_TIMEOUT)
                .build();

        System.out.println("    [Sandbox.build() 返回]");
        System.out.println("    沙箱 ID: " + sandbox.getId());

        System.out.println("    [获取沙箱状态...]");
        System.out.println("    沙箱状态: " + sandbox.getInfo().getStatus().getState());
        System.out.println("    ✓ 沙箱创建成功\n");

        return sandbox;
    }

    /**
     * 检查 Playwright 环境
     */
    private static void checkEnvironment(Sandbox sandbox) {
        System.out.println(">>> 检查环境...");

        // 检查 Python
        Execution pyCheck = sandbox.commands().run("python3 --version 2>&1");
        System.out.println("    Python: " + safeGetStdout(pyCheck).trim());

        // 检查 Playwright
        Execution pwCheck = sandbox.commands().run("python3 -c 'import playwright; print(\"Playwright \" + playwright.__version__)' 2>&1");
        System.out.println("    " + safeGetStdout(pwCheck).trim());

        // 检查 Playwright 浏览器
        System.out.println("    [检查 Playwright 浏览器...]");
        Execution browsers = sandbox.commands().run("python3 -c 'from playwright.sync_api import sync_playwright; p = sync_playwright().start(); print(\"Chromium:\", p.chromium.executable_path); p.stop()' 2>&1");
        System.out.println("    " + safeGetStdout(browsers).trim());

        // 检查工作目录
        Execution workDir = sandbox.commands().run("ls -la /home/playwright/ 2>&1 | head -5");
        System.out.println("    工作目录: " + safeGetStdout(workDir).trim());

        System.out.println();
    }

    /**
     * 执行搜索测试
     */
    private static void executeSearchTest(Sandbox sandbox, String keyword) throws Exception {
        System.out.println(">>> 执行搜索测试: " + keyword);

        // 构建 Python 脚本
        String searchScript = buildSearchScript(keyword);
        String scriptPath = WORK_DIR + "/" + SEARCH_SCRIPT_NAME;

        // 写入脚本到沙箱
        sandbox.files().write(List.of(
                WriteEntry.builder()
                        .path(scriptPath)
                        .data(searchScript)
                        .mode(755)
                        .build()
        ));
        System.out.println("    ✓ 脚本已写入: " + scriptPath);

        // 验证脚本写入成功
        Execution catCheck = sandbox.commands().run("head -5 " + scriptPath + " 2>&1");
        System.out.println("    脚本前5行: " + safeGetStdout(catCheck).trim().replace("\n", " | "));

        // 执行脚本
        System.out.println("\n>>> 运行搜索脚本...");
        System.out.println("─".repeat(50));
        System.out.println("    [开始执行 python3 " + SEARCH_SCRIPT_NAME + "]");

        Execution exec = sandbox.commands().run(
                "cd " + WORK_DIR + " && timeout 60 python3 " + SEARCH_SCRIPT_NAME + " 2>&1"
        );

        System.out.println("    [脚本执行完成，退出码: " + exec.getExitCode() + "]");

        // 打印输出
        printExecutionOutput(exec);

        // 检查是否成功
        if (exec.getExitCode() != null && exec.getExitCode() != 0) {
            throw new RuntimeException("搜索脚本执行失败，退出码: " + exec.getExitCode());
        }

        System.out.println("─".repeat(50));
        System.out.println("    ✓ 搜索完成\n");
    }

    /**
     * 构建搜索脚本
     */
    private static String buildSearchScript(String keyword) {
        return """
import asyncio
from playwright.async_api import async_playwright


async def search_bing(keyword):
    # 必应搜索 - 直接用 URL 访问搜索结果页
    results = []

    async with async_playwright() as p:
        # 启动浏览器
        browser = await p.chromium.launch(
            headless=True,
            args=['--no-sandbox', '--disable-setuid-sandbox']
        )
        page = await browser.new_page(viewport={"width": 1920, "height": 1080})

        try:
            # 直接访问搜索结果页
            search_url = f"https://www.bing.com/search?q={keyword}"
            print(f">>> 访问: {search_url}")
            await page.goto(search_url, wait_until="domcontentloaded", timeout=30000)
            print("    页面标题: " + await page.title())

            # 等待结果加载
            print(">>> 等待搜索结果...")
            await page.wait_for_selector(".b_algo", timeout=15000)
            await asyncio.sleep(2)

            # 提取搜索结果
            print("\\n" + "=" * 60)
            print("搜索结果 (前5条):")
            print("=" * 60)

            results = await page.query_selector_all(".b_algo")

            print(f"找到 {len(results)} 条结果\\n")

            for i, result in enumerate(results[:5], 1):
                try:
                    # 标题
                    title_elem = await result.query_selector("h2 a")
                    title = await title_elem.inner_text() if title_elem else "无标题"

                    # 摘要
                    snippet_elem = await result.query_selector("p, .b_caption p")
                    snippet = await snippet_elem.inner_text() if snippet_elem else "无摘要"

                    # 链接
                    link_elem = await result.query_selector("h2 a")
                    link = await link_elem.get_attribute("href") if link_elem else "无链接"

                    print(f"[{i}] {title.strip()}")
                    print(f"    摘要: {snippet[:150].strip()}...")
                    print(f"    链接: {link}")
                    print()
                except Exception as e:
                    print(f"[{i}] 解析失败: {e}")
                    continue

            # 截图
            screenshot_path = "%s"
            await page.screenshot(path=screenshot_path, full_page=True)
            print(f">>> 截图已保存: {screenshot_path}")

        finally:
            await browser.close()
            print("\\n>>> 浏览器已关闭")


if __name__ == "__main__":
    asyncio.run(search_bing("%s"))
""".formatted(SCREENSHOT_PATH, keyword);
    }

    /**
     * 下载截图到本地
     */
    private static void downloadScreenshot(Sandbox sandbox) {
        System.out.println(">>> 下载截图...");

        try {
            // 检查截图文件是否存在
            Execution lsResult = sandbox.commands().run("ls -la " + SCREENSHOT_PATH + " 2>&1");
            String output = safeGetStdout(lsResult).trim();

            if (output.contains("No such file") || output.contains("不存在")) {
                System.out.println("    ⚠️ 截图文件不存在");
                return;
            }

            // 读取截图（返回 Base64 字符串）
            String base64Data = sandbox.files().readFile(SCREENSHOT_PATH, null, null);

            // Base64 转字节数组并保存
            byte[] screenshotData = java.util.Base64.getDecoder().decode(base64Data);
            String localPath = "screenshot_" + System.currentTimeMillis() + ".png";
            Path localFilePath = Path.of(localPath);
            Files.write(localFilePath, screenshotData);

            System.out.println("    ✓ 截图已保存: " + localFilePath.toAbsolutePath());
            System.out.println("    大小: " + (screenshotData.length / 1024) + " KB");

        } catch (Exception e) {
            System.err.println("    ⚠️ 下载截图失败: " + e.getMessage());
        }
    }

    /**
     * 清理沙箱
     */
    private static void cleanupSandbox(Sandbox sandbox) {
        System.out.println("\n>>> 清理沙箱...");
        try {
            sandbox.kill();
            sandbox.close();
            System.out.println("    ✓ 沙箱已清理");
        } catch (Exception e) {
            System.err.println("    ⚠️ 清理失败: " + e.getMessage());
        }
    }

    /**
     * 打印执行输出
     */
    private static void printExecutionOutput(Execution execution) {
        if (execution.getLogs() != null) {
            if (execution.getLogs().getStdout() != null) {
                for (var msg : execution.getLogs().getStdout()) {
                    System.out.println(msg.getText());
                }
            }
            if (execution.getLogs().getStderr() != null && !execution.getLogs().getStderr().isEmpty()) {
                System.err.println("\n[stderr 输出]");
                for (var msg : execution.getLogs().getStderr()) {
                    System.err.println(msg.getText());
                }
            }
        }
    }

    /**
     * 安全获取 stdout
     */
    private static String safeGetStdout(Execution execution) {
        if (execution != null && execution.getLogs() != null
                && execution.getLogs().getStdout() != null
                && !execution.getLogs().getStdout().isEmpty()) {
            return execution.getLogs().getStdout().get(0).getText();
        }
        return "";
    }
}
