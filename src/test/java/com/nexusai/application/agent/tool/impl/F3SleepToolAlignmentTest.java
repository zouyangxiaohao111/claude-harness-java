package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.ToolNameConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3 · SleepTool 名称/description/prompt/interruptBehavior 完整对齐 CC。
 *
 * <p><b>WHY (意图验证)</b>: CC 真源 {@code Open-ClaudeCode/src/tools/SleepTool/prompt.ts}
 * 定义 {@code SLEEP_TOOL_NAME='Sleep'} (:3)、{@code DESCRIPTION='Wait for a specified duration'}
 * (:5)、{@code SLEEP_TOOL_PROMPT} 模板 (:7-17)。旧 Java 实现 name() 返回小写 {@code 'sleep'}
 * （偏离 CC 大写），description() 为自定义文本（偏离 CC），无 prompt() override（Tool.ts
 * default null → ToolRegistry:424 工具描述取不到 LLM 可见提示词），interruptBehavior() 未
 * override（default 'block'，用户新消息会阻塞等待而非取消）。本测试锁定 CC 对齐后的全串
 * 契约，防止后续回归。
 *
 * <p>主体实现（Thread.sleep + @ConditionalOnProperty 门控）保留 —— CC SleepTool 主体源
 * 缺失（git ls-files 仅 prompt.ts），OPD-19 决策不编造主体。
 *
 * @see SleepTool
 */
class F3SleepToolAlignmentTest {

    private final SleepTool tool = new SleepTool();

    @Test
    @DisplayName("name() = 'Sleep'（对齐 CC SLEEP_TOOL_NAME，prompt.ts:3）")
    void nameAlignsWithCc() {
        // WHY: CC 真源 SLEEP_TOOL_NAME = 'Sleep'（prompt.ts:3）。旧实现返回小写 'sleep'，
        // 与 CC 大写 'Sleep' 偏离 → LLM 看到的工具名不同 → 工具注册/transcript 对齐漂移。
        assertThat(tool.name()).isEqualTo(ToolNameConstants.SLEEP_TOOL_NAME);
        assertThat(tool.name()).isEqualTo("Sleep");
    }

    @Test
    @DisplayName("description() = 'Wait for a specified duration'（对齐 CC DESCRIPTION，prompt.ts:5）")
    void descriptionAlignsWithCc() {
        // WHY: CC DESCRIPTION 逐字（prompt.ts:5）。旧实现为自定义长文本，与 CC 不符；
        // description 是 LLM 判断何时调用工具的关键文案，必须逐字对齐。
        assertThat(tool.description()).isEqualTo("Wait for a specified duration");
    }

    @Test
    @DisplayName("prompt() 逐字返回 SLEEP_TOOL_PROMPT（对齐 CC prompt.ts:7-17，<tick> 内联）")
    void promptAlignsWithCc() {
        // WHY: CC SLEEP_TOOL_PROMPT 模板插值 TICK_TAG='tick'（xml.ts:25）后的最终字符串。
        // 旧实现无 prompt() override（Tool.ts default null）→ ToolRegistry:424 LLM 可见
        // 描述退化为 description()，丢失 <tick> 周期检查/并发/backtick 等关键引导。
        // 逐字断言（含 em-dash「—」、反引号、段落空行、无尾随换行）防止 text block 漂移。
        String expected = """
            Wait for a specified duration. The user can interrupt the sleep at any time.

            Use this when the user tells you to sleep or rest, when you have nothing to do, or when you're waiting for something.

            You may receive <tick> prompts — these are periodic check-ins. Look for useful work to do before sleeping.

            You can call this concurrently with other tools — it won't interfere with them.

            Prefer this over `Bash(sleep ...)` — it doesn't hold a shell process.

            Each wake-up costs an API call, but the prompt cache expires after 5 minutes of inactivity — balance accordingly.""";
        assertThat(tool.prompt()).isEqualTo(expected);
        assertThat(tool.prompt()).doesNotEndWith("\n");
    }

    @Test
    @DisplayName("interruptBehavior() = 'cancel'（对齐 CC handlePromptSubmit.ts:320 注释级语义）")
    void interruptBehaviorAlignsWithCc() {
        // WHY: CC handlePromptSubmit.ts:320 注释「interruptBehavior 'cancel' (e.g. SleepTool)」+
        // StreamingToolExecutor.ts:221-237 仅 interruptBehavior='cancel' 工具被新消息中断取消。
        // sleep 是长时等待，用户发新消息应立即取消而非 block 等待（否则 turn 卡死到 sleep 结束）。
        assertThat(tool.interruptBehavior()).isEqualTo("cancel");
    }

    @Test
    @DisplayName("[G31①] 主体契约：inputSchema 含 duration_seconds（CC SleepTool.ts:11-19）")
    void bodyPreserved() {
        // WHY: [G31①] SleepTool 按 CC 真源重写后，输入契约 = duration_seconds（秒、无上限），
        // 不再是旧 duration_ms。schema 是 LLM 调用的唯一入口，作为主体契约锚点锁定，防回退。
        var schema = tool.inputSchema();
        assertThat(schema.path("properties").path("duration_seconds").isObject()).isTrue();
        assertThat(schema.path("required").toString()).contains("duration_seconds");
        // CC z.strictObject → additionalProperties:false（拒绝多余字段）
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }
}
