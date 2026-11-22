package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.ast.AstEditStaging;
import com.nexusai.application.agent.ast.AstGrepCli;
import com.nexusai.application.agent.ast.AstGrepRunner;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ReadPermissionChecker;
import com.nexusai.application.agent.permission.WritePermissionChecker;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AstEditTool 的两段式（预览 → 落地）与失败路径。
 *
 * <p>WHY：这是唯一会写用户源码的工具。这里每条用例对应一个真实事故面：
 * 预览写了盘 / apply 无预览直接写 / 预览后文件被改仍按旧偏移写 / 重叠替换写坏文件 /
 * 删除（out ""）变成写入字符串 "null"。
 */
@DisplayName("AstEditToolTest · 预览/落地/stale/重叠/权限")
class AstEditToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CALL = Pattern.compile("foo\\((\\d+)\\)");

    @TempDir
    Path workspace;

    /**
     * fake CLI：只认 {@code foo($A)}（可选重叠模式 {@code ovl($A)}），按目标文件真实字节算偏移。
     */
    private static final class FakeCli implements AstGrepCli {
        @Override
        public Result run(List<String> args, Path workdir, AbortController abort, long timeoutMs) {
            String pat = args.get(args.indexOf("-p") + 1);
            String rewrite = args.contains("-r") ? args.get(args.indexOf("-r") + 1) : null;
            List<String> out = new ArrayList<>();
            for (String target : args) {
                // 只把「像路径且存在」的参数当目标：args 里还有 flag 值（hidden/never/java/rewrite/glob）
                if (target.isBlank() || target.startsWith("-") || target.startsWith("!")
                    || !Files.exists(Path.of(target))) {
                    continue;
                }
                Path p = Path.of(target);
                List<Path> files = new ArrayList<>();
                if (Files.isDirectory(p)) {
                    try (var stream = Files.list(p)) {
                        stream.filter(Files::isRegularFile).sorted().forEach(files::add);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                } else if (Files.isRegularFile(p)) {
                    files.add(p);
                }
                for (Path file : files) {
                    String content;
                    try {
                        content = Files.readString(file, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    if ("ovl($A)".equals(pat)) {
                        out.add(jsonLine(file.toString(), content, 0, 6, "foo(1)", rewrite));
                        out.add(jsonLine(file.toString(), content, 3, 9, "(1)\nfoo", rewrite));
                        continue;
                    }
                    if (!"foo($A)".equals(pat)) {
                        continue;
                    }
                    Matcher m = CALL.matcher(content);
                    while (m.find()) {
                        String replacement = rewrite == null ? null
                            : rewrite.replace("$A", m.group(1));
                        out.add(jsonLine(file.toString(), content, m.start(), m.end(), m.group(), replacement));
                    }
                }
            }
            return new Result(out.isEmpty() ? 1 : 0, out, List.of(
                "sg: summary|file: scannedFileCount=1,skippedFileCount=0"), false);
        }

        private static String jsonLine(String file, String content, int start, int end,
                                       String text, String replacement) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < start && i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            String rep = replacement == null ? "" : ",\"replacement\":\"" + replacement.replace("\"", "\\\"") + "\"";
            return "{\"text\":\"" + text.replace("\n", "\\n").replace("\"", "\\\"")
                + "\",\"range\":{\"byteOffset\":{\"start\":" + start + ",\"end\":" + end
                + "},\"start\":{\"line\":" + (line - 1) + ",\"column\":" + (column - 1)
                + "},\"end\":{\"line\":" + (line - 1) + ",\"column\":" + (column - 1 + text.length())
                + "}},\"file\":\"" + file.replace("\\", "\\\\") + "\",\"language\":\"JavaScript\","
                + "\"metaVariables\":{\"single\":{\"A\":{\"text\":\"1\"}},\"multi\":{},\"transformed\":{}}"
                + rep + "}";
        }
    }

    private AstEditTool tool() {
        return new AstEditTool(new PathGuard(workspace),
            new AstGrepRunner(new FakeCli()), new AstEditStaging());
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String run(AstEditTool tool, String json) {
        ToolResult<?> result = tool.execute(new ToolUseBlock("toolu_ast_edit", "AstEdit", read(json)),
            ToolUseContext.of(java.util.UUID.randomUUID().toString(), "s1"));
        // successWithStructuredOutput 的 data 是 Map（"summary" 才是模型面文本）
        if (result.data() instanceof java.util.Map<?, ?> m && m.get("summary") != null) {
            return String.valueOf(m.get("summary"));
        }
        return String.valueOf(result.data());
    }

    private Path file(String name, String content) throws Exception {
        Path p = workspace.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private static String preview(String path) {
        return "{\"ops\":[{\"pat\":\"foo($A)\",\"out\":\"bar($A)\"}],\"paths\":[\"" + path + "\"]}";
    }

    private static String apply(String path) {
        return "{\"ops\":[{\"pat\":\"foo($A)\",\"out\":\"bar($A)\"}],\"paths\":[\"" + path + "\"],\"apply\":true}";
    }

    @Test
    @DisplayName("预览：不写盘，返回 diff + 未应用提示")
    void previewDoesNotWrite() throws Exception {
        Path f = file("a.js", "foo(1)\n");
        String text = run(tool(), preview("a.js"));

        assertThat(text).startsWith("Staged as a proposal — files NOT modified yet.");
        assertThat(text).contains("a.js (1 replacements)")
            .contains("  - 1:1  foo(1)")
            .contains("  + 1:1  bar(1)")
            .contains("Total: 1 replacement(s) in 1 file(s).");
        assertThat(Files.readString(f)).isEqualTo("foo(1)\n");
    }

    @Test
    @DisplayName("落地：同签名 apply 写盘成功，内容按字节替换")
    void applyWrites() throws Exception {
        Path f = file("a.js", "foo(1)\nfoo(2)\n");
        AstEditTool tool = tool();
        run(tool, preview("a.js"));
        String text = run(tool, apply("a.js"));

        assertThat(text).startsWith("Applied 2 replacement(s) in 1 file(s).");
        assertThat(Files.readString(f)).isEqualTo("bar(1)\nbar(2)\n");
    }

    @Test
    @DisplayName("无预览直接 apply → 拒绝且不写盘")
    void applyWithoutPreviewRejected() throws Exception {
        Path f = file("a.js", "foo(1)\n");
        String text = run(tool(), apply("a.js"));
        assertThat(text).contains("Error: no preview found for this exact ops/paths request");
        assertThat(Files.readString(f)).isEqualTo("foo(1)\n");
    }

    @Test
    @DisplayName("预览后文件被改 → stale 且一个字节都不写")
    void stalePreviewRejected() throws Exception {
        Path f = file("a.js", "foo(1)\n");
        AstEditTool tool = tool();
        run(tool, preview("a.js"));
        Files.writeString(f, "manual edit\n");

        String text = run(tool, apply("a.js"));
        assertThat(text).contains("Error: preview is stale");
        assertThat(Files.readString(f)).isEqualTo("manual edit\n");
    }

    @Test
    @DisplayName("预览后目标文件被删 → stale（不得只写剩下的文件并报成功）")
    void staleWhenPreviewedFileDisappears() throws Exception {
        file("a.js", "foo(1)\n");
        Path b = file("b.js", "foo(2)\n");
        AstEditTool tool = tool();
        String ops = "{\"ops\":[{\"pat\":\"foo($A)\",\"out\":\"bar($A)\"}],\"paths\":[\".\"]}";
        run(tool, ops);
        Files.delete(b);

        String text = run(tool, ops.replace("\"paths\"", "\"apply\":true,\"paths\""));
        assertThat(text).contains("Error: preview is stale");
        assertThat(Files.readString(workspace.resolve("a.js"))).isEqualTo("foo(1)\n");
    }

    @Test
    @DisplayName("out:\"\" → 删除匹配（写入空串，不是字面 null）")
    void deletesWithEmptyOut() throws Exception {
        Path f = file("a.js", "foo(1)\n");
        AstEditTool tool = tool();
        String ops = "{\"ops\":[{\"pat\":\"foo($A)\",\"out\":\"\"}],\"paths\":[\"a.js\"]}";
        run(tool, ops);
        String applied = run(tool, ops.replace("\"paths\"", "\"apply\":true,\"paths\""));
        assertThat(applied).as("apply 结果").startsWith("Applied 1 replacement(s)");
        assertThat(Files.readString(f)).isEqualTo("\n");
    }

    @Test
    @DisplayName("重复 pat / 重叠区间 → 错误前缀 + 可读原因")
    void rejectsDuplicateAndOverlap() throws Exception {
        file("a.js", "foo(1)\n");
        String dup = "{\"ops\":[{\"pat\":\"foo($A)\",\"out\":\"a\"},{\"pat\":\"foo($A)\",\"out\":\"b\"}],\"paths\":[\"a.js\"]}";
        assertThat(run(tool(), dup))
            .isEqualTo("Error: duplicate rewrite pattern: foo($A)");

        String ovl = "{\"ops\":[{\"pat\":\"ovl($A)\",\"out\":\"x\"}],\"paths\":[\"a.js\"]}";
        assertThat(run(tool(), ovl))
            .contains("Error: overlapping replacements detected");
    }

    @Test
    @DisplayName("零变更：No replacements made（成功，不暂存不写盘）")
    void noChanges() throws Exception {
        Path f = file("a.js", "nothing here\n");
        String text = run(tool(), preview("a.js"));
        assertThat(text).isEqualTo("No replacements made");
        assertThat(Files.readString(f)).isEqualTo("nothing here\n");
    }

    @Test
    @DisplayName("权限：预览走 Read、落地走 Write，写盘 Allow 升级为 Ask，Deny 透传")
    void permissions() throws Exception {
        file("a.js", "foo(1)\n");
        AstEditTool tool = tool();
        ReadPermissionChecker readAllow = new ReadPermissionChecker() {
            @Override
            public PermissionResult check(Tool t, JsonNode input, ToolUseContext ctx) {
                return new PermissionResult.Allow(input, null, null, false, null, List.of());
            }
        };
        WritePermissionChecker writeAllow = new WritePermissionChecker() {
            @Override
            public PermissionResult check(Tool t, JsonNode input, ToolUseContext ctx) {
                return new PermissionResult.Allow(input, null, null, false, null, List.of());
            }
        };
        WritePermissionChecker writeDeny = new WritePermissionChecker() {
            @Override
            public PermissionResult check(Tool t, JsonNode input, ToolUseContext ctx) {
                return new PermissionResult.Deny("no", new PermissionDecisionReason.Other("test"), null);
            }
        };
        tool.setReadChecker(readAllow);
        tool.setWriteChecker(writeAllow);
        ToolUseContext ctx = ToolUseContext.of(java.util.UUID.randomUUID().toString(), "s1");

        assertThat(tool.checkPermissions(read(preview("a.js")), ctx))
            .isInstanceOf(PermissionResult.Allow.class);
        PermissionResult ask = tool.checkPermissions(read(apply("a.js")), ctx);
        assertThat(ask).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) ask).message()).contains("AstEdit will rewrite 1 file(s)");

        tool.setWriteChecker(writeDeny);
        assertThat(tool.checkPermissions(read(apply("a.js")), ctx))
            .isInstanceOf(PermissionResult.Deny.class);
    }
}
