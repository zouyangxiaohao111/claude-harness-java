package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1a F-08] AutoCompactor 压缩后清理<b>走默认实现</b>的会话级鉴别测试。
 *
 * <h2>WHY（CLAUDE.md 规则九 · 测试验证意图 / 规则十二 显式失败）</h2>
 * <p>AutoCompactor 是 Spring <b>单例</b> bean（ToolRegistrationConfig#autoCompactor），而
 * {@code sessionId} 是它的实例字段且<b>生产零 setter 调用</b>。旧实现的清理字段是
 * {@code Runnable}，默认值形如 {@code () -> PostCompactCleanup.runPostCompactCleanup(this.querySource,
 * this.sessionId)} —— 调用点 {@code runPostCompactCleanup.run()} <b>无参</b> ⇒ 必然读字段 ⇒
 * 生产恒传 null ⇒ {@link PostCompactCleanup} 第 4 项 clearSystemPromptSections 走
 * 「调用方未传会话标识」WARN 分支、第 1 项 resetMicrocompactState 落到默认桶 ⇒
 * <b>压缩后本会话的 system prompt section 缓存永不失效 + 本会话 microcompact 桶永不复位</b>
 * （两者都是「静默失效」：只有 WARN/DEBUG 日志可辨，功能上表现为压缩后缓存陈旧）。
 *
 * <p>而同一个 {@code autoCompactIfNeeded} 调用内部<b>已经</b>算出了正确的
 * {@code effSessionId}（来自 {@code ccContext.getSessionId()}，FIX-SM 已有），只是没传给清理器。
 *
 * <h2>本测试的鉴别力从哪来</h2>
 * <ol>
 *   <li><b>走默认实现</b>：⛔ 不调用 {@code setPostCompactCleanup}（注入 seam 会掩盖缺陷 ——
 *       这正是旧实现下 AutoCompactorCcContractTest 全绿的原因：它注入的计数器无视了两参）。</li>
 *   <li><b>断言真实副作用</b>：会话 A 的 {@code AgentState.systemPromptSectionCache()} 被清 +
 *       A 的 microcompact 桶被复位（不是断言「清理器被调用了几次」）。</li>
 *   <li><b>同一装置下的负向对照</b>：与会话 B 对照（B 的 section 缓存必须仍在、B 的桶必须仍在），
 *       证明清理命中的是 A 而不是「随便清了一个会话」。</li>
 *   <li><b>真线程</b>：{@link #legacyChain_onForeignThread_stillUsesExplicitSessionId()} 在非调用线程上
 *       驱动压缩 —— 若将来有人把会话标识改回「读 ThreadLocal/MDC 环境态」（本仓铁律禁止），
 *       新线程读不到任何值 ⇒ 断言变红。</li>
 * </ol>
 *
 * <h2>CC 对照（源仓标注）</h2>
 * <p>CC 无此维度：{@code runPostCompactCleanup(querySource?)} 只有 querySource 一参
 * （claude-code-best/src/services/compact/postCompactCleanup.ts:43，Open-ClaudeCode 同文件 :31），
 * 其 {@code clearSystemPromptSections()}（best:74 / OCC:62）与 {@code resetMicrocompactState()}
 * （best:53 / OCC:41）都是<b>无参模块函数</b>，数据源是进程级 STATE
 * （best/src/constants/systemPromptSections.ts:1-6 import 自 bootstrap/state.js）。CC 单进程单会话
 * ⇒ 无需会话维度；本仓一 JVM 多会话 ⇒ section 缓存按 sessionId 分桶（PostCompactCleanup:301），
 * 必须显式传参。<b>⛔ 不得学 CC 读进程级单例</b>（会清掉别的会话的缓存）。
 */
@DisplayName("[P1a F-08] AutoCompactor 默认清理实现的会话级鉴别（走默认实现 · 断真实副作用）")
class AutoCompactorDefaultCleanupSessionTest {

    private static final String SESS_A = "sess-A";
    private static final String SESS_B = "sess-B";
    /** section 缓存探针键（预塞 → 清理后必须不在了）。 */
    private static final String PROBE = "p1a-cleanup-probe";
    /** 压缩来源：主线程（PostCompactCleanup.isMainThreadCompact 判定用，生产值域形态）。 */
    private static final String QS = "REPL_MAIN_THREAD";

    @AfterEach
    void tearDown() {
        // 复位静态宿主（PostCompactCleanup 的协作器是 static volatile ⇒ 跨用例会污染）
        new PostCompactCleanup(null, null);
        MicroCompactor.removeSessionState(SESS_A);
        MicroCompactor.removeSessionState(SESS_B);
        SessionMemoryService.setLastSummarizedMessageId(SESS_A, null);
        PostCompactionState.reset();
    }

    // ════════════════════════════════════════════════════════════════════
    // 链 1 · SM 成功链（AutoCompactor 内 postCompactCleanup.accept(effQuerySource, effSessionId)）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("默认清理实现 · SM 链：ctx 会话的 section 缓存被清 + 该会话 microcompact 桶复位（旧实现恒 null ⇒ 双失 · 先红）")
    void smChain_defaultCleanup_clearsContextSession(@TempDir Path baseDir) throws Exception {
        // 组装：SESSION registry 走**默认** PostCompactCleanup 静态宿主（不注入任何 cleanup seam）
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        new PostCompactCleanup(null, null);
        AgentState stateA = registerWithProbe(registry, SESS_A);
        AgentState stateB = registerWithProbe(registry, SESS_B);
        MicroCompactor.setPendingCacheEditsForTest(
            new MicroCompactResult.PendingCacheEdits("auto", List.of("t1"), 0), SESS_A);
        MicroCompactor.setPendingCacheEditsForTest(
            new MicroCompactResult.PendingCacheEdits("auto", List.of("t2"), 0), SESS_B);

        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m, ctx) -> new CompactConversation.SummaryResult("<summary>should not be called</summary>", null));
        auto.setSessionMemoryService(smServiceWithContent(baseDir, SESS_A));
        SessionMemoryService.setLastSummarizedMessageId(SESS_A, null);

        AutoCompactor.AutoCompactResult result = auto.autoCompactIfNeeded(
            largeMessages(20), 0, QS, ctxFor(SESS_A));

        assertThat(result.wasCompacted()).isTrue();
        assertThat(result.source())
            .as("前置：SM 路径命中（否则本用例没测到 SM 成功链的清理调用点）")
            .isEqualTo("SESSION_MEMORY");
        // 主断言（旧实现：cleanup 收到 this.sessionId == null ⇒ 这里两条都红）
        assertThat(stateA.systemPromptSectionCache().has(PROBE))
            .as("压缩后本会话的 system prompt section 缓存必须失效（CC clearSystemPromptSections）")
            .isFalse();
        assertThat(MicroCompactor.consumePendingCacheEdits(SESS_A))
            .as("压缩后本会话的 microcompact 引用面必须复位（CC resetMicrocompactState）")
            .isNull();
        // 负向对照：别的会话不得被牵连（证明清理命中的是「本次调用的会话」而非随便一个桶）
        assertThat(stateB.systemPromptSectionCache().has(PROBE))
            .as("对照：会话 B 的 section 缓存不得被 A 的压缩清掉")
            .isTrue();
        assertThat(MicroCompactor.consumePendingCacheEdits(SESS_B))
            .as("对照：会话 B 的 microcompact 桶不得被 A 的压缩复位")
            .isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 链 2 · legacy(L4) 成功链（同文件第 2 个清理调用点）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("默认清理实现 · L4 legacy 链：ctx 会话的 section 缓存被清 + 该会话桶复位（第二个调用点）")
    void legacyChain_defaultCleanup_clearsContextSession() {
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        new PostCompactCleanup(null, null);
        AgentState stateA = registerWithProbe(registry, SESS_A);
        MicroCompactor.setPendingCacheEditsForTest(
            new MicroCompactResult.PendingCacheEdits("auto", List.of("t1"), 0), SESS_A);

        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m, ctx) -> new CompactConversation.SummaryResult("<summary>l4</summary>", null));
        // sessionMemoryService 不注入 ⇒ SM 返回 null ⇒ 走 L4 全量压缩（第 2 个 cleanup 调用点）

        AutoCompactor.AutoCompactResult result = auto.autoCompactIfNeeded(
            largeMessages(20), 0, QS, ctxFor(SESS_A));

        assertThat(result.wasCompacted()).isTrue();
        assertThat(result.source())
            .as("前置：L4 路径命中（否则本用例没测到 legacy 成功链的清理调用点）")
            .isEqualTo("AUTO");
        assertThat(stateA.systemPromptSectionCache().has(PROBE))
            .as("L4 成功链同样必须清本会话 section 缓存（旧实现恒 null ⇒ 先红）")
            .isFalse();
        assertThat(MicroCompactor.consumePendingCacheEdits(SESS_A))
            .as("L4 成功链同样必须复位本会话 microcompact 桶")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 真线程 · 会话标识必须是显式传参，不得依赖任何环境态（ThreadLocal/MDC）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("真线程：在非调用线程上压缩，默认清理仍按显式会话标识生效（禁环境态会话槽）")
    void legacyChain_onForeignThread_stillUsesExplicitSessionId() throws Exception {
        // WHY：本仓铁律「会话态一律不得经 ThreadLocal/MDC 读」（子线程读不到）。
        //   若会话标识改回环境态读取，新线程上必然取不到 ⇒ section 缓存不被清 ⇒ 本用例变红。
        //   正向对照 = 同装置在测试主线程上已由 legacyChain_defaultCleanup_clearsContextSession 证明为绿。
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        new PostCompactCleanup(null, null);
        AgentState stateA = registerWithProbe(registry, SESS_A);

        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m, ctx) -> new CompactConversation.SummaryResult("<summary>l4-thread</summary>", null));

        AtomicReference<AutoCompactor.AutoCompactResult> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                resultRef.set(auto.autoCompactIfNeeded(largeMessages(20), 0, QS, ctxFor(SESS_A)));
            } catch (Throwable t) {
                errorRef.set(t);
            }
        }, "p1a-cleanup-probe");
        worker.start();
        worker.join();

        assertThat(errorRef.get()).as("工作线程内不得抛异常").isNull();
        assertThat(resultRef.get()).as("工作线程内压缩必须完成").isNotNull();
        assertThat(resultRef.get().wasCompacted()).isTrue();
        assertThat(stateA.systemPromptSectionCache().has(PROBE))
            .as("非调用线程上的压缩也必须清掉 ctx 指定会话的 section 缓存（会话标识 = 显式形参）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** 注册会话 AgentState 并预塞 section 缓存探针（清理后必须不在了）。 */
    private static AgentState registerWithProbe(SessionAgentStateRegistry registry, String sessionId) {
        AgentState state = new AgentState("sys", sessionId, null);
        state.systemPromptSectionCache().set(PROBE, "cached");
        registry.register(sessionId, state);
        return state;
    }

    /** per-session 压缩上下文（镜像生产 buildAutoContext 的必需字段，避免 compactConversation NPE）。 */
    private static CompactConversationContext ctxFor(String sessionId) {
        return new CompactConversationContext()
            .setSessionId(sessionId)
            .setModel("test-model")
            .setQuerySource(QS)
            .setReadFileState(new LinkedHashMap<>())
            .setNotifyCompaction(() -> { });
    }

    /** SM 文件（非空、非模板）+ 启用 SM 双门控（{baseDir}/{sessionId}/session-memory/summary.md）。 */
    private static SessionMemoryService smServiceWithContent(Path baseDir, String sessionId) throws Exception {
        java.nio.file.Files.createDirectories(baseDir.resolve(sessionId).resolve("session-memory"));
        java.nio.file.Files.writeString(
            baseDir.resolve(sessionId).resolve("session-memory").resolve("summary.md"),
            "# Learnings\nsome real learning content\n");
        SessionMemoryService sm = new SessionMemoryService(baseDir);
        sm.setSmSessionMemoryEnabled(true);
        sm.setSmCompactEnabled(true);
        return sm;
    }

    private static List<ChatMessageDto> largeMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ChatMessageDto("u" + i, null, Role.user, "user", "hi", null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of(), null, false, false));
        }
        return list;
    }
}
