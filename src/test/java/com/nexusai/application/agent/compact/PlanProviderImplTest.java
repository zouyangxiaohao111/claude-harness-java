package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-6 · PlanProviderImpl 磁盘读盘/写盘/附件注入单测 · 对齐 CC plans.ts:119-146 +
 * compact.ts:1470-1486。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: PlanProvider 从「接口 + null 生产」改造为
 * 磁盘生产实现后，plan_file_reference / plan_mode 附件重建能否拿到磁盘真实路径取决于
 * getPlanFilePath/getPlan 的正确读盘契约——有文件返回全文、无文件降级 null（不抛）、
 * createPlanAttachmentIfNeeded 有文件产出 PlanRef(路径,全文) / 无文件 null。本测试用临时
 * plans 目录验证该契约（不污染用户真实 ClaudeConfigHomeDir/plans）。
 */
class PlanProviderImplTest {

    @Test
    @DisplayName("getPlan: 有 {slug}.md → 返回磁盘全文")
    void getPlanReturnsFullContentWhenFileExists(@TempDir Path tmp) throws IOException {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String content = "# My Plan\n\n- step 1\n- step 2\n";
        Files.writeString(tmp.resolve(sessionId + ".md"), content, StandardCharsets.UTF_8);

        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());

        assertThat(provider.getPlan(null)).isEqualTo(content);
    }

    @Test
    @DisplayName("getPlan: 无文件（ENOENT）→ 返回 null 不抛错")
    void getPlanReturnsNullWhenFileMissing(@TempDir Path tmp) {
        PlanProvider provider = new PlanProviderImpl("sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), tmp.toString());

        assertThat(provider.getPlan(null)).isNull();
    }

    @Test
    @DisplayName("getPlanFilePath: 主会话 null agentId → {sessionId}.md；子代理 → {sessionId}-agent-{agentId}.md")
    void getPlanFilePathMainVsSubagent(@TempDir Path tmp) {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID agentId = UUID.randomUUID();
        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());

        assertThat(provider.getPlanFilePath(null))
            .isEqualTo(tmp.resolve(sessionId + ".md").toString());
        assertThat(provider.getPlanFilePath(agentId))
            .isEqualTo(tmp.resolve(sessionId + "-agent-" + agentId + ".md").toString());
    }

    @Test
    @DisplayName("createPlanAttachmentIfNeeded: 无文件 → null（降级不注入）")
    void createPlanAttachmentIfNeededReturnsNullWhenNoFile(@TempDir Path tmp) {
        PlanProvider provider = new PlanProviderImpl("sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), tmp.toString());

        assertThat(provider.createPlanAttachmentIfNeeded(null)).isNull();
    }

    @Test
    @DisplayName("createPlanAttachmentIfNeeded: 有文件 → PlanRef(路径, 磁盘全文)")
    void createPlanAttachmentIfNeededReturnsPlanRefWithPathAndContent(@TempDir Path tmp) throws IOException {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String content = "plan full text";
        Files.writeString(tmp.resolve(sessionId + ".md"), content, StandardCharsets.UTF_8);

        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());
        AttachmentMessageDto.PlanRef ref = provider.createPlanAttachmentIfNeeded(null);

        assertThat(ref).isNotNull();
        assertThat(ref.planFilePath()).isEqualTo(tmp.resolve(sessionId + ".md").toString());
        assertThat(ref.planContent()).isEqualTo(content);
    }

    @Test
    @DisplayName("getPlan: 子代理读 {slug}-agent-{agentId}.md（与主会话隔离）")
    void getPlanReadsSubagentFile(@TempDir Path tmp) throws IOException {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID agentId = UUID.randomUUID();
        Files.writeString(tmp.resolve(sessionId + "-agent-" + agentId + ".md"),
            "sub plan", StandardCharsets.UTF_8);

        PlanProvider provider = new PlanProviderImpl(sessionId, tmp.toString());

        assertThat(provider.getPlan(agentId)).isEqualTo("sub plan");
        assertThat(provider.getPlan(null)).as("主会话文件不存在 → null").isNull();
    }
}
