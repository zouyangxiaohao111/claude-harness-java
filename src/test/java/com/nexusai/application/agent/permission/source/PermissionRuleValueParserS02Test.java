package com.nexusai.application.agent.permission.source;

import com.nexusai.application.agent.permission.PermissionRuleValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S02] malformed 规则解析对齐 CC {@code permissionRuleValueFromString}
 * （permissionRuleParser.ts:93-133，OD-WF2-01 决策 A）。
 *
 * <p>WHY 这些断言重要：CC 对畸形规则（闭合 ) 不在末尾 / 缺闭合 ) / 缺工具名）<b>不丢弃</b>，
 * 整串作 toolName（死规则，恒非 null）；Java 旧实现放宽（trim / wholeTool / 丢弃）与之相反，
 * 会让本应失效的规则意外生效（如 "Bash()y" 旧实现放宽为 wholeTool("Bash") 允许整个工具）。
 * 对齐后畸形规则必然不匹配任何真实工具，安全边界收紧。
 */
class PermissionRuleValueParserS02Test {

    private final PermissionRuleValueParser parser = new PermissionRuleValueParser();

    @Test
    @DisplayName("S02: 闭合 ) 不在末尾 → 整串作 toolName（死规则），不再放宽为 wholeTool/withContent")
    void closingParenNotAtEnd_wholeStringAsToolName() {
        // CC permissionRuleParser.ts:111-114 —— "Bash()y" 整串作 toolName
        assertThat(parser.parse("Bash()y").toolName()).isEqualTo("Bash()y");
        assertThat(parser.parse("Bash()y").ruleContent()).isNull();
        // CC —— "Bash(x)y" 同理由闭合 ) 不在末尾判定为死规则
        assertThat(parser.parse("Bash(x)y").toolName()).isEqualTo("Bash(x)y");
        assertThat(parser.parse("Bash(x)y").ruleContent()).isNull();
        // 尾部空白同样使闭合 ) 不在末尾 → 死规则
        assertThat(parser.parse("Bash(foo) ").toolName()).isEqualTo("Bash(foo) ");
    }

    @Test
    @DisplayName("S02: 无括号分支不 trim——' Bash' 保留空白当死规则（CC :100），不再 trim 放宽命中 Bash")
    void noParenBranch_keepsWhitespace() {
        assertThat(parser.parse(" Bash").toolName()).isEqualTo(" Bash");
        assertThat(parser.parse(" Bash").ruleContent()).isNull();
        assertThat(parser.parse("Bash").toolName()).isEqualTo("Bash");
    }

    @Test
    @DisplayName("S02: 缺闭合 ) / 顺序错误 → 整串作 toolName（CC :105-108），不再丢弃")
    void missingCloseParen_wholeStringAsToolName() {
        // CC —— "Bash(npm" 无闭合 ) → 整串作 toolName
        assertThat(parser.parse("Bash(npm").toolName()).isEqualTo("Bash(npm");
        assertThat(parser.parse("Bash(npm").ruleContent()).isNull();
        // CC —— "(foo)" 缺工具名 → 整串作 toolName（CC :119-122）
        assertThat(parser.parse("(foo)").toolName()).isEqualTo("(foo)");
        assertThat(parser.parse("(foo)").ruleContent()).isNull();
    }

    @Test
    @DisplayName("S02: 合法规则解析不变——无括号 wholeTool / 空内容与 * 归 wholeTool / 有内容 withContent")
    void validRules_unchanged() {
        assertThat(parser.parse("Bash").toolName()).isEqualTo("Bash");
        assertThat(parser.parse("Bash").ruleContent()).isNull();
        assertThat(parser.parse("Bash()").toolName()).isEqualTo("Bash");
        assertThat(parser.parse("Bash()").ruleContent()).isNull();
        assertThat(parser.parse("Bash(*)").toolName()).isEqualTo("Bash");
        assertThat(parser.parse("Bash(*)").ruleContent()).isNull();
        PermissionRuleValue npm = parser.parse("Bash(npm install)");
        assertThat(npm.toolName()).isEqualTo("Bash");
        assertThat(npm.ruleContent()).isEqualTo("npm install");
    }

    @Test
    @DisplayName("S02: null / 空白输入 → null（Java fail-soft 防御边界，CC 无 null 处理）")
    void nullOrBlank_returnsNull() {
        assertThat(parser.parse(null)).isNull();
        assertThat(parser.parse("")).isNull();
        assertThat(parser.parse(" ")).isNull();
    }
}
