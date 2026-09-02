package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-D1 · GlobTool 契约对齐（组 2-4）：结果上限 limit=100、深度限制删、输出对齐 CC。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>limit=100</b> —— CC GlobTool.ts:157 {@code globLimits?.maxResults ?? 100}，utils/glob.ts:126
 *       {@code truncated = len > offset + limit}。Java 旧 MAX_RESULTS=1000（TR-D3 ⊕-4）→ 模型可被
 *       超大结果淹没。断言 150 文件只返回 100 个 + 截断提示。</li>
 *   <li><b>无深度限制</b> —— CC glob() 用 rg 全遍历（utils/glob.ts:98-107，无 MAX_DEPTH）。
 *       Java 旧 MAX_DEPTH=20（TR-D3 ⊕-3）→ 深目录匹配丢失。断言 25 层深文件命中。</li>
 *   <li><b>输出文本</b> —— CC mapToolResult（GlobTool.ts:177-197）空 → 'No files found'；
 *       truncated → 追加 '(Results are truncated. Consider using a more specific path or pattern.)'。
 *       Java 旧 '(no matches)' / '(truncated at N results)' 文案偏离（TR-D3 D-2）。</li>
 * </ol>
 */
@DisplayName("GlobToolTest · IMP-D1 Grep/Glob 契约对齐（limit=100 / 无深度 / CC 文本输出）")
class GlobToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    private GlobTool tool() {
        return new GlobTool(new PathGuard(workspace));
    }

    private String run(String inputJson) {
        ToolUseBlock call = new ToolUseBlock("toolu_glob", "Glob", read(inputJson));
        ToolResult<String> result = tool().execute(call);
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("execute 必须成功（error: %s）", result.data())
            .isFalse();
        return result.data();
    }

    private JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("limit=100：150 文件只返回 100 个 + CC 截断提示（旧 MAX_RESULTS=1000 已改 100）")
    void limit100_truncatesAt100() throws Exception {
        // WHY: CC GlobTool.ts:157 limit 默认 100；Java 旧 MAX_RESULTS=1000 → 1000 才截断，
        //      150 文件全部返回淹没模型。对齐后 150 文件 → 100 + truncated 提示。
        for (int i = 0; i < 150; i++) {
            Files.writeString(workspace.resolve("f" + String.format("%03d", i) + ".txt"), "x");
        }
        String out = run("{\"pattern\":\"**/*.txt\"}");
        long filenameLines = out.lines().filter(l -> l.endsWith(".txt")).count();
        assertThat(filenameLines)
            .as("CC limit=100 → 至多返回 100 个文件名")
            .isEqualTo(100);
        assertThat(out)
            .as("截断提示逐字对齐 CC GlobTool.ts:191-193")
            .contains("(Results are truncated. Consider using a more specific path or pattern.)");
    }

    @Test
    @DisplayName("无深度限制：25 层深文件命中（旧 MAX_DEPTH=20 已删）")
    void noDepthLimit_deepNestedFileFound() throws Exception {
        // WHY: CC glob() 用 rg 全遍历（utils/glob.ts:98-107，无深度限制）；Java 旧 MAX_DEPTH=20
        //      （TR-D3 ⊕-3）→ 25 层深文件匹配丢失（漏结果）。对齐后深度不限，深文件命中。
        Path deep = workspace;
        for (int i = 0; i < 25; i++) {
            deep = deep.resolve("d" + i);
        }
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("deep.txt"), "x");
        String out = run("{\"pattern\":\"**/deep.txt\"}");
        assertThat(out)
            .as("25 层深文件必须命中（MAX_DEPTH=20 已删，rg 全遍历语义）")
            .contains("deep.txt");
    }

    @Test
    @DisplayName("空结果 → 'No files found'（CC GlobTool.ts:179-183）")
    void emptyResult_noFilesFound() throws Exception {
        // WHY: CC mapToolResult 空 → 'No files found'（:179-183）；Java 旧 '(no matches)' 文案偏离（D-2）。
        String out = run("{\"pattern\":\"**/*.nomatch\"}");
        assertThat(out)
            .as("空结果文本逐字对齐 CC")
            .isEqualTo("No files found");
    }

    @Test
    @DisplayName("glob 命中返回相对路径（cwd 下 relativize，CC toRelativePath）")
    void matchedFiles_relativized() throws Exception {
        // WHY: CC GlobTool.ts:166 call 里 filenames = files.map(toRelativePath)（cwd 相对，省 token）；
        //      相对路径命中，不暴露绝对路径。
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), "x");
        String out = run("{\"pattern\":\"**/*.java\"}");
        assertThat(out)
            .as("相对路径（cwd 下 relativize）")
            .isEqualTo("src" + java.io.File.separator + "App.java");
    }
}
