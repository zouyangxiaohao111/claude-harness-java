package com.nexusai.application.agent.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4 ast 对齐聚焦测试（P0-2 / P0-3）。
 *
 * <p>WHY（测试验证意图而非行为）：
 * <ul>
 *   <li>P0-2 安全洞：{@code ! rm -rf /} 的 negated_command 必须剥 {@code !} 产出真实 argv，
 *       否则 {@code Bash(rm:*)} deny 规则被绕过（CC ast.ts:567-577）；</li>
 *   <li>P0-3 unset：{@code VAR=safe && unset VAR && rm $VAR} 必须拒绝解析 {@code $VAR}
 *       （CC ast.ts:937-938 unset_command varScope.delete，防 fail-open）；</li>
 *   <li>P0-3 for/if/while 分支 scope 拷贝：分支内赋值不得泄漏到块后
 *       （CC ast.ts:693-880 SECURITY），for 循环变量恒 VAR_PLACEHOLDER。</li>
 * </ul>
 */
@DisplayName("BashParser G4 ast 对齐（P0-2 / P0-3）")
class BashParserG4AlignTest {

    // ── P0-2: negated_command 剥 `!` ──

    @Test
    @DisplayName("P0-2: `! rm -rf /` → Simple argv=['rm','-rf','/']（CC ast.ts:567-577 剥 !）")
    void negatedCommand_stripsBang() {
        ParseForSecurityResult r = BashParser.parseForSecurity("! rm -rf /");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        assertThat(s.commands()).hasSize(1);
        assertThat(s.commands().get(0).argv()).containsExactly("rm", "-rf", "/");
        // .text = 内层命令 span（不含 `!`，对齐 CC SimpleCommand.text）
        assertThat(s.commands().get(0).text()).isEqualTo("rm -rf /");
    }

    @Test
    @DisplayName("P0-2: 双重否定 `! ! rm -rf /` 递归剥两个 !（CC negated_command 递归）")
    void negatedCommand_doubleBangRecursivelyStripped() {
        ParseForSecurityResult r = BashParser.parseForSecurity("! ! rm -rf /");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        assertThat(s.commands().get(0).argv()).containsExactly("rm", "-rf", "/");
    }

    @Test
    @DisplayName("P0-2: `!foo` / `!=` 非否定运算符（词内叹号）不剥")
    void negatedCommand_wordInternalBang_notStripped() {
        ParseForSecurityResult r = BashParser.parseForSecurity("echo !foo");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        assertThat(s.commands().get(0).argv()).containsExactly("echo", "!foo");
    }

    @Test
    @DisplayName("P0-2: 管道后段 `echo a | ! rm -rf /` 亦剥 !")
    void negatedCommand_afterPipe_stripsBang() {
        ParseForSecurityResult r = BashParser.parseForSecurity("echo a | ! rm -rf /");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        List<String> last = s.commands().get(s.commands().size() - 1).argv();
        assertThat(last).containsExactly("rm", "-rf", "/");
    }

    // ── P0-3: unset 删除 varScope ──

    @Test
    @DisplayName("P0-3: `VAR=safe && unset VAR && rm $VAR` → TooComplex（unset 后 $VAR 不可解析）")
    void unset_removesVarFromScope() {
        ParseForSecurityResult r = BashParser.parseForSecurity("VAR=safe && unset VAR && rm $VAR");
        assertThat(r).isInstanceOf(ParseForSecurityResult.TooComplex.class);
    }

    @Test
    @DisplayName("P0-3: `unset -v VAR` 剥 flag 后仍删除 VAR")
    void unset_withFlag_stillRemovesVar() {
        ParseForSecurityResult r = BashParser.parseForSecurity("VAR=safe && unset -v VAR && rm $VAR");
        assertThat(r).isInstanceOf(ParseForSecurityResult.TooComplex.class);
    }

    @Test
    @DisplayName("P0-3: 未 unset 的变量正常解析（VAR=safe && rm $VAR → Simple argv=['rm','safe']）")
    void noUnset_varResolvesNormally() {
        ParseForSecurityResult r = BashParser.parseForSecurity("VAR=safe && rm $VAR");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        // 纯赋值段 VAR=safe 不产出命令（scope 写），唯一命令 = rm $VAR → ['rm','safe']
        assertThat(s.commands()).hasSize(1);
        assertThat(s.commands().get(0).argv()).containsExactly("rm", "safe");
    }

    // ── 遗留项2: unset 删除 varScope 严格对齐 CC（bashParser.ts:3674-3678 + ast.ts:937-938）──
    // CC parseUnset 对每个非 `-` 开头操作数统一包 variable_name → ast.ts varScope.delete，
    // 不论 -f / -v / 无旗标。故 `unset -f foo` 亦删 foo（CC 比 bash 运行语义更严，主 agent 裁决以 CC 为准）。

    @Test
    @DisplayName("遗留项2: `VAR=x && unset -f foo && echo $VAR` → Simple（unset -f 删 foo，但 foo 未追踪故对 VAR 无影响）")
    void unsetFunc_untrackedName_noEffectOnOtherVar() {
        ParseForSecurityResult r = BashParser.parseForSecurity("VAR=x && unset -f foo && echo $VAR");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        assertThat(s.commands().get(s.commands().size() - 1).argv()).containsExactly("echo", "x");
    }

    @Test
    @DisplayName("遗留项2: `VAR=x && unset -f foo && rm $VAR` → Simple（$VAR 仍追踪字面值 x）")
    void unsetFunc_untrackedName_rmStillTracksVar() {
        ParseForSecurityResult r = BashParser.parseForSecurity("VAR=x && unset -f foo && rm $VAR");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        assertThat(s.commands().get(s.commands().size() - 1).argv()).containsExactly("rm", "x");
    }

    @Test
    @DisplayName("遗留项2: `foo=x && unset -f foo && rm $foo` → TooComplex（CC：unset -f 也删 varScope，$foo 未追踪）")
    void unsetFunc_sameNameVarRemoved_tooComplex() {
        ParseForSecurityResult r = BashParser.parseForSecurity("foo=x && unset -f foo && rm $foo");
        assertThat(r).isInstanceOf(ParseForSecurityResult.TooComplex.class);
    }

    @Test
    @DisplayName("遗留项2: `foo=x && unset -v foo && rm $foo` → TooComplex（unset -v 删变量，既有行为保留）")
    void unsetVariableFlag_stillRemovesVar() {
        ParseForSecurityResult r = BashParser.parseForSecurity("foo=x && unset -v foo && rm $foo");
        assertThat(r).isInstanceOf(ParseForSecurityResult.TooComplex.class);
    }

    // ── P0-3: for 循环变量恒占位 ──

    @Test
    @DisplayName("P0-3: `for i in a b; do rm $i; done` → TooComplex（裸 $i 恒占位 → 拒绝）")
    void forLoop_bareLoopVarVar_tooComplex() {
        ParseForSecurityResult r = BashParser.parseForSecurity("for i in a b; do rm $i; done");
        assertThat(r).isInstanceOf(ParseForSecurityResult.TooComplex.class);
    }

    @Test
    @DisplayName("P0-3: `for i in a b; do echo \"item: $i\"; done` → Simple（字符串嵌入放行，占位）")
    void forLoop_stringEmbedLoopVar_simple() {
        ParseForSecurityResult r = BashParser.parseForSecurity("for i in a b; do echo \"item: $i\"; done");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        // header「for i in a b」不产出命令（CC for_statement 不 push header command）
        assertThat(s.commands()).hasSize(1);
        assertThat(s.commands().get(0).argv().get(0)).isEqualTo("echo");
        assertThat(s.commands().get(0).argv().get(1)).contains(BashParser.VAR_PLACEHOLDER);
    }

    @Test
    @DisplayName("P0-3: for 循环变量恒占位 —— `for i in /etc/passwd; do cat $i; done` → TooComplex")
    void forLoop_absolutePathLoopVar_tooComplex() {
        ParseForSecurityResult r = BashParser.parseForSecurity("for i in /etc/passwd; do cat $i; done");
        assertThat(r).isInstanceOf(ParseForSecurityResult.TooComplex.class);
    }

    // ── P0-3: if/while 分支 scope 拷贝 ──

    @Test
    @DisplayName("P0-3: `if false; then T=safe; fi && rm $T` → TooComplex（分支赋值不泄漏）")
    void ifBranch_assignmentDoesNotLeak() {
        ParseForSecurityResult r = BashParser.parseForSecurity("if false; then T=safe; fi && rm $T");
        assertThat(r).isInstanceOf(ParseForSecurityResult.TooComplex.class);
    }

    @Test
    @DisplayName("P0-3: `if true; then echo hi; fi` → Simple（cond + body 两命令，无关键字命令）")
    void ifStatement_condAndBodyCommands_only() {
        ParseForSecurityResult r = BashParser.parseForSecurity("if true; then echo hi; fi");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        assertThat(s.commands()).hasSize(2);
        assertThat(s.commands().get(0).argv()).containsExactly("true");
        assertThat(s.commands().get(1).argv()).containsExactly("echo", "hi");
    }

    @Test
    @DisplayName("P0-3: `while true; do echo hi; done` → Simple（cond + body）")
    void whileStatement_condAndBodyCommands_only() {
        ParseForSecurityResult r = BashParser.parseForSecurity("while true; do echo hi; done");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        assertThat(s.commands()).hasSize(2);
        assertThat(s.commands().get(0).argv()).containsExactly("true");
        assertThat(s.commands().get(1).argv()).containsExactly("echo", "hi");
    }

    // ── 遗留项2: while read VAR 条件变量追踪（CC ast.ts:839-877）──

    @Test
    @DisplayName("遗留项2: `while read line; do echo \"line: $line\"; done` → Simple（read 循环变量 scope 化追踪，字符串嵌入放行）")
    void whileRead_loopVarInString_simple() {
        ParseForSecurityResult r = BashParser.parseForSecurity("while read line; do echo \"line: $line\"; done");
        assertThat(r).isInstanceOf(ParseForSecurityResult.Simple.class);
        ParseForSecurityResult.Simple s = (ParseForSecurityResult.Simple) r;
        // condition `read line` + body `echo "line: ..."` 两条命令（对齐 CC：condition read 命令亦产出）
        assertThat(s.commands()).hasSize(2);
        assertThat(s.commands().get(0).argv()).containsExactly("read", "line");
        assertThat(s.commands().get(1).argv().get(0)).isEqualTo("echo");
        // body 内 $line 按循环变量（VAR_PLACEHOLDER，值运行时不详）处理，非误判为未追踪
        assertThat(s.commands().get(1).argv().get(1)).contains(BashParser.VAR_PLACEHOLDER);
    }

    @Test
    @DisplayName("遗留项2: `while read line; do rm $line; done` → TooComplex（裸 $line 恒占位 → 拒绝，fail-closed）")
    void whileRead_bareLoopVar_tooComplex() {
        ParseForSecurityResult r = BashParser.parseForSecurity("while read line; do rm $line; done");
        assertThat(r).isInstanceOf(ParseForSecurityResult.TooComplex.class);
    }

    @Test
    @DisplayName("遗留项2 fail-closed: `while read line; do echo \"$line\"; done` → TooComplex（solo-placeholder 字符串，CC ast.ts:1631-1638 同语义）")
    void whileRead_soloPlaceholderString_tooComplex() {
        // 任务原期望 Simple；但 CC walkString 拒绝 solo-placeholder 字符串（`"$VAR"` 单独成 argv 元素
        // 会绕过下游路径校验，ast.ts:1631-1638），且 `for i in a b; do echo "$i"; done` 同款亦 TooComplex。
        // 保持 fail-closed + CC 对齐：只有带字面内容的字符串（如 `"line: $line"`）才 Simple。
        ParseForSecurityResult r = BashParser.parseForSecurity("while read line; do echo \"$line\"; done");
        assertThat(r).isInstanceOf(ParseForSecurityResult.TooComplex.class);
    }

    @Test
    @DisplayName("P0-3: `if true; then T=safe; else T=other; fi && echo $T` → TooComplex（两个分支都不泄漏）")
    void ifElse_branchesDoNotLeak() {
        ParseForSecurityResult r = BashParser.parseForSecurity("if true; then T=safe; else T=other; fi && echo $T");
        assertThat(r).isInstanceOf(ParseForSecurityResult.TooComplex.class);
    }
}
