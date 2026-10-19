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
package io.agentscope.examples.documentation2.harness.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;

/**
 * MemoryFlushConsolidationExample — 双层长期记忆全流程：Flush 追加日流水账、
 * 后台 Consolidation 合并出 {@code MEMORY.md}、维护任务归档过期日流水账。
 *
 * <p><b>演示内容</b>：
 * <ol>
 *   <li><b>Flush（第一层）</b>——{@code MemoryFlushMiddleware} 在每次 {@code call()} 结束后
 *       异步抽取对话中的长期事实，追加到今天的 {@code memory/YYYY-MM-DD.md}；</li>
 *   <li><b>Consolidation（第二层）</b>——后台维护任务（最小间隔 {@code consolidationMinGap}）
 *       把日流水账合并去重、整体重写 {@code MEMORY.md}（watermark 增量合并：无新增内容则
 *       跳过 LLM 调用）；</li>
 *   <li><b>维护归档</b>——超过 {@code dailyFileRetentionDays} 的日流水账被移动到
 *       {@code memory/archive/}（本例预置一个"3 天前"的文件，配合 1 天保留期演示归档）。</li>
 * </ol>
 *
 * <p>关键认知：第一层只追加、不去重；第二层周期性整体重写；两层互不覆盖。
 * 模型每轮推理看到的是 {@code MEMORY.md}（第二层），日流水账等待被合并。
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryFlushConsolidationExample
 * </pre>
 */
public class MemoryFlushConsolidationExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("双层长期记忆 — Flush 日流水账 + Consolidation 合并 MEMORY.md");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-memory-flush-demo");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("memory-flush-demo")
                        .sysPrompt(
                                "你是一个乐于记住用户信息的助手，回答尽量简洁。"
                                        + "除非用户明确要求“记住”，否则不要主动调用 memory_save，也不要替用户保存信息。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(50) // 默认触发；本例不演示压缩
                                        .build())
                        .memory(
                                MemoryConfig.builder()
                                        .flushTrigger(
                                                MemoryConfig.FlushTrigger
                                                        .always()) // 每 call 结束都 flush
                                        .consolidationMinGap(Duration.ofSeconds(5)) // 演示用；默认 30 分钟
                                        .dailyFileRetentionDays(1) // 演示用；1 天前的日流水账即归档（默认 90 天）
                                        .build())
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("memory-demo-session").build();

        // ── 1. 预置一个"3 天前"的日流水账，用于演示归档 ────────────────────────

        System.out.println("── 1. 预置 3 天前的日流水账（模拟历史遗留）──────────────────\n");

        Path memoryDir = agent.getWorkspaceManager().getMemoryDir(ctx);
        String oldDate = LocalDate.now().minusDays(3).toString();
        agent.getWorkspaceManager()
                .writeUtf8WorkspaceRelative(
                        ctx,
                        "memory/" + oldDate + ".md",
                        "# " + oldDate + "\n\n- 历史事实：项目最初使用 Java 17（来自 3 天前的旧流水账）\n");
        System.out.println("已写入: " + memoryDir.resolve(oldDate + ".md"));

        // ── 2. 多轮对话：每次 call 结束后台 flush 追加今天的事实 ────────────────

        System.out.println("\n── 2. 多轮对话触发 Flush（每次 call 结束后台执行）──────────\n");

        String[] questions = {
            "我是后端工程师，主要用 Java 开发。",
            "我们团队用 Kubernetes 部署，测试环境叫 staging-cluster。",
            "这个项目代号 Atlas，计划 2026-12-31 上线。",
        };

        for (int i = 0; i < questions.length; i++) {
            Msg reply = agent.call(new UserMessage(questions[i]), ctx).block();
            System.out.println(
                    "Turn " + (i + 1) + ": " + (reply != null ? reply.getTextContent() : "(null)"));
        }

        // ── 3. 等待后台 Flush / Maintenance / Consolidation 完成 ────────────────

        System.out.println("\n（Flush 与后台维护都是 fire-and-forget，等待 8 秒让写入与合并完成）\n");
        Thread.sleep(8000);

        // ── 4. 验证：日流水账（第一层）已追加 ────────────────────────────────────

        System.out.println("── 3. 第一层：今天的日流水账 memory/YYYY-MM-DD.md ───────────\n");

        String today = LocalDate.now().toString();
        Path dailyLog = memoryDir.resolve(today + ".md");
        if (Files.exists(dailyLog)) {
            String content = Files.readString(dailyLog);
            System.out.println(
                    content.length() > 600 ? content.substring(0, 600) + "\n…" : content);
        } else {
            System.out.println("（今天尚无日流水账——flush 未触发，检查模型是否输出了 NO_REPLY）");
        }

        // ── 5. 验证：第二层 MEMORY.md 已被 consolidation 合并生成 ────────────────

        System.out.println("\n── 4. 第二层：MEMORY.md（后台 consolidation 合并产物）────────\n");

        Path memoryMd = agent.getWorkspaceManager().resolveRuntimeDataPath(ctx, "MEMORY.md");
        if (Files.exists(memoryMd)) {
            String content = Files.readString(memoryMd);
            System.out.println(
                    content.length() > 800 ? content.substring(0, 800) + "\n…" : content);
        } else {
            System.out.println("（MEMORY.md 尚未生成——consolidation 可能还没跑到或没有新内容）");
        }

        // ── 6. 验证：过期日流水账已归档到 memory/archive/ ────────────────────────

        System.out.println("\n── 5. 维护归档：3 天前的日流水账 → memory/archive/ ─────────\n");

        Path archiveDir = memoryDir.resolve("archive");
        if (Files.isDirectory(archiveDir)) {
            Files.list(archiveDir)
                    .sorted()
                    .forEach(p -> System.out.println("  已归档: " + p.getFileName()));
        } else {
            System.out.println("（memory/archive/ 不存在——维护任务可能还没运行）");
        }

        System.out.println("\n── memory/ 目录全貌 ────────────────────────────────────────\n");
        if (Files.isDirectory(memoryDir)) {
            Files.list(memoryDir).sorted().forEach(p -> System.out.println("  " + p.getFileName()));
        }

        agent.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：日流水账只追加不去重（第一层）；MEMORY.md 由后台合并重写（第二层）；");
        System.out.println("过期日流水账自动归档到 memory/archive/。两层互不覆盖。");
        System.out.println("Workspace: " + workspace);
        System.out.println("=".repeat(60));
    }
}
