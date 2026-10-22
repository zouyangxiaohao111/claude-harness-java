package com.nexusai;

import com.nexusai.application.agent.telemetry.ToolTelemetryProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
@EnableConfigurationProperties(ToolTelemetryProperties.class)
@MapperScan({
        "com.nexusai.repository.*.mapper"
//        "com.nexusai.repository.provider.mapper"
//        ,"com.nexusai.repository.skill.mapper"
//        ,"com.nexusai.repository.mcp.mapper"
//        ,"com.nexusai.repository.db.mapper"
//        ,"com.nexusai.repository.schedule.mapper"
//        ,"com.nexusai.repository.project.mapper"
//        ,"com.nexusai.repository.session.mapper"
//        ,"com.nexusai.repository.settings.mapper"
//        ,"com.nexusai.repository.command.mapper"
})
public class NexusAiApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(NexusAiApplication.class)
            .initializers(NexusAiApplication::ensureHomeDir)
            .run(args);
    }

    /**
     * 启动前确保 NexusAI 配置根目录存在（{@code {user.home}/.{spring.application.name}}）。
     *
     * <p>WHY 必须放在 {@code ApplicationContextInitializer}：SQLite 不会自动创建父目录，
     * 而 Flyway 在 DataSource 初始化阶段就要连库 —— 新机器首次启动（目录不存在）会直接
     * {@code SQLException: ... does not exist} 导致整个应用起不来。该初始化器在上下文
     * refresh 之前执行，早于 DataSource/Flyway，可兜住首次启动。
     *
     * <p>已存在时 no-op；创建失败 fail-loud（不静默降级到别的目录）。
     */
    private static void ensureHomeDir(ConfigurableApplicationContext ctx) {
        String appName = ctx.getEnvironment().getProperty("spring.application.name", "nexusai");
        Path home = Paths.get(System.getProperty("user.home"), "." + appName);
        try {
            Files.createDirectories(home);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 NexusAI 配置根目录: " + home, e);
        }
    }
}