package com.nexusai.application.agent.config;

import com.nexusai.application.agent.command.CompactCommand;
import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.StreamCompactSummary;
import com.nexusai.application.agent.compact.TokenCounter;
import com.nexusai.application.agent.memory.AutoDreamConsolidator;
import com.nexusai.application.agent.memory.ExtractMemoriesAgent;
import com.nexusai.application.agent.memory.MemoryStorage;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.application.agent.permission.hook.StopHookPipeline;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-M-P0-3 · 三组件 @Bean 生产接线测试（DEL-M-48 @Autowired(required=false) 恒 null 消除）。
 *
 * <p><b>WHY (规则 9 · 测试验证意图)</b>：此前 SessionMemoryService/AutoDreamConsolidator/
 * ExtractMemoriesAgent 无 @Bean，AgentLoopContextFactory 的 {@code @Autowired(required=false)}
 * 恒 null → StopHookPipeline 阶段 4 / SM 优先压缩生产不可达。本测试验证：
 * <ol>
 *   <li>三组件 @Bean 存在且可构造（bean 存在性断言）</li>
 *   <li>autoCompactor @Bean 注入 SessionMemoryService（SM 优先路径生产可达）</li>
 *   <li>/compact handler ctx.sessionMemoryService() 非 null（空指令 SM 优先可达）</li>
 *   <li>StopHookPipeline.isExtractModeActive DB 主控（auto_memory_enabled 默认 true ·
 *       [sm 决策 2026-08-30]，旧 env 总闸默认 false 已移除）</li>
 * </ol>
 */
@DisplayName("[IMP-M-P0-3] ToolRegistrationConfig 三组件 @Bean 生产接线")
class ToolRegistrationConfigMemoryBeansTest {

    private final ToolRegistrationConfig config = new ToolRegistrationConfig();

    @Test
    @DisplayName("SessionMemoryService/ExtractMemoriesAgent/AutoDreamConsolidator @Bean 存在且可构造 + 生产 fork seam 注入")
    void memoryComponentsBeansAreRegisteredAndConstructible() {
        ToolRegistry registry = new ToolRegistry();
        com.nexusai.application.agent.compact.fork.ProductionForkedQuery productionFork =
            config.productionForkedQuery(
                Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class),
                registry, null, null, null, null);

        com.nexusai.application.agent.telemetry.Telemetry telemetry =
            new com.nexusai.application.agent.telemetry.Telemetry();
        SessionMemoryService sm = config.sessionMemoryService(registry, productionFork, telemetry,
            emptyAutoCompactorProvider(),
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED,
            /*hookRegistry*/ null, /*readFileTool*/ null, /*settingsResolver*/ null);
        assertThat(sm).isNotNull();
        // [sm-reloc] 生产 2-arg 装配：per-session resolver（SessionStorage::sessionProjectDir）必须注入，
        //   非 legacy 固定 baseDir —— resolvePath 按会话动态求值 projects/{slug}（与 transcript 同源分层）
        assertThat(readField(sm, "sessionBaseDirResolver"))
            .as("sm-reloc：sessionMemoryService bean 的 sessionBaseDirResolver 非 null（per-session 落点）")
            .isNotNull();

        MemoryStorage storage = config.memoryStorage(
            com.nexusai.application.agent.memory.AutoMemPaths.defaultInstance());
        assertThat(storage).isNotNull();

        ExtractMemoriesAgent extract = config.extractMemoriesAgent(storage, registry, productionFork, telemetry,
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED);
        assertThat(extract).isNotNull();

        com.nexusai.application.agent.tasks.DreamTaskRegistry dreamRegistry =
            new com.nexusai.application.agent.tasks.DreamTaskRegistry();
        AutoDreamConsolidator dreamer = config.autoDreamConsolidator(storage, registry, productionFork,
            new com.nexusai.application.agent.telemetry.Telemetry(),
            dreamRegistry,
            /*minHours*/ 24.0, /*minSessions*/ 5);
        assertThat(dreamer).isNotNull();

        // FIX-AD: 动态阈值生产接线（CC autoDream.ts:73-91 getConfig 每轮读取 → Spring property
        //   代偿）—— @Bean 注入的 setConfigSupplier 必须真实生效（非假接线），且缺省 24/5 对齐 DEFAULTS。
        java.util.function.Supplier<AutoDreamConsolidator.AutoDreamConfig> cfgSupplier =
            (java.util.function.Supplier<AutoDreamConsolidator.AutoDreamConfig>) readField(dreamer, "configSupplier");
        assertThat(cfgSupplier).isNotNull();
        AutoDreamConsolidator.AutoDreamConfig cfg = cfgSupplier.get();
        assertThat(cfg.minHours()).isEqualTo(24.0);
        assertThat(cfg.minSessions()).isEqualTo(5);

        // IMP-M-P0-3: 生产 fork seam 注入（DEL-M-48 接线缺口消除 + R9/IMP-18 收敛）——
        //   setForkedQuery(ProductionForkedQuery) 生产注入点真实生效（不再是测试才 set 的 seam）。
        assertThat(readField(extract, "forkedQuery")).isSameAs(productionFork);
        assertThat(readField(dreamer, "forkedQuery")).isSameAs(productionFork);
        // IMP-CM-01（OPD-CM3-03/A01 · X3/X4）: SessionMemoryService 生产 fork seam 也必须真实注入
        //   —— 此前代码体未调（生产 forkedQuery 恒 null → doExtractSessionMemory 提前 return，
        //   LLM 永不写 summary.md + extractionStartedAt 滞留）。断言 @Bean 注入后与 extract/dreamer
        //   同源（sessionMemoryService bean 与 ExtractMemoriesAgent 模式一致，非测试才 set 的 seam）。
        assertThat(readField(sm, "forkedQuery")).isSameAs(productionFork);
        assertThat(readField(sm, "cacheSafeParamsSupplier")).isNotNull();
        @SuppressWarnings("unchecked")
        java.util.function.Supplier<com.nexusai.application.agent.compact.fork.CacheSafeParams> smSup =
            (java.util.function.Supplier<com.nexusai.application.agent.compact.fork.CacheSafeParams>)
                readField(sm, "cacheSafeParamsSupplier");
        assertThat(smSup.get().toolUseContext()).as("SM 生产 cacheSafeParamsSupplier 携带主线程工具集").isNotNull();

        // OPD-TP-09: dream task registry 接线（register/addDreamTurn/complete/fail/kill）——
        //   @Bean 注入必须真实生效（非假接线），且 kill 的锁回退 seam 已注入 registry
        //   （setDreamTaskRegistry → registry.setRollbackConsolidationLock(this::rollbackConsolidationLockSeam)）。
        assertThat(readField(dreamer, "dreamTaskRegistry")).isSameAs(dreamRegistry);
        assertThat(readField(dreamRegistry, "rollbackConsolidationLock"))
            .as("kill 的锁回退 seam 必须由 setDreamTaskRegistry 注入（DreamTask.ts:153-155）").isNotNull();

        // cacheSafeParamsSupplier 携带主线程工具集（fork 需真实工具数组，createMinimalCacheSafeParams
        //   兜底是空工具集 → 模型无法调工具）。断言 supplier 求值后 toolUseContext.availableTools 非空。
        assertThat(readField(extract, "cacheSafeParamsSupplier")).isNotNull();
        java.util.function.Supplier<com.nexusai.application.agent.compact.fork.CacheSafeParams> sup =
            (java.util.function.Supplier<com.nexusai.application.agent.compact.fork.CacheSafeParams>)
                readField(extract, "cacheSafeParamsSupplier");
        assertThat(sup.get().toolUseContext()).isNotNull();
    }

    @Test
    @DisplayName("FIX-CL awaySummaryService/claudemdEngine @Bean 可构造 + 惰性 provider + mothCopse 门控")
    void awaySummaryAndClaudemdBeans_wired() {
        // WHY (规则 9): FIX-CL 把 AwaySummaryService 从"0 调用方不可达"接为生产 @Bean；provider/config
        //       必须惰性解析（对齐 ProductionForkedQuery），避免 bean 构造期（settings/DB 未就绪）
        //       锁定 mock provider —— 假接线反例（bean 存在但 LLM 调用恒 mock）。
        ToolRegistry registry = new ToolRegistry();
        com.nexusai.application.agent.compact.fork.ProductionForkedQuery productionFork =
            config.productionForkedQuery(Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class),
                registry, null, null, null, null);
        SessionMemoryService sm = config.sessionMemoryService(registry, productionFork,
            new com.nexusai.application.agent.telemetry.Telemetry(), emptyAutoCompactorProvider(),
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED,
            /*hookRegistry*/ null, /*readFileTool*/ null, /*settingsResolver*/ null);

        com.nexusai.application.agent.memory.AwaySummaryService away = config.awaySummaryService(
            Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class), sm, null, null, null, null);
        assertThat(away).as("AwaySummaryService @Bean 可构造（生产可达）").isNotNull();
        assertThat(readField(away, "llmProviderSupplier")).as("provider 惰性 supplier 注入").isNotNull();
        assertThat(readField(away, "providerConfigSupplier")).as("config 惰性 supplier 注入").isNotNull();

        com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths =
            com.nexusai.application.agent.memory.AutoMemPaths.defaultInstance();
        com.nexusai.application.agent.memory.MemoryFileDetection detection =
            new com.nexusai.application.agent.memory.MemoryFileDetection(
                autoMemPaths, () -> System.getProperty("user.home"), () -> true, () -> false, () -> false);
        com.nexusai.application.agent.context.ClaudemdEngine engine =
            config.claudemdEngine(autoMemPaths, detection, null);
        assertThat(engine).as("ClaudemdEngine @Bean 可构造").isNotNull();
        assertThat(readField(engine, "mothCopseGate"))
            .as("FIX-CL tengu_moth_copse 门控注入（filterInjectedMemoryFiles 真实过滤）").isNotNull();
    }

    @Test
    @DisplayName("生产 cacheSafeParams 携带主线程工具集（fork 模型可调工具）")
    void productionCacheSafeParams_carriesMainThreadTools() {
        // WHY: IMP-M-P0-3 —— extract/auto-dream fork 需要向 provider 传真实工具数组。
        // 生产 supplier（buildProductionCacheSafeParams）必须让 toolUseContext.availableTools
        // 包含主线程工具；否则 fork 模型无法调用 Read/Write/Edit/Bash（extract 无法写记忆）。
        ToolRegistry registry = new ToolRegistry();
        // 注册一个假工具（ToolRegistry 空时 availableTools 空 → 无法断言携带工具）
        ToolRegistry populated = ToolRegistry.from(java.util.List.of(new NoOpTool("Read")));
        // 通过反射调用私有 buildProductionCacheSafeParams 等价物：直接构造 bean 上下文不可行，
        // 改由 extractMemoriesAgent bean 注入的 supplier 断言（工具集来自主 registry）。
        ExtractMemoriesAgent extract = config.extractMemoriesAgent(
            config.memoryStorage(com.nexusai.application.agent.memory.AutoMemPaths.defaultInstance()), populated,
            config.productionForkedQuery(
                Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class),
                populated, null, null, null, null),
            new com.nexusai.application.agent.telemetry.Telemetry(),
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED);
        @SuppressWarnings("unchecked")
        java.util.function.Supplier<com.nexusai.application.agent.compact.fork.CacheSafeParams> sup =
            (java.util.function.Supplier<com.nexusai.application.agent.compact.fork.CacheSafeParams>)
                readField(extract, "cacheSafeParamsSupplier");
        ToolUseContext ctx = sup.get().toolUseContext();
        assertThat(ctx).isNotNull();
        assertThat(ctx.availableTools()).isNotEmpty();
    }

    /** 简单测试工具 · 仅验证工具集透传，不执行。 */
    static final class NoOpTool implements com.nexusai.application.agent.tool.Tool {
        private final String name;
        NoOpTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "no-op test tool"; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        @Override
        public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                com.nexusai.application.agent.tool.ToolUseBlock call) {
            return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "ok");
        }
    }

    /** 反射读取私有字段（测试观察点）。 */
    private static Object readField(Object target, String fieldName) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException("readField failed: " + fieldName, e);
        }
    }

    @Test
    @DisplayName("autoCompactor @Bean 注入 SessionMemoryService（SM 优先路径生产可达）")
    void autoCompactorBean_wiresSessionMemoryService() throws Exception {
        TokenCounter tokenCounter = msgs -> 100;
        StreamCompactSummary streamCompactSummary = Mockito.mock(StreamCompactSummary.class);
        SessionMemoryService sm = sessionMemoryServiceBean();

        AutoCompactor auto = config.autoCompactor(tokenCounter, streamCompactSummary, null, sm,
            /*settingsResolver*/ null, /*modelMapper*/ null, /*providerMapper*/ null);

        Field f = AutoCompactor.class.getDeclaredField("sessionMemoryService");
        f.setAccessible(true);
        assertThat(f.get(auto)).isSameAs(sm);
    }

    @Test
    @DisplayName("/compact handler ctx.sessionMemoryService() 非 null（SM @Bean 注入 → 空指令 SM 优先可达）")
    void compactCommandContext_getsNonNullSessionMemoryService() {
        SessionMemoryService sm = sessionMemoryServiceBean();

        CompactCommand.CompactCommandContext ctx = config.buildCompactCommandContext(
            List.of(chatMessage("m1")), "s-1", "a-1", null, null, sm,
            null, null, null, null, null, false,
            new com.nexusai.application.agent.telemetry.Telemetry());  // [IMP-CM-17] telemetry 接线（tengu_compact）

        assertThat(ctx.sessionMemoryService()).isSameAs(sm);
    }

    @Test
    @DisplayName("[sm 决策 2026-08-30] isExtractModeActive DB 主控：auto_memory_enabled=true → 交互激活（默认开），false → 关闭")
    void extractModeActive_dbGateControls() {
        // 不设任何 env/property → DB settings 列 auto_memory_enabled 主控（默认 true）。
        // 经 BundledSkillEnabledGates 静态桥接 mock SettingsMapper 确定性注入 DB 真值（DB 列优先
        // 于宿主 settings.json 链，防环境敏感），tearDown 清理。
        try {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES");
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES_NON_INTERACTIVE");

            SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
            SettingsRecord rec = new SettingsRecord();
            rec.setAutoMemoryEnabled(true);
            Mockito.when(mapper.selectOneById(1)).thenReturn(rec);
            BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
            // DB 主控 true（默认开）+ 无 env 覆盖 → 交互激活；非交互仍需 NON_INTERACTIVE
            assertThat(StopHookPipeline.isExtractModeActive(false))
                .as("DB auto_memory_enabled=true → 交互提取激活（默认开，无需 env）").isTrue();
            assertThat(StopHookPipeline.isExtractModeActive(true))
                .as("非交互默认仍跳过（NON_INTERACTIVE 未开）").isFalse();

            rec.setAutoMemoryEnabled(false);
            Mockito.when(mapper.selectOneById(1)).thenReturn(rec);
            assertThat(StopHookPipeline.isExtractModeActive(false))
                .as("DB auto_memory_enabled=false → 交互不激活").isFalse();
            assertThat(StopHookPipeline.isExtractModeActive(true)).isFalse();
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES");
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES_NON_INTERACTIVE");
        }
    }

    /** P1-3: sessionMemoryService @Bean 需 ToolRegistry + ProductionForkedQuery 参数。 */
    private SessionMemoryService sessionMemoryServiceBean() {
        ToolRegistry registry = new ToolRegistry();
        com.nexusai.application.agent.compact.fork.ProductionForkedQuery productionFork =
            config.productionForkedQuery(
                Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class),
                registry, null, null, null, null);
        return config.sessionMemoryService(registry, productionFork,
            new com.nexusai.application.agent.telemetry.Telemetry(), emptyAutoCompactorProvider(),
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED,
            /*hookRegistry*/ null, /*readFileTool*/ null, /*settingsResolver*/ null);
    }

    /** FIX-SM: 空 autoCompactor ObjectProvider（getIfAvailable → null → supplier 默认 true）。 */
    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<AutoCompactor> emptyAutoCompactorProvider() {
        return Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    private static ChatMessageDto chatMessage(String id) {
        return new ChatMessageDto(id, null, com.nexusai.model.session.dto.Role.user, "user", "hi",
            null, List.of(), com.nexusai.model.session.dto.FinishReason.stop,
            null, null, "刚刚", java.time.OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }
}
