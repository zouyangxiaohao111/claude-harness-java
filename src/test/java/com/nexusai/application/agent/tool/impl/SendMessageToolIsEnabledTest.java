package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.TeamHelpers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPD-TOOL-10-R2 · SendMessageTool.isEnabled() agent-swarms 门控测试（对齐 CC SendMessageTool.ts:535-537
 * {@code isEnabled() { return isAgentSwarmsEnabled() }}）。
 *
 * <p>WHY（规则九，验证意图）：
 * <ul>
 *   <li>CC tools.ts:228-230 门控起始点 {@code isAgentSwarmsEnabled() ? [getTeamCreateTool(), ...] : []}：
 *       未开启 agent-swarms 时 SendMessageTool（与 TeamCreate/TeamDelete 并列）不进 LLM schema，
 *       避免模型对未启用功能发起无效 swarm 调用；</li>
 *   <li>isAgentSwarmsEnabled 全语义见 agentSwarmsEnabled.ts:24-44：ant 恒 true；外部需 env
 *       CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS 或 --agent-teams flag opt-in（+killswitch 缺省通过）；
 *       Java 侧 env 测试不可设 → sysprop-override seam 镜像既有 TASKS_CONFIG_DIR 模式：</li>
 *   <li>默认关 → isEnabled()=false（回归防护：防止误开暴露给 LLM）。</li>
 * </ul>
 */
class SendMessageToolIsEnabledTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // configHome 指向临时目录：TeammateMailbox 文件委托需要可写 configHome
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
    }

    private SendMessageTool newTool() {
        return new SendMessageTool(new TeamHelpers());
    }

    @Test
    @DisplayName("默认未开启 agent-swarms → isEnabled()=false（LLM schema 不暴露，CC tools.ts:228）")
    void defaultDisabled_gateClosed() {
        // WHY: CC agentSwarmsEnabled.ts:30-33 外部需 CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS
        // 或 --agent-teams opt-in，否则 SendMessageTool 不进 LLM schema（tools.ts:228）。
        assertThat(newTool().isEnabled())
                .as("默认（无 opt-in/flag/ant）时 isAgentSwarmsEnabled()==false → isEnabled()==false")
                .isFalse();
    }

    @Test
    @DisplayName("opt-in（nexusai.experimental.agent-teams=true）→ isEnabled()=true")
    void optInEnabled_gateOpen() {
        // WHY: CC agentSwarmsEnabled.ts:32 isEnvTruthy(CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS) opt-in
        // → isAgentSwarmsEnabled()==true。Java sysprop seam 镜像该 env。
        System.setProperty("nexusai.experimental.agent-teams", "true");
        assertThat(newTool().isEnabled())
                .as("opt-in 为真（1/true/yes/on 任一）→ isEnabled()=true")
                .isTrue();
    }

    @Test
    @DisplayName("flag（nexusai.agent-teams=true）→ isEnabled()=true")
    void flagEnabled_gateOpen() {
        // WHY: CC agentSwarmsEnabled.ts:10-11 isAgentTeamsFlagSet()=argv.includes('--agent-teams')
        // → opt-in。Java 无 argv 解析 → sysprop nexusai.agent-teams 映射（部署侧约定）。
        System.setProperty("nexusai.agent-teams", "true");
        assertThat(newTool().isEnabled())
                .as("--agent-teams flag 等价物为真 → isEnabled()=true")
                .isTrue();
    }

    @Test
    @DisplayName("ant（nexusai.user.type=ant）→ 恒 true，无需 opt-in（CC agentSwarmsEnabled.ts:26）")
    void antAlwaysEnabled() {
        // WHY: CC agentSwarmsEnabled.ts:26-28 process.env.USER_TYPE==='ant' → 直接 true，
        // 不经 opt-in/killswitch。Java 读 sysprop nexusai.user.type（默认回退 env USER_TYPE）。
        System.setProperty("nexusai.user.type", "ant");
        assertThat(newTool().isEnabled())
                .as("USER_TYPE=ant → 恒 true")
                .isTrue();
    }

    @Test
    @DisplayName("IM3 killswitch：opt-in 但 nexusai.swarms.killswitch=true → isEnabled()=false（CC agentSwarmsEnabled.ts:37-41）")
    void killswitchClosed_gateClosed() {
        // WHY: CC agentSwarmsEnabled.ts:37-41 外部用户 opt-in 后仍需 GrowthBook
        // getFeatureValue_CACHED_MAY_BE_STALE('tengu_amber_flint', true) 通过；killswitch 关闭
        // （feature=false）→ isAgentSwarmsEnabled()==false。Java 无 GrowthBook → sysprop
        // nexusai.swarms.killswitch 等价模拟：置 true 关闭 killswitch → 外部用户禁用 swarms。
        System.setProperty("nexusai.experimental.agent-teams", "true");
        System.setProperty("nexusai.swarms.killswitch", "true");
        assertThat(newTool().isEnabled())
                .as("opt-in + killswitch 关闭 → isAgentSwarmsEnabled()==false → isEnabled()==false")
                .isFalse();
    }

    @Test
    @DisplayName("IM3 killswitch 缺省：opt-in 且未置 killswitch → isEnabled()=true（对齐 CC 缺省通过）")
    void killswitchDefaultOpen_gateOpen() {
        // WHY: CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_amber_flint', true) 缺省 true →
        // killswitch 未关闭 → opt-in 外部用户 isAgentSwarmsEnabled()==true。Java sysprop 缺省 false
        // = killswitch 未关闭，不影响既有 opt-in 开启路径。
        System.setProperty("nexusai.experimental.agent-teams", "true");
        assertThat(newTool().isEnabled())
                .as("opt-in + killswitch 未置 → isEnabled()=true（不破坏既有 env 开启路径）")
                .isTrue();
    }

    @Test
    @DisplayName("IM3 killswitch 与 ant：ant 恒 true 不受 killswitch 影响（CC agentSwarmsEnabled.ts:26 不经 killswitch）")
    void antIgnoresKillswitch() {
        // WHY: CC agentSwarmsEnabled.ts:26-28 ant 分支直接 return true，不经 opt-in/killswitch
        // （:39 killswitch 仅约束外部用户）。
        System.setProperty("nexusai.user.type", "ant");
        System.setProperty("nexusai.swarms.killswitch", "true");
        assertThat(newTool().isEnabled())
                .as("ant + killswitch 关闭 → 仍恒 true（killswitch 仅约束外部用户）")
                .isTrue();
    }
}
