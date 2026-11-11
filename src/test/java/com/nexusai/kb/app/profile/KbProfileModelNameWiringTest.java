package com.nexusai.kb.app.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐⭐ 画像生成链的「模型 ID vs 模型名」接线断言 · 2026-11-11。
 *
 * <h2>为什么用源码级断言而不是 mock</h2>
 * 这条链要经过 Spring 容器、Mapper、LLM provider 三层，真跑起来要造一堆 mock，
 * 而这一处的错误形态恰恰是「参数传错」——不是分支算错。
 * 同款范式见 {@code KbQuestionModelNameWiringTest}。
 *
 * <h2>它钉住的是什么</h2>
 * {@code profile_config.model_id} / {@code summary_model_id} 存的是
 * <b>models.id</b>（如 {@code model-8869ce11}），而 provider API 只认
 * <b>models.name</b>（如 {@code deepseek-v4-pro}）。传错就 400：
 * <pre>
 * The supported API model names are deepseek-flash, deepseek-v4-pro,
 * but you passed model-8869ce11.
 * </pre>
 * ⇒ 必须用 {@code ResolvedModel.apiModelName()}。
 */
class KbProfileModelNameWiringTest {

    private static final Path SERVICE = Path.of("src", "main", "java", "com", "nexusai", "kb",
        "app", "profile", "KbProfileService.java");

    private static String source() throws IOException {
        assertThat(SERVICE).as("找不到 KbProfileService.java（按 backend/ 解析）").exists();
        return Files.readString(SERVICE, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("⭐⭐ 调模型必须传 apiModelName，⛔ 不能传模型 ID")
    void chatUsesApiModelName() throws IOException {
        String s = source();
        int i = s.indexOf("sideCallClient.chat(");
        assertThat(i).as("找不到 sideCallClient.chat 调用点").isGreaterThan(0);
        String call = s.substring(i, Math.min(i + 400, s.length()));
        assertThat(call)
            .as("chat() 第二参必须是 apiModelName；传模型 ID 会被对方 400"
                + "（实测 `but you passed model-8869ce11`）")
            .contains("apiModelName");
        assertThat(call)
            .as("⛔ 不能把模型 ID 直接当模型名发出去")
            .doesNotContain("provider, model,");
    }

    @Test
    @DisplayName("⭐ apiModelName 必须由 ResolvedModel 提供（并保留回退）")
    void apiModelNameComesFromResolvedModel() throws IOException {
        String s = source();
        assertThat(s).contains("resolved.apiModelName()");
        int i = s.indexOf("String apiModelName");
        assertThat(i).as("找不到 apiModelName 推导").isGreaterThan(0);
        String block = s.substring(i, Math.min(i + 400, s.length()));
        assertThat(block).as("必须有回退分支（解析不到真实名时退回原值，不能是 null）")
            .contains(": model");
    }

    @Test
    @DisplayName("⭐⭐ 聚合链必须先于模型调用（否则会退化成「按标题生成」）")
    void aggregateRunsBeforeModelCall() throws IOException {
        String s = source();
        int agg = s.indexOf("buildAggregate(kb)");
        int chat = s.indexOf("sideCallClient.chat(");
        assertThat(agg).as("找不到 buildAggregate 调用").isGreaterThan(0);
        assertThat(chat).as("找不到 chat 调用").isGreaterThan(0);
        assertThat(agg).as("⭐ 聚合必须发生在调模型之前（照源站 GenerateKnowledgeBaseProfile）")
            .isLessThan(chat);
    }

    @Test
    @DisplayName("⭐⭐ 聚合未变 ⇒ 不得调模型（指纹短路）")
    void unchangedAggregateSkipsModelCall() throws IOException {
        String s = source();
        assertThat(s).as("必须用 isReady(hash) 做指纹短路")
            .contains("existing.isReady(hash)");
        // 短路必须在 chat 之前 return
        int shortCircuit = s.indexOf("existing.isReady(hash)");
        int chat = s.indexOf("sideCallClient.chat(");
        assertThat(shortCircuit).as("⭐ 指纹短路必须发生在调模型之前")
            .isLessThan(chat);
    }

    @Test
    @DisplayName("⭐ 空聚合 ⇒ 不调模型（空库调模型必然编造）")
    void emptyAggregateSkipsModelCall() throws IOException {
        String s = source();
        int empty = s.indexOf("agg.isEmpty()");
        int chat = s.indexOf("sideCallClient.chat(");
        assertThat(empty).as("找不到空聚合短路").isGreaterThan(0);
        assertThat(empty).as("⭐ 空聚合短路必须发生在调模型之前").isLessThan(chat);
    }
}
