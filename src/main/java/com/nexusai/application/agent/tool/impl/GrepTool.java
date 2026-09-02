package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.bash.BashRuleMatcher;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.infra.util.SemanticBoolean;
import com.nexusai.infra.util.SemanticNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Grep 工具 · 对齐 CC {@code GrepTool.ts}（{@code src/tools/GrepTool/}，Open-Claude-Code 2.1.88）。
 *
 * <p>[A6 / G13⑥] <b>真实 rg 引擎</b>：旧实现用 {@code Files.walk} + Java {@code Pattern} 自建
 * 搜索（正则方言 / glob 语义 / VCS 排除均偏离 CC，TR-D3 Q-5/D-7 受控残留）。本版引入
 * {@link RipgrepRunner} 调用真实 ripgrep 二进制（打包自 CC {@code package/vendor/ripgrep/}，
 * rg 14.1.1），参数管线严格对齐 {@code GrepTool.ts:330-441}（--hidden / VCS --glob 排除 /
 * --max-columns 500 / -U --multiline-dotall / -i / -l / -c / -n / -C/-B/-A / -e / --type / --glob /
 * read-ignore --glob 排除），三模式输出解析对齐 {@code :443-576}。
 *
 * <p>IMP-D1 契约对齐（组 2-4，CC GrepTool.ts）：
 * <ul>
 *   <li><b>参数 include→glob</b> —— CC GrepTool.ts:46-51 inputSchema 用 {@code glob}（rg --glob 过滤）；
 *       Java 旧 {@code include} 参数名错位（TR-D3 D-4）已改名。</li>
 *   <li><b>三模式文本输出</b> —— CC mapToolResultToToolResultBlockParam（GrepTool.ts:254-309）
 *       content / files_with_matches / count 三种文本；Java 旧 raw JSON（R-A18 ✗）已改 CC 逐字格式。</li>
 *   <li><b>applyHeadLimit 分页</b> —— CC :110-128（head_limit 默认 250、0=无限、offset 分页、仅截断时
 *       appliedLimit）；Java 旧 MAX_MATCHES=500 硬上限（TR-D3 ⊕-8）已删。</li>
 *   <li><b>上限全删</b> —— CC VCS 排除仅 6 目录（:95-102，Java 旧 SKIP_DIRS 8 个含 node_modules 等 ⊕-5
 *       已删）；无 10MB 文件跳过（--max-columns 500 代替，⊕-6 已删）；无文件数 cap 2000（⊕-7 已删）。</li>
 * </ul>
 */
@Component
public class GrepTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC GrepTool.ts:95-102 VCS_DIRECTORIES_TO_EXCLUDE（6 个）；Java 旧 SKIP_DIRS 8 个已收敛。 */
    private static final Set<String> VCS_DIRS = Set.of(
        ".git", ".svn", ".hg", ".bzr", ".jj", ".sl");

    /** CC GrepTool.ts:108 DEFAULT_HEAD_LIMIT = 250。 */
    private static final int DEFAULT_HEAD_LIMIT = 250;

    /** CC GrepTool.ts:338 --max-columns 500（行内容截断，防 base64/压缩内容噪声）。 */
    private static final int MAX_COLUMNS = 500;

    private final PathGuard guard;

    /** 真实 ripgrep 子进程调用器 · 对齐 CC {@code utils/ripgrep.ts ripGrep}。 */
    private final RipgrepRunner ripgrep = new RipgrepRunner();

    /**
     * [IMPL-09] 读权限检查器 · 对齐 CC GrepTool.checkPermissions
     * (GrepTool.ts:233-239) 委托 checkReadPermissionForTool。
     *
     * <p>WHY: 与 {@code GlobTool} 同因 — 旧 6 hook 链对 grep 的 1c/1g 语义随删除
     * 收敛到管线，Java GrepTool 无 checkPermissions override → 默认 Allow 短路 1c。
     * 缺失时 fail-loud（对齐 ReadFileTool:312-317，Pattern #11）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.permission.ReadPermissionChecker permissionChecker;

    /** 测试/装配用 setter · 与 ReadFileTool 同模式（构造器保留旧 API）。 */
    public void setPermissionChecker(com.nexusai.application.agent.permission.ReadPermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    /**
     * [IMPL-09] checkPermissions · 委托 {@link ReadPermissionChecker}
     * （CC GrepTool.ts:233-239 checkReadPermissionForTool 等价）。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (permissionChecker == null) {
            throw new IllegalStateException(
                "permissionChecker 未注入, 无法执行 grep 读权限检查");
        }
        return permissionChecker.check(this, input, ctx);
    }

    public GrepTool(PathGuard guard) {
        if (guard == null) throw new IllegalArgumentException("guard is null");
        this.guard = guard;
    }

    @Override
    public String name() { return "Grep"; }

    // [IMP-C3 删除] 旧 snake_case 'grep' alias 已删除（DC-A2-05/TR-D3-⊕-2）：
    // CC GrepTool.ts:161 name='Grep'（GREP_TOOL_NAME=prompt.ts:4），真源无 aliases 声明，
    // 全仓 grep 仅 shell 命令名。未上线可破约（决策清单 组2-2）。不保留兼容壳。

    @Override
    public String description() {
        return "A powerful search tool built on ripgrep. " +
               "Supports full regex syntax (e.g., \"log.*Error\", \"function\\\\s+\\\\w+\"). " +
               "Filter files with glob parameter (e.g., \"*.js\", \"**/*.tsx\") or type parameter " +
               "(e.g., \"js\", \"py\", \"rust\"). Output modes: \"content\" shows matching lines, " +
               "\"files_with_matches\" shows only file paths (default), \"count\" shows match counts. " +
               "Pattern syntax: Uses ripgrep (not grep) - literal braces need escaping " +
               "(use `interface\\\\{\\\\}` to find `interface{}` in Go code). " +
               "Multiline matching: By default patterns match within single lines only. " +
               "For cross-line patterns use `multiline: true`.";
    }

    /**
     * [G10] prompt · 对齐 CC {@code GrepTool.ts:241-243} {@code async prompt() { return getDescription() }}。
     * CC getDescription()（GrepTool/prompt.ts:6-18）为内置 ripgrep 搜索工具指南。Java 端返回
     * {@link #description()}（真实 rg 引擎，正则方言对齐 CC ripgrep regex-crate）。
     */
    @Override
    public String prompt() {
        return description();
    }

    /**
     * [G9] 结果落盘阈值 · 对齐 CC {@code GrepTool.ts:164 maxResultSizeChars: 20_000}
     * （20K chars = tool result persistence threshold）。覆盖 Tool 基类默认 100_000。
     * 系统级 cap 仍由 {@code ToolResultStorage.DEFAULT_MAX_RESULT_SIZE_CHARS}=50_000 施加
     * （CC toolResultStorage.ts:77 {@code Math.min(declared, DEFAULT)}），故有效阈值 = 20_000。
     */
    @Override
    public long maxResultSizeChars() {
        return 20_000L;
    }

    /**
     * 搜索提示 · 对齐 CC {@code GrepTool.ts:162 searchHint = 'search file contents with regex (ripgrep)'}。
     * 供 ToolSearch 关键词匹配。
     */
    @Override
    public String searchHint() {
        return "search file contents with regex (ripgrep)";
    }

    /**
     * [G9] 工具使用摘要 · 对齐 CC {@code GrepTool.ts:169 getToolUseSummary}（Grep UI）
     * —— 摘要为搜索 pattern（CC UI.tsx SearchResultSummary 同源）；无 pattern → null。
     */
    @Override
    public String getToolUseSummary(java.util.Map<String, Object> processedInput) {
        Object pattern = processedInput == null ? null : processedInput.get("pattern");
        return pattern == null ? null : String.valueOf(pattern);
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        // CC GrepTool.ts:33-89 z.strictObject → unknown keys rejected
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");

        props.putObject("pattern").put("type", "string")
            .put("description", "The regular expression pattern to search for in file contents");
        props.putObject("path").put("type", "string")
            .put("description", "File or directory to search in (rg PATH). Defaults to current working directory.");
        // CC GrepTool.ts:46-51 glob（Java 旧 include 重命名）
        props.putObject("glob").put("type", "string")
            .put("description", "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\") - maps to rg --glob");
        props.putObject("output_mode").put("type", "string")
            .put("description", "Output mode: \"content\" shows matching lines, \"files_with_matches\" shows file paths, \"count\" shows match counts. Defaults to \"files_with_matches\".");
        ObjectNode outputMode = (ObjectNode) props.get("output_mode");
        ArrayNode outputModeEnum = outputMode.putArray("enum");
        outputModeEnum.add("content");
        outputModeEnum.add("files_with_matches");
        outputModeEnum.add("count");
        props.putObject("-B").put("type", "integer")
            .put("x-semantic-number", true)
            .put("description", "Number of lines to show before each match (rg -B). Requires output_mode: \"content\".");
        props.putObject("-A").put("type", "integer")
            .put("x-semantic-number", true)
            .put("description", "Number of lines to show after each match (rg -A). Requires output_mode: \"content\".");
        props.putObject("-C").put("type", "integer")
            .put("x-semantic-number", true)
            .put("description", "Alias for context.");
        props.putObject("context").put("type", "integer")
            .put("x-semantic-number", true)
            .put("description", "Number of lines to show before and after each match (rg -C). Requires output_mode: \"content\".");
        props.putObject("-n").put("type", "boolean")
            .put("x-semantic-boolean", true)
            .put("description", "Show line numbers in output (rg -n). Requires output_mode: \"content\". Defaults to true.");
        props.putObject("-i").put("type", "boolean")
            .put("x-semantic-boolean", true)
            .put("description", "Case insensitive search (rg -i)");
        props.putObject("type").put("type", "string")
            .put("description", "File type to search (rg --type). Common types: js, py, rust, go, java, etc.");
        props.putObject("head_limit").put("type", "integer")
            .put("x-semantic-number", true)
            .put("description", "Limit output to first N lines/entries, equivalent to \"| head -N\". Defaults to 250 when unspecified. Pass 0 for unlimited.");
        props.putObject("offset").put("type", "integer")
            .put("x-semantic-number", true)
            .put("description", "Skip first N lines/entries before applying head_limit. Defaults to 0.");
        props.putObject("multiline").put("type", "boolean")
            .put("x-semantic-boolean", true)
            .put("description", "Enable multiline mode where . matches newlines (rg -U --multiline-dotall). Default: false.");

        ArrayNode req = schema.putArray("required");
        req.add("pattern");
        return schema;
    }

    /**
     * 路径扩展点 · CC original: {@code getPath({path}) → path || getCwd()}
     * （{@code GrepTool.ts:195}）。
     *
     * <p>权限管线（CC {@code filesystem.ts:1035-1041}）用本方法取本次搜索根目录做权限检查。
     * Java 端 path 可选；缺失返回 null → 走 ask（CC getCwd() 兜底语义差异登记）。
     *
     * @param input 工具输入（含可选 {@code path}）
     * @return 本次搜索的根目录；缺失返回 null
     */
    @Override
    public String getPath(JsonNode input) {
        return input == null ? null : input.path("path").asText(null);
    }

    /**
     * 权限规则内容匹配器 · CC original: {@code preparePermissionMatcher}
     * （{@code GrepTool.ts:198}）→ {@code rulePattern => matchWildcardPattern(rulePattern, pattern)}。
     *
     * @param input 工具输入（含 {@code pattern}）
     * @return 内容匹配谓词（pattern → boolean）
     */
    @Override
    public Predicate<String> preparePermissionMatcher(JsonNode input) {
        String pattern = input == null ? null : input.path("pattern").asText(null);
        return rulePattern -> pattern != null && BashRuleMatcher.matchWildcardPattern(rulePattern, pattern);
    }

    @Override
    public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public boolean isConcurrencySafe(JsonNode input) { return true; }

    /**
     * [R32-b8 #2] GrepTool 是搜索操作（正则搜索文件内容）· 对齐 CC
     * {@code GrepTool.ts:192-194} 返回 {@code {isSearch: true, isRead: false}}.
     */
    @Override
    public SearchReadKind searchReadKind(JsonNode input) {
        return SearchReadKind.IS_SEARCH;
    }


    @Override
    public ToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String patternStr = input.has("pattern") ? input.get("pattern").asText() : null;
        if (patternStr == null || patternStr.isEmpty()) {
            return ToolResult.error(call.id(), "GrepTool: missing required 'pattern'");
        }
        String outputMode = input.has("output_mode") ? input.get("output_mode").asText() : "files_with_matches";
        if (!outputMode.equals("content") && !outputMode.equals("files_with_matches") && !outputMode.equals("count")) {
            return ToolResult.error(call.id(), "GrepTool: invalid output_mode: " + outputMode);
        }

        // [G12/OPD-D3-14] semantic 强转 · 对齐 CC semanticBoolean/semanticNumber（GrepTool.ts:58-88）
        //   模型偶尔引号化布尔/数字（"-i":"true"），旧 .asBoolean(false) 对字符串恒 false（EV-D3-105）。
        boolean caseInsensitive = semanticBool(input, "-i", false);
        boolean multiline = semanticBool(input, "multiline", false);
        // CC :321 `'-n': show_line_numbers = true`（默认 true）
        boolean showLineNum = semanticBool(input, "-n", true);

        // context 优先级（CC :362-376）：context > -C(alias) > -B/-A
        // CC 先查 context（:364 `context !== undefined`），再查 -C（:366），最后 -B/-A（:369-374）。
        // [G12/OPD-D3-14] semantic 数字强转（GrepTool.ts:58-67/-C/:80-85）——字符串 "3" 等价数字 3。
        int ctxC;
        if (input.has("context")) {
            ctxC = semanticInt(input, "context", 0);
        } else if (input.has("-C")) {
            ctxC = semanticInt(input, "-C", 0);
        } else {
            ctxC = 0;
        }
        int ctxB = semanticInt(input, "-B", 0);
        int ctxA = semanticInt(input, "-A", 0);

        // applyHeadLimit（CC :110-128）：head_limit 默认 250、0=无限、offset 分页
        int headLimit = semanticInt(input, "head_limit", DEFAULT_HEAD_LIMIT);
        int offset = semanticInt(input, "offset", 0);

        String glob = input.has("glob") ? input.get("glob").asText() : null;
        String type = input.has("type") ? input.get("type").asText() : null;

        Path root;
        try {
            String pathArg = input.has("path") && !input.get("path").asText().isEmpty()
                ? input.get("path").asText() : ".";
            root = pathArg.equals(".") ? guard.workdir() : guard.resolve(pathArg);
        } catch (Exception e) {
            return ToolResult.error(call.id(), "GrepTool: invalid path: " + e.getMessage());
        }

        // ─── rg 参数组装 · 严格对齐 CC GrepTool.ts:330-441 ───
        List<String> args = new ArrayList<>();
        args.add("--hidden");
        // CC :332-335 排除 VCS 目录（--glob !<dir>）
        for (String dir : VCS_DIRS) {
            args.add("--glob");
            args.add("!" + dir);
        }
        // CC :338 --max-columns 500
        args.add("--max-columns");
        args.add(String.valueOf(MAX_COLUMNS));
        // CC :341-343 仅显式 multiline 时加 -U --multiline-dotall
        if (multiline) {
            args.add("-U");
            args.add("--multiline-dotall");
        }
        // CC :346-348 可选 -i
        if (caseInsensitive) {
            args.add("-i");
        }
        // CC :351-355 输出模式标志
        if ("files_with_matches".equals(outputMode)) {
            args.add("-l");
        } else if ("count".equals(outputMode)) {
            args.add("-c");
        }
        // CC :358-360 content 模式 + -n
        if (showLineNum && "content".equals(outputMode)) {
            args.add("-n");
        }
        // CC :363-376 content 模式上下文（context > -C > -B/-A）
        if ("content".equals(outputMode)) {
            if (input.has("context")) {
                args.add("-C");
                args.add(String.valueOf(ctxC));
            } else if (input.has("-C")) {
                args.add("-C");
                args.add(String.valueOf(ctxC));
            } else {
                if (input.has("-B")) {
                    args.add("-B");
                    args.add(String.valueOf(ctxB));
                }
                if (input.has("-A")) {
                    args.add("-A");
                    args.add(String.valueOf(ctxA));
                }
            }
        }
        // CC :378-384 以 '-' 开头的 pattern 用 -e 指定（防被当作命令行选项）
        if (patternStr.startsWith("-")) {
            args.add("-e");
            args.add(patternStr);
        } else {
            args.add(patternStr);
        }
        // CC :387-389 --type 过滤
        if (type != null && !type.isEmpty()) {
            args.add("--type");
            args.add(type);
        }
        // CC :391-409 --glob 过滤：空白分段；含 {} 的分段整体保留，否则按逗号拆分
        if (glob != null && !glob.isEmpty()) {
            for (String raw : glob.split("\\s+")) {
                if (raw.isEmpty()) {
                    continue;
                }
                if (raw.indexOf('{') >= 0 && raw.indexOf('}') >= 0) {
                    args.add("--glob");
                    args.add(raw);
                } else {
                    for (String part : raw.split(",")) {
                        if (!part.isEmpty()) {
                            args.add("--glob");
                            args.add(part);
                        }
                    }
                }
            }
        }
        // CC :412-427 read-ignore patterns → --glob !**/pattern 或 !/pattern
        List<String> readDenyPatterns = readIgnorePatterns(ctx);
        for (String ignore : readDenyPatterns) {
            // CC :423-425 绝对路径前缀 '/' 直接取反；相对路径前缀 '!**/'
            args.add("--glob");
            args.add(ignore.startsWith("/") ? "!" + ignore : "!**/" + ignore);
        }
        if (log.isDebugEnabled()) {
            log.debug("GrepTool: rg 参数组装完成 pattern='{}' mode={} args={}（CC GrepTool.ts:330-441）",
                patternStr, outputMode, args);
        }

        AbortController abort = (ctx != null && ctx.abortController() != null)
            ? ctx.abortController() : AbortController.NOOP;

        List<String> results;
        try {
            results = ripgrep.ripGrep(args, root.toString(), abort);
        } catch (RipgrepRunner.RipgrepTimeoutError e) {
            // CC :436-454 超时 → RipgrepTimeoutError 上抛（模型知道搜索未完成）
            log.warn("GrepTool: rg 搜索超时 pattern='{}'（RipgrepTimeoutError）", patternStr);
            return ToolResult.error(call.id(), e.getMessage());
        } catch (IOException e) {
            // rg 二进制缺失/IO 失败 → fail-loud（CC :384-388 reject）
            log.error("GrepTool: rg 执行失败 pattern='{}'", patternStr, e);
            return ToolResult.error(call.id(), "GrepTool: rg execution failed: " + e.getMessage());
        }
        if (log.isDebugEnabled()) {
            log.debug("GrepTool: rg 执行完成 pattern='{}' mode={} 结果行数={}", patternStr, outputMode, results.size());
        }

        if ("content".equals(outputMode)) {
            return contentMode(call.id(), results, headLimit, offset);
        }
        if ("count".equals(outputMode)) {
            return countMode(call.id(), results, headLimit, offset);
        }
        return filesWithMatchesMode(call.id(), results, headLimit, offset);
    }

    // ───────────────────────── 三模式执行（基于 rg stdout） ─────────────────────────

    /** content 模式 · CC :443-476：applyHeadLimit → relativize 前缀 → 拼 content。 */
    private ToolResult contentMode(String id, List<String> results, int headLimit, int offset) {
        // CC :450-454 先 head_limit 再 relativize（逐行廉价操作，避免处理被丢弃的行）
        List<String> limited = applyHeadLimit(results, headLimit, offset);
        Path workdir = guard.workdir();
        List<String> finalLines = new ArrayList<>();
        for (String line : limited) {
            // CC :457-465 行格式 /abs/path:content 或 /abs/path:num:content → 前缀相对化。
            // 注：CC 用 indexOf(':')（POSIX 路径无冒号）；Windows 盘符路径（C:\...）首冒号在
            // 盘符后，需跳过盘符取路径分隔冒号（Java 端 Windows 修复，CC 在 Windows 同缺陷）。
            int colonIndex = pathColonIndex(line);
            if (colonIndex > 0) {
                String filePath = line.substring(0, colonIndex);
                String rest = line.substring(colonIndex);
                finalLines.add(toRelativePath(workdir, filePath) + rest);
            } else {
                finalLines.add(line);
            }
        }
        String content = String.join("\n", finalLines);
        String limitInfo = formatLimitInfo(appliedLimit(limited, headLimit, offset, results), offset);

        log.info("GrepTool content: pattern 结果行={} limited={}", results.size(), finalLines.size());
        if (content.isEmpty()) {
            content = "No matches found";
        }
        if (limitInfo != null && !limitInfo.isEmpty()) {
            return ToolResult.success(id, content + "\n\n[Showing results with pagination = " + limitInfo + "]");
        }
        return ToolResult.success(id, content);
    }

    /** count 模式 · CC :478-524：lastIndexOf(':') 拆分文件名:count → 摘要统计。 */
    private ToolResult countMode(String id, List<String> results, int headLimit, int offset) {
        // CC :481-485 applyHeadLimit 后再 parse（对齐有限集口径）
        List<String> limited = applyHeadLimit(results, headLimit, offset);
        Path workdir = guard.workdir();
        List<String> finalCountLines = new ArrayList<>();
        for (String line : limited) {
            // CC :490-497 行格式 /abs/path:count → lastIndexOf(':') 拆分（Windows 盘符路径安全）
            int colonIndex = line.lastIndexOf(':');
            if (colonIndex > 0) {
                String filePath = line.substring(0, colonIndex);
                String count = line.substring(colonIndex);
                finalCountLines.add(toRelativePath(workdir, filePath) + count);
            } else {
                finalCountLines.add(line);
            }
        }
        // CC :500-512 解析 totalMatches / fileCount
        int totalMatches = 0;
        int fileCount = 0;
        for (String line : finalCountLines) {
            int colonIndex = line.lastIndexOf(':');
            if (colonIndex > 0) {
                String countStr = line.substring(colonIndex + 1);
                try {
                    totalMatches += Integer.parseInt(countStr);
                    fileCount++;
                } catch (NumberFormatException ignored) {
                    // skip malformed（CC isNaN 等价）
                }
            }
        }
        String rawContent = String.join("\n", finalCountLines);
        String limitInfo = formatLimitInfo(appliedLimit(limited, headLimit, offset, results), offset);
        if (rawContent.isEmpty()) {
            rawContent = "No matches found";
        }
        String matchesWord = totalMatches == 1 ? "occurrence" : "occurrences";
        String filesWord = fileCount == 1 ? "file" : "files";
        String summary = "\n\nFound " + totalMatches + " total " + matchesWord + " across " + fileCount + " " + filesWord + "."
            + (limitInfo != null && !limitInfo.isEmpty() ? " with pagination = " + limitInfo : "");

        log.info("GrepTool count: files={} total={}", fileCount, totalMatches);
        return ToolResult.success(id, rawContent + summary);
    }

    /** files_with_matches 模式（默认）· CC :526-576：mtime 降序 → applyHeadLimit → relativize。 */
    private ToolResult filesWithMatchesMode(String id, List<String> results, int headLimit, int offset) {
        // CC :529-553 stat allSettled → mtime 降序（tiebreak 文件名升序，确定性）
        List<String> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> {
            long ta = mtimeOrZero(a);
            long tb = mtimeOrZero(b);
            int cmp = Long.compare(tb, ta);
            if (cmp != 0) return cmp;
            return a.compareTo(b);
        });
        // CC :556-563 applyHeadLimit → relativize
        List<String> limited = applyHeadLimit(sorted, headLimit, offset);
        Path workdir = guard.workdir();
        List<String> filenames = new ArrayList<>();
        for (String p : limited) {
            filenames.add(toRelativePath(workdir, p));
        }
        String limitInfo = formatLimitInfo(appliedLimit(limited, headLimit, offset, sorted), offset);
        int numFiles = filenames.size();

        log.info("GrepTool files_with_matches: files={} limited={}", sorted.size(), numFiles);
        if (numFiles == 0) {
            return ToolResult.success(id, "No files found");
        }
        String filesWord = numFiles == 1 ? "file" : "files";
        StringBuilder result = new StringBuilder("Found " + numFiles + " " + filesWord);
        if (limitInfo != null && !limitInfo.isEmpty()) {
            result.append(" ").append(limitInfo);
        }
        result.append("\n").append(String.join("\n", filenames));
        return ToolResult.success(id, result.toString());
    }

    // ───────────────────────── 辅助 ─────────────────────────

    /**
     * rg 输出行中"路径 → 内容"分隔冒号的位置。
     *
     * <p>CC GrepTool.ts:458/490 用 {@code indexOf(':')} / {@code lastIndexOf(':')}；POSIX 路径无
     * 冒号所以首冒号即分隔。Windows 盘符路径（{@code C:\dir\a.txt:1:content}）首冒号在盘符后
     * （{@code C:}），须跳过取下一个冒号（NTFS 文件名禁冒号，故盘符是路径内唯一冒号）。
     * Java 端 Windows 修复；POSIX 行为与 CC 逐字一致。
     *
     * @param line rg 输出行（path[:line][:content]）
     * @return 路径分隔冒号下标；无冒号返回 -1
     */
    static int pathColonIndex(String line) {
        if (line != null && line.length() >= 2
                && Character.isLetter(line.charAt(0)) && line.charAt(1) == ':') {
            return line.indexOf(':', 2);
        }
        return line == null ? -1 : line.indexOf(':');
    }

    /**
     * 绝对路径 → 相对路径 · CC original: {@code toRelativePath}（utils/path.ts:95-98）。
     * 相对结果以 {@code ..} 开头（目标在 cwd 外）→ 保留绝对路径。
     */
    private static String toRelativePath(Path workdir, String absolutePath) {
        if (absolutePath == null) {
            return null;
        }
        try {
            Path p = Path.of(absolutePath);
            Path rel = workdir.relativize(p);
            String relStr = rel.toString();
            return relStr.startsWith("..") ? absolutePath : relStr;
        } catch (Exception e) {
            // 不同盘符/非法路径 → 保留原样（CC relative() 对跨根路径的兜底）
            return absolutePath;
        }
    }

    /** 文件 mtime（ms）· 取不到返回 0（CC stat allSettled 失败 → mtime 0 :531）。 */
    private long mtimeOrZero(String absPath) {
        try {
            FileTime ft = Files.getLastModifiedTime(Path.of(absPath));
            return ft.toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** CC :110-128 applyHeadLimit：0=无限、默认 250、offset 分页。 */
    private <T> List<T> applyHeadLimit(List<T> items, int limit, int offset) {
        if (limit == 0) {
            return offset >= items.size() ? List.of() : new ArrayList<>(items.subList(offset, items.size()));
        }
        int effective = limit > 0 ? limit : DEFAULT_HEAD_LIMIT;
        int from = Math.min(offset, items.size());
        int to = Math.min(offset + effective, items.size());
        return new ArrayList<>(items.subList(from, to));
    }

    /** CC :116-127 仅截断时 set appliedLimit；offset>0 才报 appliedOffset；limit=0（无限）appliedLimit=undefined。 */
    private Integer appliedLimit(List<?> limited, int limit, int offset, List<?> original) {
        // CC :116-118 limit===0 → 无限，appliedLimit undefined
        if (limit == 0) return null;
        int effective = limit > 0 ? limit : DEFAULT_HEAD_LIMIT;
        boolean wasTruncated = original.size() - offset > effective;
        return wasTruncated ? effective : null;
    }

    /** CC :134-142 formatLimitInfo：parts join ', '。 */
    private String formatLimitInfo(Integer appliedLimit, int offset) {
        List<String> parts = new ArrayList<>();
        if (appliedLimit != null) parts.add("limit: " + appliedLimit);
        if (offset > 0) parts.add("offset: " + offset);
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * [G12/OPD-D3-14] semantic int 读取 · 对齐 CC {@code semanticNumber}（utils/semanticNumber.ts，
     * z.preprocess 隐形强转）。接受 JSON number 或数字字符串（"250"→250、"3"→3）；非法/缺失 → defaultValue。
     */
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

    /**
     * [G12/OPD-D3-14] semantic boolean 读取 · 对齐 CC {@code semanticBoolean}（utils/semanticBoolean.ts）。
     * 接受 JSON boolean 或 "true"/"false" 字符串；非法/缺失 → defaultValue。
     */
    private static boolean semanticBool(JsonNode input, String key, boolean defaultValue) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return defaultValue;
        }
        JsonNode node = input.get(key);
        Object raw;
        if (node.isBoolean()) {
            raw = node.booleanValue();
        } else if (node.isTextual()) {
            raw = node.asText();
        } else {
            return defaultValue;
        }
        return SemanticBoolean.parseBooleanOrDefault(raw, defaultValue);
    }

    /**
     * [G12] 收集 read-deny 规则 ignore patterns · CC original: {@code getFileReadIgnorePatterns}
     * （filesystem.ts:837-855）→ {@code normalizePatternsToPath}（GrepTool.ts:413-427）。
     *
     * <p>CC 收集 'read' 权限 deny 规则的 pattern（ruleContent），按 root 归一化后作为
     * {@code --glob} 排除（前缀 {@code !}，形如 {@code !**}{@code /pattern}）。
     * Java 从 {@code ToolPermissionContext.alwaysDenyRules} 提取 read 工具
     * （Read/Grep/Glob/LSP）的 deny 规则 ruleContent —— Java 权限模型无独立 'read' 权限
     * scope，以工具名过滤为近似（E2 记录，patternRoot 归一为简化：ruleContent 原样传 rg，
     * rg 自身的 glob 引擎做排除匹配，比旧 Java globToRegex 近似更贴近 CC）。
     */
    private static List<String> readIgnorePatterns(ToolUseContext ctx) {
        if (ctx == null || ctx.permissionContext() == null) {
            return List.of();
        }
        List<String> patterns = new ArrayList<>();
        for (Set<com.nexusai.application.agent.permission.PermissionRule> rules :
                ctx.permissionContext().alwaysDenyRules().values()) {
            for (com.nexusai.application.agent.permission.PermissionRule r : rules) {
                if (r.ruleBehavior() == com.nexusai.application.agent.permission.PermissionBehavior.DENY
                        && r.ruleValue().ruleContent() != null) {
                    String tool = r.ruleValue().toolName();
                    if (tool == null) {
                        continue;
                    }
                    if (tool.equals("Read") || tool.equals("Grep") || tool.equals("Glob") || tool.equals("LSP")) {
                        String content = r.ruleValue().ruleContent();
                        if (!content.isBlank()) {
                            patterns.add(content);
                        }
                    }
                }
            }
        }
        return patterns;
    }
}
