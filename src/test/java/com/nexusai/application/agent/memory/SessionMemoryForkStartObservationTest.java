package com.nexusai.application.agent.memory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [edit-obs-2a] SessionMemoryService fork 起点观测测试。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：Edit 失败 415 次里 305 次 SNF 落在「会话记忆
 * fork」自己写的文件上。要判「模型误引（H-C）」与「fork 起点→施加时刻内容漂移（H-D）」，
 * 缺的正是「fork 那一刻 currentMemory 到底是什么」—— 这条观测是唯一能定死 H-D 的通道：
 * <ul>
 *   <li>{@code sha256(fork 起点 currentMemory)} 与 SNF 行的 {@code diskSha256} 直接可比：
 *       相等 ⇒ 磁盘自 fork 起点未变（误引）；不等 ⇒ 内容在两次之间漂移。</li>
 *   <li>同一 {@code memoryPath} 被几次 fork 打过，可从本行计数得出（两次 fork 之间被 Edit
 *       就会多出一次「起点 ≠ 上次起点」）。</li>
 * </ul>
 *
 * <p>本测试用真实提取管线（{@code extractSessionMemory}，非 mock 内部）驱动，断言：
 * <ol>
 *   <li>观测行发出，且带 {@code sessionId} + {@code memoryPath}；</li>
 *   <li>{@code currentMemoryLen} 与 {@code currentMemorySha256} 与磁盘上真实内容一致；</li>
 *   <li>⛔ 不落全文（避免体量失控）。</li>
 * </ol>
 *
 * <p>⚠️ 同时钉死零行为变更：提取门控 / fork 参数 / lastSummarized 安全更新均不受影响。
 */
@DisplayName("[edit-obs-2a] SessionMemory fork 起点观测（len + sha256）")
class SessionMemoryForkStartObservationTest {

    private static final String SESSION = "s1";
    private static final String MARKER = "[SessionMemory] fork 起点观测(非判定)";

    // ── 夹具 DB 姿态显式声明（同 SessionMemoryExtractionPipelineTest）──
    @BeforeEach
    void setUp() {
        SessionProjectRootTestSupport.declareNoDatabase();
        svcLogger = (Logger) LoggerFactory.getLogger(SessionMemoryService.class);
        appender = new ListAppender<>();
        appender.start();
        svcLogger.addAppender(appender);
        svcLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        svcLogger.detachAppender(appender);
        appender.stop();
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionMemoryUtils.resetSessionMemoryState();
        SessionMemoryService.setLastSummarizedMessageId(SESSION, null);
        SessionMemoryService.setLastSummarizedMessageId(null, null);
        SessionMemoryService.resetLastMemoryMessageUuid();
    }

    private Logger svcLogger;
    private ListAppender<ILoggingEvent> appender;

    @TempDir
    Path baseDir;

    /** 测试自带的独立 sha256（不复用被测代码的实现，避免「同错同绿」）。 */
    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChatMessageDto asst(String id, int tokens, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(id, null, Role.assistant, "assistant", "ok", null,
            toolCalls, FinishReason.stop, tokens, 0, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, Map.of(), List.of(), "", AbortController.NOOP, List.of());
    }

    private List<ILoggingEvent> forkStartEvents() {
        return appender.list.stream()
            .filter(e -> e.getFormattedMessage() != null && e.getFormattedMessage().contains(MARKER))
            .toList();
    }

    @Test
    @DisplayName("(c) 提取 fork 起点 → 发出 sessionId + path + currentMemoryLen + sha256，且不落全文")
    void forkStart_emitsLenAndSha256() throws Exception {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(new NoopQuery());
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(new ReadFileTool(PathGuard.of(baseDir.toString())));
        ToolUseContext tuc = baseContext();
        String sessionId = tuc.sessionId().toString();

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of(""), Map.of(), Map.of(),
            tuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        // 零行为变更：提取仍照常 fork（session_memory 源）+ 安全更新 lastSummarized
        Path memoryPath = baseDir.resolve(sessionId).resolve("session-memory").resolve("summary.md");
        assertThat(Files.exists(memoryPath)).as("setupSessionMemoryFile 仍成立文件").isTrue();
        assertThat(SessionMemoryService.getLastSummarizedMessageId(sessionId)).isEqualTo("a1");

        List<ILoggingEvent> events = forkStartEvents();
        assertThat(events).as("fork 起点必须发出恰好一条观测行").hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
        String msg = events.get(0).getFormattedMessage();

        // fork 起点读到的内容 = 盘上真实内容（CRLF 归一后）
        String normalized = Files.readString(memoryPath, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(msg).contains("session=" + sessionId);
        assertThat(msg).contains("path=" + memoryPath);
        assertThat(msg).contains("currentMemoryLen=" + normalized.length());
        assertThat(msg).contains("currentMemorySha256=" + sha256(normalized));
        // ⛔ 不落全文：只落长度 + 摘要
        assertThat(msg).as("currentMemory 全文绝不落盘").doesNotContain(normalized);
        assertThat(normalized).as("precondition: 模板非空（否则 sha 断言无判别力）").isNotEmpty();
    }

    @Test
    @DisplayName("(c') 同路径两次提取 → 两次观测行，第二次起点 sha 反映上一次提取后的盘上内容")
    void forkStart_secondRunReflectsDiskContent() throws Exception {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(new NoopQuery());
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(new ReadFileTool(PathGuard.of(baseDir.toString())));
        ToolUseContext tuc = baseContext();
        String sessionId = tuc.sessionId().toString();
        Path memoryPath = baseDir.resolve(sessionId).resolve("session-memory").resolve("summary.md");

        svc.extractSessionMemory(new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of(""), Map.of(), Map.of(),
            tuc, QuerySource.REPL_MAIN_THREAD));
        assertThat(forkStartEvents()).hasSize(1);
        String shaOfFirst = sha256(
            Files.readString(memoryPath, StandardCharsets.UTF_8).replace("\r\n", "\n"));

        // 模拟「两次 fork 之间内容被改」（H-D 的形态）：直接改盘上文件
        Files.writeString(memoryPath, "CHANGED BETWEEN FORKS\n", StandardCharsets.UTF_8);

        SessionMemoryService.setLastSummarizedMessageId(sessionId, null);
        SessionMemoryUtils.resetSessionMemoryState();
        svc.extractSessionMemory(new PostSamplingContext(
            List.of(asst("a2", 12000, List.of())), List.of(""), Map.of(), Map.of(),
            tuc, QuerySource.REPL_MAIN_THREAD));

        List<ILoggingEvent> events = forkStartEvents();
        assertThat(events).as("两次 fork ⇒ 两条观测行（可在日志里数出同路径被 fork 几次）").hasSize(2);
        // 第二次起点 = 改动后的内容（这正是 H-D 的判读依据：起点 sha ≠ 上次起点 sha）
        String second = events.get(1).getFormattedMessage();
        assertThat(second).contains("currentMemoryLen=" + "CHANGED BETWEEN FORKS\n".length());
        assertThat(second).contains("currentMemorySha256=" + sha256("CHANGED BETWEEN FORKS\n"));
        assertThat(second)
            .as("两次 fork 起点 sha 不同 ⇒ 该通道能看出「两次 fork 之间内容被改过」")
            .doesNotContain("currentMemorySha256=" + shaOfFirst);
    }

    /** [SM-fork] fork 查询 seam 的最小假实现（不跑真实 Agent 循环）。 */
    private static final class NoopQuery implements RunForkedAgent.ForkedQuery {
        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            return new ForkedAgentResult(List.of(), ForkedAgentResult.ForkUsage.empty());
        }
    }
}
