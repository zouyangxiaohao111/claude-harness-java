package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BashTool 睡眠拦截测试 — detectBlockedSleepPattern 逐字对齐 CC BashTool.tsx:322-337 +
 * validateInput 门控（:524-538，errorCode 10）。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>只拦首命令裸 sleep N≥2</b>（CC :326-331）：裸 {@code Bash(sleep 5)} 是阻塞轮询反模式，
 *       白白烧 turn —— 应引导模型用 {@code run_in_background} / Monitor 工具；但 <b>管道/脚本内 sleep</b>
 *       （{@code cat file | sleep 5} 首子命令是 cat）是合法场景，不误伤。</li>
 *   <li><b>sub-2s 放行</b>（CC :331 "sub-2s sleeps are fine"）：{@code sleep 1} / {@code sleep 0.5}
 *       是合法 rate limiting / pacing，非轮询。</li>
 *   <li><b>浮点放行</b>（CC :327 "Float durations are allowed"）：{@code sleep 0.5} 正则不匹配。</li>
 *   <li><b>errorCode 10</b>（CC :531）：分类错误码，模型/管线可按码识别"阻塞命令"。</li>
 *   <li><b>门控</b>（CC :525）：feature('MONITOR_TOOL') 关 / run_in_background=true → 放行（背景任务
 *       不阻塞 turn，无需拦截）。</li>
 * </ul>
 */
@DisplayName("[monitor-rework] BashTool 睡眠拦截（CC BashTool.tsx:322-337 + :524-538）")
class BashToolSleepInterceptionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** MONITOR_TOOL flag 开启（第 16 位 monitorTool=true，record 17 字段）。 */
    private static final FeatureFlags MONITOR_FLAG_ON = new FeatureFlags(
        false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, true, false, false, false, false, false);

    /** 空 featureFlags（featureFlags==null 或 ALL_DISABLED → 拦截不触发，对齐 CC flag-off）。 */
    private static final FeatureFlags FLAGS_OFF = FeatureFlags.ALL_DISABLED;

    private static Tool.ValidationResult validate(BashTool tool, String command, boolean runInBackground) {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", command);
        if (runInBackground) {
            input.put("run_in_background", true);
        }
        return tool.validateInput(input, null);
    }

    // ════════════════════════════════════════════════════════════════
    // 1. detectBlockedSleepPattern 模式匹配（逐字对齐 CC :322-337）
    // ════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "command=''{0}'' → {1}")
    @CsvSource({
        // 拦截：首命令裸 sleep N≥2
        "'sleep 5', standalone sleep 5",
        "'sleep 2', standalone sleep 2",
        "'sleep 10 && check', sleep 10 followed by: check",
        "'sleep 10 || echo hi', sleep 10 followed by: echo hi",
        // R3 补漏：分号 / 管道 / 重定向-in-rest / & 四类（对齐 CC splitCommand_DEPRECATED）
        "'sleep 10; check', sleep 10 followed by: check",
        "'sleep 10 | cat', sleep 10 followed by: cat",
        "'sleep 10 && check >> log.txt', sleep 10 followed by: check",
        "'sleep 10 > /dev/null && echo hi', sleep 10 followed by: echo hi",
        "'sleep 10 & check', sleep 10 followed by: & check",
        // 放行：sub-2s / 浮点 / 非首命令
        "'sleep 1', null",
        "'sleep 0.5', null",
        "'cat file | sleep 5', null",
        "'echo hi && sleep 5', null",
        "'sleep 5 > /dev/null', standalone sleep 5",
    })
    @DisplayName("detectBlockedSleepPattern：首命令裸 sleep N≥2 拦截，sub-2s/浮点/管道内放行（CC :322-337）")
    void detectBlockedSleepPattern_matchesCcPattern(String command, String expected) {
        String actual = BashTool.detectBlockedSleepPattern(command);
        if ("null".equals(expected)) {
            assertThat(actual).as("命令应放行: %s", command).isNull();
        } else {
            assertThat(actual).as("命令应拦截: %s", command).isEqualTo(expected);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 2. validateInput 门控 + errorCode 10（对齐 CC :524-538）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MONITOR_TOOL 开 + 裸 sleep 5 → fail errorCode=10 + CC 引导消息")
    void validateInput_blocksSleep5WithErrorCode10() {
        BashTool tool = new BashTool();
        tool.setFeatureFlags(MONITOR_FLAG_ON);

        Tool.ValidationResult r = validate(tool, "sleep 5", false);
        assertThat(r.ok()).as("裸 sleep 5 必须拦截").isFalse();
        assertThat(r.errorCode()).as("CC BashTool.tsx:531 errorCode 10").isEqualTo("10");
        assertThat(r.message())
            .as("消息引导模型改用 run_in_background/Monitor/<2s（CC :530 message）")
            .contains("Blocked: standalone sleep 5")
            .contains("run_in_background: true")
            .contains("use the Monitor tool")
            .contains("under 2 seconds");
    }

    @Test
    @DisplayName("sleep 1 / sleep 0.5 → 放行（sub-2s 与浮点 pacing 合法，CC :327/:331）")
    void validateInput_allowsSub2sAndFloatSleep() {
        BashTool tool = new BashTool();
        tool.setFeatureFlags(MONITOR_FLAG_ON);

        assertThat(validate(tool, "sleep 1", false).ok()).as("sleep 1 放行（rate limiting）").isTrue();
        assertThat(validate(tool, "sleep 0.5", false).ok()).as("sleep 0.5 放行（float pacing）").isTrue();
    }

    @Test
    @DisplayName("run_in_background:true → 放行（背景任务不阻塞 turn，CC :525 门控）")
    void validateInput_allowsWhenRunInBackground() {
        BashTool tool = new BashTool();
        tool.setFeatureFlags(MONITOR_FLAG_ON);
        // 背景任务本身不阻塞 turn → 无需拦截（CC :525 !input.run_in_background）
        assertThat(validate(tool, "sleep 30", true).ok()).as("run_in_background → 放行").isTrue();
    }

    @Test
    @DisplayName("非首命令 sleep → 放行（不误伤管道/脚本内 sleep）")
    void validateInput_allowsWhenSleepNotFirst() {
        BashTool tool = new BashTool();
        tool.setFeatureFlags(MONITOR_FLAG_ON);
        assertThat(validate(tool, "cat file | sleep 5", false).ok())
            .as("管道内 sleep（首子命令是 cat）→ 放行").isTrue();
        assertThat(validate(tool, "echo hi && sleep 5", false).ok())
            .as("&& 后 sleep（首子命令是 echo）→ 放行").isTrue();
    }

    @Test
    @DisplayName("MONITOR_TOOL 门控关 → 放行（featureFlags null / ALL_DISABLED，对齐 CC flag-off）")
    void validateInput_gateOff_passes() {
        BashTool tool = new BashTool();
        tool.setFeatureFlags(FLAGS_OFF);
        assertThat(validate(tool, "sleep 5", false).ok()).as("flag 关 → 拦截不触发").isTrue();

        BashTool noFlags = new BashTool();
        assertThat(validate(noFlags, "sleep 5", false).ok()).as("featureFlags null → 拦截不触发").isTrue();
    }
}
