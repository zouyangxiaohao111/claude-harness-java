package com.nexusai.application.agent.mcp;

import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [impl-I-3 T1] gateChannelServer 自有门序契约测试（对齐 CC channelNotification.ts:191-316，
 * OPD-MCP-04 去 claude.ai OAuth 依赖）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: 门序决定 MCP channel server 是否注册
 * notifications/claude/channel handler（skip → 连接保持、handler 不注册，L183-186）。
 * 旧实现含 auth（OAuth）门 —— API key 用户恒被跳过；本批 OPD-MCP-04 去 OAuth，
 * 自有门控：capability → channelsEnabled → session → marketplace（fail-closed）→ allowlist。
 * ⑥ 即 RED 触发点：无 OAuth token 仍必须 register（旧 auth 门在此断言上失败）。
 */
class ChannelNotificationGateCcContractTest {

    /** 插件运行期 server 名（CC addPluginScopeToServers mcpPluginIntegration.ts:341 scopedName=plugin:name:X）。 */
    private static final String PLUGIN_SERVER = "plugin:slack:1.0.0";
    private static final String PLUGIN_SERVER_EVIL = "plugin:slack:1.0.0";
    private static final ChannelAllowlist.ChannelEntry SLACK_ENTRY =
        new ChannelAllowlist.ChannelEntry("plugin", "slack", "anthropic", false);

    private static ChannelNotificationGate gate(
            boolean channelsEnabled,
            List<ChannelAllowlist.ChannelEntry> session,
            List<ChannelAllowlistEntry> ledger) {
        return new ChannelNotificationGate(
            () -> channelsEnabled,
            () -> session,
            () -> ledger,
            ChannelNotificationGate::escapeXmlAttr);
    }

    private static ChannelNotificationGate.ServerCapabilities withChannelCapability() {
        return new ChannelNotificationGate.ServerCapabilities(Map.of("claude/channel", Map.of()));
    }

    @Test
    @DisplayName("① 无 experimental['claude/channel'] capability → skip/CAPABILITY")
    void noCapability_skipsCapability() {
        ChannelNotificationGate gate = gate(true, List.of(SLACK_ENTRY), List.of());
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            PLUGIN_SERVER, new ChannelNotificationGate.ServerCapabilities(Map.of()), "slack@anthropic");
        assertThat(r.action()).isEqualTo("skip");
        assertThat(r.kind()).isEqualTo(ChannelNotificationGate.GateKind.CAPABILITY);
    }

    @Test
    @DisplayName("② capability 有 + channelsEnabled=false → skip/DISABLED")
    void disabled_skipsDisabled() {
        ChannelNotificationGate gate = gate(false, List.of(SLACK_ENTRY), List.of());
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            PLUGIN_SERVER, withChannelCapability(), "slack@anthropic");
        assertThat(r.action()).isEqualTo("skip");
        assertThat(r.kind()).isEqualTo(ChannelNotificationGate.GateKind.DISABLED);
    }

    @Test
    @DisplayName("③ capability + channelsEnabled + 不在 session --channels → skip/SESSION")
    void notInSession_skipsSession() {
        ChannelNotificationGate gate = gate(true, List.of(), List.of());
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            PLUGIN_SERVER, withChannelCapability(), "slack@anthropic");
        assertThat(r.action()).isEqualTo("skip");
        assertThat(r.kind()).isEqualTo(ChannelNotificationGate.GateKind.SESSION);
    }

    @Test
    @DisplayName("④ 在 session + 不在 DB ledger → skip/ALLOWLIST")
    void notInLedger_skipsAllowlist() {
        ChannelNotificationGate gate = gate(true, List.of(SLACK_ENTRY), List.of());
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            PLUGIN_SERVER, withChannelCapability(), "slack@anthropic");
        assertThat(r.action()).isEqualTo("skip");
        assertThat(r.kind()).isEqualTo(ChannelNotificationGate.GateKind.ALLOWLIST);
    }

    @Test
    @DisplayName("⑤ 全过（capability+enabled+session+marketplace+ledger）→ register")
    void allPass_registers() {
        ChannelNotificationGate gate = gate(true, List.of(SLACK_ENTRY),
            List.of(new ChannelAllowlistEntry("anthropic", "slack")));
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            PLUGIN_SERVER, withChannelCapability(), "slack@anthropic");
        assertThat(r.action()).isEqualTo("register");
        assertThat(r.kind()).isNull();
    }

    @Test
    @DisplayName("⑥ 不注入任何 OAuth token 仍 register（auth 门已去，自有门控语义）")
    void noOAuthToken_stillRegisters() {
        // RED 依据（F2）：旧实现 auth 门在 oauthTokensSupplier 空时返回 skip/AUTH，
        // 本断言（无任何 OAuth 依赖即 register）对旧实现必失败。
        ChannelNotificationGate gate = gate(true, List.of(SLACK_ENTRY),
            List.of(new ChannelAllowlistEntry("anthropic", "slack")));
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            PLUGIN_SERVER, withChannelCapability(), "slack@anthropic");
        assertThat(r.action()).as("OPD-MCP-04 去 OAuth：无 token 必须 register")
            .isEqualTo("register");
    }

    @Test
    @DisplayName("⑦ pluginSource=null 或无 marketplace → skip/MARKETPLACE（fail-closed，对齐 CC L267-276）")
    void marketplaceUnknownSource_failsClosed() {
        // pluginSource=null（非插件 server / 未接线）→ actual=undefined → 与 entry.marketplace 不等 → skip
        ChannelNotificationGate gate = gate(true, List.of(SLACK_ENTRY),
            List.of(new ChannelAllowlistEntry("anthropic", "slack")));
        ChannelNotificationGate.ChannelGateResult rNull = gate.gateChannelServer(
            PLUGIN_SERVER, withChannelCapability(), null);
        assertThat(rNull.action()).isEqualTo("skip");
        assertThat(rNull.kind()).isEqualTo(ChannelNotificationGate.GateKind.MARKETPLACE);

        // pluginSource 无 @（bare / builtin / inline）→ marketplace undefined → skip
        ChannelNotificationGate.ChannelGateResult rBare = gate.gateChannelServer(
            PLUGIN_SERVER, withChannelCapability(), "slack");
        assertThat(rBare.action()).isEqualTo("skip");
        assertThat(rBare.kind()).isEqualTo(ChannelNotificationGate.GateKind.MARKETPLACE);

        // pluginSource 来源是别家 marketplace → 安装来源与标签错配 → skip（不产生安全绕过）
        ChannelNotificationGate.ChannelGateResult rEvil = gate.gateChannelServer(
            PLUGIN_SERVER_EVIL, withChannelCapability(), "slack@evil");
        assertThat(rEvil.action()).isEqualTo("skip");
        assertThat(rEvil.kind()).isEqualTo(ChannelNotificationGate.GateKind.MARKETPLACE);
    }

    @Test
    @DisplayName("⑧ plugin-kind entry + pluginSource=slack@anthropic + ledger 含 {slack,anthropic} → register")
    void pluginKindWithMatchingSourceAndLedger_registers() {
        ChannelNotificationGate gate = gate(true, List.of(SLACK_ENTRY),
            List.of(new ChannelAllowlistEntry("anthropic", "slack")));
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            PLUGIN_SERVER, withChannelCapability(), "slack@anthropic");
        assertThat(r.action()).isEqualTo("register");
    }

    @Test
    @DisplayName("server-kind entry 恒 skip ALLOWLIST（schema 仅 plugin，CC L302-313）")
    void serverKind_alwaysSkipsAllowlistUnlessDev() {
        ChannelAllowlist.ChannelEntry serverEntry =
            new ChannelAllowlist.ChannelEntry("server", "my-server", null, false);
        ChannelNotificationGate gate = gate(true, List.of(serverEntry), List.of());
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "my-server", withChannelCapability(), null);
        assertThat(r.action()).isEqualTo("skip");
        assertThat(r.kind()).isEqualTo(ChannelNotificationGate.GateKind.ALLOWLIST);

        // dev 豁免（--dangerously-load-development-channels）
        ChannelAllowlist.ChannelEntry devServer =
            new ChannelAllowlist.ChannelEntry("server", "my-server", null, true);
        ChannelNotificationGate gateDev = gate(true, List.of(devServer), List.of());
        ChannelNotificationGate.ChannelGateResult rDev = gateDev.gateChannelServer(
            "my-server", withChannelCapability(), null);
        assertThat(rDev.action()).isEqualTo("register");
    }

    @Test
    @DisplayName("wrapChannelMessage: source 属性 + SAFE_META_KEY 过滤 + 转义（CC L106-116）")
    void wrapChannelMessage_escapesAndFiltersMetaKeys() {
        ChannelNotificationGate gate = gate(true, List.of(), List.of());
        String wrapped = gate.wrapChannelMessage("slack<>&\"", "hi",
            Map.of("chat_id", "123", "x=\" injected=\"y", "bad"));
        assertThat(wrapped).startsWith("<channel source=\"slack&lt;&gt;&amp;&quot;\" chat_id=\"123\">\n");
        assertThat(wrapped).contains("\nhi\n</channel>");
        // 非法 meta key（含注入尝试）被 SAFE_META_KEY 过滤
        assertThat(wrapped).doesNotContain("injected");
        assertThat(wrapped).contains(" chat_id=\"123\"");
    }
}
