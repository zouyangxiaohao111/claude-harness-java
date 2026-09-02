package com.nexusai.application.agent.mcp.config;

import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * CC {@code claude mcp add} 校验链 a~g（McpConfigAddValidator · addMcpConfig config.ts:625-710）逐项验证。
 *
 * <p><b>WHY (意图验证)</b>: 校验链是「REST add 与 CC add 语义一致」的守卫。任何一条漏放
 * （名字非法 / 保留名 / enterprise 独占 / schema 非法 / 策略拒绝 / 重复）都会让 CC 拒绝的输入
 * 在 Java 侧落地，破坏对齐契约。测试逐条断言 <b>CC 逐字文案</b>（前端读 Problem.detail 展示）：
 * <ul>
 *   <li>名字非法 → ValidationException（400，CC config.ts:630-634 文案）</li>
 *   <li>保留名 → ConflictException（409，config.ts:637-648）</li>
 *   <li>enterprise 独占 → ConflictException（409，config.ts:651-655）</li>
 *   <li>schema 非法 → ValidationException（400，config.ts:658-665 + types.ts zod）</li>
 *   <li>denylist/allowlist → ConflictException（409，config.ts:668-679）</li>
 *   <li>按 scope 重复 → ConflictException（409，config.ts:681-710 + Java DB 守卫）</li>
 * </ul>
 */
class McpConfigAddValidatorTest {

    private static Map<String, Object> stdioConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "stdio");
        m.put("command", "python");
        return m;
    }

    private static Map<String, Object> httpConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "http");
        m.put("url", "https://mcp.example.com/mcp");
        return m;
    }

    // ── a. 名字字符类拒绝（config.ts:630-634） ──

    @Test
    @DisplayName("a. 名字含点/空格/中文 → 400 + CC 文案")
    void name_rejectsIllegalChars() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        for (String bad : new String[] {"my.server", "my server", "中文名", "a/b"}) {
            assertThatThrownBy(() -> v.validateName(bad))
                .as("非法名字 %s 必须拒绝", bad)
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid name " + bad + ". Names can only contain letters, numbers, hyphens, and underscores.");
        }
    }

    @Test
    @DisplayName("a. 合法名字（连字符/下划线/数字）通过")
    void name_allowsValidChars() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatCode(() -> v.validateName("my-server_01")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a. null 名拒绝")
    void name_rejectsNull() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.validateName(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("a. CC 正则无 64 上限（65 字符长名不在此层拒绝，上限是 DTO @Size）")
    void name_ccHasNoLengthCap_inValidatorLayer() {
        // WHY: 门禁修正 G1 —— CC config.ts:630 仅字符类拒绝（/[^a-zA-Z0-9_-]/），无 {1,64}。
        // 64 上限是 Java DTO @Size(max=64) 的既有约束，不应把「>64 拒绝」归因于 CC 正则。
        // 若未来把长度校验挪进本组件，此断言会红，提醒重新审视归因（规则九：验证意图）。
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        String longName = "a".repeat(65);
        assertThatCode(() -> v.validateName(longName)).doesNotThrowAnyException();
    }

    // ── b. 保留名拦截（config.ts:637-648，基于归一化名） ──

    @Test
    @DisplayName("b. nexusai-in-chrome 恒拦（不受 feature 门控影响）→ 409 + CC 文案")
    void reserved_nexusaiInChrome_alwaysRejected() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.validateReserved("nexusai-in-chrome", false))
            .isInstanceOf(ConflictException.class)
            .hasMessage("Cannot add MCP server \"nexusai-in-chrome\": this name is reserved.");
        // 即便 computerUse gate 开，nexusai-in-chrome 也恒拦
        assertThatThrownBy(() -> v.validateReserved("nexusai-in-chrome", true))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("b. computer-use 受 CHICAGO_MCP feature 门控（默认关放行，开则 409）")
    void reserved_computerUse_gatedByFeature() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatCode(() -> v.validateReserved("computer-use", false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> v.validateReserved("computer-use", true))
            .isInstanceOf(ConflictException.class)
            .hasMessage("Cannot add MCP server \"computer-use\": this name is reserved.");
    }

    @Test
    @DisplayName("b. 空格变体不命中（归一化 nexusai_in_chrome ≠ nexusai-in-chrome，E1 自验 CC 源码修正）")
    void reserved_whitespaceVariant_notHit() {
        // WHY: normalizeNameForMCP 把空格替换为 _（"nexusai in chrome"→"nexusai_in_chrome"），
        // 与保留名 "nexusai-in-chrome"（连字符）不同。计划 §2.3「空格也命中」为误判（E1 报告）。
        // 归一化语义若未来改成把空格折叠为连字符，此断言会红，提醒复核 CC 真源。
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatCode(() -> v.validateReserved("nexusai in chrome", false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("b. null 名不抛（空名由 validateName/DTO @NotBlank 兜底）")
    void reserved_nullName_noThrow() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatCode(() -> v.validateReserved(null, true)).doesNotThrowAnyException();
    }

    // ── c. enterprise 独占短路（config.ts:651-655） ──

    @Test
    @DisplayName("c. managed-mcp.json 存在 → 409 + CC 文案")
    void enterprise_exclusive_whenConfigExists() {
        McpEnterpriseConfig ent = Mockito.mock(McpEnterpriseConfig.class);
        when(ent.doesEnterpriseMcpConfigExist()).thenReturn(true);
        McpConfigAddValidator v = new McpConfigAddValidator(ent, null);
        assertThatThrownBy(v::validateEnterprise)
            .isInstanceOf(ConflictException.class)
            .hasMessage("Cannot add MCP server: enterprise MCP configuration is active and has exclusive control over MCP servers");
    }

    @Test
    @DisplayName("c. managed-mcp.json 缺失/解析失败 → 放行；组件未装配 → 放行")
    void enterprise_noBlock_whenMissingOrUnwired() {
        McpEnterpriseConfig ent = Mockito.mock(McpEnterpriseConfig.class);
        when(ent.doesEnterpriseMcpConfigExist()).thenReturn(false);
        McpConfigAddValidator v = new McpConfigAddValidator(ent, null);
        assertThatCode(v::validateEnterprise).doesNotThrowAnyException();
        // 未装配（null enterpriseConfig）→ 不短路不阻断
        assertThatCode(new McpConfigAddValidator(null, null)::validateEnterprise).doesNotThrowAnyException();
    }

    // ── d. schema 校验（config.ts:658-665 + types.ts zod） ──

    @Test
    @DisplayName("d. stdio 空 command → 400 Invalid configuration: command: Command cannot be empty")
    void schema_stdioEmptyCommand() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "stdio");
        cfg.put("command", "");
        assertThatThrownBy(() -> v.validateSchema(cfg))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Invalid configuration: command: Command cannot be empty");
    }

    @Test
    @DisplayName("d. stdio 缺 command 键 → 400 command: Required")
    void schema_stdioMissingCommandKey() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "stdio");
        assertThatThrownBy(() -> v.validateSchema(cfg))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Invalid configuration: command: Required");
    }

    @Test
    @DisplayName("d. 远程缺 url 键 → 400 url: Required（sse/http）")
    void schema_remoteMissingUrl() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "http");
        assertThatThrownBy(() -> v.validateSchema(cfg))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Invalid configuration: url: Required");
    }

    @Test
    @DisplayName("d. 非法 type → 400 Invalid configuration: type: Invalid enum value...")
    void schema_invalidType() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "teleport");
        cfg.put("command", "npx");
        assertThatThrownBy(() -> v.validateSchema(cfg))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Invalid configuration: type: Invalid enum value.");
    }

    @Test
    @DisplayName("d. callbackPort=-5 → 400 oauth.callbackPort: Number must be greater than 0")
    void schema_oauthCallbackPortNonPositive() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "http");
        cfg.put("url", "https://x.com/mcp");
        cfg.put("oauth", Map.of("callbackPort", -5));
        assertThatThrownBy(() -> v.validateSchema(cfg))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Invalid configuration: oauth.callbackPort: Number must be greater than 0");
    }

    @Test
    @DisplayName("d. callbackPort=3.5 → 400 oauth.callbackPort: Expected integer")
    void schema_oauthCallbackPortFraction() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "http");
        cfg.put("url", "https://x.com/mcp");
        cfg.put("oauth", Map.of("callbackPort", 3.5));
        assertThatThrownBy(() -> v.validateSchema(cfg))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Invalid configuration: oauth.callbackPort: Expected integer, received 3.5");
    }

    @Test
    @DisplayName("d. callbackPort 为字符串（非 Number）→ 400（Service 层 NaN 已前置丢弃，此处只见非数值类型）")
    void schema_oauthCallbackPortNonNumber() {
        // WHY: 门禁修正 1 —— CC 中 parseInt("abc")=NaN 被 falsy 短路丢弃、不进 schema。
        // Service 层（parseCallbackPort）负责丢弃；若字符串仍透传到校验层（绕过 Service 直构），
        // zod 语义为「非 number 拒绝」。本测试验证校验层的兜底，不验证 Service 丢弃（后者在
        // McpServerServiceAddFlowTest 覆盖）。
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "http");
        cfg.put("url", "https://x.com/mcp");
        cfg.put("oauth", Map.of("callbackPort", "abc"));
        assertThatThrownBy(() -> v.validateSchema(cfg))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Invalid configuration: oauth.callbackPort: Expected number, received \"abc\"");
    }

    @Test
    @DisplayName("d. authServerMetadataUrl 非 https → 400")
    void schema_oauthAuthUrlNotHttps() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "http");
        cfg.put("url", "https://x.com/mcp");
        cfg.put("oauth", Map.of("authServerMetadataUrl", "http://insecure.com"));
        assertThatThrownBy(() -> v.validateSchema(cfg))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("oauth.authServerMetadataUrl");
    }

    @Test
    @DisplayName("d. oauth.xaa 非布尔 → 400")
    void schema_oauthXaaNonBoolean() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "http");
        cfg.put("url", "https://x.com/mcp");
        cfg.put("oauth", Map.of("xaa", "yes"));
        assertThatThrownBy(() -> v.validateSchema(cfg))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Invalid configuration: oauth.xaa: Expected boolean, received \"yes\"");
    }

    @Test
    @DisplayName("d. 合法 stdio 返回归一化 config（args 缺省 []，未知键被 strip）")
    void schema_validStdio_normalizesAndStrips() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "stdio");
        cfg.put("command", "python");
        cfg.put("unknownKey", "shouldBeStripped");
        Map<String, Object> validated = v.validateSchema(cfg);
        assertThat(validated).containsEntry("type", "stdio").containsEntry("command", "python")
            .containsEntry("args", List.of())
            .doesNotContainKey("unknownKey");
    }

    // ── e/f. denylist / allowlist（config.ts:668-679） ──

    private static McpProperties propsWith(McpProperties.Policy policy) {
        return new McpProperties(false, false, false, null, null, null, null, null, null, policy, null);
    }

    @Test
    @DisplayName("e. denylist 命中 name → 409 explicitly blocked by enterprise policy")
    void policy_denyName_rejects() {
        McpProperties.Policy policy = new McpProperties.Policy(
            null, List.of(McpProperties.Entry.byName("evil")), false);
        McpConfigAddValidator v = new McpConfigAddValidator(null, propsWith(policy));
        Map<String, Object> cfg = stdioConfig();
        assertThatThrownBy(() -> v.validatePolicy("evil", cfg))
            .isInstanceOf(ConflictException.class)
            .hasMessage("Cannot add MCP server \"evil\": server is explicitly blocked by enterprise policy");
        // 未命中放行
        assertThatCode(() -> v.validatePolicy("good", cfg)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("e. denylist 命中 command 数组 / url wildcard → 拒绝")
    void policy_denyCommandAndUrl() {
        McpProperties.Policy byCmd = new McpProperties.Policy(null,
            List.of(McpProperties.Entry.byCommand(List.of("npx", "-y", "bad"))), false);
        McpConfigAddValidator vCmd = new McpConfigAddValidator(null, propsWith(byCmd));
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "stdio");
        cfg.put("command", "npx");
        cfg.put("args", List.of("-y", "bad"));
        assertThatThrownBy(() -> vCmd.validatePolicy("s", cfg)).isInstanceOf(ConflictException.class);

        McpProperties.Policy byUrl = new McpProperties.Policy(null,
            List.of(McpProperties.Entry.byUrl("https://*.evil.com/*")), false);
        McpConfigAddValidator vUrl = new McpConfigAddValidator(null, propsWith(byUrl));
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("type", "http");
        http.put("url", "https://api.evil.com/mcp");
        assertThatThrownBy(() -> vUrl.validatePolicy("s", http)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("f. 空 allowlist → 全拦 409 not allowed by enterprise policy")
    void policy_emptyAllowlist_rejectsAll() {
        McpProperties.Policy policy = new McpProperties.Policy(List.of(), null, false);
        McpConfigAddValidator v = new McpConfigAddValidator(null, propsWith(policy));
        assertThatThrownBy(() -> v.validatePolicy("anything", stdioConfig()))
            .isInstanceOf(ConflictException.class)
            .hasMessage("Cannot add MCP server \"anything\": not allowed by enterprise policy");
    }

    @Test
    @DisplayName("f. policy 未配置（null）→ 全放行")
    void policy_nullPolicy_allowsAll() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatCode(() -> v.validatePolicy("anything", stdioConfig())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("f. allowlist 未命中（name 不在表内）→ 409")
    void policy_allowNotMatching_rejects() {
        McpProperties.Policy policy = new McpProperties.Policy(
            List.of(McpProperties.Entry.byName("allowed")), null, false);
        McpConfigAddValidator v = new McpConfigAddValidator(null, propsWith(policy));
        assertThatThrownBy(() -> v.validatePolicy("other", stdioConfig()))
            .isInstanceOf(ConflictException.class);
        assertThatCode(() -> v.validatePolicy("allowed", stdioConfig())).doesNotThrowAnyException();
    }

    // ── g. 按 scope 重复检查（config.ts:681-710）+ DB 守卫 ──

    private static Map<String, Map<String, Object>> fileWith(String name) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        Map<String, Object> s = stdioConfig();
        m.put(name, s);
        return m;
    }

    @Test
    @DisplayName("g. project scope 文件已有同名 → 409 in .mcp.json")
    void duplicate_projectScope() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.checkDuplicate("dup", "project", fileWith("dup"), null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("MCP server dup already exists in .mcp.json");
    }

    @Test
    @DisplayName("g. user/local scope 文案区分（in user config / in local config）")
    void duplicate_userAndLocalScope() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.checkDuplicate("dup", "user", fileWith("dup"), null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("MCP server dup already exists in user config");
        assertThatThrownBy(() -> v.checkDuplicate("dup", "local", fileWith("dup"), null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("MCP server dup already exists in local config");
    }

    @Test
    @DisplayName("g. dynamic/enterprise/claudeai 不可写 → 409 逐字文案")
    void duplicate_nonWritableScopes() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.checkDuplicate("s", "dynamic", Map.of(), null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("Cannot add MCP server to scope: dynamic");
        assertThatThrownBy(() -> v.checkDuplicate("s", "enterprise", Map.of(), null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("Cannot add MCP server to scope: enterprise");
        assertThatThrownBy(() -> v.checkDuplicate("s", "claudeai", Map.of(), null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("Cannot add MCP server to scope: claudeai");
    }

    @Test
    @DisplayName("g. managed scope 写路径 default → 409（config.ts:758-760）")
    void duplicate_managedScope() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.checkDuplicate("s", "managed", Map.of(), null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("Cannot add MCP server to scope: managed");
    }

    @Test
    @DisplayName("g. 文件无同名 + DB 已有同名行 → 409（Java 特有守卫防 UNIQUE 500）")
    void duplicate_dbRow_rejectsInsteadOf500() {
        // WHY: DB name UNIQUE → 旧行为重复 create 落 DataIntegrityViolation 500。
        // 校验链 g 的 Java 守卫必须在写库前置 409（前端可消费 Problem.detail），
        // 否则「重复名」在 Web 契约上不可区分（500 vs 400/409）。
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        McpServerRecord row = new McpServerRecord();
        row.setId("mcp-x");
        row.setName("dup");
        assertThatThrownBy(() -> v.checkDuplicate("dup", "project", Map.of(), row))
            .isInstanceOf(ConflictException.class)
            .hasMessage("MCP server dup already exists in DB");
        // 无同名 → 放行
        assertThatCode(() -> v.checkDuplicate("fresh", "project", Map.of(), null))
            .doesNotThrowAnyException();
    }

    // ── scope / transport 解析（ensureConfigScope / ensureTransport） ──

    @Test
    @DisplayName("scope 缺省 project（REST 契约，AC-1.3 显式偏离 CC CLI 缺省 local）")
    void scope_defaultIsProject() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThat(v.ensureConfigScope(null)).isEqualTo("project");
        assertThat(v.ensureConfigScope("")).isEqualTo("project");
        assertThat(v.ensureConfigScope("local")).isEqualTo("local");
    }

    @Test
    @DisplayName("scope 非法（不在 7 值内）→ 400 + CC 文案")
    void scope_invalid_rejects() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.ensureConfigScope("bogus"))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Invalid scope: bogus. Must be one of: local, user, project, dynamic, enterprise, claudeai, managed");
    }

    @Test
    @DisplayName("transport 缺省 stdio；非法 → 400 + CC 文案")
    void transport_defaultAndInvalid() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThat(v.ensureTransport(null)).isEqualTo("stdio");
        assertThat(v.ensureTransport("sse")).isEqualTo("sse");
        assertThatThrownBy(() -> v.ensureTransport("ws"))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Invalid transport type: ws. Must be one of: stdio, sse, http");
    }

    // ── XAA add-time fail-fast（addCommand.ts:103-122） ──

    @Test
    @DisplayName("xaa: feature 关（CLAUDE_CODE_ENABLE_XAA 未设）→ 400 CLAUDE_CODE_ENABLE_XAA 文案")
    void xaa_featureOff_rejects() {
        // WHY: addCommand.ts:104-108 —— options.xaa && !isXaaEnabled() → cliError('Error: --xaa
        // requires CLAUDE_CODE_ENABLE_XAA=1 in your environment')。isXaaEnabled 读 env，生产默认
        // false → 即使 clientId/clientSecret 齐全，xaa=true 也必须被拒（Java 不接受 CC 拒绝的输入）。
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.validateXaaFailFast(true, false, true, true, true))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Error: --xaa requires CLAUDE_CODE_ENABLE_XAA=1 in your environment");
    }

    @Test
    @DisplayName("xaa: feature 开但缺 clientId → 400 --client-id")
    void xaa_missingClientId() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.validateXaaFailFast(true, true, false, true, true))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Error: --xaa requires: --client-id");
    }

    @Test
    @DisplayName("xaa: feature 开但缺 clientSecret → 400 --client-secret")
    void xaa_missingClientSecret() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.validateXaaFailFast(true, true, true, false, true))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Error: --xaa requires: --client-secret");
    }

    @Test
    @DisplayName("xaa: Java 无 settings.xaaIdp → 恒拒绝 xaa setup（受控残留）")
    void xaa_missingXaaIdpSettings() {
        // WHY: getXaaIdpSettings()（settings.xaaIdp）——Java 无此设置基础设施（Q-07 已删
        // Xaa/XaaIdpLogin）→ xaaIdpConfigured 恒 false → feature 开且 clientId/clientSecret 齐全
        // 仍被拒（文案逐字对齐 addCommand.ts:115-117）。若未来接入 XAA IdP 需恢复此配置检查。
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatThrownBy(() -> v.validateXaaFailFast(true, true, true, true, false))
            .isInstanceOf(ValidationException.class)
            .hasMessage("Error: --xaa requires: 'claude mcp xaa setup' (settings.xaaIdp not configured)");
    }

    @Test
    @DisplayName("xaa: feature 开 + clientId/clientSecret/xaaIdp 全齐 → 放行")
    void xaa_allPresent_passes() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatCode(() -> v.validateXaaFailFast(true, true, true, true, true))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("xaa: null/false → no-op（不误伤非 xaa 服务器）")
    void xaa_nullOrFalse_noop() {
        McpConfigAddValidator v = new McpConfigAddValidator(null, null);
        assertThatCode(() -> v.validateXaaFailFast(null, false, false, false, false))
            .doesNotThrowAnyException();
        assertThatCode(() -> v.validateXaaFailFast(false, false, false, false, false))
            .doesNotThrowAnyException();
    }
}
