package com.nexusai.application.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.SettingsJsonParser;
import com.nexusai.application.agent.permission.source.UserSettingsLoader;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.subagent.AgentDefinitionRegistry;
import com.nexusai.application.agent.subagent.AgentSummaryService;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CommandRegistrationConfig28 接线测试 · 第二批 14 个 core 命令（mcp/permissions/plan/hooks/skills/agents/
 * tasks/export/context/status/tag/usage/stats/diff）暴露给 web 的注册面验证。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>14 个 local-jsx 命令元数据进 BundledSkills</b>——name/description/argumentHint/aliases/type
 *       对齐 CC commands/ 各 index.ts；web GET /api/command 可见（getAllCommands 数据源）。</li>
 *   <li><b>type 判别</b>——全部 type='local-jsx'，经 getModelInvocableCommands 的 type==='prompt'
 *       过滤排除（对齐 CC commands.ts:568，local 命令不进模型可调用清单）。</li>
 *   <li><b>门控</b>——tag（USER_TYPE==='ant'）默认关 → 从 getAllCommands
 *       过滤（对齐 CC commands.ts:484 isCommandEnabled）；context 恒启用（web 交互会话）；其余无 gate 恒启用。</li>
 *   <li><b>handler 路由</b>——14 个命令 /name 输入 → UserInputDispatcher 命中命名 handler
 *       （对齐 CC processUserInput findCommand → command.call）。</li>
 * </ol>
 */
class CommandRegistrationConfig28Test {

    private final CommandRegistrationConfig28 config = new CommandRegistrationConfig28();

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @AfterEach
    void clearRegistryAfter() {
        BundledSkills.clear();
    }

    private Map<String, Command> byName() {
        return BundledSkills.getAll().stream()
            .collect(Collectors.toMap(Command::getName, Function.identity(), (a, b) -> a));
    }

    @Test
    @DisplayName("14 个 local-jsx 命令元数据注册为 BundledSkills type='local-jsx' + CC 元数据对齐")
    void fourteenLocalJsxCommandsRegisteredWithMetadata() {
        config.commandBundledRegistration28();

        Map<String, Command> cmds = byName();
        assertThat(cmds).containsKeys(
            "mcp", "permissions", "plan", "hooks", "skills", "agents", "tasks",
            "export", "context", "status", "tag", "usage", "stats", "diff");

        // 全部 type='local-jsx'（对齐各 index.ts type 字段）
        for (String name : List.of("mcp", "permissions", "plan", "hooks", "skills", "agents",
            "tasks", "export", "context", "status", "tag", "usage", "stats", "diff")) {
            assertThat(cmds.get(name).getType()).as("%s.type", name).isEqualTo("local-jsx");
        }

        // description 对齐 CC
        assertThat(cmds.get("mcp").getDescription()).isEqualTo("Manage MCP servers");
        assertThat(cmds.get("permissions").getDescription()).isEqualTo("Manage allow & deny tool permission rules");
        assertThat(cmds.get("plan").getDescription()).isEqualTo("Enable plan mode or view the current session plan");
        assertThat(cmds.get("hooks").getDescription()).isEqualTo("View hook configurations for tool events");
        assertThat(cmds.get("tasks").getDescription()).isEqualTo("List and manage background tasks");
        assertThat(cmds.get("export").getDescription()).isEqualTo("Export the current conversation to a file or clipboard");
        assertThat(cmds.get("context").getDescription()).isEqualTo("Visualize current context usage as a colored grid");
        // [批 3c 顺手修正 · 既有漂移，非本批引入] 期望串仍是改名前文案（"Claude Code"），而主代码
        //   CommandRegistrationConfig28:202 的 status 描述早已随 nexusai 改名改为 "NexusAI status…"
        //   ⇒ 本断言在批 3c 之前即为红（git show HEAD 同款期望串 vs HEAD 主代码已是 NexusAI）。
        //   此处仅同步文案，不改断言语义（仍锁「描述存在且为该固定文案」）。
        assertThat(cmds.get("status").getDescription())
            .isEqualTo("Show NexusAI status including version, model, account, API connectivity, and tool statuses");
        assertThat(cmds.get("tag").getDescription()).isEqualTo("Toggle a searchable tag on the current session");
        assertThat(cmds.get("usage").getDescription()).isEqualTo("Show plan usage limits");
        // [批 3c 顺手修正 · 既有漂移，非本批引入] 同 status：期望串是改名前文案，主代码
        //   CommandRegistrationConfig28:220 已是 "Show your NexusAI usage statistics…" ⇒ 批 3c 前即为红。
        assertThat(cmds.get("stats").getDescription()).isEqualTo("Show your NexusAI usage statistics and activity");
        assertThat(cmds.get("diff").getDescription()).isEqualTo("View uncommitted changes and per-turn diffs");

        // argumentHint 对齐 CC
        assertThat(cmds.get("mcp").getArgumentHint()).isEqualTo("[enable|disable [server-name]]");
        assertThat(cmds.get("plan").getArgumentHint()).isEqualTo("[open|<description>]");
        assertThat(cmds.get("export").getArgumentHint()).isEqualTo("[filename]");
        assertThat(cmds.get("tag").getArgumentHint()).isEqualTo("<tag-name>");

        // aliases 对齐 CC
        assertThat(cmds.get("permissions").getAliases()).containsExactly("allowed-tools");
        assertThat(cmds.get("tasks").getAliases()).containsExactly("bashes");

        // immediate 对齐 CC（mcp/hooks/status immediate=true）
        assertThat(cmds.get("mcp").getImmediate()).isTrue();
        assertThat(cmds.get("hooks").getImmediate()).isTrue();
        assertThat(cmds.get("status").getImmediate()).isTrue();
        assertThat(cmds.get("plan").getImmediate()).isFalse();
    }

    @Test
    @DisplayName("门控：tag 默认关，context 与无 gate 命令恒启用")
    void gates_matchCcDefaults() {
        config.commandBundledRegistration28();

        Map<String, Command> cmds = byName();

        // tag 门控 USER_TYPE==='ant'（默认关，CC tag/index.ts:7）
        assertThat(cmds.get("tag").isCommandEnabled()).isFalse();

        // context 恒启用（web 交互会话，CC context/index.ts:7 !nonInteractive）
        assertThat(cmds.get("context").isCommandEnabled()).isTrue();

        // 无 gate 命令恒启用（CC types/command.ts:214-215 isEnabled?.() ?? true）
        for (String name : List.of("mcp", "permissions", "plan", "hooks", "skills", "agents",
            "tasks", "export", "status", "usage", "stats", "diff")) {
            assertThat(cmds.get(name).isCommandEnabled()).as("%s enabled", name).isTrue();
        }
    }

    @Test
    @DisplayName("模型可调用过滤：14 个 local-jsx 全部排除（对齐 CC commands.ts:568 type==='prompt'）")
    void modelInvocableExcludesAllLocalJsx() {
        config.commandBundledRegistration28();

        SkillRegistry registry = new SkillRegistry("");
        registry.refresh();

        // [批 3c] 无会话 → 显式 null（本用例只验命令注册面过滤，不涉会话）
        List<String> invocable = registry.getModelInvocableCommands(null).stream()
            .map(Command::getName).toList();
        assertThat(invocable)
            .doesNotContain("mcp", "permissions", "plan", "hooks", "skills", "agents",
                "tasks", "export", "context", "status", "tag", "usage", "stats", "diff");
    }

    @Test
    @DisplayName("getAllCommands（web GET /api/command 数据源）含 13 个无 gate 命令、排除 tag")
    void getAllCommandsVisible_excludesGated() {
        config.commandBundledRegistration28();

        SkillRegistry registry = new SkillRegistry("");
        registry.refresh();

        List<String> all = registry.getAllCommands(null).stream().map(Command::getName).toList();
        assertThat(all).contains(
            "mcp", "permissions", "plan", "hooks", "skills", "agents", "tasks",
            "export", "context", "status", "usage", "stats", "diff");
        // tag 门控关 → 从 getAllCommands 过滤（对齐 CC commands.ts:484 isCommandEnabled）
        assertThat(all).doesNotContain("tag");
    }

    @Test
    @DisplayName("14 个命令 handler 注册进 UserInputDispatcher：/name 触发命名路由")
    void localSlashHandlersRegisteredAndDispatchable() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        config.commandLocalSlashRegistration28(dispatcher, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

        for (String name : List.of("mcp", "permissions", "plan", "hooks", "skills", "agents",
            "tasks", "export", "context", "status", "tag", "usage", "stats", "diff")) {
            // [批 3c] 本用例只验证「注册面 → 命名路由」（kind/routedTo），不涉会话 → 显式 null（旧实现
            //   里等价于 MDC 为空；真实会话驱动的执行快照见 realHandlersExecuteWithRealServices）
            UserInputDispatcher.RoutingResult r = dispatcher.dispatch("/" + name + " arg", null, null);
            assertThat(r.kind()).as("/%s kind", name).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
            assertThat(r.routedTo()).as("/%s routedTo", name).isEqualTo(name);
        }

        // 未注册的 /nope → 回落通用 SLASH_COMMAND handler（向后兼容，对齐 CommandRegistrationConfigTest）
        // （本用例不涉会话 → 显式 null）
        UserInputDispatcher.RoutingResult nope = dispatcher.dispatch("/nope", null, null);
        assertThat(nope.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(nope.routedTo()).isEqualTo("command-router");
    }

    @Test
    @DisplayName("[commands-real-exec] 6 个命令 handler 注入真实服务后执行不抛 + 真实服务调用")
    void realHandlersExecuteWithRealServices() throws Exception {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        AgentSummaryService summaryService = new AgentSummaryService();
        // [批 3c] 会话标识以**显式形参**随每次 dispatch 传入（下述 sessionId）；原「裸 MDC 会话槽」
        //   已删除（批 3c），故不再有 setSession/clear 装置，断言锁的是显式传参路径。
        String sessionId = "sess-cmd28-test";
        try {
            // /permissions —— 真实 UserSettingsLoader（读临时 config home，空规则不抛）
            UserSettingsLoader userLoader = new UserSettingsLoader(
                new SettingsJsonParser(new ObjectMapper(), new PermissionRuleValueParser()));
            // /skills —— 真实 SkillRegistry
            SkillRegistry skillRegistry = new SkillRegistry("");
            skillRegistry.refresh();
            // /agents —— mock SubagentTool 暴露真实 AgentDefinitionRegistry（空内置+自定义 → 0 agent 不抛）
            AgentDefinitionRegistry agentRegistry = new AgentDefinitionRegistry(Map.of(), List.of());
            SubagentTool subagentTool = mock(SubagentTool.class);
            // [批 3c] /agents handler 现调 1 参 agentRegistry(sessionId)（无参重载的 MDC 会话源已删）
            //   → stub 必须打在 1 参形态上，否则 handler 拿到 mock 默认 null（断言退化）
            when(subagentTool.agentRegistry(anyString())).thenReturn(agentRegistry);
            // /export —— mock SessionService/MessageService（会话不存在 → handler 走 warn 分支不抛）
            SessionService sessionService = mock(SessionService.class);
            when(sessionService.getById(anyString())).thenReturn(null);
            MessageService messageService = mock(MessageService.class);
            // /plan /stats —— mock SessionAgentStateRegistry（无活跃 state → handler 空安全）
            SessionAgentStateRegistry sessionRegistry = mock(SessionAgentStateRegistry.class);
            when(sessionRegistry.get(anyString())).thenReturn(null);

            config.commandLocalSlashRegistration28(dispatcher,
                null, null, skillRegistry, null, sessionService, null, null,
                sessionRegistry, userLoader, null, null, null, subagentTool, summaryService, messageService);

            // 6 个真实执行命令（本类注册；/btw 在 GroupB 注册不含）逐个触发 → 不抛 + 路由命中
            // 先触发 /permissions /agents（真实/handler 走真实路径），再 /export
            // [批 3c] 语义消失（已登记待裁定）：原此处两条断言锁「执行前后裸 MDC 会话槽的值 == 'sess-cmd28-test'」
            //   （证明 handler 不污染会话上下文）。该槽已整类删除，会话现为**显式形参**（局部变量，
            //   Java 值语义下本就不可能被 handler 改写）⇒ 等效断言会退化为 `sessionId == sessionId`
            //   的恒真式（零鉴别力、假绿灯），故删除。同一意图由下方 `verify(subagentTool)
            //   .agentRegistry(sessionId)` / `verify(sessionService).getById(sessionId)` 以真实可观测
            //   方式覆盖（handler 若拿错会话，这两条 verify 会红）。
            // ⚠ 注意：**触发调用本身必须保留** —— 下方 `verify(subagentTool).agentRegistry(sessionId)`
            //   依赖 /agents 被真实 dispatch 过。重构时曾一度把本行连同 MDC 断言一起删掉，导致
            //   verify 报「zero interactions」（断言失去被观测对象）⇒ 两条触发调用在此显式留驻。
            assertThat(dispatcher.dispatch("/permissions arg", sessionId, null).routedTo()).isEqualTo("permissions");
            assertThat(dispatcher.dispatch("/agents arg", sessionId, null).routedTo()).isEqualTo("agents");
            // /export 是**会话相关**执行（handler 用显式 sessionId 查会话）→ 必须传真实会话，
            //   否则 verify(getById) 断言退化成「无会话路径」
            assertThat(dispatcher.dispatch("/export x", sessionId, null).routedTo()).isEqualTo("export");
            verify(sessionService).getById(sessionId);

            for (String name : List.of("skills", "plan", "stats")) {
                String input = "/" + name + " arg";
                UserInputDispatcher.RoutingResult r = dispatcher.dispatch(input, sessionId, null);
                assertThat(r.kind()).as("/%s kind", name).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
                assertThat(r.routedTo()).as("/%s routedTo", name).isEqualTo(name);
            }

            // 校验注入链路生效：/agents 调 SubagentTool.agentRegistry(sessionId)（mock）——会话为
            //   **显式传入**的那个（批 3c），不得是进程默认表
            verify(subagentTool).agentRegistry(sessionId);
            // /plan /stats 经 SessionAgentStateRegistry 查询（mock）
            verify(sessionRegistry, org.mockito.Mockito.atLeastOnce()).get(anyString());
        } finally {
            summaryService.shutdown();
        }
    }
}
