package com.nexusai.application.agent.config;

import com.nexusai.application.agent.compact.fork.ProductionForkedQuery;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.llm.DynamicHeaderExpander;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.ProviderHeaderInjector;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[A · provider-hdr 2026-09-24] fork / 压缩 / away-summary 三条出口的 {@code ProviderConfig}
 * 必须带上 provider 的 {@code extraHeaders}。</b>
 *
 * <h2>被修的缺陷</h2>
 * <p>{@code ToolRegistrationConfig} 的两处构造点原为 <b>2 参便捷构造</b> ⇒ 落 {@code Map.of()} ⇒
 * {@link ProviderHeaderInjector#apply} 的空值早返回（ProviderHeaderInjector.java:102）⇒
 * <b>该出口一条自定义 header 都不发</b>。受害面 = fork 家族（SESSION_MEMORY / EXTRACT_MEMORIES /
 * AUTO_DREAM，post-sampling hook 每轮触发且用用户主模型 provider）+ 压缩摘要 + away-summary。
 * 用户症状：「配了正确的 {@code ${session_id}} 仍经常 400」——主链聊天正常，fork 每轮 400。
 *
 * <h2>为什么断言「配置链」而不是「注入链」</h2>
 * <p>本类的被测物是<b>配置构造点</b>，故钉两段：
 * <ol>
 *   <li><b>配置里带上了那条 header</b>（{@code extraHeaders} 非空且含该 key，值为<b>原始</b>
 *       {@code ${session_id}} —— 展开是注入侧的职责，配置层不得提前展开）；</li>
 *   <li><b>该配置喂进注入器后真的发出正确值</b>（{@code expandAll(..., "sess-xyz", true)} → 真会话 id；
 *       {@code injectableCount(...) == 1}）—— 只断言 (1) 会漏掉「配了但注入侧照样丢」的另一半。</li>
 * </ol>
 * <p>反向实验（本类的分辨力自证）：把任一处改回 2 参 ⇒ 断言 (1) 立刻红（{@code Map.of()} 空）。
 *
 * <h2>怎么调用「生产方法」</h2>
 * <p>两处构造点都在 {@code ToolRegistrationConfig} 内部：一处是 <b>private static</b>
 * {@code sessionForkModelRoute}（反射调用，同 {@code ToolRegistrationConfigForkModelFallbackTest}
 * 既有的「直构 + 反射装配」范式），另一处经 <b>public @Bean</b>
 * {@code productionForkedQuery(...)} 的 {@code configSupplier()} 取到（5 个 bean 共用的那个 supplier）。
 * ⛔ 无 Spring 上下文 / ⛔ 无 {@code @SpringBootTest}（会迁移用户真库）；mapper 与 service 全 Mockito。
 */
@DisplayName("[A] fork/configSupplier 的 ProviderConfig 必须携带 provider extraHeaders（否则零 header → 400）")
class ProviderConfigExtraHeadersWiringTest {

    private static final String HEADER_NAME = "x-opencode-session";
    private static final String RAW_VALUE = "${session_id}";
    private static final String SESSION_ID = "sess-xyz-123";
    private static final String MODEL_FULL_NAME = "opencode/test-model";
    private static final String PROVIDER_ID = "7";

    // ════════════════════════════════════════════════════════════════════
    // ① configSupplier（ToolRegistrationConfig 第 2 处构造点）
    //    —— 5 个 @Bean 共用：streamCompactSummary / countTokensClient / awaySummaryService /
    //       productionForkedQuery / queryLoopForkedQuery
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("configSupplier：provider 配了 ${session_id} ⇒ 配置非空且含该 header，注入时展开为真会话 id")
    void configSupplier_carriesProviderExtraHeaders() {
        ToolRegistrationConfig config = new ToolRegistrationConfig();

        FileConfigStorage fileStorage = Mockito.mock(FileConfigStorage.class);
        Mockito.when(fileStorage.readSettings(Mockito.anyList())).thenReturn(MODEL_FULL_NAME);

        ProductionForkedQuery query = config.productionForkedQuery(
            Mockito.mock(LlmProviderFactory.class),
            new ToolRegistry(),
            modelMapperReturningModel(),
            providerMapperReturningProvider(),
            providerServiceReturningKey(),
            fileStorage);

        ProviderConfig cfg = query.configSupplier().get();

        assertThat(cfg.baseUrl())
            .as("前置：本次配置解析必须成功（baseUrl 命中，否则下面断言的是 ProviderConfig.empty() 的假象）")
            .isEqualTo("https://opencode.test/v1");
        assertThat(cfg.extraHeaders())
            .as("2 参便捷构造会落 Map.of()（= 该出口零自定义 header → opencode 400）；"
                + "本断言在把构造退回 2 参时必红。实际=%s", cfg.extraHeaders())
            .isNotNull()
            .isNotEmpty()
            .containsKey(HEADER_NAME);
        assertThat(cfg.extraHeaders().get(HEADER_NAME))
            .as("配置层只搬 DB 里的原始值（含未展开占位符）—— 展开必须留给注入侧单点，"
                + "否则 gate 关闭 / 无会话上下文时无法落到兜底常量")
            .isEqualTo(RAW_VALUE);

        // ② 端到端另一半：这个配置喂进注入器后真的发出「本次会话 id」
        assertThat(DynamicHeaderExpander.expandAll(cfg.extraHeaders(), SESSION_ID, true))
            .as("fork/压缩/away-summary 请求实际发出的一条 header 必须是本次会话 id")
            .containsEntry(HEADER_NAME, SESSION_ID);
        assertThat(ProviderHeaderInjector.injectableCount(cfg.extraHeaders()))
            .as("注入条数探针口径：本次出口恰 1 条自定义 header（hdrs=1）")
            .isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // ② sessionForkModelRoute（ToolRegistrationConfig 第 1 处构造点 · private static）
    //    —— 装进 ProductionForkedQuery.ForkModelRoute，被 QueryLoopForkedQuery 取用
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sessionForkModelRoute：ForkModelRoute 的 config 非空且含该 header，注入时展开为真会话 id")
    void sessionForkModelRoute_carriesProviderExtraHeaders() throws Exception {
        Method m = ToolRegistrationConfig.class.getDeclaredMethod("sessionForkModelRoute",
            LlmProviderFactory.class, ModelMapper.class, ProviderMapper.class, ProviderService.class);
        m.setAccessible(true);   // private static：本仓既有「直构 + 反射驱动被装配块」范式

        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        Mockito.when(factory.getProvider(Mockito.any(), Mockito.any()))
            .thenReturn(Mockito.mock(LlmProvider.class));

        @SuppressWarnings("unchecked")
        Function<String, ProductionForkedQuery.ForkModelRoute> routeFn =
            (Function<String, ProductionForkedQuery.ForkModelRoute>) m.invoke(
                null, factory, modelMapperReturningModel(), providerMapperReturningProvider(),
                providerServiceReturningKey());

        ProductionForkedQuery.ForkModelRoute route = routeFn.apply(MODEL_FULL_NAME);
        assertThat(route)
            .as("前置：路由必须解析成功（否则下面断言的是 null 的假象）")
            .isNotNull();
        ProviderConfig cfg = route.config();
        assertThat(cfg.extraHeaders())
            .as("fork 家族（SM / extract / auto-dream）用这个 route 的 config 发请求；"
                + "2 参构造落 Map.of() ⇒ 每轮 fork 零 header ⇒ opencode 恒 400。实际=%s", cfg.extraHeaders())
            .isNotNull()
            .isNotEmpty()
            .containsKey(HEADER_NAME);

        assertThat(DynamicHeaderExpander.expandAll(cfg.extraHeaders(), SESSION_ID, true))
            .as("fork 请求实际发出的一条 header 必须是本次会话 id（缓存亲和 + opencode 不 400 的来源）")
            .containsEntry(HEADER_NAME, SESSION_ID);
    }

    // ─────────────────────────────── 脚手架 ───────────────────────────────

    /**
     * modelMapper：全名路径（{@code resolve} 先按 provider.name 精确定位，再联合查 models）返回该模型。
     * 用的模型全名带 '/' 且首段命中 provider ⇒ 走真全名路径（G-5 语义）。
     */
    private static ModelMapper modelMapperReturningModel() {
        ModelMapper m = Mockito.mock(ModelMapper.class);
        ModelRecord rec = new ModelRecord();
        rec.setId("42");
        rec.setProviderId(PROVIDER_ID);
        rec.setName("test-model");
        rec.setEnabled(true);
        Mockito.when(m.selectOneByQuery(Mockito.any())).thenReturn(rec);
        Mockito.when(m.selectListByQuery(Mockito.any())).thenReturn(List.of(rec));
        return m;
    }

    /**
     * providerMapper：返回「配了 {@code x-opencode-session: ${session_id}}」的 provider。
     *
     * <p>{@code extraHeaders} 列是 <b>JSON 字符串</b>（ProviderRecord.java:24），与生产写侧同源
     * （{@code ProviderService.serializeHeaders} 产出）—— 用生产序列化器构造，顺带钉住读写同格式。
     */
    private static ProviderMapper providerMapperReturningProvider() {
        ProviderMapper m = Mockito.mock(ProviderMapper.class);
        ProviderRecord prov = new ProviderRecord();
        prov.setId(PROVIDER_ID);
        prov.setName("opencode");
        prov.setType("openai_compatible");
        prov.setBaseUrl("https://opencode.test/v1");
        prov.setEnabled(true);
        prov.setExtraHeaders(ProviderService.serializeHeaders(Map.of(HEADER_NAME, RAW_VALUE)));

        Mockito.when(m.selectOneByQuery(Mockito.any())).thenReturn(prov);
        Mockito.when(m.selectOneById(PROVIDER_ID)).thenReturn(prov);
        return m;
    }

    private static ProviderService providerServiceReturningKey() {
        ProviderService svc = Mockito.mock(ProviderService.class);
        Mockito.when(svc.getDecryptedApiKey(PROVIDER_ID)).thenReturn("sk-test-key");
        return svc;
    }
}
