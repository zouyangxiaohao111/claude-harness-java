package com.nexusai.application.agent.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShellQuoteParser} 测试 · 对齐 CC {@code bashPipeCommand.ts rearrangePipeCommand}
 * + shell-quote parse（shellQuote.ts）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：含管道命令的 stdin 重定向若落到管道末命令
 * （{@code eval 'cmd1 | cmd2' < /dev/null}），第一命令（如 {@code cat}）继承父 stdin 会挂起。
 * rearrange 把 {@code < /dev/null} 插到第一管道命令后（{@code cmd1 < /dev/null | cmd2}），
 * 对齐 CC 修复该 bug。无法安全 parse 时 fallback {@code 'cmd' < /dev/null}（同 CC）。
 */
@DisplayName("ShellQuoteParser 管道命令 stdin 重组（对齐 CC rearrangePipeCommand）")
class ShellQuoteParserTest {

    @Test
    @DisplayName("简单管道：< /dev/null 插到第一命令后（cat file < /dev/null | wc -l）")
    void rearrange_simplePipe_movesStdinRedirect() {
        // WHY: CC rearrangePipeCommand（bashPipeCommand.ts:11-78）——`cat file | wc -l`
        //       → buildCommandParts 重建 + `< /dev/null` 插第一管道命令后 → singleQuoteForEval。
        assertThat(ShellQuoteParser.rearrangePipeCommand("cat file | wc -l"))
            .isEqualTo("'cat file < /dev/null | wc -l'");
    }

    @Test
    @DisplayName("管道含空格参数：重建 quote 恢复 'a b' + singleQuoteForEval 转义内部单引号（对齐 CC）")
    void rearrange_pipeWithSpacedArg_quotesRestored() {
        // WHY: parse 剥引号得 `a b` 单 token，buildCommandParts quoteOne 恢复 `'a b'`（含空白→单引号）；
        //      最后 singleQuoteForEval 整体包裹时把内部 `'` 转义为 `'"'"'`（CC bashPipeCommand.ts:224-229
        //      同款——期望值含 '"'"' 即证明与 CC 一致，非未转义理想串）。
        assertThat(ShellQuoteParser.rearrangePipeCommand("cat 'a b' | grep x"))
            .isEqualTo("'cat '\"'\"'a b'\"'\"' < /dev/null | grep x'");
    }

    @Test
    @DisplayName("含 $( 命令替换 → fallback 'cmd' < /dev/null（同 CC 回退）")
    void rearrange_commandSubstitution_fallsBack() {
        // WHY: CC 对含 $() 的管道走 quoteWithEvalStdinRedirect（bashPipeCommand.ts:18-22）。
        assertThat(ShellQuoteParser.rearrangePipeCommand("echo $(date) | head"))
            .isEqualTo("'echo $(date) | head' < /dev/null");
    }

    @Test
    @DisplayName("含 $VAR 变量 → fallback（CC 回退，shell-quote 展开会丢引用）")
    void rearrange_varRef_fallsBack() {
        assertThat(ShellQuoteParser.rearrangePipeCommand("echo $HOME | head"))
            .isEqualTo("'echo $HOME | head' < /dev/null");
    }

    @Test
    @DisplayName("含控制结构 for → fallback")
    void rearrange_controlStructure_fallsBack() {
        assertThat(ShellQuoteParser.rearrangePipeCommand("for i in 1 2; do echo $i; done | grep 1"))
            .isEqualTo("'for i in 1 2; do echo $i; done | grep 1' < /dev/null");
    }

    @Test
    @DisplayName("fd 重定向保留为单单元（2>&1 不拆分）")
    void rearrange_fdRedirect_preservedAsUnit() {
        // WHY: buildCommandParts 把 2>&1 合并为单单元（bashPipeCommand.ts:111-150），
        //       避免 `< /dev/null` 插入时拆分重定向。
        assertThat(ShellQuoteParser.rearrangePipeCommand("cat file 2>&1 | grep x"))
            .isEqualTo("'cat file 2>&1 < /dev/null | grep x'");
    }

    @Test
    @DisplayName("env 赋值只 quote 值（FOO=bar cat → FOO=bar cat）")
    void rearrange_envAssignment_quotesValueOnly() {
        // WHY: buildCommandParts 对 VAR=value 只 quote 值部分（bashPipeCommand.ts:179-191）。
        assertThat(ShellQuoteParser.rearrangePipeCommand("FOO=bar cat | grep x"))
            .isEqualTo("'FOO=bar cat < /dev/null | grep x'");
    }

    @Test
    @DisplayName("反引号 → fallback（shell-quote 不处理反引号）")
    void rearrange_backtick_fallsBack() {
        assertThat(ShellQuoteParser.rearrangePipeCommand("echo `date` | head"))
            .isEqualTo("'echo `date` | head' < /dev/null");
    }

    @Test
    @DisplayName("parseShell：操作符/词/引号 token 化")
    void parseShell_tokenizes() {
        List<Object> tokens = ShellQuoteParser.parseShell("cat 'a b' | grep x");
        assertThat(tokens).hasSize(5);
        assertThat(tokens.get(0)).isEqualTo("cat");
        assertThat(tokens.get(1)).isEqualTo("a b"); // 单引号剥
        assertThat(tokens.get(2)).isInstanceOf(ShellQuoteParser.Op.class);
        assertThat(((ShellQuoteParser.Op) tokens.get(2)).op()).isEqualTo("|");
        assertThat(tokens.get(3)).isEqualTo("grep");
        assertThat(tokens.get(4)).isEqualTo("x");
    }
}
