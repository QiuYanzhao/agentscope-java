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
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * SessionStateRecoveryExample — 会话持久化与服务重启恢复（checkpoint / 存储）。
 *
 * <p>会话日志默认保存在 Workspace 的 Filesystem（{@code WorkspaceSessionLogStore}）：
 * 只要 <b>agentId / userId / sessionId 与存储位置一致</b>，重建 Agent 实例后就能找回原会话。
 *
 * <p><b>演示内容</b>：
 * <ol>
 *   <li>agent-1 在会话 {@code conversation-001} 中建立上下文（让 Agent 记住用户名字）；</li>
 *   <li>关闭 agent-1（模拟服务重启，已提交日志与队列由持久后端保留）；</li>
 *   <li>用相同身份 + 相同 workspace 重建 agent-2，{@code session.transcript()} 恢复已保存
 *       消息，{@code session.start()} 重新开启调度；</li>
 *   <li>同一会话继续提交新任务，验证上下文仍在（checkpoint 恢复的是已提交工作状态——
 *       原 Java 线程、网络请求、工具内部进度不会被恢复）；</li>
 *   <li>对比：新 sessionId 提问 → 无历史（会话隔离）。</li>
 * </ol>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.session.SessionStateRecoveryExample
 * </pre>
 */
public class SessionStateRecoveryExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("会话操作 — 持久化与重启恢复（checkpoint / 存储）");
        System.out.println("=".repeat(60) + "\n");

        // 关键：agent-1 和 agent-2 共用同一个 workspace 目录（固定目录便于反复验证重启恢复）
        Path workspace =
                Files.createDirectories(
                        Path.of(
                                "/Users/qyz/myFiles/Temp/AgentScopeProduct/session-state-recovery"));

        // 固定身份：agentId / userId / sessionId 三者一致是恢复的前提
        String agentId = "support-agent";
        RuntimeContext ctx =
                RuntimeContext.builder().userId("alice").sessionId("conversation-001").build();

        // ── 1. 第一次运行：建立会话上下文 ────────────────────────────────────

        System.out.println("── 1. 第一次运行（agent-1）：建立会话上下文 ─────────────\n");

        HarnessAgent agent1 = buildAgent(agentId, workspace);
        AgentSession session1 = agent1.session(ctx);

        AgentSession.Task first = session1.submit("记住我的名字叫 Alice，我喜欢 Java 和 Agent 开发。");
        AgentSession.Task firstDone = session1.await(first).block(Duration.ofSeconds(180));
        System.out.println("第一个任务终态: " + (firstDone != null ? firstDone.status() : "(await 超时)"));
        session1.transcript()
                .messages()
                .forEach(
                        m ->
                                System.out.println(
                                        "  ["
                                                + m.getRole()
                                                + "] "
                                                + truncate(m.getTextContent(), 70)));

        agent1.close(); // 模拟服务进程退出

        System.out.println("\n>>> agent-1 已关闭（模拟服务重启）<<<\n");

        // ── 2. 重启后：相同身份恢复，验证上下文仍在 ───────────────────────────

        System.out.println("── 2. 重启后（agent-2）：相同身份恢复会话 ───────────────\n");

        HarnessAgent agent2 = buildAgent(agentId, workspace);
        AgentSession session2 = agent2.session(ctx);

        // 从 Session Log 恢复已保存消息（transcript）
        var transcript = session2.transcript();
        System.out.println(
                "恢复出的消息数=" + transcript.messages().size() + "，asOfSeq=" + transcript.asOfSeq());
        transcript
                .messages()
                .forEach(
                        m ->
                                System.out.println(
                                        "  ["
                                                + m.getRole()
                                                + "] "
                                                + truncate(m.getTextContent(), 70)));

        // 应用启动时重新开启已接收任务的调度
        session2.start();

        // 同一会话继续提问，验证上下文延续
        AgentSession.Task follow = session2.submit("我叫什么名字？我最近在学什么？");
        AgentSession.Task followDone = session2.await(follow).block(Duration.ofSeconds(180));
        System.out.println("继续对话终态: " + (followDone != null ? followDone.status() : "(await 超时)"));
        session2.transcript()
                .messages()
                .forEach(
                        m ->
                                System.out.println(
                                        "  ["
                                                + m.getRole()
                                                + "] "
                                                + truncate(m.getTextContent(), 70)));
        System.out.println("\n（agent-2 记得之前的对话，说明已提交状态成功恢复）\n");

        // ── 3. 新会话隔离：不同 sessionId 无历史 ─────────────────────────────

        System.out.println("── 3. 新会话隔离：不同 sessionId 无历史 ─────────────────\n");

        RuntimeContext freshCtx =
                RuntimeContext.builder().userId("alice").sessionId("brand-new-session").build();
        AgentSession freshSession = agent2.session(freshCtx);
        System.out.println("新会话恢复出的消息数=" + freshSession.transcript().messages().size());
        System.out.println("（新会话拿不到旧历史，说明会话状态按身份隔离）");

        freshSession.close();
        session2.close();
        agent2.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：恢复靠已持久化的会话状态（checkpoint）；");
        System.out.println("线程/网络/工具内部进度不会恢复，外部副作用也不会回滚。");
        System.out.println("=".repeat(60));
    }

    private static HarnessAgent buildAgent(String agentId, Path workspace) {
        return HarnessAgent.builder()
                .name("支持助手")
                .agentId(agentId)
                .sysPrompt("你是一个耐心的支持助手，回答尽量简洁。")
                .model("dashscope:qwen-plus")
                .workspace(workspace)
                .build();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "(null)";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
