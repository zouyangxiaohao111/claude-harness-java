package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.plugin.OfficialMarketplace.CheckResult;
import com.nexusai.application.agent.plugin.OfficialMarketplace.SkipReason;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MPL9] OfficialMarketplace · 对齐 CC officialMarketplace.ts + officialMarketplaceStartupCheck.ts:147。
 *
 * <p>WHY（规则九）：CC 的官方 marketplace 是启动自动安装 + 幂等跳过已装（startupCheck :183-195），
 * policy/git 门槛齐全。旧 Java 无此层 —— 新用户启动不会预装官方市场。本测试锁定幂等/门槛/安装五组契约。
 */
@DisplayName("[MPL9] OfficialMarketplace 对齐 CC officialMarketplace")
class OfficialMarketplaceTest {

    @Test
    @DisplayName("常量对齐 CC officialMarketplace.ts:15-25")
    void constantsAlignCc() {
        assertThat(OfficialMarketplace.OFFICIAL_MARKETPLACE_NAME).isEqualTo("claude-plugins-official");
        assertThat(OfficialMarketplace.OFFICIAL_MARKETPLACE_SOURCE)
            .isInstanceOfSatisfying(MarketplaceSource.Github.class,
                g -> assertThat(g.repo()).isEqualTo("anthropics/claude-plugins-official"));
    }

    @Test
    @DisplayName("已装幂等跳过 → already_installed（不重复安装）")
    void alreadyInstalledIdempotentSkip() {
        OfficialMarketplace om = new OfficialMarketplace(
            n -> true, s -> "installed", s -> true, () -> true);

        CheckResult r = om.checkAndInstallOfficialMarketplace();

        assertThat(r.installed()).isFalse();
        assertThat(r.skipped()).isTrue();
        assertThat(r.reason()).isEqualTo(SkipReason.ALREADY_INSTALLED);
    }

    @Test
    @DisplayName("企业 policy 阻断 → policy_blocked")
    void policyBlocked() {
        OfficialMarketplace om = new OfficialMarketplace(
            n -> false, s -> "x", s -> false, () -> true);

        assertThat(om.checkAndInstallOfficialMarketplace().reason())
            .isEqualTo(SkipReason.POLICY_BLOCKED);
    }

    @Test
    @DisplayName("git 不可用 → git_unavailable")
    void gitUnavailable() {
        OfficialMarketplace om = new OfficialMarketplace(
            n -> false, s -> "x", s -> true, () -> false);

        assertThat(om.checkAndInstallOfficialMarketplace().reason())
            .isEqualTo(SkipReason.GIT_UNAVAILABLE);
    }

    @Test
    @DisplayName("门槛全过 → 安装官方源并返回 installed=true")
    void installSuccess() {
        List<MarketplaceSource> installed = new ArrayList<>();
        OfficialMarketplace om = new OfficialMarketplace(
            n -> false,
            s -> {
                installed.add(s);
                return "claude-plugins-official";
            },
            s -> true, () -> true);

        CheckResult r = om.checkAndInstallOfficialMarketplace();

        assertThat(r.installed()).isTrue();
        assertThat(r.skipped()).isFalse();
        assertThat(installed).containsExactly(OfficialMarketplace.OFFICIAL_MARKETPLACE_SOURCE);
    }

    @Test
    @DisplayName("安装失败 → unknown（异常不向外抛，startup 不崩）")
    void installFailureUnknown() {
        OfficialMarketplace om = new OfficialMarketplace(
            n -> false, s -> {
                throw new IOException("network down");
            }, s -> true, () -> true);

        assertThat(om.checkAndInstallOfficialMarketplace().reason())
            .isEqualTo(SkipReason.UNKNOWN);
    }
}
