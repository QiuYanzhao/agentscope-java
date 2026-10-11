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
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import io.agentscope.harness.agent.session.SessionAnswer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Scanner;

/**
 * SessionHITLExample — 回答 Agent 的待办请求（pending / respond）。
 *
 * <p>Agent 执行命中 {@code ASK} 权限规则的工具时，任务<b>挂起</b>等待用户答复；问题保存在
 * Session Log 里，刷新页面甚至服务重启后仍能读取。
 *
 * <p><b>演示内容</b>：
 * <ol>
 *   <li>{@code session.submit(...)} 提交一个需要确认的任务；</li>
 *   <li>{@code session.await(task)} 观察到 {@code suspended}（Agent 正在等用户答复，
 *       不代表任务完成）；</li>
 *   <li>{@code session.pending()} 读取尚未答复的待办，key 是 {@code requestId}——页面应把
 *       requestId 与问题一起保存，回答时原样带回；</li>
 *   <li>{@code session.respond(requestId, true/false)} 提交答复——框架自动找到待办所属的
 *       原任务和工具调用并安排接续执行，<b>不需要再手动 resume</b>；</li>
 *   <li>拒绝时可用 {@code SessionAnswer.reject(reason)} 附带原因。</li>
 * </ol>
 *
 * <p>传 {@code --headless} 走 {@link PermissionMode#DONT_ASK}（确认请求自动拒绝）便于 CI 运行。
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
        System.out.println("会话操作 — 回答待办（pending / respond）");
        System.out.println("=".repeat(60) + "\n");

        boolean headless = args.length > 0 && "--headless".equalsIgnoreCase(args[0]);

        Path workspace =
                Files.createDirectories(
                        Path.of("/Users/qyz/myFiles/Temp/AgentScopeProduct/session-hitl"));

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
                        .toolkit(toolkit)
                        .permissionContext(permCtx)
                        .build();

        RuntimeContext ctx =
                RuntimeContext.builder().userId("alice").sessionId("file-session-001").build();

        AgentSession session = agent.session(ctx);

        System.out.println(
                headless ? "Headless 模式（DONT_ASK）：确认请求自动拒绝。\n" : "交互模式（DEFAULT）：删除文件前会要求你确认。\n");

        // ── 1. 提交一个会触发确认的任务 ──────────────────────────────────────

        System.out.println("── 1. submit：提交需要确认的任务 ───────────────────────\n");

        AgentSession.Task task = session.submit("请删除 /tmp/important.txt 这个文件。");
        System.out.println("turnId=" + task.turnId() + "\n");

        AgentSession.Task outcome = session.await(task).block(Duration.ofSeconds(180));
        System.out.println("任务状态: " + (outcome != null ? outcome.status() : "(await 超时)"));

        // ── 2. 读取待办（pending 等价）──────────────────────────────────────

        Map<String, SessionEvent> pending = session.pending();
        if (pending.isEmpty()) {
            System.out.println("\n（没有待办——headless 模式下 ASK 自动降级为拒绝）");
        } else {
            System.out.println("\n── 2. pending()：读取尚未答复的待办 ───────────────────\n");

            for (Map.Entry<String, SessionEvent> entry : pending.entrySet()) {
                String requestId = entry.getKey();
                SessionEvent request = entry.getValue();
                System.out.println("requestId=" + requestId);
                System.out.println("kind      =" + request.data().get("kind"));
                System.out.println("payload   =" + request.data().get("call"));
            }

            // ── 3. 收集用户答复（respond 等价）───────────────────────────────

            String requestId = pending.keySet().iterator().next();
            boolean approved;
            if (headless) {
                approved = false;
                System.out.println("Headless：自动拒绝。");
            } else {
                System.out.print("\n[HITL] 允许执行吗？(yes/no): ");
                String decision = new Scanner(System.in).nextLine().trim();
                approved = "yes".equalsIgnoreCase(decision) || "y".equalsIgnoreCase(decision);
            }

            System.out.println("\n── 3. respond()：提交答复，自动安排接续执行 ───────────\n");

            AgentSession.Task answer =
                    approved
                            ? session.respond(requestId, true)
                            : session.respond(requestId, SessionAnswer.reject("用户拒绝执行"));
            System.out.println(
                    "respond 已接受，turn="
                            + answer.turnId()
                            + "，runId="
                            + answer.runId()
                            + "（不需要再手动 resume）\n");

            AgentSession.Task finalState = session.await(answer).block(Duration.ofSeconds(180));
            System.out.println(
                    "接续执行终态: " + (finalState != null ? finalState.status() : "(await 超时)"));

            // ── 4. 查看最终历史 ──────────────────────────────────────────────

            System.out.println("\n── 4. transcript()：最终会话历史 ─────────────────────\n");
            session.transcript()
                    .messages()
                    .forEach(
                            m ->
                                    System.out.println(
                                            "  ["
                                                    + m.getRole()
                                                    + "] "
                                                    + truncate(m.getTextContent(), 80)));
        }

        session.close();
        agent.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("要点：挂起发生在工具确认位置；respond 自动找到原任务与工具调用；");
        System.out.println("待办与答复都持久化在 Session Log 中，刷新/重启后仍可恢复。");
        System.out.println("=".repeat(60));
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
