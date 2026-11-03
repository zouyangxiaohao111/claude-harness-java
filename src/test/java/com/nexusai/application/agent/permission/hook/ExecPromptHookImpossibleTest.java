package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [goal V253] ExecPromptHook 的 impossible 分支测试 · 对齐 claude.exe v2.1.212 的
 * stop-condition 评估器。
 *
 * <p>WHY: 旧实现只有两态（{@code {ok:true}} / {@code {ok:false,reason}}）。目标模式（/goal）
 * 在「条件根本不可能达成」时若仍走 blocking，目标会一直卡到撞连续 block 上限，
 * 模型陷入「明知道做不到还在试」的死循环 ⇒ 必须有 impossible 这一交还控制权的出口。
 *
 * <p>⚠️ 负向对照是本测试的核心：<b>所有 ok:false 都变成 impossible</b> 是错误实现，
 * 会让任何一次普通「未满足」都直接放弃目标。
 */
@DisplayName("[goal V253] ExecPromptHook impossible 分支")
class ExecPromptHookImpossibleTest {

    private static final String DEFAULT_FAST_MODEL = "haiku-test";
    private static final String HOOK_NAME = "test-prompt-hook";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HookEvent hookEvent = HookEvent.userPromptSubmit("sess-1", "agent-1", "do something");

    /** provider 桩：直接返回给定响应（只走 chatWithOptions 路径）。 */
    private LlmProvider providerReturning(String response) {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC, Boolean skipCacheWrite,
                com.nexusai.infra.llm.LlmCallScope scope,
                com.nexusai.infra.llm.StreamIdleControl streamIdleControl) {
                throw new UnsupportedOperationException();
            }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new UnsupportedOperationException("本用例只走 chatWithOptions");
            }
            @Override public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                                    LlmProvider.ChatRequestOptions options) {
                return response;
            }
        };
    }

    /** 装配 hook + 注入 mock 旁路客户端（委派给 provider 桩）。 */
    private HookResult exec(String response) {
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        LlmProvider provider = providerReturning(response);
        LlmSideCallClient client = org.mockito.Mockito.mock(LlmSideCallClient.class);
        org.mockito.Mockito.when(client.chat(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> provider.chatWithOptions(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                inv.getArgument(3), inv.getArgument(4)));
        org.springframework.test.util.ReflectionTestUtils.setField(hook, "sideCallClient", client);

        PromptHook cfg = new PromptHook("e2e 全绿", null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            provider, ProviderConfig.empty(), DEFAULT_FAST_MODEL, List.of(), null);
        return hook.exec(cfg, HOOK_NAME, hookEvent, "{}", ctx, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 正向：impossible
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ok:false + impossible:true → outcome=SUCCESS + impossible=TRUE + stopReason=reason")
    void impossible_successWithImpossible() {
        HookResult r = exec("{\"ok\": false, \"impossible\": true, \"reason\": \"测试环境缺少 REDIS_URL，容器起不来\"}");

        assertThat(r.outcome())
            .as("CC v2.1.212：impossible → success（交还控制权），不是 blocking")
            .isEqualTo(HookOutcome.SUCCESS);
        assertThat(r.impossible()).isEqualTo(Boolean.TRUE);
        assertThat(r.stopReason()).isEqualTo("测试环境缺少 REDIS_URL，容器起不来");
        assertThat(r.blockingError())
            .as("impossible 分支不产生 blockingError（否则会继续 block 下去）")
            .isNull();
        assertThat(r.preventContinuation()).isFalse();
    }

    @Test
    @DisplayName("impossible 分支仍带 hook_success attachment（供审计）")
    void impossible_hasHookSuccessAttachment() {
        HookResult r = exec("{\"ok\": false, \"impossible\": true, \"reason\": \"r\"}");
        assertThat(r.message()).isNotNull();
        assertThat(((com.nexusai.application.agent.attachment.AttachmentMessageDto) r.message()).type())
            .isEqualTo("hook_success");
    }

    // ════════════════════════════════════════════════════════════════════
    // 负向对照：普通 ok:false 绝不能被当成 impossible
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 负向对照：ok:false（无 impossible）→ 仍是 BLOCKING，impossible 为 null")
    void notMet_staysBlocking() {
        HookResult r = exec("{\"ok\": false, \"reason\": \"还没跑到 e2e\"}");

        assertThat(r.outcome())
            .as("普通『未满足』必须继续 block，绝不能被误判成 impossible")
            .isEqualTo(HookOutcome.BLOCKING);
        assertThat(r.impossible()).isNull();
        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().blockingError())
            .isEqualTo("Prompt hook condition was not met: 还没跑到 e2e");
    }

    @Test
    @DisplayName("⛔ 负向对照：ok:false + impossible:false → 仍是 BLOCKING")
    void explicitFalse_staysBlocking() {
        HookResult r = exec("{\"ok\": false, \"impossible\": false, \"reason\": \"r\"}");

        assertThat(r.outcome()).isEqualTo(HookOutcome.BLOCKING);
        assertThat(r.impossible()).isNull();
    }

    @Test
    @DisplayName("ok:true → SUCCESS，impossible 为 null（普通 prompt hook 不受影响）")
    void met_successNoImpossible() {
        HookResult r = exec("{\"ok\": true, \"reason\": \"e2e 18/18 通过\"}");

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        assertThat(r.impossible()).isNull();
        assertThat(r.stopReason()).as("ok:true 时 stopReason 沿用旧语义（null）").isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // schema 校验
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("impossible 非布尔 → non_blocking_error（schema 校验拒绝）")
    void impossibleNonBoolean_schemaRejected() {
        HookResult r = exec("{\"ok\": false, \"impossible\": \"yes\", \"reason\": \"r\"}");

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        assertThat(((com.nexusai.application.agent.attachment.AttachmentMessageDto) r.message()).content())
            .contains("Schema validation failed");
    }

    @Test
    @DisplayName("ok:true + impossible:true → 仍是 SUCCESS，impossible 记为 null（ok=true 时 impossible 无意义）")
    void metWithImpossibleField_staysSuccess() {
        // CC 语义：impossible 只在 ok=false 时有意义。ok=true 时按普通成功处理。
        HookResult r = exec("{\"ok\": true, \"impossible\": true, \"reason\": \"r\"}");

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
    }
}
