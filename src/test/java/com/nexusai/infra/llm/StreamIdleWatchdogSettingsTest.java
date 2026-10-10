package com.nexusai.infra.llm;

import com.nexusai.application.agent.compact.CompactSettingsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link StreamIdleWatchdogSettings} 配置解析单测（对齐 CC 2.1.296 · 方案规格 §2.1）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：两个数值决定看门狗会不会「误杀正常长流」或「永不触发」：
 * <ol>
 *   <li><b>下限 300000</b>：若忘了 Math.max，用户在设置页填 60（秒被误当毫秒）会把每一次正常
 *       生成在 60ms 内掐断——灾难性误杀；而 CC 原语义就是「低于 300s 也会被抬到 300s」。</li>
 *   <li><b>开关默认开</b>：对齐 CC {@code ??!0}；若默认写反（未设 = 关），线上等于没上这个特性，
 *       且没有任何报错线索。</li>
 * </ol>
 * 解析核拆成包内静态方法（{@code parseEnabledSwitch} / {@code resolveIdleMs} / {@code parsePositiveLong}）
 * 以便不依赖进程 env 直接测全矩阵；{@link #idleMsReadsResolverThrough()} 另验 resolver 接线。
 */
@DisplayName("[V78] 流空闲看门狗配置解析（env > 设置 > 默认；300s 下限；默认开）")
class StreamIdleWatchdogSettingsTest {

    @AfterEach
    void tearDown() {
        StreamIdleWatchdogSettings.resetTestOverrides();
    }

    // ══════════════════════════ 开关 ══════════════════════════

    @Test
    @DisplayName("开关解析：未设/空/不可辨识 = 开；仅显式假值才关（对齐 CC triBool 默认开）")
    void enabledSwitchParsing() {
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch(null)).as("未设 = 开（CC ??!0）").isTrue();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("")).as("空串 = 开").isTrue();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("   ")).as("空白 = 开").isTrue();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("1")).isTrue();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("TRUE")).isTrue();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("yes")).isTrue();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("on")).isTrue();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("wat"))
            .as("不可辨识值按未设处理 = 开（对齐 CC triBool 解析失败 → 默认开）").isTrue();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("0")).as("显式假值才关").isFalse();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("false")).isFalse();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("NO")).as("大小写不敏感").isFalse();
        assertThat(StreamIdleWatchdogSettings.parseEnabledSwitch("off")).isFalse();
    }

    @Test
    @DisplayName("enabled()：测试钩子可强制开/关；复位后回 env 语义（本进程 env 未设 → 开）")
    void enabledHonorsTestOverride() {
        assertThat(StreamIdleWatchdogSettings.enabled()).as("未设 override → env 语义（未设 = 开）").isTrue();
        StreamIdleWatchdogSettings.setTestOverrides(false, null);
        assertThat(StreamIdleWatchdogSettings.enabled()).isFalse();
        StreamIdleWatchdogSettings.setTestOverrides(true, null);
        assertThat(StreamIdleWatchdogSettings.enabled()).isTrue();
        StreamIdleWatchdogSettings.resetTestOverrides();
        assertThat(StreamIdleWatchdogSettings.enabled()).isTrue();
    }

    // ══════════════════════════ 阈值 ══════════════════════════

    @Test
    @DisplayName("parsePositiveLong：null/空/非法 → -1；含符号/空格可解析")
    void parsePositiveLongMatrix() {
        assertThat(StreamIdleWatchdogSettings.parsePositiveLong(null)).isEqualTo(-1L);
        assertThat(StreamIdleWatchdogSettings.parsePositiveLong("")).isEqualTo(-1L);
        assertThat(StreamIdleWatchdogSettings.parsePositiveLong("abc")).isEqualTo(-1L);
        assertThat(StreamIdleWatchdogSettings.parsePositiveLong("60000")).isEqualTo(60000L);
        assertThat(StreamIdleWatchdogSettings.parsePositiveLong(" 60000 ")).isEqualTo(60000L);
        assertThat(StreamIdleWatchdogSettings.parsePositiveLong("0")).isEqualTo(0L);
        assertThat(StreamIdleWatchdogSettings.parsePositiveLong("-5")).isEqualTo(-5L);
    }

    @Test
    @DisplayName("resolveIdleMs 全矩阵：env>设置>默认；低于 300000 一律抬到下限（对齐 CC Math.max）")
    void resolveIdleMsMatrix() {
        long floor = StreamIdleWatchdogSettings.DEFAULT_IDLE_MS;
        assertThat(floor).as("下限即 300000（CC WJt 原文）").isEqualTo(300_000L);

        assertThat(StreamIdleWatchdogSettings.resolveIdleMs(null, null))
            .as("都没配 → 默认 300000").isEqualTo(floor);
        assertThat(StreamIdleWatchdogSettings.resolveIdleMs(null, 900_000))
            .as("设置值生效").isEqualTo(900_000L);
        assertThat(StreamIdleWatchdogSettings.resolveIdleMs("600000", 900_000))
            .as("env 优先于设置（CC: env 否则远端配置）").isEqualTo(600_000L);
        assertThat(StreamIdleWatchdogSettings.resolveIdleMs("60000", null))
            .as("env 低于下限 → 抬到 300000（防 60 秒误当毫秒级的灾难性误杀）").isEqualTo(floor);
        assertThat(StreamIdleWatchdogSettings.resolveIdleMs(null, 60_000))
            .as("设置值低于下限 → 同样抬到 300000（CC 原样，不做特例）").isEqualTo(floor);
        assertThat(StreamIdleWatchdogSettings.resolveIdleMs("abc", 900_000))
            .as("env 非法 → 回落设置值").isEqualTo(900_000L);
        assertThat(StreamIdleWatchdogSettings.resolveIdleMs("-5", null))
            .as("env 非正 → 视为未配置").isEqualTo(floor);
        assertThat(StreamIdleWatchdogSettings.resolveIdleMs(null, 0))
            .as("设置为 0（= 清回未配置）→ 默认").isEqualTo(floor);
        assertThat(StreamIdleWatchdogSettings.resolveIdleMs("300000", null))
            .as("恰为下限 → 原样（含下限）").isEqualTo(floor);
    }

    @Test
    @DisplayName("idleMs(resolver)：设置层值经 resolver 流入；测试钩子绕过下限（毫秒级注入）")
    void idleMsReadsResolverThrough() {
        CompactSettingsResolver resolver = mock(CompactSettingsResolver.class);
        when(resolver.streamIdleTimeoutMs()).thenReturn(900_000);
        assertThat(StreamIdleWatchdogSettings.idleMs(resolver))
            .as("resolver 有值 → 进入解析链（env 未设 → 用设置值）").isEqualTo(900_000L);

        CompactSettingsResolver empty = mock(CompactSettingsResolver.class);
        when(empty.streamIdleTimeoutMs()).thenReturn(null);
        assertThat(StreamIdleWatchdogSettings.idleMs(empty))
            .as("resolver null 值 → 默认").isEqualTo(StreamIdleWatchdogSettings.DEFAULT_IDLE_MS);

        assertThat(StreamIdleWatchdogSettings.idleMs(null))
            .as("resolver 缺失（子代理/测试路径）→ 默认").isEqualTo(StreamIdleWatchdogSettings.DEFAULT_IDLE_MS);

        StreamIdleWatchdogSettings.setTestOverrides(null, 400L);
        assertThat(StreamIdleWatchdogSettings.idleMs(null))
            .as("测试钩子绕过下限（仅供单测做毫秒级空闲触发；生产恒 null）").isEqualTo(400L);
    }
}
