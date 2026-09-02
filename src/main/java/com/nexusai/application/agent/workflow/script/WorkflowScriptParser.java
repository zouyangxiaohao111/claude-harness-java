package com.nexusai.application.agent.workflow.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流脚本编译期解析器（对齐 CC Open-ClaudeCode/packages/workflow-engine/src/engine/script.ts:28-229
 * parseScript / extractMeta / assertScriptBody / validateMeta）。
 *
 * <p>CC 无「WorkflowDefinition」类——工作流定义 = 一段 JS/TS 函数体字符串，运行时被 parseScript 编译为
 * {@link ParsedScript}{meta, execute}。本类 = extractMeta + assertScriptBody 的纯 Java 确定实现
 * （零 JS 依赖），并在 executor 未注入时<b>默认编译受限解释器</b> {@link RestrictedScriptExecutor}：</p>
 *
 * <pre>{@code
 * parseScript(source)
 *   ├─ extractMeta(source)        # META_RE 正则 + 字符串感知花括号配平 + 纯字面量求值 + validateMeta
 *   ├─ assertScriptBody(body)     # import / import(...) / 额外 export → 精确引导报错
 *   └─ 编译 executor              # 未注入 → RestrictedScriptExecutor(body)（受限 DSL 子集，G-2 生产接线）
 *   └─ return ParsedScript{meta, body, executor}   # execute 委派 executor（受限解释器 / 注入 fake）
 * }</pre>
 *
 * <p>对齐红线（script-doc §8 + P0-plan §3 + DocReflect G 项修正）：</p>
 * <ol>
 *   <li>META_RE 无 flags，matcher.find() 取首个命中；\s 含换行故 `export\nconst\nmeta = {` 也能匹配（script.ts:28）。</li>
 *   <li>extractMeta 花括号配平是字符串感知状态机：三种引号内 {} 不计数、\x 转义跳 2 字符（script.ts:47-74）。</li>
 *   <li>meta 求值是确定性校验：无参 Function 语义 = 引用任何标识符 → 拒绝（本实现用纯字面量子集解析器等价拒绝非纯字面量）。</li>
 *   <li>assertScriptBody 三条规则先于编译执行，错误消息保留 CC 原句（含 8 参数引导语）。</li>
 *   <li>受限 DSL 子集外的构造（function 声明 / class / 正则 / 解构 / C 风格 for 等）→ 编译期
 *       {@link ScriptError}（fail loud，见 {@link RestrictedScriptExecutor} 类 Javadoc 部分对齐差距）。</li>
 *   <li>运行期错误（NonDeterministic / WorkflowError / Aborted）不归 parser，是引擎 WorkflowRunEngine 职责。</li>
 * </ol>
 *
 * <p><b>包名注记</b>：本类落包 com.nexusai.application.agent.workflow.script（script-doc §7 候选包）。
 * DocReflect verdict §2.5 已标 DEC-P0-01 包名口径漂移，合并时以 DEC-P0-01 拍板结果统一。</p>
 */
public final class WorkflowScriptParser {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScriptParser.class);

    /** CC script.ts:28 META_RE —— 无 flags（非 m 非 g），matcher.find() 取首个命中。 */
    private static final Pattern META_RE = Pattern.compile("export\\s+const\\s+meta\\s*=\\s*");

    /** CC script.ts:166 规则 1 —— 静态 import（锚定行首；UNICODE 下 \s 对齐 JS）。 */
    private static final Pattern IMPORT_STATIC_RE =
            Pattern.compile("^\\s*import\\b", Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS);

    /** CC script.ts:175 规则 2 —— 动态 import(...)（不锚定行首；\b 防字符串内 "import" 误报）。 */
    private static final Pattern IMPORT_DYNAMIC_RE =
            Pattern.compile("\\bimport\\s*\\(", Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS);

    /** CC script.ts:181 规则 3 —— meta 剥离后 body 上的额外 export（命中即第二个 export）。 */
    private static final Pattern EXTRA_EXPORT_RE =
            Pattern.compile("^\\s*export\\b", Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS);

    /** CC new AsyncFunction 的 10 个形参（script.ts:194-206）：8 业务注入 + 2 沙箱替身，位置序即绑定序。 */
    private static final List<String> ASYNC_FUNCTION_PARAMS = List.of(
            "agent", "parallel", "pipeline", "phase", "log", "workflow",
            "args", "budget", "Date", "Math");

    public WorkflowScriptParser() {
        // 无状态工具，供 Spring/DI 或直接 new 使用
    }

    /**
     * 编译期解析 + 编译受限解释执行器（对齐 CC parseScript，script.ts:189-229）。
     *
     * <p>extractMeta + assertScriptBody + 受限 DSL 编译（{@link RestrictedScriptExecutor}）；
     * 子集外构造 → {@link ScriptError} 在编译期抛出（fail loud）。</p>
     *
     * @param source 脚本字符串
     * @return ParsedScript{meta, body, executor=RestrictedScriptExecutor}（可执行）
     * @throws ScriptError 编译期失败（meta 非法 / import / 额外 export / 非纯字面量 / 受限 DSL 子集外构造）
     * @throws IllegalArgumentException source 为 null
     */
    public ParsedScript parse(String source) {
        return parse(source, null);
    }

    /**
     * 编译期解析 + 注入执行器。
     *
     * <p>executor 为 null 时默认编译 {@link RestrictedScriptExecutor}（生产 G-2 接线：生产
     * WorkflowServiceImpl → new WorkflowRunEngine() 无注入 executor → 走此默认编译，替代 NOT_WIRED）。
     * 测试可注入 fake executor 隔离解释器。</p>
     *
     * @param source   脚本字符串
     * @param executor 外部执行器（测试 fake / 引擎注入）；null → 编译 {@link RestrictedScriptExecutor}
     * @return ParsedScript{meta, body, executor}
     * @throws ScriptError 编译期失败
     * @throws IllegalArgumentException source 为 null
     */
    public ParsedScript parse(String source, WorkflowScriptExecutor executor) {
        if (source == null) {
            log.error("WorkflowScriptParser.parse 拒绝：source 为 null（CC parseScript 入参必为字符串）");
            throw new IllegalArgumentException("WorkflowScriptParser source must not be null");
        }
        log.info("WorkflowScriptParser.parse 入口：source 长度={}，注入 executor={}，AsyncFunction 形参序={}（CC script.ts:194-206）",
                source.length(),
                executor != null ? "已注入" : "默认 RestrictedScriptExecutor（受限 DSL 解释器）",
                ASYNC_FUNCTION_PARAMS);
        ExtractResult extracted = extractMeta(source);
        assertScriptBody(extracted.body());
        WorkflowScriptExecutor exec;
        if (executor != null) {
            exec = executor;
        } else {
            // G-2 生产接线：无外部 executor → 编译受限解释器（Java 无 JS 引擎，受限模型部分对齐，见类 Javadoc）
            exec = new RestrictedScriptExecutor(extracted.body());
        }
        log.info("WorkflowScriptParser.parse 通过：meta={}，body 长度={}，executor={}（extractMeta + assertScriptBody + 编译完成）",
                extracted.meta() != null ? extracted.meta().name() : null,
                extracted.body().length(),
                exec instanceof RestrictedScriptExecutor ? "RestrictedScriptExecutor" : "注入的 executor");
        return new ParsedScript(extracted.meta(), extracted.body(), exec);
    }

    /**
     * 提取 meta 纯字面量并剥离（对齐 CC extractMeta，script.ts:34-93）。
     *
     * @param source 脚本字符串
     * @return 提取结果（meta 可为 null，body 为剥离后的函数体）
     * @throws ScriptError meta 非对象字面量 / 括号未闭合 / 非纯字面量
     */
    static ExtractResult extractMeta(String source) {
        Matcher m = META_RE.matcher(source);
        if (!m.find()) {
            if (log.isDebugEnabled()) {
                log.debug("extractMeta：META_RE 未命中，meta=null，body 原样返回（CC script.ts:39）");
            }
            return new ExtractResult(null, source);
        }
        int i = m.end();
        while (i < source.length() && isJsWhitespace(source.charAt(i))) {
            i++;
        }
        if (i >= source.length() || source.charAt(i) != '{') {
            log.warn("extractMeta 拒绝：'=' 后不是对象字面量（CC script.ts:43-45 'meta must be an object literal'）");
            throw new ScriptError("meta must be an object literal `{ ... }`");
        }

        // 字符串感知花括号配平（script.ts:47-74）：引号内 {} 不计数、\x 转义跳 2 字符
        int depth = 0;
        final int start = i;
        Character inStr = null;
        for (; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (inStr != null) {
                if (ch == '\\') {
                    i++;
                    continue;
                }
                if (ch == inStr) {
                    inStr = null;
                }
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                inStr = ch;
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    i++;
                    break;
                }
            }
        }
        if (depth != 0) {
            log.warn("extractMeta 拒绝：meta 字面量括号未闭合（CC script.ts:74 'meta literal braces are not closed'）");
            throw new ScriptError("meta literal braces are not closed");
        }

        String literal = source.substring(start, i);
        WorkflowMeta meta;
        try {
            Object evaluated = new LiteralParser(literal).parse();
            meta = validateMeta(evaluated);
        } catch (ScriptError e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("extractMeta 拒绝：非纯字面量（{}），原始错误={}（CC script.ts:81-85 'meta must be a plain literal'）",
                    preview(literal), e.getMessage());
            throw new ScriptError(
                    "meta must be a plain literal (no variable/function calls/interpolation): " + e.getMessage());
        }

        // 剥离 meta 整句（含尾部可选分号 + 其后空行折叠为单换行，script.ts:88-92）
        String body = source.substring(0, m.start())
                + source.substring(i).replaceFirst("^[ \\t]*;[ \\t]*\\n", "\n");
        if (log.isDebugEnabled()) {
            log.debug("extractMeta 命中：meta={}，剥离后 body 长度={}（不含 export const meta 语句）",
                    meta.name(), body.length());
        }
        return new ExtractResult(meta, body);
    }

    /**
     * 校验 meta 形状（对齐 CC validateMeta，script.ts:95-104）：只强校验 name/description 为 string，其余透传。
     */
    private static WorkflowMeta validateMeta(Object v) {
        if (!(v instanceof Map)) {
            throw new ScriptError("meta must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) v;
        if (!(o.get("name") instanceof String) || !(o.get("description") instanceof String)) {
            throw new ScriptError("meta must include string name and description");
        }
        return new WorkflowMeta(
                (String) o.get("name"),
                (String) o.get("description"),
                o.get("whenToUse") instanceof String ? (String) o.get("whenToUse") : null,
                extractPhases(o.get("phases")));
    }

    /** phases 透传（CC 只类型断言放行、不深度校验，script-doc §1.4）：List&lt;Map&gt; → List&lt;PhaseDef&gt;，形状不合法则 null。 */
    @SuppressWarnings("unchecked")
    private static List<WorkflowMeta.PhaseDef> extractPhases(Object phases) {
        if (!(phases instanceof List)) {
            return null;
        }
        List<WorkflowMeta.PhaseDef> out = new ArrayList<>();
        for (Object item : (List<Object>) phases) {
            if (item instanceof Map) {
                Map<String, Object> pm = (Map<String, Object>) item;
                Object title = pm.get("title");
                Object detail = pm.get("detail");
                out.add(new WorkflowMeta.PhaseDef(
                        title instanceof String ? (String) title : null,
                        detail instanceof String ? (String) detail : null));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 三条静态校验规则（对齐 CC assertScriptBody，script.ts:165-187）。
     *
     * <p>Why：body 是 new AsyncFunction 的函数体（非 ESM 模块），若脚本写了 import/多余 export，
     * 会落入 AsyncFunction 的泛化 "Syntax error"，模型/用户难定位根因——三条规则给出带引导的精确报错。
     * 三条规则都<b>不阻塞正常纯 JS 脚本</b>（无 import / 无额外 export 即放行）。</p>
     */
    static void assertScriptBody(String body) {
        if (IMPORT_STATIC_RE.matcher(body).find()) {
            log.warn("assertScriptBody 拒绝：静态 import（body 预览={}...）（CC script.ts:166-170）", preview(body));
            throw new ScriptError(
                    "workflow scripts are the body of new AsyncFunction (not ESM modules); import is not supported. "
                            + "agent / parallel / pipeline / phase / log / workflow / args / budget are injected as parameters — use them directly.");
        }
        if (IMPORT_DYNAMIC_RE.matcher(body).find()) {
            log.warn("assertScriptBody 拒绝：动态 import(...)（沙箱显式逃逸拦截，CC script.ts:175-179）");
            throw new ScriptError(
                    "dynamic import(...) is forbidden in workflow scripts: it bypasses the Date/Math sandbox and breaks resume determinism. "
                            + "The sandbox does not guarantee security (same trust level as the LLM), but explicit escapes are prohibited. Inject external dependencies via args.");
        }
        if (EXTRA_EXPORT_RE.matcher(body).find()) {
            log.warn("assertScriptBody 拒绝：额外 export（meta 已剥离，命中即第二个 export，CC script.ts:181-186）");
            throw new ScriptError(
                    "workflow scripts allow only one export const meta = {...} (already extracted by the engine). "
                            + "Remove other export / export default statements; use top-level return for the result.");
        }
        if (log.isDebugEnabled()) {
            log.debug("assertScriptBody 通过：body 无 import / 动态 import / 额外 export（CC 三条规则全部放行）");
        }
    }

    /** 日志预览：前 40 字符，换行转义为 \\n。 */
    private static String preview(String s) {
        int len = Math.min(s.length(), 40);
        return s.substring(0, len).replace("\n", "\\n");
    }

    /** JS \s 白名单对齐（CC 用 /\s/）：Java isWhitespace + NBSP / U+2028 / U+2029 / U+FEFF。 */
    private static boolean isJsWhitespace(char c) {
        return Character.isWhitespace(c) || c == ' ' || c == ' ' || c == ' ' || c == '﻿';
    }

    /** extractMeta 结果（meta 可为 null；body 为剥离后的函数体）。 */
    record ExtractResult(WorkflowMeta meta, String body) {
    }

    // ---- 纯字面量解析（对齐 CC 无参 Function 求值，script.ts:76-85）----

    /**
     * JS 对象字面量纯子集解析器：只接受对象 / 数组 / 字符串 / 数字 / 布尔 / null。
     * 任何裸标识符（非 true/false/null）、函数调用、模板插值、算术 → 拒绝（等价 CC ReferenceError 包装）。
     *
     * <p>对齐说明：CC new Function('return (&#123;n: 1+2&#125;)')() 允许纯算术（无标识符引用即求值成功）；
     * 本实现略严格于 CC（算术/插值一律拒绝），属「拒绝方向」收紧——不会放行任何非确定性表达式，
     * 且 meta 形状（name/description/whenToUse/phases）均为纯字面量，不产生实际兼容缺口。</p>
     */
    private static final class LiteralParser {
        private final String src;
        private int pos;

        LiteralParser(String src) {
            this.src = src;
        }

        Object parse() {
            Object v = parseValue();
            skipWs();
            if (pos < src.length()) {
                throw new IllegalArgumentException("unexpected trailing content at index " + pos);
            }
            return v;
        }

        private Object parseValue() {
            skipWs();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unexpected end of meta literal");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                case '\'':
                case '`':
                    return parseString(c);
                default:
                    if (c == '-' || Character.isDigit(c)) {
                        return parseNumber();
                    }
                    if (isIdentStart(c)) {
                        return parseIdentValue();
                    }
                    if (c == '(') {
                        throw new IllegalArgumentException("function call / parenthesized expression is not a plain literal");
                    }
                    throw new IllegalArgumentException("unexpected character '" + c + "' at index " + pos);
            }
        }

        private Map<String, Object> parseObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key;
                char c = peek();
                if (c == '"' || c == '\'' || c == '`') {
                    key = parseString(c);
                } else if (isIdentStart(c)) {
                    key = parseIdent();
                } else {
                    throw new IllegalArgumentException("expected object key at index " + pos);
                }
                skipWs();
                if (peek() != ':') {
                    throw new IllegalArgumentException("expected ':' after object key at index " + pos);
                }
                pos++;
                Object value = parseValue();
                map.put(key, value);
                skipWs();
                char d = peek();
                if (d == ',') {
                    pos++;
                    // JS 允许尾随逗号：`,}` 视为对象结束（meta 常见 `key: v,` 结尾）
                    skipWs();
                    if (peek() == '}') {
                        pos++;
                        return map;
                    }
                } else if (d == '}') {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("expected ',' or '}' at index " + pos);
                }
            }
        }

        private List<Object> parseArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                    // JS 允许尾随逗号：`,]` 视为数组结束
                    skipWs();
                    if (peek() == ']') {
                        pos++;
                        return list;
                    }
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("expected ',' or ']' at index " + pos);
                }
            }
        }

        private String parseString(char quote) {
            pos++; // 引号
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == quote) {
                    pos++;
                    return sb.toString();
                }
                if (c == '$' && quote == '`' && pos + 1 < src.length() && src.charAt(pos + 1) == '{') {
                    throw new IllegalArgumentException("template interpolation is not a plain literal");
                }
                if (c == '\\') {
                    pos++;
                    if (pos >= src.length()) {
                        break;
                    }
                    char e = src.charAt(pos);
                    switch (e) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'v':
                            sb.append('');
                            break;
                        case '0':
                            sb.append('\0');
                            break;
                        case 'x':
                            if (pos + 2 < src.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(src.substring(pos + 1, pos + 3), 16));
                                    pos += 2;
                                } catch (NumberFormatException ex) {
                                    sb.append('x');
                                }
                            } else {
                                sb.append('x');
                            }
                            break;
                        case 'u':
                            if (pos + 4 < src.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(src.substring(pos + 1, pos + 5), 16));
                                    pos += 4;
                                } catch (NumberFormatException ex) {
                                    sb.append('u');
                                }
                            } else {
                                sb.append('u');
                            }
                            break;
                        default:
                            sb.append(e);
                    }
                    pos++;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            throw new IllegalArgumentException("unterminated string literal");
        }

        private Object parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E'
                        || ((c == '+' || c == '-') && pos > start
                        && (src.charAt(pos - 1) == 'e' || src.charAt(pos - 1) == 'E'))) {
                    pos++;
                } else {
                    break;
                }
            }
            String num = src.substring(start, pos);
            try {
                if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid number '" + num + "' at index " + start);
            }
        }

        private Object parseIdentValue() {
            String id = parseIdent();
            switch (id) {
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                case "null":
                    return null;
                default:
                    throw new IllegalArgumentException(
                            "identifier reference '" + id + "' — meta must be a plain literal");
            }
        }

        private String parseIdent() {
            int start = pos;
            while (pos < src.length() && isIdentPart(src.charAt(pos))) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private void skipWs() {
            while (pos < src.length() && isJsWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private static boolean isIdentStart(char c) {
            return Character.isLetter(c) || c == '_' || c == '$';
        }

        private static boolean isIdentPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }
    }
}
