package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.WritePermissionChecker;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * s02 NotebookEditTool — 对齐 CC NotebookEditTool.ts.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>输入: {@code notebook_path}, {@code cell_id} 或 {@code cell_type}, {@code new_source}</li>
 *   <li>操作: replace / insert / delete cell in Jupyter notebook (.ipynb JSON)</li>
 *   <li>教学版: 支持读取 .ipynb + 修改 cell + 写回 (notebook JSON 结构)</li>
 * </ul>
 */
@Component
public class NotebookEditTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(NotebookEditTool.class);

    public static final String NAME = "NotebookEdit";

    /**
     * [S06 接线 · X12] 写权限检查器 · 对齐 CC {@code checkWritePermissionForTool}
     * （filesystem.ts:1205-1412）。{@code @Autowired(required=false)} + setter 模式与
     * GlobTool/GrepTool 一致（本工具为 @Component 自动收集，字段注入生效）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.permission.WritePermissionChecker permissionChecker;

    /** 测试/装配用 setter · 与 GlobTool 同模式。 */
    public void setPermissionChecker(
            com.nexusai.application.agent.permission.WritePermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    /**
     * [IMP-D3] PathGuard · 对齐 CC {@code safeResolvePath}（filesystem.ts）。read-before-edit
     * 门禁（validateInput errorCode 9/10）经 {@link ToolUseContext#keyForReadFileState}
     * 派生 key，与 ReadFileTool 读 notebook 写入的 key 同源（同一 PathGuard bean，
     * {@code ToolConfig:29} 以 {@code user.dir} 为 workdir）。{@code @Autowired(required=false)}
     * + setter 与 permissionChecker 同模式（本工具 @Component 自动收集）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PathGuard guard;

    /** 测试/装配用 setter。 */
    public void setGuard(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Edit a Jupyter notebook (.ipynb). Supports replace / insert / delete cell operations. "
                + "Returns the updated notebook structure.";
    }

    /** 是否延迟执行 · 对齐 CC NotebookEditTool.ts:94 shouldDefer: true（常量，与 input 无关）。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode notebookPath = props.putObject("notebook_path");
        notebookPath.put("type", "string");
        notebookPath.put("description", "Absolute path to the .ipynb file.");

        ObjectNode cellId = props.putObject("cell_id");
        cellId.put("type", "string");
        cellId.put("description", "Cell ID to operate on (for replace/delete).");

        ObjectNode cellType = props.putObject("cell_type");
        cellType.put("type", "string");
        cellType.putArray("enum").add("code").add("markdown");
        cellType.put("description", "Cell type for new cells (for insert).");

        ObjectNode newSource = props.putObject("new_source");
        newSource.put("type", "string");
        newSource.put("description", "New source content for the cell.");

        ObjectNode editMode = props.putObject("edit_mode");
        editMode.put("type", "string");
        editMode.putArray("enum").add("replace").add("insert").add("delete");
        editMode.put("default", "replace");
        // CC NotebookEditTool.ts:50-56 — edit_mode 描述
        editMode.put("description", "The edit operation: " +
            "'replace' (default) updates cell_id contents; " +
            "'insert' creates a new cell after cell_id (cell_id required); " +
            "'delete' removes cell_id.");

        // [G12] new_source 必填 · 对齐 CC NotebookEditTool.ts:43 `new_source: z.string()`
        //   （非 optional）；旧 schema 仅 notebook_path required，缺 new_source 时 execute
        //   静默写空串 —— 偏离 CC schema 契约。
        schema.putArray("required").add("notebook_path").add("new_source");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 路径扩展点 · CC original: {@code getPath(input) → input.notebook_path}
     * （{@code NotebookEditTool.ts:122}）。
     *
     * <p>权限管线（CC {@code filesystem.ts:1211-1217}）用本方法取本次编辑的 notebook 路径
     * 做权限检查。Java 端 notebook_path 为必填（schema required），缺失返回 null → 走 ask。
     *
     * @param input 工具输入（含 {@code notebook_path}）
     * @return 本次编辑的 notebook 路径；缺失返回 null
     */
    @Override
    public String getPath(JsonNode input) {
        return input == null ? null : input.path("notebook_path").asText(null);
    }

    /**
     * 工具级语义验证 · 对齐 CC {@code NotebookEditTool.ts:176-294}（10 种 errorCode）。
     *
     * <p><b>HIGH R-1</b>：read-before-edit 门禁（errorCode 9）+ mtime 检查（errorCode 10）——
     * 模型未读或文件已外部修改时直接覆盖 notebook → 静默数据丢失。CC 顺序：
     * resolve → UNC 跳过 → 扩展名(2) → edit_mode(4) → insert cell_type(5) →
     * readFileState(9) → mtime(10) → 读文件 ENOENT(1)/非法 JSON(6) → cell_id(7/8)。
     *
     * @param input LLM 给的参数（含 {@code notebook_path}）
     * @param ctx   工具调用上下文（含 readFileState；管线调用恒非 null）
     * @return 验证结果（fail 含 errorCode + message 注入 LLM 让模型自纠）
     */
    @Override
    public Tool.ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String notebookPath = input == null ? null : input.path("notebook_path").asText(null);
        if (notebookPath == null || notebookPath.isBlank()) {
            return Tool.ValidationResult.pass();  // execute() 内部会拒空 path
        }
        String cellId = input.path("cell_id").asText(null);
        String cellType = input.path("cell_type").asText(null);
        String editMode = input.path("edit_mode").asText("replace");

        // resolve 绝对路径（CC :180-182：isAbsolute ? notebook_path : resolve(cwd, notebook_path)）
        // [CC 对齐 2026-09-03] PathGuard 逃逸拦截已删（resolve 纯展开不抛；不再 toRealPath → 8.3 短名
        //   误报场景也不存在），原 catch 回退（①越狱 ②8.3 短名）死代码删除。
        Path path;
        String key;
        if (guard != null) {
            path = guard.resolve(notebookPath);
            key = ToolUseContext.keyForReadFileState(guard, notebookPath);
        } else {
            path = Paths.get(notebookPath);
            key = null;
        }
        String fullPath = path.toAbsolutePath().normalize().toString();
        if (key == null) {
            key = fullPath;
        }

        // SECURITY: UNC 路径跳过文件系统（CC :184-187 防 NTLM 凭据泄露）
        if (fullPath.startsWith("\\\\") || fullPath.startsWith("//")) {
            return Tool.ValidationResult.pass();
        }

        // errorCode 2：非 .ipynb（CC :189-196）
        if (!fullPath.toLowerCase().endsWith(".ipynb")) {
            return Tool.ValidationResult.fail("2",
                "File must be a Jupyter notebook (.ipynb file). For editing other file types, use the FileEdit tool.");
        }

        // errorCode 4：edit_mode 非法（CC :198-208）
        if (!"replace".equals(editMode) && !"insert".equals(editMode) && !"delete".equals(editMode)) {
            return Tool.ValidationResult.fail("4", "Edit mode must be replace, insert, or delete.");
        }

        // errorCode 5：insert 缺 cell_type（CC :210-216）
        if ("insert".equals(editMode) && (cellType == null || cellType.isBlank())) {
            return Tool.ValidationResult.fail("5", "Cell type is required when using edit_mode=insert.");
        }

        // read-before-edit 门禁（CC :218-229）· key 与 ReadFileTool 同源派生
        //   （ToolUseContext.keyForReadFileState，ReadFileTool notebook 分支读后写入同 key）。
        ToolUseContext.ReadState readState = ctx.readFileState().get(key);
        if (readState == null) {
            // errorCode 9：文件尚未读
            return Tool.ValidationResult.fail("9",
                "File has not been read yet. Read it first before writing to it.");
        }
        // errorCode 10：文件自读后已被外部修改（CC :230-237，mtime > 读时间戳）
        try {
            long lastWrite = Files.getLastModifiedTime(path).toMillis();
            if (lastWrite > readState.mtimeMillis()) {
                return Tool.ValidationResult.fail("10",
                    "File has been modified since read, either by the user or by a linter. " +
                    "Read it again before attempting to write to it.");
            }
        } catch (java.io.IOException e) {
            // stat 失败（文件可能刚被删）：仅跳过 mtime 门禁，继续读文件分支
            // （ENOENT → errorCode 1）。不得 return pass() —— 那会短路整个校验放行缺文件。
            if (log.isDebugEnabled()) {
                log.debug("NotebookEditTool: validateInput stat 失败跳过 errorCode 10 path={} cause={}",
                    fullPath, e.toString());
            }
        }

        // 读文件内容（CC :239-259）
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            // ENOENT → errorCode 1（CC :243-249，缺文件拒绝，不新建空 notebook ⊕-9）
            return Tool.ValidationResult.fail("1", "Notebook file does not exist.");
        }
        JsonNode notebook;
        try {
            notebook = new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
        } catch (Exception e) {
            return Tool.ValidationResult.fail("6", "Notebook is not valid JSON.");
        }
        if (!(notebook instanceof com.fasterxml.jackson.databind.node.ObjectNode)
                || !notebook.has("cells")
                || !(notebook.get("cells") instanceof com.fasterxml.jackson.databind.node.ArrayNode)) {
            return Tool.ValidationResult.fail("6", "Notebook is not valid JSON.");
        }
        var cells = (com.fasterxml.jackson.databind.node.ArrayNode) notebook.get("cells");

        // cell_id 校验（CC :260-291）
        if (cellId == null || cellId.isBlank()) {
            if (!"insert".equals(editMode)) {
                // errorCode 7：非 insert 必须指定 cell_id
                return Tool.ValidationResult.fail("7",
                    "Cell ID must be specified when not inserting a new cell.");
            }
        } else {
            int idx = findCellIndex(cells, cellId);
            if (idx < 0) {
                Integer parsed = parseCellId(cellId);
                if (parsed != null) {
                    if (parsed < 0 || parsed >= cells.size()) {
                        // errorCode 7：cell-N 数值索引越界
                        return Tool.ValidationResult.fail("7",
                            "Cell with index " + parsed + " does not exist in notebook.");
                    }
                } else {
                    // errorCode 8：cell_id 找不到
                    return Tool.ValidationResult.fail("8",
                        "Cell with ID \"" + cellId + "\" not found in notebook.");
                }
            }
        }
        return Tool.ValidationResult.pass();
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String notebookPath = readString(input, "notebook_path");
        if (notebookPath == null || notebookPath.isBlank()) {
            return ToolResult.error(call.id(), "missing required input: notebook_path");
        }
        String cellId = readString(input, "cell_id");
        String cellType = readString(input, "cell_type");
        String newSource = readString(input, "new_source");
        String editMode = readString(input, "edit_mode");
        if (editMode == null) editMode = "replace";

        Path path = Paths.get(notebookPath);
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode notebook;
            // CC 对齐（NotebookEditTool.ts:240-248）：文件不存在 → errorCode 1 拒绝，不新建空 notebook。
            // 旧实现新建空 notebook（⊕-9）会静默创建意外文件（数据副作用 HIGH），已按 owner 拍板改为 CC 拒绝语义。
            if (!Files.exists(path)) {
                return ToolResult.error(call.id(), "Notebook file does not exist.");
            }
            // [G13③] 保留原始文件内容字符串供 9 字段输出 original_file（CC NotebookEditTool.ts:451）。
            String originalContent = Files.readString(path, StandardCharsets.UTF_8);
            notebook = om.readTree(originalContent);
            if (!(notebook instanceof com.fasterxml.jackson.databind.node.ObjectNode)
                    || !notebook.has("cells") || !(notebook.get("cells") instanceof com.fasterxml.jackson.databind.node.ArrayNode)) {
                // CC 对齐（NotebookEditTool.ts:249-255）：非有效 notebook JSON → errorCode 6
                return ToolResult.error(call.id(), "Notebook is not valid JSON.");
            }
            var cells = (com.fasterxml.jackson.databind.node.ArrayNode) notebook.get("cells");

            // cell index 解析 · 对齐 CC NotebookEditTool.ts:350-368：
            //   按 id 精确匹配 → parseCellId 数值回退；insert 时 cellIndex += 1（插到该 cell 之后）；
            //   无 cell_id → cellIndex=0（插入到开头，CC :351-352）。
            int cellIndex;
            if (cellId == null || cellId.isBlank()) {
                cellIndex = 0;
            } else {
                cellIndex = findCellIndex(cells, cellId);
                if (cellIndex < 0) {
                    Integer parsed = parseCellId(cellId);
                    if (parsed != null) {
                        cellIndex = parsed;
                    } else {
                        return ToolResult.error(call.id(), "cell not found: " + cellId);
                    }
                }
                if ("insert".equals(editMode)) {
                    cellIndex += 1; // insert-after（CC :365-367）
                }
            }

            // replace→insert 转换（CC :370-377）：试图替换末尾之后 → 转为 insert（cell_type 缺省 code）
            if ("replace".equals(editMode) && cellIndex == cells.size()) {
                editMode = "insert";
                if (cellType == null) {
                    cellType = "code";
                }
            }

            // nbformat>=4.5 才生成 new_cell_id（CC :379-390）：
            //   insert → Math.random().toString(36).substring(2,15)；replace/delete → 沿用 cell_id。
            boolean nbformatAtLeast45 = notebook.path("nbformat").asInt(0) > 4
                || (notebook.path("nbformat").asInt(0) == 4 && notebook.path("nbformat_minor").asInt(0) >= 5);
            String language = notebook.path("metadata").path("language_info").path("name").asText("python");
            String newCellId = null;
            if (nbformatAtLeast45) {
                if ("insert".equals(editMode)) {
                    newCellId = randomBase36(13);
                } else if (cellId != null) {
                    newCellId = cellId;
                }
            }

            // 变更语义（CC :392-428）
            if ("delete".equals(editMode)) {
                if (cellIndex < 0 || cellIndex >= cells.size()) {
                    return ToolResult.error(call.id(), "cell not found: " + cellId);
                }
                cells.remove(cellIndex);
            } else if ("insert".equals(editMode)) {
                ObjectNode newCell = JsonNodeFactory.instance.objectNode();
                if (newCellId != null) {
                    newCell.put("id", newCellId);
                }
                newCell.put("cell_type", cellType);
                newCell.put("source", newSource == null ? "" : newSource);
                newCell.put("metadata", JsonNodeFactory.instance.objectNode());
                if ("code".equals(cellType)) {
                    newCell.put("execution_count", (com.fasterxml.jackson.databind.JsonNode) null);
                    newCell.putArray("outputs");
                }
                cells.insert(cellIndex, newCell);
            } else {
                // replace（CC :416-428）：重置 execution_count + 清空 outputs + 支持 cell_type 转换
                if (cellIndex < 0 || cellIndex >= cells.size()) {
                    return ToolResult.error(call.id(), "cell not found: " + cellId);
                }
                ObjectNode targetCell = (ObjectNode) cells.get(cellIndex);
                targetCell.put("source", newSource == null ? "" : newSource);
                if ("code".equals(targetCell.path("cell_type").asText())) {
                    targetCell.put("execution_count", (com.fasterxml.jackson.databind.JsonNode) null);
                    targetCell.putArray("outputs");
                }
                if (cellType != null && !cellType.equals(targetCell.path("cell_type").asText())) {
                    targetCell.put("cell_type", cellType);
                }
            }

            // 写回 · 对齐 CC :429-432（jsonStringify indent=1 + writeTextContent；不 createDirectories，
            //   缺文件由 validateInput errorCode 1 拒绝，无新建目录副作用）。
            String updatedContent = om.writer(
                    new com.fasterxml.jackson.core.util.DefaultPrettyPrinter()
                        .withObjectIndenter(new com.fasterxml.jackson.core.util.DefaultIndenter(" ", "\n")))
                    .writeValueAsString(notebook);
            Files.writeString(path, updatedContent,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // readFileState.set（CC :437-442）：写后 mtime + offset/limit=undefined →
            //   破坏 ReadFileTool dedup（Read→NotebookEdit→Read 同毫秒返回 file_unchanged stub 问题）。
            if (ctx != null) {
                long writeMtime = Files.getLastModifiedTime(path).toMillis();
                String key = guard != null
                    ? ToolUseContext.keyForReadFileState(guard, notebookPath)
                    : path.toAbsolutePath().normalize().toString();
                ctx.readFileState().set(key,
                    ToolUseContext.ReadState.full(writeMtime, updatedContent));
            }

            log.info("[NotebookEditTool] {} cells in {} (new total={})",
                    editMode, notebookPath, cells.size());
            // [G13③] 9 字段输出 · 对齐 CC NotebookEditTool.ts outputSchema（:60-85）：
            //   new_source / cell_id / cell_type / language / edit_mode / error /
            //   notebook_path / original_file / updated_file。旧 Java 4 字段
            //   （notebook_path/edit_mode/cell_count/message）偏离 CC 已替换。
            //   summary 文案走 CC mapToolResult（mapToToolResultBlockParam 渲染，见 override）。
            Map<String, Object> structuredOutput = new LinkedHashMap<>();
            structuredOutput.put("new_source", newSource == null ? "" : newSource);
            structuredOutput.put("cell_id", newCellId != null ? newCellId : "");
            structuredOutput.put("cell_type", cellType == null ? "code" : cellType);
            structuredOutput.put("language", language);
            structuredOutput.put("edit_mode", editMode);
            structuredOutput.put("error", "");
            structuredOutput.put("notebook_path", notebookPath);
            structuredOutput.put("original_file", originalContent);
            structuredOutput.put("updated_file", updatedContent);
            return ToolResult.successWithStructuredOutput(call.id(),
                renderResultText(newCellId != null ? newCellId : cellId, editMode, newSource),
                structuredOutput);
        } catch (Exception e) {
            log.warn("[NotebookEditTool] failed: {}", e.getMessage());
            return ToolResult.error(call.id(), "notebook edit failed: " + e.getMessage());
        }
    }

    /**
     * [G13③] tool_result 块 · 对齐 CC {@code NotebookEditTool.ts:133-171
     * mapToolResultToToolResultBlockParam}：
     * <ul>
     *   <li>error 字段 → content=error + is_error=true（Java 以 ToolResult.error 已前置处理）</li>
     *   <li>replace → {@code Updated cell ${cell_id} with ${new_source}}</li>
     *   <li>insert → {@code Inserted cell ${cell_id} with ${new_source}}</li>
     *   <li>delete → {@code Deleted cell ${cell_id}}</li>
     *   <li>default → {@code 'Unknown edit mode'}</li>
     * </ul>
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (isError) {
            return Tool.super.mapToToolResultBlockParam(result, toolUseId, isError);
        }
        if (!(result instanceof ToolResult<?> tr)) {
            return null;
        }
        Map<String, Object> so = ToolResult.presentationMeta(tr);
        String cellId = so.get("cell_id") instanceof String s ? s : "";
        String editMode = so.get("edit_mode") instanceof String s ? s : "";
        String newSource = so.get("new_source") instanceof String s ? s : "";
        String content = renderResultText(cellId, editMode, newSource);
        if (log.isDebugEnabled()) {
            log.debug("[NotebookEditTool].mapToToolResultBlockParam: id={} editMode={} cellId={} contentLen={}（CC NotebookEditTool.ts:133-171）",
                toolUseId, editMode, cellId, content.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    /** [G13③] CC NotebookEditTool.ts:145-170 mapToolResult switch 文案。 */
    private static String renderResultText(String cellId, String editMode, String newSource) {
        return switch (editMode == null ? "" : editMode) {
            case "replace" -> "Updated cell " + cellId + " with " + newSource;
            case "insert" -> "Inserted cell " + cellId + " with " + newSource;
            case "delete" -> "Deleted cell " + cellId;
            default -> "Unknown edit mode";
        };
    }

    // ──────────────── [S06 接线 · X12] checkPermissions · 写权限检查 ────────────────

    /**
     * [S06 接线 · X12] 写权限检查 · 对齐 CC {@code NotebookEditTool.ts:125-132}：
     * {@code checkPermissions → checkWritePermissionForTool(NotebookEditTool, input,
     * toolPermissionContext)}（filesystem.ts:1205-1412；T06 探查 E-CALL-02）。
     * Java 等价物 = {@link com.nexusai.application.agent.permission.WritePermissionChecker#check}。
     *
     * <p><b>路径提取</b>：CC 经 {@code tool.getPath(input)} 取路径
     * （NotebookEditTool.ts:122-124 {@code getPath(input) = input.notebook_path}）。[G3]
     * Java {@code WritePermissionChecker.check} 已迁出 extractPath → 直接调用本工具
     * {@link #getPath(JsonNode)}（读取 {@code notebook_path} 字段），不再需要工具侧字段映射。
     *
     * <p><b>数据流纯净</b>：checker 决策的 updatedInput 还原为原 input——CC
     * checkWritePermissionForTool 决策不携带 updatedInput（displayInput = ctx.input），
     * 避免 path 适配副本泄漏到弹窗展示 / hook updatedInput 全替换。
     *
     * <p><b>fail-loud</b>：permissionChecker 未注入 = 装配 bug → ISE（对齐
     * ReadFileTool:408-416 模式，Pattern #11，不再静默放行）。
     *
     * @param input LLM 给的参数（含 {@code notebook_path}）
     * @param ctx   工具调用上下文（含 permissionContext；管线调用恒非 null）
     * @return      写权限决策（Allow / Ask / Deny）
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (permissionChecker == null) {
            throw new IllegalStateException(
                "permissionChecker 未注入, 无法执行 notebook_edit 写权限检查");
        }
        if (log.isDebugEnabled()) {
            log.debug("[NotebookEditTool] checkPermissions 入口: notebook_path={}",
                input == null ? null : input.path("notebook_path").asText(null));
        }
        // [G3] checker 经 tool.getPath(input) 读 notebook_path 字段（NotebookEditTool.ts:122-124）
        PermissionResult result = permissionChecker.check(this, input, ctx);
        if (result instanceof PermissionResult.Allow) {
            if (log.isDebugEnabled()) {
                log.debug("[NotebookEditTool] 写权限放行: notebook_path={}",
                    input == null ? null : input.path("notebook_path").asText(null));
            }
        } else {
            // 关键分支：Ask/Deny（未放行）→ info
            if (log.isInfoEnabled()) {
                log.info("[NotebookEditTool] 写权限未放行: decision={} notebook_path={}",
                    result.getClass().getSimpleName(),
                    input == null ? null : input.path("notebook_path").asText(null));
            }
        }
        return restoreUpdatedInput(result, input);
    }

    /**
     * updatedInput 还原为原 input（CC 决策不携带 updatedInput；见
     * {@link #checkPermissions} 数据流纯净说明）。
     */
    private static PermissionResult restoreUpdatedInput(
            PermissionResult result, JsonNode originalInput) {
        if (originalInput == null) {
            return result;
        }
        if (result instanceof PermissionResult.Allow allow) {
            return new PermissionResult.Allow(
                originalInput, allow.reason(), allow.toolUseID(),
                allow.userModified(), allow.acceptFeedback(), allow.contentBlocks());
        }
        if (result instanceof PermissionResult.Ask ask) {
            return new PermissionResult.Ask(
                ask.message(), ask.reason(), ask.suggestions(),
                ask.blockedPath(), originalInput, ask.metadata(),
                ask.isBashSecurityCheckForMisparsing(),
                ask.pendingClassifierCheck(), ask.contentBlocks());
        }
        return result;
    }

    private int findCellIndex(com.fasterxml.jackson.databind.JsonNode cells, String cellId) {
        for (int i = 0; i < cells.size(); i++) {
            if (cellId.equals(cells.get(i).path("id").asText())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * parseCellId · 对齐 CC {@code utils/notebook.ts:217-224}：
     * {@code /^cell-(\d+)$/} 解析数值索引，非 cell-N 格式返回 null。
     */
    private static Integer parseCellId(String cellId) {
        Matcher m = CELL_ID_PATTERN.matcher(cellId);
        if (m.matches()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static final Pattern CELL_ID_PATTERN = Pattern.compile("^cell-(\\d+)$");

    /**
     * 随机 base36 字符串 · 对齐 CC {@code NotebookEditTool.ts:386}
     * {@code Math.random().toString(36).substring(2,15)}（13 位小写字母数字）。
     */
    private static String randomBase36(int length) {
        final String chars = "0123456789abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(36)));
        }
        return sb.toString();
    }

    private String readString(JsonNode input, String key) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return null;
        }
        return input.get(key).asText();
    }
}