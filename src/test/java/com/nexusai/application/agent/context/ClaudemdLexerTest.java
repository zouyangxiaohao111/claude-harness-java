package com.nexusai.application.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-F1-6] ClaudemdLexer 内部块级 lexer · extractIncludePaths backtick 配对（OPD-CM5-F-08）。
 *
 * <p>WHY（规则九 · 测试验证意图）：CC {@code extractIncludePathsFromTokens} 跳过 code/codespan
 * token（claudemd.ts:496-497），单反引号/多反引号行内 span 内 {@code @path} 一律不提取。旧
 * Java 实现「找下一个 {@code `} 」单对配对，三反引号 opener 的第二个 {@code `} 被当作 close，
 * span 内容被扫描 → 其中 {@code @path} 被误提取 → 多加载一个文件进上下文（token 面，△-2）。
 * 本测试锁定：同长反引号 run 配对（marked {@code \1} backreference 语义），单/双/三反引号
 * span 内 {@code @path} 均忽略，span 外真实 {@code @path} 仍提取（防过度修复）。
 */
@DisplayName("[IMP-F1-6] ClaudemdLexer extractIncludePaths backtick 配对对齐 CC")
class ClaudemdLexerTest {

    /** 提取 {@code @./file.md}（相对 /base/doc.md 的 dirname=/base）后的期望绝对路径。 */
    private static String resolved(String baseDir, String name) {
        return Paths.get(baseDir, name).normalize().toString();
    }

    @Test
    @DisplayName("三反引号行内 codespan 内 @path 不提取；span 外真实 @path 仍提取 (claudemd.ts:496-497, △-2)")
    void tripleBacktickCodespanAtPathIgnored() {
        // WHY（OPD-CM5-F-08/△-2）：``` ```js @/secret``` ``` 行内三反引号 span 内 @path 若被
        //   误提取 → 多加载一个文件进上下文（token 面）。旧实现把 opener 第二个 ` 当 close，
        //   本用例锁同长 run 配对后 span 内 @path 忽略 + span 外真实 @path 仍提取。
        String expected = resolved("/base", "file.md");
        String skipped = resolved("/base", "skip.md");
        // 三反引号 opener + js 语言标签 + 三反引号 closer（probe 报告原例形态）
        assertThat(ClaudemdLexer.extractIncludePaths(
                "use ```js @./skip.md``` then @./file.md", "/base/doc.md"))
            .as("三反引号 span 内 @./skip.md 忽略，span 外 @./file.md 提取")
            .containsExactly(expected);
        assertThat(ClaudemdLexer.extractIncludePaths(
                "use ```@./skip.md``` then @./file.md", "/base/doc.md"))
            .as("无语言标签三反引号 span 同样忽略")
            .containsExactly(expected);
        assertThat(skipped).as("被忽略路径与提取路径不同（防断言自洽失效）")
            .isNotEqualTo(expected);
    }

    @Test
    @DisplayName("双反引号 opener 同长 run 配对跳过（多反引号 span 均跳过）")
    void doubleBacktickCodespanAtPathIgnored() {
        // WHY：CC 单反引号/多反引号 span 均跳过（claudemd.ts:496-497）；双反引号 opener 必须
        //   由同长双反引号 run 闭合，否则 opener 第二个 ` 被当 close → span 内容被扫描。
        String expected = resolved("/base", "file.md");
        assertThat(ClaudemdLexer.extractIncludePaths(
                "use ``@./skip.md`` then @./file.md", "/base/doc.md"))
            .as("双反引号 span 内忽略，span 外提取")
            .containsExactly(expected);
    }

    @Test
    @DisplayName("单反引号 codespan 内 @path 不提取（CLD-05② 既有行为回归）")
    void singleBacktickCodespanAtPathIgnored() {
        // WHY：单反引号 span（最常见 codespan 形态）内 @path 必须忽略；span 外提取。
        String expected = resolved("/base", "file.md");
        assertThat(ClaudemdLexer.extractIncludePaths(
                "use `@./skip.md` then @./file.md", "/base/doc.md"))
            .as("单反引号 span 内忽略，span 外提取")
            .containsExactly(expected);
    }

    @Test
    @DisplayName("span 内容含其他长度反引号 run 仍属代码内容跳过（同长 closer 续找）")
    void codespanContentWithShorterRunInside() {
        // WHY：marked code 规则 closer 必须与 opener **同长**（`\1` backreference）；span 内容
        //   里出现其他长度的反引号 run 属代码内容，不是 closer，须继续向后找同长 run。否则
        //   三反引号 span 内嵌单反引号时误把单反引号当 close → span 尾部 @path 被扫描。
        //   注：span 必须在段落中间（行首 ``` 会被块级 lexer 分类为 fenced CODE 块，整个跳过，
        //   不经过行内配对逻辑——那是块级行为，由 fencedCodeBlockAtPathIgnored 另测）。
        String expected = resolved("/base", "file.md");
        assertThat(ClaudemdLexer.extractIncludePaths(
                "use ```a ` b @./x.md``` then @./file.md", "/base/doc.md"))
            .as("三反引号 span（内容含单反引号）整体跳过，span 外提取")
            .containsExactly(expected);
    }

    @Test
    @DisplayName("多个 codespan + 真实 @path 混合：仅提取 span 外真实路径")
    void multipleCodespansMixed() {
        // WHY：真实 CLAUDE.md 常混用单/多反引号代码引用与 @include；逐一配对，全部 span 内
        //   @path 忽略，仅 span 外真实 @path 进上下文。
        String one = resolved("/base", "one.md");
        String two = resolved("/base", "two.md");
        String three = resolved("/base", "three.md");
        assertThat(ClaudemdLexer.extractIncludePaths(
                "a `@./one.md` b ```@./two.md``` c @./three.md", "/base/doc.md"))
            .as("单/三反引号 span 内忽略，仅 span 外 @./three.md 提取")
            .containsExactly(three);
        assertThat(one).as("one 为被忽略路径，与提取路径不同").isNotEqualTo(three);
        assertThat(two).as("two 为被忽略路径，与提取路径不同").isNotEqualTo(three);
    }

    @Test
    @DisplayName("未配对反引号容忍继续扫描（CLD-05② 既有行为回归）")
    void unmatchedBacktickContinues() {
        // WHY（OPD-R2-CLD-05②/G-91）：未配对反引号不构成 codespan（marked 行内容忍），后续
        //   文本继续扫 @path；旧实现 break 停止整段 → 后续 include 丢失。修复不得回归此分支。
        String expected = resolved("/base", "file.md");
        assertThat(ClaudemdLexer.extractIncludePaths(
                "use ` once then @./file.md", "/base/doc.md"))
            .as("未闭合单反引号后 @path 仍提取")
            .contains(expected);
        // 未闭合三反引号同样容忍（同长 closer 不存在 → 按普通字符继续扫描）
        assertThat(ClaudemdLexer.extractIncludePaths(
                "use ``` never closed then @./file.md", "/base/doc.md"))
            .as("未闭合三反引号后 @path 仍提取")
            .contains(expected);
    }

    @Test
    @DisplayName("块级 fenced code block 内 @path 不提取（块级 CODE token 跳过）")
    void fencedCodeBlockAtPathIgnored() {
        // WHY：以 ``` 开头的行被块级 lexer 分类为 CODE token（claudemd.ts:496 跳过 code），
        //   extractIncludePaths 对 CODE 块整体跳过；三反引号配对修复不得影响块级分类。
        String expected = resolved("/base", "file.md");
        assertThat(ClaudemdLexer.extractIncludePaths(
                "# T\n```\n@./skip.md (in code)\n```\n@./file.md\n", "/base/doc.md"))
            .as("块级 fenced code 内 @path 忽略，代码块外提取")
            .containsExactly(expected);
    }
}
