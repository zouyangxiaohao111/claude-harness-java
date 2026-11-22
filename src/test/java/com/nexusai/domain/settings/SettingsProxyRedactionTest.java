package com.nexusai.domain.settings;

import com.nexusai.model.settings.dto.SettingsDto;
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
 * [保密 · 2026-11-22 第二批 B3] settings.proxy 的口令段脱敏。
 *
 * <p>规则：读只回 {@code http://user:****@host:port}（主机端口保持可读，用户要能编辑）；
 * 写侧掩码哨兵允许「只改主机/端口」，口令从库里换回真值。
 *
 * <p>明文使用者：{@code WebSearchTool.readProxy} / {@code WebFetchTool} 直读 SettingsRecord
 * ⇒ 脱敏不影响真实请求（本测试同时断言 DB 记录未被改写）。
 */
class SettingsProxyRedactionTest {

    private static final int SINGLETON_ID = 1;
    private static final String STORED = "http://user:s3cret-pass@127.0.0.1:7897";
    private static final String MASKED = "http://user:****@127.0.0.1:7897";

    private SettingsService newService(SettingsMapper mapper, SettingsRecord dbRow) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(dbRow);
        return service;
    }

    private static SettingsRecord rowWithProxy(String proxy) {
        SettingsRecord s = new SettingsRecord();
        s.setAutoMemoryEnabled(true);   // 避免 toDto 回落 settings.json 文件读
        s.setProxy(proxy);
        return s;
    }

    /** 只带 proxy 的请求（69 参兼容构造器；proxy 是第 21 个参数）。 */
    private static SettingsDto proxyPatch(String proxy) {
        return new SettingsDto(
            null, null, null, null,                                          //  1- 4
            null, null, null, null, null, null, null, null, null, null,      //  5-14
            null, null, null, null,                                          // 15-18
            null, null, proxy, null, null, null,                             // 19-24 engine/apiKey/proxy/useSmall/baseUrl/domainCheck
            null, null, null,                                                 // 25-27
            null, null, null, null, null, null, null, null, null, null, null, null, // 28-39
            null, null, null, null, null, null, null, null, null, null, null, // 40-50
            null,                                                             // 51
            null, null, null, null, null, null, null, null, null,             // 52-60
            null, null,                                                       // 61-62
            null,                                                             // 63
            null, null, null, null, null, null                                // 64-69
        );
    }

    @Test
    @DisplayName("GET：口令段掩码，主机端口可读；DB 记录保持明文")
    void get_masksProxyPassword() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = rowWithProxy(STORED);
        SettingsService service = newService(mapper, row);

        SettingsDto dto = service.get();

        assertThat(dto.proxy()).isEqualTo(MASKED);
        assertThat(dto.proxy()).doesNotContain("s3cret-pass");
        assertThat(row.getProxy()).as("DB 记录保持明文").isEqualTo(STORED);
        assertThat(service.readDbWebsearchProxy())
            .as("WebFetch/WebSearch 运行时读代理必须用真值（否则代理认证失败）").isEqualTo(STORED);
    }

    @Test
    @DisplayName("PUT：整串掩码回传 → 库中口令不变（哨兵）")
    void update_withMaskedProxyKeepsStored() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = rowWithProxy(STORED);
        SettingsService service = newService(mapper, row);

        service.update(proxyPatch(MASKED));

        assertThat(row.getProxy()).isEqualTo(STORED);
        verify(mapper).update(row);
    }

    @Test
    @DisplayName("PUT：只改主机端口、口令留掩码 → 换回真口令后落库")
    void update_withMaskedPasswordAndNewHost() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = rowWithProxy(STORED);
        SettingsService service = newService(mapper, row);

        service.update(proxyPatch("http://user:****@10.0.0.2:8080"));

        assertThat(row.getProxy()).isEqualTo("http://user:s3cret-pass@10.0.0.2:8080");
    }

    @Test
    @DisplayName("无凭据代理：读/写都原样，不被误掩")
    void proxyWithoutCredentials_untouched() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = rowWithProxy("127.0.0.1:7897");
        SettingsService service = newService(mapper, row);

        assertThat(service.get().proxy()).isEqualTo("127.0.0.1:7897");
        service.update(proxyPatch("10.0.0.2:8080"));
        assertThat(row.getProxy()).isEqualTo("10.0.0.2:8080");
    }
}
