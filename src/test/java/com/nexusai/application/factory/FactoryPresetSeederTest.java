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
 *   <li><b>全新库（providers 空）才写</b>：provider 1 条（无 key）+ models 3 条 + settings merge。</li>
 *   <li><b>老库（providers 非空）跳过</b>：零写入、零覆盖。</li>
 *   <li><b>settings 只 merge JSON 键</b>：permission_mode 强制 default、10 档 *_model_name 与
 *       classifier_model 全 null（模型档位不预设）、api_key 不预设。</li>
 *   <li><b>classpath 资源内容</b>：providers 恰为 deepseek、models 3 条、settings 键 ⊆ 白名单且不含
 *       模型档位/密钥列 —— 出厂资源漂移即测试失败。</li>
 * </ol>
 */
class FactoryPresetSeederTest {

    private final ProviderMapper providerMapper = mock(ProviderMapper.class);
    private final ModelMapper modelMapper = mock(ModelMapper.class);
    private final SettingsMapper settingsMapper = mock(SettingsMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private FactoryPresetSeeder newSeeder() {
        return new FactoryPresetSeeder(providerMapper, modelMapper, settingsMapper, objectMapper);
    }

    private static JsonNode readPreset(ObjectMapper om) throws Exception {
        try (InputStream in = FactoryPresetSeeder.class.getClassLoader()
                .getResourceAsStream(FactoryPresetSeeder.RESOURCE_PATH)) {
            assertThat(in).as("classpath %s 必须存在（随 jar 分发）", FactoryPresetSeeder.RESOURCE_PATH).isNotNull();
            return om.readTree(in);
        }
    }

    @Test
    @DisplayName("全新库（providers 空）→ 写 deepseek 无 key provider + 3 models + settings merge")
    void freshDb_seedsProviderModelsAndSettings() throws Exception {
        when(providerMapper.selectAll()).thenReturn(List.of());
        when(settingsMapper.update(any())).thenReturn(1);

        boolean seeded = newSeeder().seedIfFresh();

        assertThat(seeded).as("providers 空库应执行预设写入").isTrue();

        // —— provider：deepseek，openai_compatible，baseUrl 正确，enabled；空 key 语义 ——
        ArgumentCaptor<ProviderRecord> providerCaptor = ArgumentCaptor.forClass(ProviderRecord.class);
        verify(providerMapper).insertSelective(providerCaptor.capture());
        ProviderRecord provider = providerCaptor.getValue();
        assertThat(provider.getName()).isEqualTo("deepseek");
        assertThat(provider.getType()).isEqualTo("openai_compatible");
        assertThat(provider.getBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(provider.getEnabled()).isTrue();
        assertThat(provider.getApiKeyHash()).as("providers.api_key_hash NOT NULL 用空串占位").isEmpty();
        assertThat(provider.getApiKeyMasked()).as("空 masked → hasKey=false（无 key）").isEmpty();
        assertThat(provider.getApiKeyEncrypted()).as("出厂预设绝不写任何密钥密文").isNull();

        // —— models：3 条挂该 provider ——
        verify(modelMapper, times(3)).insertSelective(any(ModelRecord.class));
        ArgumentCaptor<ModelRecord> modelCaptor = ArgumentCaptor.forClass(ModelRecord.class);
        verify(modelMapper, times(3)).insertSelective(modelCaptor.capture());
        List<String> modelNames = modelCaptor.getAllValues().stream().map(ModelRecord::getName).toList();
        assertThat(modelNames).containsExactlyInAnyOrder(
            "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp");
        assertThat(modelCaptor.getAllValues())
            .allMatch(m -> provider.getId().equals(m.getProviderId()))
            .allMatch(m -> "DS".equals(m.getTag()))
            .allMatch(m -> Boolean.TRUE.equals(m.getEnabled()));

        // —— settings：只 merge JSON 键；permission_mode=default；模型档位/密钥列不预设 ——
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
        // 10 档 *_model_name + classifier_model + api_key 全部不预设（null）
        assertThat(s.getMainModelName()).isNull();
        assertThat(s.getFastModelName()).isNull();
        assertThat(s.getWeakModelName()).isNull();
        assertThat(s.getMediumModelName()).isNull();
        assertThat(s.getStrongModelName()).isNull();
        assertThat(s.getSubagentModelName()).isNull();
        assertThat(s.getFallbackModelName()).isNull();
        assertThat(s.getMultimodalModelName()).isNull();
        assertThat(s.getTtsModelName()).isNull();
        assertThat(s.getAsrModelName()).isNull();
        assertThat(s.getClassifierModel()).isNull();
        assertThat(s.getApiKey()).isNull();
    }

    @Test
    @DisplayName("老库（providers 非空）→ 跳过预设，零写入零覆盖")
    void nonEmptyProviders_skipsPreset() throws Exception {
        when(providerMapper.selectAll()).thenReturn(List.of(new ProviderRecord()));

        boolean seeded = newSeeder().seedIfFresh();

        assertThat(seeded).as("已有任何 provider 即老库，绝不执行预设").isFalse();
        verify(providerMapper, never()).insertSelective(any());
        verify(modelMapper, never()).insertSelective(any());
        verify(settingsMapper, never()).update(any());
        verify(settingsMapper, never()).insertSelective(any());
    }

    @Test
    @DisplayName("出厂资源内容：deepseek provider + 3 models + settings 键 ⊆ 白名单且不含模型档位/密钥")
    void presetResource_shapeAndSafeKeys() throws Exception {
        JsonNode root = readPreset(objectMapper);

        JsonNode providers = root.path("providers");
        assertThat(providers.isArray()).isTrue();
        assertThat(providers.size()).isEqualTo(1);
        JsonNode provider = providers.get(0);
        assertThat(provider.path("name").asText()).isEqualTo("deepseek");
        assertThat(provider.path("type").asText()).isEqualTo("openai_compatible");
        assertThat(provider.path("baseUrl").asText()).isEqualTo("https://api.deepseek.com");
        assertThat(provider.path("enabled").asBoolean()).isTrue();

        JsonNode models = root.path("models");
        assertThat(models.isArray()).isTrue();
        assertThat(models.size()).isEqualTo(3);
        List<String> names = new ArrayList<>();
        models.forEach(m -> names.add(m.path("name").asText()));
        assertThat(names).containsExactlyInAnyOrder(
            "deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp");
        // 每模型 tag=DS、enabled；type 照用户库实际（vision-exp 为 multimodal，非 spec 示例的 chat）
        models.forEach(m -> {
            assertThat(m.path("tag").asText()).as("model %s tag=DS", m.path("name").asText()).isEqualTo("DS");
            assertThat(m.path("enabled").asBoolean()).as("model %s enabled", m.path("name").asText()).isTrue();
        });
        JsonNode vision = null;
        for (JsonNode m : models) {
            if ("deepseek-v4-flash-vision-exp".equals(m.path("name").asText())) {
                vision = m;
            }
        }
        assertThat(vision).as("资源必须含 deepseek-v4-flash-vision-exp").isNotNull();
        assertThat(vision.path("type").asText()).as("vision 模型 type 照用户库实际（multimodal）").isEqualTo("multimodal");

        JsonNode settings = root.path("settings");
        assertThat(settings.isObject()).isTrue();
        List<String> keys = new ArrayList<>();
        settings.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).as("不预设任何模型档位列").noneMatch(k -> k.endsWith("_model_name"));
        assertThat(keys).as("不预设 classifier_model").doesNotContain("classifier_model");
        assertThat(keys).as("不预设含 api_key 的密钥列").noneMatch(k -> k.contains("api_key"));
        assertThat(keys).as("出厂资源 settings 键必须 ⊆ 白名单（防漂移被静默跳过）")
            .allMatch(FactoryPresetSeeder.supportedSettingsKeys()::contains);
        assertThat(settings.path("permission_mode").asText()).isEqualTo("default");
    }
}
