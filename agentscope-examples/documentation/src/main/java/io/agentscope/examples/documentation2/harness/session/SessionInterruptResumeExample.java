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

import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Disposable;

/**
 * SessionInterruptResumeExample — 中断与继续（interrupt / resume 等价）。
 *
 * <p><b>概念映射（官方文档 → 当前真实 API）</b>：
 * <ul>
 *   <li>{@code session.interrupt(runId)} ≈ {@code run.interrupt(msg)}：按句柄协作中断，
 *       在检查点停止，方法返回不代表已停下，要等 {@code run.termination()}。</li>
 *   <li>{@code session.interrupt()}（跨请求）≈ {@code agent.interrupt(userId, sessionId, msg)}：
 *       不依赖句柄，按会话身份定位正在执行的调用。</li>
 *   <li>{@code session.resume(turnId)} ≈ 同一 {@code (userId, sessionId)} 创建新 run：
 *       已持久化的会话上下文让 Agent 接着干；业务无需重发历史消息。</li>
 *   <li>{@code session.steer(task, msg)} ≈ 协作中断旧 run + 用新要求发起新 run；
 *       {@code session.inject(msg)} ≈ 同一会话追加一条消息（进入上下文，不单独调度）。</li>
 * </ul>
 *
 * <p><b>执行流程</b>：
 * <ol>
 *   <li>Agent 启动一个长任务（工具每步 500ms，共 10 步 ≈ 5 秒）；</li>
 *   <li>约 2 秒后调用 {@code run.interrupt()}，观察终态与 {@code GenerateReason.INTERRUPTED}；</li>
 *   <li>同一会话发起新 run（resume 等价），要求继续完成；</li>
 *   <li>再演示一次 {@code agent.interrupt(userId, sessionId, msg)} 的跨请求中断。</li>
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
        System.out.println("会话操作 — 中断与继续（interrupt / resume 等价）");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-session-interrupt");
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SlowProcessTools());

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("数据处理助手")
                        .agentId("data-assistant")
                        .sysPrompt("你是一个数据处理助手。用户要求处理数据时，调用 process_dataset " + "工具，处理完成后汇报结果。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .stateStore(stateStore)
                        .toolkit(toolkit)
                        .maxIters(10)
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().userId("alice").sessionId("job-001").build();

        // ── 场景一：按句柄中断（session.interrupt(runId) 等价）────────────────

        System.out.println("── 场景一：run.interrupt() 协作中断 ────────────────────\n");

        AgentRun<Msg> run =
                agent.prepareCall(
                        List.of(new UserMessage("请处理 customer_data 数据集，执行 analyze 操作。")), ctx);
        System.out.println("已提交任务，runId=" + run.runId() + "\n");

        AtomicReference<Msg> interruptedReply = new AtomicReference<>();
        Disposable execution =
                run.stream()
                        .single()
                        .subscribe(
                                interruptedReply::set,
                                err -> System.err.println("[执行错误] " + err.getMessage()));

        // 让 Agent 先跑一会儿（工具开始执行）
        Thread.sleep(2000);
        System.out.println(">>> 用户请求停止（按 runId 中断）<<<\n");

        boolean interruptSent = run.interrupt(new UserMessage("用户取消了本次操作"));
        System.out.println("interrupt 已发出: " + interruptSent + "（等待协作检查点生效…）");

        // 等待终态：interrupt 是协作式，方法返回不代表已停下
        AgentRun.Status terminal = run.termination().block(Duration.ofSeconds(60));
        System.out.println("中断执行终态: " + terminal);

        Msg stopped = interruptedReply.get();
        if (stopped != null) {
            System.out.println("中断回复 reason=" + stopped.getGenerateReason());
            System.out.println("中断回复内容: " + truncate(stopped.getTextContent(), 80));
        }

        // ── 场景一续：同一会话新 run（resume 等价）───────────────────────────

        System.out.println("\n── 继续原任务（resume 等价：同一会话发起新 run）────────\n");

        AgentRun<Msg> resumed = agent.prepareCall(List.of(new UserMessage("继续完成刚才的数据处理任务。")), ctx);
        Msg resumedReply = resumed.stream().single().block(Duration.ofSeconds(120));
        System.out.println("继续执行终态: " + resumed.status());
        System.out.println(
                "继续后回复: "
                        + (resumedReply != null
                                ? truncate(resumedReply.getTextContent(), 80)
                                : "(null)"));

        execution.dispose();

        // ── 场景二：跨请求中断（session.interrupt() 等价，不需要句柄）─────────

        System.out.println("\n── 场景二：agent.interrupt(userId, sessionId) 跨请求中断 ──\n");

        AgentRun<Msg> run2 =
                agent.prepareCall(
                        List.of(new UserMessage("请处理 report_data 数据集，执行 analyze 操作。")), ctx);
        System.out.println("第二个任务已提交，runId=" + run2.runId() + "\n");

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Msg> run2Reply = new AtomicReference<>();
        Disposable execution2 =
                run2.stream()
                        .single()
                        .subscribe(
                                m -> {
                                    run2Reply.set(m);
                                    done.countDown();
                                },
                                err -> {
                                    System.err.println("[执行错误] " + err.getMessage());
                                    done.countDown();
                                });

        Thread.sleep(1500);
        System.out.println(">>> 另一条请求链路按 (userId, sessionId) 发起中断 <<<\n");
        agent.interrupt(ctx.getUserId(), ctx.getSessionId(), new UserMessage("新需求：改用 summary 操作。"));

        // 等待场景二结束（最多 90 秒）
        done.await(90, TimeUnit.SECONDS);
        System.out.println("中断后的终态: " + run2.status());
        Msg r2 = run2Reply.get();
        if (r2 != null) {
            System.out.println("回复 reason=" + r2.getGenerateReason());
            System.out.println("回复内容: " + truncate(r2.getTextContent(), 80));
        }

        execution2.dispose();
        agent.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：interrupt 是协作式检查点停止；resume 靠同一会话的");
        System.out.println("持久化上下文 + 新 run 实现；线程/网络/工具内部进度不可恢复。");
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
