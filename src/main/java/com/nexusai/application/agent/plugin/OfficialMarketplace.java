package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 官方 Anthropic marketplace 自动安装 · 对齐 CC {@code utils/plugins/officialMarketplace.ts}（25 行）
 * + {@code officialMarketplaceStartupCheck.ts:147-359} checkAndInstallOfficialMarketplace。
 *
 * <p>官方 marketplace 托管于 GitHub（anthropics/claude-plugins-official），提供 Anthropic
 * 一手插件。启动时为新用户自动安装，检查项（CC :134-146）：企业 policy / git 可用性 / 已尝试。
 *
 * <p><b>常量</b>（officialMarketplace.ts:15-25）：
 * <pre>
 *   OFFICIAL_MARKETPLACE_SOURCE = { source:'github', repo:'anthropics/claude-plugins-official' }
 *   OFFICIAL_MARKETPLACE_NAME   = 'claude-plugins-official'
 * </pre>
 *
 * <p><b>checkAndInstall 检查顺序</b>（officialMarketplaceStartupCheck.ts:147-359，简化后）：
 * <ol>
 *   <li>env 禁用开关 {@code CLAUDE_CODE_DISABLE_OFFICIAL_MARKETPLACE_AUTOINSTALL} → policy_blocked（:164-180）</li>
 *   <li>已装幂等跳过（known_marketplaces 有该名）→ already_installed（:183-195）</li>
 *   <li>企业 policy 允许 → policy_blocked（:198-214）</li>
 *   <li>git 可用 → git_unavailable（:288-333）</li>
 *   <li>addMarketplaceSource 安装（:335-358）</li>
 * </ol>
 *
 * <p>非 @Component：由 {@link PluginStartupAssembler} 经 {@link #wire} 显式装配（避免未接线孤儿 bean）。
 */
public class OfficialMarketplace {

    private static final Logger log = LoggerFactory.getLogger(OfficialMarketplace.class);

    /** CC original: {@code OFFICIAL_MARKETPLACE_SOURCE}（officialMarketplace.ts:15-18）。 */
    public static final MarketplaceSource OFFICIAL_MARKETPLACE_SOURCE =
        new MarketplaceSource.Github("anthropics/claude-plugins-official", null, null, null);

    /** CC original: {@code OFFICIAL_MARKETPLACE_NAME}（officialMarketplace.ts:25）。 */
    public static final String OFFICIAL_MARKETPLACE_NAME = "claude-plugins-official";

    /** CC original: {@code CLAUDE_CODE_DISABLE_OFFICIAL_MARKETPLACE_AUTOINSTALL}（:50）。 */
    public static final String DISABLE_AUTOINSTALL_ENV = "CLAUDE_CODE_DISABLE_OFFICIAL_MARKETPLACE_AUTOINSTALL";

    /** CC original: {@code OfficialMarketplaceSkipReason}（:36-42）。 */
    public enum SkipReason {
        ALREADY_ATTEMPTED, ALREADY_INSTALLED, POLICY_BLOCKED, GIT_UNAVAILABLE, UNKNOWN
    }

    /** CC original: {@code OfficialMarketplaceCheckResult}（:122-131）。 */
    public record CheckResult(boolean installed, boolean skipped, SkipReason reason) {
        public static CheckResult success() {
            return new CheckResult(true, false, null);
        }

        public static CheckResult skip(SkipReason reason) {
            return new CheckResult(false, true, reason);
        }
    }

    /** 已装判定 · known_marketplaces 是否含官方名。 */
    @FunctionalInterface
    public interface PresenceCheck {
        boolean contains(String name);
    }

    /** 安装回调 · CC {@code addMarketplaceSource}（marketplaceManager.ts:1782-1923）。 */
    @FunctionalInterface
    public interface MarketplaceInstaller {
        String install(MarketplaceSource source) throws IOException;
    }

    /** 企业 policy 判定 · CC {@code isSourceAllowedByPolicy}（marketplaceHelpers.ts）。 */
    @FunctionalInterface
    public interface PolicyChecker {
        boolean isAllowed(MarketplaceSource source);
    }

    /** git 可用性判定 · CC {@code checkGitAvailable}（gitAvailability.ts）。 */
    @FunctionalInterface
    public interface GitChecker {
        boolean isAvailable();
    }

    private final PresenceCheck presence;
    private final MarketplaceInstaller installer;
    private final PolicyChecker policy;
    private final GitChecker git;

    public OfficialMarketplace(PresenceCheck presence, MarketplaceInstaller installer,
                               PolicyChecker policy, GitChecker git) {
        this.presence = presence == null ? n -> false : presence;
        this.installer = installer == null ? s -> {
            throw new IOException("marketplace 安装器未接线");
        } : installer;
        this.policy = policy == null ? s -> true : policy;
        this.git = git == null ? () -> true : git;
    }

    /** 默认装配（无注入 → 检查恒跳过已装/缺依赖降级）。 */
    public OfficialMarketplace() {
        this(null, null, null, null);
    }

    /**
     * 生产装配 · MarketplaceManager（presence/installer）+ 简化 policy/git。
     *
     * <p>policy：Java 无企业 policy 层（PluginInstaller 有 blocked-by-policy 字符串但无 policy 判定
     * 服务），默认允许（registry 项）；git：GitProcessRunner 探测存在性（gitAvailable 探测简化，
     * 不跑实际命令，仅 resolveGitExecutable 非 null）。installer 经 MarketplaceReconciler
     * addMarketplaceSource（MPL8，源幂等已存在等值 source 直接返回）。
     */
    public static OfficialMarketplace wire(MarketplaceManager marketplaceManager,
                                           MarketplaceReconciler reconciler) {
        return new OfficialMarketplace(
            name -> marketplaceManager.loadKnownMarketplacesConfigSafe().containsKey(name),
            source -> reconciler.addMarketplaceSource(source).name(),
            src -> true,
            () -> GitProcessRunner.resolveGitExecutable() != null);
    }

    /** CC {@code isOfficialMarketplaceAutoInstallDisabled}（:47-51）· env 真值判定。 */
    public static boolean isOfficialMarketplaceAutoInstallDisabled() {
        return PluginDirectories.isEnvTruthy(System.getenv(DISABLE_AUTOINSTALL_ENV));
    }

    /**
     * 启动时检查并安装官方 marketplace · CC {@code checkAndInstallOfficialMarketplace}（:147-359）。
     *
     * <p>设计为 fire-and-forget（startup 不阻塞）。幂等：已装 → already_installed 跳过；
     * 安装成功才返回 installed=true。
     */
    public CheckResult checkAndInstallOfficialMarketplace() {
        if (isOfficialMarketplaceAutoInstallDisabled()) {
            log.info("官方 marketplace 自动安装被环境变量禁用，跳过（CC :164-180）");
            return CheckResult.skip(SkipReason.POLICY_BLOCKED);
        }
        if (presence.contains(OFFICIAL_MARKETPLACE_NAME)) {
            if (log.isDebugEnabled()) {
                log.debug("官方 marketplace '{}' 已安装，幂等跳过（CC :183-195）", OFFICIAL_MARKETPLACE_NAME);
            }
            return CheckResult.skip(SkipReason.ALREADY_INSTALLED);
        }
        if (!policy.isAllowed(OFFICIAL_MARKETPLACE_SOURCE)) {
            log.warn("官方 marketplace 被企业 policy 阻断，跳过（CC :198-214）");
            return CheckResult.skip(SkipReason.POLICY_BLOCKED);
        }
        if (!git.isAvailable()) {
            log.warn("git 不可用，跳过官方 marketplace 自动安装（CC :288-333）");
            return CheckResult.skip(SkipReason.GIT_UNAVAILABLE);
        }
        try {
            log.info("尝试自动安装官方 marketplace（CC :335-358）");
            installer.install(OFFICIAL_MARKETPLACE_SOURCE);
            return CheckResult.success();
        } catch (Exception error) {
            log.error("自动安装官方 marketplace 失败：{}", error.getMessage());
            return CheckResult.skip(SkipReason.UNKNOWN);
        }
    }
}
