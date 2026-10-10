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
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WorkspaceSandboxExample — 文件系统模式 2（沙箱）：文件与命令在隔离的 Docker 容器内运行。
 * 宿主机不受影响，且沙箱工作区在多次调用之间持续保留。
 *
 * <p>本示例展示的内容：
 * <ol>
 *   <li><b>{@link DockerFilesystemSpec}</b> — 所有文件操作和 shell 命令都会被路由到 Docker
 *       容器内执行。</li>
 *   <li><b>{@link IsolationScope#USER}</b> — 每个用户拥有独立的沙箱实例；同一用户的多次调用
 *       会复用同一个沙箱（或从快照恢复）。</li>
 *   <li><b>跨调用持久化</b> — 某次调用在沙箱中创建的文件，在同一用户的下一次调用中仍然可见。</li>
 *   <li><b>宿主机工作区投影</b> — {@code AGENTS.md}、{@code skills/} 等会在每次启动时从宿主机
 *       同步进沙箱，并通过内容哈希进行校验（避免重复同步）。</li>
 * </ol>
 *
 * <p><b>前置条件：</b>
 * <ul>
 *   <li>Docker 守护进程正在运行（{@code docker info} 应能成功执行）。</li>
 *   <li>镜像 {@code ubuntu:24.04} 已拉取或可以拉取。</li>
 *   <li>DashScope API 密钥已设置为环境变量 {@code DASHSCOPE_API_KEY}。</li>
 * </ul>
 *
 * <p><b>分布式部署（多副本）：</b>若要在多个副本之间共享沙箱状态，可配置
 * {@link io.agentscope.harness.agent.DistributedStore}。它会自动装配
 * {@code AgentStateStore}、{@code SandboxSnapshotSpec} 和 {@code SandboxExecutionGuard}：
 * <pre>
 *   DistributedStore store = RedisDistributedStore.fromJedis(jedis);
 *   HarnessAgent.builder()
 *       .distributedStore(store)
 *       .filesystem(new DockerFilesystemSpec()
 *           .image("ubuntu:24.04")
 *           .isolationScope(IsolationScope.USER))
 *       .build();
 * </pre>
 *
 * <p><b>运行方式：</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceSandboxExample
 * </pre>
 */
public class WorkspaceSandboxExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("文件系统模式 2 — Docker 沙箱（隔离执行）");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-sandbox-example");

        Files.writeString(
                workspace.resolve("AGENTS.md"),
                """
                # 沙箱助手

                你是一个运行在 Docker 沙箱中的编程助手。
                所有文件操作和 shell 命令都在容器内执行。
                宿主机不受影响。
                回答请保持简洁（不超过两句话）。
                """);

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("sandbox-agent")
                        .sysPrompt("你是 Docker 沙箱中的编程助手。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .filesystem(
                                new DockerFilesystemSpec()
                                        .image("ubuntu:24.04")
                                        .isolationScope(IsolationScope.USER))
                        .build();

        // ── Alice：在沙箱内执行命令 ─────────────────────────────────────────

        System.out.println("── Alice 第 1 次调用：在沙箱内创建文件 ──\n");

        RuntimeContext aliceCtx =
                RuntimeContext.builder().userId("alice").sessionId("alice-s1").build();

        System.out.print("Agent：");
        agent.streamEvents(
                        new UserMessage(
                                "创建文件 /workspace/hello.txt，内容为 'Hello from sandbox'，"
                                        + "然后用 cat 查看以确认。"),
                        aliceCtx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n  [工具] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        // ── Alice 第 2 次调用：验证跨调用的文件持久化 ───────────────────────

        System.out.println("\n\n── Alice 第 2 次调用：验证文件在多次调用之间仍然存在 ──\n");

        System.out.print("Agent：");
        agent.streamEvents(new UserMessage("读取 /workspace/hello.txt —— 它还存在吗？"), aliceCtx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n  [工具] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        // ── Bob：独立沙箱，看不到 Alice 的文件 ──────────────────────────────

        System.out.println("\n\n── Bob：独立沙箱（USER 隔离级别） ──\n");

        RuntimeContext bobCtx = RuntimeContext.builder().userId("bob").sessionId("bob-s1").build();

        Msg bobReply =
                agent.call(new UserMessage("/workspace/hello.txt 存在吗？只回答是或否。"), bobCtx).block();
        System.out.println("Bob 的回答：" + (bobReply != null ? bobReply.getTextContent() : "（null）"));
        System.out.println("（预期结果：否 —— Bob 拥有独立的沙箱）");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("完成。Alice 和 Bob 在彼此隔离的 Docker 容器中运行。");
        System.out.println("=".repeat(60));
    }
}
