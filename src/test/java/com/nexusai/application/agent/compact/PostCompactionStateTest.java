package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostCompactionState 测试 · 方案 1b（OD-17）会话级布尔挂 AgentState ·
 * 对齐 CC bootstrap/state.ts:256/769-781 markPostCompaction/consumePostCompaction.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 的 {@code STATE.pendingPostCompaction}
 * 语义是"压缩后置 true，<b>下个 API success 事件</b> 带 {@code isPostCompaction=true} 后自动复位"——
 * 这保证遥测能区分"压缩导致的 cache miss"与"TTL 过期导致的 cache miss"（logging.ts:452/573）。
 * 若标志不复位或提前复位，isPostCompaction 归因就会错。方案 1b 把布尔从静态
 * {@code ConcurrentHashMap<String,Boolean>} 迁到会话级 {@link AgentState#pendingPostCompaction()}
 * （经 {@link SessionAgentStateRegistry} 按 sessionId 解析），本测试锁定该状态机契约：
 * <ol>
 *   <li>markPostCompaction → 会话 AgentState pending 置位（真实落字段，非模块级 Map）</li>
 *   <li>consumePostCompaction 首次返回 true 且<b>自动复位</b>（再消费返回 false）</li>
 *   <li>会话级隔离（A mark → consume B false、consume A true；多会话服务不能跨会话串扰）</li>
 *   <li>未 mark 直接 consume → false</li>
 *   <li>回落路径：未注册 sessionId / 非法 UUID / registry 未接线 → 进程级单布尔生效
 *       （CC 单进程语义等价），且<b>不串扰</b>已注册会话</li>
 * </ol>
 */
@DisplayName("[OD-17 方案 1b] PostCompactionState 会话级布尔状态机（挂 AgentState）")
class PostCompactionStateTest {

    private static final String SESSION_A = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final String SESSION_B = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    private SessionAgentStateRegistry registry;
    private AgentState stateA;
    private AgentState stateB;

    @BeforeEach
    void setUp() {
        registry = new SessionAgentStateRegistry();
        stateA = new AgentState("system", SESSION_A, UUID.randomUUID());
        stateB = new AgentState("system", SESSION_B, UUID.randomUUID());
        registry.register(SESSION_A, stateA);
        registry.register(SESSION_B, stateB);
        PostCompactionState.setSessionAgentStateRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        // 复位 registry 注入 + 进程级回落布尔（避免跨用例静态字段污染）
        PostCompactionState.setSessionAgentStateRegistry(null);
        PostCompactionState.reset();
    }

    @Test
    @DisplayName("markPostCompaction 后会话级 pending 置位（REQ-28 · 真实落 AgentState 字段）")
    void markSetsPendingOnAgentState() {
        PostCompactionState.markPostCompaction(SESSION_A.toString());

        assertThat(PostCompactionState.isPostCompactionPending(SESSION_A.toString())).isTrue();
        // 布尔真实落到注册的 AgentState 字段（方案 1b：状态宿主从静态 Map 迁到 AgentState）
        assertThat(stateA.pendingPostCompaction()).isTrue();
    }

    @Test
    @DisplayName("consumePostCompaction 首次返回 true 后自动复位（下个 API success 消费一次）")
    void consumeReturnsTrueOnceThenAutoResets() {
        PostCompactionState.markPostCompaction(SESSION_A.toString());

        // 下个 API success 事件消费一次 → isPostCompaction=true
        assertThat(PostCompactionState.consumePostCompaction(SESSION_A.toString())).isTrue();
        // 自动复位 → AgentState 字段同步复位 + 再次消费返回 false（直到下次压缩）
        assertThat(stateA.pendingPostCompaction()).isFalse();
        assertThat(PostCompactionState.consumePostCompaction(SESSION_A.toString())).isFalse();
    }

    @Test
    @DisplayName("会话级隔离：A 压缩不会让 B 的下个 API success 误报 isPostCompaction")
    void perSessionIsolation() {
        PostCompactionState.markPostCompaction(SESSION_A.toString());

        // B 会话从未压缩 → 其 API success 不应带 isPostCompaction（B 的 AgentState 字段保持 false）
        assertThat(PostCompactionState.consumePostCompaction(SESSION_B.toString())).isFalse();
        assertThat(stateB.pendingPostCompaction()).isFalse();
        // A 会话正常消费到 true
        assertThat(PostCompactionState.consumePostCompaction(SESSION_A.toString())).isTrue();
    }

    @Test
    @DisplayName("未 mark 直接 consume → false（无压缩无标记）")
    void consumeWithoutMarkReturnsFalse() {
        assertThat(PostCompactionState.consumePostCompaction(SESSION_A.toString())).isFalse();
        assertThat(PostCompactionState.isPostCompactionPending(SESSION_A.toString())).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // OD-17 再思考 · 生产 sessionId 格式 round-trip（主路径闭环钉死）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 生产 sessionId 格式 round-trip · 钉死 OD-17 主路径格式错位（0.2.35 首次实施断裂根因）。
     *
     * <p><b>WHY（规则九 · 测试验证意图）</b>：生产 sessionId 是 {@code "sess-"} + UUID 前 8 位
     * （SessionService.generateId，<b>非合法 UUID</b>）。mark 侧（LlmAgentLoop:1550 创建 AgentState
     * 时 ChatService:199 parseSessionUuid 已归一化）用<b>解析后 UUID 串</b>注册并 markPostCompaction；
     * consume 侧（AnthropicSdkProvider.consumePostCompactionAtApiSuccess）从 MDC 拿到<b>原始
     * {@code "sess-xxx"}</b>。旧实现
     * {@code UUID.fromString("sess-xxx")} 抛 IllegalArgumentException → 回落进程级默认布尔
     * （mark 从未写进程级）→ isPostCompaction 永不触发（反射确认 49/49 + Provider 33/33 全绿仍断）。
     * 本用例用<b>生产真实值</b>（mark=解析 UUID 串 / consume=原始 "sess-xxx"）钉死归一化闭环，
     * 若回归（resolveAgentState 改回 UUID.fromString 直解）本用例变红。
     */
    @Test
    @DisplayName("生产 round-trip: mark/consume 同 short 直键命中同一 AgentState（session-id-short 主路径闭环）")
    void productionSessionIdFormatRoundTrip() {
        // [session-id-short] 生产 raw = "sess-" + UUID 前 8 位（SessionService.generateId 格式）
        // mark 侧与 consume 侧 sessionId 已统一 short 直键（不再 parseSessionUuid 归一化）
        String rawSessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState prodState = new AgentState("system", rawSessionId, UUID.randomUUID());
        registry.register(rawSessionId, prodState);

        // mark 侧生产真实值：short 直键（mark 站点 CompactCommand:224 / AutoCompactor:573 等）
        PostCompactionState.markPostCompaction(rawSessionId);

        // consume 侧生产真实值：同 short 直键（MDC RequestContext.sessionId，ChatService:120）
        assertThat(PostCompactionState.isPostCompactionPending(rawSessionId)).isTrue();
        assertThat(PostCompactionState.consumePostCompaction(rawSessionId)).isTrue();
        assertThat(prodState.pendingPostCompaction()).isFalse(); // 消费后自动复位
        assertThat(PostCompactionState.consumePostCompaction(rawSessionId)).isFalse();
        PostCompactionState.markPostCompaction(rawSessionId);
        assertThat(PostCompactionState.consumePostCompaction(rawSessionId)).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 回落路径（进程级单布尔 · CC 单进程语义等价）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("回落: 合法 UUID 但未注册会话 → 走进程级单布尔，且不串扰注册会话")
    void fallbackForUnregisteredSession() {
        UUID ghost = UUID.randomUUID(); // 合法 UUID，但未注册进 registry

        PostCompactionState.markPostCompaction(ghost.toString());

        // 未注册会话走回落单布尔 → 置位/消费/复位闭环
        assertThat(PostCompactionState.isPostCompactionPending(ghost.toString())).isTrue();
        assertThat(PostCompactionState.consumePostCompaction(ghost.toString())).isTrue();
        assertThat(PostCompactionState.consumePostCompaction(ghost.toString())).isFalse();
        // 回落布尔不串扰已注册会话 A（A 从未 mark → false）
        assertThat(PostCompactionState.consumePostCompaction(SESSION_A.toString())).isFalse();
    }

    @Test
    @DisplayName("回落: 非法 UUID（如契约测试用的 's1'）→ 走进程级单布尔，不串扰注册会话")
    void fallbackForInvalidUuid() {
        PostCompactionState.markPostCompaction("s1");

        assertThat(PostCompactionState.isPostCompactionPending("s1")).isTrue();
        assertThat(PostCompactionState.consumePostCompaction("s1")).isTrue();
        // 注册会话 A 不受回落布尔影响（保持 false）
        assertThat(PostCompactionState.isPostCompactionPending(SESSION_A.toString())).isFalse();
    }

    @Test
    @DisplayName("回落: registry 未接线（null）→ 全部走进程级单布尔")
    void fallbackWhenRegistryNotWired() {
        PostCompactionState.setSessionAgentStateRegistry(null);

        PostCompactionState.markPostCompaction(SESSION_A.toString());
        assertThat(PostCompactionState.isPostCompactionPending(SESSION_A.toString())).isTrue();
        assertThat(PostCompactionState.consumePostCompaction(SESSION_A.toString())).isTrue();
        assertThat(PostCompactionState.consumePostCompaction(SESSION_A.toString())).isFalse();
    }
}
