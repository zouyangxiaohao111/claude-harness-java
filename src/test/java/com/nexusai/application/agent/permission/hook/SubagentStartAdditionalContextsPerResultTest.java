package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H-WF4-03 · 5-W4-6] SubagentStart additionalContexts 逐结果收集 · 合并阶段集成测试（R-4 REWORK）.
 *
 * <p>WHY (规则九 · 验证意图): CC runAgent.ts:531-543 {@code for await (hookResult of
 * executeSubagentStartHooks)} 逐 result 取 {@code additionalContexts} 数组
 * （hooks.ts:2783-2789 每条 yield {@code {additionalContexts:[result.additionalContext]}}）。
 * Java 旧实现 {@code executeEvent(startEvent)} 折叠单结果 → 多 hook 场景 2+ 条 additionalContext
 * 丢失（△-5）。PATCH-4 改 {@code executeEventAll(startEvent)} 逐结果收集。
 *
 * <p>本测试在 HookRegistry 层验证"2+ hook 各贡献 1 条 additionalContext → 全收集"
 * （SubagentExecutor.executeSubagentStartHooks 私有，走 executeEventAll 同 seam）；
 * 折叠修复生效的直接证据：若回退 executeEvent（折叠单条）本测试红（只收 1 条）。
 *
 * <p>不依赖 Spring 容器：手动 new HookRegistry + register programmatic hook。
 */
@DisplayName("[H-WF4-03 · 5-W4-6] SubagentStart additionalContexts 逐结果收集 (2+ hook 全收集)")
class SubagentStartAdditionalContextsPerResultTest {

    /** 构造携带 additionalContexts 的 HookResult（其余字段占位，CC 不消费）。 */
    private static HookResult resultWith(String context) {
        // [IMP-DA-01 TY-01 同步] GenericHook.HookResult 第 19 字段 allBlockingErrors 已删除
        //   (对齐 CC HookResult 无此字段) — 本测试 19 参构造同步去掉末参 null.
        return new HookResult(false, null, null, List.of(context),
            null, null, null, null, null, HookOutcome.SUCCESS, null, null,
            null, null, null, null, null, null);
    }

    /**
     * WHY: CC 逐 result yield additionalContexts（hooks.ts:2783-2789）→ 消费端逐条 push；
     * 旧 executeEvent 折叠单结果 → 第 2+ hook 的 additionalContext 丢失。executeEventAll
     * 返回全部非 null 结果（配置驱动 + programmatic 全收）。
     */
    @Test
    @DisplayName("2 个 SubagentStart hook 各贡献 1 条 additionalContext → 全收集 (2 条)")
    void twoHooks_eachContributesOneContext_allCollected() {
        HookRegistry registry = new HookRegistry();
        registry.register("ctx-hook-1", event -> resultWith("ctx-1"), HookEventType.SUBAGENT_START);
        registry.register("ctx-hook-2", event -> resultWith("ctx-2"), HookEventType.SUBAGENT_START);

        List<GenericHook.HookResult> results = registry.executeEventAll(
            HookEvent.subagentStart("agent-1", "primary", "sess-1"));

        // SubagentExecutor.executeSubagentStartHooks 同款收集逻辑（PATCH-4 循环体）
        List<String> contexts = new ArrayList<>();
        for (GenericHook.HookResult r : results) {
            if (r != null && r.additionalContexts() != null) {
                contexts.addAll(r.additionalContexts());
            }
        }
        assertThat(contexts)
            .as("2+ hook 各贡献 1 条 additionalContext → 全收集（对齐 CC runAgent.ts:538-541 push(...hookResult.additionalContexts)）")
            .containsExactlyInAnyOrder("ctx-1", "ctx-2");
    }

    /**
     * WHY: 反向 — 单个 hook 贡献 1 条 → 仍 1 条（单 hook 行为不变，PATCH-4 无回归）。
     */
    @Test
    @DisplayName("单 hook 贡献 1 条 additionalContext → 1 条（无回归）")
    void singleHook_oneContext_oneCollected() {
        HookRegistry registry = new HookRegistry();
        registry.register("ctx-hook-1", event -> resultWith("ctx-only"), HookEventType.SUBAGENT_START);

        List<GenericHook.HookResult> results = registry.executeEventAll(
            HookEvent.subagentStart("agent-1", "primary", "sess-1"));

        List<String> contexts = new ArrayList<>();
        for (GenericHook.HookResult r : results) {
            if (r != null && r.additionalContexts() != null) {
                contexts.addAll(r.additionalContexts());
            }
        }
        assertThat(contexts)
            .as("单 hook 行为不变：1 条 additionalContext 仍 1 条")
            .containsExactly("ctx-only");
    }

    /**
     * WHY: 空结果 → 空收集（对齐 CC 无 hook 匹配时 executeEventAll 返回空 List，不 NPE）。
     */
    @Test
    @DisplayName("无 hook 匹配 → 空收集（不 NPE）")
    void noHook_noContexts_empty() {
        HookRegistry registry = new HookRegistry();

        List<GenericHook.HookResult> results = registry.executeEventAll(
            HookEvent.subagentStart("agent-1", "primary", "sess-1"));

        List<String> contexts = new ArrayList<>();
        for (GenericHook.HookResult r : results) {
            if (r != null && r.additionalContexts() != null) {
                contexts.addAll(r.additionalContexts());
            }
        }
        assertThat(contexts)
            .as("无 hook 匹配 → 空收集（executeEventAll 返回空 List）")
            .isEmpty();
    }
}
