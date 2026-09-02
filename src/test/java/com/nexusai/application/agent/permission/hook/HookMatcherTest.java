package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [IMP-DS-03 · DC-WF2-MT-03] HookMatcher 构造器 null 容错 → 严格校验（对齐 CC）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: CC {@code HookMatcherSchema}
 * （Open-ClaudeCode/src/schemas/hooks.ts:194-204）将 {@code hooks} 声明为<b>必需数组</b>
 * （:200-202 {@code z.array(HookCommandSchema())}，无 {@code .optional()}）。
 * matcher 缺 {@code hooks}（或为 null）时 zod 校验失败 → 整份 hooks 配置解析失败（loud）。
 *
 * <p>旧 Java 构造器把 null {@code hooks} 静默折叠为 {@code List.of()}（DC-WF2-MT-03 ⊕-3，
 * EV-WF2-MT-061）——Jackson 反序列化时缺 {@code hooks} 的 matcher 被静默接受为空 matcher，
 * 随后在 HookRegistry:479 / :4313 被过滤（no-op），配置缺陷无法在加载层暴露，与 CC
 * "必需字段缺失即失败"的语义偏离。
 *
 * <p>本测试固定新语义：构造器对 null {@code hooks} 抛 {@link IllegalArgumentException}
 * （严格校验），使加载层（HooksConfigSnapshot.policyHooksFromSettings / MultiSourceHooksConfigLoader）
 * 在反序列化时感知到畸形 matcher → warn + 该源置空（对齐 CC 整文件校验失败语义）。
 * 空数组 {@code hooks=[]} 仍合法（CC z.array 允许空数组），不误伤。
 *
 * @since IMP-DS-03
 */
@DisplayName("[IMP-DS-03 DC-WF2-MT-03] HookMatcher 构造器 null 容错 → 严格校验（CC hooks 必需数组）")
class HookMatcherTest {

    @Test
    @DisplayName("hooks=null → 构造即抛 IllegalArgumentException（CC hooks 必需数组, 加载层严格校验）")
    void hooks_null_throws() {
        // WHY: CC HookMatcherSchema hooks 为必需数组（schemas/hooks.ts:200-202）。
        //       旧实现 null → List.of() 静默吞掉畸形 matcher（反序列化容忍）,
        //       导致缺 hooks 的 matcher 在加载层不暴露、成为 no-op。
        //       新实现构造即抛错 → 加载层（Jackson 反序列化）失败 → warn + 源置空。
        assertThatThrownBy(() -> new HookMatcher("Write", null))
            .as("null hooks 必须被严格拒绝（对齐 CC 必需数组校验）")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hooks");
    }

    @Test
    @DisplayName("hooks=空数组 → 合法（CC z.array 允许空数组, 不误伤）")
    void hooks_emptyList_allowed() {
        // WHY: 对齐 CC z.array(HookCommandSchema()) —— 数组必填但可空。
        //       空 hooks matcher 与 CC 语义一致（合法配置, 匹配时无命令可执行）。
        HookMatcher m = new HookMatcher("Write", List.of());
        assertThat(m.matcher()).isEqualTo("Write");
        assertThat(m.hooks()).isEmpty();
    }

    @Test
    @DisplayName("hooks=非空列表 → 保存为不可变副本（List.copyOf）")
    void hooks_validList_immutableCopy() {
        // WHY: record 保证不可变性（CC 纯数据契约）—— 调用方无法在构造后篡改 hooks 列表。
        HookMatcher m = new HookMatcher("Write", List.of(
            new CommandHook("echo hi", null, null, null, null, null, null, null)));
        assertThat(m.hooks()).hasSize(1);
        assertThat(m.hooks().get(0)).isInstanceOf(CommandHook.class);
        assertThat(m.hooks()).as("List.copyOf 返回不可变列表").isUnmodifiable();
    }
}
