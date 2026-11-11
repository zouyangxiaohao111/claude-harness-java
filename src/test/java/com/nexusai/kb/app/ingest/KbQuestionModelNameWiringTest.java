package com.nexusai.kb.app.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接线守卫 · 问题生成调用模型时**必须用真实模型名**，⛔ 不能把 models.id 发出去。
 *
 * <h2>真机事实（2026-11-10）</h2>
 * 用户在抽屉里点「辅助召回问题」发现「怎么没生成」，查日志：
 * <pre>
 * [问句] 模型调用失败 knowledge=... chunk=... 原因=HTTP 400:
 *   The supported API model names are deepseek-flash, deepseek-v4-pro,
 *   but you passed model-0823a98d
 * </pre>
 * 根因与摘要 / 图谱 / Wiki 三阶段**完全同源**：{@code question_generation_config.model_id}
 * （空则继承 {@code summary_model_id}）存的是 {@code models.id}，而
 * {@code LlmSideCallClient.chat} 第二参要的是 {@code models.name}。
 *
 * <p><b>为什么必须源码级断言</b>：这个调用点在私有方法里、需要 provider 与数据库，
 * 单元测试要么起 Spring（本仓禁 {@code @SpringBootTest}，会迁移用户真库）、
 * 要么造一堆 mock 才能走到。而这一处的错误形态是「参数传错」，不是「分支算错」——
 * 源码级断言正好钉住它，且一旦有人改回去就会红。
 */
class KbQuestionModelNameWiringTest {

    private static final Path SERVICE = Path.of("src", "main", "java", "com", "nexusai", "kb",
        "app", "ingest", "KbQuestionGenerationService.java");

    private static String source() throws IOException {
        assertThat(SERVICE).as("找不到 KbQuestionGenerationService.java（按 backend/ 解析）").exists();
        return Files.readString(SERVICE, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("⭐⭐ 调模型必须传 apiModelName，⛔ 不能传 modelId")
    void chatUsesApiModelName() throws IOException {
        String s = source();
        int i = s.indexOf("sideCallClient.chat(");
        assertThat(i).as("找不到 sideCallClient.chat 调用点").isGreaterThan(0);
        String call = s.substring(i, Math.min(i + 400, s.length()));
        assertThat(call)
            .as("chat() 第二参必须是 apiModelName；传 modelId 会被对方 400"
                + "（实测 `but you passed model-0823a98d`）")
            .contains("apiModelName");
        assertThat(call)
            .as("⛔ 不能把 modelId 直接当模型名发出去")
            .doesNotContain("provider, modelId,");
    }

    @Test
    @DisplayName("⭐ apiModelName 必须由 ResolvedModel 提供（并保留回退）")
    void apiModelNameComesFromResolvedModel() throws IOException {
        String s = source();
        assertThat(s).contains("resolved.apiModelName()");
        // 回退分支：解析不到真实名时退回原值，不能变成 null
        int i = s.indexOf("String apiModelName");
        assertThat(i).isGreaterThan(0);
        String block = s.substring(i, Math.min(i + 400, s.length()));
        assertThat(block).as("必须有回退到 modelId 的分支（否则解析失败时模型名为 null）")
            .contains(": modelId");
    }
}
