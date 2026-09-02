package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.AbortController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RipgrepRunnerTest · 真实 rg 引擎调用（A6 / G13⑥）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：本测试用<b>真实 rg 二进制</b>（打包自
 * CC {@code package/vendor/ripgrep/}，rg 14.1.1）复验 {@link RipgrepRunner} 的
 * CC 关键语义（ripgrep.ts:345-463）：
 * <ol>
 *   <li><b>二进制可运行</b> —— CC {@code getRipgrepConfig}（ripgrep.ts:31-65）按平台解析
 *       打包二进制；缺失/不可运行 → 测试 fail（不得"应该可用"代替）。</li>
 *   <li><b>code=1 空结果</b> —— CC :376-380：无匹配退出码 1 → 空列表（非错误）。</li>
 *   <li><b>content/-c/-l 输出透传</b> —— 输出为 rg 原生格式（绝对路径前缀 + line/count），
 *       GrepTool 端再相对化（GrepTool.ts:443-576）。</li>
 *   <li><b>glob/--type/-i 参数透传</b> —— 真实 rg 按参数过滤（GrepTool.ts:346-409）。</li>
 *   <li><b>abort 接线</b> —— 取消信号已触发 → 空结果（CC signal 语义）。</li>
 * </ol>
 */
@DisplayName("RipgrepRunnerTest · 真实 rg 引擎调用（A6）")
class RipgrepRunnerTest {

    private final RipgrepRunner runner = new RipgrepRunner();

    @TempDir
    Path workspace;

    private Path write(String name, String content) throws Exception {
        Path p = workspace.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    // ───────────────────────── 二进制打包/可运行 ─────────────────────────

    @Test
    @DisplayName("打包 rg 二进制从 classpath 解析且真实可运行（--version）")
    void bundledRgBinary_isResolvableAndRunnable() throws Exception {
        // WHY: CC getRipgrepConfig（ripgrep.ts:31-65）解析 vendor/ripgrep 二进制；Java 端
        //      打包进 classpath rg/{os}-{arch}/rg[.exe]，运行时可执行。若打包缺失或不可运行，
        //      GrepTool 搜索全部 fail —— 这是 A6 引入真实 rg 的成败点，必须真实运行验证。
        Path rg = RipgrepRunner.ensureRgBinary();
        assertThat(Files.isRegularFile(rg))
            .as("rg 二进制必须已从 classpath 提取到缓存").isTrue();
        assertThat(RipgrepRunner.class.getResource(RipgrepRunner.resourceName()))
            .as("classpath 资源 %s 必须存在（打包 6 平台二进制）", RipgrepRunner.resourceName())
            .isNotNull();

        Process process = new ProcessBuilder(rg.toString(), "--version").start();
        String out = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertThat(code)
            .as("rg --version 必须真实运行成功（证据纪律：不伪造可用性）").isZero();
        assertThat(out)
            .as("版本输出以 ripgrep 开头（CC testRipgrepOnFirstUse :591-592 同款校验）")
            .startsWith("ripgrep ");
    }

    // ───────────────────────── code=1 空结果 ─────────────────────────

    @Test
    @DisplayName("无匹配 → 退出码 1 → 空列表（非错误）")
    void noMatch_returnsEmpty() throws Exception {
        // WHY: CC :376-380 退出码 1 = 正常"无匹配"，resolve([])。Java 若把 code=1 当错误
        //      会污染 GrepTool 的 "No matches found" 语义（区分无匹配 vs 搜索失败）。
        write("a.txt", "hello world\n");
        List<String> result = runner.ripGrep(List.of("-n", "zzz-not-present"), workspace.toString(), null);
        assertThat(result)
            .as("code=1 → 空列表（CC :376-380）").isEmpty();
    }

    // ───────────────────────── content / -c / -l 输出透传 ─────────────────────────

    @Test
    @DisplayName("content 搜索：rg 原生 path:line:content 输出透传")
    void contentSearch_returnsPathLineContent() throws Exception {
        // WHY: content 模式输出格式 path:line:content（GrepTool.ts:443-476 端再相对化前缀）。
        //      断言行以绝对路径前缀 + 正确行号/内容 —— 验证 rg 真实输出管线而非 Java 自建。
        write("a.txt", "hello world\nfoo bar\nhello again\n");
        List<String> result = runner.ripGrep(List.of("-n", "hello"), workspace.toString(), null);
        assertThat(result)
            .as("content -n 输出 path:line:content（两处匹配）")
            .hasSize(2);
        assertThat(result.get(0))
            .as("第 1 行为 :1:hello world")
            .endsWith("a.txt:1:hello world");
        assertThat(result.get(1))
            .as("第 2 行为 :3:hello again")
            .endsWith("a.txt:3:hello again");
    }

    @Test
    @DisplayName("count 搜索：rg 原生 path:count 输出透传")
    void countSearch_returnsPathCount() throws Exception {
        // WHY: count 模式输出格式 path:count（GrepTool.ts:478-524 端 lastIndexOf(':')) 拆分）。
        //      真实 rg 的 -c 输出行序为并行扫描序（非契约），断言行存在即可。
        write("a.txt", "foo\nbar\nfoo\n");
        write("b.txt", "foo\n");
        List<String> result = runner.ripGrep(List.of("-c", "foo"), workspace.toString(), null);
        assertThat(result)
            .as("count 输出 path:count（两文件）")
            .hasSize(2);
        assertThat(result.stream().anyMatch(l -> l.endsWith("a.txt:2")))
            .as("a.txt 含 2 个 foo 匹配").isTrue();
        assertThat(result.stream().anyMatch(l -> l.endsWith("b.txt:1")))
            .as("b.txt 含 1 个 foo 匹配").isTrue();
    }

    @Test
    @DisplayName("-l 文件列表输出：绝对路径列表（GrepTool 端再 mtime 排序/相对化）")
    void filesWithMatches_returnsAbsolutePaths() throws Exception {
        // WHY: -l 模式输出匹配文件绝对路径（GrepTool.ts:526-576 端再 mtime 降序 + relativize）。
        write("a.txt", "foo\n");
        write("b.txt", "bar\n");
        List<String> result = runner.ripGrep(List.of("-l", "foo"), workspace.toString(), null);
        assertThat(result)
            .as("-l 仅含匹配 foo 的文件（a.txt）")
            .hasSize(1);
        assertThat(result.get(0))
            .as("绝对路径以 a.txt 结尾")
            .endsWith("a.txt");
    }

    // ───────────────────────── 参数透传（glob / -i） ─────────────────────────

    @Test
    @DisplayName("--glob 过滤参数透传真实 rg（*.java 只返回 java 文件）")
    void globArg_filtersByExtension() throws Exception {
        // WHY: GrepTool.ts:391-409 把 glob 参数映射为 --glob；Java 端不再自建 globToRegex
        //      近似，由 rg 原生 glob 引擎过滤（GrepTool.ts:46-51 glob → rg --glob）。
        write("a.java", "foo\n");
        write("b.py", "foo\n");
        List<String> result = runner.ripGrep(List.of("-l", "foo", "--glob", "*.java"), workspace.toString(), null);
        assertThat(result)
            .as("--glob *.java 只返回 a.java")
            .hasSize(1);
        assertThat(result.get(0)).endsWith("a.java");
    }

    @Test
    @DisplayName("-i 大小写不敏感透传真实 rg")
    void caseInsensitive_flagWorks() throws Exception {
        // WHY: GrepTool.ts:346-348 case_insensitive → -i；rg 原生大小写不敏感（旧 Java Pattern
        //      CASE_INSENSITIVE 等价语义由真实 rg 承担）。
        write("a.txt", "HELLO world\n");
        List<String> result = runner.ripGrep(List.of("-i", "-n", "hello"), workspace.toString(), null);
        assertThat(result)
            .as("-i 命中大写 HELLO")
            .hasSize(1);
    }

    @Test
    @DisplayName("--type 参数透传真实 rg（--type java 命中 .java）")
    void typeArg_filtersByFileType() throws Exception {
        // WHY: GrepTool.ts:387-389 type → --type；rg 原生类型表（Java 旧 RG_TYPES 近似表被取代，
        //      G30 ⑨跟随 G13 删除）。断言 rg 内置类型表真实生效。
        write("Main.java", "foo\n");
        write("notes.txt", "foo\n");
        List<String> result = runner.ripGrep(List.of("-l", "foo", "--type", "java"), workspace.toString(), null);
        assertThat(result)
            .as("--type java 只命中 Main.java")
            .hasSize(1);
        assertThat(result.get(0)).endsWith("Main.java");
    }

    // ───────────────────────── abort 接线 ─────────────────────────

    @Test
    @DisplayName("abort 已触发 → 空结果（CC signal 已取消语义）")
    void preAbort_returnsEmpty() throws Exception {
        // WHY: CC spawn signal 选项（ripgrep.ts:139）：signal 已 aborted → 搜索立即取消。
        //      Java 端 abortController.isCancelled() 入口短路（CC :365-374 等价语义）。
        write("a.txt", "foo\n");
        AbortController abort = new AbortController();
        abort.abort("interrupt");
        List<String> result = runner.ripGrep(List.of("-n", "foo"), workspace.toString(), abort);
        assertThat(result)
            .as("abort 已触发 → 空结果（不执行搜索）").isEmpty();
    }

    @Test
    @DisplayName("abort 监听器在完成后移除（不残留累积）")
    void abortListener_removedAfterCompletion() throws Exception {
        // WHY: SleepTool 同款 cleanup（SleepTool.java:241-242 removeOnCancel）：listener 若不
        //      移除会在长会话残留累积。断言 NOOP 之外的真实 controller 在 ripGrep 返回后
        //      listenerCount 归零。
        write("a.txt", "foo\n");
        AbortController abort = new AbortController();
        runner.ripGrep(List.of("-n", "foo"), workspace.toString(), abort);
        assertThat(abort.listenerCount())
            .as("ripGrep 返回后 abort 监听器必须已移除")
            .isZero();
    }

    // ───────────────────────── EAGAIN 识别（纯函数） ─────────────────────────

    @Test
    @DisplayName("EAGAIN stderr 识别（os error 11 / Resource temporarily unavailable）")
    void eagainDetection() {
        // WHY: CC :87-92 EAGAIN → 单线程 -j 1 重试（:394-409）。识别逻辑独立成纯函数便于
        //      验证两种 stderr 特征串均命中。
        assertThat(RipgrepRunner.isEagainError("failed: os error 11 (Try again)"))
            .as("stderr 含 'os error 11' → EAGAIN").isTrue();
        assertThat(RipgrepRunner.isEagainError("Resource temporarily unavailable"))
            .as("stderr 含 'Resource temporarily unavailable' → EAGAIN").isTrue();
        assertThat(RipgrepRunner.isEagainError("normal error"))
            .as("其他 stderr → 非 EAGAIN").isFalse();
    }

    // ───────────────────────── 输出行拆分（纯函数） ─────────────────────────

    @Test
    @DisplayName("splitLines：trim/拆行/CR 剥离/filter 空行（CC :367-371）")
    void splitLines_stripsCrAndBlank() {
        // WHY: CC :367-371 stdout.trim().split('\n').map(strip CR).filter(Boolean)。
        //      Windows rg 输出可能带 \r，不剥离会污染 content 拼接。
        assertThat(RipgrepRunner.splitLines("  a:1:x\r\nb:2:y\r\n\r\n"))
            .as("拆行 + CR 剥离 + 过滤空行")
            .containsExactly("a:1:x", "b:2:y");
        assertThat(RipgrepRunner.splitLines("   \n\n"))
            .as("纯空白 → 空列表")
            .isEmpty();
    }
}
