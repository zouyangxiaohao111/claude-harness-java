package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-22] inline newMessage sourceToolUseID 打标对齐 CC 测试 ·
 * 对齐 CC {@code Open-ClaudeCode/src/tools/utils.ts:12-25 tagMessagesWithToolUseID} +
 * {@code SkillTool.ts:729-755} inline 路径（toolUseID 标记使消息 transient）。
 *
 * <p>规则九（验证意图）: SkillTool inline 展开产生的技能内容 user 消息必须携带
 * sourceToolUseID = 本次 Skill tool_use 的 id —— 使该消息 transient（与正在运行的 Skill 工具
 * 调用绑定，UI 经 getToolUseID 关联（messages.ts:2777-2780 user 分支
 * {@code if (message.sourceToolUseID) return message.sourceToolUseID}），不落历史独立条目）。
 * 若标记缺失：技能指令 user 消息在 UI 历史中独立成条 / 无法关联回正在运行的 Skill 工具调用
 * （CC transient 语义破坏）。
 *
 * <p>RED 依据: 实施前 ChatMessageDto record 无 sourceToolUseID 组件，inline 裸 ChatMessageDto
 * （SkillToolImpl.java:1177-1182）打不上标 —— 断言 newMessages()[0].sourceToolUseID()
 * 编译失败（组件不存在）/ 恒 null → 先红后绿。
 */
@DisplayName("[P2-22] SkillTool inline newMessage sourceToolUseID 打标（transient 语义）")
class ToolUseIdTagTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 无 context 前导 → 走 inline 路径（CC processPromptSlashCommand）。 */
    private SkillRegistry newInlineRegistry(Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("inline-skill");
        Files.createDirectories(skillDir);
        String md = "---\n"
                + "name: inline-skill\n"
                + "description: inline 展开技能\n"
                + "---\n"
                + "# Inline Skill\n\n正文\n";
        Files.writeString(skillDir.resolve("SKILL.md"), md);
        return new SkillRegistry(tempDir.toString());
    }

    private ToolUseBlock skillBlock(String skillName) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", skillName);
        return new ToolUseBlock("tool-use-1", "Skill", input);
    }

    @Test
    @DisplayName("inline skill 调用 → newMessage 携带 sourceToolUseID=本次 Skill tool_use id（role=user）")
    void inlineSkill_newMessageIsTaggedWithSourceToolUseId(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:729-755 — inline 路径 tagMessagesWithToolUseID(newMessages, toolUseID)
        //   给 user 消息打 sourceToolUseID=本次 Skill tool_use id（getToolUseIDFromParentMessage 返回），
        //   使消息 transient（messages.ts:2778 getToolUseID user 分支消费 sourceToolUseID 关联到
        //   正在运行的 Skill 工具调用，不落历史独立条目）。若不打标：UI 无法用 getToolUseID 把技能
        //   指令 user 消息关联回 Skill 工具调用（重复渲染 / 历史独立条目）。
        SkillToolImpl tool = new SkillToolImpl(newInlineRegistry(tempDir));

        ToolResult<?> result = (ToolResult<?>) tool.execute(
                skillBlock("inline-skill"),
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("inline skill 正常成功返回").isFalse();
        assertThat(result.newMessages())
                .as("inline 技能展开必须产生 1 条技能内容 newMessage (CC SkillTool.ts:735-755)")
                .hasSize(1);
        assertThat(result.newMessages().get(0).role())
                .as("newMessage role=user（CC tagMessagesWithToolUseID 只 tag type==='user'，utils.ts:20）")
                .isEqualTo(Role.user);
        assertThat(result.newMessages().get(0).sourceToolUseID())
                .as("newMessage sourceToolUseID = 本次 Skill tool_use id（block.id()，CC SkillTool.ts:729 toolUseID）")
                .isEqualTo("tool-use-1");
    }

    @Test
    @DisplayName("打标 helper toolUseID null/blank → 原样返回（CC utils.ts:16-18）")
    void helper_nullOrBlankToolUseId_returnsMessagesUnchanged() throws Exception {
        // WHY: CC utils.ts:16-18 — `if (!toolUseID) return messages`：无工具调用可关联时
        //   消息不得打标。若 null 打标：消息被关联到不存在的工具调用，UI getToolUseID 关联断裂。
        //   Java inline 生产路径 block.id() 恒非 null（ToolUseBlock ctor 强制非 blank），
        //   故本分支是纯防御（CC 语义保留）；经反射验证以证明 helper 镜像 CC 空值短路。
        Method m = SkillToolImpl.class.getDeclaredMethod(
                "tagMessagesWithToolUseID", List.class, String.class);
        m.setAccessible(true);

        ChatMessageDto userMsg = baseUserMessage("u1");
        List<ChatMessageDto> input = List.of(userMsg);

        List<?> nullResult = (List<?>) m.invoke(null, input, null);
        assertThat(nullResult)
                .as("toolUseID=null → 原样返回同一列表实例 (CC utils.ts:16-18)")
                .isSameAs(input);
        List<?> blankResult = (List<?>) m.invoke(null, input, "  ");
        assertThat(blankResult)
                .as("toolUseID=blank → 原样返回同一列表实例 (CC utils.ts:16-18)")
                .isSameAs(input);
    }

    @Test
    @DisplayName("打标 helper 对 role=user 消息打标并返回新列表（CC utils.ts:20-21）")
    void helper_tagsUserMessageAndReturnsNewList() throws Exception {
        // WHY: CC utils.ts:19-24 — user 消息经 spread `{...m, sourceToolUseID}` 产生新对象，
        //   原消息不被原地修改（不可变 record 语义）。若原对象被污染：后续复用同一消息实例的
        //   调用点会意外携带上次的 toolUseID 关联。
        Method m = SkillToolImpl.class.getDeclaredMethod(
                "tagMessagesWithToolUseID", List.class, String.class);
        m.setAccessible(true);

        ChatMessageDto userMsg = baseUserMessage("u1");
        List<ChatMessageDto> input = List.of(userMsg);

        List<?> result = (List<?>) m.invoke(null, input, "tool-use-1");
        assertThat(result).as("打标返回新列表，非原列表实例").isNotSameAs(input);
        assertThat(result).hasSize(1);
        ChatMessageDto tagged = (ChatMessageDto) result.get(0);
        assertThat(tagged.role()).as("tag 后角色仍为 user (CC utils.ts:20)").isEqualTo(Role.user);
        assertThat(tagged.sourceToolUseID())
                .as("role=user 消息被打标 sourceToolUseID (CC utils.ts:21)")
                .isEqualTo("tool-use-1");
        assertThat(userMsg.sourceToolUseID())
                .as("原消息不被原地修改（CC spread 不可变语义）")
                .isNull();
    }

    @Test
    @DisplayName("打标 helper 非 user 角色（tool）不被打标（CC utils.ts:23 return m）")
    void helper_nonUserMessageNotTagged() throws Exception {
        // WHY: CC utils.ts:23 — 非 user 消息走 `return m` 原样返回（attachment/system 不变）。
        //   若误 tag：tool_result 等消息被关联到 Skill 工具调用，getSiblingToolUseIDs /
        //   getProgressMessagesFromLookup（messages.ts:1119/1425）关联链错乱。
        Method m = SkillToolImpl.class.getDeclaredMethod(
                "tagMessagesWithToolUseID", List.class, String.class);
        m.setAccessible(true);

        ChatMessageDto toolMsg = new ChatMessageDto(
                "t1", null, Role.tool, "tool", "tool result", null, null, null,
                null, null, null, null, "tool-use-1", null, null, List.of(), List.of());

        List<?> result = (List<?>) m.invoke(null, List.of(toolMsg), "tool-use-1");
        assertThat(result).hasSize(1);
        assertThat(((ChatMessageDto) result.get(0)).sourceToolUseID())
                .as("非 user 消息不被 tag（CC utils.ts:23 return m）")
                .isNull();
    }

    /** 17 参兼容构造器构建一条普通 user 消息（sourceToolUseID 默认 null）。 */
    private static ChatMessageDto baseUserMessage(String id) {
        return new ChatMessageDto(
                id, null, Role.user, "user", "content", null, null, null,
                null, null, null, null, null, null, null, List.of(), List.of());
    }
}
