package com.nexusai.application.agent.tool.cron;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRON-A1b + B5 · {@link CronToHuman#cronToHuman(String)} 对齐 CC
 * {@code Open-ClaudeCode/src/utils/cron.ts:218-308} 的契约测试.
 *
 * <p><b>WHY (意图验证)</b>: humanSchedule（人类可读时间）是 CronCreate/CronList 对用户暴露的
 * 显示文本，CC 用有限模式（Every N minutes / Every hour / Every N hours / Every day at /
 * Every DOW at / Weekdays at）把 cron 表达式翻译成自然语言，其余一律兜底返回原始串。
 * 若分支顺序或 pad/AM-PM 样式偏离，用户看到的调度说明会与 CC 不符，且兜底漏掉某模式
 * 会直接把机器串泄给用户——因此按验收标准 1-5 逐分支断言，并覆盖 12 小时制边界。
 *
 * <p><b>B5 全 6 字段（A4 拍板，open-decisions.md:156）</b>: cronToHuman 重写为直接 6 字段算法
 * （0=秒 1=分 2=时 3=dom 4=月 5=dow）。6 段输入直接匹配（dom/dow 的 {@code ?} 视为通配）；
 * 5 段输入委托 toQuartz6Field 转 6 段仅用于匹配。兜底一律返回<b>原始输入串</b>（推翻 B1-1 的
 * "归一 5 段返串"契约）。非 0 秒（1-59）显示秒（"9:00:30 AM"），秒恒 0 时行为与 B1-1 一致。
 */
class CronToHumanTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // Every N minutes（cron.ts:232-242）
        "'*/1 * * * *','Every minute'",
        "'*/5 * * * *','Every 5 minutes'",
        // Every hour（cron.ts:245-255）
        "'0 * * * *','Every hour'",
        "'15 * * * *','Every hour at :15'",
        // Every N hours（cron.ts:258-269）
        "'0 */2 * * *','Every 2 hours'",
        "'30 */3 * * *','Every 3 hours at :30'",
        "'0 */1 * * *','Every hour'",
        // Daily at specific time（cron.ts:280-282）+ 12 小时制边界
        "'30 14 * * *','Every day at 2:30 PM'",
        "'0 0 * * *','Every day at 12:00 AM'",
        "'0 12 * * *','Every day at 12:00 PM'",
        // Specific day of week（cron.ts:285-300，dow 7 → Sunday %7）
        "'0 9 * * 1','Every Monday at 9:00 AM'",
        "'0 9 * * 7','Every Sunday at 9:00 AM'",
        // IMPL-04: dom 全覆盖 token（*/1）判通配 → 5 段桥修复后命中 DOW 分支
        //（等价 CC 对 '0 9 * * 1' 的 humanSchedule；修复前 *\/1 误判约束 → 兜底原串）
        "'0 9 */1 * 1','Every Monday at 9:00 AM'",
        // Weekdays（cron.ts:303-304）
        "'0 9 * * 1-5','Weekdays at 9:00 AM'",
    })
    @DisplayName("有限模式命中 → 自然语言翻译（en-US 样式）")
    void matchedPatterns(String cron, String expected) {
        assertThat(CronToHuman.cronToHuman(cron)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → 原样返回")
    @CsvSource({
        // 非 5 字段（cron.ts:221）
        "'0 9 * *'",
        // 数值门：minute 非数字（cron.ts:274）
        "'*/5 9 * * *'",
        // 数值门：hour 非数字且非 step（cron.ts:274）
        "'0 5,6 * * *'",
        "'* * * * *'",
        // dom 非 * → 不属任何模式（cron.ts:280/285/303 均要求 dom=*）
        "'0 9 1 * *'",
        // dow 区间 CC 不匹配 /^\d$/ 且非 '1-5' → 兜底
        "'0 9 * * 2-4'",
        "'0 9 * * 1-3'",
        // dow 非 * 但 dom=* mon=* 时 minute/hour 非数字门
        "'*/5 * * * 1'",
    })
    @DisplayName("未覆盖模式 → 兜底返回原始 cron 串")
    void fallbackToOriginal(String cron) {
        assertThat(CronToHuman.cronToHuman(cron)).isEqualTo(cron);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // 6 段 Quartz（CronExpressionConverter.toQuartz6Field 产物 / 用户直接 6 段输入），直接 6 字段匹配
        // Every day（0 M H * * * 全通配）
        "'0 0 9 * * *','Every day at 9:00 AM'",
        // DOW：Quartz 2=Mon → CC 1 → Monday（R1 DoW 偏移回归锁）
        "'0 0 9 * * 2','Every Monday at 9:00 AM'",
        // DOW：Quartz 1=Sun → CC 0 → Sunday
        "'0 0 9 * * 1','Every Sunday at 9:00 AM'",
        // Every N minutes（去前导秒后走 cron.ts:232-242）
        "'0 */5 * * * *','Every 5 minutes'",
    })
    @DisplayName("6 段 Quartz 命中 → 直接 6 字段自然语言翻译（B5，不再归一 5 段）")
    void matched6FieldPatterns(String cron, String expected) {
        assertThat(CronToHuman.cronToHuman(cron)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // B5 兜底返回原始 6 段串（对齐 CC cron.ts:307 return cron；推翻 B1-1 归一 5 段返串契约，A4 拍板）
        "'0 0 9 14 4 ?','0 0 9 14 4 ?'",
        // dow 逗号列表非 Mon-Fri 全集 → 兜底原串（B1-1 曾 q→q-1 归一，B5 不再归一）
        "'0 0 9 * * 1,2','0 0 9 * * 1,2'",
        // dow="0"（非法 Quartz）1-7 门拦截 → 兜底原串，防 DAY_NAMES.get(-1) 越界崩溃
        "'0 0 9 * * 0','0 0 9 * * 0'",
    })
    @DisplayName("6 段未覆盖模式 → 兜底返回原始 cron 串（CC cron.ts:307 语义；dow=0 1-7 门兜底）")
    void matched6FieldFallback(String cron, String expected) {
        assertThat(CronToHuman.cronToHuman(cron)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // B5 秒显示：非 0 秒（1-59）→ "h:mm:ss a" 显示秒（用户决策 ③）
        "'30 0 9 * * *','Every day at 9:00:30 AM'",
        "'30 0 9 * * 2','Every Monday at 9:00:30 AM'",
        // dom="?"（toQuartz6Field 产物互斥占位符）视为通配 → 直接命中
        "'0 0 9 ? * *','Every day at 9:00 AM'",
        "'0 0 9 ? * 2','Every Monday at 9:00 AM'",
        // Quartz Mon-Fri 两形态（逗号列表=5 段 1-5 转换产物；区间=6 段直接输入）
        "'0 0 9 ? * 2,3,4,5,6','Weekdays at 9:00 AM'",
        "'0 0 9 ? * 2-6','Weekdays at 9:00 AM'",
        // interval 分支要求秒=="0"，秒≠0 → 兜底原串（fail-loud，避免 "Every 5 minutes at :30" 歧义）
        "'30 */5 * * * *','30 */5 * * * *'",
    })
    @DisplayName("B5 新增：非 0 秒显示秒 / dom=? 通配 / Mon-Fri 两形态 / interval 秒≠0 兜底")
    void b5SecondsAndDomQuestion(String cron, String expected) {
        assertThat(CronToHuman.cronToHuman(cron)).isEqualTo(expected);
    }

    @Test
    @DisplayName("null 输入 → null（once 任务 cron=null 守卫，防 cron.trim() NPE）")
    void nullInputReturnsNull() {
        assertThat(CronToHuman.cronToHuman(null)).isNull();
    }
}
