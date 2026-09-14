package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
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
 * [决策 2026-09-08 / 批 4b-1 显式化] LlmAgentLoop auto-memory「DB 主路径」解析器单测。
 *
 * <p>覆盖 {@code resolveAutoMemoryProjectRoot(ctx)}（memory 段组装前解析会话项目根）：
 * <ol>
 *   <li><b>绑定 → 返回该根</b>：会话已冻结 DB 绑定项目（{@link SessionProjectRoot#setForSession}
 *       = run() 入口<b>唯一链</b> {@code SessionProjectRoot.lookup} 成功产物；F-24 Step 3 前该产物
 *       来自 B′ 兜底 {@code tryResolveBoundProjectFromDb}，该链已删）→ 解析器返回该绝对路径，
 *       供 {@code LoadMemoryPrompt} 显式消费（修复「有绑定却漏注入」）。</li>
 *   <li><b>无绑定 → 抛</b>：会话存在但 DB 无绑定（无冻结）→ 抛
 *       {@link AutoMemoryNoBoundProjectException}（调用方当场 catch → log.error + 本轮不注入假目录）。</li>
 *   <li><b>auto-memory 全局禁用 → 返回 null（不抛）</b>。</li>
 * </ol>
 *
 * <p><b>[批 4b-1 变更]</b>：原解析器 {@code ensureAutoMemoryProjectRootResolvedForPrompt} 是
 * <b>void + 回填 ThreadLocal</b>（断言 {@code AutoMemPaths.captureCurrentProjectRoot()}）；载体
 * （{@code AutoMemPaths.CURRENT_PROJECT_ROOT}）删除后改为 <b>返回值 + 调用方显式下传</b>
 * （用户铁律：会话态一律显式传参，回放不算合规）。本测试随之改为断言**返回值本身**——
 * 正向锚更硬：不再依赖任何线程局部状态。
 *
 * <p>auto-memory 全局禁用（env/settings）时解析器为 no-op —— 本测试经 JUnit assume 跳过（不伪绿）。
 */
@DisplayName("[决策 2026-09-08 · 批 4b-1] LlmAgentLoop auto-memory DB 主路径解析器（显式返回值）")
class LlmAgentLoopAutoMemoryBoundGuardTest {

    private static final String SESSION = "sess-memguard-1";

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void resetState() {
        SessionProjectRoot.reset();
    }

    /** 组装测试用最小 ctx：只需 streamSessionId（解析器唯一消费点；sessionState 为 null 走冻结表分支）。 */
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
            "resolveAutoMemoryProjectRoot", AgentLoopContext.class);
        m.setAccessible(true);
        return m;
    }

    private static String invokeGuard(AgentLoopContext ctx) throws Throwable {
        try {
            return (String) guardMethod().invoke(null, ctx);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    @DisplayName("绑定（冻结值=DB 兜底产物）→ 解析器返回该绝对路径（供 LoadMemoryPrompt 显式消费）")
    void boundSession_returnsFrozenProjectRoot() throws Throwable {
        assumeTrue(BundledSkillEnabledGates.isAutoMemoryEnabled(), "auto-memory 全局禁用，解析器为 no-op，跳过");
        Path realProj = Files.createDirectories(tempDir.resolve("proj-bound")).toRealPath();
        // 模拟 LlmAgentLoop.run() DB 兜底成功：冻结到 SessionProjectRoot（setForSession 要求绝对路径+目录存在）
        SessionProjectRoot.setForSession(SESSION, realProj.toString());

        String resolved = invokeGuard(ctxWithSession(SESSION));

        assertThat(resolved)
            .as("解析器必须返回 DB 派生（冻结）的会话项目根 —— 有绑定必注入 D 目录")
            .isEqualTo(realProj.toString());
    }

    @Test
    @DisplayName("会话存在但 DB 无绑定（无冻结）→ 抛 AutoMemoryNoBoundProjectException（fail loud 信号）")
    void sessionWithoutAnyBinding_throwsAutoMemoryNoBoundProjectException() throws Throwable {
        assumeTrue(BundledSkillEnabledGates.isAutoMemoryEnabled(), "auto-memory 全局禁用，解析器为 no-op，跳过");
        // 无 SessionProjectRoot 冻结 = DB 无绑定（run() DB 兜底失败保持无项目）

        assertThatThrownBy(() -> invokeGuard(ctxWithSession(SESSION)))
            .isInstanceOf(AutoMemoryNoBoundProjectException.class)
            .hasMessageContaining(SESSION)
            .hasMessageContaining("未绑定项目");
    }

    @Test
    @DisplayName("auto-memory 禁用时解析器 no-op：返回 null（不抛、不伪造项目根）")
    void autoMemoryDisabled_guardIsNoOp() throws Throwable {
        assumeTrue(!BundledSkillEnabledGates.isAutoMemoryEnabled(), "auto-memory 已启用，本用例仅在禁用环境验证 no-op");

        assertThat(invokeGuard(ctxWithSession(SESSION)))
            .as("禁用 → 无注入义务：返回 null，不抛、不伪造")
            .isNull();
    }
}
