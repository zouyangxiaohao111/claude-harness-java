package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.skill.DynamicSkillsManager;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseContext.ReadState;
import com.nexusai.infra.util.GitIgnoreHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-D2 · Edit 编辑语义与编码对齐 CC {@code FileEditTool.ts}。
 *
 * <p>WHY（规则九 · 验证意图）：组 2-5 拍板「全对齐 CC」——键名 file_path/old_string/new_string
 * （E5/T1）、createDirectories（E31）、utf16le/CRLF 编码保留（E19/E33/E38，数据完整性 HIGH）、
 * userModified 透传（E47）、inputsEquivalent 语义比较（E13/U14）。这些测试锁定
 * 旧实现必红的 RED→GREEN 行为：旧键名/恒 UTF-8 写/无 mkdir/恒 false/JSON equals。
 */
@DisplayName("IMP-D2 · EditFileTool 编辑语义与编码对齐")
class EditFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseContext ctxFor(Path workspace) {
        UUID agentId = UUID.nameUUIDFromBytes(("d2-edit-agent-" + workspace).getBytes());
        String sessionId = UUID.nameUUIDFromBytes(("d2-edit-sess-" + workspace).getBytes()).toString().toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    private static ToolUseBlock editCall(String path, String oldS, String newS) {
        return editCall(path, oldS, newS, false);
    }

    private static ToolUseBlock editCall(String path, String oldS, String newS, boolean replaceAll) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", oldS);
        input.put("new_string", newS);
        if (replaceAll) {
            input.put("replace_all", true);
        }
        return new ToolUseBlock("call-edit", "Edit", input);
    }

    private static void seedReadState(PathGuard guard, ToolUseContext ctx, String relPath,
                                      String content) throws Exception {
        Path file = guard.resolve(relPath);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        ctx.readFileState().set(
            ToolUseContext.keyForReadFileState(guard, relPath),
            ReadState.full(mtime, content));
    }

    /** 带 effectiveCwd=workspace 的 ToolUseContext: 技能发现上界必须显式等于 workspace（同 ReadFileToolTest.ctxWithCwd）。 */
    private static ToolUseContext ctxWithCwd(Path workspace) throws Exception {
        UUID agentId = UUID.nameUUIDFromBytes(("d2-edit-agent-" + workspace).getBytes());
        String sessionId = UUID.nameUUIDFromBytes(("d2-edit-sess-" + workspace).getBytes()).toString().toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            null, false, "", workspace.toRealPath());
    }

    /** gitExec 桩: exit 1 = 不忽略（fail-open 等价 git 无命中）· 同 DynamicSkillsManagerTest.notIgnored。 */
    private static GitIgnoreHelper.ExecResult notIgnored(String[] args, String cwd) {
        return new GitIgnoreHelper.ExecResult(1, "", "");
    }

    /**
     * 计数 DynamicSkillsManager: 覆写 discover/activate 两个技能链入口统计调用次数。
     * bare 门控测试观测点：bare 会话两入口必须零调用（CC FileEditTool.ts:407 SIMPLE 门控）。
     */
    private static final class CountingSkillsManager extends DynamicSkillsManager {
        final AtomicInteger discoverCount = new AtomicInteger();
        final AtomicInteger activateCount = new AtomicInteger();

        @Override
        public java.util.List<String> discoverSkillDirsForPaths(java.util.List<String> filePaths, Path cwd) {
            discoverCount.incrementAndGet();
            return super.discoverSkillDirsForPaths(filePaths, cwd);
        }

        @Override
        public java.util.List<String> activateConditionalSkillsForPaths(java.util.List<String> filePaths, Path cwd) {
            activateCount.incrementAndGet();
            return super.activateConditionalSkillsForPaths(filePaths, cwd);
        }
    }

    @Test
    @DisplayName("inputSchema 键名为 file_path/old_string/new_string（CC types.ts:8-17，旧 path/old_text/new_text 消失）")
    void inputSchema_usesCcKeys(@TempDir Path workspace) {
        JsonNode schema = new EditFileTool(new PathGuard(workspace)).inputSchema();
        JsonNode props = schema.path("properties");
        assertThat(props.has("file_path")).as("file_path 键必须存在").isTrue();
        assertThat(props.has("old_string")).as("old_string 键必须存在").isTrue();
        assertThat(props.has("new_string")).as("new_string 键必须存在").isTrue();
        // 旧 K-4 契约键必须消失（未上线可破约，前端契约同步）
        assertThat(props.has("path")).as("旧 path 键必须删除").isFalse();
        assertThat(props.has("old_text")).as("旧 old_text 键必须删除").isFalse();
        assertThat(props.has("new_text")).as("旧 new_text 键必须删除").isFalse();
        // required 对齐
        JsonNode required = schema.path("required");
        assertThat(required.toString()).contains("file_path", "old_string", "new_string");
    }

    @Test
    @DisplayName("Edit 到不存在目录自动创建父目录（E31，对齐 CC FileEditTool.ts:430 fs.mkdir）")
    void edit_createsParentDirectories(@TempDir Path workspace) throws Exception {
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);

        // 空 old_string + 文件不存在 = 新建文件；父目录 sub/nested 不存在
        ToolResult<String> result = tool.execute(editCall("sub/nested/created.txt", "", "hello"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("新建到不存在目录应成功（createDirectories）").isFalse();
        Path created = workspace.resolve("sub/nested/created.txt");
        assertThat(Files.exists(created)).as("父目录自动创建后文件必须落盘").isTrue();
        assertThat(Files.readString(created)).isEqualTo("hello");
    }

    @Test
    @DisplayName("utf16le 文件编辑写回不乱码且 BOM 保留（E33/E38，数据完整性 HIGH）")
    void edit_utf16le_roundTrip(@TempDir Path workspace) throws Exception {
        PathGuard guard = new PathGuard(workspace);
        EditFileTool tool = new EditFileTool(guard);
        ToolUseContext ctx = ctxFor(workspace);
        Path file = workspace.resolve("u16.txt");

        // 写 utf16le 文件：BOM(FF FE) + "old\r\n"（CRLF）
        byte[] bom = {(byte) 0xff, (byte) 0xfe};
        byte[] body = "old\r\n".getBytes(Charset.forName("UTF-16LE"));
        byte[] raw = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, raw, 0, bom.length);
        System.arraycopy(body, 0, raw, bom.length, body.length);
        Files.write(file, raw);
        // 播种 readFileState（内容 = BOM 解码 + CRLF 归一，对齐 FileEncodingReader 语义）
        seedReadState(guard, ctx, "u16.txt", "\uFEFFold\n");

        ToolResult<String> result = tool.execute(editCall("u16.txt", "old", "new"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("utf16le 编辑应成功").isFalse();
        byte[] after = Files.readAllBytes(file);
        // BOM 保留（FF FE）——保证下次读可检测回 utf16le，不乱码
        assertThat(after.length >= 2 && (after[0] & 0xff) == 0xff && (after[1] & 0xff) == 0xfe)
            .as("utf16le BOM 必须保留").isTrue();
        // 解码内容 = BOM 字符 + "new\r\n"（CRLF 保留，未转 LF）
        assertThat(new String(after, Charset.forName("UTF-16LE")))
            .isEqualTo("\uFEFFnew\r\n");
    }

    @Test
    @DisplayName("CRLF 文件编辑后行尾保留 CRLF（E38，不转 LF）")
    void edit_crlf_preserved(@TempDir Path workspace) throws Exception {
        PathGuard guard = new PathGuard(workspace);
        EditFileTool tool = new EditFileTool(guard);
        ToolUseContext ctx = ctxFor(workspace);
        Path file = workspace.resolve("crlf.txt");

        Files.writeString(file, "old\r\nold\r\n");
        seedReadState(guard, ctx, "crlf.txt", "old\nold\n");

        ToolResult<String> result = tool.execute(editCall("crlf.txt", "old", "new", true), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("CRLF 编辑应成功").isFalse();
        String after = Files.readString(file);
        assertThat(after).as("CRLF 行尾必须保留").isEqualTo("new\r\nnew\r\n");
    }

    @Test
    @DisplayName("userModified 从 ctx 透传（E47，CC data.userModified = userModified ?? false）")
    void edit_userModified_passthrough(@TempDir Path workspace) throws Exception {
        PathGuard guard = new PathGuard(workspace);
        EditFileTool tool = new EditFileTool(guard);
        ToolUseContext ctx = ctxFor(workspace).withUserModified(true);
        Path file = workspace.resolve("a.txt");

        Files.writeString(file, "old\n");
        seedReadState(guard, ctx, "a.txt", "old\n");

        ToolResult<String> result = tool.execute(editCall("a.txt", "old", "new"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(ToolResult.presentationMeta(result).get("userModified"))
            .as("ctx.userModified=true 必须透传到结构化输出").isEqualTo(true);
    }

    @Test
    @DisplayName("inputsEquivalent 语义比较（E13/U14：不同表达但结果相同 → 等价）")
    void inputsEquivalent_semantic(@TempDir Path workspace) throws Exception {
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        // 语义比较需真实文件内容（CC utils.ts:763-772 读文件；缺文件=空内容 → 双抛错同信息 → 误判等价）。
        Files.writeString(workspace.resolve("a.txt"), "old\n");
        ObjectNode in1 = JSON.createObjectNode();
        in1.put("file_path", "a.txt");
        in1.put("old_string", "old");
        in1.put("new_string", "new");
        ObjectNode in2 = JSON.createObjectNode();
        in2.put("file_path", "a.txt");
        in2.put("old_string", "old");
        in2.put("new_string", "new");

        // 字面相同 → 等价（快路径）
        assertThat(tool.inputsEquivalent(in1, in2)).as("字面相同输入必须等价").isTrue();

        // 不同文件 → 不等价（CC utils.ts:742-745 fast path）
        ObjectNode in3 = in2.deepCopy();
        in3.put("file_path", "b.txt");
        assertThat(tool.inputsEquivalent(in1, in3)).as("不同 file_path 必须不等价").isFalse();

        // 不同 new_string → 不等价（应用后结果不同）
        ObjectNode in4 = in2.deepCopy();
        in4.put("new_string", "other");
        assertThat(tool.inputsEquivalent(in1, in4)).as("结果不同的编辑必须不等价").isFalse();
    }

    // ═════════════ G24-bare: 会话级 bare 跳过 skill 目录遍历 (CC FileEditTool.ts:407) ═════════════

    @Test
    @DisplayName("[G24-bare] bare 会话 Edit 跳过动态技能发现 —— CC FileEditTool.ts:407 SIMPLE 门控")
    void bareMode_skipsSkillDiscovery(@TempDir Path workspace) throws Exception {
        // WHY（规则九 · 验证意图）：CC FileEditTool.ts:407 `if (!isEnvTruthy(process.env.CLAUDE_CODE_SIMPLE))`
        //   包裹 discoverSkillDirsForPaths + activateConditionalSkillsForPaths —— bare（SIMPLE）模式
        //   跳过 skill 目录遍历（envUtils.ts:50 isBareMode ~30 gates 之一）。Java Web 端无 simple
        //   mode 概念 → 会话级判定（bareMode 随会话走，V33 列）。变异点：删除 bare 门控 →
        //   discover/activate 被调用 + triggers 记录 → 红。
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "old\n");
        PathGuard guard = new PathGuard(workspace);
        EditFileTool tool = new EditFileTool(guard);
        CountingSkillsManager manager = new CountingSkillsManager();
        manager.setGitExec(EditFileToolTest::notIgnored);
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(workspace);
        seedReadState(guard, ctx, "a.txt", "old\n");
        try {
            new MemoryBareModeConfig(true);   // 全局桥 bare=true（ctx sessionId 非 "sess-" 派生 → 回落全局）
            ToolResult<String> r = tool.execute(editCall("a.txt", "old", "new"), ctx);
            assertThat(LlmAgentLoop.isToolErrorData(r.data())).as("bare 会话编辑仍须成功").isFalse();
            assertThat(manager.discoverCount.get())
                .as("bare 会话必须跳过 discoverSkillDirsForPaths（CC FileEditTool.ts:408）")
                .isZero();
            assertThat(manager.activateCount.get())
                .as("bare 会话必须跳过 activateConditionalSkillsForPaths（CC FileEditTool.ts:422）")
                .isZero();
            assertThat(ctx.dynamicSkillDirTriggers())
                .as("bare 会话不得记录 skill 目录")
                .isEmpty();
        } finally {
            MemoryBareModeConfig.reset();
        }
    }

    @Test
    @DisplayName("[G24-bare] 非 bare 会话 Edit 仍触发动态技能发现（SIMPLE 门控反面）")
    void nonBareMode_stillDiscoversSkills(@TempDir Path workspace) throws Exception {
        // WHY：bare 门控必须仅 isBareMode() 开启时生效；默认（非 bare）技能目录遍历照常执行
        //   （CC FileEditTool.ts:407 非 SIMPLE 分支）。变异点：门控误判（恒 true）→ 非 bare
        //   discover/activate 被误跳 → 红。
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "old\n");
        PathGuard guard = new PathGuard(workspace);
        EditFileTool tool = new EditFileTool(guard);
        CountingSkillsManager manager = new CountingSkillsManager();
        manager.setGitExec(EditFileToolTest::notIgnored);
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(workspace);
        seedReadState(guard, ctx, "a.txt", "old\n");
        try {
            new MemoryBareModeConfig(false);  // 全局桥 bare=false
            ToolResult<String> r = tool.execute(editCall("a.txt", "old", "new"), ctx);
            assertThat(LlmAgentLoop.isToolErrorData(r.data())).as("非 bare 会话编辑须成功").isFalse();
            assertThat(manager.discoverCount.get())
                .as("非 bare 会话必须执行 discoverSkillDirsForPaths（CC :408）")
                .isGreaterThanOrEqualTo(1);
            assertThat(manager.activateCount.get())
                .as("非 bare 会话必须执行 activateConditionalSkillsForPaths（CC :422）")
                .isGreaterThanOrEqualTo(1);
        } finally {
            MemoryBareModeConfig.reset();
        }
    }
}
