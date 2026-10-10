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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * SessionStateRecoveryExample — 会话持久化与服务重启恢复（checkpoint / 存储 等价）。
 *
 * <p><b>概念映射（官方文档 → 当前真实 API）</b>：
 * <ul>
 *   <li>{@code checkpoint} ≈ {@code JsonFileAgentStateStore} 按 {@code (userId, sessionId)}
 *       保存的会话工作状态（历史消息等），恢复时让 Agent 接着推理；</li>
 *   <li>{@code 服务重启后恢复} ≈ 用相同 {@code agentId} / {@code userId} / {@code sessionId}
 *       + 相同 stateStore 重建 Agent 实例，再发起调用即可继续原会话——</li>
 *   <li><b>不会</b>恢复原 Java 线程、网络请求、工具内部进度（本示例用"关闭实例重建"
 *       模拟服务重启，验证的是已提交状态可恢复）。</li>
 * </ul>
 *
 * <p><b>执行流程</b>：
 * <ol>
 *   <li>agent-1 在会话 {@code conversation-001} 中与用户建立上下文（记住用户名字）；</li>
 *   <li>关闭 agent-1（模拟进程退出）；</li>
 *   <li>用相同身份 + 相同 stateStore 重建 agent-2，同一会话继续提问，验证上下文还在；</li>
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
        System.out.println("会话操作 — 持久化与重启恢复（checkpoint / 存储 等价）");
        System.out.println("=".repeat(60) + "\n");

        Path stateDir = Files.createTempDirectory("agentscope-session-state");
        Path workspace = Files.createTempDirectory("agentscope-session-workspace");
        AgentStateStore stateStore = new JsonFileAgentStateStore(stateDir);

        // 固定身份：agentId / userId / sessionId 三者一致是恢复的前提
        String agentId = "support-agent";
        RuntimeContext ctx =
                RuntimeContext.builder().userId("alice").sessionId("conversation-001").build();

        // ── 1. 第一次运行：建立会话上下文 ────────────────────────────────────

        System.out.println("── 1. 第一次运行（agent-1）：建立会话上下文 ─────────────\n");

        HarnessAgent agent1 = buildAgent(agentId, workspace, stateStore);
        Msg first =
                agent1.call(new UserMessage("记住我的名字叫 Alice，我喜欢 Java 和 Agent 开发。"), ctx)
                        .block(Duration.ofSeconds(120));
        System.out.println("agent-1 回复: " + (first != null ? first.getTextContent() : "(null)"));
        agent1.close(); // 模拟服务进程退出

        System.out.println("\n>>> agent-1 已关闭（模拟服务重启）<<<\n");

        // ── 2. 重启后：相同身份恢复，验证上下文仍在 ───────────────────────────

        System.out.println("── 2. 重启后（agent-2）：相同身份恢复会话 ───────────────\n");

        HarnessAgent agent2 = buildAgent(agentId, workspace, stateStore);

        // 从存储中确认已持久化的状态（transcript / checkpoint 等价）
        Optional<AgentState> saved =
                stateStore.get("alice", "conversation-001", "agent_state", AgentState.class);
        System.out.println("存储中的会话消息数: " + saved.map(s -> s.getContext().size()).orElse(0));

        Msg resumed =
                agent2.call(new UserMessage("我叫什么名字？我最近在学什么？"), ctx).block(Duration.ofSeconds(120));
        System.out.println(
                "agent-2 回复: " + (resumed != null ? resumed.getTextContent() : "(null)"));
        System.out.println("\n（agent-2 记得之前的对话，说明已提交状态成功恢复）\n");

        // ── 3. 新会话隔离：不同 sessionId 看不到旧历史 ────────────────────────

        System.out.println("── 3. 新会话隔离：不同 sessionId 无历史 ─────────────────\n");

        RuntimeContext freshCtx =
                RuntimeContext.builder().userId("alice").sessionId("brand-new-session").build();

        Msg fresh =
                agent2.call(new UserMessage("我叫什么名字？"), freshCtx).block(Duration.ofSeconds(120));
        System.out.println("新会话回复: " + (fresh != null ? fresh.getTextContent() : "(null)"));
        System.out.println("\n（新会话拿不到旧历史，说明会话状态按身份隔离）");

        agent2.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：恢复靠已持久化的会话状态（checkpoint）；");
        System.out.println("线程/网络/工具内部进度不会恢复，外部副作用也不会回滚。");
        System.out.println("=".repeat(60));
    }

    private static HarnessAgent buildAgent(
            String agentId, Path workspace, AgentStateStore stateStore) {
        return HarnessAgent.builder()
                .name("支持助手")
                .agentId(agentId)
                .sysPrompt("你是一个耐心的支持助手，回答尽量简洁。")
                .model("dashscope:qwen-plus")
                .workspace(workspace)
                .stateStore(stateStore)
                .build();
    }
}
