package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.permission.hook.AbortException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R32-b15 C15 · ToolErrorFormatter 三类输出验证 · 对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:150-170, 615-680, 1631-1694}.
 *
 * <p><b>WHY (意图验证, CLAUDE.md 规则 9)</b>: 工具错误处理是状态机正确性基础.
 * 异常分类 (telemetry-safe) / 用户可读错误 / Zod 风格 schema issue 三种格式
 * 必须各自独立 + 互不污染. 一处格式错乱即破坏 LLM 自纠 + telemetry 归因.
 *
 * <p><b>关键不变式</b>:
 * <ul>
 *   <li>{@link ToolErrorFormatter#classifyToolError} 返回值是 CC 细粒度字符串
 *       （toolExecution.ts:150-171：类名 slice(0,60) / 'Error' / 'UnknownError'），
 *       <b>不含路径/源码/长 message</b> (telemetry 隐私硬约束).</li>
 *   <li>{@link ToolErrorFormatter#formatError} 给 LLM 看, 逐字对齐 CC toolErrors.ts:5-22
 *       （message+stderr/stdout parts join / 10000 中段截断 / AbortError 特例）. [P-25]</li>
 *   <li>{@link ToolErrorFormatter#formatValidationError} 把 {@link Tool.ValidationResult}
 *       转为 Zod 风格 issue 列表, {@link ToolErrorFormatter#joinIssuesForLlm} 拼接为 LLM 文案.</li>
 * </ul>
 *
 * <p><b>ER-IMP-15 / DC-14</b>: 旧 {@code sanitizeMessage} / {@code stripPaths} /
 * {@code formatForTelemetry} 三个 main 零调用死方法已删除，对应 C15 用例同步删除。
 */
class R32B15_ToolErrorFormatterTest {

    @Test
    @DisplayName("P-25: classifyToolError 把 AbortException 归为类名 'AbortException' (CC name.slice(0,60))")
    void classifyAbortException() {
        AbortException ex = new AbortException("user interrupt");
        assertThat(ToolErrorFormatter.classifyToolError(ex)).isEqualTo("AbortException");
    }

    @Test
    @DisplayName("P-25: classifyToolError 把 IOException 归为类名 'IOException'")
    void classifyIoException() {
        assertThat(ToolErrorFormatter.classifyToolError(new IOException("disk full"))).isEqualTo("IOException");
    }

    @Test
    @DisplayName("P-25: classifyToolError 把 RuntimeException 归为类名 'RuntimeException'")
    void classifyRuntimeException() {
        assertThat(ToolErrorFormatter.classifyToolError(new RuntimeException("unexpected")))
            .isEqualTo("RuntimeException");
    }

    @Test
    @DisplayName("P-25: classifyToolError 把 IllegalArgumentException 归为类名 'IllegalArgumentException'")
    void classifyIllegalArgument() {
        assertThat(ToolErrorFormatter.classifyToolError(new IllegalArgumentException("bad arg")))
            .isEqualTo("IllegalArgumentException");
    }

    @Test
    @DisplayName("P-25: classifyToolError 把 InterruptedException 归为类名 'InterruptedException'")
    void classifyInterrupted() {
        assertThat(ToolErrorFormatter.classifyToolError(new InterruptedException("wait interrupted")))
            .isEqualTo("InterruptedException");
    }

    @Test
    @DisplayName("P-25: classifyToolError 把 Error 子类归为类名 'OutOfMemoryError' (CC instanceof Error 全归 name)")
    void classifyError() {
        assertThat(ToolErrorFormatter.classifyToolError(new OutOfMemoryError("oom")))
            .isEqualTo("OutOfMemoryError");
    }

    @Test
    @DisplayName("P-25: classifyToolError(null) 返回 'UnknownError' (CC 非 Error 分支)")
    void classifyNull() {
        assertThat(ToolErrorFormatter.classifyToolError(null)).isEqualTo("UnknownError");
    }

    /** 类名 ≤3 字符（CC name.length > 3 检查失败 → 'Error' 兜底）。 */
    static final class Err extends RuntimeException {
        Err(String msg) { super(msg); }
    }

    @Test
    @DisplayName("P-25: classifyToolError 类名 ≤3 字符 → 'Error' (CC name.length>3 门)")
    void classifyShortName() {
        assertThat(ToolErrorFormatter.classifyToolError(new Err("short"))).isEqualTo("Error");
    }

    @Test
    @DisplayName("P-25: formatError 输出 message parts (CC toolErrors.ts:12-14, 无 Class 前缀)")
    void formatErrorForLlm() {
        IOException ex = new IOException("disk full at /home/user/data");
        assertThat(ToolErrorFormatter.formatError(ex)).isEqualTo("disk full at /home/user/data");
    }

    @Test
    @DisplayName("P-25: formatError(null) → 'null' (CC String(null))")
    void formatErrorNull() {
        assertThat(ToolErrorFormatter.formatError(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("P-25: formatError 无 message/stderr/stdout → 'Command failed with no output'")
    void formatErrorNoOutput() {
        assertThat(ToolErrorFormatter.formatError(new RuntimeException())).isEqualTo("Command failed with no output");
    }

    @Test
    @DisplayName("P-25: formatError AbortException → message (CC toolErrors.ts:6-8)")
    void formatErrorAbort() {
        assertThat(ToolErrorFormatter.formatError(new AbortException("user stop")))
            .isEqualTo("user stop");
        assertThat(ToolErrorFormatter.formatError(new AbortException(null)))
            .isEqualTo("[Request interrupted by user for tool use]");
    }

    /** stderr/stdout 探测测试异常（getter 通道，等价 CC 'stderr' in error）。 */
    static final class ProcErr extends RuntimeException {
        private final String stdout;
        private final String stderr;
        ProcErr(String message, String stdout, String stderr) {
            super(message);
            this.stdout = stdout;
            this.stderr = stderr;
        }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
    }

    @Test
    @DisplayName("P-25: formatError 拼接 message+stderr+stdout (CC getErrorParts :24-41)")
    void formatErrorWithChannels() {
        ProcErr ex = new ProcErr("boom", "out-line", "err-line");
        assertThat(ToolErrorFormatter.formatError(ex)).isEqualTo("boom\nerr-line\nout-line");
    }

    @Test
    @DisplayName("P-25: formatError >10000 中段截断 (CC toolErrors.ts:15-21 逐字)")
    void formatErrorTruncation() {
        String msg = "a".repeat(12000); // >10000 触发截断
        String formatted = ToolErrorFormatter.formatError(new RuntimeException(msg));
        assertThat(formatted).startsWith("a".repeat(5000));
        assertThat(formatted).endsWith("a".repeat(5000));
        assertThat(formatted).contains("... [2000 characters truncated] ...");
        // 分隔块 = "\n\n... [2000 characters truncated] ...\n\n" 共 39 字符
        assertThat(formatted.length()).isEqualTo(5000 + 39 + 5000);
    }

    @Test
    @DisplayName("P-25: formatError(空 message + 空 channel) → 'Command failed with no output'")
    void formatErrorNullMessage() {
        assertThat(ToolErrorFormatter.formatError(new RuntimeException("")))
            .isEqualTo("Command failed with no output");
    }

    @Test
    @DisplayName("C15: formatValidationError 把 ValidationResult → Zod issue 列表")
    void formatValidationErrorBasic() {
        Tool.ValidationResult result = Tool.ValidationResult.fail("SCHEMA_INVALID",
            "Missing required parameter 'path' for tool 'Read'");
        List<ToolErrorFormatter.ValidationIssue> issues = ToolErrorFormatter.formatValidationError(result);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo("SCHEMA_INVALID");
        assertThat(issues.get(0).message()).contains("Missing required parameter");
        assertThat(issues.get(0).path()).isEmpty();
    }

    @Test
    @DisplayName("C15: formatValidationError(pass) 返回空列表")
    void formatValidationErrorPass() {
        List<ToolErrorFormatter.ValidationIssue> issues =
            ToolErrorFormatter.formatValidationError(Tool.ValidationResult.pass());
        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("C15: joinIssuesForLlm 拼接为 LLM 文案")
    void joinIssuesForLlm() {
        List<ToolErrorFormatter.ValidationIssue> issues = List.of(
            new ToolErrorFormatter.ValidationIssue("params.path", "invalid_type",
                "expected string, got number"),
            new ToolErrorFormatter.ValidationIssue("params.command", "missing_required",
                "field is required")
        );
        String joined = ToolErrorFormatter.joinIssuesForLlm(issues);
        assertThat(joined).startsWith("[validation failed]");
        assertThat(joined).contains("params.path: invalid_type - expected string, got number");
        assertThat(joined).contains("params.command: missing_required - field is required");
    }

    @Test
    @DisplayName("C15: ValidationIssue 空 code 抛 IAE")
    void validationIssueBlankCode() {
        assertThatThrownBy(() -> new ToolErrorFormatter.ValidationIssue("path", "", "msg"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("C15: SafeParseResult.ok=false 必须有 issues")
    void safeParseResultRequiresIssues() {
        assertThatThrownBy(() -> ToolErrorFormatter.SafeParseResult.fail(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("C15: SafeParseResult.pass 携带 value")
    void safeParseResultPass() {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode value = m.createObjectNode().put("foo", "bar");
        ToolErrorFormatter.SafeParseResult result = ToolErrorFormatter.SafeParseResult.pass(value);
        assertThat(result.ok()).isTrue();
        assertThat(result.value()).isEqualTo(value);
        assertThat(result.issues()).isEmpty();
    }

    // ─── formatValidationPath (CC toolErrors.ts:47-57 逐字) ───

    @Test
    @DisplayName("IT-4: formatValidationPath 空/null path → 空字符串")
    void formatValidationPathEmpty() {
        assertThat(ToolErrorFormatter.formatValidationPath(List.of())).isEmpty();
        assertThat(ToolErrorFormatter.formatValidationPath(null)).isEmpty();
    }

    @Test
    @DisplayName("IT-4: formatValidationPath 混合段 → todos[0].activeForm (CC :45 例)")
    void formatValidationPathMixed() {
        assertThat(ToolErrorFormatter.formatValidationPath(List.of("todos", 0, "activeForm")))
            .isEqualTo("todos[0].activeForm");
    }

    @Test
    @DisplayName("IT-4: formatValidationPath 数字段开头 → [0].x")
    void formatValidationPathLeadingNumber() {
        assertThat(ToolErrorFormatter.formatValidationPath(List.of(0, "x")))
            .isEqualTo("[0].x");
    }

    // ─── formatZodValidationError 三句式 golden（与 /tmp/zodcheck/golden.js 实测逐字比对）───

    @Test
    @DisplayName("IT-4 golden(1): 未知键 → 单数 issue 头 + unexpected 句")
    void goldenExtraKey() {
        List<ToolErrorFormatter.ZodIssue> issues = List.of(
            new ToolErrorFormatter.ZodIssue(List.of("extraKey"), "unrecognized_keys",
                "Unrecognized key: \"extraKey\"", null, null, List.of("extraKey")));

        assertThat(ToolErrorFormatter.formatZodValidationError("TodoWrite", issues))
            .isEqualTo("TodoWrite failed due to the following issue:\n"
                + "An unexpected parameter `extraKey` was provided");
    }

    @Test
    @DisplayName("IT-4 golden(2): 缺字段 → missing 句")
    void goldenMissing() {
        List<ToolErrorFormatter.ZodIssue> issues = List.of(
            new ToolErrorFormatter.ZodIssue(List.of("todos"), "missing_required",
                "The required parameter `todos` is missing", null, null, null));

        assertThat(ToolErrorFormatter.formatZodValidationError("TodoWrite", issues))
            .isEqualTo("TodoWrite failed due to the following issue:\n"
                + "The required parameter `todos` is missing");
    }

    @Test
    @DisplayName("IT-4 golden(3): 类型错 → typeMismatch 句（句子从 expected/received 结构化字段生成）")
    void goldenTypeMismatch() {
        // message 用 zod v4 原生文案, 证明句子由结构化字段生成而非 message 原样透传
        List<ToolErrorFormatter.ZodIssue> issues = List.of(
            new ToolErrorFormatter.ZodIssue(List.of("todos"), "invalid_type",
                "Invalid input: expected array, received string", "array", "string", null));

        assertThat(ToolErrorFormatter.formatZodValidationError("TodoWrite", issues))
            .isEqualTo("TodoWrite failed due to the following issue:\n"
                + "The parameter `todos` type is expected as `array` but provided as `string`");
    }

    @Test
    @DisplayName("IT-4 golden(4): missing+extra → 复数 issues 头, missing 句在 unexpected 句前")
    void goldenMissingPlusExtra() {
        List<ToolErrorFormatter.ZodIssue> issues = List.of(
            new ToolErrorFormatter.ZodIssue(List.of("todos"), "missing_required",
                "The required parameter `todos` is missing", null, null, null),
            new ToolErrorFormatter.ZodIssue(List.of("extraKey"), "unrecognized_keys",
                "Unrecognized key: \"extraKey\"", null, null, List.of("extraKey")));

        assertThat(ToolErrorFormatter.formatZodValidationError("TodoWrite", issues))
            .isEqualTo("TodoWrite failed due to the following issues:\n"
                + "The required parameter `todos` is missing\n"
                + "An unexpected parameter `extraKey` was provided");
    }

    @Test
    @DisplayName("IT-4 golden(5): 嵌套路径 → todos[0].content")
    void goldenNestedPath() {
        List<ToolErrorFormatter.ZodIssue> issues = List.of(
            new ToolErrorFormatter.ZodIssue(List.of("todos", 0, "content"), "invalid_type",
                "Invalid input: expected string, received number", "string", "number", null));

        assertThat(ToolErrorFormatter.formatZodValidationError("TodoWrite", issues))
            .isEqualTo("TodoWrite failed due to the following issue:\n"
                + "The parameter `todos[0].content` type is expected as `string` but provided as `number`");
    }

    @Test
    @DisplayName("IT-4 golden(6): 嵌套 typeMismatch + 顶层未知 → unexpected 句排在 typeMismatch 句前")
    void goldenNestedPlusTopLevelUnknown() {
        // 输入顺序故意 typeMismatch 在前 — 类别顺序 fixed missing→unexpected→typeMismatch
        List<ToolErrorFormatter.ZodIssue> issues = List.of(
            new ToolErrorFormatter.ZodIssue(List.of("todos", 0, "content"), "invalid_type",
                "Invalid input: expected string, received number", "string", "number", null),
            new ToolErrorFormatter.ZodIssue(List.of("junk"), "unrecognized_keys",
                "Unrecognized key: \"junk\"", null, null, List.of("junk")));

        assertThat(ToolErrorFormatter.formatZodValidationError("TodoWrite", issues))
            .isEqualTo("TodoWrite failed due to the following issues:\n"
                + "An unexpected parameter `junk` was provided\n"
                + "The parameter `todos[0].content` type is expected as `string` but provided as `number`");
    }

    @Test
    @DisplayName("IT-4 golden(7): 单 issue 多 keys → 每 key 一句（CC flatMap(err.keys)）")
    void goldenMultipleKeysOneIssue() {
        // zod v4 实测: 多未知键 → 单 issue {code:'unrecognized_keys', keys:['a','b']}
        List<ToolErrorFormatter.ZodIssue> issues = List.of(
            new ToolErrorFormatter.ZodIssue(List.of(), "unrecognized_keys",
                "Unrecognized key: \"a\"", null, null, List.of("a", "b")));

        assertThat(ToolErrorFormatter.formatZodValidationError("TodoWrite", issues))
            .isEqualTo("TodoWrite failed due to the following issues:\n"
                + "An unexpected parameter `a` was provided\n"
                + "An unexpected parameter `b` was provided");
    }

    @Test
    @DisplayName("IT-4: typeMismatch received 缺省 → 从 message 正则 /received (\\w+)/ 解析, 兜底 unknown")
    void typeSentenceReceivedFallback() {
        List<ToolErrorFormatter.ZodIssue> fromMessage = List.of(
            new ToolErrorFormatter.ZodIssue(List.of("items"), "invalid_type",
                "Invalid input: expected array, received number", "array", null, null));
        assertThat(ToolErrorFormatter.formatZodValidationError("Bash", fromMessage))
            .isEqualTo("Bash failed due to the following issue:\n"
                + "The parameter `items` type is expected as `array` but provided as `number`");

        List<ToolErrorFormatter.ZodIssue> noMatch = List.of(
            new ToolErrorFormatter.ZodIssue(List.of("x"), "invalid_type",
                "Invalid input", "string", null, null));
        assertThat(ToolErrorFormatter.formatZodValidationError("Bash", noMatch))
            .isEqualTo("Bash failed due to the following issue:\n"
                + "The parameter `x` type is expected as `string` but provided as `unknown`");
    }

    @Test
    @DisplayName("IT-4: 未分类 code → toOneLine() 追加（空 path 保留旧格式 code - message）")
    void formatZodValidationErrorUnclassified() {
        List<ToolErrorFormatter.ZodIssue> issues = List.of(
            new ToolErrorFormatter.ZodIssue(List.of(), "SCHEMA_INVALID", "Tool is null",
                null, null, null));

        assertThat(ToolErrorFormatter.formatZodValidationError("Bash", issues))
            .isEqualTo("Bash failed due to the following issue:\nSCHEMA_INVALID - Tool is null");
    }

    @Test
    @DisplayName("IT-4: 空 issues → 空字符串; 未分类 code → header + toOneLine 追加")
    void formatZodValidationErrorEmptyAndFallback() {
        assertThat(ToolErrorFormatter.formatZodValidationError("Bash", List.of())).isEmpty();
        assertThat(ToolErrorFormatter.formatZodValidationError(null, List.of())).isEmpty();
        // 未分类 code 无 zod 对应类别 (CC 全 code 归三句式); Java 安全网 → toOneLine 追加,
        // 计入 header 单复数. (CC :100 error.message 兜底在 Java 仅当 parts 为空时可达,
        // safeParseSchema fail 的 issues 恒非空 → 实际不可达)
        List<ToolErrorFormatter.ZodIssue> unknownCode = List.of(
            new ToolErrorFormatter.ZodIssue(List.of(), "SOME_NEW_CODE", "line one", null, null, null),
            new ToolErrorFormatter.ZodIssue(List.of(), "SOME_NEW_CODE", "line two", null, null, null));
        assertThat(ToolErrorFormatter.formatZodValidationError("Bash", unknownCode))
            .isEqualTo("Bash failed due to the following issues:\n"
                + "SOME_NEW_CODE - line one\nSOME_NEW_CODE - line two");
    }

    @Test
    @DisplayName("IT-4: inputValidationErrorBlock 逐字 CC toolExecution.ts:670")
    void inputValidationErrorBlock() {
        assertThat(ToolErrorFormatter.inputValidationErrorBlock(
            "TodoWrite failed due to the following issue:\nThe required parameter `todos` is missing"))
            .isEqualTo("<tool_use_error>InputValidationError: TodoWrite failed due to the following issue:\n"
                + "The required parameter `todos` is missing</tool_use_error>");
    }

    @Test
    @DisplayName("IT-4: ZodIssue compact ctor null-safe (path→[], message→'', code 必填)")
    void zodIssueCompactCtor() {
        ToolErrorFormatter.ZodIssue issue = new ToolErrorFormatter.ZodIssue(
            null, "invalid_type", null, null, null, null);
        assertThat(issue.path()).isEmpty();
        assertThat(issue.message()).isEmpty();
        assertThat(issue.keys()).isNull();
        assertThat(issue.toOneLine()).isEqualTo("invalid_type");

        assertThatThrownBy(() -> new ToolErrorFormatter.ZodIssue(
            List.of(), "", "msg", null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}