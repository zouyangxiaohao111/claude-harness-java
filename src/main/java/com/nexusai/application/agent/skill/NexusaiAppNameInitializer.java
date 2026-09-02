package com.nexusai.application.agent.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * NexusAI 自有根 appName 初始化 · 决策 D1（nexusai 复刻版 .claude 改造）。
 *
 * <p>{@code spring.application.name} → {@link NexusaiPaths#setAppName}。application.yml 当前
 * {@code spring.application.name: nexusai}（G3 拍板），未指定时默认 {@code nexusai}。
 * 自有根 = {@code ~/.{appName}/}（见 {@link NexusaiPaths#getAppConfigHomeDir()}）。
 *
 * <p>@PostConstruct 时序注记：本初始化在 Spring bean 构造完成后执行；下游消费方
 * （UserSettingsLoader / AutoModeGate / FileConfigStorage）应<b>惰性</b>解析路径（读写时
 * 调用 {@link NexusaiPaths#getAppConfigHomeDir()}），避免在 bean 构造期冻结默认 appName
 * 导致路径错位。
 */
@Component
public class NexusaiAppNameInitializer {

    private static final Logger log = LoggerFactory.getLogger(NexusaiAppNameInitializer.class);

    private final String appName;

    /**
     * Spring 注入构造器。
     *
     * @param appName {@code spring.application.name}（未指定默认 {@code nexusai}）
     */
    public NexusaiAppNameInitializer(
            @Value("${spring.application.name:nexusai}") String appName) {
        this.appName = appName;
    }

    /**
     * 写入 NexusaiPaths 静态 appName（@PostConstruct：bean 构造完成后执行一次）。
     */
    @PostConstruct
    void initialize() {
        NexusaiPaths.setAppName(appName);
        log.info("Nexusai 自有根 appName 初始化完成: spring.application.name={} → 配置根={}",
            appName, NexusaiPaths.getAppConfigHomeDir());
    }
}
