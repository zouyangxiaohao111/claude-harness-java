package com.nexusai.kb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长期记忆端点契约 · 批次 W12（W9 只落了服务层，端点在这一批才补）。
 *
 * <p><b>WHY 用反射而不是 HTTP</b>：这里要锁的是「路径与动词有没有缺」，
 * 起 Spring 会迁移真库（铁律）。反射读注解足以判断，且不会碰任何数据。
 *
 * <p><b>要防的错</b>：漏一条端点 ⇒ 前端调用 404，但没人会发现（因为没人调）。
 * 源侧 16 条，本仓必须逐条对上。
 */
@DisplayName("[W12] 长期记忆端点契约")
class KbMemoryControllerContractTest {

    /** 源侧 routes_memory.go:21-38 的 16 条（verb + 路径）。 */
    private static final Set<String> EXPECTED = Set.of(
        "GET /api/v1/kb/memory/settings",
        "PUT /api/v1/kb/memory/settings",
        "GET /api/v1/kb/memory/items",
        "POST /api/v1/kb/memory/items",
        "DELETE /api/v1/kb/memory/items",
        "PUT /api/v1/kb/memory/items/{id}",
        "DELETE /api/v1/kb/memory/items/{id}",
        "POST /api/v1/kb/memory/items/{id}/confirm",
        "POST /api/v1/kb/memory/items/{id}/reject",
        "GET /api/v1/kb/memory/topics",
        "DELETE /api/v1/kb/memory/topics/{id}",
        "POST /api/v1/kb/memory/topics/{id}/promote",
        "GET /api/v1/kb/memory/documents",
        "DELETE /api/v1/kb/memory/documents/{id}",
        "GET /api/v1/kb/memory/export",
        "POST /api/v1/kb/memory/consolidate"
    );

    @Test
    @DisplayName("⭐ 16 条端点逐条存在（路径 + 动词都对）")
    void allSixteenEndpointsPresent() {
        Set<String> actual = collectRoutes();
        assertThat(actual)
            .as("源侧 routes_memory.go 的 16 条必须全部落地；缺一条前端就 404")
            .containsAll(EXPECTED);
    }

    @Test
    @DisplayName("⭐ consolidate 如实说明「没做语义合并」")
    void consolidateIsHonest() throws Exception {
        Method m = KbMemoryController.class.getDeclaredMethod("consolidate");
        assertThat(m).isNotNull();
        // 源码级判据：注释/实现里必须出现「未接入」或「不做」的如实说明
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src", "main", "java", "com", "nexusai", "kb", "api", "KbMemoryController.java"));
        assertThat(src)
            .as("⛔ 不能谎报 merged 数量")
            .contains("本仓未接入记忆合并模型");
    }

    @Test
    @DisplayName("⭐ settings 的 enabled 是「不传即不改」，不是「不传即关」")
    void settingsPartialUpdate() throws Exception {
        Class<?> req = Class.forName("com.nexusai.kb.api.KbMemoryController$SettingsRequest");
        java.util.List<Object> types = new java.util.ArrayList<>();
        for (java.lang.reflect.RecordComponent rc : req.getRecordComponents()) {
            types.add(rc.getType());
        }
        assertThat(types)
            .as("必须是 Boolean（可空）—— int/boolean 会把「没传」当 false")
            .containsExactly(Boolean.class);
    }

    private static Set<String> collectRoutes() {
        Set<String> out = new java.util.LinkedHashSet<>();
        // 类级 @RequestMapping("/api/v1/kb/memory") 不在方法注解里 ⇒ 这里补回前缀
        String base = "/api/v1/kb/memory";
        for (Method m : KbMemoryController.class.getDeclaredMethods()) {
            for (String verb : List.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping")) {
                var a = m.getAnnotation(annotationClass(verb));
                if (a == null) {
                    continue;
                }
                String path = readPath(a);
                if (path == null) {
                    continue;
                }
                out.add(verb.substring(0, verb.indexOf("Mapping")).toUpperCase()
                    + " " + (base + path));
            }
        }
        return out;
    }

    private static String readPath(java.lang.annotation.Annotation a) {
        try {
            Object v = a.annotationType().getMethod("value").invoke(a);
            if (v instanceof String[] arr && arr.length > 0) {
                return arr[0];
            }
        } catch (ReflectiveOperationException ignored) {
            // 注解无 value 元素 ⇒ 落在类级前缀
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends java.lang.annotation.Annotation> annotationClass(String simple) {
        return switch (simple) {
            case "GetMapping" -> org.springframework.web.bind.annotation.GetMapping.class;
            case "PostMapping" -> org.springframework.web.bind.annotation.PostMapping.class;
            case "PutMapping" -> org.springframework.web.bind.annotation.PutMapping.class;
            case "DeleteMapping" -> org.springframework.web.bind.annotation.DeleteMapping.class;
            default -> throw new IllegalArgumentException(simple);
        };
    }
}
