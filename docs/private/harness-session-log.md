# Harness 会话操作、事件与恢复（重构笔记）

> 原文：<https://java.agentscope.io/v2/zh/docs/harness/session-log>
> 重构目标：快速建立设计思想 + 架构分层 + API 速查，应对面试。

---

## 1. 它解决什么问题

典型场景：用户让 Agent 比较两份方案，等待期间又补充了一条要求，随后关掉了页面。再次打开时，应用要能展示之前的回答、工具执行结果和当前进度；任务曾被中断时，还能从保存的状态继续。

这背后是两件事：

| 问题 | 答案 |
|---|---|
| **谁负责把任务继续执行下去** | `AgentSession`：提供提交（submit）、补充（steer/inject）、回复（respond）、恢复（resume）等会话操作，任务由**框架在后台持有执行** |
| **执行过程保存在哪里** | **Session Log**：持久保存输入、消息、工具执行和工作状态（checkpoint） |

前端读取 Session Log 重建页面，**而不必重新发起任务**——这是整个机制的核心价值。

关键认知：**HTTP 请求结束 / 前端断连 ≠ 任务取消**。任务由框架后台执行，应用层只负责观察。

---

## 2. 核心心智模型（面试必背）

### 2.1 什么时候用 AgentSession（vs 直接调用）

| 场景 | 用什么 |
|---|---|
| 普通问答、多轮聊天、随当前请求结束的流式回复 | `agent.call(input, ctx)` / `agent.streamEvents(input, ctx)` |
| 页面关闭后继续执行、排队、运行中补充要求、回复待办、中断后续做 | `agent.session(ctx)` |

注意：HarnessAgent 默认也会保存直接调用的消息历史和 checkpoint——**只是为了记住上一轮对话，不需要改用 AgentSession**。

**同一会话二选一**：要么直接调用，要么由 AgentSession 调度。已提交任务用日志观察进度；此时再调 `streamEvents` 会启动另一次执行。

### 2.2 session / turn / run 三个身份

| 概念 | 含义 |
|---|---|
| `session` | 一段持续对话（按用户维度） |
| `turn` | 用户提交的一项任务 |
| `run` | 完成该任务的一次实际执行 |

一个 `run` 里可以多次推理、调多个工具、产生多条消息——**它不等于一次模型调用，也不等于一条 AssistantMessage**。

原文的例子最能说明关系（sessionId 全程是 S1）：

| 用户操作或执行状态 | turnId | runId |
|---|---|---|
| 提交比较任务，Agent 开始推理并查询资料 | T1 | R1 |
| 刷新页面，恢复消息和工具卡片 | T1 | 仍是 R1 |
| 补充“重点比较成本”（steer） | T1 | 仍是 R1 |
| 中断任务，之后点击继续 | T1 | R1 结束，新建 R2 |
| Agent 挂起等待确认，用户答复后继续 | T1 | R2 结束，新建 R3 |
| 完成后提交另一项任务 | 新建 T2 | 新建 R4 |

业务只通过 `submit` / `steer` / `respond` / `resume` 表达意图，框架负责关联这些身份。

**不要混用身份**：幂等 key（防重复提交）、HITL 的 `requestId`（回答待办）、工具调用 ID（关联工具结果）、`eventId`（事件去重）都不是 turnId。

### 2.3 checkpoint：恢复的载体

- 恢复依赖 **checkpoint**：执行过程中保存的会话工作状态，让 Agent 能从已提交状态**接着推理**。
- **不会**恢复：原 Java 线程、网络请求、工具内部执行进度。
- **不会**回滚：工具已经产生的外部效果（订单创建了就是创建了）。
- 普通工具所需的凭据、工作文件、外部资源必须仍然可用。

### 2.4 两类事件：AgentEvent vs SessionEvent

| 事件 | 来源 | 用途 |
|---|---|---|
| `AgentEvent` | `streamEvents` 实时产出 | 展示当前请求的进度 |
| `SessionEvent` | Session Log 已持久化记录 | 历史读取、恢复、导出 |

**实时通知到达 ≠ 日志已提交**。需要可靠重放时，以 Session Log 的已提交内容为准。

---

## 3. 会话操作 API（按意图分组）

以下 `agent` 由应用的会话管理器持有运行实例（后台任务结束或应用退出才关闭），`session = agent.session(ctx)`：

```java
HarnessAgent.Builder agentBuilder = HarnessAgent.builder()
    .name("方案助手")
    .agentId("proposal-assistant")
    .model(model)
    .workspace(Path.of("/data/proposal-workspace"));

HarnessAgent agent = agentBuilder.build(); // 会话管理器持有，别在提交接口返回时就关闭
RuntimeContext ctx = RuntimeContext.builder()
    .userId("alice").sessionId("conversation-001").build();
var session = agent.session(ctx);
```

### 3.1 提交任务：`submit`

```java
var task = session.submit("request-001", "比较方案 A 和方案 B，先给出初步结论。");
System.out.println(task.turnId());
```

- `submit` **先持久保存输入，再调度执行**。
- 返回的 `task` 是**回执**不是模型回复；任务还在排队时 `runId` 可能尚未分配。Web 接口通常把回执返回前端，让前端独立读取进度。
- **幂等**：网络超时重试时沿用相同标识 + 相同输入，得到同一次提交的回执；用户主动发起新任务则用新标识。
- 新任务按接收顺序**排队**；当前任务在等人工答复或被中断时，队列仍保留。
- `session.tasks()` 读任务状态；`session.await(task)` 观察执行结束（`suspended` = 等用户答复，**不等于完成**）。取消对 `await` 的订阅不会取消后台任务。

### 3.2 补充要求 / 材料：`steer` / `inject`

```java
session.steer(task, "重点比较运维成本，而不是只看采购价格。");
session.inject("补充材料：团队只有两名运维人员，没有夜间值班安排。");
```

| 方法 | 语义 | 关键行为 |
|---|---|---|
| `steer(task, msg)` | **调整当前任务**（补充/修改要求） | 关联到原任务，下一次推理交给模型；已发出的模型请求、正在执行的工具参数**不会被改写**；传 `task` 可防止旧页面要求误落到后来的任务；应在任务仍运行时调用 |
| `inject(msg)` | **只存背景材料**，不单独启动任务 | Agent 还有后续步骤时当前执行即可读取，否则留到后续执行；未被消费的 steer 仍属于原任务，中断后续做时再读取 |

UI 三按钮对应关系：**新任务 → `submit`，调整当前任务 → `steer`，只存参考资料 → `inject`**。

### 3.3 回答待办（HITL）：`pending` / `respond`

Agent 需要询问用户、或执行前需要用户确认时，任务**挂起**等待回答；问题保存在日志里，刷新页面甚至服务重启后仍在。

```java
session.pending().forEach((requestId, request) ->
    System.out.println(requestId + " " + request.data().get("kind")));

// ask_user / external_execution：文本答案
var answered = session.respond("external-request-id", "部署在自建 Kubernetes 集群。");
// confirmation：布尔同意/拒绝，可带原因
session.respond(requestId, true);
session.respond(requestId, SessionAnswer.reject("权限不足"));
```

关键点（面试高频）：

- `pending()` 返回的 **key 是 `requestId`**，页面必须把它和问题一起保存，回答时原样带回。
- **`respond` 会自动找到待办所属的原任务和工具调用，并自动安排接续执行——不需要再手动调 `resume`**。
- 未知、已处理、答案类型不匹配的待办会被拒绝。
- 同一任务内多个并行工具可以用 `respond(Map<String, SessionAnswer>)` 一起提交。
- 需要自定义工具结果块用 `SessionAnswer.Output`；需要提交修改后的参数或权限规则用 `SessionAnswer.Confirmation`。
- 本质：这是对原有 HITL 机制的**会话封装**——Agent 仍在原来的工具确认/外部输入位置暂停，Session Log 让待办与答案可持久恢复。

### 3.4 中断与继续：`interrupt` / `resume`

```java
session.interrupt();
var stopped = session.await(task).block();
if ("interrupted".equals(stopped.status())) {
    var continued = session.resume(stopped.turnId());
    System.out.println(continued.turnId());
}
```

- `interrupt` 是**协作式检查点停止**，不是立即终止工具；方法返回不代表已停下，要等状态变成 `interrupted` 再提供“继续”操作。
- 前端已知 `runId` 时用 `session.interrupt(runId)`，避免旧页面误中断另一轮执行。
- `resume` 恢复 **checkpoint + 尚未应用的原输入**，业务**无需重发用户消息、无需构造空输入、无需设置 turnId**。
- 只能继续**最新的未完成任务**；已完成/已取消的任务不能 `resume`；有待答交互时先 `respond`。
- 直接调用模式下中断用 `agent.interrupt`——日志负责保存状态，会话入口负责安排后续任务，**二者不是互相替代的机制**。

### 3.5 读取历史与日志：`transcript` / `log`

```java
// 已保存的完整消息（+ asOfSeq）
session.transcript().messages().forEach(m -> System.out.println(m.getTextContent()));

// 原生日志：按 seq 范围扫描
var log = session.log(); // 或 agent.sessionLog(ctx)
long through = log.head().seq();
for (var event : log.scan(0, through)) {
    System.out.printf("%d %s turn=%s run=%s%n",
        event.seq(), event.type(), event.turnId(), event.executionRunId());
}
```

- `seq`：会话内从 1 开始的**持久递增序号**，排序和续读都用它。
- 分批续读用 `readAfter(lastSeq, limit)`，处理完保存最后一条的 `seq`。
- `eventId`：接收端**去重**用，不能当顺序号。
- 日志读写是阻塞 IO，WebFlux handler 里用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 调度。
- 即使此前一直用 `agent.call` 直接对话，用相同身份取 `session` 也能读 `transcript()`，不必重新提交输入。

---

## 4. 页面恢复模式（快照 + cursor）

页面重连与任务执行是**两条独立链路**——刷新时后台可能已经完成一次工具查询、写了半段结论、又开始第二个工具，只收“从现在开始”的新片段会缺内容。

标准流程：

1. **打开/刷新，先取快照**：后端选定一个已提交日志位置，读截至该位置的记录生成页面内容，一起返回 cursor。
2. **渲染完成后，从 cursor 继续观察**：快照期间后台新产生的 121~125 条事件，续读会补上；消息和工具按稳定 ID 更新原条目，避免重复显示。
3. **区分短暂断线与刷新**：页面内容还在时，从最后已处理事件位置续读；刷新导致状态丢失时，重新取完整快照。只存一个 cursor 恢复不了 cursor 之前的内容。

注意：

- `session.transcript()` 是**消息视图**，不是完整的流式聊天快照——不包含从 `model/chunk` 重建的进行中文本和工具参数。要恢复“写到一半的回复”和工具卡片，需把已提交事件整理成页面视图（官方聊天示例的 `ChatHistory` / `ChatItems` 实现了这套）。
- **重新连接只做读取，不要再次调 `submit` 或 `streamEvents`**——任务是否继续由会话管理器负责。

---

## 5. 事件目录（精选）

普通聊天不需要处理全部事件；按关注内容挑类型读：

| 关注什么 | 事件类型 |
|---|---|
| 执行生命周期 | `run/start`、`run/end`、`run/stop_requested` |
| 任务生命周期 | `turn/start`、`turn/resumed`、`turn/output`、`turn/completed`、`turn/suspended`、`turn/failed`、`turn/interrupted`、`turn/cancelled` |
| 输入接收与处理 | `input/received`、`input/applied`、`input/discarded` |
| 消息 | `message/system`、`message/user`、`message/assistant` |
| 模型请求与流式 | `model/dispatch`、`model/chunk`、`model/end`、`model/retry` |
| 工具全链路 | `tool/requested`、`tool/decision`、`tool/dispatch`、`tool/result` |
| 人工待办及答复 | `interaction/requested`、`interaction/resolved` |
| 上下文与压缩 | `context/build`、`context/replaced`、`compaction/start`、`compaction/end` |
| 状态快照与恢复 | `state/checkpoint`、`state/restored`、`recovery/applied` |
| 子 Agent | `subagent/spawned`、`subagent/completed`、`subagent/linked` |

`SessionEvent` 关键字段：`eventId`（去重）、`seq`（持久顺序）、`type`（解析入口）、`turnId` / `executionRunId`（关联任务与实际执行）、`payloadJson`（载荷）、`required`（恢复时是否必须识别该类型——标记了 `required` 却无法识别会**阻止恢复**）。

---

## 6. 存储与后端配置

### 默认存储

`HarnessAgent` 默认用 `WorkspaceSessionLogStore`，日志跟随 Workspace 的 Filesystem（本地磁盘或分布式存储）。创建新实例或重启后，只要**身份与存储配置一致**就能找到原会话。

| 配置 | 日志在哪 |
|---|---|
| 本地 Filesystem | 身份解析后的 Filesystem 根下 `.agentscope-runtime/` |
| RemoteFilesystem | BaseStore namespace 下的 `__agentscope_session_log_v1__` 分区 |
| 自定义 | `.sessionLogStore(store)` |

身份由 `SessionKey(userId, agentId, sessionId)` + Filesystem namespace 共同确定——开启用户/会话隔离后根目录随身份变化，**不是所有会话都写在同一个固定目录**。

### 跨节点共享日志

工作文件在本机/沙箱、但希望日志跨节点保存时，单独配置日志后端：

```java
var logStore = new WorkspaceSessionLogStore(
    new RemoteFilesystem(sharedStore, List.of("my-app", "session-history")));
HarnessAgent.Builder builder = HarnessAgent.builder()
    .name("方案助手").agentId("proposal-assistant")
    .model(model).workspace(Path.of("/data/proposal-workspace"))
    .sessionLogStore(logStore);
```

**多副本必须连接同一份日志存储 + 一致的身份与 namespace**；每副本各用同名本地目录并不能共享会话。内存后端只适合测试。

### 服务重启后恢复

```java
// 用相同 agentId / userId / sessionId 和日志后端重建，再取 session
session.transcript().messages().forEach(m -> System.out.println(m.getTextContent()));
session.start(); // 应用启动时，重新开启已接收任务的调度
```

- `start()` 检查持久队列，**不会擅自恢复已中断的任务**——由应用决定何时 `resume`，或等待用户回答待办。
- `submit` / `resume` / `respond` 本身会自动开启调度，正常交互不必每次手调 `start()`。
- 关闭 `agent` 会停止调度并中断其持有的执行；已提交日志和队列由持久后端保留。

---

## 7. 故障恢复：结果未知的工具（面试亮点）

**场景**：工具已在订单系统创建订单，但进程在记录工具结果之前退出。此时日志能说明“工具被调用过”，却不能证明成功还是失败——直接重试可能**重复下单**。

**正确处理流程**：

1. `session.inspect().uncertainToolCalls()` 找出需要核对的工具调用；
2. 查真实业务系统确认结果；
3. 把调用 ID、工具名、实际结果交回框架（只保存核对结果 + checkpoint，**不再调用工具**）；
4. 之后显式 `resume` 原任务。

```java
System.out.println(session.inspect().uncertainToolCalls());

var result = ToolResultBlock.builder()
    .id("call-id-from-inspection").name("create_order")
    .output(TextBlock.builder().text("已核实订单 ORD-42 创建成功").build())
    .state(ToolResultState.SUCCESS).build();
agent.reconcileToolOutcomes(ctx, Map.of(result.getId(), result), "已核对订单系统记录");
```

约束：结果集合要**恰好覆盖**检查列出的未知调用；实际结果仍未知时，**不要填一个空的成功结果绕过检查**。

---

## 8. 进阶能力一览

| 能力 | 入口 | 一句话 |
|---|---|---|
| 导出到外部系统 | `SessionLogExporter` + 应用实现 `SessionExportSink` | `drain()` 读已提交事件交给 sink；目标端按 `eventId` 去重，`sink.name()` 跨部署稳定用于记录导出进度 |
| 记录应用自己的事件 | `SessionRecorder.from(ctx)` → `append(type, payload, required)` | 工具/中间件里的业务事件写进执行时间线；纯诊断事件用 `required=false` |
| 迁移与分支会话 | `SessionMigration.importBaseline` / `fork` | 导入旧 `AgentState` 到新会话，或从当前状态开分支；不复制完整历史、沙箱和外部资源 |

---

## 9. 面试速查

### 高频考点

1. **什么时候用 `agent.call`，什么时候用 `agent.session`？**
   call 适合普通问答、流式展示、随请求结束的场景；session 用于任务要跨页面存活：后台继续、排队、运行中补充要求、回复待办、中断续做。核心区别是**执行生命周期归谁管**：call 随请求结束，session 由框架后台持有。

2. **session、turn、run 各是什么？**
   session 是一段对话（用户维度），turn 是用户提交的一项任务，run 是完成任务的一次执行。一个 run 可含多次推理和多个工具调用，不等于一次模型调用。中断/继续、HITL 挂起/答复都会开启新 run，但 turn 不变。

3. **submit、steer、inject 的差别？**
   submit 提交新任务（排队）；steer 调整当前任务（下一次推理生效）；inject 只存背景材料不启动任务。对应 UI 上的“发新任务 / 补充要求 / 上传资料”。

4. **为什么 respond 之后不用再手动 resume？**
   respond 会找到待办所属的原任务和工具调用，自动安排接续执行。interrupt 之后才需要显式 resume。

5. **interrupt 是立即停止吗？**
   不是。它是协作式检查点停止——下一个检查点才拦截，方法返回不代表已停下，要等状态变成 `interrupted`。

6. **checkpoint 能恢复什么、不能恢复什么？**
   能恢复已提交的会话工作状态让 Agent 接着推理；不能恢复原 Java 线程、网络请求、工具内部进度，也不回滚外部副作用（如已创建的订单）。

7. **AgentEvent 和 SessionEvent 的区别？**
   AgentEvent 是 streamEvents 的实时通知，适合展示当前请求；SessionEvent 是已持久化的记录，适合历史读取、恢复、导出。实时到达不代表已提交，可靠重放以日志为准。

8. **seq 和 eventId 的区别？**
   seq 是会话内持久递增序号，用于排序和续读；eventId 是稳定 ID，用于接收端去重。不能用 eventId 排序，不能拿 seq 去重（同一事件重放时 seq 相同）。

9. **服务重启后怎么恢复会话？**
   相同 agentId/userId/sessionId + 相同日志后端重建 Agent，读 transcript 恢复历史，start() 重新开启调度；已中断的任务由应用决定何时 resume。

10. **进程在记录工具结果前挂了怎么办？**
    inspect().uncertainToolCalls() 找出结果未知的调用 → 查真实业务系统 → reconcileToolOutcomes 写回核实结果 → 显式 resume。不要靠重试撞运气（可能重复下单），也不要填空成功绕过检查。
