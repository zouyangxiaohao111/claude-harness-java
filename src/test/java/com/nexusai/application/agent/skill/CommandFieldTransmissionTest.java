package com.nexusai.application.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SkillToolImpl;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import com.nexusai.model.command.PromptFnContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-8] Command 字段透传测试（hasUserSpecifiedDescription + getPromptForCommand 残差补齐）·
 * 对齐 CC registerBundledSkill（bundledSkills.ts:75-98 22 字段 Command）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）:
 * <ol>
 *   <li><b>hasUserSpecifiedDescription 必须恒 true 透传</b>——CC bundledSkills.ts:80 对 ALL bundled
 *       恒设 {@code hasUserSpecifiedDescription: true}；Java Command.java:79 默认 FALSE，旧
 *       {@code BundledSkillDefinition.toCommand()} 未透传 → whenToUse=null 的 bundled skill
 *       （debug/claude-api/simplify/skillify/stuck/update-config/verify）从
 *       getSlashCommandToolSkills（SkillRegistry.java:365-366 过滤要求 {@code hasUserSpecifiedDescription
 *       || whenToUse}）消失 → 斜杠命令技能集行为偏移。RED：实施前 toCommand() 产出 FALSE。</li>
 *   <li><b>getPromptForCommand 必须透传为 Command.promptFn</b>——CC :97 把 prompt 闭包直挂 Command，
 *       bundled 内容源 = 闭包输出（processSlashCommand.tsx:869 {@code command.getPromptForCommand(args, context)}
 *       + :884 {@code skillContent = result.filter(text).map(text).join('\n\n')}），<b>非 SKILL.md 文件</b>。
 *       旧 toCommand() 丢弃该 BiFunction → SkillToolImpl.doExecute（:974 loadContent）对 bundled
 *       （contentPath/baseDir/content 均 null）返回 "Skill has no content"（R1）。RED：实施前 promptFn
 *       null + doExecute 返回 has-no-content error。</li>
 *   <li><b>22 字段矩阵锁死 toCommand→Command 全字段映射</b>——含 P1-3/P1-4/P2-6 已透传的 14 字段
 *       （type/name/description/aliases/allowedTools/argumentHint/whenToUse/model/disableModelInvocation/
 *       userInvocable/contentLength=0/source=BUNDLED/hooks/context/agent/isEnabled/isHidden 派生/
 *       progressMessage='running'），防后续回归。</li>
 *   <li><b>doExecute 接线关闭 R1</b>——注入含 promptFn 的 bundled Command，doExecute 不再返回
 *       "has no content"，且 newMessage 含 prompt 文本（CC :884 skillContent = 闭包输出 join）。</li>
 * </ol>
 */
@DisplayName("[P2-8] Command 字段透传 16 字段（CC bundledSkills.ts:75-98 22 字段 Command）")
class CommandFieldTransmissionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void clearBundledRegistry() {
        // 静态 BundledSkills 注册表跨测试隔离（对齐 BundledSkillsBootstrapperNoFakesTest:29 惯例）
        BundledSkills.clear();
    }

    /** 全字段 rich Definition（P1-4 15 字段全填）· 供 toCommand 全字段矩阵断言。 */
    private static BundledSkillDefinition richDefinition() {
        return new BundledSkillDefinition(
            "debug",
            "Debug a failed prompt.",
            List.of("dbg"),                        // aliases
            "When the user wants to debug a prompt", // whenToUse
            "[prompt]",                            // argumentHint
            List.of("Read", "Grep"),               // allowedTools
            "claude-sonnet-4-6",                   // model
            true,                                  // disableModelInvocation（CC debug.ts:23）
            true,                                  // userInvocable
            () -> true,                            // isEnabled
            "{\"PreToolUse\":[]}",                 // hooks（JSON 串，Command.hooks 仅透传）
            "inline",                              // context
            "general-purpose",                     // agent
            null,                                  // files
            (args, cwd) -> List.of(PromptBlock.text("MATRIX_BODY\n" + args))); // getPromptForCommand
    }

    @Test
    @DisplayName("toCommand() 透传 hasUserSpecifiedDescription=TRUE + promptFn 非空（RED: 实施前 FALSE/null）")
    void toCommand_transmitsHasUserSpecifiedDescriptionAndPromptFn() {
        Command command = richDefinition().toCommand();

        // RED 于旧实现：Command.java:79 默认 FALSE（CC bundledSkills.ts:80 hasUserSpecifiedDescription: true）
        assertThat(command.getHasUserSpecifiedDescription())
            .as("CC :80 hasUserSpecifiedDescription: true（ALL bundled 恒 true）")
            .isTrue();

        // RED 于旧实现：toCommand() 丢弃 getPromptForCommand → promptFn null
        assertThat(command.getPromptFn())
            .as("CC :97 getPromptForCommand 直挂 Command（bundled 内容源）")
            .isNotNull();
        assertThat(command.getPromptFn().apply("", PromptFnContext.of("", List.of(), null)))
            .as("bundled prompt 闭包输出非空（CC processSlashCommand.tsx:884 skillContent 来源）")
            .isNotEmpty()
            .anySatisfy(block -> assertThat(((ContentBlockParam.TextBlockParam) block).text())
                .contains("MATRIX_BODY"));
    }

    @Test
    @DisplayName("toCommand() 全字段矩阵 = CC 22 字段 Command（type/name/description/aliases/allowedTools/.../promptFn）")
    void toCommand_mapsFullCommandMatrix() {
        Command command = richDefinition().toCommand();

        // CC :76 type 'prompt' / :77 name / :78 description / :88 source 'bundled'
        assertThat(command.getType()).isEqualTo("prompt");
        assertThat(command.getId()).isEqualTo("bundled-debug");
        assertThat(command.getName()).isEqualTo("debug");
        assertThat(command.getDescription()).isEqualTo("Debug a failed prompt.");
        assertThat(command.getSource()).as("CC :88 source:'bundled'").isEqualTo(CommandSource.BUNDLED);
        assertThat(command.getBuiltin())
            .as("P3-9 01-1 / DEL-03：bundled 不再设 builtin 字段（CC registerBundledSkill 无 builtin）——source==BUNDLED 承载「builtin 性」")
            .isFalse();

        // CC :79 aliases / :82 argumentHint / :83 whenToUse / :84 model / :90 hooks / :92 context / :93 agent
        assertThat(command.getAliases()).containsExactly("dbg");
        assertThat(command.getArgumentHint()).isEqualTo("[prompt]");
        assertThat(command.getWhenToUse()).isEqualTo("When the user wants to debug a prompt");
        assertThat(command.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(command.getHooks()).isEqualTo("{\"PreToolUse\":[]}");
        assertThat(command.getContext()).isEqualTo("inline");
        assertThat(command.getAgent()).isEqualTo("general-purpose");

        // CC :81 allowedTools ?? [] / :85 disableModelInvocation ?? false / :86 userInvocable ?? true
        assertThat(command.getAllowedTools()).containsExactly("Read", "Grep");
        assertThat(command.getDisableModelInvocation()).isTrue();
        assertThat(command.getUserInvocable()).isTrue();

        // CC :94 isEnabled 透传（P2-6 惰性 supplier）+ 求值落 enabled
        assertThat(command.getIsEnabled()).as("CC :94 isEnabled 直传（P2-6）").isNotNull();
        assertThat(command.getEnabled()).isTrue();

        // CC :95 isHidden = !(userInvocable ?? true) / :96 progressMessage 'running'
        assertThat(command.getIsHidden()).as("CC :95 isHidden = !(true) = false").isFalse();
        assertThat(command.getProgressMessage()).as("CC :96 progressMessage='running'").isEqualTo("running");

        // CC :80 hasUserSpecifiedDescription: true / :97 getPromptForCommand（本项 P2-8 残差补齐）
        assertThat(command.getHasUserSpecifiedDescription()).as("CC :80").isTrue();
        assertThat(command.getPromptFn()).as("CC :97").isNotNull();
        // P2-16: promptFn 返回内容块数组（对齐 CC getPromptForCommand → ContentBlockParam[]），
        // bundled 全 text 形态 → 取首个 TextBlockParam 断言文本
        assertThat(command.getPromptFn().apply("args-x", PromptFnContext.of(null, List.of(), null)))
            .hasSize(1)
            .allSatisfy(block -> assertThat(((ContentBlockParam.TextBlockParam) block).text())
                .isEqualTo("MATRIX_BODY\nargs-x"));

        // CC :87 contentLength: 0（bundled 无 SKILL.md，Java getContentLength = content null → 0，机制不同行为等价，concern P2-8-3）
        assertThat(command.getContentLength()).as("CC :87 contentLength: 0").isZero();
    }

    @Test
    @DisplayName("getSlashCommandToolSkills 纳入 whenToUse=null 的 bundled skill（修复：缺 hasUserSpecifiedDescription=true 时从斜杠技能集消失）")
    void slashCommandToolSkills_includesBundledSkillWithoutWhenToUse(@TempDir Path tempDir) {
        // WHY: CC :80 全 true → whenToUse=null 的 bundled skill 恒进斜杠技能集；旧 Java
        // hasUserSpecifiedDescription 默认 FALSE + whenToUse=null → SkillRegistry.java:365-366 过滤掉
        // （E2-P2-8-7 行为偏移）。P2-8 补 true 后必须出现在 getSlashCommandToolSkills。
        Command cmd = new BundledSkillDefinition(
            "stuck", "Get unstuck when Claude gets stuck.",
            null, null, null, null, null, null, true,
            null, null, null, null, null,
            (args, cwd) -> List.of(PromptBlock.text("stuck body"))).toCommand();
        assertThat(cmd.getWhenToUse()).isNull();
        assertThat(cmd.getHasUserSpecifiedDescription()).as("CC :80 恒 true 是进斜杠技能集的前提").isTrue();
        BundledSkills.register(cmd);

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        assertThat(registry.getSlashCommandToolSkills())
            .as("whenToUse=null 但 hasUserSpecifiedDescription=true 的 bundled skill 必须入选斜杠技能集")
            .extracting(Command::getName)
            .contains("stuck");
    }

    @Test
    @DisplayName("doExecute 注入含 promptFn 的 bundled Command → 不再返回 'has no content' 且 newMessage 含 prompt 文本（关闭 R1）")
    void doExecute_withBundledPromptFn_closesR1(@TempDir Path tempDir) throws Exception {
        // WHY: R1「Skill has no content」根因 = bundled Command 的 contentPath/baseDir/content 全 null，
        // doExecute:974 loadContent 返回 ""。P2-8 把 promptFn 接到 doExecute 内容源双路径 →
        // bundled 内容 = 闭包输出（CC processSlashCommand.tsx:869/:884），R1 关闭。
        BundledSkillDefinition def = new BundledSkillDefinition(
            "p2-8-bundled", "desc", null, null, null, null, null, null, true,
            null, null, null, null, null,
            (args, cwd) -> List.of(PromptBlock.text("BUNDLED_CONTENT\n" + args)));
        Command cmd = def.toCommand();
        assertThat(cmd.getPromptFn()).as("toCommand() 必须透传 getPromptForCommand（CC :97）").isNotNull();
        BundledSkills.register(cmd);

        SkillToolImpl tool = new SkillToolImpl(new SkillRegistry(tempDir.toString()));

        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "p2-8-bundled");
        input.put("args", "extra-args");
        ToolUseBlock block = new ToolUseBlock("tool-use-1", "Skill", input);

        ToolResult<?> result = (ToolResult<?>) tool.execute(
            block, ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));

        // R1 关闭：bundled skill 不再因内容源全 null 报 has-no-content error
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("promptFn 提供的 bundled 内容不应触发 R1 'Skill has no content' error")
            .isFalse();

        // newMessage 含 prompt 闭包输出（CC :884 skillContent = 闭包输出 join('\n\n') → buildSkillSystemPrompt 注入）
        assertThat(result.newMessages()).isNotEmpty();
        String joined = result.newMessages().stream()
            .map(m -> m.content() != null ? m.content() : "")
            .collect(Collectors.joining());
        assertThat(joined)
            .as("bundled 内容源 = getPromptForCommand 闭包输出（CC processSlashCommand.tsx:884）")
            .contains("BUNDLED_CONTENT")
            .contains("extra-args");
    }

    @Test
    @DisplayName("P2-16: promptFn 产出 image 块 → doExecute newMessage.contentBlocks 携带 image 块（图片块通道）")
    void doExecute_withPromptFnImageBlock_carriesImageIntoContentBlocks(@TempDir Path tempDir) throws Exception {
        // WHY: P2-16 图片块通道 —— MCP prompt 返回 image 块时，image 必须经 promptFn →
        // newMessage.contentBlocks 到达 LLM（模型可见内联图，对齐 CC processSlashCommand.tsx:890
        // mainMessageContent=[...result]）。若 SkillToolImpl 仍只 text join（丢 image），通道断裂，
        // 模型只见落盘路径文本（旧 △-1 降级回归）。
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
        Command cmd = new Command();
        cmd.setName("p2-16-img");
        cmd.setSource(CommandSource.USER);
        cmd.setPromptFn((args, cwd) -> List.of(
            new ContentBlockParam.TextBlockParam("IMG_TEXT"),
            ContentBlockParam.ImageBlockParam.of("image/png", png)));
        BundledSkills.register(cmd);

        SkillToolImpl tool = new SkillToolImpl(new SkillRegistry(tempDir.toString()));
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "p2-16-img");
        ToolUseBlock block = new ToolUseBlock("tool-use-1", "Skill", input);

        ToolResult<?> result = (ToolResult<?>) tool.execute(
            block, ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("image 通道不应破坏技能执行").isFalse();
        assertThat(result.newMessages()).isNotEmpty();

        // newMessage.contentBlocks 含 image 块（JsonNode {type:image, source:{type:base64, media_type, data}}）
        List<JsonNode> imageBlocks = result.newMessages().stream()
            .flatMap(m -> m.contentBlocks() == null ? java.util.stream.Stream.<Object>of() : m.contentBlocks().stream())
            .filter(o -> o instanceof JsonNode n && "image".equals(n.path("type").asText()))
            .map(o -> (JsonNode) o)
            .toList();
        assertThat(imageBlocks)
            .as("P2-16: promptFn image 块必须进 newMessage.contentBlocks（LLM 渲染 AnthropicSdkProvider:1504-1539）")
            .hasSize(1);
        JsonNode imageNode = imageBlocks.get(0);
        assertThat(imageNode.path("source").path("type").asText()).isEqualTo("base64");
        assertThat(imageNode.path("source").path("media_type").asText()).isEqualTo("image/png");
        assertThat(imageNode.path("source").path("data").asText()).isEqualTo(png);
    }
}
