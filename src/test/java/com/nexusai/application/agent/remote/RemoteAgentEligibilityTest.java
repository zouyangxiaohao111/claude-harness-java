package com.nexusai.application.agent.remote;

import com.nexusai.application.agent.remote.RemoteAgentEligibility.EligibilityResult;
import com.nexusai.application.agent.remote.RemoteAgentEligibility.PreconditionType;
import com.nexusai.application.agent.remote.RemoteAgentEligibility.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * remote_agent 资格门控测试 · 对齐 CC remoteSession.ts:45-97 + RemoteAgentTask.tsx:124-161。
 *
 * <p><b>WHY（意图验证，规则九）</b>: 远程 agent 创建前必须通过 6 种前置条件门控
 * （policy_blocked/not_logged_in/no_remote_environment/not_in_git_repo/no_git_remote/
 * github_app_not_installed），否则创建的任务只会徒劳轮询。每个测试锁死一条 CC 真源分支：
 * <ul>
 *   <li>policy 先行阻断 → 仅 policy_blocked（remoteSession.ts:52-54）</li>
 *   <li>非 github.com remote → 不做 GitHub App 检查（remoteSession.ts:86-91）</li>
 *   <li>bundleSeedGateOn + in-git-repo → 跳过 remote+app 检查（remoteSession.ts:80-82）</li>
 *   <li>formatPreconditionError 文案与 CC 逐字一致（RemoteAgentTask.tsx:146-161）</li>
 * </ul>
 */
@DisplayName("[W6-04] RemoteAgentEligibility 资格门控（6 前置条件，对齐 CC remoteSession.ts + RemoteAgentTask.tsx）")
class RemoteAgentEligibilityTest {

    /** 便捷构造：默认全通过，测试按需覆写。 */
    private static RemoteAgentEligibility gate(
            BooleanSupplier policyAllowed,
            BooleanSupplier needsLogin,
            BooleanSupplier hasEnv,
            BooleanSupplier inGitRepo,
            BooleanSupplier bundleSeed,
            Supplier<Repository> repo,
            BiPredicate<String, String> appInstalled) {
        return new RemoteAgentEligibility(policyAllowed, needsLogin, hasEnv, inGitRepo,
            bundleSeed, repo, appInstalled);
    }

    private static final BooleanSupplier TRUE = () -> true;
    private static final BooleanSupplier FALSE = () -> false;
    private static final Supplier<Repository> GITHUB_REPO =
        () -> new Repository("github.com", "acme", "repo1");
    private static final BiPredicate<String, String> APP_INSTALLED = (o, r) -> true;

    @Test
    @DisplayName("policy 阻断 → 仅 policy_blocked，不检查其它前置条件（CC remoteSession.ts:52-54）")
    void policyBlockedShortCircuits() {
        // WHY: 组织策略禁用远程会话时，即使其余前置条件全失败也只报 policy_blocked，
        //      用户不会收到误导性的"未登录/无远程环境"噪音。
        RemoteAgentEligibility gate = gate(
            FALSE, TRUE, FALSE, FALSE, TRUE, () -> null, (o, r) -> false);

        assertThat(gate.checkRemoteAgentEligibility(false))
            .isEqualTo(new EligibilityResult.NotEligible(List.of(PreconditionType.POLICY_BLOCKED)));
    }

    @Test
    @DisplayName("未登录 + 无远程环境 + 非 git 仓库 → 三者全部上报（CC remoteSession.ts:61-80）")
    void accumulatesLoginEnvGitErrors() {
        // WHY: CC 不短路 login/env/git 三类错误 — 全部收集才能让用户一次修完
        //      （remoteSession.ts:61-80 顺序 push）。
        RemoteAgentEligibility gate = gate(
            TRUE, TRUE, FALSE, FALSE, FALSE, () -> null, (o, r) -> true);

        assertThat(gate.checkRemoteAgentEligibility(false))
            .isEqualTo(new EligibilityResult.NotEligible(List.of(
                PreconditionType.NOT_LOGGED_IN,
                PreconditionType.NO_REMOTE_ENVIRONMENT,
                PreconditionType.NOT_IN_GIT_REPO)));
    }

    @Test
    @DisplayName("非 git 仓库短路 remote 检查（不再报 no_git_remote）（CC remoteSession.ts:76-78）")
    void notInGitRepoShortCircuitsRemoteChecks() {
        // WHY: in-git-repo 是 remote 检查的前提 — 非 git 时 no_git_remote 无意义
        //      （CC :76 else-if 链）。
        RemoteAgentEligibility gate = gate(
            TRUE, FALSE, TRUE, FALSE, FALSE, () -> null, (o, r) -> false);

        assertThat(gate.checkRemoteAgentEligibility(false))
            .isEqualTo(new EligibilityResult.NotEligible(List.of(PreconditionType.NOT_IN_GIT_REPO)));
    }

    @Test
    @DisplayName("bundleSeedGateOn + in-git-repo → 跳过 remote+app 检查（CC remoteSession.ts:80-82）")
    void bundleSeedGateSkipsRemoteAndAppChecks() {
        // WHY: bundle 种子模式下只需 .git/ 即可（CC 注释 "has .git/, bundle will work"），
        //      本地 bundle 上传不依赖 GitHub remote 或 app — 不跳过会误拦合法 bundle 用户。
        RemoteAgentEligibility gate = gate(
            TRUE, FALSE, TRUE, TRUE, TRUE, () -> null, (o, r) -> false);

        assertThat(gate.checkRemoteAgentEligibility(false))
            .isEqualTo(new EligibilityResult.Eligible());
    }

    @Test
    @DisplayName("skipBundle=true → bundle 门控关闭，无 remote 时仍报 no_git_remote（CC remoteSession.ts:69-70）")
    void skipBundleDisablesBundleSeedGate() {
        // WHY: skipBundle 用于关闭 bundle 种子路径 — 此时必须走完整 remote 校验。
        RemoteAgentEligibility gate = gate(
            TRUE, FALSE, TRUE, TRUE, TRUE, () -> null, (o, r) -> false);

        assertThat(gate.checkRemoteAgentEligibility(true))
            .isEqualTo(new EligibilityResult.NotEligible(List.of(PreconditionType.NO_GIT_REMOTE)));
    }

    @Test
    @DisplayName("非 github.com remote → 不做 GitHub App 检查，直接通过（CC remoteSession.ts:86-91）")
    void nonGithubHostSkipsAppCheck() {
        // WHY: GitHub App 安装检查只对 github.com 仓库有意义 — gitlab/自建 host
        //      走 bundle/其他通道，误报 app_not_installed 会错误拦截。
        RemoteAgentEligibility gate = gate(
            TRUE, FALSE, TRUE, TRUE, FALSE,
            () -> new Repository("gitlab.com", "acme", "repo1"), (o, r) -> false);

        assertThat(gate.checkRemoteAgentEligibility(false))
            .isEqualTo(new EligibilityResult.Eligible());
    }

    @Test
    @DisplayName("github.com remote 但 app 未安装 → github_app_not_installed（CC remoteSession.ts:89-93）")
    void githubHostAppNotInstalled() {
        RemoteAgentEligibility gate = gate(
            TRUE, FALSE, TRUE, TRUE, FALSE, GITHUB_REPO, (o, r) -> false);

        assertThat(gate.checkRemoteAgentEligibility(false))
            .isEqualTo(new EligibilityResult.NotEligible(List.of(PreconditionType.GITHUB_APP_NOT_INSTALLED)));
    }

    @Test
    @DisplayName("全部前置条件通过 → eligible=true（CC RemoteAgentTask.tsx:139-140）")
    void allChecksPassEligible() {
        RemoteAgentEligibility gate = gate(
            TRUE, FALSE, TRUE, TRUE, FALSE, GITHUB_REPO, APP_INSTALLED);

        assertThat(gate.checkRemoteAgentEligibility(false))
            .isEqualTo(new EligibilityResult.Eligible());
    }

    @Test
    @DisplayName("格式文案与 CC 逐字一致（CC RemoteAgentTask.tsx:146-161）")
    void formatPreconditionErrorMatchesCcVerbatim() {
        // WHY: 文案直接回显给用户（AgentTool.tsx:437 reasons.join('\\n')），
        //      与 CC 文案不一致会导致用户按错误指引操作。
        assertThat(RemoteAgentEligibility.formatPreconditionError(PreconditionType.NOT_LOGGED_IN))
            .isEqualTo("Please run /login and sign in with your Claude.ai account (not Console).");
        assertThat(RemoteAgentEligibility.formatPreconditionError(PreconditionType.NO_REMOTE_ENVIRONMENT))
            .isEqualTo("No cloud environment available. Set one up at https://claude.ai/code/onboarding?magic=env-setup");
        assertThat(RemoteAgentEligibility.formatPreconditionError(PreconditionType.NOT_IN_GIT_REPO))
            .isEqualTo("Background tasks require a git repository. Initialize git or run from a git repository.");
        assertThat(RemoteAgentEligibility.formatPreconditionError(PreconditionType.NO_GIT_REMOTE))
            .isEqualTo("Background tasks require a GitHub remote. Add one with `git remote add origin REPO_URL`.");
        assertThat(RemoteAgentEligibility.formatPreconditionError(PreconditionType.GITHUB_APP_NOT_INSTALLED))
            .isEqualTo("The Claude GitHub app must be installed on this repository first.\nhttps://github.com/apps/claude/installations/new");
        assertThat(RemoteAgentEligibility.formatPreconditionError(PreconditionType.POLICY_BLOCKED))
            .isEqualTo("Remote sessions are disabled by your organization's policy. Contact your organization admin to enable them.");
    }
}
