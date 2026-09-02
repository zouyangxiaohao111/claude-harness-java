package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.bash.BashRuleMatcher;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.permission.PermissionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Glob 工具 · 对齐 CC {@code GlobTool.ts}（{@code src/tools/GlobTool/}，Open-Claude-Code 2.1.88）。
 *
 * <p>用 {@link java.nio.file.PathMatcher}（glob: "*.py"）在 workspace 内递归找匹配文件。
 *
 * <p>IMP-D1 契约对齐（组 2-4，CC GlobTool.ts + utils/glob.ts）：
 * <ul>
 *   <li><b>limit=100</b> —— CC GlobTool.ts:157 {@code globLimits?.maxResults ?? 100}；Java 旧
 *       {@code MAX_RESULTS=1000}（TR-D3 ⊕-4）已删除。truncated = 命中数 &gt; limit。</li>
 *   <li><b>无深度限制</b> —— CC glob() 用 rg 全遍历（utils/glob.ts:98-107，无 MAX_DEPTH）；
 *       Java 旧 {@code MAX_DEPTH=20}（TR-D3 ⊕-3）已删除，{@code Files.walk} 不限深。</li>
 *   <li><b>输出文本</b> —— CC mapToolResult（GlobTool.ts:177-197）：空 → {@code 'No files found'}；
 *       否则 filenames join '\n'；truncated 追加 {@code '(Results are truncated. Consider using
 *       a more specific path or pattern.)'}。Java 旧 {@code '(no matches)'} / {@code '(truncated at N)'}
 *       文案偏离（TR-D3 D-2）已对齐。</li>
 * </ul>
 */
@Component
public class GlobTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GlobTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC globLimits?.maxResults ?? 100（GlobTool.ts:157）；旧 MAX_RESULTS=1000 已删（TR-D3 ⊕-4）。 */
    private static final int DEFAULT_LIMIT = 100;

    private final PathGuard guard;

    /**
     * [IMPL-09] 读权限检查器 · 对齐 CC GlobTool.checkPermissions
     * (GlobTool.ts:135-140) 委托 checkReadPermissionForTool。
     *
     * <p>WHY: 旧 R26 hook 链对 glob 的 1c/1g 语义随删除收敛到管线，但 Java GlobTool
     * 无 checkPermissions override →
     * {@code Tool.checkPermissions} 默认 Allow 短路 1c，1g/2b'/DONT_ASK 语义丢失。
     * CC 真源 GlobTool 显式实现 checkPermissions（读权限 8 步链），本 override
     * 为等价委托。缺失时 fail-loud（对齐 ReadFileTool:312-317，Pattern #11）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.permission.ReadPermissionChecker permissionChecker;

    /** 测试/装配用 setter · 与 ReadFileTool 同模式（构造器保留旧 API）。 */
    public void setPermissionChecker(com.nexusai.application.agent.permission.ReadPermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    /**
     * [IMPL-09] checkPermissions · 委托 {@link ReadPermissionChecker}
     * （CC GlobTool.ts:135-140 checkReadPermissionForTool 等价）。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (permissionChecker == null) {
            throw new IllegalStateException(
                "permissionChecker 未注入, 无法执行 glob 读权限检查");
        }
        return permissionChecker.check(this, input, ctx);
    }

    public GlobTool(PathGuard guard) {
        if (guard == null) throw new IllegalArgumentException("guard is null");
        this.guard = guard;
    }

    @Override
    public String name() { return "Glob"; }

    // [IMP-C3 删除] 旧 snake_case 'glob' alias 已删除（DC-A2-04/TR-D3-⊕-1）：
    // CC GlobTool.ts:58 name='Glob'（GLOB_TOOL_NAME=prompt.ts:1），真源无 aliases 声明，
    // 全仓 glob 仅输入参数名。未上线可破约（决策清单 组2-2）。不保留兼容壳。

    @Override
    public String description() {
        return "Find files matching a glob pattern (recursive, no depth limit). Optional 'path' sets the " +
               "search root (default: workspace root); paths escaping the workspace are rejected. " +
               "Pattern uses glob syntax: '*.py' matches all .py in root; '**/*.java' matches " +
               "all .java recursively. Max 100 results (CC globLimits.maxResults).";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        // CC GlobTool.ts:26-35 z.strictObject → unknown keys rejected（同 G-A3/R-A3 口径）
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");

        ObjectNode pattern = JSON.createObjectNode();
        pattern.put("type", "string");
        pattern.put("description", "Glob pattern (e.g. '*.py', '**/*.java', 'src/**/*.ts')");
        properties.set("pattern", pattern);

        // CC GlobTool.ts:30-34 — path 可选，目录解析
        ObjectNode path = JSON.createObjectNode();
        path.put("type", "string");
        path.put("description", "Optional directory to search in. " +
            "Defaults to the workspace root. Must be a valid directory path if provided.");
        properties.set("path", path);

        ArrayNode required = JSON.createArrayNode();
        required.add("pattern");
        root.set("required", required);
        // [H-WF2-01 WF2-X1 I-2c] 对齐 CC GlobTool.ts:27 z.strictObject → zodToJsonSchema 输出
        //   additionalProperties:false（未知键拒绝）。ToolInputValidator.safeParseSchema 门禁
        //   按本旗标拒绝未声明键（HookMatcherEngine.prepareContentMatcher safeParse）。
        root.put("additionalProperties", false);
        return root;
    }

    /**
     * 路径扩展点 · CC original: {@code getPath({path}) → path ? expandPath(path) : getCwd()}
     * （{@code GlobTool.ts:88}）。
     *
     * <p>权限管线（CC {@code filesystem.ts:1035-1041}）用本方法取本次搜索目录做权限检查。
     * Java 端 path 可选；缺失返回 null → 走 ask（CC getCwd() 兜底语义差异登记：Java 无 getCwd
     * 概念，缺 path 即非法调用）。
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
     * （{@code GlobTool.ts:91}）→ {@code rulePattern => matchWildcardPattern(rulePattern, pattern)}。
     *
     * <p>hook if 条件（如 {@code Glob(src/*.java)}）的 ruleContent 与本次 pattern 做通配匹配。
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
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * [G9] 只读标记 · 对齐 CC {@code GlobTool.ts:79-81} {@code isReadOnly() { return true }}。
     *
     * <p>WHY: Glob 只做文件名模式匹配，无副作用（不读内容、不写盘）。标记只读可让上游
     * PermissionPipeline 跳过写权限检查、YoloClassifier 跳过写分类。
     */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /**
     * [G10] prompt · 对齐 CC {@code GlobTool.ts:143-145} {@code async prompt() { return DESCRIPTION }}。
     * CC DESCRIPTION（GlobTool/prompt.ts）= 'Find files matching a glob pattern...'，Java
     * {@link #description()} 为同源等价文本（glob 语法 + limit=100 说明）。
     */
    @Override
    public String prompt() {
        return description();
    }

    /**
     * [G9] 工具使用摘要 · 对齐 CC {@code GlobTool.ts:67 getToolUseSummary}（Glob UI）
     * —— 摘要为匹配 pattern；无 pattern → null。
     */
    @Override
    public String getToolUseSummary(java.util.Map<String, Object> processedInput) {
        Object pattern = processedInput == null ? null : processedInput.get("pattern");
        return pattern == null ? null : String.valueOf(pattern);
    }

    /**
     * [R32-b8 #2] GlobTool 是搜索操作（文件名模式匹配）· 对齐 CC
     * {@code GlobTool.ts:85-87} 返回 {@code {isSearch: true, isRead: false}}.
     *
     * <p>D1 校正：任务 brief 描述 "Glob → IS_LIST" 是误述; CC 源 GlobTool 实为 IS_SEARCH.
     */
    @Override
    public SearchReadKind searchReadKind(JsonNode input) {
        return SearchReadKind.IS_SEARCH;
    }


    @Override
    public ToolResult execute(ToolUseBlock call) {
        String pattern = call.input().path("pattern").asText("");
        String pathArg = call.input().path("path").asText("");
        if (pattern.isBlank()) return ToolResult.error(call.id(), "pattern is empty");

        Path root;
        try {
            if (pathArg.isBlank()) {
                root = guard.workdir();
            } else {
                root = guard.resolve(pathArg);
                if (!Files.isDirectory(root)) {
                    return ToolResult.error(call.id(),
                        "path is not a directory: " + pathArg);
                }
            }
        } catch (SecurityException se) {
            log.warn("GlobTool: blocked path escape: {}", pathArg);
            return ToolResult.error(call.id(), se.getMessage());
        }

        PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + pattern);

        // CC GlobTool.ts:158-164 glob() → files（rg 全遍历，无深度限制）+ relativize
        // truncated = 命中数 > offset+limit（offset=0）；files = slice(0, limit)
        List<String> matches = new ArrayList<>();
        boolean truncated;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> all = walk
                .filter(p -> !p.equals(root))
                .filter(p -> matcher.matches(p) || matcher.matches(root.relativize(p)))
                .toList();
            truncated = all.size() > DEFAULT_LIMIT;
            // CC glob.ts:126-127 truncated = len > offset+limit；files = slice(offset, offset+limit)
            List<Path> limited = all.size() > DEFAULT_LIMIT ? all.subList(0, DEFAULT_LIMIT) : all;
            Path workdir = guard.workdir();
            for (Path p : limited) {
                matches.add(workdir.relativize(p).toString());
            }
        } catch (Exception e) {
            log.error("GlobTool: walk error for pattern '{}' path='{}'", pattern, pathArg, e);
            return ToolResult.error(call.id(), "Glob error: " + e.getMessage());
        }

        // CC GlobTool.ts:177-197 mapToolResultToToolResultBlockParam
        if (matches.isEmpty()) {
            return ToolResult.success(call.id(), "No files found");
        }
        StringBuilder result = new StringBuilder(String.join("\n", matches));
        if (truncated) {
            result.append("\n(Results are truncated. Consider using a more specific path or pattern.)");
        }
        log.info("GlobTool: pattern='{}' path='{}' matches={} truncated={}", pattern, pathArg, matches.size(), truncated);
        return ToolResult.success(call.id(), result.toString());
    }
}
