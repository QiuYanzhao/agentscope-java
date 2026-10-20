# Harness 技能（Skill）（重构笔记）

> 原文：<https://java.agentscope.io/v2/zh/docs/harness/skill>
> 重构目标：快速建立设计思想 + 四层合成架构 + API 速查，应对面试。
> 本笔记已对照源码（agentscope-harness / agentscope-extensions）核对 API 签名；官方文档中不能编译的示例均以 ⚠️ 勘误 标注，并给出可编译写法。

---

## 1. 它解决什么问题

给 Agent 加一个新能力，最笨的办法是改代码、注册一个新工具、重新部署。技能（Skill）把这件事变成"写一个目录"：

**一个 skill 就是一份写好的能力包**：

```
code-reviewer/
├── SKILL.md                   # 必需：YAML frontmatter（name + description）+ 给 agent 看的指令
├── references/style-guide.md  # 可选：长篇参考资料，agent 按需读取
└── scripts/run-checks.sh      # 可选：agent 通过 shell 调用的脚本
```

它解决三个问题：

| 问题 | 答案 |
|---|---|
| 能力怎么复用、怎么分发 | 把能力做成目录，通过**技能市场**（Git / Nacos / MySQL / classpath）或**工作区**发布，agent 需要时自己用 |
| 多用户怎么差异化 | 工作区 `workspace/<userId>/skills/` 用户隔离目录**覆盖**共用版 |
| 能力由谁维护 | 可选**自学习闭环**：agent 自己起草 → 审核闸门 → 后台周期性整理 |

一句话：**把"给 agent 的能力"从代码资产变成数据资产**，和工作区"定义即数据"是同一套思想——技能是工作区里的一种静态资产。

---

## 2. 核心心智模型（面试必背）

### 2.1 两个安装渠道 + 一个可选闭环

- **技能市场** —— 外部仓库（Git / Nacos / MySQL / classpath），用 `skillRepository(...)` 注册；
- **工作区** —— `workspace/skills/` 下大家共用、`<userId>/skills/` 按用户隔离，**目录放好就生效，不用注册**；
- **自学习闭环** —— agent 自己写 skill（各阶段独立开关，默认全关）。

两类来源**同时生效**，不是二选一。

### 2.2 四层合成：同名冲突谁说了算

四个来源都可能给出同名 skill，优先级从低到高：

| 优先级 | 来源 | 怎么配 |
|---|---|---|
| 1（最低） | 项目全局目录 | `projectGlobalSkillsDir(Path)`，如 `~/.agentscope/skills/` |
| 2 | 技能市场 | `skillRepository(...)`，**后注册的覆盖先注册的** |
| 3 | 工作区共用 | `workspace/skills/` |
| 4（最高） | 用户隔离 | `<userId>/skills/` |

规则：**下层独有的 skill 仍然保留，只在重名时被上层覆盖**。例：团队 Git 上有通用 `code-reviewer`，项目 `workspace/skills/code-reviewer/` 写了项目专属版 → agent 看到项目版；Alice 又在自己目录覆盖一份 → Alice 拿到她自己的版本，其他用户还是项目版。

### 2.3 两段式加载（省 token 的关键设计）

1. **元数据先行**：每轮推理前，框架把当前可见 skill 的**最少元数据**（name + description + skill-id + files-root）渲染成 `<available_skills>` 块放进 system prompt；
2. **详情按需**：agent 判断哪个相关，才调 `load_skill_through_path(skillId, path)` 拉 SKILL.md 详情；脚本等资源再按需读。

```xml
<available_skills>
  <skill>
    <name>code-reviewer</name>
    <description>当用户需要代码评审、风格反馈或 PR 审核时使用。</description>
    <skill-id>code-reviewer_workspace-namespaced</skill-id>
    <files-root>/workspace/skills/code-reviewer</files-root>
  </skill>
  ...
</available_skills>
```

### 2.4 读与执行分离（面试亮点）

- **读** skill（`load_skill_through_path` 取 `SKILL.md` / `references/`）走**内存或宿主文件系统，不需要沙箱**；
- **执行** skill 脚本（agent 用 `execute` 跑 `scripts/` 里的脚本）才需要文件真的进到容器 / 文件系统，靠 `<files-root>` 拼绝对路径。

---

## 3. 设计思想

1. **能力声明化**：技能是"说明书 + 脚本集"，不是代码；改能力 = 改文件，不重新部署。
2. **元数据先行、详情按需**：每轮只付 name/description 的 token 成本，降低每轮开销。
3. **来源解耦、按优先级合并**：市场 / 工作区 / 用户隔离统一成四层合成，agent 感知不到来源差异。
4. **执行路径统一**：不管 skill 来自哪一层，agent 永远用 `<files-root>` 拼绝对路径，前缀由框架按文件系统模式算好。
5. **安全默认**：草稿 → 审核两段式；闸门默认**拒绝**；脚本执行默认进沙箱。

---

## 4. 架构：每轮推理前发生什么

每次系统提示准备时（`HarnessSkillMiddleware` 的 onSystemPrompt 阶段）：

1. 按低 → 高优先级遍历仓库，按 skill name 合并（后者覆盖）；
2. 应用可见性过滤（自学习闭环里配的，默认不过滤）；
3. 把市场 skill 的资源**物化**到 `<wsRoot>/.skills-cache/<source>/<name>/`（见第 6 节）；
4. 构建**per-call 的 `SkillCatalog`** 绑定到本次调用的 `RuntimeContext`——并发会话各自持有过滤后的视图，互不干扰；
5. 渲染 `<available_skills>` 追加进 system prompt。

### 各层资源怎么取（load 时）

| Skill 来源 | path 解析方式 |
|---|---|
| 项目全局目录（Layer 1） | 注册时预载到内存 |
| 市场 —— Git / MySQL / Nacos / classpath（Layer 2） | 由后端预载到内存 |
| `workspace/skills/` 共用（Layer 3） | 注册时预载到内存 |
| `<userId>/skills/` 用户隔离（Layer 4） | SKILL.md 预载；其它文件按需通过 `AbstractFilesystem` 读取 |

agent 感知不到差异，`load_skill_through_path` 调起来都一样。底层查找顺序：**内存命中 → 文件系统读取 → 都没有时返回"所有真正可用的路径清单"**——传错 path 只会拿到清单而不是死路。

---

## 5. API 用法（按意图分组）

### 5.1 接技能市场（外部仓库）

统一入口 `skillRepository(...)`，传什么后端都可以。

#### Git —— 团队仓库

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-skill-git-repository</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .workspace(workspace)
    .skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
    .build();
```

- **默认 autoSync=true**：每次读取做轻量化的远端检查，HEAD 变了才 pull；
- 仓库根下如果有 `skills/` 子目录会优先读它，否则读根目录；
- 想自己控制同步节奏：`new GitSkillRepository(url, false)`，然后手动 `repo.sync()`。

#### Nacos —— 在线下发 / 变更订阅

```java
NacosSkillRepository market = new NacosSkillRepository(aiService, "namespace");
HarnessAgent.builder().skillRepository(market).build();
```

适合需要在线下发、变更订阅的场景。`market` 有 `close()` 方法释放订阅，应用退出时记得关。

#### MySQL —— 平台统一管理

```java
MysqlSkillRepository registry = MysqlSkillRepository.builder(dataSource)
    .databaseName("agentscope")
    .skillsTableName("skills")
    .createIfNotExist(true)   // 自动建库建表
    .writeable(true)          // true=agent 侧可写回；只读分发传 false
    .build();
HarnessAgent.builder().skillRepository(registry).build();
```

#### Classpath —— 随 JAR 一起发布

把 skill 放进 `src/main/resources/skills/`，与 JAR 同发：

```java
// 构造抛 IOException，调用方要处理
.skillRepository(new ClasspathSkillRepository("skills"))
```

兼容标准 JAR 和 Spring Boot Fat JAR。

#### 接多个

`skillRepository(...)` 可重复调用，**后注册的优先级更高**：

```java
HarnessAgent.builder()
    .skillRepository(communityMarket)
    .skillRepository(internalRegistry)
    .skillRepository(teamGitRepo)
    .build();
```

### 5.2 放进工作区（不用注册）

- **大家共用**：`workspace/skills/<name>/SKILL.md` —— 适合项目特有规范、内部约定；
- **单个用户用 / 覆盖共用版**：`workspace/<userId>/skills/<name>/SKILL.md` —— 前提是调用时 `RuntimeContext.userId` 传了该用户。

关键认知：`workspace/<userId>/skills/` 是**逻辑路径**，不等于"一定在本机磁盘"。技能文件的读写统一走 `AbstractFilesystem` 抽象，实际落点由文件系统模式决定（本机磁盘 / 共享 KV / 沙箱容器），"按用户隔离 skill"因此跟具体存储后端解耦。不管哪种模式，`<userId>/skills/` 都按同样的优先级覆盖共用版。

### 5.3 自学习闭环（可选，三阶段独立开关）

**第一步：让 agent 能自己写 skill**

```java
HarnessAgent.builder()
    .enableSkillManageTool(SkillManageConfig.defaults())  // 默认走"草稿 → 审核"
    .build();
```

- 启用后 agent 获得两个工具：
  - `propose_skill` —— 把新 skill 写成草稿到 `skills/_drafts/<name>/`，等审核；
  - `skill_manage` —— 编辑已有 skill（创建 / 修改 / 添加附属文件 / 删除）。
- 不想要"草稿 → 审核"两步：`.enableSkillManageTool(true)`（`autoPromote=true`，写完直接生效），生产不建议。
- 默认 `securityScan=true`：每次写后跑 `SkillSecurityScanner`。
- 同时，agent 每次调 `load_skill_through_path` / `read_skill` 时，框架对 **agent 自己创建**的 skill 自动记一笔使用计数到 `skills/.usage.json` —— 为后面的清理、灰度提供数据。

**第二步：加审核闸门 + 可见性过滤**

```java
.enableSkillPromotionGate(
    new LocalApprovalGate(),          // 谁来批（见勘误①）
    new CompositeFilter(List.of(      // 怎么暴露
        new EnvironmentFilter("prod", agent.getSkillUsageStore()),
        new CanaryFilter(10, agent.getSkillUsageStore())   // 见勘误②
    )))
.environment("prod")
```

- **闸门（`SkillPromotionGate`）**：草稿变正式 skill 必须过它。内置三种：
  - `RejectAllGate`（**默认**）：直接拒绝——agent 自己永远无法把草稿晋升为正式 skill，只能由外部代码 `promoteSkill(...)` 晋升；
  - `LocalApprovalGate`：本地人工确认（需自配 prompter；CLI 演示用 `stdinPrompter(in, out)`）；
  - `NotifyAndWaitGate`：推消息后等待。
- **可见性过滤（`SkillVisibilityFilter`）**：决定 agent 推理时能看到哪些"agent 自己创建"的 skill。按部署环境（`EnvironmentFilter`）、灰度比例（`CanaryFilter`）、白名单（`AllowListFilter`）组合；`CompositeFilter` 把它们串起来。
- ⚠️ **勘误①**：官方示例 `new LocalApprovalGate(LocalApprovalGate.defaultPrompter())` **不编译**——该类没有单参 `(Prompter)` 构造；并且 `defaultPrompter()` 的设计就是"永不批准（返回 Defer 24h）"，不是"等人工确认"。正确写法：`new LocalApprovalGate()`（默认 30 分钟超时），交互式确认要用 `LocalApprovalGate.stdinPrompter(System.in, System.out)` 自己拼 `new LocalApprovalGate(timeout, prompter, envs)`。
- ⚠️ **勘误②**：官方示例 `new CanaryFilter(0.10, usageStore)` **不编译**——`percent` 是 `int`（0~100），传 `0.10` 会类型报错。正确写法 `new CanaryFilter(10, usageStore)`。

**第三步：后台周期性整理**

```java
.enableSkillCurator(SkillCuratorConfig.builder()
    .intervalHours(7 * 24)   // 一周跑一次
    .staleAfterDays(30)      // 超过 30 天没用标 stale
    .archiveAfterDays(90)    // 超过 90 天归档
    .build())
```

- 前提：先 `enableSkillManageTool(...)`（框架要求）。
- 默认（`SkillCuratorConfig.defaults()`）：`intervalHours=168`、`stale=30`、`archive=90`、`umbrellaPassMode=DRY_RUN_ONLY`——LLM 伞合并扫描默认**只出报告，不实际改动**。
- 后台按节流闸门跑：stale → 标状态；超过 90 天**非破坏性归档**到 `skills/.archive/`（只归档，永不删除）。
- ⚠️ **勘误③**：`CanaryFilter` 的 `rampUpDays` 参数当前是**占位**，未实现"按天爬坡"，始终用配置的静态百分比。

**程序化触发**

```java
// 查询某天的审计日志（存在 skills/.audit/yyyy-MM-dd.jsonl）
List<SkillAuditLog.Entry> entries =
    agent.queryAudit(LocalDate.now().toString(), e -> true);

// 立刻跑一次整理（绕过节流闸门）
agent.runCuratorOnce().subscribe(report -> System.out.println(report));

// 手动晋升一份草稿（默认 RejectAllGate 下，只有外部调用能晋升）
agent.promoteSkill("notes-taker", "alice", ctx)
    .subscribe(result -> System.out.println(result));
```

- ⚠️ **勘误④**：官方示例 `agent.queryAudit(LocalDate.now(), e -> true)` **不编译**——第一个参数是 `String dayUtc`（"yyyy-MM-dd"），不是 `LocalDate`；`agent.promoteSkill("notes-taker", "alice")` 也**缺第三个参数** `RuntimeContext ctx`（用户/会话作用域的工作区需要它来定位草稿）。

### 5.4 常用 Builder 开关与继承

| 方法 | 说明 |
|---|---|
| `skillRepository(repo)` | 追加一个市场；可重复调用 |
| `skillRepositories(list)` | 一次性替换所有市场 |
| `projectGlobalSkillsDir(path)` | 启用项目全局目录；**目录不存在则跳过** |
| `disableDynamicSkills()` | 关掉"每次推理前重新合并"，改成 build 时合并一次 |
| `disableDefaultWorkspaceSkills()` | 不注册 Layer 4 用户隔离仓库（Layer 3 与自定义市场仍生效） |

**子 agent 自动继承父的市场列表和项目全局目录**，不用重复配。

什么时候用 `disableDynamicSkills()`：单次任务跑完就退出；或市场后端慢、不想每轮拉。平时不用动。

---

## 6. 在沙箱里运行 skill：物化 → 投影 → 容器执行

先交代沙箱是什么（跨文档引用）：**沙箱模式**（Docker / Kubernetes filesystem）下，文件操作和 shell 都在隔离容器里执行，宿主完全不受影响。问题随之而来：skill 脚本写在宿主侧（市场 skill 的资源甚至只在内存里），agent 却要在容器里把它们跑起来。harness 用三步把它做成透明的。

### 哪些 skill 会进沙箱

| 来源 | 进沙箱前住哪儿 | 沙箱里的路径 |
|---|---|---|
| 工作区 skill（Layer 3 `workspace/skills/`、Layer 4 `<userId>/skills/`） | 本来就在工作区目录树里 | `/workspace/skills/<name>` |
| 市场 skill（Layer 1 项目全局、Layer 2 Git / MySQL / Nacos / classpath） | 资源最初只在内存里 | `/workspace/.skills-cache/<source>/<name>` |

### 第一步：把市场 skill 物化到宿主（`MarketplaceStager`，每轮推理前）

市场 skill 的资源拿到时只是内存里的字节，shell 没法直接执行。stager 把它们写到宿主的 `<wsRoot>/.skills-cache/<source>/<name>/`：

- **文件级 SHA-256 去重**：只重写变化过的文件，没变的跳过；
- **孤儿清理**：已下架的 skill、或从 builder 移除的整个仓库留下的目录，同一轮顺手删掉；
- **恢复执行位**：资源入库时被转成字符串，POSIX 权限丢了——stager 用启发式补回 `+x`：文件开头是 shebang（`#!`）或后缀是已知脚本类型（`.sh` / `.bash` / `.py` / `.rb` / `.pl` / `.js` / `.mjs`）就加执行位；纯静态资产（`.json` / `.md` / `.txt`）保持 644；
- **source 冲突兜底**：两个仓库返回相同的 `getSource()` 时，第二个自动加后缀（`<source>_2`、`<source>_3` …）并打 warning——路径和 skill-id 不会撞。

工作区 skill（Layer 3 / 4）跳过这一步——它们本来就在工作区目录里。

### 第二步：把工作区投影进沙箱（Workspace Projection）

沙箱 `start()` 时，harness 把宿主工作区里的静态资产打成 tar hydrate 进容器的 `/workspace`。默认投影根（`workspaceProjectionRoots`）正好覆盖 skill 需要的两个目录：

```
AGENTS.md  skills/  subagents/  knowledge/  .skills-cache/
```

投影对所有被包含的文件按内容算整体 SHA-256，跟上次一样就跳过 hydrate——反复 `call()` 不会重复传一样的文件，只有内容变了才重新注入。

可调项（`DockerFilesystemSpec` / `KubernetesFilesystemSpec` 上）：

| 方法 | 作用 |
|---|---|
| `workspaceProjectionRoots(List)` | 自定义投影哪些根目录（默认含 `skills`、`.skills-cache`） |
| `workspaceProjectionEnabled(false)` | 完全关掉投影——关了之后沙箱里就没有 skill 文件，脚本自然跑不了 |

### 第三步：在容器里执行脚本

`<available_skills>` 块里每个 skill 的 `<files-root>` 在沙箱模式下用**容器内**前缀渲染（工作区 `/workspace/skills/<name>`、市场 `/workspace/.skills-cache/<source>/<name>`），于是 agent 直接发：

```
execute("python3 /workspace/skills/code-reviewer/scripts/run-checks.sh <目标路径>")
```

这条命令在容器里跑，读到的就是投影进来的那份文件。agent 不用知道 skill 来自哪一层，前缀由框架算好；沙箱把工作区挂在非默认位置（如 AgentRun 的 `/home/agentscope/workspace`）时，前缀自动跟着换。

### 跨调用保留脚本副作用

脚本装了依赖、生成了产物（`npm install` / `pip install` / 编译输出），想下次 `call()` 还在，就给沙箱配**快照**（`snapshotSpec(...)`）。快照保存整个 `/workspace`，下次同一 scope key 的调用先恢复快照、再叠加投影——装过的东西不用重装。

### 注意：读 SKILL.md 不需要沙箱

容易混淆的一点：**读** skill（`load_skill_through_path` 取 `SKILL.md` / `references/`）走的是内存或宿主文件系统，跟沙箱无关；只有**用 shell 跑脚本**才需要文件真的进到容器里。所以即便关了投影、或某个 skill 根本没带脚本，agent 依然能正常读它的说明和参考资料。

---

## 7. 配置与边界（易踩坑）

- **`description` 决定 agent 用不用这个 skill**：agent 一开始只看得到 name + description，觉得相关才会 load 详情。写"数据分析工具"远不如写"当用户要算统计、出报表、做趋势图时使用"有效。
- **`SKILL.md` 保持精简**：控制在 2k tokens 上下，详细参考资料放 `references/`，脚本放 `scripts/`。agent 需要时会自己读。
- **SKILL.md 和脚本中只使用相对路径**：引用资源/脚本用相对于 SKILL.md 的路径（`scripts/run.py`、`references/guide.md`），**不要**硬编码绝对路径（如 `/workspace/scripts/run.py`）。框架会按当前文件系统模式为每个 skill 自动生成正确的 `<files-root>` 绝对路径前缀，agent 在 shell 执行时用它拼完整路径。
- **每轮只付元数据成本**：`<available_skills>` 只列 name / description / skill-id / files-root；详情由 `load_skill_through_path` 按需拉取。
- **工作区材料一次 call 开始时加载**：同一次 call 内改了 `SKILL.md`，本次推理不会变，下一次才生效。
- **安全默认**：闸门默认 `RejectAllGate`——agent 自己写的东西永远不能直接变成正式 skill，晋升是外部代码的职责（`promoteSkill(name, reviewerId, ctx)`）。
- **同名冲突四层覆盖**：下层独有的 skill 保留，重名时上层覆盖（见 2.2 表）。
- **`CanaryFilter` 的爬坡未实现**、`LocalApprovalGate` 默认 prompter 永不批准——用之前先看实现，别被名字误导。

---

## 8. 面试速查

### 高频考点

1. **Skill 是什么？和 Tool 有什么区别？**
   Skill 是一份"写好的能力包"（`SKILL.md` + 可选 `references/`、`scripts/`），声明式、按需加载，agent 自己决定什么时候用；Tool 是代码里注册的可调用函数（确定性入口）。Skill 给 agent 的是"说明书 + 脚本集"。

2. **四层技能合成的优先级？**
   项目全局目录（最低）→ 技能市场（后注册覆盖先注册）→ 工作区共用 `workspace/skills/` → 用户隔离 `<userId>/skills/`（最高）。重名上层覆盖，下层独有的保留。

3. **agent 怎么知道有哪些 skill？**
   每轮推理前框架把元数据（name / description / skill-id / files-root）渲染成 `<available_skills>` 块进 system prompt；agent 觉得相关才调 `load_skill_through_path` 拉详情。这就是两段式加载。

4. **市场 skill 在沙箱里怎么跑起来？**
   三步：`MarketplaceStager` 物化到宿主 `.skills-cache`（SHA-256 去重 + 孤儿清理 + 恢复执行位）→ 沙箱启动时投影（默认含 `skills` 和 `.skills-cache`）→ 容器内按 `<files-root>` 拼绝对路径 `execute`。

5. **自学习闭环的三个阶段？**
   `enableSkillManageTool`（propose_skill / skill_manage + 使用计数）→ `enableSkillPromotionGate`（闸门 + 可见性过滤）→ `enableSkillCurator`（后台 stale/archive 整理）。各阶段独立开关，默认全关。

6. **默认闸门是什么？agent 能自己晋升 skill 吗？**
   默认 `RejectAllGate`，永远 Defer；agent 自己晋升不了，必须外部 `promoteSkill(name, reviewerId, ctx)`。

7. **读 skill 需要沙箱吗？**
   不需要。`load_skill_through_path` 走内存或宿主文件系统；只有 shell 执行脚本才需要文件进容器。

8. **用户隔离 skill 一定落在本机磁盘吗？**
   不一定。`workspace/<userId>/skills/` 是逻辑路径，走 `AbstractFilesystem` 抽象，可能落在本机磁盘、共享 KV 或沙箱容器。

### 关键默认数字

| 项 | 默认值 |
|---|---|
| Git 市场同步 | autoSync=true，HEAD 变了才 pull；仓库有 `skills/` 子目录优先读它 |
| SkillManageConfig | autoPromote=false（草稿先落地）、securityScan=true、draftsDir=`skills/_drafts` |
| 闸门默认 | `RejectAllGate`（Defer 24h，只能外部晋升） |
| LocalApprovalGate | 超时 30 分钟；默认 prompter 永不批准 |
| SkillCuratorConfig | intervalHours=168（7 天）、stale 30 天、archive 90 天、umbrella=DRY_RUN_ONLY |
| 使用计数文件 | `skills/.usage.json` |
| 审计日志 | `skills/.audit/yyyy-MM-dd.jsonl` |
| 市场缓存目录 | `<wsRoot>/.skills-cache/<source>/<name>` |
| 投影默认根 | `AGENTS.md`、`skills`、`subagents`、`knowledge`、`.skills-cache` |
| SKILL.md 建议大小 | ~2k tokens |
