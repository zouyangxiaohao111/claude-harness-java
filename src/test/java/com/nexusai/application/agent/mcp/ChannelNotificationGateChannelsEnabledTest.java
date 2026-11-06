package com.nexusai.application.agent.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [V254 外部渠道] 门序[2] runtime 的<b>三态判定</b>：DB settings 权威、yml 兜底。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：门序[2] 决定外部 MCP server 能否注册
 * {@code notifications/claude/channel} handler（= 外部消息能否进会话）。这一门现在有两个来源
 * （DB settings 列 + yml 静态配置），三态语义必须钉死：
 * <ol>
 *   <li><b>DB 有值 → DB 说了算</b>：用户显式关闭（false）必须压住 yml 的 true ——
 *       否则「我在 UI 关掉了通道，重启后端它又自己开了」；</li>
 *   <li><b>DB 未配置（null）→ 回落 yml</b>：保留「yml 一把开」的运维便利；</li>
 *   <li><b>读源未安装 / 读失败 → 回落 yml</b>（不反转用户设置）。</li>
 * </ol>
 *
 * <p><b>失败即回归的两种写法</b>：
 * <ul>
 *   <li>把「DB false 时回落 yml true」写回 ⇒ {@link #dbFalseOverridesYmlTrue} 变红；</li>
 *   <li>把「DB null 时不回落 yml」写死 ⇒ {@link #dbNullFallsBackToYml} 变红。</li>
 * </ul>
 */
@DisplayName("[V254] 门序[2] runtime · DB 三态优先 + yml 兜底")
class ChannelNotificationGateChannelsEnabledTest {

    private static final ChannelAllowlist.ChannelEntry ENTRY =
        new ChannelAllowlist.ChannelEntry("plugin", "wechat", "anthropic", false);

    /** 构造 gate：yml 读源固定传给定值；DB 读源另行安装。 */
    private static ChannelNotificationGate gateWithYml(boolean ymlValue) {
        return new ChannelNotificationGate(
            () -> ymlValue,
            () -> List.of(ENTRY),
            () -> List.of(),
            ChannelNotificationGate::escapeXmlAttr);
    }

    private static ChannelNotificationGate.ServerCapabilities withChannelCapability() {
        return new ChannelNotificationGate.ServerCapabilities(Map.of("claude/channel", Map.of()));
    }

    @AfterEach
    void uninstallDbSource() {
        // 防跨用例泄漏：static 读源不卸载会污染下一个用例（记忆里的「static 缓存跨用例污染」教训）。
        ChannelNotificationGate.installChannelsEnabledSource(null);
    }

    @Test
    @DisplayName("DB 显式 false 压住 yml true：用户在 UI 关掉通道后，重启后端不会复活")
    void dbFalseOverridesYmlTrue() {
        // WHY：这是本次改造的核心意图 ——「UI 开关点了等于没点」的镜像版本：
        //   用户显式关闭后，若被 yml 顶回来，通道就在用户不知情的情况下重新打开（外部入口失守）。
        ChannelNotificationGate.installChannelsEnabledSource(() -> false);
        ChannelNotificationGate gate = gateWithYml(true);

        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "plugin:wechat:1.0.0", withChannelCapability(), "wechat@anthropic");

        assertThat(r.action())
            .as("DB 显式 false 必须压住 yml true —— 用户在 UI 关掉通道就应当真的关掉")
            .isEqualTo("skip");
        assertThat(r.kind())
            .as("跳过原因必须是 DISABLED（runtime 门），不是 capability/session 等其他门")
            .isEqualTo(ChannelNotificationGate.GateKind.DISABLED);
    }

    @Test
    @DisplayName("DB 显式 true 打开通道（即使 yml 是 false）")
    void dbTrueEnablesEvenWhenYmlFalse() {
        // WHY：用户在 UI 打开通道 ⇒ 必须立即生效，不必去改 yml 或重启后端。
        ChannelNotificationGate.installChannelsEnabledSource(() -> true);
        ChannelNotificationGate gate = gateWithYml(false);

        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "plugin:wechat:1.0.0", withChannelCapability(), "wechat@anthropic");

        assertThat(r.kind())
            .as("DB 显式 true 必须能独立打开通道（UI 开关的语义就是权威源）——"
                + "门序[2] runtime 不得拦下它")
            .isNotEqualTo(ChannelNotificationGate.GateKind.DISABLED);
    }

    @Test
    @DisplayName("DB 未配置（null）→ 回落 yml：yml=true 则放行")
    void dbNullFallsBackToYml() {
        // WHY：DB 列是「未配置」而非「关闭」⇒ 保留 yml 一把开的运维便利。
        //   若这里直接判 false，运维改 yml 打开通道的方式就废了。
        ChannelNotificationGate.installChannelsEnabledSource(() -> null);
        ChannelNotificationGate gate = gateWithYml(true);

        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "plugin:wechat:1.0.0", withChannelCapability(), "wechat@anthropic");

        assertThat(r.kind())
            .as("DB 未配置 → 回落 yml（yml=true ⇒ 门序[2] 不得拦下）")
            .isNotEqualTo(ChannelNotificationGate.GateKind.DISABLED);
    }

    @Test
    @DisplayName("DB 未配置（null）→ 回落 yml：yml=false 则关闭")
    void dbNullFallsBackToYmlDisabled() {
        ChannelNotificationGate.installChannelsEnabledSource(() -> null);
        ChannelNotificationGate gate = gateWithYml(false);

        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "plugin:wechat:1.0.0", withChannelCapability(), "wechat@anthropic");

        assertThat(r.action())
            .as("DB 未配置 + yml=false ⇒ 通道关闭（fail-closed 默认）")
            .isEqualTo("skip");
        assertThat(r.kind()).isEqualTo(ChannelNotificationGate.GateKind.DISABLED);
    }

    @Test
    @DisplayName("DB 读源未安装 → 回落 yml（POJO 单测/装配失败不反转用户设置）")
    void missingDbSourceFallsBackToYml() {
        // WHY：读源未安装 = 拿不到 DB 值（如纯 POJO 单测无 Spring 上下文）。
        //   此时应回落 yml，而不是硬判 false —— 否则单测里 yml 的语义被架空。
        ChannelNotificationGate.installChannelsEnabledSource(null);
        ChannelNotificationGate gate = gateWithYml(true);

        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "plugin:wechat:1.0.0", withChannelCapability(), "wechat@anthropic");

        assertThat(r.kind())
            .as("读源未安装 → 回落 yml（yml=true ⇒ 门序[2] 不得拦下）")
            .isNotEqualTo(ChannelNotificationGate.GateKind.DISABLED);
    }

    @Test
    @DisplayName("DB 读源抛异常 → 回落 yml（不因一次 DB 抖动反转用户设置）")
    void dbReadFailureFallsBackToYml() {
        // WHY：DB 抖动是瞬时的。若读失败就当成 false，用户会看到「通道莫名关了」；
        //   若读失败就当成 true，更糟 —— 用户显式关掉的通道被一次抖动顶开。
        //   正确语义：**读不到 = 未配置** → 回落 yml 兜底（yml 默认 false ⇒ fail-closed）。
        ChannelNotificationGate.installChannelsEnabledSource(() -> {
            throw new RuntimeException("模拟 DB 读失败");
        });
        ChannelNotificationGate gate = gateWithYml(false);

        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "plugin:wechat:1.0.0", withChannelCapability(), "wechat@anthropic");

        assertThat(r.action())
            .as("读失败 → 回落 yml（yml=false ⇒ 关闭，fail-closed）")
            .isEqualTo("skip");
        assertThat(r.kind()).isEqualTo(ChannelNotificationGate.GateKind.DISABLED);
    }
}
