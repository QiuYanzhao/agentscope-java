/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.harness.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import redis.clients.jedis.JedisPooled;

/**
 * WorkspaceSharedStoreExample —— 演示使用 Redis 的 {@link DistributedStore} API
 * 进行一行式分布式配置。
 *
 * <p><b>两条存储配置线统一说明（AgentStateStore 与 Workspace）：</b>
 *
 * <p>1. <b>仅把 AgentStateStore 配置到 Redis</b>（工作区保持默认本地）：
 * <pre>{@code
 * HarnessAgent.builder()
 *     .stateStore(RedisAgentStateStore.builder().jedisClient(jedis)
 *             .keyPrefix("agentscope:session:").build())
 *     // 不配 .filesystem(...) → 工作区使用默认 LocalFilesystemSpec（本机）
 *     .build();
 * }</pre>
 *
 * <p>2. <b>把工作区配置到 Redis</b>：RemoteFilesystemSpec 需要一个 BaseStore（KV 存储）
 * 作为两层读的"上层"。用 distributedStore 一行即可同时配好两条线：
 * <pre>{@code
 * DistributedStore store = RedisDistributedStore.fromJedis(jedis, "agentscope:example:");
 * HarnessAgent.builder()
 *     .distributedStore(store)
 *     // 自动装配：AgentStateStore（session: 前缀）+ BaseStore（store: 前缀）
 *     //            + 沙箱快照 + 执行锁
 *     .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 *
 * <p>3. <b>不使用 distributedStore，拆开配置</b>（与第 2 条等价）：
 * <pre>{@code
 * HarnessAgent.builder()
 *     .stateStore(RedisAgentStateStore.builder().jedisClient(jedis)
 *             .keyPrefix("agentscope:example:session:").build())
 *     .filesystem(new RemoteFilesystemSpec(new RedisStore(jedis, "agentscope:example:store:"))
 *             .isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 *
 * <p>4. <b>配置了 distributedStore 之后，再显式配置 RedisAgentStateStore</b>：
 * 显式 {@code .stateStore(...)} 的优先级更高（explicit builder methods take
 * precedence），AgentStateStore 使用你指定的实现；distributedStore 仍提供
 * BaseStore、沙箱快照与执行锁。
 *
 * <p>5. <b>AgentStateStore 与 Workspace 的 BaseStore 可以不在同一个 Redis</b>：
 * 二者是独立接口、独立构造，可分别指向不同实例（distributedStore 绑定单个
 * Jedis，跨实例时必须拆开配置）：
 * <pre>{@code
 * HarnessAgent.builder()
 *     .stateStore(RedisAgentStateStore.builder().jedisClient(jedisA).build())
 *     .filesystem(new RemoteFilesystemSpec(new RedisStore(jedisB, "agentscope:ws:"))
 *             .isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 *
 * <p><b>{@code .workspace(Path)} 的作用：</b>仅是线 B 两层读的"下层"（本地只读模板
 * 兜底），不是工作区的存储开关；本示例不向其放置任何文件，工作区完全持久化在 Redis：
 * AGENTS.md 种子通过 {@code WorkspaceManager.writeUtf8WorkspaceRelative(...)} 写入 KV，
 * 运行时产物（记忆、会话、任务等）同样落在 KV 中。
 *
 * <p>本示例采用第 2 条（一行式）写法，并展示：
 * <ol>
 *   <li><b>工作区全部持久化在 Redis</b> —— 种子文件与运行时产物都写入 Redis KV。</li>
 *   <li><b>多副本模拟</b> —— 两个 agent 实例共享同一个 Redis，演示跨副本的记忆与会话连续性。</li>
 *   <li><b>用户隔离</b> —— 每个用户的状态存放在隔离的命名空间中。</li>
 * </ol>
 *
 * <p><b>前置条件：</b>
 * <ul>
 *   <li>运行中的 Redis 实例（默认：{@code localhost:6379}）。</li>
 *   <li>DashScope API key 已设置为 {@code DASHSCOPE_API_KEY}。</li>
 * </ul>
 *
 * <p><b>运行：</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceSharedStoreExample
 * </pre>
 */
public class WorkspaceSharedStoreExample {

    private static final String DEFAULT_REDIS_URL = "redis://localhost:6379";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("分布式存储 —— 一行式 Redis 配置");
        System.out.println("=".repeat(60) + "\n");

        String redisUrl = System.getenv().getOrDefault("REDIS_URL", DEFAULT_REDIS_URL);
        System.out.println("正在连接 Redis：" + redisUrl + "\n");

        JedisPooled jedis = new JedisPooled(java.net.URI.create(redisUrl));
        DistributedStore store = RedisDistributedStore.fromJedis(jedis, "agentscope:example:");

        // 本地 workspace 作为两层读的"下层"（只读模板兜底）：运行时数据仍全部进 Redis KV，
        // 这里不写入任何文件；该目录可放置团队共享的模板（如 AGENTS.md 骨架）作为兜底。
        // 配置说明见类注释。
        Path workspace = Path.of("/Users/qyz/myFiles/Temp/AgentScopeProduct/workspace1");
        Files.createDirectories(workspace);

        // ── 构建 "replica 1" —— 一行式分布式配置 ────────────────────────────

        System.out.println("── 使用 distributedStore 构建 replica-1 与 replica-2 ──\n");

        HarnessAgent replica1 =
                HarnessAgent.builder()
                        .name("shared-agent")
                        .sysPrompt("你是一个乐于助人的助手。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .distributedStore(store)
                        .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
                        .build();

        // ── 将 AGENTS.md 种子写入 Redis KV（按用户隔离的命名空间）────────────
        // 每个用户（alice / bob）在 KV 中拥有各自命名空间下的 AGENTS.md。

        String agentsMd =
                """
                # 共享存储 Agent

                你是一个乐于助人的助手。
                保持回答简洁（不超过两句话）。
                """;
        RuntimeContext aliceCtx =
                RuntimeContext.builder().userId("alice").sessionId("alice-s1").build();
        RuntimeContext bobCtx = RuntimeContext.builder().userId("bob").sessionId("bob-s1").build();

        replica1.getWorkspaceManager().writeUtf8WorkspaceRelative(aliceCtx, "AGENTS.md", agentsMd);
        replica1.getWorkspaceManager().writeUtf8WorkspaceRelative(bobCtx, "AGENTS.md", agentsMd);

        System.out.println("── 已向 Redis KV 写入 AGENTS.md（按用户 alice / bob 隔离）──\n");

        // ── Alice 与 replica-1 对话 ────────────────────────────────────────

        System.out.println("── Alice 与 replica-1 对话 ──\n");

        Msg r1 = replica1.call(new UserMessage("我最喜欢的颜色是蓝色，请记住这一点。"), aliceCtx).block();
        System.out.println("Replica-1 回复：" + (r1 != null ? r1.getTextContent() : "(null)"));

        // ── Bob 与 replica-1 对话（与 Alice 隔离）────────────────────────────

        System.out.println("\n── Bob 与 replica-1 对话（独立命名空间）──\n");

        Msg r2 = replica1.call(new UserMessage("我最喜欢的颜色是红色，请记住这一点。"), bobCtx).block();
        System.out.println("Replica-1 回复：" + (r2 != null ? r2.getTextContent() : "(null)"));

        // ── 构建 "replica 2" —— 使用同一存储，演示跨副本连续性 ────────────────

        HarnessAgent replica2 =
                HarnessAgent.builder()
                        .name("shared-agent")
                        .sysPrompt("你是一个乐于助人的助手。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .distributedStore(store)
                        .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
                        .build();

        // ── Alice 在 replica-2 上恢复对话（跨副本连续性）──────────────────────

        System.out.println("\n── Alice 在 replica-2 上恢复对话（跨副本连续性）──\n");

        Msg r3 = replica2.call(new UserMessage("我最喜欢的颜色是什么？"), aliceCtx).block();
        System.out.println("Replica-2 回复（Alice）：" + (r3 != null ? r3.getTextContent() : "(null)"));

        // ── Bob 在 replica-2 上恢复对话 ──────────────────────────────────────

        System.out.println("\n── Bob 在 replica-2 上恢复对话 ──\n");

        Msg r4 = replica2.call(new UserMessage("我最喜欢的颜色是什么？"), bobCtx).block();
        System.out.println("Replica-2 回复（Bob）：" + (r4 != null ? r4.getTextContent() : "(null)"));

        System.out.println("\n" + "=".repeat(60));
        System.out.println("完成。两位用户的状态均成功跨副本迁移。");
        System.out.println("=".repeat(60));

        System.out.println(
                "\n提示：在服务器上执行 redis-cli --scan --pattern 'agentscope:example:*'"
                        + " 可查看 Redis 中保存的会话状态（session: 前缀）与工作区文件（store: 前缀）结构。");
    }
}
