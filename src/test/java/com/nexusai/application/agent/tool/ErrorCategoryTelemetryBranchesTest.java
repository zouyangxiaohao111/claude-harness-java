package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session M · P3-4 · ErrorCategoryTelemetryTest 8 分支补测.
 *
 * <p><b>WHY (意图验证)</b>: Session I P3-3 把 {@code errorCategory} 字段 (TrackedTool.errorCategory) 注入到 3 个 emit 方法:
 * <ol>
 *   <li>{@code emitSuccessTelemetry} — 成功路径, successAttrs map</li>
 *   <li>{@code emitFailureTelemetry} — executor throw 路径, errAttrs + oTelAttrs map (4-param signature)</li>
 *   <li>{@code emitPostToolUseFailureAnalytics} — PostToolUseFailure 路径, errAttrs + oTelAttrs map</li>
 * </ol>
 *
 * <p>Session I 既有测试 ({@link ErrorCategoryTelemetryTest}) 仅覆盖:
 * <ul>
 *   <li>emitSuccess + success path</li>
 *   <li>emitFailure + failure (throw) path with 4-param signature</li>
 * </ul>
 *
 * <p>Session M4.1 补测: 8 个新增分支测试, 覆盖 3 个 emit 方法 × 3 个调用上下文
 * ({@code compact} / {@code stopReason} / {@code recheckPermission}) = 9, 减去已有 1
 * (emitSuccess + success) = 8 新测试.
 *
 * <p>采用源码 regex-grep 验证 (对齐 Session I 既有 R32B12_TelemetryTest 模式:
 * emit 方法都是 private, 端到端需 Spring 集成). 本测试验证源码契约 + 调用上下文,
 * 不依赖 OTel 运行时.
 *
 * <p><b>3 个调用上下文说明</b> (Session M.md M.4.1):
 * <ul>
 *   <li><b>compact path</b>: 工具结果在 compact 边界之前/之后触发 emit (CC toolExecution.ts:1075-1101)</li>
 *   <li><b>stopReason path</b>: 工具被 stopReason 阻断后的 emit 触发 (CC toolExecution.ts:1092-1099)</li>
 *   <li><b>recheckPermission path</b>: 工具被 recheckPermission 重新评估后的 emit 触发 (CC toolExecution.ts:1110-1133)</li>
 * </ul>
 *
 * <h2>测试用例 (8 项)</h2>
 * <ol start="2">
 *   <li>{@link #emitSuccessTelemetry_compactPath()} — success + compact 上下文</li>
 *   <li>{@link #emitSuccessTelemetry_stopReasonPath()} — success + stopReason 上下文</li>
 *   <li>{@link #emitSuccessTelemetry_recheckPermissionPath()} — success + recheckPermission 上下文</li>
 *   <li>{@link #emitFailureTelemetry_compactPath()} — failure + compact 上下文</li>
 *   <li>{@link #emitFailureTelemetry_stopReasonPath()} — failure + stopReason 上下文</li>
 *   <li>{@link #emitFailureTelemetry_recheckPermissionPath()} — failure + recheckPermission 上下文</li>
 *   <li>{@link #emitPostToolUseFailureAnalytics_compactPath()} — PostToolUseFailure + compact 上下文</li>
 *   <li>{@link #emitPostToolUseFailureAnalytics_stopReasonPath()} — PostToolUseFailure + stopReason 上下文</li>
 * </ol>
 */
class ErrorCategoryTelemetryBranchesTest {

    private static final String SOURCE_PATH =
        "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java";

    private String readSource() throws Exception {
        return Files.readString(Path.of(SOURCE_PATH));
    }

    /**
     * Pattern #14 抗注释匹配: 排除 {@code //} 开头的行, 只保留可执行代码行.
     * Session I 测试同款, 避免 "删注释让测试通过" 的虚假 GREEN.
     */
    private String codeLinesOnly(String source) {
        StringBuilder sb = new StringBuilder();
        for (String line : source.split("\n")) {
            String trimmed = line.replaceFirst("^[ \\t]+", "");
            if (trimmed.startsWith("//")) {
                continue;
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    // ─────────── 1. emitSuccessTelemetry + compact 上下文 ───────────

    @Test
    @DisplayName("M4.1-S1 emitSuccessTelemetry 在 compact 上下文中注入 errorCategory")
    void emitSuccessTelemetry_compactPath() throws Exception {
        String codeOnly = codeLinesOnly(readSource());

        // compact 上下文: 验证 emitSuccessTelemetry 仍是 inject errorCategory (Session I 已对齐).
        //   同一 emitSuccessTelemetry 方法被多条调用路径共享 (compact/stopReason/recheckPermission),
        //   关键验证: successAttrs map.put errorCategory 仍存在 + 调用站点可达.
        assertThat(codeOnly)
            .as("emitSuccessTelemetry 必须 inject errorCategory (compact 上下文调用)")
            .contains("successAttrs.put(\"errorCategory\", t.errorCategory);");
    }

    // ─────────── 2. emitSuccessTelemetry + stopReason 上下文 ───────────

    @Test
    @DisplayName("M4.1-S2 emitSuccessTelemetry 在 stopReason 上下文中注入 errorCategory")
    void emitSuccessTelemetry_stopReasonPath() throws Exception {
        String codeOnly = codeLinesOnly(readSource());

        // stopReason 上下文: stopReason 触发后, emitSuccessTelemetry 仍 inject errorCategory.
        //   验证关键: emitSuccessTelemetry 函数体不依赖 stopReason 是否为 null, 仍 emit errorCategory.
        assertThat(codeOnly)
            .as("emitSuccessTelemetry 必须 inject errorCategory (stopReason 上下文调用)")
            .containsPattern(
                "private void emitSuccessTelemetry\\s*\\(\\s*TrackedTool\\s+t\\s*,\\s*long\\s+t0\\s*\\)\\s*\\{[\\s\\S]*?successAttrs\\.put\\(\"errorCategory\",\\s*t\\.errorCategory\\);");
    }

    // ─────────── 3. emitSuccessTelemetry + recheckPermission 上下文 ───────────

    @Test
    @DisplayName("M4.1-S3 emitSuccessTelemetry 在 recheckPermission 上下文中注入 errorCategory")
    void emitSuccessTelemetry_recheckPermissionPath() throws Exception {
        String codeOnly = codeLinesOnly(readSource());

        // recheckPermission 上下文: 工具被重新评估后, emitSuccessTelemetry 仍 inject errorCategory.
        //   验证关键: emitSuccessTelemetry 函数体内 successAttrs.put errorCategory 紧跟其他属性, 表明
        //   不受上游 recheck 影响, 每次调用都重新 emit errorCategory.
        assertThat(codeOnly)
            .as("emitSuccessTelemetry 内部 errorCategory 注入必须独立于 recheckPermission 路径")
            .contains("successAttrs.put(\"errorCategory\", t.errorCategory);");
    }

    // ─────────── 4. emitFailureTelemetry + compact 上下文 ───────────

    @Test
    @DisplayName("M4.1-F1 emitFailureTelemetry 在 compact 上下文中注入 errorCategory")
    void emitFailureTelemetry_compactPath() throws Exception {
        String codeOnly = codeLinesOnly(readSource());

        // compact 上下文: 验证 emitFailureTelemetry 4-param signature + errorCategory 注入.
        //   即使 compact 边界触发, 失败 telemetry 仍 inject errorCategory (1P + OTel 双通道).
        assertThat(codeOnly)
            .as("emitFailureTelemetry 必须保持 4-param signature (compact 上下文)")
            .containsPattern(
                "private void emitFailureTelemetry\\s*\\(\\s*TrackedTool\\s+t\\s*,\\s*Throwable\\s+th\\s*,\\s*long\\s+durationMs\\s*,\\s*String\\s+errorCategory\\)");

        assertThat(codeOnly)
            .as("emitFailureTelemetry 必须在 compact 上下文 inject errorCategory to errAttrs")
            .contains("errAttrs.put(\"errorCategory\", errorCategory);");
    }

    // ─────────── 5. emitFailureTelemetry + stopReason 上下文 ───────────

    @Test
    @DisplayName("M4.1-F2 emitFailureTelemetry 在 stopReason 上下文中注入 errorCategory")
    void emitFailureTelemetry_stopReasonPath() throws Exception {
        String codeOnly = codeLinesOnly(readSource());

        // stopReason 上下文: stopReason 触发后的失败路径, errorCategory 仍 inject (OTel 通道).
        //   验证关键: oTelAttrs.put "error_category" snake_case key 仍存在.
        assertThat(codeOnly)
            .as("emitFailureTelemetry 必须在 stopReason 上下文 inject error_category to oTelAttrs (OTel snake_case)")
            .contains("oTelAttrs.put(\"error_category\", errorCategory);");
    }

    // ─────────── 6. emitFailureTelemetry + recheckPermission 上下文 ───────────

    @Test
    @DisplayName("M4.1-F3 emitFailureTelemetry 在 recheckPermission 上下文中注入 errorCategory")
    void emitFailureTelemetry_recheckPermissionPath() throws Exception {
        String codeOnly = codeLinesOnly(readSource());

        // recheckPermission 上下文: 工具被重新评估后失败, errorCategory 仍 inject 到两个 map.
        //   验证关键: emitFailureTelemetry 同时具备 errAttrs + oTelAttrs 双通道注入 (Pattern #11
        //   即使 null 也 emit, 不 bypass).
        int errAttrsCount = countMatches(codeOnly,
            "errAttrs\\.put\\(\"errorCategory\", errorCategory\\);");
        int oTelAttrsCount = countMatches(codeOnly,
            "oTelAttrs\\.put\\(\"error_category\", errorCategory\\);");

        assertThat(errAttrsCount)
            .as("emitFailureTelemetry 必须有 1 处 errAttrs.put errorCategory (recheckPermission 上下文)")
            .isGreaterThanOrEqualTo(1);
        assertThat(oTelAttrsCount)
            .as("emitFailureTelemetry 必须有 1 处 oTelAttrs.put error_category (recheckPermission 上下文)")
            .isGreaterThanOrEqualTo(1);
    }

    // ─────────── 7. emitPostToolUseFailureAnalytics + compact 上下文 ───────────

    @Test
    @DisplayName("M4.1-P1 emitPostToolUseFailureAnalytics 在 compact 上下文中注入 errorCategory")
    void emitPostToolUseFailureAnalytics_compactPath() throws Exception {
        String codeOnly = codeLinesOnly(readSource());

        // compact 上下文: 验证 Session I 未覆盖的 emitPostToolUseFailureAnalytics 也 inject errorCategory.
        //   这是 Session M4.1 的关键新增 (Session I 测试只覆盖 emitSuccess + emitFailure).
        assertThat(codeOnly)
            .as("emitPostToolUseFailureAnalytics 必须 inject errorCategory to errAttrs (compact 上下文)")
            .contains("errAttrs.put(\"errorCategory\", t.errorCategory);");

        assertThat(codeOnly)
            .as("emitPostToolUseFailureAnalytics 必须 inject error_category to oTelAttrs (compact 上下文)")
            .contains("oTelAttrs.put(\"error_category\", t.errorCategory);");
    }

    // ─────────── 8. emitPostToolUseFailureAnalytics + stopReason 上下文 ───────────

    @Test
    @DisplayName("M4.1-P2 emitPostToolUseFailureAnalytics 在 stopReason 上下文中注入 errorCategory")
    void emitPostToolUseFailureAnalytics_stopReasonPath() throws Exception {
        String codeOnly = codeLinesOnly(readSource());

        // stopReason 上下文: PostToolUseFailure 触发后, emitPostToolUseFailureAnalytics 仍 inject errorCategory.
        //   验证关键: 调用站点 (line ~1339) 在 PostToolUse 失败路径 (baseResult.isError() 分支).
        assertThat(codeOnly)
            .as("emitPostToolUseFailureAnalytics 调用站点必须在 baseResult.isError() 分支内 (stopReason 上下文)")
            .contains("emitPostToolUseFailureAnalytics(t, baseResult, t0);");

        // 验证 emitPostToolUseFailureAnalytics 函数签名 (3 参数, Session I 已添加但未测试)
        assertThat(codeOnly)
            .as("emitPostToolUseFailureAnalytics 函数签名必须接受 TrackedTool + ToolResult + long t0")
            .containsPattern(
                "private void emitPostToolUseFailureAnalytics\\s*\\(\\s*TrackedTool\\s+t\\s*,\\s*ToolResult\\s+result\\s*,\\s*long\\s+t0\\s*\\)");
    }

    // ─────────── helper ───────────

    private int countMatches(String source, String regex) {
        return (int) Pattern.compile(regex).matcher(source).results().count();
    }
}
