# 工作区（Workspace）· 重构版

> 面向面试的精读版：先讲**设计思想**，再讲**架构**，最后给 **API 用法**。
> 原始文档：<https://java.agentscope.io/v2/zh/docs/harness/workspace>
> 定位：工作区是 `HarnessAgent` 的**智能体定义与进化的 source of truth**。

---

## 0. 一句话搞懂工作区

**工作区 = 一个目录 + 一堆普通 Markdown / JSON 文件，用来定义"智能体是什么"以及保存"它在运行中学到了什么"。**

它解决三个问题：

| 问题 | 工作区的答案 |
|---|---|
| 智能体的定义（人格、知识、技能、工具）散落在代码里，改配置要改代码、要重新部署 | 定义全部**声明在文件里**，与代码解耦 |
| 多用户/多租户时，一套 agent 逻辑要给不同用户不同人格，只能复制部署或写 if 分支 | 定义即数据 → **用户级覆盖目录**，无需代码分支 |
| 跨会话累积的记忆、技能、计划没有统一落点 | 框架**自动写回工作区**，下次调用自动加载 |

---

## 1. 设计思想：为什么要把智能体定义"做成文件"

### 1.1 核心主张：定义即数据

智能体的"是什么"（人格、行为约定、领域知识、技能、子 agent、工具白名单）**全部可选**地声明在工作区文件中：

| 要定义的内容 | 文件 |
|---|---|
| 人格、行为约定、系统指令 | `AGENTS.md` |
| 领域知识 | `knowledge/KNOWLEDGE.md` + 参考文件 |
| 技能（可复用能力包） | `skills/<skill-name>/SKILL.md` |
| 子 agent 声明 | `subagents/<agent-id>.md` |
| 工具白名单 + MCP server | `tools.json` |

**工作区与 API 完全等价**：每个文件都有对应的 builder 方法（`.sysPrompt(...)`、`.skill(...)`、`.subagent(...)`、`.toolsConfig(...)`）。用文件还是用 API 取决于场景，框架同等对待。

### 1.2 四条设计原则（面试直接背）

1. **智能体定义与进化的 source of truth** —— 定义在文件里声明，进化由框架自动写回，都不绑死在数据库表或代码里。
2. **内容按生命周期分三类，互不混淆**（见 2.2）。
3. **原生多租户隔离** —— 记忆、会话、任务、技能、沙箱状态由单一的 `IsolationScope` 分桶，应用层不用手写分区逻辑（见 2.3）。
4. **工作区与 filesystem 解耦** —— 同一份目录布局可落在本机磁盘 / 共享 KV 存储（Redis / JDBC）/ 沙箱容器，代码不动、部署形态切换（见 3.2）。

### 1.3 红利（为什么这套设计有价值）

- **定义当数据用**：按用户定制只是"多一个覆盖目录"，不是"多一条代码分支"。
- **多租户天然成立**：同一份二进制 + 公共定义，服务不同用户的不同人格/知识/技能。
- **部署形态可切换**：换存储后端不换 agent 代码。

---

## 2. 架构（一）：工作区的内部结构

### 2.1 目录布局（一图看懂）

```
.agentscope/workspace/
├── AGENTS.md          ← 静态：人格 + 行为约定（唯一建议必写的文件）
├── MEMORY.md          ← 长期记忆：策划后的长期事实
├── tools.json         ← 静态：MCP server + 工具白名单（可选）
├── memory/            ← 长期记忆：每天追加的事实流水账
│   └── YYYY-MM-DD.md
├── knowledge/         ← 静态：领域知识入口 + 任意参考文件
│   ├── KNOWLEDGE.md
│   └── ...
├── skills/            ← 静态：技能目录，每个子目录一份 SKILL.md
│   └── <skill-name>/SKILL.md
├── subagents/         ← 静态：子 agent 声明（文件名即 agent_id）
│   └── <agent-id>.md
├── plans/             ← 运行时：Plan Mode 写下的计划文件
│   └── PLAN.md
└── agents/<agentId>/  ← 运行时：每个 agent 自己的运行时根
    ├── tasks/
    └── <sessionId>.json
```

> **这棵树是"逻辑布局"，不是固定磁盘路径。** 它画成 `.agentscope/workspace/...` 只是默认本机落点；同一套相对路径在三种存储模式（本机/远端/沙箱）下完全一致，变的只是后端。

**唯一你真正需要写的是 `AGENTS.md`**（不写也能跑，只是少一段人格注入）。其他目录在启用对应能力时自动出现：

- 启用记忆压缩 `.compaction(...)` → `memory/` + `MEMORY.md`
- 放子 agent spec → `subagents/`
- 装技能 → `skills/`
- 启用 Plan Mode → `plans/`
- 任何 `call()` 跑过一次 → `agents/<agentId>/`

### 2.2 三类内容：静态资产 / 运行时文件 / 长期记忆

| 类型 | 谁写 | 谁读 | 例子 |
|---|---|---|---|
| **静态资产**（工程师编辑） | 你 / 团队 | 框架按指令/参考资料分层加载，或按需读取 | `AGENTS.md`、`knowledge/`、`skills/`、`subagents/`、`tools.json` |
| **运行时文件**（每次 call 写回） | 框架 / agent | 框架下次 call 时还原 | `agents/<agentId>/sessions/`、`agents/<agentId>/tasks/`、`plans/` |
| **长期记忆**（跨会话累积） | agent + 后台任务 | 框架每次 call 加载为参考上下文 + agent 用工具查询 | `MEMORY.md`、`memory/YYYY-MM-DD.md` |

> 混在一棵树里只是为部署方便（一个目录拷贝走就是完整 agent），框架内部走不同的读写路径。
> **区分文件产物和恢复状态**：计划、技能、记忆是 Workspace 文件；`AgentState` 是结构化运行状态（默认 EVENT_LOG 模式存进原生日志 checkpoint）。

### 2.3 隔离范围 IsolationScope：多租户怎么分桶

工作区数据（记忆、会话、任务、技能、沙箱状态）由 `IsolationScope` 决定**谁和谁共享一个桶**：

| `IsolationScope` | 谁共享一个桶 | 典型场景 |
|---|---|---|
| `SESSION` | 每个 `sessionId` 完全隔离 | 按会话隔离；一次性沙箱 |
| `USER`（**默认**） | 同一 `userId` 的所有会话 | 用户的多个会话共享长期记忆 / 技能（`userId` 缺失时降级为 `SESSION`） |
| `AGENT` | 该 agent 的所有用户与会话 | 共享知识库型 agent |
| `GLOBAL` | 整个 store 实例共用一个桶 | **慎用** —— 所有 agent/用户争抢同一槽位 |

落地方式随存储模式不同：本机 = 路径前缀；共享存储 = KV 命名空间；沙箱 = 状态 slot。

> 面试提示：问"Harness 怎么做多租户隔离"，答 `IsolationScope` 四档 + 默认 USER + 降级规则 + 落地方式。

---

## 3. 架构（二）：读取与部署

### 3.1 两层读架构（filesystem-first + 本地兜底）

对所有"被注入 prompt 的关键文件"（`AGENTS.md` / `MEMORY.md` / `knowledge/KNOWLEDGE.md` / `additionalContextFile`），读取都走**两层读**：

```
1. 问当前配置的 AbstractFilesystem：有没有这个 relative path？
   ├─ 有 → 返回该内容（"覆盖"层）
   └─ 没有 → 走第 2 步
2. 读本地磁盘 workspace.resolve(relativePath)   ← 模板兜底
```

**写入永远走第 1 层（filesystem 后端），从不直接落本地磁盘。**

这套设计在**共享存储模式**最有价值：第一个副本启动时，本地磁盘有团队 git 同步的 `AGENTS.md` 模板立刻可用；之后任何节点对 `AGENTS.md` 的覆盖（比如管理台编辑）写到共享 KV，所有副本下一次 `call()` 就读到最新版本。

> 一句话记忆：**模板是 fallback，远端覆盖是事实。**

### 3.2 工作区材料如何进入请求（分层规则）

工作区材料每次 Agent call 读取一次，由最终 Context 构建器**分层组织**，不直接把全部文件追加进 System：

| 材料 | 模型中的位置 | 预算行为 |
|---|---|---|
| `AGENTS.md` | System / `project_rules` | 不直接淘汰，计入最终预算 |
| 工作原则、环境信息 | System / `working_principles`、`environment` | 计入最终预算 |
| `MEMORY.md` | USER 参考消息 / `HARNESS_CONTEXT`，kind=memory | 准备时可截断，最终预算不足时可省略 |
| knowledge 入口及路径索引 | USER 参考消息 / `HARNESS_CONTEXT`，kind=knowledge | 可因最终预算省略 |
| `additionalContextFile` | USER 参考消息 / `HARNESS_CONTEXT`，kind=additional | **必需材料**，不能任意淘汰 |

关键点：

- `maxContextTokens` 默认 8000，**只用于工作区材料准备，不代表最终模型输入上限**。最终预算还包括 System、历史、状态和工具 Schema，超限则拒绝请求。
- `MEMORY.md` 在记忆工具和 Hooks 都关闭时不加载。
- 知识目录只加载**入口和索引**，其余文件由模型按需读取（见 4.3 `knowledge/`）。
- `AGENTS.md` 不需要 XML 标签。同一次 call 内文件变化不自动刷新，下一次 call 重新加载。

> 面试提示：问"AGENTS.md 和 knowledge 在 prompt 里有什么区别"，答"AGENTS.md 进 System 是硬指令不可淘汰；knowledge 进 USER 参考消息，预算不足可省略"。

### 3.3 三种 filesystem 模式（部署形态）

同一份工作区逻辑布局，物理上可落在三种地方：

| 模式 | 典型配置 | 加载方式 | 最佳实践 |
|---|---|---|---|
| **共享存储** | `.filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))` | 远端 KV 为上层、本机模板为下层的 overlay 读 | 团队约定（`AGENTS.md`/`knowledge/`/共享 `skills/`）用 git 同步到副本本地作模板；运行时产物（`MEMORY.md`、`agents/<id>/...`）让 KV 自己长 |
| **沙箱** | `.filesystem(new DockerFilesystemSpec().image("ubuntu:24.04"))` | 启动时把静态资产打包 tar hydrate 进沙箱 `/workspace`；按内容 hash 去重、SHA-256 增量更新 | 代码执行 / shell 命令都隔离在沙箱里，宿主只留"种子"。**生产部署不可信代码的首选** |
| **本机 + shell**（默认） | 不配 `.filesystem(...)` | 直接从 `<workspace>/` 读，无 overlay | 单进程 / 本机开发 / 单元测试 / 信任环境。生产不要用它跑不可信代码 —— `execute` 是宿主 `sh -c` |

### 3.4 多租户的两种机制（面试重点）

**定义按用户覆盖，进化按用户隔离** —— 两层多租户同时生效：

| 用户级层 | 定制什么 | 解析方式 |
|---|---|---|
| `<userId>/AGENTS.md` | 该用户的人格 / 行为 | 两层读的上层（共享 `AGENTS.md` 作兜底） |
| `<userId>/knowledge/` | 该用户可见的领域知识 | 用户级目录，共享 `knowledge/` 作基线 |
| `<userId>/skills/` | 只对该用户解锁的能力 | **覆盖**同名共享技能，独有的叠加可见 |
| `<userId>/subagents/` | 只有该用户能 spawn 的子 agent | 覆盖同名共享声明 |
| 运行时数据（记忆 / 会话 / 任务） | 该用户累积的进化数据 | 按 `userId` 进命名空间（路径前缀 / KV 命名空间 / 沙箱 slot） |

示例：

```
workspace/
├── skills/code-reviewer/SKILL.md     ← 共用版（所有人可见）
├── subagents/researcher.md           ← 共用版
└── alice/
    ├── skills/code-reviewer/SKILL.md ← 只对 alice 生效，覆盖共用版
    └── subagents/researcher.md       ← 只对 alice 生效
```

**重要区分**：`AGENTS.md`、`tools.json`、`knowledge/` 等**静态资产不会自动按 userId 切**，它们对所有用户共享，只能靠"用户覆盖目录"差异化；会跟着 `userId` 走的是**运行时数据**（sessions、tasks、memory）。

---

## 4. API 用法

### 4.1 Builder 配置与解析顺序

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace")) // 不传则走下面的默认解析顺序
    .additionalContextFile("SOUL.md")             // 任意 workspace 相对路径，全文注入
    .additionalContextFile("PREFERENCES.md")
    .maxContextTokens(8000)                       // MEMORY 注入预算
    .build();
```

不显式调 `workspace(...)` 时，`build()` 按以下优先级确定工作区目录：

| 优先级 | 来源 | 说明 |
|---|---|---|
| 1 | `workspace(Path)` / `workspace(String)` | Builder 显式设置，覆盖一切 |
| 2 | `agentscope.workspace` 系统属性 | `-Dagentscope.workspace=/data/workspace` |
| 3 | `AGENTSCOPE_WORKSPACE` 环境变量 | `export AGENTSCOPE_WORKSPACE=/data/workspace` |
| 4 | 默认值 | `${user.dir}/.agentscope/workspace` |

> 系统属性和环境变量主要服务**镜像打包 / 容器部署**：代码不写死路径，构建镜像或启动容器时注入。空白值视为未设置，继续回退到下一级。

`AGENTS.md` 至少写一份骨架：

```markdown
# MyAgent
你是 XX 助手，遵循以下行为约定。
## 行为
- ...
- ...
```

### 4.2 常用开关（关掉哪些子系统）

| 方法 | 关掉的是 |
|---|---|
| `disableWorkspaceContext()` | 工作区指令和参考材料加载（`AGENTS.md` / `MEMORY.md` / `knowledge/`） |
| `disableMemoryHooks()` | 记忆 flush + 后台维护 |
| `disableMemoryTools()` | `memory_search` / `memory_get` / `memory_save` / `session_search` 工具 |
| `disableSubagents()` | 整个子 agent 子系统 |
| `disableDynamicSkills()` | 每轮重新合并技能；改成 build 时一次 |
| `disableToolsConfig()` | 不读 `tools.json` |
| `disableSessionPersistence()` | 兼容 no-op；不能用它关闭原生 Session Log |

> 面试提示：能说出"哪些子系统可以按需关掉"说明真的用过，而不是背过文档。

### 4.3 重点目录与对应 API

#### `skills/` —— 技能是"写好的能力包"

```
skills/code-reviewer/
├── SKILL.md                 ← YAML frontmatter (name + description) + 指令
├── references/style-guide.md ← 可选，agent 按需 read_file
└── scripts/run-checks.sh    ← 可选，agent 通过 execute 调
```

注册路径四层（低 → 高优先级）：

1. `projectGlobalSkillsDir(Path)` —— 项目全局（`~/.agentscope/skills/`）
2. `skillRepository(...)` —— 市场后端（Git / Nacos / MySQL / classpath）
3. `workspace/skills/` —— 工作区共用
4. `<userId>/skills/` —— 用户隔离（**覆盖以上所有**）

下层独有的 skill 都保留，重名时上层覆盖。每轮推理前 `DynamicSkillMiddleware` 重新合成，在 system prompt 里渲染成 `<available_skills>` 块（只列 name + description），agent 觉得相关才 `load_skill_through_path` 拉详情。

#### `subagents/` —— 子 agent 声明（文件名即 agent_id）

```yaml
---
description: 代码评审专家。当用户需要 review PR、找代码风格问题时使用。
workspace:
  mode: isolated          # isolated（默认） | shared
model: qwen3-max          # 可选；默认继承父
tools: [read_file, grep_files]  # 可选；继承工具的白名单
---
你是一个专注代码评审的子 agent…
```

加载：`AgentSpecLoader` 在构建期**非递归**扫 `workspace/subagents/*.md`，再合并 `.subagent(...)` 编程注册的声明。主 agent 通过 `agent_spawn agent_id="reviewer" task="..."` 调用。

#### `tools.json` —— 工具白名单 + MCP server（含陷阱）

```json
{
  // 白名单：非空时只允许列出的工具
  "allow": ["read_file", "grep_files", "execute"],
  // 黑名单：列出的工具一律不暴露（优先级高于 allow）
  "deny": ["write_file"],
  // MCP server，键是名字，值是连接配置
  "mcpServers": {
    "amap": { "transport": "streamableHttp", "url": "https://mcp.amap.com/mcp?key=${AMAP_API_KEY}" },
    "local-py": { "transport": "stdio", "command": "python", "args": ["mcp_servers/my_server.py"] }
  }
}
```

行为细节（**面试容易踩的坑**）：

- MCP server 在**构建期一次性注册**到 toolkit。
- **`allow` / `deny` 在所有工具注册完之后才应用** → 也会过滤掉 Harness 内置工具（`read_file` / `memory_search` / `agent_spawn` 等）。**用 `allow` 列白名单时务必把要保留的内置工具一并列出**，否则会一起被砍掉。
- `${ENV_VAR}` 做环境变量替换；未设置时给 warning 并替换成空字符串。
- 不想用文件？直接 `builder.toolsConfig(ToolsConfig.builder()...)` 编程注入；要完全关掉读取用 `disableToolsConfig()`。
- 共享存储模式下，`tools.json` 走"远端为上层、本机模板为下层"的 overlay。

#### `plans/` —— Plan Mode 的计划文件

默认 `plans/PLAN.md`，可用 `.planFileDirectory("design-docs")` 改路径。`PlanModeContext`（阶段和计划路径）属于 AgentState，默认随原生日志 checkpoint 恢复。计划文件让"想清楚"与"做出来"解耦。

#### `agents/<agentId>/` —— 运行时根（框架自己写，你别动）

```
agents/<agentId>/
└── tasks/
    └── <sessionId>.json ← 子 agent 后台任务记录（taskId → TaskRecord）
```

原生 Session Log 位于 Filesystem 保留分区：本地 `.agentscope-runtime/`，远端 `__agentscope_session_log_v1__`。通过 `sessionTranscript()`、`session_history`、`session_list` 读取历史。

> 面试提示：跨节点恢复时，原生日志、任务/文件产物、sandbox 元数据都要用合适的共享后端；**仅配置共享 AgentStateStore 不会自动共享本地 Session Log**。

#### `knowledge/` —— 知识库"只放索引，不放细节"

```
knowledge/
├── KNOWLEDGE.md        ← 入口/概览，全文进 <domain_knowledge_context>
├── api-reference.md    ← 同目录其它文件（任意深度）只列路径清单
├── domain-terms.md
└── ...
```

`KNOWLEDGE.md` 全文进 prompt；其它文件只列**路径清单**，agent 用 `read_file` / `grep_files` / `glob_files` 按需读取。避免把整个知识库塞进 token 预算。

---

## 5. 智能体如何进化（五条自动累积通道）

工作区不只是静态定义，还是智能体**累积经验**的落点。打开对应能力，数据自动堆积并按租户隔离：

| 通道 | 落在哪 | 怎么开 | 怎么累积 |
|---|---|---|---|
| **长期记忆** | `MEMORY.md` + `memory/YYYY-MM-DD.md` | `.compaction(...)` | 压缩前 `MemoryFlushMiddleware` 从对话前缀抽取事实；后台节流任务合并去重写回 `MEMORY.md`，下次 call 重新加载 |
| **自学习技能** | `skills/`、`skills/_drafts/`、`skills/.archive/` | `.enableSkillManageTool(...)` | agent 调 `propose_skill` 起草 → 可选审批闸门 → 后台 curator 把长期未用的标 stale（30 天）并归档（90 天） |
| **计划文件** | `plans/PLAN.md` | `.enablePlanMode()` | 只读规划阶段用 `plan_write` 写计划；跨调用保留并驱动执行阶段 |
| **工具结果落盘** | 工作区 eviction 目录 | `.toolResultEviction(...)` | 单结果超阈值（默认 80K 字符）→ 完整输出写盘，上下文替换为 head/tail 预览 + `read_file` 指针 |
| **会话日志** | Filesystem 保留分区 / 自定义 SessionLogStore | 默认 EVENT_LOG | 保存执行事实；工具可读原生消息历史 |

记忆、技能、计划和卸载结果沿用 Workspace 的租户隔离与 Filesystem 路由。原生日志另需原子存储能力；兼容 JSONL 不能替代完整执行记录。

---

## 6. 写入工作区的安全规则

- `additionalContextFile`、`writeUtf8WorkspaceRelative`、`memory_get` 接受**工作区相对路径**；框架做 path-traversal 校验（拒绝 `../../etc/passwd` 逃出工作区）。
- **写文件必须通过 `HarnessAgent#getWorkspaceManager()`，而不是 `java.nio.Files`** —— 后者在沙箱或共享存储模式下会写错地方（写到宿主磁盘而不是沙箱/KV 里）。
- 唯一例外：builder 装配时的初始化脚本（如 `initWorkspaceIfAbsent` 生成 `AGENTS.md` 种子），那时还没有运行时上下文，用 `java.nio.Files` 是 OK 的（本来就要落本地模板）。

---

## 7. 面试速记 Q&A

**Q1：工作区是什么？**
工作区是 `HarnessAgent` 智能体定义与进化的 source of truth——一个目录 + 普通 Markdown/JSON 文件，定义"智能体是什么"（AGENTS.md、knowledge、skills、subagents、tools.json），并保存"运行中学到什么"（记忆、自学习技能、计划、工具结果落盘、会话日志），由框架自动读写。

**Q2：为什么不用代码/数据库定义智能体？**
因为"定义即数据"带来三个红利：按用户定制只需加覆盖目录而非代码分支（多租户）；定义与代码解耦，改配置不重新部署；同一定义可切换三种存储后端。

**Q3：多租户隔离怎么做？**
两层机制：`IsolationScope` 分桶（SESSION/USER 默认/AGENT/GLOBAL）管运行时数据；用户级覆盖目录（`<userId>/skills/` 等）管静态定义。定义按用户覆盖、进化按用户隔离。

**Q4：两层读架构是什么？**
读关键文件先问配置的 AbstractFilesystem，没有才落本地磁盘模板；写入永远走 filesystem 层。模板是 fallback，远端覆盖是事实——共享存储模式下一个副本更新，所有副本下次 call 生效。

**Q5：三种 filesystem 模式怎么选？**
本机 + shell（默认）：开发/测试/信任环境；共享存储（RemoteFilesystemSpec）：多副本、团队协作、运行时产物放 KV；沙箱（DockerFilesystemSpec）：执行不可信代码的生产首选。

**Q6：tools.json 的坑？**
`allow`/`deny` 在**所有工具注册完之后**才应用，会连带过滤 Harness 内置工具——列白名单时务必把要保留的内置工具一起列出，否则被砍掉。

**Q7：写文件为什么不能用 java.nio.Files？**
在沙箱/共享存储模式下会写到宿主磁盘或错误后端；必须走 `HarnessAgent#getWorkspaceManager()`。
