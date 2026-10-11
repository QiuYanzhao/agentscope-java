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
package io.agentscope.examples.documentation2.harness.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionViews;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * SessionInterruptResumeExample — 中断与继续（interrupt / resume），以及运行中补充要求
 * （steer / inject）。
 *
 * <p><b>演示内容</b>：
 * <ol>
 *   <li>{@code session.submit(...)} 提交一个长任务（工具每步 500ms，共 10 步 ≈ 5 秒）；</li>
 *   <li>任务运行中调用 {@code session.steer(task, ...)} 补充/调整要求——关联到原任务，
 *       下一次推理交给模型；已发出的模型请求和正在执行的工具参数不会被改写；</li>
 *   <li>{@code session.interrupt(runId)} 请求当前执行在协作检查点停止（不是立即终止，
 *       方法返回不代表已停下，要等 {@code await} 观察到 {@code interrupted}）；</li>
 *   <li>{@code session.resume(turnId)} 恢复已保存的工作状态继续原任务——业务无需重发
 *       用户消息；恢复的是 checkpoint（会话工作状态），不恢复原 Java 线程、网络请求或
 *       工具内部进度，也不回滚工具已产生的外部效果；</li>
 *   <li>最后用 {@code session.inspect()} 展示恢复检查（结果未知的工具调用集合）。</li>
 * </ol>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.session.SessionInterruptResumeExample
 * </pre>
 */
public class SessionInterruptResumeExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("会话操作 — 中断与继续（interrupt / resume / steer）");
        System.out.println("=".repeat(60) + "\n");

        Path workspace =
                Files.createDirectories(
                        Path.of(
                                "/Users/qyz/myFiles/Temp/AgentScopeProduct/session-interrupt-resume"));

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SlowProcessTools());

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("数据处理助手")
                        .agentId("data-assistant")
                        .sysPrompt("你是一个数据处理助手。用户要求处理数据时，调用 process_dataset " + "工具，处理完成后汇报结果。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .toolkit(toolkit)
                        .maxIters(10)
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().userId("alice").sessionId("job-001").build();

        AgentSession session = agent.session(ctx);

        // ── 1. 提交长任务 ────────────────────────────────────────────────────

        System.out.println("── 1. submit：提交长任务 ───────────────────────────────\n");

        AgentSession.Task task = session.submit("请处理 customer_data 数据集，执行 analyze 操作。");
        System.out.println("turnId=" + task.turnId() + "，status=" + task.status() + "\n");

        // 等任务进入执行（工具开始跑，才有"运行中"可以 steer / interrupt）
        Thread.sleep(2000);
        System.out.println("（任务已运行约 2 秒，工具正在执行中）\n");

        // ── 2. 运行中补充要求（steer）────────────────────────────────────────

        System.out.println("── 2. steer：运行中补充要求 ────────────────────────────\n");

        try {
            AgentSession.InputReceipt receipt = session.steer(task, "补充要求：处理完把结论按摘要格式输出。");
            System.out.println(
                    "steer 已接受，inputId=" + receipt.inputId() + "，turn=" + receipt.turnId());
            System.out.println("（要求会在下一次推理步骤交给模型）\n");
        } catch (IllegalStateException e) {
            System.out.println("steer 未生效（任务已不在运行中）: " + e.getMessage() + "\n");
        }

        // ── 3. 中断（interrupt）──────────────────────────────────────────────

        System.out.println("── 3. interrupt：协作检查点停止 ────────────────────────\n");

        /**
         * 这种写法是重新扫描 log + active run 组装的最新快照（第 355-388 行），此时任务已运行，`runId` 才真正有值
         * 如果使用`task.runId()` 是提交瞬间的快照，排队中必然为 null
         * 体现框架特性：回执不等于当前状态，想知道 runId 要查 `task()`
         */
        String runId = session.task(task.commandId()).runId();
        System.out.println("当前运行 runId=" + runId);

        boolean sent = session.interrupt(runId);
        System.out.println("interrupt 已接受: " + sent + "（等待协作检查点生效…）\n");

        AgentSession.Task stopped = session.await(task).block(Duration.ofSeconds(60));
        System.out.println("中断后终态: " + (stopped != null ? stopped.status() : "(await 超时)"));

        // ── 4. 继续原任务（resume）───────────────────────────────────────────

        System.out.println("\n── 4. resume：从 checkpoint 继续原任务 ─────────────────\n");

        AgentSession.Task continued = session.resume(task.turnId());
        System.out.println("resume 已接受，新执行 runId=" + continued.runId() + "\n");

        AgentSession.Task done = session.await(continued).block(Duration.ofSeconds(180));
        System.out.println("继续后终态: " + (done != null ? done.status() : "(await 超时)"));

        // ── 5. 读取最终历史 + 恢复检查 ───────────────────────────────────────

        System.out.println("\n── 5. transcript / inspect：读历史与恢复检查 ───────────\n");

        SessionViews.Transcript transcript = session.transcript();
        System.out.println("最终消息数=" + transcript.messages().size());
        transcript
                .messages()
                .forEach(
                        m ->
                                System.out.println(
                                        "  ["
                                                + m.getRole()
                                                + "] "
                                                + truncate(m.getTextContent(), 80)));

        SessionProjection projection = session.inspect();
        System.out.println(
                "\ninspect.uncertainToolCalls()="
                        + projection.uncertainToolCalls()
                        + "（结果未知的工具调用，恢复前应核对业务系统）");

        session.close();
        agent.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：interrupt 是协作式检查点停止；resume 从 checkpoint 继续；");
        System.out.println("线程/网络/工具内部进度不可恢复，外部副作用也不会回滚。");
        System.out.println("=".repeat(60));
    }

    /** 模拟长任务的工具：每步 500ms，共 10 步。 */
    public static class SlowProcessTools {

        @Tool(name = "process_dataset", description = "处理数据集（模拟耗时操作）")
        public String processDataset(
                @ToolParam(name = "dataset_name", description = "数据集名称") String datasetName,
                @ToolParam(name = "operation", description = "处理操作") String operation,
                ToolEmitter toolEmitter) {
            System.out.println("[Tool] 开始处理 " + datasetName + "（操作: " + operation + "）");
            for (int i = 1; i <= 10; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "处理被中断";
                }
                toolEmitter.emit(ToolResultBlock.text("处理进度 " + (i * 10) + "%"));
            }
            return "数据集 '" + datasetName + "' 处理完成，操作: " + operation;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "(null)";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
