package com.nexusai.application.agent.compact;

import com.nexusai.infra.llm.AnthropicSdkProvider;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP2-25 · env 覆盖移植契约测试（M-1 cap+env / M-2 isEnvTruthy 'on' / M-3 DISABLE_1M 门 / OD-16 窗口 env）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9）</b>: CC 的 env 层是生产可调通道（OD-16 ADJUDICATED 全量移植）：
 * <ol>
 *   <li><b>M-1</b> {@code getMaxOutputTokensForModel} = 模型族 default → {@code tengu_otk_slot_v1} cap(8k)
 *       → {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} 有界 override（claude.ts:3399-3419 + envValidation.ts:9-38）；
 *       compact 域两个宿主（CompactThresholdSystem / StreamCompactSummary）必须与
 *       {@link AnthropicSdkProvider}（CC 宿主 Java 等价位）单一来源收敛，无第二张漂移表。</li>
 *   <li><b>M-2</b> {@code isEnvTruthy} 真值集 = {'1','true','yes','on'}（envUtils.ts:32-37）；
 *       DISABLE_COMPACT=on / DISABLE_AUTO_COMPACT=on 必须生效（autoCompact.ts:147-158）。</li>
 *   <li><b>M-3</b> {@code CLAUDE_CODE_DISABLE_1M_CONTEXT} 门（context.ts:31-40）：禁用时
 *       [1m] 模型不得按 1M 窗口，resolver 超 200k 钳制回落 200k（context.ts:75-81）。</li>
 *   <li><b>OD-16</b> 窗口收窄与 M-1 cap 联动（reserved 减法用解析后值）；[W3-1] 收窄源已从
 *       CLAUDE_CODE_AUTO_COMPACT_WINDOW env 迁移至 DB settings.auto_compact_window（settings 权威）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>:
 * <ul>
 *   <li>M-1 cap：当前 CompactThresholdSystem 仅模型族 default 表（注释自认 IMP-15 委派）——
 *       cap 开启断言 8_000 打红（现状 32_000）；StreamCompactSummary 缺 3-5-sonnet/3-5-haiku 族
 *       收敛断言打红（现状回落 32_000，canonical 8_192）。</li>
 *   <li>M-2 'on'：当前 AutoCompactor.isEnvTruthy 仅 1/true/yes —— DISABLE_COMPACT=on 断言 false 打红（现状 true）。</li>
 *   <li>M-3 门：当前 has1mContext 无 DISABLE_1M 门 —— 禁用后 1M 断言打红（现状仍 1_000_000）。</li>
 * </ul>
 *
 * <p><b>env 注入方式</b>: cap 经系统属性 {@code nexusai.feature.tengu-otk-slot-v1}（生产 gate 载体，
 * AnthropicSdkProvider.isMaxTokensCapEnabled）；DISABLE_* 经 {@link AutoCompactor#setEnvProvider}
 * 注入 map（Java 无法改真实 env）；DISABLE_1M 经 {@link CompactEnvProperties#setDisable1MContext}
 * （Spring 宽松绑定 CLAUDE_CODE_DISABLE_1M_CONTEXT → claude.code-disable-1m-context，StringToBooleanConverter
 * 接受 CC 全真值集 1/true/yes/on）。
 */
class EnvOverridesCcContractTest {

    /** 默认模型（非 [1m]，不命中任何已知模型族 → default 32k）。 */
    private static final String MODEL = "test-model";

    /** CC original: tengu_otk_slot_v1 growthbook flag 的 Java 系统属性载体（AnthropicSdkProvider:107-109）。 */
    private static final String TENGU_OTK_SLOT_V1_PROPERTY = "nexusai.feature.tengu-otk-slot-v1";

    @AfterEach
    void clearCapProperty() {
        System.clearProperty(TENGU_OTK_SLOT_V1_PROPERTY);
    }

    // ════════════════════════════════════════════════════════════════════
    // M-1 · getMaxOutputTokensForModel cap+env（claude.ts:3399-3419）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("M-1: tengu_otk_slot_v1 cap 开启 → default 封顶 8000（CC claude.ts:3408-3410）")
    void capEnabledCapsMaxOutputTokens() {
        System.setProperty(TENGU_OTK_SLOT_V1_PROPERTY, "true");
        CompactThresholdSystem ts = new CompactThresholdSystem(null);

        // sonnet-4-6 default 32k → cap 8k
        assertThat(ts.getMaxOutputTokensForModel("sonnet-4-6")).isEqualTo(8_000);
        // reserved 减法用解析后值: effectiveWindow = 200000 − min(8000, 20000) = 192000
        assertThat(ts.getEffectiveContextWindowSize("sonnet-4-6")).isEqualTo(200_000 - 8_000);
        // claude-3-opus default 4096 低于 cap → 保持原生值（Math.min 语义，CC claude.ts:3405-3406）
        assertThat(ts.getMaxOutputTokensForModel("claude-3-opus")).isEqualTo(4_096);
    }

    @Test
    @DisplayName("M-1: cap 关（3P 默认）→ 模型族 default 原值")
    void capOffUsesModelFamilyDefault() {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        assertThat(ts.getMaxOutputTokensForModel("sonnet-4-6")).isEqualTo(32_000);
        assertThat(ts.getMaxOutputTokensForModel("opus-4-6")).isEqualTo(64_000);
    }

    @Test
    @DisplayName("M-1: 收敛——compact 域两宿主与 AnthropicSdkProvider 单一来源同值（D-29 双实现漂移防线）")
    void compactHostsConvergedOntoCanonical() {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        // 含 StreamCompactSummary 旧表缺失的 3-5-sonnet/3-5-haiku 族（旧回落 32k，canonical 8192）
        for (String m : List.of("claude-3-opus", "claude-3-sonnet", "claude-3-haiku",
                "opus-4-6", "sonnet-4-6", "3-5-sonnet", "3-5-haiku", "3-7-sonnet", MODEL)) {
            int canonical = AnthropicSdkProvider.getMaxOutputTokensForModel(m);
            assertThat(ts.getMaxOutputTokensForModel(m))
                .as("CompactThresholdSystem 收敛 %s", m)
                .isEqualTo(canonical);
            assertThat(StreamCompactSummary.getMaxOutputTokensForModel(m))
                .as("StreamCompactSummary 收敛 %s", m)
                .isEqualTo(canonical);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // M-2 · isEnvTruthy 'on'（envUtils.ts:32-37，autoCompact.ts:147-158）
    // ════════════════════════════════════════════════════════════════════

    private static AutoCompactor autoCompactorWithEnv(Map<String, String> env) {
        AutoCompactor ac = new AutoCompactor(msgs -> 0,
            (prompt, msgs) -> new CompactConversation.SummaryResult("", null));
        ac.setEnvProvider(env::get);
        return ac;
    }

    @Test
    @DisplayName("M-2: DISABLE_COMPACT=on → isAutoCompactEnabled=false（CC 'on' 真值）")
    void disableCompactOnDisablesAutoCompact() {
        assertThat(autoCompactorWithEnv(Map.of("DISABLE_COMPACT", "on")).isAutoCompactEnabled())
            .isFalse();
    }

    @Test
    @DisplayName("M-2: DISABLE_AUTO_COMPACT=on → isAutoCompactEnabled=false")
    void disableAutoCompactOnDisablesAutoCompact() {
        assertThat(autoCompactorWithEnv(Map.of("DISABLE_AUTO_COMPACT", "on")).isAutoCompactEnabled())
            .isFalse();
    }

    @Test
    @DisplayName("M-2: DISABLE_COMPACT=on → shouldAutoCompact 早退 false（autoCompact.ts:185-187 链）")
    void disableCompactOnEarlyReturnsShouldAutoCompact() {
        assertThat(autoCompactorWithEnv(Map.of("DISABLE_COMPACT", "on"))
                .shouldAutoCompact(List.of(), "user", 0))
            .isFalse();
    }

    @Test
    @DisplayName("M-2: 未设置 env → 回落 userConfig.autoCompactEnabled（默认 true，可关）")
    void noEnvFallsBackToUserConfig() {
        AutoCompactor ac = autoCompactorWithEnv(Map.of());
        assertThat(ac.isAutoCompactEnabled()).isTrue();
        ac.setAutoCompactEnabled(false);
        assertThat(ac.isAutoCompactEnabled()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // M-3 · CLAUDE_CODE_DISABLE_1M_CONTEXT 门（context.ts:31-40/70-83）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("M-3: 未禁用 → [1m] 模型 has1mContext=true 且窗口 1_000_000")
    void has1mWindowWhenNotDisabled() {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        assertThat(ts.has1mContext("claude-sonnet-4-6[1m]")).isTrue();
        assertThat(ts.getContextWindowForModel("claude-sonnet-4-6[1m]")).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("M-3: DISABLE_1M=true → has1mContext=false 且 [1m] 窗口回落 200k（context.ts:36-38）")
    void disable1mGatesHas1mContext() {
        CompactEnvProperties env = new CompactEnvProperties();
        env.setDisable1MContext(true);
        CompactThresholdSystem ts = new CompactThresholdSystem(env);

        assertThat(ts.has1mContext("claude-sonnet-4-6[1m]")).isFalse();
        assertThat(ts.getContextWindowForModel("claude-sonnet-4-6[1m]")).isEqualTo(200_000);
    }

    @Test
    @DisplayName("M-3: DISABLE_1M=true 且 resolver 超 200k → 钳制回落 200k（context.ts:75-81）")
    void disable1mClampsResolverAboveDefault() {
        CompactEnvProperties env = new CompactEnvProperties();
        env.setDisable1MContext(true);
        CompactThresholdSystem ts = new CompactThresholdSystem(env);
        ts.setModelContextWindowResolver(m -> 500_000);
        assertThat(ts.getContextWindowForModel(MODEL)).isEqualTo(200_000);

        // 对照：未禁用时 resolver 原值直通
        CompactThresholdSystem enabled = new CompactThresholdSystem(new CompactEnvProperties());
        enabled.setModelContextWindowResolver(m -> 500_000);
        assertThat(enabled.getContextWindowForModel(MODEL)).isEqualTo(500_000);
    }

    // ════════════════════════════════════════════════════════════════════
    // OD-16 · 窗口 env 与 M-1 cap 联动（reserved 减法同源）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OD-16: [W3-1] settings auto_compact_window 收窄 + cap 联动 = min(200000,100000)−8000")
    void windowSettingsNarrowsWithCap() {
        SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
        SettingsRecord record = new SettingsRecord();
        record.setAutoCompactWindow(100_000);
        Mockito.when(mapper.selectOneById(1)).thenReturn(record);

        System.setProperty(TENGU_OTK_SLOT_V1_PROPERTY, "true");
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        ts.setSettingsMapper(mapper);

        // sonnet-4-6 cap 后 reserved=8000（解析后值，非旧表 20000）→ min(200000,100000)−8000
        assertThat(ts.getEffectiveContextWindowSize("sonnet-4-6")).isEqualTo(100_000 - 8_000);
    }
}
