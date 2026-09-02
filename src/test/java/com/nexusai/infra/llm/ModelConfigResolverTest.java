package com.nexusai.infra.llm;

import com.nexusai.domain.provider.ProviderService;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * [RV14B-RES-01] ModelConfigResolver 共享配置解析器单测。
 *
 * <p>WHY（规则九 · 测试验证意图）：DEC-RV-14b 核心——7 处生产恒 mock 站点必须从 resolver
 * 拿到真实 (config, providerType)，不可用时 warn+skip（返回 null）而非构造 mock。
 *
 * <ul>
 *   <li>resolve 对 enabled model 返回真实 config+providerType</li>
 *   <li>不可用（无 model / 无 provider / 解密空 key）→ null，绝不 mock</li>
 *   <li>resolveFastModelName 弱档三级回退（W7-1：fast→weak→fallback，对齐 CC getSmallFastModel
 *       model.ts:36-38 + getDefaultHaikuModel model.ts:131-138；不回退 main）；resolveStrongModelName
 *       强档 settings.strongModelName 全名反查（[FN2]，未配置 → 固定默认）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ModelConfigResolverTest {

    @Mock private ModelMapper modelMapper;
    @Mock private ProviderMapper providerMapper;
    @Mock private SettingsMapper settingsMapper;
    @Mock private ProviderService providerService;

    private ModelConfigResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ModelConfigResolver();
        // 字段注入（无 Spring 容器场景，对齐 ChatService 测试的字段注入风格）
        setField(resolver, "modelMapper", modelMapper);
        setField(resolver, "providerMapper", providerMapper);
        setField(resolver, "settingsMapper", settingsMapper);
        setField(resolver, "providerService", providerService);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("resolve: enabled model → 真实 (config, providerType)")
    void resolve_returnsRealConfig() {
        ModelRecord model = new ModelRecord();
        model.setId("m1");
        model.setName("claude-sonnet");
        model.setProviderId("p1");
        model.setEnabled(true);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(model));

        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        provider.setEnabled(true);
        provider.setType("anthropic");
        provider.setBaseUrl("https://api.anthropic.com");
        when(providerMapper.selectOneById("p1")).thenReturn(provider);

        when(providerService.getDecryptedApiKey("p1")).thenReturn("sk-secret");

        ModelConfigResolver.ResolvedModel r = resolver.resolve("claude-sonnet");

        assertThat(r).as("enabled model 必须解析出真实 config").isNotNull();
        assertThat(r.config().baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(r.config().apiKey()).isEqualTo("sk-secret");
        assertThat(r.providerType()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("resolve: modelName 为空 → null（warn+skip，不落 mock）")
    void resolve_blankModelName_returnsNull() {
        assertThat(resolver.resolve(null)).isNull();
        assertThat(resolver.resolve("  ")).isNull();
    }

    @Test
    @DisplayName("[FIX-STRIP-PREFIX] resolveSdkModelName: 全名 providerName/modelName → DB models.name 裸名")
    void resolveSdkModelName_stripsProviderPrefix() {
        // 前端传全名 "deepseek/deepseek-v4-flash"（settings 存全名/裸名，ModelPickerModal
        // fullName=providerName/modelName）。ModelNameResolver 全名路径：首段 deepseek 命中
        // providers 表前缀 → 联合查 models.name="deepseek-v4-flash" + provider_id → 命中。
        ProviderRecord deepseek = new ProviderRecord();
        deepseek.setId("p-ds");
        deepseek.setName("deepseek");
        when(providerMapper.selectOneByQuery(any())).thenReturn(deepseek);

        ModelRecord model = new ModelRecord();
        model.setProviderId("p-ds");
        model.setName("deepseek-v4-flash");   // DB models.name = 裸名
        model.setEnabled(true);
        when(modelMapper.selectOneByQuery(any())).thenReturn(model);

        String bare = resolver.resolveSdkModelName("deepseek/deepseek-v4-flash");

        assertThat(bare)
            .as("SDK model 参数必须裸名（全名会导致 API 400: 'supported model names are deepseek-v4-flash...'）")
            .isEqualTo("deepseek-v4-flash");
    }

    @Test
    @DisplayName("[FIX-STRIP-PREFIX] resolveSdkModelName: 裸名入参原样返回（幂等，不重复剥）")
    void resolveSdkModelName_bareNameIdempotent() {
        // 裸名（无 /）走历史兼容路径：按 name 查第一条 enabled → 返回同名裸名
        ModelRecord model = new ModelRecord();
        model.setProviderId("p1");
        model.setName("deepseek-v4-flash");
        model.setEnabled(true);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(model));

        assertThat(resolver.resolveSdkModelName("deepseek-v4-flash"))
            .as("裸名入参返回裸名（幂等）")
            .isEqualTo("deepseek-v4-flash");
    }

    @Test
    @DisplayName("[FIX-STRIP-PREFIX] resolveSdkModelName: 未命中 → null（调用方回落原始值透传）")
    void resolveSdkModelName_notFound_returnsNull() {
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of());
        assertThat(resolver.resolveSdkModelName("unknown/deepseek-v4-flash"))
            .as("未命中返回 null（CC 未知名直接传 API，失败即失败，不伪造裸名）")
            .isNull();
        assertThat(resolver.resolveSdkModelName(null)).isNull();
        assertThat(resolver.resolveSdkModelName("  ")).isNull();
    }

    @Test
    @DisplayName("resolve: enabled model 未命中 → null（不落 mock）")
    void resolve_modelNotFound_returnsNull() {
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of());
        assertThat(resolver.resolve("unknown-model")).isNull();
    }

    @Test
    @DisplayName("resolve: provider apiKey 解密为空 → null（不落 mock）")
    void resolve_noApiKey_returnsNull() {
        ModelRecord model = new ModelRecord();
        model.setId("m1");
        model.setName("claude-sonnet");
        model.setProviderId("p1");
        model.setEnabled(true);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(model));

        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        provider.setEnabled(true);
        provider.setType("anthropic");
        when(providerMapper.selectOneById("p1")).thenReturn(provider);

        when(providerService.getDecryptedApiKey("p1")).thenReturn(null);

        assertThat(resolver.resolve("claude-sonnet"))
            .as("apiKey 为空 → null，绝不构造 mock 喂进模型")
            .isNull();
    }

    @Test
    @DisplayName("resolveFastModelName: 弱档三级回退 fast→weak→fallback（对齐 CC getSmallFastModel）")
    void resolveFastModelName_threeLevelFallback() {
        SettingsRecord s = new SettingsRecord();
        s.setId(1);
        when(settingsMapper.selectOneById(1)).thenReturn(s);

        // [FN2] settings 存全名/裸名（如 "anthropic/claude-haiku"）→ 全名反查唯一路径
        //   （provider 前缀命中 → 联合查 enabled model，对齐 settingsTierModelName / ModelNameResolver.resolve）
        ProviderRecord anthropic = new ProviderRecord();
        anthropic.setId("p1");
        anthropic.setName("anthropic");
        when(providerMapper.selectOneByQuery(any())).thenReturn(anthropic);

        // level 1: settings.fastModelName 全名命中 enabled model → 用 fast 名（CC ANTHROPIC_SMALL_FAST_MODEL, model.ts:36-38）
        s.setFastModelName("anthropic/claude-haiku");
        ModelRecord fast = new ModelRecord();
        fast.setProviderId("p1");
        fast.setName("claude-haiku");
        fast.setEnabled(true);
        when(modelMapper.selectOneByQuery(any())).thenReturn(fast);
        assertThat(resolver.resolveFastModelName("fallback")).isEqualTo("claude-haiku");

        // [W7-1] level 2: fast 未配置但 weak 全名命中 → weak 名（CC ANTHROPIC_DEFAULT_HAIKU_MODEL, model.ts:132-134）
        //   WHY: CC getSmallFastModel = ANTHROPIC_SMALL_FAST_MODEL || getDefaultHaikuModel()（model.ts:36-38），
        //   第二级弱档 haiku 承载在 settings.weakModelName（V25 列）——缺此级则弱档退化为直接跳过，
        //   与 CC 用 haiku 默认档兜底不符。
        s.setFastModelName(null);
        s.setWeakModelName("anthropic/claude-haiku-4-5");
        ModelRecord weak = new ModelRecord();
        weak.setProviderId("p1");
        weak.setName("claude-haiku-4-5");
        weak.setEnabled(true);
        when(modelMapper.selectOneByQuery(any())).thenReturn(weak);
        assertThat(resolver.resolveFastModelName("fallback"))
            .as("fast 未配置但 weak 配置时回退 weakModelName（W7-1 新增第二级）")
            .isEqualTo("claude-haiku-4-5");

        // [W2-2] level 3: fast/weak 均未配置但 main 配置 → 固定默认（绝不再回退 main_model_id）
        //   WHY: CC getSmallFastModel（model.ts:36-38）无 main 回退——弱档未配置时用固定默认，
        //   避免弱档静默升级成中档主模型（档位语义漂移）。main-name 不 stub 且不被查询即证明
        //   弱档解析链不再触碰 mainModelName（Mockito 严格模式兜底验证）。
        s.setWeakModelName(null);
        s.setMainModelName("main-id");
        assertThat(resolver.resolveFastModelName("fallback"))
            .as("fast/weak 均未配置时不得回退 mainModelId（W2-2 弱档直接映射）")
            .isEqualTo("fallback");

        // level 4: 均未命中 → 固定默认（CC haiku45 兜底, model.ts:137）
        s.setMainModelName(null);
        assertThat(resolver.resolveFastModelName("fallback")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("resolveStrongModelName: 强档 settings.strongModelName 全名反查，未配置 → 固定默认")
    void resolveStrongModelName_directMapping() {
        SettingsRecord s = new SettingsRecord();
        s.setId(1);
        when(settingsMapper.selectOneById(1)).thenReturn(s);

        // [FN2] strongModelName 存全名（如 "anthropic/claude-opus-4-6"）→ 全名反查唯一路径命中
        //   enabled model → DB models.name（CC getDefaultOpusModel 场景：alias opus/best、/insights 等）。
        //   WHY: 命中与回落必须可区分——回落用 distinct 字面量，命中返回 models.name 才有证明力。
        s.setStrongModelName("anthropic/claude-opus-4-6");
        ProviderRecord anthropic = new ProviderRecord();
        anthropic.setId("p1");
        anthropic.setName("anthropic");
        ModelRecord strong = new ModelRecord();
        strong.setProviderId("p1");
        strong.setName("claude-opus-4-6");
        strong.setEnabled(true);
        when(providerMapper.selectOneByQuery(any())).thenReturn(anthropic);
        when(modelMapper.selectOneByQuery(any())).thenReturn(strong);
        assertThat(resolver.resolveStrongModelName("fallback-opus")).isEqualTo("claude-opus-4-6");

        // 未配置 → 固定默认（如 opus46 字面量）
        s.setStrongModelName(null);
        assertThat(resolver.resolveStrongModelName("fallback-opus")).isEqualTo("fallback-opus");
    }
}
