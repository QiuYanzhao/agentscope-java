# Harness 会话操作、事件与恢复（session）示例

对应重构文档：`docs/private/harness-session-log.md`
官方原文：<https://java.agentscope.io/v2/zh/docs/harness/session-log>

## ⚠️ 重要背景：官方文档描述的 API 超前于当前代码

官方 v2 文档中的 `agent.session(ctx)` / `AgentSession`（`submit` / `steer` / `inject` /
`respond` / `interrupt` / `resume` / `pending` / `transcript` / `await` / `log`）**尚未在本仓库
2.0.4-SNAPSHOT（dev 分支）实现**。当前代码库全局搜索不到 `AgentSession` 类。

因此本节示例改用**当前真实存在的 API** 演示同一套设计思想，每个示例的类注释里都给出了
概念映射。面试或使用时应以当前 API 为准，同时了解官方文档的演进方向。

## 文档概念 ↔ 当前真实 API 映射表

| 文档（未来 API） | 当前真实 API（2.0.4-SNAPSHOT） | 示例 |
|---|---|---|
| `agent.session(ctx)` 取得会话操作入口 | `agent.prepareCall(msgs, ctx)` / `agent.prepareRun(msgs, ctx)` 返回 `AgentRun<T>` | 全部示例 |
| `session.submit(requestId, input)` 提交后台任务 | `prepareCall/prepareRun` + 应用自建 run 登记表（`runId()` 即回执；幂等由应用用 requestId 保证） | `SessionRunBasicsExample` |
| `session.await(task)` 等待执行结束 | `run.termination()`（终态）/ `run.stream().single()`（最终回复） | `SessionRunBasicsExample` |
| `session.tasks()` 查询任务状态 | 应用自建登记表按 `runId` 查询；`run.status()` 即时快照 | `SessionRunBasicsExample` |
| `session.transcript()` 读取已保存消息 | `agent.getAgentState().getContext()`（当前会话上下文）；持久化经 stateStore | `SessionRunBasicsExample` |
| `session.steer(task, msg)` 调整当前任务 | 协作中断旧 run（`run.interrupt()`）+ 同一 session 发起新 run（新要求作为新消息） | `SessionInterruptResumeExample` |
| `session.inject(msg)` 只存背景材料 | 同一 session 追加一条用户消息（进入持久上下文，不单独排队调度） | `SessionInterruptResumeExample` |
| `session.interrupt()` / `interrupt(runId)` | `agent.interrupt(userId, sessionId[, msg])`（跨请求）/ `run.interrupt()`（按句柄） | `SessionInterruptResumeExample` |
| `session.resume(turnId)` 继续原任务 | 同一 `(userId, sessionId)` 创建新 run；已持久化的会话上下文让 Agent 接着干 | `SessionInterruptResumeExample` |
| `session.pending()` / `session.respond(requestId, ...)` 回答待办 | 权限引擎 HITL：返回 `GenerateReason.PERMISSION_ASKING` → 检查 `ToolUseBlock(state=ASKING)` → 构造 `ConfirmResult` 再次调用恢复 | `SessionHITLExample` |
| checkpoint：已提交状态可恢复，线程/网络/工具进度不可恢复 | `JsonFileAgentStateStore` 按 `(userId, sessionId)` 持久化；重建实例 + 相同身份即可继续 | `SessionStateRecoveryExample` |
| 服务重启后恢复会话 | 相同 `agentId` / `userId` / `sessionId` + 相同 stateStore 重建 Agent | `SessionStateRecoveryExample` |
| `session.log()` / `seq` / `readAfter` 事件读取 | 当前无 Session Log 存储 API；实时事件经 `prepareRun` 返回的 `Flux<AgentEvent>` | `SessionRunBasicsExample`（事件流） |

## 运行方式

所有示例都需要模型 API key（默认走 `dashscope:qwen-plus`，自动读取 `DASHSCOPE_API_KEY`）：

```bash
export DASHSCOPE_API_KEY=your_key

mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.harness.session.SessionRunBasicsExample

mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.harness.session.SessionInterruptResumeExample

mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.harness.session.SessionHITLExample

mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.harness.session.SessionStateRecoveryExample
```

`SessionHITLExample` 支持 `--headless` 参数（`PermissionMode.DONT_ASK`，自动拒绝确认，适合 CI）。

## 各示例覆盖的知识点

| 示例 | 覆盖的文档章节 |
|---|---|
| `SessionRunBasicsExample` | 2（session/turn/run 心智模型）、3.1（submit/await）、3.5（transcript/事件流） |
| `SessionInterruptResumeExample` | 2.3（checkpoint）、3.2（steer/inject 等价）、3.4（interrupt/resume） |
| `SessionHITLExample` | 3.3（pending/respond 等价） |
| `SessionStateRecoveryExample` | 2.3（checkpoint）、6（存储与重启恢复） |
