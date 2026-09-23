package com.nexusai.application.agent.tool.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
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
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [edit-obs-2a] EditFileTool SNF(errorCode 8) 失败路径观测测试。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：Edit 失败 415 次里 308 次是
 * {@code String to replace not found}（74.2%），其中 305 次落在 nexusai 自己的会话记忆文件上。
 * 上一轮只读判定把 H-A（同批内自打）/ H-B（跨批陈旧）都否掉了，唯一有正面证据的方向是
 * H-C（模型逐字复现失准）—— 但「约 9 成 SNF 用现有日志判不出根因」。要收口必须先落盘
 * 「失败时刻的 old_string / 磁盘内容」以及能把「误引」与「漂移」分开的派生量。
 *
 * <p>本测试锁定三件事：
 * <ol>
 *   <li><b>失败时必打</b>：SNF 分支必须发出 INFO 观测行，且含 old_string 全文、磁盘 sha256、
 *       以及全部判别性派生量。少了任何一项，人工判读就回到了「判不出根因」的起点。</li>
 *   <li><b>只失败时打</b>：成功的 Edit 绝不发该行 —— 成功路径两周 6301 次，误打会炸日志
 *       （体量/性价比纪律）。</li>
 *   <li><b>派生量本身正确</b>：逐字命中 / 仅空白差异命中 / 完全不命中 / BOM 差异 / 行号形态
 *       五类输入各自的取值必须正确，否则判读结论会被错的派生量带偏。</li>
 * </ol>
 *
 * <p>⚠️ 本测试同时钉死「零行为变更」：新增观测不改变 validateInput 的返回值与 errorCode。
 */
@DisplayName("[edit-obs-2a] EditFileTool SNF 失败路径观测（含派生量）")
class EditFileToolSnfObservationTest {

    // ── 夹具 DB 姿态显式声明（同 EditFileToolErrorCode2Test）：不接 DB 回源 ⇒ 未绑定 sessionId
    //   属「确无会话」；⛔ 不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ fail-loud 抛。
    @BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
        editLogger = (Logger) LoggerFactory.getLogger(EditFileTool.class);
        appender = new ListAppender<>();
        appender.start();
        editLogger.addAppender(appender);
        editLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        editLogger.detachAppender(appender);
        appender.stop();
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    private Logger editLogger;
    private ListAppender<ILoggingEvent> appender;

    /** 观测行标识（与成功路径的其它 INFO 行区分）。 */
    private static final String SNF_MARKER = "EditFileTool SNF 观测(非判定)";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode editInput(String path, String oldText, String newText) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", oldText);
        input.put("new_string", newText);
        return input;
    }

    private static ToolUseBlock readCallWith(String path) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        return new ToolUseBlock("call-read", "read_file", input);
    }

    private static ToolUseContext ctxFor(Path workspace) {
        UUID agentId = UUID.nameUUIDFromBytes(("obs-agent-" + workspace).getBytes(StandardCharsets.UTF_8));
        String sessionId = UUID.nameUUIDFromBytes(("obs-sess-" + workspace).getBytes(StandardCharsets.UTF_8)).toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    /** 测试自带的独立 sha256（不复用被测代码的实现，避免「同错同绿」）。 */
    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ILoggingEvent> snfEvents() {
        return appender.list.stream()
            .filter(e -> e.getFormattedMessage() != null && e.getFormattedMessage().contains(SNF_MARKER))
            .toList();
    }

    // ════════════════════════════════════════════════════════════════════
    // (a) 构造一次 SNF ⇒ 断言观测行发出且字段齐全
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(a) SNF(old_string 不在文件里) → 发出观测行：old 全文 + 磁盘 sha256 + 派生量")
    void snf_emitsObservationLineWithAllFields(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello world\n", StandardCharsets.UTF_8);
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);
        // 先 Read 满足 read-before-write 门禁
        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

        String oldText = "not present anywhere";
        Tool.ValidationResult vr = tool.validateInput(editInput("a.txt", oldText, "X"), ctx);

        // 零行为变更：失败语义与 errorCode 与加观测前完全一致
        assertThat(vr.ok()).isFalse();
        assertThat(vr.errorCode()).isEqualTo("8");
        assertThat(vr.message()).isEqualTo("String to replace not found in file.\nString: " + oldText);

        List<ILoggingEvent> snf = snfEvents();
        assertThat(snf).as("SNF 必须发出恰好一条观测行").hasSize(1);
        assertThat(snf.get(0).getLevel()).isEqualTo(Level.INFO);
        String msg = snf.get(0).getFormattedMessage();

        // (a) old_string 全文 + sha256
        assertThat(msg).contains("oldString=[not present anywhere]");
        assertThat(msg).contains("oldSha256=" + sha256(oldText));
        assertThat(msg).contains("oldLen=" + oldText.length());
        // (b) new_string 只落长度 + sha256（⛔ 不落全文）
        assertThat(msg).contains("newSha256=" + sha256("X"));
        assertThat(msg).contains("newLen=1");
        assertThat(msg).as("new_string 全文绝不落盘").doesNotContain("newString=[");
        // (c) 施加时刻磁盘内容 sha256 + 字节长度（sha256("hello world\n")，UTF-8 12 字节）
        assertThat(msg).contains("diskSha256=" + sha256("hello world\n"));
        assertThat(msg).contains("diskBytes=12");
        // (d) 判别性派生量
        assertThat(msg).contains("verbatimHit=false");
        assertThat(msg).contains("wsNormHit=false");
        assertThat(msg).contains("bomStripHit=false");
        assertThat(msg).contains("lineNoPrefix=false");
        assertThat(msg).contains("lcpLen=0");
        assertThat(msg).contains("alignPos=-1");
        assertThat(msg).contains("ctxBefore=[]");
        assertThat(msg).contains("ctxAfter=[]");
        assertThat(msg).contains("head80=[not present anywhere]");
        assertThat(msg).contains("tail80=[not present anywhere]");
        // 路径与 old_string 全文便于 grep 定位（本批要判「误引 vs 漂移」须有文本本身）
        assertThat(msg).contains("a.txt");
    }

    // ════════════════════════════════════════════════════════════════════
    // (b) 反面：成功的 Edit ⇒ 绝不打该观测行（钉死「只失败时打」）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(b) 成功 Edit → 不发出观测行（成功路径 6301 次零日志、零开销）")
    void successEdit_emitsNoObservationLine(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("b.txt"), "hello world\n", StandardCharsets.UTF_8);
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);
        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("b.txt"), ctx);

        Tool.ValidationResult vr = tool.validateInput(editInput("b.txt", "hello", "bye"), ctx);

        assertThat(vr.ok()).as("old_string 唯一命中 → 成功通过").isTrue();
        assertThat(snfEvents()).as("成功路径绝不发 SNF 观测行").isEmpty();
    }

    @Test
    @DisplayName("(b') 多匹配(errorCode 9) 与空 old(errorCode 3) 也绝不打该观测行")
    void otherFailureCodes_emitNoObservationLine(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("c.txt"), "dup dup\n", StandardCharsets.UTF_8);
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);
        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("c.txt"), ctx);

        assertThat(tool.validateInput(editInput("c.txt", "dup", "x"), ctx).errorCode()).isEqualTo("9");
        assertThat(tool.validateInput(editInput("c.txt", "", "x"), ctx).errorCode()).isEqualTo("3");

        assertThat(snfEvents()).as("观测行只在 errorCode 8 分支").isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // (d) 派生量本身：五类可判输入
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(d1) 逐字命中：disk 含 old 原文 → verbatimHit/wsNormHit/bomStripHit 全 true，lcp=old 长度")
    void diagnostics_verbatimHit() {
        EditFileTool.SnfDiagnostics d =
            EditFileTool.computeSnfDiagnostics("alpha beta gamma", "beta");

        assertThat(d.verbatimHit()).isTrue();
        assertThat(d.whitespaceNormalizedHit()).isTrue();
        assertThat(d.bomStrippedHit()).isTrue();
        assertThat(d.lineNumberPrefixShape()).isFalse();
        assertThat(d.longestCommonPrefixLen()).isEqualTo(4);
        assertThat(d.bestAlignPos()).isEqualTo(6);
        assertThat(d.contextBefore()).isEqualTo("alpha ");
        assertThat(d.contextAfter()).isEqualTo("beta gamma");
        assertThat(d.head80()).isEqualTo("beta");
        assertThat(d.tail80()).isEqualTo("beta");
        assertThat(d.diskSha256()).isEqualTo(sha256("alpha beta gamma"));
        assertThat(d.diskByteLen()).isEqualTo("alpha beta gamma".getBytes(StandardCharsets.UTF_8).length);
        assertThat(d.oldSha256()).isEqualTo(sha256("beta"));
        assertThat(d.oldCharLen()).isEqualTo(4);
    }

    @Test
    @DisplayName("(d2) 仅空白差异命中：CRLF↔LF 差异 → verbatimHit=false 但 wsNormHit=true")
    void diagnostics_whitespaceAndNewlineOnlyDifference() {
        // CRLF↔LF 差异
        EditFileTool.SnfDiagnostics crlf =
            EditFileTool.computeSnfDiagnostics("line1\r\nline2\r\n", "line1\nline2");
        assertThat(crlf.verbatimHit()).isFalse();
        assertThat(crlf.whitespaceNormalizedHit()).isTrue();
        assertThat(crlf.bomStrippedHit()).isFalse();
        assertThat(crlf.longestCommonPrefixLen()).isEqualTo(5);
        assertThat(crlf.bestAlignPos()).isEqualTo(0);

        // 折叠连续空白（空格/tab 数量差）
        EditFileTool.SnfDiagnostics ws =
            EditFileTool.computeSnfDiagnostics("a   b\t c", "a b c");
        assertThat(ws.verbatimHit()).isFalse();
        assertThat(ws.whitespaceNormalizedHit()).isTrue();
        assertThat(ws.bomStrippedHit()).isFalse();
        assertThat(ws.longestCommonPrefixLen()).isEqualTo(2);
        assertThat(ws.bestAlignPos()).isEqualTo(0);
    }

    @Test
    @DisplayName("(d3) 完全不命中：无任何公共前缀 → 各派生量全 false / lcp=0 / alignPos=-1")
    void diagnostics_noHitAtAll() {
        EditFileTool.SnfDiagnostics d =
            EditFileTool.computeSnfDiagnostics("alpha beta gamma", "zzz completely different");

        assertThat(d.verbatimHit()).isFalse();
        assertThat(d.whitespaceNormalizedHit()).isFalse();
        assertThat(d.bomStrippedHit()).isFalse();
        assertThat(d.lineNumberPrefixShape()).isFalse();
        assertThat(d.longestCommonPrefixLen()).isZero();
        assertThat(d.bestAlignPos()).isEqualTo(-1);
        assertThat(d.contextBefore()).isEmpty();
        assertThat(d.contextAfter()).isEmpty();
    }

    @Test
    @DisplayName("(d4) BOM 差异：old_string 抄进了 BOM → verbatimHit=false 但 bomStripHit=true")
    void diagnostics_bomOnlyDifference() {
        EditFileTool.SnfDiagnostics d =
            EditFileTool.computeSnfDiagnostics("hello world\n", "﻿hello world");

        assertThat(d.verbatimHit()).isFalse();
        assertThat(d.bomStrippedHit()).isTrue();
        assertThat(d.longestCommonPrefixLen()).isZero();
    }

    @Test
    @DisplayName("(d5) 行号形态：old_string 以 '  123->' 或 '123\\t' 开头 → lineNoPrefix=true")
    void diagnostics_lineNumberPrefixShape() {
        assertThat(EditFileTool.computeSnfDiagnostics("x", "  123->foo").lineNumberPrefixShape()).isTrue();
        assertThat(EditFileTool.computeSnfDiagnostics("x", "123\tfoo").lineNumberPrefixShape()).isTrue();
        assertThat(EditFileTool.computeSnfDiagnostics("x", "123→foo").lineNumberPrefixShape()).isTrue();
        assertThat(EditFileTool.computeSnfDiagnostics("x", "123 foo").lineNumberPrefixShape()).isFalse();
        assertThat(EditFileTool.computeSnfDiagnostics("x", "foo123->bar").lineNumberPrefixShape()).isFalse();
    }

    @Test
    @DisplayName("(d6) 上下文截断：最佳对齐位置前后各 40 字符（验证截断边界）")
    void diagnostics_contextTruncation() {
        String before = "B".repeat(50);
        String disk = before + "MATCHME" + "A".repeat(50);
        EditFileTool.SnfDiagnostics d = EditFileTool.computeSnfDiagnostics(disk, "MATCHxxx");

        assertThat(d.bestAlignPos()).isEqualTo(50);
        assertThat(d.longestCommonPrefixLen()).isEqualTo(5);
        assertThat(d.contextBefore()).hasSize(40).isEqualTo(before.substring(10));
        assertThat(d.contextAfter()).hasSize(40).isEqualTo("MATCHME" + "A".repeat(33));
    }
}
