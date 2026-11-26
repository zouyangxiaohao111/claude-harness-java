package com.nexusai.domain.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.provider.dto.ProviderDto;
import com.nexusai.model.provider.dto.RemoteModelsResponse;
import com.nexusai.model.provider.dto.TestConnectionResponse;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProviderService 的 Google 订阅分支单元测试（Mockito 替身 mapper，⛔ 无 DB / 无 Spring / 无网络）。
 *
 * <p>覆盖：ensure 幂等与命名回退、token+project 同次写入、缺 project 拒绝、refresh 无新值保留旧值、
 * project 刷新不丢、clearOAuth 清 project、test/fetchRemoteModels 本地短路（不打 /models）。
 */
class ProviderServiceGoogleTest {

    private ProviderService service;
    private ProviderMapper providerMapper;
    private ModelMapper modelMapper;
    private final List<ProviderRecord> providers = new ArrayList<>();
    private final List<ModelRecord> models = new ArrayList<>();
    private final AtomicInteger providerInserts = new AtomicInteger();
    private final AtomicInteger modelInserts = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        service = new ProviderService();
        providerMapper = mock(ProviderMapper.class);
        modelMapper = mock(ModelMapper.class);
        CryptoUtil crypto = mock(CryptoUtil.class);
        when(crypto.encrypt(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
        when(crypto.decrypt(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).substring(4));

        when(providerMapper.selectAll()).thenAnswer(i -> providers);
        when(modelMapper.selectAll()).thenAnswer(i -> models);
        when(modelMapper.selectListByQuery(any())).thenAnswer(i -> models);
        when(providerMapper.selectOneById(anyString())).thenAnswer(i -> providers.stream()
            .filter(r -> r.getId().equals(i.getArgument(0))).findFirst().orElse(null));
        when(providerMapper.insert(any(ProviderRecord.class))).thenAnswer(i -> {
            providers.add(i.getArgument(0));
            return providerInserts.incrementAndGet();
        });
        when(modelMapper.insert(any(ModelRecord.class))).thenAnswer(i -> {
            models.add(i.getArgument(0));
            return modelInserts.incrementAndGet();
        });

        inject("providerMapper", providerMapper);
        inject("modelMapper", modelMapper);
        inject("cryptoUtil", crypto);
        inject("objectMapper", new ObjectMapper());
    }

    @Test
    @DisplayName("ensure 幂等：重复调用只插一条，默认模型只种一次")
    void ensure_isIdempotent() {
        ProviderDto first = service.ensureOfficialGoogleProvider();
        ProviderDto second = service.ensureOfficialGoogleProvider();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.type().name()).isEqualTo("gemini_code_assist");
        assertThat(first.baseUrl()).isEqualTo("https://cloudcode-pa.googleapis.com");
        assertThat(providerInserts.get()).isEqualTo(1);
        assertThat(models).extracting(ModelRecord::getName)
            .containsExactly("gemini-2.5-pro", "gemini-3.5-flash");
        assertThat(modelInserts.get()).isEqualTo(2);
        // getOfficialGoogle 必须能识别已 ensure 但未登录的行（auth_mode 仍是 api_key）
        assertThat(service.getOfficialGoogleProvider().id()).isEqualTo(first.id());
    }

    @Test
    @DisplayName("ensure 命名回退：已有同名 Google（别的类型）→ 新 provider 叫 Google-订阅")
    void ensure_nameFallback() {
        providers.add(record("prov-x", "Google", "openai_compatible", ProviderService.AUTH_MODE_API_KEY));

        ProviderDto created = service.ensureOfficialGoogleProvider();
        assertThat(created.name()).isEqualTo("Google-订阅");
        assertThat(providerInserts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("saveGoogleOAuthCredential：auth/token/refresh/expires/project 同一次 update 落库")
    void saveGoogleOAuthCredential_singleUpdate() {
        providers.add(record("p1", "Google", "gemini_code_assist", ProviderService.AUTH_MODE_API_KEY));

        service.saveGoogleOAuthCredential("p1", "access", "refresh", 123456L, " proj-1 ");

        ArgumentCaptor<ProviderRecord> captor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper).update(captor.capture(), anyBoolean());
        ProviderRecord updated = captor.getValue();
        assertThat(updated.getAuthMode()).isEqualTo(ProviderService.AUTH_MODE_GOOGLE_OAUTH);
        assertThat(updated.getOauthAccessEncrypted()).isEqualTo("enc:access");
        assertThat(updated.getOauthRefreshEncrypted()).isEqualTo("enc:refresh");
        assertThat(updated.getOauthExpiresAt()).isEqualTo(123456L);
        assertThat(updated.getOauthProject()).isEqualTo("proj-1");
    }

    @Test
    @DisplayName("saveGoogleOAuthCredential：缺 project / 缺 access → 拒绝且不写库")
    void saveGoogleOAuthCredential_rejectsHalfSuccess() {
        providers.add(record("p1", "Google", "gemini_code_assist", ProviderService.AUTH_MODE_API_KEY));

        assertThatThrownBy(() -> service.saveGoogleOAuthCredential("p1", "access", "refresh", 1L, " "))
            .isInstanceOf(RuntimeException.class).hasMessageContaining("project");
        assertThatThrownBy(() -> service.saveGoogleOAuthCredential("p1", " ", "refresh", 1L, "proj"))
            .isInstanceOf(RuntimeException.class).hasMessageContaining("access token");
        verify(providerMapper, org.mockito.Mockito.never()).update(any(), anyBoolean());
    }

    @Test
    @DisplayName("saveGoogleOAuthCredential：refresh 为 null → 保留旧 refresh（重登/刷新响应不带新值）")
    void saveGoogleOAuthCredential_nullRefreshKeepsOld() {
        ProviderRecord r = record("p1", "Google", "gemini_code_assist", ProviderService.AUTH_MODE_GOOGLE_OAUTH);
        r.setOauthRefreshEncrypted("enc:old-refresh");
        r.setOauthProject("proj-old");
        providers.add(r);

        service.saveGoogleOAuthCredential("p1", "access-2", null, 999L, "proj-new");

        ArgumentCaptor<ProviderRecord> captor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper).update(captor.capture(), anyBoolean());
        assertThat(captor.getValue().getOauthRefreshEncrypted()).isEqualTo("enc:old-refresh");
        assertThat(captor.getValue().getOauthProject()).isEqualTo("proj-new");
    }

    @Test
    @DisplayName("clearOAuth：清 5 列（含 oauth_project），auth_mode 回落 api_key")
    void clearOAuth_clearsProject() {
        ProviderRecord r = record("p1", "Google", "gemini_code_assist", ProviderService.AUTH_MODE_GOOGLE_OAUTH);
        r.setOauthAccessEncrypted("enc:a");
        r.setOauthRefreshEncrypted("enc:r");
        r.setOauthExpiresAt(1L);
        r.setOauthProject("proj");
        providers.add(r);

        service.clearOAuth("p1");

        ArgumentCaptor<ProviderRecord> captor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper).update(captor.capture(), anyBoolean());
        assertThat(captor.getValue().getOauthAccessEncrypted()).isNull();
        assertThat(captor.getValue().getOauthRefreshEncrypted()).isNull();
        assertThat(captor.getValue().getOauthProject()).isNull();
        assertThat(captor.getValue().getAuthMode()).isEqualTo(ProviderService.AUTH_MODE_API_KEY);
    }

    @Test
    @DisplayName("getOAuthSnapshot：只读元数据（含 hasRefreshToken），不带 token/state")
    void snapshot_exposesFlagsOnly() {
        ProviderRecord r = record("p1", "Google", "gemini_code_assist", ProviderService.AUTH_MODE_GOOGLE_OAUTH);
        r.setOauthAccessEncrypted("enc:a");
        r.setOauthRefreshEncrypted("enc:r");
        r.setOauthExpiresAt(42L);
        r.setOauthProject("proj");
        providers.add(r);

        var snap = service.getOAuthSnapshot("p1");
        assertThat(snap.hasAccessToken()).isTrue();
        assertThat(snap.hasRefreshToken()).isTrue();
        assertThat(snap.project()).isEqualTo("proj");
        assertThat(snap.expiresAt()).isEqualTo(42L);
    }

    @Test
    @DisplayName("test()：google 订阅走本地校验（无 token/无 project 不标绿；齐备才 true）")
    void test_googleSubscriptionLocal() {
        ProviderRecord none = record("p1", "Google", "gemini_code_assist", ProviderService.AUTH_MODE_GOOGLE_OAUTH);
        providers.add(none);
        TestConnectionResponse r1 = service.test("p1");
        assertThat(r1.ok()).isFalse();
        assertThat(r1.message()).contains("未登录");

        none.setOauthAccessEncrypted("enc:a");
        TestConnectionResponse r2 = service.test("p1");
        assertThat(r2.ok()).isFalse();
        assertThat(r2.message()).contains("项目未就绪");

        none.setOauthProject("proj");
        TestConnectionResponse r3 = service.test("p1");
        assertThat(r3.ok()).isTrue();
    }

    @Test
    @DisplayName("fetchRemoteModels：google 订阅明确本地短路，不请求 /models")
    void fetchRemoteModels_googleShortCircuits() {
        providers.add(record("p1", "Google", "gemini_code_assist", ProviderService.AUTH_MODE_GOOGLE_OAUTH));

        RemoteModelsResponse resp = service.fetchRemoteModels("p1");
        assertThat(resp.ok()).isFalse();
        assertThat(resp.models()).isEmpty();
        assertThat(resp.message()).contains("不支持远端模型探测");
    }

    @Test
    @DisplayName("ensure 并发：同 JVM 多点并发 → 同一 id、只插一条、模型只种一次")
    void ensure_concurrentSameIdOnlyOneInsert() throws Exception {
        int threads = 16;
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<ProviderDto>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.ensureOfficialGoogleProvider();
            }));
        }
        start.countDown();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (var f : futures) {
            ids.add(f.get().id());
        }
        ids.add(service.ensureOfficialGoogleProvider().id());
        pool.shutdownNow();

        assertThat(ids).hasSize(1);
        assertThat(providerInserts.get()).isEqualTo(1);
        assertThat(modelInserts.get()).as("默认模型并发下也只种一次（同 provider）").isEqualTo(2);
    }

    @Test
    @DisplayName("refresh：响应无新 refresh / 不动 project → 保留旧 refresh token 与 oauth_project")
    void refresh_keepsProjectAndRefreshToken() {
        ProviderRecord r = record("p1", "Google", "gemini_code_assist", ProviderService.AUTH_MODE_GOOGLE_OAUTH);
        r.setOauthAccessEncrypted("enc:old-access");
        r.setOauthRefreshEncrypted("enc:old-refresh");
        r.setOauthExpiresAt(1L);
        r.setOauthProject("proj-old");
        providers.add(r);
        ProviderService.OAuthRefresher refresher = new ProviderService.OAuthRefresher() {
            @Override public String authMode() {
                return ProviderService.AUTH_MODE_GOOGLE_OAUTH;
            }

            @Override public Refreshed refresh(String refreshToken) {
                assertThat(refreshToken).isEqualTo("old-refresh");
                // 官方刷新响应通常不带新 refresh_token ⇒ 返回 null
                return new Refreshed("new-access", null, 9_000_000L, null);
            }
        };
        try {
            inject("oauthRefreshers", List.of(refresher));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        String credential = service.resolveCredential("p1");

        assertThat(credential).isEqualTo("new-access");
        assertThat(r.getOauthRefreshEncrypted()).as("无新 refresh token → 沿用旧值").isEqualTo("enc:old-refresh");
        assertThat(r.getOauthProject()).as("刷新不得丢 Cloud Code project").isEqualTo("proj-old");
        assertThat(r.getOauthExpiresAt()).isEqualTo(9_000_000L);
    }

    // ───────────────────────────── 辅助 ─────────────────────────────

    private static ProviderRecord record(String id, String name, String type, String authMode) {
        ProviderRecord r = new ProviderRecord();
        r.setId(id);
        r.setName(name);
        r.setType(type);
        r.setAuthMode(authMode);
        r.setEnabled(Boolean.TRUE);
        r.setCreatedAt("2026-01-01T00:00:00+08:00");
        r.setUpdatedAt("2026-01-01T00:00:00+08:00");
        return r;
    }

    private void inject(String field, Object value) throws Exception {
        Field f = ProviderService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }
}
