package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.ast.AstGrepCli;
import com.nexusai.application.agent.ast.AstGrepRunner;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ReadPermissionChecker;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AstGrepTool 的 schema / 渲染 / 限制 / 权限合并。
 *
 * <p>WHY：工具面是模型唯一的输入面 —— schema 名、错误前缀、分页与 limit 夹取错了，模型会误用或
 * 误读；权限合并错了会把「未授权目录」放行（多路径只查第一个 = 绕过）。
 */
@DisplayName("AstGrepToolTest · schema/渲染/limit/权限")
class AstGrepToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    /** 可控 fake CLI：返回预置 JSONL 并记录参数。 */
    private static final class FakeCli implements AstGrepCli {
        private Result result = new Result(1, List.of(), List.of(
            "sg: summary|file: scannedFileCount=3,skippedFileCount=0"), false);
        private List<String> lastArgs;

        @Override
        public Result run(List<String> args, Path workdir, AbortController abort, long timeoutMs) {
            lastArgs = args;
            return result;
        }
    }

    private static String line(String file, int startByte, int line, int column, String text, String metaName, String metaText) {
        String single = metaName == null ? "{}" : "{\"" + metaName + "\":{\"text\":\"" + metaText + "\"}}";
        return "{\"text\":\"" + text + "\",\"range\":{\"byteOffset\":{\"start\":" + startByte
            + ",\"end\":" + (startByte + text.length()) + "},\"start\":{\"line\":" + (line - 1)
            + ",\"column\":" + (column - 1) + "},\"end\":{\"line\":" + (line - 1) + ",\"column\":"
            + (column - 1 + text.length()) + "}},\"file\":\"" + file.replace("\\", "\\\\")
            + "\",\"language\":\"Java\",\"metaVariables\":{\"single\":" + single
            + ",\"multi\":{},\"transformed\":{}}}";
    }

    private AstGrepTool tool(FakeCli cli) {
        return new AstGrepTool(new PathGuard(workspace), new AstGrepRunner(cli));
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String run(AstGrepTool tool, String json) {
        ToolResult<?> result = tool.execute(new ToolUseBlock("toolu_ast_grep", "AstGrep", read(json)),
            ToolUseContext.of(UUID.randomUUID().toString(), "s1"));
        // successWithStructuredOutput 的 data 是 Map（"summary" 才是模型面文本）
        if (result.data() instanceof Map<?, ?> m && m.get("summary") != null) {
            return String.valueOf(m.get("summary"));
        }
        return String.valueOf(result.data());
    }

    @Test
    @DisplayName("schema：5 个属性 + additionalProperties=false + required=[pat]")
    void schemaShape() {
        JsonNode schema = tool(new FakeCli()).inputSchema();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("pat");
        assertThat(schema.path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("pat", "path", "lang", "skip", "limit");
    }

    @Test
    @DisplayName("description：代码搜索优先 AstGrep / 非程序内容与字面定位回退 Grep / 格式边界 / schema 契约不变")
    void descriptionGuidesToolChoice() {
        AstGrepTool tool = tool(new FakeCli());
        String desc = tool.description();

        assertThat(desc).as("程序代码搜索优先 AstGrep")
            .contains("Prefer AstGrep").contains("code searches");
        assertThat(desc).as("非程序内容 / 字面文本定位 / 语言不支持 → 回退 Grep")
            .contains("Grep for non-programming content")
            .contains("literal text")
            .contains("unsupported");
        // 边界：configs 不是整类不解析——JSON/YAML 在内建语言列表里，只有「未列出的格式」才退回 Grep。
        assertThat(desc).as("边界：configs 按格式区分，不得与 logs/prose 并列为整类不解析")
            .contains("configs in formats not listed above")
            .doesNotContain("(logs, configs, prose)");
        assertThat(desc).as("内建语言列表仍含 json/yaml，与上面 configs 的边界表述一致")
            .contains("json, yaml");
        assertThat(desc).as("旧的无回退表述不应残留").doesNotContain("AstGrep does not parse");
        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("pat");
        assertThat(schema.path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("pat", "path", "lang", "skip", "limit");
    }

    @Test
    @DisplayName("空 pat → Error: 前缀；skip 越界 → 错误")
    void validatesInput() {
        AstGrepTool tool = tool(new FakeCli());
        assertThat(run(tool, "{\"pat\":\"  \"}"))
            .isEqualTo("Error: pat must be a non-empty AST pattern");
        assertThat(run(tool, "{\"pat\":\"foo($A)\",\"skip\":-1}"))
            .isEqualTo("Error: skip must be an integer in 0..10000");
    }

    @Test
    @DisplayName("渲染：按文件分组、行:列、meta 升序；多行匹配续行缩进 4 空格")
    void rendersGrouped() {
        FakeCli cli = new FakeCli();
        cli.result = new AstGrepCli.Result(0, List.of(
            line(workspace.resolve("a.js").toString(), 0, 5, 9, "foo(1)", "A", "1"),
            line(workspace.resolve("a.js").toString(), 10, 7, 3, "foo(bar(2))", "A", "bar(2)")),
            List.of("sg: summary|file: scannedFileCount=2,skippedFileCount=0"), false);
        AstGrepTool tool = tool(cli);

        String text = run(tool, "{\"pat\":\"foo($A)\"}");
        // 展示路径 = 相对会话工作目录
        assertThat(text).contains("a.js\n  5:9  foo(1)\n      meta: A=1\n  7:3  foo(bar(2))\n      meta: A=bar(2)");
        assertThat(text).startsWith("a.js\n");
        assertThat(text).doesNotContain("Result limit reached");
    }

    @Test
    @DisplayName("limit 固定上限 50（入参 100 → 只返回 50 条，且提示还有更多）")
    void clampsLimit() {
        FakeCli cli = new FakeCli();
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            lines.add(line(workspace.resolve("a.js").toString(), i * 10, i + 1, 1, "foo(" + i + ")", null, null));
        }
        cli.result = new AstGrepCli.Result(0, lines,
            List.of("sg: summary|file: scannedFileCount=1,skippedFileCount=0"), false);
        String text = run(tool(cli), "{\"pat\":\"foo($A)\",\"limit\":100}");
        long rendered = text.lines().filter(l -> l.matches("\\s+\\d+:\\d+  foo\\(\\d+\\)")).count();
        assertThat(rendered).isEqualTo(50);
        assertThat(text).contains("Result limit reached");
        // limit 不进 CLI 参数（服务端分页），这里断言不 sneaky 传 --limit
        assertThat(cli.lastArgs).doesNotContain("--limit");
    }

    @Test
    @DisplayName("分页空页 / 零扫描 的文案差异；limitReached 尾巴")
    void tailMessages() {
        FakeCli cli = new FakeCli();
        cli.result = new AstGrepCli.Result(1, List.of(), List.of(
            "sg: summary|file: scannedFileCount=4,skippedFileCount=0"), false);
        assertThat(run(tool(cli), "{\"pat\":\"foo($A)\"}"))
            .isEqualTo("No matches found");

        cli.result = new AstGrepCli.Result(1, List.of(), List.of(
            "sg: summary|file: scannedFileCount=0,skippedFileCount=0"), false);
        assertThat(run(tool(cli), "{\"pat\":\"foo($A)\"}"))
            .contains("(0 files scanned: unsupported file type or all files ignored)");

        cli.result = new AstGrepCli.Result(0, List.of(
            line(workspace.resolve("a.js").toString(), 0, 1, 1, "foo(1)", null, null),
            line(workspace.resolve("a.js").toString(), 10, 2, 1, "foo(2)", null, null)),
            List.of("sg: summary|file: scannedFileCount=1,skippedFileCount=0"), false);
        String text = run(tool(cli),
            "{\"pat\":\"foo($A)\",\"limit\":1}");
        assertThat(text).contains("Result limit reached; narrow the path or use skip to page through results.");
    }

    @Test
    @DisplayName("权限：逐路径各查一次，Deny > Ask > Allow，取最严")
    void permissionMerge() {
        AstGrepTool tool = tool(new FakeCli());
        final int[] calls = {0};
        ReadPermissionChecker checker = new ReadPermissionChecker() {
            @Override
            public PermissionResult check(Tool t, JsonNode input, ToolUseContext ctx) {
                calls[0]++;
                String p = t.getPath(input);
                return switch (p) {
                    case "deny" -> new PermissionResult.Deny("no", new PermissionDecisionReason.Other("test"), null);
                    case "ask" -> new PermissionResult.Ask("ask?", null, List.of(), null, input, null, false, null, List.of());
                    default -> new PermissionResult.Allow(input, null, null, false, null, List.of());
                };
            }
        };
        tool.setPermissionChecker(checker);
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID().toString(), "s1");

        assertThat(tool.checkPermissions(read("{\"pat\":\"p\",\"path\":\"allow;deny\"}"), ctx))
            .isInstanceOf(PermissionResult.Deny.class);
        assertThat(calls[0]).isEqualTo(2);
        assertThat(tool.checkPermissions(read("{\"pat\":\"p\",\"path\":\"ask;allow\"}"), ctx))
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(tool.checkPermissions(read("{\"pat\":\"p\",\"path\":\"allow\"}"), ctx))
            .isInstanceOf(PermissionResult.Allow.class);
        // ⛔ Allow.updatedInput 必须是原始工具输入：混入合成 {"path": p} 会被执行器当作新的
        //   effectiveCall（StreamingToolExecutor 的 ask-answers 应用分支）⇒ execute 只看到 path。
        PermissionResult.Allow allow = (PermissionResult.Allow) tool
            .checkPermissions(read("{\"pat\":\"p\",\"path\":\"allow\"}"), ctx);
        assertThat(allow.updatedInput().path("pat").asText()).isEqualTo("p");
    }
}
