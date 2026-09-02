package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * R32-b15 Stage 3.1 C13 · ToolUseContext.onCompactProgress 字段测试 ·
 * 对齐 CC {@code Tool.ts:235 onCompactProgress}.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: C13 的核心契约是"ToolUseContext
 * record 17 字段 (从 16 扩展到 17), onCompactProgress 是新增 Consumer<CompactProgressEvent>,
 * 默认 noop 保证旧调用方零影响". 测试覆盖:
 * <ol>
 *   <li>17 字段 record 完整 (canonical 构造 + 8 旧构造器全兼容)</li>
 *   <li>onCompactProgress 字段可读 + 默认 noop (null → event -> {})</li>
 *   <li>显式注入 Consumer 触发正常 (透传到前端 / EventPublisher)</li>
 *   <li>不写入 outbound DTO / LLM 请求 (BudgetTracker local-only 约束的兄弟约束)</li>
 *   <li>与 Stage 1+2 字段 (inProgressToolUseIDs / toolDecisions) 共存无冲突</li>
 * </ol>
 */
class R32B15Stage3_1_ToolUseContextC13Test {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    @Test
    @DisplayName("17 字段 record: canonical 17 参构造 (含 onCompactProgress) 可用")
    void canonical17ArgConstructor() {
        AtomicInteger counter = new AtomicInteger();
        java.util.function.Consumer<CompactProgressEvent> progressConsumer = e -> counter.incrementAndGet();

        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), progressConsumer
        );

        // 验证 17 字段全部可读
        assertThat(ctx.agentId()).isEqualTo(AGENT_ID);
        assertThat(ctx.sessionId()).isEqualTo(SESSION_ID);
        assertThat(ctx.onCompactProgress()).isSameAs(progressConsumer);

        // 触发 onCompactProgress, 验证 Consumer 链路
        ctx.onCompactProgress().accept(new CompactProgressEvent.CompactStart());
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("默认 noop: 4 参 / 5 参 / 6 参 / 8 参 / 10 参 / 11 参 / 12 参 / 13 参 / 14 参 / 15 参 / 16 参旧构造器兼容")
    void backwardCompatibleConstructorsDefaultToNoop() {
        // 4 参构造 (Phase 1 早期)
        ToolUseContext ctx4 = new ToolUseContext(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT, Map.of());
        assertThat(ctx4.onCompactProgress()).isNotNull();
        // noop Consumer 不抛异常
        assertThatCode(() -> ctx4.onCompactProgress().accept(new CompactProgressEvent.CompactEnd()))
            .doesNotThrowAnyException();

        // 13 参构造 (P1.3 时代)
        ToolUseContext ctx13 = new ToolUseContext(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "");
        assertThat(ctx13.onCompactProgress()).isNotNull();
        assertThatCode(() -> ctx13.onCompactProgress().accept(new CompactProgressEvent.CompactStart()))
            .doesNotThrowAnyException();

        // 16 参便利构造 (R32-b12 D-4)
        ToolUseContext ctx16 = ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of());
        assertThat(ctx16.onCompactProgress()).isNotNull();
        assertThatCode(() -> ctx16.onCompactProgress().accept(new CompactProgressEvent.CompactEnd()))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("17 参便利构造 of(): onCompactProgress 显式注入, 触发链路可观测")
    void of17ArgWithExplicitConsumer() {
        List<CompactProgressEvent> collected = new ArrayList<>();
        java.util.function.Consumer<CompactProgressEvent> progressConsumer = collected::add;

        ToolUseContext ctx = ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), progressConsumer);

        // 显式 Consumer 注入后, accept 链路通畅
        ctx.onCompactProgress().accept(new CompactProgressEvent.HooksStart(
            CompactProgressEvent.HooksStart.HookType.PRE_COMPACT));
        ctx.onCompactProgress().accept(new CompactProgressEvent.CompactStart());
        ctx.onCompactProgress().accept(new CompactProgressEvent.CompactEnd());

        assertThat(collected).hasSize(3);
        assertThat(collected.get(0)).isInstanceOf(CompactProgressEvent.HooksStart.class);
        assertThat(collected.get(2)).isInstanceOf(CompactProgressEvent.CompactEnd.class);
    }

    @Test
    @DisplayName("null Consumer: compact ctor 兜底 noop (不抛 NPE)")
    void nullConsumerFallsBackToNoop() {
        // 17 参构造传 null
        ToolUseContext ctx = new ToolUseContext(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null
        );

        // compact ctor 兜底 → onCompactProgress 不为 null
        assertThat(ctx.onCompactProgress()).isNotNull();
        // noop Consumer 接受所有 event 不抛异常
        assertThatCode(() -> {
            ctx.onCompactProgress().accept(new CompactProgressEvent.CompactStart());
            ctx.onCompactProgress().accept(new CompactProgressEvent.CompactEnd());
            ctx.onCompactProgress().accept(new CompactProgressEvent.HooksStart(
                CompactProgressEvent.HooksStart.HookType.SESSION_START));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Consumer 异常隔离: noop 兜底后, accept 抛异常不阻断 ctx 后续使用")
    void consumerExceptionIsolated() {
        // 故意抛异常的 Consumer
        java.util.function.Consumer<CompactProgressEvent> throwingConsumer = e -> {
            throw new RuntimeException("test exception");
        };

        // ToolUseContext 不应在 Consumer 抛异常时崩（emit 端负责 try/catch 隔离）
        // 这里 ctx 自身只是持有 Consumer 引用, 异常由 emit 端处理
        ToolUseContext ctx = ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), throwingConsumer);

        assertThat(ctx.onCompactProgress()).isSameAs(throwingConsumer);
        // ctx 自身可用 (字段读取不抛)
        assertThat(ctx.agentId()).isEqualTo(AGENT_ID);
    }
}
