package com.nexusai.apis.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.NexusAiApplication;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.model.provider.dto.ProviderCreateRequest;
import com.nexusai.model.provider.dto.ProviderDto;
import com.nexusai.model.provider.dto.ProviderType;
import com.nexusai.model.provider.dto.ProviderUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [provider-custom-headers] {@code extra_headers} 的<b>清空契约</b> · 真实 SQLite。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：本批补上了前端 KvEditor 与两个请求构造器之后，
 * 「用户配了 header」终于可用；但<b>「用户把 header 全删掉」当时是静默 no-op</b>：
 * <ol>
 *   <li>{@code serializeHeaders({})} 返回 Java {@code null}（其 javadoc 明言「这与存 {} 不可互换」）；</li>
 *   <li>MyBatis-Flex {@code update(entity)} 默认 {@code $$ignoreNulls=true} 会跳过 null 字段。</li>
 * </ol>
 * 两条叠加 ⇒ 列里的旧值原地保留，而用户看到的是「已经删干净了」。这个 bug 不会报错、不会打日志，
 * 只能靠<b>读回真实列</b>才能发现，故本类必须在真实库上取证（内存 Map 层的单测证明不了列有没有被写）。
 *
 * <p>契约（三态，不可互换）：
 * <table>
 *   <tr><th>请求体里的 extraHeaders</th><th>语义</th></tr>
 *   <tr><td>缺字段 / JSON {@code null}</td><td>不触碰（PATCH null-skip，现状语义）</td></tr>
 *   <tr><td>显式 {@code {}}</td><td><b>清空</b> → 写 SQL NULL</td></tr>
 *   <tr><td>非空 map</td><td>设为该值</td></tr>
 * </table>
 *
 * <p><b>实现手法及其风险</b>：清空分支改用本仓既有的 {@code mapper.update(entity, false)}
 * （{@code ignoreNulls=false} 显式写 NULL，范式见 {@code ProjectSessionBindingService.unbind} /
 * {@code EffortCommand} / {@code TodoWriteTool}）。这条 API 的代价是<b>该实体的所有 null 字段都会被写 NULL</b>，
 * 而 {@code p} 由 {@code r.toDomain()} 而来（DB 全字段回读）+ {@code ProviderRecord} 的 11 个字段在
 * {@code toDomain}/{@code fromDomain} 上逐一对称 —— 故理论上只会把「本来就是 NULL」的列再写一次 NULL。
 * {@link #clearExtraHeaders_doesNotClobberOtherColumns()} 就是为这条推理设的守卫：它是本类里
 * <b>唯一能证伪「误伤其他列」的用例</b>，比「清空生效」本身更值得存在。
 *
 * <p><b>变异自证（预期）</b>：把 {@code ProviderService.update} 的清空分支改回无条件
 * {@code providerMapper.update(rec)}（即退回 ignoreNulls=true）⇒
 * {@link #explicitEmptyMap_clearsColumnToNull()} 与
 * {@link #clearThenSetAgain_restoresNormalWrite()} 变红（列里仍是旧值）。
 * 把清空分支的条件从 {@code isEmpty()} 改成「只要非 null 就清空」⇒
 * {@link #nonEmptyMap_setsNewValue()} 变红。
 *
 * <p><b>与 {@code ProviderHeaderRoundTripIntegrationTest} 的分工</b>：那个类盯 create + 读链
 * （毒字符/占位符/400），本类只盯 update 的三态与「不误伤」，两边不重复造同一条断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = NexusAiApplication.class)
@DisplayName("[provider-headers] extra_headers 清空契约（{} = 清空 / null = 不触碰）· 真实 SQLite")
class ProviderHeaderClearContractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // CleanupMode.NEVER：SQLite 文件（.db/-shm/-wal）在测试结束时仍被 Hikari 锁定，JUnit 默认清理
    // 会因「文件被占用」抛 IOException（Windows 实测，同 SessionIdPlaceholder 那批集成测试）。
    @TempDir(cleanup = CleanupMode.NEVER)
    static Path tempDir;

    @DynamicPropertySource
    static void freshDb(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", () -> "jdbc:sqlite:"
            + tempDir.resolve("provider-headers-clear.db").toAbsolutePath()
            + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=5000");
    }

    @Autowired private ProviderService providerService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanProviderTables() {
        jdbcTemplate.update("DELETE FROM models");
        jdbcTemplate.update("DELETE FROM providers");
    }

    private static String uniqueName() {
        return "prov-clr-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 直接读列原文（不经任何应用层代码）—— 「有没有被写」的唯一可信证据。 */
    private String rawExtraHeaders(String providerId) {
        return jdbcTemplate.queryForObject(
            "SELECT extra_headers FROM providers WHERE id = ?", String.class, providerId);
    }

    private static Map<String, String> parse(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new AssertionError("列里的 extra_headers 不是合法 JSON：" + json, e);
        }
    }

    private ProviderDto createWithHeaders(Map<String, String> headers) {
        return providerService.create(new ProviderCreateRequest(
            uniqueName(), ProviderType.openai_compatible,
            "https://gw.example.com/v1", "sk-live-clear-test", headers, Boolean.TRUE));
    }

    /** 只改 extraHeaders，其余字段一律传 null（= 不覆盖）。 */
    private void updateHeaders(String id, Map<String, String> extraHeaders) {
        providerService.update(id, new ProviderUpdateRequest(
            null, null, null, null, extraHeaders, null));
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 三态契约
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("update 传 {} → 列被写为 SQL NULL（清空可表达，不再是静默 no-op）")
    void explicitEmptyMap_clearsColumnToNull() {
        ProviderDto p = createWithHeaders(Map.of("x-tenant", "acme"));
        assertNotNull(rawExtraHeaders(p.id()), "前置假设失败：create 后该列本应有值");

        updateHeaders(p.id(), Map.of());

        assertNull(rawExtraHeaders(p.id()),
            "显式 {} 必须把列写成 SQL NULL。若这里仍是旧 JSON，说明清空是**静默 no-op**"
            + "（serializeHeaders({}) 返回 null + MyBatis-Flex 默认 ignoreNulls 跳过 null 字段）");
        assertNull(providerService.getById(p.id()).extraHeaders(),
            "DTO 读回也应为 null（列已 NULL ⇒ deserializeHeaders(null) → null）");
    }

    @Test
    @DisplayName("update 传 null（字段缺失）→ 原值保留（PATCH null-skip 语义不变）")
    void nullExtraHeaders_preservesExistingValue() {
        Map<String, String> headers = Map.of("x-tenant", "acme");
        ProviderDto p = createWithHeaders(headers);

        updateHeaders(p.id(), null);

        String raw = rawExtraHeaders(p.id());
        assertNotNull(raw, "null/缺失 = 不触碰，列里的原值必须保留（这是 PATCH 的既有语义，不得被清空契约改写）");
        assertEquals(headers, parse(raw), "保留的必须是原值本身，不是空对象");
        assertEquals(headers, providerService.getById(p.id()).extraHeaders());
    }

    @Test
    @DisplayName("update 传非空 map → 设为新值（清空分支不得吃掉正常写入）")
    void nonEmptyMap_setsNewValue() {
        ProviderDto p = createWithHeaders(Map.of("x-tenant", "acme"));

        Map<String, String> next = new LinkedHashMap<>();
        next.put("x-tenant", "acme-2");
        next.put("x-route", "gw-a");
        updateHeaders(p.id(), next);

        String raw = rawExtraHeaders(p.id());
        assertNotNull(raw);
        assertEquals(next, parse(raw), "非空 map 必须被写为新的 JSON（含新增键）");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 清空不是单行道 + 清空不误伤其他列（ignoreNulls=false 的安全性）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("清空后可以再写回非空（清空不是单行道）")
    void clearThenSetAgain_restoresNormalWrite() {
        ProviderDto p = createWithHeaders(Map.of("x-tenant", "acme"));
        updateHeaders(p.id(), Map.of());
        assertNull(rawExtraHeaders(p.id()), "前置：清空应生效");

        updateHeaders(p.id(), Map.of("x-tenant", "back"));

        String raw = rawExtraHeaders(p.id());
        assertNotNull(raw, "清空之后必须还能写回（否则 update 的一次 null 参与会永久破坏后续写入）");
        assertEquals(Map.of("x-tenant", "back"), parse(raw));
    }

    @Test
    @DisplayName("清空 extra_headers 不得误伤其他列（name/baseUrl/enabled/apiKey 掩码全须保留）")
    void clearExtraHeaders_doesNotClobberOtherColumns() {
        // 这条是本类最重要的守卫：清空分支用 update(entity, false)，它会写 NULL 到该实体的**所有**
        // null 字段。安全的前提是 rec 由 DB 全字段回读而来（toDomain/fromDomain 11 字段对称）。
        // 一旦将来有人在 update 路径上「顺手」构造了一个字段不全的 ProviderRecord，
        // 这条会立刻变红 —— 而不是等用户发现 baseUrl 被清空。
        ProviderDto p = createWithHeaders(Map.of("x-tenant", "acme"));
        // 先把几个字段改成非默认值，确保它们"有值可丢"
        providerService.update(p.id(), new ProviderUpdateRequest(
            p.name(), null, "https://gw2.example.com/v1", null, null, Boolean.FALSE));
        ProviderDto before = providerService.getById(p.id());
        assertFalse(before.enabled(), "前置：enabled 已置为 false");
        assertEquals("https://gw2.example.com/v1", before.baseUrl(), "前置：baseUrl 已改");

        updateHeaders(p.id(), Map.of());   // ← 清空

        assertNull(rawExtraHeaders(p.id()), "前置：本次清空应生效");
        ProviderDto after = providerService.getById(p.id());
        assertEquals("https://gw2.example.com/v1", after.baseUrl(),
            "ignoreNulls=false 不得误伤 base_url（值为 null 的列会被写 NULL，故 rec 必须字段完整）");
        assertFalse(after.enabled(), "enabled=false 不得被误写为 NULL 或 true");
        assertEquals(before.apiKeyMasked(), after.apiKeyMasked(),
            "apiKey 本次未传 ⇒ 掩码与原密文必须原样保留（不得被清空分支写成 NULL）");
        assertEquals(before.name(), after.name(), "name 必须保留");
        assertTrue(after.name().startsWith("prov-clr-"), "name 不该被改写：实际=" + after.name());
    }
}
