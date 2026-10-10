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
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * SessionHITLExample — 回答 Agent 的待办请求（pending / respond 等价）。
 *
 * <p>官方文档中 {@code session.pending()} / {@code session.respond(requestId, answer)} 描述的
 * 会话级 HITL 尚未落地；当前版本通过<b>权限引擎</b>实现同一套"挂起 → 用户答复 → 恢复"的机制：
 *
 * <ol>
 *   <li>Agent 执行命中 {@code ASK} 权限规则的工具时，<b>暂停</b>并返回
 *       {@code GenerateReason.PERMISSION_ASKING}；</li>
 *   <li>应用从返回的 {@link Msg} 中取出 {@code state == ASKING} 的 {@link ToolUseBlock}
 *       （等价于 {@code session.pending()}）；</li>
 *   <li>用户给出同意/拒绝（等价于 {@code session.respond(requestId, true/false)}）；</li>
 *   <li>构造 {@link ConfirmResult} 元数据再次调用，Agent 在同一会话上下文中<b>恢复执行</b>。</li>
 * </ol>
 *
 * <p>交互模式下需要人工在控制台输入 yes/no；传 {@code --headless} 走
 * {@link PermissionMode#DONT_ASK}（自动拒绝）便于 CI 运行。
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.session.SessionHITLExample
 * </pre>
 */
public class SessionHITLExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("会话操作 — 回答待办（pending / respond 等价）");
        System.out.println("=".repeat(60) + "\n");

        boolean headless = args.length > 0 && "--headless".equalsIgnoreCase(args[0]);

        Path workspace = Files.createTempDirectory("agentscope-session-hitl");
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new GuardedTools());

        // 权限上下文：safe_read 自动放行；dangerous_delete 需要人工确认
        PermissionContextState permCtx =
                PermissionContextState.builder()
                        .mode(headless ? PermissionMode.DONT_ASK : PermissionMode.DEFAULT)
                        .addAllowRule(
                                "safe_read",
                                new PermissionRule(
                                        "safe_read", null, PermissionBehavior.ALLOW, "policy"))
                        .addAskRule(
                                "dangerous_delete",
                                new PermissionRule(
                                        "dangerous_delete", null, PermissionBehavior.ASK, "policy"))
                        .build();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("文件助手")
                        .agentId("file-assistant")
                        .sysPrompt("你是一个文件助手，拥有 safe_read 和 dangerous_delete 两个工具。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .stateStore(stateStore)
                        .toolkit(toolkit)
                        .permissionContext(permCtx)
                        .build();

        RuntimeContext ctx =
                RuntimeContext.builder().userId("alice").sessionId("file-session-001").build();

        System.out.println(
                headless ? "Headless 模式（DONT_ASK）：确认请求自动拒绝。\n" : "交互模式（DEFAULT）：删除文件前会要求你确认。\n");

        // ── 提交一个会触发确认的任务 ─────────────────────────────────────────

        AgentRun<Msg> first =
                agent.prepareCall(List.of(new UserMessage("请删除 /tmp/important.txt 这个文件。")), ctx);
        Msg result = first.stream().single().block(Duration.ofSeconds(120));
        printResult(result);

        // ── 判断是否挂起等待确认（等价于 session.pending()）───────────────────

        if (result != null && result.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
            System.out.println("\n[HITL] Agent 挂起，等待用户确认以下操作：");

            List<ToolUseBlock> askingTools =
                    result.getContent().stream()
                            .filter(b -> b instanceof ToolUseBlock)
                            .map(ToolUseBlock.class::cast)
                            .filter(t -> t.getState() == ToolCallState.ASKING)
                            .toList();
            askingTools.forEach(t -> System.out.println("  - " + t.getName() + " " + t.getInput()));

            // ── 收集用户答复（等价于 session.respond）────────────────────────
            boolean approved;
            if (headless) {
                approved = false;
                System.out.println("Headless：自动拒绝。");
            } else {
                System.out.print("[HITL] 允许吗？(yes/no): ");
                String decision = new Scanner(System.in).nextLine().trim();
                approved = "yes".equalsIgnoreCase(decision) || "y".equalsIgnoreCase(decision);
            }

            // 构造 ConfirmResult 元数据，在同一会话中恢复执行
            List<ConfirmResult> confirmResults =
                    askingTools.stream().map(t -> new ConfirmResult(approved, t)).toList();
            Map<String, Object> meta = new HashMap<>();
            meta.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
            Msg resumeMsg =
                    Msg.builder()
                            .name("user")
                            .role(MsgRole.USER)
                            .textContent(approved ? "approved" : "denied")
                            .metadata(meta)
                            .build();

            System.out.println("\n── 提交答复，Agent 在同一会话中恢复执行 ──\n");

            AgentRun<Msg> resumed = agent.prepareCall(List.of(resumeMsg), ctx);
            Msg finalResult = resumed.stream().single().block(Duration.ofSeconds(120));
            printResult(finalResult);

            System.out.println("\n恢复后会话上下文消息数: " + agent.getAgentState().getContext().size());
        } else {
            System.out.println("\n（本次执行未触发确认请求）");
        }

        agent.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：挂起发生在工具确认位置；答复后在同一会话恢复执行。");
        System.out.println("=".repeat(60));
    }

    private static void printResult(Msg msg) {
        if (msg == null) {
            System.out.println("(无回复)");
            return;
        }
        System.out.println("Reason: " + msg.getGenerateReason());
        System.out.println("Agent:  " + truncate(msg.getTextContent(), 100));
    }

    /** 一个安全工具 + 一个危险工具。 */
    public static class GuardedTools {

        @Tool(name = "safe_read", description = "读取文件（安全，自动放行）")
        public String safeRead(@ToolParam(name = "path", description = "文件路径") String path) {
            return "文件内容: [模拟数据]";
        }

        @Tool(name = "dangerous_delete", description = "永久删除文件（需要确认）")
        public String dangerousDelete(
                @ToolParam(name = "path", description = "要删除的文件路径") String path) {
            return "已删除: " + path;
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
