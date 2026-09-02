package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.lsp.LspManager;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.GlobTool;
import com.nexusai.application.agent.tool.impl.GrepTool;
import com.nexusai.application.agent.tool.impl.LspTool;
import com.nexusai.application.agent.tool.impl.NotebookEditTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.application.agent.tool.impl.SyntheticOutputTool;
import com.nexusai.application.agent.tool.impl.WriteFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [G3] P1 接口扩展点测试 · CC Tool.ts 契约（getPath/preparePermissionMatcher/inputJSONSchema）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: CC 把这三个扩展点定义为 Tool 接口可选方法
 * （Tool.ts:397 inputJSONSchema / :506 getPath / :514 preparePermissionMatcher），各工具按
 * CC 语义实现（BashTool.tsx:445-468 子命令 argv + prefix/wildcard；文件工具 getPath→file_path
 * glob 匹配）。Java 若仍把逻辑集中在 HookMatcherEngine / ReadWritePermissionChecker.extractPath
 * / SerializedTool record，就无法按工具实例分发 —— 结构不对齐 CC。本测试断言接口成员存在 +
 * 各工具按 CC 语义实现。
 *
 * @since G3 (WF-G 契约层)
 */
@DisplayName("[G3] Tool 接口扩展点（getPath/preparePermissionMatcher/inputJSONSchema）")
class G3ToolContractExtensionPointsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ────────────────────────────────────────────────────────────────────────
    // 1. 接口成员存在（default null = 未实现，对齐 CC `?` 可选）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. 接口 default 三方法存在且未实现返回 null（对齐 CC 可选成员）")
    void interface_defaults_returnNull() {
        // 意图: CC 三个方法都是可选 (Tool.ts:397/:506/:514, 均带 `?`)。Java default 返回
        //       null 让不实现的工具天然得到「未实现」语义: getPath→ask、preparePermissionMatcher
        //       →ruleContent 非空即 false、inputJSONSchema→回退 inputSchema()。
        Tool plain = new Tool() {
            @Override
            public String name() { return "plain"; }
            @Override
            public String description() { return "plain"; }
            @Override
            public JsonNode inputSchema() {
                return MAPPER.createObjectNode().put("type", "object");
            }
            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) {
                return new ToolResult<>("ok", null, null, null);
            }
        };
        assertThat(plain.getPath(null)).as("getPath 未实现 → null").isNull();
        assertThat(plain.preparePermissionMatcher(null)).as("preparePermissionMatcher 未实现 → null").isNull();
        assertThat(plain.inputJSONSchema()).as("inputJSONSchema 未实现 → null").isNull();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. getPath: 各文件工具按 CC 语义返回路径字段
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2. ReadFileTool.getPath → file_path（CC FileReadTool.ts:385）")
    void readTool_getPath_returnsFilePath() throws Exception {
        // 意图: CC FileReadTool.getPath({file_path}) → file_path (FileReadTool.ts:385)。
        //       ReadPermissionChecker 迁出 extractPath 后靠本方法取路径。
        ReadFileTool tool = new ReadFileTool(new PathGuard(Path.of(".")));
        JsonNode input = MAPPER.readTree("{\"file_path\":\"/abs/read.txt\"}");
        assertThat(tool.getPath(input)).isEqualTo("/abs/read.txt");
    }

    @Test
    @DisplayName("3. EditFileTool.getPath → path（Java schema 字段）")
    void editTool_getPath_returnsPath() throws Exception {
        EditFileTool tool = new EditFileTool(new PathGuard(Path.of(".")));
        JsonNode input = MAPPER.readTree("{\"path\":\"/abs/edit.txt\",\"old_text\":\"a\",\"new_text\":\"b\"}");
        assertThat(tool.getPath(input)).isEqualTo("/abs/edit.txt");
    }

    @Test
    @DisplayName("4. WriteFileTool.getPath → path（Java schema 字段）")
    void writeTool_getPath_returnsPath() throws Exception {
        WriteFileTool tool = new WriteFileTool(new PathGuard(Path.of(".")));
        JsonNode input = MAPPER.readTree("{\"path\":\"/abs/write.txt\",\"content\":\"x\"}");
        assertThat(tool.getPath(input)).isEqualTo("/abs/write.txt");
    }

    @Test
    @DisplayName("5. GlobTool.getPath → path（CC GlobTool.ts:88）")
    void globTool_getPath_returnsPath() throws Exception {
        GlobTool tool = new GlobTool(new PathGuard(Path.of(".")));
        JsonNode input = MAPPER.readTree("{\"path\":\"/abs/dir\",\"pattern\":\"*.java\"}");
        assertThat(tool.getPath(input)).isEqualTo("/abs/dir");
    }

    @Test
    @DisplayName("6. GrepTool.getPath → path（CC GrepTool.ts:195）")
    void grepTool_getPath_returnsPath() throws Exception {
        GrepTool tool = new GrepTool(new PathGuard(Path.of(".")));
        JsonNode input = MAPPER.readTree("{\"path\":\"/abs/dir\",\"pattern\":\"foo\"}");
        assertThat(tool.getPath(input)).isEqualTo("/abs/dir");
    }

    @Test
    @DisplayName("7. NotebookEditTool.getPath → notebook_path（CC NotebookEditTool.ts:122）")
    void notebookEdit_getPath_returnsNotebookPath() throws Exception {
        NotebookEditTool tool = new NotebookEditTool();
        JsonNode input = MAPPER.readTree("{\"notebook_path\":\"/abs/notes.ipynb\",\"cell_id\":\"0\"}");
        assertThat(tool.getPath(input)).isEqualTo("/abs/notes.ipynb");
    }

    @Test
    @DisplayName("8. LspTool.getPath → filePath（CC LSPTool.ts:152）")
    void lspTool_getPath_returnsFilePath() throws Exception {
        LspTool tool = new LspTool(new LspManager());
        JsonNode input = MAPPER.readTree("{\"filePath\":\"/abs/lsp.java\",\"operation\":\"definition\"}");
        assertThat(tool.getPath(input)).isEqualTo("/abs/lsp.java");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. preparePermissionMatcher: 各工具按 CC 语义
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("9. BashTool.preparePermissionMatcher: 复合命令任一子命令命中（CC BashTool.tsx:458）")
    void bashTool_matcher_subcommandAny() throws Exception {
        // 意图: CC BashTool.tsx:456-467 — subcommands argv.join，任一命中即 true。
        //       "ls && git push" 含 git 子命令 → Bash(git *) 命中。
        BashTool tool = new BashTool();
        JsonNode input = MAPPER.readTree("{\"command\":\"ls && git push\"}");
        Predicate<String> matcher = tool.preparePermissionMatcher(input);
        assertThat(matcher).as("BashTool 必须实现 preparePermissionMatcher").isNotNull();
        assertThat(matcher.test("git *")).as("git 子命令命中 git *").isTrue();
        assertThat(matcher.test("npm *")).as("无 npm 子命令 → 不命中").isFalse();
    }

    @Test
    @DisplayName("10. BashTool.preparePermissionMatcher: env 前缀剥离（CC argv 不含 VAR=val）")
    void bashTool_matcher_envPrefixStripped() throws Exception {
        BashTool tool = new BashTool();
        JsonNode input = MAPPER.readTree("{\"command\":\"FOO=bar git push\"}");
        Predicate<String> matcher = tool.preparePermissionMatcher(input);
        assertThat(matcher.test("git *")).as("FOO=bar git push 命中 git *").isTrue();
    }

    @Test
    @DisplayName("11. ReadFileTool.preparePermissionMatcher: file_path glob 匹配")
    void readTool_matcher_globOnFilePath() throws Exception {
        ReadFileTool tool = new ReadFileTool(new PathGuard(Path.of(".")));
        JsonNode input = MAPPER.readTree("{\"file_path\":\"/abs/src/App.java\"}");
        Predicate<String> matcher = tool.preparePermissionMatcher(input);
        assertThat(matcher).as("ReadFileTool 必须实现 preparePermissionMatcher").isNotNull();
        assertThat(matcher.test("/abs/src/*.java")).as("glob 命中 /abs/src/App.java").isTrue();
        assertThat(matcher.test("/abs/other/*.java")).as("其它目录 glob 不命中").isFalse();
    }

    @Test
    @DisplayName("12. GlobTool.preparePermissionMatcher: pattern glob 匹配（CC GlobTool.ts:91）")
    void globTool_matcher_globOnPattern() throws Exception {
        GlobTool tool = new GlobTool(new PathGuard(Path.of(".")));
        JsonNode input = MAPPER.readTree("{\"path\":\"/abs\",\"pattern\":\"src/**/*.java\"}");
        Predicate<String> matcher = tool.preparePermissionMatcher(input);
        assertThat(matcher).as("GlobTool 必须实现 preparePermissionMatcher").isNotNull();
        assertThat(matcher.test("src/*.java")).as("pattern 命中 src glob").isTrue();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. inputJSONSchema: 直接 JSON Schema 优先（CC api.ts:157-160）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("13. SyntheticOutputTool.inputJSONSchema → 构造的 schema（CC SyntheticOutputTool.ts:141）")
    void syntheticOutput_inputJSONSchema_returnsSchema() throws Exception {
        // 意图: CC buildSyntheticOutputTool 把 jsonSchema 设为 inputJSONSchema
        //       (SyntheticOutputTool.ts:141) — 序列化时优先于 zod 转换。
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}}}");
        SyntheticOutputTool tool = new SyntheticOutputTool(schema);
        assertThat(tool.inputJSONSchema()).as("SyntheticOutputTool 必须声明 inputJSONSchema").isNotNull();
        assertThat(tool.inputJSONSchema().get("properties").has("ok")).isTrue();
    }

    // 非工具实现不应声明（default null）
    @Test
    @DisplayName("14. 无路径概念工具 getPath/inputJSONSchema 默认 null（CC 可选成员未实现）")
    void nonPathTool_defaultsNull() {
        // 意图: Bash 无 getPath（CC BashTool 未实现 getPath）；普通工具无 inputJSONSchema。
        //       default null 让权限管线走 ask / 序列化走 inputSchema()。
        BashTool bash = new BashTool();
        assertThat(bash.getPath(MAPPER.createObjectNode())).as("Bash 无 getPath → null").isNull();
        assertThat(bash.inputJSONSchema()).as("Bash 无 inputJSONSchema → null").isNull();
    }
}
