package com.nexusai.application.agent.team;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LeaderPermissionBridge 定向测试 · 对齐 CC utils/swarm/leaderPermissionBridge.ts（2.1.88 单槽 →
 * [T2] 按会话分桶）。
 *
 * <p>WHY (规则九)：bridge 是「权限委派双路径」leader ToolUseConfirm 队列分支的注册中枢 ——
 * leader 会话注册 {@code setToolUseConfirmQueue} / {@code setToolPermissionContext}，in-process runner
 * 经 {@code getLeaderToolUseConfirmQueue(sessionId)} 判空决定走 leader 队列还是 mailbox fallback
 * （CC inProcessRunner.ts:195-334）。若 get 返回 null 或 unregister 不生效，双路径选择错误。
 *
 * <p><b>[T2] 改前本类两个用例只能证明「单槽 register→get→unregister」</b>——**改前形态下它们必然全绿**
 * （CC 2.1.88 单槽语义），因此对本次要修的缺陷零鉴别力：进程级单槽在多会话下 A/B 互相覆盖。
 * 本类新增的 <b>会话隔离</b> 用例是关键判据 —— 反向实验（把 {@link LeaderPermissionBridge} 的两个
 * Map 桶改回单个 {@code static volatile} 槽 + 忽略 sessionId 入参）会让
 * {@link #toolUseConfirmQueue_twoSessions_isolated} 与
 * {@link #unregister_onlyRemovesOwnSessionBucket} 立刻 RED，而「单会话三态」用例仍绿。
 */
class LeaderPermissionBridgeTest {

    /** 两个「不同会话」的桶键（多会话常驻 JVM 的常态）。 */
    private static final String SESSION_A = "sess-aaaa1111";
    private static final String SESSION_B = "sess-bbbb2222";

    @AfterEach
    void tearDown() {
        LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue(SESSION_A);
        LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue(SESSION_B);
        LeaderPermissionBridge.unregisterLeaderSetToolPermissionContext(SESSION_A);
        LeaderPermissionBridge.unregisterLeaderSetToolPermissionContext(SESSION_B);
    }

    @Test
    @DisplayName("单会话三态：register → get 同一 setter → unregister 后为空（CC :28-40）")
    void toolUseConfirmQueue_registerGetUnregister() {
        // WHY: CC leaderPermissionBridge.ts:28-40 register/get/unregister 三态（[T2] 起按会话）。
        assertNull(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_A), "初始应无注册 setter");

        LeaderPermissionBridge.SetToolUseConfirmQueueFn setter = updater -> {
            List<LeaderPermissionBridge.ToolUseConfirmEntry> next = updater.apply(List.of());
            assertNotNull(next);
        };
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(SESSION_A, setter);
        assertSame(setter, LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_A),
                "get 应返回注册的同一 setter");

        assertTrue(LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue(SESSION_A),
                "本会话确有注册 → 应返回 removed=true");
        assertNull(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_A), "unregister 后应返回 null");
    }

    @Test
    @DisplayName("[T2 核心] 两会话各自注册 ⇒ 互不覆盖：A 的 setter 不被 B 顶掉、跨会话查询不借用")
    void toolUseConfirmQueue_twoSessions_isolated() {
        // WHY（本任务的存在理由）：改前是**进程级单槽** ⇒ 第二会话注册即覆盖第一会话
        //（last-writer-wins），且跨会话查询会取到**别的会话的确认表面** —— 常驻多会话 JVM 下
        //  团队权限请求会被推给错误会话的 UI（team 权限断裂）。本用例钉死「同键覆盖、异键隔离」。
        AtomicInteger callCountA = new AtomicInteger();
        AtomicInteger callCountB = new AtomicInteger();
        LeaderPermissionBridge.SetToolUseConfirmQueueFn setterA = updater -> callCountA.incrementAndGet();
        LeaderPermissionBridge.SetToolUseConfirmQueueFn setterB = updater -> callCountB.incrementAndGet();

        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(SESSION_A, setterA);
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(SESSION_B, setterB);

        assertSame(setterA, LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_A),
                "会话 A 的桶必须是 A 的 setter；若被 B 覆盖 ⇒ 本断言 RED（改回单槽即复现）");
        assertSame(setterB, LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_B),
                "会话 B 的桶必须是 B 的 setter");

        // 行为级隔离：触发 A 的 setter 不得惊动 B 的 setter（表面各服务各会话）
        LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_A).apply(prev -> prev);
        assertEquals(1, callCountA.get(), "A 的 setter 被调用 1 次");
        assertEquals(0, callCountB.get(), "B 的 setter 不得被跨会话调用");

        // 未注册的会话：⛔ 不得借用任何已注册会话的 setter（无全局兜底桶）
        assertNull(LeaderPermissionBridge.getLeaderToolUseConfirmQueue("sess-cccc3333"),
                "未注册会话必须为 null（⛔ 不得回落全局槽 —— 那正是要修的串台根因）");
    }

    @Test
    @DisplayName("[T2] 同会话重复注册 = 覆盖（保留 CC 单会话内 last-writer-wins 语义）")
    void toolUseConfirmQueue_sameSession_overwrites() {
        // WHY: CC 原语义是「第二次注册覆盖第一次」（REPL 重挂载即替换表面）。分桶只把覆盖范围
        //  收窄到**本会话**，不得把它改成「first-wins」（会让重挂载后的表面永远失效）。
        LeaderPermissionBridge.SetToolUseConfirmQueueFn first = updater -> {
        };
        LeaderPermissionBridge.SetToolUseConfirmQueueFn second = updater -> {
        };
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(SESSION_A, first);
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(SESSION_A, second);
        assertSame(second, LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_A),
                "同会话再注册应覆盖为最新 setter");
    }

    @Test
    @DisplayName("[T2] 无会话标识 ⇒ 拒绝注册（fail-loud，⛔ 不造全局兜底桶）")
    void register_withoutSession_isRejected() {
        // WHY: null/blank sessionId 若被容忍（写入某种「全局桶」），就等于把本任务要消除的
        //   无键全局表面原地保留；而静默丢弃又会让调用方误以为注册成功。故必须拒绝 + 留痕。
        int before = LeaderPermissionBridge.toolUseConfirmQueueSessionBucketCount();
        LeaderPermissionBridge.SetToolUseConfirmQueueFn setter = updater -> {
        };

        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(null, setter);
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue("   ", setter);

        assertEquals(before, LeaderPermissionBridge.toolUseConfirmQueueSessionBucketCount(),
                "无会话标识的注册不得产生任何桶");
        assertNull(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(null), "null 会话查询恒 null");
        assertNull(LeaderPermissionBridge.getLeaderToolUseConfirmQueue("   "), "blank 会话查询恒 null");
    }

    @Test
    @DisplayName("[T2] unregister 只摘本会话桶，邻会话不受影响（注册/注销成对的最小证据）")
    void unregister_onlyRemovesOwnSessionBucket() {
        // WHY:「注销必须成对、且不得误伤」—— 若 unregister 清全局（或忽略 sessionId），
        //   会话 A 的解散会顺手摘掉会话 B 的确认表面 ⇒ B 的团队权限恒自动 deny（静默断裂）。
        LeaderPermissionBridge.SetToolUseConfirmQueueFn setterA = updater -> {
        };
        LeaderPermissionBridge.SetToolUseConfirmQueueFn setterB = updater -> {
        };
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(SESSION_A, setterA);
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(SESSION_B, setterB);

        assertTrue(LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue(SESSION_A), "A 桶应被摘除");

        assertNull(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_A), "A 桶已空");
        assertSame(setterB, LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_B),
                "B 桶必须原样保留（误清全局即 RED）");
        assertFalse(LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue(SESSION_A),
                "重复注销幂等（无桶 → false）");
    }

    @Test
    @DisplayName("[T2] clearSession（会话结束）两个桶一并摘除，邻会话不受影响")
    void clearSession_removesBothRegistriesOfThatSession() {
        // WHY: 会话结束点（SessionService.delete）必须一次摘干净本会话的两个表面注册，
        //   否则 ① 常驻 JVM 里桶无界增长 ② 同 id 复用时取到上一世会话的表面（串台第二形态）。
        LeaderPermissionBridge.SetToolUseConfirmQueueFn queueSetter = updater -> {
        };
        LeaderPermissionBridge.SetToolPermissionContextFn ctxSetter = (context, preserveMode) -> {
        };
        LeaderPermissionBridge.SetToolUseConfirmQueueFn otherQueueSetter = updater -> {
        };
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(SESSION_A, queueSetter);
        LeaderPermissionBridge.registerLeaderSetToolPermissionContext(SESSION_A, ctxSetter);
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(SESSION_B, otherQueueSetter);

        LeaderPermissionBridge.clearSession(SESSION_A);

        assertNull(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_A), "本会话队列 setter 应摘除");
        assertNull(LeaderPermissionBridge.getLeaderSetToolPermissionContext(SESSION_A),
                "本会话权限上下文 setter 应摘除");
        assertSame(otherQueueSetter, LeaderPermissionBridge.getLeaderToolUseConfirmQueue(SESSION_B),
                "邻会话桶不受 clearSession 影响（⛔ 不得清全局）");

        // 幂等 + 空值守护（会话删除路径可能拿到 null id）
        LeaderPermissionBridge.clearSession(SESSION_A);
        LeaderPermissionBridge.clearSession(null);
    }

    @Test
    @DisplayName("权限上下文 setter 三态 + 跨会话隔离（CC :42-54）")
    void setToolPermissionContext_registerGetUnregister() {
        // WHY: CC leaderPermissionBridge.ts:42-54 权限上下文 setter 三态（context 收紧为
        // ToolPermissionContext）+ [T2] 会话隔离（同 setter 桶的判据）。
        assertNull(LeaderPermissionBridge.getLeaderSetToolPermissionContext(SESSION_A));

        ToolPermissionContext expectedCtx = ToolPermissionContext.strict(PermissionMode.DEFAULT);
        final Object[] captured = new Object[2];
        LeaderPermissionBridge.SetToolPermissionContextFn setterA =
                (ToolPermissionContext context, boolean preserveMode) -> {
                    captured[0] = context;
                    captured[1] = preserveMode;
                };
        LeaderPermissionBridge.SetToolPermissionContextFn setterB = (context, preserveMode) -> {
        };
        LeaderPermissionBridge.registerLeaderSetToolPermissionContext(SESSION_A, setterA);
        LeaderPermissionBridge.registerLeaderSetToolPermissionContext(SESSION_B, setterB);

        assertSame(setterA, LeaderPermissionBridge.getLeaderSetToolPermissionContext(SESSION_A));
        assertSame(setterB, LeaderPermissionBridge.getLeaderSetToolPermissionContext(SESSION_B),
                "B 会话的权限上下文 setter 不得被 A 覆盖");

        LeaderPermissionBridge.getLeaderSetToolPermissionContext(SESSION_A).apply(expectedCtx, true);
        assertSame(expectedCtx, captured[0]);
        assertEquals(true, captured[1]);

        assertTrue(LeaderPermissionBridge.unregisterLeaderSetToolPermissionContext(SESSION_A));
        assertNull(LeaderPermissionBridge.getLeaderSetToolPermissionContext(SESSION_A));
        assertSame(setterB, LeaderPermissionBridge.getLeaderSetToolPermissionContext(SESSION_B),
                "只摘本会话桶");
    }
}
