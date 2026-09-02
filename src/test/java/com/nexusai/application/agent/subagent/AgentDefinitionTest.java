package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-SUB-18 D16 usesAllTools 精确 {@code ['*']} 回归测试.
 *
 * <p>WHY（规则九·验证意图）：CC {@code agentToolUtils.ts:163-165} {@code hasWildcard =
 * agentTools === undefined || (agentTools.length === 1 && agentTools[0] === '*')} —
 * wildcard 仅 undefined / 精确单元素 {@code ['*']}。旧实现 {@code tools().contains("*")}
 * 对任何含 {@code "*"} 元素的列表（如 {@code ['*', 'read_file']}）判 wildcard = 全量放行，
 * 绕过 by-name 精确解析，扩大安全边界。本测试锁死「多元素含 {@code *} 不再全量放行」这一
 * 安全边界收紧。
 *
 * <p>若有人把 {@code usesAllTools()} 回退为 {@code contains("*")}，本类用例全部变红
 * （规则九红线：业务逻辑变更时测试必须报错）。
 */
@DisplayName("[IMP-SUB-18] AgentDefinition.usesAllTools 精确 ['*'] 六态回归")
class AgentDefinitionTest {

    private static AgentDefinition def(List<String> tools) {
        return AgentDefinition.BuiltInAgentDefinition.builder("test-agent", "when to use", (ctx, dirs) -> "prompt")
            .tools(tools)
            .build();
    }

    @Test
    @DisplayName("['*'] 精确单元素 → usesAllTools=true (CC agentToolUtils.ts:163-165)")
    void usesAllTools_exactSingleWildcard_shouldBeTrue() {
        assertThat(def(List.of("*")).usesAllTools()).isTrue();
    }

    @Test
    @DisplayName("['*','x'] 多元素含 * → usesAllTools=false (安全边界: 不得全量放行)")
    void usesAllTools_starWithOtherTool_shouldBeFalse() {
        // WHY: 旧 contains("*") 判 wildcard → 全量放行绕过 by-name; CC 要求 length===1.
        assertThat(def(List.of("*", "Bash")).usesAllTools()).isFalse();
    }

    @Test
    @DisplayName("['x','*'] 多元素含 * (逆序) → usesAllTools=false")
    void usesAllTools_otherToolThenStar_shouldBeFalse() {
        assertThat(def(List.of("Bash", "*")).usesAllTools()).isFalse();
    }

    @Test
    @DisplayName("['x'] 单元素非 * → usesAllTools=false")
    void usesAllTools_singleByName_shouldBeFalse() {
        assertThat(def(List.of("Bash")).usesAllTools()).isFalse();
    }

    @Test
    @DisplayName("[] 显式空列表 → usesAllTools=false (CC [] 非 wildcard)")
    void usesAllTools_explicitEmpty_shouldBeFalse() {
        assertThat(def(List.of()).usesAllTools()).isFalse();
    }

    @Test
    @DisplayName("tools undefined (Optional.empty) → usesAllTools=false (helper 自身; wildcard 由调用方 tools().isEmpty() 前置)")
    void usesAllTools_undefined_shouldBeFalse() {
        // WHY: helper 只负责 CC 第二子句 (['*']); undefined 分支由唯一调用方
        //   SubagentExecutor:2659 tools().isEmpty() 前置覆盖 — 本方法对 Optional.empty 必须 false.
        assertThat(def(null).usesAllTools()).isFalse();
    }

    private static boolean hasWildcardComposition(List<String> tools) {
        AgentDefinition d = tools == null
            ? AgentDefinition.BuiltInAgentDefinition.builder("test-agent", "when to use", (ctx, dirs) -> "prompt").build()
            : def(tools);
        // 唯一调用方 SubagentExecutor:2659 的合成判定 (isEmpty() || usesAllTools()).
        return d.tools().isEmpty() || d.usesAllTools();
    }

    @Test
    @DisplayName("调用方合成语义 (isEmpty()||usesAllTools()) 六态全覆盖 — undefined→wildcard, 其余含 * 多元素→非")
    void hasWildcard_composition_sixStates() {
        // WHY: 真实 wildcard 语义是调用方组合 (SubagentExecutor:2659), 非 helper 单点 —
        //   全表锁死: undefined/['*']→wildcard; []/['*','x']/['x']/['x','*']→非 (安全边界).
        assertThat(hasWildcardComposition(null)).isTrue();                   // undefined → wildcard
        assertThat(hasWildcardComposition(List.of("*"))).isTrue();            // ['*'] → wildcard
        assertThat(hasWildcardComposition(List.of())).isFalse();              // [] → 非
        assertThat(hasWildcardComposition(List.of("*", "Bash"))).isFalse();   // ['*','x'] → 非
        assertThat(hasWildcardComposition(List.of("Bash"))).isFalse();        // ['x'] → 非
        assertThat(hasWildcardComposition(List.of("Bash", "*"))).isFalse();   // ['x','*'] → 非
    }
}
