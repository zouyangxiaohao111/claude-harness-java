package com.nexusai.application.agent.compact;

import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-06] 阈值体系单测 · 对齐 CC {@code autoCompact.ts:30-145}
 * （getEffectiveContextWindowSize / getAutoCompactThreshold / calculateTokenWarningState）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9）</b>: 阈值/blocking 窗口从固定 200_000 改为 CC model-aware
 * 统一窗口（reserved 减法 + env 覆盖 + 四态）是 P1 阈值体系的验收核心。本测试锁定：
 * <ol>
 *   <li>窗口计算：effectiveWindow − 13_000；blocking = effectiveWindow − 3_000；reserved 减法</li>
 *   <li>[W3-1] 窗口收窄：DB settings.auto_compact_window 权威（替代 CLAUDE_CODE_AUTO_COMPACT_WINDOW
 *       env，env 路已删）；PCT / BLOCKING override env 保留</li>
 *   <li>四态计算：warning / error / auto / blocking + percentLeft</li>
 *   <li>blocking 与 auto 阈值同源（同一 effectiveWindow 来源）</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: 基线（fixed 200_000）下本类引用的 {@link CompactThresholdSystem} 不存在
 * （编译失败）→ 目标 API 未实现；且 fixed 200k 窗口下 effectiveWindow=200_000−20_000 断言不可能成立。
 *
 * <p><b>env 注入方式</b>: 逻辑单测直接构造 {@link CompactEnvProperties}（override 值），
 * env 绑定单测用 {@link ApplicationContextRunner#withEnvironment} 真实注入环境变量
 * （CLAUDE_CODE_AUTO_COMPACT_WINDOW 等），经 Spring 宽松绑定到 {@code claude.*} 属性。
 */
class CompactThresholdSystemTest {

    /** 默认模型（非 [1m]，不命中任何已知模型族 → default 输出 32k → reserved=20k）。 */
    private static final String MODEL = "test-model";

    // ════════════════════════════════════════════════════════════════════
    // 1. 窗口计算（reserved 减法 + model-aware）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("默认模型: effectiveWindow=200000−20000=180000, threshold=180000−13000=167000, blocking=180000−3000=177000")
    void defaultModel_windowThresholdBlocking() {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);

        assertThat(ts.getContextWindowForModel(MODEL)).as("getContextWindowForModel 默认 200_000").isEqualTo(200_000);
        // reserved 减法: min(getMaxOutputTokensForModel(test-model)=32k, 20k) = 20_000
        assertThat(ts.getEffectiveContextWindowSize(MODEL)).as("effectiveWindow = 200000 − 20000").isEqualTo(180_000);
        assertThat(ts.getAutoCompactThreshold(MODEL)).as("threshold = effectiveWindow − 13000").isEqualTo(167_000);
        assertThat(ts.getBlockingLimit(MODEL)).as("blocking = effectiveWindow − 3000").isEqualTo(177_000);
    }

    @Test
    @DisplayName("model-aware: [1m] 模型 1M 窗口; claude-3-sonnet/opus reserved 按模型族")
    void modelAware_reservedSubtractionByModelFamily() {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);

        // [1m] 后缀 → 1_000_000（CC context.ts:70）
        assertThat(ts.getContextWindowForModel("opus[1m]")).isEqualTo(1_000_000);
        // opus-4-6 default 64k → reserved = min(64k, 20k) = 20k
        assertThat(ts.getEffectiveContextWindowSize("opus[1m]")).isEqualTo(1_000_000 - 20_000);

        // claude-3-sonnet default 8192 → reserved = 8192（小于 20k 时按模型族保留）
        assertThat(ts.getMaxOutputTokensForModel("claude-3-sonnet")).isEqualTo(8_192);
        assertThat(ts.getEffectiveContextWindowSize("claude-3-sonnet"))
            .as("effectiveWindow = 200000 − 8192（model-aware reserved）")
            .isEqualTo(200_000 - 8_192);

        // claude-3-opus default 4096 → reserved = 4096
        assertThat(ts.getMaxOutputTokensForModel("claude-3-opus")).isEqualTo(4_096);
        assertThat(ts.getEffectiveContextWindowSize("claude-3-opus"))
            .isEqualTo(200_000 - 4_096);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 窗口收窄（[W3-1] DB settings.auto_compact_window 权威；env CLAUDE_CODE_AUTO_COMPACT_WINDOW 路已删）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[W3-1] settings auto_compact_window 收窄: effectiveWindow=min(200000,100000)−20000=80000")
    void settingsAutoCompactWindowNarrowsWindow() {
        SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
        SettingsRecord record = new SettingsRecord();
        record.setAutoCompactWindow(100_000);
        Mockito.when(mapper.selectOneById(1)).thenReturn(record);

        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        ts.setSettingsMapper(mapper);

        assertThat(ts.getEffectiveContextWindowSize(MODEL)).as("settings 收窄 min(200000,100000)−20000").isEqualTo(80_000);
        assertThat(ts.getAutoCompactThreshold(MODEL)).as("threshold = 80000 − 13000").isEqualTo(67_000);
        assertThat(ts.getBlockingLimit(MODEL)).as("blocking = 80000 − 3000").isEqualTo(77_000);
    }

    @Test
    @DisplayName("[W3-1] settings 未配置/读取失败 → 不参与收窄（等价 CC env undefined）")
    void settingsUnconfiguredOrFailureNoNarrowing() {
        // 行存在但 window = null → 不参与
        SettingsMapper mapperNull = Mockito.mock(SettingsMapper.class);
        Mockito.when(mapperNull.selectOneById(1)).thenReturn(new SettingsRecord());
        CompactThresholdSystem tsNull = new CompactThresholdSystem(null);
        tsNull.setSettingsMapper(mapperNull);
        assertThat(tsNull.getEffectiveContextWindowSize(MODEL)).as("window=null 不收窄").isEqualTo(180_000);

        // window ≤ 0 → 不参与
        SettingsMapper mapperZero = Mockito.mock(SettingsMapper.class);
        SettingsRecord recordZero = new SettingsRecord();
        recordZero.setAutoCompactWindow(0);
        Mockito.when(mapperZero.selectOneById(1)).thenReturn(recordZero);
        CompactThresholdSystem tsZero = new CompactThresholdSystem(null);
        tsZero.setSettingsMapper(mapperZero);
        assertThat(tsZero.getEffectiveContextWindowSize(MODEL)).as("window=0 不收窄").isEqualTo(180_000);

        // 读取异常 → 不参与（fail-loud: warn 日志，不阻断主流程）
        SettingsMapper mapperThrows = Mockito.mock(SettingsMapper.class);
        Mockito.when(mapperThrows.selectOneById(1)).thenThrow(new RuntimeException("db down"));
        CompactThresholdSystem tsThrows = new CompactThresholdSystem(null);
        tsThrows.setSettingsMapper(mapperThrows);
        assertThat(tsThrows.getEffectiveContextWindowSize(MODEL)).as("读取失败不收窄").isEqualTo(180_000);
    }

    @Test
    @DisplayName("[W3-1] env CLAUDE_CODE_AUTO_COMPACT_WINDOW 不再参与计算（残留值不生效，settings 权威）")
    void envAutoCompactWindowNoLongerParticipates() {
        CompactEnvProperties env = new CompactEnvProperties();
        env.setCodeAutoCompactWindow(100_000);
        CompactThresholdSystem ts = new CompactThresholdSystem(env);

        assertThat(ts.getEffectiveContextWindowSize(MODEL)).as("env 残留 100000 不生效").isEqualTo(180_000);
        assertThat(ts.getAutoCompactThreshold(MODEL)).as("threshold 不受 env 影响").isEqualTo(167_000);
    }

    @Test
    @DisplayName("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE 按百分比取 min: 50% → min(floor(180000*0.5),167000)=90000")
    void pctOverrideTakesMin() {
        CompactEnvProperties env = new CompactEnvProperties();
        env.setAutocompactPctOverride(50.0);
        CompactThresholdSystem ts = new CompactThresholdSystem(env);

        assertThat(ts.getAutoCompactThreshold(MODEL))
            .as("percentageThreshold = floor(180000*0.5) = 90000 < 167000 → min = 90000")
            .isEqualTo(90_000);
    }

    @Test
    @DisplayName("PCT_OVERRIDE=100% → min(180000,167000)=167000; 非法 150 → 忽略回落默认")
    void pctOverrideBoundaries() {
        CompactEnvProperties env100 = new CompactEnvProperties();
        env100.setAutocompactPctOverride(100.0);
        assertThat(new CompactThresholdSystem(env100).getAutoCompactThreshold(MODEL))
            .as("100% → percentageThreshold=180000 但 min(180000,167000)=167000")
            .isEqualTo(167_000);

        CompactEnvProperties envInvalid = new CompactEnvProperties();
        envInvalid.setAutocompactPctOverride(150.0);
        assertThat(new CompactThresholdSystem(envInvalid).getAutoCompactThreshold(MODEL))
            .as(">100 → 忽略 override，回落 167000")
            .isEqualTo(167_000);
    }

    @Test
    @DisplayName("CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE 直接覆盖: 50000; 非法(≤0)忽略")
    void blockingLimitOverride() {
        CompactEnvProperties env = new CompactEnvProperties();
        env.setCodeBlockingLimitOverride(50_000);
        CompactThresholdSystem ts = new CompactThresholdSystem(env);

        assertThat(ts.getBlockingLimit(MODEL)).as("override 50000 直接替换默认 177000").isEqualTo(50_000);

        CompactEnvProperties envInvalid = new CompactEnvProperties();
        envInvalid.setCodeBlockingLimitOverride(0);
        assertThat(new CompactThresholdSystem(envInvalid).getBlockingLimit(MODEL))
            .as("≤0 → 忽略 override，回落 177000")
            .isEqualTo(177_000);
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 四态计算 + percentLeft
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("四态边界: 低于 warning → 全 false; 越过各阈值逐级点亮")
    void fourStatesBoundaries() {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        // threshold=167000, warningThreshold=errorThreshold=147000, autoThreshold=167000, blocking=177000

        CompactThresholdSystem.TokenWarningState low =
            ts.calculateTokenWarningState(1_000, MODEL, true);
        assertThat(low.isAboveWarningThreshold()).isFalse();
        assertThat(low.isAboveErrorThreshold()).isFalse();
        assertThat(low.isAboveAutoCompactThreshold()).isFalse();
        assertThat(low.isAtBlockingLimit()).isFalse();
        assertThat(low.percentLeft()).as("percentLeft = round((167000−1000)/167000*100) = 99")
            .isEqualTo(99);

        // usage = warningThreshold(147000) → warning + error（warning/error 同 buffer 20k）点亮
        CompactThresholdSystem.TokenWarningState warn =
            ts.calculateTokenWarningState(147_000, MODEL, true);
        assertThat(warn.isAboveWarningThreshold()).isTrue();
        assertThat(warn.isAboveErrorThreshold()).isTrue();
        assertThat(warn.isAboveAutoCompactThreshold()).isFalse();
        assertThat(warn.isAtBlockingLimit()).isFalse();

        // usage = autoThreshold(167000) → auto 点亮
        CompactThresholdSystem.TokenWarningState auto =
            ts.calculateTokenWarningState(167_000, MODEL, true);
        assertThat(auto.isAboveAutoCompactThreshold()).isTrue();
        assertThat(auto.isAtBlockingLimit()).isFalse();
        assertThat(auto.percentLeft()).isZero();

        // usage = blocking(177000) → blocking 点亮
        CompactThresholdSystem.TokenWarningState blocking =
            ts.calculateTokenWarningState(177_000, MODEL, true);
        assertThat(blocking.isAtBlockingLimit()).isTrue();
        assertThat(blocking.isAboveAutoCompactThreshold()).isTrue();
    }

    @Test
    @DisplayName("autoCompactEnabled=false → threshold=effectiveWindow, auto 态恒 false")
    void autoCompactDisabledUsesEffectiveWindow() {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        // effectiveWindow=180000; usage=170000 < blocking(177000) 但 > warning(160000)
        CompactThresholdSystem.TokenWarningState state =
            ts.calculateTokenWarningState(170_000, MODEL, false);

        assertThat(state.isAboveAutoCompactThreshold())
            .as("auto 禁用 → isAboveAutoCompactThreshold 恒 false")
            .isFalse();
        assertThat(state.isAboveWarningThreshold())
            .as("threshold=180000 → warning=160000, 170000 ≥ 160000 → true")
            .isTrue();
        assertThat(state.isAtBlockingLimit()).as("170000 < 177000 → 不阻塞").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. blocking 与 auto 阈值同源（同一 effectiveWindow 来源）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("blocking 与 auto 阈值同源: blocking = getEffectiveContextWindowSize − 3000（含 settings 收窄联动）")
    void blockingSameSourceAsAutoThreshold() {
        CompactThresholdSystem ts = new CompactThresholdSystem(null);
        assertThat(ts.getBlockingLimit(MODEL))
            .as("blocking = effectiveWindow(180000) − 3000")
            .isEqualTo(ts.getEffectiveContextWindowSize(MODEL) - 3_000);

        // [W3-1] settings 收窄后同源仍成立
        SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
        SettingsRecord record = new SettingsRecord();
        record.setAutoCompactWindow(100_000);
        Mockito.when(mapper.selectOneById(1)).thenReturn(record);
        CompactThresholdSystem narrowed = new CompactThresholdSystem(null);
        narrowed.setSettingsMapper(mapper);
        assertThat(narrowed.getBlockingLimit(MODEL))
            .as("settings 收窄后 blocking = 80000 − 3000，与 threshold 同一 effectiveWindow")
            .isEqualTo(narrowed.getEffectiveContextWindowSize(MODEL) - 3_000)
            .isEqualTo(77_000);
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. env 绑定（真实注入环境变量 → Spring 宽松绑定 → CompactEnvProperties）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("env 绑定: PCT_OVERRIDE=50 / BLOCKING_LIMIT_OVERRIDE=50000（AUTO_COMPACT_WINDOW 已删读取路, W3-1）")
    void envVarsBindToThresholdSystem() {
        // Spring 宽松绑定: env CLAUDE_AUTOCOMPACT_PCT_OVERRIDE → 属性 claude.autocompact-pct-override
        // （withPropertyValues 走与 env 完全相同的 relaxed-binding 路径，Spring Boot 3.5 移除了 withEnvironment）
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CompactThresholdConfig.class)
            .withPropertyValues(
                "claude.autocompact-pct-override=50",
                "claude.code-blocking-limit-override=50000");

        runner.run(ctx -> {
            CompactThresholdSystem ts = ctx.getBean(CompactThresholdSystem.class);
            // [W3-1] auto-compact-window env 不再参与 → effectiveWindow = 200000 − 20000 = 180000
            assertThat(ts.getEffectiveContextWindowSize(MODEL)).isEqualTo(180_000);
            // threshold = min(floor(180000*0.5)=90000, 180000−13000=167000) = 90000
            assertThat(ts.getAutoCompactThreshold(MODEL)).isEqualTo(90_000);
            // blocking = override 50000（默认 180000−3000=177000 被覆盖）
            assertThat(ts.getBlockingLimit(MODEL)).isEqualTo(50_000);
        });
    }
}
