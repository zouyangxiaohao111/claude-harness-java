package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.agent.SessionCwdHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * env_info_simple 动态 section 测试 · 对齐 CC {@code computeSimpleEnvInfo}
 * （prompts.ts:651-735）+ DEL-SP-23（WORKSPACE_TEMPLATE {@code Today's date} 行删除）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：env 段是唯一向 LLM 描述运行环境的 section，
 * 其内容契约决定 LLM 对 cwd/git/platform/shell/OS 的认知：
 * <ul>
 *   <li><b>REQ-SP-08</b>：env_info_simple 动态 section 经 buildDynamicSections 注册且 compute
 *       等价 CC computeSimpleEnvInfo——含 {@code # Environment} 头 + cwd/git/附加目录/platform/
 *       shell/OS/模型描述行（:653-671/:702/:727-735）；</li>
 *   <li><b>DEL-SP-23 闭合</b>：CC env 段无日期（computeSimpleEnvInfo 无 {@code Today's date}）；
 *       日期属 getUserContext currentDate 通道（context.ts:186，随会话冻结 I-10）。Java 已把
 *       {@code Today's date} 行从 WORKSPACE_TEMPLATE 删除并移入 userContext——本测试钉死
 *       env compute 输出不得含日期行，防旧模板回归；</li>
 *   <li>worktree 子弹跳过（Java 无 worktree 会话概念，envInfoSimpleCompute Javadoc 声明）。</li>
 * </ul>
 */
class EnvSectionTest {

    @TempDir
    Path tmp;

    @org.junit.jupiter.api.AfterEach
    void resetCwdSupplier() {
        // 测试缝复位：防 cwdSupplier 跨用例污染（S1 纪律）
        SystemPromptSections.setCwdSupplier(null);
    }

    /** 从 buildDynamicSections 取 env_info_simple section 并触发 compute。 */
    private static String envText(SystemPromptAssemblyInput input) {
        List<SystemPromptSection> sections = SystemPromptSections.buildDynamicSections(input);
        return sections.stream()
            .filter(s -> "env_info_simple".equals(s.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("env_info_simple 未注册"))
            .compute().compute().join();
    }

    private static SystemPromptAssemblyInput input() {
        return new SystemPromptAssemblyInput(Set.of("Read", "Edit"), "claude-sonnet-4-6", List.of("/extra/dir"), List.of(), null, List.of(), "zh-CN", null, false);
    }

    @Test
    @DisplayName("REQ-SP-08：env compute 等价 computeSimpleEnvInfo——# Environment 头 + cwd/git/附加目录/platform/OS 行")
    void env_equivalentToComputeSimpleEnvInfo() {
        String text = envText(input());

        assertThat(text).as("段头与引导句（prompts.ts:653-655）")
            .startsWith("# Environment\nYou have been invoked in the following environment:");
        assertThat(text).as("cwd 行（prompts.ts:656）").contains("Primary working directory:");
        assertThat(text).as("git 行（prompts.ts:657-660）").contains("Is a git repository:");
        assertThat(text).as("附加工作目录（prompts.ts:663-665）")
            .contains("Additional working directories:").contains("/extra/dir");
        assertThat(text).as("platform 行（prompts.ts:702）").contains("Platform:");
        assertThat(text).as("OS 行（prompts.ts:713）").contains("OS Version:");
        assertThat(text).as("模型描述行（model 非空 → marketing 名形态注入，prompts.ts:665）")
            .contains("You are powered by the model named Sonnet 4.6. The exact model ID is claude-sonnet-4-6.");
    }

    @Test
    @DisplayName("DEL-SP-23 闭合：env compute 不含 Today's date 行（日期已移 getUserContext 通道，随会话冻结 I-10）")
    void env_hasNoDateLine() {
        String text = envText(input());

        assertThat(text)
            .as("DEL-SP-23：WORKSPACE_TEMPLATE 日期行已删除，env 段不得含日期")
            .doesNotContain("Today's date");
        assertThat(text)
            .as("env 段不得含 currentDate 键（日期完全不属于 env 段）")
            .doesNotContain("currentDate");
    }

    @Test
    @DisplayName("env compute：model 为空 → 模型描述行省略（prompts.ts:671 条件注入）")
    void env_modelBlank_omitsModelLine() {
        SystemPromptAssemblyInput blankModel = new SystemPromptAssemblyInput(Set.of(), "", List.of(), List.of(), null, List.of(), null, null, false);
        String text = envText(blankModel);

        assertThat(text)
            .as("model 为空 → 无 'You are powered by the model' 行")
            .doesNotContain("You are powered by the model");
        assertThat(text).as("空 model 仍产出段头").startsWith("# Environment");
    }

    @Test
    @DisplayName("worktree 检测（.git 普通文件 + gitdir 目标含 commondir）→ worktree 子弹注入（prompts.ts:679-681）")
    void env_worktreeBullet() throws Exception {
        // CC worktree 结构：.git 为普通文件（gitdir: <path>），gitdir 目标含 commondir
        // （git.ts:123-183 resolveCanonicalRoot 判别；git worktree add 布局）
        Files.writeString(tmp.resolve(".git"), "gitdir: " + tmp.resolve("gitdir/wt"));
        Files.createDirectories(tmp.resolve("gitdir/wt"));
        Files.writeString(tmp.resolve("gitdir/wt/commondir"), "../..");
        SystemPromptSections.setCwdSupplier(() -> tmp);

        String text = envText(input());

        assertThat(text).as("worktree 子弹（prompts.ts:680，em dash + 反引号逐字节）")
            .contains("This is a git worktree — an isolated copy of the repository. Run all commands from this directory. Do NOT `cd` to the original repository root.");
    }

    @Test
    @DisplayName("嵌套 git 仓库（cwd 子目录、.git 在父目录）→ Is a git repository: true（findGitRoot walk-up，git.ts:27-86）")
    void env_nestedGitRepo_isGitTrue() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        Path nested = tmp.resolve("nested/sub");
        Files.createDirectories(nested);
        SystemPromptSections.setCwdSupplier(() -> nested);

        String text = envText(input());

        assertThat(text).as("walk-up 找到父目录 .git → true（现 Files.isDirectory 窄判定 false）")
            .contains("  - Is a git repository: true");
    }

    @Test
    @DisplayName("model=claude-sonnet-4-6 → Assistant knowledge cutoff is August 2025. 行（prompts.ts:669-672）")
    void env_knowledgeCutoffLine() {
        String text = envText(input());

        assertThat(text).as("knowledge cutoff 行（prompts.ts:670-671，主通道无 \\n\\n 前缀）")
            .contains("Assistant knowledge cutoff is August 2025.");
    }

    @Test
    @DisplayName("promo 3 行恒注入（prompts.ts:696/:699/:702；ant undercover 分支 N/A → 恒注入）")
    void env_promoThreeLines() {
        String text = envText(input());

        assertThat(text).as("家族行（prompts.ts:696，CLAUDE_4_5_OR_4_6_MODEL_IDS :121-125）")
            .contains("The most recent Claude model family is Claude 4.5/4.6. Model IDs — Opus 4.6: 'claude-opus-4-6', Sonnet 4.6: 'claude-sonnet-4-6', Haiku 4.5: 'claude-haiku-4-5-20251001'. When building AI applications, default to the latest and most capable Claude models.");
        assertThat(text).as("可用性行（prompts.ts:699）")
            .contains("Claude Code is available as a CLI in the terminal, desktop app (Mac/Windows), web app (claude.ai/code), and IDE extensions (VS Code, JetBrains).");
        assertThat(text).as("Fast mode 行（prompts.ts:702，FRONTIER_MODEL_NAME :118）")
            .contains("Fast mode for Claude Code uses the same Claude Opus 4.6 model with faster output. It does NOT switch to a different model. It can be toggled with /fast.");
    }

    @Test
    @DisplayName("model=claude-sonnet-4-6 → marketing 名描述行（model.ts:570-614；prompts.ts:663-667）")
    void env_marketingName() {
        String text = envText(input());

        assertThat(text).as("marketing 名 + 精确 model id（prompts.ts:665）")
            .contains("You are powered by the model named Sonnet 4.6. The exact model ID is claude-sonnet-4-6.");
    }

    // ── [cwd-session 2026-08-25 修复] env cwd 来自会话（显式 sessionId），非 MDC/user.dir ──

    /** 10 参构造：显式传 sessionId（cwd-session 修复的输入通道）。 */
    private static SystemPromptAssemblyInput inputWithSession(String sessionId) {
        return new SystemPromptAssemblyInput(Set.of("Read", "Edit"), "claude-sonnet-4-6",
            List.of(), List.of(), null, List.of(), "zh-CN", null, false, sessionId);
    }

    @Test
    @DisplayName("cwd-session：input.sessionId 命中 SessionCwdHolder → Primary working directory 用会话 cwd（非 user.dir）")
    void env_sessionId_resolvesSessionCwd() throws Exception {
        // WHY（规则 9）：env_info_simple 在 ForkJoinPool 线程渲染无 MDC，旧 cwd() 回落 user.dir
        //   （后端启动目录）→ 系统提示注入错误项目（实测 AI 答 nexusai-backend 应为绑定项目）。
        //   修复：input.sessionId() 显式传会话 → CwdResolution.getCwd(sessionId) 走会话 cwd 层。
        Path sessionCwd = tmp.resolve("session-project");
        Files.createDirectories(sessionCwd);
        try {
            SessionCwdHolder.set("sess-env-test", sessionCwd.toString());
            String text = envText(inputWithSession("sess-env-test"));
            assertThat(text).as("cwd 来自会话（sessionId → SessionCwdHolder），非 user.dir 兜底")
                .contains("Primary working directory: " + sessionCwd.toString().replace('\\', '/'));
        } finally {
            SessionCwdHolder.clear("sess-env-test");
        }
    }

    @Test
    @DisplayName("cwd-session：显式 sessionId 覆盖 cwdSupplier 测试缝（会话 cwd 优先，不经 cwdSupplier）")
    void env_sessionIdOverridesCwdSupplier() throws Exception {
        // WHY（规则 9）：sessionId 非空时必须走 CwdResolution.getCwd(sessionId)（显式会话），
        //   而非 cwdSupplier 测试缝——否则渲染线程 MDC 丢失时测试缝也不可靠。
        Path sessionCwd = tmp.resolve("session-cwd");
        Files.createDirectories(sessionCwd);
        Path decoy = tmp.resolve("decoy");
        Files.createDirectories(decoy);
        SystemPromptSections.setCwdSupplier(() -> decoy);  // 测试缝指向 decoy（若误用会断言失败）
        try {
            SessionCwdHolder.set("sess-env-test2", sessionCwd.toString());
            String text = envText(inputWithSession("sess-env-test2"));
            assertThat(text).as("显式 sessionId 的会话 cwd 胜出，cwdSupplier decoy 不生效")
                .contains("Primary working directory: " + sessionCwd.toString().replace('\\', '/'))
                .doesNotContain("Primary working directory: " + decoy.toString().replace('\\', '/'));
        } finally {
            SessionCwdHolder.clear("sess-env-test2");
            SystemPromptSections.setCwdSupplier(null);
        }
    }
}
