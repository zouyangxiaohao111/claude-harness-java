package com.nexusai.application.agent.ast;

import com.nexusai.application.agent.tool.AbortController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AstGrepRunner 的解析/排序/分页契约（fake CLI，不需要真实二进制）。
 *
 * <p>WHY：这些行为决定了模型看到的文本与后续 AstEdit 的字节区间，错了会静默漏改/错改：
 * <ul>
 *   <li>行列必须 1 基（CLI 0 基）——差 1 会让定位漂移；</li>
 *   <li>退出码 1 = 无匹配不是错误（ast-grep 语义），否则「没有结果」会变成报错；</li>
 *   <li>分页 totalMatches 必须独立于 skip/limit 计数，limitReached 才能提示「还有更多」；</li>
 *   <li>结果必须按规范化路径排序（Windows 混合分隔符会让同目录文件顺序抖动）；</li>
 *   <li>metaVariables.multi 不进 singleMeta（匹配正文已含，重复展开会让文本膨胀）。</li>
 * </ul>
 */
@DisplayName("AstGrepRunnerTest · JSONL 解析 / 分页 / 排序 / 参数原样")
class AstGrepRunnerTest {

    @TempDir
    Path workspace;

    /** fake CLI：记录参数并返回预置结果。 */
    private static final class FakeCli implements AstGrepCli {
        private AstGrepCli.Result result;
        private AstCliException failure;
        private List<String> lastArgs;
        private Path lastWorkdir;
        private long lastTimeout;
        private AbortController lastAbort;

        FakeCli(AstGrepCli.Result result) {
            this.result = result;
        }

        @Override
        public Result run(List<String> args, Path workdir, AbortController abort, long timeoutMs)
                throws AstCliException {
            this.lastArgs = args;
            this.lastWorkdir = workdir;
            this.lastTimeout = timeoutMs;
            this.lastAbort = abort;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static AstGrepCli.Result ok(String... lines) {
        return new AstGrepCli.Result(0, List.of(lines), List.of(
            "sg: summary|project: isProject=false",
            "sg: summary|file: scannedFileCount=7,skippedFileCount=0"), false);
    }

    private static String match(String file, int startByte, int endByte, int line, int column,
                                String text, String metaName, String metaText) {
        String start = "{\"line\":" + line + ",\"column\":" + column + "}";
        String end = "{\"line\":" + line + ",\"column\":" + (column + text.length()) + "}";
        String single = metaName == null ? "{}"
            : "{\"" + metaName + "\":{\"text\":\"" + metaText + "\"}}";
        return "{\"text\":\"" + text + "\",\"range\":{\"byteOffset\":{\"start\":" + startByte
            + ",\"end\":" + endByte + "},\"start\":" + start + ",\"end\":" + end + "},"
            + "\"file\":\"" + file + "\",\"language\":\"JavaScript\","
            + "\"metaVariables\":{\"single\":" + single + ",\"multi\":{},\"transformed\":{}}}";
    }

    private AstGrepRunner.Request request(String pattern) {
        return new AstGrepRunner.Request(pattern, List.of(workspace.toString()), null, workspace, 20_000L);
    }

    @Test
    @DisplayName("JSONL → AstMatch：行列 1 基、meta 只取 single、replacement 缺省为 null")
    void parsesJsonLines() {
        String line = match("a.js", 10, 16, 4, 6, "foo(1)", "A", "1");
        FakeCli cli = new FakeCli(ok(line, "not-json"));
        AstGrepRunner runner = new AstGrepRunner(cli);

        AstGrepRunner.Page page = runner.find(request("foo($A)"), 0, 10, AbortController.NOOP);

        assertThat(page.matches()).hasSize(1);
        AstMatch m = page.matches().get(0);
        assertThat(m.line()).isEqualTo(5);
        assertThat(m.column()).isEqualTo(7);
        assertThat(m.startByte()).isEqualTo(10);
        assertThat(m.endByte()).isEqualTo(16);
        assertThat(m.singleMeta()).containsEntry("A", "1");
        assertThat(m.replacement()).isNull();
        assertThat(page.filesSearched()).isEqualTo(7);
        assertThat(page.warnings()).anyMatch(w -> w.contains("unparsable"));
    }

    @Test
    @DisplayName("退出码 1（无匹配）不报错；退出码 2 带 stderr 报 FAILED")
    void exitCodes() {
        FakeCli empty = new FakeCli(new AstGrepCli.Result(1,
            List.of(), List.of("sg: summary|file: scannedFileCount=3,skippedFileCount=0"), false));
        AstGrepRunner runner = new AstGrepRunner(empty);
        AstGrepRunner.Page page = runner.find(request("zzz($A)"), 0, 10, AbortController.NOOP);
        assertThat(page.matches()).isEmpty();
        assertThat(page.totalMatches()).isZero();
        assertThat(page.filesSearched()).isEqualTo(3);

        FakeCli failing = new FakeCli(new AstGrepCli.Result(2, List.of(), List.of("boom", "trace"), false));
        assertThatThrownBy(() -> new AstGrepRunner(failing)
            .find(request("foo("), 0, 10, AbortController.NOOP))
            .isInstanceOf(AstGrepRunner.AstGrepException.class)
            .hasMessageContaining("ast-grep failed: boom trace")
            .extracting(e -> ((AstGrepRunner.AstGrepException) e).kind())
            .isEqualTo(AstGrepRunner.AstGrepException.Kind.FAILED);
    }

    @Test
    @DisplayName("分页：skip=1 limit=2 返回第 2/3 条，totalMatches 与 limitReached 独立计数")
    void paginates() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add(match("a.js", i * 10, i * 10 + 6, i, 0, "foo(" + i + ")", "A", String.valueOf(i)));
        }
        FakeCli cli = new FakeCli(ok(lines.toArray(String[]::new)));
        AstGrepRunner runner = new AstGrepRunner(cli);

        AstGrepRunner.Page page = runner.find(request("foo($A)"), 1, 2, AbortController.NOOP);

        assertThat(page.matches()).hasSize(2);
        assertThat(page.matches().get(0).text()).isEqualTo("foo(1)");
        assertThat(page.matches().get(1).text()).isEqualTo("foo(2)");
        assertThat(page.totalMatches()).isEqualTo(30);
        assertThat(page.limitReached()).isTrue();
    }

    @Test
    @DisplayName("乱序输入按 (规范化路径, startByte) 稳定排序")
    void sortsByPathThenOffset() {
        FakeCli cli = new FakeCli(ok(
            match("b.js", 5, 11, 0, 5, "foo(2)", null, null),
            match("a.js", 30, 36, 2, 0, "foo(3)", null, null),
            match("a.js", 10, 16, 1, 0, "foo(1)", null, null)));
        AstGrepRunner runner = new AstGrepRunner(cli);

        AstGrepRunner.Page page = runner.find(request("foo($A)"), 0, 10, AbortController.NOOP);

        assertThat(page.matches()).extracting(AstMatch::text)
            .containsExactly("foo(1)", "foo(3)", "foo(2)");
    }

    @Test
    @DisplayName("参数原样传给 CLI：引号/换行/中文都不改写；-r 才带 rewrite")
    void passesPatternVerbatim() {
        String pattern = "System.out.println(\"hi\")\n中文";
        FakeCli cli = new FakeCli(ok());
        new AstGrepRunner(cli).find(new AstGrepRunner.Request(pattern,
            List.of(workspace.toString()), "java", workspace, 1_234L), 0, 5, AbortController.NOOP);

        assertThat(cli.lastArgs).contains(pattern);
        assertThat(cli.lastArgs).contains("--json=stream");
        assertThat(cli.lastArgs).containsSubsequence("--no-ignore", "hidden");
        assertThat(cli.lastArgs).containsSubsequence("-l", "java");
        assertThat(cli.lastArgs).doesNotContain("-r");
        assertThat(cli.lastWorkdir).isEqualTo(workspace);
        assertThat(cli.lastTimeout).isEqualTo(1_234L);
    }

    @Test
    @DisplayName("findAll：-r 传入且空替换保留为 \"\"；超过 cap 置 exceededCap")
    void findAllAndCap() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String line = match("a.js", i * 10, i * 10 + 6, i, 0, "foo(" + i + ")", "A", String.valueOf(i));
            line = line.replaceFirst("}\\s*$", ",\"replacement\":\"\"}");
            lines.add(line);
        }
        FakeCli cli = new FakeCli(ok(lines.toArray(String[]::new)));
        AstGrepRunner runner = new AstGrepRunner(cli);

        AstGrepRunner.All all = runner.findAll(request("foo($A)"), 10, "", AbortController.NOOP);
        assertThat(cli.lastArgs).containsSubsequence("-r", "");
        assertThat(all.matches()).hasSize(5);
        assertThat(all.matches()).allSatisfy(m -> assertThat(m.replacement()).isEmpty());
        assertThat(all.exceededCap()).isFalse();

        AstGrepRunner.All capped = runner.findAll(request("foo($A)"), 3, "bar($A)", AbortController.NOOP);
        assertThat(capped.matches()).hasSize(3);
        assertThat(capped.exceededCap()).isTrue();
    }

    @Test
    @DisplayName("outputTruncated 时忽略非 0 退出码（进程是被本端 kill 的）")
    void truncatedIgnoresExitCode() {
        FakeCli cli = new FakeCli(new AstGrepCli.Result(-1,
            List.of(match("a.js", 0, 6, 0, 0, "foo(1)", null, null)),
            List.of("sg: summary|file: scannedFileCount=1,skippedFileCount=0"), true));
        AstGrepRunner.Page page = new AstGrepRunner(cli).find(request("foo($A)"), 0, 10, AbortController.NOOP);
        assertThat(page.matches()).hasSize(1);
        assertThat(page.limitReached()).isTrue();
    }

    @Test
    @DisplayName("CLI 异常类别映射：ABORTED/TIMEOUT/SPAWN 的文案与 kind")
    void mapsCliFailures() {
        FakeCli cli = new FakeCli(ok());
        cli.failure = new AstGrepCli.AstCliException(AstGrepCli.AstCliException.Kind.TIMEOUT,
            "ast-grep timed out after 5 ms", null);
        assertThatThrownBy(() -> new AstGrepRunner(cli).find(request("foo($A)"), 0, 1, AbortController.NOOP))
            .isInstanceOfSatisfying(AstGrepRunner.AstGrepException.class,
                e -> assertThat(e.kind()).isEqualTo(AstGrepRunner.AstGrepException.Kind.TIMEOUT));

        cli.failure = new AstGrepCli.AstCliException(AstGrepCli.AstCliException.Kind.ABORTED,
            "AstGrep aborted by user", null);
        assertThatThrownBy(() -> new AstGrepRunner(cli).find(request("foo($A)"), 0, 1, AbortController.NOOP))
            .hasMessage("AstGrep aborted by user");
    }
}
