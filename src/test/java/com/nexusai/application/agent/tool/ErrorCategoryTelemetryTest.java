package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session I · P3-3 errorCategory telemetry 字段测试 · R32-b15 文档化 Java 偏离:
 *   CC 真源为 toolExecution.ts:1643 classifyToolError() → error attr; Java 8 桶分类注入
 *   ToolResult.errorCategory (偏离声明见 ToolResult.java:46).
 *
 * <p><b>WHY (意图验证)</b>: P3-3 把 errorCategory 字段从 ToolResult 注入到:
 * <ol>
 *   <li>{@code emitSuccessTelemetry} (成功路径, successAttrs map)</li>
 *   <li>{@code emitFailureTelemetry} (executor throw 路径, errAttrs + oTelAttrs map)</li>
 *   <li>{@code emitPostToolUseFailureAnalytics} (PostToolUseFailure 路径, errAttrs + oTelAttrs map)</li>
 * </ol>
 *
 * <p>Pattern #11: errorCategory 为 null 时**不早返** (telemetry 真实反映 null 状态, 不 bypass).
 * 测试验证源码包含正确的 null-tolerant 注入模式:
 * <ul>
 *   <li>成功路径: {@code successAttrs.put("errorCategory", t.errorCategory)} — 即使 null 也 put</li>
 *   <li>失败路径: 把 errorCategory 作为参数传入 emitFailureTelemetry, 注入 errAttrs/oTelAttrs</li>
 * </ul>
 *
 * <p>本测试采用源码 grep 验证 (对齐 R32B12_TelemetryTest 既有模式: emitSuccessTelemetry /
 * emitPostToolUseFailureAnalytics 都是 private 方法, 端到端需 Spring 集成, b12 决策"本测试覆盖
 * 纯逻辑函数 + 源码契约"). Session I P3-3 同样: 验证源码契约, 不依赖 OTel 运行时.
 *
 * <p><b>关键: 用 regex + negative lookbehind 排除 {@code //} 注释行</b>, 否则 "RED 测试" 仅删
 * 代码注释也能让测试通过 (虚假的 GREEN). 本测试用 {@code (?m)^[ \\t]+(?<!//)} 风格的多行模式:
 * <ul>
 *   <li>{@code (?m)} — 多行模式, ^ 匹配每行开头</li>
 *   <li>{@code ^[ \\t]+} — 行首缩进 (Java 8 空格或 tab, 实际本工程用 4 空格缩进)</li>
 *   <li>不匹配 {@code //} 开头的行 (注释行)</li>
 * </ul>
 *
 * <h2>测试用例 (2 项: success + failure)</h2>
 * <ol>
 *   <li>{@link #successTelemetryIncludesErrorCategory()} — 成功路径 put errorCategory</li>
 *   <li>{@link #failureTelemetryIncludesErrorCategory()} — 失败路径透传 errorCategory 参数</li>
 * </ol>
 */
class ErrorCategoryTelemetryTest {

    private static final String SOURCE_PATH =
        "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java";

    /**
     * Pattern #14 抗注释匹配: 多行模式 + 行首缩进 + 非注释行.
     *
     * <p>关键: Java 注释行首是 {@code //}, 缩进可能在 {@code //} 前或后. 本 regex 要求
     * 行首有缩进 ({@code ^[ \\t]+}) 然后是非 {@code //} 字符 ({@code [^/]} 或 {@code [^/][^/]}).
     * 实际 Java 代码行要么是纯缩进 + statement (statement 首字符不是 /), 要么是
     * 缩进 + @Override/@Deprecated annotation. 注释行是 {@code // comment} 或
     * 缩进 + {@code // comment}, 都以 {@code //} 开头.
     *
     * <p>为简单起见, regex 用 {@code ^[ \\t]+(?!//)} 形式: 行首缩进后非注释起始.
     */
    private static final Pattern NON_COMMENT_LINE = Pattern.compile(
        "(?m)^[ \\t]+(?!//).*");

    /** 找出源文件中所有非注释代码行 (用于精确匹配). */
    private String codeLinesOnly(String source) {
        StringBuilder sb = new StringBuilder();
        for (String line : source.split("\n")) {
            // 检查行首缩进后是否为 // 注释
            String trimmed = line.replaceFirst("^[ \\t]+", "");
            if (trimmed.startsWith("//")) {
                continue; // 跳过注释行
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private String readSource() throws Exception {
        return Files.readString(Path.of(SOURCE_PATH));
    }

    // ─────────── 1. 成功路径 (emitSuccessTelemetry) ───────────

    @Test
    @DisplayName("I-S1 成功路径 emitSuccessTelemetry 注入 errorCategory (从 TrackedTool.errorCategory 字段取)")
    void successTelemetryIncludesErrorCategory() throws Exception {
        String codeOnly = codeLinesOnly(readSource());

        // 验证 emitSuccessTelemetry 函数体内有 errorCategory 注入 (successAttrs map).
        //   CC 真源: toolExecution.ts:150/:1643-1645 classifyToolError() → error attr (CC 无 ToolResult.errorCategory() API).
        //   (result.isError() == false 早返), 仍按 Pattern #11 emit null, 不 bypass.
        //   必须是非注释代码行 (排除 // successAttrs.put(...))
        assertThat(codeOnly)
            .as("emitSuccessTelemetry 必须 inject errorCategory from TrackedTool.errorCategory 字段 (非注释行)")
            .contains("successAttrs.put(\"errorCategory\", t.errorCategory);");
    }

    // ─────────── 2. 失败路径 (emitFailureTelemetry) ───────────

    @Test
    @DisplayName("I-S2 失败路径 emitFailureTelemetry 透传 errorCategory (从 catch 块入参) · Pattern #11 null 也 emit")
    void failureTelemetryIncludesErrorCategory() throws Exception {
        String source = readSource();
        String codeOnly = codeLinesOnly(source);

        // 1. 验证 emitFailureTelemetry 函数签名包含 errorCategory 参数
        //   (新增参数, 从 catch (Throwable th) 块的 classifyToolError(th) 结果透传).
        assertThat(codeOnly)
            .as("emitFailureTelemetry 必须新增 errorCategory 参数 (从 classifyToolError 透传)")
            .containsPattern(
                "private void emitFailureTelemetry\\s*\\(\\s*TrackedTool\\s+t\\s*,\\s*Throwable\\s+th\\s*,\\s*long\\s+durationMs\\s*,\\s*String\\s+errorCategory\\)");

        // 2. 验证函数体内把 errorCategory 注入 errAttrs (Statsig/1P 路径)
        assertThat(codeOnly)
            .as("emitFailureTelemetry 必须 inject errorCategory to errAttrs map (1P/Statsig) (非注释行)")
            .contains("errAttrs.put(\"errorCategory\", errorCategory);");

        // 3. 验证函数体内把 errorCategory 注入 oTelAttrs (OTel 路径, snake_case key)
        assertThat(codeOnly)
            .as("emitFailureTelemetry 必须 inject error_category to oTelAttrs map (OTel snake_case) (非注释行)")
            .contains("oTelAttrs.put(\"error_category\", errorCategory);");

        // 4. 验证 call site 把 errorCategory (catch 块内的 classifyToolError 结果) 透传给 emitFailureTelemetry
        assertThat(codeOnly)
            .as("emitFailureTelemetry call site 必须透传 errorCategory (从 catch 块变量) (非注释行)")
            .contains("emitFailureTelemetry(t, th, System.currentTimeMillis() - t0, errorCategory);");
    }
}