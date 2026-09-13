package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.compact.fork.CacheSharingParamsBuilder;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.prompt.UserContextProvider;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [TL-W1b P1] compact 链的会话 projectRoot 直传契约（W1 残留闭环）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：W1 已把 print 侧会话态违规修掉（fork 参数
 * {@code ForkedAgentParams.projectRoot} 四级直传 → {@code QueryLoopForkedQuery} 用它构造隔离 ctx），
 * 但 <b>compact 链没接线</b>：{@code StreamCompactSummary.tryForkCacheSharing} 构造
 * {@code ForkedAgentParams} 时只有 {@link CacheSafeParams} 在手，故会话态必须<b>随
 * CacheSafeParams 一起流过</b>。
 *
 * <p><b>缺陷形态（无任何报错，静默错目录）</b>：compact fork 由 {@code RunForkedAgent.run} 在
 * <b>fork 线程</b>构造隔离 ctx；plain ThreadLocal（{@code AutoMemPaths.currentSessionProjectRoot()}
 * / MDC）在 fork 线程不继承 ⇒ fork 内现算会回落 {@code ~/.nexusai}（config home）
 * 并被 {@code AgentLoopContextFactory.freshSession} 当成项目根 ⇒ fork 的
 * {@code ctx.workspaceDir} 恒为 config home 而非绑定项目（下游按「无有效项目」判定 → 记忆/技能/
 * transcript 归属全错）。
 *
 * <p>本测试钉三件事：
 * <ol>
 *   <li><b>解析点（会话线程 / 按 sessionId 现算）</b>：manual/partial 路径
 *       （{@link CacheSharingParamsBuilder#build}）与主循环 auto/reactive 路径
 *       （{@code LlmAgentLoop.buildCompactCacheSafeParams}）都必须把会话冻结 projectRoot 写进
 *       {@link CacheSafeParams#projectRoot()}；未绑定 → null（<b>绝不</b>回落 config home 冒充）。</li>
 *   <li><b>透传点</b>：{@link StreamCompactSummary} 必须把它转发到 fork 参数（删除
 *       {@code withProjectRoot} → 本测试 RED）。</li>
 *   <li><b>零 ThreadLocal</b>：本测试在<b>主测试线程</b>注册 / 清理，不做任何线程局部注入 ——
 *       解析完全由 sessionId 走全局表（{@code SessionProjectRoot}），故断言可复现。</li>
 * </ol>
 */
@DisplayName("[TL-W1b P1] compact 链: CacheSafeParams.projectRoot（会话 thread 解析 → fork 参数直传）")
class CompactForkProjectRootWiringTest {

    private static final String SESSION = "sess-tlw1b-compact";

    @AfterEach
    void tearDown() {
        SessionProjectRoot.clearSession(SESSION);
        CacheSafeParamsHolder.clear();
    }

    // ════════════════════════════════════════════════════════════════════
    // ① 解析点 · manual/partial 路径（CacheSharingParamsBuilder.build）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("manual/partial: CacheSafeParams.projectRoot = toolUseContext.sessionId() 的会话冻结根")
    void builder_resolvesProjectRootFromSessionId(@TempDir Path projectRoot) {
        SessionProjectRoot.setForSession(SESSION, projectRoot.toString());

        CacheSafeParams cs = CacheSharingParamsBuilder.build(
            sysPromptCtx(),
            () -> SystemPrompt.from(List.of("DEFAULT-1")),
            "CUSTOM-PROMPT",     // I-13 custom 短路（不调 defaultAssemble）
            null,
            ctx(SESSION),
            List.of(userMessage("f1", "ctx1")),
            false);

        assertThat(cs.projectRoot())
            .as("manual/partial 压缩 fork 的会话根必须由**会话线程**按 sessionId 解析后写入 "
                + "CacheSafeParams（删掉该实参 → fork ctx.workspaceDir 落 config home 冒充项目根）")
            .isEqualTo(projectRoot.toString());
    }

    @Test
    @DisplayName("manual/partial: 会话未绑定项目 → projectRoot=null（不造值，绝不回落 config home）")
    void builder_unboundSession_leavesProjectRootNull() {
        CacheSafeParams cs = CacheSharingParamsBuilder.build(
            sysPromptCtx(),
            () -> SystemPrompt.from(List.of("DEFAULT-1")),
            "CUSTOM-PROMPT", null,
            ctx("sess-tlw1b-unbound"),
            List.of(userMessage("f1", "ctx1")),
            false);

        assertThat(cs.projectRoot())
            .as("未绑定 → null（fork 端 shared(null) 走 CwdResolution originalCwd 回落；"
                + "写 config home 会伪造项目根）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // ② 解析点 · 主循环 auto/reactive（LlmAgentLoop.buildCompactCacheSafeParams）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("主循环 auto/reactive: CacheSafeParams.projectRoot = state.sessionId() 的会话冻结根")
    void mainLoopCompact_resolvesProjectRootFromStateSessionId(@TempDir Path projectRoot) throws Exception {
        SessionProjectRoot.setForSession(SESSION, projectRoot.toString());

        CacheSafeParams cs = invokeBuildCompactCacheSafeParams(ctx(SESSION), SESSION);

        assertThat(cs.projectRoot())
            .as("主循环 auto-compact(:5504)/reactive(:7154) 的 fork 会话根必须来自 state.sessionId()"
                + "（会话线程解析一次；fork 线程零会话态现算）")
            .isEqualTo(projectRoot.toString());
    }

    @Test
    @DisplayName("主循环 auto/reactive: 会话未绑定 → projectRoot=null（不退化成 config home 项目根）")
    void mainLoopCompact_unboundSession_leavesProjectRootNull() throws Exception {
        CacheSafeParams cs = invokeBuildCompactCacheSafeParams(ctx("sess-tlw1b-unbound"), "sess-tlw1b-unbound");

        assertThat(cs.projectRoot()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ 透传点 · StreamCompactSummary → ForkedAgentParams（fork 参数）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("StreamCompactSummary: CacheSafeParams.projectRoot 必须落到 fork 参数（删除 withProjectRoot → RED）")
    void streamCompactSummary_forwardsProjectRootToForkParams(@TempDir Path projectRoot) {
        ForkConvergenceCcContractTest.RecordingQuery recording =
            new ForkConvergenceCcContractTest.RecordingQuery();
        recording.respond(new ForkedAgentResult(
            List.of(assistantMessage("summary text")), ForkedAgentResult.ForkUsage.empty()));

        CacheSafeParams cs = new CacheSafeParams(
            List.of("systemPrompt"), Map.of(), Map.of(), ctx(SESSION),
            List.of(userMessage("c1", "ctx1")), false,
            projectRoot.toString());   // [TL-W1b P1] 会话线程解析产物（主循环 / manual 命令已写入）

        compactSummaryWith(cs, recording).streamCompactSummary(
            List.of(userMessage("u1", "ctx")), "请对会话做摘要", 0,
            "model", fakeProvider(), ProviderConfig.empty());

        RunForkedAgent.ForkQueryParams q = recording.lastParams();
        assertThat(q).as("必须真实发起 fork（否则本测试空转）").isNotNull();
        assertThat(q.projectRoot())
            .as("会话态必须随 fork 参数直达到 query seam（QueryLoopForkedQuery 用它造隔离 ctx）"
                + "—— 漏转发 = fork 内落 config home 冒充项目根（静默错目录）")
            .isEqualTo(projectRoot.toString());
    }

    @Test
    @DisplayName("StreamCompactSummary: 构造点无会话上下文（6 参构造 projectRoot=null）→ fork 参数不造字段")
    void streamCompactSummary_noProjectRoot_keepsNull() {
        ForkConvergenceCcContractTest.RecordingQuery recording =
            new ForkConvergenceCcContractTest.RecordingQuery();
        recording.respond(new ForkedAgentResult(
            List.of(assistantMessage("summary text")), ForkedAgentResult.ForkUsage.empty()));

        CacheSafeParams cs = new CacheSafeParams(
            List.of("systemPrompt"), Map.of(), Map.of(), ctx("sess-tlw1b-unbound"),
            List.of(userMessage("c1", "ctx1")));   // 6 参兼容构造 → projectRoot=null

        compactSummaryWith(cs, recording).streamCompactSummary(
            List.of(userMessage("u1", "ctx")), "请对会话做摘要", 0,
            "model", fakeProvider(), ProviderConfig.empty());

        assertThat(recording.lastParams().projectRoot())
            .as("null = 不造字段（fork 端 shared(null) 走 CwdResolution originalCwd 回落，非 config home）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试工具
    // ════════════════════════════════════════════════════════════════════

    /**
     * 反射调用 {@code LlmAgentLoop.buildCompactCacheSafeParams(QueryParams, AgentState)}（private static）。
     *
     * <p>为什么反射：该方法是主循环压缩 fork 的<b>唯一</b> CacheSafeParams 生产点
     * （:5504 auto / :7154 reactive），private static 无公开等价入口；本测试只观测其产物的
     * projectRoot 字段，不触发 loop 全链（对齐本仓既有的 private static 反射测试范式）。
     */
    private static CacheSafeParams invokeBuildCompactCacheSafeParams(ToolUseContext tuc, String sessionId)
            throws Exception {
        QueryParams params = QueryParams.forLoop(
            List.of(), List.of("SYS"), tuc, QuerySource.REPL_MAIN_THREAD, "model",
            null, null, null, null, null, null, ProviderConfig.empty());
        AgentState state = new AgentState("SYS", sessionId, null);

        Method m = Class.forName("com.nexusai.application.agent.LlmAgentLoop")
            .getDeclaredMethod("buildCompactCacheSafeParams", QueryParams.class, AgentState.class);
        m.setAccessible(true);
        return (CacheSafeParams) m.invoke(null, params, state);
    }

    /** 会话 ToolUseContext（sessionId = 会话键，即 SessionProjectRoot 的登记键）。 */
    private static ToolUseContext ctx(String sessionId) {
        return new ToolUseContext(
            UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of());
    }

    /** 13 参构造 + fork seam（RecordingQuery）· fork 参数断言面。 */
    private static StreamCompactSummary compactSummaryWith(
            CacheSafeParams cs, ForkConvergenceCcContractTest.RecordingQuery recording) {
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> fakeProvider(), () -> "model", ProviderConfig::empty,
            () -> cs, () -> new AbortController(), null, null, false, true, false, null, null, null);
        scs.setForkedQuery(recording);
        return scs;
    }

    /** systemPrompt 上下文 provider（三路 provider；custom 短路下只用到 userContext）。 */
    private static SystemPromptContextProvider sysPromptCtx() {
        return new SystemPromptContextProvider(
            "2026-08-06",
            new UserContextProvider() {
                @Override public String claudeMd() { return "项目指令"; }
                @Override public String currentDate(String sessionStartDate) {
                    return "Today's date is " + sessionStartDate + ".";
                }
            },
            new GitStatusProvider() {
                @Override public String getGitStatus() { return ""; }
            });
    }

    /** fake provider（fork 路径不经 provider；仅兜底引用）。 */
    private static LlmProvider fakeProvider() {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> blocks,
                                         List<ChatMessageDto> h, ArrayNode t, Integer maxOut,
                                         TaskBudgetParam tb, String ev, String qs,
                                         Consumer<String> oc, Consumer<AssistantMessage> oa,
                                         java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                                         Consumer<String> orc, Runnable osf, AbortController ac,
                                         Consumer<Throwable> oe, Runnable ocp, Boolean skipCacheWrite,
                    com.nexusai.application.agent.subagent.AgentContext agentContext) {
                oa.accept(new AssistantMessage("summary text", "stop", List.of()));
                ocp.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "summary text";
            }
        };
    }

    /** assistant 消息（fork 结果提取面：必须有文本，否则落流式 fallback）。 */
    private static ChatMessageDto assistantMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, "assistant",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, null,
            false, null, null, null, null, null, null, false, false);
    }

    /** user 消息（对齐 RunForkedAgentTest.userMessage 语义）。 */
    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }
}
