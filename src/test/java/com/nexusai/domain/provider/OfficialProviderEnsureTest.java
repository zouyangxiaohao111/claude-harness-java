package com.nexusai.domain.provider;

import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.provider.dto.ProviderDto;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [方案 A] {@link ProviderService#ensureOfficialCodexProvider()} —— 内置「OpenAI 官方」入口的幂等创建。
 *
 * <p><b>WHY 测这一层</b>：官方卡片随时可能被重复点击（首次点登录 / 登出后再点 / 前端重试），
 * 若 ensure 不幂等，要么每次多一个重复 provider（列表污染 + 模型选择器歧义），要么在
 * 「已 ensure 但未登录」窗口里认不出自己刚建的那条。这两条都是纯逻辑判据 ⇒ 用 mock 跑真实方法：
 * 不启 Spring、不碰真库。
 */
@DisplayName("[Provider] 官方 Codex provider 幂等 ensure")
class OfficialProviderEnsureTest {

    private ProviderMapper providerMapper;
    private ModelMapper modelMapper;
    private CryptoUtil cryptoUtil;
    private ProviderService service;

    @BeforeEach
    void setUp() {
        providerMapper = Mockito.mock(ProviderMapper.class);
        // [内置默认模型] ensureOfficialCodexProvider 会调 ensureDefaultOfficialModel → modelMapper。
        // 返回空列表 ⇒ 走「模型数为 0 → 插入默认模型」分支；本类的断言只针对 provider 侧，不受影响。
        modelMapper = Mockito.mock(ModelMapper.class);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of());
        cryptoUtil = Mockito.mock(CryptoUtil.class);
        when(cryptoUtil.encrypt("")).thenReturn("enc-empty");
        service = new ProviderService();
        ReflectionTestUtils.setField(service, "providerMapper", providerMapper);
        ReflectionTestUtils.setField(service, "modelMapper", modelMapper);
        ReflectionTestUtils.setField(service, "cryptoUtil", cryptoUtil);
    }

    private static ProviderRecord record(String id, String name, String type, String authMode) {
        ProviderRecord r = new ProviderRecord();
        r.setId(id);
        r.setName(name);
        r.setType(type);
        r.setAuthMode(authMode);
        r.setEnabled(true);
        return r;
    }

    @Test
    @DisplayName("已存在 codex_oauth → 直接返回，不 insert")
    void returnsExistingOAuthProviderWithoutInsert() {
        when(providerMapper.selectAll()).thenReturn(
            List.of(record("prov-oauth-1", "我的订阅", "openai_response", "codex_oauth")));

        ProviderDto dto = service.ensureOfficialCodexProvider();

        assertThat(dto.id()).isEqualTo("prov-oauth-1");
        assertThat(dto.authMode()).isEqualTo("codex_oauth");
        verify(providerMapper, never()).insert(any(ProviderRecord.class));
    }

    @Test
    @DisplayName("不存在 → insert 一次，字段正确（name/type/baseUrl/auth_mode）")
    void createsOfficialProviderOnce() {
        when(providerMapper.selectAll()).thenReturn(List.of());

        ProviderDto dto = service.ensureOfficialCodexProvider();

        ArgumentCaptor<ProviderRecord> captor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper, times(1)).insert(captor.capture());
        ProviderRecord saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("OpenAI");
        assertThat(saved.getType()).isEqualTo("openai_response");
        assertThat(saved.getBaseUrl()).isEqualTo("https://chatgpt.com/backend-api/codex");
        // 登录前保持 api_key（saveOAuthCredential 才翻成 codex_oauth）
        assertThat(saved.getAuthMode()).isEqualTo("api_key");
        assertThat(saved.getEnabled()).isTrue();
        assertThat(saved.getApiKeyEncrypted()).isEqualTo("enc-empty");
        assertThat(saved.getApiKeyMasked()).isEqualTo("****");
        assertThat(dto.name()).isEqualTo("OpenAI");
        assertThat(dto.authMode()).isEqualTo("api_key");
    }

    @Test
    @DisplayName("name=OpenAI 被别的类型占用 → 用 OpenAI-订阅")
    void usesFallbackNameWhenTaken() {
        when(providerMapper.selectAll()).thenReturn(
            List.of(record("prov-other", "OpenAI", "openai_compatible", "api_key")));

        service.ensureOfficialCodexProvider();

        ArgumentCaptor<ProviderRecord> captor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("OpenAI-订阅");
        assertThat(captor.getValue().getType()).isEqualTo("openai_response");
    }

    @Test
    @DisplayName("连续调用两次（第二次模拟已存在）→ 只有一次 insert")
    void secondCallDoesNotInsertAgain() {
        ProviderRecord created = record("prov-official-1", "OpenAI", "openai_response", "codex_oauth");
        when(providerMapper.selectAll()).thenReturn(List.of(), List.of(created));

        service.ensureOfficialCodexProvider();
        ProviderDto second = service.ensureOfficialCodexProvider();

        verify(providerMapper, times(1)).insert(any(ProviderRecord.class));
        assertThat(second.id()).isEqualTo("prov-official-1");
    }
}
