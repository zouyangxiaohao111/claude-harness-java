package com.nexusai.application.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ALIGN-BD-1 · keybindings-help 补齐 CC 完整 prompt + isEnabled 接线（RED→GREEN）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li>CC keybindings.ts 产出 11 段完整 prompt（intro/file-format/keystroke-syntax/unbinding/
 *       interaction/common-patterns/behavioral-rules/doctor/reserved-shortcuts/contexts/actions），
 *       改造前 Java 仅 3 段（# Keybindings / ## Contexts / ## Actions）+ 2 上下文 + 2 动作——
 *       用户 /keybindings-help 看到的内容与 CC 严重偏离。断言 11 段全在 = 证明「补齐」而非「仍删减」。</li>
 *   <li>CC keybindings.ts:299 isEnabled = isKeybindingCustomizationEnabled（GB 门默认 false），
 *       改造前 isEnabled=null（未接线）。断言 def.isEnabled() 非 null 且默认 false = 证明惰性
 *       启用判定已接线（而非 null 兜底恒 true）。</li>
 * </ol>
 */
class KeybindingsSkillTest {

    private static final String KEYBINDING_CUSTOMIZATION_PROP = "nexusai.feature.keybinding-customization";

    @AfterEach
    void clearConfig() {
        System.clearProperty(KEYBINDING_CUSTOMIZATION_PROP);
    }

    @Test
    @DisplayName("prompt 含 CC 完整 11 段（不再 3 段删减）")
    void promptContainsAllElevenSections() {
        String prompt = new KeybindingsSkill().formatPrompt(null).get(0).text();

        assertThat(prompt).contains(
            "# Keybindings Skill",
            "## CRITICAL: Read Before Write",
            "## File Format",
            "## Keystroke Syntax",
            "## Unbinding Default Shortcuts",
            "## How User Bindings Interact with Defaults",
            "## Common Patterns",
            "### Rebind a key",
            "### Add a chord binding",
            "## Behavioral Rules",
            "## Validation with /doctor",
            "### Common Issues and Fixes",
            "### Example /doctor Output",
            "## Reserved Shortcuts",
            "## Available Contexts",
            "## Available Actions");
    }

    @Test
    @DisplayName("reserved shortcuts 三段齐全（Non-rebindable / Terminal / macOS）")
    void reservedShortcutsListAllThreeGroups() {
        String reserved = new KeybindingsSkill().generateReservedShortcuts();

        assertThat(reserved).contains(
            "### Non-rebindable (errors)",
            "- `ctrl+c` — Cannot be rebound - used for interrupt/exit (hardcoded)",
            "- `ctrl+d` — Cannot be rebound - used for exit (hardcoded)",
            "- `ctrl+m` — Cannot be rebound - identical to Enter in terminals (both send CR)",
            "### Terminal reserved (errors/warnings)",
            "- `ctrl+z` — Unix process suspend (SIGTSTP) (may conflict)",
            "- `ctrl+\\` — Terminal quit signal (SIGQUIT) (will not work)",
            "### macOS reserved (errors)",
            "- `cmd+c` — macOS system copy",
            "- `cmd+space` — macOS Spotlight");
    }

    @Test
    @DisplayName("Available Contexts 表含 CC 全部 18 上下文")
    void contextsTableHasAll18Contexts() {
        String table = new KeybindingsSkill().generateContextsTable();

        List<String> rows = table.lines()
            .filter(line -> line.startsWith("| `"))
            .toList();
        // header + separator + 18 rows = 20 lines；数据行 = 18
        assertThat(rows).hasSize(18);
        assertThat(table).contains("| `Global` | Active everywhere, regardless of focus |")
            .contains("| `Plugin` | When the plugin dialog is open |");
    }

    @Test
    @DisplayName("Available Actions 表含 CC 全部 86 动作")
    void actionsTableHasAll86Actions() {
        String table = new KeybindingsSkill().generateActionsTable();

        List<String> rows = table.lines()
            .filter(line -> line.startsWith("| `"))
            .toList();
        assertThat(rows).hasSize(86);

        // 有默认键位：ctrl+c → app:interrupt（Global）
        assertThat(table).contains("| `app:interrupt` | `ctrl+c` | Global |");
        // 无默认键位（feature-gated）：显示 (none) + 推断 context
        assertThat(table).contains("| `voice:pushToTalk` | (none) | Unknown |");
        // 多键合并：chat:externalEditor 双键
        assertThat(table).contains("| `chat:externalEditor` | `ctrl+x ctrl+e`, `ctrl+g` | Chat |");
    }

    @Test
    @DisplayName("isEnabled 接线：默认 false（CC GB 默认），配置 truthy 开启")
    void isEnabledWiredWithConfigDefaultFalse() {
        // 默认无配置 → false（CC loadUserBindings.ts:41-46 getFeatureValue(..., false)）
        assertThat(KeybindingsSkill.isKeybindingCustomizationEnabled()).isFalse();

        // 配置/环境 truthy → true
        System.setProperty(KEYBINDING_CUSTOMIZATION_PROP, "true");
        assertThat(KeybindingsSkill.isKeybindingCustomizationEnabled()).isTrue();
    }

    @Test
    @DisplayName("registerSkill 接线 isEnabled supplier（改造前为 null）")
    void registerSkillWiresIsEnabledSupplier() {
        BundledSkillDefinition[] captured = new BundledSkillDefinition[1];
        new KeybindingsSkill(def -> captured[0] = def)
            .registerSkill();

        assertThat(captured[0].isEnabled())
            .as("isEnabled 必须接线为 isKeybindingCustomizationEnabled（CC keybindings.ts:299），非 null")
            .isNotNull();
        // 默认配置下 supplier 求值为 false
        assertThat(captured[0].isEnabled().getAsBoolean()).isFalse();
        // description 对齐 CC keybindings.ts:296（含 Examples 尾句）
        assertThat(captured[0].description()).isEqualTo(
            "Use when the user wants to customize keyboard shortcuts, rebind keys, add chord bindings, "
            + "or modify ~/.nexusai/keybindings.json. Examples: \"rebind ctrl+s\", \"add a chord shortcut\", "
            + "\"change the submit key\", \"customize keybindings\".");
        assertThat(captured[0].whenToUse()).as("CC keybindings.ts 无 whenToUse 字段").isNull();
    }

    @Test
    @DisplayName("markdownTable 末行无尾随换行（对齐 CC join('\\n')，keybindings.ts:332-339）")
    void markdownTableHasNoTrailingNewline() {
        // WHY: CC markdownTable 用 .join('\n')（keybindings.ts:334-338），末行无尾随 \n；
        // 改造前 Java 每行追加 " |\n"，导致 Available Contexts 表末行与 ## Available Actions 之间
        // 多一空行（3 换行 vs CC 2 换行）+ prompt 末尾多一尾随换行。此测试锁定字节级一致。
        String contextsTable = new KeybindingsSkill().generateContextsTable();
        assertThat(contextsTable).doesNotEndWith("\n");
        assertThat(contextsTable).endsWith("| `Plugin` | When the plugin dialog is open |");

        String prompt = new KeybindingsSkill().formatPrompt(null).get(0).text();
        assertThat(prompt).doesNotEndWith("\n");
        assertThat(prompt).endsWith("| `voice:pushToTalk` | (none) | Unknown |");
    }
}
