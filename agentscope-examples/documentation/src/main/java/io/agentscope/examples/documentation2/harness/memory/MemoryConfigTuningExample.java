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
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * MemoryConfigTuningExample — MemoryConfig 定制与关闭开关的行为对比。
 *
 * <p><b>演示内容</b>：
 * <ol>
 *   <li><b>flushTrigger(NEVER)</b>——只关 per-call flush：跑一轮对话后，今天不产生新的
 *       日流水账（后台 consolidation 仍可运行）；</li>
 *   <li><b>consolidationPrompt 占位符校验</b>——自定义 prompt 必须恰好包含两个 {@code %d}
 *       占位符，否则 {@code MemoryConfig.Builder} 构造时就抛 {@code IllegalArgumentException}
 *       （错误尽早暴露）；</li>
 *   <li><b>disableMemoryHooks()</b>——核选项：连 flush + 后台维护一起关，跑一轮后
 *       memory/ 目录完全无新增；</li>
 *   <li><b>自定义 flushPrompt + 小模型</b>——在默认 prompt 上追加项目规则、用便宜模型跑
 *       记忆操作（API 展示）。</li>
 * </ol>
 *
 * <p>开关速记：{@code flushTrigger(NEVER)} 只关每 call 的自动抽取；{@code disableMemoryHooks()}
 * 关 flush + 后台维护；{@code disableMemoryTools()} 不注册四个记忆工具；两者一起关还会跳过
 * {@code MEMORY.md} 注入。只想节流不想关，用 {@code flushTrigger(throttled(...))}。
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryConfigTuningExample
 * </pre>
 */
public class MemoryConfigTuningExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("MemoryConfig 定制与开关 — 行为对比");
        System.out.println("=".repeat(60) + "\n");

        // ── 1. flushTrigger(NEVER)：不产生新日流水账 ─────────────────────────

        System.out.println("── 1. flushTrigger(NEVER)：关闭 per-call flush ───────────────\n");

        Path workspace1 = Files.createTempDirectory("agentscope-memory-never-demo");
        HarnessAgent neverAgent =
                HarnessAgent.builder()
                        .name("memory-never-demo")
                        .sysPrompt(
                                "你是一个乐于记住用户信息的助手。"
                                        + "除非用户明确要求“记住”，否则不要主动调用 memory_save，也不要替用户保存信息。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace1)
                        .memory(
                                MemoryConfig.builder()
                                        .flushTrigger(MemoryConfig.FlushTrigger.never())
                                        .build())
                        .build();

        RuntimeContext ctx1 = RuntimeContext.builder().sessionId("never-demo").build();
        Msg reply1 = neverAgent.call(new UserMessage("我平时用 IntelliJ IDEA 写代码。"), ctx1).block();
        System.out.println("Agent: " + (reply1 != null ? reply1.getTextContent() : "(null)"));

        Thread.sleep(3000); // 给潜在的 flush 机会（若有）

        Path memoryDir1 = neverAgent.getWorkspaceManager().getMemoryDir(ctx1);
        String today = LocalDate.now().toString();
        Path daily1 = memoryDir1.resolve(today + ".md");
        System.out.println(
                "\n今天日流水账存在？ "
                        + Files.exists(daily1)
                        + "（flushTrigger(NEVER) → 应为 false；模型未主动 memory_save）");
        neverAgent.close();

        // ── 2. consolidationPrompt 占位符校验（无需模型） ─────────────────────

        System.out.println("\n── 2. consolidationPrompt 必须恰好两个 %d（构造时校验）────────\n");

        try {
            MemoryConfig.builder()
                    .consolidationPrompt(
                            "You are merging daily memory ledgers. Keep within %d tokens."
                                    + " Output the complete file in markdown.") // 只有 1 个 %d
                    .build();
            System.out.println("（未抛异常——不应走到这里）");
        } catch (IllegalArgumentException e) {
            System.out.println("构造时抛 IllegalArgumentException：");
            System.out.println("  " + e.getMessage());
        }

        // ── 3. disableMemoryHooks()：flush + 后台维护全关 ─────────────────────

        System.out.println("\n── 3. disableMemoryHooks()：flush + 后台维护全关 ────────────\n");

        Path workspace3 = Files.createTempDirectory("agentscope-memory-hooks-off-demo");
        HarnessAgent hooksOffAgent =
                HarnessAgent.builder()
                        .name("memory-hooks-off-demo")
                        .sysPrompt(
                                "你是一个乐于记住用户信息的助手。"
                                        + "除非用户明确要求“记住”，否则不要主动调用 memory_save，也不要替用户保存信息。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace3)
                        .disableMemoryHooks() // 核选项：连后台 consolidation 一起关
                        .build();

        RuntimeContext ctx3 = RuntimeContext.builder().sessionId("hooks-off-demo").build();
        Msg reply3 =
                hooksOffAgent.call(new UserMessage("团队每天用 GitHub Actions 跑 CI。"), ctx3).block();
        System.out.println("Agent: " + (reply3 != null ? reply3.getTextContent() : "(null)"));

        Thread.sleep(3000);

        Path memoryDir3 = hooksOffAgent.getWorkspaceManager().getMemoryDir(ctx3);
        boolean hasMemoryDir = Files.isDirectory(memoryDir3);
        System.out.println(
                "\nmemory/ 目录被创建？ "
                        + hasMemoryDir
                        + "（disableMemoryHooks → 应为 false；模型未主动 memory_save）");
        hooksOffAgent.close();

        // ── 4. 自定义 flushPrompt + 小模型（API 展示，正常跑一轮） ────────────

        System.out.println("\n── 4. 自定义 flushPrompt 追加项目规则 + 用便宜模型跑记忆操作 ────────\n");

        Path workspace4 = Files.createTempDirectory("agentscope-memory-tuned-demo");
        HarnessAgent tunedAgent =
                HarnessAgent.builder()
                        .name("memory-tuned-demo")
                        .sysPrompt(
                                "你是一个乐于记住用户信息的助手。"
                                        + "除非用户明确要求“记住”，否则不要主动调用 memory_save，也不要替用户保存信息。")
                        .model("dashscope:qwen-plus") // 主推理模型
                        .workspace(workspace4)
                        .memory(
                                MemoryConfig.builder()
                                        .model("dashscope:qwen-turbo") // 记忆操作用更便宜的模型
                                        .flushPrompt(
                                                MemoryFlushManager.DEFAULT_FLUSH_PROMPT
                                                        + "\n"
                                                        + "Additional project rules:\n"
                                                        + "- Never record customer PII (names,"
                                                        + " emails, phone numbers).\n"
                                                        + "- Always use Chinese for"
                                                        + " project-internal vocabulary.\n")
                                        .build())
                        .build();

        RuntimeContext ctx4 = RuntimeContext.builder().sessionId("tuned-demo").build();
        Msg reply4 =
                tunedAgent.call(new UserMessage("项目用 Spring AI Alibaba 做 Agent 开发。"), ctx4).block();
        System.out.println("Agent: " + (reply4 != null ? reply4.getTextContent() : "(null)"));

        Thread.sleep(3000);

        Path memoryDir4 = tunedAgent.getWorkspaceManager().getMemoryDir(ctx4);
        Path daily4 = memoryDir4.resolve(LocalDate.now() + ".md");
        System.out.println(
                "\n自定义 flushPrompt 已生效（今天日流水账由自动 flush 生成）？ " + Files.exists(daily4) + "（应为 true）");
        tunedAgent.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：NEVER 只关 per-call flush；disableMemoryHooks 连后台一起关；");
        System.out.println("consolidationPrompt 两个 %d 是硬校验；flush/consolidation 可配独立小模型。");
        System.out.println("=".repeat(60));
    }
}
