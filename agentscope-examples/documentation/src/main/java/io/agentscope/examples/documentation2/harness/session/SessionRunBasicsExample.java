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
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionViews;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * SessionRunBasicsExample — 会话 / 回合 / 执行（session / turn / run）心智模型，
 * 以及"提交任务后台执行 + 等待结果 + 读取已保存消息与事件"的基础演示。
 *
 * <p><b>演示内容</b>：
 * <ol>
 *   <li>{@code agent.session(ctx)} 取得会话操作入口；</li>
 *   <li>{@code session.submit(idempotencyKey, input)} 提交后台任务（先持久保存输入，
 *       再调度执行），返回回执 {@link AgentSession.Task}——排队中 {@code runId} 可能未分配；</li>
 *   <li>{@code session.await(task)} 等待执行到达终态（completed / suspended / interrupted
 *       / failed 等；{@code suspended} 表示 Agent 正在等用户答复，不等于任务完成）；</li>
 *   <li>同一会话提交第二个任务 = 新 turn / 新 run，但共享会话上下文；</li>
 *   <li>{@code session.transcript()} 读取已保存的消息；</li>
 *   <li>{@code session.log()} 按 {@code seq} 扫描已提交事件，{@code eventId} 用于去重。</li>
 * </ol>
 *
 * <p>幂等：网络超时重试时沿用相同 {@code idempotencyKey} + 相同输入，会得到同一次提交的
 * 回执；用户主动发起新任务时应使用新标识。
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

        // 工作区使用固定目录，会话日志默认跟随 Workspace 的 Filesystem 持久化
        Path workspace =
                Files.createDirectories(
                        Path.of("/Users/qyz/myFiles/Temp/AgentScopeProduct/session-run-basics"));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("方案助手")
                        .agentId("proposal-assistant")
                        .sysPrompt("你是一个帮助用户比较方案的助手，回答尽量简洁。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .build();

        // 用户 alice 在会话 conversation-001 中持续对话
        RuntimeContext ctx =
                RuntimeContext.builder().userId("alice").sessionId("conversation-001").build();

        AgentSession session = agent.session(ctx);

        // ── 1. 提交任务（submit）─────────────────────────────────────────────

        System.out.println("── 1. submit：提交后台任务，返回回执 ───────────────────\n");

        AgentSession.Task task = session.submit("request-001", "比较方案 A 和方案 B，先给出初步结论。");
        System.out.println("commandId=" + task.commandId());
        System.out.println("turnId   =" + task.turnId());
        System.out.println("runId    =" + task.runId() + "（排队中可能尚未分配）");
        System.out.println("status   =" + task.status());
        System.out.println("（submit 先持久保存输入再调度执行，方法返回不代表执行完成）\n");

        // ── 2. await：等待执行到达终态 ──────────────────────────────────────

        System.out.println("── 2. await：等待执行到达终态 ──────────────────────────\n");

        AgentSession.Task done = session.await(task).block(Duration.ofSeconds(180));
        System.out.println("终态: " + (done != null ? done.status() : "(await 超时)"));
        if (done != null && done.runId() != null) {
            System.out.println("执行 runId: " + done.runId());
        }

        // ── 3. 同一会话第二个任务：新 turn / 新 run ──────────────────────────

        System.out.println("\n── 3. 同一会话第二个任务（新 turn，新 run）──────────────\n");

        AgentSession.Task task2 = session.submit("基于刚才的结论，起草一封给领导的邮件。");
        System.out.println("第二个任务 turnId=" + task2.turnId() + "（与第一次不同）");
        System.out.println("但两者属于同一会话 conversation-001，共享上下文\n");

        AgentSession.Task done2 = session.await(task2).block(Duration.ofSeconds(180));
        System.out.println("第二个任务终态: " + (done2 != null ? done2.status() : "(await 超时)"));

        // ── 4. tasks()：查看会话内全部任务 ───────────────────────────────────

        System.out.println("\n── 4. tasks()：会话内全部任务 ──────────────────────────\n");
        for (AgentSession.Task t : session.tasks()) {
            System.out.println("  turn=" + t.turnId() + " status=" + t.status());
        }

        // ── 5. transcript()：读取已保存的消息 ────────────────────────────────

        System.out.println("\n── 5. transcript()：读取会话历史 ───────────────────────\n");

        SessionViews.Transcript transcript = session.transcript();
        System.out.println(
                "asOfSeq=" + transcript.asOfSeq() + "，消息数=" + transcript.messages().size());
        transcript
                .messages()
                .forEach(
                        msg ->
                                System.out.println(
                                        "  ["
                                                + msg.getRole()
                                                + "] "
                                                + truncate(msg.getTextContent(), 70)));

        // ── 6. log()：按 seq 扫描已提交事件 ──────────────────────────────────

        System.out.println("\n── 6. log()：按 seq 扫描已提交事件 ─────────────────────\n");

        SessionLog log = session.log();
        long through = log.head().seq();
        int shown = 0;
        for (SessionEvent event : log.scan(0, through)) {
            if (shown++ >= 20) {
                System.out.println("  …（仅展示前 20 条）");
                break;
            }
            System.out.printf(
                    "  seq=%d type=%-22s turn=%-36s run=%s%n",
                    event.seq(), event.type(), safe(event.turnId()), safe(event.executionRunId()));
        }

        session.close();
        agent.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：同一会话 = 一段对话；每个任务 = 一个 turn / run；");
        System.out.println("run 之间共享持久化上下文，但执行相互独立。");
        System.out.println("=".repeat(60));
    }

    private static String safe(String s) {
        return s == null ? "-" : s;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "(null)";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
