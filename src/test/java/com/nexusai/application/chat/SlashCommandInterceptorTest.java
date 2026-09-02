package com.nexusai.application.chat;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SlashCommandInterceptor 测试锚点（Plan-P1 §6 · 测试验证意图 CLAUDE.md 规则 9）。
 *
 * <p><b>WHY</b>：CC processSlashCommand 边界拦截是本批次核心 —— 未知命令不得查模型
 * （Unknown skill shouldQuery=false）、prompt 型技能内容必须生成并 isMeta 落库（shouldQuery=true）、
 * local 型必须路由到 UserInputDispatcher 并回传 {@code <local-command-stdout>}（shouldQuery=false）、
 * 文件路径疑似输入必须回落普通 prompt（不误伤 CC :362-380 语义）。
 */
class SlashCommandInterceptorTest {

    private static final String SESSION = "sess-1234";

    private SlashCommandInterceptor interceptor(SkillRegistry registry, UserInputDispatcher dispatcher) {
        return new SlashCommandInterceptor(registry, dispatcher);
    }

    // ─────────────────────────── 1. unknown 命令 ───────────────────────────

    /**
     * WHY（意图）：CC processSlashCommand.tsx:343-360 —— 未知名但「形似命令」的输入必须
     * {@code Unknown skill: X} + shouldQuery=false（不查模型、不起 LLM），否则模型会把垃圾斜杠
     * 输入当正常 prompt 消费，浪费 token + 偏离 CC 语义。
     */
    @Test
    @DisplayName("未知命令 /nosuchcmd → Unknown skill: nosuchcmd, shouldQuery=false")
    void unknownCommand_returnsUnknownSkill_noQuery() {
        SkillRegistry registry = new SkillRegistry("");
        UserInputDispatcher dispatcher = new UserInputDispatcher();

        SlashCommandInterceptor.SlashResolution r =
            interceptor(registry, dispatcher).intercept(SESSION, "msg-user-1", "/nosuchcmd", null, null);

        assertThat(r.handled()).isTrue();
        assertThat(r.shouldQuery()).isFalse();
        assertThat(r.resultText()).isEqualTo("Unknown skill: nosuchcmd");
        assertThat(r.command()).isNull();
    }

    /**
     * WHY（意图 · Fix-P2 Issue 4）：CC processSlashCommand.tsx:355-357 —— 未知命令附带 args 时追加
     * {@code Args from unknown skill: ...} system warning（gh-32591：保留 args 供用户复制重新提交）。
     * Java 无 CLI system-message 概念，resultText 追加提示等价承载（UI 可见）。RED: resultText 不含
     * args 提示 → 变红。
     */
    @Test
    @DisplayName("未知命令带 args /nosuchcmd a b → resultText 含 args 提示（对齐 CC :355-357）")
    void unknownCommandWithArgs_appendsArgsHint() {
        SkillRegistry registry = new SkillRegistry("");
        UserInputDispatcher dispatcher = new UserInputDispatcher();

        SlashCommandInterceptor.SlashResolution r =
            interceptor(registry, dispatcher).intercept(SESSION, "msg-user-2", "/nosuchcmd a b", null, null);

        assertThat(r.handled()).isTrue();
        assertThat(r.shouldQuery()).isFalse();
        assertThat(r.resultText()).isEqualTo("Unknown skill: nosuchcmd\nArgs from unknown skill: a b");
    }

    // ─────────────────────────── 2. 文件路径疑似回落 ───────────────────────────

    /**
     * WHY（意图）：CC processSlashCommand.tsx:362-380 —— 非命令输入（文件路径）必须回落普通 prompt
     * （handled=false → shouldQuery=true），不能误报 Unknown skill。用户输入 /var/log 应继续走
     * 正常 LLM（如「帮我看看 /var/log 里的错误」），而非被 slash 拦截吞掉。
     */
    @Test
    @DisplayName("文件路径疑似 /var/log/foo → 回落普通 prompt (handled=false)")
    void filePathInput_fallsThroughToNormalPrompt() {
        SkillRegistry registry = new SkillRegistry("");
        UserInputDispatcher dispatcher = new UserInputDispatcher();

        SlashCommandInterceptor.SlashResolution r =
            interceptor(registry, dispatcher).intercept(SESSION, "msg-user-1", "/var/log/foo", null, null);

        assertThat(r.handled()).isFalse();
    }

    /**
     * WHY（意图）：[Fix-P1 LOW] CC processSlashCommand.tsx:337-342 对 '/usr' 做 stat 实探 → 命中系统目录
     * → 回落普通 prompt（不是 'Unknown skill: usr'）。Java 旧实现仅 /var /tmp /private 前缀 → /usr 误报
     * Unknown skill。变异点：isLikelyFilePath 未扩展 /usr → Unknown skill → 红。
     */
    @Test
    @DisplayName("系统路径 /usr → 回落普通 prompt (handled=false)，不误报 Unknown skill")
    void systemPathUsr_fallsThroughToNormalPrompt() {
        SkillRegistry registry = new SkillRegistry("");
        UserInputDispatcher dispatcher = new UserInputDispatcher();

        SlashCommandInterceptor.SlashResolution r =
            interceptor(registry, dispatcher).intercept(SESSION, "msg-user-1", "/usr", null, null);

        assertThat(r.handled()).as("/usr 是系统目录（CC stat 命中）→ 回落普通 prompt").isFalse();
    }

    // ─────────────────────────── 3. prompt 型磁盘 skill ───────────────────────────

    /**
     * WHY（意图）：CC processSlashCommand.tsx:869-921 getMessagesForPromptSlashCommand —— 磁盘技能
     * 经 SKILL.md 管线渲染（withBaseDirPrefix + substituteArguments + ${CLAUDE_SKILL_DIR} 等）产出
     * skillContent，shouldQuery=true + metaMessageContent（ChatService 以 isMeta=true 落库 →
     * 模型可见 UI 隐藏），模型据此执行技能。断言渲染后内容含正文，验证「技能内容真的进模型」。
     */
    @Test
    @DisplayName("prompt 型磁盘 skill → skillContent 生成, shouldQuery=true, metaMessageContent 携带正文")
    void promptDiskSkill_generatesSkillContent_queriesModel(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Files.createDirectories(skillsRoot.resolve("skill-a"));
        Files.writeString(skillsRoot.resolve("skill-a").resolve("SKILL.md"),
            "---\nname: skill-a\n---\nSkill body content for testing\n");

        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        UserInputDispatcher dispatcher = new UserInputDispatcher();

        SlashCommandInterceptor.SlashResolution r =
            interceptor(registry, dispatcher).intercept(SESSION, "msg-user-1", "/skill-a", null, null);

        assertThat(r.handled()).isTrue();
        assertThat(r.shouldQuery()).isTrue();
        assertThat(r.command()).isNotNull();
        assertThat(r.command().getName()).isEqualTo("skill-a");
        // metaMessageContent = isMeta 落库内容（ChatService 传 createQueuedUserMessage(..., true) 参数源）
        assertThat(r.metaMessageContent()).contains("Skill body content for testing");
        assertThat(r.effectivePrompt()).isEqualTo(r.metaMessageContent());
    }

    /**
     * WHY（意图）：bundled skill（promptFn 闭包）内容源 —— CC processSlashCommand.tsx:884
     * {@code skillContent = text 块 join('\n\n')}，与磁盘管线同语义；验证闭包输出正确进入模型上下文。
     */
    @Test
    @DisplayName("prompt 型 bundled skill（promptFn 闭包）→ 闭包 text 块 join 为 skillContent")
    void promptBundledSkill_promptFnClosureJoinsTextBlocks() {
        SkillRegistry registry = new SkillRegistry("");
        Command bundled = new Command();
        bundled.setName("bundle");
        bundled.setType("prompt");
        bundled.setContext("inline");
        bundled.setPromptFn((args, ctx) -> List.of(
            new ContentBlockParam.TextBlockParam("Part one"),
            new ContentBlockParam.TextBlockParam("Part two " + args)));
        registry.setWorkflowCommandProvider(() -> List.of(bundled));

        UserInputDispatcher dispatcher = new UserInputDispatcher();

        SlashCommandInterceptor.SlashResolution r =
            interceptor(registry, dispatcher).intercept(SESSION, "msg-user-1", "/bundle hey", null, null);

        assertThat(r.handled()).isTrue();
        assertThat(r.shouldQuery()).isTrue();
        assertThat(r.metaMessageContent()).isEqualTo("Part one\n\nPart two hey");
    }

    // ─────────────────────────── 4. local 型 ───────────────────────────

    /**
     * WHY（意图）：CC processSlashCommand.tsx:657-721 local 分支 —— local 命令经
     * {@code mod.call(args, context)} 回传 LocalCommandResult，text 结果包 {@code <local-command-stdout>}
     * 消息（shouldQuery=false，本地执行不查模型）。验证 dispatchResult 路由 + 结果承载。
     */
    @Test
    @DisplayName("local 型 → UserInputDispatcher.dispatchResult 路由, <local-command-stdout> 结果")
    void localCommand_routesToDispatcherResult_returnsStdout() {
        SkillRegistry registry = new SkillRegistry("");
        Command local = new Command();
        local.setName("localtest");
        local.setType("local");
        registry.setWorkflowCommandProvider(() -> List.of(local));

        UserInputDispatcher dispatcher = new UserInputDispatcher();
        dispatcher.registerSlashCommandResult("localtest",
            args -> UserInputDispatcher.LocalCommandResult.text("echo: " + args));

        SlashCommandInterceptor.SlashResolution r =
            interceptor(registry, dispatcher).intercept(SESSION, "msg-user-1", "/localtest hello", null, null);

        assertThat(r.handled()).isTrue();
        assertThat(r.shouldQuery()).isFalse();
        assertThat(r.resultText()).isEqualTo("<local-command-stdout>echo: hello</local-command-stdout>");
    }

    /**
     * WHY（意图）：CC processSlashCommand.tsx:670-676 skip 分支 —— local 命令返回 skip 时不产生任何
     * 消息（shouldQuery=false）；不得制造空气泡或误推普通 prompt。
     */
    @Test
    @DisplayName("local 型 skip → 无消息 (resultText=null)")
    void localCommand_skipResult_noMessage() {
        SkillRegistry registry = new SkillRegistry("");
        Command local = new Command();
        local.setName("skipcmd");
        local.setType("local");
        registry.setWorkflowCommandProvider(() -> List.of(local));

        UserInputDispatcher dispatcher = new UserInputDispatcher();
        dispatcher.registerSlashCommandResult("skipcmd", args -> UserInputDispatcher.LocalCommandResult.skip());

        SlashCommandInterceptor.SlashResolution r =
            interceptor(registry, dispatcher).intercept(SESSION, "msg-user-1", "/skipcmd", null, null);

        assertThat(r.handled()).isTrue();
        assertThat(r.shouldQuery()).isFalse();
        assertThat(r.resultText()).isNull();
    }

    // ─────────────────────────── 5. userInvocable=false ───────────────────────────

    /**
     * WHY（意图）：CC processSlashCommand.tsx:535-548 —— userInvocable=false 技能只能由 Claude 经
     * SkillTool 调用，用户直接 /name 必须拒绝（shouldQuery=false）；否则用户可绕过权限约束调用
     * 仅模型可用技能。生产路径 ChatService 已由 P5 rejectNonUserInvocable 先行拦截，本测试验证
     * 拦截器自身防御性兜底。
     */
    @Test
    @DisplayName("userInvocable=false → 拒绝文案, shouldQuery=false")
    void userInvocableFalse_returnsRejection_noQuery() {
        SkillRegistry registry = new SkillRegistry("");
        Command internal = new Command();
        internal.setName("internal");
        internal.setType("prompt");
        internal.setUserInvocable(false);
        registry.setWorkflowCommandProvider(() -> List.of(internal));

        UserInputDispatcher dispatcher = new UserInputDispatcher();

        SlashCommandInterceptor.SlashResolution r =
            interceptor(registry, dispatcher).intercept(SESSION, "msg-user-1", "/internal", null, null);

        assertThat(r.handled()).isTrue();
        assertThat(r.shouldQuery()).isFalse();
        assertThat(r.resultText()).contains("This skill can only be invoked by Claude");
    }

    // ─────────────────────────── 6. parse 失败 ───────────────────────────

    /**
     * WHY（意图）：CC processSlashCommand.tsx:309-324 —— parse 失败（仅 '/' / 空）→ 表单错误提示，
     * shouldQuery=false。防止「/」这种边缘输入进 LLM 空转。
     */
    @Test
    @DisplayName("parse 失败（仅 '/'）→ 'Commands are in the form...', shouldQuery=false")
    void parseFailure_returnsFormError_noQuery() {
        SkillRegistry registry = new SkillRegistry("");
        UserInputDispatcher dispatcher = new UserInputDispatcher();

        SlashCommandInterceptor.SlashResolution r =
            interceptor(registry, dispatcher).intercept(SESSION, "msg-user-1", "/", null, null);

        assertThat(r.handled()).isTrue();
        assertThat(r.shouldQuery()).isFalse();
        assertThat(r.resultText()).isEqualTo("Commands are in the form `/command [args]`");
    }
}
