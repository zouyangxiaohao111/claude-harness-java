package com.nexusai.application.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FactoryPresetSeeder} 聚焦单测（Mockito 轻量，不起 Spring 上下文）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：出厂预设的安全/触发语义是本次任务核心，逐条锁定：
 * <ol>
 *   <li><b>全新库（providers 空）才写</b>：minimax + deepseek 两个 provider、minimax 模型 + 3 条
 *       deepseek 模型、settings merge；<b>没有本地叠加时两个 provider 都无 key</b>（GitHub 版本语义）。</li>
 *   <li><b>老库（providers 非空）跳过</b>：零写入、零覆盖。</li>
 *   <li><b>本地叠加</b>：仅内网版本存在 {@code factory-preset.local.json}，只按 name 给已存在的
 *       provider 补 apiKey（加密落库、可解密），不新增 provider；未知 name/空 key 跳过。</li>
 *   <li><b>classpath 基础资源内容</b>：providers = minimax + deepseek、models 4 条、9 个模型档位全部
 *       指向 minimax、settings 键 ⊆ 白名单且<b>不含任何密钥</b> —— 资源漂移即测试失败。</li>
 * </ol>
 */
class FactoryPresetSeederTest {

    /** 与 application.yml 的 dev 默认一致；仅用于验证加密落库可解密。 */
    private static final String TEST_ENCRYPTION_KEY = "HxBcdiB5ncUwttp/2Z/kImpmtdG8gz79qbekIeGmAGc=";

    /** 测试叠加里的假秘钥（⛔ 非真实秘钥，只为验证叠加链路）。 */
    private static final String TEST_OVERLAY_KEY = "sk-test-only-1234567890";

    private final ProviderMapper providerMapper = mock(ProviderMapper.class);
    private final ModelMapper modelMapper = mock(ModelMapper.class);
    private final SettingsMapper settingsMapper = mock(SettingsMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CryptoUtil cryptoUtil = new CryptoUtil(TEST_ENCRYPTION_KEY);

    private FactoryPresetSeeder newSeeder() {
        return newSeeder("factory/__absent-local-overlay__.json");
    }

    private FactoryPresetSeeder newSeeder(String overlayResourcePath) {
        return new FactoryPresetSeeder(providerMapper, modelMapper, settingsMapper, objectMapper,
            cryptoUtil, overlayResourcePath);
    }

    private static JsonNode readPreset(ObjectMapper om) throws Exception {
        try (InputStream in = FactoryPresetSeeder.class.getClassLoader()
                .getResourceAsStream(FactoryPresetSeeder.RESOURCE_PATH)) {
            assertThat(in).as("classpath %s 必须存在（随 jar 分发）", FactoryPresetSeeder.RESOURCE_PATH).isNotNull();
            return om.readTree(in);
        }
    }

    @Test
    @DisplayName("全新库（providers 空）→ minimax + deepseek 两 provider、4 models、9 档位指向 minimax")
    void freshDb_seedsProviderModelsAndSettings() throws Exception {
        when(providerMapper.selectAll()).thenReturn(List.of());
        when(settingsMapper.update(any())).thenReturn(1);

        boolean seeded = newSeeder().seedIfFresh();

        assertThat(seeded).as("providers 空库应执行预设写入").isTrue();

        // —— providers：minimax + deepseek；无本地叠加 ⇒ 两者都无 key（GitHub 版本语义）——
        ArgumentCaptor<ProviderRecord> providerCaptor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper, times(2)).insertSelective(providerCaptor.capture());
        List<ProviderRecord> providers = providerCaptor.getAllValues();
        ProviderRecord minimax = providers.stream()
            .filter(p -> "minimax".equals(p.getName())).findFirst().orElseThrow();
        ProviderRecord deepseek = providers.stream()
            .filter(p -> "deepseek".equals(p.getName())).findFirst().orElseThrow();
        assertThat(minimax.getType()).isEqualTo("openai_compatible");
        assertThat(minimax.getBaseUrl()).isEqualTo("https://api.minimax.cn/v1");
        assertThat(minimax.getEnabled()).isTrue();
        assertThat(deepseek.getType()).isEqualTo("openai_compatible");
        assertThat(deepseek.getBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(deepseek.getEnabled()).isTrue();
        for (ProviderRecord p : providers) {
            assertThat(p.getApiKeyHash()).as("%s 无叠加时 hash 空串占位", p.getName()).isEmpty();
            assertThat(p.getApiKeyMasked()).as("%s 无叠加时 masked 空串 → hasKey=false", p.getName()).isEmpty();
            assertThat(p.getApiKeyEncrypted()).as("%s 无叠加时绝不写密钥密文", p.getName()).isNull();
        }

        // —— models：minimax 1 条 + deepseek 3 条 ——
        verify(modelMapper, times(4)).insertSelective(any(ModelRecord.class));
        ArgumentCaptor<ModelRecord> modelCaptor = ArgumentCaptor.forClass(ModelRecord.class);
        verify(modelMapper, times(4)).insertSelective(modelCaptor.capture());
        List<ModelRecord> models = modelCaptor.getAllValues();
        List<String> modelNames = models.stream().map(ModelRecord::getName).toList();
        assertThat(modelNames).containsExactlyInAnyOrder(
            "MiniMax-M3.1-Flash-Preview",
            "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp");
        ModelRecord minimaxModel = models.stream()
            .filter(m -> "MiniMax-M3.1-Flash-Preview".equals(m.getName())).findFirst().orElseThrow();
        assertThat(minimaxModel.getProviderId()).isEqualTo(minimax.getId());
        assertThat(minimaxModel.getType()).isEqualTo("multimodal");
        assertThat(minimaxModel.getTag()).isEqualTo("MM");
        // 1M 窗口必须随出厂预设落库，否则上下文解析会按 200k 兜底
        assertThat(minimaxModel.getMaxTokens()).isEqualTo(262144);
        assertThat(minimaxModel.getMaxContextTokens()).isEqualTo(1048576);
        assertThat(models).allMatch(m -> Boolean.TRUE.equals(m.getEnabled()));

        // —— settings：permission_mode=default；9 个档位全部指向 minimax；api_key 不预设 ——
        ArgumentCaptor<SettingsRecord> settingsCaptor = ArgumentCaptor.forClass(SettingsRecord.class);
        verify(settingsMapper).update(settingsCaptor.capture());
        SettingsRecord s = settingsCaptor.getValue();
        assertThat(s.getPermissionMode()).as("permission_mode 强制 default（不照搬用户的 bypassPermissions）").isEqualTo("default");
        assertThat(s.getAutoCompactWindow()).isEqualTo(1000000);
        assertThat(s.getMaxOutputTokens()).isEqualTo(8000);
        assertThat(s.getWebsearchUseSmallModel()).isTrue();
        assertThat(s.getAgentSwarmsEnabled()).isTrue();
        assertThat(s.getAutoCompactEnabled()).isTrue();
        assertThat(s.getReactiveCompactEnabled()).isTrue();
        assertThat(s.getHistorySnipEnabled()).isTrue();
        assertThat(s.getSmSessionMemoryEnabled()).isTrue();
        assertThat(s.getSmCompactEnabled()).isTrue();
        assertThat(s.getTimeBasedMcEnabled()).isFalse();
        assertThat(s.getLanguage()).isEqualTo("中文");
        String expectedModel = "minimax/MiniMax-M3.1-Flash-Preview";
        assertThat(s.getMainModelName()).isEqualTo(expectedModel);
        assertThat(s.getFastModelName()).isEqualTo(expectedModel);
        assertThat(s.getWeakModelName()).isEqualTo(expectedModel);
        assertThat(s.getMediumModelName()).isEqualTo(expectedModel);
        assertThat(s.getStrongModelName()).isEqualTo(expectedModel);
        assertThat(s.getSubagentModelName()).isEqualTo(expectedModel);
        assertThat(s.getFallbackModelName()).isEqualTo(expectedModel);
        assertThat(s.getMultimodalModelName()).isEqualTo(expectedModel);
        assertThat(s.getClassifierModel()).isEqualTo(expectedModel);
        assertThat(s.getTtsModelName()).isNull();
        assertThat(s.getAsrModelName()).isNull();
        assertThat(s.getApiKey()).as("settings 绝不预设密钥").isNull();
    }

    @Test
    @DisplayName("本地叠加（内网版本）：只给 minimax 注入秘钥并加密落库，可解密；deepseek 仍无 key")
    void localOverlay_injectsKeyOnlyIntoExistingProvider() throws Exception {
        when(providerMapper.selectAll()).thenReturn(List.of());
        when(settingsMapper.update(any())).thenReturn(1);

        boolean seeded = newSeeder("factory/factory-preset.local-test.json").seedIfFresh();
        assertThat(seeded).isTrue();

        ArgumentCaptor<ProviderRecord> captor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper, times(2)).insertSelective(captor.capture());
        List<ProviderRecord> providers = captor.getAllValues();
        ProviderRecord minimax = providers.stream()
            .filter(p -> "minimax".equals(p.getName())).findFirst().orElseThrow();
        ProviderRecord deepseek = providers.stream()
            .filter(p -> "deepseek".equals(p.getName())).findFirst().orElseThrow();

        assertThat(minimax.getApiKeyHash()).isNotEmpty();
        assertThat(minimax.getApiKeyMasked()).endsWith(TEST_OVERLAY_KEY.substring(TEST_OVERLAY_KEY.length() - 4));
        assertThat(minimax.getApiKeyEncrypted()).isNotNull();
        assertThat(cryptoUtil.decrypt(minimax.getApiKeyEncrypted()))
            .as("叠加进来的秘钥必须能被同一 CryptoUtil 解密（否则内网安装后不可用）")
            .isEqualTo(TEST_OVERLAY_KEY);
        assertThat(deepseek.getApiKeyEncrypted()).as("未叠加的 provider 仍无 key").isNull();
    }

    @Test
    @DisplayName("本地叠加只按 name 补 apiKey：未知 provider 不新增、空 key 跳过、不改入参")
    void localOverlay_onlyPatchesExistingProvider() throws Exception {
        JsonNode base = objectMapper.readTree(
            "[{\"name\":\"minimax\"},{\"name\":\"deepseek\"}]");
        JsonNode overlay = objectMapper.readTree(
            "{\"providers\":[{\"name\":\"minimax\",\"apiKey\":\"k1\"},"
                + "{\"name\":\"unknown\",\"apiKey\":\"k2\"},{\"name\":\"deepseek\"}]}");

        JsonNode merged = FactoryPresetSeeder.applyLocalOverlay(base, overlay);

        assertThat(merged.size()).as("不允许本地新增 provider").isEqualTo(2);
        assertThat(merged.get(0).path("apiKey").asText()).isEqualTo("k1");
        assertThat(merged.get(1).has("apiKey")).as("缺少 apiKey 的条目跳过").isFalse();
        assertThat(base.get(0).has("apiKey")).as("不得修改入参（避免叠加污染基础预设）").isFalse();
    }

    @Test
    @DisplayName("老库（providers 非空）→ 跳过预设，零写入零覆盖")
    void nonEmptyProviders_skipsPreset() throws Exception {
        ProviderRecord minimax = new ProviderRecord();
        minimax.setId("prov-minimax");
        minimax.setName("minimax");
        ModelRecord minimaxModel = new ModelRecord();
        minimaxModel.setProviderId("prov-minimax");
        minimaxModel.setName("MiniMax-M3.1-Flash-Preview");
        when(providerMapper.selectAll()).thenReturn(List.of(minimax));
        when(modelMapper.selectAll()).thenReturn(List.of(minimaxModel));

        boolean seeded = newSeeder().seedIfFresh();

        assertThat(seeded).as("已有任何 provider 即老库，绝不执行完整预设").isFalse();
        verify(providerMapper, never()).insertSelective(any());
        verify(modelMapper, never()).insertSelective(any());
        verify(settingsMapper, never()).update(any());
        verify(settingsMapper, never()).insertSelective(any());
    }

    @Test
    @DisplayName("老库只有 deepseek → 只补 minimax provider + 模型，不写 key，不动 settings")
    void existingDeepseek_addsMinimaxWithoutKey() throws Exception {
        ProviderRecord deepseek = new ProviderRecord();
        deepseek.setId("prov-deepseek");
        deepseek.setName("deepseek");
        when(providerMapper.selectAll()).thenReturn(List.of(deepseek));
        when(modelMapper.selectAll()).thenReturn(List.of());

        boolean seeded = newSeeder().seedIfFresh();

        assertThat(seeded).as("老库仍不执行完整出厂预设").isFalse();

        ArgumentCaptor<ProviderRecord> providerCaptor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper).insertSelective(providerCaptor.capture());
        ProviderRecord minimax = providerCaptor.getValue();
        assertThat(minimax.getName()).isEqualTo("minimax");
        assertThat(minimax.getBaseUrl()).isEqualTo("https://api.minimax.cn/v1");
        assertThat(minimax.getApiKeyMasked()).as("已有库补全不写 key").isEmpty();
        assertThat(minimax.getApiKeyEncrypted()).as("已有库补全不写 key").isNull();

        ArgumentCaptor<ModelRecord> modelCaptor = ArgumentCaptor.forClass(ModelRecord.class);
        verify(modelMapper).insertSelective(modelCaptor.capture());
        ModelRecord model = modelCaptor.getValue();
        assertThat(model.getProviderId()).isEqualTo(minimax.getId());
        assertThat(model.getName()).isEqualTo("MiniMax-M3.1-Flash-Preview");
        assertThat(model.getMaxContextTokens()).isEqualTo(1048576);

        verify(settingsMapper, never()).update(any());
        verify(settingsMapper, never()).insertSelective(any());
    }

    @Test
    @DisplayName("出厂资源内容：minimax + deepseek、4 models、9 档位指向 minimax、settings 键 ⊆ 白名单且无密钥")
    void presetResource_shapeAndSafeKeys() throws Exception {
        JsonNode root = readPreset(objectMapper);

        JsonNode providers = root.path("providers");
        assertThat(providers.isArray()).isTrue();
        assertThat(providers.size()).isEqualTo(2);
        List<String> providerNames = new ArrayList<>();
        providers.forEach(p -> {
            providerNames.add(p.path("name").asText());
            // ⛔ 基础资源绝不包含秘钥（秘钥只能在 gitignored 的 local 叠加里）
            assertThat(p.has("apiKey")).as("factory-preset.json 禁止包含 apiKey").isFalse();
            assertThat(p.path("type").asText()).isEqualTo("openai_compatible");
            assertThat(p.path("enabled").asBoolean()).isTrue();
        });
        assertThat(providerNames).containsExactlyInAnyOrder("minimax", "deepseek");
        JsonNode minimax = providers.get(providerNames.indexOf("minimax"));
        JsonNode deepseek = providers.get(providerNames.indexOf("deepseek"));
        assertThat(minimax.path("baseUrl").asText()).isEqualTo("https://api.minimax.cn/v1");
        assertThat(deepseek.path("baseUrl").asText()).isEqualTo("https://api.deepseek.com");

        JsonNode models = root.path("models");
        assertThat(models.isArray()).isTrue();
        assertThat(models.size()).isEqualTo(4);
        List<String> names = new ArrayList<>();
        models.forEach(m -> names.add(m.path("name").asText()));
        assertThat(names).containsExactlyInAnyOrder(
            "MiniMax-M3.1-Flash-Preview",
            "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp");
        models.forEach(m -> {
            assertThat(m.path("enabled").asBoolean()).as("model %s enabled", m.path("name").asText()).isTrue();
            assertThat(m.path("provider").asText())
                .as("model %s provider 必须存在", m.path("name").asText())
                .isIn("minimax", "deepseek");
        });
        JsonNode minimaxModel = null;
        JsonNode vision = null;
        for (JsonNode m : models) {
            if ("MiniMax-M3.1-Flash-Preview".equals(m.path("name").asText())) {
                minimaxModel = m;
            }
            if ("deepseek-v4-flash-vision-exp".equals(m.path("name").asText())) {
                vision = m;
            }
        }
        assertThat(minimaxModel).as("资源必须含 minimax 模型").isNotNull();
        assertThat(minimaxModel.path("provider").asText()).isEqualTo("minimax");
        assertThat(minimaxModel.path("type").asText()).as("MiniMax 模型 type 照用户库实际（multimodal）").isEqualTo("multimodal");
        assertThat(minimaxModel.path("maxContextTokens").asInt())
            .as("1M 窗口必须随出厂预设下发（否则上下文解析按 200k 兜底）").isEqualTo(1048576);
        assertThat(vision).as("资源必须含 deepseek-v4-flash-vision-exp").isNotNull();
        assertThat(vision.path("type").asText()).as("vision 模型 type 照用户库实际（multimodal）").isEqualTo("multimodal");

        JsonNode settings = root.path("settings");
        assertThat(settings.isObject()).isTrue();
        List<String> keys = new ArrayList<>();
        settings.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).as("不预设含 api_key 的密钥列").noneMatch(k -> k.contains("api_key"));
        assertThat(keys).as("出厂资源 settings 键必须 ⊆ 白名单（防漂移被静默跳过）")
            .allMatch(FactoryPresetSeeder.supportedSettingsKeys()::contains);
        assertThat(settings.path("permission_mode").asText()).isEqualTo("default");
        // 9 个模型档位全部指向 minimax（2026-10-26 用户裁定）
        for (String tier : List.of("main_model_name", "fast_model_name", "weak_model_name",
            "medium_model_name", "strong_model_name", "subagent_model_name",
            "fallback_model_name", "multimodal_model_name", "classifier_model")) {
            assertThat(settings.path(tier).asText())
                .as("档位 %s 必须指向 minimax", tier)
                .isEqualTo("minimax/MiniMax-M3.1-Flash-Preview");
        }
    }
}
