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
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MemoryToolsExample — 四个记忆工具（memory_save / memory_search / memory_get /
 * session_search）与"跨会话记忆"的完整演示。
 *
 * <p><b>演示内容</b>：
 * <ol>
 *   <li>会话 A：模型调用 <b>memory_save</b> 主动保存事实（同时追加 MEMORY.md 与今天的
 *       日流水账，是唯一被推荐的写入通道——不能用 write_file/edit_file 碰记忆文件）；</li>
 *   <li>会话 B（同 user、新 session）：不重新交代背景，模型靠 <b>MEMORY.md 注入</b>回答
 *       跨会话问题——这就是"跨会话记忆"的核心价值；</li>
 *   <li>会话 B 第二轮：显式引导模型用 <b>memory_search</b> 搜关键词、用 <b>memory_get</b>
 *       按行范围拉上下文（MEMORY.md 超出 8000 token 注入预算被截断时，模型应自行搜索）；</li>
 *   <li>会话 C：用 <b>session_search</b> 在原始会话日志（*.log.jsonl）里搜历史对话——与
 *       memory_search 的区别：前者搜"整理过的记忆文件"，后者搜"未加工的完整记录"。</li>
 * </ol>
 *
 * <p>说明：示例通过对话驱动模型调用工具（qwen-plus 对明确指令通常会自动调用）；
 * 结果是否命中取决于模型行为，但记忆文件的写入与读取由框架保证，示例同时打印文件内容佐证。
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryToolsExample
 * </pre>
 */
public class MemoryToolsExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("记忆工具 — memory_save / memory_search / memory_get / session_search");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-memory-tools-demo");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("memory-tools-demo")
                        .agentId("memory-tools-agent")
                        .sysPrompt(
                                "你是一个有长期记忆的助手。\n"
                                        + "- 用户明确要求记住、或你观察到重要偏好/决定时，用 memory_save 保存；\n"
                                        + "- 回答涉及以前说过的事、偏好、日期时，先用 memory_search 搜索 MEMORY.md 和"
                                        + " memory/*.md，再用 memory_get 读取命中行所在文件的上下文；\n"
                                        + "- 需要回忆之前某次会话说了什么时，用 session_search 搜索会话日志；\n"
                                        + "- 回答引用记忆时注明来源（如 MEMORY.md#12）。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .build();

        // 同一用户 alice 的三个不同会话：A 写入，B/C 回忆
        RuntimeContext ctxA =
                RuntimeContext.builder().userId("alice").sessionId("session-A").build();
        RuntimeContext ctxB =
                RuntimeContext.builder().userId("alice").sessionId("session-B").build();
        RuntimeContext ctxC =
                RuntimeContext.builder().userId("alice").sessionId("session-C").build();

        // ── 1. 会话 A：memory_save 保存事实 ───────────────────────────────────

        System.out.println("── 1. 会话 A：请模型用 memory_save 保存事实 ─────────────────\n");

        Msg saveReply =
                agent.call(
                                new UserMessage(
                                        "请记住以下信息，并用 memory_save 保存："
                                                + "用户偏好美式咖啡；项目代号是 Atlas；项目截止日期 2026-12-31。"),
                                ctxA)
                        .block();
        System.out.println("Agent: " + (saveReply != null ? saveReply.getTextContent() : "(null)"));

        // 等待 memory_save 异步写盘（工具调用本身是同步的，sleep 仅为保险）
        Thread.sleep(2000);

        System.out.println("\n── 验证：MEMORY.md 与今天日流水账均已追加 ──────────────────\n");

        Path memoryDir = agent.getWorkspaceManager().getMemoryDir(ctxA);
        Path memoryMd = agent.getWorkspaceManager().resolveRuntimeDataPath(ctxA, "MEMORY.md");
        if (Files.exists(memoryMd)) {
            System.out.println("MEMORY.md 内容：");
            System.out.println(Files.readString(memoryMd));
        }
        Files.list(memoryDir)
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .forEach(
                        p -> {
                            try {
                                System.out.println("日流水账 " + p.getFileName() + "：");
                                System.out.println(
                                        Files.readString(p).length() > 300
                                                ? Files.readString(p).substring(0, 300) + "\n…"
                                                : Files.readString(p));
                            } catch (Exception e) {
                                System.out.println("  读取失败: " + e.getMessage());
                            }
                        });

        // ── 2. 会话 B（全新会话）：靠 MEMORY.md 注入回答跨会话问题 ───────────────

        System.out.println("\n── 2. 会话 B（新会话，不重新交代背景）：跨会话记忆 ──────────\n");

        Msg recallReply =
                agent.call(new UserMessage("我之前跟你说过的咖啡偏好是什么？项目代号和截止日期呢？" + "请先回忆再回答。"), ctxB)
                        .block();
        System.out.println(
                "Agent: " + (recallReply != null ? recallReply.getTextContent() : "(null)"));

        // ── 3. 会话 B 第二轮：显式引导 memory_search + memory_get ───────────────

        System.out.println("\n── 3. 会话 B：显式引导 memory_search + memory_get ──────────\n");

        Msg searchReply =
                agent.call(
                                new UserMessage(
                                        "请用 memory_search 搜索关键词“咖啡”，"
                                                + "再用 memory_get 读取命中行所在文件的相关行，把原文告诉我。"),
                                ctxB)
                        .block();
        System.out.println(
                "Agent: " + (searchReply != null ? searchReply.getTextContent() : "(null)"));

        // ── 4. 会话 C：session_search 搜索原始会话日志 ─────────────────────────

        System.out.println("\n── 4. 会话 C：session_search 搜历史会话日志 ────────────────\n");

        Msg sessionReply =
                agent.call(
                                new UserMessage(
                                        "请用 session_search 搜索“Atlas”，" + "告诉我它在哪次会话中出现过、上下文是什么。"),
                                ctxC)
                        .block();
        System.out.println(
                "Agent: " + (sessionReply != null ? sessionReply.getTextContent() : "(null)"));

        agent.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：memory_save 写入（两层同时追加）；memory_search/memory_get 检索事实；");
        System.out.println("session_search 检索原始对话日志；同 user 的多个 session 共享长期记忆。");
        System.out.println("Workspace: " + workspace);
        System.out.println("=".repeat(60));
    }
}
