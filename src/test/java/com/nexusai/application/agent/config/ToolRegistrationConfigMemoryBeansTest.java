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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** [批 7] 供应器形参（真实会话 id）测试夹具 —— 形态同生产会话键（{@code sess-} + 8hex）。 */
    private static final String MEM_BEANS_SESSION = "sess-abcdef12";

    /** [批 7] 供应器第二形参夹具（会话已解析 cwd 快照）。 */
    private static final java.nio.file.Path MEM_BEANS_CWD =
        java.nio.file.Path.of("C:/probe/session-cwd");

    /** [批 7] **DB 认得但无绑定项目**的会话 id（fail-loud 判据三态之 unbound）。 */
    private static final String KNOWN_UNBOUND = "sess-known-unbound";

    /** [批 7] **DB 不认得**的会话 id（走 CwdResolution unknown 出口 / 或触发 fail-loud）——
     *  用于证明「构造期显式 cwd ⇒ 不重跑解析」。 */
    private static final String UNBOUND_SESSION = "sess-unbound99";

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
        com.nexusai.application.agent.compact.fork.QueryLoopForkedQuery queryLoopFork = queryLoopForkBean();
        SessionMemoryService sm = config.sessionMemoryService(registry, productionFork, queryLoopFork,
            telemetry,
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

        ExtractMemoriesAgent extract = config.extractMemoriesAgent(storage, registry, productionFork,
            queryLoopFork, telemetry,
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED);
        assertThat(extract).isNotNull();

        com.nexusai.application.agent.tasks.DreamTaskRegistry dreamRegistry =
            new com.nexusai.application.agent.tasks.DreamTaskRegistry();
        AutoDreamConsolidator dreamer = config.autoDreamConsolidator(storage, registry, productionFork,
            queryLoopFork,
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
        //   setForkedQuery 生产注入点真实生效（不再是测试才 set 的 seam）。
        //   [E-1b-2] 实现已从 ProductionForkedQuery（自建循环）切到 QueryLoopForkedQuery
        //   （主循环 queryLoop）——断言的是「@Bean 注入的同源引用」这一意图，不钉具体实现类。
        assertThat(readField(extract, "forkedQuery")).isSameAs(queryLoopFork);
        assertThat(readField(dreamer, "forkedQuery")).isSameAs(queryLoopFork);
        // IMP-CM-01（OPD-CM3-03/A01 · X3/X4）: SessionMemoryService 生产 fork seam 也必须真实注入
        //   —— 此前代码体未调（生产 forkedQuery 恒 null → doExtractSessionMemory 提前 return，
        //   LLM 永不写 summary.md + extractionStartedAt 滞留）。断言 @Bean 注入后与 extract/dreamer
        //   同源（sessionMemoryService bean 与 ExtractMemoriesAgent 模式一致，非测试才 set 的 seam）。
        assertThat(readField(sm, "forkedQuery")).isSameAs(queryLoopFork);
        assertThat(readField(sm, "cacheSafeParamsSupplier")).isNotNull();
        @SuppressWarnings("unchecked")
        java.util.function.BiFunction<String, java.nio.file.Path,
                com.nexusai.application.agent.compact.fork.CacheSafeParams> smSup =
            (java.util.function.BiFunction<String, java.nio.file.Path,
                com.nexusai.application.agent.compact.fork.CacheSafeParams>)
                readField(sm, "cacheSafeParamsSupplier");
        assertThat(smSup.apply(MEM_BEANS_SESSION, MEM_BEANS_CWD).toolUseContext())
            .as("SM 生产 cacheSafeParamsSupplier 携带主线程工具集").isNotNull();
        // [批 7 2026-09-14 · 修掉批 6 遗留的会话丢失] 供应器形参 = 真实会话 id，且必须**在构造期**
        //   进入 fork 基础上下文（该 TUC 是 fork 隔离上下文的 parent；sessionId 经
        //   ToolUseContext.with(...) 流遍 fork 工具执行链 → file-history 备份目录 /
        //   SessionFilesRecorder 归属；effectiveCwd 也由它构造期推出）。
        //   批 6 形态（Supplier 无形参）⇒ 生产 fork 的 TUC sessionId 恒为哨兵，
        //   e2e 实证 `{configHome}/file-history/no-session/…`。
        assertThat(smSup.apply(MEM_BEANS_SESSION, MEM_BEANS_CWD).toolUseContext().sessionId())
            .as("生产 supplier 的 fork 上下文必须带**传入的真实会话 id**")
            .isEqualTo(MEM_BEANS_SESSION);
        // ⛔ 不得伪造：确无会话（传 null）时只允许显式哨兵，绝不允许 "sess-"+UUID 假会话键。
        assertThat(smSup.apply(null, null).toolUseContext().sessionId())
            .as("确无会话（null）⇒ 显式哨兵，⛔ 不得伪造会话键")
            .isEqualTo(com.nexusai.common.SessionKeys.NO_SESSION)
            .doesNotStartWith("sess-");
        // [批 7 · 第二形参] 会话**已解析 cwd 快照**必须**在构造期**写入 fork base TUC：
        //   ⛔ 不传的话 ToolUseContext 紧凑构造器会重跑 CwdResolution.getCwd(sessionId)，
        //   而该入口对「会话存在但无绑定项目」是 fail-loud 抛（批 4a 有意设计）⇒ 会把
        //   「cron 显式锚 + 未绑定会话」这条本来能跑的路径变成异常（未登记的行为变更）。
        //   ⭐ 本断言用**未注册的会话 id**做正向对照：若构造器改回自行推导，就会抛
        //   IllegalStateException/或落 user.dir —— 两者都让本断言变红。
        assertThat(smSup.apply(UNBOUND_SESSION, MEM_BEANS_CWD).toolUseContext().effectiveCwd())
            .as("已解析 cwd 必须在构造期生效（不重跑 CwdResolution）")
            .isEqualTo(MEM_BEANS_CWD);

        // OPD-TP-09: dream task registry 接线（register/addDreamTurn/complete/fail/kill）——
        //   @Bean 注入必须真实生效（非假接线），且 kill 的锁回退 seam 已注入 registry
        //   （setDreamTaskRegistry → registry.setRollbackConsolidationLock(this::rollbackConsolidationLockSeam)）。
        assertThat(readField(dreamer, "dreamTaskRegistry")).isSameAs(dreamRegistry);
        assertThat(readField(dreamRegistry, "rollbackConsolidationLock"))
            .as("kill 的锁回退 seam 必须由 setDreamTaskRegistry 注入（DreamTask.ts:153-155）").isNotNull();

        // cacheSafeParamsSupplier 携带主线程工具集（fork 需真实工具数组，createMinimalCacheSafeParams
        //   兜底是空工具集 → 模型无法调工具）。断言 supplier 求值后 toolUseContext.availableTools 非空。
        assertThat(readField(extract, "cacheSafeParamsSupplier")).isNotNull();
        java.util.function.BiFunction<String, java.nio.file.Path,
                com.nexusai.application.agent.compact.fork.CacheSafeParams> sup =
            (java.util.function.BiFunction<String, java.nio.file.Path,
                com.nexusai.application.agent.compact.fork.CacheSafeParams>)
                readField(extract, "cacheSafeParamsSupplier");
        assertThat(sup.apply(MEM_BEANS_SESSION, MEM_BEANS_CWD).toolUseContext()).isNotNull();
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
        SessionMemoryService sm = config.sessionMemoryService(registry, productionFork, queryLoopForkBean(),
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
            queryLoopForkBean(),
            new com.nexusai.application.agent.telemetry.Telemetry(),
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED);
        @SuppressWarnings("unchecked")
        java.util.function.BiFunction<String, java.nio.file.Path,
                com.nexusai.application.agent.compact.fork.CacheSafeParams> sup =
            (java.util.function.BiFunction<String, java.nio.file.Path,
                com.nexusai.application.agent.compact.fork.CacheSafeParams>)
                readField(extract, "cacheSafeParamsSupplier");
        ToolUseContext ctx = sup.apply(MEM_BEANS_SESSION, MEM_BEANS_CWD).toolUseContext();
        assertThat(ctx).isNotNull();
        assertThat(ctx.availableTools()).isNotEmpty();
    }

    @Test
    @DisplayName("[批 7] cron 显式锚 + 未绑定会话：供应商带已解析 cwd ⇒ **不重跑 CwdResolution** ⇒ 不抛")
    void productionCacheSafeParams_explicitCwd_avoidsCwdResolutionThrow() {
        // WHY（规则九 + 用户 2026-09-14 裁定）：批 4a 把「DB 认得该会话但无绑定项目」定为 fail-loud
        //   （有意设计）。但本批起 fork base TUC 开始携带**真实 sessionId** ⇒ 若不同时显式传入
        //   会话已解析 cwd，ToolUseContext 紧凑构造器会对该 id 重跑 CwdResolution.getCwd ⇒ 抛 ⇒
        //   「cron 显式锚（LlmAgentLoop:9824 → 构造期 effectiveCwd 直传） + 未绑定会话」这条
        //   **本来能跑**的路径变成异常，属**未登记的行为变更**。本用例把该场景钉死。
        // 构造「会话存在但无绑定项目」的 DB 回源三态（判据同 CwdResolutionTest:knownSessionWithoutBinding_failsLoud）
        com.nexusai.common.SessionProjectRoot.setDbResolver(
            sid -> com.nexusai.common.SessionProjectRoot.Lookup.unbound());
        try {
            ToolRegistry populated = ToolRegistry.from(java.util.List.of(new NoOpTool("Read")));
            ExtractMemoriesAgent extract = config.extractMemoriesAgent(
                config.memoryStorage(com.nexusai.application.agent.memory.AutoMemPaths.defaultInstance()),
                populated,
                config.productionForkedQuery(
                    Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class),
                    populated, null, null, null, null),
                queryLoopForkBean(),
                new com.nexusai.application.agent.telemetry.Telemetry(),
                com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED);
            @SuppressWarnings("unchecked")
            java.util.function.BiFunction<String, java.nio.file.Path,
                    com.nexusai.application.agent.compact.fork.CacheSafeParams> sup =
                (java.util.function.BiFunction<String, java.nio.file.Path,
                    com.nexusai.application.agent.compact.fork.CacheSafeParams>)
                    readField(extract, "cacheSafeParamsSupplier");

            // ① 场景成立性（正向对照）：该会话状态下解析入口**必抛** —— 证明「不抛」不是恒真
            assertThatThrownBy(() -> com.nexusai.application.agent.agent.CwdResolution.getCwd(KNOWN_UNBOUND))
                .as("该会话状态下 CwdResolution.getCwd 必抛（批 4a fail-loud 设计）")
                .isInstanceOf(IllegalStateException.class);

            // ② 被测腿：带已解析 cwd（= 会话 TUC 快照）⇒ 构造期不再解析 ⇒ 不抛，且 cwd 与 sessionId 均生效
            assertThatCode(() -> sup.apply(KNOWN_UNBOUND, MEM_BEANS_CWD))
                .as("显式 cwd ⇒ 供应商不重跑 CwdResolution ⇒ 不抛")
                .doesNotThrowAnyException();
            assertThat(sup.apply(KNOWN_UNBOUND, MEM_BEANS_CWD).toolUseContext().effectiveCwd())
                .as("构造期显式 cwd 必须生效").isEqualTo(MEM_BEANS_CWD);
            assertThat(sup.apply(KNOWN_UNBOUND, MEM_BEANS_CWD).toolUseContext().sessionId())
                .as("同一 TUC 的会话身份仍是真实 id").isEqualTo(KNOWN_UNBOUND);

            // ③ 反向对照：cwd=null（漏传 / 旧形态）⇒ 退回推导 ⇒ 抛（= 若不传 cwd 会引入的回归）
            assertThatThrownBy(() -> sup.apply(KNOWN_UNBOUND, null))
                .as("cwd=null ⇒ 退回 CwdResolution 推导 ⇒ 抛（本用例的区分力来源）")
                .isInstanceOf(IllegalStateException.class);
        } finally {
            com.nexusai.common.SessionProjectRoot.setDbResolver(null);
        }
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
            List.of(chatMessage("m1")), "s-1", "a-1", null, null, null, sm,
            null, null, null, null, null, false,
            new com.nexusai.application.agent.telemetry.Telemetry(),  // [IMP-CM-17] telemetry 接线（tengu_compact）
            null,  // [批 5a] compactAbort（显式载荷）
            null,  // [批 5a] progressSink（显式载荷）
            null); // [批 5a-2] warningPushContext（显式载荷）

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
        return config.sessionMemoryService(registry, productionFork, queryLoopForkBean(),
            new com.nexusai.application.agent.telemetry.Telemetry(), emptyAutoCompactorProvider(),
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED,
            /*hookRegistry*/ null, /*readFileTool*/ null, /*settingsResolver*/ null);
    }

    /**
     * [E-1b-2] 生产 fork seam 实现 bean（fork 走主循环 queryLoop）· 直调 @Bean 方法
     * （Spring 未启动，@Lazy 参数在直调路径上就是普通实参）。
     */
    private com.nexusai.application.agent.compact.fork.QueryLoopForkedQuery queryLoopForkBean() {
        return config.queryLoopForkedQuery(
            Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class),
            null, null, null, null,
            new com.nexusai.application.agent.loop.AgentLoopContextFactory());
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
