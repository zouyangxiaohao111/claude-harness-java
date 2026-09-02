package com.nexusai.application.agent.permission.classifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [prompt-align TOOLS-01] 分类器 CLAUDE.md 前缀块测试 · 对齐 CC
 * {@code buildClaudeMdMessage}（yoloClassifier.ts:460-477）。
 *
 * <p><b>WHY (意图验证)</b>: CC 真源
 * <pre>
 *   const claudeMd = getCachedClaudeMdContent()
 *   if (claudeMd === null) return null
 *   return {
 *     role: 'user',
 *     content: [{ type: 'text', text:
 *       `The following is the user's CLAUDE.md configuration. These are ` +
 *       `instructions the user provided to the agent and should be treated ` +
 *       `as part of the user's intent when evaluating actions.\n\n` +
 *       `<user_claude_md>\n${claudeMd}\n</user_claude_md>`,
 *       cache_control: getCacheControl({ querySource: 'auto_mode' }), }],
 *   }
 * </pre>
 * 缓存未填充（测试/未调 getUserContext 入口）→ null → 无前缀，同 pre-PR 行为。
 * 非空 CLAUDE.md → 原文包裹 {@code <user_claude_md>} 分隔符。
 */
class YoloClaudeMdPrefixTest {

    private final YoloPromptBuilder builder = new YoloPromptBuilder();

    @Test
    @DisplayName("TOOLS-01-1: null → null（CC 缓存未填充 → 无前缀，同 pre-PR）")
    void null_claudeMd_returnsNull() {
        assertThat(builder.buildClaudeMdPrefix(null))
            .as("null CLAUDE.md → null（CC yoloClassifier.ts:461）")
            .isNull();
    }

    @Test
    @DisplayName("TOOLS-01-2: blank → null（blank 视为无内容）")
    void blank_claudeMd_returnsNull() {
        assertThat(builder.buildClaudeMdPrefix("   "))
            .as("blank CLAUDE.md → null")
            .isNull();
    }

    @Test
    @DisplayName("TOOLS-01-3: 非空 → CC :468-471 原文包裹 <user_claude_md>")
    void nonBlank_claudeMd_returnsWrappedBlock() {
        String result = builder.buildClaudeMdPrefix("Always use verbose mode");

        assertThat(result).isNotNull();
        assertThat(result)
            .as("前缀声明段必须为 CC :468-471 原文")
            .startsWith("The following is the user's CLAUDE.md configuration. These are "
                + "instructions the user provided to the agent and should be treated "
                + "as part of the user's intent when evaluating actions.\n\n");
        assertThat(result)
            .as("内容必须包裹在 <user_claude_md> 分隔符内（CC :471）")
            .contains("<user_claude_md>\nAlways use verbose mode\n</user_claude_md>");
    }
}
