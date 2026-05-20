# Sandbox Agent

一个基于 Spring Boot 的 Web 端 Agent 系统，集成了沙箱隔离环境和大语言模型，支持技能（Skills）扩展。

## 技术栈

- **后端**: Spring Boot 3.2.5 + Java 17
- **前端**: 原生 HTML/CSS/JS（无框架依赖），端口 8081
- **数据库**: MySQL + Spring Data JPA
- **沙箱**: OpenSandbox SDK（连接 `localhost:8080`）
- **LLM**: 智谱 GLM-4
- **构建**: Maven

## 核心功能

### 1. ReAct Agent

实现了 ReAct（Reasoning + Acting）模式，通过循环迭代完成任务：

1. **Thought** — 分析当前情况，决定下一步行动
2. **Action** — 选择工具并执行
3. **Observation** — 观察工具执行结果
4. 重复直到得出最终答案

### 2. 沙箱隔离

每个会话关联一个独立的沙箱环境，通过 OpenSandbox 实现：
- 命令执行隔离
- 文件系统隔离
- 会话生命周期管理（创建/关闭）

### 3. 技能系统

技能通过前端手动设置根目录后点击"加载"按钮加载本地 `SKILL.md` 文件，渐进式披露使用：

- `skill_list` — 列出所有可用技能（简历模式）
- `skill_activate` — 激活技能，加载完整指令
- `skill_reference` — 读取技能的引用文件

### 4. 工具集

Agent 可调用的工具包括：

| 工具 | 功能 |
|------|------|
| `execute_command` | 在沙箱中执行命令行 |
| `read_file` | 读取沙箱中的文件 |
| `write_file` | 写入/编辑沙箱中的文件 |
| `list_files` | 列出沙箱目录结构 |
| `request_sandbox` | 请求创建/获取沙箱 |
| `skill_list` | 列出可用技能 |
| `skill_activate` | 激活指定技能 |
| `skill_reference` | 读取技能引用文件 |

### 5. 文件上传与同步

- 支持多文件上传，上传后同步到沙箱 `/workspace/uploads/` 目录
- 支持本地存储和 OSS 存储两种模式
- 前端实时显示上传进度

## 项目结构

```
src/main/java/com/example/sandbox/web/
├── controller/          # REST API 控制器
│   ├── AgentController   # 会话与对话 API
│   ├── SkillController   # 技能管理 API
│   ├── SandboxController # 沙箱管理 API
│   └── FileUploadController # 文件上传 API
├── service/              # 业务服务层
│   ├── impl/
│   │   ├── AgentServiceImpl      # Agent 编排
│   │   ├── ReactAgent             # ReAct 核心逻辑
│   │   ├── ConversationServiceImpl # 会话管理
│   │   ├── SkillServiceImpl       # 技能加载（读文件系统）
│   │   ├── SandboxServiceImpl     # 沙箱生命周期
│   │   └── ZhipuLlmServiceImpl    # 智谱 GLM 调用
│   └── tool/             # Agent 可用工具
│       ├── ExecuteCommandTool
│       ├── ReadFileTool
│       ├── WriteFileTool
│       ├── ListFilesTool
│       ├── RequestSandboxTool
│       ├── SkillListTool
│       ├── SkillActivateTool
│       └── SkillReferenceTool
├── model/                # 数据模型
└── config/               # 配置类
```

## API 接口

**会话管理** (`/api/sessions`)

- `POST /` — 创建会话
- `GET /{id}` — 获取会话信息
- `DELETE /{id}` — 关闭会话
- `POST /{id}/chat` — 发送消息
- `GET /{id}/history` — 获取历史消息
- `GET /{id}/skills` — 获取启用的技能
- `POST /{id}/skills/{skillId}/enable` — 启用技能
- `POST /{id}/skills/{skillId}/disable` — 禁用技能

**技能管理** (`/api/skills`)

- `GET /` — 列出所有技能
- `GET /{id}` — 获取技能详情
- `POST /set-root` — 设置技能根目录

**文件上传** (`/api/files`)

- `POST /upload` — 上传文件到会话

## 配置

在 `src/main/resources/application.yml` 中配置：

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/sandbox_agent

agent:
  sandbox:
    domain: localhost:8080           # OpenSandbox 服务地址
    image: sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.2
  storage:
    type: local                      # local 或 oss
  skill:
    directory: .claude/skills        # 默认技能目录
  llm:
    api-url: https://open.bigmodel.cn/api/paas/v4
    api-key: your-api-key
    model: glm-4.7
```

## 启动

```bash
mvn spring-boot:run
```

访问 `http://localhost:8081` 打开前端界面。
