package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.ForkSubagentAgentDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.assertj.core.api.Assertions;
import org.slf4j.LoggerFactory;

/**
 * [H9 v3 Gap①] SubagentExecutor fork 子 agent 的 permissionMode 生产来源 · RED-GREEN 双证.
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: v2 对抗复验判定 H9 PARTIAL — 根因是
 * {@code resolvePermissionMode(agentDefinition)} 的结果只传给 runSubagentQueryLoop 但
 * <b>从未落到子 base TUC</b>。CC 真源 (runAgent.ts:415-432)：fork 子 agent 的
 * {@code agentPermissionMode = agentDefinition.permissionMode} (ForkSubagent 固定 "bubble")
 * 覆盖子 {@code toolPermissionContext.mode}，父 mode 为 bypassPermissions/acceptEdits/auto
 * 时父优先级更高不覆盖 (:424-431)。本测试钉死 {@link SubagentExecutor#resolveEffectiveForkMode}：
 * <ul>
 *   <li>fork (permissionMode=bubble) + 父 DEFAULT → BUBBLE（BUBBLE 真实生产来源）</li>
 *   <li>fork (permissionMode=bubble) + 父 BYPASS_PERMISSIONS → BYPASS_PERMISSIONS（父优先级）</li>
 *   <li>未定义 permissionMode → 继承父（不误覆盖）</li>
 *   <li>ForkSubagentAgentDefinition 端到端：create().permissionMode() == "bubble" →
 *       resolvePermissionMode → BUBBLE</li>
 * </ul>
 * BUBBLE 落到 base TUC 后，{@code AgentLoopContext.toolExecContext} 派生
 * {@code awaitAutomatedChecksBeforeDialog=true} → gate coordinator 分支生产可达。
 *
 * @since H9 v3 缺口修复
 */
@DisplayName("[H9 v3 Gap①] SubagentExecutor fork permissionMode 生产来源")
class SubagentExecutorForkModeTest {

    /** fork 子 agent 定义 (CC forkSubagent.ts:67 permissionMode:'bubble' 的 Java 等价). */
    private static AgentDefinition forkDefinition() {
        return ForkSubagentAgentDefinition.create();
    }

    /** 未定义 permissionMode 的普通 agent 定义. */
    private static AgentDefinition noPermissionModeDefinition() {
        return AgentDefinition.BuiltInAgentDefinition.create(
            "explore", "explore without permissionMode",
            List.of("Read"), (ctx, dirs) -> "");
    }

    @Test
    @DisplayName("fork (permissionMode=bubble) + 父 DEFAULT → BUBBLE (生产来源)")
    void forkBubbleWithParentDefault_appliesBubble() {
        // WHY: 核心生产来源 — fork 子 agent 的 BUBBLE 必须从 agentDefinition 落到子 TUC.
        //   父 DEFAULT 时无父优先级 → 采用 fork 的 bubble. 若该决策丢失 → base TUC 恒非 BUBBLE
        //   → toolExecContext 派生 awaitAutomatedChecks=false → coordinator 分支生产不可达.
        assertThat(SubagentExecutor.resolveEffectiveForkMode(
                forkDefinition(), PermissionMode.BUBBLE, PermissionMode.DEFAULT))
            .as("fork bubble + 父 DEFAULT → 子 base TUC permissionMode 必须 BUBBLE (CC runAgent.ts:419-432)")
            .isEqualTo(PermissionMode.BUBBLE);
    }

    @Test
    @DisplayName("fork (permissionMode=bubble) + 父 BYPASS_PERMISSIONS → BYPASS_PERMISSIONS (父优先级)")
    void forkBubbleWithParentBypass_keepsParent() {
        // WHY: CC runAgent.ts:424-431 — 父 mode 为 bypassPermissions/acceptEdits/auto 时
        //   父优先级更高，不覆盖. 若覆盖成 bubble → 子 agent 丢失父的 bypass 权限语义.
        assertThat(SubagentExecutor.resolveEffectiveForkMode(
                forkDefinition(), PermissionMode.BUBBLE, PermissionMode.BYPASS_PERMISSIONS))
            .as("fork bubble + 父 BYPASS_PERMISSIONS → 父优先级更高, 保持 BYPASS_PERMISSIONS")
            .isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
    }

    @Test
    @DisplayName("未定义 permissionMode → 继承父 (不误覆盖)")
    void noPermissionMode_inheritsParent() {
        // WHY: 普通 subagent 未定义 permissionMode → 子 TUC 从父继承, 不得被 DEFAULT 误覆盖
        //   (CC runAgent.ts:419 agentPermissionMode 未定义时保持父 mode).
        assertThat(SubagentExecutor.resolveEffectiveForkMode(
                noPermissionModeDefinition(), PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS))
            .as("未定义 permissionMode → 继承父 ACCEPT_EDITS")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    @Test
    @DisplayName("ForkSubagentAgentDefinition 端到端: permissionMode=bubble → resolvePermissionMode=BUBBLE")
    void forkDefinition_carriesBubbleEndToEnd() {
        // WHY: 生产来源端到端 — ForkSubagentAgentDefinition.create() (CC forkSubagent.ts:67)
        //   必须携带 permissionMode="bubble", resolvePermissionMode 必须解析为 BUBBLE.
        //   若定义/解析断裂 → 即使 resolveEffectiveForkMode 正确也无 BUBBLE 可应用.
        assertThat(forkDefinition().permissionMode())
            .as("ForkSubagentAgentDefinition.permissionMode 必须 = bubble (CC forkSubagent.ts:67)")
            .contains("bubble");
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "model", "system-prompt");
        assertThat(executor.resolvePermissionMode(forkDefinition()))
            .as("resolvePermissionMode(fork) 必须 = BUBBLE")
            .isEqualTo(PermissionMode.BUBBLE);
    }

    @Test
    @DisplayName("fork path 无 subagentAssistantMessage → 回退普通装配 (不调 buildForkedMessages)")
    void forkPath_withoutAssistantMessage_fallsBackToPlainPrompt() {
        // WHY (Session E 补充 RED→GREEN): buildForkedMessages 接入条件 = isForkAgentType
        //   AND subagentAssistantMessage != null 双闸门 (CC AgentTool.tsx:512 只在父
        //   assistantMessage 存在时构建 fork 前缀). 无 assistantMessage (如非 fork 调用方 /
        //   assistant 消息缺失) 必须回退普通 "role=user, content=prompt" 装配 —
        //   若生产代码误无条件调用 buildForkedMessages → 本测试必红 (NPE 或日志出现).
        Logger logger = (Logger) LoggerFactory.getLogger(SubagentExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.setLevel(Level.DEBUG);

            SubagentExecutor executor = new SubagentExecutor(
                ToolRegistry.from(List.of()), null, null, null, null, "model", "system-prompt");
            // forkParams=null → 非 fork 前缀路径 (subagentAssistantMessage 保持 null)

            // contextFactory 未注入 → Step 20 抛 ISE (Step 10 装配已完成)
            Assertions.assertThatThrownBy(
                () -> executor.execute("plain directive", "fork", null, null))
                .isInstanceOf(IllegalStateException.class);

            List<String> logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
            Assertions.assertThat(logs)
                .as("无 assistantMessage 时不得走 buildForkedMessages 分支 (回退普通装配)")
                .noneMatch(m -> m.contains("fork path: 消息前缀已用 buildForkedMessages 构造"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
