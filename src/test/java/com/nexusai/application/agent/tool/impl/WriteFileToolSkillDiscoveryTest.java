package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.skill.DynamicSkillsManager;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.util.GitIgnoreHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-2 文件工具技能发现接线测试 · 对齐 CC FileWriteTool.ts:232-245
 * （discoverSkillDirsForPaths → dynamicSkillDirTriggers.add + addSkillDirectories +
 * activateConditionalSkillsForPaths）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）: 文件工具必须把发现的 .claude/skills
 * 目录写入 {@code ctx.dynamicSkillDirTriggers()}（供 LlmAgentLoop per-turn attachment 装配），
 * 否则动态技能目录对 UI/transcript 不可见（CC FileWriteTool.ts:238）。
 */
@DisplayName("P1-2 WriteFileTool 技能发现接线 · CC FileWriteTool.ts:232-245")
class WriteFileToolSkillDiscoveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseBlock writeCallWith(String path, String content) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("content", content);
        return new ToolUseBlock("call-write", "write_file", input);
    }

    private static ToolUseContext ctxWithCwd(Path cwd) {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, "", cwd);
    }

    @Test
    @DisplayName("写文件后：发现的 .claude/skills 目录写入 ctx.dynamicSkillDirTriggers + 技能加载")
    void writeTriggersDiscoveryAndTriggerSet(@TempDir Path workspace) throws Exception {
        // 前置：workspace/sub/.claude/skills/my-skill/SKILL.md
        // 注: PathGuard 构造 + resolve 内部 toRealPath() 把 Windows 8.3 短路径 (ADMINI~1) 解析为
        //   长路径 — cwd 与 file 必须同形 discover 的 startsWith 前缀才匹配, 故统一 toRealPath()
        Path realWorkspace = workspace.toRealPath();
        Path skillDir = realWorkspace.resolve("sub/.claude/skills");
        Path skillSub = skillDir.resolve("my-skill");
        Files.createDirectories(skillSub);
        Files.writeString(skillSub.resolve("SKILL.md"), "---\nname: my-skill\n---\n# my-skill\n");

        DynamicSkillsManager manager = new DynamicSkillsManager();
        // gitExec 桩：exit 1 = 不忽略（避免依赖真实 git 环境）
        manager.setGitExec((args, cwd) -> new GitIgnoreHelper.ExecResult(1, "", ""));

        WriteFileTool tool = new WriteFileTool(new PathGuard(realWorkspace));
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(realWorkspace);

        tool.execute(writeCallWith("sub/new.txt", "content"), ctx);

        // CC FileWriteTool.ts:238 dynamicSkillDirTriggers?.add(dir)
        // 注: PathGuard.resolve 经 toRealPath() 解析 8.3 短路径 (Windows ADMINI~1) 为长路径,
        //    discover 产出的 dir 为长名 → 断言对齐 toRealPath() (与 skillDir 同一目录)
        assertThat(ctx.dynamicSkillDirTriggers())
            .as("写文件后发现的技能目录必须写入 ctx.dynamicSkillDirTriggers（CC :238）")
            .contains(skillDir.toString());
        // CC :241 addSkillDirectories → dynamicSkills 加载
        assertThat(manager.getDynamicSkills())
            .extracting(c -> c.getName())
            .contains("my-skill");
    }

    @Test
    @DisplayName("动态技能管理器未注入时写文件不抛异常（@Autowired(required=false) 兜底）")
    void noManagerDoesNotBreakWrite(@TempDir Path workspace) throws Exception {
        WriteFileTool tool = new WriteFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxWithCwd(workspace);

        // 无 dynamicSkillsManager → 跳过发现，写文件照常成功
        var result = tool.execute(writeCallWith("fresh.txt", "content"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
    }
}
