package com.nexusai;

import com.nexusai.application.agent.telemetry.ToolTelemetryProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

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
        SpringApplication.run(NexusAiApplication.class, args);
    }
}