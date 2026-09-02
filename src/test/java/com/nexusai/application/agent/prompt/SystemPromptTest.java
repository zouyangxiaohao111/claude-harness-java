package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemPrompt 品牌数组 · identity 工厂契约测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * 数组态是后续 IMP-SP-03 组装 / IMP-SP-06 拆分的<b>契约前提</b>——CC systemPromptType.ts:8-10
 * 中 SystemPrompt 是数组（readonly string[] 品牌），元素序即发送序，boundary 是独立数组元素。
 * 若 identity 工厂中途坍缩为单字符串 / 复制数组，后续拆分（splitSysPromptPrefix）与缓存作用域
 * 划分将错位。本测试钉死「返回原引用 + 元素序不变」这一零拷贝 identity 语义。
 */
class SystemPromptTest {

    @Test
    @DisplayName("from：返回原 List 引用（CC asSystemPrompt = value as SystemPrompt，纯强转 identity）")
    void from_returnsSameReference() {
        List<String> original = new ArrayList<>(List.of("staticA", "staticB"));

        SystemPrompt sp = SystemPrompt.from(original);

        assertThat(sp.elements())
            .as("identity 工厂必须返回原数组引用，不得防御性拷贝（CC systemPromptType.ts:12-14 纯强转）")
            .isSameAs(original);
    }

    @Test
    @DisplayName("from：元素序不变（元素序=发送序，IMP-SP-06 拆分依赖）")
    void from_preservesElementOrder() {
        List<String> original = new ArrayList<>(List.of("staticA", "__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__", "dynamicB"));

        SystemPrompt sp = SystemPrompt.from(original);

        assertThat(sp.elements())
            .as("元素序必须保持（boundary 作为独立数组元素，非文本内嵌）")
            .containsExactly("staticA", "__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__", "dynamicB");
    }

    @Test
    @DisplayName("from：数组态品牌类型 SystemPrompt 可直接承载多元素（不坍缩为单字符串）")
    void from_carriesArrayStateNotCollapsedString() {
        List<String> multi = new ArrayList<>(List.of("a", "b", "c"));

        SystemPrompt sp = SystemPrompt.from(multi);

        assertThat(sp.elements()).hasSize(3);
        // 无 toString 坍缩语义：SystemPrompt 是数组容器，不是已拼好的文本
        assertThat(sp.elements().get(0)).isEqualTo("a");
    }
}
