package com.nexusai.application.agent.cli;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * FIX-R11-3: NativeInstaller 依赖 Bean 注册.
 *
 * <p>NativeInstaller 通过 {@code @Autowired} 注入 PidLock / Download / PackageManagers,
 *    但这三个类本身不是 {@code @Component}. Spring 上下文加载失败 → BashToolBackgroundFieldTest
 *    ({@code @SpringBootTest}) 触发 UnsatisfiedDependencyException.
 * <p>修补: 在此 @Configuration 显式注册 3 个 bean + PidLock 的 locks 目录.
 */
@Configuration
public class NativeInstallerConfig {

    /**
     * PidLock 默认 locksDir = ${java.io.tmpdir}/nexusai-installer-locks.
     * <p>FIX-R11-3: 测试环境 (@TempDir) 隔离 — 生产路径仍走 XDG_DATA_HOME/versions.
     */
    @Bean
    public PidLock pidLock() {
        Path locksDir = Path.of(System.getProperty("java.io.tmpdir"), "nexusai-installer-locks");
        return new PidLock(locksDir);
    }

    @Bean
    public Download download() {
        return new Download();
    }

    @Bean
    public PackageManagers packageManagers() {
        return new PackageManagers();
    }
}