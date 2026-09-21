package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.attachment.SessionChangedFilesBaselineRegistry;
import com.nexusai.application.agent.compact.PostCompactCleanup;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryFileDetection;
import com.nexusai.application.agent.prompt.PromptCacheGroup;
import com.nexusai.application.agent.prompt.SessionPromptCacheRegistry;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.test.support.OutboundRequest;
import com.nexusai.test.support.PrefixStabilityAssert;
import com.nexusai.test.support.RecordingLlmProvider;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>步骤 8 · 前缀缓存防回归判据（离线层）</b>：把「会话级头部冻结 + 消息严格前缀扩展」
 * 变成<b>可判绿的不变量</b>，并配齐<b>反面实验</b>证明它有判别力。
 *
 * <h2>真源映射（逐条 · 均为实施者自己读到的，非计划转抄）</h2>
 * <ol>
 *   <li><b>严格前缀扩展</b>：{@code D:/code/deepseek-harness/packages/core/agent-loop/tests/
 *       request-reconstruction.spec.ts:55-60} 的 {@code expectPrefixExtension}
 *       —— {@code messages} 严格变长、前 N 条逐值相等、{@code system} / {@code tools} 相等。
 *       本仓实现在 {@link PrefixStabilityAssert#expectPrefixExtension}。</li>
 *   <li><b>请求必须冻结且等于派生值</b>：同仓 {@code src/invariant.ts:21-53}
 *       （{@code a loop-built request must be frozen} / {@code session.deriveMessages()} 逐字节比对）。
 *       本仓对应物 = {@link PrefixStabilityAssert#expectHeadFrozen}：同一会话相邻两次请求的
 *       {@code system} 串 / {@code tools} 数组 / {@code messages[0]} 三者逐字节相等。</li>
 *   <li><b>仅凭日志重建</b>：同 spec {@code :566-612} 的 THEOREM 型断言
 *       （{@code Session.create(id, events.slice(0, seq))} 重建 ⇒ 与实发请求逐字节相等）。
 *       本仓形态见 {@link #theorem_headIsAFunctionOfTheSessionNotOfTheLoopInstance()}：
 *       <b>另一个 loop 实例</b>、同一会话、同一日志 ⇒ 头部三构件逐字节相等。</li>
 * </ol>
 *
 * <h2>⭐ 本类的核心设计：每条正向判据都配一条<b>反面</b>（否则等于没判）</h2>
 * <p>「头部跨 run 不变」这条断言最容易写成<b>恒绿</b>（比如把比较对象取成同一个对象、
 * 或让变异根本走不到被守护的路径）。故本类对<b>两个可控失效源</b>各出一对正反用例：
 * <table border="1">
 *   <caption>正反对照矩阵</caption>
 *   <tr><th>失效源</th><th>不变（冻结生效）</th><th>显式失效事件后必须变（否则失效没接上）</th></tr>
 *   <tr><td>claudeMd 内容（集合 B / userContext）</td>
 *       <td>{@link #claudeMdChangedWithoutInvalidation_headStaysFrozen()}</td>
 *       <td>{@link #compactEvent_makesHeadAssertionFail()}（可达的 /compact 事件）<br>
 *           {@link #clearChainBody_makesHeadAssertionFail()}（/clear 链体 · 见其 javadoc 的入口停用登记）</td></tr>
 *   <tr><td>会话 cwd（集合 A 的 {@code env_info_simple} 段）</td>
 *       <td>{@link #cwdChangedWithoutInvalidation_headStaysFrozen()}</td>
 *       <td>{@link #worktreeSectionsClear_makesHeadAssertionFail()}</td></tr>
 * </table>
 * <p>⭐ 左边那一列<b>不是多余的</b>：没有它，右边的「变红了」就无法归因给失效事件
 * （可能只是「源变了头部自然就变」——那就根本没有冻结这回事）。两侧同时成立，才证明
 * <b>「冻结」与「显式失效」是同一枚硬币的两面</b>（CC §1.3 的纪律）。
 *
 * <h2>变异形态的选择（对齐 counter-control-must-reach-guarded-path）</h2>
 * <p>反面侧的变异取<b>最激进形态</b>，不是「改坏一点点」：
 * <ul>
 *   <li>{@link #compactEvent_makesHeadAssertionFail} / {@link #clearChainBody_makesHeadAssertionFail}：
 *       <b>真的把 CLAUDE.md 改盘</b>（内容全换）+ 走完整的失效集合 —— 让「重算」的结果必然与
 *       冻结值不同。⛔ 若只清不换源，重算结果与冻结值恰好相同 ⇒ 断言仍绿 ⇒ 反面实验<b>失败</b>，
 *       这正是「反面输入走不到被守护路径」的经典陷阱。</li>
 *   <li>{@link #worktreeSectionsClear_makesHeadAssertionFail}：只清集合 A（worktree 进/出的
 *       精确集合），并真的把<b>会话 cwd 改到另一个目录</b> —— {@code env_info_simple} 段的
 *       {@code Primary working directory} 重算后必然不同。</li>
 * </ul>
 * <p>⭐ 反面侧的断言写法是「<b>必须抛错</b>」而不是「不抛错」（见
 * {@link PrefixStabilityAssert#expectHeadFrozenFails}）—— 于是「失效没接上、头部没变」会
 * <b>直接判红</b>，而不是静默变成一条恒绿的假判据。实施期已用最激进变异（把
 * {@code PostCompactCleanup} 的引擎接线那行注释掉 ⇒ 内层 memoize 不清 ⇒ 头部不变）实测过：
 * 该用例立刻翻红（{@code Expecting code to raise a throwable}）。</p>
 *
 * <p>纯 JUnit（⛔ 无 Spring / 无 @SpringBootTest / 无真 API）：真临时目录 + 真
 * {@link ClaudemdEngine} + 真 {@code LlmAgentLoop.run(RunRequest)} 全链，provider 边界用
 * {@link RecordingLlmProvider} 记录出站请求。
 */
@DisplayName("步骤 8 · 前缀缓存防回归：会话级头部冻结 + 消息严格前缀扩展（含反面实验）")
class PromptHeadFreezePrefixInvariantTest {

    /** 会话键（short 形态，与 SessionService.generateId 前缀一致）。 */
    private static final String SESSION = "sess-cache-head-001";

    /** streamUserMessageId（DB 历史注入按它排除在途用户消息；与对话日志无交集即可）。 */
    private static final String STREAM_MSG_ID = "msg-curr-001";

    @TempDir
    Path workspace;

    /** 第二个目录（cwd 维度变异用：切 cwd ⇒ {@code Primary working directory} 段重算后必然不同）。 */
    @TempDir
    Path otherDir;

    private Path configHome;
    private Path managedPath;
    private Path memoryBase;
    private ClaudemdEngine engine;

    /** DB 历史（由测试驱动：= 上一 run 实发请求去掉头部元消息后的切片）。 */
    private final AtomicReference<List<ChatMessageDto>> dbHistory = new AtomicReference<>(List.of());

    @BeforeEach
    void setUp() throws Exception {
        SessionProjectRootTestSupport.declareNoDatabase();
        SessionProjectRoot.reset();
        SessionPromptCacheRegistry.resetForTest();
        // [步骤 7 修正] 会话级变更基线表按 sessionId 分区且跨 run 存活；本用例用固定 SESSION，
        //   不清会在用例之间串（上一条用例的基线让「跨 run 首次登记」不再是首次）。
        SessionChangedFilesBaselineRegistry.resetForTest();
        SessionCwdHolder.reset();

        Path realWorkspace = workspace.toRealPath();
        SessionProjectRoot.setForSession(SESSION, realWorkspace.toString());

        configHome = Files.createTempDirectory("cache-head-config");
        managedPath = Files.createTempDirectory("cache-head-managed");
        memoryBase = Files.createTempDirectory("cache-head-memory");
        ClaudePaths.setConfigDirOverride(configHome.toString());
        ClaudePaths.setManagedFilePathOverride(managedPath.toString());
        NexusaiPaths.setAppNameOverride("nexusai-cache-head-" + configHome.getFileName());

        AutoMemPaths autoMemPaths = new AutoMemPaths(
            () -> realWorkspace.toString(),
            () -> memoryBase.toString(),
            () -> realWorkspace.toString() + java.io.File.separator,
            () -> null);
        MemoryFileDetection detection = new MemoryFileDetection(
            autoMemPaths, () -> configHome.toString(), () -> true, () -> false, () -> false);
        engine = new ClaudemdEngine(autoMemPaths, detection,
            sessionId -> realWorkspace.toString(),   // CC getOriginalCwd()（忽略会话的定值注入）
            () -> true, () -> true, () -> true,
            () -> false,                             // feature('TEAMMEM')
            () -> List.of());                        // claudeMdExcludes（无 settings 源 → 空）
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);
        // 复位 PostCompactCleanup 的静态协作器（构造器是唯一写点；传 null 即复位，防跨用例静态污染）
        new PostCompactCleanup(null, null);
        SessionProjectRoot.reset();
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionPromptCacheRegistry.resetForTest();
        SessionChangedFilesBaselineRegistry.resetForTest();
        SessionCwdHolder.reset();
    }

    // ════════════════════ 正向 ① · 跨 run 冻结 + 严格前缀扩展 ════════════════════

    @Test
    @DisplayName("跨 run：同会话相邻两次请求 —— system/tools/messages[0] 逐字节相等 且 messages 严格前缀扩展")
    void crossRun_headFrozen_andMessagesStrictlyExtend() throws Exception {
        writeClaudeMd("v1");
        RecordingLlmProvider provider = RecordingLlmProvider.of(
            new AssistantMessage("ack-1", "stop", List.of()));
        LlmAgentLoop loop = newLoop(provider);

        OutboundRequest req0 = runAndCapture(loop, provider, "第一问");
        // 第二 run 的「历史」= 上一 run 实发请求去掉头部元消息后的切片（= 会话日志的本仓载体）
        dbHistory.set(req0.messages().subList(1, req0.messages().size()));
        OutboundRequest req1 = runAndCapture(loop, provider, "第二问");

        assertThat(req1.messageCount())
            .as("第二 run 的消息必须比第一 run 多（新用户消息）")
            .isGreaterThan(req0.messageCount());
        PrefixStabilityAssert.expectFrozenHeadAndPrefixExtension(req0, req1);
    }

    // ════════════════════ 正向 ② · THEOREM 型：头部只由会话决定，与 loop 实例无关 ════════════════════

    @Test
    @DisplayName("THEOREM：同会话同日志、换一个 loop 实例重建 ⇒ 头部三构件逐字节相等")
    void theorem_headIsAFunctionOfTheSessionNotOfTheLoopInstance() throws Exception {
        writeClaudeMd("v1");
        RecordingLlmProvider providerA = RecordingLlmProvider.of(
            new AssistantMessage("ack-1", "stop", List.of()));
        LlmAgentLoop loopA = newLoop(providerA);
        OutboundRequest reqA0 = runAndCapture(loopA, providerA, "第一问");

        dbHistory.set(reqA0.messages().subList(1, reqA0.messages().size()));

        // ⭐ 独立重建：另一个 LlmAgentLoop + 另一个 provider 实例，同一会话、同一日志
        RecordingLlmProvider providerB = RecordingLlmProvider.of(
            new AssistantMessage("ack-2", "stop", List.of()));
        LlmAgentLoop loopB = newLoop(providerB);
        OutboundRequest reqB = runAndCapture(loopB, providerB, "第二问");

        PrefixStabilityAssert.expectFrozenHeadAndPrefixExtension(reqA0, reqB);
    }

    // ════════════════ 反面 ① · claudeMd 维度：不变 vs 显式失效后必须变 ════════════════

    @Test
    @DisplayName("claudeMd 改盘但【不失效】⇒ 头部仍旧冻结（messages[0] 保留旧 claudeMd）")
    void claudeMdChangedWithoutInvalidation_headStaysFrozen() throws Exception {
        writeClaudeMd("v1");
        RecordingLlmProvider provider = RecordingLlmProvider.of(
            new AssistantMessage("ack-1", "stop", List.of()));
        LlmAgentLoop loop = newLoop(provider);
        OutboundRequest req0 = runAndCapture(loop, provider, "第一问");
        assertThat(req0.headWireMessage())
            .as("前置：头部元消息必须真含 claudeMd 内容 v1（否则本用例的两侧都不可观测）")
            .contains("v1");

        writeClaudeMd("v2");   // 改盘，但⛔ 不触发任何失效事件
        dbHistory.set(req0.messages().subList(1, req0.messages().size()));
        OutboundRequest req1 = runAndCapture(loop, provider, "第二问");

        PrefixStabilityAssert.expectHeadFrozen(req0, req1);
        assertThat(req1.headWireMessage())
            .as("冻结的语义就是「提示里写着陈旧值」——头部必须仍是 v1（CC §1.4 同一取舍）")
            .contains("v1")
            .doesNotContain("v2");
    }

    @Test
    @DisplayName("claudeMd 改盘 + 【/compact 主线程事件】⇒ 同一断言必须失败（否则说明失效没接上）")
    void compactEvent_makesHeadAssertionFail() throws Exception {
        writeClaudeMd("v1");
        RecordingLlmProvider provider = RecordingLlmProvider.of(
            new AssistantMessage("ack-1", "stop", List.of()));
        LlmAgentLoop loop = newLoop(provider);
        OutboundRequest req0 = runAndCapture(loop, provider, "第一问");

        writeClaudeMd("v2");
        // ⭐ 真实可达的显式失效事件：/compact 的清理序列（主线程）
        //   PostCompactCleanup.runPostCompactCleanup(querySource=null → isMainThreadCompact=true, sessionId)
        //   一次执行：集合 B（POST_COMPACT_USER_CONTEXT ← postCompactCleanup.ts:59 getUserContext.cache.clear）
        //   + 内层引擎 resetGetMemoryFilesCache('compact')（:52-59 / claudemd.ts:1119-1122）+ 集合 A（:62）。
        //   ⇒ 两层成对清（外层 userContext + 内层 getMemoryFiles）——这正是头部能真正重算的前提：
        //    实施期实测反证 = 只调 registry.clearPromptCaches(CLEAR_SESSION_ALL)（外层）而**不**调
        //    engine.resetGetMemoryFilesCache 时，头部**不变**（内层 memoize 仍返回旧 claudeMd）⇒
        //    若删掉下面构造器那行接线，本用例会退化成恒绿（这正是计划 §5 步骤 5「内外两层必须成对清」）。
        //   构造器是 STATIC_CLAUDE_MD 的接线点（见 PostCompactCleanup 构造器 javadoc）—— 纯 JUnit 无
        //   Spring 容器，必须显式接线，否则链内的 resetGetMemoryFilesCache 会静默跳过（debug skip）。
        new PostCompactCleanup(null, engine);
        PostCompactCleanup.runPostCompactCleanup(null, SESSION);

        dbHistory.set(req0.messages().subList(1, req0.messages().size()));
        OutboundRequest req1 = runAndCapture(loop, provider, "第二问");

        PrefixStabilityAssert.expectHeadFrozenFails(req0, req1,
            "claudeMd v1→v2 改盘 + /compact 主线程清理序列（集合B + 内层引擎 + 集合A）");
        assertThat(req1.headWireMessage())
            .as("失效生效的正面证据：头部元消息必须看到新内容 v2（两层成对清才可能）")
            .contains("v2");
    }

    @Test
    @DisplayName("claudeMd 改盘 + 【/clear 链体：全集 A+B+C+D+E + 内层引擎】⇒ 同一断言必须失败")
    void clearChainBody_makesHeadAssertionFail() throws Exception {
        writeClaudeMd("v1");
        RecordingLlmProvider provider = RecordingLlmProvider.of(
            new AssistantMessage("ack-1", "stop", List.of()));
        LlmAgentLoop loop = newLoop(provider);
        OutboundRequest req0 = runAndCapture(loop, provider, "第一问");

        writeClaudeMd("v2");
        // ⭐ 最激进的变异形态：/clear 的清理链体（两处调用与 CommandController 的 /clear 分支逐条对应）
        //   ① CommandController.java:453 → clearPromptCaches(CLEAR_SESSION_ALL)（A+B+C+D+E 全集）
        //   ② CommandController.java:491 → ClaudemdEngine.resetGetMemoryFilesCache("session_start")
        //   ⚠ 诚实边界：/clear 的**入口**自 P0-0/N1（2026-09-11）起 fail-loud 停用（该分支为保留不删的
        //     死代码）⇒ 本用例驱动的是「链体本身」，不是可达入口。可达的等价事件见
        //     {@link #compactEvent_makesHeadAssertionFail}（/compact 走同一对清空动作）。
        boolean cleared = SessionPromptCacheRegistry.clearPromptCaches(
            SESSION, PromptCacheGroup.CLEAR_SESSION_ALL, "test: /clear 链体 ①（clearSessionCaches 全集）");
        assertThat(cleared).as("失效必须先真的接上（否则下面的红无从归因）").isTrue();
        engine.resetGetMemoryFilesCache("session_start", SESSION);   // 链体 ②（内层引擎）

        dbHistory.set(req0.messages().subList(1, req0.messages().size()));
        OutboundRequest req1 = runAndCapture(loop, provider, "第二问");

        PrefixStabilityAssert.expectHeadFrozenFails(req0, req1,
            "claudeMd v1→v2 改盘 + /clear 链体（全集 + 内层引擎）");
        PrefixStabilityAssert.expectPrefixExtensionFails(req0, req1,
            "claudeMd v1→v2 改盘 + /clear 链体（全集 + 内层引擎）");
        assertThat(req1.headWireMessage())
            .as("失效生效的正面证据：头部元消息必须看到新内容 v2")
            .contains("v2");
    }

    // ════════════════ 反面 ② · cwd 维度：不变 vs 只清 A（worktree 集合）后必须变 ════════════════

    @Test
    @DisplayName("会话 cwd 改到别处但【不失效】⇒ 头部仍旧冻结（Primary working directory 保持陈旧）")
    void cwdChangedWithoutInvalidation_headStaysFrozen() throws Exception {
        writeClaudeMd("v1");
        RecordingLlmProvider provider = RecordingLlmProvider.of(
            new AssistantMessage("ack-1", "stop", List.of()));
        LlmAgentLoop loop = newLoop(provider);
        OutboundRequest req0 = runAndCapture(loop, provider, "第一问");
        assertThat(req0.systemText())
            .as("前置：system 里必须有 cwd 段，否则本用例不可观测")
            .contains("Primary working directory");

        SessionCwdHolder.set(SESSION, otherDir.toRealPath().toString());
        dbHistory.set(req0.messages().subList(1, req0.messages().size()));
        OutboundRequest req1 = runAndCapture(loop, provider, "第二问");

        PrefixStabilityAssert.expectHeadFrozen(req0, req1);
    }

    @Test
    @DisplayName("会话 cwd 改到别处 + 【只清集合 A（worktree 进/出的精确集合）】⇒ 同一断言必须失败")
    void worktreeSectionsClear_makesHeadAssertionFail() throws Exception {
        writeClaudeMd("v1");
        RecordingLlmProvider provider = RecordingLlmProvider.of(
            new AssistantMessage("ack-1", "stop", List.of()));
        LlmAgentLoop loop = newLoop(provider);
        OutboundRequest req0 = runAndCapture(loop, provider, "第一问");

        SessionCwdHolder.set(SESSION, otherDir.toRealPath().toString());
        // ⭐ worktree 进/出的精确集合 = 只有 A（CC EnterWorktreeTool.ts:99 / ExitWorktreeTool.ts:143
        //   都只调 clearSystemPromptSections）—— 本用例同时钉住「清 A 足够打掉 cwd 段」
        boolean cleared = SessionPromptCacheRegistry.clearPromptCaches(
            SESSION, PromptCacheGroup.WORKTREE_SECTIONS, "test: 模拟 worktree 进/出（只清 A）");
        assertThat(cleared).as("失效必须先真的接上").isTrue();

        dbHistory.set(req0.messages().subList(1, req0.messages().size()));
        OutboundRequest req1 = runAndCapture(loop, provider, "第二问");

        PrefixStabilityAssert.expectHeadFrozenFails(req0, req1,
            "会话 cwd 改到 otherDir + WORKTREE_SECTIONS（只清 A）");
        assertThat(req1.systemText())
            .as("失效生效的正面证据：重算后的 env_info_simple 必须写出新 cwd")
            .contains("Primary working directory: "
                + otherDir.toRealPath().toString().replace('\\', '/'));
    }

    // ════════════ 步骤 8 第 4 条 · freshness 与头部冻结必须【两条同时成立】 ════════════

    @Test
    @DisplayName("跨 run 改 CLAUDE.md（用户两条消息之间）⇒ 下一 run 尾部出现变更提示 且 头部字节不变")
    void freshness_acrossRunsChangeDeliveredAtTail_whileHeadStaysFrozen() throws Exception {
        writeClaudeMd("v1");
        Path claudeMd = workspace.resolve("CLAUDE.md");

        RecordingLlmProvider provider = RecordingLlmProvider.of(
            new AssistantMessage("ack-1", "stop", List.of()),
            new AssistantMessage("ack-2", "stop", List.of()),
            new AssistantMessage("ack-3", "stop", List.of()));
        LlmAgentLoop loop = newLoop(provider);

        OutboundRequest req0 = runAndCapture(loop, provider, "第一问");
        assertThat(req0.headWireMessage())
            .as("前置：头部元消息必须真含 claudeMd 内容 v1（否则本用例不可观测）")
            .contains("v1");

        // ⭐⭐ 被守护的真实场景：用户在【两条消息之间】= 两次 run 之间改盘。
        //   ⛔ 不靠「把 mtime 推到未来 1s」那种手脚（旧版用例正是因此变成「走不到被守护路径」的
        //      假绿）：本用例的改盘时刻由<b>墙钟自然推进</b>，只需严格大于 run1 的登记时间戳
        //      —— 而 run1 早已结束，故这是真实场景的忠实表达，而不是人为造出的时序。
        //      ⚠ 判据是严格 `mtime > 记录时间戳`：等墙钟明确越过 run1 的登记时刻再写盘
        //      （文件系统时间戳与 JVM 时钟同源，粒度实测 < 1ms；20ms 余量足够，不引入未来时间）。
        String key = claudeMd.toAbsolutePath().normalize().toString();
        long recordedAtRun1 = baselineEntry(key).mtimeMillis();
        long deadline = recordedAtRun1 + 20L;
        while (System.currentTimeMillis() <= deadline) {
            Thread.onSpinWait();
        }
        writeClaudeMd("v2");
        assertThat(Files.getLastModifiedTime(claudeMd).toMillis())
            .as("前置（判据本身的成立条件）：改盘后的 mtime 必须严格大于 run1 的登记时间戳 %d"
                + "（否则本用例连判据的成立条件都不具备 —— fail loud 而不是静默假绿）", recordedAtRun1)
            .isGreaterThan(recordedAtRun1);

        // 第 2 个 run：历史 = 第 1 个 run 实发请求去掉头部元消息后的切片（= 会话日志的本仓载体）
        dbHistory.set(req0.messages().subList(1, req0.messages().size()));
        OutboundRequest req1 = runAndCapture(loop, provider, "第二问");

        // ⭐ 两条同时成立才算判绿：
        //   ① 头部字节不变（冻结）——否则投递等于「回写前缀」，把缓存修复抹平
        PrefixStabilityAssert.expectHeadFrozen(req0, req1);
        assertThat(req1.headWireMessage())
            .as("头部必须仍是旧 claudeMd v1（冻结语义：跨 run 不重算 userContext）")
            .contains("v1")
            .doesNotContain("v2");
        //   ② 尾部必须真的看到变更 —— 否则模型永久停在会话开始时的旧 CLAUDE.md
        //      （改造前：本 run 的登记以「当下」覆盖基线 ⇒ mtime < 登记时间戳 ⇒ 被吸收 ⇒ 本断言必红）
        PrefixStabilityAssert.expectTailDelivery(req1, "edited_text_file",
            claudeMd.toRealPath().toString());

        // ③ 固化：同一变更只投一次（跨 run）—— 若不把投递后的新状态写回会话基线，
        //    下一 run 又拿首次基线比对 ⇒ 每个 run 都重复追加同一条提示（刷屏 + 破坏前缀扩展语义）。
        dbHistory.set(req1.messages().subList(1, req1.messages().size()));
        OutboundRequest req2 = runAndCapture(loop, provider, "第三问");
        assertThat(countTailDeliveries(req2))
            .as("第二问已投递过的变更不得在第三问重复投递（历史里的那 1 条不算重复）")
            .isEqualTo(1);
        assertThat(req2.messages().get(req2.messages().size() - 1).subtype())
            .as("第三问的请求末尾应是本轮用户输入，而不是重复的变更提示")
            .isNotEqualTo("edited_text_file");
    }

    /**
     * 取会话基线里该 key 的 entry（= run1 落下的「首次登记」）。
     * 不存在即 fail loud —— 那说明「首次会话登记固化」没接上，本用例的判据根本不成立
     * （而不是让它静默变成一条恒绿/恒红的假判据）。
     */
    private static ToolUseContext.ReadState baselineEntry(String key) {
        FileStateCache baseline = SessionChangedFilesBaselineRegistry.peek(SESSION);
        assertThat(baseline)
            .as("run1 必须已落下会话级基线（首次登记固化；⛔ 无它则跨 run 变更检测没有比对基准）")
            .isNotNull();
        ToolUseContext.ReadState entry = baseline.get(key);
        assertThat(entry).as("基线里必须有该记忆文件的 entry（key=%s）", key).isNotNull();
        assertThat(entry.offset()).as("基线 entry 必须是全量视图（offset 留空）").isNull();
        assertThat(entry.limit()).as("基线 entry 必须是全量视图（limit 留空）").isNull();
        return entry;
    }

    /** 请求消息里 {@code edited_text_file} 尾部提示的条数（判「只投一次」用）。 */
    private static long countTailDeliveries(OutboundRequest req) {
        return req.messages().stream()
            .filter(m -> "edited_text_file".equals(m.subtype()))
            .count();
    }

    // ═══════════════════════════════ 脚手架 ═══════════════════════════════

    /** 写 CLAUDE.md（每次覆盖，mtime 由文件系统自然推进；需要严格增大时由调用方显式推）。 */
    private void writeClaudeMd(String marker) throws Exception {
        Files.writeString(workspace.resolve("CLAUDE.md"), "# 规则\n- 标记 " + marker + "\n");
    }

    /** 生产同款最小装配：真 run 入口 + 真 claudemd 引擎 + mocked MessageService（DB 历史通道）。 */
    private LlmAgentLoop newLoop(RecordingLlmProvider provider) {
        return newLoop(provider, null);
    }

    /**
     * 同上，另可注册工具（无工具 ⇒ 首轮回 tool_calls 也无从执行，loop 会在工具轮报错退出）。
     *
     * @param tool 需要注册的工具（null = 无工具，仅适合「单轮 stop」用例）
     */
    private LlmAgentLoop newLoop(RecordingLlmProvider provider, Tool tool) {
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        MessageService messageService = mock(MessageService.class);
        when(messageService.listRawForTranscript(SESSION))
            .thenAnswer(inv -> List.copyOf(dbHistory.get()));
        when(messageService.listForResumeExcluding(anyList(), any()))
            .thenAnswer(inv -> List.copyOf(dbHistory.get()));
        when(messageService.listForResumeExcluding(anyString(), any()))
            .thenAnswer(inv -> List.copyOf(dbHistory.get()));

        LlmAgentLoop loop = tool == null
            ? new LlmAgentLoop(factory)
            : new LlmAgentLoop(factory, null, registryOf(tool));
        loop.setClaudemdEngine(engine);
        loop.setMessageService(messageService);
        loop.setStreamContext(null, SESSION, STREAM_MSG_ID);
        return loop;
    }

    /** 跑一个 run 并返回本 run 的<b>首条</b>出站请求（= 会话边界上的那一条）。 */
    private OutboundRequest runAndCapture(LlmAgentLoop loop, RecordingLlmProvider provider, String prompt) {
        int before = provider.callCount();
        loop.run(RunRequest.session(prompt, SESSION, null,
            ProviderConfig.empty(), "test-model", null, null));
        assertThat(provider.callCount())
            .as("run 必须至少发出一次 LLM 请求（否则本用例不可观测）: prompt=%s", prompt)
            .isGreaterThan(before);
        return provider.request(before);
    }

    /** 单工具注册表（生产 base TUC 的 availableTools 来源 = {@code toolRegistry.all()}）。 */
    private static ToolRegistry registryOf(Tool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry;
    }
}
