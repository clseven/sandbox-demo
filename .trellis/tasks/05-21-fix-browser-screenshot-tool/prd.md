# 修复 BrowserScreenshotTool 工具描述和能力

## 问题描述

### 现状
`BrowserScreenshotTool` 存在两个问题：

1. **工具描述不准确**
   - 当前描述: `"截取浏览器当前页面的截图。截图会保存到沙箱 /tmp/screenshot.png，返回文件路径和查看链接。"`
   - 描述暗示只能截"当前页面"，没有说明工具实际能力

2. **缺少 URL 参数**
   - 工具只能调用 `/v1/browser/screenshot` 截取当前状态
   - 没有 URL 导航能力
   - 当用户请求"截取某个网站的截图"时，LLM 会错误地选择 `execute_command` 来运行 chromium 命令

### 根本原因
LLM 看到工具描述后，推理需要先导航到 URL → 使用 execute_command 运行 chromium → 这是工具能力不足导致的合理推理

## 目标

让 `browser_screenshot` 工具能够：
1. 接收 URL 参数（可选）
2. 如果提供了 URL，先导航到该 URL 再截图
3. 更新描述准确反映工具能力

## 实现方案

### 方案：使用 Playwright 脚本实现导航+截图 ✅ 已实现

**修改文件**: `src/main/java/com/example/sandbox/web/service/tool/BrowserScreenshotTool.java`

1. **添加 URL 参数**
   - 参数 schema 添加 `url` 字段（可选，string 类型）
   - 描述说明参数用途

2. **实现双模式逻辑**
   - 有 URL：调用 `navigateAndScreenshot()` 使用 Playwright 脚本
   - 无 URL：调用原有 `client.screenshot()` 截取当前页面

3. **更新工具描述**
   - 改为："截取网页截图。可指定 URL 导航后截图，或截取浏览器当前页面。截图保存到沙箱临时目录并返回查看链接。"

4. **Playwright 脚本**
   - 自动添加 `https://` 前缀（如果 URL 没有协议）
   - 无头模式启动 Chromium
   - 导航到 URL 后等待 2 秒确保页面加载
   - 截图保存到沙箱临时目录

## 验收标准

- [ ] `browser_screenshot` 工具支持可选 `url` 参数
- [ ] 提供 URL 时先导航再截图
- [ ] 工具描述准确反映能力
- [ ] LLM 能正确选择 `browser_screenshot` 而非 `execute_command` 来完成截图任务

## 技术调研

需要确认：
1. AIO sandbox 是否有 `/v1/browser/navigate` 或类似 API
2. 如果没有，Playwright 脚本如何实现导航+截图
