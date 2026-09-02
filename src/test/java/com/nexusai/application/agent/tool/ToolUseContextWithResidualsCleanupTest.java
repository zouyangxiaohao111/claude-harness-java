package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session M.1.1 残余字段决策关闭 · RED→GREEN 验证（Pattern #14）.
 *
 * <p><b>背景</b>（计划 deleteList M.1.1-R1/R2/R4 + Session M.4.4 收尾 C2）: master 上
 * with() 仍有 5 处与 CC 显式不符的字段决策（带"后续 Stage 4.0 再对齐"TODO 注释 =
 * 兼容壳）:
 * <ol>
 *   <li>{@code inProgressToolUseIDs}: 父透传 → 必须 noop（CC forkedAgent.ts:425
 *       {@code setInProgressToolUseIDs: () => {}}）</li>
 *   <li>{@code updateFileHistoryState}: 父透传 → 必须 noop（CC forkedAgent.ts:432
 *       {@code updateFileHistoryState: () => {}}）</li>
 *   <li>{@code requireCanUseTool}: 父值兜底 → 必须纯 override（CC forkedAgent.ts:460
 *       {@code requireCanUseTool: overrides?.requireCanUseTool}, override 缺省=undefined=falsy）</li>
 *   <li>{@code setStreamMode}: 父透传 → 必须 null（CC forkedAgent.ts:440
 *       {@code setStreamMode: undefined}, 子 agent 不能控制父 UI; compact ctor 兜底 noop）</li>
 *   <li>{@code setSDKStatus}: 父透传 → 必须 null（CC forkedAgent.ts:441
 *       {@code setSDKStatus: undefined}, 子 agent 不能控制父 UI; compact ctor 兜底 noop）</li>
 * </ol>
 *
 * <p><b>RED 验证策略</b>: 本测试类编写后先对旧实现（父透传/父兜底）跑 → 期望 FAIL；
 * 改造后再跑 → 期望 PASS. 两份输出贴 YAML.
 */
@DisplayName("Session M.1.1 残余 · with() 5 处字段决策关闭（noop / 纯 override / null）")
class ToolUseContextWithResidualsCleanupTest {

    /** 14 参 canonical 空 overrides（全部 null, 含 requireCanUseTool）· [B 返工 R-2] 12 参壳已删. */
    private static ToolUseContext.SubagentContextOverrides emptyOverrides() {
        return new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);
    }

    /**
     * 构造带非默认 callback / requireCanUseTool 的父 ctx（canonical 46 参, 其余字段
     * 全部对齐 compact ctor 兜底值）.
     */
    private static ToolUseContext buildParent(
            boolean requireCanUseTool,
            Function<Set<String>, Set<String>> inProgress,
            Consumer<FileHistoryState> updateFileHistoryState,
            Consumer<SpinnerMode> setStreamMode,
            Consumer<SDKStatus> setSDKStatus) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", null,
            inProgress, Map.of(), null,
            null, null, setStreamMode, setSDKStatus,
            null, null, null, null, null, updateFileHistoryState, null, null, null, null,
            false, null, null, null, null, null, requireCanUseTool, false,
            null, null, null, null, null, null);
    }

    // ═════════════════════ Test 1: inProgressToolUseIDs 总 noop ═════════════════════

    @Test
    @DisplayName("with(): inProgressToolUseIDs 总 noop, 即便父有 tracker（CC :425 () => {}）")
    void with_inProgressToolUseIDs_alwaysNoopEvenWithParent() {
        Set<String> parentRecorded = ConcurrentHashMap.newKeySet();
        Function<Set<String>, Set<String>> parentTracker = prev -> {
            parentRecorded.addAll(prev);
            return Set.copyOf(prev);
        };
        ToolUseContext parent = buildParent(false, parentTracker, fhs -> { }, null, null);

        ToolUseContext child = parent.with(emptyOverrides());

        // noop 形态与 compact ctor 默认一致 (R32B8 断言 s -> Set.of()): apply 返回空 Set
        Set<String> snapshot = child.inProgressToolUseIDs().apply(Set.of("toolu_parent"));
        assertThat(snapshot)
            .as("子 ctx inProgressToolUseIDs 必须 noop（CC forkedAgent.ts:425 setInProgressToolUseIDs: () => {}）")
            .isEmpty();
        assertThat(parentRecorded)
            .as("noop 不得触发父 tracker（CC 隔离语义: 子 agent 通知不得冒泡到父）")
            .isEmpty();
    }

    // ═════════════════════ Test 2: updateFileHistoryState 总 noop ═════════════════════

    @Test
    @DisplayName("with(): updateFileHistoryState 总 noop, 即便父有 updater（CC :432 () => {}）")
    void with_updateFileHistoryState_alwaysNoopEvenWithParent() {
        AtomicInteger parentCalls = new AtomicInteger();
        Consumer<FileHistoryState> parentUpdater = fhs -> parentCalls.incrementAndGet();
        ToolUseContext parent = buildParent(false, s -> Set.of(), parentUpdater, null, null);

        ToolUseContext child = parent.with(emptyOverrides());

        // noop 忽略入参（null 也不抛）; 若仍是父透传, parentCalls 会变成 1 → 测试 FAIL
        child.updateFileHistoryState().accept(null);
        assertThat(parentCalls)
            .as("子 ctx updateFileHistoryState 必须 noop（CC forkedAgent.ts:432 updateFileHistoryState: () => {}）")
            .hasValue(0);
    }

    // ═════════════════════ Test 3: requireCanUseTool 纯 override ═════════════════════

    @Test
    @DisplayName("with(): requireCanUseTool 纯 override, 父 true 不兜底（CC :460 undefined→falsy）")
    void with_requireCanUseTool_onlyOverrideNoParentFallback() {
        ToolUseContext parent = buildParent(true, s -> Set.of(), fhs -> { }, null, null);

        ToolUseContext child = parent.with(emptyOverrides());

        assertThat(child.requireCanUseTool())
            .as("父 requireCanUseTool=true 不得兜底给子（CC forkedAgent.ts:460 requireCanUseTool: overrides?.requireCanUseTool, override 缺省=undefined=falsy）")
            .isFalse();
    }

    @Test
    @DisplayName("with(): requireCanUseTool override=true 必须生效（CC :460）")
    void with_requireCanUseTool_overrideTrueWins() {
        ToolUseContext parent = buildParent(false, s -> Set.of(), fhs -> { }, null, null);

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, Boolean.TRUE));

        assertThat(child.requireCanUseTool())
            .as("override requireCanUseTool=true 必须生效（CC forkedAgent.ts:460）")
            .isTrue();
    }

    // ═════════════════════ Test 5: setStreamMode 不父透传 ═════════════════════

    @Test
    @DisplayName("with(): setStreamMode 总 null→compact ctor noop, 父实例不透传（CC :440 undefined）")
    void with_uiCallbacks_setStreamModeNotParentInstance() {
        AtomicInteger parentCalls = new AtomicInteger();
        Consumer<SpinnerMode> parentStreamMode = m -> parentCalls.incrementAndGet();
        ToolUseContext parent =
            buildParent(false, s -> Set.of(), fhs -> { }, parentStreamMode, null);

        ToolUseContext child = parent.with(emptyOverrides());

        // child 拿到 compact ctor 兜底 noop（CC :440 setStreamMode: undefined → Java null → noop）
        assertThat(child.setStreamMode())
            .as("子 ctx setStreamMode 不得是父实例（CC forkedAgent.ts:440 setStreamMode: undefined）")
            .isNotSameAs(parentStreamMode);
        child.setStreamMode().accept(SpinnerMode.REQUESTING);
        assertThat(parentCalls)
            .as("子 ctx setStreamMode 必须 noop, 不得触发父 callback")
            .hasValue(0);
    }

    // ═════════════════════ Test 6: setSDKStatus 不父透传 ═════════════════════

    @Test
    @DisplayName("with(): setSDKStatus 总 null→compact ctor noop, 父实例不透传（CC :441 undefined）")
    void with_uiCallbacks_setSDKStatusNotParentInstance() {
        AtomicInteger parentCalls = new AtomicInteger();
        Consumer<SDKStatus> parentSDKStatus = s -> parentCalls.incrementAndGet();
        ToolUseContext parent =
            buildParent(false, s -> Set.of(), fhs -> { }, null, parentSDKStatus);

        ToolUseContext child = parent.with(emptyOverrides());

        assertThat(child.setSDKStatus())
            .as("子 ctx setSDKStatus 不得是父实例（CC forkedAgent.ts:441 setSDKStatus: undefined）")
            .isNotSameAs(parentSDKStatus);
        child.setSDKStatus().accept(SDKStatus.COMPACTING);
        assertThat(parentCalls)
            .as("子 ctx setSDKStatus 必须 noop, 不得触发父 callback")
            .hasValue(0);
    }
}
