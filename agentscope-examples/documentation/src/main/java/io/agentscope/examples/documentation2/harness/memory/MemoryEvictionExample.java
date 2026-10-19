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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * MemoryEvictionExample — 大工具结果卸载（Tool Result Eviction）：单次工具返回超过阈值时，
 * 全文写盘、上下文只留首尾预览 + 占位符，模型想看全文自己 read_file。
 *
 * <p><b>演示内容</b>：
 * <ol>
 *   <li>注册自定义工具 {@code bulk_output}（返回一大段文本）；</li>
 *   <li>{@code toolResultEviction} 把阈值调低到 800 字符、预览 200 字符（默认 80,000 / 2,000），
 *       让演示一次调用即可触发；</li>
 *   <li>引导模型调用 {@code bulk_output}，验证：工具结果被卸载到
 *       {@code large_tool_results/<agentName>/<callId>-<hash>}，且完整内容落盘；</li>
 *   <li>默认排除的工具（read_file / write_file / edit_file / memory_search / memory_get /
 *       session_search）不会被卸载；execute（shell）输出可能巨大，故意不排除。</li>
 * </ol>
 *
 * <p>与压缩的关系：卸载治"单条消息太宽"（width），压缩治"消息太多太深"（depth），
 * 两者独立、可单独开关。
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryEvictionExample
 * </pre>
 */
public class MemoryEvictionExample {

    /** 自定义工具：返回一大段文本，用于触发卸载。 */
    public static class BulkOutputTool {

        @Tool(
                name = "bulk_output",
                description =
                        "Generate a large block of text (default 40 lines) to demonstrate"
                                + " oversized tool-result eviction.")
        public ToolResultBlock bulkOutput(
                @ToolParam(
                                name = "lines",
                                description = "Number of lines to generate",
                                required = false)
                        Integer lines) {
            int n = (lines == null || lines <= 0) ? 40 : lines;
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                sb.append("line ")
                        .append(i)
                        .append(": ")
                        .append(
                                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
                                        .repeat(2))
                        .append("(padding to grow the output)\n");
            }
            return ToolResultBlock.success(sb.toString());
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("大工具结果卸载 — Tool Result Eviction");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-memory-eviction-demo");

        // 自定义工具与 Harness 内置工具共存（builder.toolkit 是追加语义）
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new BulkOutputTool());

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("memory-eviction-demo")
                        .sysPrompt("你有一个 bulk_output 工具，可以生成一大段文本。" + "当用户要求生成大段测试文本时调用它。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .toolkit(toolkit)
                        .toolResultEviction(
                                ToolResultEvictionConfig.builder()
                                        .maxResultChars(800) // 演示用阈值（默认 80,000）
                                        .previewChars(200) // 演示用预览（默认 2,000）
                                        .build())
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("eviction-demo").build();

        // ── 1. 让模型调用工具生成大输出 ───────────────────────────────────────

        System.out.println("── 1. 引导模型调用 bulk_output（输出远超 800 字符阈值）────────\n");

        Msg reply =
                agent.call(
                                new UserMessage(
                                        "请调用 bulk_output 工具生成 40 行测试文本，" + "然后告诉我工具大约返回了多少行。"),
                                ctx)
                        .block();
        System.out.println("Agent: " + (reply != null ? reply.getTextContent() : "(null)"));

        // ── 2. 验证：完整内容已落盘到 large_tool_results/ ─────────────────────

        System.out.println("\n── 2. 验证：卸载后的完整内容落盘位置 ────────────────────────\n");

        // 卸载写入走 filesystem（带 rc），本地模式下可能落在 workspace 根或
        // runtime-data 命名空间下，递归查找 "large_tool_results" 目录最稳妥。
        java.util.List<Path> evictedFiles = new java.util.ArrayList<>();
        try (Stream<Path> walk = Files.walk(workspace)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().contains("large_tool_results"))
                    .forEach(evictedFiles::add);
        }

        if (!evictedFiles.isEmpty()) {
            for (Path p : evictedFiles) {
                long bytes = Files.size(p);
                String head = Files.readString(p).substring(0, (int) Math.min(80, bytes));
                System.out.println("文件: " + p);
                System.out.println("字节数: " + bytes + "（完整内容在磁盘，上下文只剩首尾预览 + 占位符）");
                System.out.println("开头: " + head.replace('\n', ' ') + "…\n");
            }
        } else {
            System.out.println(
                    "（large_tool_results/ 不存在——卸载未触发。" + "可能原因：模型没调用工具，或工具输出未超过 maxResultChars）");
        }

        agent.close();

        System.out.println("=".repeat(60));
        System.out.println("要点：超阈值工具结果全文写盘（large_tool_results/<agentName>/），");
        System.out.println("上下文只保留首尾预览 + 占位符，模型想看全文自己 read_file；");
        System.out.println("默认排除文件/记忆类工具，execute 故意不排除。");
        System.out.println("Workspace: " + workspace);
        System.out.println("=".repeat(60));
    }
}
