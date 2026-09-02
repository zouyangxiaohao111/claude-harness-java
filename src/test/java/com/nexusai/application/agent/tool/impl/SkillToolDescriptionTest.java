package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-8] SkillTool description(JsonNode) 动态化测试 · 对齐 CC {@code SkillTool.ts:342}
 * {@code description: async ({ skill }) => `Execute skill: ${skill}`}。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>：
 * <ol>
 *   <li>CC description 是 input-aware 函数，权限弹窗消费方（useCanUseTool.tsx:56-60
 *       {@code await tool.description(input, {...})}）据此展示 "Execute skill: &lt;skill&gt;"，
 *       用户能区分正在允许/拒绝哪个技能 —— 静态文案 "Execute a skill within the main conversation"
 *       无法传达本次调用的技能名。</li>
 *   <li>input=null / skill 缺失兜底回退无参 {@code description()}（Java Tool 接口强制抽象方法
 *       不可删除；CC 权限流程 input 恒有值）—— 防御性兜底不 NPE，不改变 CC 语义。</li>
 * </ol>
 */
@DisplayName("P1-8 · SkillTool description(input) 动态化 (CC SkillTool.ts:342)")
class SkillToolDescriptionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("description(input): input 含 skill → 'Execute skill: <skill>' (CC SkillTool.ts:342)")
    void description_withSkillInput_returnsDynamicText() {
        // GIVEN: description(input) 不依赖 registry，null 构造即可（同 SkillToolSchemaTest）
        SkillToolImpl tool = new SkillToolImpl(null);

        // WHEN
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "review-pr");
        String desc = tool.description(input);

        // THEN: 对齐 CC 模板 `Execute skill: ${skill}`
        assertThat(desc).isEqualTo("Execute skill: review-pr");
    }

    @Test
    @DisplayName("description(input): input=null 回退无参 description() 不 NPE")
    void description_withNullInput_fallsBack() {
        // GIVEN
        SkillToolImpl tool = new SkillToolImpl(null);

        // WHEN
        String desc = tool.description((JsonNode) null);

        // THEN: 回退静态文案（Java Tool 接口抽象方法产物，CC 无静态文案）
        assertThat(desc).isEqualTo("Execute a skill within the main conversation");
    }

    @Test
    @DisplayName("description(input): input 缺 skill 字段回退无参 description() 不 NPE")
    void description_withoutSkillField_fallsBack() {
        // GIVEN
        SkillToolImpl tool = new SkillToolImpl(null);

        // WHEN
        String desc = tool.description(MAPPER.createObjectNode());

        // THEN: skill 缺失回退（CC 权限流程 input 恒含 skill，Java 防御性兜底）
        assertThat(desc).isEqualTo("Execute a skill within the main conversation");
    }
}
