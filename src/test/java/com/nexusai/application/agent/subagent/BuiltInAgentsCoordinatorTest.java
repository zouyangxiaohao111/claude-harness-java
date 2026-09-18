package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.prompt.PromptAlignSettingsResolver;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [coordinator 缺件补齐 · 定义侧] {@code getBuiltInAgents()} coordinator 分支 · 对齐 CC
 * {@code packages/builtin-tools/src/tools/AgentTool/builtInAgents.ts:20-46}。
 *
 * <h2>CC 语义（实读 CC 源，非转述）</h2>
 * <pre>
 *   getBuiltInAgents():
 *     if (CLAUDE_AGENT_SDK_DISABLE_BUILTIN_AGENTS &amp;&amp; 非交互) return []      // :23-28
 *     if (feature('COORDINATOR_MODE') &amp;&amp; isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE))
 *         return getCoordinatorAgents()                                     // :33-41 ← 本测试钉的分支
 *     base = [GENERAL_PURPOSE, STATUSLINE_SETUP] ...                        // :43-69
 * </pre>
 * <p><b>关键点：coordinator 模式下 CC 直接 return，base 列表（general-purpose / Explore / Plan /
 * claude-code-guide …）全部消失，只剩 {@code [worker]}。</b>不是「worker 追加进列表」，是「整表替换」——
 * 这条差别决定 {@link BuiltInAgents#get(String)} 在 coordinator 模式下对其它 agentType 返回 null。
 *
 * <h2>WHY 存在（意图验证）</h2>
 * <p>worker 定义本身由 {@code WorkerAgentDefinitionTest} 钉住；本类钉的是<b>接线</b>：
 * 「coordinator 激活 → 模型看到的 agent 列表里恰好有 worker」这条链一旦断开，模型照 coordinator 提示
 * 派 {@code subagent_type: "worker"} 就撞
 * {@code Agent type 'worker' not found. Available agents: ...}
 * （异常文本 {@code SubagentExecutor:1594-1600}，可用列表正是本方法的返回值）。
 *
 * <h2>夹具（对齐本项目 test-env-failloud-unreachable 教训）</h2>
 * <p>测试环境里 coordinator 门<b>结构上不可达</b>：application.yml 默认
 * {@code nexusai.feature.coordinator-mode: false}（且专门有一条测试断言它必须为 false），
 * env {@code CLAUDE_CODE_COORDINATOR_MODE} 也不设。所以本类<b>不读</b>真实 env/Spring 环境，
 * 一律经 {@link BuiltInAgents.GateConfig}（生产同款注入落点）显式设置，{@code @AfterEach} 显式复位。
 * 「跑了但门从没真开过」这类假绿在本类不可能发生——开门的那一行就在用例里。
 */
@DisplayName("coordinator 定义侧 · getBuiltInAgents() 协调者分支 (CC builtInAgents.ts:33-41)")
class BuiltInAgentsCoordinatorTest {

    /** coordinator 门夹具：feature=true + env="true" → isCoordinatorMode()=true（不依赖 env/Spring）。 */
    private static CoordinatorMode coordinatorOn() {
        return new CoordinatorMode(() -> true, () -> "true");
    }

    /** 生产同款注入落点（@Configuration + 静态字段桥）；测试直接调用其 setter = 走同一条线。 */
    private static BuiltInAgents.GateConfig gate() {
        return new BuiltInAgents.GateConfig();
    }

    /** 复位全部静态门（含 coordinator 两层桥）—— static 槽位跨用例串味是这类测试的头号假绿来源。 */
    private static void resetGates() {
        BuiltInAgents.GateConfig g = gate();
        g.setCoordinatorMode(null);                  // 复位默认：feature 恒关 → 不进入 coordinator 分支
        g.setPromptAlignSettingsResolver(null);      // 复位 DB 覆盖层（否则上一例的 stub 会串到下一例）
        g.setExplorePlanEnabled(true);
        g.setVerificationEnabled(false);
        g.setEntrypoint("");
    }

    /**
     * DB 覆盖层夹具 · 同 {@code DeferredToolsDeltaUnifiedGateTest.resolverWith}（:170-178）范式 ——
     * mock {@code SettingsMapper.selectOneById} 返回一行 settings，其余字段自然为 null。
     *
     * @param dbValue {@code settings.coordinator_mode_enabled} 列值（null = 未配置 → 回落 env+feature）
     */
    private static PromptAlignSettingsResolver resolverWith(Boolean dbValue) {
        SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        row.setCoordinatorModeEnabled(dbValue);
        Mockito.when(mapper.selectOneById(1)).thenReturn(row);
        PromptAlignSettingsResolver r = new PromptAlignSettingsResolver();
        r.setSettingsMapper(mapper);
        return r;
    }

    @AfterEach
    void afterEach() {
        resetGates();
    }

    // ─────────────────────── 1. 门开 → 整表替换为 [worker] ───────────────────────

    @Test
    @DisplayName("coordinator 开 → getBuiltInAgents() 只返回 [worker]（CC :33-41 直接 return，base 列表消失）")
    void coordinatorOn_returnsOnlyWorker() {
        // GIVEN: coordinator 门开
        gate().setCoordinatorMode(coordinatorOn());

        // WHEN
        List<String> types = BuiltInAgents.getBuiltInAgents().stream()
            .map(AgentDefinition::agentType)
            .collect(Collectors.toList());

        // THEN: 恰好 [worker] —— 不是「含 worker」，是「只有 worker」
        assertThat(types)
            .as("CC builtInAgents.ts:39 `return getCoordinatorAgents()` 是整表替换；"
                + "若写成「worker 追加」则协调者模式下其它内置 agent 仍可见，与 CC 语义不符")
            .containsExactly("worker");
    }

    @Test
    @DisplayName("coordinator 开 → 其它内置 agentType 全部不可命中（get() 返回 null）")
    void coordinatorOn_otherAgentTypesUnresolvable() {
        gate().setCoordinatorMode(coordinatorOn());

        assertThat(BuiltInAgents.get("general-purpose")).isNull();
        assertThat(BuiltInAgents.get("Explore")).isNull();
        assertThat(BuiltInAgents.get("claude-code-guide")).isNull();
        // 但 worker 命中（本任务的全部意义）
        assertThat(BuiltInAgents.get("worker")).isNotNull();
    }

    @Test
    @DisplayName("coordinator 开 → 即使 explore/plan/verification 门也开，仍只返回 worker（分支在最前，短路后置门）")
    void coordinatorOn_shortCircuitsLaterGates() {
        BuiltInAgents.GateConfig g = gate();
        g.setCoordinatorMode(coordinatorOn());
        g.setExplorePlanEnabled(true);
        g.setVerificationEnabled(true);
        g.setEntrypoint("");   // 非 SDK 入口 → 本来会加 claude-code-guide

        assertThat(BuiltInAgents.getBuiltInAgents().stream().map(AgentDefinition::agentType))
            .containsExactly("worker");
    }

    // ─────────────────────── 2. 门关 → 原有装配不变 ───────────────────────

    @Test
    @DisplayName("coordinator 关 → 返回原有列表（general-purpose/statusline-setup/Explore/Plan/guide），且不含 worker")
    void coordinatorOff_returnsLegacyAssemblyWithoutWorker() {
        // GIVEN: 显式关门 + 显式设置其余门（不依赖测试 JVM 里 static 字段的上一个值）
        BuiltInAgents.GateConfig g = gate();
        g.setCoordinatorMode(null);
        g.setExplorePlanEnabled(true);
        g.setVerificationEnabled(false);
        g.setEntrypoint("");

        // WHEN
        List<String> types = BuiltInAgents.getBuiltInAgents().stream()
            .map(AgentDefinition::agentType)
            .collect(Collectors.toList());

        // THEN: base 两项恒在（CC :45-48）+ explore/plan 门开（:50-52）+ 非 SDK 入口 guide（:54-61）
        assertThat(types).contains(
            "general-purpose", "statusline-setup", "Explore", "Plan", "claude-code-guide");
        assertThat(types)
            .as("worker 只在 coordinator 模式存在（CC :33-41）；普通会话里出现 = 模型可能派一个"
                + "拿不到 coordinator 上下文的 worker")
            .doesNotContain("worker");
        // verification GrowthBook default false（CC :64-69）
        assertThat(types).doesNotContain("verification");
    }

    @Test
    @DisplayName("门关 → 显式注入 coordinatorMode 后立刻开门（桥是活读，不是构造期冻结）")
    void coordinatorGateIsReadLivePerCall() {
        // GIVEN: 先关门跑一次
        gate().setCoordinatorMode(null);
        assertThat(BuiltInAgents.getBuiltInAgents().stream().map(AgentDefinition::agentType))
            .contains("general-purpose")
            .doesNotContain("worker");

        // WHEN: 同一进程内开门（CC 每调用读 env/feature，非启动期快照）
        gate().setCoordinatorMode(coordinatorOn());

        // THEN: 下一次调用即为 coordinator 列表 —— 证明分支是逐调用求值
        assertThat(BuiltInAgents.getBuiltInAgents().stream().map(AgentDefinition::agentType))
            .containsExactly("worker");
    }

    // ────────────────── 3. DB 覆盖层（唯一用户可达的激活路径）必须与提示注入门同源 ──────────────────

    @Test
    @DisplayName("DB coordinator_mode_enabled=true + feature/env 关 → 仍返回 [worker]（前端勾选即生效）")
    void dbTrue_featureFalse_coordinatorBranchActive() {
        // GIVEN: 前端「设置 → 环境配置 → 协调者模式」勾选 → settings.coordinatorModeEnabled=true；
        //   application.yml 的 nexusai.feature.coordinator-mode 恒 false 且无 UI 可改。
        BuiltInAgents.GateConfig g = gate();
        g.setPromptAlignSettingsResolver(resolverWith(true));
        g.setCoordinatorMode(null);   // env+feature 层判 false —— 旧 env-only 门会在此返回 legacy 列表

        // WHEN / THEN: coordinator 系统提示此刻<b>已经</b>注入（LlmAgentLoop:4618/4660 同一条 DB 链），
        //   若本分支只读 env+feature → 提示让用 worker 而列表没有 = 派活必失败（本任务要修的故障原样保留）
        assertThat(BuiltInAgents.getBuiltInAgents().stream().map(AgentDefinition::agentType))
            .as("DB 开关是唯一用户可达路径；此处理应整表替换为 [worker]（改回 env-only 则此处变红）")
            .containsExactly("worker");
        assertThat(BuiltInAgents.get("worker")).isNotNull();
    }

    @Test
    @DisplayName("DB coordinator_mode_enabled=false + feature/env 真 → 回落层被 DB 覆盖为关（legacy 列表）")
    void dbFalse_featureTrue_dbOverrideWins() {
        // GIVEN: DB 显式关（DB 有值 → 覆盖 env+feature 层）
        BuiltInAgents.GateConfig g = gate();
        g.setPromptAlignSettingsResolver(resolverWith(false));
        g.setCoordinatorMode(coordinatorOn());
        g.setExplorePlanEnabled(true);
        g.setVerificationEnabled(false);
        g.setEntrypoint("");

        // WHEN / THEN: DB 覆盖必须生效（对齐 LlmAgentLoop 的 `gate != null ? gate : ...` 语义）
        assertThat(BuiltInAgents.getBuiltInAgents().stream().map(AgentDefinition::agentType))
            .as("DB 显式 false 覆盖 env 层的 true（若忽略 DB 层则此处只剩 worker → 红）")
            .contains("general-purpose", "statusline-setup")
            .doesNotContain("worker");
    }

    @Test
    @DisplayName("DB 列为 NULL（未配置）→ 回落 env+feature 层（不因「有 resolver」就恒关）")
    void dbNull_fallsBackToEnvFeatureLayer() {
        // GIVEN: settings 行存在但该列为 null（生产默认：DB 未配置）
        BuiltInAgents.GateConfig g = gate();
        g.setPromptAlignSettingsResolver(resolverWith(null));
        g.setCoordinatorMode(coordinatorOn());

        // WHEN / THEN: 回落 env+feature（= 真值）→ 激活
        assertThat(BuiltInAgents.getBuiltInAgents().stream().map(AgentDefinition::agentType))
            .as("null = DB 未配置 → 回落 CoordinatorMode（对齐 PromptAlignSettingsResolver 的 null 语义）")
            .containsExactly("worker");
    }

    @Test
    @DisplayName("无 resolver（非 Spring 单测/无 mapper）+ feature 真 → 回落 env+feature 层激活")
    void noResolver_fallsBackToEnvFeatureLayer() {
        BuiltInAgents.GateConfig g = gate();
        g.setPromptAlignSettingsResolver(null);
        g.setCoordinatorMode(coordinatorOn());

        assertThat(BuiltInAgents.getBuiltInAgents().stream().map(AgentDefinition::agentType))
            .containsExactly("worker");
    }

    // ─────────────────────── 4. 静态桥静态可达性（防「桥从没被注入」的静默失效） ───────────────────────

    @Test
    @DisplayName("GateConfig 是 Spring 扫描候选 —— 生产 base package com.nexusai 下 coordinator 桥真的会被注入")
    void gateConfigIsSpringScanCandidate() {
        // WHY: 上面所有用例都直接调 setter，所以「Spring 从没实例化 GateConfig」这种失效它们<b>照不出红</b>
        //   ——分支在生产恒不激活，而测试全绿（对齐教训 test-env-failloud-unreachable：
        //   「门结构上不可达」不是「门关着」）。本用例用 Spring 自己的扫描器在生产 base package 上验证
        //   GateConfig 确实是候选组件，把「桥可被注入」这一前提钉死。
        ClassPathScanningCandidateComponentProvider provider =
            new ClassPathScanningCandidateComponentProvider(true);

        Set<String> candidates = provider
            .findCandidateComponents("com.nexusai.application.agent.subagent").stream()
            .map(BeanDefinition::getBeanClassName)
            .collect(Collectors.toSet());

        assertThat(candidates)
            .as("GateConfig 不在候选里 → @Value/@Autowired setter 永不执行 → 静态门恒为默认值；"
                + "coordinator 分支在生产永远打不开（而 worker 定义本身仍是对的，故障静默）")
            .contains("com.nexusai.application.agent.subagent.BuiltInAgents$GateConfig");
    }

    @Test
    @DisplayName("Spring 实例化 GateConfig 时两个 @Autowired setter 真被调用（扫描候选 ≠ 注入生效）")
    void gateConfigAutowiredSettersActuallyPopulateBridges() {
        // WHY: 上一条只证明「GateConfig 会被注册」。「注册了但 @Autowired 方法没被调用 / 被改坏」是独立
        //   的失效面（方法改名、注解丢失、参数类型不匹配 → Spring 静默跳过），而那时生产分支恒不激活
        //   且所有其它测试照绿。本用例真起一个最小容器（只注册 GateConfig + 单个 stub bean），并用
        //   「先复位成关、再断言为开」的方式证明注入确实发生（否则断言会是 false → 红）。

        // ── 第一段：只提供 PromptAlignSettingsResolver（DB 层），验证 setPromptAlignSettingsResolver 被调 ──
        BuiltInAgents.GateConfig g = gate();
        g.setCoordinatorMode(null);                            // 第 1 层显式关 → 若 DB 层没注入则整体为 false
        g.setPromptAlignSettingsResolver(null);
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(BuiltInAgents.GateConfig.class);
            ctx.registerBean("promptAlignSettingsResolver", PromptAlignSettingsResolver.class,
                () -> resolverWith(true));
            ctx.refresh();

            assertThat(BuiltInAgents.isCoordinatorModeActive())
                .as("DB 层 setter 未被调用 → 生产里 DB 勾选开关永不生效（而 WorkerAgentDefinitionTest 等全绿）")
                .isTrue();
        }

        // ── 第二段：只提供 CoordinatorMode（env+feature 层），验证 setCoordinatorMode 被调 ──
        g.setCoordinatorMode(null);
        g.setPromptAlignSettingsResolver(null);
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(BuiltInAgents.GateConfig.class);
            ctx.registerBean("coordinatorMode", CoordinatorMode.class, BuiltInAgentsCoordinatorTest::coordinatorOn);
            ctx.refresh();

            assertThat(BuiltInAgents.isCoordinatorModeActive())
                .as("env+feature 层 setter 未被调用 → CC 原判定链在 Java 侧断线")
                .isTrue();
        }
    }
}
