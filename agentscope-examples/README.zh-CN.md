# AgentScope Java 示例

本目录包含展示 AgentScope Java 框架核心特性的示例代码。

## 🚀 快速开始

### 环境要求

- **JDK 17** 或更高版本
- **Maven 3.6+**
- **DashScope API Key** —— 在 https://dashscope.console.aliyun.com/apiKey 申请

### 构建示例

```bash
# 在项目根目录下，先构建并安装主库
cd agentscope-core
mvn clean install

# 构建示例
cd agentscope-examples
mvn compile
```

### 环境配置

设置你的 API Key（可选——未设置时示例会提示你输入）：

```bash
export DASHSCOPE_API_KEY=your_api_key_here
```

## 📚 示例总览

| 示例 | 说明 | 核心概念 | 运行命令 |
|---------|-------------|---------------|-------------|
| **BasicChatExample** | 最简单的智能体对话 | Agent、Model、Memory | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.quickstart.BasicChatExample"` |
| **ToolCallingExample** | 为智能体配备工具 | @Tool、Toolkit、工具调用 | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.tool.ToolCallingExample"` |
| **StructuredOutputExample** | 生成类型化的结构化输出 | 结构化输出、Schema 校验 | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.structuredoutput.StructuredOutputExample"` |
| **ToolGroupExample** | 自主管理工具组 | 元工具、工具组、自主激活 | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.tool.ToolGroupExample"` |
| **McpStdioExample** | 本地 MCP 服务器集成 | MCP、StdIO 传输 | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.mcp.McpStdioExample"` |
| **McpSseExample** | 远程 MCP 服务器集成 | MCP、SSE 传输 | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.mcp.McpSseExample"` |
| **McpStreamableHttpExample** | 远程 MCP 服务器集成 | MCP、Streamable HTTP | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.mcp.McpStreamableHttpExample"` |
| **CustomizedMiddlewareExample** | 监控智能体执行过程 | Middleware、生命周期拦截 | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.middleware.CustomizedMiddlewareExample"` |
| **StreamingWebExample** | Spring Boot + SSE 流式输出 | Web API、实时流式 | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.streaming.StreamingWebExample"` |
| **StateExample** | 对话持久化 | AgentStateStore、状态管理 | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.state.StateExample"` |
| **InterruptionExample** | 智能体中断机制 | 用户中断、恢复 | `mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.hitl.InterruptionExample"` |

## 📖 详细示例

### 1. BasicChatExample

创建智能体并与之对话的最简单方式。

```bash
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.quickstart.BasicChatExample"
```

**你将学到：**
- 创建 ReActAgent
- 配置 Model、Memory 和 Formatter
- 进行交互式对话

**可以试试问：**
- "你好，介绍一下你自己"
- "你能帮我做什么？"

---

### 2. ToolCallingExample

学习如何让智能体使用工具。

```bash
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.tool.ToolCallingExample"
```

**你将学到：**
- 使用 `@Tool` 注解定义工具
- 将工具注册到 Toolkit
- 让智能体自动调用工具

**可以试试问：**
- "东京现在几点？"
- "计算 123 * 456"
- "搜索'人工智能'"

---

### 3. StructuredOutputExample

从自然语言查询中生成结构化的、类型化的输出。

```bash
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.structuredoutput.StructuredOutputExample"
```

**你将学到：**
- 使用 Java 类定义结构化输出 Schema
- 让智能体返回结构化响应
- 提取并校验类型化数据

**工作原理：**
本示例演示了三个使用场景：

1. **产品需求提取**
   - 输入：自然语言形式的产品描述
   - 输出：包含类型、品牌、规格、预算、特性的结构化 `ProductRequirements` 对象

2. **联系信息提取**
   - 输入：包含联系信息的文本
   - 输出：包含姓名、邮箱、电话、公司的结构化 `ContactInfo` 对象

3. **情感分析**
   - 输入：客户评价文本
   - 输出：包含情感、评分、主题、摘要的结构化 `SentimentAnalysis` 对象

**示例输出：**
```
=== Example 1: Product Information ===
Query: I'm looking for a laptop. I need at least 16GB RAM, prefer Apple brand...

Extracted structured data:
  Product Type: laptop
  Brand: Apple
  Min RAM: 16 GB
  Max Budget: $2000.0
  Features: [lightweight, travel-friendly]
```

**核心特性：**
- ✅ 类型安全的数据提取
- ✅ 从 Java 类自动生成 Schema
- ✅ 兼容所有支持工具调用的模型
- ✅ 无需手动解析 JSON

---

### 4. ToolGroupExample

智能体使用元工具（meta-tool）自主管理工具组。

```bash
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.tool.ToolGroupExample"
```

**你将学到：**
- 创建工具组来组织工具
- 智能体使用 `reset_equipped_tools` 元工具自主激活工具组
- 智能体根据任务需求决定激活哪些工具

**工作原理：**
- 所有工具组初始状态为 **INACTIVE**（未激活）
- 智能体可以访问 `reset_equipped_tools` 元工具
- 当你给智能体一个任务时，它会：
  1. 判断需要哪些工具组
  2. 调用 `reset_equipped_tools` 激活这些工具组
  3. 使用已激活工具组中的工具

**可以试试的提示词：**

1. **激活单一工具组：**
   ```
   You> Calculate the factorial of 5
   ```
   观察：智能体激活 `math_ops`，然后使用 `factorial` 工具

2. **换一个工具组：**
   ```
   You> Ping google.com
   ```
   观察：智能体激活 `network_ops`，然后使用 `ping` 工具

3. **再换一个工具组：**
   ```
   You> List files in /tmp
   ```
   观察：智能体激活 `file_ops`，然后使用 `list_files` 工具

4. **一个任务激活多个工具组：**
   ```
   You> Calculate factorial of 7 and then ping github.com
   ```
   观察：智能体同时激活 `math_ops` 和 `network_ops`

5. **复杂的多工具组任务：**
   ```
   You> Check if 17 is prime, then list files in /tmp
   ```
   观察：智能体激活 `math_ops` 和 `file_ops`

本示例展示了**自主工具管理**——智能体会根据你的请求智能决定启用哪些工具！

---

### 5. MCP 示例

使用模型上下文协议（Model Context Protocol, MCP）连接外部工具服务器。

```bash
# 通过 stdin/stdout 的本地子进程
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.mcp.McpStdioExample"

# 通过 Server-Sent Events 的远程服务器
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.mcp.McpSseExample"

# 通过 Streamable HTTP 的远程服务器
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.mcp.McpStreamableHttpExample"
```

**前置条件：**
安装一个 MCP 服务器：
```bash
npm install -g @modelcontextprotocol/server-filesystem
```

使用远程传输方式时，在运行对应示例前设置 `MCP_SSE_URL` 或 `MCP_HTTP_URL`。可选凭据可通过 `MCP_SSE_TOKEN` 或 `MCP_HTTP_API_KEY` 提供。

**你将学到：**
- 连接 MCP 服务器（StdIO、SSE、HTTP 三种传输方式）
- 使用 MCP 服务器提供的外部工具
- 针对本地或远程服务器选择合适的 MCP 传输方式

**可以试试问：**
- "列出 /tmp 下的文件"
- "读取 /tmp/test.txt 的内容"

---

### 6. CustomizedMiddlewareExample（原 HookExample）

实时监控和拦截智能体的执行过程。

```bash
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.middleware.CustomizedMiddlewareExample"
```

**你将学到：**
- 使用 `MiddlewareBase` 拦截智能体的推理（reasoning）和执行（acting）阶段
- 监控流式输出
- 追踪工具执行
- 使用 ToolEmitter 上报进度

**可以试试问：**
- "处理客户数据集"

你会看到以下详细日志：
- 智能体启动
- 推理分片（流式）
- 工具调用及结果
- 进度更新
- 完成

---

### 7. StreamingWebExample

使用 Server-Sent Events（SSE）流式输出的 Spring Boot Web 应用。

```bash
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.streaming.StreamingWebExample"
```

**你将学到：**
- 使用响应式端点构建 Spring Boot REST API
- 使用 Server-Sent Events（SSE）实现实时流式输出
- 基于 Hook 的流式响应收集
- Web 环境下的会话持久化

**如何使用：**
启动服务器后，打开浏览器或使用 curl：

```bash
# 简单查询
curl -N "http://localhost:8080/chat?message=Hello"

# 带会话持久化
curl -N "http://localhost:8080/chat?message=What%20is%20AI?&sessionId=my-session"

# 或在浏览器中打开
http://localhost:8080/chat?message=Hello
```

你会看到智能体的响应逐字实时流式输出。

---

### 8. StateExample

跨多次运行保持持久的对话历史。

```bash
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.state.StateExample"
```

**你将学到：**
- 使用 `JsonFileAgentStateStore` 实现持久化
- 自动保存和加载 `AgentState`
- 使用相同的 session ID 恢复对话

**可以试试这个流程：**
```
# 第一次运行
Enter session ID: alice_session
You> My name is Alice and I love pizza

# 第二次运行（相同的 session ID）
Enter session ID: alice_session
You> What's my name and what do I like?
Agent> Your name is Alice and you love pizza!
```

---

### 9. InterruptionExample

优雅地中断长时间运行的智能体任务。

```bash
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.hitl.InterruptionExample"
```

**你将学到：**
- 用户发起的中断
- 协作式中断机制
- 生成伪工具结果（fake tool results）
- 优雅恢复

本示例通过启动一个长任务并在 2 秒后中断它，来自动演示中断机制。

---

## 🛠️ 常用操作

### 运行指定示例

```bash
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.quickstart.BasicChatExample"
```

### 调试示例

开启调试日志：
```bash
mvn exec:java -pl documentation -Dexec.mainClass="io.agentscope.examples.documentation2.quickstart.BasicChatExample" \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=debug
```

### 代码格式化

```bash
mvn spotless:apply
```

## 📝 API Key 配置

示例支持两种提供 API Key 的方式：

1. **环境变量**（推荐）：
   ```bash
   export DASHSCOPE_API_KEY=your_key_here
   mvn exec:java -pl documentation -Dexec.mainClass="..."
   ```

2. **交互式输入**：
   如果未设置环境变量，示例会提示你输入 API Key。

## 🤔 常见问题排查

### 提示"DASHSCOPE_API_KEY not found"

设置环境变量：
```bash
export DASHSCOPE_API_KEY=sk-xxx
```

或者让示例以交互方式提示你输入。

### MCP 服务器连接失败

对于 `McpStdioExample`，请确保已安装 MCP 服务器：
```bash
# 文件系统服务器
npm install -g @modelcontextprotocol/server-filesystem

# Git 服务器
npm install -g @modelcontextprotocol/server-git
```

### 编译错误

请确认先构建了主库：
```bash
cd /path/to/agentscope-core
mvn clean install
```

## 📚 更多资源

- [AgentScope Java 文档](https://java.agentscope.io/)
- [API 参考](../docs/)
- [CONTEXT.md](../CONTEXT.md) —— 开发指南

## 💡 贡献指南

添加新示例时：

1. 每个示例专注于单一功能
2. 添加清晰的文档和注释
3. 包含用于配置的交互式提示
4. 遵循现有代码风格
5. 提交前运行 `mvn spotless:apply`

## 📄 许可证

Apache License 2.0 —— 详见 [LICENSE](../LICENSE)。
