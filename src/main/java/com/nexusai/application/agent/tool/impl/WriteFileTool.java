package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.CountLinesChanged;
import com.nexusai.application.agent.tool.GitDiffFetcher;
import com.nexusai.application.agent.bash.BashRuleMatcher;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.StructuredPatchGenerator;
import com.nexusai.application.agent.tool.StructuredPatchHunk;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseContext.ReadState;
import com.nexusai.application.agent.tool.ToolResult;
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
import java.util.function.Predicate;
import java.util.Map;

/**
 * Write File 工具 · 对齐 CC {@code FileWriteTool.ts}（{@code src/tools/FileWriteTool/}，Open-Claude-Code 2.1.88）。
 *
 * <p>生产级特性：
 * <ul>
 *   <li><b>PathGuard</b>：路径逃逸 → 错误</li>
 *   <li><b>parent.mkdirs</b>：自动创建父目录（LLM 不用先 mkdir）</li>
 *   <li><b>覆盖语义</b>：对齐 CC FileWriteTool.ts —— 无 create 入参，文件已存在即覆盖，
 *       输出 type='update'；文件不存在即新建，输出 type='create'（C-29 已删 create 参数）</li>
 *   <li><b>concurrency-unsafe</b>：写操作 → false（不能与其它 write 并发）</li>
 * </ul>
 */
@Component
public class WriteFileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WriteFileTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PathGuard guard;

    // HOOK-WIRE: FileChanged — 对齐 CC hooks.ts:FILE_CHANGED. Phase 5 集中化:
    // WriteFileTool 现在与 EditFileTool 走同一公共入口 HookRegistry.fireFileChanged.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.permission.hook.HookRegistry hookRegistry;
    public void setHookRegistry(com.nexusai.application.agent.permission.hook.HookRegistry registry) {
        this.hookRegistry = registry;
    }

    // [RES-07d] VSCode SDK 桥（notifyVscodeFileUpdated consumer · 对齐 CC FileWriteTool → vscodeSdkMcp.ts）。
    // @Autowired(required=false)：无 bean 时跳过（POJO 测试不破）；notify 内部 userType=ant + client 存在才发送。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.mcp.VscodeSdkMcp vscodeSdkMcp;
    public void setVscodeSdkMcp(com.nexusai.application.agent.mcp.VscodeSdkMcp vscodeSdkMcp) {
        this.vscodeSdkMcp = vscodeSdkMcp;
    }

    // [OPD-TOOL-06-4] FileHistoryService（fileHistoryTrackEdit pre-edit 备份 · 对齐 CC FileWriteTool.ts:255-263）。
    // @Autowired(required=false)：无 bean 时跳过（POJO 测试不破）；trackEdit 内部 fileHistoryEnabled 门控。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FileHistoryService fileHistoryService;
    public void setFileHistoryService(FileHistoryService fileHistoryService) {
        this.fileHistoryService = fileHistoryService;
    }

    // [OPD-TOOL-06-4] LspManager（写盘后 didChange/didSave 通知 · 对齐 CC FileWriteTool.ts:311-323）。
    // @Autowired(required=false)：无 bean 时跳过（POJO 测试不破）。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LspManager lspManager;
    public void setLspManager(LspManager lspManager) {
        this.lspManager = lspManager;
    }

    /** [RES-07d] 写文件后通知 VSCode SDK（fail-soft：notify 内部跳过 + 异常不抛）。 */
    private void notifyVscodeFileUpdated(String relPath, String oldContent, String newContent) {
        if (vscodeSdkMcp == null) {
            return;
        }
        try {
            vscodeSdkMcp.notifyVscodeFileUpdated(relPath, oldContent, newContent);
        } catch (Exception e) {
            log.warn("WriteFileTool: vscode file_updated 通知失败: {}", e.getMessage());
        }
    }

    /**
     * [OPD-TOOL-06-4] 写文件前 fileHistoryTrackEdit 备份（pre-edit 内容）· 对齐 CC
     * {@code FileWriteTool.ts:255-263} {@code fileHistoryTrackEdit(updateFileHistoryState,
     * fullFilePath, parentMessage.uuid)}（位于 mkdir 之后、write 之前）。
     *
     * <p>messageId surrogate：CC 用 {@code parentMessage.uuid}，Java 用 {@code toolUseId()}
     * 优先、{@code sessionId()} 兜底（语义缺口见 FileHistoryService JavaDoc）。
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
            log.warn("WriteFileTool: fileHistory trackEdit 失败: {} cause={}", file, e.toString());
        }
    }

    /**
     * [OPD-TOOL-06-4] 写盘后 LSP didChange/didSave 通知 · 对齐 CC {@code FileWriteTool.ts:311-323}：
     * {@code clearDeliveredDiagnosticsForFile('file://'+fullFilePath)} + {@code changeFile} + {@code saveFile}。
     * fail-soft：lspManager 未注入跳过；通知异常不抛。
     */
    private void notifyLspChange(Path file, String content) {
        if (lspManager == null) {
            return;
        }
        try {
            String absolutePath = file.toAbsolutePath().normalize().toString();
            // CC FileWriteTool.ts:314 clearDeliveredDiagnosticsForFile('file://'+fullFilePath)
            LspDiagnosticRegistry.clearDeliveredDiagnosticsForFile("file://" + absolutePath);
            // CC :318 changeFile(fullFilePath, content)
            lspManager.changeFile(absolutePath, content);
            // CC :322 saveFile(fullFilePath)
            lspManager.saveFile(absolutePath);
        } catch (Exception e) {
            log.warn("WriteFileTool: LSP 通知失败: {} cause={}", file, e.toString());
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
     * [IMPL-09] 写权限检查器 · 对齐 CC FileWriteTool.checkPermissions
     * (FileWriteTool.ts) 委托 checkWritePermissionForTool。
     *
     * <p>WHY: 与 {@code GlobTool} 同因 — 旧 6 hook 链对 write_file 的 1c/1g/2b'/
     * DONT_ASK 语义随删除收敛到管线，Java WriteFileTool 无 checkPermissions override
     * → 默认 Allow 短路 1c。缺失时 fail-loud（对齐 ReadFileTool:312-317，Pattern #11）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.permission.WritePermissionChecker permissionChecker;
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
     *       agent-memory / auto-memory 路径静默 allow（IMP-M-P2-2 + OPD-M-48；
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
                    // IMP-M-P2-2: agent-memory 写 carve-out（对齐 CC filesystem.ts:1554-1562
                    //   checkWritePermissionForTool 的 isAgentMemoryPath 预检查）：
                    //   CC 置于 memdir carve-out 之前（filesystem.ts:1554 isAgentMemoryPath → 1565 memdir）。
                    //   无 isAutoMemoryEnabled 门控（isAgentMemoryPath 是纯路径判定，agentMemory.ts:67-104）。
                    if (agentMemoryDirectory != null && agentMemoryDirectory.isAgentMemoryPath(absolute)) {
                        if (log.isDebugEnabled()) {
                            log.debug("WriteFileTool: agent-memory 写 carve-out 静默 allow (IMP-M-P2-2): {}",
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
                            log.debug("WriteFileTool: auto-memory 写 carve-out 静默 allow (OPD-M-48): {}",
                                relPath);
                        }
                        return new com.nexusai.application.agent.permission.PermissionResult.Allow(
                            input,
                            new com.nexusai.application.agent.permission.PermissionDecisionReason.Other(
                                "auto memory files are allowed for writing"),
                            null, false, null, java.util.List.of());
                    }
                } catch (SecurityException se) {
                    // 路径逃逸由 execute/validateInput 拒绝，此处不参与
                    if (log.isDebugEnabled()) {
                        log.debug("WriteFileTool: carve-out 路径逃逸跳过: {}", relPath);
                    }
                }
            }
        }
        // 非 carve-out 路径 → 委托 WritePermissionChecker（CC checkWritePermissionForTool 主体）
        if (permissionChecker == null) {
            throw new IllegalStateException(
                "permissionChecker 未注入, 无法执行 write 写权限检查");
        }
        return permissionChecker.check(this, input, ctx);
    }

    // P1-2: 动态技能管理器 · 对齐 CC FileWriteTool.ts:232-245（discoverSkillDirsForPaths +
    //   addSkillDirectories + activateConditionalSkillsForPaths）。@Autowired(required=false)：
    //   无 bean 时跳过（POJO 测试不破）。由 Spring 注入（DynamicSkillsManager @Component）。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.skill.DynamicSkillsManager dynamicSkillsManager;
    public void setDynamicSkillsManager(com.nexusai.application.agent.skill.DynamicSkillsManager m) {
        this.dynamicSkillsManager = m;
    }

    // P1-4: team memory secret 守卫 · 对齐 CC FileWriteTool.ts:157 checkTeamMemSecrets 挂载。
    // @Autowired(required=false)：无 bean 时跳过（POJO 测试不破）。由 Spring 注入
    // （TeamMemSecretGuard @Bean，ToolRegistrationConfig）。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.memory.TeamMemSecretGuard teamMemSecretGuard;
    public void setTeamMemSecretGuard(com.nexusai.application.agent.memory.TeamMemSecretGuard guard) {
        this.teamMemSecretGuard = guard;
    }

    // IMP-M-P2-4: auto-memory 路径解析（写 carve-out · 对齐 CC filesystem.ts:1565-1581
    //   checkEditableInternalPath memdir carve-out）。@Autowired(required=false)：无 bean 跳过。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths;
    public void setAutoMemPaths(com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths) {
        this.autoMemPaths = autoMemPaths;
    }

    // IMP-M-P2-2: agent-memory 路径解析（写 carve-out 第一分支 · 对齐 CC filesystem.ts:1554-1562
    //   isAgentMemoryPath）。@Autowired(required=false)：无 bean 跳过。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory;
    public void setAgentMemoryDirectory(com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory) {
        this.agentMemoryDirectory = agentMemoryDirectory;
    }

    public WriteFileTool(PathGuard guard) {
        if (guard == null) throw new IllegalArgumentException("guard is null");
        this.guard = guard;
    }

    // [PROBE-PG DC-2] WriteFileTool.pathGuard() 公开访问器已删除（全仓实测零消费者，
    //   CC FileWriteTool.ts 无对应 API；guard 字段保持 private 供内部使用——validateInput/
    //   executeInternal/构造器，见探查/tool_v4/implementation/probe-pathguard/probe-report.md §6.1）。
    //   EditFileTool.pathGuard() 保留（MagicDocUpdater.java:445/:450 活消费者）。

    @Override
    public String name() { return "Write"; }

    // [IMP-C3 删除] 旧 snake_case 'write_file' alias 已删除（DC-A2-03）：
    // CC FileWriteTool.ts:95 name='Write'（FILE_WRITE_TOOL_NAME=prompt.ts:3），真源无 aliases 声明，
    // 全仓 write_file 仅 UI/事件类型（write_file_single）。未上线可破约（决策清单 组2-2）。不保留兼容壳。

    @Override
    public String description() {
        return "Write content to a file. Path is resolved relative to the workspace root; " +
               "paths escaping the workspace are rejected. Parent directories are auto-created.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        // CC FileWriteTool.ts:57 z.strictObject → additionalProperties:false（未知键拒绝，
        //   由 ToolInputValidator:230-232 跟随广告层 UNSPECIFIED 策略逐键拒绝）
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");

        // [IMP-D2] schema 键名对齐 CC FileWriteTool.ts:58-64 file_path/content
        //   （旧 Java 键 path 删除，未上线可破约）。前端契约同步见 04-plan IMP-D2。
        ObjectNode path = JSON.createObjectNode();
        path.put("type", "string");
        path.put("description",
            "The absolute path to the file to write (must be absolute, not relative)");
        properties.set("file_path", path);

        ObjectNode content = JSON.createObjectNode();
        content.put("type", "string");
        content.put("description", "The content to write to the file");
        properties.set("content", content);

        ArrayNode required = JSON.createArrayNode();
        required.add("file_path");
        required.add("content");
        root.set("required", required);
        return root;
    }

    /**
     * 路径扩展点 · CC original: {@code getPath(input) → input.file_path}
     * （{@code FileWriteTool.ts:122}）。
     *
     * <p>权限管线（CC {@code filesystem.ts:1211-1217}）用本方法取本次写入路径做权限检查。
     * Java 端 schema 字段为 {@code path}（K-4 起 Java 契约），返回 {@code input.path("file_path")}。
     *
     * @param input 工具输入（含 {@code path}）
     * @return 本次写入的路径；缺失返回 null（等价 CC getPath 未定义 → ask）
     */
    @Override
    public String getPath(JsonNode input) {
        return input == null ? null : input.path("file_path").asText(null);
    }

    /**
     * [FIX-A backfill-observable] 观察者输入回填 · 对齐 CC {@code FileWriteTool.ts:126-131}
     * {@code backfillObservableInput}：{@code if (typeof input.file_path === 'string')
     * input.file_path = expandPath(input.file_path)}。
     *
     * <p>hooks.mdx 约定 file_path 为绝对路径；在 hook/canUseTool 观察前把 {@code path}
     * 展开为绝对路径，防 {@code ~}/相对路径绕过 hook allowlist（CC FileWriteTool.ts:127-128
     * 注释语义）。Java 端 schema 键为 {@code path}（K-4 起 Java 契约，CC 键为 {@code file_path}），
     * 故读 {@code input.path("file_path")}。
     *
     * <p><b>幂等 + 非抛异常</b>（CC Tool.ts:475-484 契约）：绝对路径（展开后不变）或缺
     * 字段 → 返回原引用；null 字节/非法输入 → 返回原引用。调用方
     * {@link com.nexusai.application.agent.permission.InputSanitizer#backfill} 已做防御性
     * deepCopy，原 input 永不被 in-place 改动。
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
                log.debug("WriteFileTool.backfillObservableInput: 路径展开失败返回原引用: path={} cause={}",
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
            log.debug("WriteFileTool.backfillObservableInput: path 绝对化 {} → {} (CC FileWriteTool.ts:126-131)",
                raw, expanded);
        }
        return copy;
    }

    /**
     * 自动分类器输入 · 对齐 CC {@code FileWriteTool.ts:119-121}
     * {@code toAutoClassifierInput(input) { return `${input.file_path}: ${input.content}` }}。
     *
     * <p><b>[IMP-D2] 键对齐</b>：CC schema 键 {@code file_path}（{@code FileWriteTool.ts:119}），
     * Java 已对齐同键（旧 {@code path} 删除）。投影 {@code file_path: content}，格式语义与 CC 一致。</p>
     *
     * <p>[OPD-24 G1] 接线：Write 是高安全相关工具，若未 override 走默认 {@code ''}
     * （CC Tool.ts:767）→ auto-mode 空串短路 ALLOW（yoloClassifier.ts:411/:1021-1024），
     * 写入操作不被分类 —— G6 阻断安全缺口。本投影让分类器拿到「路径 + 内容片段」。</p>
     *
     * @param input 工具输入（含 {@code file_path}/{@code content}）
     * @return {@code file_path: content}；缺失 → {@code ''}
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        if (input == null) {
            return "";
        }
        String path = input.path("file_path").asText("");
        if (path.isEmpty()) {
            // 安全相关载体（路径）缺失 = 无安全相关性 → 空串跳过转录（CC 空串语义）。
            // 偏离说明：CC FileWriteTool.ts:120 对缺失 file_path 会插值出 "undefined: ..."
            // （JS artifact），Java 端显式归空避免把垃圾块送进分类器。
            return "";
        }
        String content = input.path("content").asText("");
        String projection = path + ": " + content;
        if (log.isDebugEnabled()) {
            log.debug("WriteFileTool.toAutoClassifierInput: file_path:content 投影完成, 长度={} (CC FileWriteTool.ts:119-121)",
                projection.length());
        }
        return projection;
    }

    /**
     * 权限规则内容匹配器 · CC original: {@code preparePermissionMatcher}
     * （{@code FileWriteTool.ts:132}）→ {@code pattern => matchWildcardPattern(pattern, file_path)}。
     *
     * <p>hook if 条件（如 {@code Write(/abs/src/*.java)}）的 ruleContent 与本次 path 做通配匹配。
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
        return false;  // 写操作独占
    }

    /**
     * 严格模式 · 对齐 CC {@code FileWriteTool.ts:98 strict: true}（buildTool 配置块相邻三行之一）。
     * 严格模式下 API 更严格遵循工具指令与参数 schema，模型不可注入额外字段。
     */
    @Override
    public boolean strict() {
        return true;
    }

    /**
     * 搜索提示 · 对齐 CC {@code FileWriteTool.ts:96 searchHint = 'create or overwrite files'}。
     * 供 ToolSearch 关键词匹配（CC Tool.ts:378 可选字段，3-10 词、无尾句号约束）。
     */
    @Override
    public String searchHint() {
        return "create or overwrite files";
    }

    /**
     * [G9] 工具使用摘要 · 对齐 CC {@code FileWriteTool.ts:103 getToolUseSummary}
     * （UI.tsx:156-164：无 file_path → null；否则 getDisplayPath(file_path)）。
     */
    @Override
    public String getToolUseSummary(java.util.Map<String, Object> processedInput) {
        Object fp = processedInput == null ? null : processedInput.get("file_path");
        return fp == null ? null : String.valueOf(fp);
    }

    /**
     * [G10] prompt · 对齐 CC {@code FileWriteTool.ts:108-110 async prompt() { return getWriteToolDescription() }}
     * （FileWriteTool/prompt.ts:10-17）。pre-read 指示逐字一致。
     */
    @Override
    public String prompt() {
        return "Writes a file to the local filesystem.\n"
            + "\n"
            + "Usage:\n"
            + "- This tool will overwrite the existing file if there is one at the provided path.\n"
            + "- If this is an existing file, you MUST use the "
            + com.nexusai.application.agent.tool.ToolNameConstants.FILE_READ_TOOL_NAME
            + " tool first to read the file's contents. This tool will fail if you did not read the file first.\n"
            + "- Prefer the Edit tool for modifying existing files — it only sends the diff. Only use this tool "
            + "to create new files or for complete rewrites.\n"
            + "- NEVER create documentation files (*.md) or README files unless explicitly requested by the User.\n"
            + "- Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked.";
    }

    /**
     * 结果落盘阈值 · 对齐 CC {@code FileWriteTool.ts:97 maxResultSizeChars = 100_000}
     * （覆盖 Tool 接口默认 50000）。
     */
    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }


    /**
     * [L+ round 3] Write validateInput · 严格对齐 CC {@code FileWriteTool.ts:153-221}.
     *
     * <p>两个门禁 + 一个豁免:
     * <ol>
     *   <li><b>ENOENT 豁免</b> (CC :188-196) — 新建文件 (stat ENOENT) 直接 pass, 不走门禁.
     *       WHY: 新文件没被 Read 过是合法的, 模型调 Write 本来就是要创建文件.</li>
     *   <li><b>read-before-write</b> (CC :198-206 errorCode=2) —
     *       已存在的文件若没被 Read → 拒绝.</li>
     *   <li><b>stale-write 拒绝</b> (CC :208-219 errorCode=3) —
     *       mtime > readTimestamp.timestamp → 拒绝. 注: Write 端没有 Edit 的内容兜底
     *       (CC 注释明确 "meta.content is CRLF-normalized", 但 Write 不像 Edit 那样
     *       接受 full read content fallback).</li>
     * </ol>
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        if (input == null) {
            // null input 仅是参数校验失败: 让 execute() 内部 catch 兜底.
            return Tool.ValidationResult.pass();
        }
        if (ctx == null) {
            // [L+ round 4] 关闭 ctx==null 绕过: CC FileWriteTool.ts:153 validateInput 签名
            // 必传 toolUseContext, Java 端两重载 + execute(call) 无 ctx 重载打开的缺口
            // 让"无 ctx 调用"完全跳过 read-before-write + stale-write 两道门, 违反
            // CLAUDE.md 规则十二 (显式失败) + 偏离 CC 行为.
            // 对齐 CC: 无 ctx = 工具无从得知文件是否被读过, 放行等于静默跳过安全检查.
            if (log.isWarnEnabled()) {
                log.warn("WriteFileTool: validateInput ctx==null 拒绝 (CC FileWriteTool.ts:153 必传 toolUseContext, 门禁不能旁路)");
            }
            return Tool.ValidationResult.fail("GATE_BYPASS",
                "WriteFileTool requires ToolUseContext; calling validateInput/execute without " +
                "ctx silently bypasses the read-before-write + stale-write gates. Aligning with " +
                "CC FileWriteTool.ts:153 where toolUseContext is mandatory.");
        }
        String relPath = input.path("file_path").asText("");
        if (relPath.isBlank()) {
            return Tool.ValidationResult.pass();
        }

        Path file;
        try {
            file = guard.resolve(relPath);
        } catch (SecurityException se) {
            return Tool.ValidationResult.pass();
        }
        // UNC 路径提前 pass (CC :182-184).
        String fullFilePathStr = file.toString();
        if (fullFilePathStr.startsWith("\\\\") || fullFilePathStr.startsWith("//")) {
            return Tool.ValidationResult.pass();
        }

        // P1-4: team memory secret 检查 · 对齐 CC FileWriteTool.ts:157 — 写 team memory 文件
        // 含 secret → 拒绝 (errorCode 0)，防止 secret 同步给仓库协作者 (teamMemSecretGuard.ts:7-9)。
        if (teamMemSecretGuard != null) {
            String content = input.path("content").asText("");
            String secretError = teamMemSecretGuard.checkTeamMemSecrets(fullFilePathStr, content);
            if (secretError != null) {
                return Tool.ValidationResult.fail("0", secretError);
            }
        }

        // [G12] errorCode 1: deny 规则检查 · 对齐 CC FileWriteTool.ts:162-177
        //   `matchingRuleForInput(fullFilePath, toolPermissionContext, 'edit', 'deny')` 命中 →
        //   逐字文案 "File is in a directory that is denied by your permission settings."。
        //   Java 端用 RuleQuery.getEditRuleByContentsForPath（与 EditFileTool:487-498 同源），
        //   旧实现 validateInput 缺失该 deny 门（只有 checkPermissions 链的 checkDeny）。
        ToolPermissionContext permCtx = ctx.permissionContext();
        if (permCtx != null) {
            String absoluteNormalizedPath = file.toAbsolutePath().normalize().toString();
            PermissionRule denyRule = RuleQuery.getEditRuleByContentsForPath(
                permCtx, absoluteNormalizedPath, PermissionBehavior.DENY);
            if (denyRule != null) {
                if (log.isInfoEnabled()) {
                    log.info("WriteFileTool: write deny 规则命中 → errorCode=1 拒绝: rule={} path={}",
                        RuleQuery.ruleToString(denyRule), absoluteNormalizedPath);
                }
                return Tool.ValidationResult.fail("1",
                    "File is in a directory that is denied by your permission settings.");
            }
        }

        // 豁免 1: ENOENT 新建文件直接 pass (CC :188-196).
        if (!Files.exists(file)) {
            return Tool.ValidationResult.pass();
        }

        // 门禁 1: read-before-write (CC :198-206 errorCode=2)
        ReadState readState = ctx.readFileState().get(ToolUseContext.keyForReadFileState(guard, relPath));
        if (readState == null || readState.isPartialView()) {
            return Tool.ValidationResult.fail("2",
                "File has not been read yet. Read it first before writing to it.");
        }

        // 门禁 2: stale-write 拒绝 (CC :211-218 errorCode=3)
        long lastWriteTime;
        try {
            lastWriteTime = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return Tool.ValidationResult.pass();
        }
        if (lastWriteTime > readState.mtimeMillis()) {
            return Tool.ValidationResult.fail("3",
                "File has been modified since read, either by the user or by a linter. " +
                "Read it again before attempting to write to it.");
        }

        return Tool.ValidationResult.pass();
    }

    @Override
    public ToolResult execute(ToolUseBlock call) {
        // [L+ round 4] 关闭 execute(call) 无 ctx 绕过: 之前该路径直接走 executeInternal
        // 完全跳过 read-before-write + stale-write 两道门, 违反 CC FileWriteTool.ts:153
        // 必传 toolUseContext 语义. 现在显式拒绝, 强制 caller 走 ctx 路径.
        if (log.isWarnEnabled()) {
            log.warn("WriteFileTool: execute(call) 无 ctx 拒绝 (CC FileWriteTool.ts:153 必传 toolUseContext, 门禁不能旁路)");
        }
        return ToolResult.error(call.id(),
            "WriteFileTool requires ToolUseContext; calling execute(ToolUseBlock) without " +
            "ctx silently bypasses the read-before-write + stale-write gates. " +
            "Use execute(ToolUseBlock, ToolUseContext) instead. " +
            "Aligning with CC FileWriteTool.ts:153 where toolUseContext is mandatory.");
    }

    /**
     * [L+ R1 收尾 · 跨工具消费者] Write 成功后 invalidate ctx.readFileState().
     *
     * <p>对齐 CC {@code BashTool.tsx:404} (Bash 写文件后 readFileState.set) +
     * {@code FileEditTool.ts:520} (Edit 后 readFileState.set offset=undefined).
     *
     * <p>WHY 关键: {@code offset=null} / {@code limit=null} 让
     * {@link ReadFileTool#execute} dedup 守卫
     * ({@code prevState.offset() != null && prevState.limit() != null})
     * 拒绝命中 → 强制下次 Read 走 full read 拿到新内容, 避免 LLM 拿到 stale content。
     *
     * <p>WHY 必须有 ctx 才写入: R1 已彻底删除实例级 fallback (见 ReadFileTool 注释),
     * EditFileTool/WriteFileTool 也遵循相同契约 — 无 ctx 时无会话边界, 不参与 cache。
     *
     * <p>key 格式: 与 ReadFileTool 一致, 使用 {@code relPath} (LLM 入参原始字符串) 作 key。
     * 经实测 ReadFileTool {@code dispatchText} 用同一 key, 接线等价。
     */
    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        // [L+ round 3] 先跑 validateInput 门禁 (ENOENT 豁免 + read-before-write + stale-write).
        // 对齐 CC FileWriteTool.ts:153-221 validateInput 阶段先于 call() 执行.
        // [L+ round 4] ctx 为 null → validateInput 内部已拒绝, 此处不再 null 兜底.
        ValidationResult vr = validateInput(call.input(), ctx);
        if (!vr.ok()) {
            return ToolResult.error(call.id(),
                "WriteFileTool validateInput failed: " + vr.message());
        }
        ToolResult result = executeInternal(call, ctx);
        // 仅写成功后 invalidate cache; 错误结果不动 cache (避免污染)
        if (!com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(result.data()) && ctx != null) {
            String relPath = call.input().path("file_path").asText("");
            try {
                Path file = guard.resolve(relPath);
                long mtime = Files.getLastModifiedTime(file).toMillis();
                // [L+ round 3] CRLF 归一化 + 归一化 key, 与 Edit 对齐.
                // [IMP-D2] 编码感知读: utf16le 文件写回后不能 Files.readString（UTF-8 乱码）,
                //   用 FileEncodingReader.readFileMetadata 按 BOM 解码。
                String updatedContent = FileEncodingReader.readFileMetadata(file).content();
                String keyForCache = ToolUseContext.keyForReadFileState(guard, relPath);
                // offset=null / limit=null: 让 ReadFileTool dedup 守卫拒绝命中 (CC FileEditTool.ts:520 对齐)
                ctx.readFileState().set(keyForCache, ReadState.full(mtime, updatedContent));
                if (log.isInfoEnabled()) {
                    log.info("WriteFileTool: 写文件后 invalidate readFileState: path={} mtime={}",
                        relPath, mtime);
                }
            } catch (Exception e) {
                // stat 失败不阻塞主流程, 但 warn 暴露, 防止 dedup stale content 静默累积
                if (log.isWarnEnabled()) {
                    log.warn("WriteFileTool: 写文件后无法 stat 文件 invalidate readFileState: path={} cause={}",
                        relPath, e.toString());
                }
            }
        }
        return result;
    }

    private ToolResult executeInternal(ToolUseBlock call, ToolUseContext ctx) {
        String relPath = call.input().path("file_path").asText("");
        String content = call.input().path("content").asText("");

        if (relPath.isBlank()) {
            return ToolResult.error(call.id(), "path is empty");
        }

        Path file;
        try {
            file = guard.resolve(relPath);
        } catch (SecurityException se) {
            log.warn("WriteFileTool: blocked path escape: {}", relPath);
            return ToolResult.error(call.id(), se.getMessage());
        }

        // P1-2: 动态技能发现 + 条件技能激活 · 对齐 CC FileWriteTool.ts:232-245
        //   （在 call() 开头、写文件前触发；fire-and-forget 不阻塞工具调用链）
        triggerDynamicSkillDiscovery(ctx, file);

        // [OPD-TOOL-06-4] 写文件前 fileHistoryTrackEdit 备份（pre-edit 内容）· 对齐 CC FileWriteTool.ts:255-263
        //   （位于 mkdir 之后、write 之前，备份 pre-edit 内容）
        trackFileHistory(ctx, file);

        try {
            // 读原文件内容（对齐 CC FileWriteTool.ts:268-277 + :298 oldContent = meta?.content）——
            // 文件已存在 → update 分支；不存在 → create 分支。无 create 入参（C-29 已删）。
            // [IMP-D2] W24 编码检测：readFileSyncWithMetadata 按 BOM 检测 encoding + CRLF 归一；
            //   utf16le 文件不再恒 UTF-8 读（乱码）。新文件默认 utf8（CC meta?.encoding ?? 'utf8'）。
            boolean existedBefore = Files.exists(file);
            String encoding = FileEncodingReader.UTF8;
            String oldContent = null;
            if (existedBefore) {
                FileEncodingReader.FileMetadata meta = FileEncodingReader.readFileMetadata(file);
                oldContent = meta.content();   // CRLF 归一（对齐 CC meta.content）
                encoding = meta.encoding();
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // [IMP-D2] W26 编码保留：保留检测 encoding（utf16le 用 UTF-16LE 写回；
            //   BOM 随 content U+FEFF 保真，不强制前置——对齐 CC writeTextContent，PROBE-BOM DC-1 收敛），
            //   行尾恒 LF（对齐 CC writeTextContent(fullFilePath, content, enc, 'LF')
            //   ——模型 content 自带行尾语义，不做 CRLF 改写）。
            FileEncodingReader.writeTextContent(file, content, encoding,
                FileEncodingReader.LineEndingType.LF);
            log.info("WriteFileTool: wrote {} bytes to {} (type={} encoding={})",
                content.getBytes().length, relPath, existedBefore ? "update" : "create", encoding);
            // [OPD-TOOL-06-4] 写盘后 LSP didChange/didSave 通知 · 对齐 CC FileWriteTool.ts:311-323
            //   （clearDeliveredDiagnosticsForFile + changeFile + saveFile）
            notifyLspChange(file, content);
            // ── 结构化输出契约（对齐 CC FileWriteTool.ts:369-401 data + :418-433 mapToolResult） ──
            // E1: Write 输出 {type, filePath, content, structuredPatch, originalFile, gitDiff?}
            String filePath = file.toAbsolutePath().normalize().toString();
            ToolUseDiff gitDiff = GitDiffFetcher.isEnabled() ? GitDiffFetcher.fetch(file) : null;
            Map<String, Object> structuredOutput = new LinkedHashMap<>();
            String summary;
            if (existedBefore) {
                // update 分支（CC FileWriteTool.ts:373-378）
                List<StructuredPatchHunk> patch = StructuredPatchGenerator.getPatch(oldContent, content);
                // 行变更计数（对齐 CC FileWriteTool.ts:380-386 countLinesChanged(patch)）——数据流日志消费
                CountLinesChanged.countLinesChanged(patch, null);
                structuredOutput.put("type", "update");              // CC data.type（FileWriteTool.ts:373）
                structuredOutput.put("filePath", filePath);          // CC data.filePath（:374）
                structuredOutput.put("content", content);            // CC data.content（:375）
                structuredOutput.put("structuredPatch", patch);      // CC data.structuredPatch（:376）
                structuredOutput.put("originalFile", oldContent);    // CC data.originalFile（:377）
                if (gitDiff != null) {
                    structuredOutput.put("gitDiff", gitDiff);        // CC data.gitDiff（:378）
                }
                summary = "The file " + filePath + " has been updated successfully.";  // CC map（:426-430）
            } else {
                // create 分支（CC FileWriteTool.ts:396-401）
                // 新建文件全行算新增（对齐 CC FileWriteTool.ts:403-406 countLinesChanged([], content)）
                CountLinesChanged.countLinesChanged(List.of(), content);
                structuredOutput.put("type", "create");              // CC data.type（:396）
                structuredOutput.put("filePath", filePath);          // CC data.filePath（:397）
                structuredOutput.put("content", content);            // CC data.content（:398）
                structuredOutput.put("structuredPatch", List.of());  // CC data.structuredPatch=[]（:399）
                structuredOutput.put("originalFile", null);          // CC data.originalFile=null（:400）
                if (gitDiff != null) {
                    structuredOutput.put("gitDiff", gitDiff);        // CC data.gitDiff（:401）
                }
                summary = "File created successfully at: " + filePath;  // CC map（:424）
            }
            // HOOK-WIRE: FileChanged — 写文件后 emit (对齐 CC hooks.ts:4280 event 值域
            //   'change'|'add'|'unlink')。existedBefore → change（覆盖已有文件）；
            //   新建 → add。旧值 'write'/'create' 不在 CC 值域内，按值域配置 matcher 失配
            //   （IMP-HOOKS-S5 D-08）；CC 由 fs watcher 触发 (fileChangedWatcher.ts:75-85),
            //   Java 以工具写盘事件等效触发
            try {
                if (hookRegistry != null) {
                    String event = existedBefore ? "change" : "add";
                    hookRegistry.fireFileChanged(relPath, event, null);
                }
            } catch (Exception hookEx) {
                log.warn("WriteFileTool: HOOK FileChanged failed: {}", hookEx.getMessage());
            }
            // [RES-07d] VSCode SDK file_updated 通知（对齐 CC FileWriteTool consumer，
            // vscodeSdkMcp.ts:39-59；userType=ant + client 存在才发送，否则内部跳过）
            notifyVscodeFileUpdated(relPath, oldContent, content);
            if (log.isDebugEnabled()) {
                log.debug("WriteFileTool: 结构化输出组装完成 path={} type={} gitDiff={}",
                    filePath, existedBefore ? "update" : "create", gitDiff != null);
            }
            return ToolResult.successWithStructuredOutput(call.id(), summary, structuredOutput);
        } catch (Exception e) {
            log.error("WriteFileTool: error writing {}", file, e);
            return ToolResult.error(call.id(), "Write error: " + e.getMessage());
        }
    }

    /**
     * P1-2: 动态技能发现 + 条件技能激活 · 对齐 CC FileWriteTool.ts:232-245。
     *
     * <p>CC original（FileWriteTool.ts:234-245）：
     * <pre>
     *   const newSkillDirs = await discoverSkillDirsForPaths([fullFilePath], cwd)
     *   if (newSkillDirs.length > 0) {
     *     for (const dir of newSkillDirs) dynamicSkillDirTriggers?.add(dir)
     *     addSkillDirectories(newSkillDirs).catch(() => {})     // fire-and-forget
     *   }
     *   activateConditionalSkillsForPaths([fullFilePath], cwd)
     * </pre>
     * Java 同步执行（规则五：确定性流程用代码），try-catch 不阻塞工具调用链。
     *
     * @param ctx          工具调用上下文（null → 跳过；dynamicSkillDirTriggers 供 attachment 显示）
     * @param fullFilePath 归一化绝对文件路径
     */
    private void triggerDynamicSkillDiscovery(ToolUseContext ctx, Path fullFilePath) {
        if (dynamicSkillsManager == null || ctx == null || ctx.effectiveCwd() == null) {
            return;
        }
        try {
            // CC :234 discoverSkillDirsForPaths([fullFilePath], cwd) —— 从文件父目录向上走
            java.util.List<String> newSkillDirs = dynamicSkillsManager.discoverSkillDirsForPaths(
                java.util.List.of(fullFilePath.toString()), ctx.effectiveCwd());
            if (!newSkillDirs.isEmpty()) {
                // CC :238 dynamicSkillDirTriggers?.add(dir) —— 供 per-turn attachment 装配显示
                for (String dir : newSkillDirs) {
                    ctx.dynamicSkillDirTriggers().add(dir);
                }
                // CC :241 addSkillDirectories(newSkillDirs).catch(()=>{}) —— fire-and-forget
                dynamicSkillsManager.addSkillDirectories(newSkillDirs);
            }
            // CC :245 activateConditionalSkillsForPaths([fullFilePath], cwd)
            dynamicSkillsManager.activateConditionalSkillsForPaths(
                java.util.List.of(fullFilePath.toString()), ctx.effectiveCwd());
        } catch (Exception e) {
            // 技能发现失败不阻塞写文件（CC .catch(()=>{}) 等价 · 规则十二显式暴露但非主链异常）
            if (log.isDebugEnabled()) {
                log.debug("WriteFileTool: 动态技能发现失败, 不阻塞工具: cause={}", e.toString());
            }
        }
    }

    /**
     * [G2] tool_result 块 · 对齐 CC {@code FileWriteTool.ts:418-433 mapToolResultToToolResultBlockParam}
     * （成功路径被调 toolExecution.ts:1292）。
     *
     * <p>Java 端结构化字段在 {@link ToolResult#structuredOutput}（type/filePath，
     * WriteFileTool.java:414-431 对齐 CC FileWriteTool.ts:369-401 data），本 mapper 读该 map
     * 重建 CC 同款 content（CC :420-432）:
     * <ul>
     *   <li>create → {@code File created successfully at: ${filePath}}</li>
     *   <li>update → {@code The file ${filePath} has been updated successfully.}</li>
     * </ul>
     *
     * @param result 工具执行结果（structuredOutput 含 type/filePath）
     * @return tool_result 块（tool_use_id/type/content/is_error）
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (!(result instanceof ToolResult<?> tr)) {
            return null;
        }
        Map<String, Object> so = ToolResult.presentationMeta(tr);
        String filePath = so.get("filePath") instanceof String s ? s : "";
        String type = so.get("type") instanceof String s ? s : "";
        String content;
        if ("create".equals(type)) {
            content = "File created successfully at: " + filePath;
        } else {
            content = "The file " + filePath + " has been updated successfully.";
        }
        if (log.isDebugEnabled()) {
            log.debug("WriteFileTool.mapToToolResultBlockParam: id={} type={} filePath={} contentLen={}（CC FileWriteTool.ts:418-433）",
                toolUseId, type, filePath, content.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }
}
