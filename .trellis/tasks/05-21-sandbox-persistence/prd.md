# 沙箱状态持久化

## Goal

实现沙箱状态的持久化，使应用重启后能复用现有沙箱，避免重复创建。

## Requirements

1. **创建时持久化**
   - 创建沙箱后，将 `sandboxId` 和 `aioEndpoint` 保存到 `ConversationSessionEntity`
   - 持久化到数据库

2. **启动时恢复**
   - 应用启动时，扫描数据库中有 `sandboxId` 的 session
   - 尝试连接到这些沙箱（通过 endpoint）
   - 重建内存中的 `sandboxAgents` 和 `sessionSandboxMap`

3. **健康检查**
   - 恢复前检查沙箱是否健康（AIO 用 client.isReady()）
   - 不健康的沙箱清除记录，下次请求重新创建

## Acceptance Criteria

- [ ] 创建沙箱后 sandboxId + aioEndpoint 写入数据库
- [ ] 应用重启后能恢复沙箱映射
- [ ] 不健康的沙箱能被检测并清理
- [ ] 重启后用户请求不会触发重复创建沙箱

## Technical Approach

**方案 B：持久化 endpoint** ✅ 已实现

修改点：
1. `ConversationSessionEntity` - 添加 `aioEndpoint` 字段 ✅
2. `ConversationSessionRepository` - 添加 `findAllWithSandbox()` 方法 ✅
3. `AioSandboxStore` - 新组件，管理 endpoint 映射 + 启动恢复 ✅
4. `SandboxServiceImpl` - 创建沙箱时写入 DB，getAioClient 优先从 Store 获取 ✅

**工作流程**：
1. 创建沙箱 → 保存 sandboxId + endpoint 到 DB + AioSandboxStore
2. 应用重启 → `AioSandboxStore.restore()` 从 DB 恢复
3. 检查健康 → 健康的加入内存映射，不健康的清除记录
4. 后续请求 → 直接从 Store 获取 AioSandboxClient

## Out of Scope

- 沙箱 TTL 管理
- 沙箱定时清理任务

## Technical Notes

- OpenSandbox SDK 不支持通过 ID 重连，但可以直接通过 endpoint 创建客户端
- `AioSandboxClient(endpoint)` 可直接连接已运行的沙箱
