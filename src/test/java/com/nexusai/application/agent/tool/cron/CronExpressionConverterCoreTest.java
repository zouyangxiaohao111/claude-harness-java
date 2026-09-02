package com.nexusai.application.agent.tool.cron;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronExpression;

import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session CRON-B5-1 · {@link CronExpressionConverter} 核心契约验证（委托 Quartz 6 字段）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>: B5 全 6 字段（用户拍板 open-decisions.md A4）删除
 * 旧 5 字段算法（5 段解析、字段展开、字段模型 record、6→5 归一、下次触发计算），
 * nextCronRunMs 委托 Quartz。测试必须验证：5→6 段转换（Quartz isValidExpression 终值闸门）、
 * 双约束 OR 变体、6 段/|| 存储串解析、严格 after、无匹配 null、DST（以 Quartz 输出为真值）。
 * 测试以系统默认时区构造时间，与实现同用本地时区，避免 TZ 敏感；DST 用例固定 America/New_York。
 */
@DisplayName("CRON-B5-1 · CronExpressionConverter 核心转换契约（Quartz 委托）")
class CronExpressionConverterCoreTest {

    /** 以系统默认时区构造 epoch 毫秒（实现用 ZoneId.systemDefault，测试需一致）。 */
    private static long localMs(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), ZoneId.systemDefault())
            .toInstant().toEpochMilli();
    }

    /** America/New_York 固定时区（含 DST 规则）· DST 用例确定性断言专用。 */
    private static final ZoneId NY = ZoneId.of("America/New_York");

    /** 以 NY 时区构造 epoch 毫秒（ZonedDateTime.of 重叠取早偏移=EDT，即 DST 第一次出现）。 */
    private static long nyMs(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), NY).toInstant().toEpochMilli();
    }

    /** 以 NY 时区指定偏移构造 epoch 毫秒（秋退重叠断言第二次出现 EST/-05:00 用）。
     *  注：JDK 25 已移除 ZonedDateTime.of(LocalDateTime, ZoneId, ZoneOffset)，改用
     *  toInstant(ZoneOffset) + atZone 显式指定偏移构造重叠时刻。 */
    private static long nyMsOff(int y, int mo, int d, int h, int mi, int offHours) {
        return LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.ofHours(offHours))
            .atZone(NY).toInstant().toEpochMilli();
    }

    // ═════════════ 验收 1 · toQuartz6Field（5→6 / 6 段透传 / 终值闸门）═════════════

    @Test
    @DisplayName("toQuartz6Field 单变体四情形 + 6 段透传 + 7 段拒绝 + DoW 别名")
    void toQuartz6Field_cases() {
        // domWild && dowWild → 0 0 9 ? * *（E-C1：修复后 Quartz 合法）
        assertThat(CronExpressionConverter.toQuartz6Field("0 9 * * *")).isEqualTo("0 0 9 ? * *");
        // domWild → 0 0 9 ? * dow+1（Quartz 拒绝 dom="*"+dow 具体值，未约束侧必须 "?"）
        assertThat(CronExpressionConverter.toQuartz6Field("0 9 * * 1")).isEqualTo("0 0 9 ? * 2");
        // dowWild → 0 0 9 dom * ?
        assertThat(CronExpressionConverter.toQuartz6Field("0 9 1 * *")).isEqualTo("0 0 9 1 * ?");
        // 双约束 → 主变体 0 0 9 dom * ?（OR 的 dow 侧由 toQuartzCronVariants 第二变体承担）
        assertThat(CronExpressionConverter.toQuartz6Field("0 9 1 * 1")).isEqualTo("0 0 9 1 * ?");
        // 6 段透传 / 7 段 null
        assertThat(CronExpressionConverter.toQuartz6Field("0 30 9 * * ?")).isEqualTo("0 30 9 * * ?");
        assertThat(CronExpressionConverter.toQuartz6Field("0 30 9 * * ? 2026")).isNull();
        // DoW 7 别名 → Quartz 1（Sunday）；区间 5-7 → 1,6,7（周日别名回卷）
        assertThat(CronExpressionConverter.toQuartz6Field("0 9 * * 7")).isEqualTo("0 0 9 ? * 1");
        assertThat(CronExpressionConverter.toQuartz6Field("0 9 * * 5-7")).isEqualTo("0 0 9 ? * 1,6,7");
        // 非法 → null（内字段越界/7 段）
        assertThat(CronExpressionConverter.toQuartz6Field("60 9 * * *")).isNull();
        // B5 更严：非法 6 段（dom+dow 冲突）→ null
        assertThat(CronExpressionConverter.toQuartz6Field("0 0 9 * * 1-5")).isNull();
    }

    @Test
    @DisplayName("toQuartz6Field 转换结果 Quartz isValidExpression=true（E-C1 全灭修复）")
    void toQuartz6Field_quartzValid() {
        assertThat(CronExpression.isValidExpression(CronExpressionConverter.toQuartz6Field("0 9 * * *"))).isTrue();
        assertThat(CronExpression.isValidExpression(CronExpressionConverter.toQuartz6Field("0 9 * * 1"))).isTrue();
        assertThat(CronExpression.isValidExpression(CronExpressionConverter.toQuartz6Field("0 9 1 * *"))).isTrue();
        assertThat(CronExpression.isValidExpression(CronExpressionConverter.toQuartz6Field("0 9 * * 5-7"))).isTrue();
        assertThat(CronExpression.isValidExpression(CronExpressionConverter.toQuartz6Field("0 9 * * 1-5"))).isTrue();
    }

    // ═════════════ 验收 2 · toQuartzCronVariants（F2 双 trigger OR）═════════════

    @Test
    @DisplayName("toQuartzCronVariants 双约束 → 2 变体（dom 侧 + dow 侧，并集=CC OR）")
    void toQuartzCronVariants_doubleConstraint() {
        var variants = CronExpressionConverter.toQuartzCronVariants("0 9 1 * 1");
        assertThat(variants).isNotNull().hasSize(2);
        // 变体 1 覆盖 dom 侧（每月 1 号）、变体 2 覆盖 dow 侧（周一），并集=CC OR
        assertThat(variants.get(0)).isEqualTo("0 0 9 1 * ?");
        assertThat(variants.get(1)).isEqualTo("0 0 9 ? * 2");
        for (String v : variants) {
            assertThat(CronExpression.isValidExpression(v)).isTrue();
        }
    }

    @Test
    @DisplayName("toQuartzCronVariants 其余 → 单变体；7 段 / 非法 → null")
    void toQuartzCronVariants_singleOrNull() {
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 9 * * *")).containsExactly("0 0 9 ? * *");
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 9 1 * *")).containsExactly("0 0 9 1 * ?");
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 9 * * 1")).containsExactly("0 0 9 ? * 2");
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 30 9 * * ?")).containsExactly("0 30 9 * * ?");
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 30 9 * * ? 2026")).isNull();
        assertThat(CronExpressionConverter.toQuartzCronVariants("60 9 * * *")).isNull();
    }

    @Test
    @DisplayName("B5 改进：5 段 dow 区间 '0 9 * * 1-5' → 单变体 '0 0 9 ? * 2,3,4,5,6'")
    void toQuartzCronVariants_dowRange() {
        // WHY: 旧 6→5 归一对 dow 区间数值化失败闭合（errorCode1 拒）；B5 委托 Quartz 后
        // dow 区间是合法 Quartz（Quartz 1-5=Sun-Thu ≡ CC 1-5=Mon-Fri → +1=2..6），errorCode1 由拒变通过。
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 9 * * 1-5"))
            .containsExactly("0 0 9 ? * 2,3,4,5,6");
    }

    // ═════════════ 验收 3 · nextCronRunMs（委托 Quartz：严格 after + OR min + 无匹配 null）═════════════

    @Test
    @DisplayName("nextCronRunMs 包装：合法 → ms；非法 / 无匹配 → null")
    void nextCronRunMs_wrapper() {
        assertThat(CronExpressionConverter.nextCronRunMs("0 9 * * *", localMs(2026, 8, 10, 9, 0)))
            .isEqualTo(localMs(2026, 8, 11, 9, 0));
        assertThat(CronExpressionConverter.nextCronRunMs("60 9 * * *", localMs(2026, 8, 10, 0, 0))).isNull();
        // 2 月 30 日不存在 → Quartz getNextValidTimeAfter 返回 null（Feb30 永不匹配，等价旧 366 天上限语义）
        assertThat(CronExpressionConverter.nextCronRunMs("0 9 30 2 *", localMs(2026, 1, 1, 0, 0))).isNull();
    }

    @Test
    @DisplayName("6 字段 Quartz 存储串（dow 侧）与 5 字段 CC 等下次触发")
    void nextCronRunMs_sixFieldDowEquivalent() {
        // WHY: CronCreateTool 经 toQuartzDow 存 Quartz 6 段（dow 1-7），findMissedTasks 必须能解析。
        // Quartz dow=2（周一）≡ CC dow=1（周一）。
        assertThat(CronExpressionConverter.nextCronRunMs("0 0 9 ? * 2", localMs(2026, 8, 3, 0, 0)))
            .isEqualTo(CronExpressionConverter.nextCronRunMs("0 9 * * 1", localMs(2026, 8, 3, 0, 0)));
    }

    @Test
    @DisplayName("6 字段 Quartz 存储串（dom 侧）与 5 字段 CC 等下次触发")
    void nextCronRunMs_sixFieldDomEquivalent() {
        assertThat(CronExpressionConverter.nextCronRunMs("0 0 9 1 * ?", localMs(2026, 8, 1, 10, 0)))
            .isEqualTo(CronExpressionConverter.nextCronRunMs("0 9 1 * *", localMs(2026, 8, 1, 10, 0)));
    }

    @Test
    @DisplayName("|| OR 变体取 min：双约束存串 ≡ CC 双约束 5 字段（cron.ts:151-158）")
    void nextCronRunMs_orVariantsMin() {
        // WHY: 双约束 cron 经 joinVariants 存 'A||B'（dom 侧 + dow 侧），任一匹配即触发 = CC OR。
        // min(各变体 next) 必须等于 5 字段双约束 OR 的 next——依赖 nextCronRunMs 对 5 字段双约束
        // 输入经 toQuartzCronVariants 拆 2 变体（裸 toQuartz6Field 只给 dom 侧会漏 dow 侧）。
        assertThat(CronExpressionConverter.nextCronRunMs("0 0 9 1 * ?||0 0 9 ? * 2", localMs(2026, 8, 3, 0, 0)))
            .isEqualTo(CronExpressionConverter.nextCronRunMs("0 9 1 * 1", localMs(2026, 8, 3, 0, 0)));
    }

    @Test
    @DisplayName("7 段 / null / 6 段 dom+dow 冲突 → null（失败闭合）")
    void nextCronRunMs_invalidFormsNull() {
        assertThat(CronExpressionConverter.nextCronRunMs("0 30 9 * * ? 2026", localMs(2026, 8, 3, 0, 0))).isNull();
        assertThat(CronExpressionConverter.nextCronRunMs(null, localMs(2026, 8, 3, 0, 0))).isNull();
        // dom="*"+dow="1-5" 双具体 → Quartz 互斥规则拒绝（isValidExpression=false → null）
        assertThat(CronExpressionConverter.nextCronRunMs("0 30 9 * * 1-5", localMs(2026, 8, 3, 0, 0))).isNull();
    }

    @Test
    @DisplayName("nextCronRunMs 委托 parity：与 new CronExpression(q).getNextValidTimeAfter 一致（固定 zone）")
    void nextCronRunMs_quartzParity() throws ParseException {
        // WHY（规则九）：委托方案的意图可验证性——nextCronRunMs 必须与直接 Quartz 调用等值，
        // 防未来绕开 Quartz 又自建算法导致偏离。
        for (String q6 : List.of("0 0 9 ? * *", "0 30 2 ? * *", "0 0 9 1 * ?")) {
            long from = nyMs(2026, 3, 1, 0, 0);
            Long via = CronExpressionConverter.nextCronRunMs(q6, from, NY);
            CronExpression ce = new CronExpression(q6);
            ce.setTimeZone(TimeZone.getTimeZone(NY));
            Date d = ce.getNextValidTimeAfter(new Date(from));
            assertThat(via).isEqualTo(d == null ? null : d.getTime());
        }
    }

    // ═════════════ 验收 4 · DST（CRON-B5-1：以 Quartz 输出为真值）═════════════
    // WHY: 委托 Quartz 后 DST 行为由 CronExpression.getNextValidTimeAfter 决定，与旧 LocalDateTime
    // 墙钟实现存在细节差异（秋退重复小时：Quartz 取第二次出现 EST/-05:00，旧实现 ofLocal 重叠取早
    // 偏移=EDT/-04:00）。测试以 Quartz 实际输出为真值重写（探针验证，2026 NY：3/8 春进、11/1 秋退）。

    @Test
    @DisplayName("DST 春进：固定小时 `30 2` 跳过 gap 日 3/8（S1/S2 → 3/9 02:30 EDT）")
    void nextCronRunMs_dstSpringForwardSkipGap() {
        assertThat(CronExpressionConverter.nextCronRunMs("30 2 * * *", nyMs(2026, 3, 7, 2, 30), NY))
            .isEqualTo(nyMs(2026, 3, 9, 2, 30));
        assertThat(CronExpressionConverter.nextCronRunMs("30 2 * * *", nyMs(2026, 3, 8, 0, 0), NY))
            .isEqualTo(nyMs(2026, 3, 9, 2, 30));
    }

    @Test
    @DisplayName("DST 春进：通配小时 `0 *` gap 后首个整点 3/8 03:00 EDT（S3）")
    void nextCronRunMs_dstWildcardHourGapFirstMinute() {
        assertThat(CronExpressionConverter.nextCronRunMs("0 * * * *", nyMs(2026, 3, 8, 1, 0), NY))
            .isEqualTo(nyMs(2026, 3, 8, 3, 0));
    }

    @Test
    @DisplayName("DST 秋退：`30 1` 从 11/1 00:00 → 11/1 01:30 EST（Quartz 取第二次出现，-05:00）")
    void nextCronRunMs_dstFallBackSecondOccurrence() {
        // WHY（B5 偏差登记）：Quartz getNextValidTimeAfter 对秋退重复小时返回第二次出现
        // （EST/-05:00），旧自定义下次触发计算的 ofLocal 重叠取早偏移=EDT/-04:00。
        // 委托 Quartz → 以 Quartz 真值断言偏移 -05:00。
        Long r = CronExpressionConverter.nextCronRunMs("30 1 * * *", nyMs(2026, 11, 1, 0, 0), NY);
        assertThat(r).isEqualTo(nyMsOff(2026, 11, 1, 1, 30, -5));
        assertThat(ZonedDateTime.ofInstant(Instant.ofEpochMilli(r), NY).getOffset().getTotalSeconds())
            .isEqualTo(-5 * 3600);
    }

    @Test
    @DisplayName("DST 秋退不重入：F1 结果之后 → 11/2 01:30 EST（F2）")
    void nextCronRunMs_dstFallBackNoReentry() {
        long f1 = CronExpressionConverter.nextCronRunMs("30 1 * * *", nyMs(2026, 11, 1, 0, 0), NY);
        assertThat(CronExpressionConverter.nextCronRunMs("30 1 * * *", f1, NY))
            .isEqualTo(nyMs(2026, 11, 2, 1, 30));
    }

    @Test
    @DisplayName("DST 秋退不重入：from 落重复小时第一次（11/1 01:45 EDT）→ 11/2 01:30 EST（F3）")
    void nextCronRunMs_dstFallBackInsideRepeatedHour() {
        // nyMs(11/1 01:45) = ZonedDateTime.of 早偏移 = EDT（第一次出现）；Quartz 从该瞬时向后，
        // 同一天第二次 01:30 EST 也被跳过 → 11/2（探针验证）
        assertThat(CronExpressionConverter.nextCronRunMs("30 1 * * *", nyMs(2026, 11, 1, 1, 45), NY))
            .isEqualTo(nyMs(2026, 11, 2, 1, 30));
    }

    @Test
    @DisplayName("nextCronRunMs 3 参重载：DST 场景 sanity（fixed zone 与 Quartz 真值一致）")
    void nextCronRunMs_zoneOverloadDst() {
        assertThat(CronExpressionConverter.nextCronRunMs("30 2 * * *", nyMs(2026, 3, 7, 2, 30), NY))
            .isEqualTo(nyMs(2026, 3, 9, 2, 30));
        assertThat(CronExpressionConverter.nextCronRunMs("30 1 * * *", nyMs(2026, 11, 1, 1, 45), NY))
            .isEqualTo(nyMs(2026, 11, 2, 1, 30));
    }

    // ═════════════ 验收 5 · IMPL-04 dom/dow 通配分类修复（CC 展开长度全值域判定）═════════════
    // WHY: CC cron.ts:130-131 通配判定 = 展开长度全值域（dom==31 / dow==7），非 token 形状；
    // 旧 Java "token 级 * 判定" 把 */1、1-31、0-6 等全覆盖 token 误判为约束 → 双约束拆 2 变体 →
    // 全覆盖侧变体每日 fire，fire 日集较 CC 扩大（如 '0 9 */1 * 1' CC 仅周一、Java 修复前每日）。
    // 以下用例锁定修复后与 CC 一致的分类行为。

    @Test
    @DisplayName("IMPL-04: dom 全覆盖 token（*/1、1-31）判通配 → '0 9 */1 * 1' 仅周一（CC 一致）")
    void toQuartzCronVariants_fullRangeDom() {
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 9 */1 * 1"))
            .containsExactly("0 0 9 ? * 2");
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 9 1-31 * 1"))
            .containsExactly("0 0 9 ? * 2");
        for (String cron : List.of("0 9 */1 * 1", "0 9 1-31 * 1")) {
            List<String> variants = CronExpressionConverter.toQuartzCronVariants(cron);
            assertThat(variants).isNotNull().hasSize(1);
            for (String v : variants) {
                assertThat(CronExpression.isValidExpression(v)).isTrue();
            }
            // 与 CC 等价表达式 '0 9 * * 1' 同日触发：2026-08-10 为周一，锚 08-10 00:00 → 当日 09:00
            long anchor = localMs(2026, 8, 10, 0, 0);
            assertThat(CronExpressionConverter.nextCronRunMs(cron, anchor))
                .isEqualTo(CronExpressionConverter.nextCronRunMs("0 9 * * 1", anchor))
                .isEqualTo(localMs(2026, 8, 10, 9, 0));
        }
    }

    @Test
    @DisplayName("IMPL-04: dow 全覆盖 token（0-6）判通配 → '0 9 1 * 0-6' 仅每月 1 日（CC 一致）")
    void toQuartzCronVariants_fullRangeDow() {
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 9 1 * 0-6"))
            .containsExactly("0 0 9 1 * ?");
        long anchor = localMs(2026, 8, 1, 10, 0);
        assertThat(CronExpressionConverter.nextCronRunMs("0 9 1 * 0-6", anchor))
            .isEqualTo(CronExpressionConverter.nextCronRunMs("0 9 1 * *", anchor))
            .isEqualTo(localMs(2026, 9, 1, 9, 0));
    }

    @Test
    @DisplayName("IMPL-04 反守卫: step>1 / 非全覆盖区间仍判约束 → 双约束 2 变体（防过度判通配）")
    void toQuartzCronVariants_stepNotFullRange() {
        // WHY: CC expandField '*/2' 展开 16 值（1,3,..,31）非全值域 → 非通配（cron.ts:37-43）；
        // dow '0-4' 展开 5 值非全值域 → 非通配。过度判通配会把 OR 误收成单约束，同样偏离 CC 日集。
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 9 */2 * 1"))
            .containsExactly("0 0 9 */2 * ?", "0 0 9 ? * 2");
        assertThat(CronExpressionConverter.toQuartzCronVariants("0 9 1 * 0-4"))
            .containsExactly("0 0 9 1 * ?", "0 0 9 ? * 1,2,3,4,5");
        for (String v : List.of("0 0 9 */2 * ?", "0 0 9 ? * 2", "0 0 9 1 * ?", "0 0 9 ? * 1,2,3,4,5")) {
            assertThat(CronExpression.isValidExpression(v)).isTrue();
        }
    }

    // ═════════════ 验收 6 · IMPL-03 hasMatchWithinYear / CC_MAX_LOOKAHEAD_MS（CC cron.ts:138 等价）═════════════
    // WHY: CC computeNextCronRun 从 from 取整到分钟 +1 起逐分钟步进（cron.ts:133-136），超
    // maxIter=366*24*60 分钟即 return null（cron.ts:138/:180）→ errorCode2「一年内无匹配」
    // （CronCreateTool.ts:90-96）。Quartz getNextValidTimeAfter 无此上限（Feb-29 类稀疏 cron 返回
    // 任意未来匹配，实测 2026-03-01 → 2028-02-29 = 730 天；仅 Feb-30 类永不匹配 → null），
    // Java 侧自建 366 天上限等价判定：next == null 或 next - from > 366 天 → 无匹配
    // （09-open-decisions NEW-1 已定方向，IMPL-03 实施）。确定性：全部固定 localMs 锚点，不依赖系统时钟。

    @Test
    @DisplayName("IMPL-03: CC_MAX_LOOKAHEAD_MS 常量钉死 = 366 天毫秒（cron.ts:138 maxIter 等价）")
    void ccMaxLookaheadMs_constant() {
        assertThat(CronExpressionConverter.CC_MAX_LOOKAHEAD_MS)
            .as("CC cron.ts:138 maxIter = 366 * 24 * 60 分钟 ≈ 366 天毫秒")
            .isEqualTo(366L * 24 * 3600 * 1000);
    }

    @Test
    @DisplayName("IMPL-03: Feb-29 超 366 天 → false（CC errorCode2 等价，Quartz 却返回 2028-02-29）")
    void hasMatchWithinYear_feb29_beyond366d() {
        // 2026-08-15 → 2028-02-29 ≈ 563 天 > 366 天：CC maxIter 耗尽 return null → errorCode2 拒；
        // Quartz 无上限返回 2028-02-29（EV-018 探针）——hasMatchWithinYear 必须补闸。
        long from = localMs(2026, 8, 15, 0, 0);
        assertThat(CronExpressionConverter.hasMatchWithinYear("0 0 9 29 2 ?", from)).isFalse();
        assertThat(CronExpressionConverter.hasMatchWithinYear("0 9 29 2 *", from)).isFalse();
    }

    @Test
    @DisplayName("IMPL-03: Feb-29 窗口内（+365 天 ≤ 366 天）→ true")
    void hasMatchWithinYear_feb29_within366d() {
        // 2027-03-01 → 2028-02-29 = 365 天（2027 非闰年）≤ 366 天 → CC maxIter 内匹配 → true
        long from = localMs(2027, 3, 1, 0, 0);
        assertThat(CronExpressionConverter.hasMatchWithinYear("0 0 9 29 2 ?", from)).isTrue();
    }

    @Test
    @DisplayName("IMPL-03: Feb-30 Quartz null → false（永不匹配）")
    void hasMatchWithinYear_feb30_never() {
        long from = localMs(2026, 8, 15, 0, 0);
        assertThat(CronExpressionConverter.hasMatchWithinYear("0 0 30 2 *", from)).isFalse();
        assertThat(CronExpressionConverter.hasMatchWithinYear("0 0 0 30 2 ?", from)).isFalse();
    }

    @Test
    @DisplayName("IMPL-03: 常规 cron '*/5 * * * *' → true")
    void hasMatchWithinYear_regular() {
        long from = localMs(2026, 8, 15, 0, 0);
        assertThat(CronExpressionConverter.hasMatchWithinYear("*/5 * * * *", from)).isTrue();
    }

    @Test
    @DisplayName("IMPL-03: 双约束 OR 拯救（dom=29 超限但 dow 侧 2027-02-01 在 366 天内）→ true")
    void hasMatchWithinYear_doubleConstraintOrSaves() {
        // '0 9 29 2 1' 双约束 → 2 变体：domOnly=2028-02-29（563 天，超限），
        // dowOnly='0 0 9 ? 2 2'（2 月周一）→ 2027-02-01 09:00（170 天，2027-02-01 为周一）
        // → OR-min = 2027-02-01 → 366 天内匹配（CC cron.ts:151-158 OR 语义；勿逐变体判定）
        long from = localMs(2026, 8, 15, 0, 0);
        assertThat(CronExpressionConverter.hasMatchWithinYear("0 9 29 2 1", from)).isTrue();
    }
}

