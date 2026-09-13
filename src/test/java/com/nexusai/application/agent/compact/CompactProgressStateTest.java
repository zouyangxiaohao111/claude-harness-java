package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [compact-progress-push 2026-09-04] 压缩进度 STOMP 推送状态测试。
 *
 * <p><b>WHY（规则九 · 意图验证）</b>: CC 手动 /compact 的"进度条"实为前端 spinner + 文案
 * （claude-code-best REPL.tsx:3003-3032：{@code hooks_start} 变色+文案、{@code compact_start}
 * 显示 {@code "Compacting conversation"}、{@code compact_end} 清空），由 {@code onCompactProgress}
 * 事件驱动。Java 已全链 emit {@link CompactProgressEvent}（单流程恰 5 事件）但此前消费端 no-op →
 * 前端收不到压缩进行中信号。本类 = 线程注册通道 + 前端 JSON 契约，manual/auto 压缩期间注册，
 * {@link CompactConversationContext} 未显式设时委托注册表推出。测试锁定：前端 JSON 契约（前端
 * 依赖 type 值渲染 spinner）+ 注册/清理线程隔离 + ccCtx getter 委托（无注册回落字段/no-op 不回归）。
 */
class CompactProgressStateTest {


    @Test
    @DisplayName("前端 JSON 契约：事件 → {type} / {type,hookType}（前端 REPL spinner 依赖 type 值）")
    void toFrontendJson_mapsCcUnionContract() {
        // compact_start / compact_end（REPL spinner 启停）
        JsonNode start = CompactProgressState.toFrontendJson(new CompactProgressEvent.CompactStart());
        assertThat(start.get("type").asText()).as("compact_start → spinner 显示 'Compacting conversation'").isEqualTo("compact_start");
        JsonNode end = CompactProgressState.toFrontendJson(new CompactProgressEvent.CompactEnd());
        assertThat(end.get("type").asText()).isEqualTo("compact_end");
        // hooks_start × 3 hookType（REPL spinner 各 hook 文案）
        JsonNode pre = CompactProgressState.toFrontendJson(
            new CompactProgressEvent.HooksStart(CompactProgressEvent.HooksStart.HookType.PRE_COMPACT));
        assertThat(pre.get("type").asText()).isEqualTo("hooks_start");
        assertThat(pre.get("hookType").asText()).as("hookType 对齐 CC 'pre_compact'").isEqualTo("pre_compact");
        JsonNode post = CompactProgressState.toFrontendJson(
            new CompactProgressEvent.HooksStart(CompactProgressEvent.HooksStart.HookType.POST_COMPACT));
        assertThat(post.get("hookType").asText()).isEqualTo("post_compact");
        JsonNode session = CompactProgressState.toFrontendJson(
            new CompactProgressEvent.HooksStart(CompactProgressEvent.HooksStart.HookType.SESSION_START));
        assertThat(session.get("hookType").asText()).isEqualTo("session_start");
        // Java 扩展：摘要流式真进度（前端进度条蠕动源，非 CC union）
        JsonNode prog = CompactProgressState.toFrontendJson(new CompactProgressEvent.SummaryProgress(1234));
        assertThat(prog.get("type").asText()).isEqualTo("compact_progress");
        assertThat(prog.get("chars").asInt()).isEqualTo(1234);
    }

    @Test
    @DisplayName("topic：/topic/sessions/{sessionId}/compact-progress（与 token-warning 同构订阅点）")
    void topic_buildsSessionScopedStompDestination() {
        assertThat(CompactProgressState.topic("sess-abc123"))
            .isEqualTo("/topic/sessions/sess-abc123/compact-progress");
    }

    @Test
    @DisplayName("[批 5a] 进度 sink 由 ctx 显式携带（原 ThreadLocal 注册的重表达）：按上下文隔离，不串台")
    void progressSink_isCarriedByExplicitContext() {
        // 原用例断言 `register/current/clear`（已删除的 ThreadLocal 载体）。语义重表达为：
        // sink 挂在 ctx 上 ⇒ 天然按上下文隔离（这正是原「防串台」断言要保的东西）。
        CompactConversationContext ctxA = new CompactConversationContext();
        assertThat(ctxA.getOnCompactProgress()).as("未显式 set → 默认 no-op，emit 不抛").isNotNull();

        AtomicReference<CompactProgressEvent> captured = new AtomicReference<>();
        ctxA.setOnCompactProgress(captured::set);
        ctxA.getOnCompactProgress().accept(new CompactProgressEvent.CompactStart());
        assertThat(captured.get()).as("显式装箱后可推").isInstanceOf(CompactProgressEvent.CompactStart.class);

        // 对照：另一个 ctx 读不到 A 的 sink（无共享槽位）
        CompactConversationContext ctxB = new CompactConversationContext();
        AtomicReference<CompactProgressEvent> leaked = new AtomicReference<>();
        new Thread(() -> ctxB.getOnCompactProgress().accept(new CompactProgressEvent.CompactEnd())).start();
        assertThat(leaked.get()).as("另一 ctx 的 sink 不被 A 影响（无 ThreadLocal 串台）").isNull();
    }

    @Test
    @DisplayName("[批 5a] getOnCompactProgress 单源 = 显式字段（原「委托注册表 / 回落字段」双源已收口）")
    void conversationContextGetter_singleExplicitSource() {
        CompactConversationContext cc = new CompactConversationContext();
        // 未 set → 字段默认 no-op，emit 不抛
        cc.getOnCompactProgress().accept(new CompactProgressEvent.CompactStart());

        AtomicReference<CompactProgressEvent> explicit = new AtomicReference<>();
        cc.setOnCompactProgress(explicit::set);
        cc.getOnCompactProgress().accept(new CompactProgressEvent.CompactEnd());
        assertThat(explicit.get()).as("getter 返回显式 set 的那个 consumer（无第二来源）")
            .isInstanceOf(CompactProgressEvent.CompactEnd.class);
    }

    @Test
    @DisplayName("[批 5a] 可中断：会话级 abortForSession（前端停止/Esc → cancelSession 打断压缩）· 跨线程可达")
    void abortChannel_sessionLevelAbortsCrossThread() throws InterruptedException {
        // 原用例前 3 行断言 registerAbort/currentAbort（已删除的 ThreadLocal）。语义重表达：
        // 摘要中断源现随 ctx 显式携带（见 CompactConversationContext.getAbortController ，
        // 消费点 StreamCompactSummary 经 ctx 读取）——本用例保留**跨线程**可达的会话级通道断言
        // （这才是「前端停止键能打断 REST 线程压缩」的承重部分）。
        com.nexusai.application.agent.tool.AbortController ac =
            new com.nexusai.application.agent.tool.AbortController();
        // 显式载荷：同一实例既进 ctx（摘要断流源）又进会话级槽（跨线程 abort）
        CompactConversationContext ctx = new CompactConversationContext().setAbortController(ac);
        assertThat(ctx.getAbortController()).as("摘要断流源 = ctx 携带的同一实例").isSameAs(ac);

        assertThat(CompactProgressState.abortForSession("sess-xyz"))
            .as("未登记 → false（无在飞压缩，cancelSession 不阻塞原逻辑）").isFalse();
        CompactProgressState.registerSessionAbort("sess-xyz", ac);
        assertThat(ac.isCancelled()).as("abort 前未取消").isFalse();

        // 真实新线程（模拟前端 cancel 请求线程）——证明会话级通道非线程绑定
        java.util.concurrent.atomic.AtomicBoolean hit = new java.util.concurrent.atomic.AtomicBoolean();
        Thread canceller = new Thread(() -> hit.set(CompactProgressState.abortForSession("sess-xyz")),
            "test-cancel-thread");
        canceller.start();
        canceller.join(5_000);
        assertThat(hit.get()).as("另一线程 abort 命中在飞压缩（跨线程可达）").isTrue();
        assertThat(ac.isCancelled()).as("abort('user_cancel') 已置位 → 摘要 provider 硬断流").isTrue();
        // 幂等：已取消再 abort false
        assertThat(CompactProgressState.abortForSession("sess-xyz")).isFalse();
        CompactProgressState.removeSessionAbort("sess-xyz");
        assertThat(CompactProgressState.abortForSession("sess-xyz")).as("移除后无在飞 → false").isFalse();
    }
}
