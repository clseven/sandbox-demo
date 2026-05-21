# AIO Sandbox API 参考

> **Agent Infrastructure Sandbox** - 智能体基础设施沙箱，提供代码执行和浏览器自动化环境。

## 重要区分

项目中存在 **两种沙箱类型**：

| 类型 | 操作方式 | 工具前缀 |
|------|----------|----------|
| **AIO Sandbox** | 只能通过 REST API 操控 | `AIO` |
| **OpenSandbox** | 使用 SDK 直接操控 | `ALL` 或 `COMMON` |

**AIO 沙箱关键特点**：
- 所有操作通过 HTTP REST API
- 沙箱 endpoint 在创建时动态分配端口（如 `127.0.0.1:43360`）
- 浏览器操作使用专用 Browser API，不要用 Playwright 脚本

---

## API 端点总览

### 🌐 Browser（浏览器自动化）

| 接口 | 功能 |
|------|------|
| `GET /v1/browser/info` | 获取浏览器状态信息 |
| `GET /v1/browser/screenshot` | 截取浏览器屏幕截图 |
| `POST /v1/browser/actions` | 执行浏览器操作（键鼠模拟） |

**浏览器操作类型**（14 种）：

| 操作 | 说明 | 参数 |
|------|------|------|
| `MOVE_TO` | 移动鼠标到绝对坐标 | `x`, `y` |
| `MOVE_REL` | 相对移动鼠标 | `x`, `y` |
| `CLICK` | 左键单击 | `x`, `y` |
| `DOUBLE_CLICK` | 左键双击 | `x`, `y` |
| `RIGHT_CLICK` | 右键点击 | `x`, `y` |
| `MOUSE_DOWN` | 按下鼠标键 | `x`, `y`, `button` |
| `MOUSE_UP` | 释放鼠标键 | `x`, `y`, `button` |
| `DRAG_TO` | 拖拽到目标位置 | `x`, `y` |
| `DRAG_REL` | 相对拖拽 | `x`, `y` |
| `TYPING` | 输入文本 | `text`, `use_clipboard` (Linux 必须 `false`) |
| `KEY_DOWN` | 按下某个键 | `key` |
| `KEY_UP` | 释放某个键 | `key` |
| `PRESS` | 按单个键 | `key` (string, 小写如 `"enter"`) |
| `HOTKEY` | 组合键 | `keys: ["control", "l"]` |
| `SCROLL` | 滚动页面 | `x`, `y`, `scroll_x`, `scroll_y` |
| `WAIT` | 等待指定时间 | `wait` |

**导航到 URL 示例**（键鼠模拟方式）：
```json
// 1. Ctrl+L 选中地址栏
POST /v1/browser/actions
{ "action": "HOTKEY", "keys": ["control", "l"] }

// 2. 输入 URL
{ "action": "TYPING", "text": "https://www.baidu.com" }

// 3. 按 Enter
{ "action": "PRESS", "keys": ["Enter"] }

// 4. 等待加载
{ "action": "WAIT", "wait": 2000 }

// 5. 截图
GET /v1/browser/screenshot
```

### 💻 Shell（命令行执行）

| 接口 | 功能 |
|------|------|
| `POST /v1/shell/exec` | 执行单次命令 |
| `POST /v1/shell/view` | 查看运行中的 shell 会话输出 |
| `POST /v1/shell/wait` | 等待某个进程完成 |
| `POST /v1/shell/write` | 向运行中的进程写入输入 |
| `POST /v1/shell/kill` | 终止指定进程 |
| `POST /v1/shell/sessions/create` | 创建持久化 shell 会话 |
| `GET /v1/shell/sessions` | 列出所有活跃的 shell 会话 |

### 📁 File（文件操作）

| 接口 | 功能 |
|------|------|
| `POST /v1/file/read` | 读取文件内容 |
| `POST /v1/file/write` | 写入/创建文件 |
| `POST /v1/file/replace` | 在文件中查找替换 |
| `POST /v1/file/search` | 在文件中搜索内容 |
| `POST /v1/file/find` | 查找文件 |
| `POST /v1/file/upload` | 上传文件到沙箱 |
| `GET /v1/file/download` | 从沙箱下载文件 |
| `POST /v1/file/list` | 列出目录内容 |

### 📋 Sandbox（沙箱管理）

| 接口 | 功能 |
|------|------|
| `GET /v1/sandbox` | 获取沙箱上下文信息 |
| `GET /v1/sandbox/packages/python` | 查看已安装的 Python 包 |
| `GET /v1/sandbox/packages/nodejs` | 查看已安装的 Node.js 包 |

### 📓 Jupyter（笔记本）

| 接口 | 功能 |
|------|------|
| `POST /v1/jupyter/execute` | 执行 Jupyter 代码 |
| `GET /v1/jupyter/info` | 获取 Jupyter 环境信息 |

### 🔧 Node.js（JavaScript 执行）

| 接口 | 功能 |
|------|------|
| `POST /v1/nodejs/execute` | 执行 Node.js 代码 |

### 🐍 Code（代码执行）

| 接口 | 功能 |
|------|------|
| `POST /v1/code/execute` | 通用代码执行 |

---

## 工具实现指南

### BrowserActionTool ✅ 已实现（通用浏览器操作工具）

- 参数：`action_type` + 对应参数
- 支持：HOTKEY, TYPING, PRESS, CLICK, MOVE_TO, SCROLL, WAIT
- 日志清晰，每步操作独立记录

### BrowserScreenshotTool ✅ 已实现

- 接受可选 `url` 参数
- 如果提供 URL → 调用 `browserAction` 执行导航（HOTKEY → TYPING → PRESS）
- 调用 `GET /v1/browser/screenshot` 截图
- 用 `POST /v1/file/write` 保存（base64 编码）

### DownloadFileTool ✅ 已实现

- 参数：`path` - 沙箱文件路径
- 返回：下载链接

### AioSandboxClient ✅ 已扩展

已实现的方法：
- `browserAction(Map)` - 通用浏览器操作（推荐使用）
- `navigate(String)` - 导航到 URL（内部调用 browserAction）
- `screenshot()` - 调用 `/v1/browser/screenshot`
- `browserInfo()` - 调用 `/v1/browser/info`
- `writeFile(path, bytes)` - 调用 `/v1/file/write`
- `downloadFile(path)` - 调用 `/v1/file/download`
- `shellExec()` - 调用 `/v1/shell/exec`
- `getContext()` - 调用 `/v1/sandbox`

---

## 请求/响应格式

详细参数格式需要时询问用户。
