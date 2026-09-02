package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-7] SkillTool outputSchema union 测试 · 对齐 CC SkillTool.ts:301-326 outputSchema
 * （{@code z.union([inlineOutputSchema, forkedOutputSchema])} → 根 anyOf 两分支，
 * zodToJsonSchema.ts:17-26 native toJSONSchema 广告契约）。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>：
 * <ol>
 *   <li>CC :325 根 z.union([inline, forked]) — LLM 需知 Skill 返回两种形态（inline 展开 vs fork
 *       子代理结果）。旧扁平 schema 把 result 放共享顶层 + 无 status/agentId，模型无法识别 fork
 *       结果形态（探查 §4.3 S1 已登记 schema-运行时漂移；Java 运行时数据
 *       SkillToolImpl.java:694-707 inline data{success,commandName,allowedTools?,model} 无 status /
 *       :746-751 forkData{success,commandName,status:'forked',agentId,result} 已与两分支匹配）。</li>
 *   <li>分支字段严格隔离（CC :303-312 inline 无 result/agentId；:315-323 forked 无
 *       allowedTools/model）— 模型按分支 const status 区分形态，字段污染会导致 schema 与
 *       运行时数据错配。</li>
 *   <li>required 数组（inline=[success,commandName]，forked=全 5 字段）忠实 CC zod 可选性标注
 *       （仅 inline 的 allowedTools/model/status 为 .optional()）。</li>
 *   <li>status z.literal → {type:string, const:'inline'/'forked'} — zod v4 toJSONSchema 广告契约。</li>
 *   <li>z.object（zod v4 默认闭包）→ additionalProperties:false（对齐 outputschema-strict-v1
 *       先例，TodoWriteTool.java:512/:562）。</li>
 * </ol>
 */
@DisplayName("P1-7 · SkillTool outputSchema union (inline + forked)")
class SkillToolSchemaTest {

    @Test
    @DisplayName("outputSchema: 根 anyOf 2 分支 (inline + forked) (CC :325)")
    void outputSchema_isUnionOfInlineAndForked() {
        // GIVEN: outputSchema 不依赖 registry，null 构造即可
        SkillToolImpl tool = new SkillToolImpl(null);

        // WHEN
        JsonNode schema = tool.outputSchema();

        // THEN: 非 null；根 anyOf 2 分支（CC :325 z.union 参数序 [inline, forked]）
        assertThat(schema).isNotNull();
        assertThat(schema.path("anyOf").size()).isEqualTo(2);

        JsonNode inline = schema.path("anyOf").get(0);
        JsonNode forked = schema.path("anyOf").get(1);

        // ── inline 分支（CC :303-312 inlineOutputSchema）──
        assertThat(inline.path("type").asText()).isEqualTo("object");
        // zod v4 z.object 默认闭包 → additionalProperties:false
        assertThat(inline.path("additionalProperties").asBoolean()).isFalse();
        // success: boolean + 'Whether the skill is valid'（CC :304，与 forked 文案区分）
        assertThat(inline.path("properties").path("success").path("type").asText()).isEqualTo("boolean");
        assertThat(inline.path("properties").path("success").path("description").asText())
                .isEqualTo("Whether the skill is valid");
        // commandName: string（CC :305）
        assertThat(inline.path("properties").path("commandName").path("type").asText()).isEqualTo("string");
        // allowedTools: array<string>（CC :306-309）
        assertThat(inline.path("properties").path("allowedTools").path("type").asText()).isEqualTo("array");
        assertThat(inline.path("properties").path("allowedTools").path("items").path("type").asText())
                .isEqualTo("string");
        // model: string（CC :310）
        assertThat(inline.path("properties").path("model").path("type").asText()).isEqualTo("string");
        // status: {type:string, const:'inline'}（CC :311 z.literal('inline')）
        assertThat(inline.path("properties").path("status").path("type").asText()).isEqualTo("string");
        assertThat(inline.path("properties").path("status").path("const").asText()).isEqualTo("inline");
        // required=[success, commandName]（CC 仅 allowedTools/model/status .optional()）
        assertThat(toList(inline.path("required"))).containsExactly("success", "commandName");
        // 分支隔离：inline 不含 result/agentId（CC :303-312 逐字段核实）
        assertThat(inline.path("properties").has("result")).isFalse();
        assertThat(inline.path("properties").has("agentId")).isFalse();

        // ── forked 分支（CC :315-323 forkedOutputSchema）──
        assertThat(forked.path("type").asText()).isEqualTo("object");
        // zod v4 z.object 默认闭包 → additionalProperties:false
        assertThat(forked.path("additionalProperties").asBoolean()).isFalse();
        // success: boolean + 'Whether the skill completed successfully'（CC :316）
        assertThat(forked.path("properties").path("success").path("type").asText()).isEqualTo("boolean");
        assertThat(forked.path("properties").path("success").path("description").asText())
                .isEqualTo("Whether the skill completed successfully");
        // commandName: string（CC :317）
        assertThat(forked.path("properties").path("commandName").path("type").asText()).isEqualTo("string");
        // status: {type:string, const:'forked'}（CC :318 z.literal('forked')）
        assertThat(forked.path("properties").path("status").path("type").asText()).isEqualTo("string");
        assertThat(forked.path("properties").path("status").path("const").asText()).isEqualTo("forked");
        // agentId: string（CC :319-321）
        assertThat(forked.path("properties").path("agentId").path("type").asText()).isEqualTo("string");
        // result: string（CC :322）
        assertThat(forked.path("properties").path("result").path("type").asText()).isEqualTo("string");
        // required=全 5 字段（CC :315-323 全必填）
        assertThat(toList(forked.path("required")))
                .containsExactly("success", "commandName", "status", "agentId", "result");
        // 分支隔离：forked 不含 allowedTools/model
        assertThat(forked.path("properties").has("allowedTools")).isFalse();
        assertThat(forked.path("properties").has("model")).isFalse();
    }

    private static List<String> toList(JsonNode arr) {
        List<String> result = new ArrayList<>();
        arr.forEach(n -> result.add(n.asText()));
        return result;
    }
}
