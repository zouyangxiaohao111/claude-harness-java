package com.nexusai.infra.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI / OpenAPI 元数据 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI nexusAiOpenAPI() {
        return new OpenAPI().info(new Info()
            .title("NexusAI Backend API")
            .description("LLM 客户端后端 — Provider/Model/Session/Chat 全套管理。访问 /swagger-ui.html 查看交互文档。")
            .version("0.1.0")
        );
    }
}