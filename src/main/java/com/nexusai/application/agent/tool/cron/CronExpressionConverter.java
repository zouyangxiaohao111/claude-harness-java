package com.nexusai.application.agent.tool.cron;

import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CronExpressionConverter · Quartz 6 字段 cron 转换与下一次触发时间计算（Session CRON-B5-1）。
 *
 * <p><b>B5 全 6 字段（用户拍板 open-decisions.md A4，2026-08-12）</b>: CC cron.ts 5 字段算法层
 * 偏离被接受——本类不再逐行复刻 CC 的 5 字段解析/下次触发计算，而是<b>委托 Quartz
 * {@link CronExpression} 原生 6 字段能力</b>。已删除旧 5 字段算法整块（解析、字段展开、
 * 字段模型 record、6→5 归一、DoW 集合转换、下次触发 ×2、DST 墙钟规范化；grep 复验 0 命中）。
 *
 * <p><b>保留语义</b>（对齐 CC cron.ts:151-158 + OPD-Cron-T1-01/05）:
 * <ul>
 *   <li>{@link #VARIANT_SEPARATOR} / {@link #joinVariants} / {@link #splitVariants}：双约束
 *       dom/dow OR → 2 变体并集表达，存储 join 串、注册/计算前拆回。</li>
 *   <li>{@link #toQuartz6Field}：5→6 段兼容输入（5 段转 6 段、6 段透传；非法→null）。</li>
 *   <li>{@link #toQuartzCronVariants}：双约束 → dom-only + dow-only 2 变体。</li>
 *   <li>{@link #nextCronRunMs}：CC cronTasks.ts:302-307 语义——严格 after、多变体取最早=OR。</li>
 * </ul>
 *
 * <p><b>CC 真源</b>: {@code Open-ClaudeCode/src/utils/cron.ts}（字段值域 :20-26、
 * 单字段展开 :31-77、5 段解析 :83-101、下次触发计算 :119-181，OR 双约束 :151-158）、
 * {@code Open-ClaudeCode/src/utils/cronTasks.ts:302-307}（nextCronRunMs）。
 */
public final class CronExpressionConverter {

    private static final Logger log = LoggerFactory.getLogger(CronExpressionConverter.class);

    /**
     * 变体 join 分隔符（OPD-Cron-T1-05 recurring OR 接线）· 双约束 cron 拆出的多个 Quartz
     * 6 段表达式以该分隔符 join 存进单 cron 字段（CronCreateTool → ScheduleRecord），
     * QuartzScheduleService 按此拆回逐变体注册多 CronTrigger（对齐 CC cron.ts:151-158
     * dom/dow 双约束任一匹配即触发 = OR）。Quartz cron 语法不含字符 '|'，无碰撞。
     */
    public static final String VARIANT_SEPARATOR = "||";

    /** toQuartzDow 单值判定：^\d+$（CC dow 0-7，7=Sun 别名）。 */
    private static final Pattern DOW_SINGLE = Pattern.compile("^\\d+$");
    /** toQuartzDow 区间判定：^(\d+)-(\d+)(?:\/(\d+))?$（CC dow 0-7 含 step）。 */
    private static final Pattern DOW_RANGE = Pattern.compile("^(\\d+)-(\\d+)(?:/(\\d+))?$");
    /** toQuartzDow 步进判定：^\*\/(\d+)$ · 透传（Quartz 自 1=Sun 与 CC 自 0=Sun 展开等价）。 */
    private static final Pattern DOW_STEP = Pattern.compile("^\\*/(\\d+)$");

    private CronExpressionConverter() {
        // 纯函数工具类，禁止实例化。
    }

    /**
     * CC DoW token → Quartz DoW token（私有 5→6 段 dow+1 转换，CRON-B5-1 新建）。
     *
     * <p>CC dow 0=Sun..6=Sat、7=Sun 别名（cron.ts:66）；Quartz dow 1=Sun..7=Sat。映射：
     * <ul>
     *   <li>单值 N（0-7）→ (N%7)+1（7→1 周日别名）</li>
     *   <li>区间 N-M[/S] → 逐值 (v%7)+1，升序去重（如 5-7 → 1,6,7）</li>
     *   <li>步进 *\/N → 原样透传（Quartz 自 1=Sun 起，与 CC 自 0=Sun 起的展开值恒同集）</li>
     *   <li>* / ? → 原样（通配；调用方已按 dom/dow 分流，此分支防御）</li>
     *   <li>逗号列表 → 逐元素处理再合并升序</li>
     *   <li>非法（越界 &gt;7 / 非数值）→ null</li>
     * </ul>
     *
     * @param ccDowToken CC dow 单字段 token（CC original: dayOfWeek 字段，cron.ts:47-70）
     * @return Quartz dow token；非法 → null
     */
    private static String toQuartzDow(String ccDowToken) {
        if (ccDowToken == null) return null;
        String t = ccDowToken.trim();
        if ("*".equals(t) || "?".equals(t)) return t;
        if (DOW_STEP.matcher(t).matches()) return t; // *\/N 透传
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (String part : t.split(",")) {
            part = part.trim();
            Matcher single = DOW_SINGLE.matcher(part);
            if (single.matches()) {
                int n = Integer.parseInt(part);
                if (n > 7) return null; // CC dow 越界（0-7，7 为周日别名）
                out.add((n % 7) + 1);
                continue;
            }
            Matcher range = DOW_RANGE.matcher(part);
            if (range.matches()) {
                int lo = Integer.parseInt(range.group(1));
                int hi = Integer.parseInt(range.group(2));
                int step = range.group(3) != null ? Integer.parseInt(range.group(3)) : 1;
                if (lo > hi || step < 1 || lo < 0 || hi > 7) return null;
                for (int i = lo; i <= hi; i += step) {
                    out.add((i % 7) + 1);
                }
                continue;
            }
            return null; // 非法语法
        }
        if (out.isEmpty()) return null;
        List<Integer> sorted = new ArrayList<>(out);
        sorted.sort(Integer::compareTo);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(sorted.get(i));
        }
        return sb.toString();
    }

    /**
     * 数值字段 token 越界校验（CRON-B5-1 补充闸门）· Quartz 2.5.0 {@code isValidExpression} 对
     * 分钟/秒的 61-99 宽容放行（实测 {@code "0 99 9 ? * *"} 合法且 next 落 :00）——本类必须按 CC
     * 5 字段值域更严拒绝（CronCreateTool errorCode1 / ScheduleService missed 判定依赖非法→null
     * 契约）。仅判定纯数值 token 的单值/区间端点（步进 *\/N 与名称/L/W/#/? 交 Quartz），任一
     * 单值/端点 &gt; max → false。
     *
     * @param field 数值字段 token（minute/hour/dom/month/sec，可含逗号列表）
     * @param max   该字段最大合法值
     * @return token 内所有单值/区间端点 ≤ max
     */
    private static boolean numericFieldInRange(String field, int max) {
        if (field == null) return false;
        for (String part : field.split(",")) {
            part = part.trim();
            Matcher single = DOW_SINGLE.matcher(part);
            if (single.matches()) {
                if (Integer.parseInt(part) > max) return false;
                continue;
            }
            Matcher range = DOW_RANGE.matcher(part);
            if (range.matches()) {
                if (Integer.parseInt(range.group(1)) > max
                    || Integer.parseInt(range.group(2)) > max) {
                    return false;
                }
            }
            // * / *\/N / 名称 / L / W / ? 等：交 Quartz 判定
        }
        return true;
    }

    /**
     * 6 段 5 个数值字段越界校验（秒 0-59 / 分 0-59 / 时 0-23 / 日 1-31 / 月 1-12）·
     * {@link #numericFieldInRange} 打包。Quartz 对时/日/月越界本已 ParseException 拒绝，
     * 秒/分 61-99 需本方法兜底。
     */
    private static boolean quartz6FieldsInRange(String[] parts) {
        return parts.length == 6
            && numericFieldInRange(parts[0], 59)
            && numericFieldInRange(parts[1], 59)
            && numericFieldInRange(parts[2], 23)
            && numericFieldInRange(parts[3], 31)
            && numericFieldInRange(parts[4], 12);
    }

    /**
     * 全值域覆盖判定（IMPL-04 · CC cron.ts:130-131 展开长度判定的 Java 等价）· 按 CC
     * {@code expandField}（cron.ts:31-77）语义把字段 token 展开为值集合，返回
     * {@code 集合大小 == max-min+1}（dom 展开 31 值 / dow 展开 7 值即通配）。
     *
     * <p>判定口径（CC 真源，逐项对应）:
     * <ul>
     *   <li>{@code *} / {@code *\/N}（step&lt;1 → false）→ 自 min 起按 step 展开；全值域仅当
     *       step==1（{@code *\/1} 判通配、{@code *\/2} 判约束；cron.ts:37-43）</li>
     *   <li>区间 {@code N-M[/S]} → dow 时上限 7（cron.ts:52-53）、{@code 7→0} 周日别名
     *       （cron.ts:56）；端点非法（lo&gt;hi / step&lt;1 / lo&lt;min / hi&gt;effMax）→ false</li>
     *   <li>单值 N → dow 时 {@code 7→0} 别名（cron.ts:64-66）；越界 → false</li>
     *   <li>逗号列表 → 逐 part 展开并集；任一非法 part → false（cron.ts:35/:72）</li>
     * </ul>
     * 非法 token（L/W/#/? 等）→ false（走约束分支，Quartz {@code isValidExpression} 终值
     * 闸门兜底，与修复前 B5 委托 Quartz 宽容语义一致）。
     *
     * @param field    dom/dow 单字段 token（CC original: dayOfMonth/dayOfWeek 字段，cron.ts:130-131）
     * @param min      值域下界（dom=1、dow=0；CC FIELD_RANGES cron.ts:23/:25）
     * @param max      值域上界（dom=31、dow=6；CC FIELD_RANGES cron.ts:23/:25）
     * @param dowAlias dow 字段 7=周日别名（CC cron.ts:52-56/:64-66），dom 传 false
     * @return 展开集合覆盖全值域（通配）→ true；否则 false
     */
    private static boolean coversFullRange(String field, int min, int max, boolean dowAlias) {
        if (field == null) return false;
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (String part : field.split(",")) {
            part = part.trim();
            if ("*".equals(part)) {
                for (int i = min; i <= max; i++) out.add(i);
                continue;
            }
            Matcher step = DOW_STEP.matcher(part);
            if (step.matches()) {
                int s = Integer.parseInt(step.group(1));
                if (s < 1) return false;
                for (int i = min; i <= max; i += s) out.add(i);
                continue;
            }
            Matcher range = DOW_RANGE.matcher(part);
            if (range.matches()) {
                int lo = Integer.parseInt(range.group(1));
                int hi = Integer.parseInt(range.group(2));
                int s = range.group(3) != null ? Integer.parseInt(range.group(3)) : 1;
                int effMax = dowAlias ? 7 : max;
                if (lo > hi || s < 1 || lo < min || hi > effMax) return false;
                for (int i = lo; i <= hi; i += s) {
                    out.add(dowAlias && i == 7 ? 0 : i);
                }
                continue;
            }
            Matcher single = DOW_SINGLE.matcher(part);
            if (single.matches()) {
                int n = Integer.parseInt(part);
                if (dowAlias && n == 7) n = 0;
                if (n < min || n > max) return false;
                out.add(n);
                continue;
            }
            if (log.isDebugEnabled()) {
                log.debug("coversFullRange 非法 part 判定非通配，field=[{}] part=[{}]", field, part);
            }
            return false;
        }
        return out.size() == max - min + 1;
    }

    /**
     * 5→6 段 Quartz cron 转换（OPD-Cron-T1-01/02/05 · CRON-B5-1 重写去旧 5 段解析依赖）。
     *
     * <p>5 段 → 前缀 "0 " + DoW 偏移 +1（私有 {@link #toQuartzDow}）；6 段透传（Quartz
     * {@code isValidExpression} 终值闸门，非法 6 段→null，B5 较 B1 更严）；7 段→null；非法→null。
     * dom/dow 通配以 <b>展开长度全值域判定</b>（{@link #coversFullRange}，对齐 CC cron.ts:130-131
     * domWild=展开 31 值 / dowWild=展开 7 值）：{@code *}、{@code *\/1}、全覆盖区间（dom
     * {@code 1-31}；dow {@code 0-6}/{@code 1-7}）、全覆盖列表均判通配；step&gt;1（如
     * {@code *\/2}）展开非全值域、仍判约束：
     * <ul>
     *   <li>domWild &amp;&amp; dowWild → {@code 0 S H ? M *}</li>
     *   <li>domWild → {@code 0 S H ? M dow+1}（dom 用 {@code ?} 满足 Quartz 互斥规则）</li>
     *   <li>dowWild 或双约束 → {@code 0 S H dom M ?}（dom 作主约束；双约束 dow 侧由
     *       {@link #toQuartzCronVariants} 第二变体承担）</li>
     * </ul>
     * 终值以 Quartz {@code CronExpression.isValidExpression} 校验（覆盖内字段越界 / dom-dow 冲突）。
     *
     * @param cron CC original: expr（cron.ts:83）· 5 字段 CC / 6 字段 Quartz
     * @return Quartz 6 段串；null/7 段/非法 → null
     */
    public static String toQuartz6Field(String cron) {
        if (cron == null) return null;
        String trimmed = cron.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 6) {
            // B5 更严：6 段透传以数值越界 + Quartz isValidExpression 双闸门（非法 6 段→null；
            // Quartz 对秒/分 61-99 宽容放行，必须 numericFieldInRange 兜底）
            if (!quartz6FieldsInRange(parts)) {
                if (log.isDebugEnabled()) {
                    log.debug("toQuartz6Field 6 段数值越界拒绝: [{}]", trimmed);
                }
                return null;
            }
            return CronExpression.isValidExpression(trimmed) ? trimmed : null;
        }
        if (parts.length != 5) return null; // 7 段及其他 → null
        String minute = parts[0], hour = parts[1], dom = parts[2], month = parts[3], dow = parts[4];
        if (!numericFieldInRange(minute, 59) || !numericFieldInRange(hour, 23)
            || !numericFieldInRange(dom, 31) || !numericFieldInRange(month, 12)) {
            if (log.isDebugEnabled()) {
                log.debug("toQuartz6Field 5 段数值越界拒绝: [{}]", trimmed);
            }
            return null;
        }
        boolean domWild = coversFullRange(dom, 1, 31, false); // CC cron.ts:130 展开长度==31 判通配
        boolean dowWild = coversFullRange(dow, 0, 6, true);   // CC cron.ts:131 展开长度==7 判通配（dow 7=Sun 别名）
        if (log.isDebugEnabled()) {
            log.debug("toQuartz6Field 5 段通配判定（IMPL-04 全值域口径）: [{}] domWild=[{}] dowWild=[{}]",
                trimmed, domWild, dowWild);
        }
        String out;
        if (domWild && dowWild) {
            out = "0 " + minute + " " + hour + " ? " + month + " *";
        } else if (domWild) {
            String dowQ = toQuartzDow(dow);
            if (dowQ == null) return null;
            out = "0 " + minute + " " + hour + " ? " + month + " " + dowQ;
        } else {
            out = "0 " + minute + " " + hour + " " + dom + " " + month + " ?";
        }
        if (!CronExpression.isValidExpression(out)) {
            if (log.isDebugEnabled()) {
                log.debug("toQuartz6Field Quartz 终值闸门拒绝: [{}] → [{}]", trimmed, out);
            }
            return null;
        }
        return out;
    }

    /**
     * recurring OR 双 trigger 变体列表（OPD-Cron-T1-05，WF-A-OD-12 F2 · CRON-B5-1 重写）· CC
     * 双约束（cron.ts:151-158）dom 或 dow 任一匹配即触发，Quartz 单 trigger 按 AND 解释——
     * 故双约束 → 2 变体（dom-only + dow-only），并集=CC OR；其余（单约束/双通配/6 段）→
     * 单变体；7 段/非法 → null。
     *
     * @param cron CC original: expr（cron.ts:83）· 5 字段 CC / 6 字段 Quartz
     * @return Quartz 6 段变体列表；null → 非法
     */
    public static List<String> toQuartzCronVariants(String cron) {
        if (cron == null) return null;
        String trimmed = cron.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 7) return null;
        if (parts.length == 6) {
            // 与 toQuartz6Field 同闸门：数值越界 + Quartz isValidExpression
            if (!quartz6FieldsInRange(parts)) {
                if (log.isDebugEnabled()) {
                    log.debug("toQuartzCronVariants 6 段数值越界拒绝: [{}]", trimmed);
                }
                return null;
            }
            return CronExpression.isValidExpression(trimmed) ? List.of(trimmed) : null;
        }
        if (parts.length != 5) return null;
        boolean domWild = coversFullRange(parts[2], 1, 31, false); // CC cron.ts:130 展开 31 值判通配
        boolean dowWild = coversFullRange(parts[4], 0, 6, true);   // CC cron.ts:131 展开 7 值判通配
        if (log.isDebugEnabled()) {
            log.debug("toQuartzCronVariants 5 段通配判定（IMPL-04 全值域口径）: [{}] domWild=[{}] dowWild=[{}]",
                trimmed, domWild, dowWild);
        }
        if (!domWild && !dowWild) {
            // 双约束（dom/dow 展开均非全值域，如 '0 9 */2 * 1'）：先数值越界闸门（分钟 61-99 Quartz 宽容，需兜底）
            if (!numericFieldInRange(parts[0], 59) || !numericFieldInRange(parts[1], 23)
                || !numericFieldInRange(parts[2], 31) || !numericFieldInRange(parts[3], 12)) {
                if (log.isDebugEnabled()) {
                    log.debug("toQuartzCronVariants 双约束数值越界拒绝: [{}]", trimmed);
                }
                return null;
            }
            String minute = parts[0], hour = parts[1], dom = parts[2], month = parts[3];
            String dowQ = toQuartzDow(parts[4]);
            if (dowQ == null) return null;
            String domOnly = "0 " + minute + " " + hour + " " + dom + " " + month + " ?";
            String dowOnly = "0 " + minute + " " + hour + " ? " + month + " " + dowQ;
            if (!CronExpression.isValidExpression(domOnly) || !CronExpression.isValidExpression(dowOnly)) {
                if (log.isDebugEnabled()) {
                    log.debug("toQuartzCronVariants 双约束变体 Quartz 拒绝: [{}] → domOnly=[{}] dowOnly=[{}]",
                        cron, domOnly, dowOnly);
                }
                return null;
            }
            if (log.isDebugEnabled()) {
                log.debug("toQuartzCronVariants 双约束 → 2 变体: {} → [{}]", cron, List.of(domOnly, dowOnly));
            }
            return List.of(domOnly, dowOnly);
        }
        String single = toQuartz6Field(trimmed);
        return single == null ? null : List.of(single);
    }

    /**
     * 变体列表 join 成单 cron 字段串（OPD-Cron-T1-05）· 用 {@link #VARIANT_SEPARATOR} 连接
     * {@link #toQuartzCronVariants} 产物。null 或空 → null（上层沿用非法 cron 报错文案）。
     */
    public static String joinVariants(List<String> variants) {
        if (variants == null || variants.isEmpty()) return null;
        return String.join(VARIANT_SEPARATOR, variants);
    }

    /**
     * join 串拆回变体列表（OPD-Cron-T1-05）· QuartzScheduleService 注册多 CronTrigger
     * 前调用。null → null；无分隔符 → 单元素（单变体/6 段透传）。Quartz cron 语法不含 '|'，
     * 分隔符拆分安全。
     */
    public static List<String> splitVariants(String joined) {
        if (joined == null) return null;
        if (!joined.contains(VARIANT_SEPARATOR)) return List.of(joined);
        String[] parts = joined.split(Pattern.quote(VARIANT_SEPARATOR));
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isBlank()) out.add(p.trim());
        }
        return out;
    }

    /**
     * CC 366 天上限毫秒等价（IMPL-03/NEW-1）· CC cron.ts:138 {@code maxIter = 366 * 24 * 60}
     * 分钟 ≈ 366 天，超限 {@code computeNextCronRun} 即 return null（cron.ts:180）→
     * errorCode2「一年内无匹配」。Quartz {@code getNextValidTimeAfter} <b>无此上限</b>
     * （Feb-29 类稀疏 cron 返回任意未来匹配），故 errorCode2 判定须自建等价上限：
     * {@code next - from <= CC_MAX_LOOKAHEAD_MS}（09-open-decisions NEW-1 已定方向，
     * 「自建 366 天上限或等价位」，本常量即等价位单一真源）。
     */
    public static final long CC_MAX_LOOKAHEAD_MS = 366L * 24 * 3600 * 1000;

    /**
     * CC「一年内无匹配」判定（IMPL-03/NEW-1）· CC computeNextCronRun 从 from 取整到分钟 +1 起
     * 逐分钟步进（cron.ts:133-136），超 {@code maxIter=366*24*60} 分钟即 return null
     * （cron.ts:138/:180）→ errorCode2（CronCreateTool.ts:90-96）。本方法等价判定：
     * {@link #nextCronRunMs} 结果 null 或超出 {@link #CC_MAX_LOOKAHEAD_MS} → false。
     *
     * <p>OR-min 语义由 {@link #nextCronRunMs} 多变体取最早保证（5 段双约束拆 2 变体 +
     * '||' 存串拆回，对齐 CC cron.ts:151-158 dom/dow 任一匹配即 fire）——<b>勿逐变体判定</b>，
     * 直接喂原始 cron 串（5 字段 CC / 6 字段 Quartz / '||' 变体串均可）。
     *
     * @param cron   CC original: cron（cronTasks.ts:302）· 5 字段 CC / 6 字段 Quartz / '||' 变体串
     * @param fromMs 起始 epoch 毫秒
     * @return 366 天窗口内存在下一次匹配（含边界）→ true；无匹配 / 超限 → false
     */
    public static boolean hasMatchWithinYear(String cron, long fromMs) {
        Long next = nextCronRunMs(cron, fromMs);
        return next != null && next - fromMs <= CC_MAX_LOOKAHEAD_MS;
    }

    /**
     * nextCronRunMs 包装 · 对齐 CC cronTasks.ts:302-307 + 6 字段/|| 扩展（CRON-B5-1 委托 Quartz）。
     *
     * <p><b>B5 委托方案（探针已验证 Quartz 2.5.0）</b>: 每个变体先经 {@link #toQuartzCronVariants}
     * 归一（5 段转 6 段 / 6 段透传校验），再交 Quartz {@code new CronExpression(v).
     * getNextValidTimeAfter(new Date(fromMs))} 取 getTime。单变体直接委托；{@code A||B} 多变体
     * （splitVariants 拆开）各自算取最早=min（CC cron.ts:151-158 dom/dow OR = 任一变体 fire 即 fire）。
     * <b>语义偏差点（相对 B5 计划原文）</b>: 归一用 toQuartzCronVariants 而非裸 toQuartz6Field——
     * 5 段双约束输入（如 "0 9 1 * 1"，ScheduleService missed 判定 / CronJitter 喂原串）必须拆 2
     * 变体才保留 OR（裸 toQuartz6Field 只给 dom 侧，漏 dow 侧，会与 || 存串 min 结果不等）。
     *
     * <p>ParseException / 非法变体 → 该变体跳过 continue；全败 → null。Quartz
     * getNextValidTimeAfter <b>无 366 天上限</b>（Feb-29 类稀疏 cron 返回任意未来匹配，探针实测
     * 2026-03-01 → 2028-02-29 = 730 天，EV-018 约 731 天系 from 含秒差；仅 Feb-30 类永不匹配
     * → Quartz null）。CC maxIter 366 天上限（cron.ts:138 {@code maxIter = 366 * 24 * 60} 分钟）
     * 的等价校验由 {@code CronCreateTool.validateInput} errorCode2 闸门经 {@link #hasMatchWithinYear}
     * 实现（IMPL-03/NEW-1），本方法保持纯 Quartz 无界语义（findMissedTasks / kickstart 消费方
     * 依赖无界结果）。
     *
     * @param cron   CC original: cron（cronTasks.ts:302）· 5 字段 CC / 6 字段 Quartz / '||' 变体串
     * @param fromMs 起始 epoch 毫秒，返回严格晚于该时刻
     * @return 下一次触发 epoch 毫秒；全部变体非法 / 无匹配时 null
     */
    public static Long nextCronRunMs(String cron, long fromMs) {
        return nextCronRunMs(cron, fromMs, ZoneId.systemDefault());
    }

    /**
     * nextCronRunMs 3 参重载（CRON-B1-4 保留）· 2 参委派 {@code ZoneId.systemDefault()}，本重载供 DST
     * 单测固定时区（{@code ZoneId.of("America/New_York")}）确定性验证，对齐 CC 进程本地时区语义。
     *
     * @param cron   CC original: cron（cronTasks.ts:302）· 5 字段 CC / 6 字段 Quartz / '||' 变体串
     * @param fromMs 起始 epoch 毫秒
     * @param zone   时区（测试传固定区；生产走 systemDefault）
     * @return 下一次触发 epoch 毫秒；全部变体非法 / 无匹配时 null
     */
    public static Long nextCronRunMs(String cron, long fromMs, ZoneId zone) {
        if (cron == null || cron.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("nextCronRunMs 收到 null/空 cron，返回 null");
            }
            return null;
        }
        List<String> variants = splitVariants(cron);
        Long min = null;
        for (String v : variants) {
            List<String> q6s = toQuartzCronVariants(v); // 5 段双约束 → 2 变体(OR)；6 段 → 单变体；非法 → null
            if (q6s == null) {
                if (log.isDebugEnabled()) {
                    log.debug("nextCronRunMs 单变体非法跳过，variant=[{}]", v);
                }
                continue;
            }
            for (String q6 : q6s) {
                Long next = nextSingleQuartz(q6, fromMs, zone);
                if (next == null) continue;
                if (min == null || next < min) min = next;
            }
        }
        if (min == null) {
            if (log.isDebugEnabled()) {
                log.debug("nextCronRunMs 解析失败/无匹配，cron=[{}]（变体数 {}）", cron, variants.size());
            }
        }
        return min;
    }

    /**
     * 单变体 Quartz 委托（CRON-B5-1 私有）· {@code new CronExpression(q6)} + 指定时区 +
     * {@code getNextValidTimeAfter}。ParseException 防御（toQuartzCronVariants 已 isValidExpression
     * 闸门，正常不可达）→ null。
     */
    private static Long nextSingleQuartz(String quartz6, long fromMs, ZoneId zone) {
        try {
            CronExpression ce = new CronExpression(quartz6);
            ce.setTimeZone(TimeZone.getTimeZone(zone));
            Date next = ce.getNextValidTimeAfter(new Date(fromMs));
            return next == null ? null : next.getTime();
        } catch (ParseException e) {
            if (log.isDebugEnabled()) {
                log.debug("nextCronRunMs Quartz 解析异常，q6=[{}]: {}", quartz6, e.getMessage());
            }
            return null;
        }
    }
}
