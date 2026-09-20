package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.source.PermissionSourceLoader;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 A2] destination=SESSION 授权跨轮存活测试 · 「写进 appState 的会话级授权下一轮读得回来」。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：CC 的 {@code appState.toolPermissionContext}
 * 是<b>长效对象</b> —— 文件类工具弹窗的「Yes, allow all edits during this session」/「Yes, during
 * this session」（{@code destination='session'}）经 {@code setToolPermissionContext} 写进 appState
 * 后，后续每次权限检查读同一对象（CC {@code PermissionUpdate.ts:208-216} 明示 session
 * <b>不落盘</b> ⇒ 内存对象是它唯一的存活载体）。
 *
 * <p>Java 端 per-turn permCtx 由 {@link PermissionContextBuilder} <b>每轮从盘重建</b>
 * （只遍历 source loader、不读 appState）⇒ 批 A2 前 SESSION 规则是<b>死目的地</b>：写进 appState
 * 后下一轮读不回来（等于从未生效）。本测试钉死修复后语义：
 * <ol>
 *   <li><b>跨轮存活</b>：第 1 轮写入的 SESSION allow 规则，第 2 轮 {@code buildPermissionContext}
 *       结果里必须存在（RED：旧实现只搬 COMMAND 桶 ⇒ SESSION 桶恒空）；</li>
 *   <li><b>mode 跨轮</b>：第 1 轮的 {@code SetMode(session, acceptEdits)}（文件类写路径的真实产出）
 *       第 2 轮 permCtx.mode() 必须是 ACCEPT_EDITS；</li>
 *   <li><b>附加目录跨轮</b>：SESSION {@code addDirectories} 第 2 轮仍在；</li>
 *   <li><b>会话隔离</b>：换会话（另一份 appState）后 SESSION 规则不得残留；</li>
 *   <li><b>不回归</b>：COMMAND 桶（技能 allowedTools）仍合并；从盘读的 source 桶<b>不被替换</b>
 *       （盘上规则 + appState SESSION 规则<b>同时</b>在）。</li>
 * </ol>
 *
 * <p><b>授权产出点用真源</b>：不手搓 {@code AddRules}/{@code SetMode}，而是调
 * {@link PermissionUpdates#createReadRuleSuggestion(String, PermissionUpdate.Destination)}
 * （CC {@code PermissionUpdate.ts:361-389}）与
 * {@link PermissionUpdates#generateSuggestions(String, PermissionUpdates.OperationType,
 * PermissionMode, boolean)}（CC {@code filesystem.ts:1414-1478}）—— 正是前端文件类弹窗选项的
 * 后端产出点（「Yes, during this session」/「Yes, allow all edits during this session」）。
 *
 * <p><b>appState 回写走生产路径形态</b>：{@code applyAll(updates, permCtx)} →
 * {@code setAppState(prev -> {...toolPermissionContext})}，与
 * {@code WebSocketPermissionPrompter.applyAndPersistUpdates}（:1027-1060）逐句同形
 * （该方法是私有的，测试无法直调；此处镜像其两步）。
 *
 * <p><b>范围边界</b>：本测试只覆盖「同一次 run 内跨轮」（appState 载体 =
 * {@code LlmAgentLoop.appStateRef} 实例字段）。同轮内第 2+ 个 tool call 不即时生效、跨用户消息
 * （新 loop 实例）不存活，均为已登记残留，不在本测试断言范围。
 */
@DisplayName("[批 A2] destination=SESSION 授权跨轮存活")
class SessionDestinationPermissionContextCrossTurnTest {

    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final String SESSION_A = "sess-aaaaaaa2";
    private static final String SESSION_B = "sess-bbbbbbb2";

    private static final String READ_DIR = "/tmp/a2-session-dir";
    private static final String WRITE_FILE = "/tmp/a2-session-file.txt";
    private static final String EXTRA_DIR = "/tmp/a2-extra-dir";

    /** 共享 builder 实例（会话隔离断言：builder 不得在会话间夹带状态）。 */
    private final PermissionContextBuilder builder = new PermissionContextBuilder();

    // ══════════════════════════ 夹具 ══════════════════════════

    /** AgentLoopContext 只接 permissionContextBuilder（其余 deps null，对齐 F1ByPerTurn 测试接缝）。 */
    private static AgentLoopContext ctxWith(PermissionContextBuilder b) {
        return new AgentLoopContext(
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, null, null, null, null,
            b, null, null, null);
    }

    /** base permCtx：mode=DEFAULT、bypass 不可用（最严格；与 per-turn 重建互不干扰）。 */
    private static ToolPermissionContext basePermCtx() {
        return new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
    }

    /**
     * getAppState/setAppState 绑定到可观测 {@code appState} Map 的 base TUC ·
     * 对齐 {@code LlmAgentLoop.java:10037-10041}（getAppStateSnapshot / setAppState 接线）。
     */
    private static ToolUseContext appStateBoundBaseTuc(
            String sessionId, Map<String, Object> appState) {
        Function<Map<String, Object>, Map<String, Object>> getAppState = prev -> Map.copyOf(appState);
        Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState = updater -> {
            Map<String, Object> next = updater.apply(Map.copyOf(appState));
            appState.clear();
            if (next != null) {
                appState.putAll(next);
            }
        };
        return new ToolUseContext(
            AGENT_ID, sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of(),
            basePermCtx(), PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            getAppState, setAppState, null, null);
    }

    /** 派生某一轮的 per-turn TUC（生产唯一入口）。 */
    private ToolUseContext perTurn(AgentLoopContext ctx, ToolUseContext baseTuc, String sessionId) {
        ToolUseContext perTurn = AgentLoopContext.toolExecContext(
            ctx, baseTuc, new AgentState("sys", sessionId, AGENT_ID), Map.of());
        assertThat(perTurn).as("toolExecContext 必须返回 per-turn TUC").isNotNull();
        assertThat(perTurn.permissionContext()).as("per-turn permCtx 必须重建").isNotNull();
        return perTurn;
    }

    /**
     * 用户批准 updatedPermissions 的两步生产动作（镜像
     * {@code WebSocketPermissionPrompter.applyAndPersistUpdates:1026-1060} 的 ①apply ③setAppState；
     * ②persist 不涉及——SESSION 按 CC {@code supportsPersistence} 不落盘）。
     */
    private static void approve(ToolUseContext ctx, List<PermissionUpdate> updates) {
        ToolPermissionContext current = ctx.permissionContext();
        ToolPermissionContext applied = new PermissionUpdateApplier().applyAll(updates, current);
        ctx.setAppState().accept(prev -> {
            Map<String, Object> next = new LinkedHashMap<>(prev != null ? prev : Map.of());
            next.put("toolPermissionContext", applied);
            return next;
        });
    }

    /** 规则渲染为 {@code toolName(ruleContent)} 便于断言集合。 */
    private static Set<String> ruleKeys(Set<PermissionRule> rules) {
        return rules == null ? Set.of() : rules.stream()
            .map(r -> r.ruleValue().toolName() + "(" + r.ruleValue().ruleContent() + ")")
            .collect(Collectors.toSet());
    }

    private static Set<PermissionRule> allowBucket(ToolPermissionContext ctx, PermissionRuleSource src) {
        return ctx.alwaysAllowRules().get(src);
    }

    // ══════════════════════════ ① 跨轮存活（核心） ══════════════════════════

    @Test
    @DisplayName("① SESSION allow 规则：第 1 轮写入 → 第 2 轮仍在（RED: 旧实现只搬 COMMAND 桶）")
    void sessionAllowRule_survivesNextTurn() {
        Map<String, Object> appState = new ConcurrentHashMap<>();
        ToolUseContext baseTuc = appStateBoundBaseTuc(SESSION_A, appState);
        AgentLoopContext ctx = ctxWith(builder);

        // ── 第 1 轮：写之前 SESSION 桶为空（前置量，防「本来就有一条」假绿）──
        ToolUseContext turn1 = perTurn(ctx, baseTuc, SESSION_A);
        assertThat(ruleKeys(allowBucket(turn1.permissionContext(), PermissionRuleSource.SESSION)))
            .as("前置：第 1 轮写之前 SESSION 桶必须为空（无 loader 注入，唯一来源 = appState）")
            .isEmpty();

        // 真实产出点：文件类弹窗「Yes, during this session」（CC permissionOptions.tsx:127-131
        // session 档 → PermissionUpdate.ts:361-389 createReadRuleSuggestion(dir,'session')）
        PermissionUpdate.AddRules readRule = PermissionUpdates
            .createReadRuleSuggestion(READ_DIR, PermissionUpdate.Destination.SESSION)
            .orElseThrow(() -> new AssertionError("createReadRuleSuggestion 不得为空（非根目录）"));
        approve(turn1, List.of(readRule));

        assertThat(appState.get("toolPermissionContext"))
            .as("appState.toolPermissionContext 必须被落桶（CC setToolPermissionContext 语义）")
            .isInstanceOf(ToolPermissionContext.class);

        // ── 第 2 轮：重建 per-turn ctx，SESSION 规则必须读得回来 ──
        ToolUseContext turn2 = perTurn(ctx, baseTuc, SESSION_A);
        Set<String> sessionRules = ruleKeys(allowBucket(turn2.permissionContext(), PermissionRuleSource.SESSION));
        assertThat(sessionRules)
            .as("第 2 轮 permCtx 必须含第 1 轮写入的 SESSION Read 规则"
                + "（批 A2 前：appState 不参与重建 ⇒ 此断言 RED）")
            .isNotEmpty();
        assertThat(sessionRules).anySatisfy(key ->
            assertThat(key).as("规则形状 = Read(<dir>/**)（CC createReadRuleSuggestion）").startsWith("Read("));

        // ③ 桶归属：必须是 SESSION 桶，不得漂移到其它 source
        assertThat(allowBucket(turn2.permissionContext(), PermissionRuleSource.SESSION))
            .as("规则 source 必须 = SESSION（CC 桶 key 即归属）")
            .allSatisfy(r -> assertThat(r.source()).isEqualTo(PermissionRuleSource.SESSION));

        // ④ 不落盘：本测试全程无 persister 调用 ⇒ 磁盘三条桶不被写（CC supportsPersistence
        //    只认 user/project/localSettings，PermissionUpdate.ts:208-216）
        assertThat(builder.loaderCount()).as("无盘 loader ⇒ SESSION 不可能经盘回流").isZero();
    }

    // ══════════════════════════ ② mode 跨轮（SetMode SESSION） ══════════════════════════

    @Test
    @DisplayName("② SetMode(session, acceptEdits)：第 2 轮 permCtx.mode() = ACCEPT_EDITS")
    void sessionSetMode_survivesNextTurn() {
        Map<String, Object> appState = new ConcurrentHashMap<>();
        ToolUseContext baseTuc = appStateBoundBaseTuc(SESSION_A, appState);
        AgentLoopContext ctx = ctxWith(builder);

        ToolUseContext turn1 = perTurn(ctx, baseTuc, SESSION_A);
        assertThat(turn1.permissionContext().mode())
            .as("前置：base mode = DEFAULT").isEqualTo(PermissionMode.DEFAULT);

        // 真实产出点：文件类写路径「Yes, allow all edits during this session」
        // （CC filesystem.ts:1448-1463 write/create → SetMode(acceptEdits, 'session')）
        List<PermissionUpdate> updates = PermissionUpdates.generateSuggestions(
            WRITE_FILE, PermissionUpdates.OperationType.WRITE, PermissionMode.DEFAULT, false);
        assertThat(updates)
            .as("write + mode=default ⇒ 必须产出 SetMode(session, acceptEdits)")
            .anyMatch(u -> u instanceof PermissionUpdate.SetMode sm
                && sm.mode() == PermissionMode.ACCEPT_EDITS);
        approve(turn1, updates);

        ToolUseContext turn2 = perTurn(ctx, baseTuc, SESSION_A);
        assertThat(turn2.permissionContext().mode())
            .as("第 2 轮 permCtx.mode() 必须是 appState 里的 setMode 结果（批 A2 前：恒 baseTuc.permissionMode()）")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
        // per-turn TUC 的 permissionMode 同步（gate 读 ctx.permissionMode()）
        assertThat(turn2.permissionMode())
            .as("per-turn TUC.permissionMode() 与 permCtx.mode() 同源")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    // ══════════════════════════ ③ 附加目录跨轮（AddDirectories SESSION） ══════════════════════════

    @Test
    @DisplayName("③ AddDirectories(session)：第 2 轮 additionalWorkingDirectories 仍在")
    void sessionAddDirectories_survivesNextTurn() {
        Map<String, Object> appState = new ConcurrentHashMap<>();
        ToolUseContext baseTuc = appStateBoundBaseTuc(SESSION_A, appState);
        AgentLoopContext ctx = ctxWith(builder);

        ToolUseContext turn1 = perTurn(ctx, baseTuc, SESSION_A);
        assertThat(turn1.permissionContext().additionalWorkingDirectories()).isEmpty();

        approve(turn1, List.of(new PermissionUpdate.AddDirectories(
            PermissionUpdate.Destination.SESSION, List.of(EXTRA_DIR))));

        ToolUseContext turn2 = perTurn(ctx, baseTuc, SESSION_A);
        assertThat(turn2.permissionContext().additionalWorkingDirectories())
            .as("第 2 轮必须含第 1 轮 SESSION addDirectories 的目录")
            .containsKey(EXTRA_DIR);
        assertThat(turn2.permissionContext().additionalWorkingDirectories().get(EXTRA_DIR).source())
            .as("目录归属 source = SESSION（CC PermissionUpdate.ts:130-131）")
            .isEqualTo(PermissionRuleSource.SESSION);
    }

    // ══════════════════════════ ④ 会话隔离（不残留） ══════════════════════════

    @Test
    @DisplayName("④ 会话隔离：A 会话写入的 SESSION 规则不得出现在 B 会话的重建结果里")
    void sessionRules_doNotLeakAcrossSessions() {
        Map<String, Object> appStateA = new ConcurrentHashMap<>();
        Map<String, Object> appStateB = new ConcurrentHashMap<>();
        // 同一 builder 实例服务两个会话（builder 无会话态 ⇒ 不得夹带）
        AgentLoopContext ctx = ctxWith(builder);

        ToolUseContext baseA = appStateBoundBaseTuc(SESSION_A, appStateA);
        ToolUseContext turnA = perTurn(ctx, baseA, SESSION_A);
        approve(turnA, List.of(PermissionUpdates
            .createReadRuleSuggestion(READ_DIR, PermissionUpdate.Destination.SESSION).orElseThrow()));

        // 换会话：B 有独立的 base TUC + 独立 appState（生产为 prototype loop 新实例）
        ToolUseContext baseB = appStateBoundBaseTuc(SESSION_B, appStateB);
        ToolUseContext turnB = perTurn(ctx, baseB, SESSION_B);

        assertThat(ruleKeys(allowBucket(turnB.permissionContext(), PermissionRuleSource.SESSION)))
            .as("B 会话 SESSION 桶必须为空（A 的授权不得跨会话残留）")
            .isEmpty();
        assertThat(turnB.permissionContext().additionalWorkingDirectories())
            .as("B 会话附加目录亦不得残留").isEmpty();
        assertThat(turnB.permissionContext().mode())
            .as("B 会话 mode 不得被 A 影响").isEqualTo(PermissionMode.DEFAULT);
        // A 会话自身仍持有（隔离不是靠「把 A 也清掉」实现的）
        assertThat(ruleKeys(appStateTpc(appStateA).alwaysAllowRules().get(PermissionRuleSource.SESSION)))
            .as("A 会话 appState 仍持有该规则（隔离方向性：只挡跨会话，不误伤本会话）")
            .isNotEmpty();
    }

    private static ToolPermissionContext appStateTpc(Map<String, Object> appState) {
        Object tpc = appState.get("toolPermissionContext");
        assertThat(tpc).isInstanceOf(ToolPermissionContext.class);
        return (ToolPermissionContext) tpc;
    }

    // ══════════════════════════ ⑤ 不回归：COMMAND 桶 + 盘桶不被替换 ══════════════════════════

    @Test
    @DisplayName("⑤-a COMMAND 桶（技能 allowedTools）仍合并 —— P0-2 既有行为不回归")
    void commandBucket_stillMerged() {
        Map<String, Object> appState = new ConcurrentHashMap<>();
        ToolUseContext baseTuc = appStateBoundBaseTuc(SESSION_A, appState);
        AgentLoopContext ctx = ctxWith(builder);

        ToolUseContext turn1 = perTurn(ctx, baseTuc, SESSION_A);
        assertThat(turn1.permissionContext().alwaysAllowRules())
            .as("前置：第 1 轮（写入前）无 COMMAND 桶，防「本来就有一条」假绿")
            .isEmpty();
        // 技能授权桶：SkillToolImpl.mergeAllowedToolsIntoAppState（:2140-2155）把
        // allowedTools 建成 wholeTool ALLOW 规则后直接 put 进 alwaysAllowRules[COMMAND]
        // （不走 PermissionUpdate destination —— 桶 key 与规则 source 同为 COMMAND）。
        appState.put("toolPermissionContext", new ToolPermissionContext(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.COMMAND, Set.of(new PermissionRule(
                PermissionRuleSource.COMMAND, PermissionBehavior.ALLOW,
                PermissionRuleValue.wholeTool("Bash")))),
            Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null));

        ToolUseContext turn2 = perTurn(ctx, baseTuc, SESSION_A);
        assertThat(ruleKeys(allowBucket(turn2.permissionContext(), PermissionRuleSource.COMMAND)))
            .as("COMMAND 桶既有合并行为必须保留（批 A2 扩桶不得把 COMMAND 搬丢）")
            .containsExactly("Bash(null)");
    }

    @Test
    @DisplayName("⑤-b 盘 source 桶不被替换：盘规则与 appState SESSION 规则同时存在（是合并不是替换）")
    void diskSources_notReplacedByAppStateMerge() {
        PermissionRule diskRule = new PermissionRule(
            PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW,
            PermissionRuleValue.wholeTool("Glob"));
        // 盘 loader 假件（模拟 user settings 从盘读到 1 条 allow）
        PermissionSourceLoader diskLoader = new PermissionSourceLoader() {
            @Override
            public PermissionRuleSource source() {
                return PermissionRuleSource.USER_SETTINGS;
            }

            @Override
            public List<PermissionRule> load() {
                return List.of(diskRule);
            }
        };
        PermissionContextBuilder withDisk = new PermissionContextBuilder(List.of(diskLoader));
        AgentLoopContext ctx = ctxWith(withDisk);

        Map<String, Object> appState = new ConcurrentHashMap<>();
        ToolUseContext baseTuc = appStateBoundBaseTuc(SESSION_A, appState);
        ToolUseContext turn1 = perTurn(ctx, baseTuc, SESSION_A);
        assertThat(ruleKeys(allowBucket(turn1.permissionContext(), PermissionRuleSource.USER_SETTINGS)))
            .as("前置：盘规则进入 per-turn ctx").containsExactly("Glob(null)");
        approve(turn1, List.of(PermissionUpdates
            .createReadRuleSuggestion(READ_DIR, PermissionUpdate.Destination.SESSION).orElseThrow()));

        ToolUseContext turn2 = perTurn(ctx, baseTuc, SESSION_A);
        assertThat(ruleKeys(allowBucket(turn2.permissionContext(), PermissionRuleSource.USER_SETTINGS)))
            .as("从盘读的 USER_SETTINGS 桶不得被 appState 合并替换掉（每轮从盘重读语义不破）")
            .containsExactly("Glob(null)");
        assertThat(ruleKeys(allowBucket(turn2.permissionContext(), PermissionRuleSource.SESSION)))
            .as("appState 的 SESSION 规则同时叠加（合并 = 盘桶 ∪ appState 桶）")
            .isNotEmpty();
    }

    @Test
    @DisplayName("⑤-c 无 appState.toolPermissionContext 时零行为变化（返回原实例）")
    void noAppStateTpc_isNoOp() {
        Map<String, Object> appState = new ConcurrentHashMap<>();   // 空 appState
        ToolUseContext baseTuc = appStateBoundBaseTuc(SESSION_A, appState);
        AgentLoopContext ctx = ctxWith(builder);

        ToolUseContext turn1 = perTurn(ctx, baseTuc, SESSION_A);
        assertThat(turn1.permissionContext().alwaysAllowRules()).isEmpty();
        assertThat(turn1.permissionContext().alwaysDenyRules()).isEmpty();
        assertThat(turn1.permissionContext().alwaysAskRules()).isEmpty();
        assertThat(turn1.permissionContext().mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(turn1.permissionContext().isBypassPermissionsModeAvailable())
            .as("appState 缺位时 base 的 bypass 可用性保真（F1-BY 语义不得被批 A2 打掉）")
            .isFalse();
    }

    @Nested
    @DisplayName("⑥ BUBBLE 守卫：fork 子 agent 回写的 mode=BUBBLE 不得污染父会话")
    class BubbleGuard {

        @Test
        @DisplayName("appState 侧 mode=BUBBLE ⇒ 主循环 permCtx.mode() 保持原位，不采纳 BUBBLE")
        void bubbleModeFromForkChild_isNotAdopted() {
            Map<String, Object> appState = new ConcurrentHashMap<>();
            ToolUseContext baseTuc = appStateBoundBaseTuc(SESSION_A, appState);
            AgentLoopContext ctx = ctxWith(builder);

            // 模拟 fork 子 agent（shareSetAppState=true）回写：其 per-turn ctx mode=BUBBLE
            ToolPermissionContext bubbleCtx = new ToolPermissionContext(
                PermissionMode.BUBBLE, Map.of(), Map.of(), Map.of(), Map.of(),
                false, false, Map.of(), false, false, null);
            appState.put("toolPermissionContext", bubbleCtx);

            ToolUseContext turn1 = perTurn(ctx, baseTuc, SESSION_A);
            assertThat(turn1.permissionContext().mode())
                .as("appState 的 BUBBLE 是子 agent 冒泡内部标记，不得成为主循环 mode"
                    + "（否则 ToolPermissionGate:1224 bubble 分支在主线程误触发）")
                .isEqualTo(PermissionMode.DEFAULT);
        }
    }
}
