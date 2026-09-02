package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Session H3 v2 对抗核验] 缺口修复 RED 测试 · 覆盖 3 个可修复缺口.
 *
 * <p>WHY (规则九 意图验证): v2 对抗核验确认 H3 遗留 4 缺口, 本测试覆盖其中 3 个可安全修复项:
 * <ol>
 *   <li><b>message attachment 恒 null</b> (Gap 1, H3-GAP-1): CC hooks.ts:710-736 对 blockingError
 *       有无生成 hook_blocking_error / hook_success attachment. 本测试验证 message 不再恒 null.</li>
 *   <li><b>Elicitation Map.of NPE</b> (Gap 4, NEW): CC types/hooks.ts:138/143 content 是
 *       z.record(...).optional() 允许 undefined. 本测试验证 hook 只返回 action 不返回 content
 *       时不抛 NPE, elicitation 响应不丢失.</li>
 *   <li><b>expectedHookEvent 校验接线</b> (Gap 3, H3-GAP-4): CC hooks.ts:583-590 非空
 *       expectedHookEvent 且不匹配 → throw. 本测试验证 mismatch 时 fail-loud.</li>
 * </ol>
 *
 * <p><b>Gap 2 (validateHookJson 宽松降级) — [S4 决策反转] 已修复</b>: v2 曾登记
 * 『H3-GAP-2, registered_gap:true 不修』(宽松降级保证不破坏 hook 链). S4 G17 对齐 CC zod
 * 严格校验 (types/hooks.ts:50-176) — 类型偏差 (continue:"yes" 等) 现在抛 validationError →
 * hook_non_blocking_error 可见失败 (CC zod 行为: 模型得到修正提示). 兼容性影响:
 * 部署中返回类型偏差的旧 hook 首次暴露格式错误, 属 CC 对齐的预期行为变化.
 * 严格断言见 HookOutputParserTest 第 11 节 (G17 用例组).
 */
class HookOutputParserV2GapTest {

    // ─────────── Gap 1: message attachment (H3-GAP-1, CC hooks.ts:710-736) ───────────

    @Test
    @DisplayName("Gap1a blockingError 存在 → message 为 hook_blocking_error attachment")
    void blockingError_producesHookBlockingErrorAttachment() {
        // WHY: CC processHookJSONOutput (hooks.ts:710-715) blockingError 非空 → hook_blocking_error
        //       attachment. Java 端 message 恒 null 会让 hook 阻塞的系统提醒丢失. 修复后 message
        //       必须携带 AttachmentMessageDto(type=hook_blocking_error) 而非 null.
        GenericHook.HookResult result = parseFull(
            "{\"decision\":\"block\",\"reason\":\"no access\"}").result();

        assertThat(result.message()).isNotNull();
        assertThat(result.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) result.message()).type()).isEqualTo("hook_blocking_error");
    }

    @Test
    @DisplayName("Gap1b 无 blockingError → message 为 hook_success attachment")
    void noBlockingError_producesHookSuccessAttachment() {
        // WHY: CC processHookJSONOutput (hooks.ts:716-736) 无 blockingError → hook_success attachment
        //       (content:'' 抑制 trivial reminder). Java 端 message 恒 null 会让 hook 成功的系统提醒
        //       丢失. 修复后 message 必须携带 AttachmentMessageDto(type=hook_success) 而非 null.
        GenericHook.HookResult result = parseFull("{\"continue\":true}").result();

        assertThat(result.message()).isNotNull();
        assertThat(result.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) result.message()).type()).isEqualTo("hook_success");
    }

    // ─────────── Gap 4: Elicitation content null → Map.of NPE (NEW, registered_gap:false) ───────────

    @Test
    @DisplayName("Gap4a Elicitation 只返 action 不返 content → 不抛 NPE, elicitationResponse 保留")
    void elicitationWithoutContent_noNpe() {
        // WHY: CC types/hooks.ts:138 content 是 z.record(z.string(), z.unknown()).optional(), hook 只返回
        //       {action:'accept'} 时 content=undefined. Java 端 Map.of("content", null) 抛 NPE →
        //       调用方 catch 后静默降级 proceed, elicitation 响应丢失. 修复后 action 单独成 map 不丢响应.
        HookOutputParser.ParsedHookJSONOutput parsed = parseFull(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"Elicitation\",\"action\":\"accept\"}}");

        assertThat(parsed.elicitationResponse()).isNotNull();
        assertThat(parsed.elicitationResponse()).isInstanceOf(ElicitationResponse.class);
        ElicitationResponse resp = parsed.elicitationResponse();
        assertThat(resp.action()).isEqualTo("accept");
        assertThat(resp.content()).isNull();
    }

    @Test
    @DisplayName("Gap4b ElicitationResult 只返 action → 不抛 NPE, elicitationResultResponse 保留")
    void elicitationResultWithoutContent_noNpe() {
        // WHY: 同 Gap4a, ElicitationResult (types/hooks.ts:143) content 也是 optional. 双通道
        //       (elicitationResponse / elicitationResultResponse) 都不能因 content null 丢响应.
        HookOutputParser.ParsedHookJSONOutput parsed = parseFull(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"ElicitationResult\",\"action\":\"decline\"}}");

        assertThat(parsed.elicitationResultResponse()).isNotNull();
        ElicitationResponse resp = parsed.elicitationResultResponse();
        assertThat(resp.action()).isEqualTo("decline");
        assertThat(resp.content()).isNull();
    }

    // ─────────── Gap 3: expectedHookEvent 校验 (H3-GAP-4, CC hooks.ts:583-590) ───────────

    @Test
    @DisplayName("Gap3a expectedHookEvent 不匹配 → fail-loud throw (CC hooks.ts:583-590)")
    void expectedHookEventMismatch_throws() {
        // WHY: CC processHookJSONOutput 非空 expectedHookEvent 且 hookSpecificOutput.hookEventName
        //       不匹配 → throw (fail-loud). Java 端 processHookJSONOutput 已支持该参数, 但调用方
        //       (CommandHookExecutor.parseStdoutJson) 传 null 跳过校验. 修复后经 toHookResult 接线
        //       实际事件名, mismatch 必须抛异常.
        // 直接调 processHookJSONOutput 传 expectedHookEvent=PreToolUse → 必须 throw
        assertThatThrownBy(() -> HookOutputParser.processHookJSONOutput(
            (HookJSONOutput.SyncHookOutput) HookOutputParser.validateHookJson(
                "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}").json(),
            "cmd", null, "PreToolUse"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Hook returned incorrect event name");
    }

    @Test
    @DisplayName("Gap3b expectedHookEvent 匹配 → 不抛 (CC hooks.ts:583-590)")
    void expectedHookEventMatch_doesNotThrow() {
        // WHY: expectedHookEvent == hookSpecificOutput.hookEventName 时校验通过, 不误伤正常 hook.
        HookOutputParser.ParsedHookJSONOutput parsed = HookOutputParser.processHookJSONOutput(
            (HookJSONOutput.SyncHookOutput) HookOutputParser.validateHookJson(
                "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"ask\"}}").json(),
            "cmd", null, "PreToolUse");

        assertThat(parsed.result().permissionBehavior()).isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // helper · 走 CommandHookExecutor 完整链路 (status==0 委托 HookOutputParser)
    // ════════════════════════════════════════════════════════════════════════

    private static HookOutputParser.ParsedHookJSONOutput parseFull(String stdout) {
        HookOutputParser.ParseResult pr = HookOutputParser.parseHookOutput(stdout);
        assertThat(pr.json()).isInstanceOf(HookJSONOutput.SyncHookOutput.class);
        return HookOutputParser.processHookJSONOutput(
            (HookJSONOutput.SyncHookOutput) pr.json(), "check.sh", null, null);
    }
}
