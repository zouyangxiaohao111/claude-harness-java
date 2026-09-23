package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.team.MissingLeaderSessionException;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * <b>[fail-loud] 「携带 teammateIdentity 但没走父 TUC 通道」⇒ 直接报错</b>
 * （2026-09-22 用户裁定；前一批只做了「Leader 会话解析」侧 fail-loud，本类是另一半）。
 *
 * <h2>WHY（规则九 · 本类守护的意图）</h2>
 * <p>teammate 的执行 TUC 只有两条路：{@code createSubagentContext.create(parent, …)} 的
 * <b>hasParent 分支</b>（继承 Leader 的 sessionId / effectiveCwd）或 <b>standalone 分支</b>
 * （sessionId 置 {@code SessionKeys.NO_SESSION} 哨兵）。判据就是 {@code executeStreaming} 入口算出的
 * {@code effectiveParentTuc}。改前若 teammate 路径拿到 null 父 TUC：
 * <ul>
 *   <li>teammate 的 transcript 目录 / effectiveCwd / 权限会话规则 / file-history / hook 桶
 *       全挂 <b>no-session 幻影键</b>；</li>
 *   <li>更直接的用户可感后果：批准后写进 <b>Leader 会话列</b>（{@code sessions.session_permission_rules}）
 *       的授权，在 teammate 下一轮<b>读不回来</b>（会话列的读回注入只挂 {@code LlmAgentLoop.doRun}
 *       主循环 + {@code AgentLoopContext.mergeAppStatePermissionRules} 的 {@code baseTuc.getAppState()}
 *       通道；no-session 的 minimal 父 TUC 的 getAppState 是恒等函数，合并恒 no-op）
 *       ⇒ 「授权写进去了」≠「下次免问」= 用户最初的抱怨。</li>
 * </ul>
 * e2e 反向实验已实证：该形态<b>不抛</b>、静默降级、授权被丢弃。用户裁定：「将来若出现『携带
 * teammateIdentity 但没走父 TUC 通道』的新路径<b>直接报错</b>」。
 *
 * <h2>判别力（正反双向，⛔ 非恒真）</h2>
 * <ul>
 *   <li>正向：teammate + 无父 ⇒ 抛 {@link MissingLeaderSessionException}，且文案含四要素
 *       （环节 / 期望 / 实际 / 如何修）；</li>
 *   <li>反向对照 1（{@link #teammateTurn_withParentTuc_passesGuard}）：teammate + 有父 ⇒
 *       <b>不抛本异常</b>。用未知 agent 类型把「不抛」变成<b>可判定的实跑产物</b>
 *       （流程继续走到 Step 1 抛 {@link AgentNotFoundException}）—— 若守卫误写成恒抛，
 *       该用例立刻变红；</li>
 *   <li>反向对照 2（{@link #nonTeammate_withoutParentTuc_keepsSentinelSemantics}）：<b>非</b> teammate
 *       + 无父 ⇒ 不抛本异常（合法降级路径保留 NO_SESSION 哨兵语义）—— 这是任务书要求的边界：
 *       「真无会话的合法降级」与「teammate 不该无父」必须被区分开。</li>
 *   <li><b>反向实验配方（可证伪，已验证 RED）</b>：把 {@code teammateWithoutParentTuc} 的返回改成
 *       {@code false}（= 去掉守卫）⇒ 正向用例变红（不再抛、而是继续走到 Step 1 抛
 *       AgentNotFoundException）；把返回改成 {@code true}（= 写成恒抛）⇒ 两个反向对照同时变红。</li>
 * </ul>
 *
 * <h2>边界（⛔ 本类不守护的东西）</h2>
 * <p>「确无会话」的合法降级路径（入站 MCP 子进程 / 无会话 plan provider / workflow worker /
 * standalone fork 子代理 —— 它们的 {@code teammateIdentity} 恒为 null）仍走
 * {@code SessionKeys.NO_SESSION} 哨兵与 {@code CwdResolution} 命名出口，本断言不触碰它们。
 * 同理断言不放进 {@code createSubagentContext.create}（那里只有 (parent, overrides) 通用语义，
 * 没有 teammate 概念，塞进去会污染通用合同并让合法无父路径跟着抛）。
 */
@DisplayName("fail-loud · 携带 teammateIdentity 却无父 TUC 即报错（⛔ 不静默降级到 no-session）")
class TeammateParentTucFailLoudTest {

    private static final String LEADER_SESSION = "sess-leader-t1";

    private static TeammateIdentity identity() {
        return new TeammateIdentity("alice@team-t1", "alice", "team-t1", "blue", false, LEADER_SESSION);
    }

    /** 生产形态：7 参构造器 ⇒ 构造器级 parentToolUseContext = null（单例 bean 即此形态）。 */
    private static SubagentExecutor standaloneCtorExecutor() {
        return new SubagentExecutor(null, null, null, null, null, "gpt-4", "sys");
    }

    /** 未知 agent 类型：用于把「守卫没拦」变成可判定的实跑产物（Step 1 抛 AgentNotFoundException）。 */
    private static final String UNKNOWN_AGENT = "no-such-agent-failloud-probe";

    @Test
    @DisplayName("判据真值表：仅「有 identity 且无父」为 true（另三种组合恒 false）")
    void predicate_truthTable() {
        ToolUseContext parent = ToolUseContext.of(null, LEADER_SESSION, PermissionMode.DEFAULT);

        assertThat(SubagentExecutor.teammateWithoutParentTuc(identity(), null))
            .as("teammate + 无父 ⇒ 必须 fail-loud")
            .isTrue();
        assertThat(SubagentExecutor.teammateWithoutParentTuc(identity(), parent))
            .as("teammate + 有父 ⇒ 正常路径")
            .isFalse();
        assertThat(SubagentExecutor.teammateWithoutParentTuc(null, null))
            .as("非 teammate + 无父 ⇒ 合法降级（NO_SESSION 哨兵语义保留）")
            .isFalse();
        assertThat(SubagentExecutor.teammateWithoutParentTuc(null, parent))
            .as("非 teammate + 有父 ⇒ 普通 Agent-tool 子代理路径")
            .isFalse();
    }

    @Test
    @DisplayName("正向：teammate 无父 TUC ⇒ 抛 MissingLeaderSessionException（四要素齐全）")
    void teammateTurn_withoutParentTuc_throwsFailLoud() {
        SubagentExecutor executor = standaloneCtorExecutor();

        Throwable thrown = catchThrowable(() -> executor.executeTeammateTurn(
            "do something", UNKNOWN_AGENT, null, null, null, null,
            /* parentTucOverride = */ null, identity()));

        assertThat(thrown)
            .as("teammate 却无父 TUC：⛔ 不得静默降级到 standalone/no-session")
            .isInstanceOf(MissingLeaderSessionException.class);
        String msg = thrown.getMessage();
        assertThat(msg)
            .as("fail-loud 文案必须含四要素（环节/期望/实际/如何修）+ Lead 归属上下文")
            .contains("环节=").contains("期望=").contains("实际=").contains("如何修=")
            .contains("SubagentExecutor.executeStreaming")
            .contains("alice@team-t1")
            .contains(LEADER_SESSION);
    }

    @Test
    @DisplayName("反向对照：teammate 有父 TUC ⇒ 守卫放行（流程继续到 Step 1 才因未知 agent 失败）")
    void teammateTurn_withParentTuc_passesGuard() {
        SubagentExecutor executor = standaloneCtorExecutor();
        ToolUseContext leaderParentTuc = ToolUseContext.of(null, LEADER_SESSION, PermissionMode.DEFAULT);

        Throwable thrown = catchThrowable(() -> executor.executeTeammateTurn(
            "do something", UNKNOWN_AGENT, null, null, null, null,
            leaderParentTuc, identity()));

        assertThat(thrown)
            .as("有父 TUC 时守卫必须放行；此处应在 Step 1 因未知 agent 类型失败（证明流程真的往下走了）")
            .isInstanceOf(AgentNotFoundException.class);
        assertThat(thrown)
            .as("⛔ 守卫不得写成恒抛")
            .isNotInstanceOf(MissingLeaderSessionException.class);
    }

    @Test
    @DisplayName("反向对照：非 teammate 无父 ⇒ 不抛本异常（合法降级路径保留哨兵语义）")
    void nonTeammate_withoutParentTuc_keepsSentinelSemantics() {
        SubagentExecutor executor = standaloneCtorExecutor();

        Throwable thrown = catchThrowable(() -> executor.executeTeammateTurn(
            "do something", UNKNOWN_AGENT, null, null, null, null,
            /* parentTucOverride = */ null, /* teammateIdentityOverride = */ null));

        assertThat(thrown)
            .as("非 teammate（workflow/hook/standalone 等合法降级）不得被本断言波及")
            .isNotInstanceOf(MissingLeaderSessionException.class);
        assertThat(thrown)
            .as("守卫放行后应走到 Step 1 因未知 agent 类型失败")
            .isInstanceOf(AgentNotFoundException.class);
    }
}
