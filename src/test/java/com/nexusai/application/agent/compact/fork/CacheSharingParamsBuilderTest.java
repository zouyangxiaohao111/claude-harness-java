package com.nexusai.application.agent.compact.fork;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.prompt.SystemPromptSplitter;
import com.nexusai.application.agent.prompt.UserContextProvider;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheSharingParamsBuilder + CacheSafeParamsHolder 意图测试 · 对齐 CC
 * {@code getCacheSharingParams}（Open-ClaudeCode/src/commands/compact/compact.ts:250-287）
 * + {@code saveCacheSafeParams}/{@code getLastCacheSafeParams}（forkedAgent.ts:70-81）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）: RES-② 的目标是让 fork 缓存共享真正触发
 * —— CacheSafeParams 的 5 字段（systemPrompt/userContext/systemContext/toolUseContext/
 * forkContextMessages）必须与主线程 cache key 完全一致（forkedAgent.ts:46-56），否则 fork
 * 拿不到缓存命中 → 白耗双倍 token。本测试钉死：
 * <ol>
 *   <li>5 字段来源正确（systemPrompt=custom 或 default 组装、userContext/systemContext 走
 *       SystemPromptContextProvider 三路、toolUseContext/forkContextMessages 原样透传）</li>
 *   <li>custom 短路（I-13）—— custom 非空时 defaultAssemble 不被调用（default 完全不出现在结果）</li>
 *   <li>Holder 槽位 save/get/clear + ThreadLocal 会话隔离（并发 loop 防串台）</li>
 * </ol>
 */
class CacheSharingParamsBuilderTest {

    private static final String SESSION_DATE = "2026-08-06";

    @TempDir
    Path tmp;

    private FakeEnv env;
    private GitStatusProvider fakeGit;
    private UserContextProvider fakeUser;

    @BeforeEach
    void setUp() {
        env = new FakeEnv();
        fakeGit = new GitStatusProvider(tmp) {
            @Override
            public String getGitStatus() {
                return "GIT-BLOCK";
            }
        };
        fakeUser = new UserContextProvider(tmp) {
            @Override
            public String claudeMd() {
                return "项目指令";
            }

            @Override
            public String currentDate(String sessionStartDate) {
                return "Today's date is " + sessionStartDate + ".";
            }
        };
    }

    private SystemPromptContextProvider provider() {
        return new SystemPromptContextProvider(SESSION_DATE, fakeUser, fakeGit, env);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · 5 字段来源正确
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("custom 短路（I-13）: systemPrompt=custom，systemContext 跳过，userContext/toolUseContext/forkContextMessages 透传")
    void build_customShortCircuit_fiveFieldsSourcesCorrect() {
        ToolUseContext tuc = baseContext();
        List<ChatMessageDto> forkMsgs = List.of(userMessage("f1", "fork context 1"));
        AtomicInteger assembleCalls = new AtomicInteger();

        CacheSafeParams cs = CacheSharingParamsBuilder.build(
            provider(),
            () -> {
                assembleCalls.incrementAndGet();
                return SystemPrompt.from(List.of("DEFAULT-1", "DEFAULT-2"));
            },
            "CUSTOM-PROMPT",
            null,   // appendSystemPrompt（RES-SP31 接线；本用例无追加）
            tuc,
            forkMsgs,
            false); // [RES-R4-1] gate 显式传（3P 默认场景）

        // systemPrompt = custom 数组（default 完全不出现在结果 · CC systemPrompt.ts:118-119 +
        //   queryContext.ts:62-63）；[RES-R4] 数组语义（forkedAgent.ts:59），非扁平 String
        assertThat(cs.systemPrompt()).containsExactly("CUSTOM-PROMPT");
        // userContext 来自 SystemPromptContextProvider.getUserContext（claudeMd + currentDate）
        assertThat(cs.userContext()).containsEntry("claudeMd", "项目指令");
        assertThat(cs.userContext()).containsKey("currentDate");
        // I-13 custom 短路：systemContext 跳过（queryContext.ts:71）→ 空 map
        assertThat(cs.systemContext()).isEmpty();
        // toolUseContext / forkContextMessages 原样透传（forkedAgent.ts:65/67）
        assertThat(cs.toolUseContext()).isSameAs(tuc);
        assertThat(cs.forkContextMessages()).isSameAs(forkMsgs);
        // custom 短路：defaultAssemble 不被调用
        assertThat(assembleCalls).hasValue(0);
    }

    @Test
    @DisplayName("无 custom: systemPrompt=default 组装 + systemContext(gitStatus) 并入，userContext 照常")
    void build_noCustom_defaultAssemblyAndSystemContextIncluded() {
        ToolUseContext tuc = baseContext();
        List<ChatMessageDto> forkMsgs = List.of(userMessage("f1", "fork context 1"));
        AtomicInteger assembleCalls = new AtomicInteger();

        CacheSafeParams cs = CacheSharingParamsBuilder.build(
            provider(),
            () -> {
                assembleCalls.incrementAndGet();
                return SystemPrompt.from(List.of("DEFAULT-1", "DEFAULT-2"));
            },
            null,   // 无 custom → default 组装
            null,   // appendSystemPrompt（RES-SP31 接线；本用例无追加）
            tuc,
            forkMsgs,
            false); // [RES-R4-1] gate 显式传（3P 默认场景）

        // defaultAssemble 恰好被调用一次（CC getSystemPrompt compact.ts:259-263）
        assertThat(assembleCalls).hasValue(1);
        // systemPrompt = default 元素数组 + systemContext(gitStatus) 并入（[RES-R4] 数组语义）
        assertThat(cs.systemPrompt())
            .as("default 元素 + appendSystemContext 并入的 gitStatus 块，顺序保持发送序")
            .containsExactly("DEFAULT-1", "DEFAULT-2", "gitStatus: GIT-BLOCK");
        // systemContext 来源 = getSystemContext（git 块）
        assertThat(cs.systemContext()).containsEntry("gitStatus", "GIT-BLOCK");
        // userContext 不受 custom 影响
        assertThat(cs.userContext()).containsEntry("claudeMd", "项目指令");
    }

    @Test
    @DisplayName("appendSystemPrompt 接线: 恒末尾追加进 cache-safe systemPrompt（CC compact.ts:274）")
    void build_withAppendSystemPrompt_appendTailInSystemPrompt() {
        ToolUseContext tuc = baseContext();
        List<ChatMessageDto> forkMsgs = List.of(userMessage("f1", "fork context 1"));

        CacheSafeParams cs = CacheSharingParamsBuilder.build(
            provider(),
            () -> SystemPrompt.from(List.of("DEFAULT-1", "DEFAULT-2")),
            null, "APPEND-CMD",
            tuc, forkMsgs, false); // [RES-R4-1] gate 显式传（3P 默认场景）

        // default 组装元素后紧跟 append（EffectiveSystemPromptBuilder 恒末尾追加 :121）→
        // systemContext(gitStatus) 由 appendSystemContext 再并到末尾。断言 append 紧跟 default、
        // 在 systemContext 之前 = 恒末尾语义（CC buildEffectiveSystemPrompt 结果 + api.ts:437-447 并入）。
        assertThat(cs.systemPrompt())
            .as("append 必须紧跟 default 元素末尾（恒末尾追加），随后才是 systemContext 并入（[RES-R4] 数组语义）")
            .containsExactly("DEFAULT-1", "DEFAULT-2", "APPEND-CMD", "gitStatus: GIT-BLOCK");
    }

    @Test
    @DisplayName("custom + append: systemPrompt 恰为 [custom, append]（CC systemPrompt.ts:118-119,121）")
    void build_customWithAppend_customThenAppend() {
        ToolUseContext tuc = baseContext();
        List<ChatMessageDto> forkMsgs = List.of(userMessage("f1", "fork context 1"));

        CacheSafeParams cs = CacheSharingParamsBuilder.build(
            provider(),
            () -> SystemPrompt.from(List.of("DEFAULT-1", "DEFAULT-2")),
            "CUSTOM-PROMPT", "APPEND-CMD",
            tuc, forkMsgs, false); // [RES-R4-1] gate 显式传（3P 默认场景）

        assertThat(cs.systemPrompt())
            .as("custom 替换 default，append 恒末尾追加在其后（不出现 default 元素）；[RES-R4] 数组语义")
            .containsExactly("CUSTOM-PROMPT", "APPEND-CMD")
            .doesNotContain("DEFAULT-1");
    }

    @Test
    @DisplayName("fail-safe: toolUseContext 或 sysPromptCtxProvider 为 null → 返回 null（不阻断压缩）")
    void build_nullInputs_returnsNull() {
        ToolUseContext tuc = baseContext();
        List<ChatMessageDto> forkMsgs = List.of(userMessage("f1", "ctx"));

        // toolUseContext null → null（CacheSafeParams 紧凑构造对 null TUC 抛异常，前置守卫返回 null）
        assertThat(CacheSharingParamsBuilder.build(
            provider(), () -> SystemPrompt.from(List.of("D")), null, null, null, forkMsgs, false)).isNull();
        // sysPromptCtxProvider null → null
        assertThat(CacheSharingParamsBuilder.build(
            null, () -> SystemPrompt.from(List.of("D")), null, null, tuc, forkMsgs, false)).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · [RES-R4] fork 缓存共享 firstParty/boundary 语义（OPD-SP-24 R4）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RES-R4 REQ-R4-2/1/3: firstParty gate + boundary → systemPrompt 保留发送前数组（boundary 元素在），fork split 与主线程同输出（boundary 剥离、静态→GLOBAL、动态→NULL）")
    void build_firstPartyGate_boundaryArrayPreservedAndForkSplitMatchesMainThread() {
        ToolUseContext tuc = baseContext();
        List<ChatMessageDto> forkMsgs = List.of(userMessage("f1", "ctx"));
        String boundary = SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY;
        // 隔离数组内容断言：CLAUDE_CODE_REMOTE truthy → gitStatus 跳过 → systemContext 空 →
        // appendSystemContext 不并入额外元素（context.ts:125 CCR 跳过 git status）
        env.put("CLAUDE_CODE_REMOTE", "true");

        // 主线程 firstParty 场景：defaultAssemble 产出含 boundary 的数组（SystemPromptAssembler
        //   在 globalCacheScopeGate=true 时插入 boundary · SystemPromptAssembler:92-93），
        //   构建器以同一 gate 求值注入 CacheSafeParams（LlmAgentLoop buildCompactCacheSafeParams
        //   传 useGlobalCacheScope(params.config()) = 主线程 :2807 同一判定）。
        CacheSafeParams cs = CacheSharingParamsBuilder.build(
            provider(),
            () -> SystemPrompt.from(List.of("STATIC-1", "STATIC-2", boundary, "DYNAMIC-1")),
            null, null,
            tuc, forkMsgs,
            true);   // firstParty gate（REQ-R4-3）

        // REQ-R4-2: fork 的 systemPrompt 必须与主线程发送前数组字节一致（含 boundary 元素），
        //   不中途扁平化为 String（旧实现 String.join 丢失 boundary → RED）。
        assertThat(cs.systemPrompt())
            .as("fork systemPrompt = 主线程发送前数组（含 boundary 元素）")
            .containsExactly("STATIC-1", "STATIC-2", boundary, "DYNAMIC-1");
        assertThat(cs.useGlobalCacheScope()).as("gate 值随 CacheSafeParams 注入").isTrue();

        // REQ-R4-1: fork 发送边界（StreamCompactSummary streamOnce 现以
        //   splitSysPromptPrefix(cs.systemPrompt(), cs.useGlobalCacheScope(), false) 拆分）
        //   = 主线程 LlmAgentLoop:2807 同一输出：boundary 剥离、静态块 GLOBAL、动态块 NULL。
        List<SystemPromptBlock> forkBlocks = SystemPromptSplitter.splitSysPromptPrefix(
            cs.systemPrompt(), cs.useGlobalCacheScope(), false);
        assertThat(forkBlocks).as("boundary 剥离后 ≤2 block（静态+动态，无 prefix/attribution）").hasSize(2);
        assertThat(forkBlocks.get(0))
            .as("boundary 前静态块 join 为 cacheScope=global 单 block")
            .isEqualTo(new SystemPromptBlock("STATIC-1\n\nSTATIC-2", CacheScope.GLOBAL));
        assertThat(forkBlocks.get(1))
            .as("boundary 后动态块 join 为 cacheScope=null")
            .isEqualTo(new SystemPromptBlock("DYNAMIC-1", CacheScope.NULL));
        assertThat(forkBlocks).extracting(SystemPromptBlock::text)
            .as("boundary 本身永不发送（I-8 / api.ts:369-379）")
            .doesNotContain(boundary);
    }

    @Test
    @DisplayName("RES-R4 REQ-R4-4: 3P 默认（gate=false 无 boundary）→ fork split 默认模式输出字节与旧 flat String 拼接一致（无回归）")
    void build_3pDefault_flatJoinByteEquivalent() {
        ToolUseContext tuc = baseContext();
        List<ChatMessageDto> forkMsgs = List.of(userMessage("f1", "ctx"));

        CacheSafeParams cs = CacheSharingParamsBuilder.build(
            provider(),
            () -> SystemPrompt.from(List.of("DEFAULT-1", "DEFAULT-2")),
            null, null, tuc, forkMsgs, false);

        // REQ-R4-4: 非 firstParty（无 boundary）→ split 默认模式（rest 以 \n\n 拼接为单 ORG block），
        //   与改造前无条件 String.join("\n\n", fullSystemPrompt) 字节等价 → fork 缓存仍命中。
        String flatJoin = String.join("\n\n", cs.systemPrompt());
        String splitJoin = SystemPromptSplitter.splitSysPromptPrefix(cs.systemPrompt(), false, false)
            .stream().map(SystemPromptBlock::text).collect(Collectors.joining("\n\n"));
        assertThat(splitJoin).as("3P 默认 fork split 输出 == 旧 flat 拼接（无回归）").isEqualTo(flatJoin);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · Holder 槽位 save/get/clear + ThreadLocal 会话隔离
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Holder: save/get 同线程可读，clear 后为 null")
    void holder_saveGetClear() {
        ToolUseContext tuc = baseContext();
        CacheSafeParams cs = new CacheSafeParams(List.of("sys"), Map.of(), Map.of(), tuc, List.of());

        CacheSafeParamsHolder.clear();
        assertThat(CacheSafeParamsHolder.get()).isNull();

        CacheSafeParamsHolder.save(cs);
        assertThat(CacheSafeParamsHolder.get()).isSameAs(cs);

        CacheSafeParamsHolder.clear();
        assertThat(CacheSafeParamsHolder.get()).isNull();
    }

    @Test
    @DisplayName("Holder: ThreadLocal 会话隔离——主线程 save 不影响其他线程（并发 loop 防串台）")
    void holder_threadLocalIsolation() throws InterruptedException {
        ToolUseContext tuc = baseContext();
        CountDownLatch otherReady = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<CacheSafeParams> otherThreadGet = new AtomicReference<>();

        Thread other = new Thread(() -> {
            try {
                otherReady.countDown();
                proceed.await(2, TimeUnit.SECONDS);
                otherThreadGet.set(CacheSafeParamsHolder.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        other.start();
        otherReady.await(2, TimeUnit.SECONDS);

        CacheSafeParamsHolder.clear();
        CacheSafeParams mainCs = new CacheSafeParams(List.of("main-sys"), Map.of(), Map.of(), tuc, List.of());
        CacheSafeParamsHolder.save(mainCs);

        proceed.countDown();
        other.join(2_000);

        // 其他线程读不到主线程槽位（null）；主线程可读同一实例
        assertThat(otherThreadGet.get()).isNull();
        assertThat(CacheSafeParamsHolder.get()).isSameAs(mainCs);

        CacheSafeParamsHolder.clear();
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试工具
    // ════════════════════════════════════════════════════════════════════

    /** 最小 ToolUseContext（8 参兼容构造器 · 对齐 RunForkedAgentTest.baseContext）。 */
    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of());
    }

    /** 构造 user 消息（对齐 RunForkedAgentTest.userMessage 语义）。 */
    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    /** 可控环境变量查询（对齐 SystemPromptContextProviderTest.FakeEnv）。 */
    private static final class FakeEnv implements SystemPromptContextProvider.Environment {
        private final Map<String, String> env = new HashMap<>();

        void put(String key, String value) {
            env.put(key, value);
        }

        @Override
        public String get(String key) {
            return env.get(key);
        }
    }
}
