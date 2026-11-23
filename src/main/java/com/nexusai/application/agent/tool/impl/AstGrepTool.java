package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.ast.AstGrepBinary;
import com.nexusai.application.agent.ast.AstGrepRunner;
import com.nexusai.application.agent.ast.AstMatch;
import com.nexusai.application.agent.ast.AstPermissionSupport;
import com.nexusai.application.agent.ast.AstUiPayload;
import com.nexusai.application.agent.ast.PlatformKey;
import com.nexusai.application.agent.ast.ProcessAstGrepCli;
import com.nexusai.application.agent.bash.BashRuleMatcher;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ReadPermissionChecker;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.settings.AstToolsConfig;
import com.nexusai.infra.util.SemanticNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * AstGrep · 按 AST 结构搜索（ast-grep CLI 支撑）。
 *
 * <p>与 {@code Grep} 的区别：Grep 匹配<b>文本</b>（正则），AstGrep 匹配<b>语法结构</b>——
 * {@code foo($A)} 命中 {@code foo(1)}，不会命中 {@code fooBar(1)}；元变量 {@code $A} 可回填
 * （{@code meta} 段展示）。适合「找出所有调用点 / 某类声明 / 特定语言构造」，避免用正则猜语法。
 *
 * <h2>关键取舍</h2>
 * <ul>
 *   <li><b>引擎</b>：随包 ast-grep CLI（{@link AstGrepBinary}，与 GrepTool 随包 rg 同一取舍）；
 *       二进制缺失（平台未打包）时 {@link #isEnabled()} 为 false，工具不暴露给模型。</li>
 *   <li><b>分页</b>：{@code skip}/{@code limit}，{@code limit} 夹在 {@code 1..50}
 *       （{@link AstToolsConfig#MAX_MATCHES}，固定值不开放配置）；结果尾巴提示还有更多。</li>
 *   <li><b>作用域</b>：只由 {@code path} 表达（{@code ;} 分隔多目标，缺省 = 会话工作目录），
 *       不做 glob 参数（未验证的 {@code --globs} 与显式文件路径组合语义不稳）。</li>
 *   <li><b>结果形状</b>：模型面文本按文件分组（{@code 行:列  匹配正文} + {@code meta: K=V}）；
 *       前端拿结构化载荷（{@link AstUiPayload}），二者互不影响。</li>
 * </ul>
 */
@Component
public class AstGrepTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AstGrepTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** pat 长度上限（Windows 命令行 32K 的安全余量；正常模式远小于此）。 */
    private static final int MAX_PATTERN_CHARS = 4_000;

    /** skip 上限（防「大 skip 撑爆分页堆」）。 */
    private static final int MAX_SKIP = 10_000;

    /** 模型面 warnings 最多展示条数（runner 侧保留 20 条）。 */
    private static final int MAX_WARNINGS_SHOWN = 5;

    private final PathGuard guard;
    private final AstGrepRunner runner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ReadPermissionChecker permissionChecker;

    /** 测试/装配用 setter（与 GrepTool 同模式）。 */
    public void setPermissionChecker(ReadPermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AstGrepTool(PathGuard guard) {
        this(guard, new AstGrepRunner(new ProcessAstGrepCli()));
    }

    /** 测试注入构造器（fake CLI/runner）。 */
    public AstGrepTool(PathGuard guard, AstGrepRunner runner) {
        if (guard == null) throw new IllegalArgumentException("guard is null");
        if (runner == null) throw new IllegalArgumentException("runner is null");
        this.guard = guard;
        this.runner = runner;
    }

    @Override
    public String name() {
        return ToolNameConstants.AST_GREP_TOOL_NAME;
    }

    @Override
    public String description() {
        return prompt();
    }

    @Override
    public String prompt() {
        return "Search code by AST structure (ast-grep), not text. Pattern examples: \"foo($A)\" matches "
            + "calls foo(1)/foo(bar(2)) but not fooBar(1); \"class $C { $$$B }\" matches a class body. "
            + "Metavariables: $NAME binds one node, $_ matches without binding, $$$NAME binds zero or more "
            + "nodes, $$$ matches zero or more without binding; a name used twice must match the same shape "
            + "($A == $A only matches x == x); metavariable names must be uppercase and cover a whole node "
            + "(prefix$VAR is invalid, $$NAME is invalid). A pattern must be a single AST node - wrap "
            + "non-standalone statements (class $_ { ... }, function $_() { ... }). "
            + "Language is inferred per file from the extension; pass lang only to disambiguate (e.g. cpp for .h) "
            + "and note it then restricts the scan to that language. Built-in languages: java, javascript, "
            + "typescript, tsx, python, go, rust, c, cpp, csharp, kotlin, bash, json, yaml, html, css, markdown, "
            + "ruby, php, swift, scala, lua (toml/sql/xml/vue/svelte are not built in). "
            + "Start narrow with path; use skip/limit to page. A parse warning is not proof of absence - refine "
            + "the pattern before concluding there are no matches. Results are grouped per file with line:column "
            + "and metavariable bindings. "
            + "Prefer AstGrep for code searches: calls, declarations and language constructs that a regex would "
            + "have to guess. Use Grep for non-programming content (logs, prose, and configs in formats not "
            + "listed above), for literal text targeting, or when the language is unsupported: "
            + "AstGrep only scans extensions that map to a parsed language, while node kinds such as string "
            + "literals or comments are matchable only where the parser exposes them.";
    }

    @Override
    public boolean isEnabled() {
        return AstGrepBinary.available();
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    @Override
    public SearchReadKind searchReadKind(JsonNode input) {
        return SearchReadKind.IS_SEARCH;
    }

    @Override
    public long maxResultSizeChars() {
        return 20_000L;
    }

    @Override
    public boolean strict() {
        return true;
    }

    @Override
    public String searchHint() {
        return "structural AST search (ast-grep)";
    }

    @Override
    public String getToolUseSummary(Map<String, Object> processedInput) {
        Object pat = processedInput == null ? null : processedInput.get("pat");
        return pat == null ? null : String.valueOf(pat);
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        props.putObject("pat").put("type", "string")
            .put("description", "A single AST pattern, e.g. foo($A); metavariables $NAME / $_ / $$$NAME / $$$");
        props.putObject("path").put("type", "string")
            .put("description", "File or directory; separate multiple with ';'. Defaults to the session working directory.");
        props.putObject("lang").put("type", "string")
            .put("description", "Language override for ambiguous extensions (e.g. cpp for .h). When set, only files of that language are scanned.");
        props.putObject("skip").put("type", "integer").put("x-semantic-number", true)
            .put("description", "Skip the first N matches (pagination).");
        props.putObject("limit").put("type", "integer").put("x-semantic-number", true)
            .put("description", "Matches per page; defaults to 50 and is clamped to 1..50.");
        ArrayNode required = schema.putArray("required");
        required.add("pat");
        return schema;
    }

    /**
     * 路径扩展点 · 返回 {@code path} 的第一个非空段（原样，不展开）。
     *
     * <p>权限 checker 只读这一个值；但 {@link #checkPermissions} 会对<b>每一段</b>各查一次并取最严
     * （合成输入形状 {@code {"path": p}}，见 {@link AstPermissionSupport}）。原始串保留相对/绝对形态，
     * 与 GrepTool 的 {@code getPath} 语义一致。
     */
    @Override
    public String getPath(JsonNode input) {
        List<String> parts = splitPaths(input == null ? null : input.path("path").asText(null));
        return parts.isEmpty() ? null : parts.get(0);
    }

    @Override
    public Predicate<String> preparePermissionMatcher(JsonNode input) {
        String pat = input == null ? null : input.path("pat").asText(null);
        return rule -> pat != null && BashRuleMatcher.matchWildcardPattern(rule, pat);
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        List<String> paths = splitPaths(input == null ? null : input.path("path").asText(null));
        if (paths.isEmpty()) {
            String sessionId = ctx == null ? null : ctx.sessionId();
            paths = List.of(guard.workdir(sessionId).toString());
        }
        return AstPermissionSupport.strictest(this, ctx, paths, permissionChecker, null, false, null,
            input);
    }

    @Override
    public ToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        if (!AstGrepBinary.available()) {
            return ToolResult.error(call.id(), "Error: ast-grep binary is not available on this platform"
                + " (missing resource " + PlatformKey.gzResource() + ")");
        }
        JsonNode input = call.input();
        String pat = input != null && input.hasNonNull("pat") ? input.get("pat").asText() : null;
        if (pat == null || pat.trim().isEmpty()) {
            return ToolResult.error(call.id(), "Error: pat must be a non-empty AST pattern");
        }
        if (pat.length() > MAX_PATTERN_CHARS) {
            return ToolResult.error(call.id(), "Error: pat is too long (max " + MAX_PATTERN_CHARS + " chars)");
        }
        int skip = semanticInt(input, "skip", 0);
        if (skip < 0 || skip > MAX_SKIP) {
            return ToolResult.error(call.id(), "Error: skip must be an integer in 0.." + MAX_SKIP);
        }
        int maxMatches = AstToolsConfig.MAX_MATCHES;
        int limit = Math.max(1, Math.min(semanticInt(input, "limit", maxMatches), maxMatches));

        String sessionId = ctx == null ? null : ctx.sessionId();
        String rawPath = input.hasNonNull("path") ? input.get("path").asText() : null;
        List<String> rawTargets = splitPaths(rawPath);
        Path workdir = guard.workdir(sessionId);
        List<String> targets = new ArrayList<>();
        for (String raw : rawTargets) {
            Path resolved;
            try {
                resolved = guard.resolve(sessionId, raw);
            } catch (Exception e) {
                return ToolResult.error(call.id(), "Error: path not found: " + raw);
            }
            if (!Files.exists(resolved)) {
                return ToolResult.error(call.id(), "Error: path not found: " + raw);
            }
            targets.add(resolved.toAbsolutePath().normalize().toString());
        }
        if (targets.isEmpty()) {
            targets.add(workdir.toAbsolutePath().normalize().toString());
        }
        String lang = trimToNull(input.hasNonNull("lang") ? input.get("lang").asText() : null);

        AbortController abort = (ctx != null && ctx.abortController() != null)
            ? ctx.abortController() : AbortController.NOOP;

        AstGrepRunner.Page page;
        try {
            page = runner.find(new AstGrepRunner.Request(pat, targets, lang, workdir, AstToolsConfig.TIMEOUT_MS),
                skip, limit, abort);
        } catch (AstGrepRunner.AstGrepException e) {
            log.warn("[AstGrep] 执行失败 pattern={} kind={} msg={}", pat, e.kind(), e.getMessage());
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }

        AstGrepRunner.Page display = displayPage(workdir, page);
        String text = render(display, skip);
        String scope = rawTargets.isEmpty() ? "." : String.join(";", rawTargets);
        Map<String, Object> payload = AstUiPayload.grep(pat, lang, scope, display, skip);
        log.info("[AstGrep] pattern={} targets={} 命中={} 扫描={} 截断={}",
            pat, targets, page.matches().size(), page.filesSearched(), page.outputTruncated());
        return ToolResult.successWithStructuredOutput(call.id(), text, payload);
    }

    // ───────────────────────── 渲染 ─────────────────────────

    /** 模型面文本：按文件分组，{@code 行:列 正文}，多行匹配续行缩进 4 空格，{@code meta} 缩进 6 空格。 */
    static String render(AstGrepRunner.Page page, int skip) {
        StringBuilder sb = new StringBuilder();
        if (page.matches().isEmpty()) {
            String base;
            if (skip > 0 && page.totalMatches() > 0) {
                base = "No matches on this page (skip=" + skip + ", total=" + page.totalMatches()
                    + "); reduce skip.";
            } else if (page.filesSearched() == 0) {
                base = "No matches found (0 files scanned: unsupported file type or all files ignored)";
            } else {
                base = "No matches found";
            }
            sb.append(base);
        } else {
            String currentFile = null;
            for (AstMatch m : page.matches()) {
                if (!m.file().equals(currentFile)) {
                    if (currentFile != null) {
                        sb.append('\n');
                    }
                    sb.append(m.file()).append('\n');
                    currentFile = m.file();
                }
                String[] lines = m.text().split("\n", -1);
                sb.append("  ").append(m.line()).append(':').append(m.column()).append("  ").append(lines[0]);
                for (int i = 1; i < lines.length; i++) {
                    sb.append('\n').append("    ").append(lines[i]);
                }
                sb.append('\n');
                if (!m.singleMeta().isEmpty()) {
                    List<String> keys = new ArrayList<>(m.singleMeta().keySet());
                    keys.sort(String::compareTo);
                    List<String> pairs = new ArrayList<>();
                    for (String k : keys) {
                        pairs.add(k + "=" + m.singleMeta().get(k));
                    }
                    sb.append("      meta: ").append(String.join(", ", pairs)).append('\n');
                }
            }
            if (sb.charAt(sb.length() - 1) == '\n') {
                sb.setLength(sb.length() - 1);
            }
        }
        if (page.limitReached()) {
            sb.append("\n\nResult limit reached; narrow the path or use skip to page through results.");
        }
        if (page.outputTruncated()) {
            // CLI 输出行数超上限、进程被本端 kill：结果不完整，必须让模型知道（否则会误判“就这些”）
            sb.append("\n\n(output truncated at " + ProcessAstGrepCli.MAX_LINES + " matches)");
        }
        List<String> warnings = page.warnings();
        if (warnings != null && !warnings.isEmpty()) {
            sb.append("\n\nWarnings:");
            int shown = Math.min(warnings.size(), MAX_WARNINGS_SHOWN);
            for (int i = 0; i < shown; i++) {
                sb.append("\n- ").append(warnings.get(i));
            }
        }
        return sb.toString();
    }

    /** 载荷里的路径换展示路径（相对会话工作目录，分隔符统一 {@code /}）。 */
    private AstGrepRunner.Page displayPage(Path workdir, AstGrepRunner.Page page) {
        List<AstMatch> display = new ArrayList<>(page.matches().size());
        for (AstMatch m : page.matches()) {
            display.add(new AstMatch(toDisplayPath(workdir, m.file()), m.text(), m.language(),
                m.line(), m.column(), m.endLine(), m.endColumn(), m.startByte(), m.endByte(),
                m.singleMeta(), m.replacement()));
        }
        return new AstGrepRunner.Page(display, page.totalMatches(), page.limitReached(),
            page.filesSearched(), page.warnings(), page.outputTruncated());
    }

    /** 绝对路径 → 相对 workdir 的展示串（不在其下则绝对路径）；分隔符统一 {@code /}。 */
    static String toDisplayPath(Path workdir, String absolutePath) {
        if (absolutePath == null) {
            return "";
        }
        try {
            Path p = Path.of(absolutePath);
            Path rel = workdir.relativize(p.toAbsolutePath().normalize());
            String relStr = rel.toString();
            if (!relStr.isEmpty() && !relStr.startsWith("..")) {
                return relStr.replace('\\', '/');
            }
        } catch (Exception ignored) {
            // 不同盘符/非法路径 → 绝对路径
        }
        return absolutePath.replace('\\', '/');
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** {@code path} 按 {@code ;} 拆分、trim、丢空项。 */
    static List<String> splitPaths(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(";")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** semantic int 读取（接受 JSON number 或数字字符串；非法/缺失 → 默认值）。 */
    private static int semanticInt(JsonNode input, String key, int defaultValue) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return defaultValue;
        }
        JsonNode node = input.get(key);
        Object raw;
        if (node.isNumber()) {
            raw = node.numberValue();
        } else if (node.isTextual()) {
            raw = node.asText();
        } else {
            return defaultValue;
        }
        Object parsed = SemanticNumber.parseNumber(raw);
        if (parsed instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
}
