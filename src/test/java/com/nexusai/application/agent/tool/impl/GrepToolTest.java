package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-D1 · GrepTool 契约对齐（组 2-4）：参数 include→glob、三模式文本输出、上限全删。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>参数名 glob</b> —— CC GrepTool.ts:46-51 inputSchema 用 {@code glob}（rg --glob 过滤），
 *       Java 旧参数 {@code include} 是重命名产物（TR-D3 D-4）。参数名不兼容 → LLM 按 CC 文档传
 *       {@code glob} 时 Java 静默忽略过滤 → 漏结果。断言 schema 含 glob、不含 include。</li>
 *   <li><b>三模式文本输出</b> —— CC mapToolResultToToolResultBlockParam（GrepTool.ts:254-309）
 *       content/files_with_matches/count 三种文本；Java 旧实现返回 raw JSON 字符串（R-A18 ✗）。
 *       断言 content 行格式、files_with_matches 摘要、count 摘要与 CC 逐字一致。</li>
 *   <li><b>上限全删</b> —— CC VCS 排除仅 6 目录（:95-102）、无 10MB 跳过（--max-columns 500 代替）、
 *       无文件数 cap 2000、匹配数走 applyHeadLimit（默认 250 / head_limit=0 无限，:108-128）。
 *       Java 旧 SKIP_DIRS 8 个（漏搜 node_modules 等）、MAX_FILE_SIZE=10MB、cap 2000、MAX_MATCHES=500
 *       （TR-D3 ⊕-5/6/7/8）。断言 node_modules 被搜索、head_limit=0 返回 600 行（>500 证明 cap 移除）。</li>
 * </ol>
 */
@DisplayName("GrepToolTest · IMP-D1 Grep/Glob 契约对齐（glob 参数 / 三模式输出 / 上限删除）")
class GrepToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    private GrepTool tool() {
        return new GrepTool(new PathGuard(workspace));
    }

    private String run(String inputJson) {
        ToolUseBlock call = new ToolUseBlock("toolu_grep", "Grep", read(inputJson));
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

    private void setMtime(Path p, long millis) throws Exception {
        Files.setLastModifiedTime(p, FileTime.fromMillis(millis));
    }

    // ───────────────────────── 参数名 include→glob ─────────────────────────

    @Test
    @DisplayName("inputSchema 用 glob 不用 include + 含 CC 全参数 + additionalProperties:false")
    void inputSchema_usesGlob_notInclude() {
        // WHY: CC GrepTool.ts:33-89 z.strictObject 含 pattern/path/glob/output_mode/-B/-A/-C/
        //      context/-n/-i/type/head_limit/offset/multiline。Java include→glob 重命名（TR-D3 D-4），
        //      z.strictObject → additionalProperties:false（未知键拒绝，同 G-A3/R-A3 口径）。
        JsonNode schema = tool().inputSchema();
        JsonNode props = schema.path("properties");
        assertThat(props.has("glob"))
            .as("参数名必须为 glob（CC GrepTool.ts:46，Java 旧 include 重命名）").isTrue();
        assertThat(props.has("include"))
            .as("旧参数 include 必须删除").isFalse();
        assertThat(props.has("output_mode"))
            .as("CC output_mode 三态参数（content/files_with_matches/count）").isTrue();
        assertThat(props.has("head_limit"))
            .as("CC head_limit（默认 250 / 0=无限）").isTrue();
        assertThat(props.has("offset"))
            .as("CC offset 分页").isTrue();
        assertThat(props.has("type"))
            .as("CC --type 过滤").isTrue();
        assertThat(props.has("multiline"))
            .as("CC -U --multiline-dotall").isTrue();
        assertThat(schema.path("additionalProperties").asBoolean(false))
            .as("z.strictObject → additionalProperties:false").isFalse();
    }

    // ───────────────────────── 三模式输出 ─────────────────────────

    @Test
    @DisplayName("content 模式：path:line:content 行（-n 默认 true）+ 无截断无分页后缀")
    void contentMode_producesPathLineContent() throws Exception {
        // WHY: CC content 模式 call（:443-476）applyHeadLimit 后 relativize → `relPath:num:content`，
        //      mapToolResult（:267-277）无截断时 content 原样返回。旧 Java 返回 raw JSON → 模型可读性下降（R-6）。
        Files.writeString(workspace.resolve("a.txt"), "hello world\nfoo bar\nhello again\n");
        String out = run("{\"pattern\":\"hello\",\"output_mode\":\"content\"}");
        assertThat(out)
            .as("content 模式逐字对齐 CC（relPath:num:content，-n 默认 true）")
            .isEqualTo("a.txt:1:hello world\na.txt:3:hello again");
    }

    @Test
    @DisplayName("content 模式截断：head_limit=2 → 前 2 行 + [Showing results with pagination = limit: 2]")
    void contentMode_headLimit_showsPagination() throws Exception {
        // WHY: CC applyHeadLimit（:110-128）仅截断时 set appliedLimit；mapToolResult content 分支
        //      （:270-272）追加 `\n\n[Showing results with pagination = limit: X]`。分页信息让模型
        //      知道可 offset 续翻，缺失则模型误以为全部结果。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append("foo\n");
        Files.writeString(workspace.resolve("a.txt"), sb.toString());
        String out = run("{\"pattern\":\"foo\",\"output_mode\":\"content\",\"head_limit\":2}");
        assertThat(out)
            .as("截断时 content + 分页信息（CC :270-272）")
            .isEqualTo("a.txt:1:foo\na.txt:2:foo\n\n[Showing results with pagination = limit: 2]");
    }

    @Test
    @DisplayName("files_with_matches 模式（默认）：Found N files + mtime 降序文件名")
    void filesWithMatchesMode_default_SummaryAndFilenames() throws Exception {
        // WHY: CC files_with_matches（:526-576）stat allSettled → mtime 降序 → applyHeadLimit →
        //      mapToolResult（:293-308）`Found N files\nf1\nf2`（按 mtime 最新在前）。旧 Java raw JSON
        //      无此摘要。
        Path a = workspace.resolve("a.txt");
        Path b = workspace.resolve("b.txt");
        Files.writeString(a, "foo\n");
        Files.writeString(b, "foo\n");
        setMtime(a, 2000L); // a 最新 → 第一
        setMtime(b, 1000L);
        String out = run("{\"pattern\":\"foo\"}");
        assertThat(out)
            .as("默认 files_with_matches 模式 CC 摘要格式")
            .isEqualTo("Found 2 files\na.txt\nb.txt");
    }

    @Test
    @DisplayName("count 模式：file:count 行 + Found N total occurrences across M files（行序非契约）")
    void countMode_Summary() throws Exception {
        // WHY: CC count（:478-524）relativize → `relPath:count`，mapToolResult（:280-291）
        //      `rawContent + \n\nFound N total occurrences across M files.`。总数让模型判断结果规模。
        //      注意：真实 rg 的 -c 输出顺序是并行扫描序（CC 对 count 模式不排序，GrepTool.ts:478-524
        //      直接透传 rg stdout），故只断言两行 count 均在 + 汇总行，不依赖行序（测试验证意图
        //      而非行为；旧 Java 自建实现按 Files.walk 序输出的确定性顺序不是 CC 契约）。
        Files.writeString(workspace.resolve("a.txt"), "foo\nbar\nfoo\n");
        Files.writeString(workspace.resolve("b.txt"), "foo\n");
        String out = run("{\"pattern\":\"foo\",\"output_mode\":\"count\"}");
        java.util.List<String> lines = out.lines().toList();
        assertThat(lines)
            .as("count 模式逐字对齐 CC：两行 file:count + Found N total occurrences across M files.")
            .contains("a.txt:2", "b.txt:1", "", "Found 3 total occurrences across 2 files.");
        assertThat(lines)
            .as("恰好 2 行 count + 空行 + 汇总行（无额外行）")
            .hasSize(4);
    }

    // ───────────────────────── 上限全删 ─────────────────────────

    @Test
    @DisplayName("head_limit=0 无限：600 行全部返回（> 旧 MAX_MATCHES=500，证明 cap 删除）")
    void headLimitZero_unlimited_600Lines() throws Exception {
        // WHY: CC head_limit=0 显式无限（:116-118）；Java 旧 MAX_MATCHES=500 硬上限（TR-D3 ⊕-8）
        //      → 500 行即截断。600 行全返回证明 MAX_MATCHES 已删、走 CC applyHeadLimit 语义。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append("foo\n");
        Files.writeString(workspace.resolve("big.txt"), sb.toString());
        String out = run("{\"pattern\":\"foo\",\"output_mode\":\"content\",\"head_limit\":0}");
        assertThat(out.lines().count())
            .as("head_limit=0 → 600 行全返回（旧 cap 500 已删）")
            .isEqualTo(600);
    }

    @Test
    @DisplayName("node_modules 被搜索（旧 SKIP_DIRS 额外 7 目录已删）；.git 仍排除（CC VCS 6 目录）")
    void nonVcsDirsSearched_vcsStillExcluded() throws Exception {
        // WHY: CC VCS_DIRECTORIES_TO_EXCLUDE 仅 6 个（:95-102，无 node_modules/target 等）；
        //      Java 旧 SKIP_DIRS 8 个漏搜 node_modules（TR-D3 ⊕-5/D-5）→ 修复。.git 属 CC VCS 6 目录仍排除。
        Files.createDirectories(workspace.resolve("node_modules"));
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve("node_modules/x.txt"), "foo\n");
        Files.writeString(workspace.resolve(".git/config"), "foo\n");
        String out = run("{\"pattern\":\"foo\",\"output_mode\":\"files_with_matches\"}");
        assertThat(out)
            .as("node_modules 必须被搜索（旧 SKIP_DIRS 删除，漏结果修复）")
            .contains("node_modules" + java.io.File.separator + "x.txt");
        assertThat(out)
            .as(".git 属 CC VCS 6 目录仍排除")
            .doesNotContain(".git");
    }

    @Test
    @DisplayName("glob 过滤参数生效（*.java 只匹配 java 文件）")
    void globFilter_appliesToFileNames() throws Exception {
        // WHY: CC glob 参数映射 rg --glob（:46-51），Java 旧 include 语义相同但参数名错位。
        Files.writeString(workspace.resolve("a.java"), "foo\n");
        Files.writeString(workspace.resolve("b.py"), "foo\n");
        String out = run("{\"pattern\":\"foo\",\"glob\":\"*.java\",\"output_mode\":\"files_with_matches\"}");
        assertThat(out)
            .as("glob=*.java 只返回 java 文件")
            .isEqualTo("Found 1 file\na.java");
    }

    @Test
    @DisplayName("glob 花括号模式整体保留：*.{ts,tsx} 同时命中 .ts 与 .tsx（CC GrepTool.ts:391-409）")
    void globFilter_bracePattern_preservesBraces() throws Exception {
        // WHY: CC :391-409 对含 { 与 } 的空白分段不按逗号拆分（整体作为一个 --glob 传给 rg，
        //      rg 自行展开 {ts,tsx} 替代）。旧实现恒按逗号拆分 → "*.{ts,tsx}" → ["*.{ts","tsx}"]
        //      → 两段均无法匹配真实 .ts/.tsx → 静默 0 命中（REQ-G2-4-1 契约未闭环）。
        Files.writeString(workspace.resolve("a.ts"), "foo\n");
        Files.writeString(workspace.resolve("b.tsx"), "foo\n");
        Files.writeString(workspace.resolve("c.java"), "foo\n");
        setMtime(workspace.resolve("a.ts"), 2000L);  // 最新 → 第一（files_with_matches mtime 降序）
        setMtime(workspace.resolve("b.tsx"), 1000L);
        String out = run("{\"pattern\":\"foo\",\"glob\":\"*.{ts,tsx}\",\"output_mode\":\"files_with_matches\"}");
        assertThat(out)
            .as("花括号 glob 必须整体保留（不按逗号拆分），同时命中 .ts 与 .tsx")
            .isEqualTo("Found 2 files\na.ts\nb.tsx");
    }

    @Test
    @DisplayName("glob 逗号分隔多模式仍生效：*.js,*.py 同时命中 .js 与 .py（防花括号修复回归）")
    void globFilter_commaSeparated_multiplePatterns() throws Exception {
        // WHY: CC :391-409 无花括号分段按逗号拆分（多模式 OR，filter(Boolean)）。
        //      花括号保留修复后必须保持逗号拆分语义，否则 "*.js,*.py" 被误作单 glob → 漏结果。
        Files.writeString(workspace.resolve("a.js"), "foo\n");
        Files.writeString(workspace.resolve("b.py"), "foo\n");
        Files.writeString(workspace.resolve("c.rb"), "foo\n");
        setMtime(workspace.resolve("a.js"), 2000L);  // 最新 → 第一
        setMtime(workspace.resolve("b.py"), 1000L);
        String out = run("{\"pattern\":\"foo\",\"glob\":\"*.js,*.py\",\"output_mode\":\"files_with_matches\"}");
        assertThat(out)
            .as("逗号分隔多模式（无花括号）仍按逗号拆分，同时命中 .js 与 .py")
            .isEqualTo("Found 2 files\na.js\nb.py");
    }
}
