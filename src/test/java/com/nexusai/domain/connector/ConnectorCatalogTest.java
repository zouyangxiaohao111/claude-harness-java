package com.nexusai.domain.connector;

import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.connector.ConnectorDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内置连接器 catalog（[2026-11-23] 官方 MCP 批次：32 条 = Octop 30 + 高德 + 腾讯位置）。
 *
 * <p>锁死：supported 18（oauth2 6 / bearer 4 / header 2 / query 6）、unavailable 14、
 * official 24、无 credentialOnly；hidden 仍为 qcc/tencent-ardot。
 */
class ConnectorCatalogTest {

    private final ConnectorCatalog catalog = new ConnectorCatalog();

    @Test
    @DisplayName("目录 = 32 条，key 唯一；visible 30（qcc/tencent-ardot hidden）")
    void entriesAndVisibility() {
        assertThat(catalog.all()).hasSize(32);
        assertThat(catalog.all().stream().map(ConnectorDefinition::key).distinct().count())
                .isEqualTo(32);
        assertThat(catalog.visible()).hasSize(30);
        assertThat(catalog.require("qcc").hiddenByDefault()).isTrue();
        assertThat(catalog.require("tencent-ardot").hiddenByDefault()).isTrue();
    }

    @Test
    @DisplayName("分档：supported 18 / unavailable 14 / credentialOnly 0；official 24")
    void tierAndOfficialCounts() {
        Map<String, Long> tiers = catalog.all().stream()
                .collect(Collectors.groupingBy(ConnectorDefinition::supportTier, Collectors.counting()));
        assertThat(tiers).containsEntry("supported", 18L).containsEntry("unavailable", 14L);
        assertThat(tiers.getOrDefault("credentialOnly", 0L)).isZero();

        Map<String, Long> modes = catalog.all().stream()
                .collect(Collectors.groupingBy(ConnectorDefinition::authMode, Collectors.counting()));
        assertThat(modes).containsEntry("oauth2", 6L).containsEntry("bearer", 4L)
                .containsEntry("header", 2L).containsEntry("query", 6L)
                .containsEntry("unsupported", 14L);

        assertThat(catalog.all().stream().filter(ConnectorDefinition::official).count())
                .isEqualTo(24);
        assertThat(catalog.all()).allMatch(d -> d.icon() != null && !d.icon().isBlank());
        assertThat(catalog.all()).allMatch(d -> d.color() != null && !d.color().isBlank());
        assertThat(catalog.all()).allMatch(d -> "available".equals(d.phase()));
    }

    @Test
    @DisplayName("supported 清单含官方 MCP：腾讯文档/微云/IMA/元典/飞书/企微/乐享/百度/滴滴/美团/有道/高德/腾讯位置")
    void supportedKeys() {
        List<String> supported = catalog.all().stream()
                .filter(ConnectorDefinition::supported).map(ConnectorDefinition::key).toList();
        assertThat(supported).contains(
                "notion", "openalex", "dida365", "qcc", "tencent-ardot", "tencent-weiyun",
                "tencent-docs", "tencent-ima", "yuandian", "meituan-travel",
                "feishu-cli", "youdao-note",
                "wecom-cli", "tencent-lexiang", "baidu-map", "didi",
                "amap", "tencent-location");
        assertThat(supported).hasSize(18);
    }

    @Test
    @DisplayName("authMode 端点/模板：query 带占位符，header 有头映射，oauth2 有 issuer")
    void endpointMetadata() {
        ConnectorDefinition baidu = catalog.require("baidu-map");
        assertThat(baidu.authMode()).isEqualTo("query");
        assertThat(baidu.endpointTemplate()).contains("ak={ak}");
        assertThat(baidu.tokenField()).isEqualTo("ak");

        ConnectorDefinition feishu = catalog.require("feishu-cli");
        assertThat(feishu.authMode()).isEqualTo("header");
        assertThat(feishu.mcpHeaders()).containsKeys("X-Lark-MCP-UAT", "X-Lark-MCP-Allowed-Tools");

        ConnectorDefinition weiyun = catalog.require("tencent-weiyun");
        assertThat(weiyun.isOAuth()).isTrue();
        assertThat(weiyun.authMode()).isEqualTo("oauth2");
        assertThat(weiyun.mcpUrl()).isEqualTo("https://www.weiyun.com/api/v3/mcpserver");

        assertThat(catalog.require("youdao-note").mcpTransport()).isEqualTo("sse");
    }

    @Test
    @DisplayName("unavailable 每条有中文原因（含会议路径未公开 / 钉钉非标准 OAuth / 8 条无官方 MCP）")
    void unavailableHasReason() {
        List<ConnectorDefinition> unavailable = catalog.all().stream()
                .filter(ConnectorDefinition::unavailable).toList();
        assertThat(unavailable).hasSize(14);
        assertThat(unavailable).allMatch(d -> d.unavailableReason() != null
                && !d.unavailableReason().isBlank());
        assertThat(catalog.require("tencent-meeting").unavailableMessage()).contains("路径未公开");
        assertThat(catalog.require("dingtalk-cli").unavailableMessage()).contains("非标准");
        assertThat(catalog.require("tencent-news").official()).isFalse();
        assertThat(catalog.require("ragflow").official()).isTrue();
    }

    @Test
    @DisplayName("credentialFields：Octop 5 条 + 官方 MCP 表单字段（共 17 条）")
    void credentialFields() {
        assertThat(catalog.all().stream().filter(d -> !d.credentialFields().isEmpty()).count())
                .isEqualTo(17);
        assertThat(catalog.require("dingtalk-cli").credentialFields()).hasSize(2);
        assertThat(catalog.require("ragflow").credentialFields()).hasSize(3);
        assertThat(catalog.require("dify").credentialFields()).hasSize(1);
        assertThat(catalog.require("feishu-cli").credentialFields()).hasSize(2);
        assertThat(catalog.require("tencent-lexiang").credentialFields()).hasSize(2);
        assertThat(catalog.require("baidu-map").credentialFields()).hasSize(1);
        assertThat(catalog.require("amap").credentialFields()).extracting("key").containsExactly("key");
    }

    @Test
    @DisplayName("未知 key → 404（NotFoundException），find 返回空")
    void unknownKey() {
        assertThat(catalog.find("nope")).isEmpty();
        assertThatThrownBy(() -> catalog.require("nope"))
                .isInstanceOf(NotFoundException.class);
    }
}
