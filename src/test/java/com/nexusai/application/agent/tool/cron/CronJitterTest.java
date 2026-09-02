package com.nexusai.application.agent.tool.cron;

import com.nexusai.application.agent.tool.cron.CronJitter.CronJitterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session CRON-F1 · {@link CronJitter} 抖动算法契约验证（对齐 CC cronTasks.ts:348-445）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：多 session 排同一 cron 会在整点同时打爆推理
 * （thundering herd）——必须验证 taskId 确定性散列（跨重启稳定、非 hex 回退 0）、recurring
 * 前向延迟落在 [0, min(frac*frac*间隙, cap)]、one-shot 仅整点分钟（:00/:30）提前且钳到 fromMs。
 * 测试以系统默认时区构造时间，与实现同用本地时区，避免 TZ 敏感。
 */
@DisplayName("CRON-F1 · CronJitter 确定性抖动契约")
class CronJitterTest {

    /** 以系统默认时区构造 epoch 毫秒（实现用 ZoneId.systemDefault，测试需一致）。 */
    private static long localMs(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), ZoneId.systemDefault())
            .toInstant().toEpochMilli();
    }

    // ═════════════ 验收 1 · jitterFrac 确定性散列 ═════════════

    @Test
    @DisplayName("jitterFrac 同 taskId 跨调用稳定（确定性）、不同 id 发散、值域 [0,1)（cronTasks.ts:362-365）")
    void jitterFrac_isDeterministicAndBounded() {
        assertThat(CronJitter.jitterFrac("abc12345")).isEqualTo(CronJitter.jitterFrac("abc12345"));
        assertThat(CronJitter.jitterFrac("00000000")).isNotEqualTo(CronJitter.jitterFrac("ffffffff"));
        for (String id : new String[]{"00000000", "ffffffff", "12345678", "a1b2c3d4"}) {
            double frac = CronJitter.jitterFrac(id);
            assertThat(frac).as("frac(%s) ∈ [0,1)", id).isGreaterThanOrEqualTo(0.0d).isLessThan(1.0d);
        }
        // 已知边界：u32 归一 → 00000000=0，ffffffff 趋近 1（不含）
        assertThat(CronJitter.jitterFrac("00000000")).isEqualTo(0.0d);
        assertThat(CronJitter.jitterFrac("ffffffff")).isGreaterThan(0.999d).isLessThan(1.0d);
    }

    @Test
    @DisplayName("jitterFrac null / 非 hex / 空 → 0 = 无抖动（cronTasks.ts:359-361 Number.isFinite 回退）")
    void jitterFrac_fallsBackToZeroForNonHex() {
        assertThat(CronJitter.jitterFrac(null)).isEqualTo(0.0d);
        assertThat(CronJitter.jitterFrac("")).isEqualTo(0.0d);
        assertThat(CronJitter.jitterFrac("zzzzzzzz")).isEqualTo(0.0d);
        assertThat(CronJitter.jitterFrac("abc-defg")).isEqualTo(0.0d);
    }

    // ═════════════ 验收 2 · recurring 前向延迟（cronTasks.ts:381-398）═════════════

    @Test
    @DisplayName("recurring 每小时 cron → 结果 ∈ [t1, t1+0.1*间隙]；taskId=00000000 → 直发 t1")
    void recurring_delayProportionalWithinFractionOfGap() {
        long from = localMs(2026, 8, 1, 10, 0);
        Long t1 = CronExpressionConverter.nextCronRunMs("0 * * * *", from); // 11:00
        assertThat(t1).isNotNull();
        long gap = CronExpressionConverter.nextCronRunMs("0 * * * *", t1) - t1; // 1h
        assertThat(gap).isEqualTo(3600_000L);
        double maxDelay = 0.1d * gap; // 默认 recurringFrac=0.1，未触 cap（360000 < 900000）

        Long noJitter = CronJitter.jitteredNextCronRunMs("0 * * * *", from, "00000000");
        assertThat(noJitter).isEqualTo(t1); // frac=0 → 前向延迟 0

        Long result = CronJitter.jitteredNextCronRunMs("0 * * * *", from, "ffffffff");
        assertThat(result).isNotNull().isGreaterThan(t1)
            .isLessThanOrEqualTo((long) (t1 + maxDelay));
    }

    @Test
    @DisplayName("recurring 大间隙 cron → 前向延迟封顶 recurringCapMs=15min（cronTasks.ts:393-396）")
    void recurring_delayCappedAtCapMs() {
        long from = localMs(2026, 8, 1, 10, 0);
        Long t1 = CronExpressionConverter.nextCronRunMs("0 9 * * *", from); // 次日 09:00
        assertThat(t1).isNotNull();
        long cap = CronJitterConfig.DEFAULT.recurringCapMs(); // 900000
        // 24h 间隙：0.1*86400000 = 8640000 > cap → frac=1 时封顶
        assertThat(0.1d * 86400_000L).isGreaterThan((double) cap);

        Long result = CronJitter.jitteredNextCronRunMs("0 9 * * *", from, "ffffffff");
        assertThat(result).isEqualTo(t1 + cap);
    }

    // ═════════════ 验收 3 · one-shot 整点提前（cronTasks.ts:421-445）═════════════

    @Test
    @DisplayName("one-shot 落在 :00/:30 整点 → 提前 lead=frac*(max-floor)；非整点 → 不抖动直发 t1")
    void oneShot_jittersOnlyOnMinuteBoundary() {
        long from = localMs(2026, 8, 1, 10, 0);
        Long t1 = CronExpressionConverter.nextCronRunMs("0 15 * * *", from); // 15:00
        assertThat(t1).isNotNull();
        long maxLead = CronJitterConfig.DEFAULT.oneShotMaxMs(); // 90000

        // 整点分钟（%30==0）→ 提前 full lead（frac≈1）
        Long jittered = CronJitter.oneShotJitteredNextCronRunMs("0 15 * * *", from, "ffffffff");
        assertThat(jittered).isEqualTo(t1 - maxLead);
        // frac=0 → 提前 0，仍直发 t1
        assertThat(CronJitter.oneShotJitteredNextCronRunMs("0 15 * * *", from, "00000000"))
            .isEqualTo(t1);
        // 非整点分钟 23（%30≠0）→ 不抖动
        Long t23 = CronExpressionConverter.nextCronRunMs("23 15 * * *", from); // 15:23
        assertThat(t23).isNotNull();
        assertThat(CronJitter.oneShotJitteredNextCronRunMs("23 15 * * *", from, "ffffffff"))
            .isEqualTo(t23);
    }

    @Test
    @DisplayName("one-shot 创建于自身提前窗内 → 钳到 fromMs 不早于创建（cronTasks.ts:442-444）")
    void oneShot_clampsToFromMsInsideLeadWindow() {
        long from = localMs(2026, 8, 1, 10, 0);
        Long t1 = CronExpressionConverter.nextCronRunMs("0 15 * * *", from);
        long createdInsideWindow = t1 - 1000; // 距整点仅 1s < lead 90s
        Long result = CronJitter.oneShotJitteredNextCronRunMs("0 15 * * *", createdInsideWindow, "ffffffff");
        assertThat(result).isEqualTo(createdInsideWindow);
    }
}
