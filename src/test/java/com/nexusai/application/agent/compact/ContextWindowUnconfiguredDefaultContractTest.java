package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * 「模型上下文窗口未配置时的默认值」前后端契约测试（2026-09-11）。
 *
 * <h2>WHY（规则九 · 意图而非行为）</h2>
 * 前端契约承诺「留空 = 1M」（{@code front/src/components/ui/ModelFormModal.tsx:88}
 * 「模型上下文窗口 · 1M=1048576 · 留空=1M」），后端却对「查不到窗口」回落 CC 的 200_000
 * —— 两端口径不一致。实测事故：{@code models.deepseek-flash.max_context_tokens = NULL}
 * → 窗口回落 200_000 → autoCompact 阈值 179_000 → 会话在 <b>92.6 万</b> tokens 就被压缩
 * （按 1M 应 ~98.7 万才压）→「该不压时压了」。
 *
 * <p>本类把四条契约钉死：
 * <ol>
 *   <li><b>未配置/查不到 → 1_048_576</b>（事故路径：DB 行 NULL / 行缺失 / resolver 未注入）</li>
 *   <li><b>1M 被禁用 → 仍收窄到 200_000</b>（回归锁：{@code MODEL_CONTEXT_WINDOW_DEFAULT}
 *       的「收窄目标」职责不得被本批改动破坏）</li>
 *   <li><b>已配置的窗口按配置值</b>（90k 就是 90k，既不被抬到 1M 也不被抬到 200k）</li>
 *   <li><b>阈值口径与展示口径同源</b>（同一 model → {@link CompactThresholdSystem} 与
 *       {@link ContextUsageCalculator} 得到同一窗口）</li>
 * </ol>
 *
 * <p><b>RED teeth</b>：把 {@code CONTEXT_WINDOW_UNCONFIGURED_DEFAULT} 改回 200_000（或把
 * {@code MODEL_CONTEXT_WINDOW_DEFAULT} 拿去当回落值）→ 用例 1/2/4/5 红；把
 * {@code is1mContextDisabled()} 收窄分支删掉 → 用例 2 红；恢复「&lt;100k 能力门」→ 用例 3 红。
 */
class ContextWindowUnconfiguredDefaultContractTest {

    private static final String MODEL = "test-model-ctxwin";

    // ════════════════════════════════════════════════════════════════════
    // 0. 常量本身：两个 200_000 / 1_048_576 语义互斥
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("常量拆分: 未配置默认=1_048_576（前端契约）· 1M 禁用收窄目标=200_000（CC 原义）")
    void constantsAreSplitByRole() {
        assertThat(CompactConstants.CONTEXT_WINDOW_UNCONFIGURED_DEFAULT)
            .as("本产品「未配置窗口」默认值必须与前端 留空=1M 一致（1M = 1048576）")
            .isEqualTo(1_048_576);
        assertThat(CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT)
            .as("CC MODEL_CONTEXT_WINDOW_DEFAULT 原义 = 200_000，只服务 1M 禁用收窄")
            .isEqualTo(200_000);
        assertThat(CompactConstants.CONTEXT_1M_WINDOW)
            .as("CC [1m] 显式 opt-in 值 = 1_000_000，与「未配置默认」1_048_576 数值不同、不得互换")
            .isEqualTo(1_000_000)
            .isNotEqualTo(CompactConstants.CONTEXT_WINDOW_UNCONFIGURED_DEFAULT);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 未配置/查不到 → 1_048_576（事故路径）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("① DB 行 max_context_tokens=NULL（事故原形）→ 生产 resolver 回落 1_048_576")
    void nullRow_fallsBackToUnconfiguredDefault() throws Exception {
        ModelRecord row = new ModelRecord();
        row.setMaxContextTokens(null); // 事故当时的真实数据形态

        assertThat(productionResolverWindow(row))
            .as("deepseek-flash 行 max_context_tokens=NULL 时必须按 1M 计，而非 200_000")
            .isEqualTo(1_048_576)
            .isEqualTo(CompactConstants.CONTEXT_WINDOW_UNCONFIGURED_DEFAULT);
    }

    @Test
    @DisplayName("② DB 行缺失（ModelNameResolver 未命中）→ 生产 resolver 回落 1_048_576")
    void missingRow_fallsBackToUnconfiguredDefault() throws Exception {
        assertThat(productionResolverWindow(null))
            .as("模型查不到时同样是「未配置」→ 1M")
            .isEqualTo(1_048_576);
    }

    @Test
    @DisplayName("③ resolver 返回 ≤ 0（有配置源但该模型无值）→ 1_048_576；无配置源（未注入）→ 保持 CC 末位 200_000")
    void resolverAbsentOrZero_fallsBackToUnconfiguredDefault() {
        // resolver 显式返回 0 = 有 DB 配置源、但该模型 max_context_tokens 为空 → 「未配置窗口」
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        ts.setModelContextWindowResolver(m -> 0);
        assertThat(ts.getContextWindowForModel(MODEL))
            .as("有配置源但无值 = 用户没配窗口 → 1M（前端契约 留空=1M）")
            .isEqualTo(1_048_576);

        // 「本部署无窗口配置源」（resolver 未注入 / Spring 无关的单测场景）→ CC 末位默认，不变
        assertThat(new CompactThresholdSystem(null).getContextWindowForModel(MODEL))
            .as("无配置源 ≠ 未配置窗口：保持 CC MODEL_CONTEXT_WINDOW_DEFAULT（200_000），本批不改")
            .isEqualTo(200_000);
        CompactThresholdSystem cleared = new CompactThresholdSystem(null);
        cleared.setModelContextWindowResolver(m -> 0);
        cleared.setModelContextWindowResolver(null);
        assertThat(cleared.getContextWindowForModel(MODEL)).isEqualTo(200_000);
    }

    @Test
    @DisplayName("④ 生产装配链（AgentLoopContextFactory 注入 resolver）: NULL 行 → CompactThresholdSystem 得 1M")
    void wiredProductionChain_nullRow_yields1M() throws Exception {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        AgentLoopContextFactory factory = new AgentLoopContextFactory();
        setField(factory, "compactThresholdSystem", ts);
        setField(factory, "modelMapper", modelMapperReturning(rowWith(null)));
        setField(factory, "providerMapper", Mockito.mock(ProviderMapper.class));
        factory.wireThresholdSystemResolver();

        assertThat(ts.getContextWindowForModel(MODEL))
            .as("事故路径（生产装配链）必须得 1M —— 修前为 200_000")
            .isEqualTo(1_048_576);
    }

    @Test
    @DisplayName("⑤ 阈值派生回归: 未配置窗口 → autoCompact 阈值基于 1M（≥900k），不再 179_000")
    void unconfiguredWindow_thresholdFollows1M() {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        ts.setModelContextWindowResolver(m -> 0); // 生产 resolver 对 NULL 行的实际返回（见 ①/④）

        assertThat(ts.getEffectiveContextWindowSize(MODEL))
            .as("effectiveWindow = 1_048_576 − reserved(20_000)")
            .isEqualTo(1_048_576 - 20_000);
        assertThat(ts.getAutoCompactThreshold(MODEL))
            .as("阈值必须随 1M 窗口派生（事故里是 179_000，导致 92.6 万 tokens 就被压缩）")
            .isGreaterThan(900_000)
            .isNotEqualTo(179_000);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. [回归锁] 1M 被禁用 → 仍收窄到 200_000（这一职不得改坏）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⑥ CLAUDE_CODE_DISABLE_1M_CONTEXT: resolved > 200k → 收窄 200_000（不参与本批「回落」改动）")
    void disable1mClampsTo200k() {
        CompactEnvProperties env = new CompactEnvProperties();
        env.setDisable1MContext(true);
        CompactThresholdSystem ts = new CompactThresholdSystem(env);

        ts.setModelContextWindowResolver(m -> 500_000);
        assertThat(ts.getContextWindowForModel(MODEL))
            .as("1M 禁用 → 500k 收窄到 200_000（CC context.ts:76-82，HIPAA）")
            .isEqualTo(200_000);

        // 未配置默认值（1_048_576）同样在禁用下被收窄
        ts.setModelContextWindowResolver(m -> 0);
        assertThat(ts.getContextWindowForModel(MODEL))
            .as("1M 禁用时「未配置默认 1M」也必须收窄到 200_000")
            .isEqualTo(200_000);

        // 对照：未禁用 → 原值直通（不误收窄）
        CompactThresholdSystem enabled = new CompactThresholdSystem(new CompactEnvProperties());
        enabled.setModelContextWindowResolver(m -> 500_000);
        assertThat(enabled.getContextWindowForModel(MODEL)).isEqualTo(500_000);
        enabled.setModelContextWindowResolver(m -> 0);
        assertThat(enabled.getContextWindowForModel(MODEL)).isEqualTo(1_048_576);
    }

    @Test
    @DisplayName("⑦ 1M 禁用: [1m] 后缀模型 has1mContext=false → 走未配置默认（1M）后再收窄 200_000")
    void disable1m_gates1mSuffix_thenClamped() {
        CompactEnvProperties env = new CompactEnvProperties();
        env.setDisable1MContext(true);
        CompactThresholdSystem ts = new CompactThresholdSystem(env);

        assertThat(ts.has1mContext("claude-sonnet-4-6[1m]")).isFalse();
        assertThat(ts.getContextWindowForModel("claude-sonnet-4-6[1m]"))
            .as("禁用后 [1m] 恒定 false → 未配置默认 1M → 收窄 200_000（CC context.ts:36-38 + 76-82）")
            .isEqualTo(200_000);

        // 未禁用 → [1m] 显式 opt-in 优先，1_000_000（不是 1_048_576）
        CompactThresholdSystem enabled = new CompactThresholdSystem(new CompactEnvProperties());
        assertThat(enabled.getContextWindowForModel("claude-sonnet-4-6[1m]")).isEqualTo(1_000_000);
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 已配置的窗口 → 按配置值（不被默认值 / 能力门吞掉）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⑧ 正常配置的小窗口 90k → 按配置值（既不被抬到 1M，也不被抬到 200k）")
    void configured90k_isHonored() throws Exception {
        // 生产 resolver 链（DB 直配 90k）
        assertThat(productionResolverWindow(rowWith(90_000)))
            .as("用户显式配置 90k 必须原样采用 —— 修前被 <100k 能力门静默抬成 200k")
            .isEqualTo(90_000);

        // 阈值系统链
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        ts.setModelContextWindowResolver(m -> 90_000);
        assertThat(ts.getContextWindowForModel(MODEL))
            .as("阈值口径同样按配置值 90k")
            .isEqualTo(90_000);
    }

    @Test
    @DisplayName("⑨ 事故数据形态 1_048_567（手滑少 9）与 1_048_576 都原样采用")
    void configuredNear1M_isHonored() {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        ts.setModelContextWindowResolver(m -> 1_048_567);
        assertThat(ts.getContextWindowForModel(MODEL))
            .as("已配置值 > 0 一律原样采用，不被「未配置默认」覆盖")
            .isEqualTo(1_048_567);

        ts.setModelContextWindowResolver(m -> 1_048_576);
        assertThat(ts.getContextWindowForModel(MODEL)).isEqualTo(1_048_576);
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 同源：阈值口径 == 展示口径（同一 model 同一窗口）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⑩ 同源: NULL 行 / 90k 行 / 未命中 三种数据下，阈值口径 == ContextUsageCalculator 展示口径")
    void thresholdAndDisplayShareSameWindowSource() {
        assertSameWindowForRow(rowWith(null));
        assertSameWindowForRow(rowWith(90_000));
        assertSameWindowForRow(null);
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** 用 mock DB 走生产 resolver（{@code AgentLoopContextFactory.resolveModelContextWindow}）取窗口。 */
    private static int productionResolverWindow(ModelRecord row) throws Exception {
        AgentLoopContextFactory factory = new AgentLoopContextFactory();
        setField(factory, "modelMapper", modelMapperReturning(row));
        setField(factory, "providerMapper", Mockito.mock(ProviderMapper.class));
        Method m = AgentLoopContextFactory.class.getDeclaredMethod("resolveModelContextWindow", String.class);
        m.setAccessible(true);
        return (int) m.invoke(factory, MODEL);
    }

    /** 同一份 mock DB 分别喂阈值口径与展示口径，断言同一窗口（同源）。 */
    private static void assertSameWindowForRow(ModelRecord row) {
        ModelMapper modelMapper = modelMapperReturning(row);
        ProviderMapper providerMapper = Mockito.mock(ProviderMapper.class);

        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        ts.setModelContextWindowResolver(m -> {
            ModelRecord r = com.nexusai.infra.llm.ModelNameResolver.resolve(modelMapper, providerMapper, m);
            return (r != null && r.getMaxContextTokens() != null) ? r.getMaxContextTokens() : 0;
        });
        int thresholdWindow = ts.getContextWindowForModel(MODEL);
        long displayWindow = ContextUsageCalculator
            .snapshot(modelMapper, providerMapper, MODEL, null).contextWindow();

        assertThat(thresholdWindow)
            .as("同一 model 下阈值口径与展示口径必须得到同一窗口（rows=%s）",
                row == null ? "缺失" : String.valueOf(row.getMaxContextTokens()))
            .isEqualTo((int) displayWindow);
    }

    private static ModelRecord rowWith(Integer maxContextTokens) {
        ModelRecord row = new ModelRecord();
        row.setMaxContextTokens(maxContextTokens);
        return row;
    }

    private static ModelMapper modelMapperReturning(ModelRecord row) {
        ModelMapper mapper = Mockito.mock(ModelMapper.class);
        Mockito.when(mapper.selectListByQuery(any())).thenReturn(row == null ? List.of() : List.of(row));
        return mapper;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
