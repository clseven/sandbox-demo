# AIO Sandbox 浏览器能力调研

## 发现

### 1. 当前架构

```
SandboxClient (interface)
  └── AioSandboxClient (实现)
        - screenshot() → /v1/browser/screenshot
        - 没有 navigate 方法
```

### 2. 可用的浏览器截图方式

**方式 A：AIO sandbox API**
- 端点：`/v1/browser/screenshot`
- 能力：只能截取当前浏览器状态
- 限制：无导航 API

**方式 B：Playwright 脚本**
- 参考：`PlaywrightBaiduTest.java`
- 能力：完整控制浏览器（导航、截图、交互）
- 实现：通过 shell 命令运行 Python 脚本

### 3. 推荐方案

**使用 Playwright 脚本实现导航+截图**

理由：
1. AIO sandbox 可能没有独立的 navigate API
2. Playwright 已在沙箱中可用（AIO 包含完整浏览器环境）
3. 一个脚本完成导航+截图，原子操作更可靠

## 实现建议

在 `BrowserScreenshotTool` 中：
1. 检测是否有 URL 参数
2. 如果有 URL → 运行 Playwright 脚本导航+截图
3. 如果没有 URL → 直接调用 `/v1/browser/screenshot`

或者：
- 统一使用 Playwright 脚本（URL 可选，不提供则截当前页）
