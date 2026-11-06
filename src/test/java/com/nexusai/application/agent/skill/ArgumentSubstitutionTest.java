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

    @Test
    @DisplayName("parseArguments 长 JSON 输入不抛 StackOverflowError（2026-11-05 生产故障回归）")
    void parseArguments_longJsonInput_noStackOverflow() {
        // WHY（真实事故）：prompt/agent 型 hook 把整个 hook 输入 JSON 当 args 传给
        // substituteArguments（CC hookHelpers.ts:30 addArgumentsToPrompt）。Stop 事件的
        // jsonInput 含 last_assistant_message（整段模型回答）⇒ 几 KB 的转义 JSON。
        // Java 的 Pattern 用线程栈递归回溯（V8 是堆上显式栈，所以 CC 侧永不炸），
        // 旧 chunker 在 5 KB 处必抛 StackOverflowError，且 catch(RuntimeException) 接不住
        // （Error 不是 RuntimeException）⇒ 穿透到 ExecPromptHook.catch(Throwable) →
        // 归一成 non_blocking_error → 「目标评估器执行失败 → 重试 3 次 → 目标暂停」。
        StringBuilder sb = new StringBuilder("{\"hook_event_name\":\"Stop\",\"session_id\":\"sess-x\"");
        for (int i = 0; i < 200; i++) {
            sb.append(",\"k").append(i).append("\":\"C:\\\\a\\\\b | c (d) \\\"q\\\" (e)\"");
        }
        String longJson = sb.append("}").toString();
        assertThat(longJson.length()).isGreaterThan(5000);   // 必须越过旧实现的爆栈阈值

        // 断言 1：不抛错。若 chunker 退回递归回溯形态，这里会抛 StackOverflowError（测试失败）。
        List<String> parsed = ArgumentSubstitution.parseArguments(longJson);
        // 断言 2：返回非空，且首 token 携带 JSON 的骨架 —— 命中的是 shell 分词语义下的
        //   「去引号去转义」形态（整段 JSON 被视作一个 chunk，因为空格都在引号内）。
        //   ⚠️ 不断言原样 JSON：shell-quote 本来就会吃掉引号与反斜杠，CC 侧行为相同。
        assertThat(parsed).isNotEmpty();
        assertThat(parsed.get(0)).startsWith("{hook_event_name:Stop");
        assertThat(parsed.get(0)).contains("session_id:sess-x");
    }

    @Test
    @DisplayName("parseArguments 分词失败 → 回退空白分割（覆盖 Error 型失败，对齐 CC 无类型 catch）")
    void parseArguments_fallbackCoversErrorType() {
        // CC argumentSubstitution.ts:30-34 的 catch (error) 在 JS 里不区分异常类型，
        // 任何解析失败都退回 args.split(/\s+/).filter(Boolean)。Java 侧只有
        // catch(RuntimeException) 时，Error（StackOverflowError 等）会绕过兜底 —
        // 本测试锁住「兜底必须覆盖 Error」这条意图：期望值等同空白分割结果。
        // 注：走不到分词异常时本条也成立（结果与空白分割一致），故用普通输入断言契约。
        assertThat(ArgumentSubstitution.parseArguments("foo ${} bar"))
            .containsExactly("foo", "${}", "bar");
        // 超长坏替换同样必须走兜底而不是抛错（坏替换抛 IllegalArgumentException 在 chunk 扫描期，
        // 长输入下 chunker 的栈问题先发生 → 也必须在兜底范围内）。
        String longBad = "x ${} " + "y ".repeat(5000);
        assertThat(ArgumentSubstitution.parseArguments(longBad)).isNotEmpty();
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
