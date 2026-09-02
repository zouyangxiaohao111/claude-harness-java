package com.nexusai.infra.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Configuration
@ConfigurationProperties(prefix = "nexusai")
public class NexusProperties {

    /**
     * 各厂商不同的推理字段名列表（按优先级排列，先匹配先生效）。
     * 对应 yml: nexusai.openai-reasoning-field
     */
    private Set<String> openaiReasoningField = new LinkedHashSet<>(Set.of(
        "reasoning_content",
        "reasoning",
        "thinking",
        "reasoning_split"
    ));

    /**
     * 显式暴露 getter：Lombok 1.18.38 + Java 25 在某些环境下未能为
     * {@code openaiReasoningField} 字段生成 getter，导致
     * OpenAiSdkProvider 编译失败。此处手写以绕过。
     */
    public Set<String> getOpenaiReasoningField() {
        return openaiReasoningField;
    }

    public void setOpenaiReasoningField(Set<String> fields) {
        this.openaiReasoningField = (fields == null)
            ? new LinkedHashSet<>()
            : new LinkedHashSet<>(fields);
    }

    /**
     * AES/GCM 加密配置。
     */
    private Encryption encryption = new Encryption();
    // ---- nested ----

    public static class Encryption {

        /**
         * AES/GCM 256-bit key (base64)。
         */
        private String key;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }
}
