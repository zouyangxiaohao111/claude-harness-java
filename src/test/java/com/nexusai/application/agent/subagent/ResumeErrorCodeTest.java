package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * resume 错误码与文案 · 对齐 CC {@code subagent_resume_*} 族。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>码用于分类/遥测、文案面向用户。两者混用会让前端拿到不可解析的字符串，
 * 或让用户看到内部术语。本用例锁死「码名逐字」与「用户出口句」。
 */
@DisplayName("[agent-resume-align] 错误码")
class ResumeErrorCodeTest {

    @Test
    @DisplayName("码名逐字对齐 CC")
    void codeNamesMatchCc() {
        assertThat(ResumeErrorCode.AGENT_TRANSCRIPT_UNRESUMABLE.code())
            .isEqualTo("agent_transcript_unresumable");
        assertThat(ResumeErrorCode.SUBAGENT_RESUME_FORK_DROPPED.code())
            .isEqualTo("subagent_resume_fork_dropped");
        assertThat(ResumeErrorCode.SUBAGENT_RESUME_CWD_REFUSED.code())
            .isEqualTo("subagent_resume_cwd_refused");
        assertThat(ResumeErrorCode.SUBAGENT_RESUME_WORKTREE_REFUSED.code())
            .isEqualTo("subagent_resume_worktree_refused");
    }

    @Test
    @DisplayName("异常携带错误码 + 用户出口句")
    void exceptionCarriesCodeAndSuffix() {
        var ex = new ResumeErrorCode.ResumeException(
            ResumeErrorCode.SUBAGENT_RESUME_FORK_DROPPED,
            "Cannot resume this fork agent: unable to reconstruct its parent system prompt."
                + ResumeErrorCode.START_NEW_AGENT_SUFFIX);

        assertThat(ex.errorCode()).isEqualTo(ResumeErrorCode.SUBAGENT_RESUME_FORK_DROPPED);
        assertThat(ex.getMessage()).contains("Start a new agent if the work is still needed.");
    }
}
