package com.nexusai.application.agent.tool.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S4] executeSubagentStartHooks additionalContexts 数组 RED-GREEN 双证测试 (P1 差异项 3).
 *
 * <p>规则九 (验证意图): CC runAgent.ts:538-541 取 {@code hookResult.additionalContexts} 数组并
 * <b>直接 push 原文</b>; Java 旧代码 :1103-1104 误读 {@code result.stopReason()} 单字段包装成
 * {@code "[Hook: ...]"} (Pattern #9 注释/源码双重错位). hook 注入多条 context 时旧代码只保留
 * 1 条且丢失原文 = 上下文丢失.
 *
 * <p>测试方式 (seam 模式): {@link SubagentExecutor#collectAdditionalContext(String)} 是
 * package-private static seam (executeSubagentStartHooks 真实调用), 入参 String 规避 HookResult
 * package-private 构造器. RED 依据: seam 在 S4 前不存在; 回退 stopReason 包装实现 → 断言红
 * (collectAdditionalContext("ctx") != "[Hook: ctx]").
 */
@DisplayName("[S4] executeSubagentStartHooks additionalContexts (collectAdditionalContext seam)")
class ExecuteSubagentStartHooksTest {

    @Test
    @DisplayName("hook 返回 additionalContext → 收集原文, 非 [Hook: ...] 包装 (CC hooks.ts:2788)")
    void collectAdditionalContext_shouldKeepRawText_notStopReasonWrapper() {
        // WHY: CC hooks.ts:2783-2789 yield { additionalContexts: [result.additionalContext] }
        //   — 直接 push hook 原文, 不包装. 旧代码 "[Hook: " + stopReason + "]" 丢失原始 context 文本,
        //   父 Agent 看到的上下文被篡改.
        List<String> contexts = SubagentExecutor.collectAdditionalContext("这是 hook 注入的原始上下文");

        assertThat(contexts)
            .as("必须收集原文, 不包装 [Hook: ...]")
            .containsExactly("这是 hook 注入的原始上下文");
    }

    @Test
    @DisplayName("hook 无 additionalContext (null/空白) → 空列表, 不注入 (CC if (result.additionalContext))")
    void collectAdditionalContext_nullOrBlank_shouldReturnEmpty() {
        assertThat(SubagentExecutor.collectAdditionalContext(null))
            .as("null → 空列表 (对齐 CC if (result.additionalContext) 守卫)")
            .isEmpty();
        assertThat(SubagentExecutor.collectAdditionalContext("   "))
            .as("空白 → 空列表")
            .isEmpty();
    }

    @Test
    @DisplayName("多条 hook 各自贡献 1 条 context, 拼接为数组 (CC additionalContexts 数组语义)")
    void collectAdditionalContext_multipleHooks_shouldAccumulate() {
        // WHY: CC 是数组语义 (additionalContexts: string[]), 每条 hook 的 additionalContext 追加.
        List<String> total = new java.util.ArrayList<>();
        total.addAll(SubagentExecutor.collectAdditionalContext("第一条上下文"));
        total.addAll(SubagentExecutor.collectAdditionalContext("第二条上下文"));

        assertThat(total)
            .as("每条 hook 的 additionalContext 顺序追加为数组")
            .containsExactly("第一条上下文", "第二条上下文");
    }
}
