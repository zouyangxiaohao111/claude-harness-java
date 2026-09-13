package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.compact.SnipCompactor;
import com.nexusai.application.agent.loop.FeatureFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [snip-protect-recent] SnipTool 工具描述契约测试 · 验证意图（CLAUDE.md 规则 9）。
 *
 * <p><b>WHY</b>：三层保护的第 ③ 层 = <b>提示词写明规则</b>。本仓系统提示词对 snip <b>零内容</b>，
 * {@code SnipTool.prompt()}（工具 description）+ {@code inputSchema} 是模型能看到 snip 语义的
 * <b>既有唯一落点</b> —— 另起一处会造出第二套说法（规则十一）。规则若不写明，模型会持续尝试点名
 * 最后两条（硬门会拒，但每次都是白跑一轮）。
 *
 * <p>测试钉死：
 * <ol>
 *   <li>{@code prompt()} 含「最近 N 条用户消息不可 snip」规则；</li>
 *   <li>规则里的数字由 {@link SnipCompactor#KEEP_RECENT_USER_TURNS} <b>拼接</b>（不写死字面量）——
 *       断言直接引用常量，故「写死 2 + 常量改 3」时文案仍显示 2 → 本用例必红；</li>
 *   <li>{@code inputSchema} 的 {@code message_ids} 描述指向<b>同一条</b>规则（同一判据单点）。</li>
 * </ol>
 */
class SnipToolPromptTest {

    private final SnipTool tool = new SnipTool(new FeatureFlags(
        false, false, false, false, false, true,   // historySnip=true（第 6 参）
        false, false, false, false, false, false,
        false, false, false, false, false, false,
        false, false, false, false, false, false));

    @Test
    @DisplayName("工具描述写明最近 N 条用户消息不可 snip")
    void promptStatesProtectionRule() {
        String prompt = tool.prompt();

        assertThat(prompt)
            .as("规则文本必须在工具 description 里（三层保护的第 ③ 层）")
            .contains("most recent user messages carry NO [id:] tag")
            .contains("cannot be snipped")
            .contains("they define the current task and must stay in context");
    }

    @Test
    @DisplayName("规则里的数字由常量拼接（改常量文案跟着变）")
    void promptNumberComesFromConstant() {
        // 断言直接引用常量：若实现把数字写死成字面量，常量变更后本用例必红
        String expectedRule = "The " + SnipCompactor.KEEP_RECENT_USER_TURNS
            + " most recent user messages carry NO [id:] tag";

        assertThat(tool.prompt())
            .as("prompt() 的数字必须由 KEEP_RECENT_USER_TURNS 拼接")
            .contains(expectedRule);

        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("properties").path("message_ids").path("description").asText())
            .as("inputSchema 的 message_ids 描述指向同一规则（数字同由常量拼接）")
            .contains("The " + SnipCompactor.KEEP_RECENT_USER_TURNS + " most recent user messages")
            .contains("cannot be snipped")
            .as("inputSchema 必须【同时】指向 D4 首条规则 —— prompt() 与 schema 两处不得分叉")
            .contains("The FIRST user message also carries no [id:] tag");
    }

    @Test
    @DisplayName("工具描述写明首条用户消息不可 snip（D4 首尾双保护）")
    void promptStatesFirstMessageProtection() {
        // WHY：上一条「最近 N 条」在语义上暗示更早的消息可弃 —— 不明写首条也受保护，
        //   模型会理所当然去裁 U₁（原始任务陈述 + 约束），那正是 D4 要堵的症状
        assertThat(tool.prompt())
            .as("首条规则必须【显式】写出，不能只靠隐含")
            .contains("The FIRST user message also carries NO [id:] tag")
            .contains("cannot be snipped")
            .contains("it holds the original task and its constraints");
    }

    @Test
    @DisplayName("工具描述仍保留既有的 message_ids 短 id 说明（不回归）")
    void promptKeepsExistingShortIdGuidance() {
        assertThat(tool.prompt())
            .as("新增规则不得挤掉既有 [id:xxx] 短 id 说明（对齐 CC SnipTool.ts 语义）")
            .contains("[id:xxx] short IDs")
            .contains("content text never matches");
    }
}
