# 子 Agent（Subagent）· 重构笔记

> 原文：<https://java.agentscope.io/v2/zh/docs/harness/subagent>
> 重构目标：快速建立设计思想 + 架构分层 + API 速查，应对面试。
> 源码核对版本：基于 agentscope-java 仓库当前 main 分支（`AgentSpawnTool` / `SubagentDeclaration` / `SubagentsMiddleware` 源码逐项核对）。

---

## 1. 它解决什么问题

主 agent 跑一个任务时，所有上下文（会话历史、工具结果、中间产物）都堆在它自己身上。任务一重，主线程就膨胀：上下文爆掉、prompt 变贵、多个不相关的子问题互相干扰。

子 Agent 的思路一句话：**把"可独立处理、上下文重、可并行"的任务委派出去，每个子任务开一个临时实例跑自己的会话，结果作为工具结果交回给父 agent。**

对父 agent 而言，子 agent 本质上是一个**工具**（`agent_spawn`）——它不是并行线程，不是消息队列，是"调用一个外部 Agent 然后把它的回复拿回来"。

**这个机制解决的三类问题**：

| 问题 | 子 agent 的答案 |
|---|---|
| 主 agent 上下文膨胀 | 子任务在**独立会话**里跑，中间过程不占父的上下文 |
| 任务可并行但主 agent 只能串行 | 同步子 agent 按 Toolkit 默认并行推进，一次 fan-out / fan-in |
| 子任务需要不同的模型 / 工具 / 人格 | 每个子 agent 声明可覆盖模型、工具白名单、系统提示 |

---

## 2. 核心心智模型（面试必背）

### 2.1 三个身份，别混

| 身份 | 是什么 | 例子 | 用在哪 |
|---|---|---|---|
| `agent_id` | 子 agent 的**类型名**（声明时定的） | `reviewer` | spawn 时指定要哪种子 agent |
| `agent_key` | 一次 spawn 出的**实例句柄** | `agent:reviewer:abc-123` | `agent_send` 给这个实例补消息 |
| `task_id` | **后台任务**的编号 | `task-42` | `task_output` / `task_cancel` 查后台任务 |

关键：`agent_key` 不是 `task_id`，也不是 `session_id`。spawn 的返回值第一行就是 `agent_key`，要原样传给 `agent_send`。

### 2.2 委派链

```
用户 → 主 agent (orchestrator)
          ├─ agent_spawn → 子 agent A（独立会话）
          └─ agent_spawn → 子 agent B（独立会话）
                               └─ agent_spawn → 孙 agent（可以，但最多 3 层）
```

- 每个子 agent 是**临时实例**（本地 `HarnessAgent` 或远程 HTTP stub），跑自己的会话；
- **递归保护**：spawn 深度硬上限 3 层（`AgentSpawnTool.MAX_SPAWN_DEPTH = 3`），超限直接返回错误工具结果；
- **安全边界沿链传递**：父的 DENY 权限规则、Plan Mode 只读限制、`userId` 会自动透传给子，委派链不会成为绕过安全边界的通道。

### 2.3 同步 vs 后台（一条参数定生死）

`agent_spawn` 的核心参数是 `timeout_seconds`：

| 值 | 语义 | 结果 |
|---|---|---|
| `> 0`（默认 30，最大 600） | **同步**调用，父在这一步阻塞等待 | 子 agent 的回复作为工具结果返回 |
| `= 0` | **后台**调用，立即返回 | 返回 `task_id`，子 agent 在后台继续跑 |

**默认超时后自动 promote**：同步等满 `timeout_seconds` 没跑完，任务**不会失败**——框架把它转成后台任务（返回 `status: timeout_promoted` + `task_id`），子 agent 继续跑，结果稍后通过反向通知送达。

### 2.4 结果怎么回到父 agent

- **同步**：子回复直接作为 `agent_spawn` 的工具结果；
- **后台**：框架在**下一次推理开始前**自动把已完成任务的结果作为 `<system-reminder>` 注入父的对话末尾——**父 agent 不需要轮询**。

---

## 3. 声明方式：三类来源，构建期合并

| 来源 | 适用场景 | 怎么配 |
|---|---|---|
| 内置 `general-purpose` | 通用兜底，能力镜像主 agent | 总是可用，什么都不用配 |
| 工作区 spec 文件 | 项目特有、能版本控制、静态 | `workspace/subagents/<id>.md` |
| 编程式声明 | 运行时才能确定（远程、动态参数） | `builder.subagent(SubagentDeclaration.builder()...)` |

三类在构建期合并（源码 `HarnessAgentBuilderSupport.buildSubagentEntries`：先加入编程式声明，再追加文件扫描，最后自定义工厂）。**同名覆盖顺序（高→低）：自定义工厂 > 工作区文件声明 > 编程式声明 > general-purpose**——同名文件声明会盖掉编程式声明。

### 3.1 工作区 spec 文件（最常用）

在 `workspace/subagents/` 下放一个 Markdown 文件，**文件名（去掉 `.md`）就是 `agent_id`**，不需要注册，构建期自动扫描加载：

```markdown
---
description: 代码评审专家。当用户需要 review PR、找代码问题、检查代码规范时使用。
workspace:
  mode: isolated          # isolated（默认） | shared
model: qwen3-max          # 可选；不写继承父
steps: 8                  # 可选；单次最多推理迭代次数（默认 10）
tools: [read_file, grep_files]  # 可选；继承工具的白名单
---
你是一个专注代码评审的子 agent。请按以下流程工作：
1. 先 read_file / grep_files 收集上下文
2. 给出按文件 / 行号的具体建议
3. 末尾给一个 1-5 的总体评分
```

主 agent 在推理中直接调用（不需要做任何注册）：

```
agent_spawn agent_id="reviewer" task="review 这次 PR 的所有改动"
```

**front matter 完整字段**（源码 `AgentSpecLoader` 确认）：

| 字段 | 默认 | 说明 |
|---|---|---|
| `description` | **必填** | 模型决定要不要委派的关键依据，写清楚"什么时候用" |
| `workspace.mode` | `isolated` | `isolated` / `shared` |
| `workspace.path` | 自动开子目录 | 定义工作区路径（含 `AGENTS.md`）；与 body 互斥，同时写时 **body 被忽略** |
| `model` | 继承父 | 模型名，经 `ModelRegistry` 解析 |
| `steps` | `10` | 单次最多推理迭代次数（旧别名 `maxIters`） |
| `temperature` / `top_p` | 继承父 | 采样参数覆盖 |
| `mode` | `all` | `primary`（不能 spawn）/ `subagent` / `all` |
| `hidden` | `false` | `true` 时不出现在模型可见列表（仍可程序化 spawn） |
| `expose_to_user` | 不表态 | 三态：`true` 强制暴露 / `false` 禁止 / 不写交给 LLM |
| `enable_pending_tool_recovery` | 继承父 | 悬空工具调用恢复（也兼容 `enablePendingToolRecovery` 写法） |
| `tools` / `skills` | 继承全部 | 继承能力白名单 |

**坑**：spec 文件**不要**写 `name`（用了文件名）；`description` 缺失直接导致该声明被跳过。

### 3.2 编程式声明（`SubagentDeclaration`）

适合运行时才能确定的场景（远程地址、动态参数），或不想把声明落成文件：

```java
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import java.nio.file.Path;
import java.util.List;

HarnessAgent agent = HarnessAgent.builder()
    .name("orchestrator")
    .model(model)                       // 父模型，子 agent 默认继承
    .subagent(SubagentDeclaration.builder()
        .name("reviewer")
        .description("代码审查专家，当用户需要 review PR 时使用")
        .workspace(Path.of("./defs/reviewer"))   // 三种来源之一：定义工作区
        .workspaceMode(WorkspaceMode.ISOLATED)
        .model("qwen3-max")             // 覆盖父模型
        .steps(8)
        .tools(List.of("read_file", "grep_files"))  // 工具白名单
        .build())
    .subagent(SubagentDeclaration.builder()
        .name("remote-researcher")
        .description("远端调研子 agent")
        .url("http://agent-task-server:8080")      // 三种来源之二：远程 HTTP
        .headers(Map.of("Authorization", "Bearer xxx"))
        .build())
    .build();
```

**三种来源互斥**（源码 `SubagentDeclaration.build()` 强制）：`workspace(Path)`、`inlineAgentsBody(String)`、`url(String)` 只能选一个，同时设多个直接抛 `IllegalArgumentException`。

### 3.3 内置 `general-purpose`

不需要写声明文件，总是可用。角色是"通用兜底"——能力与主 agent 一致（同模型、同工具、同技能），**共享主工作区**（SHARED）。适合"主 agent 想隔离上下文跑个子任务，但又懒得专门写 spec"。

---

## 4. 工作区隔离：ISOLATED vs SHARED

`workspaceMode` 决定子 agent 的运行时工作区根目录怎么算（源码 `WorkspaceMode` 的五行决策表，比官方文档更完整）：

| `workspacePath` | 模式 | 运行时工作区根 | 说明 |
|---|---|---|---|
| 已设 | `ISOLATED` | `workspacePath` | 定义目录即运行时根，`AGENTS.md` 作为系统提示 |
| 已设 | `SHARED` | `mainWorkspace`（父工作区） | 定义目录的 skills / knowledge / MEMORY **被忽略**，只取 `AGENTS.md` 做系统提示 |
| 未设 | `ISOLATED` | `mainWorkspace/agents/<name>/workspace/` | 自动创建子目录；inline body 作为系统提示 |
| 未设 | `SHARED` | `mainWorkspace` | 直接用父工作区 |
| `general-purpose` | 恒为 SHARED | `mainWorkspace` | 完全镜像主 agent |

**默认 ISOLATED**。ISOLATED 模式还有个额外保障：子 agent 的持久化状态按 **`(userId, 父 sessionId)`** 分桶——同一用户在不同对话里 spawn 同名子 agent 也互不污染。

**什么时候选 SHARED**：子 agent 的输出会被父立即读到（例如 `general-purpose` 直接读写父工作区文件）。SHARED 模式的子 agent 故意与父共用租户桶，**不做多租户隔离**。

---

## 5. 调用：同步 / 后台 / 强制同步（API 按意图分组）

### 5.1 同步调用（等结果）

模型侧就是这样（LLM 生成工具调用，不是应用代码直接调）：

```
agent_spawn agent_id="reviewer" task="review 这次 PR" timeout_seconds=60
```

- 父在这一步阻塞，子回复作为工具结果返回；
- 多个同步 `agent_spawn` 在**同一轮推理里并行**推进（`ToolkitConfig.parallel=true` 默认），父等这一批都返回后再进下一轮推理——相当于一次同步 fan-out / fan-in；
- 要串行，构建 Agent 时传 `ToolkitConfig.builder().parallel(false).build()` 的自定义 `Toolkit`。

### 5.2 后台调用（立即返回）

```
agent_spawn agent_id="research-analyst" task="调研竞品" timeout_seconds=0
→ status: accepted
  task_id: xxx
```

返回 `task_id` 后父立即继续。**不建议**在 prompt 里写"记得调 task_output 轮询"——那是旧版本做法，见 5.4。

### 5.3 应用侧强制同步（`RuntimeContext`）

LLM 可能自己选了异步（`timeout_seconds=0`），应用可以在 `RuntimeContext` 里强制同步，覆盖模型决策：

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.tool.AgentSpawnTool;

RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("s-1")
    .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
    .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 120) // 可选；硬超时（秒）
    .build();
```

强制同步生效后的三条规则：

1. 设了 `CTX_FORCE_SYNC_TIMEOUT_SECONDS` 就**完全覆盖** LLM 的 `timeout_seconds`（`<=0` 回退 30s，上限 600s）；
2. 没设时，LLM 传的 `timeout_seconds=0` 会被改写成默认同步超时（30s），**不会**提交后台任务；LLM 传的正数超时照常生效；
3. 同步等待超时后返回 `status: timeout` 并**中断**子 agent，**不会** promote 成后台任务。

`agent_send` 同样遵守该开关。

### 5.4 后台任务自动反向通知（面试亮点）

后台任务跑完，**主 agent 不需要轮询**——下一次推理开始前，框架把已完成任务的结果作为系统提醒注入对话末尾：

```
<system-reminder>
后台任务已交付：
- task_id=xxx，agent=research-analyst，status=COMPLETED
  结果摘要：...
</system-reminder>
```

主 agent 看到这条 reminder 自然回应或继续行动。**不需要**在 prompt 里写"记得调 task_output 轮询"。

### 5.5 后台任务工具族（逃生口）

| 工具 | 管什么 | 职责 |
|---|---|---|
| `agent_spawn` / `agent_send` | **实例** | 创建、复用、给实例发消息 |
| `task_output` | **任务结果** | 按 `task_id` 取后台任务结果（阻塞/非阻塞） |
| `wait_async_results` | **任务结果** | 等一组任务全部完成（barrier）或等任意一条 |
| `task_cancel` | **任务结果** | 取消正在运行的后台任务 |
| `task_list` | **任务结果** | 列出所有后台任务及状态 |

> ⚠️ **源码勘误**：`wait_async_results` 工具只在配置了 `messageBus` 时才注册（`HarnessAgent.Builder.messageBus(...)`）；没配 messageBus 的 Agent 没有这个工具。其余四个工具由 `SubagentsMiddleware` 常驻注册。

**三种异步结果收集方式**：

| 方式 | 怎么做 | 适用 |
|---|---|---|
| 主动通知（默认） | 什么都不做，等 `<system-reminder>` | 不阻塞等待的默认路径 |
| 指定任务检查 | `task_output(task_id, block=false)` | 主动查看某任务当前/终态 |
| 等待 barrier | `wait_async_results(task_ids="id1,id2")` 或 `wait_async_results(wait_all=true)` | 必须等齐一组结果再继续 |

barrier 模式把各任务结果**直接写进本次工具返回**，主 agent 立刻能继续推理。`wait_all=true` 以**调用开始时**的未完成任务快照为准，等待期间新建的任务不会加入 wait set。

**遗留陷阱（inbox-any）**：不传 `task_ids` 也不传 `wait_all` 时，`wait_async_results` 只等到 inbox 中**任意一条**消息到达就返回——这不是 wait-all。需要一组全完成，必须显式传 `task_ids` 或 `wait_all=true`。

---

## 6. 给已存在的子 agent 补一条消息（`agent_send`）

`agent_spawn` 返回里有 `agent_key`（运行时实例句柄），用它或 spawn 时设的 `label` 追加消息：

```
agent_send agent_key="agent:reviewer:abc-123" message="顺便也看下 schema 变更"
```

```java
// 更可读的方式：spawn 时给实例一个 label，之后用 label 寻址
// agent_spawn agent_id="reviewer" task="review 这次 PR" label="pr-reviewer"
// agent_send label="pr-reviewer" message="顺便也看下 schema 变更"
```

- `agent_key` 与 `label` **互斥**，同时传会报错；
- `agent_send` 同样支持 `timeout_seconds`（0 = 后台，返回 `task_id`）；
- 列当前活跃的子 agent 实例用 `agent_list`。

**设计意图**：同步调用是"一问一答"；`agent_send` 让父 agent 能和已经存在的子 agent **多轮对话**——先派任务，看结果不完整再补一句，子 agent 保留自己的会话上下文。

---

## 7. 持久会话（`persistSession`）

**默认行为**：每次 `agent_spawn` 都创建**新的**子 agent 实例和会话——不保留上次调用的上下文。

**开启后**：同一子 agent 在多次 spawn 之间复用。框架根据 `(parentSessionId, agentId, label)` 生成**确定性 key**；再次 spawn 同样的组合，复用已存在的实例——对话历史和状态都保留。

```java
.subagent(SubagentDeclaration.builder()
    .name("note-taker")
    .description("跨对话轮次积累笔记")
    .persistSession(true)          // 默认 false
    .build())
```

**边界（源码核对）**：
- `persistSession` 目前**只能编程式设置**——工作区 spec 文件的 front matter 解析（`AgentSpecLoader`）**不支持** `persist_session` 字段；
- 开启后进程重启也能恢复状态（确定性 key 不依赖随机 UUID）；
- 适合"子 agent 是长期协作者"（记笔记、持续跟踪）而不是"一次性打手"。

---

## 8. 向用户暴露子 Agent（`expose_to_user`）

### 8.1 它解决什么问题

默认子 agent 对用户不可见——是父 agent 幕后的内部工具。但"分支对话"场景需要用户能**直接和子 agent 聊**：父 agent spawn 一个专家，用户独立地继续和那个专家交流，不再经过父 agent。

`agent_spawn` 传 `expose_to_user=true` 做两件事：

1. 在 Gateway 里注册子 agent，成为用户可寻址的入口；
2. 向流式事件流发一个 `SubagentExposedEvent`，携带 `subagentId` 句柄。

### 8.2 怎么开启：`agent.channel(...)` 一行

```java
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;

HarnessAgent agent = HarnessAgent.builder()
    .name("orchestrator")
    .model(model)
    .build();

ChatUiChannel chat = agent.channel(ChatUiChannel.create());  // bridge 自动接好
```

客户端在事件流里收到暴露事件后，直接向子 agent 发消息（绕过父 agent）：

```java
// 客户端：监听暴露的子 agent（SendOptions 在 io.agentscope.harness.agent.gateway.channel.chatui 包）
chat.sendStream(SendOptions.userId("user-1"), "派一个研究员调查 AI 趋势")
    .doOnNext(event -> {
        if (event instanceof SubagentExposedEvent se) {
            // se.getSubagentId() → 用来直接和子 agent 对话
            // se.getAgentId()   → 子 agent 类型（如 "researcher"）
            // se.getLabel()     → 可选的人类可读名称
        }
    })
    .blockLast();

// 直接向暴露的子 agent 发消息
chat.sendToSubagent(subagentId, "重点关注 LLM agent").block();
```

**边界**：没有绑定 Channel 时，`expose_to_user=true` 被**静默忽略**——子 agent 照常工作，只是不暴露给用户。

### 8.3 用代码控制是否暴露（三优先级）

完全依赖 LLM 传参不可控。暴露与否按以下优先级解析（高 → 低）：

| 优先级 | 来源 | 作用域 |
|---|---|---|
| 1 | `RuntimeContext` 的 `AgentSpawnTool.CTX_EXPOSE_TO_USER` | 当前这一次调用里的所有 `agent_spawn` |
| 2 | `SubagentDeclaration.exposeToUser(...)` | 该子 agent 类型的静态策略 |
| 3 | LLM 传入的 `expose_to_user` 工具参数 | 单次 spawn |
| 4 | 都没有 → 默认 `false` | — |

```java
// 优先级 1：按调用强制
RuntimeContext ctx = RuntimeContext.builder()
    .userId("user-1")
    .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)   // 传 false 则禁止暴露
    .build();

// 优先级 2：按类型三态策略
SubagentDeclaration decl = SubagentDeclaration.builder()
    .name("researcher")
    .description("调研主题并返回汇总报告。")
    .exposeToUser(true)          // TRUE 总是暴露；FALSE 永不暴露（覆盖 LLM）；null 不表态
    .build();
```

### 8.4 跨重启与多副本

默认暴露只存在于**创建它的进程**：`subagentId` 只在那个节点有效，重启即失效。要让暴露的子 agent 在任意副本、重启之后都能解析，给 agent 配 `distributedStore(...)`：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("orchestrator")
    .model(model)
    .distributedStore(RedisDistributedStore.fromJedis(jedis))
    .build();

ChatUiChannel chat = agent.channel(ChatUiChannel.create());  // 恢复能力自动接好
```

`subagentId` 持久化到后端，子 agent 的对话按 session 从分布式 `AgentStateStore` 重新加载——后续消息落到不同节点，用户面对的仍是同一个子 agent。

---

## 9. 让 agent 自己写新的子 agent spec（`agent_generate`）

**默认关闭**。开启后，LLM 在运行中发现自己缺一类助手时，可以起草一份新的子 agent spec 并直接写到 `workspace/subagents/<name>.md`：

```java
import io.agentscope.harness.agent.subagent.SubagentSpecGenerator;

// 内部实现：SubagentsMiddleware.enableAgentGenerateTool(new SubagentSpecGenerator(model))
```

**⚠️ 源码勘误（与官方文档不一致）**：官方文档写"拿到 builder 内部的 SubagentsMiddleware 引用，调一下 `enableAgentGenerateTool`"——但当前 `HarnessAgent.Builder` **没有公开**获取/配置 `SubagentsMiddleware` 的入口（`SubagentsMiddleware` 是 build() 内部自动构建并注册的），也没有 `enableAgentGenerateTool` 便捷方法。实际可行的开启方式：① 自建 `SubagentsMiddleware`（构造后调 `enableAgentGenerateTool(new SubagentSpecGenerator(model))`），再通过 `builder.middleware(...)` 注入——但 build() 内部仍会再构建一个默认的 `SubagentsMiddleware`，需配合 `builder.disableSubagents()` 避免重复注册；② 直接构造 `AgentGenerateTool`，通过 `builder.toolkit(...)` 自定义 Toolkit 注册。生产环境**慎用**——通常先让 agent 把方案写出来人工 review 再落文件。

---

## 10. 行为细节与边界（面试常考）

### 10.1 `description` 要写好

这是模型决定**要不要委派**的关键依据。"代码评审"远不如"当用户要 review PR、找代码风格问题时使用"有效。

### 10.2 递归保护（软约束 + 硬上限）

- **软约束**：每个子 agent 的系统提示里被注入"你是 leaf worker，不要再 spawn 子 agent"（源码 `HarnessAgentBuilderSupport.SUBAGENT_CONTEXT_SECTION`）——提示模型别往下传；
- **硬上限**：spawn 深度上限 **3 层**（源码 `AgentSpawnTool.MAX_SPAWN_DEPTH = 3`），超过直接返回 `Maximum spawn depth exceeded` 错误工具结果；
- `mode: primary` 的声明**不允许被 spawn**（返回 `PRIMARY-only` 错误）。

### 10.3 身份与安全沿链传递

| 继承项 | 行为 | 关闭方式 |
|---|---|---|
| `userId` | 父的 `RuntimeContext.userId` 自动透传，多租户隔离链不断 | 不可关 |
| DENY 权限规则 | 父的 DENY 全部传给子，父禁用的工具子一样禁用 | `inheritParentPermissions(false)` |
| Plan Mode 只读 | 父在 Plan Mode 时 spawn 的子自动进入 Plan Mode，无法写操作 | 不可关 |

### 10.4 悬空工具调用恢复

自动构建的本地子 agent（含 `general-purpose`）继承父的 `enablePendingToolRecovery(...)` 配置，**默认关闭**。开启后：

- 新的普通消息会为悬空工具调用补充错误结果；
- 也适用于从失败会话中重新加载的工具调用；
- **等待人工审批的工具仍须提供审批结果**——审批确认不会被自动恢复；
- 远程 agent 和自定义工厂需自行配置恢复策略。

---

## 11. 远程子 agent（Agent Protocol）

声明里只填 `url` + 可选 `headers`，子 agent 就走远程 HTTP 服务执行（Agent Protocol）：

```java
.subagent(SubagentDeclaration.builder()
    .name("remote-researcher")
    .description("远端调研子 agent")
    .url("http://agent-task-server:8080")
    .headers(Map.of("Authorization", "Bearer xxx"))
    .remoteStreaming(true)                       // 默认 true
    .remoteStreamDetail(RemoteStreamDetail.VERBOSE)  // 默认 FULL
    .remoteAskPolicy(RemoteAskPolicy.DENY)       // 默认 DENY
    .remoteContextAttributes(Map.of("region", "cn"))
    .build())
```

同样支持同步（`timeout_seconds>0`）和后台（`timeout_seconds=0`）。远程模式专用字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `remoteStreaming` | `true` | 父用 `streamEvents()` 时，把远程任务的 SSE 事件转发进父流 |
| `remoteStreamDetail` | `FULL` | 回传多少远程事件（见下） |
| `remoteAskPolicy` | `DENY` | 远程工具确认（HITL）怎么处理 |
| `remoteContextAttributes` | 无 | 每次提交都携带的静态调用方属性（`context.attributes`）；按次追加用 `AgentSpawnTool.CTX_REMOTE_CONTEXT_ATTRIBUTES` 放 map 到父的 `RuntimeContext` |

### 11.1 远程流式详细度（三档）

| 档位 | 回传内容 |
|---|---|
| `STATUS` | 运行生命周期、工具调用起止、工具结果、确认请求 |
| `FULL`（默认） | STATUS + 文本与思考增量 |
| `VERBOSE` | 远程 agent 的**每一个**事件：块边界、工具入参增量、**工具输出增量**、带 token usage 的模型调用、hint、结果、自定义事件 |

**选型建议**：希望父流在本地/远程子 agent 之间表现一致，选 `VERBOSE`——它是唯一能让远程子 agent 的工具输出内容和 token 用量到达父 agent 的档位。只渲染文本的调用方选默认即可，避免额外流量。

### 11.2 远程授权（HITL 确认）

父的 DENY 权限规则随远程提交的 `context.deny_rules` 转发（可用 `inheritParentPermissions(false)` 关闭）。远程 agent 因工具确认暂停（`awaiting_confirm`）时：

- **父流式 + `remoteAskPolicy=PROPAGATE`**：向父的 `streamEvents()` 转发带 `source` 标记的 `RequireUserConfirmEvent`；恢复走 Agent Protocol `POST /tasks/{id}/resume`，请求体 `decisions[{toolCallId, approved, reason}]`；
- **父非流式或 `remoteAskPolicy=DENY`（默认）**：自动拒绝待确认项，工具结果附注 `remote tool confirmation(s) were auto-denied`。

等待确认期间任务状态保持 `RUNNING`（`awaitingConfirm=true`），所以 `wait_async_results` 等 barrier 会继续等待直到任务被 resume 进入终态。

---

## 12. 异步任务的存储位置

后台任务状态默认写到 `workspace/agents/<parentAgentId>/tasks/<sessionId>.json`。含义：

- **共享存储模式（多副本）**：任意节点都能读到任务状态；
- 任务执行**粘在创建节点**，但完成结果可被任意节点读到、正常推送回父 agent；
- 取消可以从任意节点调 `task_cancel`——执行节点轮询取消标记后中止。

---

## 13. 子 Agent 流式（事件如何透传）

### 13.1 事件流模型

新代码用 `streamEvents()`（返回 `Flux<AgentEvent>`）；旧 `stream()` 系列（`Flux<Event>`）自 2.0.0 起 `@Deprecated(forRemoval = true)`。

父 agent 同步调用子 agent 时，子 agent 的中间事件**实时转发**到父的 `streamEvents()` 流中。每个子事件带 `source` 字段（`/` 分隔的路径，如 `"main/researcher"`），父事件的 `source` 为 `null`。

```
caller
└─ parent.streamEvents(msg, ctx)
   ├─ AGENT_START                                    ← 父启动
   ├─ TEXT_BLOCK_DELTA …                             ← 父推理
   ├─ TOOL_CALL_START "agent_spawn"                  ← 父要 spawn
   │   [子 agent 创建]
   ├─ AGENT_START (source="main/researcher")         ← 子启动
   ├─ TEXT_BLOCK_DELTA … (source="main/researcher")  ← 子推理
   ├─ TOOL_CALL_START … (source="main/researcher")
   ├─ TOOL_RESULT_END … (source="main/researcher")
   ├─ AGENT_END (source="main/researcher")           ← 子结束
   │   [agent_spawn 返回，子结果作为 TOOL_RESULT 传给父]
   ├─ TOOL_RESULT_END                                ← 父收到工具结果
   ├─ TEXT_BLOCK_DELTA …                             ← 父第二轮推理
   └─ AGENT_END                                      ← 父结束
```

远程 Agent Protocol 子 agent 额外写入 `metadata.taskId` 与 `metadata.parentSessionId`——同一轮里对同一远程 agent 的多次调用即使 `source` 相同也能区分。

### 13.2 使用 `streamEvents()`（推荐）

```java
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;

parent.streamEvents(new UserMessage(message), ctx)
    .doOnNext(event -> {
        String src = event.getSource();
        String prefix = (src != null) ? "[" + src + "] " : "";

        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
            System.out.print(prefix + ((TextBlockDeltaEvent) event).getDelta());
        } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
            System.out.println(prefix + "[tool] " + ((ToolCallStartEvent) event).getToolCallName());
        } else if (event.getType() == AgentEventType.AGENT_START) {
            if (src != null) System.out.println("── 子 agent 启动: " + src);
        } else if (event.getType() == AgentEventType.AGENT_END) {
            if (src != null) System.out.println("── 子 agent 结束: " + src);
        }
    })
    .blockLast();
```

区分父子事件：

```java
events.filter(e -> e.getSource() == null).subscribe(…);                              // 只看父
events.filter(e -> e.getSource() != null).subscribe(…);                              // 只看子
events.filter(e -> e.getSource() != null && e.getSource().contains("researcher")).subscribe(…);  // 只看某子 agent
```

### 13.3 哪些场景会实时流转发（行为边界表）

| 场景 | 是否实时流转发 |
|---|---|
| `streamEvents()` + 同步本地子 agent（`timeout_seconds > 0`） | ✔ |
| `call()` 模式（非流式） | ✗（子结果以 `tool_result` 字符串返回） |
| `timeout_seconds = 0` 后台任务 | ✗（终态通过反向通知给父下一轮） |
| 远程子 agent + 父 `streamEvents()` + `remoteStreaming=true`（默认） | ✔ |
| 远程子 agent + 父 `call()` 或 `remoteStreaming=false` | ✗ |

### 13.4 错误处理

子 agent 内部出错时，框架**捕获错误并写成一条 `TOOL_RESULT` 给父**，不会把 `onError` 传播到父流——父流不会被子 agent 失败打断。父流自身出错（如模型调用失败）按标准 Reactor 语义处理（`onErrorResume` 等）。

---

## 14. 面试速查

### 高频考点

1. **子 agent 解决了什么问题？**
   上下文膨胀与串行瓶颈：把"可独立处理、上下文重、可并行"的任务委派出去，每个子任务独立会话、结果作为工具结果返回。对父而言子 agent 就是一个工具。

2. **`agent_id` / `agent_key` / `task_id` 的区别？**
   类型名 / 实例句柄（spawn 返回第一行，传给 agent_send）/ 后台任务编号（spawn 用 timeout_seconds=0 时返回）。三者不是一回事。

3. **同步和后台怎么切换？**
   `timeout_seconds > 0`（默认 30，最大 600）同步等结果；`= 0` 后台立即返回 task_id；超时会 promote 成后台任务（`timeout_promoted`）。应用可用 `RuntimeContext` 的 `CTX_FORCE_SYNC` 强制同步，强制后超时不会 promote。

4. **后台任务结果怎么拿？还要轮询吗？**
   不需要轮询——框架在下一轮推理前把已完成结果作为 `<system-reminder>` 注入父的对话。逃生口是 `task_output`（查单个）、`wait_async_results`（barrier 等齐，注意 wait_all 语义）、`task_cancel`、`task_list`。

5. **ISOLATED 和 SHARED 的区别？**
   ISOLATED（默认）子 agent 有自己的工作区（未指定路径时自动建 `agents/<name>/workspace/`），运行时状态按 (userId, 父 sessionId) 分桶互不污染；SHARED 直接用父工作区，适合输出被父立即读取的场景（如 general-purpose）。

6. **子 agent 的三种声明方式？**
   内置 `general-purpose`（总是可用，镜像主 agent）；工作区 spec 文件（`subagents/<id>.md`，文件名即 agent_id，构建期自动扫描）；编程式 `builder.subagent(SubagentDeclaration.builder()...)`。三种来源互斥：workspace / inlineAgentsBody / url 三选一。

7. **怎么让用户直接和子 agent 对话？**
   `agent_spawn(expose_to_user=true)` + 父 agent 绑定 Channel（`agent.channel(ChatUiChannel.create())`）。客户端监听 `SubagentExposedEvent` 拿到 `subagentId`，用 `chat.sendToSubagent(subagentId, msg)` 绕过父 agent。跨重启需要 `distributedStore`。

8. **安全边界怎么沿委派链传递？**
   父的 DENY 权限规则、Plan Mode 只读限制、userId 自动传给子；spawn 深度上限 3 层；`mode: primary` 不可被 spawn。想放开权限继承用 `inheritParentPermissions(false)`（罕见）。

9. **子 agent 事件怎么流回父？**
   同步子 agent 的中间事件实时转发进父的 `streamEvents()`，每个子事件带 `/` 分隔的 `source`（如 `main/researcher`），父事件 source 为 null。子 agent 出错不会打断父流——错误被写成 TOOL_RESULT。

### 关键默认数字

| 项 | 默认值 |
|---|---|
| `timeout_seconds` | 30 秒（同步），最大 600 秒 |
| spawn 深度上限 | 3 层 |
| `steps`（单次最大推理迭代） | 10 |
| `workspaceMode` | ISOLATED |
| `persistSession` | false（每次 spawn 新实例） |
| `expose_to_user` 兜底 | false |
| 权限继承 | true（DENY 规则透传） |
| `remoteStreaming` / `remoteStreamDetail` / `remoteAskPolicy` | true / FULL / DENY |
| 后台任务存储 | `workspace/agents/<parentAgentId>/tasks/<sessionId>.json` |
