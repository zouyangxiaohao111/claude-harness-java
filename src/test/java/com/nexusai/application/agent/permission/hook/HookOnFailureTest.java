package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Wave2-D] {@code onFailure: continue|block} 执行语义 · 对齐 claude.exe v2.1.325
 * {@code pg()} 命令子 schema 原文：失败（起不来 / 超时 / 退出码非 0、2 / JSON 非法）时
 * {@code block} 视同 exit 2 拦住事件守护的动作；缺省 {@code continue}；<b>忽略 async 与
 * Stop/SubagentStop/TaskCompleted/TeammateIdle</b>。
 *
 * <p><b>WHY</b>: 这是安全语义 —— 用户配 {@code onFailure:"block"} 的守卫 hook 若被当成
 * fail-open，会静默放行本该拦下的动作（探查登记的安全 P0）。本测试锁住「哪些事件/哪种结果
 * 会升级为 BLOCKING」这一可观察契约。
 */
@DisplayName("[Wave2-D] onFailure=block 触发条件与结果升级")
class HookOnFailureTest {

    private static CommandHook blockHook() {
        return new CommandHook("hook-cmd", null, null, null, null, null, null, null,
            null, "block", null, null, null);
    }

    private static CommandHook continueHook() {
        return new CommandHook("hook-cmd", null, null, null, null, null, null, null,
            null, "continue", null, null, null);
    }

    private static CommandHookExecutor.CommandHookResult result(int status, boolean aborted, boolean backgrounded,
                                            boolean failClosed) {
        return new CommandHookExecutor.CommandHookResult("", "boom", "boom", status, aborted, backgrounded, failClosed);
    }

    // ── 1. 触发条件 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("block + PreToolUse → 生效")
    void failClosed_appliesToToolEvents() {
        assertThat(CommandHookExecutor.isFailClosedOnFailure(blockHook(), HookEventType.PRE_TOOL_USE))
            .isTrue();
    }

    @Test
    @DisplayName("continue → 不生效（缺省语义）")
    void continue_doesNotFailClosed() {
        assertThat(CommandHookExecutor.isFailClosedOnFailure(continueHook(), HookEventType.PRE_TOOL_USE))
            .isFalse();
    }

    @Test
    @DisplayName("onFailure 缺失 → 不生效")
    void missingOnFailure_doesNotFailClosed() {
        CommandHook hook = new CommandHook("hook-cmd", null, null, null, null, null, null, null);
        assertThat(CommandHookExecutor.isFailClosedOnFailure(hook, HookEventType.PRE_TOOL_USE))
            .isFalse();
    }

    @Test
    @DisplayName("CC 忽略名单 4 事件即使配 block 也不生效")
    void ignoredEvents_neverFailClosed() {
        CommandHook hook = blockHook();
        assertThat(CommandHookExecutor.isFailClosedOnFailure(hook, HookEventType.STOP)).isFalse();
        assertThat(CommandHookExecutor.isFailClosedOnFailure(hook, HookEventType.SUBAGENT_STOP)).isFalse();
        assertThat(CommandHookExecutor.isFailClosedOnFailure(hook, HookEventType.TASK_COMPLETED)).isFalse();
        assertThat(CommandHookExecutor.isFailClosedOnFailure(hook, HookEventType.TEAMMATE_IDLE)).isFalse();
    }

    @Test
    @DisplayName("hook=null → 不生效；event=null + block → 生效（无事件上下文不参与忽略名单）")
    void nullInputs() {
        assertThat(CommandHookExecutor.isFailClosedOnFailure(null, HookEventType.PRE_TOOL_USE)).isFalse();
        assertThat(CommandHookExecutor.isFailClosedOnFailure(blockHook(), null)).isTrue();
    }

    // ── 2. 结果升级 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("failClosed=true + 非 0/2 退出 → BLOCKING 且带 blockingError")
    void failClosed_upgradesNonBlockingExitToBlocking() {
        GenericHook.HookResult r = CommandHookExecutor.toHookResult(
            result(1, false, false, true), blockHook());

        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.BLOCKING);
        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().blockingError()).contains("boom");
    }

    @Test
    @DisplayName("failClosed=false + 非 0/2 退出 → 仍是 NON_BLOCKING_ERROR")
    void notFailClosed_keepsNonBlocking() {
        GenericHook.HookResult r = CommandHookExecutor.toHookResult(
            result(1, false, false, false), continueHook());

        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("failClosed=true + exit 0 → 仍是 SUCCESS（成功不升级）")
    void successNeverUpgraded() {
        GenericHook.HookResult r = CommandHookExecutor.toHookResult(
            result(0, false, false, true), blockHook());

        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("exit 2 → BLOCKING（既有语义，与 onFailure 无关）")
    void exitTwoBlocksRegardless() {
        GenericHook.HookResult r = CommandHookExecutor.toHookResult(
            result(2, false, false, false), continueHook());

        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.BLOCKING);
        assertThat(r.blockingError()).isNotNull();
    }

    @Test
    @DisplayName("backgrounded=true + failClosed=true → 不得 BLOCKING（async 忽略）")
    void asyncNeverFailCloses() {
        GenericHook.HookResult r = CommandHookExecutor.toHookResult(
            result(1, false, true, true), blockHook());

        assertThat(r.outcome()).isNotEqualTo(GenericHook.HookOutcome.BLOCKING);
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("aborted=true + failClosed=true → CANCELLED（取消不升级为阻断）")
    void abortedNeverFailCloses() {
        GenericHook.HookResult r = CommandHookExecutor.toHookResult(
            result(1, true, false, true), blockHook());

        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.CANCELLED);
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("JSON 校验失败 + failClosed=true → BLOCKING（schema 不合法也受 onFailure 约束）")
    void invalidJsonUpgrades() {
        // `{oops` 这类不可解析 stdout 按 CC 是「静默 plainText → hook_success」，不算失败；
        // 真正的失败是「可解析但 schema 非法」→ validationError → non_blocking → 升级。
        CommandHookExecutor.CommandHookResult bad = new CommandHookExecutor.CommandHookResult(
            "{\"decision\":\"nope\"}", "", "{\"decision\":\"nope\"}", 0, false, false, true);
        GenericHook.HookResult r = CommandHookExecutor.toHookResult(bad, blockHook());

        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.BLOCKING);
        assertThat(r.blockingError()).isNotNull();
    }

    /** 断言用: 4 类 hook 的 onFailure 字段读取路径一致（命令行 hook 无特殊化）。 */
    @Test
    @DisplayName("args 为空列表仍视为 exec 形式（args()!=null 判定）")
    void emptyArgsIsStillExecForm() {
        CommandHook hook = new CommandHook("hook-cmd", null, null, null, null, null, null, null,
            List.of(), "block", null, null, null);
        assertThat(hook.args()).isNotNull().isEmpty();
        assertThat(CommandHookExecutor.isFailClosedOnFailure(hook, HookEventType.PRE_TOOL_USE)).isTrue();
    }
}
