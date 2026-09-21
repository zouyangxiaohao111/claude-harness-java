package com.nexusai.application.agent.prompt;

import com.nexusai.apis.command.CommandController;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.PostCompactCleanup;
import com.nexusai.application.agent.config.ToolRegistrationConfig;
import com.nexusai.model.command.dto.BuiltInCommandDto;
import org.junit.jupiter.api.Disabled;
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

    /**
     * [步骤 4] 逐用例回收<b>会话级 prompt 缓存注册表</b>：本类夹具会经
     * {@code AgentState.systemPromptSectionCache()}（及 {@code forSession}）在<b>进程级静态表</b>
     * 里建 store ⇒ 不回收会跨用例/跨测试类累积（evict→close 亦顺手注销 provider 的
     * SystemPromptInjection 回调，register/unregister 成对）。
     */
    @org.junit.jupiter.api.AfterEach
    void tearDownSessionPromptCacheRegistry() {
        SessionPromptCacheRegistry.resetForTest();
    }

    /**
     * 历史 /clear 用例停用说明 · [P0-0 / N1 · 2026-09-11 用户拍板]。
     *
     * <p>WHY：这两个用例断言「executeBuiltin(clear) 清 section 缓存」——停用后该分支已抛
     * ConflictException，清理链不可达。清理链代码按决策保留不删（砍掉属另一决策，仅登记），
     * 故用例只禁用、不删除，待用户裁定清理链去留后再定去留。
     */
    private static final String CLEAR_DISABLED_REASON =
        "[P0-0/N1 2026-09-11 用户拍板] /clear 已停用：CommandController 对 clear（含别名 reset/new）直接抛 "
        + "ConflictException → 本用例覆盖的会话级清理链已不可达（清理链代码按决策保留不删，仅登记）。"
        + "fail-loud 守卫见 CommandControllerBuiltInCommandsTest#executeBuiltin_clear_isDisabled_failsLoud。";

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

    @Test
    @Disabled(CLEAR_DISABLED_REASON)
    @DisplayName("/clear 触发: executeBuiltin(clear) 清缓存 → 同 name 重算，返回 DTO 不变")
    void clearViaCommandController_trigger() {
        Fixture fx = new Fixture();
        fx.primeCache();

        CommandController controller = new CommandController();
        ReflectionTestUtils.setField(controller, "sessionAgentStateRegistry", fx.registry);
        // [批 3c] 会话标识经**第 2 形参**显式传入（原经裸 MDC 会话槽，该槽已删）
        // [RES-④] executeBuiltin 另有可选 @RequestBody 参数（resume 分支消费），非 resume 传 null；
        //   返回类型 Object（resume → ResumeAgentResult，其余 → BuiltInCommandDto），此处强转
        //   ⚠ 形参序：executeBuiltin(String name, String sessionIdParam, ResumeExecuteRequest request)
        BuiltInCommandDto dto = (BuiltInCommandDto) controller.executeBuiltin("clear", fx.sessionId, null);
        assertThat(dto.name()).as("DEC-9 返回 DTO 不变").isEqualTo("clear");
        assertThat(fx.resolveAfterClear())
            .as("/clear 后缓存已清 → resolveAll 重新 compute（CC clearSystemPromptSections）")
            .isEqualTo(2);
    }

    @Test
    @Disabled(CLEAR_DISABLED_REASON)
    @DisplayName("/clear 无会话上下文 → 失效跳过，缓存不清（保 CommandController 测试兼容）")
    void clearWithoutSession_skips() {
        Fixture fx = new Fixture();
        fx.primeCache();

        CommandController controller = new CommandController();
        ReflectionTestUtils.setField(controller, "sessionAgentStateRegistry", fx.registry);
        // 无会话（第三形参 sessionId=null）→ 失效跳过
        controller.executeBuiltin("clear", null, null);
        assertThat(fx.computeCount.get()).as("无会话上下文 → 失效跳过 → 缓存未清").isEqualTo(1);
    }

    @Test
    @DisplayName("/compact 触发: runPostCompactCleanup 清缓存 → 同 name 重算（REQ-SP-11）")
    void clearViaPostCompactCleanup_trigger() {
        Fixture fx = new Fixture();
        fx.primeCache();

        // 构造即把 STATIC_SESSION_REGISTRY 覆盖为当前用例 registry（跨用例静态隔离：每用例新建覆盖；
        //   FIX-CL 新增 ClaudemdEngine 位传 null → STATIC_CLAUDE_MD 空 → resetGetMemoryFilesCache 跳过）
        new PostCompactCleanup(null, null);
        // [批 3c] 会话标识显式传入（原经裸 MDC 会话槽定位会话级 section 缓存，该槽已删）
        PostCompactCleanup.runPostCompactCleanup("compact", fx.sessionId);
        assertThat(fx.resolveAfterClear())
            .as("compact 后不命中旧缓存（CC postCompactCleanup.ts:62 / REQ-SP-11）")
            .isEqualTo(2);
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
            new PostCompactCleanup(null, null);
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

    /**
     * [步骤 4] 会话级 provider 探针 —— per-session 失效的<b>唯一有效观测面</b>。
     *
     * <p>WHY：步骤 4 起集合 B 的失效走 {@code store.clearGroups → provider.clearUserContextCache()}
     * <b>直清 provider</b>，不再经 {@code SystemPromptInjection} 的全局广播钩子通道
     * ⇒ 「钩子触发次数」不再是有效观测；有效观测 = <b>同 provider 的 userContext 缓存失效
     * （下一次 getUserContext 重算）</b>，这也是该失效在真实链路上的目的。
     *
     * @param store        目标会话的会话级 store（{@code state.promptCacheStore()} 或注册表取用）
     * @param userComputes 计数载体（claudeMd + currentDate 各计一次）
     * @return 接好的会话级 provider（调用方负责 close）
     */
    private static SystemPromptContextProvider wireSessionProvider(
            SessionPromptCacheStore store, java.util.concurrent.atomic.AtomicInteger userComputes) {
        SystemPromptContextProvider provider = new SystemPromptContextProvider("2026-08-12",
            new UserContextProvider(java.nio.file.Path.of("")) {
                @Override
                public String claudeMd() {
                    userComputes.incrementAndGet();
                    return "项目指令";
                }

                @Override
                public String currentDate(String sessionStartDate) {
                    userComputes.incrementAndGet();
                    return "Today's date is " + sessionStartDate + ".";
                }
            },
            new GitStatusProvider(java.nio.file.Path.of("")) {
                @Override
                public String getGitStatus() {
                    return "GIT-BLOCK";
                }
            },
            env -> null);
        store.contextProvider(() -> provider);
        return provider;
    }

    @Test
    @DisplayName("[步骤 4] main-thread /compact → 集合B 按**本会话**精确清（provider userContext 重算；⛔ 不牵连别的会话）")
    void compact_clearsUserContextOfTargetSessionOnly() {
        // WHY: 集合B（CC postCompactCleanup.ts:59 getUserContext.cache.clear）在 CC 侧是**进程级**
        //   memoize（一进程一会话）；本仓一 JVM 多会话 ⇒ 必须映射为**按会话清**。本用例同时钉死两件事：
        //   ① 目标会话必须真被清（防回退到 no-op 假接线）；② 别的会话<b>不得</b>被牵连
        //   （⛔ 广播清会让「会话 A 压缩」打掉「会话 B 的 claudeMd 头部」= 跨会话串味）。
        Fixture fx = new Fixture();
        fx.primeCache();
        java.util.concurrent.atomic.AtomicInteger sessionAUsers = new java.util.concurrent.atomic.AtomicInteger();
        SystemPromptContextProvider providerA = wireSessionProvider(fx.state.promptCacheStore(), sessionAUsers);
        providerA.getUserContext();
        int aBefore = sessionAUsers.get();

        // 对照会话 B：同 JVM 的另一个会话（独立 store + 独立 provider）
        String otherSession = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        java.util.concurrent.atomic.AtomicInteger sessionBUsers = new java.util.concurrent.atomic.AtomicInteger();
        SystemPromptContextProvider providerB = wireSessionProvider(
            SessionPromptCacheRegistry.forSession(otherSession, "2026-08-12"), sessionBUsers);
        providerB.getUserContext();
        int bBefore = sessionBUsers.get();

        new PostCompactCleanup(null, null);
        // main-thread querySource（CC isMainThreadCompact: repl_main_thread* 前缀）——只有 main-thread
        // 压缩才重置模块级状态（postCompactCleanup.ts:36-39），subagent/compact 命令不触达
        PostCompactCleanup.runPostCompactCleanup("repl_main_thread:test", fx.sessionId);

        assertThat(fx.resolveAfterClear())
            .as("集合A: compact 后 section 缓存清 → 同 name 重算（postCompactCleanup.ts:62）")
            .isEqualTo(2);
        providerA.getUserContext();
        assertThat(sessionAUsers.get())
            .as("集合B: 本会话 userContext 冻结值必须失效 → 重算（CC getUserContext.cache.clear）")
            .isGreaterThan(aBefore);
        providerB.getUserContext();
        assertThat(sessionBUsers.get())
            .as("对照：别的会话不得被本会话的压缩牵连（CC 进程级 → 本仓会话级映射，⛔ 不广播）")
            .isEqualTo(bBefore);
        providerA.close();
        providerB.close();
    }

    @Test
    @DisplayName("[步骤 4 · IMP-SP2-01] 生产大写枚举名走主线程门（清集合B）；子代理源只清集合A（集合B 刻意不清）")
    void compaction_uppercaseMainThread_clearsUserContext_subagentDoesNot() {
        // WHY: 生产传值链 LlmAgentLoop 传 params.querySource().name()，而
        //       QuerySource.REPL_MAIN_THREAD.name() = "REPL_MAIN_THREAD"（大写枚举名）。
        //       旧 gate 用大小写敏感 startsWith → gate 恒 false → main-thread 清理不执行。
        //       [步骤 4] 观测面从「user-only 钩子触发」改为「目标会话 provider 重算」，并补上
        //       **子代理负向对照**（CC :51-60 的 isMainThreadCompact 守卫要求子代理<u>刻意不清</u>
        //       主会话 userContext，否则破坏主线程状态）。
        Fixture mainFx = new Fixture();
        mainFx.primeCache();
        java.util.concurrent.atomic.AtomicInteger mainUsers = new java.util.concurrent.atomic.AtomicInteger();
        SystemPromptContextProvider mainProvider =
            wireSessionProvider(mainFx.state.promptCacheStore(), mainUsers);
        mainProvider.getUserContext();
        int mainBefore = mainUsers.get();
        PostCompactCleanup.runPostCompactCleanup("REPL_MAIN_THREAD:test", mainFx.sessionId);
        mainProvider.getUserContext();
        assertThat(mainUsers.get())
            .as("大写枚举名（生产真实值）必须命中 main-thread 门 → 集合B 清（CC postCompactCleanup.ts:36-39/:59）")
            .isGreaterThan(mainBefore);
        assertThat(mainFx.resolveAfterClear())
            .as("集合A: compact 后 section 缓存清 → 同 name 重算（postCompactCleanup.ts:62）")
            .isEqualTo(2);

        // ── 负向对照：子代理源（agent:…）──
        Fixture subFx = new Fixture();
        subFx.primeCache();
        java.util.concurrent.atomic.AtomicInteger subUsers = new java.util.concurrent.atomic.AtomicInteger();
        SystemPromptContextProvider subProvider =
            wireSessionProvider(subFx.state.promptCacheStore(), subUsers);
        subProvider.getUserContext();
        int subBefore = subUsers.get();
        PostCompactCleanup.runPostCompactCleanup("agent:sub-1", subFx.sessionId);
        subProvider.getUserContext();
        assertThat(subUsers.get())
            .as("子代理 compact 不得清主会话 userContext（CC :51-60 守卫；否则破坏主线程模块级状态）")
            .isEqualTo(subBefore);
        assertThat(subFx.resolveAfterClear())
            .as("⭐ 但集合A 在守卫之外（无守卫）→ 子代理 compact 也清（CC :62）")
            .isEqualTo(2);
        mainProvider.close();
        subProvider.close();
    }

    @Test
    @DisplayName("工具注册触发: 工具 @Bean 构建后失效 → 缓存清 → 同 name 重算")
    void clearViaToolRegistration_trigger() {
        Fixture fx = new Fixture();
        fx.primeCache();

        ToolRegistrationConfig config = new ToolRegistrationConfig();
        ReflectionTestUtils.setField(config, "sessionAgentStateRegistry", fx.registry);
        // [批 3c] 失效接线改为遍历全部活跃会话（不再读裸 MDC 取「当前会话」，该槽已删）
        ReflectionTestUtils.invokeMethod(
            config, "invalidateActiveSessionSystemPromptSections", "工具注册测试");
        assertThat(fx.resolveAfterClear())
            .as("工具注册后缓存清 → resolveAll 重算（CC clearSystemPromptSections 工具注册）")
            .isEqualTo(2);
    }
}
