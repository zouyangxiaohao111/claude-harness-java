package com.nexusai.kb.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbConfigSecrets} 测试 · 批次 W8。
 *
 * <p><b>⭐ 为什么这套要逐条测</b>：脱敏有两类静默故障 ——
 * ①漏脱敏 ⇒ 密码明文出站（安全）；
 * ②过度脱敏 ⇒ 用户只改别的字段再保存，真密码被六个星号覆盖（数据损毁，且不报错）。
 * 两类都只能靠断言抓。
 */
class KbConfigSecretsTest {

    @Nested
    @DisplayName("mask：只脱敏非空敏感字段")
    class Mask {

        @Test
        @DisplayName("有值的敏感字段被替换为占位符")
        void masksNonEmptySecrets() {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("addr", "http://localhost:9200");
            in.put("password", "s3cret");

            Map<String, Object> out = KbConfigSecrets.mask(in, List.of("password"));

            assertThat(out.get("password")).isEqualTo(KbConfigSecrets.REDACTED);
            assertThat(out.get("addr")).isEqualTo("http://localhost:9200");
        }

        @Test
        @DisplayName("空敏感字段保持空 —— 界面要能区分「没配」与「配了」")
        void leavesEmptySecretEmpty() {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("password", "");

            Map<String, Object> out = KbConfigSecrets.mask(in, List.of("password"));

            assertThat(out.get("password")).isEqualTo("");
        }

        @Test
        @DisplayName("null 敏感字段保持 null")
        void leavesNullSecretNull() {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("password", null);

            Map<String, Object> out = KbConfigSecrets.mask(in, List.of("password"));

            assertThat(out).containsEntry("password", null);
        }

        @Test
        @DisplayName("不改入参")
        void doesNotMutateInput() {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("password", "s3cret");

            KbConfigSecrets.mask(in, List.of("password"));

            assertThat(in.get("password")).isEqualTo("s3cret");
        }

        @Test
        @DisplayName("非敏感字段一个都不动")
        void ignoresNonSensitiveFields() {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("addr", "x");
            in.put("username", "elastic");

            Map<String, Object> out = KbConfigSecrets.mask(in, List.of("password"));

            assertThat(out).isEqualTo(in);
        }
    }

    @Nested
    @DisplayName("preserveRedacted：回传占位符时保留旧值")
    class PreserveRedacted {

        @Test
        @DisplayName("⭐ 提交占位符 + 库里旧值 ⇒ 用旧值（否则真密码被六个星号覆盖）")
        void keepsExistingValueWhenPlaceholderSubmitted() {
            Map<String, Object> incoming = new LinkedHashMap<>();
            incoming.put("addr", "http://new:9200");
            incoming.put("password", KbConfigSecrets.REDACTED);
            Map<String, Object> existing = new LinkedHashMap<>();
            existing.put("password", "real-secret");

            Map<String, Object> out = KbConfigSecrets.preserveRedacted(
                incoming, existing, List.of("password"));

            assertThat(out.get("password")).isEqualTo("real-secret");
            assertThat(out.get("addr")).isEqualTo("http://new:9200");
        }

        @Test
        @DisplayName("提交占位符但库里也没有旧值 ⇒ 删掉该键（不把 ****** 当密码存）")
        void dropsPlaceholderWhenNoExistingValue() {
            Map<String, Object> incoming = new LinkedHashMap<>();
            incoming.put("password", KbConfigSecrets.REDACTED);

            Map<String, Object> out = KbConfigSecrets.preserveRedacted(
                incoming, new LinkedHashMap<>(), List.of("password"));

            assertThat(out).doesNotContainKey("password");
        }

        @Test
        @DisplayName("提交新密码 ⇒ 用新密码（不保留旧值）")
        void acceptsNewSecret() {
            Map<String, Object> incoming = new LinkedHashMap<>();
            incoming.put("password", "brand-new");
            Map<String, Object> existing = new LinkedHashMap<>();
            existing.put("password", "old");

            Map<String, Object> out = KbConfigSecrets.preserveRedacted(
                incoming, existing, List.of("password"));

            assertThat(out.get("password")).isEqualTo("brand-new");
        }

        @Test
        @DisplayName("existing 为 null 不炸")
        void handlesNullExisting() {
            Map<String, Object> incoming = new LinkedHashMap<>();
            incoming.put("password", KbConfigSecrets.REDACTED);

            Map<String, Object> out = KbConfigSecrets.preserveRedacted(
                incoming, null, List.of("password"));

            assertThat(out).doesNotContainKey("password");
        }
    }

    @Nested
    @DisplayName("parse / toJson 往返")
    class RoundTrip {

        @Test
        @DisplayName("JSON ↔ Map 往返不丢字段")
        void roundTrips() {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("addr", "http://localhost:9200");
            in.put("number_of_shards", 4);
            in.put("use_tls", true);

            Map<String, Object> back = KbConfigSecrets.parse(KbConfigSecrets.toJson(in));

            assertThat(back).containsEntry("addr", "http://localhost:9200")
                .containsEntry("number_of_shards", 4)
                .containsEntry("use_tls", true);
        }

        @Test
        @DisplayName("空串 / null / 非法 JSON 一律返回空 Map（读路径不炸）")
        void toleratesBadInput() {
            assertThat(KbConfigSecrets.parse(null)).isEmpty();
            assertThat(KbConfigSecrets.parse("")).isEmpty();
            assertThat(KbConfigSecrets.parse("not-json")).isEmpty();
            assertThat(KbConfigSecrets.parse("[1,2]")).isEmpty();  // 数组不是对象
        }

        @Test
        @DisplayName("toJson(null) 产出 {}")
        void nullBecomesEmptyObject() {
            assertThat(KbConfigSecrets.toJson(null)).isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("isJsonObject：写路径校验")
    class IsJsonObject {

        @Test
        @DisplayName("空值视为合法（用默认 {}）")
        void blankIsValid() {
            assertThat(KbConfigSecrets.isJsonObject(null)).isTrue();
            assertThat(KbConfigSecrets.isJsonObject("   ")).isTrue();
        }

        @Test
        @DisplayName("对象合法")
        void objectIsValid() {
            assertThat(KbConfigSecrets.isJsonObject("{\"a\":1}")).isTrue();
        }

        @Test
        @DisplayName("⭐ 数组 / 标量 / 非法都判否 —— 配置必须是对象")
        void rejectsNonObject() {
            assertThat(KbConfigSecrets.isJsonObject("[1,2]")).isFalse();
            assertThat(KbConfigSecrets.isJsonObject("\"str\"")).isFalse();
            assertThat(KbConfigSecrets.isJsonObject("{bad}")).isFalse();
        }
    }
}
