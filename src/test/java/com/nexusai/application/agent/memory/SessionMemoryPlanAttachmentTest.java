package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.compact.CompactionResult;
import com.nexusai.application.agent.compact.PlanProviderImpl;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-CM-04（OPD-CM3-15/D01）· SM 压缩结果 plan_file_reference 附件注入。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC {@code trySessionMemoryCompaction}
 * 结果构造 `const planAttachment = createPlanAttachmentIfNeeded(agentId);
 * const attachments = planAttachment ? [planAttachment] : []`
 * （sessionMemoryCompact.ts:484-485）——SM 压缩后模型能否保有 plan 上下文取决于
 * CompactionResult.attachments 是否携带 plan_file_reference。传统路径
 * CompactConversation:303 populatePlanAttachment 经 typed state.attachments() 通道注入；
 * SM 路径无 registry 访问 → 直接注入 CompactionResult.attachments（数据源
 * PlanProvider/PlanProviderImpl 已存在，参照 PostCompactAttachmentRestorer 渲染）。有 plan 文件
 * → attachments 含 plan_file_reference（携带磁盘全文）；无 plan 文件 → attachments 空（CC
 * createPlanAttachmentIfNeeded 返回 null → attachments=[]）。
 */
@DisplayName("[IMP-CM-04] SM 压缩结果 plan_file_reference 附件注入（sessionMemoryCompact.ts:484-485）")
class SessionMemoryPlanAttachmentTest {

    @TempDir
    Path baseDir;

    private static List<ChatMessageDto> messages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ChatMessageDto("u" + i, null, Role.user, "user", "hi", null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of(), null, false, false));
        }
        return list;
    }

    private SessionMemoryService newService(String sessionId, PlanProviderImpl provider) throws Exception {
        Path dir = baseDir.resolve(sessionId).resolve("session-memory");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("summary.md"), "# Learnings\nsome real learning content\n");
        SessionMemoryService sm = new SessionMemoryService(baseDir);
        sm.setSmSessionMemoryEnabled(true);
        sm.setSmCompactEnabled(true);
        if (provider != null) {
            sm.setPlanProvider(provider);
        }
        // [sm-cursor-sessionize] 清本会话游标（trySessionMemoryCompaction 读同 sessionId 键）
        SessionMemoryService.setLastSummarizedMessageId(sessionId, null);
        return sm;
    }

    @Test
    @DisplayName("有 plan 文件 → SM 结果 attachments 含 plan_file_reference（携带磁盘全文 + 路径）")
    void smResult_injectsPlanFileReference_whenPlanExists(@TempDir Path plansDir) throws Exception {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String planContent = "disk plan full text for IMP-CM-04";
        Files.createDirectories(plansDir);
        Files.writeString(plansDir.resolve(sessionId + ".md"), planContent, StandardCharsets.UTF_8);

        SessionMemoryService sm = newService(sessionId.toString(),
            new PlanProviderImpl(sessionId, plansDir.toString()));

        CompactionResult r = sm.trySessionMemoryCompaction(
            messages(15), sessionId.toString(), null, Integer.MAX_VALUE);

        assertThat(r).isNotNull();
        assertThat(r.attachments())
            .as("SM 结果 attachments 必须注入 plan_file_reference（CC sessionMemoryCompact.ts:484-485）")
            .hasSize(1);
        ChatMessageDto planAttachment = r.attachments().get(0);
        assertThat(planAttachment.author()).isEqualTo("attachment");
        assertThat(planAttachment.subtype()).isEqualTo("plan_file_reference");
        assertThat(planAttachment.content())
            .as("附件载荷须携带磁盘 plan 全文 + plan 文件路径（CC compact.ts:1481-1485）")
            .contains(planContent)
            .contains(sessionId + ".md");
    }

    @Test
    @DisplayName("无 plan 文件 → SM 结果 attachments 空（CC createPlanAttachmentIfNeeded null → []）")
    void smResult_noPlanAttachment_whenNoPlanFile(@TempDir Path plansDir) throws Exception {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Files.createDirectories(plansDir); // 空 plans 目录 → getPlan ENOENT → null

        SessionMemoryService sm = newService(sessionId.toString(),
            new PlanProviderImpl(sessionId, plansDir.toString()));

        CompactionResult r = sm.trySessionMemoryCompaction(
            messages(15), sessionId.toString(), null, Integer.MAX_VALUE);

        assertThat(r).isNotNull();
        assertThat(r.attachments())
            .as("无 plan 文件 → 结果 attachments 为空（对齐 CC createPlanAttachmentIfNeeded 返回 null）")
            .isEmpty();
    }

    @Test
    @DisplayName("planProvider 未注入 + sessionId 非 UUID → 回落不可得 → attachments 空（不抛错）")
    void smResult_noPlanAttachment_whenNoProviderResolvable() throws Exception {
        String sessionId = "s1"; // 非 UUID → 无法回落构造 PlanProviderImpl
        Path dir = baseDir.resolve(sessionId).resolve("session-memory");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("summary.md"), "# Learnings\nsome real learning content\n");

        SessionMemoryService sm = new SessionMemoryService(baseDir);
        sm.setSmSessionMemoryEnabled(true);
        sm.setSmCompactEnabled(true);
        SessionMemoryService.setLastSummarizedMessageId(sessionId, null);

        CompactionResult r = sm.trySessionMemoryCompaction(
            messages(15), sessionId, null, Integer.MAX_VALUE);

        assertThat(r).isNotNull();
        assertThat(r.attachments())
            .as("planProvider 未注入且 sessionId 非 UUID → 跳过 plan 附件注入（不中断压缩成功路径）")
            .isEmpty();
    }
}
