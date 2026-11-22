package com.nexusai.domain.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.provider.dto.ProviderCreateRequest;
import com.nexusai.model.provider.dto.ProviderType;
import com.nexusai.model.provider.dto.ProviderUpdateRequest;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [保密 2026-11-22 B4/B5] provider.apiKey 写侧哨兵 + 掩码格式统一。
 *
 * <p>WHY：GET /providers 只回 apiKeyMasked；写侧若仍按「非 null 就覆盖」，把掩码当 apiKey
 * 回传的客户端会把真 key 的密文换成掩码的密文 —— 静默破坏（之后全 401，且难查）。
 * 基准 = 当前密文的解密明文（拿不到回落 api_key_masked）；掩码/空/未传 = 不改。
 */
class ProviderApiKeySentinelTest {

    private static final String ID = "prov-1";
    private static final String CURRENT_PLAIN = "sk-current-123456";
    private static final String CURRENT_ENC = "enc-current";

    private ProviderMapper providerMapper;
    private CryptoUtil cryptoUtil;
    private ProviderService service;
    private ProviderRecord current;

    @BeforeEach
    void setUp() {
        service = new ProviderService();
        providerMapper = mock(ProviderMapper.class);
        cryptoUtil = mock(CryptoUtil.class);
        ReflectionTestUtils.setField(service, "providerMapper", providerMapper);
        ReflectionTestUtils.setField(service, "modelMapper", mock(ModelMapper.class));
        ReflectionTestUtils.setField(service, "cryptoUtil", cryptoUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(cryptoUtil.decrypt(CURRENT_ENC)).thenReturn(CURRENT_PLAIN);
        when(cryptoUtil.encrypt(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
        current = record("****3456");
        when(providerMapper.selectOneById(ID)).thenReturn(current);
    }

    private static ProviderRecord record(String masked) {
        com.nexusai.model.provider.Provider p = new com.nexusai.model.provider.Provider();
        p.setId(ID);
        p.setName("demo");
        p.setType(ProviderType.openai_compatible.name());
        p.setBaseUrl("https://api.example.com");
        p.setApiKeyHash("hash");
        p.setApiKeyMasked(masked);
        p.setApiKeyEncrypted(CURRENT_ENC);
        p.setEnabled(true);
        p.setAuthMode(ProviderService.AUTH_MODE_API_KEY);
        p.setCreatedAt(java.time.OffsetDateTime.now().toString());
        p.setUpdatedAt(java.time.OffsetDateTime.now().toString());
        return ProviderRecord.fromDomain(p);
    }

    private ArgumentCaptor<ProviderRecord> patch(String apiKey) {
        service.update(ID, new ProviderUpdateRequest(null, null, null, apiKey, null, null, null));
        ArgumentCaptor<ProviderRecord> c = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper).update(c.capture());
        return c;
    }

    @Test
    @DisplayName("PUT：回传末四位掩码 → 密文不变")
    void maskedEcho_keepsCiphertext() {
        ProviderRecord saved = patch("****3456").getValue();
        assertThat(saved.getApiKeyEncrypted()).isEqualTo(CURRENT_ENC);
        assertThat(saved.getApiKeyMasked()).isEqualTo("****3456");
    }

    @Test
    @DisplayName("PUT：回传旧格式前缀掩码（sk-****3456）→ 密文不变（解密比对兜底）")
    void legacyPrefixMask_keepsCiphertext() {
        current = record("sk-****3456");
        when(providerMapper.selectOneById(ID)).thenReturn(current);
        ProviderRecord saved = patch("sk-****3456").getValue();
        assertThat(saved.getApiKeyEncrypted()).isEqualTo(CURRENT_ENC);
    }

    @Test
    @DisplayName("PUT：空串 → 不改（不把空凭据写进库）")
    void blankKey_keepsCiphertext() {
        assertThat(patch("").getValue().getApiKeyEncrypted()).isEqualTo(CURRENT_ENC);
    }

    @Test
    @DisplayName("PUT：null → 不改（既有 PATCH null-skip 语义）")
    void nullKey_keepsCiphertext() {
        assertThat(patch(null).getValue().getApiKeyEncrypted()).isEqualTo(CURRENT_ENC);
    }

    @Test
    @DisplayName("PUT：新明文 → 覆盖密文，掩码统一为末四位（不出现 sk-）")
    void newPlainKey_overwrites() {
        ProviderRecord saved = patch("sk-rotated-9999").getValue();
        assertThat(saved.getApiKeyEncrypted()).isEqualTo("enc:sk-rotated-9999");
        assertThat(saved.getApiKeyMasked()).isEqualTo("****9999");
    }

    @Test
    @DisplayName("POST：新建 provider 的 api_key_masked 也统一为末四位（不含 sk- 前缀）")
    void create_maskFormatUnified() {
        when(providerMapper.selectOneByQuery(any())).thenReturn(null);
        service.create(new ProviderCreateRequest(
            "p-new", ProviderType.openai_compatible, "https://api.example.com",
            "sk-live-abcdefgh1234", null, true, null));

        ArgumentCaptor<ProviderRecord> c = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper).insert(c.capture());
        assertThat(c.getValue().getApiKeyMasked()).isEqualTo("****1234");
        assertThat(c.getValue().getApiKeyMasked()).doesNotContain("sk-");
        assertThat(c.getValue().getApiKeyEncrypted()).isEqualTo("enc:sk-live-abcdefgh1234");
    }
}
