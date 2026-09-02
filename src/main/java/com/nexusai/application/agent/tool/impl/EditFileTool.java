package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.CountLinesChanged;
import com.nexusai.application.agent.tool.EditMatchEngine;
import com.nexusai.application.agent.tool.GitDiffFetcher;
import com.nexusai.application.agent.bash.BashRuleMatcher;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.StructuredPatchGenerator;
import com.nexusai.application.agent.tool.StructuredPatchHunk;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseContext.ReadState;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultStorage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseDiff;
import com.nexusai.application.agent.file.FileHistoryService;
import com.nexusai.application.agent.lsp.LspDiagnosticRegistry;
import com.nexusai.application.agent.lsp.LspManager;
import com.nexusai.infra.util.FileEncodingReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.Map;

/**
 * Edit File 工具 · 对齐 CC {@code FileEditTool.ts}（{@code src/tools/FileEditTool/}）。
 *
 * <p>替换文件中的精确文本。生产级特性：
 * <ul>
 *   <li><b>{@code replace_all=false}</b>（默认）：{@code old_string} 出现 0 次 → 错误；多次 → 错误（避免 LLM 误改）</li>
 *   <li><b>{@code replace_all=true}</b>：替换文件中所有出现位置；0 次 → 错误</li>
 *   <li><b>PathGuard</b>：路径逃逸 → 错误</li>
 *   <li><b>concurrency-unsafe</b>：写操作 → false</li>
 * </ul>
 *
 * <p>对齐 CC FileEditTool.ts 的 {@code file_path}/{@code old_string}/{@code new_string}/{@code replace_all}
 * 四参 schema（IMP-D2 键名对齐）。{@code unique-match only} 是 s02 教学版简化，与 CC 默认行为一致。
 */
@Component
public class EditFileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(EditFileTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // CC original: MAX_EDIT_FILE_SIZE = 1 GiB (FileEditTool.ts:84) — validateInput errorCode 10
    // 前置闸, 防多 GB 文件 OOM. 以磁盘 stat bytes 计 (对齐 CC fs.stat().size).
    private static final long MAX_EDIT_FILE_SIZE = 1024L * 1024 * 1024;

    // CC original: FILE_NOT_FOUND_CWD_NOTE = 'Note: your current working directory is' (utils/file.ts:213)
    // errorCode 4 建议文案尾部. Java 无 findSimilarFile/suggestPathUnderCwd 等价, 输出基础文案 (见 E2 concerns).
    private static final String FILE_NOT_FOUND_CWD_NOTE = "Note: your current working directory is";

    // [G33①/OPD-D2-07] CC original: FILE_UNEXPECTEDLY_MODIFIED_ERROR
    // （FileEditTool/constants.ts:10-11）——call() 内 stale 复检（FileEditTool.ts:465
    //   throw new Error(FILE_UNEXPECTEDLY_MODIFIED_ERROR)）文案逐字一致。
    private static final String FILE_UNEXPECTEDLY_MODIFIED_ERROR =
        "File has been unexpectedly modified. Read it again before attempting to write it.";

    private final PathGuard guard;

    // IMP-M-P2-4: auto-memory 路径解析（写 carve-out 第二分支 · 对齐 CC filesystem.ts:1565-1581
    //   checkEditableInternalPath memdir carve-out）。@Autowired(required=false)：无 bean 跳过。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths;
    public void setAutoMemPaths(com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths) {
        this.autoMemPaths = autoMemPaths;
    }

    // IMP-M-P2-2b: agent-memory 路径解析（写 carve-out 第一分支 · 对齐 CC filesystem.ts:1554-1562
    //   checkEditableInternalPath isAgentMemoryPath 预检查，先于 memdir carve-out :1565）。
    //   @Autowired(required=false)：无 bean 跳过（POJO 测试不破）。由 Spring 注入
    //   （ToolRegistrationConfig @Bean productionDefault，config:473-475）。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory;
    public void setAgentMemoryDirectory(com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory) {
        this.agentMemoryDirectory = agentMemoryDirectory;
    }

    public EditFileTool(PathGuard guard) {
        if (guard == null) throw new IllegalArgumentException("guard is null");
        this.guard = guard;
    }

    /**
     * [IMPL-09] 写权限检查器 · 对齐 CC FileEditTool.checkPermissions
     * (FileEditTool.ts) 委托 checkWritePermissionForTool。
     *
     * <p>WHY: 与 {@code GlobTool} 同因 — 旧 6 hook 链对 edit_file 的 1c/1g/2b'/
     * DONT_ASK 语义随删除收敛到管线，Java EditFileTool 无 checkPermissions override
     * → 默认 Allow 短路 1c。缺失时 fail-loud（对齐 ReadFileTool:312-317，Pattern #11）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.permission.WritePermissionChecker permissionChecker;

    /** 测试/装配用 setter · 与 ReadFileTool 同模式（构造器保留旧 API）。 */
    public void setPermissionChecker(com.nexusai.application.agent.permission.WritePermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    /**
     * [IMPL-09] checkPermissions · 写权限检查（CC checkWritePermissionForTool 等价，
     * filesystem.ts:1205-1412）。
     *
     * <p>顺序对齐 CC 决策链（deny 步骤1 先于 carve-out 步骤1.5）：
     * <ol>
     *   <li><b>步骤 1 deny 规则</b>（CC filesystem.ts:1219-1239）——先查显式 Edit deny 规则
     *       （{@link WritePermissionChecker#checkDeny}），命中 → Deny（deny 优先于 carve-out；
     *       memory 路径 + Edit deny 规则并发时 CC=Deny，本重排对齐）。</li>
     *   <li><b>步骤 1.5 carve-out</b>（CC filesystem.ts:1554-1623 checkEditableInternalPath）——
     *       agent-memory / auto-memory 路径静默 allow（IMP-M-P2-4 + IMP-M-P2-2b + OPD-M-48；
     *       OPD-M-48：默认路径在 ~/.claude/ 属 DANGEROUS_DIRECTORIES，必须静默放行否则
     *       每次写记忆文件都弹窗；override 时目录无此冲突 → 不做特殊放行）。</li>
     *   <li>其余步骤委托 {@link WritePermissionChecker#check}（内部 deny 幂等重算，语义一致）。</li>
     * </ol>
     */
    @Override
    public com.nexusai.application.agent.permission.PermissionResult checkPermissions(
            JsonNode input, ToolUseContext ctx) {
        // 步骤 1 deny 规则（CC filesystem.ts:1219-1239）先于 carve-out（:1241-1250）。
        // 仅当 permissionChecker 已注入才有 deny 检查能力；未注入时跳过（carve-out 仍可放行）。
        if (permissionChecker != null) {
            com.nexusai.application.agent.permission.PermissionResult deny =
                permissionChecker.checkDeny(this, input, ctx);
            if (deny != null) {
                return deny;
            }
        }
        if (input != null) {
            String relPath = input.path("file_path").asText("");
            if (!relPath.isBlank()) {
                try {
                    Path file = guard.resolve(relPath);
                    String absolute = file.toAbsolutePath().normalize().toString();
                    // IMP-M-P2-2b: agent-memory 写 carve-out（对齐 CC filesystem.ts:1554-1562
                    //   checkWritePermissionForTool 的 isAgentMemoryPath 预检查）：
                    //   CC 置于 memdir carve-out 之前（filesystem.ts:1554 isAgentMemoryPath → 1565 memdir）。
                    //   无 isAutoMemoryEnabled 门控（isAgentMemoryPath 是纯路径判定，agentMemory.ts:67-104）。
                    if (agentMemoryDirectory != null && agentMemoryDirectory.isAgentMemoryPath(absolute)) {
                        if (log.isDebugEnabled()) {
                            log.debug("EditFileTool: agent-memory 写 carve-out 静默 allow (IMP-M-P2-2b): {}",
                                relPath);
                        }
                        return new com.nexusai.application.agent.permission.PermissionResult.Allow(
                            input,
                            new com.nexusai.application.agent.permission.PermissionDecisionReason.Other(
                                "Agent memory files are allowed for writing"),
                            null, false, null, java.util.List.of());
                    }
                    // OPD-M-48: auto-memory 写 carve-out（对齐 CC filesystem.ts:1565-1581
                    //   checkEditableInternalPath 的 memdir carve-out；hasAutoMemPathOverride
                    //   时不做特殊放行，filesystem.ts:1566-1571 注释）。
                    if (autoMemPaths != null && !autoMemPaths.hasAutoMemPathOverride() && autoMemPaths.isAutoMemPath(absolute)) {
                        if (log.isDebugEnabled()) {
                            log.debug("EditFileTool: auto-memory 写 carve-out 静默 allow (OPD-M-48): {}",
                                relPath);
                        }
                        return new com.nexusai.application.agent.permission.PermissionResult.Allow(
                            input,
                            new com.nexusai.application.agent.permission.PermissionDecisionReason.Other(
                                "auto memory files are allowed for writing"),
                            null, false, null, java.util.List.of());
                    }
                } catch (SecurityException se) {
                    if (log.isDebugEnabled()) {
                        log.debug("EditFileTool: carve-out 路径逃逸跳过: {}", relPath);
                    }
                }
            }
        }
        // 非 carve-out 路径 → 委托 WritePermissionChecker（CC checkWritePermissionForTool 主体）
        if (permissionChecker == null) {
            throw new IllegalStateException(
                "permissionChecker 未注入, 无法执行 edit 写权限检查");
        }
        return permissionChecker.check(this, input, ctx);
    }

    /**
     * 暴露 PathGuard · [L+ round 4] 让同一工作目录的跨工具消费者 (MagicDocUpdater.writeViaEditTool)
     * 用同一 guard 派生 readFileState key. WHY 必须用同一 guard: 不同 workspace
     * (eg 测试 TempDir vs 生产) 的 guard.resolve(relPath) 会得到不同绝对路径 → readFileState
     * key 错位 → read-before-write 门禁永远不命中.
     */
    public PathGuard pathGuard() {
        return guard;
    }

    @Override
    public String name() { return "Edit"; }

    // [IMP-C3 删除] 旧 snake_case 'edit_file' alias 已删除（DC-A2-02）：
    // CC FileEditTool.ts:87 name='Edit'（FILE_EDIT_TOOL_NAME=constants.ts:2），真源无 aliases 声明，
    // 全仓 edit_file 0 命中。未上线可破约（决策清单 组2-2）。不保留兼容壳。

    @Override
    public String description() {
        return "Replace text in a file. By default old_string must appear exactly once; " +
               "set replace_all=true to substitute every occurrence. Returns an error when " +
               "old_string is missing or (in non-replace_all mode) appears more than once. " +
               "file_path is resolved relative to the workspace root.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        // CC FileEditTool/types.ts:7 z.strictObject → additionalProperties:false（未知键拒绝，
        //   由 ToolInputValidator:230-232 跟随广告层 UNSPECIFIED 策略逐键拒绝）
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");

        // [IMP-D2] schema 键名对齐 CC FileEditTool/types.ts:8-17 file_path/old_string/new_string
        //   （旧 Java 键 path/old_text/new_text 删除，未上线可破约）。前端契约同步见 04-plan IMP-D2。
        ObjectNode path = JSON.createObjectNode();
        path.put("type", "string");
        path.put("description", "The absolute path to the file to modify");
        properties.set("file_path", path);

        ObjectNode oldString = JSON.createObjectNode();
        oldString.put("type", "string");
        oldString.put("description", "The text to replace");
        properties.set("old_string", oldString);

        ObjectNode newString = JSON.createObjectNode();
        newString.put("type", "string");
        newString.put("description",
            "The text to replace it with (must be different from old_string)");
        properties.set("new_string", newString);

        ObjectNode replaceAll = JSON.createObjectNode();
        replaceAll.put("type", "boolean");
        replaceAll.put("default", false);
        replaceAll.put("description", "Replace all occurrences of old_string (default false)");
        properties.set("replace_all", replaceAll);

        ArrayNode required = JSON.createArrayNode();
        required.add("file_path");
        required.add("old_string");
        required.add("new_string");
        root.set("required", required);
        return root;
    }

    /**
     * 路径扩展点 · CC original: {@code getPath(input) → input.file_path}
     * （{@code FileEditTool.ts:112}）。
     *
     * <p>权限管线（CC {@code filesystem.ts:1211-1217}）用本方法取本次写入路径做权限检查。
     * Java 端 schema 字段为 {@code path}（K-4 起 Java 契约），返回 {@code input.path("file_path")}。
     *
     * @param input 工具输入（含 {@code path}）
     * @return 本次编辑的路径；缺失返回 null（等价 CC getPath 未定义 → ask）
     */
    @Override
    public String getPath(JsonNode input) {
        return input == null ? null : input.path("file_path").asText(null);
    }

    /**
     * [FIX-A backfill-observable] 观察者输入回填 · 对齐 CC {@code FileEditTool.ts:116-120}
     * {@code backfillObservableInput}：{@code if (typeof input.file_path === 'string')
     * input.file_path = expandPath(input.file_path)}。
     *
     * <p>hooks.mdx 约定 file_path 为绝对路径；在 hook/canUseTool 观察前把 {@code path}
     * 展开为绝对路径（~ → 家目录、相对 → workspace 根），防 {@code ~}/相对路径绕过
     * hook allowlist（CC FileEditTool.ts:117-118 注释语义）。Java 端 schema 键为 {@code path}
     * （K-4 起 Java 契约，CC 键为 {@code file_path}），故读 {@code input.path("file_path")}。
     *
     * <p><b>幂等 + 非抛异常</b>（CC Tool.ts:475-484 契约）：绝对路径（展开后不变）或缺
     * 路径字段 → 返回原引用；null 字节/非法输入 → 返回原引用（不阻断工具执行）。
     * 调用方 {@link com.nexusai.application.agent.permission.InputSanitizer#backfill}
     * 已做防御性 deepCopy，原 input 永不被 in-place 改动。
     */
    @Override
    public JsonNode backfillObservableInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            return input;
        }
        JsonNode pathNode = input.get("file_path");
        if (pathNode == null || !pathNode.isTextual()) {
            return input;  // 缺 path 字段 → 返回原引用（幂等，CC typeof 非 string 跳过）
        }
        String raw = pathNode.asText();
        String expanded;
        try {
            expanded = PathGuard.expandPath(raw, guard.workdir().toString());
        } catch (IllegalArgumentException e) {
            // null 字节等非法输入 → 返回原引用（backfill 阶段不阻断工具）
            if (log.isDebugEnabled()) {
                log.debug("EditFileTool.backfillObservableInput: 路径展开失败返回原引用: path={} cause={}",
                    raw, e.getMessage());
            }
            return input;
        }
        if (expanded.equals(raw)) {
            return input;  // 已绝对/归一化不变 → 返回原引用（幂等，非抛异常）
        }
        ObjectNode copy = input.deepCopy();
        copy.put("file_path", expanded);
        if (log.isDebugEnabled()) {
            log.debug("EditFileTool.backfillObservableInput: path 绝对化 {} → {} (CC FileEditTool.ts:116-120)",
                raw, expanded);
        }
        return copy;
    }

    /**
     * 自动分类器输入 · 对齐 CC {@code FileEditTool.ts:109-111}
     * {@code toAutoClassifierInput(input) { return `${input.file_path}: ${input.new_string}` }}。
     *
     * <p><b>[IMP-D2] 键对齐</b>：CC schema 键 {@code file_path}/{@code new_string}
     * （{@code FileEditTool.ts:109}），Java 已对齐同键（旧 {@code path}/{@code new_text}
     * 删除）。投影 {@code file_path: new_string}，格式语义与 CC 一致。</p>
     *
     * <p>[OPD-24 G1] 接线：Edit 是高安全相关工具，若未 override 走默认 {@code ''}
     * （CC Tool.ts:767）→ auto-mode 空串短路 ALLOW（yoloClassifier.ts:411/:1021-1024），
     * 编辑操作不被分类 —— G6 阻断安全缺口。本投影让分类器拿到「路径 + 新文本片段」。</p>
     *
     * @param input 工具输入（含 {@code file_path}/{@code new_string}）
     * @return {@code file_path: new_string}；缺失 → {@code ''}
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        if (input == null) {
            return "";
        }
        String path = input.path("file_path").asText("");
        if (path.isEmpty()) {
            // 安全相关载体（路径）缺失 = 无安全相关性 → 空串跳过转录（CC 空串语义）。
            // 偏离说明：CC FileEditTool.ts:110 对缺失 file_path 会插值出 "undefined: ..."
            // （JS artifact），Java 端显式归空避免把垃圾块送进分类器。
            return "";
        }
        String newString = input.path("new_string").asText("");
        String projection = path + ": " + newString;
        if (log.isDebugEnabled()) {
            log.debug("EditFileTool.toAutoClassifierInput: file_path:new_string 投影完成, 长度={} (CC FileEditTool.ts:109-111)",
                projection.length());
        }
        return projection;
    }

    /**
     * 权限规则内容匹配器 · CC original: {@code preparePermissionMatcher}
     * （{@code FileEditTool.ts:122}）→ {@code pattern => matchWildcardPattern(pattern, file_path)}。
     *
     * <p>hook if 条件（如 {@code Edit(/abs/src/*.java)}）的 ruleContent 与本次 path 做通配匹配。
     *
     * @param input 工具输入（含 {@code path}）
     * @return 内容匹配谓词（pattern → boolean）
     */
    @Override
    public Predicate<String> preparePermissionMatcher(JsonNode input) {
        String path = input == null ? null : input.path("file_path").asText(null);
        return pattern -> path != null && BashRuleMatcher.matchWildcardPattern(pattern, path);
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /**
     * 严格模式 · 对齐 CC {@code FileEditTool.ts:90 strict: true}（buildTool 配置块相邻三行之一）。
     * 严格模式下 API 更严格遵循工具指令与参数 schema，模型不可注入额外字段。
     */
    @Override
    public boolean strict() {
        return true;
    }

    /**
     * 搜索提示 · 对齐 CC {@code FileEditTool.ts:88 searchHint = 'modify file contents in place'}。
     * 供 ToolSearch 关键词匹配（CC Tool.ts:378 可选字段，3-10 词、无尾句号约束）。
     */
    @Override
    public String searchHint() {
        return "modify file contents in place";
    }

    /**
     * [G9] 工具使用摘要 · 对齐 CC {@code FileEditTool.ts:98 getToolUseSummary}
     * （UI.tsx:46-56：无 file_path → null；否则 getDisplayPath(file_path)）。
     */
    @Override
    public String getToolUseSummary(java.util.Map<String, Object> processedInput) {
        Object fp = processedInput == null ? null : processedInput.get("file_path");
        return fp == null ? null : String.valueOf(fp);
    }

    /**
     * [G10] prompt · 对齐 CC {@code FileEditTool.ts:94-96 async prompt() { return getEditToolDescription() }}
     * （FileEditTool/prompt.ts:8-28 getDefaultEditDescription）。
     *
     * <p>prefixFormat：CC {@code isCompactLinePrefixEnabled()}（killswitch off 默认 compact →
     * 'line number + tab'）；Java 沿用 compact 默认。minimalUniquenessHint：CC 仅
     * {@code USER_TYPE === 'ant'} 附加，Java Web 后端恒空。行号前缀指示与 pre-read 指示逐字一致。
     */
    @Override
    public String prompt() {
        return "Performs exact string replacements in files.\n"
            + "\n"
            + "Usage:\n"
            + "- You must use your `"
            + com.nexusai.application.agent.tool.ToolNameConstants.FILE_READ_TOOL_NAME
            + "` tool at least once in the conversation before editing. This tool will error if you attempt an edit without reading the file. \n"
            + "- When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) "
            + "as it appears AFTER the line number prefix. The line number prefix format is: line number + tab. "
            + "Everything after that is the actual file content to match. Never include any part of the line "
            + "number prefix in the old_string or new_string.\n"
            + "- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.\n"
            + "- Only use emojis if the user explicitly requests it. Avoid adding emojis to files unless asked.\n"
            + "- The edit will FAIL if `old_string` is not unique in the file. Either provide a larger string with "
            + "more surrounding context to make it unique or use `replace_all` to change every instance of `old_string`.\n"
            + "- Use `replace_all` for replacing and renaming strings across the file. This parameter is useful if you want to rename a variable for instance.";
    }

    /**
     * 结果落盘阈值 · 对齐 CC {@code FileEditTool.ts:89 maxResultSizeChars = 100_000}
     * （覆盖 Tool 接口默认 50000）。
     */
    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }


    /**
     * [L+ round 3] Edit validateInput · 严格对齐 CC {@code FileEditTool.ts:137-311}.
     *
     * <p>两个门禁:
     * <ol>
     *   <li><b>read-before-write</b> (CC :275-287 errorCode=6) —
     *       {@code toolUseContext.readFileState.get(fullFilePath)} 为空或 isPartialView=true
     *       → 拒绝, 让 LLM 先 Read. WHY: 防"未见过文件内容"就改, 写出错的 patch.</li>
     *   <li><b>stale-write 拒绝</b> (CC :290-310 errorCode=7) —
     *       mtime > readTimestamp.timestamp → 检查内容兜底; offset/limit 均为 null
     *       (即上一次是 full read 或 Edit/Write 写回) 且 fileContent === readTimestamp.content
     *       → 放行, 否则拒绝. WHY: 防"Read 后被外部改"还盲改.</li>
     * </ol>
     *
     * <p>排在前置步骤 (deny 规则 / 路径越狱 / UNC / 大文件) 之后, 在执行 findActualString 之前。
     * 这两个门禁都基于 {@link PathGuard} resolve 后的归一化路径, 与 Read tool 一致.
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        if (input == null) {
            // null input 仅是参数校验失败: 让 execute() 内部 catch 兜底, 保持最小占位语义.
            return Tool.ValidationResult.pass();
        }
        if (ctx == null) {
            // [L+ round 4] 关闭 ctx==null 绕过: CC FileEditTool.ts:137 validateInput 签名
            // 必传 toolUseContext, Java 端两重载 + execute(call) 无 ctx 重载打开的缺口
            // 让"无 ctx 调用"完全跳过 read-before-write + stale-write 两道门, 违反
            // CLAUDE.md 规则十二 (显式失败) + 偏离 CC 行为.
            // 对齐 CC: 无 ctx = 工具无从得知文件是否被读过, 放行等于静默跳过安全检查,
            // 必须拒绝并要求 caller 走 ctx 路径.
            if (log.isWarnEnabled()) {
                log.warn("EditFileTool: validateInput ctx==null 拒绝 (CC FileEditTool.ts:137 必传 toolUseContext, 门禁不能旁路)");
            }
            return Tool.ValidationResult.fail("GATE_BYPASS",
                "EditFileTool requires ToolUseContext; calling validateInput/execute without " +
                "ctx silently bypasses the read-before-write + stale-write gates. Aligning with " +
                "CC FileEditTool.ts:137 where toolUseContext is mandatory.");
        }
        String relPath = input.path("file_path").asText("");
        if (relPath.isBlank()) {
            return Tool.ValidationResult.pass();  // execute() 内部会拒空 path
        }

        Path file;
        try {
            file = guard.resolve(relPath);
        } catch (SecurityException se) {
            // 路径越狱: 让 execute() 内部 SecurityException 兜底处理,
            // 这里 pass 让其他 validate 阶段先做 deny 规则等检查.
            return Tool.ValidationResult.pass();
        }
        // UNC 路径提前 pass (CC :179-181 防 NTLM 凭据泄露).
        String fullFilePathStr = file.toString();
        if (fullFilePathStr.startsWith("\\\\") || fullFilePathStr.startsWith("//")) {
            return Tool.ValidationResult.pass();
        }

        // P1-4: team memory secret 检查 · 对齐 CC FileEditTool.ts:144 — 编辑 team memory 文件
        // 写入 new_string 含 secret → 拒绝 (errorCode 0)，防止 secret 同步给仓库协作者。
        String oldString = input.path("old_string").asText("");
        String newString = input.path("new_string").asText("");
        boolean replaceAll = input.path("replace_all").asBoolean(false);
        if (teamMemSecretGuard != null) {
            String secretError = teamMemSecretGuard.checkTeamMemSecrets(fullFilePathStr, newString);
            if (secretError != null) {
                return Tool.ValidationResult.fail("0", secretError);
            }
        }

        // errorCode 1: old==new 无改动 · 对齐 CC FileEditTool.ts:148-155
        if (oldString.equals(newString)) {
            return Tool.ValidationResult.fail("1",
                "No changes to make: old_string and new_string are exactly the same.");
        }

        // errorCode 2: deny 规则检查 · 对齐 CC FileEditTool.ts:158-174
        //   `matchingRuleForInput(fullFilePath, appState.toolPermissionContext, 'edit', 'deny')`
        //   命中非 null → errorCode=2 + 逐字文案 "File is in a directory that is denied by your permission settings."。
        //   Java 端用 RuleQuery.getEditRuleByContentsForPath(permCtx, absoluteNormalizedPath, DENY)
        //   （与 WritePermissionChecker:162-177 同源 content-glob 匹配）；路径用 checkPermissions 同款
        //   file.toAbsolutePath().normalize().toString()。null-safe：permCtx==null（ctxFor 等 POJO 测试
        //   用 ToolUseContext.of 未注入 permissionContext）跳过不 NPE。
        ToolPermissionContext permCtx = ctx.permissionContext();
        if (permCtx != null) {
            String absoluteNormalizedPath = file.toAbsolutePath().normalize().toString();
            PermissionRule denyRule = RuleQuery.getEditRuleByContentsForPath(
                permCtx, absoluteNormalizedPath, PermissionBehavior.DENY);
            if (denyRule != null) {
                if (log.isInfoEnabled()) {
                    log.info("EditFileTool: edit deny 规则命中 → errorCode=2 拒绝: rule={} path={}",
                        RuleQuery.ruleToString(denyRule), absoluteNormalizedPath);
                }
                return Tool.ValidationResult.fail("2",
                    "File is in a directory that is denied by your permission settings.");
            }
        }

        // errorCode 10: 1 GiB 大小上限 · 对齐 CC FileEditTool.ts:184-197（stat bytes 前置于读取，
        //   防多 GB 文件读入内存 OOM）。ENOENT 时 Files.size 抛 IOException, 落到后续文件读取分支。
        boolean fileExists = Files.exists(file);
        if (fileExists) {
            try {
                long size = Files.size(file);
                if (size > MAX_EDIT_FILE_SIZE) {
                    return Tool.ValidationResult.fail("10",
                        "File is too large to edit (" + ToolResultStorage.formatFileSize(size)
                            + "). Maximum editable file size is "
                            + ToolResultStorage.formatFileSize(MAX_EDIT_FILE_SIZE) + ".");
                }
            } catch (java.io.IOException e) {
                // stat 失败（权限/竞态）: 不阻塞, 后续 Files.readString 会显式失败
                if (log.isWarnEnabled()) {
                    log.warn("EditFileTool: stat 失败跳过 errorCode 10 path={}", fullFilePathStr);
                }
                fileExists = Files.exists(file);
            }
        }

        // 读文件内容 · 对齐 CC FileEditTool.ts:199-213（utf8/utf16le BOM 检测 + CRLF 归一化）。
        //   [IMP-D2] 由 FileEncodingReader.readFileMetadata 按 BOM 检测 encoding 后解码——
        //   utf16le 文件不再恒 UTF-8 读（乱码），CRLF 已归一（EV-D2-020）。ENOENT → null。
        String fileContent = null;
        try {
            fileContent = FileEncodingReader.readFileMetadata(file).content();
        } catch (java.io.IOException e) {
            if (!Files.exists(file)) {
                fileContent = null; // ENOENT: 交给 errorCode 4 分支
            } else {
                throw new IllegalStateException("Failed to read file for Edit validation: " + relPath, e);
            }
        }

        // ENOENT → errorCode 4 · 对齐 CC FileEditTool.ts:215-240。空 old = 新建文件豁免;
        // 非空 old → 拒绝。CC 含 findSimilarFile/suggestPathUnderCwd 建议文案, Java 无等价,
        // 输出基础文案 + FILE_NOT_FOUND_CWD_NOTE (见 E2 concerns 登记)。
        if (fileContent == null) {
            if (oldString.isEmpty()) {
                return Tool.ValidationResult.pass(); // 新建文件 (空 old) 豁免
            }
            return Tool.ValidationResult.fail("4",
                "File does not exist. " + FILE_NOT_FOUND_CWD_NOTE + ".");
        }

        // errorCode 3: 空 old + 非空文件 → 拒绝建新文件 · 对齐 CC FileEditTool.ts:242-255
        if (oldString.isEmpty()) {
            if (!fileContent.isBlank()) {
                return Tool.ValidationResult.fail("3", "Cannot create new file - file already exists.");
            }
            return Tool.ValidationResult.pass(); // 空文件 + 空 old 合法 (替换空为内容)
        }

        // errorCode 5: ipynb 拒绝 · 对齐 CC FileEditTool.ts:257-262
        if (fullFilePathStr.toLowerCase().endsWith(".ipynb")) {
            return Tool.ValidationResult.fail("5",
                "File is a Jupyter Notebook. Use the NotebookEditTool to edit this file.");
        }

        // 门禁 1: read-before-write · 对齐 CC FileEditTool.ts:264-273 errorCode=6
        ReadState readState = ctx.readFileState().get(ToolUseContext.keyForReadFileState(guard, relPath));
        if (readState == null || readState.isPartialView()) {
            return Tool.ValidationResult.fail("6",
                "File has not been read yet. Read it first before writing to it.");
        }

        // 门禁 2: stale-write 拒绝 · 对齐 CC FileEditTool.ts:275-310 errorCode=7
        long lastWriteTime;
        try {
            lastWriteTime = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            // stat 失败: 不阻塞, 让 execute() 内部 catch 兜底
            return Tool.ValidationResult.pass();
        }
        if (lastWriteTime > readState.mtimeMillis()) {
            // CC 内容兜底: 仅当 entry 的 offset/limit 均为 null (isFullRead) 且
            // fileContent 与 readState.content 完全一致时, 才放行 (防 mtime 误增).
            boolean isPostWriteEntry = readState.offset() == null && readState.limit() == null;
            String readContent = readState.content();
            // 注: readContent 可能是 null (旧 entry 没填), 此时拿不到内容兜底, 直接拒.
            if (!(isPostWriteEntry && readContent != null)) {
                return Tool.ValidationResult.fail("7",
                    "File has been modified since read, either by the user or by a linter. " +
                    "Read it again before attempting to write to it.");
            }
            // 复用已读 fileContent (已 CRLF 归一化), 与 readState.content 比对防 mtime 误增
            // [G13④] BOM 归一：ReadState.content 为无 BOM 形式（ReadFileTool 按 CC readFileInRange
            //   剥 UTF-8 BOM），fileContent 保留 BOM（FileEncodingReader 对齐 CC readFileSyncWithMetadata）
            //   —— 双侧剥前导 U+FEFF 后比对，防 BOM 文件在 mtime 误增（云同步/杀软 touch）时误判"内容已变"。
            if (!stripLeadingBom(fileContent).equals(stripLeadingBom(readContent))) {
                return Tool.ValidationResult.fail("7",
                    "File has been modified since read, either by the user or by a linter. " +
                    "Read it again before attempting to write to it.");
            }
        }

        // errorCode 8: findActualString 未命中 · 对齐 CC FileEditTool.ts:315-323。
        //   Java 无独立 LLM 解析层做 desanitize (CC api.ts:627 层), 此处先 normalizeEdit
        //   (stripTrailingWhitespace + desanitize 兜底) 再 findActualString, 与 executeInternal
        //   匹配路径行为一致 (行为等价, 层不同 — 见 E2 concerns)。
        EditMatchEngine.NormalizedEdit normalized =
            EditMatchEngine.normalizeEdit(relPath, fileContent, oldString, newString);
        String actualOldString = EditMatchEngine.findActualString(fileContent, normalized.oldString());
        if (actualOldString == null) {
            return Tool.ValidationResult.fail("8",
                "String to replace not found in file.\nString: " + oldString);
        }

        // errorCode 9: 多匹配但 replace_all=false · 对齐 CC FileEditTool.ts:325-337
        int matches = countOccurrences(fileContent, actualOldString);
        if (matches > 1 && !replaceAll) {
            return Tool.ValidationResult.fail("9",
                "Found " + matches + " matches of the string to replace, but replace_all is false. "
                + "To replace all occurrences, set replace_all to true. To replace only one occurrence, "
                + "please provide more context to uniquely identify the instance.\nString: " + oldString);
        }

        // [G17④] settings 文件编辑校验 · 对齐 CC FileEditTool.ts:345-359
        //   validateInputForSettingsFileEdit（utils/settings/validateEditTool.ts）：
        //   settings 路径且编辑前 JSON 合法 → 编辑后必须仍为合法 JSON，否则拒绝（errorCode 10）。
        //   （旧注释「settings 校验归属域 H」随拍板「settings 校验归 Edit（对齐 CC）」作废，
        //   由本工具接回；CC SettingsSchema 全量 schema 校验属 config 域，见 helper Javadoc。）
        Tool.ValidationResult settingsResult = validateSettingsEdit(
            fullFilePathStr, fileContent, actualOldString, newString, replaceAll);
        if (settingsResult != null) {
            return settingsResult;
        }

        // 成功收尾 · 对齐 CC FileEditTool.ts:361 `return { result: true }`（CC 自身 Tool.ts:95-101
        //   ValidationResult 类型未声明 meta，meta 属 TS 结构化多余字段死权重；[IMP-C4 DC-A1-02] 删除
        //   passWithMeta 后改道 pass()，actualOldString 仅保留在下方 debug 日志，不进入验证结果契约）。
        // [IMP-D2] settings 文件编辑校验归属（E27/OPD-D2-02）：CC validateInputForSettingsFileEdit
        //   （utils/settings/validateEditTool.ts）对 settings 路径做 schema 校验；Java 无 JSON Schema
        //   校验引擎，SettingsSchema 真源在 update-config skill 域（UpdateConfigSkillRegistrar.REAL_SETTINGS_SCHEMA）。
        //   拍板「settings 校验归属域 H 或 Edit 接回」→ 归属 = 域 H（config/settings 域承载校验引擎），
        //   Edit 不接回（域 H 未提供校验引擎前登记受控残留，见 08-traceability REQ-G2-5-3）。
        if (log.isDebugEnabled()) {
            log.debug("EditFileTool: validateInput 成功收尾 actualOldString 长度={} (CC FileEditTool.ts:361, 不再透传 meta)",
                actualOldString.length());
        }
        return Tool.ValidationResult.pass();
    }

    /**
     * 统计子串出现次数 (非重叠, 等价 CC {@code file.split(sub).length - 1}) ·
     * errorCode 9 多匹配判断用。
     */
    private static int countOccurrences(String s, String sub) {
        if (sub.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * [G13④] 剥 UTF-8 BOM（首字符 U+FEFF）· 供 stale-write content 比对归一。
     *
     * <p>WHY: ReadFileTool 按 CC readFileInRange.ts:138 已把 ReadState.content 存为无 BOM 形式；
     * Edit/Write 经 FileEncodingReader.readFileMetadata 读回的 CRLF 归一 content 对 UTF-8 BOM 文件
     * 保留首字符 U+FEFF（对齐 CC readFileSyncWithMetadata）。直接 equals 会对 BOM 文件在 mtime 误增
     * 时误判"内容已变"（云同步/杀软 touch）。比对双侧各剥一次前导 U+FEFF 后等价，避免失配。
     */
    private static String stripLeadingBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    /**
     * [G17④] settings 文件编辑校验 · 对齐 CC {@code validateInputForSettingsFileEdit}
     * （utils/settings/validateEditTool.ts:22-51）。
     *
     * <p>CC 语义：仅 Claude settings 路径（{@code .claude/settings.json} /
     * {@code .claude/settings.local.json}，filesystem.ts:200-222 isClaudeSettingsPath）触发；
     * before 版本非法 → 放行（CC :47-53）；before 合法 → after 必须仍合法（SettingsSchema），
     * 否则 errorCode 10 拒绝（CC :55-63）。
     *
     * <p><b>边界登记</b>：Java 端以 <b>JSON 合法性</b>为边界（before/after 均可被
     * {@code JSON.readTree} 解析）；CC 的 SettingsSchema 全量 schema 校验（settings.ts
     * safeParse + SettingsSchemaGenerator）属 config/settings 域，登记受控残留——
     * 编辑把合法 JSON 改成非法 JSON 是本校验能拦截的 CC 等价子集。
     *
     * @return fail(errorCode 10) 或 null（非 settings 路径 / before 非法 / after 合法 → 放行）
     */
    private Tool.ValidationResult validateSettingsEdit(String fullFilePathStr, String fileContent,
            String actualOldString, String newString, boolean replaceAll) {
        String normalized = fullFilePathStr.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        // 决策 D2/D6 全动态：项目级 nexusai 目录名 = NexusaiPaths.getProjectDirName()（.{appName}），
        // .nexusai/settings.json + .nexusai/settings.local.json 与 .claude 等价受保护配置 carve-out。
        String nexusaiDirLower = NexusaiPaths.getProjectDirName().toLowerCase();
        boolean isSettings = normalized.endsWith("/.claude/settings.json")
            || normalized.endsWith("/.claude/settings.local.json")
            || normalized.endsWith("/" + nexusaiDirLower + "/settings.json")
            || normalized.endsWith("/" + nexusaiDirLower + "/settings.local.json");
        if (!isSettings) {
            return null;
        }
        // before 非法 → 放行（CC「If the before version is invalid, allow the edit」）
        if (!isValidSettingsJson(fileContent)) {
            return null;
        }
        String updated = replaceAll
            ? fileContent.replace(actualOldString, newString)
            : replaceFirstLiteral(fileContent, actualOldString, newString);
        if (!isValidSettingsJson(updated)) {
            if (log.isInfoEnabled()) {
                log.info("EditFileTool: settings 编辑后 JSON 非法 → errorCode 10 拒绝: path={}（CC validateEditTool.ts:55-63）",
                    fullFilePathStr);
            }
            return Tool.ValidationResult.fail("10",
                "Claude Code settings.json validation failed after edit: the edited file is not valid JSON.\n"
                    + "IMPORTANT: Do not update the env unless explicitly instructed to do so.");
        }
        return null;
    }

    private static boolean isValidSettingsJson(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            JSON.readTree(content);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 仅替换第一个字面量出现（等价 JS {@code String.replace}，非 replaceAll）。 */
    private static String replaceFirstLiteral(String s, String target, String replacement) {
        int idx = s.indexOf(target);
        if (idx < 0) {
            return s;
        }
        return s.substring(0, idx) + replacement + s.substring(idx + target.length());
    }

    /**
     * [IMP-D2] inputsEquivalent · 对齐 CC {@code FileEditTool.ts:363-386} +
     * {@code utils.ts:732-775} {@code areFileEditsInputsEquivalent}。
     *
     * <p>语义比较：两次 tool_call 的输入是否产生相同编辑结果。默认
     * {@link Tool#inputsEquivalent} 用 JSON equals（对象级），本 override 用
     * 「同一 file_path + 字面等价快路径 + 应用后结果等价」——若执行器去重/用户修改
     * 检测消费本方法，能识别不同表达但结果相同的编辑（EV-D2-013 U14 缺口）。
     *
     * @param a 输入 A
     * @param b 输入 B
     * @return true = 语义等价
     */
    @Override
    public boolean inputsEquivalent(JsonNode a, JsonNode b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        String pathA = a.path("file_path").asText("");
        String pathB = b.path("file_path").asText("");
        // Fast path: 不同文件 → 不等价（CC utils.ts:742-745）
        if (!Objects.equals(pathA, pathB)) {
            return false;
        }
        List<EditMatchEngine.EditMatch> edits1 = List.of(new EditMatchEngine.EditMatch(
            a.path("old_string").asText(""),
            a.path("new_string").asText(""),
            a.path("replace_all").asBoolean(false)));
        List<EditMatchEngine.EditMatch> edits2 = List.of(new EditMatchEngine.EditMatch(
            b.path("old_string").asText(""),
            b.path("new_string").asText(""),
            b.path("replace_all").asBoolean(false)));
        // 语义比较（读文件内容做应用后比较）：ENOENT → 空内容（CC utils.ts:763-772
        //   读文件失败无 TOCTOU 预检）。encoding-aware 读（utf16le 不乱码）。
        String fileContent = "";
        Path file;
        try {
            file = guard.resolve(pathA);
        } catch (SecurityException se) {
            return false;
        }
        if (Files.exists(file)) {
            try {
                fileContent = FileEncodingReader.readFileMetadata(file).content();
            } catch (java.io.IOException e) {
                // 读失败 → 不等价（不抛，等价比较不阻断工具链）
                return false;
            }
        }
        // EditMatchEngine.areFileEditsEquivalent 已含字面快路径 + 应用后结果比较（CC utils.ts:664-726）
        return EditMatchEngine.areFileEditsEquivalent(edits1, edits2, fileContent);
    }

    @Override
    public ToolResult execute(ToolUseBlock call) {
        // [L+ round 4] 关闭 execute(call) 无 ctx 绕过: 之前该路径直接走 executeInternal
        // 完全跳过 read-before-write + stale-write 两道门, 违反 CC FileEditTool.ts:137
        // 必传 toolUseContext 语义. 现在显式拒绝, 强制 caller 走 ctx 路径
        // (MagicDocUpdater.writeViaEditTool 已改造为构造最小 ctx + 播种 readFileState).
        if (log.isWarnEnabled()) {
            log.warn("EditFileTool: execute(call) 无 ctx 拒绝 (CC FileEditTool.ts:137 必传 toolUseContext, 门禁不能旁路)");
        }
        return ToolResult.error(call.id(),
            "EditFileTool requires ToolUseContext; calling execute(ToolUseBlock) without " +
            "ctx silently bypasses the read-before-write + stale-write gates. " +
            "Use execute(ToolUseBlock, ToolUseContext) instead. " +
            "Aligning with CC FileEditTool.ts:137 where toolUseContext is mandatory.");
    }

    /**
     * [L+ R1 收尾 · 跨工具消费者] Edit 成功后 invalidate ctx.readFileState().
     *
     * <p>对齐 CC {@code FileEditTool.ts:520}:
     * <pre>
     * // 6. Update read timestamp, to invalidate stale writes
     * readFileState.set(absoluteFilePath, {
     *   content: updatedFile,
     *   timestamp: getFileModificationTime(absoluteFilePath),
     *   offset: undefined,   // ← 关键: offset=undefined 让 dedup 跳过本条
     *   limit: undefined,
     * })
     * </pre>
     *
     * <p>WHY 关键: {@code offset=null} / {@code limit=null} 让
     * {@link ReadFileTool#execute} dedup 守卫
     * ({@code prevState.offset() != null && prevState.limit() != null})
     * 拒绝命中 → 强制下次 Read 走 full read 拿到新内容, 避免 LLM 拿到 stale content。
     *
     * <p>WHY 必须有 ctx 才写入: R1 已彻底删除实例级 fallback (见 ReadFileTool 注释),
     * EditFileTool/WriteFileTool 也遵循相同契约 — 无 ctx 时无会话边界, 不参与 cache。
     *
     * <p><b>[L+ round 3] key + content 升级</b>: 用 {@link ToolUseContext#keyForReadFileState}
     * 与 ReadFileTool 共用归一化函数; content 字段填充 CRLF 归一化后的新内容, 供后续
     * Edit/Write stale-write 内容兜底比对 (CC {@code FileEditTool.ts:521}).
     */
    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        // [L+ round 3] 先跑 validateInput 门禁 (read-before-write + stale-write).
        // 对齐 CC FileEditTool.ts:275-310 validateInput 阶段先于 call() 执行.
        // 若 ToolInputValidator 在 ToolExecution 链路上已先跑过 validateInput, 此次会重复
        // 跑一遍但结果一致 (幂等); 若调用方绕过 validator 直接调 execute(call, ctx),
        // 此次执行就是兜底, 防止漏过门禁.
        // [L+ round 4] ctx 为 null → validateInput 内部已拒绝, 此处不再 null 兜底.
        ValidationResult vr = validateInput(call.input(), ctx);
        if (!vr.ok()) {
            return ToolResult.error(call.id(),
                "EditFileTool validateInput failed: " + vr.message());
        }
        ToolResult result = executeInternal(call, ctx);
        // 仅写回成功后 invalidate cache; 错误结果不动 cache (避免污染)
        if (!com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(result.data()) && ctx != null) {
            String relPath = call.input().path("file_path").asText("");
            try {
                Path file = guard.resolve(relPath);
                long mtime = Files.getLastModifiedTime(file).toMillis();
                // [L+ round 3] CRLF 归一化: 写入 cache 的 content 必须 LF-only, 与 Read 侧一致.
                // [IMP-D2] 编码感知读: utf16le 文件写回后不能 Files.readString（UTF-8 乱码）,
                //   用 FileEncodingReader.readFileMetadata 按 BOM 解码（对齐 CC readFileForEdit 语义）。
                String updatedContent = FileEncodingReader.readFileMetadata(file).content();
                String keyForCache = ToolUseContext.keyForReadFileState(guard, relPath);
                // offset=null / limit=null: 让 ReadFileTool dedup 守卫拒绝命中 (CC FileEditTool.ts:520 对齐)
                ctx.readFileState().set(keyForCache, ReadState.full(mtime, updatedContent));
                if (log.isInfoEnabled()) {
                    log.info("EditFileTool: 写回后 invalidate readFileState: path={} mtime={}",
                        relPath, mtime);
                }
            } catch (Exception e) {
                // stat 失败不阻塞主流程, 但 warn 暴露, 防止 dedup stale content 静默累积
                if (log.isWarnEnabled()) {
                    log.warn("EditFileTool: 写回后无法 stat 文件 invalidate readFileState: path={} cause={}",
                        relPath, e.toString());
                }
            }
        }
        return result;
    }

    private ToolResult executeInternal(ToolUseBlock call, ToolUseContext ctx) {
        String relPath = call.input().path("file_path").asText("");
        String oldString = call.input().path("old_string").asText("");
        String newString = call.input().path("new_string").asText("");
        boolean replaceAll = call.input().path("replace_all").asBoolean(false);

        if (relPath.isBlank()) return ToolResult.error(call.id(), "path is empty");

        Path file;
        try {
            file = guard.resolve(relPath);
        } catch (SecurityException se) {
            log.warn("EditFileTool: blocked path escape: {}", relPath);
            return ToolResult.error(call.id(), se.getMessage());
        }

        // P1-2: 动态技能发现 + 条件技能激活 · 对齐 CC FileEditTool.ts:404-423
        //   （在 call() 开头、编辑前触发；fire-and-forget 不阻塞工具调用链）
        triggerDynamicSkillDiscovery(ctx, file);

        // [OPD-TOOL-06-4] 编辑前 fileHistoryTrackEdit 备份（pre-edit 内容）· 对齐 CC FileEditTool.ts:431-440
        //   （位于 staleness check 之前，备份 pre-edit 内容）
        trackFileHistory(ctx, file);

        try {
            // [IMP-D2] E33/E38 编码保留：读文件拿 encoding + lineEndings + CRLF 归一内容，
            //   写回保留 encoding + 原行尾（utf16le 不乱码、CRLF 不转 LF）。对齐 CC
            //   FileEditTool.ts:444-449 readFileForEdit（readFileSyncWithMetadata）+ :491
            //   writeTextContent(absoluteFilePath, updatedFile, encoding, endings)。
            //   新建文件（ENOENT）→ 空串 + utf8/LF（对齐 CC readFileForEdit ENOENT 分支 :615-622）。
            boolean exists = Files.exists(file);
            FileEncodingReader.FileMetadata meta = exists
                ? FileEncodingReader.readFileMetadata(file)
                : new FileEncodingReader.FileMetadata("", FileEncodingReader.UTF8,
                    FileEncodingReader.LineEndingType.LF);
            String content = meta.content();            // 已 CRLF 归一（对齐 CC fileBuffer.toString().replaceAll('\r\n','\n')）
            String encoding = meta.encoding();
            FileEncodingReader.LineEndingType endings = meta.lineEndings();

            // [IMP-D2] E31 mkdir：编辑目标文件父目录不存在时自动创建（对齐 CC FileEditTool.ts:430
            //   fs.mkdir(dirname(absoluteFilePath)) 递归建父目录）。WriteFileTool 已有（W22），Edit 补齐。
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }

            // [G33①/OPD-D2-07] call 级 stale 复检 · 对齐 CC FileEditTool.ts:451-468：
            //   validateInput errorCode-7 是前置门禁（读到内容之前）；CC 在 call() 内、读文件之后、
            //   写盘之前再查一次 —— `lastWriteTime > lastRead.timestamp` 且非（full-read 且 content
            //   一致）→ throw FILE_UNEXPECTEDLY_MODIFIED_ERROR（constants.ts:10 逐字）。WHY: 防
            //   validateInput 与 writeTextContent 之间文件被外部改动（TOCTOU），且 Java 全仓此前无
            //   该错误路径（仅依赖 validateInput errorCode-7，见 08-traceability REQ-G33-1）。
            //   content 兜底：full-read entry（offset/limit 均 null）且 content 一致才放行（Windows
            //   云同步/杀软 mtime 误增防误拒，CC :455-457 注释同款）。
            if (exists) {
                long lastWriteTime;
                try {
                    lastWriteTime = Files.getLastModifiedTime(file).toMillis();
                } catch (Exception e) {
                    lastWriteTime = -1L;  // stat 失败不阻断写（CC getFileModificationTime 抛错会向上传，
                    //   Java 兜底为不判 stale，避免一次 stat 失败锁死编辑）
                }
                ReadState lastRead = ctx != null
                    ? ctx.readFileState().get(ToolUseContext.keyForReadFileState(guard, relPath))
                    : null;
                if (lastRead == null || lastWriteTime > lastRead.mtimeMillis()) {
                    boolean isFullRead = lastRead != null
                        && lastRead.offset() == null && lastRead.limit() == null;
                    // [G13④] BOM 归一比对（同 validateInput errorCode-7）：ReadState 无 BOM、
                    //   FileEncodingReader 保留 BOM，双侧剥前导 U+FEFF 后等价。
                    boolean contentUnchanged = isFullRead
                        && stripLeadingBom(content).equals(stripLeadingBom(lastRead.content()));
                    if (!contentUnchanged) {
                        if (log.isWarnEnabled()) {
                            log.warn("EditFileTool: call 级 stale 复检拒绝（CC FileEditTool.ts:465）: path={} "
                                + "lastWriteTime={} readMtime={} isFullRead={}",
                                relPath, lastWriteTime,
                                lastRead == null ? -1L : lastRead.mtimeMillis(), isFullRead);
                        }
                        // CC FileEditTool.ts:465 `throw new Error(FILE_UNEXPECTEDLY_MODIFIED_ERROR)`：
                        // Java 由 executeInternal 外层 catch 转 "Edit error: <msg>"（isToolErrorData 前缀
                        // "Edit error" 识别 → is_error=true，StreamingToolExecutor:1861）。message 保持
                        // CC constants.ts:10 逐字。
                        throw new IllegalStateException(FILE_UNEXPECTEDLY_MODIFIED_ERROR);
                    }
                }
            }

            // 1. normalizeFileEditInput 等价 (CC utils.ts:584-647): stripTrailingWhitespace(new) +
            //    desanitize 兜底. Java 无独立 LLM 解析层 (CC api.ts:627), 落到匹配路径内做 fallback
            //    (行为等价, 层不同 — 见 E2 concerns).
            EditMatchEngine.NormalizedEdit normalized =
                EditMatchEngine.normalizeEdit(relPath, content, oldString, newString);

            // 2. findActualString (CC utils.ts:73-93): 精确 → 弯引号归一化 → 取文件真实子串.
            //    CC call(): actualOldString = findActualString(...) || old_string (FileEditTool.ts:472-473)
            String actualOldString = EditMatchEngine.findActualString(content, normalized.oldString());
            if (actualOldString == null) {
                actualOldString = normalized.oldString();
            }

            // 3. preserveQuoteStyle (CC utils.ts:104-141): 文件含弯引号时 new_string 弯引号保真
            String actualNewString = EditMatchEngine.preserveQuoteStyle(
                normalized.oldString(), actualOldString, normalized.newString());

            // 4. getPatchForEdits (CC utils.ts:262-353): 子串守卫 + 空文件特例 + 未变更抛错 + 显示 patch
            EditMatchEngine.EditMatchResult result = EditMatchEngine.getPatchForEdits(content,
                List.of(new EditMatchEngine.EditMatch(actualOldString, actualNewString, replaceAll)));
            String newContent = result.updatedFile();

            // [IMP-D2] E38 写回保留 encoding + lineEndings（对齐 CC writeTextContent：CRLF 文件
            //   把 LF 转回 CRLF，utf16le 用 UTF-16LE 编码；BOM 随 content U+FEFF 保真，
            //   不强制前置——PROBE-BOM DC-1 收敛）。utf16le 乱码/CRLF→LF 修复。
            FileEncodingReader.writeTextContent(file, newContent, encoding, endings);
            log.info("EditFileTool: replaced oldString={} chars with newString={} chars in {} " +
                "(replace_all={} encoding={} endings={})",
                actualOldString.length(), actualNewString.length(), relPath, replaceAll, encoding, endings);
            // HOOK-WIRE: FileChanged — 文件编辑后 emit (对齐 CC hooks.ts:4280 event 值域
            //   'change'|'add'|'unlink'；编辑=change。旧值 'edit' 不在 CC 值域内，
            //   按值域配置 matcher 失配（IMP-HOOKS-S5 D-08）；CC 由 fs watcher 触发
            //   (fileChangedWatcher.ts:75-85 handleFileEvent→executeFileChangedHooks),
            //   Java 以工具写盘事件等效触发)
            emitFileChangedHook(relPath, "change");
            // [OPD-TOOL-06-4] 写盘后 LSP didChange/didSave 通知 · 对齐 CC FileEditTool.ts:494-514
            //   （clearDeliveredDiagnosticsForFile + changeFile + saveFile）
            notifyLspChange(file, newContent);
            // vscodeSdkMcp.ts:39-59；userType=ant + client 存在才发送，否则内部跳过）
            notifyVscodeFileUpdated(relPath, content, newContent);
            if (log.isDebugEnabled()) {
                log.debug("EditFileTool: 匹配链完成 old={} actual={} new={} replaceAll={} hunk={}",
                    normalized.oldString(), actualOldString, actualNewString, replaceAll, result.patch().size());
            }
            // ── 结构化输出契约（对齐 CC FileEditTool.ts:555-572 data + :575-594 mapToolResult） ──
            // E1: Edit 输出 {filePath, oldString, newString, originalFile, structuredPatch,
            //    userModified, replaceAll, gitDiff?}; 模型可见 summary 保持短文本。
            String originalFile = content;                        // 已 CRLF 归一（CC originalFileContents）
            List<StructuredPatchHunk> patch = result.patch();       // CC getPatchForEdit 显示 patch
            // 行变更计数（对齐 CC FileEditTool.ts:531 countLinesChanged(patch)）——数据流日志消费
            CountLinesChanged.countLinesChanged(patch, null);
            String filePath = file.toAbsolutePath().normalize().toString();
            // gitDiff 门控默认关（对齐 CC FileEditTool.ts:544-554 CLAUDE_CODE_REMOTE && tengu_quartz_lantern）
            ToolUseDiff gitDiff = GitDiffFetcher.isEnabled() ? GitDiffFetcher.fetch(file) : null;

            Map<String, Object> structuredOutput = new LinkedHashMap<>();
            structuredOutput.put("filePath", filePath);            // CC data.filePath（FileEditTool.ts:561）
            structuredOutput.put("oldString", actualOldString);    // CC data.oldString = actualOldString（FileEditTool.ts:472-473）
            structuredOutput.put("newString", newString);          // CC data.newString = 输入 new_string（FileEditTool.ts:564，非 preserveQuoteStyle 后）
            structuredOutput.put("originalFile", originalFile);    // CC data.originalFile（FileEditTool.ts:564）
            structuredOutput.put("structuredPatch", patch);        // CC data.structuredPatch（FileEditTool.ts:566）
            structuredOutput.put("userModified", ctx != null && ctx.userModified());  // CC data.userModified = userModified ?? false（FileEditTool.ts:567）
            structuredOutput.put("replaceAll", replaceAll);        // CC data.replaceAll（FileEditTool.ts:568）
            if (gitDiff != null) {
                structuredOutput.put("gitDiff", gitDiff);          // CC data.gitDiff（FileEditTool.ts:569）
            }
            String summary = replaceAll
                ? "The file " + filePath + " has been updated. All occurrences were successfully replaced."
                : "The file " + filePath + " has been updated successfully.";
            if (log.isDebugEnabled()) {
                log.debug("EditFileTool: 结构化输出组装完成 path={} replaceAll={} gitDiff={} hunk={}",
                    filePath, replaceAll, gitDiff != null, patch.size());
            }
            return ToolResult.successWithStructuredOutput(call.id(), summary, structuredOutput);
        } catch (Exception e) {
            log.error("EditFileTool: error editing {}", file, e);
            return ToolResult.error(call.id(), "Edit error: " + e.getMessage());
        }
    }

    // P1-2: 动态技能管理器 · 对齐 CC FileEditTool.ts:408-422。@Autowired(required=false)：
    //   无 bean 时跳过（POJO 测试不破）。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.skill.DynamicSkillsManager dynamicSkillsManager;
    public void setDynamicSkillsManager(com.nexusai.application.agent.skill.DynamicSkillsManager m) {
        this.dynamicSkillsManager = m;
    }

    // P1-4: team memory secret 守卫 · 对齐 CC FileEditTool.ts:144 checkTeamMemSecrets 挂载。
    // @Autowired(required=false)：无 bean 时跳过（POJO 测试不破）。由 Spring 注入。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.memory.TeamMemSecretGuard teamMemSecretGuard;
    public void setTeamMemSecretGuard(com.nexusai.application.agent.memory.TeamMemSecretGuard guard) {
        this.teamMemSecretGuard = guard;
    }

    // ── HOOK-WIRE: FileChanged ──
    // FIX-HK2: @Autowired 让 Spring 自动注入 hookRegistry, 避免 EMIT-WIRED-NULL.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.permission.hook.HookRegistry hookRegistry;
    public void setHookRegistry(com.nexusai.application.agent.permission.hook.HookRegistry registry) {
        this.hookRegistry = registry;
    }

    // [RES-07d] VSCode SDK 桥（notifyVscodeFileUpdated consumer · 对齐 CC FileEditTool → vscodeSdkMcp.ts）。
    // @Autowired(required=false)：无 bean 时跳过（POJO 测试不破）；notify 内部 userType=ant + client 存在才发送。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.mcp.VscodeSdkMcp vscodeSdkMcp;
    public void setVscodeSdkMcp(com.nexusai.application.agent.mcp.VscodeSdkMcp vscodeSdkMcp) {
        this.vscodeSdkMcp = vscodeSdkMcp;
    }

    // [OPD-TOOL-06-4] FileHistoryService（fileHistoryTrackEdit pre-edit 备份 · 对齐 CC FileEditTool.ts:431-440）。
    // @Autowired(required=false)：无 bean 时跳过（POJO 测试不破）；trackEdit 内部 fileHistoryEnabled 门控。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FileHistoryService fileHistoryService;
    public void setFileHistoryService(FileHistoryService fileHistoryService) {
        this.fileHistoryService = fileHistoryService;
    }

    // [OPD-TOOL-06-4] LspManager（写盘后 didChange/didSave 通知 · 对齐 CC FileEditTool.ts:494-514）。
    // @Autowired(required=false)：无 bean 时跳过（POJO 测试不破）。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LspManager lspManager;
    public void setLspManager(LspManager lspManager) {
        this.lspManager = lspManager;
    }

    private void emitFileChangedHook(String path, String event) {
        if (hookRegistry == null) return;
        try {
            hookRegistry.fireFileChanged(path, event, null);
        } catch (Exception e) {
            log.warn("HOOK FileChanged failed: {}", e.getMessage());
        }
    }

    /** [RES-07d] 文件编辑后通知 VSCode SDK（fail-soft：notify 内部跳过 + 异常不抛）。 */
    private void notifyVscodeFileUpdated(String relPath, String oldContent, String newContent) {
        if (vscodeSdkMcp == null) {
            return;
        }
        try {
            vscodeSdkMcp.notifyVscodeFileUpdated(relPath, oldContent, newContent);
        } catch (Exception e) {
            log.warn("EditFileTool: vscode file_updated 通知失败: {}", e.getMessage());
        }
    }

    /**
     * [OPD-TOOL-06-4] 编辑前 fileHistoryTrackEdit 备份（pre-edit 内容）· 对齐 CC
     * {@code FileEditTool.ts:431-440} {@code fileHistoryTrackEdit(updateFileHistoryState,
     * absoluteFilePath, parentMessage.uuid)}（位于 staleness check 之前）。
     *
     * <p>messageId surrogate：CC 用 {@code parentMessage.uuid}（assistant message UUID），
     * Java ToolUseContext 已撤回 assistantMessage 字段，用 {@code toolUseId()} 优先、
     * {@code sessionId()} 兜底（语义缺口见 FileHistoryService JavaDoc）。
     * sessionId 另传 {@code ctx.sessionId()}（CC resolveBackupPath 的 getSessionId() 语义，
     * 备份目录键，非 messageId surrogate）。
     */
    private void trackFileHistory(ToolUseContext ctx, Path file) {
        if (fileHistoryService == null) {
            return;
        }
        try {
            String absolutePath = file.toAbsolutePath().normalize().toString();
            String messageId = resolveMessageId(ctx);
            String sessionId = ctx == null ? null : ctx.sessionId();
            fileHistoryService.trackEdit(absolutePath, messageId, sessionId);
        } catch (Exception e) {
            // 备份失败不阻塞编辑（CC createBackup 失败 logError + return 等价）
            log.warn("EditFileTool: fileHistory trackEdit 失败: {} cause={}", file, e.toString());
        }
    }

    /**
     * [OPD-TOOL-06-4] 写盘后 LSP didChange/didSave 通知 · 对齐 CC {@code FileEditTool.ts:494-514}：
     * {@code clearDeliveredDiagnosticsForFile('file://'+absoluteFilePath)} + {@code changeFile} + {@code saveFile}。
     * fail-soft：lspManager 未注入跳过；通知异常不抛。
     */
    private void notifyLspChange(Path file, String updatedFile) {
        if (lspManager == null) {
            return;
        }
        try {
            String absolutePath = file.toAbsolutePath().normalize().toString();
            // CC FileEditTool.ts:496 clearDeliveredDiagnosticsForFile('file://'+absoluteFilePath)
            LspDiagnosticRegistry.clearDeliveredDiagnosticsForFile("file://" + absolutePath);
            // CC :500 changeFile(absoluteFilePath, updatedFile)
            lspManager.changeFile(absolutePath, updatedFile);
            // CC :508 saveFile(absoluteFilePath)
            lspManager.saveFile(absolutePath);
        } catch (Exception e) {
            log.warn("EditFileTool: LSP 通知失败: {} cause={}", file, e.toString());
        }
    }

    /** [OPD-TOOL-06-4] messageId surrogate：toolUseId 优先，sessionId 兜底（CC parentMessage.uuid 语义缺口）。 */
    private String resolveMessageId(ToolUseContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (ctx.toolUseId() != null && !ctx.toolUseId().isBlank()) {
            return ctx.toolUseId();
        }
        return ctx.sessionId();
    }

    /**
     * P1-2: 动态技能发现 + 条件技能激活 · 对齐 CC FileEditTool.ts:404-423。
     *
     * <p>CC original（FileEditTool.ts:408-422）：
     * <pre>
     *   if (!isEnvTruthy(process.env.CLAUDE_CODE_SIMPLE)) {
     *     const newSkillDirs = await discoverSkillDirsForPaths([absoluteFilePath], cwd)
     *     if (newSkillDirs.length > 0) {
     *       for (const dir of newSkillDirs) dynamicSkillDirTriggers?.add(dir)
     *       addSkillDirectories(newSkillDirs).catch(() => {})     // fire-and-forget
     *     }
     *     activateConditionalSkillsForPaths([absoluteFilePath], cwd)
     *   }
     * </pre>
     * Java 会话级 bare 判定（用户 2026-08-23 拍板 bareMode 随会话走，V33 列 bare_mode →
     * 回落 {@code nexusai.memory.bare-mode} / env CLAUDE_CODE_SIMPLE / false）：bare 会话跳过
     * 技能目录遍历；非 bare 恒触发；try-catch 不阻塞主链。
     *
     * @param ctx          工具调用上下文（null → 跳过）
     * @param fullFilePath 归一化绝对文件路径
     */
    private void triggerDynamicSkillDiscovery(ToolUseContext ctx, Path fullFilePath) {
        if (dynamicSkillsManager == null || ctx == null || ctx.effectiveCwd() == null) {
            return;
        }
        // [G24-bare] 动态技能发现门控 · 对齐 CC FileEditTool.ts:407
        //   `if (!isEnvTruthy(process.env.CLAUDE_CODE_SIMPLE))` —— bare（SIMPLE）模式跳过
        //   discoverSkillDirsForPaths + activateConditionalSkillsForPaths（skill 目录遍历）。
        //   Web 端无 simple mode 概念 → Java 会话级判定（bareMode 随会话走，V33 列）。
        if (MemoryBareModeConfig.isBareMode(ctx.sessionId())) {
            if (log.isDebugEnabled()) {
                log.debug("EditFileTool: bare 模式跳过动态技能发现（CC FileEditTool.ts:407 SIMPLE 门控, 会话 {}）",
                    ctx.sessionId());
            }
            return;
        }
        try {
            // CC :408 discoverSkillDirsForPaths([absoluteFilePath], cwd)
            java.util.List<String> newSkillDirs = dynamicSkillsManager.discoverSkillDirsForPaths(
                java.util.List.of(fullFilePath.toString()), ctx.effectiveCwd());
            if (!newSkillDirs.isEmpty()) {
                // CC :415 dynamicSkillDirTriggers?.add(dir)
                for (String dir : newSkillDirs) {
                    ctx.dynamicSkillDirTriggers().add(dir);
                }
                // CC :418 addSkillDirectories(newSkillDirs).catch(()=>{}) —— fire-and-forget
                dynamicSkillsManager.addSkillDirectories(newSkillDirs);
            }
            // CC :422 activateConditionalSkillsForPaths([absoluteFilePath], cwd)
            dynamicSkillsManager.activateConditionalSkillsForPaths(
                java.util.List.of(fullFilePath.toString()), ctx.effectiveCwd());
        } catch (Exception e) {
            // 技能发现失败不阻塞编辑（CC .catch(()=>{}) 等价）
            if (log.isDebugEnabled()) {
                log.debug("EditFileTool: 动态技能发现失败, 不阻塞工具: cause={}", e.toString());
            }
        }
    }

    /**
     * [G2] tool_result 块 · 对齐 CC {@code FileEditTool.ts:575-596 mapToolResultToToolResultBlockParam}
     * （成功路径被调 toolExecution.ts:1292）。
     *
     * <p>Java 端结构化字段在 {@link ToolResult#structuredOutput}（filePath/userModified/
     * replaceAll，EditFileTool.java:587-596 对齐 CC FileEditTool.ts:561-568 data），本 mapper
     * 读该 map 重建 CC 同款 content（CC :583-595）:
     * <ul>
     *   <li>replaceAll → {@code The file ${filePath} has been updated${modifiedNote}. All occurrences were successfully replaced.}</li>
     *   <li>默认      → {@code The file ${filePath} has been updated successfully${modifiedNote}.}</li>
     * </ul>
     * modifiedNote（CC :579-581）: userModified 时追加
     * {@code ".  The user modified your proposed changes before accepting them. "}。
     *
     * @param result 工具执行结果（structuredOutput 含 filePath/userModified/replaceAll）
     * @return tool_result 块（tool_use_id/type/content/is_error）
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (!(result instanceof ToolResult<?> tr)) {
            return null;
        }
        Map<String, Object> so = ToolResult.presentationMeta(tr);
        String filePath = so.get("filePath") instanceof String s ? s : "";
        boolean userModified = Boolean.TRUE.equals(so.get("userModified"));
        boolean replaceAll = Boolean.TRUE.equals(so.get("replaceAll"));
        String modifiedNote = userModified
                ? ".  The user modified your proposed changes before accepting them. "
                : "";
        String content;
        if (replaceAll) {
            content = "The file " + filePath + " has been updated" + modifiedNote
                    + ". All occurrences were successfully replaced.";
        } else {
            content = "The file " + filePath + " has been updated successfully" + modifiedNote + ".";
        }
        if (log.isDebugEnabled()) {
            log.debug("EditFileTool.mapToToolResultBlockParam: id={} filePath={} replaceAll={} userModified={} contentLen={}（CC FileEditTool.ts:575-596）",
                toolUseId, filePath, replaceAll, userModified, content.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }
}
