package com.nexusai.application.agent.skill;

import com.nexusai.model.command.PromptFnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BD-27 update-config 真实 schema（RED→GREEN）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>CC updateConfig.ts:10-13/463 generateSettingsSchema()</b> 从 Zod SettingsSchema 动态生成真实
 *       JSON Schema，使 /update-config prompt 的 "Full Settings JSON Schema" 段与真实类型同步。
 *       Java 旧版 Bootstrapper 注入 {@code () -> "{}"} → 该段恒为 {@code {}}。本测试断言默认构造器
 *       产出真实 schema（含 permissions/env/hooks/model 等核心字段），若退回 {@code {}} 桩必红。</li>
 * </ol>
 */
class UpdateConfigSkillRegistrarSchemaTest {

    @Test
    @DisplayName("默认构造器 register() 产出真实 JSON Schema（非 {} 桩）")
    void defaultRegistrarEmitsRealSchema() {
        BundledSkillDefinition def = new UpdateConfigSkillRegistrar().register();
        List<PromptBlock> blocks = def.getPromptForCommand().apply("",
            PromptFnContext.of(null, List.of(), null));
        String prompt = blocks.get(0).text();

        assertThat(prompt)
            .as("CC updateConfig.ts:465-466 ## Full Settings JSON Schema 段")
            .contains("## Full Settings JSON Schema")
            .as("真实 schema 核心字段（CC SettingsSchema types.ts）")
            .contains("\"permissions\"")
            .contains("\"env\"")
            .contains("\"hooks\"")
            .contains("\"model\"")
            .contains("\"additionalProperties\": true");
    }

    @Test
    @DisplayName("REAL_SETTINGS_SCHEMA 为真实 JSON Schema（object + passthrough + 核心字段）")
    void realSettingsSchemaIsNotStub() {
        assertThat(UpdateConfigSkillRegistrar.REAL_SETTINGS_SCHEMA)
            .as("CC SettingsSchema .passthrough()（types.ts:1072）→ additionalProperties:true")
            .contains("\"type\": \"object\"")
            .contains("\"additionalProperties\": true")
            .contains("\"permissions\"")
            .contains("\"env\"")
            .contains("\"hooks\"")
            .contains("\"cleanupPeriodDays\"")
            .contains("\"spinnerTipsEnabled\"");
    }

    @Test
    @DisplayName("$schema 字段对齐 CC z.literal(CLAUDE_CODE_SETTINGS_SCHEMA_URL) → const 精确值")
    void schemaFieldIsLiteralConstNotGeneralizedString() {
        assertThat(UpdateConfigSkillRegistrar.REAL_SETTINGS_SCHEMA)
            .as("CC types.ts:258-259 z.literal(CLAUDE_CODE_SETTINGS_SCHEMA_URL) 的 toJSONSchema 产物 = type:string + const")
            .contains("\"$schema\"")
            .contains("\"const\": \"https://json.schemastore.org/claude-code-settings.json\"");
    }
}
