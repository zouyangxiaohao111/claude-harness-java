package com.nexusai.application.agent.remote;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * remote_agent 资格门控 · 对齐 CC RemoteAgentTask/RemoteAgentTask.tsx:124-161
 * + utils/background/remote/remoteSession.ts:31-97。
 *
 * <p>CC 真源（grep -n 自验）:
 * <ul>
 *   <li>{@code checkRemoteAgentEligibility} — RemoteAgentTask.tsx:124-141：委托
 *       {@code checkBackgroundRemoteSessionEligibility({skipBundle})}，errors 非空 →
 *       {@code {eligible:false, errors}}，否则 {@code {eligible:true}}。</li>
 *   <li>{@code checkBackgroundRemoteSessionEligibility} — remoteSession.ts:45-97：
 *       policy 先行（阻断直接返回仅 policy_blocked）→ 并行 needsLogin/hasRemoteEnv/
 *       detectRepository → not_in_git_repo → bundleSeedGateOn 跳过 remote+app 检查 →
 *       repository null → no_git_remote → host==='github.com' → github_app_not_installed。</li>
 *   <li>{@code formatPreconditionError} — RemoteAgentTask.tsx:146-161：6 类型 → 用户提示文案。</li>
 * </ul>
 *
 * <p><b>WHY（规则九）</b>: 远程 agent 创建前必须通过资格门控（CC AgentTool.tsx:436-438 失败即
 * throw "Cannot launch remote agent"），否则在无 auth/无远程环境/非 git 仓库等场景下创建任务
 * 只会徒劳轮询。本类把 CC 的 6 种失败前置条件收敛为可注入检查函数的门控，
 * 生产依赖（OAuth/git/远程环境/GitHub App）由调用方注入，纯逻辑可脱离真实 IO 测试。
 */
public final class RemoteAgentEligibility {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentEligibility.class);

    /** CC BackgroundRemoteSessionPrecondition（remoteSession.ts:31-37）— 6 种失败前置条件。 */
    public enum PreconditionType {
        /** CC original: 'not_logged_in'. */
        NOT_LOGGED_IN("not_logged_in"),
        /** CC original: 'no_remote_environment'. */
        NO_REMOTE_ENVIRONMENT("no_remote_environment"),
        /** CC original: 'not_in_git_repo'. */
        NOT_IN_GIT_REPO("not_in_git_repo"),
        /** CC original: 'no_git_remote'. */
        NO_GIT_REMOTE("no_git_remote"),
        /** CC original: 'github_app_not_installed'. */
        GITHUB_APP_NOT_INSTALLED("github_app_not_installed"),
        /** CC original: 'policy_blocked'. */
        POLICY_BLOCKED("policy_blocked");

        private final String value;

        PreconditionType(String value) {
            this.value = value;
        }

        /** CC wire 值（snake_case 字面量，remoteSession.ts:31-37）. */
        public String value() {
            return value;
        }
    }

    /** CC Repository（detectCurrentRepositoryWithHost 返回）— host/owner/name. */
    public record Repository(String host, String owner, String name) {
    }

    /** CC RemoteAgentPreconditionResult（RemoteAgentTask.tsx:115-122）. */
    public sealed interface EligibilityResult {
        /** CC {eligible: true} — 通过全部前置条件。 */
        record Eligible() implements EligibilityResult {
        }

        /** CC {eligible: false, errors} — 列出全部失败前置条件。 */
        record NotEligible(List<PreconditionType> errors) implements EligibilityResult {
            public NotEligible {
                errors = List.copyOf(errors);
            }
        }
    }

    /** CC isPolicyAllowed('allow_remote_sessions')（remoteSession.ts:52-54）— policy 先行阻断. */
    private final BooleanSupplier policyAllowed;
    /** CC checkNeedsClaudeAiLogin（remoteSession.ts:56-57）. */
    private final BooleanSupplier needsClaudeAiLogin;
    /** CC checkHasRemoteEnvironment（remoteSession.ts:58-60）. */
    private final BooleanSupplier hasRemoteEnvironment;
    /** CC checkIsInGitRepo（remoteSession.ts:80）. */
    private final BooleanSupplier isInGitRepo;
    /** CC bundleSeedGateOn 门控（remoteSession.ts:69-73）— CCR_FORCE_BUNDLE/CCR_ENABLE_BUNDLE/gate. */
    private final BooleanSupplier bundleSeedGateOn;
    /** CC detectCurrentRepositoryWithHost（remoteSession.ts:85-91）— null 表示无 remote. */
    private final Supplier<Repository> detectRepository;
    /** CC checkGithubAppInstalled(owner, name)（remoteSession.ts:89-93）. */
    private final BiPredicate<String, String> githubAppInstalled;

    /**
     * @param policyAllowed       {@code isPolicyAllowed('allow_remote_sessions')} — false 时
     *                            仅返回 policy_blocked（CC remoteSession.ts:52-54）
     * @param needsClaudeAiLogin  {@code checkNeedsClaudeAiLogin()} — true 时 push not_logged_in
     * @param hasRemoteEnvironment {@code checkHasRemoteEnvironment()} — false 时 push no_remote_environment
     * @param isInGitRepo         {@code checkIsInGitRepo()} — false 时 push not_in_git_repo
     * @param bundleSeedGateOn    bundle 门控（CCR_FORCE_BUNDLE || CCR_ENABLE_BUNDLE ||
     *                            gate('tengu_ccr_bundle_seed_enabled')）— true 且 in-git-repo 时跳过 remote+app 检查
     * @param detectRepository    {@code detectCurrentRepositoryWithHost()} — null 表示无 git remote
     * @param githubAppInstalled  {@code checkGithubAppInstalled(owner, name)} — 仅 host==='github.com' 时调用
     */
    public RemoteAgentEligibility(BooleanSupplier policyAllowed,
                                  BooleanSupplier needsClaudeAiLogin,
                                  BooleanSupplier hasRemoteEnvironment,
                                  BooleanSupplier isInGitRepo,
                                  BooleanSupplier bundleSeedGateOn,
                                  Supplier<Repository> detectRepository,
                                  BiPredicate<String, String> githubAppInstalled) {
        this.policyAllowed = Objects.requireNonNull(policyAllowed);
        this.needsClaudeAiLogin = Objects.requireNonNull(needsClaudeAiLogin);
        this.hasRemoteEnvironment = Objects.requireNonNull(hasRemoteEnvironment);
        this.isInGitRepo = Objects.requireNonNull(isInGitRepo);
        this.bundleSeedGateOn = Objects.requireNonNull(bundleSeedGateOn);
        this.detectRepository = Objects.requireNonNull(detectRepository);
        this.githubAppInstalled = Objects.requireNonNull(githubAppInstalled);
    }

    /**
     * CC checkRemoteAgentEligibility（RemoteAgentTask.tsx:124-141）— 委托
     * checkBackgroundRemoteSessionEligibility，errors 非空 → not eligible。
     *
     * @param skipBundle CC {skipBundle=false} — true 时关闭 bundle 种子门控（bundleSeedGateOn 恒 false）
     */
    public EligibilityResult checkRemoteAgentEligibility(boolean skipBundle) {
        List<PreconditionType> errors = checkBackgroundRemoteSessionEligibility(skipBundle);
        if (errors.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[RemoteAgentEligibility] 资格通过（eligible=true）");
            }
            return new EligibilityResult.Eligible();
        }
        if (log.isDebugEnabled()) {
            log.debug("[RemoteAgentEligibility] 资格未通过: {}", errors);
        }
        return new EligibilityResult.NotEligible(errors);
    }

    /**
     * CC checkBackgroundRemoteSessionEligibility（remoteSession.ts:45-97）。
     *
     * <p>顺序（CC 真源）:
     * <ol>
     *   <li>policy 先行：false → 仅 push policy_blocked 并直接返回（不检查其它前置条件）</li>
     *   <li>needsLogin → not_logged_in；!hasRemoteEnv → no_remote_environment</li>
     *   <li>!inGitRepo → not_in_git_repo（短路，不再判 remote）</li>
     *   <li>bundleSeedGateOn（且 skipBundle=false）→ 跳过 remote+app 检查</li>
     *   <li>repository null → no_git_remote</li>
     *   <li>host==='github.com' && !appInstalled → github_app_not_installed</li>
     * </ol>
     */
    public List<PreconditionType> checkBackgroundRemoteSessionEligibility(boolean skipBundle) {
        List<PreconditionType> errors = new ArrayList<>();

        // CC remoteSession.ts:52-54 — policy 先行阻断
        if (!policyAllowed.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("[RemoteAgentEligibility] policy 阻断（allow_remote_sessions=false）");
            }
            errors.add(PreconditionType.POLICY_BLOCKED);
            return errors;
        }

        boolean needsLogin = needsClaudeAiLogin.getAsBoolean();
        boolean hasRemoteEnv = hasRemoteEnvironment.getAsBoolean();
        Repository repository = detectRepository.get();

        // CC remoteSession.ts:61-62
        if (needsLogin) {
            errors.add(PreconditionType.NOT_LOGGED_IN);
        }
        // CC remoteSession.ts:63-64
        if (!hasRemoteEnv) {
            errors.add(PreconditionType.NO_REMOTE_ENVIRONMENT);
        }

        // CC remoteSession.ts:69-73 — bundle 种子门控（仅 skipBundle=false 时生效）
        boolean bundleSeed = !skipBundle && bundleSeedGateOn.getAsBoolean();

        // CC remoteSession.ts:76-94
        if (!isInGitRepo.getAsBoolean()) {
            errors.add(PreconditionType.NOT_IN_GIT_REPO);
        } else if (bundleSeed) {
            // has .git/，bundle 可用 — 跳过 remote+app 检查（CC :80-82 注释）
        } else if (repository == null) {
            errors.add(PreconditionType.NO_GIT_REMOTE);
        } else if ("github.com".equals(repository.host())) {
            boolean installed = githubAppInstalled.test(repository.owner(), repository.name());
            if (!installed) {
                errors.add(PreconditionType.GITHUB_APP_NOT_INSTALLED);
            }
        }

        return errors;
    }

    /**
     * CC formatPreconditionError（RemoteAgentTask.tsx:146-161）— 6 类型 → 用户提示文案。
     * 文案与 CC 逐字对齐（行为对齐优先于中文化）。
     */
    public static String formatPreconditionError(PreconditionType error) {
        switch (error) {
            case NOT_LOGGED_IN:
                return "Please run /login and sign in with your Claude.ai account (not Console).";
            case NO_REMOTE_ENVIRONMENT:
                return "No cloud environment available. Set one up at https://claude.ai/code/onboarding?magic=env-setup";
            case NOT_IN_GIT_REPO:
                return "Background tasks require a git repository. Initialize git or run from a git repository.";
            case NO_GIT_REMOTE:
                return "Background tasks require a GitHub remote. Add one with `git remote add origin REPO_URL`.";
            case GITHUB_APP_NOT_INSTALLED:
                return "The Claude GitHub app must be installed on this repository first.\nhttps://github.com/apps/claude/installations/new";
            case POLICY_BLOCKED:
                return "Remote sessions are disabled by your organization's policy. Contact your organization admin to enable them.";
            default:
                throw new IllegalArgumentException("未知前置条件类型: " + error);
        }
    }
}
