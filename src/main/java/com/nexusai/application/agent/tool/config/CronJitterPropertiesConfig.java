package com.nexusai.application.agent.tool.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cron 抖动配置接线 · 仿 {@code CronEnabledGatesConfig}
 * （@EnableConfigurationProperties + record @ConfigurationProperties）：
 * 注册 {@link CronJitterProperties} bean，让 application.yml
 * {@code nexusai.cron.jitter.*} 被类型化绑定（默认对齐 CC DEFAULT_CRON_JITTER_CONFIG）。
 */
@Configuration
@EnableConfigurationProperties(CronJitterProperties.class)
public class CronJitterPropertiesConfig {

    private static final Logger log = LoggerFactory.getLogger(CronJitterPropertiesConfig.class);

    public CronJitterPropertiesConfig(CronJitterProperties props) {
        // 启动校验 floor<=max（对齐 CC cronJitterConfig.ts:52 refine）：@Min/@Max 只拦单字段越界，
        // 跨字段区间反置须在此 + toConfig() 双点暴露（refine 失败 CC 整对象回退 DEFAULT :74）。
        if (props.oneShotFloorMs() > props.oneShotMaxMs()) {
            log.error("[CronJitterPropertiesConfig] 启动校验失败：oneShotFloorMs={} > oneShotMaxMs={}，"
                    + "jitter 区间反置（对齐 CC cronJitterConfig.ts:52 refine → :74 整对象回退 DEFAULT），"
                    + "消费端 toConfig() 将回退默认值 oneShotMaxMs=90000/oneShotFloorMs=0，"
                    + "请修正 nexusai.cron.jitter.* 配置",
                props.oneShotFloorMs(), props.oneShotMaxMs());
        }
        if (log.isDebugEnabled()) {
            log.debug("[CronJitterPropertiesConfig] 加载 cron jitter 配置：recurringFrac={} "
                    + "recurringCapMs={} oneShotMaxMs={} oneShotFloorMs={} oneShotMinuteMod={} "
                    + "recurringMaxAgeMs={}（对齐 CC DEFAULT_CRON_JITTER_CONFIG cronTasks.ts:348-355）",
                props.recurringFrac(), props.recurringCapMs(), props.oneShotMaxMs(),
                props.oneShotFloorMs(), props.oneShotMinuteMod(), props.recurringMaxAgeMs());
        }
    }
}
