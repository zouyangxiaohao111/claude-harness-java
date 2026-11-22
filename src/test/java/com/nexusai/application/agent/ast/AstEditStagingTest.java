package com.nexusai.application.agent.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AstEditStaging 的签名、幂等消费与 TTL。
 *
 * <p>WHY：落地前必须证明「这次 apply 与上次预览是同一个请求、且文件没变」：
 * <ul>
 *   <li>签名与 {@code paths} 书写顺序无关（同一个请求换个顺序不该要求重新预览）；</li>
 *   <li>{@code ops} 顺序属于请求语义（per-op diff 顺序不同），签名必须区分；</li>
 *   <li>{@code take} 存在即移除 —— 同一签名不能重放；过期记录不可用。</li>
 * </ul>
 */
@DisplayName("AstEditStagingTest · 签名/幂等/TTL")
class AstEditStagingTest {

    @TempDir
    Path workspace;

    private static Map<String, String> op(String pat, String out) {
        return Map.of("pat", pat, "out", out);
    }

    @Test
    @DisplayName("签名：paths 顺序无关，ops 顺序/内容与 lang 有关")
    void signatureShape() {
        String a = AstEditStaging.signature(List.of(op("foo($A)", "bar($A)")), null,
            List.of("/w/a.js", "/w/b.js"));
        String b = AstEditStaging.signature(List.of(op("foo($A)", "bar($A)")), null,
            List.of("/w/b.js", "/w/a.js"));
        assertThat(a).isEqualTo(b);

        String reordered = AstEditStaging.signature(List.of(
            op("foo($A)", "bar($A)"), op("baz($A)", "qux($A)")), null, List.of("/w/a.js"));
        String reordered2 = AstEditStaging.signature(List.of(
            op("baz($A)", "qux($A)"), op("foo($A)", "bar($A)")), null, List.of("/w/a.js"));
        assertThat(reordered).isNotEqualTo(reordered2);

        String withLang = AstEditStaging.signature(List.of(op("foo($A)", "bar($A)")), "java",
            List.of("/w/a.js"));
        assertThat(withLang).isNotEqualTo(a);
    }

    @Test
    @DisplayName("stage/take：take 一次后失效（防重放），快照记录指纹与变更")
    void stageThenTakeOnce() throws Exception {
        Path file = workspace.resolve("a.js");
        Files.writeString(file, "foo(1)", StandardCharsets.UTF_8);
        AstEditStaging staging = new AstEditStaging();
        AstEditPlan plan = new AstEditPlan(file.toAbsolutePath().normalize().toString(), List.of(
            new AstEditPlan.Change(0, 6, "foo(1)", "bar(1)", 1, 1, 1, 7)));
        String sig = AstEditStaging.signature(List.of(op("foo($A)", "bar($A)")), null,
            List.of(file.toString()));

        staging.stage("s1", sig, List.of(plan));
        AstEditStaging.Staged staged = staging.take("s1", sig);
        assertThat(staged).isNotNull();
        assertThat(staged.sha256ByFile()).containsKey(plan.file());
        assertThat(staged.changesByFile().get(plan.file())).hasSize(1);
        assertThat(staging.take("s1", sig)).as("第二次 take 必须为空（防重放）").isNull();
        assertThat(staging.take("s2", sig)).as("不同会话不可取").isNull();
    }

    @Test
    @DisplayName("TTL：超过 30 分钟的暂存不可取")
    void expiredIsNotUsable() throws Exception {
        AstEditStaging staging = new AstEditStaging();
        String sig = AstEditStaging.signature(List.of(op("foo($A)", "bar($A)")), null, List.of("/w/a.js"));
        staging.stage("s1", sig, List.of());

        // 直接把暂存时刻改老（生产没有时间源注入口，测试用反射构造过期记录）
        Field field = AstEditStaging.class.getDeclaredField("staged");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, AstEditStaging.Staged> map =
            (ConcurrentHashMap<String, AstEditStaging.Staged>) field.get(staging);
        assertThat(map).hasSize(1);
        String key = map.keySet().iterator().next();
        AstEditStaging.Staged old = map.get(key);
        map.put(key, new AstEditStaging.Staged(old.sha256ByFile(), old.changesByFile(),
            System.currentTimeMillis() - AstEditStaging.TTL_MS - 1_000L));

        assertThat(staging.take("s1", sig)).isNull();
    }
}
