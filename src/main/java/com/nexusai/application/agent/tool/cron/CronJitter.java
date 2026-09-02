package com.nexusai.application.agent.tool.cron;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * CronJitter · 对齐 CC {@code Open-ClaudeCode/src/utils/cronTasks.ts:348-445} 的确定性抖动算法
 * （recurring 前向延迟 + one-shot 整点提前），供 CronCreateTool 计算首触发偏移（CRON-F1）。
 *
 * <p><b>WHY</b>: 多 session 排同一 cron（如 {@code 0 * * * *}）会在 :00 同时打爆推理 →
 * thundering herd。CC 用 taskId（8-hex UUID 片）确定性散列把触发点摊开；本类为纯函数、无
 * Spring 依赖。Spring 配置源（application.yml {@code nexusai.cron.jitter.*}）见
 * {@code com.nexusai.application.agent.tool.config.CronJitterProperties}。
 *
 * <p>CC 真源: cronTasks.ts:348-355 (DEFAULT_CRON_JITTER_CONFIG)、:362-365 (jitterFrac)、
 * :381-398 (jitteredNextCronRunMs)、:421-445 (oneShotJitteredNextCronRunMs)。
 */
public final class CronJitter {

    private static final Logger log = LoggerFactory.getLogger(CronJitter.class);

    /** CC 0x1_0000_0000 = 2^32（cronTasks.ts:363 除法归一 [0,1)）。 */
    private static final double TWO_POW_32 = 4294967296.0d;

    private CronJitter() {
        // 纯函数工具类，禁止实例化。
    }

    /**
     * 抖动配置 record · 对齐 CC CronJitterConfig（cronTasks.ts:330-355）。
     * Spring 绑定源 {@code ...config.CronJitterProperties}（nexusai.cron.jitter.*，可覆写）。
     */
    public record CronJitterConfig(
        /** CC original: recurringFrac (cronTasks.ts:348) · recurring 前向延迟占火间隙比例。 */
        double recurringFrac,
        /** CC original: recurringCapMs (cronTasks.ts:349) · recurring 延迟上限 ms。 */
        long recurringCapMs,
        /** CC original: oneShotMaxMs (cronTasks.ts:350) · one-shot 提前量上限 ms。 */
        long oneShotMaxMs,
        /** CC original: oneShotFloorMs (cronTasks.ts:351) · one-shot 提前量下限 ms（floor>0 时没人踩整点）。 */
        long oneShotFloorMs,
        /** CC original: oneShotMinuteMod (cronTasks.ts:352) · one-shot 整点分钟模数（默认 30 → 仅 :00/:30 抖动）。 */
        int oneShotMinuteMod,
        /** CC original: recurringMaxAgeMs (cronTasks.ts:345) · recurring 过期窗口，本类不消费，仅配置完整性。 */
        long recurringMaxAgeMs
    ) {
        /** 对齐 CC DEFAULT_CRON_JITTER_CONFIG（cronTasks.ts:348-355）。 */
        public static final CronJitterConfig DEFAULT = new CronJitterConfig(
            0.1, 15L * 60 * 1000, 90L * 1000, 0L, 30, 7L * 24 * 60 * 60 * 1000);

        /** 缺省构造（非 Spring/测试用）→ DEFAULT。 */
        public CronJitterConfig() {
            this(DEFAULT.recurringFrac(), DEFAULT.recurringCapMs(), DEFAULT.oneShotMaxMs(),
                DEFAULT.oneShotFloorMs(), DEFAULT.oneShotMinuteMod(), DEFAULT.recurringMaxAgeMs());
        }
    }

    /**
     * CC jitterFrac（cronTasks.ts:362-365）· taskId 8-hex 前 8 字符 → u32 → [0,1)。
     * 跨重启稳定、全 fleet 均匀分布；非 hex id（手编 JSON）回退 0 = 无抖动。
     *
     * @param taskId CC original: taskId（cronTasks.ts:362）· 应为 schedule id 后 8 字符的 hex 片
     * @return [0,1) 确定性散列；null / 非 8-hex → 0
     */
    public static double jitterFrac(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return 0.0d;
        }
        String hex = taskId.length() > 8 ? taskId.substring(0, 8) : taskId;
        long parsed;
        try {
            parsed = Long.parseLong(hex, 16); // parseInt(…,16)，非全 hex → NumberFormatException
        } catch (NumberFormatException e) {
            if (log.isDebugEnabled()) {
                log.debug("CronJitter.jitterFrac: taskId 非 hex 回退 0，taskId=[{}]", taskId);
            }
            return 0.0d;
        }
        return parsed / TWO_POW_32;
    }

    /**
     * CC jitteredNextCronRunMs（cronTasks.ts:381-398）· recurring 前向延迟。
     *
     * <p>t1 后加 min(frac*recurringFrac*(t2-t1), recurringCapMs)：默认下每小时任务摊到
     * [:00,:06)，每分钟任务只摊几秒；t2 无匹配（钉死日期）→ 直接 t1。
     *
     * @param cron   CC original: cron（cronTasks.ts:381）· 6 字段 Quartz cron（B5 全 6 字段后，委托 Quartz getNextValidTimeAfter）
     * @param fromMs 起始 epoch 毫秒
     * @param taskId 8-hex 确定性散列输入
     * @param cfg    抖动配置（null → DEFAULT）
     * @return 抖动后首次触发 epoch 毫秒；非法 / 无匹配 → null
     */
    public static Long jitteredNextCronRunMs(String cron, long fromMs, String taskId, CronJitterConfig cfg) {
        CronJitterConfig c = cfg != null ? cfg : CronJitterConfig.DEFAULT;
        Long t1 = CronExpressionConverter.nextCronRunMs(cron, fromMs);
        if (t1 == null) {
            return null;
        }
        Long t2 = CronExpressionConverter.nextCronRunMs(cron, t1);
        if (t2 == null) { // cronTasks.ts:391-392 · 明年无第二匹配 → 无 herd 风险，直发 t1
            return t1;
        }
        double frac = jitterFrac(taskId);
        double jitter = Math.min(frac * c.recurringFrac() * (t2 - t1), (double) c.recurringCapMs());
        long result = (long) (t1 + jitter);
        if (log.isDebugEnabled()) {
            log.debug("CronJitter.jitteredNextCronRunMs: cron=[{}] t1={} t2={} frac={} jitter={} → result={}",
                cron, t1, t2, frac, jitter, result);
        }
        return result;
    }

    /**
     * CC jitteredNextCronRunMs 3 参便捷版 · cfg 缺省 → DEFAULT（对齐 CC 默认参数）。
     */
    public static Long jitteredNextCronRunMs(String cron, long fromMs, String taskId) {
        return jitteredNextCronRunMs(cron, fromMs, taskId, CronJitterConfig.DEFAULT);
    }

    /**
     * CC oneShotJitteredNextCronRunMs（cronTasks.ts:421-445）· one-shot 整点提前。
     *
     * <p>用户钉死时间（"3pm 提醒"）不能延后 → 提前几秒不可见且摊开 :00/:30 推理尖峰。
     * 仅当 t1 的<b>本地</b>分钟 % oneShotMinuteMod == 0（人只会挑整半点）才抖动；提前量
     * floor + frac*(max-floor)；任务创建于自身提前窗内时钳到 fromMs（cronTasks.ts:444）。
     *
     * @param cron   CC original: cron（cronTasks.ts:421）· 6 字段 Quartz cron（B5 全 6 字段后，委托 Quartz getNextValidTimeAfter）
     * @param fromMs 起始 epoch 毫秒
     * @param taskId 8-hex 确定性散列输入
     * @param cfg    抖动配置（null → DEFAULT）
     * @return 抖动后触发 epoch 毫秒；非法 / 无匹配 → null
     */
    public static Long oneShotJitteredNextCronRunMs(String cron, long fromMs, String taskId, CronJitterConfig cfg) {
        CronJitterConfig c = cfg != null ? cfg : CronJitterConfig.DEFAULT;
        Long t1 = CronExpressionConverter.nextCronRunMs(cron, fromMs);
        if (t1 == null) {
            return null;
        }
        Long result = jitterOneShotFireTime(t1, fromMs, taskId, c);
        if (log.isDebugEnabled()) {
            log.debug("CronJitter.oneShotJitteredNextCronRunMs: cron=[{}] fromMs={} t1={} → jittered={}",
                cron, fromMs, t1, result);
        }
        return result;
    }

    /**
     * CC oneShotJitteredNextCronRunMs 核心数学（cronTasks.ts:427-444）· 对<b>已算出</b>的 fire
     * 时间 t1 施加 one-shot 整点提前。CRON-B2-2（决策 #2 / OPD-Cron-F1-b）抽取供 REST 直建
     * one-shot 复用同一套 jitter 数学（工具路径 CronCreateTool:368 经 {@link #oneShotJitteredNextCronRunMs}
     * 解析 cron 后委托本方法；REST 路径直接以 runAt epoch 为 t1 调用）。
     *
     * <p>仅当 t1 的<b>本地</b>分钟 % oneShotMinuteMod == 0（人只会挑整半点）才抖动；提前量
     * floor + frac*(max-floor)；任务创建于自身提前窗内时钳到 fromMs（cronTasks.ts:444）。
     *
     * @param t1     CC original: nextCronRunMs(cron, fromMs) 的结果（cronTasks.ts:427）· REST 侧 =
     *               用户钉死 runAt 的 epoch 毫秒（保留秒，不往返 cron 以免截秒/跳次）
     * @param fromMs 起始 epoch 毫秒（CC 锚 = createdAt，cronTasks.ts:443 钳制下界）
     * @param taskId 8-hex 确定性散列输入（同 cronTasks.ts:362-365 jitterFrac）
     * @param cfg    抖动配置（null → DEFAULT）
     * @return 抖动后触发 epoch 毫秒（恒非 null：t1 为定值，钳制只影响取值）
     */
    public static long jitterOneShotFireTime(long t1, long fromMs, String taskId, CronJitterConfig cfg) {
        CronJitterConfig c = cfg != null ? cfg : CronJitterConfig.DEFAULT;
        // getMinutes() 本地时区（cronTasks.ts:435 · 用户挑的整点 = 其本地 TZ 整点；UTC+5:30 等半时区
        // 下 UTC 分钟校验会抖动错 mark）
        int localMinute = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t1), ZoneId.systemDefault())
            .getMinute();
        if (localMinute % c.oneShotMinuteMod() != 0) { // cronTasks.ts:435 · 非整点 mark → 不抖动
            return t1;
        }
        double lead = c.oneShotFloorMs() + jitterFrac(taskId) * (c.oneShotMaxMs() - c.oneShotFloorMs());
        // CC cronTasks.ts:444 Math.max(t1 - lead, fromMs)：t1 为整数 ms，floor(t1-lead)=t1-ceil(lead)
        // （lead 带小数时），用 ceil 保留 CC float 时间戳的 floor 语义，避免 (long) 截断偏 1ms。
        long result = Math.max(t1 - (long) Math.ceil(lead), fromMs); // cronTasks.ts:444 · 钳到 fromMs
        if (log.isDebugEnabled()) {
            log.debug("CronJitter.jitterOneShotFireTime: t1={} localMinute={} lead={} fromMs={} → result={}",
                t1, localMinute, lead, fromMs, result);
        }
        return result;
    }

    /**
     * CC oneShotJitteredNextCronRunMs 3 参便捷版 · cfg 缺省 → DEFAULT（对齐 CC 默认参数）。
     */
    public static Long oneShotJitteredNextCronRunMs(String cron, long fromMs, String taskId) {
        return oneShotJitteredNextCronRunMs(cron, fromMs, taskId, CronJitterConfig.DEFAULT);
    }
}
