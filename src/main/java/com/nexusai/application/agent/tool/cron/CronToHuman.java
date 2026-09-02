package com.nexusai.application.agent.tool.cron;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CronToHuman · 对齐 CC {@code Open-ClaudeCode/src/utils/cron.ts:218-308} 的
 * {@code cronToHuman} 工具函数：把 cron 表达式翻译为人类可读的调度说明（humanSchedule）。
 *
 * <p><b>WHY (CRON-A1b)</b>: CronCreate/CronList 对用户暴露的 humanSchedule 显示文本必须与 CC
 * 一致（CronCreateTool.ts:137 humanSchedule=cronToHuman(cron)；CronListTool.ts:73 jobs 映射）。
 * 本类为纯函数、无 Spring 依赖，供 WF-A CRON-A2/A4 接线消费。
 *
 * <p><b>与 CC 的差异（WF-A-OD-8 + B5 A4 算法层偏离）</b>: CC 的 {@code opts.utc} 仅用于 CCR 远程
 * 触发器（agents-platform.tsx），Java 无此路径，故只实现本地时区 {@link #cronToHuman(String)}；
 * {@code formatUtcTimeAsLocal}（cron.ts:207-216）与 utc 分支（cron.ts:288-295）登记不实现。
 * <b>B5 A4 拍板（open-decisions.md:156）</b>: 本类改为<b>直接 6 字段算法</b>（秒 分 时 dom 月 dow），
 * 不再归一 5 段走 CC cron.ts 5 字段分支——接受 CC cron.ts 算法层偏离，Java 不再逐行对齐其
 * 5 字段索引，而是委托 Quartz 6 字段原生能力。CC 5 字段分支的 Every N minutes / Every hour /
 * Every N hours / Daily / DOW / Weekdays 语义保留并映射到 6 字段索引；6→5 归一桥
 * （normalizeQuartz6ToCc5 等 4 方法）已删净（A4 推翻 B1 桥接）。
 *
 * <p>once 任务落库 cron=null（CronCreateTool:361 cronForSchedule=null）→ 返回 null（CronList
 * 改用 runAt 格式化，防 NPE）。
 *
 * <p>CC 真源 (Pattern #9): {@code Open-ClaudeCode/src/utils/cron.ts:190-308}
 * <ul>
 *   <li>{@code DAY_NAMES} cron.ts:190-198</li>
 *   <li>{@code formatLocalTime} cron.ts:200-205（toLocaleTimeString('en-US',{hour:'numeric',minute:'2-digit'})）</li>
 *   <li>{@code cronToHuman} cron.ts:218-308</li>
 * </ul>
 */
public final class CronToHuman {

    private static final Logger log = LoggerFactory.getLogger(CronToHuman.class);

    /** CC DAY_NAMES（cron.ts:190-198）· 星期名数组，索引 0=Sunday。 */
    private static final List<String> DAY_NAMES = List.of(
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    );

    /** CC formatLocalTime（cron.ts:200-205）· en-US 12 小时制 "h:mm a"。 */
    private static final DateTimeFormatter LOCAL_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    /** B5 新增 · 秒显示专用 "h:mm:ss a"（非 0 秒 cron 如 "30 0 9 * * *" → "9:00:30 AM"）。 */
    private static final DateTimeFormatter LOCAL_TIME_SEC_FORMATTER =
        DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US);

    /** CC /^\*\/(\d+)$/（cron.ts:232/258）· step 步进匹配。 */
    private static final Pattern STEP_PATTERN = Pattern.compile("^\\*/(\\d+)$");
    /** CC /^\d+$/（cron.ts:246/260/274）· 纯数字匹配。 */
    private static final Pattern DIGITS_PATTERN = Pattern.compile("^\\d+$");
    /** CC /^\d$/（cron.ts:285）· 单数字星期匹配。 */
    private static final Pattern SINGLE_DIGIT_PATTERN = Pattern.compile("^\\d$");

    private CronToHuman() {
        // 纯函数工具类，禁止实例化。
    }

    /**
     * CC cronToHuman（cron.ts:218-308）· 将 cron 翻译为人类可读的调度说明。
     *
     * <p><b>B5 全 6 字段（A4 拍板，open-decisions.md:156）</b>: 本方法重写为<b>直接 6 字段算法</b>——
     * 不再归一 5 段走 CC 分支。6 段输入（生产存储，CronExpressionConverter.toQuartz6Field 产物）
     * 直接按索引匹配（0=秒 1=分 2=时 3=dom 4=月 5=dow）；5 段输入（CronCreateTool 原始 LLM 输入）
     * 委托 {@link CronExpressionConverter#toQuartz6Field} 转 6 段仅用于匹配（A4 拍板保留 5→6 桥），
     * 转换失败/未命中模式 → 兜底返回<b>原始输入串</b>（对齐 CC cron.ts:221/:307 {@code return cron}；
     * B1-1 的"归一 5 段返串"契约被 A4 推翻）。其他段数 → 原样返回。null 输入（once 落库 cron=null）
     * → null（B1-1 守卫保留，CronList 改用 runAt 格式化，防 NPE）。
     *
     * <p>秒段语义（B5 新增）: 生产路径秒恒为 "0"（toQuartz6Field 前缀 "0 "），行为与 B1-1 完全一致
     * （"Every day at 9:00 AM"）；非 0 秒（1-59）显示秒（"Every day at 9:00:30 AM"）。
     * interval 分支（Every N minutes/hour）要求秒=="0"，秒≠0 → 兜底原串（fail-loud，避免
     * "Every 5 minutes at :30" 歧义英文，计划已留位的小扩展，待用户拍板）。
     *
     * @param cron CC original: cron（cron.ts:218）· 6 段 Quartz（秒 分 时 dom 月 dow）或 5 段 CC；可为 null
     * @return 人类可读调度说明；未命中模式时返回原始输入串；null 输入 → null
     */
    public static String cronToHuman(String cron) {
        if (cron == null) {
            // CRON-B1-1 · once 任务落库 cron=null（CronCreateTool:361 cronForSchedule=null），
            // 防 cron.trim() NPE（复验版 §9-3 Δ2：CronListTool:253 cronToHuman(null) NPE）
            if (log.isDebugEnabled()) {
                log.debug("cronToHuman 收到 null（once 任务 cron=null），返回 null");
            }
            return null;
        }
        String[] parts = cron.trim().split("\\s+");
        if (parts.length == 6) {
            // B5 · 生产存储 6 段 Quartz → 直接 6 字段匹配（0=秒 1=分 2=时 3=dom 4=月 5=dow），
            // 不再走 B1-1 的 normalizeQuartz6ToCc5 6→5 归一桥（A4 拍板删除）。
            return match6Fields(parts, cron);
        }
        if (parts.length == 5) {
            // B5 · 5 段 CC 输入（CronCreateTool 原始 LLM 输入）委托 toQuartz6Field（5→6，A4 保留桥）
            // 仅用于匹配；转换失败 → 返回原串（CC cron.ts:221 非 5 字段兜底语义）。
            String q6 = CronExpressionConverter.toQuartz6Field(cron.trim());
            if (q6 == null) {
                if (log.isDebugEnabled()) {
                    log.debug("cronToHuman 5 段转 6 段失败，原样返回 [{}]", cron);
                }
                return cron;
            }
            if (log.isDebugEnabled()) {
                log.debug("cronToHuman 5 段委托 toQuartz6Field: [{}] → [{}]", cron, q6);
            }
            return match6Fields(q6.split("\\s+"), cron);
        }
        if (log.isDebugEnabled()) {
            log.debug("cronToHuman 非 5/6 段（实际 {} 段），原样返回 [{}]", parts.length, cron);
        }
        return cron; // CC cron.ts:221 · 非 5 字段 → 原样返回
    }

    /**
     * 6 字段直接匹配（B5 新增私有）· CC cron.ts:218-308 5 字段分支语义映射到 Quartz 6 字段索引
     * （0=秒 1=分 2=时 3=dom 4=月 5=dow）。分支顺序严格按 CC：Every N minutes → Every hour →
     * Every N hours → 数值门 → 秒门 → Daily → DOW → Weekdays → 兜底。未命中任何模式返回
     * fallback（调用方传原始输入串，对齐 CC cron.ts:307 {@code return cron}）。
     *
     * <p>秒段恒 "0" 时行为与 B1-1 一致；非 0 秒（1-59）在时间分支显示秒。dom/dow 通配用
     * {@link #isWild}（{@code *} 或 {@code ?} 均通配，替代 B1-1 的 normalizeQuartzDom ?→* 桥）。
     * dow 保持 Quartz 1-7（1=Sun），单数字分支 {@code (dow-1)%7} 对齐 CC 归一后 %7 语义；
     * dow="0"（非法 Quartz）1-7 门拦截 → 兜底，防 {@code DAY_NAMES.get(-1)} 越界崩溃。
     *
     * @param parts6   6 段拆分数组（0=秒 1=分 2=时 3=dom 4=月 5=dow）
     * @param fallback 未命中模式时返回的串（调用方传原始输入，CC cron.ts:307）
     * @return 人类可读调度说明；未命中 → fallback
     */
    private static String match6Fields(String[] parts6, String fallback) {
        String sec = parts6[0];
        String minute = parts6[1];
        String hour = parts6[2];
        String dom = parts6[3];
        String month = parts6[4];
        String dow = parts6[5];

        // Every N minutes: 0 step/N * * * *（CC cron.ts:232-242，6 段映射；interval 分支要求秒=0）
        Matcher everyMinMatch = STEP_PATTERN.matcher(minute);
        if (sec.equals("0") && everyMinMatch.matches() && isWild(hour) && isWild(dom)
            && isWild(month) && isWild(dow)) {
            int n = Integer.parseInt(everyMinMatch.group(1));
            String result = n == 1 ? "Every minute" : "Every " + n + " minutes";
            if (log.isDebugEnabled()) {
                log.debug("cronToHuman 命中 Every N minutes，cron=[{}] → [{}]", fallback, result);
            }
            return result;
        }

        // Every hour: 0 M * * * *（CC cron.ts:245-255，6 段映射）
        if (sec.equals("0") && DIGITS_PATTERN.matcher(minute).matches() && isWild(hour)
            && isWild(dom) && isWild(month) && isWild(dow)) {
            int m = Integer.parseInt(minute);
            String result = m == 0 ? "Every hour" : "Every hour at :" + pad2(m);
            if (log.isDebugEnabled()) {
                log.debug("cronToHuman 命中 Every hour，cron=[{}] → [{}]", fallback, result);
            }
            return result;
        }

        // Every N hours: 0 M step/N * * *（CC cron.ts:258-269，6 段映射）
        Matcher everyHourMatch = STEP_PATTERN.matcher(hour);
        if (sec.equals("0") && DIGITS_PATTERN.matcher(minute).matches() && everyHourMatch.matches()
            && isWild(dom) && isWild(month) && isWild(dow)) {
            int n = Integer.parseInt(everyHourMatch.group(1));
            int m = Integer.parseInt(minute);
            String suffix = m == 0 ? "" : " at :" + pad2(m);
            String result = n == 1 ? "Every hour" + suffix : "Every " + n + " hours" + suffix;
            if (log.isDebugEnabled()) {
                log.debug("cronToHuman 命中 Every N hours，cron=[{}] → [{}]", fallback, result);
            }
            return result;
        }

        // 数值门（CC cron.ts:274）· minute/hour 任一非纯数字 → 兜底原串
        if (!DIGITS_PATTERN.matcher(minute).matches() || !DIGITS_PATTERN.matcher(hour).matches()) {
            if (log.isDebugEnabled()) {
                log.debug("cronToHuman 数值门拦截（minute/hour 非纯数字），原样返回 [{}]", fallback);
            }
            return fallback;
        }
        int m = Integer.parseInt(minute);
        int h = Integer.parseInt(hour);

        // 秒门（B5 新增）· sec=="0" → 不显示秒（s=-1）；sec 1-59 → 显示秒；其余（如 step 步进）→ 兜底原串（fail-loud）
        int s;
        if (sec.equals("0")) {
            s = -1;
        } else if (DIGITS_PATTERN.matcher(sec).matches()) {
            int secVal = Integer.parseInt(sec);
            if (secVal >= 1 && secVal <= 59) {
                s = secVal;
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("cronToHuman 秒段越界（1-59，收到 [{}]），原样返回 [{}]", sec, fallback);
                }
                return fallback;
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("cronToHuman 秒段非纯数字 [{}]（如 step 步进），原样返回 [{}]", sec, fallback);
            }
            return fallback;
        }

        // Daily at specific time: 0 M H * * *（CC cron.ts:280-282，6 段映射）
        if (isWild(dom) && isWild(month) && isWild(dow)) {
            String result = "Every day at " + formatTime(m, h, s);
            if (log.isDebugEnabled()) {
                log.debug("cronToHuman 命中 Daily，cron=[{}] → [{}]", fallback, result);
            }
            return result;
        }

        // Specific day of week: 0 M H * * D（CC cron.ts:285-300，Quartz dow 1-7, 1=Sun）
        if (isWild(dom) && isWild(month) && SINGLE_DIGIT_PATTERN.matcher(dow).matches()) {
            int dowVal = Integer.parseInt(dow);
            if (dowVal < 1 || dowVal > 7) { // dow="0" 非法 Quartz 1-7 门兜底，防 (0-1)%7=-1 越界崩溃
                if (log.isDebugEnabled()) {
                    log.debug("cronToHuman DOW 越界（Quartz 应为 1-7，收到 [{}]），原样返回 [{}]", dow, fallback);
                }
                return fallback;
            }
            int dayIndex = (dowVal - 1) % 7;
            String dayName = DAY_NAMES.get(dayIndex);
            String result = "Every " + dayName + " at " + formatTime(m, h, s);
            if (log.isDebugEnabled()) {
                log.debug("cronToHuman 命中 DOW，cron=[{}] → [{}]", fallback, result);
            }
            return result;
        }

        // Weekdays: 0 M H * * 2-6 / 2,3,4,5,6（CC cron.ts:303-304 "1-5" → Quartz Mon-Fri 两形态）
        if (isWild(dom) && isWild(month) && (dow.equals("2-6") || dow.equals("2,3,4,5,6"))) {
            String result = "Weekdays at " + formatTime(m, h, s);
            if (log.isDebugEnabled()) {
                log.debug("cronToHuman 命中 Weekdays，cron=[{}] → [{}]", fallback, result);
            }
            return result;
        }

        // 兜底（CC cron.ts:307）· 未命中任何模式 → 返回原始输入串
        if (log.isDebugEnabled()) {
            log.debug("cronToHuman 未命中任何模式，原样返回 [{}]", fallback);
        }
        return fallback;
    }

    /**
     * 通配判定（B5 新增）· Quartz 6 字段 dom/dow 位可为 {@code *} 或 {@code ?}（互斥占位符），
     * 均视为通配。替代 B1-1 的 normalizeQuartzDom/normalizeQuartzDow 的 ?→* 桥（A4 拍板删除）。
     */
    private static boolean isWild(String field) {
        return "*".equals(field) || "?".equals(field);
    }

    /**
     * 时间显示（B5 新增）· 秒段恒 "0"（s=-1）→ 12 小时制 "h:mm a"（现状样式 "9:00 AM"）；
     * 非 0 秒（s≥1）→ "h:mm:ss a" 显示秒（"9:00:30 AM"）。不得用 {@link #formatLocalTime}
     * 结果后置拼接 ":30"（会得 "9:00 AM:30" 错误），必须走秒专用 formatter LOCAL_TIME_SEC_FORMATTER。
     *
     * @param minute 分（cron.ts:200 CC original: minute）
     * @param hour   时（cron.ts:200 CC original: hour）
     * @param sec    秒（B5 新增；-1 表示秒段 "0" 不显示，≥1 显示）
     * @return en-US 12 小时制时间文本
     */
    private static String formatTime(int minute, int hour, int sec) {
        if (sec <= 0) {
            return formatLocalTime(minute, hour);
        }
        return LOCAL_TIME_SEC_FORMATTER.format(LocalTime.of(hour, minute, sec));
    }

    /**
     * CC formatLocalTime（cron.ts:200-205）· 12 小时制人类可读时间（en-US "h:mm a"）。
     *
     * <p>差异登记：JS {@code toLocaleTimeString} 对 minute&gt;59 / hour&gt;23 会回卷 Date，
     * Java {@link LocalTime#of} 抛异常——本方法仅面向合法 cron 值（单测只覆盖合法输入）。
     */
    private static String formatLocalTime(int minute, int hour) {
        return LOCAL_TIME_FORMATTER.format(LocalTime.of(hour, minute));
    }

    /** CC toString().padStart(2, '0')（cron.ts:254/268）· 分钟补零两位。 */
    private static String pad2(int value) {
        return String.format(Locale.ROOT, "%02d", value);
    }
}
