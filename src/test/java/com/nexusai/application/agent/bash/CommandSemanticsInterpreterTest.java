package com.nexusai.application.agent.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommandSemanticsInterpreter} 退出码语义解释 · 对齐 CC
 * {@code tools/BashTool/commandSemantics.ts}。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：CC 用 {@code COMMAND_SEMANTICS} 区分
 * "退出码 1 表达信息而非错误" 的命令（grep no-match / diff differ / find partial / test false），
 * 多数命令只用 DEFAULT（0=成功，其他=error）。本测试锁定 6 语义 + default 与 CC 逐字一致，
 * 避免 Java 端退化为一刀切 {@code exitCode != 0 → error}（旧 BashTool.execute 的脏行为，DEL-A2-01）。
 * 行号以 Read CC 真源自验为准。
 */
@DisplayName("CommandSemanticsInterpreter CC 语义对齐（commandSemantics.ts）")
class CommandSemanticsInterpreterTest {

    /** 注入 BashParser::splitCommands（CC 恒 splitCommand_DEPRECATED 无 no-split 路径；默认构造器已删除）。 */
    private final CommandSemanticsInterpreter interpreter =
        new CommandSemanticsInterpreter(BashParser::splitCommands);

    @Test
    @DisplayName("grep 退出 1 → 非 error + No matches found（CC commandSemantics.ts:33-39）")
    void grep_exit1_isNotError_withNoMatchesMessage() {
        // WHY: CC grep 语义 isError=exitCode>=2；exit==1 → "No matches found"。
        //      旧 Java 把 exit 1 当 error → LLM 误以为 grep 失败，真实语义是"没有匹配"。
        CommandSemanticsInterpreter.Result r =
            interpreter.interpretCommandResult("grep foo file.txt", 1, "", "");
        assertThat(r.isError()).isFalse();
        assertThat(r.message()).isEqualTo("No matches found");
    }

    @Test
    @DisplayName("grep 退出 2 → error（CC commandSemantics.ts:35-37）")
    void grep_exit2_isError() {
        // WHY: exit>=2 才是 grep 真实错误（如文件不存在）；2 与 1 必须区分，不能一并判 error 或非 error
        CommandSemanticsInterpreter.Result r =
            interpreter.interpretCommandResult("grep foo missing.txt", 2, "", "grep: missing.txt: No such file");
        assertThat(r.isError()).isTrue();
    }

    @Test
    @DisplayName("rg 退出 1 → 非 error + No matches found（CC commandSemantics.ts:42-48）")
    void rg_exit1_isNotError() {
        // WHY: ripgrep 语义同 grep，1=无匹配非错误
        CommandSemanticsInterpreter.Result r =
            interpreter.interpretCommandResult("rg pattern .", 1, "", "");
        assertThat(r.isError()).isFalse();
        assertThat(r.message()).isEqualTo("No matches found");
    }

    @Test
    @DisplayName("diff 退出 1 → 非 error + Files differ（CC commandSemantics.ts:60-67）")
    void diff_exit1_isNotError_withFilesDifferMessage() {
        // WHY: diff 退出 1 = 文件有差异（正常业务结果），不是错误；2+ 才是真错误
        CommandSemanticsInterpreter.Result r =
            interpreter.interpretCommandResult("diff a.txt b.txt", 1, "< old\n---\n> new", "");
        assertThat(r.isError()).isFalse();
        assertThat(r.message()).isEqualTo("Files differ");
    }

    @Test
    @DisplayName("find 退出 1 → 非 error + Some directories were inaccessible（CC commandSemantics.ts:50-58）")
    void find_exit1_isNotError_withPartialSuccessMessage() {
        // WHY: find 退出 1 = 部分目录不可访问（partial success），非错误。
        //      ★ A2.md §5.3 误写 "find 1 仍为 error"，与 CC 冲突；以 CC 为准（探查-decisions.md:121 DEC-14 Q1）。
        CommandSemanticsInterpreter.Result r =
            interpreter.interpretCommandResult("find / -name foo", 1, "", "");
        assertThat(r.isError()).isFalse();
        assertThat(r.message()).isEqualTo("Some directories were inaccessible");
    }

    @Test
    @DisplayName("find 退出 2 → error（CC commandSemantics.ts:53-54）")
    void find_exit2_isError() {
        // WHY: exit>=2 才是 find 真实错误（语法错误等）
        CommandSemanticsInterpreter.Result r =
            interpreter.interpretCommandResult("find --badflag .", 2, "", "");
        assertThat(r.isError()).isTrue();
    }

    @Test
    @DisplayName("test / [ 退出 1 → 非 error + Condition is false（CC commandSemantics.ts:69-85）")
    void testBracket_exit1_isNotError_withConditionFalseMessage() {
        // WHY: test/[ 退出 1 = 条件为假（正常判定结果），非错误；2+ 才是真错误
        CommandSemanticsInterpreter.Result r1 =
            interpreter.interpretCommandResult("test -f /nonexistent", 1, "", "");
        assertThat(r1.isError()).isFalse();
        assertThat(r1.message()).isEqualTo("Condition is false");
        CommandSemanticsInterpreter.Result r2 =
            interpreter.interpretCommandResult("[ -d /tmp ]", 1, "", "");
        assertThat(r2.isError()).isFalse();
        assertThat(r2.message()).isEqualTo("Condition is false");
    }

    @Test
    @DisplayName("未识别命令走 DEFAULT：exit 1 → error（CC commandSemantics.ts:22-26 + 94-99）")
    void unknownCommand_exit1_isError() {
        // WHY: 未在 COMMAND_SEMANTICS 的命令（如 git/ls）只有 0=成功，1+ 都是错误
        CommandSemanticsInterpreter.Result r =
            interpreter.interpretCommandResult("git status", 1, "", "fatal: not a git repository");
        assertThat(r.isError()).isTrue();
        assertThat(r.message()).isEqualTo("Command failed with exit code 1");
    }

    @Test
    @DisplayName("DEFAULT：exit 0 → 非 error（CC commandSemantics.ts:22-23）")
    void defaultCommand_exit0_isNotError() {
        CommandSemanticsInterpreter.Result r =
            interpreter.interpretCommandResult("ls -la", 0, "total 0", "");
        assertThat(r.isError()).isFalse();
    }

    @Test
    @DisplayName("注入 BashParser.splitCommands：grep foo | tail -1 → 末段 tail → DEFAULT（CC commandSemantics.ts:112-119）")
    void injectedSplitter_pipeChain_takesLastSegment() {
        // WHY: CC heuristicallyExtractBaseCommand 取 splitCommand_DEPRECATED 最后一段（决定退出码的
        //      是管道末段）；`grep foo | tail -1` 的退出码由 tail 决定 → 应走 DEFAULT 而非 grep 语义。
        //      CC 恒 splitCommand_DEPRECATED、无 no-split 路径 → 必须注入 BashParser.splitCommands 对齐。
        CommandSemanticsInterpreter pipeInterpreter =
            new CommandSemanticsInterpreter(BashParser::splitCommands);
        CommandSemanticsInterpreter.Result r =
            pipeInterpreter.interpretCommandResult("grep foo | tail -1", 1, "", "");
        assertThat(r.isError()).isTrue();
        assertThat(r.message()).isEqualTo("Command failed with exit code 1");
    }

}

