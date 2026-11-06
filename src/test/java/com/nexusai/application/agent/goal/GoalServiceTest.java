package com.nexusai.application.agent.goal;

import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookCommand;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PromptHook;
import com.nexusai.application.agent.permission.hook.SessionHookStore;
import com.nexusai.domain.session.SessionGoal;
import com.nexusai.domain.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [goal V253] GoalService 单测 · 对齐 CC claude.exe v2.1.212 的 goal 机制
 * （{@code Sor()} 设 / {@code Aor()} 清 / {@code restoreGoalFromTranscript()} 恢复）。
 *
 * <p>WHY: 目标机制横跨三个存储 —— {@code sessions.goal} 列（真源）、session hook 注册表
 * （执行通道）、内存 armed 缓存（防重复挂）。任一环节断裂都会让目标「设了不生效」
 * 或「清了还在评估」。
 */
@DisplayName("[goal V253] GoalService")
class GoalServiceTest {

    private static final String SID = "sess-1";

    private SessionService sessionService;
    private HookRegistry hookRegistry;
    private GoalService goalService;

    /** 列的存储（模拟 DB）。 */
    private Map<String, SessionGoal> columnStore;

    /**
     * 会话 hook 注册表的轻量模拟：add/remove/clear 维护、getSessionHooks 回读。
     *
     * <p>WHY 必须有它：{@code isArmed} / {@code ensureArmed} / {@code restoreForResume} 的判据
     * 已经改成「核实注册表里真有这个 hook」，只 mock 掉 addSessionHook 而不提供查询回读，
     * 就会让所有「已挂载」断言变成假红 —— 更糟的是测不出「内存说有、注册表其实被清空」这个真缺陷。
     */
    private Map<String, java.util.List<HookCommand>> stopHooks;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        hookRegistry = mock(HookRegistry.class);
        columnStore = new java.util.HashMap<>();
        stopHooks = new java.util.HashMap<>();

        when(sessionService.getGoal(anyString())).thenAnswer(inv ->
            columnStore.get(inv.getArgument(0, String.class)));
        org.mockito.Mockito.doAnswer(inv -> {
            columnStore.put(inv.getArgument(0, String.class), inv.getArgument(1, SessionGoal.class));
            return null;
        }).when(sessionService).setGoal(anyString(), any());

        // ── 会话 hook 注册表模拟（见 stopHooks 字段的 WHY）──
        org.mockito.Mockito.doAnswer(inv -> {
            String sid = inv.getArgument(0);
            HookCommand hook = inv.getArgument(3);
            stopHooks.computeIfAbsent(sid, k -> new java.util.ArrayList<>()).add(hook);
            return null;
        }).when(hookRegistry).addSessionHook(anyString(), any(), anyString(), any(), any(), any());

        org.mockito.Mockito.doAnswer(inv -> {
            String sid = inv.getArgument(0);
            HookCommand hook = inv.getArgument(2);
            java.util.List<HookCommand> list = stopHooks.get(sid);
            if (list != null) {
                list.removeIf(h -> samePrompt(h, hook));
            }
            return null;
        }).when(hookRegistry).removeSessionHook(anyString(), any(), any());

        org.mockito.Mockito.doAnswer(inv -> {
            stopHooks.remove(inv.getArgument(0, String.class));
            return null;
        }).when(hookRegistry).clearSessionHooks(anyString());

        org.mockito.Mockito.doAnswer(inv -> {
            String sid = inv.getArgument(0, String.class);
            java.util.List<HookCommand> list = stopHooks.get(sid);
            Map<HookEventType, java.util.List<SessionHookStore.SessionDerivedHookMatcher>> out =
                new java.util.LinkedHashMap<>();
            if (list != null && !list.isEmpty()) {
                out.put(HookEventType.STOP, java.util.List.of(
                    new SessionHookStore.SessionDerivedHookMatcher("", null, java.util.List.copyOf(list))));
            }
            return out;
        }).when(hookRegistry).getSessionHooks(anyString(), any());

        // HookRegistry 走 ObjectProvider 懒解析（生产同款，避免 Spring 循环依赖）。
        // 注入 provider：getIfAvailable() 返回 mock。
        org.springframework.beans.factory.ObjectProvider<HookRegistry> registryProvider =
            new org.springframework.beans.factory.ObjectProvider<>() {
                @Override public HookRegistry getObject() { return hookRegistry; }
                @Override public HookRegistry getObject(Object... args) { return hookRegistry; }
                @Override public HookRegistry getIfAvailable() { return hookRegistry; }
                @Override public HookRegistry getIfUnique() { return hookRegistry; }
            };
        goalService = new GoalService(sessionService, registryProvider, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 设定
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("setGoal：写列 + 挂 prompt Stop hook（matcher 必须空串）")
    void setGoal_writesColumnAndArmsHook() {
        SessionGoal g = goalService.setGoal(SID, "e2e 全绿", 1000L);

        assertThat(g.condition()).isEqualTo("e2e 全绿");
        assertThat(g.phase()).isEqualTo(SessionGoal.Phase.ACTIVE);
        assertThat(g.iterations()).isZero();
        assertThat(g.tokensAtStart()).isEqualTo(1000L);
        assertThat(columnStore.get(SID)).isNotNull();

        ArgumentCaptor<PromptHook> captor = ArgumentCaptor.forClass(PromptHook.class);
        verify(hookRegistry).addSessionHook(eq(SID), eq(HookEventType.STOP), eq(""), captor.capture(),
            eq(null), eq(null));
        assertThat(captor.getValue().prompt()).isEqualTo("e2e 全绿");
        assertThat(goalService.isArmed(SID)).isTrue();
    }

    @Test
    @DisplayName("setGoal：条件超 4000 字符 → 拒绝（对齐 CC bor=4000），不写列不挂 hook")
    void setGoal_tooLong_rejected() {
        String tooLong = "x".repeat(4001);

        assertThatThrownBy(() -> goalService.setGoal(SID, tooLong, 0L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("4000");

        assertThat(columnStore).isEmpty();
        verify(hookRegistry, times(0)).addSessionHook(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("setGoal：空条件 → 拒绝")
    void setGoal_blankRejected() {
        assertThatThrownBy(() -> goalService.setGoal(SID, "   ", 0L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(columnStore).isEmpty();
    }

    @Test
    @DisplayName("setGoal：覆盖旧目标 → 先摘旧 hook 再挂新（CC Sor 语义）")
    void setGoal_overwrite_removesOldHook() {
        goalService.setGoal(SID, "第一个条件", 0L);
        goalService.setGoal(SID, "第二个条件", 0L);

        // 摘一次（第一个）+ 挂两次
        verify(hookRegistry, times(1)).removeSessionHook(eq(SID), eq(HookEventType.STOP), any());
        verify(hookRegistry, times(2)).addSessionHook(eq(SID), eq(HookEventType.STOP), eq(""), any(), any(), any());
        assertThat(columnStore.get(SID).condition()).isEqualTo("第二个条件");
    }

    // ════════════════════════════════════════════════════════════════════
    // 清除
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("clearGoal：列置 null + 摘 hook")
    void clearGoal_clearsColumnAndHook() {
        goalService.setGoal(SID, "条件", 0L);
        String cleared = goalService.clearGoal(SID);

        assertThat(cleared).isEqualTo("条件");
        assertThat(columnStore.get(SID)).isNull();
        assertThat(goalService.isArmed(SID)).isFalse();
        verify(hookRegistry).removeSessionHook(eq(SID), eq(HookEventType.STOP), any());
    }

    @Test
    @DisplayName("clearGoal：无目标时返回 null，不抛")
    void clearGoal_noGoal_returnsNull() {
        assertThat(goalService.clearGoal(SID)).isNull();
    }

    @Test
    @DisplayName("isClearIntent：六个别名 + 大小写不敏感（对齐 CC 别名集合）")
    void isClearIntent_matchesCcAliases() {
        for (String alias : new String[]{"clear", "stop", "off", "reset", "none", "cancel"}) {
            assertThat(GoalService.isClearIntent(alias)).as(alias).isTrue();
            assertThat(GoalService.isClearIntent(alias.toUpperCase())).as(alias + " 大写").isTrue();
        }
        assertThat(GoalService.isClearIntent("e2e 全绿")).isFalse();
        assertThat(GoalService.isClearIntent(null)).isFalse();
        assertThat(GoalService.isClearIntent("")).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 评估回写
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("recordNotMet：iterations++ + lastReason 落列")
    void recordNotMet_incrementsIterations() {
        goalService.setGoal(SID, "条件", 0L);
        SessionGoal g = goalService.recordNotMet(SID, "还没跑到 e2e");

        assertThat(g.iterations()).isEqualTo(1);
        assertThat(g.lastReason()).isEqualTo("还没跑到 e2e");
        assertThat(g.phase()).isEqualTo(SessionGoal.Phase.ACTIVE);
        assertThat(goalService.isArmed(SID)).as("未达成时 hook 保持挂着").isTrue();
    }

    @Test
    @DisplayName("recordAchieved：phase=achieved + 摘 hook")
    void recordAchieved_finishesAndDisarms() {
        goalService.setGoal(SID, "条件", 0L);
        SessionGoal g = goalService.recordAchieved(SID);

        assertThat(g.phase()).isEqualTo(SessionGoal.Phase.ACHIEVED);
        assertThat(g.iterations()).isEqualTo(1);
        assertThat(goalService.isArmed(SID)).isFalse();
        verify(hookRegistry).removeSessionHook(eq(SID), eq(HookEventType.STOP), any());
    }

    @Test
    @DisplayName("recordImpossible：phase=impossible + 摘 hook（不自动消失，等用户处理）")
    void recordImpossible_finishesAndDisarms() {
        goalService.setGoal(SID, "条件", 0L);
        SessionGoal g = goalService.recordImpossible(SID);

        assertThat(g.phase()).isEqualTo(SessionGoal.Phase.IMPOSSIBLE);
        assertThat(goalService.isArmed(SID)).isFalse();
        assertThat(columnStore.get(SID)).as("终态快照留在列里供前端显示红条").isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 恢复
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⭐ restoreForResume：active 目标 → 只重挂 hook，轮数/基线/理由全部原样保留")
    void restoreForResume_active_rearmWithoutResettingCounters() {
        goalService.setGoal(SID, "条件", 1000L);
        // 模拟进程重启：内存 armed 态丢失
        goalService.clearGoal(SID);   // 摘 hook + 清列
        // 进程重启后列里留着真实进度：已评估 3 轮、setAt=999、token 基线=1000、有上一次判定理由
        columnStore.put(SID, new SessionGoal("条件", SessionGoal.Phase.ACTIVE, 3, 999L, 1000L, "旧 reason"));
        // 只统计 act 阶段的写库（arrange 里的 setGoal/clearGoal 不算）
        clearInvocations(sessionService);

        SessionGoal restored = goalService.restoreForResume(SID, 5555L);

        assertThat(restored).isNotNull();
        // WHY 这几条必须保真：restoreForResume 挂在 ChatService.processUserMessage 上（**每条
        //   用户消息**都跑）⇒ 一旦在这里重置，评估器刚记下的「第 N 轮」和「已运行时长」
        //   会被每条消息清零，目标头恒显示「第 1 轮」（用户报的缺陷）。
        //   CC 的 turnsExecuted 只在 setGoal / `/goal continue` 归零（goalState.ts:115/131），
        //   resumeGoal 保留它 ⇒ 这里也必须保留。
        assertThat(restored.iterations()).as("恢复不得清零轮数").isEqualTo(3);
        assertThat(restored.setAt()).as("恢复不得重置计时起点").isEqualTo(999L);
        assertThat(restored.tokensAtStart()).as("恢复不得重置 token 基线").isEqualTo(1000L);
        assertThat(restored.lastReason()).as("恢复不得丢弃上一次判定").isEqualTo("旧 reason");
        assertThat(goalService.isArmed(SID)).isTrue();
        // 不得回写误导：状态没变就不该写库（多写一次只制造读-写窗口）
        verify(sessionService, times(0)).setGoal(anyString(), any());
    }

    @Test
    @DisplayName("restoreForResume：fresh 守卫 —— 已 armed 且条件一致 → 不重复挂 hook")
    void restoreForResume_alreadyArmed_noDuplicate() {
        goalService.setGoal(SID, "条件", 0L);

        goalService.restoreForResume(SID, 7777L);

        verify(hookRegistry, times(1)).addSessionHook(eq(SID), eq(HookEventType.STOP), eq(""), any(), any(), any());
        verify(hookRegistry, times(0)).removeSessionHook(anyString(), any(), any());
    }

    @Test
    @DisplayName("restoreForResume：已达成 / 已放弃的目标不复活")
    void restoreForResume_finished_notRestored() {
        for (SessionGoal.Phase p : new SessionGoal.Phase[]{SessionGoal.Phase.ACHIEVED, SessionGoal.Phase.IMPOSSIBLE}) {
            columnStore.put(SID, new SessionGoal("条件", p, 1, 1L, 1L, "r"));
            assertThat(goalService.restoreForResume(SID, 0L)).as("phase=%s 不复活", p).isNull();
        }
        verify(hookRegistry, times(0)).addSessionHook(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("restoreForResume：无目标 → null")
    void restoreForResume_noGoal_null() {
        assertThat(goalService.restoreForResume(SID, 0L)).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // hook 被外部路径摘掉后的自愈（SessionEnd → clearSessionHooks）
    // ════════════════════════════════════════════════════════════════════

    /** 两个 command 是否指向同一个 goal hook（判据 = prompt 文本，与 GoalService.isGoalHookResult 同口径）。 */
    private static boolean samePrompt(HookCommand a, HookCommand b) {
        if (a instanceof PromptHook pa && b instanceof PromptHook pb) {
            return pa.prompt() != null && pa.prompt().equals(pb.prompt());
        }
        return false;
    }

    @Test
    @DisplayName("isArmed：注册表被清空后不得再报「已挂」（内存标记会骗人）")
    void isArmed_registryCleared_false() {
        goalService.setGoal(SID, "e2e 全绿", 0L);
        assertThat(goalService.isArmed(SID)).isTrue();

        // 模拟 HookRegistry.executeSessionEndHooks 的 finally
        hookRegistry.clearSessionHooks(SID);

        assertThat(goalService.isArmed(SID))
            .as("内存 armedConditions 还在，但注册表已空 —— 必须报未挂载")
            .isFalse();
    }

    @Test
    @DisplayName("ensureArmed：hook 被 SessionEnd 清掉后补挂回注册表（真机 sess-189e74fa 那个缺陷）")
    void ensureArmed_afterSessionEndWipe_rearms() {
        goalService.setGoal(SID, "按照排期实现到完成", 0L);
        hookRegistry.clearSessionHooks(SID);          // 上一轮 run 结束时的清理
        assertThat(goalService.isArmed(SID)).isFalse();

        goalService.ensureArmed(SID);                  // 下一轮 run 开始前

        assertThat(goalService.isArmed(SID)).as("必须补挂回来").isTrue();
        // 补挂不得动目标字段（iterations/setAt 属于 resume 语义，不是补 hook）
        assertThat(columnStore.get(SID).condition()).isEqualTo("按照排期实现到完成");
        assertThat(columnStore.get(SID).iterations()).isZero();
    }

    @Test
    @DisplayName("ensureArmed：已挂着 ⇒ 幂等，不重复 add")
    void ensureArmed_alreadyArmed_idempotent() {
        goalService.setGoal(SID, "条件", 0L);
        goalService.ensureArmed(SID);

        verify(hookRegistry, times(1))
            .addSessionHook(eq(SID), eq(HookEventType.STOP), eq(""), any(), any(), any());
    }

    @Test
    @DisplayName("ensureArmed：暂停态不自动重挂（发消息才恢复，是显式状态）")
    void ensureArmed_paused_doesNotRearm() {
        columnStore.put(SID, new SessionGoal("条件", SessionGoal.Phase.PAUSED, 1, 1L, 1L, "r"));

        goalService.ensureArmed(SID);

        verify(hookRegistry, times(0)).addSessionHook(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("ensureArmed：延迟评估期间不重挂（故意摘掉，等后台归位）")
    void ensureArmed_deferred_doesNotRearm() {
        goalService.setGoal(SID, "条件", 0L);
        goalService.deferEvaluationForBackground(SID);
        int addsBefore = stopHooks.get(SID) == null ? 0 : stopHooks.get(SID).size();

        goalService.ensureArmed(SID);

        assertThat(stopHooks.get(SID) == null ? 0 : stopHooks.get(SID).size())
            .as("延迟评估中不该补挂").isEqualTo(addsBefore);
    }

    @Test
    @DisplayName("restoreForResume：内存说已挂但注册表已空 ⇒ 重新挂（否则 fresh 守卫会永久短路）")
    void restoreForResume_staleMemory_rearms() {
        goalService.setGoal(SID, "条件", 0L);
        hookRegistry.clearSessionHooks(SID);

        goalService.restoreForResume(SID, 0L);

        assertThat(goalService.isArmed(SID)).as("必须自愈").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 评估器连续失败 ⇒ gave_up（对齐 CC su() 的 announced:"gave_up"）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 造一条「评估器自身失败」的 Stop hook 结果 · hook 身份必须是本会话目标的 PromptHook，
     * 否则 {@code consumeStopHookResult} 会在身份校验处直接 IGNORED（根本走不到重试分支）。
     */
    private static GenericHook.HookResult evaluatorErrorResult(String condition) {
        return new GenericHook.HookResult(
            false, null, java.util.List.of(), java.util.List.of(), null, null, null,
            null, null, GenericHook.HookOutcome.NON_BLOCKING_ERROR, "evaluator boom", null, null,
            new PromptHook(condition, null, null, null, null, null),
            null, null, null, null);
    }

    @Test
    @DisplayName("重试次数未达上限 ⇒ 仍在 ACTIVE 并排下一次重试（守卫不得提前放弃）")
    void evaluatorError_underLimit_schedulesRetry() {
        // 已重试 1 次（上限 3）⇒ 还应再排一次
        columnStore.put(SID, new SessionGoal(
            "e2e 全绿", SessionGoal.Phase.ACTIVE, 0, 1L, 0L, null,
            null, null, null, 999L, 1));

        GoalService.GoalVerdict v = goalService.consumeStopHookResult(
            SID, evaluatorErrorResult("e2e 全绿"));

        assertThat(v.kind()).isEqualTo(GoalService.GoalVerdict.Kind.EVALUATOR_ERROR);
        SessionGoal after = columnStore.get(SID);
        assertThat(after.phase()).as("未到上限不该暂停").isEqualTo(SessionGoal.Phase.ACTIVE);
        assertThat(after.retryAt()).as("应排出下一次重试").isNotNull();
        assertThat(after.retryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("重试次数达上限 ⇒ 转 PAUSED 并给出「发消息继续」原因（不再静默留在 ACTIVE）")
    void evaluatorError_retryExhausted_pausesGoal() {
        columnStore.put(SID, new SessionGoal(
            "e2e 全绿", SessionGoal.Phase.ACTIVE, 2, 1L, 0L, "上一轮判定",
            null, null, null, 999L, GoalCheckinConfig.MAX_AUTO_RETRIES));

        goalService.consumeStopHookResult(SID, evaluatorErrorResult("e2e 全绿"));

        SessionGoal after = columnStore.get(SID);
        assertThat(after.phase())
            .as("旧实现只打 warn 留在 ACTIVE —— 用户会看到一个永不推进的目标头")
            .isEqualTo(SessionGoal.Phase.PAUSED);
        assertThat(after.retryAt()).as("放弃后不再排重试").isNull();
        assertThat(after.retryCount()).as("重试计数应清空（否则下次继续时带着旧计数起算）").isNull();
        assertThat(after.lastReason()).as("原因要能让用户看懂").contains("已暂停").contains("发消息继续");
        assertThat(after.iterations())
            .as("评估器一次都没评出结论 ⇒ 不该算轮次（与撞 cap 的暂停不同）")
            .isEqualTo(2);
        assertThat(after.condition()).as("条件保留，发消息即可继续").isEqualTo("e2e 全绿");
    }

    @Test
    @DisplayName("撞 cap 的暂停仍算一轮（既有语义不变）")
    void recordPaused_defaultCountsIteration() {
        columnStore.put(SID, new SessionGoal("c", SessionGoal.Phase.ACTIVE, 4, 1L, 0L, null));

        SessionGoal paused = goalService.recordPaused(SID, "goal checks kept finding it unmet this turn");

        assertThat(paused.iterations()).isEqualTo(5);
        assertThat(columnStore.get(SID).phase()).isEqualTo(SessionGoal.Phase.PAUSED);
    }

    // ════════════════════════════════════════════════════════════════════
    // 查询
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getGoal / hasActiveGoal：列为准")
    void getGoal_readsColumn() {
        assertThat(goalService.getGoal(SID)).isNull();
        assertThat(goalService.hasActiveGoal(SID)).isFalse();

        goalService.setGoal(SID, "条件", 0L);
        assertThat(goalService.getGoal(SID)).isNotNull();
        assertThat(goalService.hasActiveGoal(SID)).isTrue();

        goalService.recordAchieved(SID);
        assertThat(goalService.hasActiveGoal(SID)).as("达成后不再是 active").isFalse();
    }
}
