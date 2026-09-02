package com.nexusai.application.agent.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [G3-2] 空白值域统一测试 · 单一事实源 {@link BashWhitespace}（JS {@code \s} legacy 值域）。
 *
 * <p>WHY（规则九：验证意图而非仅行为）：CC bashSecurity.ts 各 {@code /\s/} 单字符测试与
 * BashParser.tokenize / splitForSecurity 的空白扫描必须同值域，否则同一输入两侧判定不一致
 * （安全校验器按 JS {@code \s} 拦截、tokenizer 按 {@code Character.isWhitespace} 切词，产生
 * 解析差异绕过）。断言三不变量：
 * <ul>
 *   <li>NBSP(U+00A0) 是 JS {@code \s} 空白 → isBashWhitespace true，tokenize/splitForSecurity 视为词界；</li>
 *   <li>FS(U+001C) 非 JS {@code \s} 空白 → isBashWhitespace false，tokenize 视为字面字符；</li>
 *   <li>两侧同源（tokenize 空白边界 == isBashWhitespace，均委派 {@link BashWhitespace}）。</li>
 * </ul>
 */
@DisplayName("[G3-2] BashWhitespace JS \\s 值域统一")
class BashWhitespaceTest {

    @Test
    @DisplayName("JS \\s 值域判定：ASCII 空白 + Unicode 空白 true；FS/GS/RS/US(U+001C-1F) false")
    void valueDomain() {
        assertThat(BashWhitespace.isBashWhitespace(' ')).as("space").isTrue();
        assertThat(BashWhitespace.isBashWhitespace('\t')).as("tab").isTrue();
        assertThat(BashWhitespace.isBashWhitespace('\n')).as("LF").isTrue();
        assertThat(BashWhitespace.isBashWhitespace('\r')).as("CR").isTrue();
        assertThat(BashWhitespace.isBashWhitespace('\f')).as("FF").isTrue();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x0B)).as("VT").isTrue();
        // Unicode 空白：NBSP / U+1680 / U+2000-200A / U+2028 / U+2029 / U+202F / U+205F / U+3000 / U+FEFF
        assertThat(BashWhitespace.isBashWhitespace((char) 0x00A0)).as("NBSP").isTrue();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x1680)).isTrue();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x2000)).as("图空格族").isTrue();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x2007)).as("图空格 U+2007").isTrue();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x2028)).isTrue();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x2029)).isTrue();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x202F)).as("窄不换行空格").isTrue();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x205F)).isTrue();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x3000)).isTrue();
        assertThat(BashWhitespace.isBashWhitespace((char) 0xFEFF)).as("ZWNBSP/BOM").isTrue();
        // 非 JS \s：FS/GS/RS/US（Character.isWhitespace 多含、JS \s 不含）
        assertThat(BashWhitespace.isBashWhitespace((char) 0x001C)).as("FS").isFalse();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x001D)).as("GS").isFalse();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x001E)).as("RS").isFalse();
        assertThat(BashWhitespace.isBashWhitespace((char) 0x001F)).as("US").isFalse();
        // 普通字母不是空白
        assertThat(BashWhitespace.isBashWhitespace('a')).isFalse();
    }

    @Test
    @DisplayName("tokenize：NBSP(U+00A0) 被识别为空白词界（ls<NBSP>/etc → 2 词）")
    void tokenizeNbspIsWordBoundary() {
        List<BashParser.Token> tokens = BashParser.tokenize("ls" + (char) 0x00A0 + "/etc");
        List<String> words = tokens.stream()
                .filter(t -> t.kind() == BashParser.TokenKind.WORD)
                .map(BashParser.Token::text)
                .toList();
        // 修复前 Character.isWhitespace 不认 NBSP → 单 WORD "ls<NBSP>/etc"；修复后拆 2 词
        assertThat(words).containsExactly("ls", "/etc");
    }

    @Test
    @DisplayName("tokenize：FS(U+001C) 不被识别为空白 → 字面字符并入词（JS \\s 不含 U+001C）")
    void tokenizeFsIsNotWhitespace() {
        List<BashParser.Token> tokens = BashParser.tokenize("ls" + (char) 0x001C + "/etc");
        List<String> words = tokens.stream()
                .filter(t -> t.kind() == BashParser.TokenKind.WORD)
                .map(BashParser.Token::text)
                .toList();
        assertThat(words).containsExactly("ls" + (char) 0x001C + "/etc");
    }

    @Test
    @DisplayName("splitForSecurity：heredoc 定界符前 NBSP 被识别为空白跳过（<<<NBSP>EOF → delim=EOF）")
    void splitForSecurityHeredocNbspSkipped() {
        BashParser.SplitForSecurity split =
                BashParser.splitForSecurity("cat <<" + (char) 0x00A0 + "EOF\nbody\nEOF\n");
        // 修复前 NBSP 并入定界符 → heredoc 不识别 → 整段成一命令；修复后 delim=EOF → 首命令 "cat <<EOF"
        assertThat(split.commands()).contains("cat <<EOF");
    }
}
