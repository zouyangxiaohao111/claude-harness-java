package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.classifier.YoloClassifier;
import com.nexusai.application.agent.permission.classifier.YoloClassifierResult;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [IMP-SUB-25 返工 R4] handoff 安全分类意图测试 · 对齐 CC
 * {@code classifyHandoffIfNeeded}（agentToolUtils.ts:389-481）。
 *
 * <p>WHY（规则九 验证意图）：handoff 分类是<b>安全机制</b>——子 Agent 交还控制权给父 Agent 前，
 * auto-mode 分类器复核子 Agent 工作终态，命中危险输出时父 Agent 必须收到 SECURITY WARNING /
 * 分类器不可用验证提示。若该机制失效（mock 不注入、门控错位、警告不前置），父 Agent 会在
 * 不知情下直接采信子 Agent 的危险输出 —— 这是安全链路缺口，测试必须证明每条门控与警告
 * 语义（blocked→SECURITY WARNING / unavailable+blocked→NOTE / mode!=AUTO→跳过 /
 * classifier null|!isAvailable→跳过 / feature 关闭→跳过），而非仅断言行为。
 *
 * <p>classifyHandoffIfNeeded 为包可见测试 seam（Pattern #14，与 countToolUses /
 * extractUsageFromMessages 同模式）；mock YoloClassifier 经 {@code setYoloClassifier} 注入
 * （验证 L1 接线归零后 handoff 分类真实可达）。
 */
@DisplayName("[IMP-SUB-25] SubagentExecutor.handoff 安全分类意图测试")
class SubagentExecutorHandoffClassifierTest {

    private static final String SESSION = UUID.randomUUID().toString();

    /** R32-b14 17 参兼容构造器：只关心 role + content（user 文本 → 转录非空，过门 3）。 */
    private static ChatMessageDto userMsg(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, Role.user, null, content, null,
            null, null, null, null, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** R32-b14 17 参兼容构造器：assistant 消息（过 sync 错误恢复 hasAssistantMessages 门）。 */
    private static ChatMessageDto assistantMsg(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, Role.assistant, null, content, null,
            null, null, null, null, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private SubagentExecutor newExecutor() {
        // 无 Spring 依赖的最小构造（对齐 SubagentExecutorAdditionalAgentsTest 既有构造模式）
        return new SubagentExecutor(
            new com.nexusai.application.agent.tool.ToolRegistry(),
            null, null, null, null,
            "gpt-4", "system", null);
    }

    /** 装配一个 isAvailable=true + classifyTextAction 返回给定结果的 mock 分类器。 */
    private YoloClassifier availableClassifier(YoloClassifierResult result) {
        YoloClassifier classifier = mock(YoloClassifier.class);
        when(classifier.isAvailable()).thenReturn(true);
        when(classifier.classifyTextAction(any(), anyList(), any()))
            .thenReturn(CompletableFuture.completedFuture(result));
        return classifier;
    }

    @Test
    @DisplayName("blocked → SECURITY WARNING 且前置到最终结论文本（父 Agent 必须看到危险输出）")
    void blocked_returnsSecurityWarning_prependedToSummary() {
        // WHY: CC agentToolUtils.ts:472-476 —— 分类器标记子 Agent 输出 → SECURITY WARNING + reason。
        //   父 Agent 若收不到该警告，会直接采信子 Agent 的危险输出（安全链路缺口）。
        SubagentExecutor executor = newExecutor();
        YoloClassifierResult blocked = YoloClassifierResult.blocked(
            "rm -rf / is destructive", "claude-sonnet", 1);
        executor.setYoloClassifier(availableClassifier(blocked));

        String warning = executor.classifyHandoffIfNeeded(
            List.of(userMsg("sub-agent finished work")), PermissionMode.AUTO,
            "general-purpose", 1, null);

        assertThat(warning)
            .as("blocked 必须返回 SECURITY WARNING 且透传 reason（CC :476 原文语义）")
            .isNotNull()
            .contains("SECURITY WARNING")
            .contains("rm -rf / is destructive");

        // WHY（前置语义）：CC AgentTool.tsx:1246-1251 content 前置 —— 警告必须在子 Agent 结论之前
        String summary = "sub-agent's final result text";
        String prepended = SubagentExecutor.prependHandoffWarning(warning, summary);
        assertThat(prepended)
            .as("SECURITY WARNING 必须前置到最终结论文本（父 Agent 先见警告再见结论）")
            .startsWith("SECURITY WARNING")
            .endsWith(summary)
            .contains("\n\n" + summary);
    }

    @Test
    @DisplayName("unavailable+blocked → HANDOFF_UNAVAILABLE_NOTE（放行但附验证提示）")
    void unavailableAndBlocked_returnsUnavailableNote() {
        // WHY: CC agentToolUtils.ts:461-470 —— 分类器不可用时仍传播子 Agent 结果，但附警告让父 Agent
        //   人工核验（"classifier unavailable, still propagate results but with a warning"）。
        SubagentExecutor executor = newExecutor();
        YoloClassifierResult unavailable = YoloClassifierResult.unavailable(
            "Classifier unavailable - blocking for safety", "claude-sonnet", 1);
        executor.setYoloClassifier(availableClassifier(unavailable));

        String warning = executor.classifyHandoffIfNeeded(
            List.of(userMsg("sub-agent finished work")), PermissionMode.AUTO,
            "general-purpose", 1, null);

        assertThat(warning)
            .as("unavailable 命中 shouldBlock 时必须返回验证提示（CC :469 原文）")
            .isNotNull()
            .contains("Note: The safety classifier was unavailable when reviewing this sub-agent's work");
    }

    @Test
    @DisplayName("mode != AUTO → 跳过且不调用分类器（门 2）")
    void nonAutoMode_skipsWithoutInvokingClassifier() {
        // WHY: CC agentToolUtils.ts:405 —— toolPermissionContext.mode !== 'auto' 早返。
        //   非 auto 模式下分类器不复核 handoff（auto 专属机制）；不得触发 LLM 调用。
        SubagentExecutor executor = newExecutor();
        YoloClassifier classifier = mock(YoloClassifier.class);
        executor.setYoloClassifier(classifier); // 分类器已接线，仍须跳过

        String warning = executor.classifyHandoffIfNeeded(
            List.of(userMsg("sub-agent finished work")), PermissionMode.DEFAULT,
            "general-purpose", 1, null);

        assertThat(warning).as("mode=DEFAULT 必须跳过").isNull();
        verify(classifier, never()).classifyTextAction(any(), anyList(), any());
    }

    @Test
    @DisplayName("classifier null → 跳过（门 4 未接线兜底）")
    void nullClassifier_skips() {
        // WHY: 分类器未注入（yoloClassifier==null）时 handoff 必须跳过而非抛异常——
        //   对齐 PermissionPipeline:394 分类器不可用跳过约定；L1 接线归零前该路径是安全兜底。
        SubagentExecutor executor = newExecutor(); // 不注入 setYoloClassifier

        String warning = executor.classifyHandoffIfNeeded(
            List.of(userMsg("sub-agent finished work")), PermissionMode.AUTO,
            "general-purpose", 1, null);

        assertThat(warning).as("分类器 null 必须跳过且不抛异常").isNull();
    }

    @Test
    @DisplayName("classifier !isAvailable → 跳过（门 4）")
    void unavailableClassifier_skips() {
        // WHY: isAvailable()==false（模型未配置/provider 不可用）→ handoff 复核不可行，跳过
        //   而非 fail loud（对齐 PermissionPipeline:394 约定）。
        SubagentExecutor executor = newExecutor();
        YoloClassifier classifier = mock(YoloClassifier.class);
        when(classifier.isAvailable()).thenReturn(false);
        executor.setYoloClassifier(classifier);

        String warning = executor.classifyHandoffIfNeeded(
            List.of(userMsg("sub-agent finished work")), PermissionMode.AUTO,
            "general-purpose", 1, null);

        assertThat(warning).as("分类器不可用必须跳过").isNull();
        verify(classifier, never()).classifyTextAction(any(), anyList(), any());
    }

    @Test
    @DisplayName("transcriptClassifierEnabled=false → 跳过（门 1 feature flag）")
    void featureFlagOff_skips() {
        // WHY: CC agentToolUtils.ts:404 —— feature('TRANSCRIPT_CLASSIFIER') 关闭时整个
        //   handoff 分类链路早返（功能门控，非异常路径）。关闭时即使分类器已接线也不得调用。
        SubagentExecutor executor = newExecutor();
        executor.setYoloClassifier(availableClassifier(YoloClassifierResult.blocked(
            "should not be reached", "claude-sonnet", 1)));
        executor.setTranscriptClassifierEnabled(false);

        String warning = executor.classifyHandoffIfNeeded(
            List.of(userMsg("sub-agent finished work")), PermissionMode.AUTO,
            "general-purpose", 1, null);

        assertThat(warning).as("TRANSCRIPT_CLASSIFIER 关闭必须跳过").isNull();
    }

    @Test
    @DisplayName("sync 错误恢复有 assistant 消息 → 分类并前置警告（CC AgentTool.tsx:1223-1252）")
    void syncErrorRecovery_withAssistantMessages_classifiesAndPrepends() {
        // WHY: CC AgentTool.tsx:1223-1252 —— syncAgentError 但已有 assistant 消息（子 Agent 异常中断
        //   的部分输出可能含危险动作）→ CC 仍运行 classifyHandoffIfNeeded 并前置警告。Java catch 错误
        //   恢复（返回 completed）若漏分类，父 Agent 会在不知情下采信子 Agent 的部分危险输出 —— 安全链路缺口。
        SubagentExecutor executor = newExecutor();
        YoloClassifierResult blocked = YoloClassifierResult.blocked(
            "dangerous partial action", "claude-sonnet", 1);
        executor.setYoloClassifier(availableClassifier(blocked));

        String fallback = "partial sub-agent output after error";
        // userMsg 保证转录非空过门 3（assistant 纯文本消息 toCompact 返回 null，不产转录）
        String result = executor.applySyncErrorRecoveryClassification(
            List.of(assistantMsg("partial result text"), userMsg("sub-agent context")),
            PermissionMode.AUTO, "general-purpose", 1, null, fallback);

        assertThat(result)
            .as("sync 错误恢复有 assistant 消息时必须分类并前置 SECURITY WARNING（父 Agent 先见警告再见部分输出）")
            .startsWith("SECURITY WARNING")
            .contains("dangerous partial action")
            .endsWith(fallback)
            .contains("\n\n" + fallback);
    }

    @Test
    @DisplayName("sync 错误恢复无 assistant 消息 → 不分类（CC :1226-1228 无部分输出不复核）")
    void syncErrorRecovery_withoutAssistantMessages_skips() {
        // WHY: CC AgentTool.tsx:1226-1228 —— 无 assistant 消息（子 loop 无部分输出可复核）→ CC 重抛
        //   错误。Java 错误恢复（返回 completed）无产物可复核 → 不得调用分类器：该门防误报，无子 Agent
        //   产物时不得消耗 LLM 分类（且父 Agent 收到的是空结论，无需安全警告）。
        SubagentExecutor executor = newExecutor();
        YoloClassifier classifier = mock(YoloClassifier.class);
        when(classifier.isAvailable()).thenReturn(true);
        executor.setYoloClassifier(classifier);

        String fallback = "no assistant output before error";
        String result = executor.applySyncErrorRecoveryClassification(
            List.of(userMsg("sub-agent prompt")),
            PermissionMode.AUTO, "general-purpose", 1, null, fallback);

        assertThat(result).as("无 assistant 消息时必须原样返回结论文本").isEqualTo(fallback);
        verify(classifier, never()).classifyTextAction(any(), anyList(), any());
    }
}
