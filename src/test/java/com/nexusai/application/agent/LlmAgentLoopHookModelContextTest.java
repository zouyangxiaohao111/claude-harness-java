package com.nexusai.application.agent;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [SM/fork 模型直传 · 回归] PostSamplingHook 的 toolUseContext 必须带「本轮模型」
 * （effectiveModelName = CC options.mainLoopModel）。
 *
 * <p>WHY（事故）：hook 原传 run 级 TUC（effectiveModelName 恒空）→ SM fork 直传解析
 * model=null → provider 回落 MockLlmProvider（"mock final response"、永不 Edit）。旧单测
 * 自建带 model 的 TUC → 自证式通过、漏掉接线。本测试锁死 helper 行为防再退化。
 */
class LlmAgentLoopHookModelContextTest {

    @Test
    @DisplayName("有本轮模型 → 写入 effectiveModelName（fork 直传才拿得到模型）")
    void withCurrentModel_setsEffectiveModelName() {
        ToolUseContext base = base();
        assertThat(base.effectiveModelName()).as("run 级 TUC 恒空（事故根因）").isNull();

        ToolUseContext out = LlmAgentLoop.hookToolUseContext(base, "deepseek/deepseek-v4.1-flash-expires-on-0910");

        assertThat(out).isNotSameAs(base);
        assertThat(out.effectiveModelName()).isEqualTo("deepseek/deepseek-v4.1-flash-expires-on-0910");
    }

    @Test
    @DisplayName("无模型（null/blank）→ 原样返回，不造字段")
    void withoutModel_returnsBaseUnchanged() {
        ToolUseContext base = base();
        assertThat(LlmAgentLoop.hookToolUseContext(base, null)).isSameAs(base);
        assertThat(LlmAgentLoop.hookToolUseContext(base, "  ")).isSameAs(base);
        assertThat(LlmAgentLoop.hookToolUseContext(null, "m")).isNull();
    }

    private static ToolUseContext base() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, Map.of(), List.of(), "", AbortController.NOOP, List.of());
    }
}
