package com.nexusai.application.agent.permission.classifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session M3.4 · transcriptClassifierEnabled 默认值=true + application.yml 文档化测试 · 对齐 CC
 * Open-ClaudeCode/src/services/tools/toolExecution.ts:1075-1101.
 *
 * <p><b>WHY (意图验证)</b>: CC 端 {@code feature('TRANSCRIPT_CLASSIFIER')} 触发 retry hook
 * 路径, Java 端需要默认开启才能让 retry hook 链路生效. 该测试通过 classpath 读
 * application.yml 验证默认值已经从 false 改为 true (M3.4 决策).
 */
class TranscriptClassifierEnabledDefaultTest {

    // ─────────── 1. yml 默认值 = true ───────────

    @Test
    @DisplayName("M3.4-1 application.yml transcriptClassifierEnabled 默认 true · 对齐 CC toolExecution.ts:1076")
    void transcriptClassifierEnabledDefaultTrue() throws IOException {
        // 通过 classpath 读, 避免 maven 多模块 / IDE 不同 cwd 影响
        String body;
        try (InputStream in = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("application.yml")) {
            if (in == null) {
                throw new AssertionError("application.yml not found on classpath");
            }
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // WHY 整段扫描: transcript.enabled 是嵌套 yaml 键 (classifier.transcript.enabled),
        //   单行 l.contains("transcript") && l.contains("enabled") 找不齐 (父键与子键分行).
        //   改用整段 substring 匹配: 必须存在 `transcript:\\n      enabled: true` 块.
        assertThat(body)
            .as("application.yml must contain transcript classifier enabled: true block")
            .containsPattern("transcript:\\s*\\n\\s+enabled:\\s*true");

        // 验证没有遗留 "transcript...enabled: false" 反向默认
        assertThat(body)
            .as("transcriptClassifierEnabled must NOT contain ': false' default")
            .doesNotContain("transcript:\n      enabled: false");
    }
}
