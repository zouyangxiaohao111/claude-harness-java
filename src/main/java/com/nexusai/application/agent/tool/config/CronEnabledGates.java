package com.nexusai.application.agent.tool.config;

import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cron 三工具门控 · 对齐 CC {@code ScheduleCronTool/prompt.ts} 的 isKairosCronEnabled +
 * isDurableCronEnabled 双门（Spring 运行时等价，接口用 Spring）。
 *
 * <p>CC 真源：
 * <ul>
 *   <li>{@code isKairosCronEnabled()} = {@code feature('AGENT_TRIGGERS') ? !isEnvTruthy(CLAUDE_CODE_DISABLE_CRON) && GB('tengu_kairos_cron', true) : false}
 *       （prompt.ts:36-45）——编译期 AGENT_TRIGGERS 折叠（生产 G15 编译 true）+ 本地 env
 *       kill-switch + GrowthBook 运行门（默认 true）。</li>
 *   <li>{@code isDurableCronEnabled()} = {@code GB('tengu_kairos_cron_durable', true)}
 *       （prompt.ts:56-62）——只受 GB 门，不 consult CLAUDE_CODE_DISABLE_CRON。</li>
 * </ul>
 *
 * <p>Java 映射：
 * <ul>
 *   <li>{@code feature('AGENT_TRIGGERS')} → {@code nexusai.feature.agent-trigger-cron} 属性
 *       （默认 true）；{@code CLAUDE_CODE_DISABLE_CRON} → {@link BundledSkillEnabledGates}
 *       内部 {@code TaskSystemConfig.isEnvTruthy} 判定；GB 'tengu_kairos_cron' 默认 true →
 *       {@code BundledSkillEnabledGates.isKairosCronEnabled(boolean, BooleanSupplier)} 的
 *       kairosCronRuntime 供应（Java 无 GrowthBook，默认 () -&gt; true）。</li>
 *   <li>GB 'tengu_kairos_cron_durable' 默认 true → {@code nexusai.feature.cron-durable} 属性
 *       （默认 true）。</li>
 * </ul>
 *
 * <p><b>默认开</b>：yml 生产默认 true（对齐 CC /loop GA + GB 默认 true）；显式置 false 即关停。
 * {@code @DefaultValue("true")} 保证属性缺省（用户删 yml 项）时仍默认开，与三工具
 * {@code @ConditionalOnProperty(matchIfMissing=true)} 默认注册语义一致。
 *
 * <p><b>null 契约（决策#11 CRON-F2）</b>：本 bean 自身无 null 态（{@link #DEFAULTS} 与默认构造均
 * (true,true) 非 null）。消费方（CronCreateTool/CronDeleteTool/CronListTool 等）遇注入为 null 时
 * 统一 <b>fail-open</b> 处理（null→门开），对齐 CC 无 null 态布尔链（isKairosCronEnabled 恒返回
 * boolean 默认 true）+ TestJob.java:99 / CronIdleExecutor.java:131 既有 fail-open。
 *
 * <p><b>运行时刷新缺口（CRON-F2 登记，本 session 只登记不改）</b>：{@link #isKairosCronEnabled()}
 * 为<b>会话静态值</b>——{@code agentTriggerCron} 由 Spring {@code @ConfigurationProperties} 启动绑定，
 * GB 等价 supplier 硬编码 {@code () -> true}（Java 无 GrowthBook）。对齐 CC：
 * GB 'tengu_kairos_cron' 由 {@code getFeatureValue_CACHED_WITH_REFRESH} 5min TTL 后台 refetch 可
 * 运行时翻转（useScheduledTasks.ts:54-60 注释：launch guard 是启动级，mid-session killswitch 由
 * isKilled 每 tick 轮询 isKairosCronEnabled —— cronScheduler.ts:231 + useScheduledTasks.ts:119）。
 * Java 当前仅启动级（yml/env）killswitch 生效，无法 mid-session 翻转；需 owner 后续接入动态
 * supplier（替换 {@code () -> true}）才真正对齐 CC GB 推送级翻转。
 */
@ConfigurationProperties(prefix = "nexusai.feature")
public record CronEnabledGates(
    /**
     * CC feature('AGENT_TRIGGERS') 等价 · CC original: AGENT_TRIGGERS (prompt.ts:37)。
     * 默认 true（CC 生产 bundle 编译折叠 true，cli.js G15）。
     */
    @DefaultValue("true") boolean agentTriggerCron,
    /**
     * CC GB 'tengu_kairos_cron_durable' 等价 · CC original: tengu_kairos_cron_durable
     * (prompt.ts:57-59)。默认 true（CC GB 无配置默认 true）。
     */
    @DefaultValue("true") boolean cronDurable
) {

    /** 未配置 / 非 Spring 构造（测试）默认：agent-trigger-cron=true + cron-durable=true。 */
    public static final CronEnabledGates DEFAULTS = new CronEnabledGates();

    public CronEnabledGates() {
        this(true, true);
    }

    /**
     * 运行时 isEnabled 门 · CC original: isKairosCronEnabled (prompt.ts:36-45)。
     * 委托 {@link BundledSkillEnabledGates#isKairosCronEnabled(boolean, java.util.function.BooleanSupplier)}
     * 单一 source（agentTriggers && !CLAUDE_CODE_DISABLE_CRON truthy && GB 默认 true），
     * 避免测试/生产双实现漂移。
     */
    public boolean isKairosCronEnabled() {
        return BundledSkillEnabledGates.isKairosCronEnabled(agentTriggerCron, () -> true);
    }

    /**
     * durable 门 · CC original: isDurableCronEnabled (prompt.ts:56-62)。
     * 只读 {@code nexusai.feature.cron-durable}（默认 true），不 consult CLAUDE_CODE_DISABLE_CRON。
     * 由 CRON-A2 CronCreate effectiveDurable 消费（本 session 只暴露门，不触碰 create 内逻辑，防双写）。
     */
    public boolean isDurableCronEnabled() {
        return cronDurable;
    }
}
