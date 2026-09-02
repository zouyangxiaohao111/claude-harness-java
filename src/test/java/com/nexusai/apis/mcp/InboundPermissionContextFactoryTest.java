package com.nexusai.apis.mcp;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InboundPermissionContextFactory 空上下文契约测试 · 对齐 CC
 * {@code getEmptyToolPermissionContext}（Open-ClaudeCode/src/Tool.ts:140-148）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：v4 拍板 OPD-WF8-02-GS-01「对齐 CC 空上下文」——
 * 入站 MCP 调用 <b>不合并全量 settings 规则</b>、mode=default、三桶为空、
 * {@code shouldAvoidPermissionPrompts} 未置位（CC undefined→false）。旧实现按 S06 文档
 * I-11 合并 7 个 {@code PermissionSourceLoader} 规则 + 置 headless auto-deny 位，偏离
 * CC 真源（EV-WF8-GS-106）。若 factory 仍合并规则 / 置位，入站工具会被额外 deny/ask
 * 拦截，行为与 CC 源码不一致。
 */
class InboundPermissionContextFactoryTest {

    private final InboundPermissionContextFactory factory = new InboundPermissionContextFactory();

    @Test
    @DisplayName("空上下文契约：mode=default + 空三桶 + shouldAvoidPermissionPrompts=false（对齐 CC getEmptyToolPermissionContext）")
    void build_returnsCcEmptyContext() {
        ToolPermissionContext ctx = factory.build();

        assertThat(ctx.mode())
            .as("CC getEmptyToolPermissionContext mode='default'（Tool.ts:141）")
            .isEqualTo(PermissionMode.DEFAULT);
        assertThat(ctx.alwaysAllowRules())
            .as("CC alwaysAllowRules={}（Tool.ts:144）——入站不合并 allow 规则")
            .isEmpty();
        assertThat(ctx.alwaysDenyRules())
            .as("CC alwaysDenyRules={}（Tool.ts:145）——settings deny 不直接作用于入站")
            .isEmpty();
        assertThat(ctx.alwaysAskRules())
            .as("CC alwaysAskRules={}（Tool.ts:146）")
            .isEmpty();
        assertThat(ctx.additionalWorkingDirectories())
            .as("CC additionalWorkingDirectories=new Map()（Tool.ts:143）")
            .isEmpty();
        assertThat(ctx.isBypassPermissionsModeAvailable())
            .as("CC isBypassPermissionsModeAvailable=false（Tool.ts:147）")
            .isFalse();
        assertThat(ctx.isAutoModeAvailable())
            .as("CC 空上下文未置 isAutoModeAvailable（undefined→false）")
            .isFalse();
        assertThat(ctx.strippedDangerousRules())
            .as("CC 空上下文未置 strippedDangerousRules（undefined→空）")
            .isEmpty();
        assertThat(ctx.shouldAvoidPermissionPrompts())
            .as("CC getEmptyToolPermissionContext 未置 shouldAvoidPermissionPrompts（undefined→false），"
                + "对齐 CC 空上下文必须为 false——headless auto-deny 位由调用方 isNonInteractiveSession 承载")
            .isFalse();
        assertThat(ctx.awaitAutomatedChecksBeforeDialog()).isFalse();
        assertThat(ctx.prePlanMode()).isNull();
    }

    @Test
    @DisplayName("空上下文每调用新鲜构建：build() 两次返回等值空桶（CC per-call getEmptyToolPermissionContext）")
    void build_returnsFreshEmptyContextEachCall() {
        ToolPermissionContext first = factory.build();
        ToolPermissionContext second = factory.build();

        assertThat(first).isNotSameAs(second);
        assertThat(first.alwaysDenyRules()).isEmpty();
        assertThat(second.alwaysDenyRules()).isEmpty();
    }
}
