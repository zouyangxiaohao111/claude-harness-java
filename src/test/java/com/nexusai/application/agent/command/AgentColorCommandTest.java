package com.nexusai.application.agent.command;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.cli.AgentsHandler;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMP-SUB-04 · AgentColorCommand L2 契约 + getAgentColor/setAgentColor 语义 +
 * AgentsHandler.format 分组/shadowed/空列表 + AgentState.color @JsonIgnore。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-SUB-04 反思（IMP-SUB-04-reflection.md
 * P0-1）判定原"聚焦测试 SessionGuidanceSectionTest"与本任务零交集 —— AgentColorCommand L1-L3
 * 契约（5 Release Gate A1-A5）、getAgentColor/setAgentColor 读侧语义、AgentsHandler.format
 * 与 AgentState.color @JsonIgnore 全部无测试。本测试按实施计划 04-implementation-plan.md
 * 指定的聚焦测试类 {@code AgentColorCommandTest} 补齐全部契约，防止回归：
 * <ol>
 *   <li><b>A1</b>: {@code execute(args, env) → CommandResult} 签名（handled/message/display 三要素）</li>
 *   <li><b>A2 Golden Trace</b>: teammate → system error；空参 → 列出可用 colors；reset 别名 →
 *       save('default') + setAppStateColor(null)；有效色 → save + setAppStateColor；非法色 → error</li>
 *   <li><b>A3/A5</b>: 大小写不敏感（CC {@code args.trim().toLowerCase()}，args="BLUE" → "set to: blue"）</li>
 *   <li><b>A4</b>: null/whitespace-only args → 空参分支；reset 别名优先于合法色分支</li>
 *   <li><b>getAgentColor 读侧</b>: general-purpose → null；命中合法色 → theme key；未命中/非法 → null</li>
 *   <li><b>setAgentColor 写侧</b>: null/空串 → 删除（CC `!color`）；合法色 → 写；空白串/非法 → 忽略</li>
 *   <li><b>AgentsHandler.format</b>: 分组/排序/shadowed 前缀/空列表 → "No agents found."</li>
 *   <li><b>AgentState.color @JsonIgnore</b>: local-only 红线（CLAUDE.md BudgetTracker 架构），
 *       绝不序列化到 outbound DTO（复用 AgentStateEffortValueTest 反射断言先例）</li>
 *   <li><b>R-B1 颜色 API（GET /api/agents/{type}/color）</b>: getColor 读侧契约 —— 命中合法色 →
 *       color=原始色名 + themeColor=CC 主题 key；general-purpose → 双 null（CC 早返）；未设置/非法色 → 双 null；
 *       subagentTool 未注入 → 双 null（不 NPE）</li>
 *   <li><b>R-B3 · B-3 /color 生产级端到端</b>（unresolved-owner-decisions.md B-3：dispatch 无生产级
 *       端到端测试 → 本期补）: MDC sessionId + 真实 SessionAgentStateRegistry + 真实 UserInputDispatcher →
 *       {@code registerSlashCommand()} → {@code dispatch("/color ...")} → 生产 Env（buildProductionEnv）全链
 *       执行 —— {@code persistAgentColor}（SessionStorage.reAppendSessionMetadata 落盘 transcript
 *       agent-color entry）+ {@code setAppStateColor}（registry 中 AgentState.color 更新）</li>
 * </ol>
 */
class AgentColorCommandTest {

    // ════════════════════════════════════════════════════════════════════
    // A1 · execute(args, env) → CommandResult 签名
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("A1: execute 返回 CommandResult（handled/message/display 三要素）")
    void executeReturnsCommandResultShape() {
        AgentColorCommand cmd = new AgentColorCommand();
        AtomicReference<String> onDone = new AtomicReference<>();
        AgentColorCommand.CommandResult r = cmd.execute("blue", env(false, null, null, onDone::set));
        assertThat(r).isNotNull();
        assertThat(r.handled()).isTrue();
        assertThat(r.message()).isEqualTo("Session color set to: blue");
        assertThat(r.display()).isEqualTo("system");
        assertThat(onDone.get()).isEqualTo("Session color set to: blue");
    }

    // ════════════════════════════════════════════════════════════════════
    // @PostConstruct 生产注册（反思 §6 无测试覆盖的关键路径）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("@PostConstruct registerSlashCommand: UserInputDispatcher 注册 /color → dispatch 路由到 execute（生产 Env，无会话 MDC 不 NPE）")
    void registerSlashCommandRoutesColorToExecute() throws Exception {
        AgentColorCommand cmd = new AgentColorCommand();
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        Field f = AgentColorCommand.class.getDeclaredField("userInputDispatcher");
        f.setAccessible(true);
        f.set(cmd, dispatcher);
        cmd.registerSlashCommand();

        // 生产 Env 路径：teammate=false（无上下文）、sessionId=null（MDC 未 set）→ persistAgentColor
        // 短路 completedFuture、setAppStateColor no-op → 不 NPE（D4 注册真实可达）
        UserInputDispatcher.RoutingResult r = dispatcher.dispatch("/color blue");
        assertThat(r.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(r.routedTo()).isEqualTo("color");
        assertThat(r.payload()).isEqualTo("blue");
    }

    // ════════════════════════════════════════════════════════════════════
    // R-B3 · B-3 /color 生产级端到端（dispatch 全链）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B-3 生产 E2E: MDC sessionId + registry + dispatch('/color blue') → AgentState.color=blue + transcript agent-color entry 落盘")
    void productionDispatch_colorBlue_fullChain() throws Exception {
        // WHY（规则九）：B-3 决策（unresolved-owner-decisions.md:32）——/color 已注册但 dispatch 无生产级
        //   端到端测试。既有用例只断言 RoutingResult（kind/routedTo/payload），未证明生产 handler 在真实
        //   会话上下文（MDC sessionId + SessionAgentStateRegistry）下执行完整副作用链：persistAgentColor
        //   → transcript 落盘 + setAppStateColor → registry 中 AgentState.color。若接线只有路由无副作用，
        //   前端仍不可见颜色变化（B-1/B-3 决策目标落空）。
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RequestContext.setSession(sessionId.toString());
        Path transcript = null;
        try {
            // 生产装配：真实 dispatcher + registry（非 mock）+ 已注册主会话 AgentState
            AgentColorCommand cmd = new AgentColorCommand();
            UserInputDispatcher dispatcher = new UserInputDispatcher();
            SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
            AgentState state = new AgentState("test-sys", sessionId, null);
            registry.register(sessionId, state);
            injectCommandField(cmd, "userInputDispatcher", dispatcher);
            injectCommandField(cmd, "sessionAgentStateRegistry", registry);
            cmd.registerSlashCommand();

            UserInputDispatcher.RoutingResult r = dispatcher.dispatch("/color blue");
            assertThat(r.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
            assertThat(r.routedTo()).isEqualTo("color");
            assertThat(r.payload()).isEqualTo("blue");

            // 生产副作用①：setAppStateColor → registry 中 AgentState.color（CC standaloneAgentContext.color）
            assertThat(state.color()).as("生产 /color blue 后 AgentState.color 必须更新").isEqualTo("blue");

            // 生产副作用②：persistAgentColor → SessionStorage.reAppendSessionMetadata 落盘 agent-color entry
            // [S2] transcript 锚点迁 config-home（未绑定 → originalCwdLayer 回落 user.dir → config-home slug）
            transcript = com.nexusai.application.agent.tool.SessionStorage.sessionProjectDir(sessionId.toString())
                .resolve(sessionId + ".jsonl");
            assertThat(Files.isRegularFile(transcript)).as("transcript 文件已创建").isTrue();
            String content = Files.readString(transcript);
            assertThat(content).as("transcript 含 agent-color entry").contains("\"type\":\"agent-color\"");
            assertThat(content).as("agent-color 值为 blue").contains("\"agentColor\":\"blue\"");
        } finally {
            RequestContext.clear();
            if (transcript != null) {
                try {
                    Files.deleteIfExists(transcript);
                } catch (IOException ignored) {
                    // 清理失败不掩盖测试结果
                }
            }
        }
    }

    @Test
    @DisplayName("B-3 生产 E2E: dispatch('/color reset') → AgentState.color=null + transcript agent-color='default' sentinel 落盘")
    void productionDispatch_resetAlias_clearsColorAndPersistsDefault() throws Exception {
        // WHY（规则九）：reset 别名生产路径（CC color.ts:45-64）——saveAgentColor('default' sentinel)
        //   保证跨会话重启颜色保持默认（truthiness 守卫）+ setAppStateColor(null) 清空当前态。
        //   若接线只 save 不清 AppState（或反过来），reset 行为与 CC 背离：transcript 有 default 但
        //   当前 AgentState.color 仍是旧值。端到端断言两个副作用同时发生。
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RequestContext.setSession(sessionId.toString());
        Path transcript = null;
        try {
            AgentColorCommand cmd = new AgentColorCommand();
            UserInputDispatcher dispatcher = new UserInputDispatcher();
            SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
            AgentState state = new AgentState("test-sys", sessionId, null);
            state.setColor("blue"); // 预置旧色，验证 reset 清空
            registry.register(sessionId, state);
            injectCommandField(cmd, "userInputDispatcher", dispatcher);
            injectCommandField(cmd, "sessionAgentStateRegistry", registry);
            cmd.registerSlashCommand();

            UserInputDispatcher.RoutingResult r = dispatcher.dispatch("/color reset");
            assertThat(r.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
            assertThat(r.routedTo()).isEqualTo("color");
            assertThat(r.payload()).isEqualTo("reset");

            // setAppStateColor(null) → AgentState.color 清空（CC color: undefined）
            assertThat(state.color()).as("reset → AgentState.color 清空").isNull();

            // saveAgentColor('default' sentinel) → transcript agent-color=default（CC color.ts:51）
            // [S2] transcript 锚点迁 config-home（未绑定 → originalCwdLayer 回落 user.dir → config-home slug）
            transcript = com.nexusai.application.agent.tool.SessionStorage.sessionProjectDir(sessionId.toString())
                .resolve(sessionId + ".jsonl");
            assertThat(Files.isRegularFile(transcript)).as("transcript 文件已创建").isTrue();
            String content = Files.readString(transcript);
            assertThat(content).as("transcript 含 agent-color entry").contains("\"type\":\"agent-color\"");
            assertThat(content).as("agent-color 值为 default sentinel").contains("\"agentColor\":\"default\"");
        } finally {
            RequestContext.clear();
            if (transcript != null) {
                try {
                    Files.deleteIfExists(transcript);
                } catch (IOException ignored) {
                    // 清理失败不掩盖测试结果
                }
            }
        }
    }

    /** 反射注入 AgentColorCommand 字段（对齐 registerSlashCommandRoutesColorToExecute 的注入先例）。 */
    private static void injectCommandField(AgentColorCommand cmd, String fieldName, Object value) throws Exception {
        Field f = AgentColorCommand.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(cmd, value);
    }

    // ════════════════════════════════════════════════════════════════════
    // A2 Golden Trace
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("A2: teammate → system error（CC color.ts:26-32，teammate 不能自设色）")
    void teammateCannotSetColor() {
        AgentColorCommand cmd = new AgentColorCommand();
        AtomicReference<String> onDone = new AtomicReference<>();
        AgentColorCommand.CommandResult r =
            cmd.execute("blue", env(true, null, null, onDone::set));
        assertThat(r.handled()).isFalse();
        assertThat(r.display()).isEqualTo("system");
        assertThat(r.message()).isEqualTo(
            "Cannot set color: This session is a swarm teammate. Teammate colors are assigned by the team leader.");
        assertThat(onDone.get()).isEqualTo(r.message());
    }

    @Test
    @DisplayName("A2: 空参 → 列出可用 colors（CC color.ts:34-40）")
    void emptyArgsListsAvailableColors() {
        AgentColorCommand cmd = new AgentColorCommand();
        AtomicReference<String> onDone = new AtomicReference<>();
        AgentColorCommand.CommandResult r = cmd.execute("", env(false, null, null, onDone::set));
        assertThat(r.handled()).isFalse();
        assertThat(r.message()).isEqualTo(
            "Please provide a color. Available colors: red, blue, green, yellow, purple, orange, pink, cyan, default");
    }

    @Test
    @DisplayName("A2: reset 别名 → 'Session color reset to default' + save('default') + setAppStateColor(null)")
    void resetAliasSavesDefaultSentinelAndClearsAppState() {
        AgentColorCommand cmd = new AgentColorCommand();
        AtomicReference<String> savedColor = new AtomicReference<>();
        AtomicReference<String> appStateColor = new AtomicReference<>("__unset__");
        AtomicReference<UUID> savedSession = new AtomicReference<>();
        AgentColorCommand.CommandResult r = cmd.execute("reset", env(false,
            (sid, color) -> { savedSession.set(sid); savedColor.set(color); return CompletableFuture.completedFuture(null); },
            appStateColor::set, msg -> { }));
        assertThat(r.handled()).isTrue();
        assertThat(r.message()).isEqualTo("Session color reset to default");
        // CC color.ts:51 用 "default" sentinel（非空串）持久化，truthiness 守卫跨会话重启保留
        assertThat(savedColor.get()).isEqualTo("default");
        assertThat(savedSession.get()).isNotNull();
        // CC color.ts:53-60 standaloneAgentContext.color = undefined
        assertThat(appStateColor.get()).isNull();
    }

    @Test
    @DisplayName("A2: 有效色 → save + setAppStateColor(color) + 'Session color set to: blue'")
    void validColorSavesAndSetsAppState() {
        AgentColorCommand cmd = new AgentColorCommand();
        AtomicReference<String> savedColor = new AtomicReference<>();
        AtomicReference<String> appStateColor = new AtomicReference<>();
        AgentColorCommand.CommandResult r = cmd.execute("blue", env(false,
            (sid, color) -> { savedColor.set(color); return CompletableFuture.completedFuture(null); },
            appStateColor::set, msg -> { }));
        assertThat(r.handled()).isTrue();
        assertThat(r.message()).isEqualTo("Session color set to: blue");
        assertThat(savedColor.get()).isEqualTo("blue");
        assertThat(appStateColor.get()).isEqualTo("blue");
    }

    @Test
    @DisplayName("A2: 非法色 → 'Invalid color ...' error（CC color.ts:66-73，不 save 不 set）")
    void invalidColorErrorsWithoutSideEffects() {
        AgentColorCommand cmd = new AgentColorCommand();
        AtomicReference<Boolean> saveCalled = new AtomicReference<>(false);
        AtomicReference<Boolean> appStateCalled = new AtomicReference<>(false);
        AtomicReference<String> onDone = new AtomicReference<>();
        AgentColorCommand.CommandResult r = cmd.execute("chartreuse", env(false,
            (sid, color) -> { saveCalled.set(true); return CompletableFuture.completedFuture(null); },
            c -> appStateCalled.set(true), onDone::set));
        assertThat(r.handled()).isFalse();
        assertThat(r.message()).isEqualTo(
            "Invalid color \"chartreuse\". Available colors: red, blue, green, yellow, purple, orange, pink, cyan, default");
        assertThat(saveCalled.get()).as("非法色不得触发 saveAgentColor").isFalse();
        assertThat(appStateCalled.get()).as("非法色不得触发 setAppStateColor").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // A3/A5 · 大小写不敏感
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("A3/A5: args='BLUE' 大写 → 小写化匹配 → 'Session color set to: blue'（CC args.trim().toLowerCase()）")
    void uppercaseColorMatchesCaseInsensitively() {
        AgentColorCommand cmd = new AgentColorCommand();
        AtomicReference<String> savedColor = new AtomicReference<>();
        AtomicReference<String> appStateColor = new AtomicReference<>();
        AgentColorCommand.CommandResult r = cmd.execute("BLUE", env(false,
            (sid, color) -> { savedColor.set(color); return CompletableFuture.completedFuture(null); },
            appStateColor::set, msg -> { }));
        assertThat(r.handled()).isTrue();
        assertThat(r.message()).isEqualTo("Session color set to: blue");
        assertThat(savedColor.get()).isEqualTo("blue");
        assertThat(appStateColor.get()).isEqualTo("blue");
    }

    @Test
    @DisplayName("A3: reset 别名大小写不敏感（args='Reset' → reset 分支）")
    void uppercaseResetAliasHitsResetBranch() {
        AgentColorCommand cmd = new AgentColorCommand();
        AgentColorCommand.CommandResult r = cmd.execute("Reset", env(false, null, null, msg -> { }));
        assertThat(r.message()).isEqualTo("Session color reset to default");
    }

    // ════════════════════════════════════════════════════════════════════
    // A4 · null / whitespace-only → 空参；reset 别名优先
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("A4: null args → 空参分支（不 NPE，CC !args）")
    void nullArgsBehaveAsEmpty() {
        AgentColorCommand cmd = new AgentColorCommand();
        AgentColorCommand.CommandResult r = cmd.execute(null, env(false, null, null, msg -> { }));
        assertThat(r.handled()).isFalse();
        assertThat(r.message()).startsWith("Please provide a color.");
    }

    @Test
    @DisplayName("A4: whitespace-only args → 空参分支（CC args.trim() === ''）")
    void whitespaceOnlyArgsBehaveAsEmpty() {
        AgentColorCommand cmd = new AgentColorCommand();
        AgentColorCommand.CommandResult r = cmd.execute("   \t ", env(false, null, null, msg -> { }));
        assertThat(r.handled()).isFalse();
        assertThat(r.message()).startsWith("Please provide a color.");
    }

    @Test
    @DisplayName("A4: reset 别名优先于合法色分支（CC 先查 RESET_ALIASES 后查 AGENT_COLORS，color.ts:45/66）")
    void resetAliasBranchRunsBeforeValidColorBranch() {
        AgentColorCommand cmd = new AgentColorCommand();
        AtomicReference<String> saved = new AtomicReference<>();
        AtomicReference<String> appState = new AtomicReference<>("__unset__");
        // 'default' 既是 reset 别名；若误走合法色分支会 save('default') 且不 setAppState(null)
        AgentColorCommand.CommandResult r = cmd.execute("default", env(false,
            (sid, color) -> { saved.set(color); return CompletableFuture.completedFuture(null); },
            appState::set, msg -> { }));
        assertThat(r.message()).isEqualTo("Session color reset to default");
        assertThat(saved.get()).isEqualTo("default");
        assertThat(appState.get()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // getAgentColor 读侧 · CC agentColorManager.ts:36-50
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getAgentColor: general-purpose → null（CC 早返，不查 map）")
    void getAgentColor_generalPurposeIsNull() {
        SubagentTool tool = new SubagentTool();
        assertThat(tool.getAgentColor("general-purpose")).isNull();
    }

    @Test
    @DisplayName("getAgentColor: 命中合法色 → 主题色 key（blue → blue_FOR_SUBAGENTS_ONLY，CC AGENT_COLOR_TO_THEME_COLOR）")
    void getAgentColor_hitValidColorReturnsThemeKey() {
        SubagentTool tool = new SubagentTool();
        tool.setAgentColor("my-agent", "blue");
        assertThat(tool.getAgentColor("my-agent"))
            .isEqualTo("blue_FOR_SUBAGENTS_ONLY");
    }

    @Test
    @DisplayName("getAgentColor: 未设置 / 非法色写入被忽略 → null")
    void getAgentColor_unsetOrInvalidIsNull() {
        SubagentTool tool = new SubagentTool();
        assertThat(tool.getAgentColor("my-agent")).as("未设置 → null").isNull();
        tool.setAgentColor("my-agent", "not-a-color");
        assertThat(tool.getAgentColor("my-agent")).as("非法色写入被忽略 → 仍 null").isNull();
    }

    @Test
    @DisplayName("getAgentColor: 颜色被删除（null）后 → null")
    void getAgentColor_deletedReturnsNull() {
        SubagentTool tool = new SubagentTool();
        tool.setAgentColor("my-agent", "cyan");
        assertThat(tool.getAgentColor("my-agent")).isEqualTo("cyan_FOR_SUBAGENTS_ONLY");
        tool.setAgentColor("my-agent", null);
        assertThat(tool.getAgentColor("my-agent")).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // setAgentColor 写侧 · CC agentColorManager.ts:52-66
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("setAgentColor: 合法色写入 map（CC AGENT_COLORS.includes 分支）")
    void setAgentColor_validColorWrites() {
        SubagentTool tool = new SubagentTool();
        tool.setAgentColor("a", "purple");
        assertThat(tool.getAgentColorMap().get("a")).isEqualTo("purple");
    }

    @Test
    @DisplayName("setAgentColor: null / 空串 → 删除（CC `if (!color)` agentColorManager.ts:58）")
    void setAgentColor_nullOrEmptyDeletes() {
        SubagentTool tool = new SubagentTool();
        tool.setAgentColor("a", "red");
        tool.setAgentColor("a", null);
        assertThat(tool.getAgentColorMap()).doesNotContainKey("a");
        tool.setAgentColor("b", "red");
        tool.setAgentColor("b", "");
        assertThat(tool.getAgentColorMap()).doesNotContainKey("b");
    }

    @Test
    @DisplayName("setAgentColor: 空白串 → 忽略（CC truthy 分支走 includes 判非法，非删除）[REWORK isBlank→isEmpty]")
    void setAgentColor_whitespaceIgnoredNotDeleted() {
        SubagentTool tool = new SubagentTool();
        tool.setAgentColor("a", "green");
        tool.setAgentColor("a", "  ");
        assertThat(tool.getAgentColorMap().get("a"))
            .as("空白串非空 → 不进删除分支（CC ' ' truthy → includes 判非法 → 忽略），既有色保留")
            .isEqualTo("green");
    }

    @Test
    @DisplayName("setAgentColor: 非法色 → 忽略（不写不删）")
    void setAgentColor_invalidIgnored() {
        SubagentTool tool = new SubagentTool();
        tool.setAgentColor("a", "red");
        tool.setAgentColor("a", "chartreuse");
        assertThat(tool.getAgentColorMap().get("a")).as("非法色忽略 → 既有色保留").isEqualTo("red");
        tool.setAgentColor("new-agent", "chartreuse");
        assertThat(tool.getAgentColorMap()).as("非法色忽略 → 不新增").doesNotContainKey("new-agent");
    }

    @Test
    @DisplayName("setAgentColor: null agentType → no-op（不 NPE）")
    void setAgentColor_nullAgentTypeNoop() {
        SubagentTool tool = new SubagentTool();
        tool.setAgentColor(null, "red");
        assertThat(tool.getAgentColorMap()).isEmpty();
    }

    @Test
    @DisplayName("getAgentColorMap: 只读视图（unmodifiableMap，写抛异常）")
    void getAgentColorMap_isUnmodifiable() {
        SubagentTool tool = new SubagentTool();
        tool.setAgentColor("a", "blue");
        assertThatThrownBy(() -> tool.getAgentColorMap().put("x", "red"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // AgentsHandler.format · 分组 / shadowed / 空列表
    // ════════════════════════════════════════════════════════════════════

    private static final List<AgentsHandler.SourceGroup> PRODUCTION_GROUPS = List.of(
        new AgentsHandler.SourceGroup("userSettings", "User agents"),
        new AgentsHandler.SourceGroup("projectSettings", "Project agents"),
        new AgentsHandler.SourceGroup("localSettings", "Local agents"),
        new AgentsHandler.SourceGroup("policySettings", "Managed agents"),
        new AgentsHandler.SourceGroup("plugin", "Plugin agents"),
        new AgentsHandler.SourceGroup("flagSettings", "CLI arg agents"),
        new AgentsHandler.SourceGroup("built-in", "Built-in agents"));

    @Test
    @DisplayName("format: 空列表 → 'No agents found.'（CC agents.ts:61-63）")
    void format_emptyListNoAgents() {
        AgentsHandler h = new AgentsHandler();
        assertThat(h.format(List.of(), PRODUCTION_GROUPS)).isEqualTo("No agents found.");
    }

    @Test
    @DisplayName("format: 按 AGENT_SOURCE_GROUPS 顺序分组 + 组内按名排序 + active 计数头")
    void format_groupsAndSorts() {
        AgentsHandler h = new AgentsHandler();
        List<AgentsHandler.ResolvedAgent> agents = List.of(
            new AgentsHandler.ResolvedAgent("z-builtin", "m-z", null, "built-in", null),
            new AgentsHandler.ResolvedAgent("a-builtin", null, null, "built-in", null),
            new AgentsHandler.ResolvedAgent("user-custom", "m2", "short", "userSettings", null));
        String out = h.format(agents, PRODUCTION_GROUPS);
        // 3 个 agent 均 active（user-custom + a-builtin + z-builtin）
        assertThat(out).startsWith("3 active agents\n\n");
        // 组顺序：userSettings 组先于 built-in 组（CC agentDisplay.ts:24-32 顺序）
        assertThat(out).containsSubsequence(
            "User agents:\n  user-custom · m2 · short memory\n",
            // R1-WF-C REWORK-1 方案 A：a-builtin model=null → 'inherit' 兜底
            // （CC agentDisplay.ts:78-84 model || getDefaultSubagentModel()，getDefaultSubagentModel 恒返回 'inherit'）
            "Built-in agents:\n  a-builtin · inherit\n  z-builtin · m-z");
        // built-in 组内字典序（a 在 z 前）
        assertThat(out.indexOf("  a-builtin")).isLessThan(out.indexOf("  z-builtin"));
    }

    @Test
    @DisplayName("format: overriddenBy 非空 → '(shadowed by {source}) ' 前缀且不计入 active（CC agents.ts:50-56）")
    void format_shadowedAgentPrefixNotCounted() {
        AgentsHandler h = new AgentsHandler();
        List<AgentsHandler.ResolvedAgent> agents = List.of(
            new AgentsHandler.ResolvedAgent("dup", "m", null, "built-in", "userSettings"),
            new AgentsHandler.ResolvedAgent("dup", "m", null, "userSettings", null));
        String out = h.format(agents, PRODUCTION_GROUPS);
        // shadowed 分支（format() 支持，Java 生产端点因 registry 合并不可达——显式登记差异）
        assertThat(out).contains("  (shadowed by user) dup · m");
        assertThat(out).startsWith("1 active agents\n\n");
        // 非 shadowed 的 userSettings 组正常列出
        assertThat(out).contains("User agents:\n  dup · m");
    }

    @Test
    @DisplayName("format: model=null → 'inherit' 兜底（CC agentDisplay.ts:78-84）；memory=null 不显示字段（避免 'null memory'）")
    void format_omitsNullFields() {
        AgentsHandler h = new AgentsHandler();
        List<AgentsHandler.ResolvedAgent> agents = List.of(
            new AgentsHandler.ResolvedAgent("plain", null, null, "built-in", null));
        String out = h.format(agents, PRODUCTION_GROUPS);
        // R1-WF-C REWORK-1 方案 A：model=null → 'inherit'（getDefaultSubagentModel() 恒返回 'inherit'）
        assertThat(out).contains("  plain · inherit")
            .doesNotContain("null memory").doesNotContain("null ·");
    }

    // ════════════════════════════════════════════════════════════════════
    // AgentState.color @JsonIgnore · local-only 红线
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AgentState.color set/get 往返（/color 命令 setAppStateColor 写侧）")
    void agentStateColor_setGetRoundTrip() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        assertThat(state.color()).as("初始 color 为 null（= 未设置，默认色）").isNull();
        state.setColor("blue");
        assertThat(state.color()).isEqualTo("blue");
        state.setColor(null);
        assertThat(state.color()).as("可清空为 null").isNull();
    }

    @Test
    @DisplayName("AgentState.color 必须 @JsonIgnore（local-only 红线 · 绝不序列化 outbound DTO/STOMP/EventPublisher）")
    void agentStateColor_jsonIgnoreLocalOnly() throws Exception {
        // AgentState 无 public getter（方法式 accessor），Jackson 默认不序列化 → 改断言注解本体
        //（AgentStateEffortValueTest 先例：漏标则 writeValueAsString(state) 即泄漏 budgetTracker 同款字段）
        Field field = AgentState.class.getDeclaredField("color");
        assertThat(field.isAnnotationPresent(JsonIgnore.class))
            .as("color 必须 @JsonIgnore（CLAUDE.md BudgetTracker 架构红线，CC standaloneAgentContext.color 本地态）")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // R-B1 · B-1 颜色 API 暴露（GET /api/agents/{type}/color）· CC agentColorManager.ts:36-50
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getColor: 命中合法色 → color=原始色名 + themeColor=CC 主题色 key（agentColorManager.ts:44-47）")
    void getColor_hitValidColorReturnsRawAndTheme() throws Exception {
        AgentColorCommand cmd = new AgentColorCommand();
        SubagentTool tool = new SubagentTool();
        tool.setAgentColor("my-agent", "blue");
        injectSubagentTool(cmd, tool);
        AgentColorCommand.AgentColorResponse r = cmd.getColor("my-agent");
        assertThat(r.agentType()).isEqualTo("my-agent");
        assertThat(r.color()).isEqualTo("blue");
        assertThat(r.themeColor()).isEqualTo("blue_FOR_SUBAGENTS_ONLY");
    }

    @Test
    @DisplayName("getColor: general-purpose → 双 null（CC 早返 undefined，agentColorManager.ts:37-39）")
    void getColor_generalPurposeIsNull() throws Exception {
        AgentColorCommand cmd = new AgentColorCommand();
        SubagentTool tool = new SubagentTool();
        tool.setAgentColor("general-purpose", "red");
        injectSubagentTool(cmd, tool);
        AgentColorCommand.AgentColorResponse r = cmd.getColor("general-purpose");
        assertThat(r.color()).as("general-purpose 早返 → color null").isNull();
        assertThat(r.themeColor()).as("general-purpose 早返 → themeColor null").isNull();
    }

    @Test
    @DisplayName("getColor: 未设置 / 非法色 → 双 null（CC undefined 分支）")
    void getColor_unsetOrInvalidIsNull() throws Exception {
        AgentColorCommand cmd = new AgentColorCommand();
        SubagentTool tool = new SubagentTool();
        injectSubagentTool(cmd, tool);
        assertThat(cmd.getColor("ghost").color()).as("未设置 → color null").isNull();
        assertThat(cmd.getColor("ghost").themeColor()).isNull();
        tool.setAgentColor("bad", "not-a-color");
        assertThat(cmd.getColor("bad").themeColor()).as("非法色写入被忽略 → themeColor null").isNull();
    }

    @Test
    @DisplayName("getColor: subagentTool 未注入 → 双 null（不 NPE，plain JUnit 兼容）")
    void getColor_subagentToolNullIsNull() {
        AgentColorCommand cmd = new AgentColorCommand();
        AgentColorCommand.AgentColorResponse r = cmd.getColor("my-agent");
        assertThat(r.color()).isNull();
        assertThat(r.themeColor()).isNull();
    }

    /** 反射注入 SubagentTool（对齐 registerSlashCommandRoutesColorToExecute 的 userInputDispatcher 注入先例）。 */
    private static void injectSubagentTool(AgentColorCommand cmd, SubagentTool tool) throws Exception {
        Field f = AgentColorCommand.class.getDeclaredField("subagentTool");
        f.setAccessible(true);
        f.set(cmd, tool);
    }

    // WF-1C · 会话存档根走统一入口 originalCwd 层（DEL-05 / G8 / AC-1）
    // 对齐 CC sessionStorage.ts:202-205 getTranscriptPath():
    //   projectDir = getSessionProjectDir() ?? getProjectDir(getOriginalCwd())

    @AfterEach
    void clearCwdState() {
        // 隔离每个用例的会话 cwd / 绑定项目状态，防止跨用例污染（SessionCwdHolder / SessionProjectRoot 均为 JVM 全局静态）
        SessionProjectRoot.reset();
        if (RequestContext.sessionId() != null) {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("AC-1: 会话绑定项目时 /color transcript 落到项目目录（非 user.dir 漂移）· 对齐 CC getSessionProjectDir ?? getProjectDir(originalCwd)")
    void colorCommand_boundProject_transcriptLandsInProjectDir() throws Exception {
        // WHY（规则九）：CC sessionStorage.ts:202-205 getTranscriptPath 的 projectDir 取
        //   getSessionProjectDir() ?? getProjectDir(getOriginalCwd())——会话存档根必须跟随会话绑定的
        //   项目目录，而非 JVM 启动 user.dir。旧 Java 端 AgentColorCommand.workspaceDir() 恒返回
        //   user.dir（:156 DEL-05），导致绑定项目场景下 transcript 落到启动目录而非项目目录，
        //   与 CC 行为漂移（G8）。若业务逻辑改为走统一入口后该测试仍报错，说明接线未真正落地。
        Path projectDir = Files.createTempDirectory("wf1c-project-");
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RequestContext.setSession(sessionId.toString());
        // 绑定会话项目根（对齐 CC getSessionProjectDir() 非空分支）
        SessionProjectRoot.setForSession(sessionId.toString(), projectDir.toString());
        Path transcriptInProject = null;
        Path transcriptInUserDir = null;
        try {
            AgentColorCommand cmd = new AgentColorCommand();
            UserInputDispatcher dispatcher = new UserInputDispatcher();
            SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
            AgentState state = new AgentState("test-sys", sessionId, null);
            registry.register(sessionId, state);
            injectCommandField(cmd, "userInputDispatcher", dispatcher);
            injectCommandField(cmd, "sessionAgentStateRegistry", registry);
            cmd.registerSlashCommand();

            dispatcher.dispatch("/color blue");

            // 统一入口解析的存档根（对齐 CC getOriginalCwd 层，已 normalizeCwd=realpath+NFC）
            Path resolvedRoot = Path.of(CwdResolution.getOriginalCwdLayer(sessionId.toString()));
            assertThat(resolvedRoot.toRealPath())
                .as("统一入口应解析到绑定的项目目录（realpath 归一化后等价）")
                .isEqualTo(projectDir.toRealPath());

            // [S2] transcript 锚点迁 config-home：{configHome}/projects/{sanitizePath(originalCwdLayer)}/{sessionId}.jsonl
            //   经 sessionProjectDir 同 seam 派生（内部 getOriginalCwdLayer 已 realpath+NFC 归一）
            transcriptInProject = com.nexusai.application.agent.tool.SessionStorage
                .sessionProjectDir(sessionId.toString()).resolve(sessionId + ".jsonl");
            transcriptInUserDir = Path.of(System.getProperty("user.dir", ".")).resolve(sessionId + ".jsonl");

            assertThat(Files.isRegularFile(transcriptInProject))
                .as("transcript 必须落到 config-home projects slug 目录（S2，对齐 CC getProjectDir(originalCwd)）")
                .isTrue();
            assertThat(Files.isRegularFile(transcriptInUserDir))
                .as("transcript 不得漂移到 user.dir（DEL-05 旧直读行为）")
                .isFalse();
            String content = Files.readString(transcriptInProject);
            assertThat(content).contains("\"type\":\"agent-color\"").contains("\"agentColor\":\"blue\"");
        } finally {
            RequestContext.clear();
            SessionProjectRoot.clearSession(sessionId.toString());
            if (transcriptInProject != null) {
                Files.deleteIfExists(transcriptInProject);
            }
            if (transcriptInUserDir != null) {
                try { Files.deleteIfExists(transcriptInUserDir); } catch (IOException ignored) { }
            }
            Files.deleteIfExists(projectDir);
        }
    }

    @Test
    @DisplayName("AC-1: 会话未绑定项目时 /color transcript 回落 user.dir（统一入口兜底层，不抛异常）")
    void colorCommand_unboundSession_transcriptFallsBackToUserDir() throws Exception {
        // WHY（规则九）：CC getProjectDir(getOriginalCwd()) 在无 sessionProjectDir 时回落 originalCwd
        //   （Java 等价 user.dir，JVM 启动目录）。统一入口必须保证未绑定场景恒非 null、不抛异常
        //   （对齐 CC getCwd catch → getOriginalCwd 兜底语义）。若未绑定场景抛 NPE，生产 /color 即崩溃。
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RequestContext.setSession(sessionId.toString());
        // 显式确保未绑定（隔离前置用例的 setForSession 残留）
        SessionProjectRoot.clearSession(sessionId.toString());
        Path transcript = null;
        try {
            AgentColorCommand cmd = new AgentColorCommand();
            UserInputDispatcher dispatcher = new UserInputDispatcher();
            SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
            AgentState state = new AgentState("test-sys", sessionId, null);
            registry.register(sessionId, state);
            injectCommandField(cmd, "userInputDispatcher", dispatcher);
            injectCommandField(cmd, "sessionAgentStateRegistry", registry);
            cmd.registerSlashCommand();

            dispatcher.dispatch("/color green");

            // 未绑定 → 统一入口回落 user.dir（对齐 CC getOriginalCwd 兜底，realpath 归一化后等价）
            Path resolvedRoot = Path.of(CwdResolution.getOriginalCwdLayer(sessionId.toString()));
            assertThat(resolvedRoot.toRealPath())
                .as("未绑定回落 user.dir")
                .isEqualTo(Path.of(System.getProperty("user.dir", ".")).toRealPath());

            // [S2] transcript 锚点迁 config-home：{configHome}/projects/{sanitizePath(originalCwdLayer)}/{sessionId}.jsonl
            //   经 sessionProjectDir 同 seam 派生（内部 getOriginalCwdLayer 已 realpath+NFC 归一）
            transcript = com.nexusai.application.agent.tool.SessionStorage.sessionProjectDir(sessionId.toString())
                .resolve(sessionId + ".jsonl");
            assertThat(Files.isRegularFile(transcript))
                .as("未绑定 → transcript 落 config-home projects slug（对齐 CC getProjectDir(getOriginalCwd())）").isTrue();
        } finally {
            RequestContext.clear();
            if (transcript != null) {
                try { Files.deleteIfExists(transcript); } catch (IOException ignored) { }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** 构造默认 Env · agentColors 用 SubagentTool.AGENT_COLORS（生产真源，探查 △-3）。 */
    private static AgentColorCommand.Env env(boolean isTeammate,
                                             java.util.function.BiFunction<UUID, String, CompletableFuture<Void>> save,
                                             Consumer<String> setAppState,
                                             Consumer<String> onDone) {
        return new AgentColorCommand.Env(
            () -> isTeammate,
            UUID::randomUUID,
            () -> "/tmp/transcript.jsonl",
            () -> SubagentTool.AGENT_COLORS,
            save != null ? save : (sid, c) -> CompletableFuture.completedFuture(null),
            setAppState != null ? setAppState : c -> { },
            onDone != null ? onDone : msg -> { });
    }
}
