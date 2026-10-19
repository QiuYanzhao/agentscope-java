# Harness 记忆（Memory）（重构笔记）

> 原文：<https://java.agentscope.io/v2/zh/docs/harness/memory>
> 重构目标：快速建立设计思想 + 架构分层 + API 速查，应对面试。
> 本文以源码为准（`agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/`），官方文档与源码不一致处均已标注勘误。

---

## 1. 它解决什么问题

Agent 有两件容易搞混的"记性"难题：

| 难题 | 表象 | 后果 |
|---|---|---|
| **跨会话记忆** | 昨天你说过"项目用 Java 17"，今天再开一个新会话它全忘了 | 每次都要重新交代背景，体验像失忆 |
| **上下文无限增长** | 长任务里消息越堆越多，直到塞爆模型窗口 | 要么报错，要么烧钱，要么早期信息被挤掉 |

Harness 用**两套独立机制**分别回答这两个问题，先记住一句话：

- **记忆（Memory）= 把"值得长期记住的事实"抽出来存进文件**，下次会话还能用；
- **压缩（Compaction）= 把"当前这次对话的历史"蒸馏成摘要**，让模型能继续干下去。

两者都调用 LLM，但写入目标、prompt、触发时机完全不同——这是全篇最容易被绕晕的地方（第 2 节的三处 LLM 调用会一次讲清）。

另外还有两个"不调 LLM"的帮手：**大工具结果卸载**（单次输出太大就落盘换占位符）和**参数截断**（工具调用参数太长就裁剪），都属于上下文治理。

> 与已重构文档的关系：本文讲"长期记忆"（`MemoryConfig` 管辖）与"压缩/卸载"（`CompactionConfig`/`ToolResultEvictionConfig` 管辖）；压缩预算与卸载的完整机制见《上下文管理（重构笔记）》第 6 节，本文只讲与记忆相关的部分。Session Log（完整执行记录）见《harness-session-log（重构笔记）》——记忆会被摘要和合并，**不能拿记忆当完整历史**。

---

## 2. 核心心智模型（面试必背）

### 2.1 两层记忆：日流水账 vs 策划后的 MEMORY.md

| 层 | 文件 | 特点 | 谁写 | 谁读 |
|---|---|---|---|---|
| **第一层 · 日流水账** | `memory/YYYY-MM-DD.md` | 每天一个文件，**只追加、不去重、原始** | `MemoryFlushManager`（flush） | 等待被第二层合并 |
| **第二层 · 策划后长期记忆** | `MEMORY.md` | **整体重写**、LLM 合并去重、有 token 上限 | `MemoryConsolidator`（consolidation） | 每轮推理作为参考材料注入 |

**关键认知（面试点）**：

1. 第一层只追加、不去重；第二层周期性整体重写；**两层互不覆盖**——flush 绝不写 `MEMORY.md`，consolidation 绝不动日流水账。
2. **第二层才是模型每次看到的来源**；第一层是"待合并的草稿"，等后台 consolidation 把它并入 `MEMORY.md`。
3. 对话被压缩前的**原始消息**会另存一份永不压缩的日志（`*.log.jsonl`，即 Session Log），供审计和 `session_search`——与记忆是两条独立存储。

### 2.2 三处 LLM 调用全景（最易混淆，先背这张表）

记忆管线里一共有**三处独立的 LLM 调用**，每一处都有自己的 prompt、触发时机和配置入口：

| # | 操作 | 写入目标 | Prompt（默认） | 触发时机 | 由谁配置 |
|---|---|---|---|---|---|
| 1 | **Flush** | `memory/YYYY-MM-DD.md`（追加） | `MemoryFlushManager.DEFAULT_FLUSH_PROMPT` | 每次 `call()` 结束（可节流） | `MemoryConfig.flushPrompt(...)` |
| 2 | **Consolidation** | `MEMORY.md`（整体重写） | `MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT` | 后台节流任务 | `MemoryConfig.consolidationPrompt(...)` |
| 3 | **Compaction summary** | 注入当前上下文（一条摘要消息） | `CompactionConfig.DEFAULT_SUMMARY_PROMPT` | 消息/token 超阈值 | `CompactionConfig.summaryPrompt(...)` |

口诀：**前两个沉淀长期记忆（`MemoryConfig` 管），第三个压缩当下上下文（`CompactionConfig` 管）**。三处默认共用 agent 主模型，但各自支持 `.model(...)` 覆盖成便宜的小模型。

### 2.3 整体数据流（一图看懂）

```
每轮 call 结束 ──► Flush（LLM ①）──► memory/YYYY-MM-DD.md（追加）
                                              │
                             后台节流任务 ──► Consolidation（LLM ②）──► MEMORY.md（重写）──► 每轮推理注入 prompt
                                              │
                      对话历史超阈值 ──► Compaction（LLM ③）──► 摘要消息替换历史前缀
                                              │
                      单条工具输出超大 ──► Eviction（无 LLM）──► large_tool_results/ + 预览占位符
```

记忆与"三个历史"的分工（承接《上下文管理》第 7 节）：压缩改变"模型继续看什么"，记忆改变"以后的会话记得什么"，Session Log 提供"人回看全程的记录"，三者互不替代。

---

## 3. 自动沉淀：Flush（LLM 调用 ①）

### 3.1 解决什么问题

用户说了一堆背景、偏好、决定，如果没人管，这些信息只活在当前对话里，会话一结束就蒸发。Flush 负责**每次对话结束后，把值得长期记住的事实抽出来**，追加进今天的日流水账。

### 3.2 为什么这样设计

- 抽取走 LLM，但用的是**独立的 system prompt**（默认 `DEFAULT_FLUSH_PROMPT`），模型只输出 markdown 列表，**没有值得记的就输出 `NO_REPLY`**，框架跳过写入；
- 抽取时把 **`MEMORY.md` 和今天的日流水账作为只读上下文**一并喂给模型，让它"不要重复记已知事实"；
- 写入是**追加**，原始事实先完整保留，去重交给后面的 consolidation。

### 3.3 三个触发点

| 触发点 | 机制 | 说明 |
|---|---|---|
| ① **每次 `call()` 结束** | `MemoryFlushMiddleware`（默认行为） | 每轮对话完都抽一次；可用 `flushTrigger` 改 `NEVER` / `THROTTLED` |
| ② **压缩之前** | `CompactionConfig.flushBeforeCompact = true`（默认） | 对话前缀要被蒸馏成摘要前，先把它里面的事实抽走，防止"历史没了、记忆也没留下" |
| ③ **上下文溢出兜底** | 模型真报 `context_length_exceeded` 时紧急压缩 | 强制压缩时连带 flush 一次 |

三处共用**同一份 `flushPrompt`**，定制后行为一致。

### 3.4 异步性与并发（面试细节）

- **Flush 是 fire-and-forget**：在响应流 `doOnComplete` 后启动，跑在 `Schedulers.boundedElastic()`，**不阻塞当前 `call()` 的返回**——调用方拿到完整回复后，flush 才在后台开始。
- **节流窗口按隔离维度分桶**：`USER`（默认）按 `userId`、`SESSION` 按 `sessionId`、`AGENT/GLOBAL` 全实例共享一个窗口。
- **同桶并发串行化**：同一隔离 key 同时最多一个 flush 在跑；排队中的多个 flush **合并成一个**（新任务执行时读到的对话状态已包含被替换任务的内容，不会丢消息）。

### 3.5 Flush 的输入裁剪（容易忽略）

flush 喂给模型的是"对话窗口的序列化文本"，不是原样全量：

- 跳过 `SYSTEM` 消息和内部上下文消息（`<session_context>` 包装、压缩摘要消息）；
- 工具调用参数**截断到 500 字符**、工具结果**截断到 1000 字符**——只是给抽取模型看的浓缩版。

### 3.6 边界与坑

- 默认 `ALWAYS` 意味着**长会话每轮都花一次 LLM 调用**做抽取，成本不低；想省钱用 `THROTTLED`（见 7.1）。
- `flushTrigger(NEVER)` **只关触发点 ①**，②③ 照跑，后台 consolidation 也不受影响；想连后台一起关用 `disableMemoryHooks()`。
- flush 失败只记 warning，**不会让本次对话失败**——它是尽力而为的增强，不是可靠性链路。

---

## 4. 后台 Consolidation 与维护（LLM 调用 ②）

### 4.1 解决什么问题

日流水账每天堆积：一天 3 次 flush 就 3 段，90 天几百段，里面大量重复、过期、噪音。需要一个"图书管理员"定期把草稿整理成一本干净的 `MEMORY.md`。

### 4.2 工作机制（watermark 增量合并）

`MemoryConsolidator` 每次运行：

1. 读上次成功合并的时间戳（状态文件 `memory/.consolidation_state`）；
2. **只读 modifiedAt 晚于该时间戳的日流水账** + 当前 `MEMORY.md`；
3. 让 LLM 合并去重、删过期，**整体重写 `MEMORY.md`**（上限 `consolidationMaxTokens`，默认 4000）；
4. 成功后推进 watermark。

**关键行为**：如果自上次合并以来没有新增日流水账，**直接跳过，不调 LLM**——省 token 靠 watermark，不靠运气。

### 4.3 维护任务（MemoryMaintenanceMiddleware）

每次 `call()` 结束后台节流触发（最小间隔 `consolidationMinGap`，默认 **30 分钟**；**第一次符合条件的 call 立即放行**，间隔只约束后续）：

| 步骤 | 动作 |
|---|---|
| 1 | 把超过 `dailyFileRetentionDays`（默认 **90 天**）的日流水账**移动**到 `memory/archive/` |
| 2 | 跑一次 consolidation（若配置了） |

维护和 per-call flush 是**两套独立的节流窗口**，互不影响。

> ⚠️ **勘误**：官方文档列出的 `sessionRetentionDays` 字段（"默认 180 天后清掉 *.log.jsonl"）及其示例 `sessionRetentionDays(60)` **在当前源码中不存在**——`MemoryConfig` 只有 7 个字段（见 7.6 速查表），`MemoryMaintenanceMiddleware` 也不清理会话日志。Session Log 的存储与保留走 SessionLogStore 体系（见《harness-session-log》第 6 节），别在 `MemoryConfig` 里找它。

---

## 5. 记忆工具：给模型自己读写记忆

> 官方文档只讲了 2 个，实际注册 **4 个**（`HarnessAgent` 在 `!disableMemoryTools` 时注册）：
> `memory_search`、`memory_get`、`memory_save`、`session_search`。

### 5.1 写记忆：`memory_save`（唯一推荐的写入通道）

**解决什么问题**：用户说"记住 X"或模型观察到重要偏好时，需要立刻落盘，不等下个 flush。

**为什么这样设计**：如果让模型用 `write_file` 写 `MEMORY.md`，它会随机起文件名、跳过日流水账、写坏格式。所以 Harness 把写入收敛成**一个原子工具**：同时追加 `MEMORY.md` 和今天的日流水账。系统提示里直接写明：**不要用 `write_file` / `edit_file` 碰 `MEMORY.md` 或 `memory/` 下任何路径**。

```json
{ "content": "- 用户偏好暗色主题\n- 项目截止日期是 2026-07-01" }
```

模型每次调用传一条 markdown 列表；返回 "Saved N memories to MEMORY.md"。

### 5.2 查记忆：`memory_search` + `memory_get`

**解决什么问题**：`MEMORY.md` 注入 prompt 时有 8000 token 预算（见 6 节），超了就被截断——模型看到截断提示时，应该自己调工具找回老内容。

`memory_search`（关键词扫描 `MEMORY.md` + `memory/*.md`，逐行匹配）：

| 参数 | 必填 | 说明 |
|---|---|---|
| `query` | 是 | 字面短语，或空格分隔的关键词（按 matchMode 解释） |
| `matchMode` | 否 | `phrase`（默认，精确子串）/ `all`（同**行**含全部关键词）/ `any`（同**行**含任一关键词） |

- 大小写不敏感；**没有中文分词**，中文请传完整短语。
- 命中返回 `Source: <path>#<line>: <内容>` 格式，方便模型引用。
- > ⚠️ **勘误**：官方文档说"最多返回 30 条命中"，源码**没有条数上限**，命中多少返多少。

`memory_get`（读指定行范围，通常在 `memory_search` 之后拉上下文）：

```json
{ "path": "memory/2026-06-02.md", "startLine": 10, "endLine": 40 }
```

- 三个参数**全部必填**，1-based 含端点；
- 有 path traversal 校验，拒绝逃出工作区的路径。

### 5.3 查历史：`session_search`

在**完整会话日志**（`*.log.jsonl`）里搜历史对话，回答"上次我们讨论到哪了"这类问题。与 `memory_search` 的区别：前者搜的是**事实**（已经整理过的记忆文件），后者搜的是**原始对话**（未加工的完整记录）。

### 5.4 边界

- 工具注册受 `disableMemoryTools()` 控制，关掉后模型既没有工具也没有对应引导（见 8 节）；
- 记忆路径的写入**必须走工具或框架**，绕开文件工具是为了防止模型破坏数据结构；
- flush（自动）与 `memory_save`（主动）都写日流水账，都靠 consolidation 去重，互不冲突。

---

## 6. MEMORY.md 如何进入 prompt（注入机制）

**解决什么问题**：光存进文件不算数，模型每次推理时得能看到记忆。

**机制**：`WorkspaceContextMiddleware` 在**每次 `call()` 开始时**读取 `MEMORY.md`，作为 kind=`memory` 的参考材料放进 `HARNESS_CONTEXT`（USER 层参考消息，不是 System 指令）。

**预算与截断**：工作区材料总预算 `maxContextTokens`（默认 **8000**）内，`MEMORY.md` 超出部分被截断，并追加提示：

```
... (memory truncated — use memory_search for older entries) ...
```

——这就是模型"看到截断就自己调 `memory_search`"的引导来源。工具被禁用时提示变成无工具的简化版。

**注入前提（容易踩）**：`MEMORY.md` 加载条件是 `!(disableMemoryTools && disableMemoryHooks)`——**两个开关都关**才会跳过注入；只关其中一个，记忆照样注入。

---

## 7. 上下文治理：压缩、卸载、截断（与记忆的关系）

> 完整机制见《上下文管理（重构笔记）》第 6 节。这里只给与记忆同页的配置默认值，重点是**它和记忆是两套独立 pipeline**。

### 7.1 压缩 Compaction（LLM 调用 ③）

**是什么**：对话历史太长时，把早期消息蒸馏成一条摘要，保留尾部，让模型"接着干"。

**开启即默认全套**，`HarnessAgent.builder()` 配一次：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .compaction(CompactionConfig.builder()
        .triggerMessages(30)     // 消息条数到 30 触发（默认 50；0=关闭）
        .keepTokens(0)           // 切到"消息数保留"模式（默认 -1 走动态 token 预算）
        .keepMessages(10)        // 压缩后保留最近 10 条（仅 keepTokens=0 时生效）
        .build())
    .build();
```

**默认值速查（源码 `CompactionConfig.Builder` 为准）**：

| 参数 | 默认 | 含义 |
|---|---|---|
| `triggerMessages` | 50 | 按条数触发（0 = 关闭该条件） |
| `triggerTokens` | 0 | 0 = 动态：模型窗口 − `reserved`（20k）；模型不报告窗口时回退 **160k**；>0 = 显式阈值（仍会取 `min(配置值, 预算)`，不会超过可用预算） |
| `reserved` | 20,000 | 给压缩过程本身（摘要 prompt + 输出）预留的 token，只在动态模式下用 |
| `keepMessages` | 20 | 保留尾部条数（仅 `keepTokens=0` 时生效） |
| `keepTokens` | -1 | -1 = 动态 `min(8k, max(2k, usable × 0.25))`；0 = 用 `keepMessages`；>0 = 固定预算（动态/静态的最终值还会被 clamp 到 `预算/2`） |
| `keepTokensMin` | 2,000 | 动态保留预算的下限 |
| `keepTokensMax` | 8,000 | 动态保留预算的上限 |
| `keepTokensRatio` | 0.25 | 动态保留预算 = `usable × 0.25` |
| `flushBeforeCompact` | true | 压缩前先 flush（第 3.3 节触发点 ②） |
| `summaryPrompt` | `DEFAULT_SUMMARY_PROMPT` | 摘要 prompt，**必须含 `{messages}` 占位符** |
| `model` | null（agent 主模型） | 压缩摘要用独立模型 |

**触发与保留的判定逻辑（源码 `ConversationCompactor.shouldCompact` / `determineCutoffIndex`）**：

**① 触发：两个条件是"或"，不是优先级，也不会冲突**

```java
// shouldCompact：任一命中即压缩
if (triggerMessages > 0 && 消息数 >= triggerMessages) return true; // 条件 A
if (triggerTokens  > 0 && token数  >= triggerTokens)  return true; // 条件 B
return false;
```

- 默认（50 条 / 动态 token 阈值）下**谁先到谁触发**：消息密集的短对话先撞 50 条，长文本对话先撞 token 阈值；
- `triggerMessages(0)` 可关闭条件 A → 纯 token 触发；
- **没有官方"关闭条件 B"的开关**：`triggerTokens(0)` 是动态模式（仍会解析出一个具体阈值），想近似纯消息触发只能设一个远大于预算的 `triggerTokens`。

**② 保留：三值语义，`keepTokens` 决定模式，`keepMessages` 只在 `keepTokens=0` 时生效**

```java
// determineCutoffIndex
if (keepTokens > 0) cutoff = 按 token 预算从尾部往回扫; // token 模式（keepMessages 忽略）
else                cutoff = 按 keepMessages 条数;      // 消息模式
```

| `keepTokens` 取值 | 生效模式 | `keepMessages` 是否生效 |
|---|---|---|
| `-1`（默认，动态） | 动态公式解析成具体 token 值 → **token 模式** | 否（默认值 20 形同虚设） |
| `0` | **消息模式** | 是 |
| `> 0`（静态） | token 预算模式 | 否 |

- **动态解析与 clamp**：`keepTokens=-1` 时先算 `min(8k, max(2k, usable×0.25))`，再 `min(结果, 预算/2)`；静态 `keepTokens>0` 同样被 `min(配置值, 预算/2)` 约束——防止"摘要 + 尾部"超出可用预算（`triggerTokens>0` 同理被 `min(配置值, 预算)` 约束）；
- **易踩坑**：想"保留最近 N 条消息"，必须 `keepTokens(0)` + `keepMessages(N)`；**只配 `keepMessages(N)` 不生效**（默认 `-1` 走动态 token 模式）。

**③ 决策表（配置速查）**

| 想实现 | 配置 |
|---|---|
| 默认智能模式（消息或 token 谁先到谁触发；保留约 8k token 尾部） | 不配（全默认） |
| 只按 token 触发 | `.triggerMessages(0)` |
| 只按消息数触发 | `.triggerTokens(极大值)`（无官方关闭项，见上） |
| 保留最近 N 条消息 | `.keepTokens(0).keepMessages(N)` |
| 保留固定 token 尾部 | `.keepTokens(N)` |
| 保留动态尾部但收紧上限 | `.keepTokens(-1).keepTokensMax(N)` |

**裁剪旧工具结果（prune，无 LLM、默认开）**：压缩前先做轻量裁剪——保护最近 **40k token** 的工具输出不动，可裁剪总量超过 **20k token** 时，旧结果替换成首尾预览。默认排除 `read_file` / `memory_search` / `memory_get` / `session_search`。

**参数截断（truncateArgs，默认关）**：`write_file` 这类工具的参数体量大但后期没人看。在 LLM 摘要之前做一次**不走 LLM 的字符串截断**：

```java
.compaction(CompactionConfig.builder()
    .triggerMessages(80)
    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
        .maxArgLength(2000)                    // 单参数最长 2000 字符（默认）
        .truncationText("... [truncated] ...") // 截断后缀
        .build())
    .build())
```

默认触发：25 条消息或 40k token，最近 20 条不动。**不配置 `truncateArgs` 就是关闭的**。

**上下文溢出自动恢复**：模型真的返回 `context_length_exceeded` 时，框架强制做一轮压缩然后重试一次——**前提是你配了 `.compaction(...)`**，否则错误直接抛回上层。

### 7.2 大工具结果卸载 Eviction（无 LLM）

**是什么**：某次工具返回超过阈值时，全文写盘、上下文只留首尾预览 + 占位符，模型想看全文自己 `read_file`。

```java
HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();
```

| 项 | 默认 |
|---|---|
| 触发阈值 | **80,000** 字符（约 20k token） |
| 预览 | 首尾各 **2,000** 字符 + "完整内容见 {path}" |
| 落盘路径 | 工作区 `large_tool_results/` |
| 排除的工具 | `read_file`、`write_file`、`edit_file`、`memory_search`、`memory_get`、`session_search`（**6 个**） |

> ⚠️ **勘误**：官方文档只说"默认排除 read_file"。源码默认排除上述 **6 个工具**（文件读写/编辑工具返回小结果且避免回读循环，记忆工具结果小而可分页）；`execute`（shell 输出可能巨大）**故意不排除**。

**与压缩的关系**：完全独立、可单独开关。卸载治"单条消息太宽"（width），压缩治"消息太多太深"（depth）。

---

## 8. 定制与关闭：MemoryConfig 按意图分组

### 8.1 节流 per-call flush，省 token

每次 call 结束都抽一次，长会话成本不低。节流到"最多每 10 分钟一次"：

```java
HarnessAgent.builder()
    .name("MyAgent").model(model).workspace(workspace)
    .memory(MemoryConfig.builder()
        .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
        .build())
    .build();
```

**注意**：`THROTTLED` 只影响触发点 ①。压缩内嵌的 flush（②）和溢出兜底（③）按各自条件照常跑；第一次符合条件的 call 立即 flush，`Duration` 只约束后续。offload 与 Session Log 不受影响。

### 8.2 完全关掉 per-call flush

```java
.memory(MemoryConfig.builder()
    .flushTrigger(MemoryConfig.FlushTrigger.never())
    .build())
```

只有压缩发生时才会 flush（成本和原始压缩成本一致）。**这只关 per-call flush，后台 consolidation 仍跑**；想把 flush + 后台维护全关，用 `disableMemoryHooks()`。

### 8.3 在默认 flush prompt 上追加项目规则

```java
.memory(MemoryConfig.builder()
    .flushPrompt(MemoryFlushManager.DEFAULT_FLUSH_PROMPT + """
        Additional project rules:
        - Never record customer PII (names, emails, phone numbers).
        - Always use Chinese for project-internal vocabulary.
        """)
    .build())
```

`flushPrompt` 是 **SYSTEM 消息，无占位符要求**，原样透传。

### 8.4 完全自定义 consolidation prompt

```java
.memory(MemoryConfig.builder()
    .consolidationPrompt("""
        You are merging daily memory ledgers into MEMORY.md.
        Keep within %d tokens (~%d chars). Output the complete file in markdown.
        ... your custom rules ...
        """)
    .build())
```

**硬性约束（源码强校验）**：自定义 `consolidationPrompt` **必须恰好包含两个 `%d` 占位符**（依次是 max-tokens 和 max-chars），否则 `Builder` **构造时就抛 `IllegalArgumentException`**——错误尽早暴露，而不是运行时才崩。

### 8.5 调整后台维护节奏

```java
.memory(MemoryConfig.builder()
    .consolidationMinGap(Duration.ofHours(2))  // 首次可立即运行，之后至少间隔 2 小时
    .dailyFileRetentionDays(30)                // 30 天后日流水账归档到 memory/archive/
    .consolidationMaxTokens(8_000)             // MEMORY.md 上限放宽到 8K tokens
    .build())
```

### 8.6 用小模型跑记忆操作

flush / consolidation / 压缩摘要都不需要主推理模型那么强：

```java
HarnessAgent.builder()
    .name("MyAgent").model("openai:o3")        // 主推理模型
    .memory(MemoryConfig.builder()
        .model("openai:gpt-4.1-mini")          // 记忆操作（flush + consolidation）用小模型
        .build())
    .compaction(CompactionConfig.builder()
        .model("openai:gpt-4.1-mini")          // 压缩摘要也用小模型
        .build())
    .build();
```

`model(String)` 走 `ModelRegistry.resolve()`，也可直接传 `Model` 实例；不设则回退 agent 主模型。

### 8.7 MemoryConfig 字段速查（源码为准，7 个）

| 字段 | 默认 | 作用 |
|---|---|---|
| `model` | null（agent 主模型） | flush / consolidation 的独立模型 |
| `flushPrompt` | null（`DEFAULT_FLUSH_PROMPT`） | 触发点 ① 的 SYSTEM prompt，无占位符 |
| `consolidationPrompt` | null（`DEFAULT_CONSOLIDATION_PROMPT`） | 触发点 ② 的模板，**必须含恰好两个 `%d`** |
| `consolidationMaxTokens` | 4,000 | `MEMORY.md` token 上限 |
| `consolidationMinGap` | 30 min | 后台维护最小间隔；首次立即放行 |
| `dailyFileRetentionDays` | 90 | 多少天后日流水账归档到 `memory/archive/` |
| `flushTrigger` | `always()` | `ALWAYS` / `NEVER` / `THROTTLED(Duration)` |

> 所有字段都有默认值，不调 `.memory(...)` 时与历史行为完全一致（no-op 升级）。

### 8.8 完全关掉 vs 关一半（面试高频）

| 开关 | 关掉什么 | 保留什么 |
|---|---|---|
| `disableMemoryHooks()` | per-call flush + 后台 consolidation/维护（"对话结束自动抽取"引导也去掉） | 记忆工具、`MEMORY.md` 注入 |
| `disableMemoryTools()` | `memory_search` / `memory_get` / `memory_save` / `session_search` 注册 + Memory Recall / memory_save 引导 | flush、后台维护、`MEMORY.md` 注入 |
| **两者一起** | 以上全部 + **跳过 `MEMORY.md` 注入** | Domain Knowledge / AGENTS / knowledge 上下文照常 |

要点：`disableMemoryHooks()` 是核选项（想全关用这个）；**只想节流不想关**，用 `.memory(MemoryConfig.builder().flushTrigger(...).build())`。想自己接管记忆、自己写工具时，两个一起上。

---

## 9. 最小可运行示例

对应官方示例 `agentscope-examples/documentation/.../harness/memory/MemoryCompactionExample.java`，开箱即跑：

```java
Path workspace = Files.createTempDirectory("agentscope-memory-example");

HarnessAgent agent =
        HarnessAgent.builder()
                .name("memory-demo")
                .sysPrompt("You are a helpful assistant. Keep answers under two sentences.")
                .model("dashscope:qwen-plus")
                .workspace(workspace)
                .compaction(
                        CompactionConfig.builder()
                                .triggerMessages(6)
                                .keepMessages(2)
                                .keepTokens(0)   // 强制消息数保留模式，短对话也能触发压缩
                                .build())
                .memory(
                        MemoryConfig.builder()
                                .flushTrigger(MemoryConfig.FlushTrigger.always())
                                .consolidationMinGap(Duration.ofSeconds(5)) // 演示用，实际默认 30 分钟
                                .build())
                .build();

RuntimeContext ctx = RuntimeContext.builder().sessionId("memory-demo-session").build();

// 多轮对话，触发压缩 + 每次结束自动 flush
Msg reply = agent.call(new UserMessage("The capital of France is Paris. Remember this fact."), ctx).block();

// flush/consolidation 是异步的，回读文件前稍等
Thread.sleep(3000);

Path memoryDir = agent.getWorkspaceManager().getMemoryDir(ctx); // <workspace>/<sessionId>/memory/
Path dailyLog  = memoryDir.resolve(LocalDate.now() + ".md");    // 日流水账（第一层）
Path memoryMd  = agent.getWorkspaceManager().resolveRuntimeDataPath(ctx, "MEMORY.md"); // 第二层
```

运行：

```bash
export DASHSCOPE_API_KEY=your_key
mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryCompactionExample
```

---

## 10. 面试速查

### 关键默认数字

| 项 | 默认值 |
|---|---|
| per-call flush 触发 | `ALWAYS`（每 call 结束） |
| 后台维护最小间隔 `consolidationMinGap` | 30 分钟（首次立即放行） |
| `MEMORY.md` token 上限 `consolidationMaxTokens` | 4,000 |
| 日流水账保留 `dailyFileRetentionDays` | 90 天 → `memory/archive/` |
| `MEMORY.md` 注入预算 `maxContextTokens` | 8,000 token（超出截断 + 引导调 `memory_search`） |
| 压缩触发 `triggerMessages` / `triggerTokens` | 50 条 **或** 动态 token 阈值（窗口 − 20k reserved；不报告窗口回退 160k）——**OR 竞争，任一先到即触发**；`triggerMessages(0)` 可关消息条件，token 条件无官方关闭项 |
| 压缩保留尾部 `keepTokens` | -1 动态 `min(8k, max(2k, usable×0.25))`，最终 clamp 到 `预算/2`；`keepMessages`（20）只在 `keepTokens(0)` 时生效 |
| 动态保留上下限 `keepTokensMin/Max/Ratio` | 2k / 8k / 0.25 |
| prune 旧工具结果 | 保护最近 40k token，可裁剪总量 > 20k 才动手 |
| 参数截断 `truncateArgs` | **默认关闭**；`maxArgLength` 2000 |
| 大结果卸载阈值 | 80,000 字符；预览首尾各 2,000；排除 6 个工具 |
| 上下文溢出 | 配了 compaction 时强制压缩并重试一次 |

### 高频考点

1. **两层记忆的区别？**
   第一层 `memory/YYYY-MM-DD.md`：每天追加、原始、不去重（flush 写）；第二层 `MEMORY.md`：LLM 周期性合并去重、整体重写、有上限（consolidation 写）。模型每轮看到的是第二层；两层互不覆盖。

2. **三处 LLM 调用分别是什么？谁管？**
   Flush（对话→日流水账）、Consolidation（日流水账→MEMORY.md）由 `MemoryConfig` 管；Compaction summary（历史→摘要）由 `CompactionConfig` 管。前两个是"沉淀记忆"，第三个是"压缩当下"，默认共用主模型但可各配小模型。

3. **Flush 的三个触发点？是同步还是异步？**
   每次 call 结束（默认）、压缩之前（`flushBeforeCompact=true`）、上下文溢出兜底。全部是 fire-and-forget 异步执行（`doOnComplete` 后后台调度），不阻塞调用返回。

4. **Consolidation 怎么避免每次调 LLM？**
   watermark（`memory/.consolidation_state`）记录上次成功合并时间，只读之后修改的日流水账；没有新增就跳过，不调模型。

5. **为什么不能用 `write_file` 写 `MEMORY.md`？**
   系统提示和框架都强制用 `memory_save`：它会原子地同时追加 `MEMORY.md` 和日流水账，避免模型随机命名、跳过流水账、写坏格式。

6. **`disableMemoryHooks` 和 `disableMemoryTools` 的差别？**
   Hooks 关自动抽取+后台维护；Tools 关 4 个记忆工具和对应引导。**两个一起关**才额外跳过 `MEMORY.md` 注入；`flushTrigger(NEVER)` 只关 per-call flush，后台 consolidation 照跑。

7. **记忆、压缩、Session Log 三者关系？**
   记忆跨会话记住事实（会被合并去重）；压缩让当前对话继续（早期变摘要）；Session Log 是原始完整记录（永不压缩，供审计和 `session_search`）。谁也不替代谁，压缩不删日志。

8. **`memory_search` 和 `session_search` 的区别？**
   `memory_search` 搜整理过的记忆文件（MEMORY.md + memory/*.md，按行匹配、无中文分词、无条数上限）；`session_search` 搜原始会话日志（*.log.jsonl）。一个是"记得的事实"，一个是"当时说的话"。

9. **`triggerMessages` 与 `triggerTokens`、`keepMessages` 与 `keepTokens` 是什么关系？**
   触发是 **OR 竞争**：任一条件先命中即压缩，不存在优先级冲突；`triggerMessages(0)` 关消息条件，token 条件默认总活跃（0=动态）。保留是 **互斥三态，由 `keepTokens` 决定模式**：`-1` 动态（解析成 token 预算）、`0` 消息模式（此时 `keepMessages` 才生效）、`>0` 静态 token 预算；默认 `-1` 时 `keepMessages` 不生效，且最终保留值会被 clamp 到 `预算/2`（防"摘要 + 尾部"超预算）。
