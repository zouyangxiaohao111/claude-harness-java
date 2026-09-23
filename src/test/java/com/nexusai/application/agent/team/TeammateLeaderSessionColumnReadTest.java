package com.nexusai.application.agent.team;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionContextBuilder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.PermissionUpdates;
import com.nexusai.application.agent.permission.SessionPermissionOverlay;
import com.nexusai.application.agent.subagent.createSubagentContext;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>[T1-A2] teammate 的 per-turn permCtx 必须能读到 Leader 会话列授权</b>
 * （2026-09-22 用户裁定 ②：「让子代理路径也能读到会话列」）。
 *
 * <h2>WHY（规则九 · 本类守护的意图）</h2>
 * <p>用户抱怨的形态 = 「在 teammate 里点了『本次会话允许』，下一次**还是弹**」。根因不是「授权没写进去」，
 * 而是<b>写进去了没人读</b>：
 * <ol>
 *   <li><b>写侧成功</b>：{@code WebSocketPermissionPrompter.applyAndPersistUpdates} 第 0 步
 *       （:1056-1066）把 destination=SESSION 的更新经
 *       {@link SessionPermissionOverlay#persistSessionUpdates} 写进
 *       {@code sessions.session_permission_rules}（跨 send 唯一通道）；</li>
 *   <li><b>读侧只有一条链</b>：{@code LlmAgentLoop.doRun}（:3443-3457）把列注入 {@code appStateRef} →
 *       {@code AgentLoopContext.mergeAppStatePermissionRules}（:1604）经
 *       {@code baseTuc.getAppState().apply(null)} 并进 per-turn permCtx；</li>
 *   <li><b>teammate 不走那条链</b>：它的执行父 TUC 由 {@code SpawnInProcess} 造「最小父 TUC」，
 *       改前 {@code getAppState} 是紧凑构造器兜底的<b>恒等函数</b>（ToolUseContext.java:469-470）
 *       且 {@code withEffectiveCwd} 又把它显式置 null（SubagentExecutor 同批 A-1 已修）
 *       ⇒ {@code apply(null)} 返回 null ⇒ 合并**恒早退**（:1605）⇒ 列里的规则对 teammate 永不可见。</li>
 * </ol>
 * <p>会话键对 ≠ 规则可见：teammate 的 {@code state.sessionId()} 已是 Leader 会话（T1 已修），
 * 但会话列**不是经 sessionId 读的** —— 它只经 appState（getAppState）读。
 *
 * <h2>本类钉死的东西（写读全走生产路径，不手搓 JSON）</h2>
 * <ul>
 *   <li><b>写</b>：{@link SessionPermissionOverlay#persistSessionUpdates}（生产唯一写点）
 *       + {@link PermissionUpdates#createReadRuleSuggestion}（文件类弹窗「Yes, during this session」
 *       的真实产出点，CC {@code PermissionUpdate.ts:361-389}）；</li>
 *   <li><b>构造</b>：{@link SpawnInProcess#buildLeaderParentTuc}（生产同源，spawn 与测试同一份代码）；</li>
 *   <li><b>读</b>：{@code createSubagentContext.create(父 TUC, …)}（teammate 真实装配，CC
 *       forkedAgent.ts:345-462）→ {@code AgentLoopContext.toolExecContext}（per-turn ctx
 *       生产唯一入口）。</li>
 * </ul>
 *
 * <h2>判别力（可证伪）</h2>
 * <p>把「读桥」换回恒等（{@code prev -> prev}）⇒ ①/②/③ 全部变红
 * （见 {@link #identityReader_isTheBreak_columnRuleNotVisible}，它就是那个「断开态」的常驻对照组）；
 * 把 {@code withEffectiveCwd} 改回置 null getAppState（A-1 回退）⇒ {@link #a1_withEffectiveCwd_preservesGetAppState}
 * 与 ①②③ 同时变红。
 */
@DisplayName("[T1-A2] teammate 路径读 Leader 会话列授权（会话列 → 子代理 per-turn permCtx）")
class TeammateLeaderSessionColumnReadTest {

    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final String LEADER_SESSION = "sess-leader-t1a2";
    private static final String LEADER_CWD = "D:/tmp/t1a2-leader-cwd";
    /** 文件类弹窗「Yes, during this session」的目录（CC permissionOptions.tsx:127-131 → session 档）。 */
    private static final String READ_DIR = "/tmp/t1a2-col-dir";
    private static final String WRITE_FILE = "/tmp/t1a2-col-file.txt";

    // ══════════════════════════ 夹具 ══════════════════════════

    /** mock SessionMapper：{@code selectOneById} 恒返回同一可写 record（= 一行 DB 记录）。 */
    private static SessionMapper mapperFor(SessionRecord row) {
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(LEADER_SESSION)).thenReturn(row);
        return mapper;
    }

    /** 空行（列尚未被任何「本次会话允许」写过）。 */
    private static SessionRecord emptyRow() {
        SessionRecord row = new SessionRecord();
        row.setId(LEADER_SESSION);
        return row;
    }

    /** AgentLoopContext 只接 permissionContextBuilder（其余 deps null）· 对齐 SessionDestinationPermissionContextCrossTurnTest 接缝。 */
    private static AgentLoopContext ctxWith(PermissionContextBuilder b) {
        return new AgentLoopContext(
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, null, null, null, null,
            b, null, null, null);
    }

    /**
     * 生产同源构造：Leader 归属父 TUC（含会话列活读桥）。
     *
     * <p>⛔ 故意**不**在测试里手搓读桥 —— 走 {@code spawnInProcessTeammate} 真正调用的那条实例方法
     * （{@code SpawnInProcess.buildLeaderParentTuc(sessionId, cwd)} → 内部用注入的 sessionMapper
     * 造读桥）。否则「读桥没接上」这种回归会逃过用例（已实测：在测试里手搓读桥时，把生产侧换成
     * 恒等 reader 用例仍全绿 = 假绿）。
     */
    private static ToolUseContext leaderParentTuc(SessionMapper mapper) {
        SpawnInProcess spawn = new SpawnInProcess();
        spawn.setSessionMapper(mapper);
        return spawn.buildLeaderParentTuc(LEADER_SESSION, LEADER_CWD);
    }

    /** teammate 真实装配：父 TUC → createSubagentContext.create（CC forkedAgent.ts:345-462）。 */
    private static ToolUseContext teammateSubagentCtx(ToolUseContext leaderParentTuc) {
        ToolUseContext.SubagentContextOverrides overrides = new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return createSubagentContext.create(leaderParentTuc, overrides);
    }

    /** teammate 的 per-turn TUC（生产唯一入口；合并 appState 会话级授权的点就在它内部）。 */
    private static ToolUseContext perTurn(AgentLoopContext ctx, ToolUseContext subagentCtx) {
        ToolUseContext perTurn = AgentLoopContext.toolExecContext(
            ctx, subagentCtx, new AgentState("sys", LEADER_SESSION, AGENT_ID), Map.of());
        assertThat(perTurn).as("toolExecContext 必须返回 per-turn TUC").isNotNull();
        assertThat(perTurn.permissionContext()).as("per-turn permCtx 必须重建").isNotNull();
        return perTurn;
    }

    /** 规则渲染为 {@code toolName(ruleContent)} 便于断言集合。 */
    private static Set<String> ruleKeys(Set<com.nexusai.application.agent.permission.PermissionRule> rules) {
        return rules == null ? Set.of() : rules.stream()
            .map(r -> r.ruleValue().toolName() + "(" + r.ruleValue().ruleContent() + ")")
            .collect(Collectors.toSet());
    }

    private static Set<String> sessionAllow(ToolUseContext tuc) {
        return ruleKeys(tuc.permissionContext().alwaysAllowRules().get(PermissionRuleSource.SESSION));
    }

    /** 真实写侧：批准「本会话允许读该目录」⇒ 经生产写点落进会话列。 */
    private static void approveSessionRead(SessionMapper mapper) {
        SessionPermissionOverlay.persistSessionUpdates(mapper, LEADER_SESSION,
            List.of(PermissionUpdates.createReadRuleSuggestion(READ_DIR, PermissionUpdate.Destination.SESSION)
                .orElseThrow(() -> new AssertionError("createReadRuleSuggestion 不得为空（非根目录）"))),
            "test:t1a2");
    }

    // ══════════════════════════ ① 核心：列 → teammate per-turn permCtx ══════════════════════════

    @Test
    @DisplayName("① 会话列写入 ⇒ teammate 的 per-turn permCtx 里 SESSION allow 规则可见（改前恒空）")
    void columnRule_reachesTeammatePerTurnPermCtx() {
        SessionRecord row = emptyRow();
        SessionMapper mapper = mapperFor(row);
        AgentLoopContext ctx = ctxWith(new PermissionContextBuilder());

        // ── 前置量：写之前必须为空（防「本来就有一条」假绿）──
        ToolUseContext turn1 = perTurn(ctx, teammateSubagentCtx(leaderParentTuc(mapper)));
        assertThat(sessionAllow(turn1))
            .as("前置：写之前 teammate 的 SESSION 桶必须为空（无 loader 注入，唯一来源 = 会话列读桥）")
            .isEmpty();

        // ── 用户批准（生产写侧）──
        approveSessionRead(mapper);
        assertThat(row.getSessionPermissionRules())
            .as("会话列必须被写侧落盘（WebSocketPermissionPrompter.applyAndPersistUpdates 第 0 步同款）")
            .isNotNull();
        assertThat(row.getSessionPermissionRules()).contains(READ_DIR);

        // ── 下一轮：teammate 重建 per-turn ctx，规则必须读得回来 ──
        ToolUseContext turn2 = perTurn(ctx, teammateSubagentCtx(leaderParentTuc(mapper)));
        assertThat(sessionAllow(turn2))
            .as("teammate 的 per-turn permCtx 必须含 Leader 会话列里的 SESSION Read 规则"
                + "（改前：父 TUC 的 getAppState 是恒等函数 ⇒ 合并恒早退 ⇒ 此断言 RED）")
            .isNotEmpty();
        assertThat(sessionAllow(turn2)).anySatisfy(key ->
            assertThat(key).as("规则形状 = Read(<dir>/**)（CC createReadRuleSuggestion）")
                .startsWith("Read(").contains(READ_DIR));
        // 桶归属不得漂移（桶 key 即归属，CC 语义）
        assertThat(turn2.permissionContext().alwaysAllowRules().get(PermissionRuleSource.SESSION))
            .as("规则 source 必须 = SESSION")
            .allSatisfy(r -> assertThat(r.source()).isEqualTo(PermissionRuleSource.SESSION));

        // 会话键对（T1 已修）+ 规则可见 —— 两者缺一都解释不了用户抱怨
        assertThat(turn2.sessionId())
            .as("teammate 执行 TUC 的会话键 = Leader 会话（T1 已修；本类补的是「规则也能读」）")
            .isEqualTo(LEADER_SESSION);
    }

    // ══════════════════════════ ② mode / 附加目录通道 ══════════════════════════

    @Test
    @DisplayName("② 会话列 SESSION setMode ⇒ teammate per-turn permCtx.mode() = ACCEPT_EDITS")
    void columnSetMode_reachesTeammatePerTurnPermCtx() {
        SessionRecord row = emptyRow();
        SessionMapper mapper = mapperFor(row);
        AgentLoopContext ctx = ctxWith(new PermissionContextBuilder());

        ToolUseContext turn1 = perTurn(ctx, teammateSubagentCtx(leaderParentTuc(mapper)));
        assertThat(turn1.permissionContext().mode())
            .as("前置：teammate 基线 mode = DEFAULT（general-purpose 未设 permissionMode）")
            .isEqualTo(PermissionMode.DEFAULT);

        // 真实产出点：文件类写路径「Yes, allow all edits during this session」
        // （CC filesystem.ts:1448-1463 → SetMode(acceptEdits, 'session')）
        List<PermissionUpdate> updates = PermissionUpdates.generateSuggestions(
            WRITE_FILE, PermissionUpdates.OperationType.WRITE, PermissionMode.DEFAULT, false);
        assertThat(updates)
            .as("write + mode=default ⇒ 必须产出 SetMode(session, acceptEdits)")
            .anyMatch(u -> u instanceof PermissionUpdate.SetMode sm
                && sm.mode() == PermissionMode.ACCEPT_EDITS);
        SessionPermissionOverlay.persistSessionUpdates(mapper, LEADER_SESSION, updates, "test:t1a2-mode");

        ToolUseContext turn2 = perTurn(ctx, teammateSubagentCtx(leaderParentTuc(mapper)));
        assertThat(turn2.permissionContext().mode())
            .as("「本会话允许编辑」必须对 teammate 也生效（appState 侧 mode 胜出语义，:1622-1624）")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(turn2.permissionMode())
            .as("per-turn TUC.permissionMode() 与 permCtx.mode() 同源（gate 读 ctx.permissionMode()）")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    // ══════════════════════════ ③ 空态：不塞空 ctx（不覆盖基线 mode） ══════════════════════════

    @Test
    @DisplayName("③ 会话列无 SESSION 条目 ⇒ 不并入（teammate 基线 mode 不被占位值覆盖）")
    void emptyColumn_isNoOp() {
        SessionRecord row = emptyRow();
        SessionMapper mapper = mapperFor(row);
        AgentLoopContext ctx = ctxWith(new PermissionContextBuilder());

        ToolUseContext perTurn = perTurn(ctx, teammateSubagentCtx(leaderParentTuc(mapper)));
        assertThat(sessionAllow(perTurn)).as("空列 ⇒ SESSION 桶为空").isEmpty();
        assertThat(perTurn.permissionContext().mode())
            .as("空列 ⇒ 占位 mode 不得覆盖 per-turn 基线（这正是「不塞空 ctx」的理由）")
            .isEqualTo(PermissionMode.DEFAULT);
    }

    // ══════════════════════════ ④ 常驻反向对照：断开读桥 ⇒ 规则不可见 ══════════════════════════

    @Test
    @DisplayName("④ 反向对照：读桥换回恒等 ⇒ 同一份列在 teammate per-turn permCtx 里不可见")
    void identityReader_isTheBreak_columnRuleNotVisible() {
        SessionRecord row = emptyRow();
        SessionMapper mapper = mapperFor(row);
        approveSessionRead(mapper);   // 列里确实有规则（前置量）

        AgentLoopContext ctx = ctxWith(new PermissionContextBuilder());
        // 断开态：父 TUC 的 getAppState = 恒等（= 改前 SpawnInProcess + withEffectiveCwd 的组合形态）
        ToolUseContext brokenParent = SpawnInProcess.buildLeaderParentTuc(
            LEADER_SESSION, LEADER_CWD, prev -> prev);
        ToolUseContext perTurn = perTurn(ctx, teammateSubagentCtx(brokenParent));

        assertThat(sessionAllow(perTurn))
            .as("读桥断开（恒等）⇒ 合并早退 ⇒ 规则不可见 —— 本断言就是「该用例会变红」的常驻对照组，"
                + "也证明 ① 不是恒真")
            .isEmpty();
    }

    // ══════════════════════════ ⑤ A-1 承重：withEffectiveCwd 保留 getAppState ══════════════════════════

    @Test
    @DisplayName("⑤ A-1 承重：withEffectiveCwd 必须保留源 getAppState（改前置 null ⇒ 读桥被摘）")
    void a1_withEffectiveCwd_preservesGetAppState() {
        Function<Map<String, Object>, Map<String, Object>> marker = prev -> Map.of("marker", "t1a2");
        ToolUseContext source = ToolUseContext.of(
            null, LEADER_SESSION, PermissionMode.DEFAULT,
            List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null,
            null, null, null, marker, null, null, null);

        ToolUseContext derived = SubagentExecutor.withEffectiveCwd(source, Path.of(LEADER_CWD));

        assertThat(derived.getAppState().apply(null))
            .as("withEffectiveCwd 派生后 getAppState 必须仍是源的（CC forkedAgent.ts:274 override ?? parent）；"
                + "改前置 null ⇒ 紧凑构造器兜底恒等 ⇒ 断言 RED")
            .containsEntry("marker", "t1a2");
        assertThat(derived.effectiveCwd())
            .as("effectiveCwd 仍被替换为给定值（本方法的既有职责不变）")
            .isEqualTo(Path.of(LEADER_CWD).toAbsolutePath());

        // 承重点：读桥经 createSubagentContext + toolExecContext 一路可见
        SessionRecord row = emptyRow();
        SessionMapper mapper = mapperFor(row);
        approveSessionRead(mapper);
        ToolUseContext turn = perTurn(ctxWith(new PermissionContextBuilder()),
            teammateSubagentCtx(leaderParentTuc(mapper)));
        assertThat(sessionAllow(turn)).as("端到端：同一条链里规则可见").isNotEmpty();
    }
}
