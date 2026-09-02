package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryType 四类封闭分类体系 · 对齐 CC memdir/memoryTypes.ts:14-31.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 把记忆类型约束为
 * user/feedback/project/reference 四类——项目可推导内容（代码模式/架构/git
 * 历史）不属于记忆。这决定了提取器 (extract-memories) 对 frontmatter
 * {@code type:} 字段的解析语义：未知类型必须优雅降级（memoryTypes.ts:28-31
 * 注释：legacy files without a {@code type:} field keep working, files with
 * unknown types degrade gracefully），而不是让未知类型污染记忆体系。
 *
 * <p><b>FIX-MC 对齐（修正原 USER 默认偏差）</b>: CC {@code parseMemoryType}
 * 未知/缺失返回 {@code undefined}（memoryTypes.ts:28-31
 * {@code MEMORY_TYPES.find(t => t === raw)}）；{@link MemoryType#fromString}
 * 现返回 {@code null}（对应 CC undefined）——formatMemoryManifest 对 null
 * 省略 {@code [type]} 标签（memoryScan.ts:87 {@code tag = m.type ? `[${m.type}] ` : ''}），
 * 不把未知类型污染为 USER。本测试锁定「未知/缺失 → null + 不抛异常」这一意图。
 *
 * <p>closed set 不变量：枚举值恰为四类，不存在第五类「项目可推导内容」类型。
 */
@DisplayName("[IMP-M-C-2] MemoryType 四类封闭分类体系 + parse 降级")
class MemoryTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"user"})
    @DisplayName("user 精确小写解析 → USER")
    void parseUser(String raw) {
        assertThat(MemoryType.fromString(raw)).isEqualTo(MemoryType.USER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "User"})
    @DisplayName("user 大写/混合大小写 → null（CC parseMemoryType t===raw 大小写敏感精确匹配）")
    void uppercaseDegradesGracefully(String raw) {
        assertThat(MemoryType.fromString(raw))
            .as("CC MEMORY_TYPES 全小写（memoryTypes.ts:14-17），parseMemoryType 用 t===raw 精确匹配 → 非小写降级 null")
            .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"feedback", "project", "reference"})
    @DisplayName("其余三类解析正确")
    void parseOtherTypes(String raw) {
        assertThat(MemoryType.fromString(raw))
            .isIn(MemoryType.FEEDBACK, MemoryType.PROJECT, MemoryType.REFERENCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "gossip", "task", "history", "   "})
    @DisplayName("未知/空类型优雅降级为 null（CC undefined，不引入第五类）")
    void unknownDegradesGracefully(String raw) {
        assertThat(MemoryType.fromString(raw))
            .as("未知类型 → null（CC parseMemoryType undefined）")
            .isNull();
    }

    @Test
    @DisplayName("null 输入 → null（CC parseMemoryType undefined · legacy 无 type 文件继续可用）")
    void nullDegrades() {
        assertThat(MemoryType.fromString(null))
            .as("缺失 type → null（CC undefined，非 USER 默认）")
            .isNull();
    }

    @Test
    @DisplayName("封闭集恰为四类——项目可推导内容不属于记忆类型（memoryTypes.ts:3-8 注释意图）")
    void closedSetExactlyFour() {
        assertThat(MemoryType.values())
            .containsExactlyInAnyOrder(
                MemoryType.USER, MemoryType.FEEDBACK, MemoryType.PROJECT, MemoryType.REFERENCE);
    }

    @Test
    @DisplayName("toTypeValue 输出 frontmatter 小写 type 字段值")
    void toTypeValueLowerCase() {
        assertThat(MemoryType.PROJECT.toTypeValue()).isEqualTo("project");
    }
}
