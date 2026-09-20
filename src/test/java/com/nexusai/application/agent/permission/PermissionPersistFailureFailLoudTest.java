package com.nexusai.application.agent.permission;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * [批 A4c P1] <b>写盘抛异常不得挂死弹窗 / 不得吞掉 allow 决策</b>。
 *
 * <h2>WHY（缺陷本体 · 逐字复核）</h2>
 * <p>批准链上「persistAll 抛」会穿出调用方，而调用方的 {@code future.complete(result)} 在其<b>最末</b>：
 * <ul>
 *   <li><b>单点 ①</b>（用户弹窗批准）：{@code WebSocketPermissionPrompter.onResponse} ——
 *       {@code :1855 applyAndPersistUpdates(...)} 在 {@code :1898 future.complete(result)} <b>之前</b>，
 *       且该段<b>无任何 try/catch</b>。persist 抛 ⇒ 异常逃出 {@code onResponse} ⇒ future 永不完成
 *       ⇒ 弹窗卡死（既不是允许也不是拒绝）。持久化器<b>确实会抛</b>：
 *       {@code LocalSettingsLoader.atomicWrite:210-214} 把 IOException 包成 RuntimeException 抛出
 *       （写侧 fail-loud 是刻意设计，缺陷在于「抛」没有被接线方接住）。</li>
 *   <li><b>单点 ②</b>（hook / coordinator / swarm 携带 updatedPermissions 的 allow）：
 *       {@code ToolPermissionGate.applyAndPersistPermissionUpdates} 的 3 个调用点
 *       （{@code :794} hook allow / {@code :1019} coordinator allow / {@code :1126} swarm allow）
 *       全在「决策已定、只差落地」处 —— 抛出去会被 {@code :820} / {@code :1285} 的
 *       {@code catch (Throwable)} 吞成「hook 未表态」/「cancelAndAbort」⇒ allow 静默丢失 / 变中止。</li>
 * </ul>
 * <p>裁定语义（两处一致）：<b>写盘失败 ⇒ fail-loud（ERROR + 异常原文）且仍然放行</b> ——
 * 本 run 内授权照常生效（appState 同步继续执行），但「规则没写进设置文件」必须留痕。
 *
 * <h2>RED tooth（本测试靠什么变红）</h2>
 * <ol>
 *   <li>{@link #wsPersistThrows_promptStillResolves_andAllowReturned()} ——
 *       删掉 {@code WebSocketPermissionPrompter} 里 persist 段的 try/catch ⇒
 *       prompt 线程 5s 内不结束（future 永不完成）⇒ {@code t.isAlive()} 断言红；</li>
 *   <li>{@link #wsPersistThrows_errorLogged()} —— 删掉 try/catch ⇒ 无 ERROR 日志 ⇒ 断言红；</li>
 *   <li>{@link #gatePersistThrows_doesNotThrow_andErrorLogged()} —— 删掉
 *       {@code ToolPermissionGate} 里 persist 段的 try/catch ⇒ 反射调用直接抛 ⇒
 *       {@code assertThatCode(...).doesNotThrowAnyException()} 红。</li>
 * </ol>
 *
 * <h2>能力边界（如实登记）</h2>
 * <ul>
 *   <li>单点 ① 走<b>真公开入口</b>：{@code prompt(...) → onResponse(allow, updatedPermissions)}；</li>
 *   <li>单点 ② 的 3 个调用点都需要 coordinator / swarm / hook 全套夹具，
 *       本测试<b>直接反射调用该私有单点本身</b>（3 个调用点共用同一实现 ⇒ 覆盖该实现即覆盖 3 处）；</li>
 *   <li>「持久化器真的会抛」不是假设：{@link #persisterMockIsArmed()} 先证明桩确实抛。</li>
 * </ul>
 */
@DisplayName("[批 A4c P1] persist 抛异常 ⇒ fail-loud 且不挂死响应")
class PermissionPersistFailureFailLoudTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000a4c");
    private static final String SESSION_ID = "sess-a4c0001";
    /** LOCAL_SETTINGS 目的地 —— 可持久化，persist 段会真的被执行（SESSION/CLI_ARG 会被拦住）。 */
    private static final String ADD_LOCAL_RULE =
        "{\"type\":\"addRules\",\"rules\":[{\"toolName\":\"Bash\",\"ruleContent\":\"npm run test\"}],"
            + "\"behavior\":\"allow\",\"destination\":\"localSettings\"}";

    // ══════════════════════════ 夹具 ══════════════════════════

    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub-result");
        }
    }

    /** 真会抛的持久化器：模拟 {@code LocalSettingsLoader.atomicWrite:213} 的写盘失败。 */
    private static PermissionUpdatePersister throwingPersister(AtomicReference<Throwable> seen) {
        PermissionUpdatePersister persister = mock(PermissionUpdatePersister.class);
        org.mockito.Mockito.doAnswer(inv -> {
            RuntimeException boom = new RuntimeException("Failed to save settings: <simulated disk failure>");
            seen.set(boom);
            throw boom;
        }).when(persister).persistAll(org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any());
        return persister;
    }

    private static ListAppender<ILoggingEvent> attach(Class<?> target) {
        Logger logger = (Logger) LoggerFactory.getLogger(target);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> target, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(target)).detachAppender(appender);
        appender.stop();
    }

    private static boolean hasErrorMentioning(ListAppender<ILoggingEvent> appender, String needle) {
        return appender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains(needle));
    }

    /** per-turn TUC（生产形状）· 带可写 appState，供断言「步骤 3 appState 同步仍执行」。 */
    private static ToolUseContext promptCtx(String sessionId, Map<String, Object> appState) {
        ToolPermissionContext permCtx = new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
        Function<Map<String, Object>, Map<String, Object>> getAppState = prev -> Map.copyOf(appState);
        Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState = updater -> {
            Map<String, Object> next = updater.apply(Map.copyOf(appState));
            appState.clear();
            if (next != null) {
                appState.putAll(next);
            }
        };
        return new ToolUseContext(
            AGENT_ID, sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of(),
            permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            getAppState, setAppState, null, null);
    }

    private static WebSocketPermissionPrompter prompterWith(PermissionUpdatePersister persister)
            throws Exception {
        WebSocketPermissionPrompter prompter =
            new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class), 100);
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("permissionUpdatePersister");
        f.setAccessible(true);
        f.set(prompter, persister);
        return prompter;
    }

    private static void awaitPendingRegistration(WebSocketPermissionPrompter prompter, String requestId)
            throws Exception {
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("pending");
        f.setAccessible(true);
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            @SuppressWarnings("unchecked")
            Map<String, ?> pending = (Map<String, ?>) f.get(prompter);
            if (pending.containsKey(requestId)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("pending not registered: " + requestId);
    }

    private static List<JsonNode> nodes(String... rawEntries) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String raw : rawEntries) {
            out.add(JSON.readTree(raw));
        }
        return out;
    }

    // ══════════════════════════ 0. 桩自证 ══════════════════════════

    @Test
    @DisplayName("0. 夹具自证：本测试用的持久化器桩确实抛（否则后两条断言是空的）")
    void persisterMockIsArmed() {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        PermissionUpdatePersister persister = throwingPersister(seen);
        assertThatThrownBy(() -> persister.persistAll(List.of(), SESSION_ID))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("simulated disk failure");
        assertThat(seen.get()).isNotNull();
    }

    // ══════════════════════════ 1. 单点 ①（用户弹窗批准） ══════════════════════════

    @Test
    @DisplayName("1. persist 抛 ⇒ prompt 仍返回 Allow（future 被 complete，弹窗不卡死）")
    void wsPersistThrows_promptStillResolves_andAllowReturned() throws Exception {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        WebSocketPermissionPrompter prompter = prompterWith(throwingPersister(seen));
        String requestId = "req-a4c-p1-1";
        Map<String, Object> appState = new LinkedHashMap<>();
        ToolUseContext ctx = promptCtx(SESSION_ID, appState);
        AtomicReference<PermissionResult> resolved = new AtomicReference<>();

        Thread t = new Thread(() -> {
            try {
                resolved.set(prompter.prompt(new StubTool("Bash"),
                    JSON.createObjectNode().put("command", "npm run test"),
                    new PermissionDecisionReason.Other("test"), ctx, requestId,
                    new PermissionPromptDetails("desc", List.of(), null)));
            } catch (Throwable th) {
                resolved.set(null);
            }
        });
        t.start();
        awaitPendingRegistration(prompter, requestId);
        // ⚠️ 缺陷本体之一：onResponse 内部没有外层 try/catch ⇒ 写盘异常会**逃出 onResponse**
        //    （STOMP 入口只记日志），future 永不完成。这里显式接住以便把它变成一条断言，
        //    而不是让测试以「意外异常」收场（那样测不出「卡死」这一面）。
        Throwable escaped = null;
        try {
            prompter.onResponse(requestId, "allow", nodes(ADD_LOCAL_RULE), null, null);
        } catch (Throwable th) {
            escaped = th;
        }
        t.join(5_000);

        // ① 异常不得穿出 onResponse
        assertThat(escaped)
            .as("写盘异常不得穿出 onResponse（穿出后 future 永不完成 ⇒ 弹窗卡死）")
            .isNull();
        // ② 弹窗不卡死：prompt 线程必须结束
        assertThat(t.isAlive())
            .as("persist 抛异常后 future 必须仍被 complete ⇒ prompt 不得永久阻塞（弹窗卡死本体）")
            .isFalse();
        // ② 允许照常返回
        assertThat(resolved.get())
            .as("用户点了允许 ⇒ 决策必须是 Allow（不得因写盘失败退化成拒绝/中止）")
            .isInstanceOf(PermissionResult.Allow.class);
        // ③ 桩确实被调用过（不是因早退而没走到 persist）
        assertThat(seen.get()).as("必须真的走到 persist 并抛出").isNotNull();
        // ④ 步骤 3（appState 同步）仍在 persist 之后被执行 ⇒ 本 run 内授权生效
        assertThat(appState)
            .as("persist 失败后仍必须执行 appState 同步（本次运行内授权照常生效）")
            .containsKey("toolPermissionContext");
    }

    @Test
    @DisplayName("2. persist 抛 ⇒ ERROR 留痕（区分于「持久化成功」的 info 日志），且 pending 已清理")
    void wsPersistThrows_errorLogged() throws Exception {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        WebSocketPermissionPrompter prompter = prompterWith(throwingPersister(seen));
        String requestId = "req-a4c-p1-2";
        ToolUseContext ctx = promptCtx(SESSION_ID, new LinkedHashMap<>());
        ListAppender<ILoggingEvent> appender = attach(WebSocketPermissionPrompter.class);
        try {
            Thread t = new Thread(() -> {
                try {
                    prompter.prompt(new StubTool("Bash"),
                        JSON.createObjectNode().put("command", "npm run test"),
                        new PermissionDecisionReason.Other("test"), ctx, requestId,
                        new PermissionPromptDetails("desc", List.of(), null));
                } catch (Throwable ignored) {
                    // 用户响应为正常返回路径
                }
            });
            t.start();
            awaitPendingRegistration(prompter, requestId);
            // 同测试 1：写盘异常若穿出 onResponse 就不是「留痕失败」而是「响应丢失」，必须显式接住
            Throwable escaped = null;
            try {
                prompter.onResponse(requestId, "allow", nodes(ADD_LOCAL_RULE), null, null);
            } catch (Throwable th) {
                escaped = th;
            }
            t.join(5_000);

            assertThat(escaped).as("写盘异常不得穿出 onResponse").isNull();
            assertThat(hasErrorMentioning(appender, "persist 失败"))
                .as("写盘失败必须 fail-loud（ERROR）—— 否则运维无法区分「规则已落盘」与「没落盘」")
                .isTrue();
            assertThat(hasErrorMentioning(appender, "重启后不再存在"))
                .as("ERROR 文案必须点明后果（规则未落盘 ⇒ 重启后不再存在）")
                .isTrue();
            assertThat(prompter.pendingCount())
                .as("响应已消费 ⇒ pending 必须清空（无悬挂请求）")
                .isZero();
        } finally {
            detach(WebSocketPermissionPrompter.class, appender);
        }
    }

    // ══════════════════════════ 2. 单点 ②（hook / coordinator / swarm 共用实现） ══════════════════════════

    @Test
    @DisplayName("3. gate 单点 persist 抛 ⇒ 方法本身不抛（3 个调用点的 allow 不被吞）+ ERROR 留痕")
    void gatePersistThrows_doesNotThrow_andErrorLogged() throws Exception {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        ToolPermissionGate gate = new ToolPermissionGate(
            mock(PermissionPipeline.class), mock(PermissionPrompter.class),
            null, null, null, null, null, null,
            new PermissionDecisionLogger(null), null,
            /* permissionUpdateApplier */ null,
            /* permissionUpdatePersister */ throwingPersister(seen));

        ToolUseContext ctx = promptCtx(SESSION_ID, new LinkedHashMap<>());
        List<PermissionUpdate> updates = List.of(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.LOCAL_SETTINGS,
            List.of(new PermissionRule(PermissionRuleSource.LOCAL_SETTINGS, PermissionBehavior.ALLOW,
                PermissionRuleValue.wholeTool("Bash"))),
            PermissionBehavior.ALLOW));

        Method m = ToolPermissionGate.class.getDeclaredMethod(
            "applyAndPersistPermissionUpdates", List.class, ToolUseContext.class, String.class);
        m.setAccessible(true);

        ListAppender<ILoggingEvent> appender = attach(ToolPermissionGate.class);
        try {
            assertThatCode(() -> m.invoke(gate, updates, ctx, "call-a4c-1"))
                .as("hook/coordinator/swarm 三条 allow 路径共用本单点 ⇒ 写盘失败不得穿出"
                    + "（:820 / :1285 的 catch(Throwable) 会把它吞成「hook 未表态」/「cancelAndAbort」）")
                .doesNotThrowAnyException();
            assertThat(seen.get()).as("必须真的走到 persist 并抛出").isNotNull();
            assertThat(hasErrorMentioning(appender, "persist 失败"))
                .as("gate 单点写盘失败同样必须 fail-loud（ERROR）")
                .isTrue();
        } finally {
            detach(ToolPermissionGate.class, appender);
        }
    }
}
