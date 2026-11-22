package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.ast.AstEditException;
import com.nexusai.application.agent.ast.AstEditPlan;
import com.nexusai.application.agent.ast.AstEditStaging;
import com.nexusai.application.agent.ast.AstFileWriter;
import com.nexusai.application.agent.ast.AstGrepBinary;
import com.nexusai.application.agent.ast.AstGrepRunner;
import com.nexusai.application.agent.ast.AstMatch;
import com.nexusai.application.agent.ast.AstPermissionSupport;
import com.nexusai.application.agent.ast.AstUiPayload;
import com.nexusai.application.agent.ast.PlatformKey;
import com.nexusai.application.agent.ast.ProcessAstGrepCli;
import com.nexusai.application.agent.bash.BashRuleMatcher;
import com.nexusai.application.agent.file.FileHistoryService;
import com.nexusai.application.agent.lsp.LspDiagnosticRegistry;
import com.nexusai.application.agent.lsp.LspManager;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ReadPermissionChecker;
import com.nexusai.application.agent.permission.WritePermissionChecker;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.session.SessionFilesRecorder;
import com.nexusai.domain.settings.AstToolsConfig;
import com.nexusai.infra.util.SemanticBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * AstEdit · 按 AST 结构批量改写（ast-grep CLI 做匹配，Java 做字节级合并与写盘）。
 *
 * <h2>两段式（预览 → 落地）</h2>
 * <ol>
 *   <li>{@code apply} 缺省 / false：只产出 diff，<b>不改文件</b>；同时把「每文件 SHA-256 + 逐条变更」
 *       暂存到 {@link AstEditStaging}。</li>
 *   <li>{@code apply:true}：必须命中同一 {@code ops/lang/paths} 的暂存（否则拒绝），重新计算并
 *       逐文件比对指纹与变更列表——文件在预览后被改过就报 stale 且<b>一个字节都不写</b>。</li>
 * </ol>
 *
 * <h2>为什么不做 read-before-write</h2>
 * 多文件 codemod 无法要求逐文件先 Read（模型不可能为了改 50 个调用点逐个读文件）。安全保障改由
 * 四件事承担：预览 diff、显式 {@code apply:true}、权限层 {@link PermissionResult.Ask}（写盘升级）、
 * 以及预览指纹比对（stale 检查）。这是与 {@code Edit}（单文件、必须读过才能改）<b>有意</b>的偏离。
 *
 * <h2>写盘</h2>
 * 按 UTF-8 字节区间对<b>同一份原始字节</b>一次性拼接（{@link AstEditPlan#applyToBytes}），
 * 临时文件 + 原子移动落盘（{@link AstFileWriter}）；BOM / CRLF 原样保留。
 */
@Component
public class AstEditTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AstEditTool.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单个 op 的匹配上限（超过要求收窄；防一次 codemod 把整仓写花）。 */
    private static final int MAX_MATCHES_PER_OP = 2_000;

    /** 影响文件数上限。 */
    private static final int MAX_FILES = 1_000;

    /** ops 条数上限。 */
    private static final int MAX_OPS = 10;

    /** pat/out 长度上限（Windows 命令行安全余量）。 */
    private static final int MAX_PATTERN_CHARS = 4_000;

    /** 模型面 warnings 最多展示条数。 */
    private static final int MAX_WARNINGS_SHOWN = 5;

    private final PathGuard guard;
    private final AstGrepRunner runner;
    private AstEditStaging staging;

    @Autowired(required = false)
    private ReadPermissionChecker readChecker;

    @Autowired(required = false)
    private WritePermissionChecker writeChecker;

    @Autowired(required = false)
    private FileHistoryService fileHistoryService;

    @Autowired(required = false)
    private SessionFilesRecorder sessionFilesRecorder;

    @Autowired(required = false)
    private LspManager lspManager;

    /** 测试/装配用 setter。 */
    public void setReadChecker(ReadPermissionChecker readChecker) {
        this.readChecker = readChecker;
    }

    public void setWriteChecker(WritePermissionChecker writeChecker) {
        this.writeChecker = writeChecker;
    }

    public void setFileHistoryService(FileHistoryService fileHistoryService) {
        this.fileHistoryService = fileHistoryService;
    }

    public void setSessionFilesRecorder(SessionFilesRecorder sessionFilesRecorder) {
        this.sessionFilesRecorder = sessionFilesRecorder;
    }

    public void setLspManager(LspManager lspManager) {
        this.lspManager = lspManager;
    }

    /** 预览暂存 bean（生产由 Spring 注入；测试走构造器传入独立实例）。 */
    @Autowired(required = false)
    public void setStaging(AstEditStaging staging) {
        if (staging != null) {
            this.staging = staging;
        }
    }

    @Autowired
    public AstEditTool(PathGuard guard) {
        this(guard, new AstGrepRunner(new ProcessAstGrepCli()), new AstEditStaging());
    }

    /** 测试注入构造器（fake runner + 独立暂存）。 */
    public AstEditTool(PathGuard guard, AstGrepRunner runner, AstEditStaging staging) {
        if (guard == null) throw new IllegalArgumentException("guard is null");
        if (runner == null) throw new IllegalArgumentException("runner is null");
        if (staging == null) throw new IllegalArgumentException("staging is null");
        this.guard = guard;
        this.runner = runner;
        this.staging = staging;
    }

    @Override
    public String name() {
        return ToolNameConstants.AST_EDIT_TOOL_NAME;
    }

    @Override
    public String description() {
        return prompt();
    }

    @Override
    public String prompt() {
        return "Rewrite code by AST structure (ast-grep). Input: ops[{pat,out}] (1..10, unique pats) and "
            + "paths[]. Same pattern language as AstGrep: $NAME binds one node, $_ matches without binding, "
            + "$$$NAME binds zero or more, $$$ matches zero or more without binding; a pattern must be a single "
            + "AST node. out must be structurally 1:1 with pat (no splitting/merging captures) and use the same "
            + "metavariables; delete by passing out: \"\". "
            + "Default is PREVIEW: nothing is written and a diff is returned. Review the diff, then call again "
            + "with the identical ops/paths and apply: true to write. If a previewed file changes before apply, "
            + "the edit is rejected as stale - re-preview. Overlapping replacements are rejected (refine the "
            + "pattern). Language is inferred per file; pass lang only to disambiguate (e.g. cpp for .h) and note "
            + "it then restricts the scan to that language.";
    }

    @Override
    public boolean isEnabled() {
        return AstGrepBinary.available();
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return !semanticBool(input, "apply", false);
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    @Override
    public SearchReadKind searchReadKind(JsonNode input) {
        return SearchReadKind.NONE;
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
        return "structural AST rewrite (ast-grep codemod)";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");

        ObjectNode ops = props.putObject("ops");
        ops.put("type", "array");
        ops.put("minItems", 1);
        ObjectNode items = ops.putObject("items");
        items.put("type", "object");
        items.put("additionalProperties", false);
        ObjectNode itemProps = items.putObject("properties");
        itemProps.putObject("pat").put("type", "string")
            .put("description", "AST pattern to match (e.g. foo($A))");
        itemProps.putObject("out").put("type", "string")
            .put("description", "Replacement (e.g. bar($A)); empty string deletes the match");
        ArrayNode itemRequired = items.putArray("required");
        itemRequired.add("pat");
        itemRequired.add("out");

        ObjectNode paths = props.putObject("paths");
        paths.put("type", "array");
        paths.put("minItems", 1);
        ObjectNode pathItems = paths.putObject("items");
        pathItems.put("type", "string");
        paths.put("description", "Files or directories to rewrite");

        props.putObject("lang").put("type", "string")
            .put("description", "Language override for ambiguous extensions (e.g. cpp for .h). When set, only files of that language are scanned.");
        props.putObject("apply").put("type", "boolean").put("x-semantic-boolean", true)
            .put("description", "true writes the changes; default false only previews a diff");

        ArrayNode required = schema.putArray("required");
        required.add("ops");
        required.add("paths");
        return schema;
    }

    @Override
    public String getPath(JsonNode input) {
        if (input == null) {
            return null;
        }
        String path = input.path("path").asText(null);
        if (path != null && !path.isBlank()) {
            return path;
        }
        return firstPath(input.path("paths"));
    }

    @Override
    public Predicate<String> preparePermissionMatcher(JsonNode input) {
        if (input == null) {
            return rule -> false;
        }
        List<String> pats = new ArrayList<>();
        JsonNode ops = input.path("ops");
        if (ops.isArray()) {
            for (JsonNode op : ops) {
                String pat = op.path("pat").asText(null);
                if (pat != null && !pat.isEmpty()) {
                    pats.add(pat);
                }
            }
        }
        return rule -> {
            for (String pat : pats) {
                if (BashRuleMatcher.matchWildcardPattern(rule, pat)) {
                    return true;
                }
            }
            return false;
        };
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        boolean apply = semanticBool(input, "apply", false);
        List<String> paths = allPaths(input == null ? null : input.path("paths"));
        String askMessage = "AstEdit will rewrite " + paths.size() + " file(s): "
            + String.join(", ", paths) + ". Apply?";
        return AstPermissionSupport.strictest(this, ctx, paths, readChecker, writeChecker, apply, askMessage,
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

        // ── 1. ops 校验 ──
        JsonNode opsNode = input == null ? null : input.path("ops");
        if (opsNode == null || !opsNode.isArray() || opsNode.isEmpty()) {
            return ToolResult.error(call.id(), "Error: ops must contain at least one {pat,out} entry");
        }
        if (opsNode.size() > MAX_OPS) {
            return ToolResult.error(call.id(),
                "Error: too many ops (" + opsNode.size() + " > " + MAX_OPS + ")");
        }
        List<Map<String, String>> ops = new ArrayList<>();
        Set<String> pats = new LinkedHashSet<>();
        for (int i = 0; i < opsNode.size(); i++) {
            JsonNode op = opsNode.get(i);
            String pat = op.hasNonNull("pat") ? op.get("pat").asText() : null;
            if (pat == null || pat.trim().isEmpty()) {
                return ToolResult.error(call.id(),
                    "Error: ops[" + i + "].pat must be a non-empty AST pattern");
            }
            if (pat.length() > MAX_PATTERN_CHARS) {
                return ToolResult.error(call.id(), "Error: pat is too long (max " + MAX_PATTERN_CHARS + " chars)");
            }
            JsonNode outNode = op.get("out");
            if (outNode == null || outNode.isNull() || !outNode.isTextual()) {
                return ToolResult.error(call.id(), "Error: ops[" + i + "].out must be a string");
            }
            String out = outNode.asText();
            if (out.length() > MAX_PATTERN_CHARS) {
                return ToolResult.error(call.id(),
                    "Error: ops[" + i + "].out is too long (max " + MAX_PATTERN_CHARS + " chars)");
            }
            if (!pats.add(pat)) {
                return ToolResult.error(call.id(), "Error: duplicate rewrite pattern: " + pat);
            }
            ops.add(Map.of("pat", pat, "out", out));
        }

        // ── 2. paths 校验与解析 ──
        List<String> rawPaths = allPaths(input == null ? null : input.path("paths"));
        if (rawPaths.isEmpty()) {
            return ToolResult.error(call.id(), "Error: paths must include at least one non-empty path");
        }
        String sessionId = ctx == null ? null : ctx.sessionId();
        Path workdir = guard.workdir(sessionId);
        List<String> absTargets = new ArrayList<>();
        for (String raw : rawPaths) {
            Path resolved;
            try {
                resolved = guard.resolve(sessionId, raw);
            } catch (Exception e) {
                return ToolResult.error(call.id(), "Error: path not found: " + raw);
            }
            if (!Files.exists(resolved)) {
                return ToolResult.error(call.id(), "Error: path not found: " + raw);
            }
            absTargets.add(resolved.toAbsolutePath().normalize().toString());
        }
        String lang = input.hasNonNull("lang") ? trimToNull(input.get("lang").asText()) : null;

        AbortController abort = (ctx != null && ctx.abortController() != null)
            ? ctx.abortController() : AbortController.NOOP;

        // ── 3. 逐 op 调 CLI 并合并 ──
        List<AstMatch> all = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        int filesSearched = 0;
        for (Map<String, String> op : ops) {
            if (abort.isCancelled()) {
                return ToolResult.error(call.id(), "Error: AstEdit aborted by user");
            }
            AstGrepRunner.All result;
            try {
                result = runner.findAll(
                    new AstGrepRunner.Request(op.get("pat"), absTargets, lang, workdir, AstToolsConfig.TIMEOUT_MS),
                    MAX_MATCHES_PER_OP, op.get("out"), abort);
            } catch (AstGrepRunner.AstGrepException e) {
                log.warn("[AstEdit] 执行失败 pattern={} kind={} msg={}", op.get("pat"), e.kind(), e.getMessage());
                return ToolResult.error(call.id(), "Error: " + e.getMessage());
            }
            if (result.exceededCap()) {
                return ToolResult.error(call.id(), "Error: too many matches ("
                    + (result.totalMatches() + 1) + " > " + MAX_MATCHES_PER_OP + ") for pattern "
                    + op.get("pat") + "; narrow paths or refine the pattern");
            }
            all.addAll(result.matches());
            warnings.addAll(result.warnings());
            filesSearched = Math.max(filesSearched, result.filesSearched());
        }

        List<AstEditPlan> plans;
        try {
            plans = AstEditPlan.merge(all);
        } catch (AstEditException e) {
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }
        if (plans.size() > MAX_FILES) {
            return ToolResult.error(call.id(),
                "Error: too many files touched (" + plans.size() + " > " + MAX_FILES + "); narrow paths");
        }

        boolean apply = semanticBool(input, "apply", false);
        String tailWarnings = renderWarnings(warnings);
        String signature = AstEditStaging.signature(ops, lang, absTargets);

        // ── 4. 零变更 ──
        //  apply=true 且没有匹配时仍要判一次「预览后文件是否被改过」：预览时能匹配、落地时匹配不到，
        //  正是「文件已被改动」的典型形态（如手工把目标代码删掉），报 stale 比报「无变更」更诚实。
        if (plans.isEmpty()) {
            if (apply && staging.take(sessionId, signature) != null) {
                return ToolResult.error(call.id(), "Error: preview is stale; files changed since the preview. "
                    + "Run a fresh preview (omit apply) and retry.");
            }
            String noMatch = filesSearched == 0
                ? "No replacements made (0 files scanned: unsupported file type or all files ignored)"
                : "No replacements made";
            Map<String, Object> payload = AstUiPayload.edit(false, ops, rawPaths, List.of(), false);
            log.info("[AstEdit] pattern 数={} 零变更（扫描={}）", ops.size(), filesSearched);
            return ToolResult.successWithStructuredOutput(call.id(), noMatch + tailWarnings, payload);
        }

        Map<String, Object> payload = AstUiPayload.edit(
            apply, ops, rawPaths, displayPlans(workdir, plans), false);

        // ── 5. 预览 ──
        if (!apply) {
            try {
                staging.stage(sessionId, signature, plans);
            } catch (IOException e) {
                log.warn("[AstEdit] 预览暂存失败: {}", e.toString());
                return ToolResult.error(call.id(), "Error: apply failed: " + e);
            }
            String text = "Staged as a proposal — files NOT modified yet. Re-run AstEdit with "
                + "\"apply\": true to write the changes.\n\n" + renderDiff(workdir, plans)
                + "\nTotal: " + countChanges(plans) + " replacement(s) in " + plans.size() + " file(s)."
                + tailWarnings;
            log.info("[AstEdit] 预览 ops={} 文件={} 替换={}", ops.size(), plans.size(), countChanges(plans));
            return ToolResult.successWithStructuredOutput(call.id(), text, payload);
        }

        // ── 6. 落地：暂存校验 → 指纹比对 → 写盘 ──
        AstEditStaging.Staged staged = staging.take(sessionId, signature);
        if (staged == null) {
            return ToolResult.error(call.id(), "Error: no preview found for this exact ops/paths request; "
                + "call AstEdit without apply first, then apply:true.");
        }
        Map<Path, byte[]> originals = new java.util.LinkedHashMap<>();
        // 预览里存在、apply 时却不再出现在结果中的文件（被删/改名/模式不再命中）同样是 stale：
        // 只遍历当前 plans 会把它们的改动静默丢掉（预览 a+b，apply 只写 a 还报成功）。
        java.util.Set<String> currentFiles = new java.util.HashSet<>();
        for (AstEditPlan plan : plans) {
            currentFiles.add(plan.file());
        }
        if (!currentFiles.containsAll(staged.sha256ByFile().keySet())) {
            return ToolResult.error(call.id(), "Error: preview is stale; files changed since the preview. "
                + "Run a fresh preview (omit apply) and retry.");
        }
        for (AstEditPlan plan : plans) {
            Path file = Path.of(plan.file());
            try {
                AstFileWriter.requireRegularFile(file);
                byte[] bytes = Files.readAllBytes(file);
                originals.put(file, bytes);
                String actual = AstEditStaging.sha256(bytes);
                String expected = staged.sha256ByFile().get(plan.file());
                List<AstEditPlan.Change> stagedChanges = staged.changesByFile().get(plan.file());
                if (expected == null || !expected.equals(actual) || !sameChanges(stagedChanges, plan.changes())) {
                    return ToolResult.error(call.id(), "Error: preview is stale; files changed since the preview. "
                        + "Run a fresh preview (omit apply) and retry.");
                }
            } catch (IOException e) {
                return ToolResult.error(call.id(), "Error: apply failed: " + e);
            }
        }
        int written = 0;
        for (AstEditPlan plan : plans) {
            Path file = Path.of(plan.file());
            byte[] original = originals.get(file);
            byte[] updated;
            try {
                updated = AstEditPlan.applyToBytes(original, plan.changes());
                trackHistory(ctx, file);
                AstFileWriter.requireRegularFile(file);
                AstFileWriter.writeAtomic(file, updated);
                written++;
            } catch (AstEditException e) {
                return ToolResult.error(call.id(), "Error: " + e.getMessage());
            } catch (IOException e) {
                log.warn("[AstEdit] 写盘失败 file={} cause={}", file, e.toString());
                return ToolResult.error(call.id(), "Error: apply failed: " + e);
            }
            recordSessionFile(ctx, workdir, file, original, updated);
            notifyLspChange(file, updated);
        }
        int total = countChanges(plans);
        String text = "Applied " + total + " replacement(s) in " + written + " file(s).\n\n"
            + renderDiff(workdir, plans) + "\n\nTotal: " + total + " replacement(s) in " + written + " file(s)."
            + tailWarnings;
        log.info("[AstEdit] 落地 ops={} 文件={} 替换={}", ops.size(), written, total);
        return ToolResult.successWithStructuredOutput(call.id(), text,
            AstUiPayload.edit(true, ops, rawPaths, displayPlans(workdir, plans), false));
    }

    // ───────────────────────── 渲染 ─────────────────────────

    /** diff 段：逐文件 `path (N replacements)` + `- 行:列 before` / `+ 行:列 after`。 */
    static String renderDiff(Path workdir, List<AstEditPlan> plans) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plans.size(); i++) {
            AstEditPlan plan = plans.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(AstGrepTool.toDisplayPath(workdir, plan.file()))
                .append(" (").append(plan.changes().size()).append(" replacements)\n");
            for (AstEditPlan.Change c : plan.changes()) {
                appendDiffLine(sb, "- ", c.line(), c.column(), c.before());
                appendDiffLine(sb, "+ ", c.line(), c.column(), c.after());
            }
        }
        return sb.toString();
    }

    private static void appendDiffLine(StringBuilder sb, String sign, int line, int column, String text) {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        sb.append("  ").append(sign).append(line).append(':').append(column).append("  ").append(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            sb.append('\n').append("      ").append(lines[i]);
        }
        sb.append('\n');
    }

    /** warnings 尾巴（去重后最多 5 条）。 */
    static String renderWarnings(Set<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nWarnings:");
        int shown = 0;
        for (String w : warnings) {
            if (shown >= MAX_WARNINGS_SHOWN) {
                break;
            }
            sb.append("\n- ").append(w);
            shown++;
        }
        return sb.toString();
    }

    // ───────────────────────── 写盘副作用链 ─────────────────────────

    /** 写前备份（fail-soft）· 与 EditFileTool 同义：messageId 优先取 toolUseId。 */
    private void trackHistory(ToolUseContext ctx, Path file) {
        if (fileHistoryService == null) {
            return;
        }
        try {
            String messageId = ctx == null ? null
                : (ctx.toolUseId() != null && !ctx.toolUseId().isBlank()
                    ? ctx.toolUseId() : ctx.sessionId());
            String sessionId = ctx == null ? null : ctx.sessionId();
            fileHistoryService.trackEdit(file.toAbsolutePath().normalize().toString(), messageId, sessionId);
        } catch (Exception e) {
            log.warn("AstEditTool: fileHistory trackEdit 失败: {} cause={}", file, e.toString());
        }
    }

    /** 会话改动文件记录（右栏「文件」tab + files.changed 推送）· fail-soft。 */
    private void recordSessionFile(ToolUseContext ctx, Path workdir, Path file, byte[] before, byte[] after) {
        if (sessionFilesRecorder == null) {
            return;
        }
        try {
            String sessionId = ctx == null ? null : ctx.sessionId();
            String abs = file.toAbsolutePath().normalize().toString();
            sessionFilesRecorder.record(sessionId, AstGrepTool.toDisplayPath(workdir, abs), abs,
                new String(before, StandardCharsets.UTF_8), new String(after, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("AstEditTool: sessionFiles record 失败: {} cause={}", file, e.toString());
        }
    }

    /** LSP 通知（清诊断 → changeFile → saveFile）· fail-soft，仿 EditFileTool.notifyLspChange。 */
    private void notifyLspChange(Path file, byte[] updated) {
        if (lspManager == null) {
            return;
        }
        try {
            String absolutePath = file.toAbsolutePath().normalize().toString();
            LspDiagnosticRegistry.clearDeliveredDiagnosticsForFile("file://" + absolutePath);
            String text = new String(updated, StandardCharsets.UTF_8);
            lspManager.changeFile(absolutePath, text);
            lspManager.saveFile(absolutePath);
        } catch (Exception e) {
            log.warn("AstEditTool: LSP 通知失败: {} cause={}", file, e.toString());
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** 载荷里的计划换展示路径（相对会话工作目录）。 */
    private static List<AstEditPlan> displayPlans(Path workdir, List<AstEditPlan> plans) {
        List<AstEditPlan> out = new ArrayList<>(plans.size());
        for (AstEditPlan plan : plans) {
            out.add(new AstEditPlan(AstGrepTool.toDisplayPath(workdir, plan.file()), plan.changes()));
        }
        return out;
    }

    private static boolean sameChanges(List<AstEditPlan.Change> a, List<AstEditPlan.Change> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            AstEditPlan.Change x = a.get(i);
            AstEditPlan.Change y = b.get(i);
            if (x.startByte() != y.startByte() || x.endByte() != y.endByte()
                || !x.after().equals(y.after())) {
                return false;
            }
        }
        return true;
    }

    private static int countChanges(List<AstEditPlan> plans) {
        int n = 0;
        for (AstEditPlan plan : plans) {
            n += plan.changes().size();
        }
        return n;
    }

    /** {@code paths} 数组 → trim 后的非空串列表。 */
    static List<String> allPaths(JsonNode pathsNode) {
        List<String> out = new ArrayList<>();
        if (pathsNode == null || !pathsNode.isArray()) {
            return out;
        }
        for (JsonNode n : pathsNode) {
            String t = n.isTextual() ? n.asText().trim() : "";
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String firstPath(JsonNode pathsNode) {
        List<String> paths = allPaths(pathsNode);
        return paths.isEmpty() ? null : paths.get(0);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** semantic boolean 读取（接受 JSON boolean 或 "true"/"false" 字符串）。 */
    private static boolean semanticBool(JsonNode input, String key, boolean defaultValue) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return defaultValue;
        }
        JsonNode node = input.get(key);
        Object raw = node.isBoolean() ? node.booleanValue() : node.asText();
        return SemanticBoolean.parseBooleanOrDefault(raw, defaultValue);
    }
}
