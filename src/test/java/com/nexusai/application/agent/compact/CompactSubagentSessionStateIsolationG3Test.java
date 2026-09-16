package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.tool.AbortController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [G3] 「父子共用会话态」的两处真误伤 · 端到端证据（各配反向实验）。
 *
 * <h2>WHY 存在（验证意图 · 非行为）</h2>
 * <p>G1 之后子代理**真的会**压缩，而<b>子代理的 {@code sessionId} 就是父会话的 {@code sessionId}</b>
 * （{@code SubagentExecutor} 探查 :4291/:4309）⇒ 一切「按 sessionId 键控的进程级状态」都会父子共用。
 * 本类只锁其中 2 处（其余 7 处经裁定**不动**，见 {@code D:/tmp/g3-residuals.txt}）：
 * <ol>
 *   <li><b>① {@link CompactProgressState} 单槽覆盖</b> ⇒ 子代理压缩与父会话压缩落同一槽、
 *       {@code finally} 互相误删 ⇒ <b>父会话按 Esc 停压缩静默失效</b>（用户以为停了，其实没停）。</li>
 *   <li><b>② {@link PostCompactionState#markPostCompaction} 命中父 {@code AgentState}</b> ⇒
 *       父的下一个 API success 被误标 {@code isPostCompaction=true}
 *       （PromptCacheBreakDetection 的 cache-miss 归因失真）。</li>
 * </ol>
 *
 * <h2>⛔ 为什么这两处只在本仓存在</h2>
 * <p>CC 结构上无对应物：① CC 用 {@code context.abortController}（per-invocation 实例，REPL.tsx 持
 * React state）—— 没有「按 id 查表」这一跳；② CC 用**进程级单布尔** {@code STATE.pendingPostCompaction}
 * （bootstrap/state.ts:256/771，setter 无形参）。两者都是本仓为 Web 多会话/子代理并发自造的。
 */
@DisplayName("[G3] 父子共用会话态的两处真误伤：abort 槽覆盖 + 父 AgentState 误标")
class CompactSubagentSessionStateIsolationG3Test {

    private static final String SESSION =
        "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final String CHILD_AGENT = UUID.randomUUID().toString();

    private SessionAgentStateRegistry registry;
    private AgentState parentState;

    @BeforeEach
    void setUp() {
        registry = new SessionAgentStateRegistry();
        parentState = new AgentState("system", SESSION, null);   // 父 = 主线程（agentId==null 约定）
        registry.register(SESSION, parentState);
        PostCompactionState.setSessionAgentStateRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        CompactProgressState.removeSessionAbort(SESSION, null);
        CompactProgressState.removeSessionAbort(SESSION, CHILD_AGENT);
        PostCompactionState.setSessionAgentStateRegistry(null);
        PostCompactionState.reset();
    }

    // ════════════════════════ ① abort 槽：不得互相误删 ════════════════════════

    @Test
    @DisplayName("[G3-①] 父+子代理各有在飞压缩：子代理 finally 只删自己的键 ⇒ 父的槽仍在、Esc 仍能停到")
    void childFinally_doesNotRemoveParentSlot_parentEscStillWorks() {
        AbortController parentAc = new AbortController();
        AbortController childAc = new AbortController();
        // GIVEN: 父（主线程键）与子代理（agentId 键）各有一个在飞压缩，**同一 sessionId**
        CompactProgressState.registerSessionAbort(SESSION, null, parentAc);
        CompactProgressState.registerSessionAbort(SESSION, CHILD_AGENT, childAc);

        // WHEN: 子代理压缩收尾（finally 只按自己的键删）
        CompactProgressState.removeSessionAbort(SESSION, CHILD_AGENT);

        // THEN 1: ⛔ 父的槽不得被误删（旧单槽实现此处被连带删除）
        assertThat(parentAc.isCancelled())
            .as("⛔ 子代理 finally 误删了父会话仍在飞的槽（旧单槽语义）").isFalse();
        assertThat(childAc.isCancelled()).as("子代理自己的槽已被自己清掉").isFalse();

        // THEN 2: 父会话按 Esc（ChatService → abortForSession）必须仍能停到在飞压缩
        assertThat(CompactProgressState.abortForSession(SESSION))
            .as("⛔ 父按 Esc 恒 false = 静默失效（用户以为停了，其实没停）—— 本批要修的缺陷")
            .isTrue();
        assertThat(parentAc.isCancelled()).as("abortForSession 命中的正是父的控制器").isTrue();
    }

    @Test
    @DisplayName("[G3-①] 反向：父会话 finally 也不得删掉子代理仍在飞的槽")
    void parentFinally_doesNotRemoveChildSlot() {
        AbortController parentAc = new AbortController();
        AbortController childAc = new AbortController();
        CompactProgressState.registerSessionAbort(SESSION, null, parentAc);
        CompactProgressState.registerSessionAbort(SESSION, CHILD_AGENT, childAc);

        // WHEN: 父会话压缩收尾
        CompactProgressState.removeSessionAbort(SESSION, null);

        // THEN: 双方的槽互不影响；Esc 仍能停到子代理那条（Esc 语义 = 停本会话「一切」在飞压缩）
        assertThat(childAc.isCancelled()).as("⛔ 父 finally 误删了子代理仍在飞的槽").isFalse();
        assertThat(CompactProgressState.abortForSession(SESSION))
            .as("会话级 Esc = 停该会话一切在飞压缩（含子代理）").isTrue();
        assertThat(childAc.isCancelled()).as("命中的是子代理的控制器").isTrue();
    }

    // ════════════════════ ② markPostCompaction：不得污染父 AgentState ════════════════════

    @Test
    @DisplayName("[G3-②] 子代理 markPostCompaction ⇒ 父会话 AgentState 标记不得被置位")
    void subagentMark_doesNotSetParentAgentStateFlag() {
        // WHEN: 子代理压缩成功收尾（sessionId = 父会话 id，agentId = 子代理 id）
        PostCompactionState.markPostCompaction(SESSION, CHILD_AGENT);

        // THEN: 父的标记保持洁净 ⇒ 父下一个 API success 不会被误标 isPostCompaction=true
        assertThat(parentState.pendingPostCompaction())
            .as("⛔ 子代理压缩误标了父会话（cache-miss 归因失真）—— 本批要修的缺陷").isFalse();
        assertThat(PostCompactionState.isPostCompactionPending(SESSION))
            .as("父会话 isPostCompactionPending 必须仍为 false").isFalse();
    }

    @Test
    @DisplayName("[G3-② 对照] 主线程 markPostCompaction（agentId=null）仍置位父标记 —— 证明上条非「全不置位」假绿")
    void mainThreadMark_stillSetsParentAgentStateFlag() {
        PostCompactionState.markPostCompaction(SESSION, null);

        assertThat(parentState.pendingPostCompaction())
            .as("主线程路径行为必须与升级前逐字一致（⛔ 不得因加固而整体 no-op）").isTrue();
    }

    @Test
    @DisplayName("[G3-② 哨兵] agentId=\"main\"（REST partial 路径）必须按**主线程**处理，仍置位父标记")
    void mainSentinel_stillMarksParent() {
        // ⚠️ 实测事实：PartialCompactService:613 对 REST partial 路径显式 cc.setAgentId("main")
        //   —— 非 null 的**主线程哨兵** ⇒ 仅判 null 会把主线程 partial 误判为子代理（回归）。
        PostCompactionState.markPostCompaction(SESSION, "main");

        assertThat(parentState.pendingPostCompaction())
            .as("REST partial（主线程）的 markPostCompaction 必须仍置位父标记").isTrue();
    }

    @Test
    @DisplayName("[G3-① 键隔离] 不同 agentId 的槽互不覆盖（同一 sessionId 下三路并存）")
    void distinctAgentIds_doNotOverwriteEachOther() {
        String agentB = UUID.randomUUID().toString();
        AbortController parentAc = new AbortController();
        AbortController aAc = new AbortController();
        AbortController bAc = new AbortController();
        CompactProgressState.registerSessionAbort(SESSION, null, parentAc);
        CompactProgressState.registerSessionAbort(SESSION, CHILD_AGENT, aAc);
        CompactProgressState.registerSessionAbort(SESSION, agentB, bAc);

        // 全量 Esc：三个都该被停（前缀匹配遍历）
        assertThat(CompactProgressState.abortForSession(SESSION)).isTrue();
        assertThat(parentAc.isCancelled()).as("父槽未被后写者覆盖").isTrue();
        assertThat(aAc.isCancelled()).as("子代理 A 槽未被覆盖").isTrue();
        assertThat(bAc.isCancelled()).as("子代理 B 槽未被覆盖（旧单槽下只有最后一个存活）").isTrue();

        CompactProgressState.removeSessionAbort(SESSION, agentB);
    }
}
