package com.nexusai.application.agent.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [impl-I-3 T3 / R1] parsePluginIdentifier 语义对齐 CC pluginIdentifier.ts:51-57。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: v1 断言「plugin:slack@anthropic → {name='slack'}」错误，
 * CC 真源<b>只按首个 {@code @} 切、不剥 {@code plugin:} 前缀</b>（剥前缀属 main.tsx:1650-1675
 * parseChannelEntries）。若 Java 按剥前缀实现，marketplace 校验（channelNotification.ts:267-276
 * {@code parsePluginIdentifier(pluginSource).marketplace}）会把安装来源与标签错配 → 安全绕过。
 */
class ChannelAllowlistParseTest {

    @Test
    @DisplayName("parsePluginIdentifier('slack@anthropic') → {name='slack', marketplace='anthropic'}（真实 pluginSource 形态）")
    void parse_slackAtAnthropic() {
        ChannelAllowlist.PluginIdentifier id = ChannelAllowlist.parsePluginIdentifier("slack@anthropic");
        assertThat(id.name()).isEqualTo("slack");
        assertThat(id.marketplace()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("parsePluginIdentifier('slack') → {name='slack', marketplace=null}（bare 名无 marketplace）")
    void parse_bare() {
        ChannelAllowlist.PluginIdentifier id = ChannelAllowlist.parsePluginIdentifier("slack");
        assertThat(id.name()).isEqualTo("slack");
        assertThat(id.marketplace()).isNull();
    }

    @Test
    @DisplayName("parsePluginIdentifier('plugin:slack@anthropic') → {name='plugin:slack', marketplace='anthropic'}（不剥 plugin: 前缀，CC 原样）")
    void parse_pluginPrefixed_notStripped() {
        ChannelAllowlist.PluginIdentifier id = ChannelAllowlist.parsePluginIdentifier("plugin:slack@anthropic");
        assertThat(id.name()).as("CC pluginIdentifier.ts:51-57 不剥 plugin: 前缀").isEqualTo("plugin:slack");
        assertThat(id.marketplace()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("parsePluginIdentifier('plugin@market@place') → {name='plugin', marketplace='market'}（首个 @ 切分）")
    void parse_multiAt_usesFirstAt() {
        ChannelAllowlist.PluginIdentifier id = ChannelAllowlist.parsePluginIdentifier("plugin@market@place");
        assertThat(id.name()).isEqualTo("plugin");
        assertThat(id.marketplace()).isEqualTo("market");
    }

    @Test
    @DisplayName("parseChannelEntries: plugin:name@marketplace / server:name 剥 7 字符前缀（main.tsx:1650-1675）")
    void parseChannelEntries_stripsPrefixes() {
        List<ChannelAllowlist.ChannelEntry> entries = ChannelAllowlist.parseChannelEntries(
            List.of("plugin:slack@anthropic", "server:my-server"));
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).kind()).isEqualTo("plugin");
        assertThat(entries.get(0).name()).isEqualTo("slack");
        assertThat(entries.get(0).marketplace()).isEqualTo("anthropic");
        assertThat(entries.get(1).kind()).isEqualTo("server");
        assertThat(entries.get(1).name()).isEqualTo("my-server");
        assertThat(entries.get(1).marketplace()).isNull();
    }
}
