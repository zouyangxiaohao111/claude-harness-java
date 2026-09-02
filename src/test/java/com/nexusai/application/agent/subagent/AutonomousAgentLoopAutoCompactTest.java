package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.tool.ContentReplacementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-C · AutonomousAgentLoop auto-compact + contentReplacementState 契约测试。
 *
 * <p>WHY 本测试验证意图（规则九，对齐 CC inProcessRunner.ts:1043-1113 实际行为，不信注释）：
 * <ul>
 *   <li><b>per-teammate contentReplacementState 跨 turn 持久化</b>（:1043-1045）：若父
 *       toolUseContext.contentReplacementState 存在，teammate 持独立 ContentReplacementState 跨
 *       while 迭代复用——否则每轮从 createSubagentContext 拿 fresh empty state，重新 holistic
 *       replace-globally-largest 决策导致 wire prefix 漂移 → prompt cache miss；</li>
 *   <li><b>auto-compact reset</b>（:1111-1113）：token 超阈值时 reset contentReplacementState，
 *       清 stale Map 条目防长会话内存无限增长；feature off（undefined）→ no-op。</li>
 * </ul>
 */
@DisplayName("T-C · AutonomousAgentLoop auto-compact + contentReplacementState")
class AutonomousAgentLoopAutoCompactTest {

    @Test
    @DisplayName("contentReplacementState: set/get 往返（per-teammate 跨 turn 持久化载体）")
    void contentReplacementState_roundTrip() {
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        ContentReplacementState state = ContentReplacementState.create();
        loop.setContentReplacementState(state);

        assertThat(loop.contentReplacementState()).isSameAs(state);
    }

    @Test
    @DisplayName("maybeAutoCompact: feature off（null state）→ no-op（CC :1043-1045 undefined 分支）")
    void maybeAutoCompact_featureOff_isNoOp() {
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.injectUserMessage("hello world");
        loop.setAutoCompactThresholdTokens(1);

        loop.maybeAutoCompact();

        assertThat(loop.contentReplacementState()).as("feature off 时不得凭空创建 state").isNull();
    }

    @Test
    @DisplayName("maybeAutoCompact: token 超阈值 → reset contentReplacementState（CC :1111-1113）")
    void maybeAutoCompact_overThreshold_resetsState() {
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        ContentReplacementState original = ContentReplacementState.create();
        original.markSeen("tool-1");
        original.recordReplacement("tool-1", "preview-text");
        loop.setContentReplacementState(original);
        // 注入足够多消息使镜像 token 估 > 阈值
        loop.injectUserMessage("a very long user message to push token estimate over threshold");
        loop.injectUserMessage("another long message to accumulate tokens across turns");
        loop.setAutoCompactThresholdTokens(1);

        loop.maybeAutoCompact();

        ContentReplacementState reset = loop.contentReplacementState();
        assertThat(reset).as("reset 后必须为非 null 新实例").isNotNull();
        assertThat(reset).as("reset 后必须是新实例（清 stale Map 条目）").isNotSameAs(original);
        assertThat(reset.isSeen("tool-1")).as("reset 后旧 seen 条目必须清空").isFalse();
    }

    @Test
    @DisplayName("maybeAutoCompact: token 未超阈值 → 保留同一实例（跨 turn 持久化）")
    void maybeAutoCompact_underThreshold_keepsState() {
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        ContentReplacementState original = ContentReplacementState.create();
        original.markSeen("tool-1");
        loop.setContentReplacementState(original);
        loop.injectUserMessage("hello");
        loop.setAutoCompactThresholdTokens(100_000);

        loop.maybeAutoCompact();

        assertThat(loop.contentReplacementState()).as("未达阈值必须保留同一实例").isSameAs(original);
        assertThat(loop.contentReplacementState().isSeen("tool-1")).isTrue();
    }
}
