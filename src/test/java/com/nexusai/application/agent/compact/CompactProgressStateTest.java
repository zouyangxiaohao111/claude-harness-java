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

    @AfterEach
    void tearDown() {
        CompactProgressState.clear(); // 防 ThreadLocal 泄漏跨用例
    }

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
    @DisplayName("register/current/clear：线程注册推送、清理后回落 null（防串台）")
    void register_current_clear_roundtrip() {
        assertThat(CompactProgressState.current()).as("初始无注册").isNull();
        AtomicReference<CompactProgressEvent> captured = new AtomicReference<>();
        CompactProgressState.register(captured::set);
        assertThat(CompactProgressState.current()).as("注册后可取推送 consumer").isNotNull();
        CompactProgressState.current().accept(new CompactProgressEvent.CompactStart());
        assertThat(captured.get()).isInstanceOf(CompactProgressEvent.CompactStart.class);
        CompactProgressState.clear();
        assertThat(CompactProgressState.current()).as("clear 后回落 null").isNull();
    }

    @Test
    @DisplayName("ccCtx getter 委托：注册表存在时 emit 走注册推送；无注册回落显式 set（行为不回归）")
    void conversationContextGetter_delegatesToRegisteredPush_elseExplicitField() {
        CompactConversationContext cc = new CompactConversationContext();
        // 无注册 → 字段默认 no-op，emit 不抛
        cc.getOnCompactProgress().accept(new CompactProgressEvent.CompactStart());

        // 显式 set（模拟 buildAutoContext 从 tuc 注入）→ 无注册时 getter 返回显式
        AtomicReference<CompactProgressEvent> explicit = new AtomicReference<>();
        cc.setOnCompactProgress(explicit::set);
        cc.getOnCompactProgress().accept(new CompactProgressEvent.CompactEnd());
        assertThat(explicit.get()).as("无注册 → getter 回落显式 set").isInstanceOf(CompactProgressEvent.CompactEnd.class);

        // 注册表注册（manual handleCompactCommand / auto LlmAgentLoop 压缩期间）→ getter 委托注册
        AtomicReference<CompactProgressEvent> registered = new AtomicReference<>();
        CompactProgressState.register(registered::set);
        try {
            cc.getOnCompactProgress().accept(new CompactProgressEvent.CompactStart());
            assertThat(registered.get()).as("注册表存在 → ccCtx emit 走注册推送（前端收到 spinner 信号）")
                .isInstanceOf(CompactProgressEvent.CompactStart.class);
        } finally {
            CompactProgressState.clear();
        }
    }

    @Test
    @DisplayName("可中断：registerAbort/currentAbort + 会话级 abortForSession（前端停止/Esc → cancelSession 打断压缩）")
    void abortChannel_registersAndAborts() {
        assertThat(CompactProgressState.currentAbort()).as("初始无当前压缩 abort").isNull();
        com.nexusai.application.agent.tool.AbortController ac =
            new com.nexusai.application.agent.tool.AbortController();
        CompactProgressState.registerAbort(ac);
        assertThat(CompactProgressState.currentAbort()).as("注册后可取（摘要中断源 supplier 读此）").isSameAs(ac);
        CompactProgressState.clearAbort();
        assertThat(CompactProgressState.currentAbort()).as("clearAbort 后回落 null").isNull();

        // 会话级登记（跨线程前端 cancel → abortForSession）
        assertThat(CompactProgressState.abortForSession("sess-xyz"))
            .as("未登记 → false（无在飞压缩，cancelSession 不阻塞原逻辑）").isFalse();
        CompactProgressState.registerSessionAbort("sess-xyz", ac);
        assertThat(ac.isCancelled()).as("abort 前未取消").isFalse();
        assertThat(CompactProgressState.abortForSession("sess-xyz"))
            .as("abort 在飞压缩 → true（前端停止键/Esc 经 cancelSession 命中）").isTrue();
        assertThat(ac.isCancelled()).as("abort('user_cancel') 已置位 → 摘要 provider 硬断流").isTrue();
        // 幂等：已取消再 abort false
        assertThat(CompactProgressState.abortForSession("sess-xyz")).isFalse();
        CompactProgressState.removeSessionAbort("sess-xyz");
        assertThat(CompactProgressState.abortForSession("sess-xyz")).as("移除后无在飞 → false").isFalse();
    }
}
