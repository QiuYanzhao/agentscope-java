# Harness 记忆（Memory）示例

对应重构文档：`docs/private/harness-记忆.md`
官方原文：<https://java.agentscope.io/v2/zh/docs/harness/memory>

## 说明

本目录示例直接使用当前代码库中已实现的记忆 API：

- `HarnessAgent.builder().memory(MemoryConfig...)` —— 双层长期记忆（Flush + Consolidation + 后台维护）；
- `HarnessAgent.builder().compaction(CompactionConfig...)` —— 对话压缩（LLM 摘要历史前缀）；
- `HarnessAgent.builder().toolResultEviction(ToolResultEvictionConfig...)` —— 大工具结果卸载（全文落盘 + 预览占位符）；
- 四个记忆工具：`memory_save` / `memory_search` / `memory_get` / `session_search`（由模型在 ReAct 循环中调用）。

核心心智模型（三层记忆操作的定位）：

| 操作 | 写入目标 | 触发 | 配置入口 |
|---|---|---|---|
| Flush（LLM ①） | `memory/YYYY-MM-DD.md`（追加） | 每次 `call()` 结束，可节流 | `MemoryConfig.flushPrompt/flushTrigger` |
| Consolidation（LLM ②） | `MEMORY.md`（整体重写） | 后台节流任务（watermark 增量） | `MemoryConfig.consolidationPrompt/consolidationMinGap` |
| Compaction summary（LLM ③） | 上下文（摘要消息） | 消息/token 超阈值 | `CompactionConfig.summaryPrompt` |

> ⚠️ 勘误备忘：官方文档的 `sessionRetentionDays` 字段在当前源码中不存在；记忆工具实际注册
> 4 个（含 `memory_save` / `session_search`）；`memory_search` 无"30 条上限"；卸载默认排除
> 6 个工具（详见重构文档中的 ⚠️ 标注）。

## 运行方式

所有示例都需要模型 API key（默认走 `dashscope:qwen-plus`，自动读取 `DASHSCOPE_API_KEY`）：

```bash
export DASHSCOPE_API_KEY=your_key

mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryFlushConsolidationExample

mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryToolsExample

mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryEvictionExample

mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryConfigTuningExample

mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryCompactionExample
```

`MemoryToolsExample` 通过对话驱动模型调用记忆工具，结果是否命中取决于模型行为；
其余示例的写入/卸载行为由框架保证，示例会打印磁盘文件作为佐证。

## 各示例覆盖的知识点

| 示例 | 覆盖的文档章节 |
|---|---|
| `MemoryFlushConsolidationExample` | 3（Flush 触发/异步）、4（Consolidation watermark/维护归档/retention） |
| `MemoryToolsExample` | 5（memory_save / memory_search / memory_get / session_search）、6（MEMORY.md 注入与跨会话记忆） |
| `MemoryEvictionExample` | 7.2（大结果卸载：阈值/预览/落盘路径/排除工具） |
| `MemoryConfigTuningExample` | 8（NEVER / 自定义 prompt / 小模型 / disableMemoryHooks / consolidationPrompt 校验） |
| `MemoryCompactionExample` | 7.1（压缩触发与保留、压缩前后上下文对比） |

## 存储

- 记忆文件按运行时数据命名空间保存：默认 `IsolationScope.USER`（缺 `userId` 时降级 `SESSION`），
  本机路径形如 `<workspace>/<sessionId>/memory/YYYY-MM-DD.md` 与 `<workspace>/<sessionId>/MEMORY.md`；
- 卸载的大工具结果落在 `<workspace>/large_tool_results/<agentName>/<callId>-<hash>`；
- 原始会话日志（`*.log.jsonl`）独立保存、永不压缩，供 `session_search` 与事后审计。
