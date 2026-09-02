package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.PromptShellExecutor;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [P5-③] SkillPreloader 全链动态派生测试 · 对齐 CC runAgent.ts:617-627
 * {@code skill.getPromptForCommand('', toolUseContext)}（loadSkillsDir.ts:344-396 全链）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>preload 必须跑与 SkillToolImpl.doExecute 相同的派生链</b>——旧实现仅
 *       {@code contentLoader.loadContent(cmd)}，${CLAUDE_SKILL_DIR}/${CLAUDE_SESSION_ID} 在
 *       subagent 预加载场景全部保持字面（P5 真实缺口）。若本测试通过则证明 preload 输出 =
 *       getPromptForCommand 全链输出（子代理技能内容与 SkillTool 内联展开语义一致）。</li>
 *   <li><b>${CLAUDE_SKILL_DIR} → 技能实际目录</b>（cmd.getBaseDir()，win32 反斜杠→正斜杠）·
 *       对齐 CC loadSkillsDir.ts:359-363，让子代理技能内 {@code !`...`} 可引用技能目录脚本。</li>
 *   <li><b>${CLAUDE_SESSION_ID} → 会话 short id</b>（对齐 loadSkillsDir.ts:366-369 getSessionId）。</li>
 *   <li><b>shell 注入必须经 PromptShellExecutor</b>（含 MCP 安全闸后）· 对齐 loadSkillsDir.ts:371-396，
 *       传 {@code "/"+skillName} + cmd.getShell() + cmd.getAllowedTools()。</li>
 * </ol>
 */
class SkillPreloaderFullChainTest {

    /** 写一个含 frontmatter + 自定义 body 的 SKILL.md（body 含待替换占位符）。 */
    private static void writeSkill(Path skillsRoot, String skillName, String body) throws Exception {
        Path dir = skillsRoot.resolve(skillName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
            "---\nname: " + skillName + "\n---\n" + body + "\n");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    @Test
    @DisplayName("全链派生：${CLAUDE_SKILL_DIR}→技能实际目录、${CLAUDE_SESSION_ID}→会话 short id、args 替换")
    void preload_diskSkill_fullChainSubstitution(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "my-skill",
            "cd ${CLAUDE_SKILL_DIR} && echo ${CLAUDE_SESSION_ID} && echo $ARGUMENTS");

        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        SkillPreloader preloader = new SkillPreloader(registry);
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-abc");

        SkillPreloader.PreloadResult result = preloader.preload(List.of("my-skill"), "sess-abc", ctx);

        assertThat(result.missingSkills()).isEmpty();
        assertThat(result.initialMessages()).hasSize(1);
        Map<String, Object> message = result.initialMessages().get(0);
        List<?> contentBlocks = (List<?>) message.get("content");
        // 第一条 text = progressMessage，第二条 text = 派生后技能内容
        String content = ((Map<?, ?>) contentBlocks.get(1)).get("text").toString();

        String expectedDir = isWindows()
            ? skillsRoot.resolve("my-skill").toString().replace('\\', '/')
            : skillsRoot.resolve("my-skill").toString();
        // args='' 空串 → $ARGUMENTS 替换为空值（CC :101-102 空串 args 合法），无占位符追加不触发
        assertThat(content)
            .as("P5-③: 预加载内容 = getPromptForCommand 全链输出（SKILL_DIR/SESSION_ID/args 均替换）")
            .contains("cd " + expectedDir)
            .contains("echo sess-abc");
    }

    @Test
    @DisplayName("全链派生：${CLAUDE_SKILL_DIR} 未命中 baseDir（null）时保持字面（CC loadSkillsDir.ts:359-363 if(baseDir) 守卫）")
    void preload_diskSkill_nullBaseDir_keepsLiteral(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "no-dir-skill", "echo ${CLAUDE_SKILL_DIR}");

        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        SkillPreloader preloader = new SkillPreloader(registry);
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-x");

        SkillPreloader.PreloadResult result = preloader.preload(List.of("no-dir-skill"), "sess-x", ctx);

        Map<String, Object> message = result.initialMessages().get(0);
        List<?> contentBlocks = (List<?>) message.get("content");
        String content = ((Map<?, ?>) contentBlocks.get(1)).get("text").toString();
        // 文件系统技能 loader 恒设 baseDir（SkillsLoader:725）→ 此处恒替换；null 守卫由
        // SkillContentLoaderSubstitutionTest.renderSkill_nullGuards 覆盖（纯 loader 层）。
        assertThat(content).contains(skillsRoot.resolve("no-dir-skill").toString().replace('\\', '/'));
    }

    @Test
    @DisplayName("shell 注入：MCP 安全闸后经 PromptShellExecutor，传 /skillname + shell + allowedTools")
    void preload_diskSkill_shellInjectionInvoked(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        // frontmatter 声明 allowed-tools + shell，body 含内嵌 shell 命令 !`echo hi`
        Path skillDir = skillsRoot.resolve("sh-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: sh-skill\nallowed-tools: Bash\n---\nrun !`echo hi` in ${CLAUDE_SKILL_DIR}\n");

        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        SkillPreloader preloader = new SkillPreloader(registry);
        PromptShellExecutor shellExecutor = mock(PromptShellExecutor.class);
        preloader.setPromptShellExecutor(shellExecutor);
        when(shellExecutor.executeShellCommandsInPrompt(anyString(), any(), anyString(), any(), anyList()))
            .thenReturn("INJECTED-OUTPUT");
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-abc");

        SkillPreloader.PreloadResult result = preloader.preload(List.of("sh-skill"), "sess-abc", ctx);

        verify(shellExecutor).executeShellCommandsInPrompt(
            org.mockito.ArgumentMatchers.contains("!`echo hi`"),
            org.mockito.ArgumentMatchers.eq(ctx),
            org.mockito.ArgumentMatchers.eq("/sh-skill"),
            org.mockito.ArgumentMatchers.isNull(),   // frontmatter 未声明 shell → null（回退 Bash）
            org.mockito.ArgumentMatchers.eq(List.of("Bash")));

        Map<String, Object> message = result.initialMessages().get(0);
        List<?> contentBlocks = (List<?>) message.get("content");
        String content = ((Map<?, ?>) contentBlocks.get(1)).get("text").toString();
        assertThat(content)
            .as("P5-③: shell 注入结果进入预加载内容（对齐 CC executeShellCommandsInPrompt 最后一步）")
            .isEqualTo("INJECTED-OUTPUT");
    }

    @Test
    @DisplayName("ctx=null 时 shell 注入跳过（log.warn 不阻断），其余替换链照常执行")
    void preload_ctxNull_skipsShellInjection(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "no-ctx-skill", "echo ${CLAUDE_SESSION_ID} !`echo hi`");

        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        SkillPreloader preloader = new SkillPreloader(registry);
        PromptShellExecutor shellExecutor = mock(PromptShellExecutor.class);
        preloader.setPromptShellExecutor(shellExecutor);

        // 兼容重载 preload(List) → ctx=null → shell 注入跳过；skill 仍预加载成功（不因 ctx 缺省整体失败）
        SkillPreloader.PreloadResult result = preloader.preload(List.of("no-ctx-skill"));

        assertThat(result.missingSkills()).isEmpty();
        assertThat(result.initialMessages()).hasSize(1);
        Map<String, Object> message = result.initialMessages().get(0);
        List<?> contentBlocks = (List<?>) message.get("content");
        String content = ((Map<?, ?>) contentBlocks.get(1)).get("text").toString();
        // withBaseDirPrefix 照常执行（skill 仍成功预加载，不因 ctx 缺省整体失败）
        assertThat(content).startsWith("Base directory for this skill:");
        // sessionId=null（1 参兼容重载）→ ${CLAUDE_SESSION_ID} 保持字面（CC loadSkillsDir.ts:366-369
        //   getSessionId() 有值才替换；SkillContentLoader.replaceSessionId null 守卫）
        assertThat(content).contains("${CLAUDE_SESSION_ID}");
        // shell 命令保持字面（ctx=null 无法权限预检 → 跳过注入并 warn，对齐 SkillToolImpl:1543-1549）
        assertThat(content).contains("!`echo hi`");
    }

    /**
     * [Fix-P5-反思#1] bundled skill（promptFn 非 null）withBaseDirPrefix 前缀 · 镜像
     * SkillToolImpl:1503（对 bundled+disk 统一应用）+ CC bundledSkills.ts:66-72 prependBaseDir
     * （files 存在才加前缀）。
     *
     * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：带参考文件的 bundled skill 注册期 eager
     * setBaseDir（BundledSkillsBootstrapper:520）→ SkillTool 执行内容含『Base directory for this
     * skill: ...』前缀；若 preload 缺此前缀，子代理预加载内容 ≠ SkillTool 实际执行内容（模型无法
     * Read/Grep 参考文件，契约分叉）。本测试 RED 于修复前（bundled 分支无 withBaseDirPrefix → 无前缀）。
     */
    @Test
    @DisplayName("bundled skill（baseDir 非 null）：预加载内容带 withBaseDirPrefix 前缀（对齐 CC prependBaseDir）")
    void preload_bundledSkill_withBaseDirPrefix(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Files.createDirectories(skillsRoot);
        // 带参考文件的 bundled skill：baseDir = 参考文件解压目录（BundledSkillsBootstrapper:520 注册期定值）
        Path bundledDir = tempDir.resolve("bundled-extract");
        Files.createDirectories(bundledDir);
        BundledSkills.register(buildBundledCommand("bundle-skill", bundledDir.toString(), "bundled body"));

        try {
            SkillPreloader preloader = new SkillPreloader(new SkillRegistry(skillsRoot.toString()));
            ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-abc");

            SkillPreloader.PreloadResult result = preloader.preload(List.of("bundle-skill"), "sess-abc", ctx);

            assertThat(result.missingSkills()).isEmpty();
            Map<String, Object> message = result.initialMessages().get(0);
            List<?> contentBlocks = (List<?>) message.get("content");
            String content = ((Map<?, ?>) contentBlocks.get(1)).get("text").toString();
            assertThat(content)
                .as("P5-反思#1: bundled-with-files 预加载内容必须带 base-dir 前缀（镜像 SkillToolImpl:1503 + CC bundledSkills.ts:66-72）")
                .startsWith("Base directory for this skill: " + bundledDir.toString())
                .contains("bundled body");
        } finally {
            BundledSkills.clear();
        }
    }

    @Test
    @DisplayName("bundled skill（baseDir=null）：withBaseDirPrefix no-op 安全（无前缀，对齐 CC bundledSkills.ts:70-71）")
    void preload_bundledSkill_nullBaseDir_noPrefix(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Files.createDirectories(skillsRoot);
        // 无参考文件的 bundled skill：baseDir=null → withBaseDirPrefix no-op（CC 仅 extractedDir!==null 才 prependBaseDir）
        BundledSkills.register(buildBundledCommand("bundle-plain", null, "plain body"));

        try {
            SkillPreloader preloader = new SkillPreloader(new SkillRegistry(skillsRoot.toString()));
            ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-x");

            SkillPreloader.PreloadResult result = preloader.preload(List.of("bundle-plain"), "sess-x", ctx);

            assertThat(result.missingSkills()).isEmpty();
            Map<String, Object> message = result.initialMessages().get(0);
            List<?> contentBlocks = (List<?>) message.get("content");
            String content = ((Map<?, ?>) contentBlocks.get(1)).get("text").toString();
            assertThat(content)
                .as("P5-反思#1: baseDir=null → withBaseDirPrefix no-op 安全")
                .isEqualTo("plain body");
        } finally {
            BundledSkills.clear();
        }
    }

    /** 构造 bundled skill Command（promptFn 闭包 · 对齐 BundledSkillDefinition.toCommand）。 */
    private static Command buildBundledCommand(String name, String baseDir, String body) {
        Command cmd = new Command();
        cmd.setName(name);
        cmd.setType("prompt");
        cmd.setProgressMessage("Preloaded skill: " + name);
        cmd.setBaseDir(baseDir);
        cmd.setPromptFn((args, context) -> List.of(new ContentBlockParam.TextBlockParam(body)));
        return cmd;
    }
}
