package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>CC 端把异常分类为 telemetry-safe 字符串 (classifyToolError), 用户可读 (formatError),
 * schema issue (formatValidationError), 以及 schema Zod 三句式
 * (formatZodValidationError, toolErrors.ts:66-132 逐字). 本类把这 4 种格式集中到一处,
 * 避免在 StreamingToolExecutor / LlmAgentLoop 等多处拼接错误消息时丢失隐私或语义.
 *
 * <h2>四类输出</h2>
 * <ul>
 *   <li>{@link #classifyToolError(Throwable)} — 把异常归类为 telemetry-safe 短字符串,
 *       逐字对齐 CC toolExecution.ts:150-171 (类名 slice(0,60) / 'Error' / 'UnknownError'),
 *       <b>不含路径/源码/用户输入</b>.</li>
 *   <li>{@link #formatError(Throwable)} — 用户可读错误消息, 逐字对齐 CC toolErrors.ts:5-22
 *       (message+stderr+stdout parts join / 10000 中段截断 / AbortError 特例 /
 *       'Command failed with no output'). 用于 ToolResult.error content (LLM 看到) 和 UI 提示.</li>
 *   <li>{@link #formatValidationError(Tool.ValidationResult)} — schema / semantic
 *       验证失败的 Zod 风格 issue 列表, 给 LLM 自纠. 也可用于 telemetry (errorCode 字段).</li>
 *   <li>{@link #formatZodValidationError(String, List)} — schema 校验失败的 CC 三句式
 *       (missing / unexpected / typeMismatch), 逐字对齐 {@code toolErrors.ts:66-132};
 *       供 StreamingToolExecutor schema 失败路径注入 LLM.</li>
 * </ul>
 *
 * <h2>隐私与 telemetry 边界 (CLAUDE.md 规则 12 · Fail loud)</h2>
 * <p>{@link #classifyToolError(Throwable)} 返回值<b>严禁</b>包含以下任何一项:
 * <ul>
 *   <li>文件绝对路径 (e.g. {@code /home/user/secrets.txt})</li>
 *   <li>用户输入的 content (LLM tool_call 内容)</li>
 *   <li>异常堆栈内容 (可能含 token / 凭据)</li>
 *   <li>原始 errorMessage 中超过 80 字符的子串 (避免泄露内部信息)</li>
 * </ul>
 *
 * <p>{@link #formatError(Throwable)} 与 {@link #formatValidationError} 给 LLM / 用户看,
 * <b>可以</b>包含原始 message (LLM 自纠需要). 但仍建议调用方按需截断 (e.g. 4000 字符上限).
 *
 * <h2>线程安全</h2>
 * <p>所有方法纯函数, 线程安全.
 *
 * @see StreamingToolExecutor
 * @see LlmAgentLoop#applyPermissionFilter
 */
public final class ToolErrorFormatter {

    private static final Logger log = LoggerFactory.getLogger(ToolErrorFormatter.class);

    /** AbortException 简化类名引用, 避免直接 import 内部类. */
    private static final String ABORT_EX_CLASS =
        "com.nexusai.application.agent.permission.hook.AbortException";

    /**
     * Zod 风格 schema issue · 对齐 CC {@code toolExecution.ts:670} ZodIssue 字段.
     *
     * @param path    字段路径 (e.g. {@code "params.path"}), 顶层错误时为 {@code ""}.
     * @param code    错误码 (e.g. {@code "invalid_type"}, {@code "missing_required"}).
     * @param message 人类可读消息.
     */
    public record ValidationIssue(String path, String code, String message) {
        public ValidationIssue {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("ValidationIssue.code is required");
            }
            if (message == null) message = "";
            if (path == null) path = "";
        }

        /** 单行 issue 文本 (e.g. {@code "params.path: invalid_type - expected string"}). */
        public String toOneLine() {
            return (path.isEmpty() ? "" : path + ": ") + code
                + (message.isEmpty() ? "" : " - " + message);
        }
    }

    /**
     * Zod v4 风格 issue · 对齐 CC {@code toolErrors.ts:66-132} formatZodValidationError 输入
     * 与 {@code toolExecution.ts:670} ZodIssue 字段.
     *
     * <p>与 {@link ValidationIssue} (语义路径仍用, path 为 String) 的区别:
     * path 是结构化 {@code List<Object>} (字符串段 + 数字段), 由
     * {@link #formatValidationPath(List)} 渲染为 CC 逐字路径 (e.g. {@code todos[0].activeForm});
     * expected/received/keys 保留 zod 结构化字段, 供三句式直接生成句子.
     *
     * @param path      字段路径段列表 (e.g. {@code List.of("todos", 0, "content")}); 顶层为 {@code List.of()}
     * @param code      错误码 (e.g. {@code "invalid_type"}, {@code "unrecognized_keys"}, {@code "missing_required"})
     * @param message   人类可读消息 (三句式逐字句子, safeParseSchema 创作)
     * @param expected  期望类型 (invalid_type 时非空, 对齐 zod {@code err.expected})
     * @param received  实际类型 (invalid_type 时非空; 可空时 formatter 从 message 正则解析)
     * @param keys      unrecognized_keys 的未知键列表 (CC {@code err.keys}; 可空)
     */
    public record ZodIssue(List<Object> path, String code, String message,
            String expected, String received, List<String> keys) {
        public ZodIssue {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("ZodIssue.code is required");
            }
            if (message == null) message = "";
            path = path == null ? List.of() : List.copyOf(path);
            keys = keys == null ? null : List.copyOf(keys);
        }

        /** 单行 issue 文本 (e.g. {@code "todos[0].content: invalid_type - expected string, got number"}). */
        public String toOneLine() {
            String p = formatValidationPath(path);
            return (p.isEmpty() ? "" : p + ": ") + code
                + (message.isEmpty() ? "" : " - " + message);
        }
    }

    /**
     * safeParse 风格返回类型 · 对齐 CC {@code toolOrchestration.ts:97-107} safeParse.
     *
     * <p>区别于 {@link Tool.ValidationResult} (boolean ok / errorCode / message 单值),
     * SafeParseResult 携带 typed value + issues 列表, 适用于 typed/transformed schema
     * 校验场景 (本期不强制 LLM 端消费 typed value, 但保留向后扩展).
     *
     * @param ok     是否通过
     * @param value  typed/transformed value (校验通过时填入 input, 失败时为 null)
     * @param issues Zod 风格 issue 列表
     */
    public record SafeParseResult(boolean ok, JsonNode value, List<ZodIssue> issues) {
        public SafeParseResult {
            if (!ok && (issues == null || issues.isEmpty())) {
                throw new IllegalArgumentException(
                    "SafeParseResult.issues must be non-empty when !ok");
            }
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public static SafeParseResult pass(JsonNode value) {
            return new SafeParseResult(true, value, List.of());
        }

        public static SafeParseResult fail(List<ZodIssue> issues) {
            return new SafeParseResult(false, null, issues);
        }
    }

    // ─── classifyToolError ───

    /**
     * 异常分类为 telemetry-safe 短字符串 · 逐字对齐 CC {@code toolExecution.ts:150-171 classifyToolError}
     * （read 自验）。
     *
     * <p>CC 实现：
     * <pre>
     * 1. instanceof TelemetrySafeError → telemetryMessage.slice(0, 200)   // Java N/A（无等价类型）
     * 2. instanceof Error:
     *    a. Node fs errno code (ENOENT 等) → `Error:${code}`              // Java N/A（无 errno 字段）
     *    b. name 稳定 && name !== 'Error' && name.length &gt; 3 → name.slice(0, 60)
     *    c. 其余 → 'Error'
     * 3. 非 Error（含 null）→ 'UnknownError'
     * </pre>
     *
     * <p><b>Java 映射</b>：TS {@code error.name}（构造器设置、minify 后仍稳定）等价位 =
     * {@code throwable.getClass().getSimpleName()}（类名天然稳定，P-25 改造前 8 类粗粒度桶
     * abort/validation/permission/io 等已删除——Java 独有偏离，CC 全仓无对应，见
     * implementation-2026-08-15 决策 P-25）。errno / TelemetrySafeError 分支 N/A 标注：
     * Java 异常体系无 Node fs errno code 字段、无 TelemetrySafeError 等价类型
     * （toolExecution.ts:151-155 注释自验）。
     *
     * <p><b>关键不变式</b> (CLAUDE.md 规则 12 · Fail loud): 返回值<b>永不</b>含路径 / 源码 /
     * 用户输入（仅稳定类名，最多 60 字符）。
     *
     * <p><b>ER-IMP-15 / DC-14</b>: 旧 {@code sanitizeMessage} / {@code formatForTelemetry} /
     * {@code stripPaths} 三个 main 零调用死方法已删除（含 ABS_PATH 正则）。
     */
    public static String classifyToolError(Throwable th) {
        if (th == null) {
            return "UnknownError";
        }
        String name = th.getClass().getSimpleName();
        if (name != null && !name.equals("Error") && name.length() > 3) {
            return name.length() <= 60 ? name : name.substring(0, 60);
        }
        return "Error";
    }

    // ─── formatError ───

    /** CC toolErrors.ts:15-21 截断阈值与半长（MAX=10000, HALF=5000）。 */
    private static final int MAX_FORMAT_ERROR_LENGTH = 10000;
    private static final int FORMAT_ERROR_HALF_LENGTH = 5000;

    /**
     * 格式化为用户可读错误 · 逐字对齐 CC {@code toolErrors.ts:5-22 formatError}（TR-A2 S-10 /
     * TR-A3 △-13 语义偏移修复）。
     *
     * <p>CC 真源（toolErrors.ts:5-22）:
     * <pre>{@code
     * if (error instanceof AbortError) return error.message || INTERRUPT_MESSAGE_FOR_TOOL_USE
     * if (!(error instanceof Error)) return String(error)
     * const parts = getErrorParts(error)
     * const fullMessage = parts.filter(Boolean).join('\n').trim() || 'Command failed with no output'
     * if (fullMessage.length <= 10000) return fullMessage
     * const start = fullMessage.slice(0, 5000); const end = fullMessage.slice(-5000)
     * return `${start}\n\n... [${fullMessage.length - 10000} characters truncated] ...\n\n${end}`
     * }</pre>
     *
     * <p><b>[IMP-C4 REQ-G3-2-3] 补 3 语义</b>:
     * <ol>
     *   <li><b>AbortError 特例</b>: {@link com.nexusai.application.agent.permission.hook.AbortException}
     *       → message 非空返回 message，否则 {@code INTERRUPT_MESSAGE_FOR_TOOL_USE}（CC :6-8）。</li>
     *   <li><b>ShellError 展开</b>: {@link ShellError} → {@code "Exit code N"} + interrupted 标记
     *       + stderr + stdout 分区（CC :24-32 getErrorParts）；其余异常 message 后按 duck-typing
     *       附加 getStderr()/getStdout() 非空值。</li>
     *   <li><b>10000 截断</b>: 超长消息保留头尾各 5000 字符，中插
     *       {@code "… [N characters truncated] …"}（CC :15-21）。</li>
     * </ol>
     *
     * <p>旧输出格式 {@code "<Class>: <msg>"} 已废弃（Bash 失败 LLM 可见文本可读性，联动 B 域）。
     *
     * @param th 工具执行抛出的异常（null → "null"，CC {@code String(error)} 语义）
     * @return CC 对齐的用户可读错误文本
     */
    public static String formatError(Throwable th) {
        if (th == null) {
            // CC toolErrors.ts:9-11: `if (!(error instanceof Error)) return String(error)`
            //   → String(null) = "null"（Java 防御兜底, 与 CC 语义逐字一致）
            return "null";
        }
        // CC toolErrors.ts:6-8 AbortError 特例
        if (isAbortError(th)) {
            String msg = th.getMessage();
            return (msg == null || msg.isBlank()) ? INTERRUPT_MESSAGE_FOR_TOOL_USE : msg;
        }
        // CC toolErrors.ts:13-14 非 Error（JS unknown）→ Java 中 Throwable 恒为错误对象；
        //   空 message 的裸 Throwable 走 String 兜底（joinErrorParts 过滤空段）
        String fullMessage = joinErrorParts(getErrorParts(th));
        if (fullMessage.isEmpty()) {
            return "Command failed with no output";
        }
        if (fullMessage.length() <= MAX_FORMAT_ERROR_LENGTH) {
            return fullMessage;
        }
        int halfLength = FORMAT_ERROR_HALF_LENGTH;
        String start = fullMessage.substring(0, halfLength);
        String end = fullMessage.substring(fullMessage.length() - halfLength);
        return start + "\n\n... [" + (fullMessage.length() - MAX_FORMAT_ERROR_LENGTH)
            + " characters truncated] ...\n\n" + end;
    }

    /**
     * CC {@code toolErrors.ts:24-41 getErrorParts} · 展开错误分区文本。
     *
     * <p>ShellError → {@code [Exit code N, interrupted?, stderr, stdout]}（:26-32）；
     * 其余 → {@code [message]} + duck-typing 附加 stderr/stdout（:34-40，TS {@code 'stderr' in error}）。
     *
     * @param th 异常（非 null）
     * @return 分区文本列表（含空串，join 前由 {@link #joinErrorParts} 过滤）
     */
    private static java.util.List<String> getErrorParts(Throwable th) {
        if (th instanceof ShellError se) {
            return java.util.List.of(
                "Exit code " + se.code(),
                se.interrupted() ? INTERRUPT_MESSAGE_FOR_TOOL_USE : "",
                se.stderr(),
                se.stdout());
        }
        java.util.List<String> parts = new java.util.ArrayList<>(3);
        parts.add(th.getMessage());
        // CC :34-37 'stderr' in error && typeof stderr === 'string' → Java getter 反射 duck-typing
        String stderr = readStringGetter(th, "getStderr");
        if (stderr != null) {
            parts.add(stderr);
        }
        // CC :38-40 'stdout' in error && typeof stdout === 'string'
        String stdout = readStringGetter(th, "getStdout");
        if (stdout != null) {
            parts.add(stdout);
        }
        return parts;
    }

    /**
     * CC {@code parts.filter(Boolean).join('\n').trim()} · 过滤空串/空白段后以换行连接并 trim。
     *
     * <p>TS {@code filter(Boolean)} 只过滤 falsy（''）；空白串（如 {@code ' '}）truthy 保留，
     * 故 Java 用 {@code !isEmpty()} 而非 {@code !isBlank()}。
     *
     * @param parts 分区文本列表
     * @return join+trim 结果（全空 → 空串）
     */
    private static String joinErrorParts(java.util.List<String> parts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append('\n');
            }
            sb.append(p);
            first = false;
        }
        return sb.toString().trim();
    }

    /** CC original: INTERRUPT_MESSAGE_FOR_TOOL_USE（LlmAgentLoop 单一真理源，messages.ts:208-209）。 */
    private static final String INTERRUPT_MESSAGE_FOR_TOOL_USE =
        com.nexusai.application.agent.LlmAgentLoop.INTERRUPT_MESSAGE_FOR_TOOL_USE;

    /** AbortException 判定（与 {@link #classifyToolError} 同源 ABORT_EX_CLASS）。 */
    private static boolean isAbortError(Throwable th) {
        return ABORT_EX_CLASS.equals(th.getClass().getName());
    }

    /** duck-typing: 反射读取 String getter（CC {@code 'stderr' in error} / {@code 'stdout' in error}）。 */
    private static String readStringGetter(Throwable th, String getterName) {
        try {
            java.lang.reflect.Method m = th.getClass().getMethod(getterName);
            Object v = m.invoke(th);
            return (v instanceof String s) ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ─── formatValidationError ───

    /**
     * 格式化验证失败结果为 Zod 风格 issue 列表 · 对齐 CC {@code toolExecution.ts:670}.
     *
     * <p>输入: 工具的 {@link Tool.ValidationResult} (含 errorCode + message).
     * 输出: 单元素 issue 列表 (本期未引入 typed value, 只展开单 issue).
     *
     * <p>调用方 (LlmAgentLoop) 把 issue 列表拼接到 ToolResult.error content 注入 LLM.
     */
    public static List<ValidationIssue> formatValidationError(Tool.ValidationResult result) {
        if (result == null || result.ok()) {
            return List.of();
        }
        List<ValidationIssue> issues = new ArrayList<>(1);
        String code = result.errorCode() != null ? result.errorCode() : "SCHEMA_INVALID";
        String msg = result.message() != null ? result.message() : "validation failed";
        issues.add(new ValidationIssue("", code, msg));
        return issues;
    }

    /**
     * 把 issue 列表拼接为 LLM 可见的单行错误 · 用于 ToolResult.error content.
     *
     * <p>输出格式 (CC toolExecution.ts:670 ZodIssue 风格):
     * <pre>
     *   [validation failed]
     *   - params.path: invalid_type - expected string, got number
     *   - params.path: missing_required - field is required
     * </pre>
     */
    public static String joinIssuesForLlm(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) return "validation failed";
        StringBuilder sb = new StringBuilder("[validation failed]");
        for (ValidationIssue issue : issues) {
            sb.append("\n- ").append(issue.toOneLine());
        }
        return sb.toString();
    }

    // ─── formatZodValidationError (CC toolErrors.ts:47-132 逐字) ───

    private static final Pattern RECEIVED_PATTERN = Pattern.compile("received (\\w+)");

    /**
     * 格式化 Zod validation path 为可读字符串 · 逐字对齐 CC
     * {@code toolErrors.ts:47-57 formatValidationPath}: 空 path→{@code ""}; 数字段→
     * {@code [n]} 追加; 字符串段 index==0 裸拼、其余 {@code .name}.
     *
     * <p>例: {@code ['todos', 0, 'activeForm']} → {@code "todos[0].activeForm"};
     * {@code [0, 'x']} → {@code "[0].x"}.
     *
     * @param path 路径段列表 (数字段 = 数组下标, 字符串段 = 字段名)
     * @return CC 逐字路径文本
     */
    public static String formatValidationPath(List<Object> path) {
        if (path == null || path.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            Object segment = path.get(i);
            String segStr = String.valueOf(segment);
            if (segment instanceof Number) {
                sb.append('[').append(segStr).append(']');
            } else if (i == 0) {
                sb.append(segStr);
            } else {
                sb.append('.').append(segStr);
            }
        }
        return sb.toString();
    }

    /**
     * 格式化 Zod issue 列表为 LLM 可读错误 · 逐字对齐 CC
     * {@code toolErrors.ts:66-132 formatZodValidationError} 三句式:
     * <ol>
     *   <li><b>missing</b> — {@code "The required parameter `p` is missing"} (:107)</li>
     *   <li><b>unexpected</b> — {@code "An unexpected parameter `k` was provided"} (:114),
     *       unrecognized_keys 每 key 一句 (CC :78-80 flatMap(err.keys))</li>
     *   <li><b>typeMismatch</b> — {@code "The parameter `p` type is expected as `e` but
     *       provided as `r`"} (:122), expected/received 取结构化字段, received 缺省从
     *       message 正则 {@code /received (\w+)/} 解析、兜底 {@code "unknown"} (:90-91)</li>
     * </ol>
     *
     * <p>类别顺序固定 missing → unexpected → typeMismatch (:105-125), 与 issue 输入顺序无关;
     * header = {@code "{toolName} failed due to the following {issues|issue}:\n"} +
     * join("\n") (:128). 无分类 parts 时按 CC :100 fallback (error.message) 输出
     * issue messages join("\n"); issues 为空返回 {@code ""}.
     *
     * <p>Java 分类是 code 驱动的 (missing_required / unrecognized_keys / invalid_type),
     * 不依赖 CC 的 message 文本 "received undefined" 判定 (zod 升级改 message 不影响
     * Java 输出; Java 侧由 safeParseSchema 直接创作逐字句子).
     *
     * @param toolName 工具名 (CC tool.name, 如 "TodoWrite"); null 按 "" 处理
     * @param issues   Zod 风格 issue 列表
     * @return 逐字对齐 CC toolErrors.ts:128 的格式化错误文本
     */
    public static String formatZodValidationError(String toolName, List<ZodIssue> issues) {
        List<String> missing = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        List<String> typeMismatch = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();
        if (issues != null) {
            for (ZodIssue issue : issues) {
                switch (issue.code()) {
                    case "missing_required" -> missing.add(issue.message());
                    case "unrecognized_keys" -> {
                        List<String> keys = issue.keys();
                        if (keys == null || keys.isEmpty()) {
                            unexpected.add(issue.message());
                        } else {
                            for (String key : keys) {
                                unexpected.add("An unexpected parameter `" + key
                                    + "` was provided");
                            }
                        }
                    }
                    case "invalid_type" -> typeMismatch.add(zodTypeSentence(issue));
                    default -> unclassified.add(issue.toOneLine());
                }
            }
        }
        List<String> parts = new ArrayList<>(
            missing.size() + unexpected.size() + typeMismatch.size() + unclassified.size());
        parts.addAll(missing);
        parts.addAll(unexpected);
        parts.addAll(typeMismatch);
        parts.addAll(unclassified);
        if (parts.isEmpty()) {
            // CC :100 fallback → error.message; Java 等价 = issue messages join("\n")
            if (issues == null || issues.isEmpty()) return "";
            List<String> msgs = new ArrayList<>();
            for (ZodIssue issue : issues) {
                if (!issue.message().isEmpty()) msgs.add(issue.message());
            }
            return String.join("\n", msgs);
        }
        String name = toolName == null ? "" : toolName;
        return name + " failed due to the following " + (parts.size() > 1 ? "issues" : "issue")
            + ":\n" + String.join("\n", parts);
    }

    /**
     * CC {@code toolExecution.ts:670} tool_result content 逐字块:
     * {@code <tool_use_error>InputValidationError: ${errorContent}</tool_use_error>}.
     *
     * @param errorContent formatZodValidationError 输出 (+ schemaHint)
     * @return CC :670 逐字 tool_result content
     */
    public static String inputValidationErrorBlock(String errorContent) {
        return "<tool_use_error>InputValidationError: " + errorContent + "</tool_use_error>";
    }

    /** CC :119-125 typeMismatch 句子 · expected/received 结构化字段优先, received 正则兜底. */
    private static String zodTypeSentence(ZodIssue issue) {
        String expected = issue.expected();
        if (expected == null || expected.isEmpty()) expected = "unknown";
        String received = issue.received();
        if (received == null || received.isEmpty()) {
            Matcher m = RECEIVED_PATTERN.matcher(issue.message());
            received = m.find() ? m.group(1) : "unknown";
        }
        return "The parameter `" + formatValidationPath(issue.path()) + "` type is expected as `"
            + expected + "` but provided as `" + received + "`";
    }

    private ToolErrorFormatter() {
        // utility class
    }
}