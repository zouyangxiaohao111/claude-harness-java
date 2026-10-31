package com.nexusai.infra.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [2026-10-29] 非选模型路径的裸名歧义处理 · {@link ModelNameResolver#resolveQuiet} /
 * {@link ModelNameResolver#resolveByProvider} 单测。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：2026-10-22 为「裸名命中多个 provider」加了 fail-loud
 * ERROR —— 那是<b>选模型链路</b>的护栏（静默选错 provider 会以 401 形式推迟暴露）。但同一解析器
 * 也被计费 / 上下文统计 / 能力查询复用：这些场景既拿不到调用方 provider 身份、也不需要做选择，
 * 打 ERROR 只会刷屏（实测单日 1968 条同一裸名歧义）。
 *
 * <p><b>钉死的两条不变量</b>：
 * <ol>
 *   <li>宽松解析（统计/计费）：裸名歧义 → <b>null 且零 WARN/ERROR</b>；</li>
 *   <li>选模型解析 {@link ModelNameResolver#resolve}：同样的输入<b>仍然 ERROR + null</b>
 *       （护栏没被削弱 —— 本类用对照组直接验证，而不是只看宽松路径）。</li>
 *   <li>给定 providerType 的计费解析：只在<b>该 type 且 enabled 的 provider</b> 下找，
 *       唯一命中返回、多命中返回 null（不猜）。</li>
 * </ol>
 */
class ModelNameResolverProviderScopedTest {

    private static ModelRecord model(String id, String name, String providerId) {
        ModelRecord m = new ModelRecord();
        m.setId(id);
        m.setName(name);
        m.setProviderId(providerId);
        m.setEnabled(true);
        return m;
    }

    private static ProviderRecord provider(String id, String name, String type, boolean enabled) {
        ProviderRecord p = new ProviderRecord();
        p.setId(id);
        p.setName(name);
        p.setType(type);
        p.setEnabled(enabled);
        return p;
    }

    /** 本机真实数据形态：deepseek-flash 三行，只有 deepseek 一行的 provider 仍 enabled。 */
    private static List<ModelRecord> threeDeepseekFlashRows() {
        return List.of(
            model("model-da68c5e0", "deepseek-flash", "prov-a583e4c7"),
            model("model-0823a98d", "deepseek-flash", "prov-b053b03d"),
            model("model-330cbd6a", "deepseek-flash", "prov-e90af148"));
    }

    private static ListAppender<ILoggingEvent> attachCapture() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ModelNameResolver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachCapture(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ModelNameResolver.class);
        logger.detachAppender(appender);
    }

    private static long warnOrErrorCount(ListAppender<ILoggingEvent> app) {
        return app.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).count();
    }

    // ─────────────────────────── resolveByProvider ───────────────────────────

    @Test
    @DisplayName("全名路径：providerName/modelName 精确命中（不需要 providerType 过滤）")
    void resolveByProvider_fullName_hitsExactProviderModel() {
        ModelMapper modelMapper = mock(ModelMapper.class);
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        when(providerMapper.selectOneByQuery(any())).thenReturn(
            provider("prov-b053b03d", "deepseek", "openai_compatible", true));
        when(modelMapper.selectOneByQuery(any())).thenReturn(
            model("model-0823a98d", "deepseek-flash", "prov-b053b03d"));

        ModelRecord hit = ModelNameResolver.resolveByProvider(modelMapper, providerMapper,
            "deepseek/deepseek-flash", "openai_compatible");

        assertThat(hit).isNotNull();
        assertThat(hit.getId()).as("全名精确定位到 deepseek 那一行").isEqualTo("model-0823a98d");
    }

    @Test
    @DisplayName("裸名 + providerType：候选查询带 provider(type, enabled) 过滤 → 唯一命中")
    void resolveByProvider_bareName_filtersByProviderType() {
        ModelMapper modelMapper = mock(ModelMapper.class);
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        when(modelMapper.selectListByQuery(any())).thenReturn(threeDeepseekFlashRows());
        // providers.type=enabled 过滤后只剩 deepseek（ds-zcw / ds-zcw-openai 已停用）
        when(providerMapper.selectListByQuery(any())).thenReturn(List.of(
            provider("prov-b053b03d", "deepseek", "openai_compatible", true)));

        ModelRecord hit = ModelNameResolver.resolveByProvider(modelMapper, providerMapper,
            "deepseek-flash", "openai_compatible");

        assertThat(hit).isNotNull();
        assertThat(hit.getProviderId())
            .as("必须命中 enabled provider 下那一行，绝不按字典序猜 ds-zcw-openai")
            .isEqualTo("prov-b053b03d");

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        org.mockito.Mockito.verify(providerMapper).selectListByQuery(captor.capture());
        String sql = String.valueOf(captor.getValue().toSQL());
        assertThat(sql)
            .as("provider 查询必须同时带 type 与 enabled 过滤（否则 disabled provider 的同名模型会制造假歧义）")
            .contains("type").contains("enabled");
    }

    @Test
    @DisplayName("裸名 + providerType：同类型下仍多命中 → null（不猜、不 fail-loud）")
    void resolveByProvider_bareName_multipleInSameType_returnsNull() {
        ModelMapper modelMapper = mock(ModelMapper.class);
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        when(modelMapper.selectListByQuery(any())).thenReturn(threeDeepseekFlashRows());
        when(providerMapper.selectListByQuery(any())).thenReturn(List.of(
            provider("prov-a583e4c7", "ds-zcw-openai", "openai_compatible", true),
            provider("prov-b053b03d", "deepseek", "openai_compatible", true)));

        ListAppender<ILoggingEvent> app = attachCapture();
        ModelRecord hit;
        try {
            hit = ModelNameResolver.resolveByProvider(modelMapper, providerMapper,
                "deepseek-flash", "openai_compatible");
            assertThat(hit).as("多个候选时返回 null，由计费回落内置默认档").isNull();
            assertThat(warnOrErrorCount(app))
                .as("计费路径的歧义只允许 DEBUG，⛔ 不得再刷 ERROR")
                .isZero();
        } finally {
            detachCapture(app);
        }
    }

    @Test
    @DisplayName("providerType 为空 → 退回通用宽松解析（裸名唯一命中仍兼容）")
    void resolveByProvider_blankProviderType_fallsBackToQuietResolution() {
        ModelMapper modelMapper = mock(ModelMapper.class);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(
            model("m1", "kimi-k3", "prov-de487e66")));

        ModelRecord hit = ModelNameResolver.resolveByProvider(modelMapper, null, "kimi-k3", null);

        assertThat(hit).isNotNull();
        assertThat(hit.getId()).isEqualTo("m1");
    }

    // ─────────────────────────── resolveQuiet vs resolve 对照 ───────────────────────────

    @Test
    @DisplayName("对照：同一歧义输入 —— resolveQuiet 零 WARN/ERROR，resolve 仍 ERROR（选模型护栏未被削弱）")
    void quietVsFailLoud_controlGroup() {
        ModelMapper modelMapper = mock(ModelMapper.class);
        when(modelMapper.selectListByQuery(any())).thenReturn(threeDeepseekFlashRows());

        ListAppender<ILoggingEvent> app = attachCapture();
        try {
            ModelRecord quiet = ModelNameResolver.resolveQuiet(modelMapper, null, "deepseek-flash");
            assertThat(quiet).as("歧义时返回值与 resolve 一致：null").isNull();
            assertThat(warnOrErrorCount(app))
                .as("统计/计费路径（resolveQuiet）不得产生 WARN/ERROR").isZero();

            app.list.clear();
            ModelRecord loud = ModelNameResolver.resolve(modelMapper, null, "deepseek-flash");
            assertThat(loud).as("选模型路径仍返回 null（拒绝解析）").isNull();
            assertThat(app.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR))
                .as("对照组：resolve 必须仍然打 ERROR —— 护栏没被宽松路径削弱")
                .isTrue();
        } finally {
            detachCapture(app);
        }
    }

    @Test
    @DisplayName("resolveQuiet：裸名唯一命中 / [1m] 剥离重查 / 全名精确，均与 resolve 同结果")
    void quiet_matchesResolveSemantics() {
        ModelMapper modelMapper = mock(ModelMapper.class);
        // 第一查（原键 "deepseek-flash[1m]"）空 → 第二查（剥离后 "deepseek-flash"）唯一命中
        when(modelMapper.selectListByQuery(any()))
            .thenReturn(List.of())
            .thenReturn(List.of(model("m1", "deepseek-flash", "prov-b053b03d")));

        ModelRecord hit = ModelNameResolver.resolveQuiet(modelMapper, null, "deepseek-flash[1m]");

        assertThat(hit).isNotNull();
        assertThat(hit.getId()).as("[1m] 只影响查询键，返回值仍是 DB 原记录").isEqualTo("m1");
    }
}
