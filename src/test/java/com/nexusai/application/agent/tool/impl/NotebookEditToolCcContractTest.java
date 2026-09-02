package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-D3 · NotebookEditTool 对齐 CC NotebookEditTool.ts 的契约测试。
 *
 * <p>WHY 覆盖意图（CLAUDE.md 规则九）：
 * <ul>
 *   <li><b>HIGH R-1 read-before-edit + mtime</b>：模型未读（errorCode 9）或文件已被外部修改
 *       （errorCode 10）时直接覆盖 notebook → 静默数据丢失。这是 IMP-D3 的核心数据完整性门禁，
 *       与 FileEditTool/FileWriteTool 的 read-before-write 同一语义族。</li>
 *   <li><b>缺文件拒绝 errorCode 1（⊕-9）</b>：CC 拒绝而非新建空 notebook（意外文件副作用）。</li>
 *   <li><b>insert 插开头（⊕-11）</b>：无 cell_id → cellIndex=0 插到开头，非追加末尾。</li>
 *   <li><b>写回 readFileState.set</b>：写后 offset/limit=undefined 破坏 ReadFileTool dedup，
 *       对齐 CC :437-442。</li>
 * </ul>
 */
@DisplayName("IMP-D3 · NotebookEditTool CC 契约（validateInput 10 errorCode + insert 开头 + 写回 readFileState）")
class NotebookEditToolCcContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static NotebookEditTool wiredTool(PathGuard guard) {
        NotebookEditTool tool = new NotebookEditTool();
        tool.setGuard(guard);
        return tool;
    }

    private static ToolUseContext ctx(Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()),
            PermissionMode.DEFAULT, Map.of(), false, "", effectiveCwd);
    }

    private static ObjectNode input(String notebookPath, String editMode, String cellType, String cellId) {
        ObjectNode node = JSON.createObjectNode();
        node.put("notebook_path", notebookPath);
        if (editMode != null) node.put("edit_mode", editMode);
        if (cellType != null) node.put("cell_type", cellType);
        if (cellId != null) node.put("cell_id", cellId);
        node.put("new_source", "print(1)");
        return node;
    }

    private static Path writeNotebook(Path dir, String name) throws Exception {
        Path nb = dir.resolve(name);
        ObjectNode root = JSON.createObjectNode();
        root.put("nbformat", 4);
        root.put("nbformat_minor", 5);
        ObjectNode meta = root.putObject("metadata");
        meta.putObject("language_info").put("name", "python");
        ArrayNode cells = root.putArray("cells");
        ObjectNode c0 = cells.addObject();
        c0.put("cell_type", "code");
        c0.put("id", "cell-0");
        c0.put("source", "print('a')");
        c0.putObject("metadata");
        c0.put("execution_count", 1);
        c0.putArray("outputs");
        ObjectNode c1 = cells.addObject();
        c1.put("cell_type", "markdown");
        c1.put("id", "cell-1");
        c1.put("source", "# hi");
        c1.putObject("metadata");
        Files.writeString(nb, JSON.writeValueAsString(root));
        return nb;
    }

    /** 标记文件已读（对齐 ReadFileTool notebook 分支写 readFileState 的 key 派生）。 */
    private static void markRead(ToolUseContext tctx, PathGuard guard, Path nb) throws Exception {
        String key = ToolUseContext.keyForReadFileState(guard, nb.toString());
        long mtime = Files.getLastModifiedTime(nb).toMillis();
        tctx.readFileState().set(key, ToolUseContext.ReadState.full(mtime));
    }

    // ───────────────────────────── read-before-edit + mtime（HIGH R-1） ─────────────────────────────

    @Test
    @DisplayName("未读文件 → errorCode 9（read-before-edit 门禁，HIGH R-1）")
    void validateInput_notReadYet_errorCode9(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);
        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(nb.toString(), "replace", null, "cell-0"), tctx);

        assertThat(r.ok()).as("未读 notebook 编辑必须拒绝（CC NotebookEditTool.ts:221-229 errorCode 9）")
            .isFalse();
        assertThat(r.errorCode()).isEqualTo("9");
    }

    @Test
    @DisplayName("已读但文件被外部修改 → errorCode 10（mtime 门禁，HIGH R-1）")
    void validateInput_mtimeChanged_errorCode10(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);
        // 以过去 5s 的 mtime 标记已读 → 当前文件 mtime 更大 → errorCode 10
        String key = ToolUseContext.keyForReadFileState(guard, nb.toString());
        long past = Files.getLastModifiedTime(nb).toMillis() - 5_000L;
        tctx.readFileState().set(key, ToolUseContext.ReadState.full(past));
        // 外部修改：重写文件让 mtime 前进
        Files.writeString(nb, "modified");

        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(nb.toString(), "replace", null, "cell-0"), tctx);

        assertThat(r.ok()).as("外部修改后直接覆盖必须拒绝（CC NotebookEditTool.ts:230-237 errorCode 10）")
            .isFalse();
        assertThat(r.errorCode()).isEqualTo("10");
    }

    @Test
    @DisplayName("缺文件 → errorCode 1（CC 拒绝而非新建空 notebook，⊕-9）")
    void validateInput_missingFile_errorCode1() throws Exception {
        // 用项目 target/ 下合成绝对路径（对齐 NotebookEditToolPermissionTest 同款约束）：
        //   %TEMP% 含 Windows 8.3 短名（ADMINI~1）→ 文件删除后 PathGuard.toRealPath 与
        //   normalize 产生不同路径串（8.3 短名 vs 长名），readFileState key 错位 → 误报 errorCode 9。
        //   target/ 下无 8.3 短名，key 派生一致，读文件分支 ENOENT → errorCode 1。
        Path ws = Paths.get("target", "d3-missing-" + UUID.randomUUID().toString().substring(0, 8))
            .toAbsolutePath();
        Files.createDirectories(ws);
        Path nb = writeNotebook(ws, "nb.ipynb");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);
        markRead(tctx, guard, nb);   // 已读（readFileState 存在）→ 跳过 errorCode 9
        Files.delete(nb);            // 再删除 → 读文件分支 ENOENT → errorCode 1

        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(nb.toString(), "replace", null, "cell-0"), tctx);

        assertThat(r.ok()).as("缺文件必须拒绝 errorCode 1，不新建空 notebook（CC :243-249）")
            .isFalse();
        assertThat(r.errorCode()).isEqualTo("1");
    }

    // ───────────────────────────── 其余 errorCode（CC 10 种链） ─────────────────────────────

    @Test
    @DisplayName("非 .ipynb → errorCode 2")
    void validateInput_nonIpynb_errorCode2(@TempDir Path ws) throws Exception {
        Path txt = ws.resolve("note.txt");
        Files.writeString(txt, "hello");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);

        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(txt.toString(), "replace", null, "cell-0"), tctx);

        assertThat(r.errorCode()).isEqualTo("2");
    }

    @Test
    @DisplayName("edit_mode 非法 → errorCode 4")
    void validateInput_invalidEditMode_errorCode4(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);

        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(nb.toString(), "rename", null, "cell-0"), tctx);

        assertThat(r.errorCode()).isEqualTo("4");
    }

    @Test
    @DisplayName("insert 缺 cell_type → errorCode 5")
    void validateInput_insertWithoutCellType_errorCode5(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);

        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(nb.toString(), "insert", null, "cell-0"), tctx);

        assertThat(r.errorCode()).isEqualTo("5");
    }

    @Test
    @DisplayName("非法 notebook JSON → errorCode 6")
    void validateInput_invalidJson_errorCode6(@TempDir Path ws) throws Exception {
        Path nb = ws.resolve("bad.ipynb");
        Files.writeString(nb, "not-json{");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);
        markRead(tctx, guard, nb);

        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(nb.toString(), "replace", null, "cell-0"), tctx);

        assertThat(r.errorCode()).isEqualTo("6");
    }

    @Test
    @DisplayName("非 insert 缺 cell_id → errorCode 7")
    void validateInput_missingCellId_errorCode7(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);
        markRead(tctx, guard, nb);

        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(nb.toString(), "replace", null, null), tctx);

        assertThat(r.errorCode()).isEqualTo("7");
    }

    @Test
    @DisplayName("cell_id 找不到 → errorCode 8")
    void validateInput_cellIdNotFound_errorCode8(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);
        markRead(tctx, guard, nb);

        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(nb.toString(), "replace", null, "no-such-id"), tctx);

        assertThat(r.errorCode()).isEqualTo("8");
    }

    @Test
    @DisplayName("cell-N 数值索引越界 → errorCode 7（parseCellId 回退，CC utils/notebook.ts:217-224）")
    void validateInput_cellIndexOutOfRange_errorCode7(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);
        markRead(tctx, guard, nb);

        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(nb.toString(), "replace", null, "cell-99"), tctx);

        assertThat(r.errorCode()).isEqualTo("7");
    }

    @Test
    @DisplayName("合法输入 → pass（已读 + cell_id 命中 + replace）")
    void validateInput_valid_passes(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        PathGuard guard = new PathGuard(ws);
        ToolUseContext tctx = ctx(ws);
        markRead(tctx, guard, nb);

        Tool.ValidationResult r = wiredTool(guard).validateInput(
            input(nb.toString(), "replace", null, "cell-0"), tctx);

        assertThat(r.ok()).as("已读 + 合法 cell_id + replace → pass（CC :293 return {result:true}）")
            .isTrue();
    }

    // ───────────────────────────── insert 插开头 + replace 变更语义（执行） ─────────────────────────────

    @Test
    @DisplayName("insert 无 cell_id → 插到开头（cellIndex=0，⊕-11 修正追加末尾）")
    void execute_insertNoCellId_insertsAtBeginning(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        NotebookEditTool tool = wiredTool(new PathGuard(ws));
        ObjectNode in = input(nb.toString(), "insert", "code", null);

        var result = tool.execute(new ToolUseBlock("call-1", "NotebookEdit", in));

        JsonNode saved = JSON.readTree(Files.readString(nb));
        ArrayNode cells = (ArrayNode) saved.get("cells");
        assertThat(cells.size()).isEqualTo(3);
        assertThat(cells.get(0).path("cell_type").asText())
            .as("无 cell_id 时 insert 必须插入到开头（CC NotebookEditTool.ts:351-352 cellIndex=0）")
            .isEqualTo("code");
        assertThat(cells.get(0).path("source").asText()).isEqualTo("print(1)");
    }

    @Test
    @DisplayName("insert 有 cell_id → 插到该 cell 之后（insert-after，CC :365-367）")
    void execute_insertAfterCell(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        NotebookEditTool tool = wiredTool(new PathGuard(ws));
        ObjectNode in = input(nb.toString(), "insert", "markdown", "cell-0");

        var result = tool.execute(new ToolUseBlock("call-1", "NotebookEdit", in));

        JsonNode saved = JSON.readTree(Files.readString(nb));
        ArrayNode cells = (ArrayNode) saved.get("cells");
        assertThat(cells.size()).isEqualTo(3);
        assertThat(cells.get(1).path("cell_type").asText())
            .as("insert 应插到 cell-0 之后（原 cell-1 前移，CC :365-367 cellIndex+=1）")
            .isEqualTo("markdown");
    }

    @Test
    @DisplayName("replace 重置 execution_count + 清空 outputs + 支持 cell_type 转换（CC :416-428）")
    void execute_replace_resetsOutputsAndExecutionCount(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        NotebookEditTool tool = wiredTool(new PathGuard(ws));
        ObjectNode in = input(nb.toString(), "replace", "markdown", "cell-0");
        in.put("new_source", "# replaced");

        var result = tool.execute(new ToolUseBlock("call-1", "NotebookEdit", in));

        JsonNode saved = JSON.readTree(Files.readString(nb));
        ArrayNode cells = (ArrayNode) saved.get("cells");
        JsonNode cell0 = cells.get(0);
        assertThat(cell0.path("source").asText()).isEqualTo("# replaced");
        assertThat(cell0.path("cell_type").asText())
            .as("cell_type 转换必须生效（CC :425-427）")
            .isEqualTo("markdown");
        assertThat(cell0.path("execution_count").isNull())
            .as("replace 必须重置 execution_count=null（CC :422-424，防残留旧计数）")
            .isTrue();
        assertThat(cell0.path("outputs").isArray() && cell0.path("outputs").isEmpty())
            .as("replace 必须清空 outputs（CC :423，防残留旧输出）")
            .isTrue();
    }

    @Test
    @DisplayName("写回后 readFileState.set（offset/limit=null 破坏 ReadFileTool dedup，CC :437-442）")
    void execute_writeBack_readFileStateSet(@TempDir Path ws) throws Exception {
        Path nb = writeNotebook(ws, "nb.ipynb");
        PathGuard guard = new PathGuard(ws);
        NotebookEditTool tool = wiredTool(guard);
        ToolUseContext tctx = ctx(ws);
        ObjectNode in = input(nb.toString(), "insert", "code", null);

        tool.execute(new ToolUseBlock("call-1", "NotebookEdit", in), tctx);

        String key = ToolUseContext.keyForReadFileState(guard, nb.toString());
        ToolUseContext.ReadState rs = tctx.readFileState().get(key);
        assertThat(rs).as("写后必须写入 readFileState（CC :437-442）").isNotNull();
        assertThat(rs.offset()).as("写后 offset=undefined 破坏 ReadFileTool dedup").isNull();
        assertThat(rs.limit()).as("写后 limit=undefined 破坏 ReadFileTool dedup").isNull();
        assertThat(rs.content()).isNotNull();
    }
}
