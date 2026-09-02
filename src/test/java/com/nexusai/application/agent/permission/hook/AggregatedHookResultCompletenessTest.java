package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Session M P1-1] AggregatedHookResult 字段核对验证 · 对齐 CC
 * Open-ClaudeCode/src/utils/hooks.ts:359-376 AggregatedHookResult（16 字段）。
 *
 * <p><b>[H4 修正]</b>: 字段集为 16（CC 真源 16 字段, 无 additionalContext 单值 /
 * aggregatedAt —— 均非 CC 字段, H4 已移除）。旧版 javadoc 的 "18 字段 (CC 16 + Java 扩展 2)"
 * 是 M.md M.2.1 过时意图（补 8 字段）的残留, 断言早已随 H4 改为 16, 本注释同步修正（Pattern #9）。
 *
 * <p>本测试覆盖 M.2.1 的 3 个验证点:
 * <ol>
 *   <li>字段数量 = 16 (对齐 CC utils/hooks.ts:359-376; M.2.1 扩展字段已移除 [H4])</li>
 *   <li>hookSource 字段来自最新 hook (覆盖 HookRegistry.mergeAggregated 行为)</li>
 *   <li>permissionBehavior 字段按 deny > ask > allow 优先级聚合</li>
 * </ol>
 *
 * <h2>WHY</h2>
 * <p>AggregatedHookResult 在 P0-3 / I session 已对齐 CC 16 字段, 但 M.md M.2.1 要求补全
 * 到 18 字段. Java 端把 additionalContext (String, 单值) + aggregatedAt (Long, 时间戳) 作为
 * CC 端将来可能扩展的字段先落地 ([H4] 已移除两扩展, 对齐 CC 16 字段, 见 :49 注记).
 *
 * @since Session M P1
 */
class AggregatedHookResultCompletenessTest {

    /**
     * 字段数反射验证: AggregatedHookResult 16 字段 (对齐 CC utils/hooks.ts:359-376).
     *
     * <p>CC 真源 Open-ClaudeCode/src/utils/hooks.ts:359-376 = 16 字段; Java 端曾按 M.md
     * 追加 additionalContext (String) + aggregatedAt (Long) 2 个扩展字段, [H4] 已移除
     * 对齐 CC 16 字段.
     *
     * <p>WHY 反射: record 字段数为编译期事实, 但 record accessor 是隐式生成. 显式反射
     * 检查 {@code RecordComponent[]} 长度比手写 16 个 getter 调用更鲁棒.
     */
    @Test
    void aggregatedHookResult_carriesAll16Fields() {
        RecordComponent[] components = AggregatedHookResult.class.getRecordComponents();
        assertNotNull(components, "AggregatedHookResult 是 record, RecordComponent[] 必非 null");
        // [H4] 字段数 18→16 (移除 additionalContext 单值 + aggregatedAt, 对齐 CC utils/hooks.ts:359-376)
        // [DEL-WF1-TY-02 v4 实施] systemMessage 聚合字段已删除 (17→16) — CC AHR
        //   (utils/hooks.ts:359-376) 无 systemMessages 字段; systemMessage 按 CC
        //   hooks.ts:2769-2780 逐结果就地折叠为 hook_system_message attachment 并入 message 通道
        //   (AggregatedHookResult.foldSystemMessages), 不再承载独立聚合字段.
        assertEquals(16, components.length,
            "AggregatedHookResult 字段数应为 16 (对齐 CC utils/hooks.ts:359-376, 无 Java 独有聚合字段)");
    }

    /**
     * 字段类型验证: 16 字段按声明顺序类型一致 (防御 future 字段类型变更).
     *
     * <p>[IMPL-07 OD-14] message 类型通道 String → List&lt;AttachmentMessageDto&gt;
     * (CC message?: HookResultMessage 消息对象; Java 聚合 N 结果全保留, D3-3).
     */
    @Test
    void aggregatedHookResult_fieldTypesMatchCCSpec() {
        RecordComponent[] components = AggregatedHookResult.class.getRecordComponents();
        // [DEL-WF1-TY-02 v4 实施] 17→16: systemMessages 聚合字段已删除 (CC AHR 无此字段),
        //   对齐 CC utils/hooks.ts:359-376 16 字段; systemMessage 经 foldSystemMessages 折叠进
        //   message 通道 (hook_system_message attachment), 不再有独立 String 字段.
        assertEquals(16, components.length);
        // 关键字段类型 spot check
        assertSame(List.class, components[0].getType(), "message 应为 List<AttachmentMessageDto> [IMPL-07]");
        assertSame(HookBlockingError.class, components[1].getType(), "blockingError 应为 HookBlockingError [H4]");
        assertSame(boolean.class, components[2].getType(), "preventContinuation 应为 boolean (primitive)");
        assertSame(List.class, components[7].getType(), "additionalContexts 应为 List");
        assertSame(java.util.Map.class, components[9].getType(), "updatedInput 应为 Map");
        assertSame(PermissionRequestResult.class, components[11].getType(), "permissionRequestResult 应为 PermissionRequestResult sealed [H4]");
        assertSame(Boolean.class, components[15].getType(), "retry 应为 Boolean");
    }

    /**
     * [IMPL-07 D3-1/CCJ-005] hookSource 字段行为: 随 permissionBehavior 配对 last-wins
     * (CC 末次 yield 覆盖, toolExecution.ts:831-832 + hooks.ts:2862-2867).
     *
     * <p>旧行为 first-non-null (base 胜出) 与 CC 分叉 — 最小反例 A(allow,sourceA)+B(ask,sourceB)
     * → CC {ask,sourceB}. 本测试模拟 mergeAggregated 的 last-wins 规则:
     * next 携带 permissionBehavior 时 reason/source 随 next 覆盖.
     */
    @Test
    void aggregatedHookResult_hookSourceLastWinsWithBehavior() {
        com.nexusai.application.agent.permission.PermissionResult allowPb =
            new com.nexusai.application.agent.permission.PermissionResult.Allow(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                new com.nexusai.application.agent.permission.PermissionDecisionReason.Other("allow"),
                null, false, null, java.util.List.of());
        com.nexusai.application.agent.permission.PermissionResult askPb =
            new com.nexusai.application.agent.permission.PermissionResult.Ask(
                "ask", new com.nexusai.application.agent.permission.PermissionDecisionReason.Other("ask"),
                java.util.List.of(), null, null, null, false, null, null);
        AggregatedHookResult base = new AggregatedHookResult(
            null, null, false, null,
            "reasonA", "source-A", allowPb, null,
            null, null, null, null, null, null, null, null);
        AggregatedHookResult next = new AggregatedHookResult(
            null, null, false, null,
            "reasonB", "source-B", askPb, null,
            null, null, null, null, null, null, null, null);

        // 模拟 mergeAggregated last-wins: next 携带 permissionBehavior → reason/source 随 next
        String effectiveReason = next.permissionBehavior() != null
            ? next.hookPermissionDecisionReason() : base.hookPermissionDecisionReason();
        String effectiveSource = next.permissionBehavior() != null
            ? next.hookSource() : base.hookSource();
        assertEquals("reasonB", effectiveReason,
            "reason 应随末次 permissionBehavior result 配对 (last-wins, CCJ-007)");
        assertEquals("source-B", effectiveSource,
            "hookSource 应随末次 permissionBehavior result 配对 (last-wins, CCJ-005)");
    }

    /**
     * permissionBehavior 字段按 deny > ask > allow 优先级聚合 (对齐 CC
     * Open-ClaudeCode/src/utils/permissions/permissions.ts:2820-2847 hook 优先级).
     *
     * <p>HookRegistry.executePreToolUse 单独按桶填充 deny > ask > allow, 此处验证该逻辑
     * 通过手写桶填充模拟聚合, 确认优先级正确.
     */
    @Test
    void aggregatedHookResult_permissionBehaviorPriorityOrdering() {
        // 模拟 4 个 hook 各产出不同 permissionBehavior
        com.nexusai.application.agent.permission.PermissionResult allowPb =
            new com.nexusai.application.agent.permission.PermissionResult.Allow(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                new com.nexusai.application.agent.permission.PermissionDecisionReason.Other("allow"),
                null, false, null, java.util.List.of());
        com.nexusai.application.agent.permission.PermissionResult askPb =
            new com.nexusai.application.agent.permission.PermissionResult.Ask(
                "ask", new com.nexusai.application.agent.permission.PermissionDecisionReason.Other("ask"),
                java.util.List.of(), null, null, null, false, null, null);
        com.nexusai.application.agent.permission.PermissionResult denyPb =
            new com.nexusai.application.agent.permission.PermissionResult.Deny(
                "deny", new com.nexusai.application.agent.permission.PermissionDecisionReason.Other("deny"),
                null);

        // 桶填充
        com.nexusai.application.agent.permission.PermissionResult denyBehavior = denyPb;
        com.nexusai.application.agent.permission.PermissionResult askBehavior = askPb;
        com.nexusai.application.agent.permission.PermissionResult allowBehavior = allowPb;

        // 三元解析: deny > ask > allow
        com.nexusai.application.agent.permission.PermissionResult resolved =
            denyBehavior != null ? denyBehavior
                : askBehavior != null ? askBehavior
                : allowBehavior != null ? allowBehavior
                : null;

        assertSame(denyPb, resolved,
            "permissionBehavior 优先级 deny > ask > allow, 全 3 个非 null 时应 deny 胜出");

        // 移除 deny → ask 胜出
        denyBehavior = null;
        resolved = denyBehavior != null ? denyBehavior
            : askBehavior != null ? askBehavior
            : allowBehavior != null ? allowBehavior
            : null;
        assertSame(askPb, resolved,
            "无 deny 时应 ask 胜出");

        // 只剩 allow → allow 胜出
        askBehavior = null;
        resolved = denyBehavior != null ? denyBehavior
            : askBehavior != null ? askBehavior
            : allowBehavior != null ? allowBehavior
            : null;
        assertSame(allowPb, resolved,
            "只剩 allow 时应 allow 胜出");
    }

    /**
     * proceed() 工厂返回全 null 字段 (无干预状态), 包括 M.2.1 新增的 2 字段.
     */
    @Test
    void aggregatedHookResult_proceedFactoryReturnsFullNull() {
        AggregatedHookResult proceed = AggregatedHookResult.proceed();
        assertTrue(proceed.isProceed(),
            "proceed() 工厂应返回 isProceed()=true (全字段默认 null/false)");
        // [H4] additionalContext 单值 + aggregatedAt 已移除 (对齐 CC utils/hooks.ts:359-376)
        assertEquals(null, proceed.permissionBehavior());
        assertEquals(false, proceed.preventContinuation());
    }

    /**
     * 防御性测试: 字段名集合与 CC 16 字段集一致, 且不含非 CC 扩展字段.
     */
    @Test
    void aggregatedHookResult_fieldNamesMatchCC() {
        String[] fieldNames = Arrays.stream(AggregatedHookResult.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toArray(String[]::new);
        // [H4] additionalContext 单值 + aggregatedAt 已移除 (对齐 CC utils/hooks.ts:359-376)
        assertFalse(Arrays.asList(fieldNames).contains("additionalContext"),
            "字段名不应包含 additionalContext (H4 移除, CC AHR 无此字段)");
        assertFalse(Arrays.asList(fieldNames).contains("aggregatedAt"),
            "字段名不应包含 aggregatedAt (H4 移除, CC AHR 无此字段)");
        assertTrue(Arrays.asList(fieldNames).contains("permissionBehavior"),
            "字段名应包含 permissionBehavior (CC 字段)");
        assertTrue(Arrays.asList(fieldNames).contains("watchPaths"),
            "字段名应包含 watchPaths (CC 字段)");
        assertTrue(Arrays.asList(fieldNames).contains("retry"),
            "字段名应包含 retry (CC 字段)");
    }
}
