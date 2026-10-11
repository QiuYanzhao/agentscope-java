# Harness 会话操作、事件与恢复（session）示例

对应重构文档：`docs/private/harness-session-log.md`
官方原文：<https://java.agentscope.io/v2/zh/docs/harness/session-log>

## 说明

本目录示例直接使用当前代码库中已实现的 `AgentSession` API
（`io.agentscope.harness.agent.session.AgentSession`），与官方文档一致。

核心入口：`HarnessAgent.session(RuntimeContext)` 返回 `AgentSession`，提供
`submit` / `steer` / `inject` / `respond` / `interrupt` / `resume` / `pending` /
`transcript` / `log` / `inspect` / `await` / `tasks` 等会话操作。命令同步接受，
执行由框架后台持有；关闭所属 `HarnessAgent` 时一并关闭会话运行时。

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
| `SessionRunBasicsExample` | 2（session/turn/run 心智模型）、3.1（submit/await/幂等）、3.5（transcript/log/事件流） |
| `SessionInterruptResumeExample` | 2.3（checkpoint）、3.2（steer/inject）、3.4（interrupt/resume） |
| `SessionHITLExample` | 3.3（pending/respond） |
| `SessionStateRecoveryExample` | 2.3（checkpoint）、6（存储与重启恢复） |

## 存储

默认使用 `WorkspaceSessionLogStore`，日志跟随 `workspace(...)` 指定的 Filesystem 保存；
也可通过 `.sessionLogStore(store)` 显式指定（如 `InMemorySessionLogStore` 用于测试）。
重启恢复的前提：相同 `agentId` / `userId` / `sessionId` + 相同日志后端。
