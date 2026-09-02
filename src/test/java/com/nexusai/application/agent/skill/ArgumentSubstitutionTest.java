package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArgumentSubstitution 单元测试 · 对齐 CC argumentSubstitution.ts（golden 测试）
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>parseArguments 的 shell-quote 语义是替换行为的地基</b>——引号内空格合并、运算符/glob
 *       过滤、$VAR 保留字面、坏替换回退空白分割，直接决定 $0/$1/$ARGUMENTS[N]/$ARGUMENTS 的取值。
 *       不锁死这些语义，5 替换就失去可信度。</li>
 *   <li><b>5 替换 + append 的顺序与边界必须端到端可观测</b>——$name lookahead 不匹配
 *       {@code $name[...]}/{$nameXxx}、越界索引→空串、无占位符时 args 非空才追加、null args
 *       原样返回。</li>
 *   <li><b>期望值全部来自 shell-quote@1.8.2 实跑 + CC 注释三例</b>（argumentSubstitution.ts:19-22），
 *       非手工推导，避免把 Java 实现的偏差固化进测试。</li>
 * </ol>
 *
 * <p>RED teeth: revert 任一分支（如 parseArguments 不做引号内空格合并 / substituteArguments 顺序
 * 颠倒 / $name 用 Pattern.quote 转义）→ 本测试必须 fail。
 */
class ArgumentSubstitutionTest {

    // ════════════════════════════════════════════════════════════════════════
    // parseArguments · CC argumentSubstitution.ts:24-40
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseArguments 文档三例（argumentSubstitution.ts:19-22）：空白拆分 / 引号内空格合并 / 单双引号")
    void parseArguments_documentedExamples() {
        assertThat(ArgumentSubstitution.parseArguments("foo bar baz"))
            .containsExactly("foo", "bar", "baz");
        assertThat(ArgumentSubstitution.parseArguments("foo \"hello world\" baz"))
            .containsExactly("foo", "hello world", "baz");
        assertThat(ArgumentSubstitution.parseArguments("foo 'hello world' baz"))
            .containsExactly("foo", "hello world", "baz");
    }

    @Test
    @DisplayName("parseArguments 空串/纯空白 → 空数组（CC :25-27）")
    void parseArguments_emptyAndBlank() {
        assertThat(ArgumentSubstitution.parseArguments("")).isEmpty();
        assertThat(ArgumentSubstitution.parseArguments("   ")).isEmpty();
        assertThat(ArgumentSubstitution.parseArguments("\t\n")).isEmpty();
    }

    @Test
    @DisplayName("parseArguments 运算符 | ; > < ( ) 过滤为 string 之外的 token（shell-quote CONTROL）")
    void parseArguments_operatorsFiltered() {
        // shell-quote 实跑: "a;b|c>d" -> ["a",{op:";"},"b",{op:"|"},"c",{op:">"},"d"]
        assertThat(ArgumentSubstitution.parseArguments("a;b|c>d"))
            .containsExactly("a", "b", "c", "d");
    }

    @Test
    @DisplayName("parseArguments 未闭合引号：丢弃引号字符，余下按 bareword 解析（shell-quote chunker 实证）")
    void parseArguments_unterminatedQuote() {
        // shell-quote 实跑: "foo \"unterminated bar" -> ["foo","unterminated","bar"]
        assertThat(ArgumentSubstitution.parseArguments("foo \"unterminated bar"))
            .containsExactly("foo", "unterminated", "bar");
    }

    @Test
    @DisplayName("parseArguments 坏替换 ${} 抛错 → 回退空白分割（CC :31-34 tryParseShellCommand fail）")
    void parseArguments_badSubstitutionFallback() {
        // shell-quote 实跑: "foo ${} bar" -> THROW -> parseArguments 回退 split(/\s+/).filter(Boolean)
        assertThat(ArgumentSubstitution.parseArguments("foo ${} bar"))
            .containsExactly("foo", "${}", "bar");
    }

    @Test
    @DisplayName("parseArguments glob（* ? 在引号外）被过滤为对象（shell-quote glob op）")
    void parseArguments_globFiltered() {
        // shell-quote 实跑: "file*.txt" -> [{op:"glob"}] -> 过滤后 []
        assertThat(ArgumentSubstitution.parseArguments("file*.txt")).isEmpty();
        assertThat(ArgumentSubstitution.parseArguments("a*b")).isEmpty();
        assertThat(ArgumentSubstitution.parseArguments("a?b")).isEmpty();
        // 引号内 glob 是字面
        assertThat(ArgumentSubstitution.parseArguments("\"foo*\"")).containsExactly("foo*");
        assertThat(ArgumentSubstitution.parseArguments("'a* b'")).containsExactly("a* b");
    }

    @Test
    @DisplayName("parseArguments $VAR 保留字面（env key=>'$'+key 不展开变量，CC :29-30）")
    void parseArguments_variablePreservedLiterally() {
        assertThat(ArgumentSubstitution.parseArguments("a$b c"))
            .containsExactly("a$b", "c");
        assertThat(ArgumentSubstitution.parseArguments("--model \"$M\""))
            .containsExactly("--model", "$M");
        assertThat(ArgumentSubstitution.parseArguments("\"$HOME/x\""))
            .containsExactly("$HOME/x");
        assertThat(ArgumentSubstitution.parseArguments("${FOO}bar"))
            .containsExactly("$FOObar");
    }

    @Test
    @DisplayName("parseArguments 转义空格、引号内空格合并、双引号内 $VAR 字面")
    void parseArguments_escapesAndQuoteMerging() {
        assertThat(ArgumentSubstitution.parseArguments("a\\ b c"))
            .containsExactly("a b", "c");
        assertThat(ArgumentSubstitution.parseArguments("\"a b\"c"))
            .containsExactly("a bc");
        assertThat(ArgumentSubstitution.parseArguments("a\"\"b"))
            .containsExactly("ab");
        assertThat(ArgumentSubstitution.parseArguments("\"a b\" 'c d' e"))
            .containsExactly("a b", "c d", "e");
        assertThat(ArgumentSubstitution.parseArguments("  spaced  out  "))
            .containsExactly("spaced", "out");
    }

    // ════════════════════════════════════════════════════════════════════════
    // parseArgumentNames · CC argumentSubstitution.ts:50-68
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseArgumentNames 数组输入：过滤 trim 空 与纯数字（防与 $N 冲突，CC :57-59）")
    void parseArgumentNames_arrayInput() {
        assertThat(ArgumentSubstitution.parseArgumentNames(List.of("foo", "bar", "baz")))
            .containsExactly("foo", "bar", "baz");
        assertThat(ArgumentSubstitution.parseArgumentNames(List.of("foo", "", "123", "bar")))
            .containsExactly("foo", "bar");
    }

    @Test
    @DisplayName("parseArgumentNames 空格串输入：split 后过滤（CC :64-66）")
    void parseArgumentNames_stringInput() {
        assertThat(ArgumentSubstitution.parseArgumentNames("foo bar baz"))
            .containsExactly("foo", "bar", "baz");
        assertThat(ArgumentSubstitution.parseArgumentNames("foo 123 bar"))
            .containsExactly("foo", "bar");
    }

    @Test
    @DisplayName("parseArgumentNames null / 非法类型 → 空数组（CC :53-55, :67）")
    void parseArgumentNames_nullAndInvalid() {
        assertThat(ArgumentSubstitution.parseArgumentNames(null)).isEmpty();
        assertThat(ArgumentSubstitution.parseArgumentNames("")).isEmpty();
        assertThat(ArgumentSubstitution.parseArgumentNames("   ")).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // generateProgressiveArgumentHint · CC argumentSubstitution.ts:76-83
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("generateProgressiveArgumentHint 剩余参数提示：[arg2] [arg3]（CC :76-83）")
    void generateHint_remainingArgs() {
        assertThat(ArgumentSubstitution.generateProgressiveArgumentHint(
            List.of("arg1", "arg2", "arg3"), List.of("typed1")))
            .isEqualTo("[arg2] [arg3]");
        assertThat(ArgumentSubstitution.generateProgressiveArgumentHint(
            List.of("foo", "bar"), List.of()))
            .isEqualTo("[foo] [bar]");
    }

    @Test
    @DisplayName("generateProgressiveArgumentHint 全部已填 / 超出 → null（CC return undefined）")
    void generateHint_noRemaining() {
        assertThat(ArgumentSubstitution.generateProgressiveArgumentHint(
            List.of("arg1", "arg2"), List.of("a", "b"))).isNull();
        // typed 超出 names 长度 → slice 空 → undefined
        assertThat(ArgumentSubstitution.generateProgressiveArgumentHint(
            List.of("arg1"), List.of("a", "b", "c"))).isNull();
    }

    @Test
    @DisplayName("generateProgressiveArgumentHint null 参数空安全（null argNames → null 提示）")
    void generateHint_nullSafe() {
        assertThat(ArgumentSubstitution.generateProgressiveArgumentHint(null, List.of("a"))).isNull();
        assertThat(ArgumentSubstitution.generateProgressiveArgumentHint(
            List.of("arg1"), null)).isEqualTo("[arg1]");
    }

    // ════════════════════════════════════════════════════════════════════════
    // substituteArguments · CC argumentSubstitution.ts:94-145
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("$ARGUMENTS 全串替换（CC :136，最后执行）")
    void substitute_fullArguments() {
        assertThat(ArgumentSubstitution.substituteArguments(
            "hello $ARGUMENTS", "world foo", true, List.of()))
            .isEqualTo("hello world foo");
        assertThat(ArgumentSubstitution.substituteArguments(
            "a $ARGUMENTS b", "x y", true, List.of()))
            .isEqualTo("a x y b");
    }

    @Test
    @DisplayName("$ARGUMENTS[N] 索引替换，越界 → 空串（CC :124-127）")
    void substitute_indexed() {
        assertThat(ArgumentSubstitution.substituteArguments(
            "$ARGUMENTS[0] and $ARGUMENTS[1]", "foo bar baz", true, List.of()))
            .isEqualTo("foo and bar");
        assertThat(ArgumentSubstitution.substituteArguments(
            "$ARGUMENTS[5]", "a b", true, List.of()))
            .isEqualTo("");
    }

    @Test
    @DisplayName("$N 简写替换，越界 → 空串（CC :130-133）")
    void substitute_shorthand() {
        assertThat(ArgumentSubstitution.substituteArguments(
            "$0 $1 $2", "foo bar", true, List.of()))
            .isEqualTo("foo bar ");
        assertThat(ArgumentSubstitution.substituteArguments(
            "$5", "a b", true, List.of()))
            .isEqualTo("");
    }

    @Test
    @DisplayName("5 替换混合 + 顺序（$name → $ARGUMENTS[N] → $N → $ARGUMENTS）")
    void substitute_mixedOrder() {
        assertThat(ArgumentSubstitution.substituteArguments(
            "mix $ARGUMENTS[0] $1 and $ARGUMENTS", "x y", true, List.of()))
            .isEqualTo("mix x y and x y");
        assertThat(ArgumentSubstitution.substituteArguments(
            "$ARGUMENTS\n\n$ARGUMENTS", "dup", true, List.of()))
            .isEqualTo("dup\n\ndup");
    }

    @Test
    @DisplayName("appendIfNoPlaceholder=true 且无占位符且 args 非空 → 追加 ARGUMENTS: (CC :140-141)")
    void substitute_append() {
        assertThat(ArgumentSubstitution.substituteArguments(
            "no placeholders here", "foo bar", true, List.of()))
            .isEqualTo("no placeholders here\n\nARGUMENTS: foo bar");
    }

    @Test
    @DisplayName("append 不触发：false / args 空串 / 已发生替换（CC :140）")
    void substitute_appendSuppressed() {
        assertThat(ArgumentSubstitution.substituteArguments(
            "no placeholders here", "foo bar", false, List.of()))
            .isEqualTo("no placeholders here");
        assertThat(ArgumentSubstitution.substituteArguments(
            "no placeholders here", "", true, List.of()))
            .isEqualTo("no placeholders here");
        assertThat(ArgumentSubstitution.substituteArguments(
            "has $ARGUMENTS", "val", true, List.of()))
            .isEqualTo("has val");
    }

    @Test
    @DisplayName("null args → 原样返回 content（CC :100-104）；空串是合法输入会替换为空值")
    void substitute_nullArgs() {
        assertThat(ArgumentSubstitution.substituteArguments(
            "hello $ARGUMENTS", null, true, List.of()))
            .isEqualTo("hello $ARGUMENTS");
        // 空串 args 是合法输入：占位符替换为空值（CC :101-102 注释自证）
        assertThat(ArgumentSubstitution.substituteArguments(
            "$ARGUMENTS", "", true, List.of()))
            .isEqualTo("");
    }

    @Test
    @DisplayName("$name 命名替换：lookahead (?![\\[\\w]) 不匹配 $name[...] 与 $nameXxx（CC :111-121）")
    void substitute_named() {
        assertThat(ArgumentSubstitution.substituteArguments(
            "$name is great", "Alice Bob", true, List.of("name")))
            .isEqualTo("Alice is great");
        assertThat(ArgumentSubstitution.substituteArguments(
            "$name[...] and $nameXxx", "Alice", true, List.of("name")))
            .isEqualTo("$name[...] and $nameXxx\n\nARGUMENTS: Alice");
        assertThat(ArgumentSubstitution.substituteArguments(
            "$name[0]", "zero", true, List.of("name")))
            .isEqualTo("$name[0]\n\nARGUMENTS: zero");
        assertThat(ArgumentSubstitution.substituteArguments(
            "$name2", "not replaced", true, List.of("name")))
            .isEqualTo("$name2\n\nARGUMENTS: not replaced");
    }

    @Test
    @DisplayName("$name 按 argumentNames 索引映射 parsedArgs 位置（CC :111-119）")
    void substitute_namedIndexMapping() {
        assertThat(ArgumentSubstitution.substituteArguments(
            "$arg $other", "A B C", true, List.of("arg", "other")))
            .isEqualTo("A B");
        assertThat(ArgumentSubstitution.substituteArguments(
            "$a $b $c", "1 2 3", true, List.of("a", "b", "c")))
            .isEqualTo("1 2 3");
        assertThat(ArgumentSubstitution.substituteArguments(
            "$foo bar", "V", true, List.of("foo")))
            .isEqualTo("V bar");
        // 词中替换：$a-arg 中 $a 后跟 -（非 [ 或 \w）→ 替换
        assertThat(ArgumentSubstitution.substituteArguments(
            "$a-arg and $a", "x y", true, List.of("a")))
            .isEqualTo("x-arg and x");
    }
}
