# 添加 LLM 调用链路和 Token 消耗的前端可视化

## Goal

每次用户发消息后，前端不仅显示最终回复，还能展开看到完整的调用链路：PlanAgent 规划 → ReactAgent 每一步的 Thought/Action/Observation → 每次 LLM API 调用的 Token 消耗。类似 claude-tap 的简化版，集成在项目自身 UI 中。

## What I already know

- Spring Boot 3.2.5 + 纯 HTML/CSS/JS 前端
- PlanAgent（Zhipu GLM-4.7）→ ReactAgent（DeepSeek V4 Flash）双层架构
- ReactAgent 的 ReAct 循环（Thought/Action/Observation）目前仅 log 输出
- LLM API 响应包含 `usage` 字段，当前完全忽略
- 前端是阻塞式 fetch POST，无 SSE/WebSocket

## Decision (ADR-lite)

**Context**: 需要在现有阻塞式请求-响应架构中加入调用链路可视化
**Decision**: 采用方案 B（事后展示），在 chat API 响应 JSON 中附带完整 `trace` 数组，前端一次性渲染为可折叠时间线。不改 API 的请求-响应模式，不引入 SSE。
**Consequences**: 用户需等完整响应后才能看到 trace（非实时），但实现简单、改动小、无新增依赖。

## Requirements

### 后端
- [ ] `LlmResponse` 增加 `TokenUsage`（promptTokens, completionTokens, totalTokens）
- [ ] ZhipuLlmServiceImpl / DeepSeekLlmServiceImpl 解析 API 响应中的 `usage` 字段
- [ ] 新增 `TraceEvent` 模型（type, timestamp, data），类型：PLAN / THOUGHT / ACTION / OBSERVATION / FINAL
- [ ] AgentServiceImpl.chat() 在编排过程中收集 TraceEvent 列表
- [ ] chat API 响应 JSON 增加 `trace` 字段（events + totalTokens）

### 前端
- [ ] 每条助手消息下方增加可折叠的"查看思考过程"区域
- [ ] 时间线样式展示 trace events（不同类型不同颜色/图标）
- [ ] 显示每次 LLM 调用的 Token 消耗
- [ ] 最终汇总本轮总 Token
- [ ] 纯 CSS/JS，零外部依赖

## Out of Scope

- 实时 SSE 流式推送
- Token 历史统计图表
- Token 数据持久化到 DB
- 多轮对话的 token 趋势分析
- 请求 diff 对比（claude-tap 的核心功能）

## Technical Design

### 1. 数据模型

```java
// 新增：LlmService.java 内部类
class TokenUsage {
    int promptTokens;
    int completionTokens;  
    int totalTokens;
}

// 修改：LlmResponse 增加字段
class LlmResponse {
    String content;
    ToolCall toolCall;
    boolean finished;
    TokenUsage tokenUsage;  // NEW
}

// 新增：TraceEvent.java
class TraceEvent {
    String type;       // PLAN | THOUGHT | ACTION | OBSERVATION | FINAL
    long timestamp;
    String title;      // 简短标题，如 "规划完成"、"第1步：read_file"
    String content;    // 详细内容
    TokenUsage tokens; // 仅 LLM 调用类事件携带
}
```

### 2. 后端改动点

**LlmService 层**（改动小）
- `LlmResponse` 加 `TokenUsage` 字段
- `ZhipuLlmServiceImpl` 和 `DeepSeekLlmServiceImpl` 在解析响应时提取 `usage` 节点

**AgentServiceImpl 层**（改动中等）
- `chat()` 方法内创建一个 `List<TraceEvent>` 收集器
- PlanAgent 返回 plan 后，添加 PLAN 事件
- ReactAgent 每次迭代时，添加 THOUGHT/ACTION/OBSERVATION 事件
- LLM 每次调用后，从 LlmResponse 提取 tokenUsage 附加到事件上
- 最终返回包含 trace 的响应

**Controller 层**（改动小）
- chat 接口的响应 JSON 增加 `trace` 字段

### 3. 前端改动点

**新增 CSS 样式**
- `.trace-panel` — 折叠面板容器
- `.trace-timeline` — 时间线（左侧竖线 + 圆点）
- `.trace-event` — 单条事件，按类型着色
- `.token-badge` — Token 消耗小标签

**新增 JS 逻辑**
- 解析响应中的 `trace` 字段
- 为每条 assistant 消息渲染可折叠 trace 面板
- 点击"查看思考过程"展开/收起

### 4. 前端 UI 示意

```
┌─────────────────────────────────────────┐
│ 助手                                    │
│ ┌─────────────────────────────────────┐ │
│ │ 已为您生成了数据分析报告...          │ │
│ └─────────────────────────────────────┘ │
│ ▶ 查看思考过程 (3步 · 1801 tokens)     │  ← 可折叠
│                                         │
│ ▼ 查看思考过程 (3步 · 1801 tokens)     │  ← 展开后
│ ┌─────────────────────────────────────┐ │
│ │ 📋 规划完成                    0ms  │ │
│ │   意图: 生成数据分析报告            │ │
│ │   步骤: 1.读取文件 2.分析数据...     │ │
│ │                                     │ │
│ │ 💭 第1步思考               +230ms  │ │
│ │   需要先读取上传的 CSV 文件...       │ │
│ │   📊 1,234 tokens                   │ │
│ │                                     │ │
│ │ 🔧 调用工具: read_file      +450ms  │ │
│ │   参数: {path: "/workspace/..."}    │ │
│ │                                     │ │
│ │ 👁️ 观察结果                +890ms  │ │
│ │   name,age,city\n张三,28,北京\n...   │ │
│ │                                     │ │
│ │ 💬 最终回复              +1,200ms  │ │
│ │   📊 567 tokens                    │ │
│ │                                     │ │
│ │ ──────────────────────────────      │ │
│ │ 📊 本轮总计: 1,801 tokens           │ │
│ │   输入 1,234 · 输出 567             │ │
│ └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

### 5. API 响应格式变化

```json
{
  "code": 200,
  "data": {
    "content": "已为您生成了数据分析报告...",
    "trace": {
      "events": [
        {
          "type": "PLAN",
          "title": "规划完成",
          "content": "意图: 生成数据分析报告\n步骤: 1.读取CSV 2.分析数据 3.生成报告",
          "timestamp": 1716000000000
        },
        {
          "type": "THOUGHT",
          "title": "第1步思考",
          "content": "需要先读取上传的 CSV 文件...",
          "timestamp": 1716000000230,
          "tokens": {"promptTokens": 1234, "completionTokens": 120, "totalTokens": 1354}
        },
        {
          "type": "ACTION",
          "title": "调用工具: read_file",
          "content": "参数: {\"path\": \"/workspace/uploads/data.csv\"}",
          "timestamp": 1716000000450
        },
        {
          "type": "OBSERVATION",
          "title": "观察结果",
          "content": "name,age,city\n张三,28,北京\n...",
          "timestamp": 1716000000890
        },
        {
          "type": "FINAL",
          "title": "最终回复",
          "content": "",
          "timestamp": 1716000001200,
          "tokens": {"promptTokens": 0, "completionTokens": 567, "totalTokens": 567}
        }
      ],
      "totalTokens": {"promptTokens": 1234, "completionTokens": 687, "totalTokens": 1921}
    }
  }
}
```

### 6. 改动文件清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `LlmService.java` | 修改 | 新增 TokenUsage 内部类，LlmResponse 加字段 |
| `ZhipuLlmServiceImpl.java` | 修改 | chatWithSystem/chatWithTools 解析 usage |
| `DeepSeekLlmServiceImpl.java` | 修改 | chatWithSystem/chatWithTools 解析 usage |
| `AgentServiceImpl.java` | 修改 | 收集 TraceEvent，返回带 trace 的响应 |
| `ReactAgent.java` | 修改 | 每步发布事件（通过回调或返回结构） |
| `PlanAgent.java` | 修改 | 返回 plan 时附带 token 信息 |
| `AgentController.java` | 修改 | 响应结构增加 trace |
| `ChatMessage.java` | 修改 | 可能需要增加 trace 字段 |
| 新增 `TraceEvent.java` | 新增 | trace 事件模型 |
| `index.html` | 修改 | 前端 UI：折叠面板 + 时间线 + token 显示 |

## Technical Notes

- Zhipu GLM API: `response.usage` 包含 `prompt_tokens`, `completion_tokens`, `total_tokens`
- DeepSeek API: 同上，OpenAI 兼容格式
- 不需要引入新依赖，全部基于现有 Spring Boot + Jackson + 原生 JS
- 前端折叠面板用 CSS `max-height` transition 做动画
