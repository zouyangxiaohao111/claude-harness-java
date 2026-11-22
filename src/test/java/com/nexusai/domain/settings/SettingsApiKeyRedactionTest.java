package com.nexusai.domain.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.util.ApiKeyHasher;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.model.settings.dto.SettingsResponse;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [保密 · 用户 2026-11-22「明文 apiKey 不能」] settings.apiKey 的读/写脱敏契约。
 *
 * <p><b>WHY</b>：{@code GET /api/v1/settings} 曾把 DB 里的 apiKey 明文原样下发。修法有两条腿，
 * 缺一条就会退化成「掩码覆盖真值」的更难查的故障：
 * <ol>
 *   <li><b>读侧</b>：响应只给 {@code ****abcd}（只留末四位，⛔ 不留 {@code sk-} 前缀）；未配置 → null。</li>
 *   <li><b>写侧哨兵</b>：前端把掩码原样回传时必须视为「未修改」，否则库里的真 key 会被
 *       {@code ****abcd} 覆盖（WebSearch 之后一律 401）。</li>
 * </ol>
 *
 * <p><b>不测</b>：连接测试 / 实际抓取。它们读的是 {@code SettingsRecord}
 * （{@code WebSearchTool.readSettings() → settingsMapper.selectOneById(1)}），不经本 DTO ⇒
 * 脱敏在结构上碰不到它们（见 WebSearchTool.java:347/1063）。
 *
 * <p>本类为纯 POJO 单测（mock mapper），⛔ 不启动 Spring、不碰真库。
 */
class SettingsApiKeyRedactionTest {

    private static final int SINGLETON_ID = 1;

    /** 与真实 key 同形：以 sk- 开头，便于断言「响应体里连前缀都不出现」。 */
    private static final String PLAIN_KEY = "sk-live-abcdefgh1234";

    private static final ObjectMapper JSON = new ObjectMapper();

    private SettingsService newService(SettingsMapper mapper, SettingsRecord dbRow) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(dbRow);
        return service;
    }

    private static SettingsRecord rowWithKey(String apiKey) {
        SettingsRecord s = new SettingsRecord();
        s.setAutoMemoryEnabled(true);   // 避免 toDto 回落 settings.json 文件读
        s.setApiKey(apiKey);
        return s;
    }

    /** 只带 apiKey 的请求（69 参兼容构造器；apiKey 是第 20 个参数）。 */
    private static SettingsDto apiKeyPatch(String apiKey) {
        return new SettingsDto(
            null, null, null, null,                                          //  1- 4 theme..animationsEnabled
            null, null, null, null, null, null, null, null, null, null,      //  5-14 十个模型档位
            null, null, null, null,                                          // 15-18 压缩窗口/记忆三项
            null, apiKey, null, null, null, null,                            // 19-24 engine/apiKey/proxy/useSmall/baseUrl/domainCheck
            null, null, null,                                                 // 25-27 swarms/permissionMode/classifierModel
            null, null, null, null, null, null, null, null, null, null, null, null, // 28-39 压缩 12 列
            null, null, null, null, null, null, null, null, null, null, null, // 40-50 压缩数值 11 列
            null,                                                             // 51 snipNudgeThreshold
            null, null, null, null, null, null, null, null, null,             // 52-60 提示词对齐 9 列
            null, null,                                                       // 61-62 language/outputStyle
            null,                                                             // 63 enabledPlugins
            null, null, null, null, null, null                                // 64-69 pluginClaudeFallback..channelsEnabled
        );
    }

    @Test
    @DisplayName("GET：响应只回末四位掩码，明文与 sk- 前缀都不出站")
    void get_returnsTailMaskOnly() throws Exception {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = rowWithKey(PLAIN_KEY);
        SettingsService service = newService(mapper, row);

        SettingsDto dto = service.get();

        assertThat(dto.apiKey()).isEqualTo("****1234");
        // 脱敏只作用于出站 DTO：服务端内部读链（WebSearchTool 直读 SettingsRecord）仍拿明文，
        // 所以「连接测试 / 实际抓取」这种必须用真 key 的场景不受影响。
        assertThat(row.getApiKey()).as("DB 记录保持明文（服务端内部使用）").isEqualTo(PLAIN_KEY);
        assertThat(dto.apiKey()).isNotEqualTo(PLAIN_KEY);

        String json = JSON.writeValueAsString(
            new SettingsResponse(dto, "nexusai", "C:/tmp/config"));
        assertThat(json).as("明文 key 不得出现在响应 JSON 里").doesNotContain(PLAIN_KEY);
        assertThat(json).as("验收线：响应体里连 sk- 前缀都不允许出现").doesNotContain("sk-");
    }

    @Test
    @DisplayName("服务端内部读链：readDbApiKeyPlain 给明文，GET DTO 仍只给掩码")
    void plainApiKeyAccessor_forRuntimeCall() {
        SettingsRecord row = rowWithKey(PLAIN_KEY);
        SettingsService service = newService(mock(SettingsMapper.class), row);

        assertThat(service.get().apiKey()).isEqualTo("****1234");
        assertThat(service.readDbApiKeyPlain())
            .as("WebFetch DeepSeek 抓取必须用真 key（否则 401）").isEqualTo(PLAIN_KEY);
    }

    @Test
    @DisplayName("GET：未配置 / 空白 key → null（不是 ****，前端据此区分已配置与未配置）")
    void get_blankKeyBecomesNull() {
        SettingsMapper nullMapper = mock(SettingsMapper.class);
        assertThat(newService(nullMapper, rowWithKey(null)).get().apiKey()).isNull();

        SettingsMapper blankMapper = mock(SettingsMapper.class);
        assertThat(newService(blankMapper, rowWithKey("   ")).get().apiKey()).isNull();
    }

    @Test
    @DisplayName("PUT：回传末四位掩码 → 库中真 key 不变（掩码哨兵）")
    void update_withTailMaskKeepsStoredKey() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = rowWithKey(PLAIN_KEY);
        SettingsService service = newService(mapper, row);

        service.update(apiKeyPatch(ApiKeyHasher.maskTail(PLAIN_KEY)));

        assertThat(row.getApiKey()).as("掩码回传必须视为「未修改」").isEqualTo(PLAIN_KEY);
        verify(mapper).update(row);
    }

    @Test
    @DisplayName("PUT：回传旧格式前缀掩码（sk-****1234）也不覆盖真 key")
    void update_withLegacyPrefixMaskKeepsStoredKey() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = rowWithKey(PLAIN_KEY);
        SettingsService service = newService(mapper, row);

        service.update(apiKeyPatch(ApiKeyHasher.mask(PLAIN_KEY)));

        assertThat(row.getApiKey()).isEqualTo(PLAIN_KEY);
    }

    @Test
    @DisplayName("PUT：新明文 key → 覆盖，且响应仍只回掩码")
    void update_withNewPlainKeyOverwrites() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = rowWithKey(PLAIN_KEY);
        SettingsService service = newService(mapper, row);

        SettingsDto dto = service.update(apiKeyPatch("sk-new-key-9999"));

        assertThat(row.getApiKey()).isEqualTo("sk-new-key-9999");
        assertThat(dto.apiKey()).as("写回响应同样不得回明文").isEqualTo("****9999");
    }

    @Test
    @DisplayName("PUT：空串 = 显式清空（前端「清空」按钮的语义，不是掩码）")
    void update_withEmptyStringClears() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = rowWithKey(PLAIN_KEY);
        SettingsService service = newService(mapper, row);

        service.update(apiKeyPatch(""));

        assertThat(row.getApiKey()).isEmpty();
    }
}
