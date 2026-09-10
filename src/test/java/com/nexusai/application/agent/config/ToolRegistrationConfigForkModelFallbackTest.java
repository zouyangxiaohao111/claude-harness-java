package com.nexusai.application.agent.config;

import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [fork 模型兜底 2026-09-10] settings.json 无 {@code model} 键 → 回落 DB
 * {@code settings.main_model_name}，否则 fork 的 modelSupplier 恒 null →
 * {@code ProviderConfig.empty()} → provider 回落 {@code MockLlmProvider}（假回复/永不 Edit）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：生产实测 {@code ~/.nexusai/settings.json} 仅
 * {@code {"autoMemoryEnabled":true}}（无 model 键），而 DB {@code settings.main_model_name} /
 * {@code sessions.model_name} 都有值 —— 文件是<b>部分</b>配置源，缺失不应等同"无模型"。
 * 本测试锁定：(a) DB 兜底取值语义（空/异常 → null 不炸）；(b) 文件源优先于 DB；
 * (c) 生产 bean 的 modelSupplier 在文件缺失时确实取到 DB 主模型（非 null）。
 */
@DisplayName("[fork 模型兜底] settings.json 缺 model → DB settings.main_model_name 兜底")
class ToolRegistrationConfigForkModelFallbackTest {

    private static final String DB_MAIN_MODEL = "deepseek/deepseek-flash";

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · readDbMainModelName（DB 读语义，失败一律 null 不炸）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DB 读: mapper=null / 行缺失 / 空值 / 抛异常 → null（调用方保持旧行为，不炸 fork 线程）")
    void readDbMainModelName_nullSafeAndFailSoft() {
        assertThat(ToolRegistrationConfig.readDbMainModelName(null)).isNull();

        SettingsMapper missing = Mockito.mock(SettingsMapper.class);
        Mockito.when(missing.selectOneById(1)).thenReturn(null);
        assertThat(ToolRegistrationConfig.readDbMainModelName(missing)).isNull();

        SettingsMapper blank = Mockito.mock(SettingsMapper.class);
        SettingsRecord blankRec = new SettingsRecord();
        blankRec.setMainModelName("   ");
        Mockito.when(blank.selectOneById(1)).thenReturn(blankRec);
        assertThat(ToolRegistrationConfig.readDbMainModelName(blank)).isNull();

        SettingsMapper throwing = Mockito.mock(SettingsMapper.class);
        Mockito.when(throwing.selectOneById(1)).thenThrow(new IllegalStateException("db down"));
        assertThat(ToolRegistrationConfig.readDbMainModelName(throwing))
            .as("DB 读失败必须回落 null（fork 线程不得因读配置崩溃）").isNull();
    }

    @Test
    @DisplayName("DB 读: settings.main_model_name 有值 → 原样返回（全名/裸名由 ModelNameResolver 解析）")
    void readDbMainModelName_returnsValue() {
        SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
        SettingsRecord rec = new SettingsRecord();
        rec.setMainModelName(DB_MAIN_MODEL);
        Mockito.when(mapper.selectOneById(1)).thenReturn(rec);

        assertThat(ToolRegistrationConfig.readDbMainModelName(mapper)).isEqualTo(DB_MAIN_MODEL);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · 生产 bean 的 modelSupplier（文件缺失 → DB；文件命中 → 文件优先）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("生产 productionForkedQuery: filesettings 无 model → modelSupplier 取 DB 主模型（非 null）")
    void modelSupplier_fallsBackToDbMainModel() throws Exception {
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        injectSettingsMapper(config, settingsMapperWith(DB_MAIN_MODEL));

        // configStorage=null 等价于「settings.json 无 model 键」（readSettings 路径缺失 → null）
        var query = config.productionForkedQuery(
            Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class),
            new com.nexusai.application.agent.tool.ToolRegistry(),
            Mockito.mock(ModelMapper.class), Mockito.mock(ProviderMapper.class),
            Mockito.mock(com.nexusai.domain.provider.ProviderService.class),
            null);

        assertThat(query.modelSupplier().get())
            .as("settings.json 无 model 时 modelSupplier 不得为 null（DB settings.main_model_name 兜底）")
            .isEqualTo(DB_MAIN_MODEL);
    }

    @Test
    @DisplayName("生产 productionForkedQuery: settings.json 有 model → 文件源优先（不查 DB）")
    void modelSupplier_fileSettingsWins() throws Exception {
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        // DB 值存在但不得被采用（文件源优先）
        injectSettingsMapper(config, settingsMapperWith("db-should-not-win"));

        com.nexusai.application.agent.settings.storage.FileConfigStorage fileStorage =
            Mockito.mock(com.nexusai.application.agent.settings.storage.FileConfigStorage.class);
        Mockito.when(fileStorage.readSettings(Mockito.anyList())).thenReturn("file-model");

        var query = config.productionForkedQuery(
            Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class),
            new com.nexusai.application.agent.tool.ToolRegistry(),
            Mockito.mock(ModelMapper.class), Mockito.mock(ProviderMapper.class),
            Mockito.mock(com.nexusai.domain.provider.ProviderService.class),
            fileStorage);

        assertThat(query.modelSupplier().get()).isEqualTo("file-model");
    }

    // ── helpers ──

    private static SettingsMapper settingsMapperWith(String mainModelName) {
        SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
        SettingsRecord rec = new SettingsRecord();
        rec.setMainModelName(mainModelName);
        Mockito.when(mapper.selectOneById(1)).thenReturn(rec);
        return mapper;
    }

    /** @Autowired(required=false) 字段注入（同 ToolRegistrationConfigMemoryBeansTest.readField 装配模式）。 */
    private static void injectSettingsMapper(ToolRegistrationConfig config, SettingsMapper mapper)
            throws Exception {
        Field f = ToolRegistrationConfig.class.getDeclaredField("settingsMapper");
        f.setAccessible(true);
        f.set(config, mapper);
    }
}
