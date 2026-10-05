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
package io.agentscope.examples.documentation2.hitl;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

/**
 * StreamingPermissionHITLExample - 基于事件流的人机交互确认示例。
 *
 * <p>它是 {@link PermissionHITLExample} 的流式版本：不再用 {@code agent.call(...)} 阻塞等待后
 * 检查返回结果，而是订阅 {@code agent.streamEvents(...)}，实时响应细粒度 {@link AgentEvent}，
 * 对应 AgentScope 文档「人机交互」章节的流式写法：
 *
 * <ol>
 *   <li>{@link TextBlockDeltaEvent} 到达时实时打印模型流式输出的文本。</li>
 *   <li>{@link ToolCallStartEvent} 到达时打印工具调用开始。</li>
 *   <li>权限引擎命中 {@code ASK} 规则时，智能体暂停并发出 {@link RequireUserConfirmEvent}；
 *       展示待确认的 {@link ToolUseBlock}，逐个询问用户批准 / 拒绝。</li>
 *   <li>构造携带 {@link ConfirmResult} 的恢复消息 ，metadata 中放入
 *       {@code Msg.METADATA_CONFIRM_RESULTS}，再次流式喂给智能体即可从中断点继续；
 *       被批准的工具立即执行，被拒绝的工具以权限错误写回模型上下文。用户还可以选择把
 *       决策固化为 ALLOW 规则，让今后对同一工具的调用自动放行。</li>
 * </ol>
 *
 * <p><b>Headless 模式（{@link PermissionMode#DONT_ASK}）：</b>
 * 在无人值守场景（CI 流水线、自动化测试）下运行，传入 {@code --headless} 参数即可。
 * 此时 ASK 规则自动降级为拒绝，不会发出任何 {@link RequireUserConfirmEvent}。
 *
 * <p><b>运行：</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.hitl.StreamingPermissionHITLExample
 * </pre>
 */
public class StreamingPermissionHITLExample {

    /**
     * 运行流式权限 HITL 示例。
     *
     * @param args 命令行参数（忽略；{@code --headless} 启用 DONT_ASK 模式）
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("流式权限人机交互示例");
        System.out.println("=".repeat(60));
        System.out.println(
                "演示基于事件流的人机交互确认。\n"
                        + "safe_read 自动放行；dangerous_delete 需要确认。\n"
                        + "传入 --headless 以 DONT_ASK 模式运行（自动拒绝确认）。");
        System.out.println("=".repeat(60) + "\n");

        boolean headless = args.length > 0 && "--headless".equalsIgnoreCase(args[0]);

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DangerousTools());

        // ── 构建权限上下文 ────────────────────────────────────────────────────────────
        //
        // - safe_read：始终自动放行（ALLOW 规则，ruleContent 为 null 表示匹配该工具的所有调用）
        // - dangerous_delete：执行前询问用户（ASK 规则）
        // - Headless 模式使用 DONT_ASK：ASK 自动降级为 DENY
        PermissionMode mode = headless ? PermissionMode.DONT_ASK : PermissionMode.DEFAULT;
        PermissionContextState permCtx =
                PermissionContextState.builder()
                        .mode(mode)
                        .addAllowRule(
                                "safe_read",
                                new PermissionRule(
                                        "safe_read", null, PermissionBehavior.ALLOW, "policy"))
                        .addAskRule(
                                "dangerous_delete",
                                new PermissionRule(
                                        "dangerous_delete", null, PermissionBehavior.ASK, "policy"))
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("GuardedAgent")
                        .sysPrompt(
                                "You are a file assistant. You have safe_read and dangerous_delete"
                                        + " tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .permissionContext(permCtx)
                        .build();

        if (headless) {
            System.out.println("以 DONT_ASK 模式运行：确认请求将被自动拒绝。\n");
            runHeadless(agent);
        } else {
            System.out.println("以交互模式运行（DEFAULT）：删除操作将询问你确认。\n");
            runInteractive(agent);
        }
    }

    /**
     * Headless 运行：以 DONT_ASK 模式流式执行一轮。ASK 规则降级为 DENY，因此不会发出
     * {@link RequireUserConfirmEvent}，模型只会看到权限错误。
     *
     * @param agent 要运行的智能体
     */
    private static void runHeadless(ReActAgent agent) {
        AtomicReference<RequireUserConfirmEvent> pending = new AtomicReference<>();
        agent.streamEvents(new UserMessage("user", "Delete the file /tmp/important.txt"))
                .doOnNext(event -> handleEvent(event, pending))
                .blockLast();
        System.out.println("\n\n（DONT_ASK 模式：确认请求已被自动拒绝，未发出" + " RequireUserConfirmEvent。）");
    }

    /**
     * 交互式运行：流式执行每一轮；当智能体因 {@link RequireUserConfirmEvent} 暂停时，询问用户
     * 并通过流式恢复消息继续执行。
     *
     * @param agent 要运行的智能体
     */
    private static void runInteractive(ReActAgent agent) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("你: ");

        while (scanner.hasNextLine()) {
            String userInput = scanner.nextLine().trim();
            if (userInput.isBlank() || "exit".equalsIgnoreCase(userInput)) {
                break;
            }

            // 每次用户输入开启一轮流式执行；若智能体因确认而暂停，streamTurn 返回恢复消息，
            // 循环继续流式执行，直到该轮自然完成（streamTurn 返回 null）。
            Msg next = new UserMessage("user", userInput);
            while (next != null) {
                next = streamTurn(agent, next, scanner);
            }
            System.out.print("\n你: ");
        }
    }

    /**
     * 流式执行智能体的一轮，阻塞直到执行完成或暂停。
     *
     * <p>事件到达即分发：文本增量实时打印、工具调用即时提示，{@link RequireUserConfirmEvent}
     * 被捕获作为暂停标记。
     *
     * @param agent   从中获取事件流的智能体
     * @param input   喂给智能体的消息（用户消息，或携带 {@link ConfirmResult} 的恢复消息）
     * @param scanner 终端输入，用于收集用户的确认决策
     * @return 智能体因确认而暂停时返回恢复消息；该轮自然完成时返回 {@code null}
     */
    private static Msg streamTurn(ReActAgent agent, Msg input, Scanner scanner) {
        AtomicReference<RequireUserConfirmEvent> pending = new AtomicReference<>();
        agent.streamEvents(List.of(input))
                .doOnNext(event -> handleEvent(event, pending))
                .blockLast();

        RequireUserConfirmEvent confirm = pending.get();
        if (confirm == null) {
            return null; // 本轮正常完成，无需确认
        }

        // ── 智能体暂停：展示待确认操作并收集决策 ────────────────────────────────────
        System.out.println("\n[人机交互] 智能体想要执行需要你批准的工具调用。");
        System.out.println("[人机交互] 待确认操作：");

        List<ConfirmResult> confirmResults = new ArrayList<>();
        for (ToolUseBlock call : confirm.getToolCalls()) {
            System.out.println("  - " + call.getName() + " " + call.getInput());
            System.out.print("[人机交互] 是否允许？(yes/no): ");
            String decision = scanner.hasNextLine() ? scanner.nextLine().trim() : "no";
            boolean approved = "yes".equalsIgnoreCase(decision) || "y".equalsIgnoreCase(decision);

            List<PermissionRule> rules = null;
            if (approved) {
                // 可选：把本次决策固化为 ALLOW 规则，今后对同一工具的调用自动放行
                // （对应文档中「接受规则让今后相同调用自动放行」的用法）。
                System.out.print("[人机交互] 记住该决策，今后自动放行？(yes/no): ");
                String remember = scanner.hasNextLine() ? scanner.nextLine().trim() : "no";
                if ("yes".equalsIgnoreCase(remember) || "y".equalsIgnoreCase(remember)) {
                    rules =
                            List.of(
                                    new PermissionRule(
                                            call.getName(),
                                            null,
                                            PermissionBehavior.ALLOW,
                                            "userSettings"));
                }
            }
            confirmResults.add(
                    rules == null
                            ? new ConfirmResult(approved, call)
                            : new ConfirmResult(approved, call, rules));
        }

        // ── 恢复：确认结果随消息 metadata 传回 ───────────────────────────────────────
        String summary =
                confirmResults.stream().allMatch(ConfirmResult::isConfirmed)
                        ? "approved"
                        : "denied";
        return UserMessage.builder()
                .name("user")
                .textContent(summary)
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                .build();
    }

    /**
     * 分发事件：打印流式文本、提示工具调用、捕获确认暂停，并在智能体恢复后回显确认结果。
     *
     * @param event   待处理的事件
     * @param pending 若收到 {@link RequireUserConfirmEvent} 则保存到该引用
     */
    private static void handleEvent(
            AgentEvent event, AtomicReference<RequireUserConfirmEvent> pending) {
        if (event instanceof TextBlockDeltaEvent e) {
            System.out.print(e.getDelta());
        } else if (event instanceof ToolCallStartEvent e) {
            System.out.printf("%n[工具] %s%n", e.getToolCallName());
        } else if (event instanceof RequireUserConfirmEvent e) {
            pending.set(e);
        } else if (event instanceof UserConfirmResultEvent e) {
            e.getConfirmResults()
                    .forEach(
                            r ->
                                    System.out.printf(
                                            "%n[确认] %s → %s%n",
                                            r.getToolCall().getName(),
                                            r.isConfirmed() ? "已批准" : "已拒绝"));
        }
    }

    /** 工具集：一个安全工具，一个危险工具。 */
    public static class DangerousTools {

        /**
         * 安全读取文件（始终自动放行）。
         *
         * @param path 要读取的文件路径
         * @return 模拟的文件内容
         */
        @Tool(name = "safe_read", description = "Read a file (safe, always allowed)")
        public String safeRead(
                @ToolParam(name = "path", description = "File path to read") String path) {
            return "Content of " + path + ": [simulated data]";
        }

        /**
         * 永久删除文件（需要用户确认）。
         *
         * @param path 要删除的文件路径
         * @return 删除确认字符串
         */
        @Tool(
                name = "dangerous_delete",
                description = "Permanently delete a file (requires confirmation)")
        public String dangerousDelete(
                @ToolParam(name = "path", description = "File path to delete permanently")
                        String path) {
            return "Deleted: " + path;
        }
    }
}
