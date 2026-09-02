package com.nexusai.application.agent.prompt;

import com.nexusai.apis.command.CommandController;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.PostCompactCleanup;
import com.nexusai.application.agent.config.ToolRegistrationConfig;
import com.nexusai.common.RequestContext;
import com.nexusai.model.command.dto.BuiltInCommandDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-SP-07 失效接线意图测试 · 对齐 CC {@code clearSystemPromptSections}
 * （systemPromptSections.ts:65-68）的 4 类触发点（Java 落实：/clear、/compact、memory 写、工具注册）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：失效接线的核心不变量是「触发后同 name {@code resolve} 重算」——
 * per-section 缓存已清，二次 resolveAll 走 compute（计数递增）。若触发点未真实接线（no-op / 未调
 * clear），本测试 fail。每条触发点的统一断言形态：
 * <pre>
 *   注册同 name section → resolveAll 写缓存（compute=1）→ 触发 clear → resolveAll 再走 compute（compute=2）
 * </pre>
 *
 * <p>触发路径：
 * <ul>
 *   <li>/clear —— {@link CommandController#executeBuiltin}（DEC-9 薄触发 + 失效副作用，返回 DTO 不变）</li>
 *   <li>/compact —— {@link PostCompactCleanup#runPostCompactCleanup}（CC postCompactCleanup.ts:62）</li>
 *   <li>工具注册 —— {@link ToolRegistrationConfig#invalidateActiveSessionSystemPromptSections}（工具 @Bean 构建后）</li>
 * </ul>
 *
 * <p><b>FIX-MC</b>：memory 写/删触发点已随 MemoryStorage CRUD 死层删除（CC 无程序化记忆写 API，
 * 模型用 Write/Edit 维护，section 缓存失效走 hook 路径而非存储层）。原 3 条 memory 写触发测试
 * （write/delete/setSectionCacheInvalidator）随被删 API 一并移除。
 */
class CacheInvalidationTest {

    /** 统一夹具：注册同 name section 到 AgentState 的会话级缓存，compute 计数可断言。 */
    private static final class Fixture {
        final String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        final SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        final AgentState state = new AgentState("system-prompt", sessionId, null);
        final SystemPromptSectionRegistry sectionRegistry = new SystemPromptSectionRegistry();
        final AtomicInteger computeCount = new AtomicInteger();

        Fixture() {
            registry.register(sessionId, state);
            sectionRegistry.register(SystemPromptSections.systemPromptSection(
                "env_info_simple",
                () -> {
                    computeCount.incrementAndGet();
                    return CompletableFuture.completedFuture("env-v1");
                }));
        }

        /** 首次 resolveAll → 写缓存（compute=1）；断言缓存已建立。 */
        void primeCache() {
            List<String> first = sectionRegistry.resolveAll(state.systemPromptSectionCache());
            assertThat(computeCount.get()).as("首次 resolve 走 compute").isEqualTo(1);
            assertThat(first).containsExactly("env-v1");
        }

        /** 失效后再 resolve（recompute），不断言计数（供 delete 用例在 write 触发后重新装填缓存）。 */
        void reprime() {
            List<String> values = sectionRegistry.resolveAll(state.systemPromptSectionCache());
            assertThat(values).containsExactly("env-v1");
        }

        /** 触发 clear 后再 resolveAll → 缓存已清 → 重新 compute；返回累计 compute 计数。 */
        int resolveAfterClear() {
            List<String> second = sectionRegistry.resolveAll(state.systemPromptSectionCache());
            assertThat(second).as("失效后重算结果不变（同 name resolve）").containsExactly("env-v1");
            return computeCount.get();
        }
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("/clear 触发: executeBuiltin(clear) 清缓存 → 同 name 重算，返回 DTO 不变")
    void clearViaCommandController_trigger() {
        Fixture fx = new Fixture();
        fx.primeCache();

        CommandController controller = new CommandController();
        ReflectionTestUtils.setField(controller, "sessionAgentStateRegistry", fx.registry);
        RequestContext.set(fx.sessionId.toString(), "req-clear");
        try {
            // [RES-④] executeBuiltin 新增可选 @RequestBody 参数（resume 分支消费），非 resume 传 null；
            //   返回类型 Object（resume → ResumeAgentResult，其余 → BuiltInCommandDto），此处强转
            BuiltInCommandDto dto = (BuiltInCommandDto) controller.executeBuiltin("clear", null);
            assertThat(dto.name()).as("DEC-9 返回 DTO 不变").isEqualTo("clear");
            assertThat(fx.resolveAfterClear())
                .as("/clear 后缓存已清 → resolveAll 重新 compute（CC clearSystemPromptSections）")
                .isEqualTo(2);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("/clear 无会话上下文 → 失效跳过，缓存不清（保 CommandController 测试兼容）")
    void clearWithoutSession_skips() {
        Fixture fx = new Fixture();
        fx.primeCache();

        CommandController controller = new CommandController();
        ReflectionTestUtils.setField(controller, "sessionAgentStateRegistry", fx.registry);
        // MDC 未设置 → sessionId null → debug skip
        controller.executeBuiltin("clear", null);
        assertThat(fx.computeCount.get()).as("无会话上下文 → 失效跳过 → 缓存未清").isEqualTo(1);
    }

    @Test
    @DisplayName("/compact 触发: runPostCompactCleanup 清缓存 → 同 name 重算（REQ-SP-11）")
    void clearViaPostCompactCleanup_trigger() {
        Fixture fx = new Fixture();
        fx.primeCache();

        // 构造即把 STATIC_SESSION_REGISTRY 覆盖为当前用例 registry（跨用例静态隔离：每用例新建覆盖；
        //   FIX-CL 新增 ClaudemdEngine 位传 null → STATIC_CLAUDE_MD 空 → resetGetMemoryFilesCache 跳过）
        new PostCompactCleanup(null, fx.registry, null);
        RequestContext.set(fx.sessionId.toString(), "req-compact");
        try {
            PostCompactCleanup.runPostCompactCleanup("compact");
            assertThat(fx.resolveAfterClear())
                .as("compact 后不命中旧缓存（CC postCompactCleanup.ts:62 / REQ-SP-11）")
                .isEqualTo(2);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("IMP-SP2-08 SP-07△-6: compact 只清 userContext 缓存，systemContext/gitStatus 缓存保留（CC postCompactCleanup.ts:51-60）")
    void compact_keepsSystemContextCache_clearsUserContextCache() {
        // WHY: CC postCompactCleanup.ts:51-60 main-thread 段只 getUserContext.cache.clear?.() +
        //   resetGetMemoryFilesCache('compact')，不碰 getSystemContext/getGitStatus 缓存（SP-07 △-6）。
        //   旧 Java 实现经 clearAllProviderCaches 双清 system/user → /compact 后 systemContext 重算
        //   （gitStatus 子进程重跑）。本用例用真实 SystemPromptContextProvider（注入假
        //   UserContextProvider/GitStatusProvider 计数 compute）断言：compact 后 systemContext 命中缓存
        //   （gitStatus compute 仍 1）、userContext 重算（claudeMd/currentDate compute 递增）。
        java.util.concurrent.atomic.AtomicInteger gitCompute = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger userCompute = new java.util.concurrent.atomic.AtomicInteger();
        GitStatusProvider fakeGit = new GitStatusProvider(java.nio.file.Path.of("")) {
            @Override
            public String getGitStatus() {
                gitCompute.incrementAndGet();
                return "GIT-BLOCK";
            }
        };
        UserContextProvider fakeUser = new UserContextProvider(java.nio.file.Path.of("")) {
            @Override
            public String claudeMd() {
                userCompute.incrementAndGet();
                return "项目指令";
            }

            @Override
            public String currentDate(String sessionStartDate) {
                userCompute.incrementAndGet();
                return "Today's date is " + sessionStartDate + ".";
            }
        };
        // env 恒 null → 无 CCR/禁 git 门控，gitStatus 必算（context.ts:124-128）
        SystemPromptContextProvider provider =
            new SystemPromptContextProvider("2026-08-12", fakeUser, fakeGit, env -> null);
        try {
            assertThat(provider.getSystemContext()).containsEntry("gitStatus", "GIT-BLOCK");
            assertThat(provider.getUserContext()).containsEntry("claudeMd", "项目指令");
            assertThat(gitCompute.get()).as("首次 getSystemContext 走 compute").isEqualTo(1);
            assertThat(userCompute.get()).as("首次 getUserContext 走 compute").isEqualTo(2);

            Fixture fx = new Fixture();
            new PostCompactCleanup(null, fx.registry, null);
            PostCompactCleanup.runPostCompactCleanup("REPL_MAIN_THREAD:test");

            assertThat(provider.getSystemContext())
                .as("compact 后 systemContext 缓存保留（CC postCompactCleanup.ts:51-60 不清 getSystemContext.cache）")
                .containsEntry("gitStatus", "GIT-BLOCK");
            assertThat(gitCompute.get())
                .as("systemContext 未重算 → gitStatus 缓存保留（SP-07 △-6）")
                .isEqualTo(1);
            assertThat(provider.getUserContext())
                .as("compact 后 userContext 缓存已清 → 重算")
                .containsEntry("claudeMd", "项目指令");
            assertThat(userCompute.get())
                .as("userContext 重算（CC getUserContext.cache.clear 等价）")
                .isEqualTo(4);
        } finally {
            provider.close();
        }
    }

    @Test
    @DisplayName("FIX-CL /compact 触发: main-thread 分支调 SystemPromptInjection.clearUserOnlyProviderCaches → 已注册 user-only 缓存清空回调触发")
    void compact_clearsProviderCachesViaSystemPromptInjection() {
        // WHY: FIX-CL 把 CC getUserContext.cache.clear（postCompactCleanup.ts:52）接线为
        //       SystemPromptInjection.clearUserOnlyProviderCaches()（触发已注册 provider 的 user
        //       缓存清理；[IMP-SP2-08 SP-07 △-6] 收敛到 user-only 通道 —— CC :51-60 只清
        //       getUserContext.cache，不清 getSystemContext，旧全清通道为多清偏差）。本用例注册一个
        //       user-only 缓存清空回调，断言 /compact 后必然触发 —— 防止回退到
        //       "Java 无 memoized 缓存 → no-op" 假接线。
        java.util.concurrent.atomic.AtomicInteger hookFired = new java.util.concurrent.atomic.AtomicInteger();
        SystemPromptInjection.registerUserCacheClearHook(hookFired::incrementAndGet);

        Fixture fx = new Fixture();
        fx.primeCache();
        new PostCompactCleanup(null, fx.registry, null);
        RequestContext.set(fx.sessionId.toString(), "req-compact");
        try {
            // main-thread querySource（CC isMainThreadCompact: repl_main_thread* 前缀）——只有 main-thread
            // 压缩才重置模块级状态（postCompactCleanup.ts:36-39），subagent/compact 命令不触达
            PostCompactCleanup.runPostCompactCleanup("repl_main_thread:test");
            assertThat(hookFired.get())
                .as("clearUserOnlyProviderCaches 触发注册的 user-only 缓存清空回调（CC getUserContext.cache.clear 等价）")
                .isGreaterThan(0);
            assertThat(fx.resolveAfterClear())
                .as("compact 后 section 缓存清 → 同 name 重算（postCompactCleanup.ts:62）")
                .isEqualTo(2);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("IMP-SP2-01 生产大写枚举名: runPostCompactCleanup(\"REPL_MAIN_THREAD:…\") 触发 main-thread 清理（REQ-SP-07△21）")
    void testCompaction_uppercaseEnumName_triggersMainThreadCleanup() {
        // WHY: 生产传值链 LlmAgentLoop:2878/:2921-2922 传 params.querySource().name()，而
        //       QuerySource.REPL_MAIN_THREAD.name() = "REPL_MAIN_THREAD"（大写枚举名，QuerySource.java:26）。
        //       旧 gate 用大小写敏感 startsWith("repl_main_thread") → gate 恒 false → main-thread 清理
        //       （clearUserOnlyProviderCaches + resetGetMemoryFilesCache('compact')）不执行。本用例用生产
        //       大写值断言 user-only hook 真实触发（[IMP-SP2-08 SP-07 △-6] main-thread 清理面
        //       收敛到 user-only 通道 —— 对齐 CC postCompactCleanup.ts:36-39 isMainThreadCompact
        //       startsWith 前缀匹配，大小写不敏感；CC :51-60 只清 getUserContext.cache）。
        java.util.concurrent.atomic.AtomicInteger hookFired = new java.util.concurrent.atomic.AtomicInteger();
        SystemPromptInjection.registerUserCacheClearHook(hookFired::incrementAndGet);

        Fixture fx = new Fixture();
        fx.primeCache();
        new PostCompactCleanup(null, fx.registry, null);
        RequestContext.set(fx.sessionId.toString(), "req-compact");
        try {
            PostCompactCleanup.runPostCompactCleanup("REPL_MAIN_THREAD:test");
            assertThat(hookFired.get())
                .as("大写枚举名（生产真实值）必须触发 clearUserOnlyProviderCaches 回调（CC postCompactCleanup.ts:36-39）")
                .isGreaterThan(0);
            assertThat(fx.resolveAfterClear())
                .as("compact 后 section 缓存清 → 同 name 重算（postCompactCleanup.ts:62）")
                .isEqualTo(2);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("工具注册触发: 工具 @Bean 构建后失效 → 缓存清 → 同 name 重算")
    void clearViaToolRegistration_trigger() {
        Fixture fx = new Fixture();
        fx.primeCache();

        ToolRegistrationConfig config = new ToolRegistrationConfig();
        ReflectionTestUtils.setField(config, "sessionAgentStateRegistry", fx.registry);
        RequestContext.set(fx.sessionId.toString(), "req-tools");
        try {
            ReflectionTestUtils.invokeMethod(
                config, "invalidateActiveSessionSystemPromptSections", "工具注册测试");
            assertThat(fx.resolveAfterClear())
                .as("工具注册后缓存清 → resolveAll 重算（CC clearSystemPromptSections 工具注册）")
                .isEqualTo(2);
        } finally {
            RequestContext.clear();
        }
    }
}
