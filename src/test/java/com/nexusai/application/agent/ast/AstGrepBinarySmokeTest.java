package com.nexusai.application.agent.ast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 ast-grep 二进制的冒烟（资源缺失时整体跳过）。
 *
 * <p>进程层（{@link ProcessAstGrepCli}）没有可控假二进制，只能在这里覆盖：abort、超时、
 * 引号/中文模式经 ProcessBuilder 传递、lang 过滤、预览不写盘、apply 真写盘。
 */
@DisplayName("AstGrepBinarySmokeTest · 真实二进制端到端")
class AstGrepBinarySmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    @BeforeEach
    void requireBinary() {
        assumeTrue(AstGrepBinary.available(), "本平台未打包 ast-grep 二进制，跳过真实冒烟");
        try {
            AstGrepBinary.ensureBinary();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(workspace.resolve(name), content, StandardCharsets.UTF_8);
    }

    private AstGrepRunner.Request request(String pattern, String lang) {
        return new AstGrepRunner.Request(pattern, List.of(workspace.toString()), lang, workspace, 20_000L);
    }

    @Test
    @DisplayName("AstGrep：无 lang 命中 Java+JS；lang=java 只命中 Java；引号模式可传递")
    void grepsAcrossLanguages() throws Exception {
        write("A.java", "class A { void f(){ foo(1); foo(bar(2)); } }\n");
        write("b.js", "function f(){ foo(1); }\n");
        write("c.java", "class C { void g(){ System.out.println(\"hi\"); } }\n");
        AstGrepRunner runner = new AstGrepRunner();

        AstGrepRunner.Page all = runner.find(request("foo($A)", null), 0, 50, AbortController.NOOP);
        assertThat(all.matches()).hasSize(3);
        assertThat(all.matches()).extracting(AstMatch::language)
            .contains("Java", "JavaScript");
        assertThat(all.matches().get(0).singleMeta()).containsKey("A");
        assertThat(all.filesSearched()).isPositive();

        AstGrepRunner.Page java = runner.find(request("foo($A)", "java"), 0, 50, AbortController.NOOP);
        assertThat(java.matches()).hasSize(2);
        assertThat(java.matches()).allSatisfy(m -> assertThat(m.language()).isEqualTo("Java"));

        AstGrepRunner.Page quoted = runner.find(
            request("System.out.println(\"hi\")", "java"), 0, 50, AbortController.NOOP);
        assertThat(quoted.matches()).hasSize(1);
        assertThat(quoted.matches().get(0).text()).isEqualTo("System.out.println(\"hi\")");
    }

    @Test
    @DisplayName("AstEdit 工具：预览不改文件；apply 后 foo(1) → bar(1)")
    void editPreviewThenApply() throws Exception {
        Path f = workspace.resolve("a.js");
        write("a.js", "foo(1)\n");
        AstEditToolFixture fixture = new AstEditToolFixture(workspace);
        String ops = "{\"ops\":[{\"pat\":\"foo($A)\",\"out\":\"bar($A)\"}],\"paths\":[\"a.js\"]}";

        String preview = fixture.run(ops);
        assertThat(preview).startsWith("Staged as a proposal");
        assertThat(Files.readString(f)).isEqualTo("foo(1)\n");

        String applied = fixture.run(ops.replace("\"paths\"", "\"apply\":true,\"paths\""));
        assertThat(applied).startsWith("Applied 1 replacement(s) in 1 file(s).");
        assertThat(Files.readString(f)).isEqualTo("bar(1)\n");
    }

    @Test
    @DisplayName("abort：预先取消 → AstGrepException(ABORTED)")
    void aborts() {
        AbortController abort = new AbortController();
        abort.abort();
        assertThatThrownBy(() -> new AstGrepRunner().find(request("foo($A)", null), 0, 5, abort))
            .isInstanceOfSatisfying(AstGrepRunner.AstGrepException.class,
                e -> assertThat(e.kind()).isEqualTo(AstGrepRunner.AstGrepException.Kind.ABORTED));
    }

    @Test
    @DisplayName("超时：timeoutMs=1 → AstGrepException(TIMEOUT)")
    void timesOut() {
        AstGrepRunner.Request tight = new AstGrepRunner.Request(
            "foo($A)", List.of(workspace.toString()), null, workspace, 1L);
        assertThatThrownBy(() -> new AstGrepRunner().find(tight, 0, 5, AbortController.NOOP))
            .isInstanceOfSatisfying(AstGrepRunner.AstGrepException.class, e -> {
                assertThat(e.kind()).isEqualTo(AstGrepRunner.AstGrepException.Kind.TIMEOUT);
                assertThat(e.getMessage()).contains("timed out");
            });
    }

    /** 只在本测试用的最小工具夹具（生产装配由 Spring 注入依赖）。 */
    private static final class AstEditToolFixture {
        private final com.nexusai.application.agent.tool.impl.AstEditTool tool;

        AstEditToolFixture(Path workspace) {
            this.tool = new com.nexusai.application.agent.tool.impl.AstEditTool(
                new PathGuard(workspace), new AstGrepRunner(), new AstEditStaging());
        }

        String run(String json) {
            try {
                var result = tool.execute(new ToolUseBlock("toolu_smoke", "AstEdit", JSON.readTree(json)),
                    ToolUseContext.of(UUID.randomUUID().toString(), "s-smoke"));
                if (result.data() instanceof java.util.Map<?, ?> m && m.get("summary") != null) {
                    return String.valueOf(m.get("summary"));
                }
                return String.valueOf(result.data());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
