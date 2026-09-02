package com.nexusai.application.agent.tool.config;

import com.nexusai.application.agent.tool.cron.CronJitter.CronJitterConfig;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Cron 抖动配置 · 对齐 CC {@code DEFAULT_CRON_JITTER_CONFIG}
 * （Open-ClaudeCode/src/utils/cronTasks.ts:348-355）+ GrowthBook 可覆写 schema
 * （cronJitterConfig.ts:37-52 校验与默认回退）。Spring 接口等价：application.yml
 * {@code nexusai.cron.jitter.*} 类型化绑定。
 *
 * <p><b>默认值逐字对齐 CC</b>：recurringFrac=0.1 / recurringCapMs=15min /
 * oneShotMaxMs=90s / oneShotFloorMs=0 / oneShotMinuteMod=30 / recurringMaxAgeMs=7d。
 * {@code @DefaultValue} 保证 yml 缺省（用户删项）时仍回退默认。
 *
 * <p><b>边界校验（对齐 CC cronJitterConfig.ts:40-50 Zod 逐字段约束）</b>：
 * <ul>
 *   <li>recurringFrac ∈ [0,1]（:40 z.number().min(0).max(1)）</li>
 *   <li>recurringCapMs / oneShotMaxMs / oneShotFloorMs ∈ [0,1800000]（:41-43，HALF_HOUR_MS=1800000）</li>
 *   <li>oneShotMinuteMod ∈ [1,60]（:44 z.number().int().min(1).max(60)）</li>
 *   <li>recurringMaxAgeMs ∈ [0,2592000000]（:45-50，THIRTY_DAYS_MS=2592000000）</li>
 * </ul>
 * {@code @Validated + @Min/@Max} 走 Spring 绑定校验：yml 任一项越界 → 启动即失败（abort）。
 * <b>WHY（决策 A6 拍板）</b>：CC safeParse 对越界软回退 DEFAULT（cronJitterConfig.ts:74），
 * Java 侧取「拦截」语义（用户决策的非对齐偏差）——误配在启动期显式暴露，而非静默吃默认。
 * 跨字段 {@code oneShotFloorMs ≤ oneShotMaxMs}（CC :52 refine）无法用 @Min/@Max 表达，
 * 由 {@link #toConfig()} 消费前 log.error 回退 DEFAULT（等价 :52→:74 整对象回退）。
 *
 * <p>消费：{@link CronJitter} 纯函数以 {@code CronJitterConfig} 为 cfg 入参；
 * CronCreateTool 注入本 record 并 {@code toConfig()} 后传入（接线见 CronCreateTool）。
 */
@Validated
@ConfigurationProperties(prefix = "nexusai.cron.jitter")
public record CronJitterProperties(
    /**
     * CC original: recurringFrac (cronTasks.ts:348) · recurring 前向延迟占火间隙比例。
     * 默认 0.1；边界 [0,1]（cronJitterConfig.ts:40 z.number().min(0).max(1)）。
     */
    @DefaultValue("0.1")
    @Min(0) @Max(1) double recurringFrac,
    /**
     * CC original: recurringCapMs (cronTasks.ts:349) · recurring 延迟上限 ms。默认 900000（15min）。
     * 边界 [0,1800000]（cronJitterConfig.ts:41 z.number().int().min(0).max(HALF_HOUR_MS)）。
     */
    @DefaultValue("900000")
    @Min(0) @Max(1_800_000) long recurringCapMs,
    /**
     * CC original: oneShotMaxMs (cronTasks.ts:350) · one-shot 提前量上限 ms。默认 90000（90s）。
     * 边界 [0,1800000]（cronJitterConfig.ts:42）。
     */
    @DefaultValue("90000")
    @Min(0) @Max(1_800_000) long oneShotMaxMs,
    /**
     * CC original: oneShotFloorMs (cronTasks.ts:351) · one-shot 提前量下限 ms。默认 0。
     * 边界 [0,1800000]（cronJitterConfig.ts:43）；floor≤max 交叉约束由 CC refine（:52）表达，
     * Java 端在 {@link #toConfig()} 拦截回退 DEFAULT。
     */
    @DefaultValue("0")
    @Min(0) @Max(1_800_000) long oneShotFloorMs,
    /**
     * CC original: oneShotMinuteMod (cronTasks.ts:352) · one-shot 整点分钟模数。默认 30
     * （仅 :00/:30 抖动）。边界 [1,60]（cronJitterConfig.ts:44 z.number().int().min(1).max(60)）。
     */
    @DefaultValue("30")
    @Min(1) @Max(60) int oneShotMinuteMod,
    /**
     * CC original: recurringMaxAgeMs (cronTasks.ts:345) · recurring 过期窗口，由
     * {@link com.nexusai.domain.schedule.ScheduleService#resolveRecurringMaxAgeMs} 运行时消费
     * （对齐 CC cronScheduler.ts:302 fire 时刻读 jitterCfg.recurringMaxAgeMs，配置 push 即生效）。
     * CronJitter 不消费。默认 604800000（7d）；边界 [0,2592000000]
     * （cronJitterConfig.ts:45-50，上限 2592000000 超 int 范围须写 2_592_000_000L 长字面量）。
     */
    @DefaultValue("604800000")
    @Min(0) @Max(2_592_000_000L) long recurringMaxAgeMs
) {

    private static final Logger log = LoggerFactory.getLogger(CronJitterProperties.class);

    /** 未配置 / 非 Spring 构造（测试）默认 · 对齐 CC DEFAULT_CRON_JITTER_CONFIG。 */
    public static final CronJitterProperties DEFAULTS = new CronJitterProperties();

    public CronJitterProperties() {
        this(0.1, 900000L, 90000L, 0L, 30, 604800000L);
    }

    /**
     * 转 {@link CronJitterConfig} 供 CronJitter 纯函数消费 · 双 record 单一定义点防漂移
     * （CronJitterConfig.DEFAULT 为纯函数默认，本 record 为 Spring 可覆写源，CronCreateTool 注入后
     * 以本方法产物作为 cfg 传入，无 Spring 注入时 3 参便捷版回退 CronJitterConfig.DEFAULT）。
     *
     * <p><b>floor&gt;max 交叉校验（对齐 CC cronJitterConfig.ts:52 refine → :74 整对象回退）</b>：
     * oneShotFloorMs &gt; oneShotMaxMs 时 jitter 区间反置（cronTasks.ts:440-441
     * {@code floor + jitterFrac * (max - floor)} 得负区间），CC refine 失败整对象回退
     * DEFAULT_CRON_JITTER_CONFIG。此处等价：log.error 警告后返回 {@link CronJitterConfig#DEFAULT}，
     * 消费方拿到合法区间而非反置区间（QuartzScheduleService/ScheduleService/CronCreateTool 共享此回退）。
     */
    public CronJitterConfig toConfig() {
        if (oneShotFloorMs > oneShotMaxMs) {
            log.error("[CronJitterProperties] 配置非法：oneShotFloorMs={} > oneShotMaxMs={}，"
                    + "jitter 区间反置（对齐 CC cronJitterConfig.ts:52 refine → :74 整对象回退 DEFAULT），"
                    + "已回退默认值 oneShotMaxMs=90000/oneShotFloorMs=0，请修正 nexusai.cron.jitter.* 配置",
                oneShotFloorMs, oneShotMaxMs);
            return CronJitterConfig.DEFAULT;
        }
        return new CronJitterConfig(
            recurringFrac, recurringCapMs, oneShotMaxMs, oneShotFloorMs, oneShotMinuteMod, recurringMaxAgeMs);
    }
}
