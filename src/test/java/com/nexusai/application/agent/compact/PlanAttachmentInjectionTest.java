package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-6 · plan_file_reference / plan_mode 附件重建注入单测 · 对齐 CC compact.ts:545-555
 * （createPlanAttachmentIfNeeded → push，plan 之后 createPlanModeAttachmentIfNeeded → push）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 压缩后模型能否保有 plan 上下文取决于
 * populatePlanAttachment / populatePlanModeAttachment 是否正确注入：有 plan 文件 → 经 typed 工厂
 * {@link AttachmentMessageDto#planFileReference} 写入 state.attachments()（由
 * maybeInjectHookAttachments 渲染为 system-reminder 携带磁盘全文）；plan 模式 → 产出
 * subtype=plan_mode 且带真实 planFilePath + planExists；无 plan 文件 / 非 plan 模式 → 降级不注入
 * （不抛错、不中断压缩成功路径）。本测试用临时 plans 目录验证该契约。
 *
 * <p><b>[WF6 R2]</b>: plan_file_reference 收敛到 typed 工厂（state.attachments()），不再手拼
 * ChatMessageDto 双轨——本测试是 typed 工厂 {@code planFileReference} 获得真实调用方的对抗性证明。
 */
class PlanAttachmentInjectionTest {

    /** 构造带 appState.toolPermissionContext 的 ToolUseContext（对齐 EnterPlanModeExitPlanModeV2Test 模式）。 */
    private static ToolUseContext planModeToolUseContext(String sessionId, PermissionMode mode) {
        Map<String, Object> appState = new ConcurrentHashMap<>();
        appState.put("toolPermissionContext", new ToolPermissionContext(
            mode, Map.of(), Map.of(), Map.of(), Map.of(), false, false, Map.of(), false, false, null));
        Function<Map<String, Object>, Map<String, Object>> get = ignored -> appState;
        Consumer<Function<Map<String, Object>, Map<String, Object>>> set = updater -> {
            Map<String, Object> next = updater.apply(appState);
            appState.clear();
            appState.putAll(next);
        };
        return ToolUseContext.of(
            UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", Path.of("."),
            null, null, null, get, set, m -> {}, s -> {});
    }

    /** 构造并注册主会话 AgentState + registry（populatePlanAttachment 的 typed 写入面）。 */
    private static AgentState registerState(SessionAgentStateRegistry registry, String sessionId) {
        AgentState state = new AgentState("test");
        registry.register(sessionId, state);
        return state;
    }

    @Test
    @DisplayName("populatePlanAttachment: 有 plan 文件 → typed 工厂写入 state.attachments()（content=磁盘全文）")
    void populatePlanAttachmentInjectsWhenFileExists(@TempDir Path tmp) throws IOException {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String content = "disk plan full text";
        Files.writeString(tmp.resolve(sessionId + ".md"), content, StandardCharsets.UTF_8);

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = registerState(registry, sessionId);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId(sessionId.toString())
            .setAgentId(null)
            .setPlanProvider(new PlanProviderImpl(sessionId, tmp.toString()));

        PostCompactAttachmentRestorer.populatePlanAttachment(registry, ctx);

        List<AttachmentMessageDto> attachments = state.attachments();
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).type()).isEqualTo("plan_file_reference");
        assertThat(attachments.get(0).plan()).isNotNull();
        assertThat(attachments.get(0).plan().planContent()).contains(content);
        assertThat(attachments.get(0).plan().planFilePath()).contains(sessionId + ".md");
    }

    @Test
    @DisplayName("populatePlanAttachment: 无 plan 文件 → 降级不注入（state.attachments() 保持空）")
    void populatePlanAttachmentDegradesWhenNoFile(@TempDir Path tmp) {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = registerState(registry, sessionId);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId(sessionId.toString())
            .setPlanProvider(new PlanProviderImpl(sessionId, tmp.toString()));

        PostCompactAttachmentRestorer.populatePlanAttachment(registry, ctx);

        assertThat(state.attachments()).isEmpty();
    }

    @Test
    @DisplayName("populatePlanAttachment: registry 未注册该会话 → 跳过不抛错")
    void populatePlanAttachmentSkipsWhenSessionNotRegistered(@TempDir Path tmp) throws IOException {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Files.writeString(tmp.resolve(sessionId + ".md"), "plan", StandardCharsets.UTF_8);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry(); // 空 registry
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId(sessionId.toString())
            .setPlanProvider(new PlanProviderImpl(sessionId, tmp.toString()));

        PostCompactAttachmentRestorer.populatePlanAttachment(registry, ctx);

        assertThat(ctx.getAdditionalPostCompactAttachments()).isEmpty();
    }

    @Test
    @DisplayName("populatePlanModeAttachment: plan 模式 + 有文件 → 注入 plan_mode（planExists=true + 真实路径）")
    void populatePlanModeAttachmentInjectsWhenInPlanModeAndFileExists(@TempDir Path tmp) throws IOException {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Files.writeString(tmp.resolve(sessionId + ".md"), "plan", StandardCharsets.UTF_8);

        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId(sessionId.toString())
            .setAgentId(null)
            .setPlanProvider(new PlanProviderImpl(sessionId, tmp.toString()))
            .setToolUseContext(planModeToolUseContext(sessionId, PermissionMode.PLAN));

        PostCompactAttachmentRestorer.populatePlanModeAttachment(ctx);

        List<ChatMessageDto> attachments = ctx.getAdditionalPostCompactAttachments();
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).subtype()).isEqualTo("plan_mode");
        assertThat(attachments.get(0).content()).contains("\"planExists\":true");
        assertThat(attachments.get(0).content()).contains(sessionId + ".md");
    }

    @Test
    @DisplayName("populatePlanModeAttachment: 非 plan 模式 → 跳过不注入")
    void populatePlanModeAttachmentSkipsWhenNotInPlanMode(@TempDir Path tmp) throws IOException {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Files.writeString(tmp.resolve(sessionId + ".md"), "plan", StandardCharsets.UTF_8);

        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId(sessionId.toString())
            .setPlanProvider(new PlanProviderImpl(sessionId, tmp.toString()))
            .setToolUseContext(planModeToolUseContext(sessionId, PermissionMode.DEFAULT));

        PostCompactAttachmentRestorer.populatePlanModeAttachment(ctx);

        assertThat(ctx.getAdditionalPostCompactAttachments()).isEmpty();
    }

    @Test
    @DisplayName("组合: plan_file_reference 走 typed state.attachments()，plan_mode 走 additionalPostCompactAttachments（双通道各归其位）")
    void injectsPlanReferenceTypedAndPlanModeChatMessage(@TempDir Path tmp) throws IOException {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Files.writeString(tmp.resolve(sessionId + ".md"), "plan", StandardCharsets.UTF_8);

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = registerState(registry, sessionId);
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId(sessionId.toString())
            .setPlanProvider(new PlanProviderImpl(sessionId, tmp.toString()))
            .setToolUseContext(planModeToolUseContext(sessionId, PermissionMode.PLAN));

        PostCompactAttachmentRestorer.populatePlanAttachment(registry, ctx);
        PostCompactAttachmentRestorer.populatePlanModeAttachment(ctx);

        // plan_file_reference → typed state.attachments()
        assertThat(state.attachments()).extracting(AttachmentMessageDto::type)
            .containsExactly("plan_file_reference");
        // plan_mode → ChatMessageDto additionalPostCompactAttachments
        assertThat(ctx.getAdditionalPostCompactAttachments()).extracting(ChatMessageDto::subtype)
            .containsExactly("plan_mode");
    }
}
