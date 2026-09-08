package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.AutoMemoryNoBoundProjectException;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * [决策 2026-09-08] LlmAgentLoop auto-memory「DB 主路径」守卫单测。
 *
 * <p>覆盖 {@code ensureAutoMemoryProjectRootResolvedForPrompt}（memory 段组装前的 projectRoot 确认）：
 * <ol>
 *   <li><b>绑定 → 注入 D 目录</b>：会话已冻结 DB 绑定项目（SessionProjectRoot.setForSession = DB
 *       兜底 tryResolveBoundProjectFromDb 成功产物），但组装线程 ThreadLocal 未回放 → 守卫把冻结值
 *       回填本线程 → 后续 loadMemoryPrompt 读到该 D 目录（修复「有绑定却漏注入」）。</li>
 *   <li><b>无绑定 → error 不注入</b>：会话存在但 DB 无绑定（无冻结）且 ThreadLocal 未注入 → 守卫抛
 *       {@link AutoMemoryNoBoundProjectException}（调用方当场 catch → log.error + 本轮不注入假目录）。</li>
 * </ol>
 *
 * <p>auto-memory 全局禁用（env/settings）时守卫为 no-op —— 本测试经 JUnit assume 跳过（不伪绿）。
 */
@DisplayName("[决策 2026-09-08] LlmAgentLoop auto-memory DB 主路径守卫")
class LlmAgentLoopAutoMemoryBoundGuardTest {

    private static final String SESSION = "sess-memguard-1";

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void resetState() {
        AutoMemPaths.resetCurrentProjectRoot();
        SessionProjectRoot.reset();
    }

    /** 组装测试用最小 ctx：只需 streamSessionId（守卫唯一消费点）。 */
    private static AgentLoopContext ctxWithSession(String sessionId) {
        // 32 参 compat ctor：1-15 null, 16 wsTemplate null, 17 streamTopic, 18 streamSessionId,
        // 19 streamUserMessageId, 20 featureFlags ALL_DISABLED, 21-32 null
        return new AgentLoopContext(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, "topic", sessionId, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static Method guardMethod() throws NoSuchMethodException {
        Method m = LlmAgentLoop.class.getDeclaredMethod(
            "ensureAutoMemoryProjectRootResolvedForPrompt", AgentLoopContext.class);
        m.setAccessible(true);
        return m;
    }

    @Test
    @DisplayName("绑定（冻结值=DB 兜底产物）+ 组装线程 ThreadLocal 缺失 → 守卫回填冻结值（后续解析 D 目录）")
    void boundSession_missingThreadLocal_backfillsFrozenProjectRoot() throws Exception {
        assumeTrue(BundledSkillEnabledGates.isAutoMemoryEnabled(), "auto-memory 全局禁用，守卫为 no-op，跳过");
        Path realProj = Files.createDirectories(tempDir.resolve("proj-bound")).toRealPath();
        // 模拟 LlmAgentLoop.run() DB 兜底成功：冻结到 SessionProjectRoot（setForSession 要求绝对路径+目录存在）
        SessionProjectRoot.setForSession(SESSION, realProj.toString());

        // 组装线程未注入 ThreadLocal（如 compact/queryLoop 非 run() 线程形态）
        AutoMemPaths.resetCurrentProjectRoot();
        assertThat(AutoMemPaths.captureCurrentProjectRoot()).isNull();

        guardMethod().invoke(null, ctxWithSession(SESSION));

        assertThat(AutoMemPaths.captureCurrentProjectRoot())
            .as("守卫把 DB 派生冻结值回填本线程 → loadMemoryPrompt 解析到绑定项目 D 目录")
            .isEqualTo(realProj.toString());
    }

    @Test
    @DisplayName("会话存在但 DB 无绑定（无冻结）+ ThreadLocal 缺失 → 抛 AutoMemoryNoBoundProjectException（fail loud 信号）")
    void sessionWithoutAnyBinding_throwsAutoMemoryNoBoundProjectException() throws Exception {
        assumeTrue(BundledSkillEnabledGates.isAutoMemoryEnabled(), "auto-memory 全局禁用，守卫为 no-op，跳过");
        AutoMemPaths.resetCurrentProjectRoot();
        // 无 SessionProjectRoot 冻结 = DB 无绑定（run() DB 兜底失败保持无项目）

        assertThatThrownBy(() -> invokeGuard(ctxWithSession(SESSION)))
            .isInstanceOf(AutoMemoryNoBoundProjectException.class)
            .hasMessageContaining(SESSION)
            .hasMessageContaining("未绑定项目");
    }

    @Test
    @DisplayName("auto-memory 禁用时守卫 no-op（不抛、不触碰 ThreadLocal）")
    void autoMemoryDisabled_guardIsNoOp() throws Exception {
        assumeTrue(!BundledSkillEnabledGates.isAutoMemoryEnabled(), "auto-memory 已启用，本用例仅在禁用环境验证 no-op");
        AutoMemPaths.resetCurrentProjectRoot();

        guardMethod().invoke(null, ctxWithSession(SESSION));

        assertThat(AutoMemPaths.captureCurrentProjectRoot())
            .as("禁用 → 守卫不注入、不抛，保持原状")
            .isNull();
    }

    private static void invokeGuard(AgentLoopContext ctx) throws Throwable {
        try {
            guardMethod().invoke(null, ctx);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
