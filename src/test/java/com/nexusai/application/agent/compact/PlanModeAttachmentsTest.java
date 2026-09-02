package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-6 RC-2 · tool 轮 plan_mode 附件生产器单测 · 对齐 CC attachments.ts:1186-1273
 * （getPlanModeAttachments 节流/reentry/full-sparse + getPlanModeExitAttachment 一次性）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 读侧依赖写侧 —— 模型在 plan 模式只有拿到
 * 真实 planFilePath + planExists 才会写 plan 文件（→ getPlan 非 null → plan_file_reference 注入）。
 * 本测试验证生产器在 plan 模式注入 plan_mode（携带 planFilePath + planExists，即使文件不存在
 * path 仍生成）、节流 5 轮、reentry/exit 一次性语义。
 */
class PlanModeAttachmentsTest {

    /** 最小 user 消息（节流测试仅需 messages 非空）。 */
    private static ChatMessageDto userMsg(String content) {
        return new ChatMessageDto("m1", null, Role.user, null, content,
            null, List.of(), null, null, null, null, null, null, null, null,
            List.of(), List.of(), null, false, false);
    }

    @Test
    @DisplayName("非 plan 模式 → 返回空列表")
    void nonPlanModeReturnsEmpty(@TempDir Path tmp) {
        PlanProvider provider = new PlanProviderImpl("sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), tmp.toString());

        List<AttachmentMessageDto> out = PlanModeAttachments.getPlanModeAttachments(
            List.of(), List.of(), PermissionMode.DEFAULT, null, provider,
            new PlanModeAttachments.PlanModeFlags(), 0);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("plan 模式无文件 → 注入 plan_mode（reminderType=full, planExists=false, 真实 planFilePath）")
    void planModeNoFileInjectsPlanModeWithPathAndExistsFalse(@TempDir Path tmp) {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());

        List<AttachmentMessageDto> out = PlanModeAttachments.getPlanModeAttachments(
            List.of(), List.of(), PermissionMode.PLAN, null, provider,
            new PlanModeAttachments.PlanModeFlags(), 0);

        assertThat(out).hasSize(1);
        AttachmentMessageDto planMode = out.get(0);
        assertThat(planMode.type()).isEqualTo("plan_mode");
        assertThat(planMode.reminderType()).isEqualTo("full"); // count=0+1=1, %5==1 → full
        assertThat(planMode.planExists()).isFalse();
        assertThat(planMode.plan().planFilePath()).endsWith(sessionId + ".md");
    }

    @Test
    @DisplayName("plan 模式有文件 → planExists=true")
    void planModeWithFilePlanExistsTrue(@TempDir Path tmp) throws IOException {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Files.writeString(tmp.resolve(sessionId + ".md"), "plan", StandardCharsets.UTF_8);
        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());

        List<AttachmentMessageDto> out = PlanModeAttachments.getPlanModeAttachments(
            List.of(), List.of(), PermissionMode.PLAN, null, provider,
            new PlanModeAttachments.PlanModeFlags(), 0);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).planExists()).isTrue();
    }

    @Test
    @DisplayName("节流：距上次 < 5 轮 → 返回空（首轮恒注入）")
    void throttleWithinThresholdReturnsEmpty(@TempDir Path tmp) {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());
        PlanModeAttachments.PlanModeFlags flags = new PlanModeAttachments.PlanModeFlags();
        List<ChatMessageDto> messages = List.of(userMsg("hi"));

        // 首轮注入
        List<AttachmentMessageDto> first = PlanModeAttachments.getPlanModeAttachments(
            messages, List.of(), PermissionMode.PLAN, null, provider, flags, 0);
        assertThat(first).hasSize(1);
        assertThat(flags.lastPlanModeAttachmentTurn()).isZero();

        // 距上次 1 轮 < 5 → 空
        List<AttachmentMessageDto> second = PlanModeAttachments.getPlanModeAttachments(
            messages, List.of(), PermissionMode.PLAN, null, provider, flags, 1);
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("reentry：hasExitedPlanModeInSession 且文件存在 → 注入 plan_mode_reentry + plan_mode 并清 flag")
    void reentryWhenExitedAndFileExists(@TempDir Path tmp) throws IOException {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Files.writeString(tmp.resolve(sessionId + ".md"), "plan", StandardCharsets.UTF_8);
        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());
        PlanModeAttachments.PlanModeFlags flags = new PlanModeAttachments.PlanModeFlags();
        flags.setHasExitedPlanModeInSession(true);

        List<AttachmentMessageDto> out = PlanModeAttachments.getPlanModeAttachments(
            List.of(), List.of(), PermissionMode.PLAN, null, provider, flags, 0);

        assertThat(out).extracting(AttachmentMessageDto::type)
            .containsExactly("plan_mode_reentry", "plan_mode");
        assertThat(flags.hasExitedPlanModeInSession()).isFalse(); // 一次性 clear
    }

    @Test
    @DisplayName("exit：needsPlanModeExitAttachment 且非 plan → 注入 plan_mode_exit 并清 flag")
    void exitWhenNeedsExitAndNotPlan(@TempDir Path tmp) {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());
        PlanModeAttachments.PlanModeFlags flags = new PlanModeAttachments.PlanModeFlags();
        flags.setNeedsPlanModeExitAttachment(true);

        List<AttachmentMessageDto> out = PlanModeAttachments.getPlanModeExitAttachment(
            PermissionMode.DEFAULT, null, provider, flags);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo("plan_mode_exit");
        assertThat(flags.needsPlanModeExitAttachment()).isFalse(); // 一次性 clear
    }

    @Test
    @DisplayName("full/sparse 周期：连续 6 次注入 reminderType 在 full→sparse 间切换（attachmentCount % 5 == 1 → full）")
    void reminderTypeCyclesFullThenSparse(@TempDir Path tmp) {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());
        PlanModeAttachments.PlanModeFlags flags = new PlanModeAttachments.PlanModeFlags();
        // 同一持久化计数源：模拟 maybeInjectPlanModeAttachments 每轮 append 到 state.attachments()
        List<AttachmentMessageDto> persistentAttachments = new ArrayList<>();

        for (int i = 1; i <= 6; i++) {
            List<AttachmentMessageDto> produced = PlanModeAttachments.getPlanModeAttachments(
                List.of(), persistentAttachments, PermissionMode.PLAN, null, provider, flags, i);
            assertThat(produced).as("第 %d 次注入", i).hasSize(1);
            String reminderType = produced.get(0).reminderType();
            if (i % 5 == 1) {
                assertThat(reminderType).as("第 %d 次应为 full（count=%d %%5==1）", i, i)
                    .isEqualTo("full");
            } else {
                assertThat(reminderType).as("第 %d 次应为 sparse（count=%d）", i, i)
                    .isEqualTo("sparse");
            }
            persistentAttachments.addAll(produced);
        }
    }

    @Test
    @DisplayName("full/sparse 周期重置：plan_mode_exit 后重新计数（遇 exit 停）")
    void reminderTypeResetsAfterPlanModeExit(@TempDir Path tmp) {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());
        List<AttachmentMessageDto> persistentAttachments = new ArrayList<>();

        // 第 1 次 full
        List<AttachmentMessageDto> first = PlanModeAttachments.getPlanModeAttachments(
            List.of(), persistentAttachments, PermissionMode.PLAN, null, provider,
            new PlanModeAttachments.PlanModeFlags(), 1);
        assertThat(first.get(0).reminderType()).isEqualTo("full");
        persistentAttachments.addAll(first);

        // 第 2 次 sparse（count=2）
        List<AttachmentMessageDto> second = PlanModeAttachments.getPlanModeAttachments(
            List.of(), persistentAttachments, PermissionMode.PLAN, null, provider,
            new PlanModeAttachments.PlanModeFlags(), 2);
        assertThat(second.get(0).reminderType()).isEqualTo("sparse");
        persistentAttachments.addAll(second);

        // 注入一次 plan_mode_exit（遇 exit 计数停）→ 后续重新从 full 开始
        persistentAttachments.add(AttachmentMessageDto.planModeExit(provider.getPlanFilePath(null), false));

        List<AttachmentMessageDto> afterExit = PlanModeAttachments.getPlanModeAttachments(
            List.of(), persistentAttachments, PermissionMode.PLAN, null, provider,
            new PlanModeAttachments.PlanModeFlags(), 3);
        // countPlanModeAttachmentsSinceLastExit 反向扫描遇 exit 停 → count=0+1=1 → full
        assertThat(afterExit.get(0).reminderType()).isEqualTo("full");
    }

    @Test
    @DisplayName("exit：仍在 plan 模式 → 清 flag 返回空（防 plan_mode + plan_mode_exit 双发）")
    void exitStillInPlanModeClearsAndEmpty(@TempDir Path tmp) {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());
        PlanModeAttachments.PlanModeFlags flags = new PlanModeAttachments.PlanModeFlags();
        flags.setNeedsPlanModeExitAttachment(true);

        List<AttachmentMessageDto> out = PlanModeAttachments.getPlanModeExitAttachment(
            PermissionMode.PLAN, null, provider, flags);

        assertThat(out).isEmpty();
        assertThat(flags.needsPlanModeExitAttachment()).isFalse();
    }
}
