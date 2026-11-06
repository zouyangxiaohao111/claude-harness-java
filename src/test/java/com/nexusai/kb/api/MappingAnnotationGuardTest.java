package com.nexusai.kb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 禁止「同一方法挂两个 mapping 注解」· 真实踩坑（2026-11-05 W12 真机）。
 *
 * <p><b>现象</b>：给 {@code SessionController#update} 同时标
 * {@code @PatchMapping("/{id}")} 和 {@code @PutMapping("/{id}")}，编译通过、启动无告警，
 * 但 {@code PUT /api/v1/sessions/{id}} 运行时返回
 * {@code HttpRequestMethodNotSupportedException: Request method 'PUT' is not supported}。
 *
 * <p><b>根因</b>：{@code @GetMapping}/{@code @PostMapping}/… 都派生自
 * {@code @RequestMapping}，而 {@code @RequestMapping} <b>不是 {@code @Repeatable}</b>
 * ⇒ 同一方法上的两个 mapping 注解，Spring 只认其中一个（另一个被静默丢弃）。
 *
 * <p><b>正确写法</b>：合并成一个注解，多方法用
 * {@code @RequestMapping(value = "/x", method = {PATCH, PUT})}；
 * 或者一个注解多路径 {@code @PostMapping({"/a", "/b"})}（这个合法）。
 */
@DisplayName("[W12] 禁止同一方法挂多个 mapping 注解")
class MappingAnnotationGuardTest {

    private static final Path ROOT = Path.of("src", "main", "java");

    private static final List<String> MAPPING_ANNOTATIONS = List.of(
        "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping");

    @Test
    @DisplayName("⭐ 全仓扫描：没有方法同时带两个不同的 mapping 注解")
    void noMethodHasTwoMappingAnnotations() throws IOException {
        List<String> offenders = new ArrayList<>();
        if (!Files.isDirectory(ROOT)) {
            return; // 非预期工作目录：不制造假红
        }
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                checkFile(f, offenders);
            }
        }
        assertThat(offenders)
            .as(" ⭐ 第二个注解会被 Spring 静默丢弃 ⇒ 运行时 405/500。请合成一个注解："
                + "@RequestMapping(value=..., method={...})")
            .isEmpty();
    }

    private void checkFile(Path file, List<String> offenders) throws IOException {
        // 反射拿不到「被丢弃的注解」——它还在 class 上，所以必须读源码文本：
        // 判据 = 方法声明前连续的注解块里出现 >= 2 个不同的 mapping 注解。
        List<String> lines = Files.readAllLines(file);
        List<String> pending = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("@") && MAPPING_ANNOTATIONS.stream().anyMatch(a -> t.startsWith("@" + a))) {
                String name = MAPPING_ANNOTATIONS.stream()
                    .filter(a -> t.startsWith("@" + a)).findFirst().orElse("?");
                pending.add(name);
                continue;
            }
            if (t.startsWith("@") || t.isEmpty() || t.startsWith("//") || t.startsWith("*")
                || t.startsWith("/*")) {
                continue; // 其它注解 / 注释 / 空行：不打断注解块
            }
            // 到达方法签名（或字段）⇒ 结算上一段注解块
            long distinct = pending.stream().distinct().count();
            if (distinct > 1) {
                offenders.add(file + ":" + (i + 1) + " 上一个方法挂了 " + pending);
            }
            pending.clear();
        }
    }

    @Test
    @DisplayName("⭐ SessionController#update 用单注解承载 PATCH + PUT")
    void sessionUpdateSupportsBothVerbs() {
        Method target = null;
        for (Method m : com.nexusai.apis.session.SessionController.class.getDeclaredMethods()) {
            if (m.getName().equals("update")) {
                target = m;
                break;
            }
        }
        assertThat(target).isNotNull();
        Annotation[] annotations = target.getAnnotations();
        List<String> names = new ArrayList<>();
        for (Annotation a : annotations) {
            names.add(a.annotationType().getSimpleName());
        }
        assertThat(names)
            .as("两个 mapping 注解同时存在时 Spring 只用一个 ⇒ 必须合并成 @RequestMapping")
            .isEqualTo(java.util.Collections.singletonList("RequestMapping"));

        org.springframework.web.bind.annotation.RequestMapping rm =
            target.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        assertThat(rm).isNotNull();
        assertThat(List.of(rm.method()))
            .as("必须同时声明 PATCH（本仓原生）与 PUT（源侧 WeKnora）")
            .contains(org.springframework.web.bind.annotation.RequestMethod.PATCH,
                      org.springframework.web.bind.annotation.RequestMethod.PUT);
    }
}
