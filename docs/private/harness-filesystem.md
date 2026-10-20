# Harness 文件系统（重构笔记）

> 原文：<https://java.agentscope.io/v2/zh/docs/harness/filesystem>
> 重构目标：快速建立设计思想 + 架构分层 + API 速查，应对面试。
> 前置关系：本页是《工作区（Workspace）》的姊妹篇——**工作区定义"有哪些目录和文件"，本页定义"这些目录落在哪种物理存储上"**。两者同读，先把"目录布局"记成工作区文档，再到这里看"三种部署形态怎么选"。
>
> 相关文档一句话关系：上下文（Context）管 `AgentState` 的 `(userId, sessionId)` 寻址；沙箱文档管容器生命周期与快照恢复链路；本页管**文件与命令落在哪**。

---

## 1. 它解决什么问题

Agent 离不开读写文件和跑命令。最省事的实现是把文件系统写死成"本机磁盘 + 宿主 shell"——但这三种部署场景立刻卡住：

| 场景 | 写死本机磁盘的问题 |
|---|---|
| 多副本部署：三个 pod 服务同一批用户 | 每个 pod 各有一份记忆/日志，用户换个 pod 就"失忆" |
| 执行不可信代码（agent 下载并运行脚本） | 命令直接在宿主上跑，一条 `rm -rf` 就出事 |
| 本地开发：让 agent 改项目代码 | 没问题，但文件全落在 `.agentscope/workspace/` 而不是项目目录 |

**Harness 的答案一句话：把所有文件访问抽象成统一接口 `AbstractFilesystem`，用一行 builder 配置切换三种部署模式，agent 代码零改动。**

所有文件工具（`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files`）和可选的 `execute`（shell）都从这个抽象走。切模式 = 换一行 `filesystem(...)`，不换 agent 代码。

---

## 2. 核心心智模型（面试必背）

### 2.1 三个关键认知

1. **agent 代码永远只面对"一个文件系统"**。每次文件操作都携带当前 `RuntimeContext`，由后端决定实际读写位置——agent 感知不到本机 / KV / 容器之间的差异。
2. **shell 三模式不同**：本机 ✅（宿主 `sh -c`）、共享存储 ❌（**故意不给**，见 3.1）、沙箱 ✅（容器内）。
3. **`filesystem(...)` 是声明式配置，不是运行时实现**。你在 Builder 上写的是 spec（规格），`build()` 时才把它转成真正的 `AbstractFilesystem` 实例。

### 2.2 新旧概念对照

| 官方术语 | 说人话 |
|---|---|
| `AbstractFilesystem` | 文件系统的统一接口，管"读/写/列表/搜索/执行" |
| `XxxFilesystemSpec` | 一份**部署规格**：我要本机 / 共享 KV / 沙箱，资源多少，隔离维度如何 |
| `OverlayFilesystem` | 双层叠加：上层读写、下层只读兜底（copy-on-write） |
| `CompositeFilesystem` | 按路径路由：命中路由的走 KV，其余的走本地 |
| `IsolationScope` | 谁的记忆和谁共享（分桶维度） |
| `SandboxSnapshotSpec` | 沙箱状态的存档策略（tar 包存哪） |
| `workspaceProjection` | 启动沙箱时把宿主静态资产注入容器的工作区 |

### 2.3 怎么选模式（决策树）

```
要执行不可信代码 / 生产隔离？ ──是──▶ 模式 2 沙箱（Docker / K8s / E2B / Daytona / AgentRun）
否 ↓
要多副本共享同一份记忆？ ──是──▶ 模式 1 共享存储（RemoteFilesystemSpec，无 shell）
否 ↓
本地开发 / 信任环境 / 单进程 ──▶ 模式 3 本机 + shell（LocalFilesystemSpec，默认）
```

> 注意：模式 1 和模式 3 可以组合——比如"工作文件在沙箱、日志在共享存储"（见会话文档的日志后端配置）。`filesystem(...)` 决定文件系统，`sessionLogStore(...)` 单独决定日志落点。

---

## 3. 模式 1：共享存储（`RemoteFilesystemSpec`）

### 3.1 设计思想：为什么"共享记忆"和"无 shell"是绑定的

多副本场景的核心诉求是：**alice 的长期记忆（`MEMORY.md`）、对话日志、子任务状态，任意 pod 都能读到最新版**。做法是把这些路径路由到一个共享 KV 存储（Redis / JDBC）。

**为什么故意不给 shell？** 因为有了共享存储再开 shell，等于"每个 pod 都能在宿主上跑命令"，多副本 + 宿主命令 = 事故放大器。要 shell 就用模式 2（沙箱）或模式 3（本机），意图是逼你明确"命令在哪跑"。

### 3.2 内置路由规则（面试默写表）

框架自动把以下工作区路径路由到共享 KV，**每个路径段一个独立命名空间，互不污染**：

| 路径 | KV 命名空间段 |
|---|---|
| `AGENTS.md`、`MEMORY.md`、`tools.json` | `root` |
| `memory/` | `memory` |
| `skills/` | `skills` |
| `subagents/` | `subagents` |
| `knowledge/` | `knowledge` |
| `plans/` | `plans` |
| `agents/<agentId>/sessions/` | `sessions` |
| `agents/<agentId>/tasks/` | `tasks` |

不在上表的路径落到本地 `LocalFilesystem`（无 shell）。

> **勘误**：官方页面 v2 的路由表漏了 `plans/` → `plans` 这一行，源码（`RemoteFilesystemSpec.toFilesystem`）实际存在。面试若被问到"哪些路径会路由"，把 `plans/` 也算上。

每个路由的物理形态是 `OverlayFilesystem`：**上层** = `RemoteFilesystem`（读写，存 KV），**下层** = 只读本地模板目录。所以"本地 git 同步的模板是基线，KV 里的覆盖是事实"——第一次启动时本地模板立刻可用，管理台改了 KV 后所有副本下次读取都是新版。

### 3.3 最小可运行示例

```java
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import redis.clients.jedis.JedisPooled;

var store = RedisDistributedStore.fromJedis(new JedisPooled("redis://shared-redis:6379"));

HarnessAgent agent = HarnessAgent.builder()
    .name("store-agent")
    .model(model)
    .workspace(Paths.get("/opt/agent/workspace"))
    .distributedStore(store)      // ① 一键配置 stateStore + baseStore
    .filesystem(new RemoteFilesystemSpec() // ② 无参构造，store 由 ① 自动注入
        .isolationScope(IsolationScope.USER)
        .anonymousUserId("anonymous"))
    .build();
```

- `distributedStore(store)` 同时注入 `AgentStateStore` 和 `BaseStore`；`RemoteFilesystemSpec()` 无参构造的 store 在 `build()` 时被自动注入。
- 也可以不配 `distributedStore`，直接 `new RemoteFilesystemSpec(store)` 传 `BaseStore`。
- **没配 store 会怎样**：`build()` 直接抛 `IllegalStateException`，不会静默降级成本机。

### 3.4 配置项与边界

| 方法 | 说明 | 默认值 |
|---|---|---|
| `isolationScope(IsolationScope)` | 命名空间隔离维度（见第 6 节） | `USER` |
| `anonymousUserId(String)` | `userId` 为空时的兜底标识 | `"_default"` |
| `addSharedPrefix(String)` | 额外路由到 KV 的工作区前缀（如 `"prompts/"` / `"configs/"`） | 无 |
| `workspaceIndex(WorkspaceIndex)` | SQLite 索引，加速远端 ls/glob/grep | 不加索引，全量扫描 |
| `sharedLocalWorkspace(boolean)` | 默认后端不按用户分命名空间，直接服务工作区根 | `false` |

**边界（易踩坑）**：

- **`workspaceIndex` 是 best-effort**：可能滞后远端变更；grep 时仍要从远端取候选文件内容，只是候选列表更快。
- **`sharedLocalWorkspace(true)` 只适合单租户宿主集成**（宿主用 `java.nio` 直接往工作区根写 `uploads/` 之类，agent 要能读到）。它关掉了工作区根的按用户隔离——多租户场景用户会互相读到、覆盖对方的文件。源码注释明确标了 "Not safe for multi-tenant workspaces"。
- 未路由路径（如自定义的 `data/`）落本机——**本机不共享**，多副本注意这点。
- 模式 1 的路由目录里，`skills/`、`subagents/`、`knowledge/` 这类"模板"的本地版本依然可见（overlay 下层），可别误以为"路由了就得全走 KV"。

### 3.5 `BaseStore` 可用实现

| 实现 | 说明 | 依赖模块 |
|---|---|---|
| `RedisStore` | 基于 Jedis，低延迟高并发 | `agentscope-extensions-redis` |
| `JdbcStore` | 基于 JDBC，适合 MySQL / PostgreSQL / H2 / SQLite | `agentscope-extensions-jdbc` |
| `InMemoryStore` | 内存实现，**只适合测试** | `agentscope-harness` |

### 3.6 示例场景：多副本客服 agent

三个 pod 各跑一个 `HarnessAgent`，共用一个 Redis：

- 每个 pod 本地磁盘的 `AGENTS.md` / `knowledge/` / `skills/` 是**只读模板**（git 同步）；
- 运行时产物（`MEMORY.md`、`memory/`、对话日志）自动进 Redis，任意 pod 读到最新；
- alice 的记忆落在 KV 键 `agents/customer-service/users/alice/memory/...` 下。

---

## 4. 模式 2：沙箱（`SandboxFilesystemSpec` 系列）

### 4.1 设计思想：沙箱要解决两个问题，快照和投影各管一个

沙箱 = 文件操作和 shell 命令都在隔离容器里执行。但容器是**瞬态**的，直接引出两个问题：

1. **容器停了，工作区就没了** → 需要**快照（snapshot）**：把容器工作区打成 tar 存档，下次 `call()` 恢复。
2. **沙箱启动时是空的，而宿主有静态资产**（`AGENTS.md`、`skills/`、`knowledge/`）→ 需要**工作区投影（projection）**：启动时把这些资产注入容器。

想清楚这两点，后面所有配置就都是"给谁存档、注入哪些"的细节。

### 4.2 Docker 沙箱（最常用）

```java
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;

HarnessAgent agent = HarnessAgent.builder()
    .name("sandbox-agent")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")                    // 必填
        .isolationScope(IsolationScope.SESSION)   // 每个会话独立沙箱
        .memorySizeBytes(512 * 1024 * 1024L)      // 512 MB 内存限制
        .cpuCount(2L)
        .network("host")
        .exposedPorts(8080, 3000)
        .environment(Map.of("NODE_ENV", "development"))
        .snapshotSpec(new LocalSnapshotSpec("/data/snapshots")))  // 快照存宿主磁盘
    .build();
```

配置项（继承 `SandboxFilesystemSpec` 的公共项见 4.7）：

| 方法 | 说明 | 默认值 |
|---|---|---|
| `image(String)` | Docker 镜像 | 必填 |
| `workspaceRoot(String)` | 容器内工作区挂载点 | `/workspace` |
| `memorySizeBytes(Long)` / `cpuCount(Long)` | 容器资源限制 | Docker 默认 |
| `network(String)` | Docker network | Docker 默认 |
| `exposedPorts(int...)` | 暴露端口 | 无 |
| `environment(Map)` | 容器环境变量 | 无 |
| `additionalRunArgs(String...)` | 额外 `docker run` 参数 | 无 |
| `workspaceSpec(WorkspaceSpec)` | 工作区挂载规则 | 默认 |
| `snapshotSpec(SandboxSnapshotSpec)` | 快照策略 | `NoopSnapshotSpec`（不快照） |

### 4.3 快照策略：默认是不存档

| 实现 | 说明 |
|---|---|
| `NoopSnapshotSpec` | **默认**。不快照——每次会话开始从完整 manifest 初始化全新工作区 |
| `LocalSnapshotSpec(Path)` | 快照存宿主本地磁盘，`{basePath}/{snapshotId}.tar` |
| `RedisSnapshotSpec` | 快照存 Redis（继承 `RemoteSnapshotSpec`） |
| `OssSnapshotSpec` | 快照存阿里云 OSS |
| `RemoteSnapshotSpec` | 快照存 `BaseStore` |

> **关键认知**：`NoopSnapshotSpec` 是默认——**不开快照，跨调用就"失忆"**（npm install 装好的依赖、生成的文件全丢）。要跨调用保留环境状态，必须显式配快照。

### 4.4 工作区投影（Workspace Projection）

沙箱启动时，框架把宿主工作区的**静态资产**打成 tar 注入容器的 `/workspace`：

- 默认注入：`AGENTS.md`、`skills/`、`subagents/`、`knowledge/`、`.skills-cache/`
- 按内容 **SHA-256 增量比对**：没变的文件跳过 hydrate
- `workspaceProjectionRoots(List)` 自定义包含哪些路径；`workspaceProjectionEnabled(false)` 完全关闭

### 4.5 示例：编程助手（Docker + 本地快照）

```java
HarnessAgent codingAgent = HarnessAgent.builder()
    .name("coder")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))
    .filesystem(new DockerFilesystemSpec()
        .image("node:20-slim")
        .isolationScope(IsolationScope.USER)     // alice 的所有会话共享同一快照
        .memorySizeBytes(1024 * 1024 * 1024L)
        .snapshotSpec(new LocalSnapshotSpec("/data/sandbox-snapshots")))
    .distributedStore(store)                     // 快照之外的运行时状态也进 KV
    .build();

RuntimeContext rc = RuntimeContext.builder()
    .userId("alice").sessionId("dev-session-1").build();

agent.call(Msg.user("npm install && npm test"), rc).block(); // 第一次：装依赖，结束时存快照
agent.call(Msg.user("npm run build"), rc).block();           // 第二次：恢复快照，node_modules 还在
```

**边界**：
- `IsolationScope.USER` 下 alice 的多个会话**共享**同一份快照槽位；换 `SESSION` 则每个会话独立。
- 快照恢复的是**工作区文件状态**，不恢复正在运行的进程/网络连接。

### 4.6 其他沙箱后端

| 后端 | 类 | 要点 |
|---|---|---|
| **Kubernetes** | `KubernetesFilesystemSpec` | 基于开源 agent-sandbox 控制器：镜像/资源/PVC 声明在集群侧 `SandboxTemplate` / `SandboxWarmPool`，Java 侧通过 `SandboxClaim` 从预热池领实例。`namespace` 默认 `default`、`warmPoolName` 必填、`workspaceRoot` 默认 `/workspace` 且**必须落在模板声明的 PVC 挂载点上**、`fileApiBaseDir` 默认 `/workspace`（置空退回 base64-over-exec 传输）、`serverPort` 默认 `8888`。`apiUrl` / `gateway*` 都不配时默认用 `kubectl port-forward` 本地隧道（开发环境） |
| **E2B** | `E2bFilesystemSpec` | `.apiKey("${E2B_API_KEY}")`、`templateId`、`sandboxTimeoutSeconds`，另有一组 `runUser` / `persistenceMode` / `codec` / 超时与重试项 |
| **Daytona** | `DaytonaFilesystemSpec` | `.apiKey("${DAYTONA_API_KEY}")`、`controlPlaneBaseUrl`、`image`、`cpu(2)`、`memory(4)`（GiB）、`disk(10)`（GiB） |
| **AgentRun（阿里云）** | `AgentRunFilesystemSpec` | `.apiKey("${AGENTRUN_API_KEY}")`、`accountId`、`region("cn-hangzhou")`、`templateName`、`sandboxIdleTimeoutSeconds(600)`，可挂 NAS / OSS 卷（`nasConfig` / `addOssMount`） |

### 4.7 所有沙箱后端的公共配置（继承 `SandboxFilesystemSpec`）

| 方法 | 说明 | 默认值 |
|---|---|---|
| `isolationScope(IsolationScope)` | 隔离维度 | `USER` |
| `snapshotSpec(SandboxSnapshotSpec)` | 快照策略 | `NoopSnapshotSpec` |
| `executionGuard(SandboxExecutionGuard)` | AGENT / GLOBAL scope 下的并发串行化守卫（见 6.3） | 无 |
| `workspaceProjectionEnabled(boolean)` | 是否投影宿主静态资产到沙箱 | `true` |
| `workspaceProjectionRoots(List)` | 投影的根路径列表 | `AGENTS.md`, `skills`, `subagents`, `knowledge`, `.skills-cache` |

---

## 5. 模式 3：本机 + shell（`LocalFilesystemSpec`，默认）

### 5.1 设计思想：Overlay 双层，读写与只读分离

什么都不写就是本机模式：工作区落到 `${cwd}/.agentscope/workspace/`，shell 在宿主上跑（`sh -c`）。它实际产出的是一个 `OverlayFilesystem`：

```
上层（读写）  LocalFilesystemWithShell   根 = workspace   提供 shell
下层（只读）  LocalFilesystem            根 = project     模板兜底
```

- **读**：先看 workspace，没有再退到 project（copy-on-write 语义）；
- **写**：默认全部落 workspace；
- **shell 的 `pwd` 是 project 目录**——agent 执行 `ls` 看到的是项目文件，符合"在项目里干活"的直觉。

> 一个容易误解的点：project 目录既是"读兜底"，又是"shell 工作目录"，两者是同一份配置 `project(Path)`，默认 `${user.dir}`。

### 5.2 路径解析策略（`LocalFsMode`）

agent 给绝对路径时怎么处理，三档：

| 模式 | 行为 |
|---|---|
| `ROOTED`（**默认**） | 绝对路径只在 `project` + `workspace` + `additionalRoots` 白名单内接受；`..` 穿越被拒绝。相对路径照常解析。Claude Code 风格 |
| `SANDBOXED` | 所有路径强制锚定到文件系统根；绝对路径和 `..` 全部拒绝 |
| `UNRESTRICTED` | 绝对路径原样透传，不做限制。**仅测试或完全信任环境** |

> 老的 `virtualMode(boolean)` 已废弃：`true` = `SANDBOXED`，`false` = `UNRESTRICTED`——注意它**够不着 `ROOTED`**，新代码直接用 `mode(LocalFsMode.ROOTED)`。

### 5.3 项目可写模式（`projectWritable`）

默认所有写入都落 workspace——阅读/分析类场景够用，但**生成代码**（写个微服务）时你会发现文件全进了 `.agentscope/workspace/` 而不是项目目录。开 `projectWritable(true)` 后按路径自动路由：

| 路径类型 | 写入位置 | 例子 |
|---|---|---|
| 工作区元数据 | workspace | `MEMORY.md`、`memory/`、`agents/`、`skills/`、`knowledge/`、`plans/`、`subagents/`、`rules/`、`tools.json` |
| 其他所有文件 | 项目目录 | `src/main/java/App.java`、`pom.xml`、`README.md`、`docker-compose.yml` |

读取行为不变（workspace 优先、project 兜底）。

### 5.4 配置项与完整示例

| 方法 | 说明 | 默认值 |
|---|---|---|
| `executeTimeoutSeconds(int)` | 单条 shell 命令超时（秒） | `120` |
| `maxOutputBytes(int)` | 单条命令最大捕获输出字节 | `100_000` |
| `env(String, String)` | 追加 shell 环境变量 | 无 |
| `inheritEnv(boolean)` | 是否继承父进程环境 | `false` |
| `mode(LocalFsMode)` | 路径解析策略 | `ROOTED` |
| `project(Path)` | 项目根（overlay 下层 + shell cwd） | `user.dir` |
| `addRoot(Path)` / `additionalRoots(Collection)` | 额外允许访问的宿主目录 | 无 |
| `projectWritable(boolean)` | 非元数据写入路由到项目目录 | `false` |
| `isolationScope(IsolationScope)` | 本地文件的用户/会话命名空间 | `USER` |
| `sharedLocalWorkspace(boolean)` | 非路由路径不按用户加前缀 | `false` |

```java
HarnessAgent devHelper = HarnessAgent.builder()
    .name("dev-helper")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))
    .filesystem(new LocalFilesystemSpec()
        .project(Paths.get("/Users/alice/my-project"))  // shell cwd + 读兜底
        .addRoot(Paths.get("/Users/alice/.config"))     // 额外白名单目录
        .mode(LocalFsMode.ROOTED)
        .inheritEnv(true)
        .executeTimeoutSeconds(300)
        .projectWritable(true))                          // 代码文件写到项目目录
    .build();
```

**边界**：`inheritEnv(false)`（默认）时，shell 命令**只看到 `env(...)` 加进去的变量**——工具链命令（如需要 PATH 里的 node）跑不起来是这里的常见坑，本地开发记得开 `inheritEnv(true)`。

---

## 6. IsolationScope：谁和谁共享同一份状态

### 6.1 四档（面试默写表）

模式 1（共享存储）和模式 2（沙箱）共用这一个概念，决定 `RuntimeContext` 里的谁跟谁共享状态：

| Scope | 含义 | 存储命名空间键 | 典型场景 |
|---|---|---|---|
| `SESSION` | 每个 sessionId 独立 | `agents/<agentId>/sessions/<sessionId>/...` | 每个对话完全隔离的一次性沙箱 |
| `USER`（**默认**） | 同一 userId 跨会话共享 | `agents/<agentId>/users/<userId>/...` | 同一用户多会话共享长期记忆 / 已装依赖 |
| `AGENT` | 该 agent 的所有用户/会话共享 | `agents/<agentId>/shared/...` | 公共知识库型 agent |
| `GLOBAL` | 全局共享一份 | `global/...` | **慎用**：所有 agent/用户抢同一槽位 |

### 6.2 降级规则（含一处勘误）

`IsolationScope` 的一般语义（沙箱/本地命名空间）：

- `USER` 下 `userId` 为空 → 降级按 `sessionId` 隔离；
- `SESSION` 下 `sessionId` 为空 → 跳过状态查找，创建全新环境；
- `AGENT` 的命名空间键由 agent name 决定（build 时固定），**永不因缺字段降级**。

> **勘误**：`RemoteFilesystemSpec`（模式 1）的 KV 命名空间**不是**照抄上面这套降级逻辑，实际是：
>
> | Scope | RemoteFilesystemSpec 实际命名空间 |
> |---|---|
> | `SESSION` 且无 sessionId | `agents/<agentId>/sessions/default` |
> | `USER` 且无 userId | `agents/<agentId>/users/_default`（`anonymousUserId` 可改） |
>
> 面试被问"userId 为空会怎样"，答 `anonymousUserId`（默认 `"_default"`）更准确；"降级为 SESSION"是沙箱侧语义。

### 6.3 沙箱并发行为（高频坑）

沙箱的共享是**顺序复用**，不是实时实例共享：

- 同一 scope key 的**并发**调用各自启动独立容器；
- 每次调用结束时，**最后写入的快照胜出**（last-write-wins）；
- 对 `AGENT` / `GLOBAL` 这种多用户共享 scope，要串行化就配 `executionGuard(SandboxExecutionGuard)`——同一隔离槽位的并发执行会被串起来，避免互相覆盖状态。

### 6.4 场景组合示例

```java
// 场景 1：每个用户独立的编程沙箱，跨会话保留已装依赖
.filesystem(new DockerFilesystemSpec()
    .image("python:3.12")
    .isolationScope(IsolationScope.USER)
    .snapshotSpec(new LocalSnapshotSpec("/snapshots")))

// 场景 2：每个对话独立的一次性沙箱（无快照，用完即焚）
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.SESSION))

// 场景 3：共享知识库客服 agent（所有用户共享同一份 memory/skills）
.distributedStore(store)
.filesystem(new RemoteFilesystemSpec()
    .isolationScope(IsolationScope.AGENT))
```

---

## 7. 多用户隔离：`userId` 是钥匙

`RuntimeContext.userId` 决定多用户怎么切，三种模式物理表现不同：

| 模式 | userId 的作用 | 物理表现 |
|---|---|---|
| 本机 | 用户级文件落在 `workspace/<userId>/...`（如 `workspace/alice/skills/code-reviewer/SKILL.md` 只对 alice 生效） | 路径前缀 |
| 共享存储 | KV 命名空间前缀 `agents/<agentId>/users/<userId>/...` | KV 键前缀 |
| 沙箱 | 快照 slot key（配 `IsolationScope.USER` 时） | 沙箱实例隔离 |

`userId` 不传时走单租户默认（`anonymousUserId`，`"_default"`），所有人共享一个根。

### 运行时数据 vs 静态资产（关键区分）

- **运行时数据**（对话日志、tasks、memory）跟着 `IsolationScope` / `userId` 走，**自动隔离**；
- **静态资产**（`AGENTS.md`、`tools.json`、`knowledge/`）对所有用户共享，**不**按 userId 自动分区——差异化只能靠「用户覆盖目录」：

```
workspace/
├── skills/code-reviewer/SKILL.md     ← 共用版（所有人可见）
└── alice/
    └── skills/code-reviewer/SKILL.md ← 只对 alice 生效，覆盖共用版
```

---

## 8. 技能与工具在各模式下的行为

### 技能（Skills）

`DynamicSkillMiddleware` 每轮推理前从技能仓库列表合并技能并渲染到 system prompt；技能文件加载走 `AbstractFilesystem`，三模式透明工作：

| 模式 | 技能加载方式 |
|---|---|
| 本机 | `workspace/skills/` 直读本地磁盘；`<userId>/skills/` 做用户覆盖 |
| 共享存储 | `skills/` 路由 KV：先查远端，没有再退本地模板。管理台改技能 → 所有副本下次推理生效 |
| 沙箱 | 宿主 `skills/` 通过 workspace projection 注入容器 `/workspace/skills/` |

四层优先级不变（低 → 高）：`projectGlobalSkillsDir` → `skillRepository` → `workspace/skills/` → `<userId>/skills/`。

### 文件工具（read_file / write_file / edit_file / …）

全部走 `AbstractFilesystem`，每次操作带 `RuntimeContext`，agent 感知不到模式差异：

| 模式 | 读写行为 |
|---|---|
| 本机 | Overlay：写落 workspace 上层；读先 workspace 后 project。`projectWritable(true)` 后非元数据写项目 |
| 共享存储 | Composite：命中路由的路径走 KV overlay（远端上层 + 本地模板下层），其余走本地 |
| 沙箱 | 所有文件操作转发到沙箱容器内 |

### Shell 执行（execute）

| 模式 | Shell？ | 执行位置 |
|---|---|---|
| 本机 | ✅ | 宿主 `sh -c`，cwd = `project` 目录 |
| 共享存储 | ❌ | 不提供 |
| 沙箱 | ✅ | 沙箱容器内 |

### tools.json / MCP 服务器

- `tools.json` 在 **`build()` 时一次性**从工作区读取（走 `WorkspaceManager`，支持两层读），注册 MCP server 和 allow/deny 过滤；
- **三种模式行为一致**——都在 build 时读取，不受运行时 filesystem 模式影响；
- 共享存储模式下 `tools.json` 也走"远端上层 + 本地模板下层"的 overlay，但**管理台修改后必须重新 build agent 才生效**（MCP server 注册是一次性的）。

---

## 9. 两层读取 + 完全自管（`abstractFilesystem`）

### 9.1 两层读取（回顾）

`AGENTS.md`、`MEMORY.md`、`KNOWLEDGE.md` 等关键文件读取有"两层兜底"：**先问配置的文件系统后端，没有再退回本地磁盘**。这对模式 1 的"模板文件"最有用——第一个副本启动时本地模板立刻可用，后续副本从共享存储读最新版。**写入永远走配置的后端**。

### 9.2 完全自管：`abstractFilesystem(...)`

```java
HarnessAgent.builder()
    .abstractFilesystem(myCustomFilesystem) // 与 filesystem(...) 互斥
    .build();
```

三种模式都不合适时，传一个自己实现的 `AbstractFilesystem`。**通常不需要**——三种模式覆盖了 95% 的场景。也可以在主文件系统之外用 `filesystemRoute(prefix, filesystem)` 额外挂一个后端到指定前缀（如 `"memory-stores/notes/"`）。

---

## 10. 面试速查

### 高频考点

1. **三种模式怎么选？**
   不可信代码 / 生产隔离 → 沙箱；多副本共享记忆 → 共享存储（无 shell）；本地开发/信任环境 → 本机 + shell（默认）。

2. **为什么模式 1（共享存储）故意不给 shell？**
   共享存储 + 宿主 shell = 每个 pod 都能在宿主跑命令。要 shell 就去沙箱或本机，逼你明确"命令在哪跑"。

3. **共享存储的路由规则？**（含勘误）
   `AGENTS.md`/`MEMORY.md`/`tools.json` → `root`；`memory/` → `memory`；`skills/` → `skills`；`subagents/` → `subagents`；`knowledge/` → `knowledge`；`plans/` → `plans`；`agents/<id>/sessions/` → `sessions`；`agents/<id>/tasks/` → `tasks`。每个路由独立命名空间段，其余落本地（无 shell）。

4. **本机模式的 Overlay 双层结构？**
   上层 `LocalFilesystemWithShell`（根 = workspace，读写 + shell）；下层 `LocalFilesystem`（根 = project，只读）。读先上层后下层（copy-on-write），写默认落 workspace，shell 的 pwd 是 project。

5. **`LocalFsMode` 三档？**
   `ROOTED`（默认，白名单内绝对路径可用）/ `SANDBOXED`（全部锚定根，拒绝逃逸）/ `UNRESTRICTED`（原样透传，仅测试）。

6. **`projectWritable(true)` 解决什么问题？**
   生成代码场景下把非元数据写入路由到项目目录，而不是全堆进 `.agentscope/workspace/`。元数据（`MEMORY.md`、`agents/`、`skills/` 等）仍写 workspace。

7. **IsolationScope 四档 + 默认？**
   SESSION / USER（默认）/ AGENT / GLOBAL。USER 缺 userId 在沙箱语义下降级按 sessionId；模式 1 实际落到 `anonymousUserId`（`"_default"`）；AGENT 永不降级。

8. **沙箱跨调用恢复靠什么？默认开吗？**
   靠快照（`snapshotSpec`）。**默认 `NoopSnapshotSpec` 不快照**——要跨调用保留环境（已装依赖、生成文件）必须显式配置。启动时静态资产靠工作区投影注入（默认开，SHA-256 增量）。

9. **同一 scope 的沙箱并发调用会怎样？**
   各自启动独立容器，不是共享实例；结束时最后写入的快照胜出。AGENT/GLOBAL 需要串行化时配 `executionGuard`。

10. **多用户隔离怎么做？静态资产会自动隔离吗？**
    运行时数据（日志/tasks/memory）自动跟 userId 走（路径前缀 / KV 命名空间 / 快照 slot）；静态资产（AGENTS.md、tools.json、knowledge/）对所有用户共享，靠 `<userId>/...` 覆盖目录差异化。

11. **tools.json 改了要重新 build 吗？**
    要。MCP server 注册是一次性的，`tools.json` 在 build 时读取；共享存储下改远端后必须重建 agent 才生效。

### 关键默认数字

| 项 | 默认值 |
|---|---|
| 默认模式 | 模式 3（本机 + shell） |
| `RemoteFilesystemSpec` 隔离维度 | `USER` |
| `anonymousUserId` | `"_default"` |
| 模式 1 是否提供 shell | 否 |
| shell 命令超时（模式 3） | 120 秒 |
| 单条命令输出上限（模式 3） | 100,000 字节 |
| 路径解析策略（模式 3） | `ROOTED` |
| `project` 目录（模式 3） | `${user.dir}` |
| `projectWritable` | `false` |
| 沙箱快照 | `NoopSnapshotSpec`（不快照） |
| 沙箱投影 | 开启；根 = `AGENTS.md`, `skills`, `subagents`, `knowledge`, `.skills-cache` |
| Docker `workspaceRoot` | `/workspace` |
| K8s `namespace` / `serverPort` / `fileApiBaseDir` | `default` / `8888` / `/workspace` |
| 沙箱并发语义 | 顺序复用，最后写入快照胜出 |
