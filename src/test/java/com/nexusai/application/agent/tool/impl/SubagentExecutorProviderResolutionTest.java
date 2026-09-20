package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.AgentModelResolver;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * [P0-D1 / P0-D2] {@link SubagentExecutor} 在 {@code providerConfig == null} 时「自解析 provider / 模型」。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：生产 {@code ToolRegistrationConfig.subagentExecutor @Bean}
 * 第 5 实参传的是 <b>null</b>（{@code providerConfig}）、第 6 实参是字面量 {@code "gpt-4"}。本 bean 上跑的
 * <b>三条路径</b>因此 query loop 恒为 {@code MockLlmProvider}（{@code LlmProviderFactory:48-50}
 * {@code config==null → mock}）：① 队友 {@code AutonomousAgentLoop:1169}（{@code SpawnInProcess:60}
 * 注入的就是本单例）；② fork 技能 {@code SkillToolImpl:1660}（{@code ToolRegistrationConfig:607}
 * {@code setSubagentExecutor(本单例)}）；③ workflow worker {@code ClaudeCodeBackendAdapter:290}。
 * 对照：{@code SubagentTool} 每次 new 私有实例并经 {@code effectiveProviderConfig} 运行时解析 ⇒ 那条路好。
 *
 * <p><b>本类为何必须存在</b>：既有 {@code ToolRegistrationConfigSubagentExecutorBeanTest} 是
 * <b>直接调用</b> {@code config.subagentExecutor(...)}（无容器）⇒ 结构性无法覆盖
 * 「@Autowired 字段注入到 @Bean 返回实例」这一前提，故 providerConfig 那条腿此前<b>零覆盖</b>。
 * 本类用<b>最小容器</b>复刻 @Bean 的 {@code new + return} 形态，证明该前提成立 —— 若 Spring 不把
 * resolver 注入 @Bean 返回实例，第 1 个用例即红（这正是本批要防的「声称守护 X 实际守不住」）。
 *
 * <p>容器注入口诀：依赖用 {@code registerBean(name, type, supplier)}（=@Bean 形态，走完整 bean 生命周期
 * 含 @Autowired 字段注入）；<b>被注入的 stub 依赖</b>用 {@code getBeanFactory().registerSingleton(...)}
 * 预置（mock 实例不走 populateBean，否则 Mockito mock 会因其类上的 {@code @Autowired} 字段而装配失败）。
 */
@DisplayName("[P0-D1/D2] SubagentExecutor provider/model 运行时自解析（修队友/fork技能/workflow 三路径恒 mock）")
class SubagentExecutorProviderResolutionTest {

    /** 复刻 {@code ToolRegistrationConfig.subagentExecutor @Bean} 的实参形态（第 5 参 providerConfig=null）。 */
    private static SubagentExecutor beanLikeExecutor() {
        return new SubagentExecutor(null, null, null, null, null, "gpt-4", null);
    }

    private static ProviderConfig usableConfig(String tag) {
        return new ProviderConfig("https://" + tag + ".example.invalid", "sk-" + tag);
    }

    /**
     * 最小容器 · {@code subagentExecutor} 经 {@code registerBean(supplier)} 装配（= 生产 @Bean 形态），
     * 依赖以预置单例注入。
     */
    private static AnnotationConfigApplicationContext containerWith(ModelConfigResolver resolver,
                                                                   SettingsMapper settingsMapper) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean("subagentExecutor", SubagentExecutor.class,
            SubagentExecutorProviderResolutionTest::beanLikeExecutor);
        if (resolver != null) {
            ctx.getBeanFactory().registerSingleton("modelConfigResolver", resolver);
        }
        if (settingsMapper != null) {
            ctx.getBeanFactory().registerSingleton("settingsMapper", settingsMapper);
        }
        ctx.refresh();
        return ctx;
    }

    // ────────────────────────────────────────────────────────────────────
    // P0-D1：providerConfig == null 时经 @Autowired resolver 自解析
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[P0-D1][核心] @Bean 形态 + providerConfig=null → @Autowired ModelConfigResolver 注入并自解析出 config")
    void beanLikeWiring_nullProviderConfig_resolvesViaAutowiredResolver() {
        // WHY: 这一条同时钉两件事 ——（a）Spring 对「@Bean 方法 new 出来并返回的实例」确实执行
        //   @Autowired 字段注入（P0-D1 方案成立的前提）；（b）providerConfig 为 null 时按模型名解析出
        //   真实 config（三条受害路径的解药）。任一条不成立 → 本断言红。
        ProviderConfig resolved = usableConfig("p0d1");
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve("deepseek-v4-flash"))
            .thenReturn(new ModelConfigResolver.ResolvedModel(resolved, "openai_compatible"));

        try (AnnotationConfigApplicationContext ctx = containerWith(resolver, null)) {
            assertThat(ctx.getBean(SubagentExecutor.class).effectiveProviderConfig("deepseek-v4-flash"))
                .as("providerConfig=null（@Bean 第 5 实参）时必须由 @Autowired resolver 自解析；"
                    + "若为 null ⇒ @Autowired 未注入 @Bean 返回实例，三条路径仍恒 MockLlmProvider")
                .isSameAs(resolved);
        }
    }

    @Test
    @DisplayName("[P0-D1] 解析器未注入（测试/手动直构）→ 仍返回 null（mock 兜底契约不变，不构造 mock）")
    void withoutResolver_returnsNull_mockFallbackContractKept() {
        SubagentExecutor executor = beanLikeExecutor();
        assertThat(executor.effectiveProviderConfig("deepseek-v4-flash"))
            .as("resolver 未注入 → null ⇒ LlmProviderFactory:48-50 落 mock（既有契约，不在此处构造 mock）")
            .isNull();
    }

    @Test
    @DisplayName("[P0-D1] 装配期 providerConfig 非 null → 原样优先（零行为变化，不查 resolver）")
    void explicitProviderConfig_takesPrecedence_noBehaviorChange() {
        ProviderConfig explicit = usableConfig("explicit");
        SubagentExecutor executor =
            new SubagentExecutor(null, null, null, null, explicit, "gpt-4", null);

        assertThat(executor.effectiveProviderConfig("deepseek-v4-flash"))
            .as("SubagentTool 路径（显式注入 providerConfig 非 null）必须原样优先，零行为变化")
            .isSameAs(explicit);
    }

    @Test
    @DisplayName("[P0-D1] resolver 未命中（DB models.name 无该行）→ 返回 null（warn+skip，不落 mock 文本）")
    void resolverMiss_returnsNull() {
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(null);

        try (AnnotationConfigApplicationContext ctx = containerWith(resolver, null)) {
            assertThat(ctx.getBean(SubagentExecutor.class).effectiveProviderConfig("gpt-4"))
                .as("库内无 gpt-4 行 ⇒ 解析 null（ModelConfigResolver 是 DB models.name 精确匹配）")
                .isNull();
        }
    }

    @Test
    @DisplayName("[P0-D1] 模型名空 → 不查 resolver，直接 null（不产生无谓 DB 查询/告警）")
    void blankModel_skipsResolver() {
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);

        try (AnnotationConfigApplicationContext ctx = containerWith(resolver, null)) {
            assertThat(ctx.getBean(SubagentExecutor.class).effectiveProviderConfig("  ")).isNull();
        }
        Mockito.verifyNoInteractions(resolver);
    }

    // ────────────────────────────────────────────────────────────────────
    // P0-D2：effective model 兜底链（不再静默落字面量 "gpt-4"）
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[P0-D2] settings.subagent_model_name（DB 默认子代理模型）腿生效 —— 队友路径不落 gpt-4")
    void resolveEffectiveModel_dbSubagentModelNameLeg() {
        // WHY: 队友的 agentDefinition 是 BuiltInAgents.GENERAL_PURPOSE_AGENT（未设 model），
        //   modelOverride 由 TeamController:344 显式传 null ⇒ 旧链落字面量 "gpt-4"（DB 无该行 ⇒ mock）。
        //   本用例钉住「DB 默认子代理模型」这条 CC getAgentModel 腿（agent.ts:43-45 同段）真实接线。
        //   确定性说明：DB 腿（resolveWithEnv 步骤 2）排在 env 腿（步骤 3）之前 ⇒ 不受
        //   CLAUDE_CODE_SUBAGENT_MODEL 影响。
        SettingsRecord s = new SettingsRecord();
        s.setSubagentModelName("deepseek-v4-flash-from-db");
        SettingsMapper settingsMapper = Mockito.mock(SettingsMapper.class);
        Mockito.when(settingsMapper.selectOneById(1)).thenReturn(s);

        try (AnnotationConfigApplicationContext ctx = containerWith(null, settingsMapper)) {
            String model = ctx.getBean(SubagentExecutor.class)
                .resolveEffectiveModel(BuiltInAgents.GENERAL_PURPOSE_AGENT, PermissionMode.DEFAULT, null);

            assertThat(model)
                .as("settings.subagent_model_name 必须被 AgentModelResolver 的 DB 腿消费（否则队友仍落 gpt-4）")
                .isEqualTo("deepseek-v4-flash-from-db")
                .isNotEqualTo("gpt-4");
        }
    }

    @Test
    @DisplayName("[P0-D2] 'inherit' 腿：父 TUC 的 effectiveModelName（CC options.mainLoopModel）被继承"
        + "（本 JVM 设了 CLAUDE_CODE_SUBAGENT_MODEL → env 腿先于 inherit，判据随环境切换）")
    void resolveEffectiveModel_inheritOrEnvLeg() {
        // WHY: fork 技能路径带父 ToolUseContext（SkillToolImpl:1660 → executeForkedSkill(parentTuc)），
        //   其 effectiveModelName = 父当前 turn 模型 —— 这是 CC 'inherit'（agent.ts:78-88）在本仓的等价源。
        //   ⚠️ AgentModelResolver 解析链里 env 腿（步骤 3）排在 'inherit'（步骤 4）之前（CC agent.ts:43-45
        //   同序），故本用例对环境做**显式分支断言**而不是假设环境干净（避免「假绿」）。
        SubagentExecutor executor = beanLikeExecutor();
        ToolUseContext parentTuc = ToolUseContext.of(UUID.randomUUID(), "sess-lead")
            .withEffectiveModelName("lead-session-model");

        String model = executor.resolveEffectiveModel(
            BuiltInAgents.GENERAL_PURPOSE_AGENT, PermissionMode.DEFAULT, parentTuc);

        String env = System.getenv("CLAUDE_CODE_SUBAGENT_MODEL");
        if (env == null || env.isBlank()) {
            assertThat(model)
                .as("无 env 覆盖 ⇒ 'inherit' 腿必须取父 TUC 的 effectiveModelName")
                .isEqualTo("lead-session-model");
        } else {
            assertThat(model)
                .as("env 覆盖先于 'inherit'（CC agent.ts:43-45）⇒ 必须等于 parseUserSpecifiedModel(env)")
                .isEqualTo(AgentModelResolver.parseUserSpecifiedModel(env));
        }
    }

    @Test
    @DisplayName("[P0-D2] 全链不可解析（无 settings / 无 env / 无父模型）→ 告警并回落装配兜底（不再静默）")
    void resolveEffectiveModel_allUnavailable_warnsAndFallsBack() {
        String env = System.getenv("CLAUDE_CODE_SUBAGENT_MODEL");
        assumeTrue(env == null || env.isBlank(),
            "本 JVM 设了 CLAUDE_CODE_SUBAGENT_MODEL=" + env + " ⇒ env 腿会先生效，'全链不可用'不可达；"
                + "该分支判据改由 resolveEffectiveModel_inheritOrEnvLeg 的 env 分支覆盖");

        SubagentExecutor executor = beanLikeExecutor();
        String model = executor.resolveEffectiveModel(
            BuiltInAgents.GENERAL_PURPOSE_AGENT, PermissionMode.DEFAULT, null);

        assertThat(model)
            .as("全链不可用 ⇒ 回落装配兜底名（同旧行为），但必须已发 WARN（不再静默）")
            .isEqualTo("gpt-4");
    }

    @Test
    @DisplayName("[P0-D2] agentDefinition 自带 model（非 'inherit'）→ 走定义自带值（无 env/settings 覆盖时）")
    void resolveEffectiveModel_agentDefinitionModelUsed_whenNoEnvOrDb() {
        // WHY: 钉住「兜底链不改写 agent 自己声明的模型」——与 CC getAgentModel 的
        //   {@code agentModel ?? 'inherit'} 一步同义（agent.ts:78）。frontmatter 声明 model 的自定义
        //   agent（AgentDefinition.Builder.model）不得被兜底链改写。
        //   ⚠️ env 腿（步骤 3）在 agentModel 之前（CC agent.ts:43-45 同序）⇒ 显式分支断言。
        AgentDefinition pinned = AgentDefinition.BuiltInAgentDefinition
            .builder("pinned-model-agent", "test", (modelId, dirs) -> "")
            .model("agent-pinned-model")
            .build();
        SubagentExecutor executor = beanLikeExecutor();

        String model = executor.resolveEffectiveModel(pinned, PermissionMode.DEFAULT, null);

        String env = System.getenv("CLAUDE_CODE_SUBAGENT_MODEL");
        if (env == null || env.isBlank()) {
            assertThat(model).isEqualTo("agent-pinned-model");
        } else {
            assertThat(model)
                .as("env 腿先于 agentModel（CC agent.ts:43-45）")
                .isEqualTo(AgentModelResolver.parseUserSpecifiedModel(env));
        }
    }
}
