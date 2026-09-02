package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-10 纯函数单测（IMP-03 返工补测）· 对齐 CC {@code skills/bundled/scheduleRemoteAgents.ts}。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图，而非仅验证行为）:
 * <ol>
 *   <li><b>taggedIdToUUID 是 connector 枚举链的底座</b>——CC getConnectedClaudeAIConnectors
 *       （scheduleRemoteAgents.ts:90-104）对每个 claudeai-proxy client 的 {@code client.config.id}
 *       做 base58→UUID 解码，解码失败（非 mcpsrv_ 前缀 / 非法 base58 字符）则该 connector 被静默跳过。
 *       若解码逻辑漂移（漏 version 跳过、UUID 分节错位、非法字符返回非 null），连接器列表会缺项或
 *       出现畸形 uuid，直接进入 /schedule prompt 供 LLM 引用 —— 错误静默、无日志。测试锁定 CC :35-57
 *       的解码语义。</li>
 *   <li><b>sanitizeConnectorName 是 mcp_connections.name 校验约束</b>——CC :89-95 把 connector name
 *       净化为 {@code [a-zA-Z0-9_-]}（dots/spaces 不允许，prompt :413 明文警告）。若净化规则漂移
 *       （claude.ai 前缀不剥离 / 非法字符不归一 / 连续 - 不折叠 / 首尾 - 不除），LLM 生成的名字会
 *       被 MCP 服务端拒绝。</li>
 *   <li><b>formatConnectorsInfo / formatEnvironmentsInfo 是 prompt 渲染契约</b>——非空行必须含
 *       {@code name: {safeName}}（△-8 关闭）；空环境不得有 Java 旧 fallback 文案（DEL-04，CC 恒非空
 *       才调用，dead branch）。</li>
 * </ol>
 */
class ScheduleRemoteAgentsPureFunctionsTest {

    // ────────────────────────────────────────────────────────────────
    // taggedIdToUUID（CC scheduleRemoteAgents.ts:35-57）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("taggedIdToUUID：mcpsrv_ 前缀 + version(01) 跳过 + base58 解码 → UUID 分节格式")
    void taggedIdToUUID_decodesValidTaggedId() {
        // CC :37 taggedId 格式 mcpsrv_01{base58(uuid.int)}——01 是 version 前缀（2 字符，恒跳过）。
        // 本向量经 CC 真源（node 复跑 scheduleRemoteAgents.ts:35-57 同算法）验证：
        //   base58("550e8400-e29b-41d4-a716-446655440000".int) = "BWBeN28Vb7cMEx7Ym8AUzs"
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID(
                "mcpsrv_01BWBeN28Vb7cMEx7Ym8AUzs"))
            .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    @DisplayName("taggedIdToUUID：单字符 base58 解码 + 32 位 hex 左补零（0 / 9 / 57）")
    void taggedIdToUUID_singleCharDecodesWithLeftPadding() {
        // '2' 在 BASE58 下标 1 → n=1 → 32 位 hex 左补零末位 1（CC :53-54 padStart(32,'0')）
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID("mcpsrv_012"))
            .isEqualTo("00000000-0000-0000-0000-000000000001");
        // '1A' → '1'(0)*58 + 'A'(9) = 9（version 前缀 01 之后的数据段 "1A"）
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID("mcpsrv_011A"))
            .isEqualTo("00000000-0000-0000-0000-000000000009");
        // 'z' 是 BASE58 末位（下标 57）→ n=57 → hex 0x39
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID("mcpsrv_01z"))
            .isEqualTo("00000000-0000-0000-0000-000000000039");
    }

    @Test
    @DisplayName("taggedIdToUUID：非 mcpsrv_ 前缀 → null（CC :38 早返）")
    void taggedIdToUUID_nonMcpsrvPrefixReturnsNull() {
        // CC :37-38 if (!taggedId.startsWith(prefix)) return null
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID("xxx01BWBeN28Vb7cMEx7Ym8AUzs"))
            .isNull();
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID(""))
            .isNull();
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID(null))
            .isNull();
    }

    @Test
    @DisplayName("taggedIdToUUID：非法 base58 字符 → null（'0'/'O'/'I'/'l' 不在字母表）")
    void taggedIdToUUID_illegalBase58CharReturnsNull() {
        // CC :47-50 BASE58.indexOf(c) === -1 → return null；'0' 零不在 Bitcoin 风格字母表
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID("mcpsrv_010"))
            .isNull();
        // 'O' 大写字母不在字母表（CC BASE58 :24 只有小写 o）
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID("mcpsrv_01O"))
            .isNull();
        // 'I' 大写不在字母表
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID("mcpsrv_01I"))
            .isNull();
        // 'l' 小写不在字母表（BASE58 剔除视觉歧义字符）
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID("mcpsrv_01l"))
            .isNull();
    }

    @Test
    @DisplayName("taggedIdToUUID：version 前缀缺失/超短 → 空数据段解码为全零 UUID（CC rest.slice(2) 同）")
    void taggedIdToUUID_shortOrMissingVersionSkipsToEmptyData() {
        // rest="01" → slice(2)=""（Java rest.length()>2 ? substring(2) : "" 等价）→ n=0 → 全零 UUID
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID("mcpsrv_01"))
            .isEqualTo("00000000-0000-0000-0000-000000000000");
        // rest="" → 空数据段同样全零
        assertThat(ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID("mcpsrv_"))
            .isEqualTo("00000000-0000-0000-0000-000000000000");
    }

    @Test
    @DisplayName("taggedIdToUUID：输出恒为 8-4-4-4-12 小写 hex UUID 分节格式")
    void taggedIdToUUID_outputIsSectionedUuid() {
        // UUID 分节格式是下游 contract（CC :55-57 模板串），任何分节/大小写漂移必红
        String uuid = ScheduleRemoteAgentsSkillRegistrar.taggedIdToUUID(
            "mcpsrv_01BWBeN28Vb7cMEx7Ym8AUzs");
        assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(uuid.split("-")).hasSize(5);
        assertThat(uuid).isLowerCase();
    }

    // ────────────────────────────────────────────────────────────────
    // sanitizeConnectorName（CC scheduleRemoteAgents.ts:89-95）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sanitizeConnectorName：剥离 claude.ai 前缀（大小写不敏感 + 分隔符 . / 空格 / -）")
    void sanitizeConnectorName_stripsClaudeAiPrefix() {
        // CC :90 /^claude[.\s-]ai[.\s-]/i 前缀剥离
        assertThat(ScheduleRemoteAgentsSkillRegistrar.sanitizeConnectorName("claude.ai my-server"))
            .isEqualTo("my-server");
        assertThat(ScheduleRemoteAgentsSkillRegistrar.sanitizeConnectorName("Claude.AI-Foo"))
            .isEqualTo("Foo");
        assertThat(ScheduleRemoteAgentsSkillRegistrar.sanitizeConnectorName("claude-ai bar"))
            .isEqualTo("bar");
        // 无尾部分隔符（"claude.ai" 后直接结束）→ 前缀不匹配 → 落非 alnum→'-' 归一
        assertThat(ScheduleRemoteAgentsSkillRegistrar.sanitizeConnectorName("claude.ai"))
            .isEqualTo("claude-ai");
    }

    @Test
    @DisplayName("sanitizeConnectorName：非 [a-zA-Z0-9_-] → '-' + 连续 '-' 折叠 + 去首尾 '-'")
    void sanitizeConnectorName_normalizesIllegalChars() {
        // CC :91-94：非法字符归一 → 折叠 → 去首尾
        assertThat(ScheduleRemoteAgentsSkillRegistrar.sanitizeConnectorName("foo.bar baz"))
            .isEqualTo("foo-bar-baz");
        assertThat(ScheduleRemoteAgentsSkillRegistrar.sanitizeConnectorName("My Connector Name"))
            .isEqualTo("My-Connector-Name");
        assertThat(ScheduleRemoteAgentsSkillRegistrar.sanitizeConnectorName("a--b"))
            .as("CC :93 -+→- 连续折叠").isEqualTo("a-b");
        assertThat(ScheduleRemoteAgentsSkillRegistrar.sanitizeConnectorName("-foo-"))
            .as("CC :94 去首尾 -").isEqualTo("foo");
        // 合法字符原样保留（下划线 + 数字不在归一范围）
        assertThat(ScheduleRemoteAgentsSkillRegistrar.sanitizeConnectorName("connector_1"))
            .isEqualTo("connector_1");
    }

    @Test
    @DisplayName("sanitizeConnectorName：非 claude.ai 前缀名（如 claudeai 无分隔符）不被误剥离")
    void sanitizeConnectorName_doesNotStripWithoutSeparator() {
        // CC :90 正则要求 claude 与 ai 之间必须是 [.\s-] 之一；"claudeai" 无分隔符 → 不匹配 → 原样
        assertThat(ScheduleRemoteAgentsSkillRegistrar.sanitizeConnectorName("claudeai"))
            .isEqualTo("claudeai");
    }

    // ────────────────────────────────────────────────────────────────
    // formatConnectorsInfo（CC scheduleRemoteAgents.ts:97-109）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("formatConnectorsInfo：空连接器 → 引导文案（settings/connectors 链接）")
    void formatConnectorsInfo_emptyReturnsGuideCopy() {
        // CC :98-100 空数组引导文案逐字对齐
        assertThat(ScheduleRemoteAgentsSkillRegistrar.formatConnectorsInfo(List.of()))
            .isEqualTo("No connected MCP connectors found. The user may need to connect servers at "
                + "https://claude.ai/settings/connectors");
    }

    @Test
    @DisplayName("formatConnectorsInfo：非空行含 name:{safeName}（净化名，△-8 关闭）+ connector_uuid/url")
    void formatConnectorsInfo_nonEmptyLinesCarrySafeName() {
        // CC :101-108：每行 `- {name} (connector_uuid: {uuid}, name: {safeName}, url: {url})`
        String info = ScheduleRemoteAgentsSkillRegistrar.formatConnectorsInfo(List.of(
            new ScheduleRemoteAgentsSkillRegistrar.ConnectorInfo("u1", "claude.ai Slack", "https://s"),
            new ScheduleRemoteAgentsSkillRegistrar.ConnectorInfo("u2", "Datadog API", "https://d")));

        assertThat(info).startsWith("Connected connectors (available for triggers):");
        // name: {safeName} 必须使用净化名（claude.ai 前缀剥离 → Slack，保留原大小写）
        assertThat(info)
            .as("△-8：非空行 name: 字段必须为 sanitizeConnectorName 产物")
            .contains("- claude.ai Slack (connector_uuid: u1, name: Slack, url: https://s)")
            .contains("- Datadog API (connector_uuid: u2, name: Datadog-API, url: https://d)");
    }

    // ────────────────────────────────────────────────────────────────
    // formatEnvironmentsInfo（CC scheduleRemoteAgents.ts:427-433 / DEL-04）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("formatEnvironmentsInfo：恒 'Available environments:' 头 + env 行（id/kind 来自访问器）")
    void formatEnvironmentsInfo_constantHeaderAndEnvLines() {
        // CC :427-429 environmentsInfo 内联拼装：['Available environments:'] + 每行
        // `- {name} (id: {environment_id}, kind: {kind})`；environmentId() 为 record 访问器
        String info = ScheduleRemoteAgentsSkillRegistrar.formatEnvironmentsInfo(List.of(
            new ScheduleRemoteAgentsSkillRegistrar.EnvironmentResource("prod", "env-1", "cloud"),
            new ScheduleRemoteAgentsSkillRegistrar.EnvironmentResource("staging", "env-2", "cloud")));

        assertThat(info)
            .startsWith("Available environments:")
            .contains("\n- prod (id: env-1, kind: cloud)")
            .contains("\n- staging (id: env-2, kind: cloud)");
    }

    @Test
    @DisplayName("formatEnvironmentsInfo：空列表 → 仅头无 env 行，且无 Java 旧 fallback 文案（DEL-04 验证）")
    void formatEnvironmentsInfo_emptyList_noFallbackCopy() {
        // CC :427 空数组仅剩 ['Available environments:'] 头（无任何 fallback 行）；
        // Java 旧「(none yet — a default will be created automatically)」dead branch 已删（P3-9 03-1）
        String info = ScheduleRemoteAgentsSkillRegistrar.formatEnvironmentsInfo(List.of());

        assertThat(info).isEqualTo("Available environments:");
        assertThat(info)
            .as("DEL-04：CC 无空环境 fallback 文案（envs 恒非空才调用，createDefault 兜底）")
            .doesNotContain("none yet")
            .doesNotContain("a default will be created");
    }
}
