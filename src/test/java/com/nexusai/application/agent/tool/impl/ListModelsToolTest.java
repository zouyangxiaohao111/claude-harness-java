package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ListModels 契约：可用/不可用分组、类型白名单、provider 停用、档位绑定与 notes、能力门。
 */
@DisplayName("ListModels · 契约")
class ListModelsToolTest {

    private ListModelsTool tool;
    private ModelMapper modelMapper;
    private ProviderMapper providerMapper;
    private SettingsMapper settingsMapper;
    private ModelRecord flash;

    @BeforeEach
    void setUp() {
        modelMapper = mock(ModelMapper.class);
        providerMapper = mock(ProviderMapper.class);
        settingsMapper = mock(SettingsMapper.class);

        ProviderRecord deepseek = provider("prov-ds", "deepseek", true);
        ProviderRecord xm = provider("prov-xm", "xm", false);
        when(providerMapper.selectAll()).thenReturn(List.of(deepseek, xm));

        flash = model("model-flash", "prov-ds", "deepseek-flash", "multimodal", "DS", 1_048_576);
        ModelRecord embed = model("model-embed", "prov-ds", "text-embedding-v4", "embedding", "DS", 8_192);
        ModelRecord xmModel = model("model-xm", "prov-xm", "mimo-v2.6-pro", "multimodal", "DS", 1_048_576);
        when(modelMapper.selectAll()).thenReturn(List.of(flash, embed, xmModel));

        SettingsRecord settings = new SettingsRecord();
        settings.setMainModelName("deepseek/deepseek-flash");
        settings.setFastModelName("deepseek/deepseek-flash");
        settings.setWeakModelName("bad/unknown");
        when(settingsMapper.selectOneById(1)).thenReturn(settings);

        tool = new ListModelsTool();
        tool.setModelMapper(modelMapper);
        tool.setProviderMapper(providerMapper);
        tool.setSettingsMapper(settingsMapper);
        // 测试直接给「档位值 → 模型行」，不依赖 MyBatis 查询条件；生产走 ModelNameResolver。
        tool.setTierModelResolverForTest(raw ->
            "deepseek/deepseek-flash".equals(raw) ? flash : null);
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
        System.clearProperty("nexusai.feature.messaging-beyond-own-agents");
    }

    @Test
    @DisplayName("可用组只含对话模型 + provider/modelName；embedding 与停用 provider 进不可用组")
    void groupsModelsByUsability() {
        String out = run("{}");

        assertThat(out).contains("Available session models");
        assertThat(out).contains("deepseek/deepseek-flash");
        assertThat(out).contains("[default]");
        assertThat(out).contains("tiers: main, fast");
        assertThat(out).contains("Other models not usable for sessions: 2");
        // 不可用模型默认不逐条展开
        assertThat(out).doesNotContain("text-embedding-v4");
        assertThat(out).doesNotContain("mimo-v2.6-pro");
        // 解析不了的档位必须留痕，不静默吞掉
        assertThat(out).contains("tier \"weak\" could not be resolved: bad/unknown");
    }

    @Test
    @DisplayName("include_unavailable=true：逐条列出原因（类型不符 / provider 停用）")
    void includeUnavailableListsReasons() {
        String out = run("{\"include_unavailable\":true}");

        assertThat(out).contains("text-embedding-v4 (embedding) — not conversation-capable");
        assertThat(out).contains("mimo-v2.6-pro (multimodal) — provider disabled");
    }

    @Test
    @DisplayName("能力门跟随跨会话投递开关（与 SessionCreate 同门）")
    void gate() {
        TaskSystemConfig.clearForTest();
        System.setProperty("nexusai.feature.messaging-beyond-own-agents", "false");
        assertThat(tool.isEnabled()).isFalse();
        System.setProperty("nexusai.feature.messaging-beyond-own-agents", "true");
        assertThat(tool.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("mapper 未接线 → 明确报错，不伪造空清单")
    void notWired_returnsError() {
        ListModelsTool bare = new ListModelsTool();
        String out = (String) bare.execute(call("{}"), null).data();
        assertThat(out).contains("not available");
    }

    // ─────────────────────────── fixtures ───────────────────────────

    private String run(String json) {
        return (String) tool.execute(call(json), null).data();
    }

    private static ToolUseBlock call(String json) {
        return new ToolUseBlock(UUID.randomUUID().toString(), ListModelsTool.NAME, readJson(json));
    }

    private static JsonNode readJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProviderRecord provider(String id, String name, boolean enabled) {
        ProviderRecord p = new ProviderRecord();
        p.setId(id);
        p.setName(name);
        p.setEnabled(enabled);
        return p;
    }

    private static ModelRecord model(String id, String providerId, String name,
                                     String type, String tag, Integer maxContextTokens) {
        ModelRecord m = new ModelRecord();
        m.setId(id);
        m.setProviderId(providerId);
        m.setName(name);
        m.setType(type);
        m.setTag(tag);
        m.setEnabled(true);
        m.setMaxContextTokens(maxContextTokens);
        return m;
    }
}
