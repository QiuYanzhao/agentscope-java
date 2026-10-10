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
package io.agentscope.examples.documentation2.harness.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WorkspaceSetupExample — 演示工作区目录结构，以及每个文件是如何被加载进 Agent 的系统提示词的。
 *
 * <p>本示例展示的内容：
 * <ol>
 *   <li><b>工作区结构</b> — 创建一个完整的工作区，包含 {@code AGENTS.md}、
 *       {@code knowledge/KNOWLEDGE.md}、{@code subagents/researcher.md} 以及自定义的
 *       {@code PREFERENCES.md}。</li>
 *   <li><b>{@code AGENTS.md} 注入</b> — 角色设定文件会在每个推理步骤中被注入系统提示词。</li>
 *   <li><b>知识库目录</b> — {@code KNOWLEDGE.md} 会被完整注入；{@code knowledge/} 下的其他文件
 *       只会按路径列出，由 Agent 按需读取。</li>
 *   <li><b>{@code additionalContextFile}</b> — 自定义文件会与标准工作区内容一起被注入。</li>
 *   <li><b>子代理规格文件</b> — 在 {@code subagents/} 目录下放置一个 {@code .md} 文件，
 *       即可通过 {@code agent_spawn} 使用该子代理。</li>
 * </ol>
 *
 * <p><b>运行方式：</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceSetupExample
 * </pre>
 */
public class WorkspaceSetupExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("工作区 — 目录结构与内容注入");
        System.out.println("=".repeat(60) + "\n");

        // ── 创建包含所有关键文件的工作区 ────────────────────────────────────

        Path workspace = Files.createTempDirectory("agentscope-workspace-example");

        // AGENTS.md — 角色设定与行为规则
        Files.writeString(
                workspace.resolve("AGENTS.md"),
                """
                # 项目助手

                你是 "Phoenix" 项目的项目管理助手。

                ## 行为规则
                - 向用户打招呼时，始终提及 Phoenix 项目。
                - 回答领域问题时，使用知识库。
                - 将调研任务委派给 researcher 子代理。
                - 回答格式规范请参考 PREFERENCES.md。
                """);

        // knowledge/KNOWLEDGE.md — 领域知识入口文件
        Path knowledgeDir = Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(
                knowledgeDir.resolve("KNOWLEDGE.md"),
                """
                # Phoenix 项目知识库

                ## 项目概述
                Phoenix 是一个云原生微服务平台，基于 Java 17+ 和 Spring Boot 3.x 构建，
                由 5 个核心服务组成：
                - gateway-service（API 路由）
                - auth-service（OAuth 2.0 + JWT）
                - user-service（用户管理）
                - order-service（订单处理）
                - notification-service（邮件 + 推送）

                ## 技术栈
                - 运行时：JDK 21、Spring Boot 3.3
                - 数据库：PostgreSQL 16 + Redis 7
                - 消息队列：Apache Kafka 3.7
                - 部署：AWS EKS 上的 Kubernetes 1.29
                """);

        // knowledge/api-conventions.md — 附加参考文件（仅列出路径，不注入提示词）
        Files.writeString(
                knowledgeDir.resolve("api-conventions.md"),
                """
                # API 约定

                - 所有端点使用 kebab-case 命名：`/api/v1/user-profiles`
                - 分页参数为 `?page=0&size=20`
                - 错误响应遵循 RFC 7807 Problem Details 规范
                - 速率限制：每个 API 密钥 100 次请求/分钟
                """);

        // subagents/researcher.md — 子代理声明文件
        Path subagentsDir = Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                subagentsDir.resolve("researcher.md"),
                """
                ---
                description: 调研专家。当用户需要对某个主题进行调研时使用。
                ---

                你是 Phoenix 项目的调研子代理。
                调研指定的主题，并给出简洁的总结。
                """);

        // PREFERENCES.md — 附加上下文文件
        Files.writeString(
                workspace.resolve("PREFERENCES.md"),
                """
                # 回答偏好

                - 列表使用项目符号，不要使用编号列表。
                - 回答控制在 200 字以内。
                - 结尾必须附上"后续步骤"部分。
                """);

        System.out.println("工作区已创建于：" + workspace);
        System.out.println("内容：");
        Files.walk(workspace)
                .filter(p -> !p.equals(workspace))
                .forEach(
                        p -> {
                            String rel = workspace.relativize(p).toString();
                            System.out.println("  " + (Files.isDirectory(p) ? rel + "/" : rel));
                        });

        // ── 使用工作区构建 Agent ───────────────────────────────────────────

        System.out.println("\n── 正在使用工作区构建 HarnessAgent ──\n");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("phoenix-assistant")
                        .sysPrompt("你是 Phoenix 项目的助手。")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .additionalContextFile("PREFERENCES.md")
                        .maxContextTokens(4000)
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("workspace-demo").build();

        // ── 提出利用工作区内容回答的问题 ────────────────────────────────────

        System.out.println("── 问题 1：Agent 是否了解项目角色设定？ ──\n");
        System.out.print("Agent：");
        agent.streamEvents(new UserMessage("请做个自我介绍，并说明你支持的项目。"), ctx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            }
                        })
                .blockLast();

        System.out.println("\n\n── 问题 2：Agent 是否了解知识库？ ──\n");
        System.out.print("Agent：");
        agent.streamEvents(new UserMessage("Phoenix 平台由哪些服务组成？"), ctx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            }
                        })
                .blockLast();

        System.out.println("\n\n── 问题 3：Agent 能否读取知识库参考文件？ ──\n");
        System.out.print("Agent：");
        agent.streamEvents(new UserMessage("这个项目的 API 约定是什么？请查看知识库文件了解详情。"), ctx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n[工具] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        System.out.println("\n\n" + "=".repeat(60));
        System.out.println("完成。工作区位于：" + workspace);
        System.out.println("=".repeat(60));
    }
}
