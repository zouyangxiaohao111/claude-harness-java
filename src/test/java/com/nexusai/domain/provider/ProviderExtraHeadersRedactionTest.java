package com.nexusai.domain.provider;

import com.nexusai.infra.util.ApiKeyHasher;
import com.nexusai.model.provider.Provider;
import com.nexusai.model.provider.dto.ProviderDto;
import com.nexusai.model.provider.dto.ProviderType;
import com.nexusai.model.provider.dto.ProviderUpdateRequest;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [保密 · 2026-11-22 第二批 B1] provider 自定义 header 的敏感值脱敏 + 写侧哨兵。
 *
 * <p>规则：名字命中 *KEY/*TOKEN/*SECRET/*PASSWORD/Authorization 的值只回 {@code ****末四位}，
 * 其余头原样（用户要能看/改它们）。写侧掩码回传 ⇒ 保留库中真值，否则下一次上游请求就 401。
 *
 * <p>真实请求仍用明文：{@code ProviderHeaderInjector} 读 {@code Provider.extraHeaders}（DB），
 * 不经 DTO —— 本测试断言 DB 记录里的头保持原值。
 */
class ProviderExtraHeadersRedactionTest {

    private static final String ID = "prov-1";
    private static final String REAL_AUTH = "Bearer sk-live-abcdefgh1234";

    private ProviderService service(ProviderRecord rec) {
        ProviderService service = new ProviderService();
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        ModelMapper modelMapper = mock(ModelMapper.class);
        ReflectionTestUtils.setField(service, "providerMapper", providerMapper);
        ReflectionTestUtils.setField(service, "modelMapper", modelMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        when(providerMapper.selectOneById(ID)).thenReturn(rec);
        when(modelMapper.selectListByQuery(any())).thenReturn(java.util.List.of());
        return service;
    }

    private static ProviderRecord recordWith(Map<String, String> headers) {
        Provider p = new Provider();
        p.setId(ID);
        p.setName("demo");
        p.setType(ProviderType.openai_compatible.name());
        p.setBaseUrl("https://api.example.com");
        p.setApiKeyMasked("****1234");
        p.setApiKeyHash("hash");
        p.setApiKeyEncrypted("enc");
        p.setExtraHeaders(ProviderService.serializeHeaders(headers));
        p.setEnabled(true);
        p.setAuthMode(ProviderService.AUTH_MODE_API_KEY);
        p.setCreatedAt(java.time.OffsetDateTime.now().toString());
        p.setUpdatedAt(java.time.OffsetDateTime.now().toString());
        return ProviderRecord.fromDomain(p);
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", REAL_AUTH);
        h.put("X-API-Key", "key-1234567890");
        h.put("X-Auth-Token", "tok-1234567890");
        h.put("X-Trace-Id", "trace-1");
        return h;
    }

    @Test
    @DisplayName("GET：敏感头只回末四位掩码，非敏感头原样；DB 记录保持明文")
    void get_masksSensitiveHeaders() {
        ProviderRecord rec = recordWith(baseHeaders());
        ProviderService service = service(rec);

        ProviderDto dto = service.getById(ID);

        assertThat(dto.extraHeaders()).containsEntry("Authorization", "****1234");
        assertThat(dto.extraHeaders()).containsEntry("X-API-Key", "****7890");
        assertThat(dto.extraHeaders()).containsEntry("X-Auth-Token", "****7890");
        assertThat(dto.extraHeaders()).containsEntry("X-Trace-Id", "trace-1");
        assertThat(dto.extraHeaders().values()).noneMatch(v -> v.contains("sk-live-abcdefgh1234"));
        // 运行时注入读 DB（ProviderHeaderInjector.apply(p.getExtraHeaders())）⇒ 明文必须还在
        assertThat(ProviderService.deserializeHeaders(rec.getExtraHeaders()).get("Authorization"))
            .isEqualTo(REAL_AUTH);
    }

    @Test
    @DisplayName("PATCH：掩码回传保留真值，同时其它头照改")
    void update_maskedHeadersKeepStored() {
        ProviderRecord rec = recordWith(baseHeaders());
        ProviderService service = service(rec);

        // Authorization / x-api-key 属系统管理头（validateExtraHeaders 拒绝自定义）⇒ 用 X-Auth-Token
        // 这类同样命中 *TOKEN 的自定义敏感头验证哨兵。
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("X-Auth-Token", "****7890");
        incoming.put("X-Trace-Id", "trace-2");     // 非敏感：正常改
        service.update(ID, new ProviderUpdateRequest(null, null, null, null, incoming, null, null));

        ArgumentCaptor<ProviderRecord> captor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(mapperOf(service)).update(captor.capture());
        Map<String, String> saved = ProviderService.deserializeHeaders(captor.getValue().getExtraHeaders());
        assertThat(saved).containsEntry("X-Auth-Token", "tok-1234567890");
        assertThat(saved).containsEntry("X-Trace-Id", "trace-2");
    }

    @Test
    @DisplayName("PATCH：新明文敏感头 → 覆盖")
    void update_newPlainHeaderOverwrites() {
        ProviderRecord rec = recordWith(baseHeaders());
        ProviderService service = service(rec);

        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("X-Auth-Token", "tok-rotated-9999");
        service.update(ID, new ProviderUpdateRequest(null, null, null, null, incoming, null, null));

        ArgumentCaptor<ProviderRecord> captor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(mapperOf(service)).update(captor.capture());
        Map<String, String> saved = ProviderService.deserializeHeaders(captor.getValue().getExtraHeaders());
        assertThat(saved).containsEntry("X-Auth-Token", "tok-rotated-9999");
    }

    private static ProviderMapper mapperOf(ProviderService service) {
        return (ProviderMapper) ReflectionTestUtils.getField(service, "providerMapper");
    }

    @Test
    @DisplayName("服务端内部读链：getPlainExtraHeaders 给真值，GET DTO 仍只给掩码")
    void plainExtraHeaders_forRuntimeRequest() {
        ProviderRecord rec = recordWith(baseHeaders());
        ProviderService service = service(rec);

        assertThat(service.getById(ID).extraHeaders())
            .as("出站 DTO 必须掩码").containsEntry("Authorization", "****1234");
        assertThat(service.getPlainExtraHeaders(ID))
            .as("hook 子代理发请求必须用真值（否则上游 401）")
            .containsEntry("Authorization", REAL_AUTH);
    }

    @Test
    @DisplayName("统一掩码口径：maskTail/maskSecret 与 isMaskedSentinel 同一实现")
    void sharedMasker() {
        assertThat(ApiKeyHasher.isMaskedSentinel("****1234", REAL_AUTH)).isTrue();
        assertThat(ApiKeyHasher.isMaskedSentinel("Bearer new", REAL_AUTH)).isFalse();
    }
}
