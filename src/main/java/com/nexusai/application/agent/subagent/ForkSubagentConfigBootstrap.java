package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.coordinator.CoordinatorMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Fork subagent 配置注册 · {@code @EnableConfigurationProperties} 把 application.yml
 * {@code nexusai.fork.*} 绑定为 {@link ForkSubagentConfig}，并在此构造器注册为静态 {@code current}
 * （供 {@code SubagentTool} 构造路径 / 测试读取），同时把三参同步到 {@link ForkSubagent} 运行时门槽
 * （对齐 CC {@code feature('FORK_SUBAGENT')} 进程级全局语义）。
 *
 * <p><b>WHY（用户拍板 RES-SP23-1，09-open-decisions.md §十一 SP23-1）</b>: CC 的 fork gate 由
 * {@code feature('FORK_SUBAGENT')} 全局读取（forkSubagent.ts:32-39），与 Spring bean 生命周期无关。
 * Java 生产 SubagentTool 为 {@code new} 构造 → 旧 @Value 注入不触发 → 门停默认。本 bootstrap 把 yml
 * 配置写入静态 current + ForkSubagent 运行时门槽，生产/测试经 {@code ForkSubagentConfig.current()}
 * 读到真实配置（feature-on=false → 门关）。
 *
 * <p><b>注册模式</b>: 对齐 {@code PromptCachingTtlConfigBootstrap}
 * （@Configuration + @EnableConfigurationProperties + 构造注册静态 current）。
 *
 * <p><b>[R-A12] coordinator 单一来源接线（WF-D-UN-3）</b>: 注入 {@link CoordinatorMode} bean 并注册为
 * {@link ForkSubagent#setCoordinatorModeSupplier}（env 真源），使静态 prompt 链
 * （SessionGuidanceSection → ForkSubagent.isForkSubagentEnabled）与 SubagentTool 内部 fork gate 的
 * coordinator 判定同源，消除 config 静态槽与动态 bean 双源分叉。
 */
@Configuration
@EnableConfigurationProperties(ForkSubagentConfig.class)
public class ForkSubagentConfigBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ForkSubagentConfigBootstrap.class);

    public ForkSubagentConfigBootstrap(ForkSubagentConfig config, CoordinatorMode coordinatorMode) {
        ForkSubagentConfig.register(config);
        // [R-A12] coordinator 单一来源：动态 CoordinatorMode bean（env 真源）→ ForkSubagent supplier。
        //   bean 为 @Component（Spring 必注入非 null）；测试/直构路径经 ForkSubagent 静态槽回退。
        ForkSubagent.setCoordinatorModeSupplier(() -> coordinatorMode.isCoordinatorMode());
        // 同步 ForkSubagent 运行时门槽（进程级全局，对齐 CC feature('FORK_SUBAGENT')；
        //   coordinator 槽位现仅作 supplier 未注入时的 fallback）
        ForkSubagent.syncRuntimeGate(
            config.isFeatureOn(), config.isCoordinatorMode(), config.isNonInteractive());
        if (log.isInfoEnabled()) {
            log.info("[ForkSubagentConfigBootstrap] 注册 nexusai.fork 配置并同步运行时门槽："
                    + "featureOn={}, coordinatorMode={}, nonInteractive={}；coordinator 判定源=CoordinatorMode bean"
                    + "（R-A12 单一来源，SP23-1 配置类驱动）",
                config.isFeatureOn(), config.isCoordinatorMode(), config.isNonInteractive());
        }
    }
}
