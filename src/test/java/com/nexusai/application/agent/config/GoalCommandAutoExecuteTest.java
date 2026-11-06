package com.nexusai.application.agent.config;

import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.goal.GoalService;
import com.nexusai.domain.session.SessionGoal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [goal V253] {@code /goal <条件>} ⇒ <b>触发一轮执行</b> 的契约测试。
 *
 * <p><b>WHY 需要</b>：CC 的 goal 命令返回 {@code {type:"query", value:"Goal set: <c>",
 * prompt:kickoff}}，{@code processSlashCommand} 据此把 kickoff 提示词当 <b>isMeta 的
 * user 消息</b>注入本轮并 {@code shouldQuery:true} ⇒ <b>模型立刻开始朝目标工作</b>。
 *
 * <p>本仓原实现只回 {@code text} ⇒ <b>只记下目标、不执行</b>（用户实测发现的缺口）。
 *
 * <p><b>三条不变量</b>：
 * <ol>
 *   <li>设定目标 ⇒ 返回 kind=query（<b>不是</b> text）</li>
 *   <li>kickoff 提示词**逐字**含条件原文，且含 CC 的三条关键约束（立即开始 / 不许停下问 /
 *       不要让用户手动 clear）</li>
 *   <li>无参 / clear ⇒ 仍是 text（<b>不</b>触发执行 —— 那两条只读状态）</li>
 * </ol>
 */
@DisplayName("[goal V253] /goal 设定目标后触发一轮执行")
class GoalCommandAutoExecuteTest {

    private GoalService goalService;
    private UserInputDispatcher dispatcher;

    /** 构造一个只关心 result handler 的 dispatcher（不注册任何 handler ⇒ 直接调 handler 主体）。 */
    private UserInputDispatcher.LocalCommandResult callGoal(String args, String sessionId) {
        GoalCommandRegistrationConfig cfg = new GoalCommandRegistrationConfig();
        return cfg.handleGoalForTest(args, sessionId, goalService, null);
    }

    @Test
    @DisplayName("设定目标 ⇒ kind=query（触发执行），带 kickoff 提示词")
    void setGoal_returnsQuery_withKickoff() {
        goalService = mock(GoalService.class);
        when(goalService.getGoal(anyString())).thenReturn(null);
        when(goalService.setGoal(anyString(), anyString(), anyLong()))
                .thenReturn(new SessionGoal("e2e 全绿", SessionGoal.Phase.ACTIVE, 0, 1L, 0L, null));

        UserInputDispatcher.LocalCommandResult r = callGoal("e2e 全绿", "sess-1");

        assertThat(r.kind())
            .as("CC 的 /goal 返回 type:\"query\" ⇒ 必须触发一轮执行（否则只记不跑）")
            .isEqualTo("query");
        assertThat(r.value()).contains("Goal set").contains("e2e 全绿");
        assertThat(r.prompt())
            .as("kickoff 提示词必须含条件原文")
            .contains("e2e 全绿");
    }

    @Test
    @DisplayName("kickoff 提示词含 CC 的三条关键约束")
    void kickoff_containsCcConstraints() {
        goalService = mock(GoalService.class);
        when(goalService.getGoal(anyString())).thenReturn(null);
        when(goalService.setGoal(anyString(), anyString(), anyLong()))
                .thenReturn(new SessionGoal("cond", SessionGoal.Phase.ACTIVE, 0, 1L, 0L, null));

        String kickoff = callGoal("cond", "sess-1").prompt();

        // CC 原文：immediately start (or continue) working toward it
        assertThat(kickoff).contains("immediately start (or continue) working toward it");
        // CC 原文：do not pause to ask the user what to do
        assertThat(kickoff).contains("do not pause to ask the user what to do");
        // CC 原文：do not tell the user to run `/goal clear` after success
        assertThat(kickoff).contains("do not tell the user to run `/goal clear`");
        // CC 原文：The hook will block stopping until the condition holds
        assertThat(kickoff).contains("block stopping until the condition holds");
    }

    @Test
    @DisplayName("无参 ⇒ text（只显示状态，不触发执行）")
    void noArgs_returnsText_notTrigger() {
        goalService = mock(GoalService.class);
        when(goalService.getGoal(anyString())).thenReturn(null);

        UserInputDispatcher.LocalCommandResult r = callGoal("", "sess-1");

        assertThat(r.kind()).isEqualTo("text");
        assertThat(r.value()).isEqualTo("No goal set");
        assertThat(r.prompt()).isNull();
    }

    @Test
    @DisplayName("clear ⇒ text（只清状态，不触发执行）")
    void clear_returnsText_notTrigger() {
        goalService = mock(GoalService.class);
        when(goalService.getGoal(anyString())).thenReturn(null);
        when(goalService.clearGoal(anyString())).thenReturn("旧目标");

        UserInputDispatcher.LocalCommandResult r = callGoal("clear", "sess-1");

        assertThat(r.kind()).isEqualTo("text");
        assertThat(r.value()).isEqualTo("Goal cleared");
        assertThat(r.prompt()).isNull();
    }

    @Test
    @DisplayName("超长条件 ⇒ text（拒绝，不触发执行）")
    void tooLong_returnsText_notTrigger() {
        goalService = mock(GoalService.class);
        when(goalService.getGoal(anyString())).thenReturn(null);
        when(goalService.setGoal(anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalArgumentException("目标条件长度超过上限 4000 字符（实际 4001）"));

        UserInputDispatcher.LocalCommandResult r = callGoal("x".repeat(4001), "sess-1");

        assertThat(r.kind()).isEqualTo("text");
        assertThat(r.value()).contains("4000");
        assertThat(r.prompt()).isNull();
    }
}
