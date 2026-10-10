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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Disposable;

/**
 * SessionRunBasicsExample — 会话 / 回合 / 执行（session / turn / run）心智模型，
 * 以及"提交任务后台执行 + 等待结果 + 读取已保存消息"的最小演示。
 *
 * <p><b>概念映射（官方文档 → 当前真实 API）</b>：
 * <ul>
 *   <li>{@code session.submit(requestId, input)} ≈ {@code agent.prepareCall(msgs, ctx)}：
 *       提交一次执行，返回 run 句柄（回执）。句柄先创建、订阅后才真正执行。</li>
 *   <li>{@code session.await(task)} ≈ {@code run.termination()}（等待终态）
 *       / {@code run.stream().single()}（取最终回复）。</li>
 *   <li>{@code session.transcript()} ≈ {@code agent.getAgentState().getContext()}
 *       （当前会话已保存的消息）。</li>
 *   <li>同一个 session 提交多个任务 = 多个 run；run 之间相互独立，但共享会话上下文
 *       —— 对应文档中的 turn / run 关系。</li>
 * </ul>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.session.SessionRunBasicsExample
 * </pre>
 */
public class SessionRunBasicsExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("会话操作基础 — session / turn / run 心智模型");
        System.out.println("=".repeat(60) + "\n");

        // ── 构建 Agent（工作区与状态存储使用临时目录，避免污染仓库）──────────

        Path workspace = Files.createTempDirectory("agentscope-session-basics");
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("方案助手")
                        .agentId("proposal-assistant")
                        .sysPrompt("你是一个帮助用户比较方案的助手，回答尽量简洁。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .stateStore(stateStore)
                        .build();

        // 用户 alice 在会话 conversation-001 中持续对话
        RuntimeContext ctx =
                RuntimeContext.builder().userId("alice").sessionId("conversation-001").build();

        // ── 1. 提交第一个任务：prepareCall 只创建句柄，订阅后才执行 ──────────

        System.out.println("── 1. 提交任务（submit 等价）──────────────────────────\n");

        AgentRun<Msg> run =
                agent.prepareCall(List.of(new UserMessage("比较方案 A 和方案 B，先给出初步结论。")), ctx);

        String runId = run.runId(); // 回执：runId 唯一标识这一次执行
        System.out.println("已提交任务，runId=" + runId + "，初始状态=" + run.status());
        System.out.println("（prepareCall 只创建句柄，不会启动执行）\n");

        // 订阅即执行（await 等价）
        Msg reply = run.stream().single().block(Duration.ofSeconds(120));
        System.out.println("执行完成，终态=" + run.status());
        System.out.println("回复: " + (reply != null ? reply.getTextContent() : "(null)"));

        // ── 2. 同一会话提交第二个任务：新 run，共享会话上下文 ─────────────────

        System.out.println("\n── 2. 同一会话第二个任务（新 turn，新 run）────────────\n");

        AgentRun<Msg> run2 =
                agent.prepareCall(List.of(new UserMessage("基于刚才的结论，起草一封给领导的邮件。")), ctx);
        System.out.println("第二个任务 runId=" + run2.runId() + "（与第一次不同）");
        System.out.println("但两者属于同一会话 conversation-001，共享上下文\n");

        Msg reply2 = run2.stream().single().block(Duration.ofSeconds(120));
        System.out.println("回复: " + (reply2 != null ? reply2.getTextContent() : "(null)"));

        // ── 3. 读取该会话已保存的消息（transcript 等价）──────────────────────

        System.out.println("\n── 3. 读取会话历史（transcript 等价）──────────────────\n");
        System.out.println("当前会话上下文消息数: " + agent.getAgentState().getContext().size());
        agent.getAgentState()
                .getContext()
                .forEach(
                        m ->
                                System.out.println(
                                        "  ["
                                                + m.getRole()
                                                + "] "
                                                + truncate(m.getTextContent(), 70)));

        // ── 4. 事件流方式：prepareRun 返回 AgentEvent 流，可实时观察执行 ──────

        System.out.println("\n── 4. 事件流执行（streamEvents 等价，观察 AgentEvent）───\n");

        AgentRun<AgentEvent> eventRun =
                agent.prepareRun(List.of(new UserMessage("用一句话介绍你自己。")), ctx);
        AtomicReference<Msg> streamingResult = new AtomicReference<>();

        Disposable subscription =
                eventRun.stream()
                        .filter(e -> e instanceof AgentResultEvent)
                        .cast(AgentResultEvent.class)
                        .map(AgentResultEvent::getResult)
                        .subscribe(streamingResult::set, err -> System.err.println("流错误: " + err));

        // 等待终态（等价于 await）
        eventRun.termination().block(Duration.ofSeconds(120));
        System.out.println("事件流执行完成，终态=" + eventRun.status());
        System.out.println(
                "事件流最终回复: "
                        + (streamingResult.get() != null
                                ? truncate(streamingResult.get().getTextContent(), 70)
                                : "(null)"));

        subscription.dispose();
        agent.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：同一会话 = 一段对话；每个任务 = 一个 run；");
        System.out.println("run 之间共享持久化上下文，但执行相互独立。");
        System.out.println("=".repeat(60));
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "(null)";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
