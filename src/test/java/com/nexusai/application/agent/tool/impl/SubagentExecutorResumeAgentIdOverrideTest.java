package com.nexusai.application.agent.tool.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentMessage;
import com.nexusai.application.agent.subagent.createSubagentContext;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RES-R2 · resume 二次续跑 agentId override 测试（对齐 CC resumeAgent.ts:236-244 override.agentId）。
 *
 * <p>规则九（测试验证意图）：resume 续跑的转录键必须是<b>原 agentId</b>（CC registerAsyncAgent
 * 复用原键 resumeAgent.ts:198-205 + runAgent override.agentId :236-244），否则二次 resume 读到的
 * 是 pre-resume transcript（新键空 transcript），被 kill 的异步 agent 恢复成空壳——前端
 * POST /builtins/resume/execute 连续调用同一 agent 时上下文丢失。
 *
 * <p>测试方式（对齐 SubagentToolForkTest 日志拦截模式）：Step 5 创建的子上下文 agentId 经
 * {@code Step 1-5: agentId=...} 数据流日志暴露。驱动 {@code execute()} 走 resume 分支，Step 20
 * （contextFactory 未注入）抛 IllegalStateException 前，Step 1-5 日志已含最终 agentId。
 */
@DisplayName("[RES-R2] resume 二次续跑 agentId override（续写原键）")
class SubagentExecutorResumeAgentIdOverrideTest {

    /** 固定 resume 原 agentId —— resume 续跑必须续写此键（CC resumeAgent.ts:240 override.agentId）。 */
    private static final UUID ORIGINAL_AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    // ────────────────────────────────────────────────────────────────────────
    // 1. resume 路径：agentIdOverride 生效（续写原键）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resume 路径: ForkPathParams.agentIdOverride 非空 → Step 5 子上下文 agentId = 原 agentId（续写原键）")
    void resumePath_agentIdOverride_appliesToSubagentContext() {
        // GIVEN: resume 分支 ForkPathParams —— resumedMessages 非空（resume 专属字段）+ agentIdOverride = 原键
        SubagentExecutor.ForkPathParams resumeForkParams = new SubagentExecutor.ForkPathParams(
            null, null, "", null,
            List.of(AgentMessage.of("user", "pre-resume 上下文")),
            null,
            ORIGINAL_AGENT_ID,
            null);
        // 拦截 SubagentExecutor 日志 —— 生产路径观测通道（Step 1-5 agentId 数据流日志）
        Logger logger = (Logger) LoggerFactory.getLogger(SubagentExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.setLevel(Level.DEBUG);

            // WHEN: 经生产路径驱动 SubagentExecutor.execute(prompt, "general-purpose", null, resumeForkParams)
            //   contextFactory 未注入 → Step 20 抛 IllegalStateException；Step 1-5/18/19 在抛出前已完成
            SubagentExecutor executor = new SubagentExecutor(
                ToolRegistry.from(List.of()), null, null, null, null, "model", "system-prompt");
            assertThatThrownBy(() -> executor.execute("continue", "general-purpose", null, resumeForkParams))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contextFactory not injected");

            // THEN: Step 1-5 日志暴露的子上下文 agentId == 原 agentId（CC resumeAgent.ts:240 override.agentId）
            List<String> logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(logs)
                .as("resume 续跑必须续写原键: Step 1-5 agentId = 原 agentId " + ORIGINAL_AGENT_ID)
                .anyMatch(m -> m.contains("Step 1-5: agentId=" + ORIGINAL_AGENT_ID));
            // 反向断言：不得出现"新键"（任何非原 agentId 的 UUID）
            assertThat(logs.stream().filter(m -> m.contains("Step 1-5: agentId="))
                .noneMatch(m -> m.contains("Step 1-5: agentId=" + ORIGINAL_AGENT_ID) == false))
                .as("不得出现新键 agentId")
                .isTrue();
            // [REQ-R2-3] Step 18 writeMetadata 写点也复用 override 后的 agentId（transcript/metadata 同键）
            assertThat(logs)
                .as("Step 18 元数据写点必须复用原键 (CC resumeAgent.ts:240 override.agentId 续写原 transcript)")
                .anyMatch(m -> m.contains("Step 18: 已写入 agent 元数据 agent=" + ORIGINAL_AGENT_ID));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. 非 resume 路径：agentIdOverride null → 仍 generate 新 UUID（无回归）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("非 resume 路径: ForkPathParams.agentIdOverride null → Step 5 仍 generate 新 UUID（无回归）")
    void nonResumePath_noAgentIdOverride_stillGeneratesNewUuid() {
        // GIVEN: 标准 fork 分支 ForkPathParams —— agentIdOverride 未提供（null，4 参兼容构造）
        SubagentExecutor.ForkPathParams forkParams = new SubagentExecutor.ForkPathParams(
            null, List.of(), "parent-system-prompt", null);
        Logger logger = (Logger) LoggerFactory.getLogger(SubagentExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.setLevel(Level.INFO);

            // WHEN: 经生产路径驱动 execute —— 非 resume（resumedMessages=null），fork 子 agent
            SubagentExecutor executor = new SubagentExecutor(
                ToolRegistry.from(List.of()), null, null, null, null, "model", "system-prompt");
            assertThatThrownBy(() -> executor.execute("directive", "fork", null, forkParams))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contextFactory not injected");

            // THEN: Step 1-5 日志暴露的子上下文 agentId 是"新 UUID"（非原 agentId —— 无 override 通道）
            List<String> logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(logs)
                .as("非 resume 路径必须仍 generate 新 UUID（不能是 null）")
                .anyMatch(m -> m.contains("Step 1-5: agentId=")
                    && !m.contains("Step 1-5: agentId=null"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. 父 TUC 分支（RES-R2-1 聚焦 · effectiveParentTuc != null，SubagentExecutor:914-958）
    //    agentIdOverride 透传 · CC runAgent.ts:347 override?.agentId ?? createAgentId()
    // ────────────────────────────────────────────────────────────────────────

    /** 父 TUC 会话固定 UUID —— 父 TUC 分支 hasParent=true 判别 + 子 ctx 继承父 sessionId。 */
    private static final UUID PARENT_AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String PARENT_SESSION_ID = "00000000-0000-0000-0000-0000000000b2";

    /** 最小父 ToolUseContext（4 参紧凑构造）—— 经 8 参 SubagentExecutor 构造器注入驱动父 TUC 分支。 */
    private static ToolUseContext parentTuc() {
        return new ToolUseContext(PARENT_AGENT_ID, PARENT_SESSION_ID, PermissionMode.DEFAULT, Map.of());
    }

    /**
     * [RES-R2-1] 父 TUC 分支 + resume → 续写原键。
     *
     * <p>WHY（规则九）：现有 standalone 分支用例（:43-86）只证明"resume override 在 standalone 路径生效"；
     * 父 TUC 分支（effectiveParentTuc != null → create(parent, overrides)，CC forkedAgent.ts:345-462）与
     * standalone 共享 {@code resumeAgentIdOverride} 透传机制，但无直接聚焦用例——若未来有人把 override
     * 只接进 standalone 分支，本用例必须立即变红。分支判别用 createSubagentContext 日志 hasParent=true
     * （无父 TUC 驱动时该分支不执行 → hasParent=false，断言必失败，即 RED 语义）。
     */
    @Test
    @DisplayName("父 TUC 分支: resume agentIdOverride 续写原键 (CC runAgent.ts:347 override 优先级独立于分支)")
    void parentTucBranch_resumeAgentIdOverride_appliesToSubagentContext() {
        // GIVEN: resume 分支 ForkPathParams —— resumedMessages 非空 + agentIdOverride = 原键
        SubagentExecutor.ForkPathParams resumeForkParams = new SubagentExecutor.ForkPathParams(
            null, null, "", null,
            List.of(AgentMessage.of("user", "pre-resume 上下文")),
            null,
            ORIGINAL_AGENT_ID,
            null);
        Logger executorLogger = (Logger) LoggerFactory.getLogger(SubagentExecutor.class);
        Logger createLogger = (Logger) LoggerFactory.getLogger(createSubagentContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        executorLogger.addAppender(appender);
        createLogger.addAppender(appender);
        try {
            executorLogger.setLevel(Level.DEBUG);
            createLogger.setLevel(Level.INFO);

            // WHEN: 8 参构造器注入父 TUC → effectiveParentTuc != null → Step 5 走父 TUC 分支
            //   contextFactory 未注入 → Step 20 抛 IllegalStateException；Step 1-5 日志在抛出前已完成
            SubagentExecutor executor = new SubagentExecutor(
                ToolRegistry.from(List.of()), null, null, null, null, "model", "system-prompt", parentTuc());
            assertThatThrownBy(() -> executor.execute("continue", "general-purpose", null, resumeForkParams))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contextFactory not injected");

            List<String> logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            // 分支判别: 必须走父 TUC 分支 (hasParent=true) —— 若误用 standalone 构造, 本断言立即失败
            assertThat(logs)
                .as("必须走父 TUC 分支: createSubagentContext hasParent=true "
                    + "(否则本用例只覆盖 standalone 分支, 父 TUC 分支透传仍是盲区)")
                .anyMatch(m -> m.contains("hasParent=true"));
            // 续写原键: 父 TUC 分支下 override 仍优先 (CC runAgent.ts:347 override?.agentId 优先级独立于分支)
            assertThat(logs)
                .as("父 TUC 分支 resume 续跑必须续写原键: Step 1-5 agentId = 原 agentId " + ORIGINAL_AGENT_ID)
                .anyMatch(m -> m.contains("Step 1-5: agentId=" + ORIGINAL_AGENT_ID));
        } finally {
            executorLogger.detachAppender(appender);
            createLogger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * [RES-R2-1] 反断言: 父 TUC 分支 + 非 resume（agentIdOverride null）→ 仍 generate 新 UUID（无回归）。
     */
    @Test
    @DisplayName("父 TUC 分支: agentIdOverride null (非 resume) → 仍 generate 新 UUID（无回归）")
    void parentTucBranch_noAgentIdOverride_stillGeneratesNewUuid() {
        // GIVEN: 非 resume forkParams —— agentIdOverride 未提供（null，4 参兼容构造）
        SubagentExecutor.ForkPathParams forkParams = new SubagentExecutor.ForkPathParams(
            null, List.of(), "parent-system-prompt", null);
        Logger executorLogger = (Logger) LoggerFactory.getLogger(SubagentExecutor.class);
        Logger createLogger = (Logger) LoggerFactory.getLogger(createSubagentContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        executorLogger.addAppender(appender);
        createLogger.addAppender(appender);
        try {
            executorLogger.setLevel(Level.INFO);
            createLogger.setLevel(Level.INFO);

            // WHEN: 8 参构造器注入父 TUC → 父 TUC 分支
            SubagentExecutor executor = new SubagentExecutor(
                ToolRegistry.from(List.of()), null, null, null, null, "model", "system-prompt", parentTuc());
            assertThatThrownBy(() -> executor.execute("directive", "general-purpose", null, forkParams))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contextFactory not injected");

            List<String> logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            // 分支判别: 必须走父 TUC 分支
            assertThat(logs)
                .as("必须走父 TUC 分支: createSubagentContext hasParent=true")
                .anyMatch(m -> m.contains("hasParent=true"));
            // 反断言: 非 resume 父 TUC 分支仍 generate 新 UUID（agentIdOverride null → create() 内部新 UUID）
            assertThat(logs)
                .as("非 resume 父 TUC 分支必须仍 generate 新 UUID（不能是 null）")
                .anyMatch(m -> m.contains("Step 1-5: agentId=")
                    && !m.contains("Step 1-5: agentId=null"));
        } finally {
            executorLogger.detachAppender(appender);
            createLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. ForkPathParams 字段契约（兼容构造不破坏现有 4 参语义）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ForkPathParams 4 参兼容构造: agentIdOverride 默认为 null（非 resume 调用方不受影响）")
    void forkPathParams_4argCompat_agentIdOverrideDefaultsNull() {
        SubagentExecutor.ForkPathParams forkParams = new SubagentExecutor.ForkPathParams(
            null, List.of(), "parent-system-prompt", null);
        assertThat(forkParams.agentIdOverride())
            .as("4 参兼容构造（非 resume 调用方）agentIdOverride 必须为 null")
            .isNull();
    }
}
