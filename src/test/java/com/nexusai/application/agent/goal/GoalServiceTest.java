package com.nexusai.application.agent.goal;

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

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        hookRegistry = mock(HookRegistry.class);
        columnStore = new java.util.HashMap<>();

        when(sessionService.getGoal(anyString())).thenAnswer(inv ->
            columnStore.get(inv.getArgument(0, String.class)));
        org.mockito.Mockito.doAnswer(inv -> {
            columnStore.put(inv.getArgument(0, String.class), inv.getArgument(1, SessionGoal.class));
            return null;
        }).when(sessionService).setGoal(anyString(), any());

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
    @DisplayName("restoreForResume：active 目标 → 重挂 hook + 重置 setAt/tokensAtStart 基线")
    void restoreForResume_active_restoreAndResetBaseline() {
        goalService.setGoal(SID, "条件", 1000L);
        // 模拟进程重启：内存 armed 态丢失
        goalService.clearGoal(SID);   // 摘 hook + 清列
        columnStore.put(SID, new SessionGoal("条件", SessionGoal.Phase.ACTIVE, 3, 999L, 1000L, "旧 reason"));

        SessionGoal restored = goalService.restoreForResume(SID, 5555L);

        assertThat(restored).isNotNull();
        assertThat(restored.iterations()).as("CC：恢复时轮数重置为 0").isZero();
        assertThat(restored.tokensAtStart()).as("CC：恢复时 token 基线重置").isEqualTo(5555L);
        assertThat(restored.lastReason()).isNull();
        assertThat(goalService.isArmed(SID)).isTrue();
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
