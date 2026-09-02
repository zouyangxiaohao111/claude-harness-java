package com.nexusai.application.agent.subagent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fork subagent 运行时门槽配置 · 用户拍板 RES-SP23-1（09-open-decisions.md §十一 SP23-1）：
 * 生产 SubagentTool 为 {@code new} 构造 → 旧 @Value setter 注入不触发 → 门停默认 {true,false,false}。
 * 由本配置类承载 {@code nexusai.fork.*} 单一配置源，经 {@link ForkSubagentConfigBootstrap} 注册静态
 * {@link #current()}，SubagentTool 读配置同步 {@link ForkSubagent#syncRuntimeGate}（对齐 CC
 * {@code feature('FORK_SUBAGENT')} 进程级全局语义，forkSubagent.ts:32-39）。
 *
 * <p><b>注册模式</b>: 对齐 {@code PromptCachingTtlConfig}（plain class + DEFAULTS +
 * @ConfigurationProperties + Bootstrap @EnableConfigurationProperties）。用 plain class（非 record）
 * 承载默认值 —— record 构造器绑定在 yml 未设时布尔落 false，会静默关掉 fork 门（与"默认开启"相悖）。
 *
 * <p><b>字段语义</b>（CC original 标注）:
 * <ul>
 *   <li>{@code featureOn} - 主开关 · CC original: feature('FORK_SUBAGENT')（forkSubagent.ts:33）</li>
 *   <li>{@code coordinatorMode} - coordinator 模式 · CC original: isCoordinatorMode()（forkSubagent.ts:34）</li>
 *   <li>{@code nonInteractive} - 非交互式 · CC original: getIsNonInteractiveSession()（forkSubagent.ts:35）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "nexusai.fork")
public class ForkSubagentConfig {

    /** 默认配置：featureOn=true, coordinatorMode=false, nonInteractive=false（对齐硬编码基线）。 */
    public static final ForkSubagentConfig DEFAULTS = new ForkSubagentConfig();

    private static volatile ForkSubagentConfig current = DEFAULTS;

    /** 主开关 · 默认 true（CC feature('FORK_SUBAGENT') 发行版启用，forkSubagent.ts:33）。 */
    private boolean featureOn = true;

    /** coordinator 模式 · 默认 false（CC isCoordinatorMode()，forkSubagent.ts:34；与 fork 互斥）。 */
    private boolean coordinatorMode = false;

    /** 非交互式会话 · 默认 false（CC getIsNonInteractiveSession()，forkSubagent.ts:35）。 */
    private boolean nonInteractive = false;

    public ForkSubagentConfig() {}

    public ForkSubagentConfig(boolean featureOn, boolean coordinatorMode, boolean nonInteractive) {
        this.featureOn = featureOn;
        this.coordinatorMode = coordinatorMode;
        this.nonInteractive = nonInteractive;
    }

    /** 当前生效配置（默认 {@link #DEFAULTS}；Spring 启动后为 yml 绑定实例）。 */
    public static ForkSubagentConfig current() {
        return current;
    }

    /** 注册当前配置（null → 复位默认）· 供 Spring bootstrap 与测试钩子。 */
    public static void register(ForkSubagentConfig config) {
        current = config != null ? config : DEFAULTS;
    }

    public boolean isFeatureOn() {
        return featureOn;
    }

    public void setFeatureOn(boolean featureOn) {
        this.featureOn = featureOn;
    }

    public boolean isCoordinatorMode() {
        return coordinatorMode;
    }

    public void setCoordinatorMode(boolean coordinatorMode) {
        this.coordinatorMode = coordinatorMode;
    }

    public boolean isNonInteractive() {
        return nonInteractive;
    }

    public void setNonInteractive(boolean nonInteractive) {
        this.nonInteractive = nonInteractive;
    }
}
