package com.nexusai.application.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FactoryPresetSeeder} 聚焦单测（Mockito 轻量，不起 Spring 上下文）。
 *
 * <p><b>WHY（规则九 · 测试验证意图 + 2026-11-20 安全止血）</b>：
 * <ol>
 *   <li><b>默认关闭</b>：{@code @ConditionalOnProperty(matchIfMissing=false)}，不显式开启就不种入。</li>
 *   <li><b>全新库（providers 空）才写</b>：minimax + deepseek 两个 provider、4 条模型、settings merge；
 *       <b>两个 provider 都没有 key</b>（即使本地叠加文件写了 apiKey 也不采纳）。</li>
 *   <li><b>老库（providers 非空）跳过完整预设</b>：零覆盖。</li>
 *   <li><b>本地叠加只含非秘钥字段</b>：优先 configHome、其次环境变量；apiKey 一律忽略。</li>
 *   <li><b>预设文件缺失不抛异常</b>：按无叠加继续。</li>
 *   <li><b>classpath 基础资源内容</b>：providers = minimax + deepseek、models 4 条、9 个档位指向
 *       minimax、settings 键 ⊆ 白名单且<b>不含任何密钥</b> —— 资源漂移即测试失败。</li>
 * </ol>
 */
class FactoryPresetSeederTest {

    /** 测试叠加里的假秘钥（⛔ 非真实秘钥；只用于断言「写了也必须被忽略」）。 */
    private static final String TEST_OVERLAY_KEY = "sk-test-only-1234567890";

    @TempDir
    Path tempDir;

    private final ProviderMapper providerMapper = mock(ProviderMapper.class);
    private final ModelMapper modelMapper = mock(ModelMapper.class);
    private final SettingsMapper settingsMapper = mock(SettingsMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private FactoryPresetSeeder newSeeder() {
        return newSeeder(tempDir, null);
    }

    private FactoryPresetSeeder newSeeder(Path configHome, String envPresetValue) {
        return new FactoryPresetSeeder(providerMapper, modelMapper, settingsMapper, objectMapper,
            configHome, envPresetValue);
    }

    private static JsonNode readPreset(ObjectMapper om) throws Exception {
        try (InputStream in = FactoryPresetSeeder.class.getClassLoader()
                .getResourceAsStream(FactoryPresetSeeder.RESOURCE_PATH)) {
            assertThat(in).as("classpath %s 必须存在（随 jar 分发）", FactoryPresetSeeder.RESOURCE_PATH).isNotNull();
            return om.readTree(in);
        }
    }

    @Test
    @DisplayName("默认关闭：@ConditionalOnProperty matchIfMissing=false，必须显式 enabled=true")
    void defaultDisabled_unlessExplicitlyEnabled() {
        ConditionalOnProperty condition =
            FactoryPresetSeeder.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).as("必须保留条件装配注解").isNotNull();
        assertThat(condition.prefix()).isEqualTo("nexusai.factory.preset");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing())
            .as("⛔ 缺省必须关闭：空库启动不得自动种入可用 provider")
            .isFalse();
    }

    @Test
    @DisplayName("全新库（providers 空）→ minimax + deepseek、4 models、9 档位；provider 全部无 key")
    void freshDb_seedsProviderModelsAndSettings() throws Exception {
        when(providerMapper.selectAll()).thenReturn(List.of());
        when(settingsMapper.update(any())).thenReturn(1);

        boolean seeded = newSeeder().seedIfFresh();

        assertThat(seeded).as("providers 空库应执行预设写入").isTrue();

        // —— providers：minimax + deepseek；无叠加 ⇒ 两者都无 key ——
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
            assertThat(p.getApiKeyHash()).as("%s hash 空串占位", p.getName()).isEmpty();
            assertThat(p.getApiKeyMasked()).as("%s masked 空串 → hasKey=false", p.getName()).isEmpty();
            assertThat(p.getApiKeyEncrypted()).as("%s 绝不写密钥密文", p.getName()).isNull();
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
        assertThat(minimaxModel.getMaxTokens()).isEqualTo(262144);
        assertThat(minimaxModel.getMaxContextTokens()).isEqualTo(1048576);
        assertThat(models).allMatch(m -> Boolean.TRUE.equals(m.getEnabled()));

        // —— settings：permission_mode=default；9 个档位全部指向 minimax；api_key 不预设 ——
        ArgumentCaptor<SettingsRecord> settingsCaptor = ArgumentCaptor.forClass(SettingsRecord.class);
        verify(settingsMapper).update(settingsCaptor.capture());
        SettingsRecord s = settingsCaptor.getValue();
        assertThat(s.getPermissionMode()).isEqualTo("default");
        assertThat(s.getAutoCompactWindow()).isEqualTo(1000000);
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
    @DisplayName("configHome 叠加写了 apiKey：baseUrl 生效但 apiKey 必须被忽略（种入仍无 key）")
    void configHomeOverlay_keyIgnoredBaseUrlApplied() throws Exception {
        Path overlay = tempDir.resolve(FactoryPresetSeeder.CONFIG_HOME_OVERLAY_RELATIVE);
        Files.createDirectories(overlay.getParent());
        Files.writeString(overlay,
            "{\"providers\":[{\"name\":\"minimax\",\"apiKey\":\"" + TEST_OVERLAY_KEY
                + "\",\"baseUrl\":\"https://overlay.example/v1\"}]}",
            StandardCharsets.UTF_8);
        when(providerMapper.selectAll()).thenReturn(List.of());
        when(settingsMapper.update(any())).thenReturn(1);

        assertThat(newSeeder(tempDir, null).seedIfFresh()).isTrue();

        ArgumentCaptor<ProviderRecord> captor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper, times(2)).insertSelective(captor.capture());
        ProviderRecord minimax = captor.getAllValues().stream()
            .filter(p -> "minimax".equals(p.getName())).findFirst().orElseThrow();
        assertThat(minimax.getBaseUrl()).as("非秘钥字段允许叠加").isEqualTo("https://overlay.example/v1");
        assertThat(minimax.getApiKeyHash()).as("⛔ apiKey 绝不注入").isEmpty();
        assertThat(minimax.getApiKeyMasked()).as("⛔ apiKey 绝不注入").isEmpty();
        assertThat(minimax.getApiKeyEncrypted()).as("⛔ apiKey 绝不落库").isNull();
    }

    @Test
    @DisplayName("环境变量 NEXUSAI_PROVIDER_PRESET 的 JSON：非秘钥字段生效，apiKey 忽略")
    void envPresetJson_keyIgnored() {
        when(providerMapper.selectAll()).thenReturn(List.of());
        when(settingsMapper.update(any())).thenReturn(1);
        String env = "{\"providers\":[{\"name\":\"deepseek\",\"apiKey\":\"" + TEST_OVERLAY_KEY
            + "\",\"baseUrl\":\"https://env.example\"}]}";

        assertThat(newSeeder(tempDir, env).seedIfFresh()).isTrue();

        ArgumentCaptor<ProviderRecord> captor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper, times(2)).insertSelective(captor.capture());
        ProviderRecord deepseek = captor.getAllValues().stream()
            .filter(p -> "deepseek".equals(p.getName())).findFirst().orElseThrow();
        assertThat(deepseek.getBaseUrl()).isEqualTo("https://env.example");
        assertThat(deepseek.getApiKeyMasked()).isEmpty();
        assertThat(deepseek.getApiKeyEncrypted()).isNull();
    }

    @Test
    @DisplayName("本地叠加只按 name 覆盖非秘钥字段：未知 provider 不新增、apiKey 丢弃、不改入参")
    void localOverlay_onlyPatchesExistingProvider() throws Exception {
        JsonNode base = objectMapper.readTree(
            "[{\"name\":\"minimax\"},{\"name\":\"deepseek\"}]");
        JsonNode overlay = objectMapper.readTree(
            "{\"providers\":[{\"name\":\"minimax\",\"apiKey\":\"k1\",\"baseUrl\":\"https://x\"},"
                + "{\"name\":\"unknown\",\"apiKey\":\"k2\"},{\"name\":\"deepseek\"}]}");

        JsonNode merged = FactoryPresetSeeder.applyLocalOverlay(base, overlay);

        assertThat(merged.size()).as("不允许本地新增 provider").isEqualTo(2);
        assertThat(merged.get(0).path("baseUrl").asText()).isEqualTo("https://x");
        assertThat(merged.get(0).has("apiKey")).as("⛔ apiKey 绝不被合并").isFalse();
        assertThat(merged.get(1).has("apiKey")).as("缺少 apiKey 的条目跳过").isFalse();
        assertThat(base.get(0).has("apiKey")).as("不得修改入参").isFalse();
    }

    @Test
    @DisplayName("预设文件缺失（configHome 与 env 都没有）→ 正常工作、不抛异常")
    void missingOverlay_doesNotThrow() {
        when(providerMapper.selectAll()).thenReturn(List.of());
        when(settingsMapper.update(any())).thenReturn(1);

        assertThatCode(() -> {
            boolean seeded = newSeeder(tempDir.resolve("no-such-home"), null).seedIfFresh();
            assertThat(seeded).isTrue();
        }).doesNotThrowAnyException();
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
            // ⛔ 基础资源绝不包含秘钥
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
        assertThat(minimaxModel.path("type").asText()).isEqualTo("multimodal");
        assertThat(minimaxModel.path("maxContextTokens").asInt())
            .as("1M 窗口必须随出厂预设下发").isEqualTo(1048576);
        assertThat(vision).as("资源必须含 deepseek-v4-flash-vision-exp").isNotNull();
        assertThat(vision.path("type").asText()).isEqualTo("multimodal");

        JsonNode settings = root.path("settings");
        assertThat(settings.isObject()).isTrue();
        List<String> keys = new ArrayList<>();
        settings.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).as("不预设含 api_key 的密钥列").noneMatch(k -> k.contains("api_key"));
        assertThat(keys).as("出厂资源 settings 键必须 ⊆ 白名单")
            .allMatch(FactoryPresetSeeder.supportedSettingsKeys()::contains);
        assertThat(settings.path("permission_mode").asText()).isEqualTo("default");
        for (String tier : List.of("main_model_name", "fast_model_name", "weak_model_name",
            "medium_model_name", "strong_model_name", "subagent_model_name",
            "fallback_model_name", "multimodal_model_name", "classifier_model")) {
            assertThat(settings.path(tier).asText())
                .as("档位 %s 必须指向 minimax", tier)
                .isEqualTo("minimax/MiniMax-M3.1-Flash-Preview");
        }
    }
}
