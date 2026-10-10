package com.nexusai.application.agent.compact;

import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompactSettingsResolver#streamIdleTimeoutMs()} 单测（V78 列 · 流空闲看门狗阈值实时读源）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：本方法与既有 {@code gapThresholdMinutes()} 同范式
 * ——<b>只做「DB 有值（>0）原样透出」</b>，不做下限钳制（300000 下限在消费点
 * {@code StreamIdleWatchdogSettings.idleMs()} 统一执行，对齐 CC {@code Math.max(am()??0,300000)}）。
 * 若这里把非正值当「有值」返回，消费点会把 0 当合法阈值 → 每次调用立即超时。
 *
 * <p>钉死三条：①null/非正 → null（未配置）；②正数原样透出（含低于 300000 的值——钳制不在此层，
 * 消费点测试负责该边界）；③不缓存（每次调用都读 DB，前端 PUT 后下一轮即生效）。
 */
@DisplayName("[V78] CompactSettingsResolver.streamIdleTimeoutMs 实时读源")
class CompactSettingsResolverStreamIdleTest {

    private static final int SINGLETON_ID = 1;

    private static CompactSettingsResolver resolverWith(SettingsMapper mapper) {
        CompactSettingsResolver resolver = new CompactSettingsResolver();
        resolver.setSettingsMapper(mapper);
        return resolver;
    }

    @Test
    @DisplayName("未配置：行缺失 / 行在但列为 null / 列为 0 / 列为负 → 一律 null")
    void unconfiguredMeansNull() {
        SettingsMapper missingRow = mock(SettingsMapper.class);
        when(missingRow.selectOneById(SINGLETON_ID)).thenReturn(null);
        assertThat(resolverWith(missingRow).streamIdleTimeoutMs())
            .as("行缺失 → null").isNull();

        SettingsMapper nullColumn = mock(SettingsMapper.class);
        when(nullColumn.selectOneById(SINGLETON_ID)).thenReturn(new SettingsRecord());
        assertThat(resolverWith(nullColumn).streamIdleTimeoutMs())
            .as("列为 NULL（未配置）→ null").isNull();

        SettingsMapper zero = mock(SettingsMapper.class);
        SettingsRecord zeroRow = new SettingsRecord();
        zeroRow.setStreamIdleTimeoutMs(0);
        when(zero.selectOneById(SINGLETON_ID)).thenReturn(zeroRow);
        assertThat(resolverWith(zero).streamIdleTimeoutMs())
            .as("0 = 未配置（消费点回落 env/默认）→ null").isNull();

        SettingsMapper negative = mock(SettingsMapper.class);
        SettingsRecord negRow = new SettingsRecord();
        negRow.setStreamIdleTimeoutMs(-1);
        when(negative.selectOneById(SINGLETON_ID)).thenReturn(negRow);
        assertThat(resolverWith(negative).streamIdleTimeoutMs())
            .as("负值无意义 → 按未配置处理").isNull();
    }

    @Test
    @DisplayName("有配置：正数原样透出（本层不钳制——300000 下限在消费点执行）")
    void positiveValuePassedThroughRaw() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        row.setStreamIdleTimeoutMs(900000);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);
        assertThat(resolverWith(mapper).streamIdleTimeoutMs()).isEqualTo(900000);

        // 低于下限的值本层也必须原样透出（钳制是消费点职责；否则「设了 60000」在 UI 与
        // 实际生效值之间的对照测试将失去可分辨性）
        SettingsMapper low = mock(SettingsMapper.class);
        SettingsRecord lowRow = new SettingsRecord();
        lowRow.setStreamIdleTimeoutMs(60000);
        when(low.selectOneById(SINGLETON_ID)).thenReturn(lowRow);
        assertThat(resolverWith(low).streamIdleTimeoutMs())
            .as("60000 > 0 → 原样透出（消费点再钳到 300000）").isEqualTo(60000);
    }

    @Test
    @DisplayName("不缓存：每次调用都读 DB（前端 PUT 后下一轮即生效）")
    void readsDbEveryCall() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        row.setStreamIdleTimeoutMs(600000);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);
        CompactSettingsResolver resolver = resolverWith(mapper);

        assertThat(resolver.streamIdleTimeoutMs()).isEqualTo(600000);
        assertThat(resolver.streamIdleTimeoutMs()).isEqualTo(600000);
        verify(mapper, times(2)).selectOneById(SINGLETON_ID);
    }
}
