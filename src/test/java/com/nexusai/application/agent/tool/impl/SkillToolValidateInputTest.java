package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-1] SkillToolImpl.validateInput errorCode 5 对齐 CC 测试 ·
 * 对齐 CC {@code Open-ClaudeCode/src/tools/SkillTool/SkillTool.ts:411-430}.
 *
 * <p>规则九（验证意图）: 旧实现 validateInput（SkillToolImpl.java:476-502）在 errorCode 4
 * （disableModelInvocation）之后直接 return true —— 对 {@code type !== 'prompt'} 的技能
 * （如未来接入的 workflow 技能）不设防，偏离 CC 契约:
 * <pre>
 *   // Check if command is a prompt-based command        (SkillTool.ts:420)
 *   if (foundCommand.type !== 'prompt') {                 (SkillTool.ts:421)
 *     return { result: false,                             (SkillTool.ts:422)
 *       message: `Skill ${name} is not a prompt-based skill`, (SkillTool.ts:424)
 *       errorCode: 5 }                                    (SkillTool.ts:425)
 *   }
 *   return { result: true }                               (SkillTool.ts:429)
 * </pre>
 * 若 errorCode 5 检查被删除，type='workflow' 命令的 validateInput 会误判 ok=true ——
 * 非 prompt 技能被当作普通 prompt 技能进入执行链，本测试必 fail（差分对照：type 默认
 * 'prompt'（Command.java:104）仍返回 ok，证明新增分支只拒绝非 prompt 命令）。
 */
@DisplayName("[P2-1] SkillToolImpl.validateInput errorCode 5 (type!=='prompt') 对齐 CC SkillTool.ts:420-429")
class SkillToolValidateInputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 返回单个 Command 的注册表（避免加载磁盘/Bundled 技能）。 */
    private static final class SingleCommandRegistry extends SkillRegistry {
        private final List<Command> cmds;

        SingleCommandRegistry(List<Command> cmds) {
            super(".claude/skills");
            this.cmds = cmds;
        }

        @Override
        public List<Command> getAllCommands() {
            return cmds;
        }
    }

    private static SkillRegistry registryWith(Command cmd) {
        return new SingleCommandRegistry(List.of(cmd));
    }

    private static ToolUseContext ctx() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    private static ToolUseBlock skillBlock(String skillName) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", skillName);
        return new ToolUseBlock(UUID.randomUUID().toString(), "Skill", input);
    }

    private static Command namedSkill(String name) {
        Command cmd = new Command();
        cmd.setName(name);
        return cmd;
    }

    /** type 非 'prompt' 的命令（如未来 workflow 技能）→ errorCode 5 拒绝。 */
    @Test
    @DisplayName("type='workflow' 命令 → ValidationResult(false, \"5\", \"Skill <name> is not a prompt-based skill\")")
    void nonPromptCommand_returnsErrorCode5() {
        // WHY: CC SkillTool.ts:421-425 对 type!=='prompt' 命令返回 errorCode 5 ——
        //   validateInput 是工具执行的语义前置闸，非 prompt 技能（workflow 等）不得
        //   被 Skill 工具当作 prompt 技能展开。若此分支被删除，validateInput 对非 prompt
        //   命令误返回 ok=true，CC 契约缺失（P2-1 补齐项）。
        Command cmd = namedSkill("build-deploy");
        // 显式把类型改为非 prompt（模拟未来 workflow 命令；Command.java:104 默认 'prompt'）
        cmd.setType("workflow");
        SkillToolImpl tool = new SkillToolImpl(registryWith(cmd));

        Tool.ValidationResult result = tool.validateInput(
                skillBlock("build-deploy").input(), ctx());

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("5");
        // 消息逐字对齐 CC SkillTool.ts:424 `Skill ${normalizedCommandName} is not a prompt-based skill`
        assertThat(result.message()).isEqualTo("Skill build-deploy is not a prompt-based skill");
    }

    /** type 默认 'prompt'（Command.java:104）→ 仍返回 ok（差分对照：新增分支只拒绝非 prompt）。 */
    @Test
    @DisplayName("type 默认 'prompt' 命令 → validateInput 仍返回 ok=true（errorCode 5 不误伤 prompt 技能）")
    void promptCommand_defaultType_returnsOk() {
        // WHY: Command.java:104 默认 type='prompt' —— 当前生产全部技能走 prompt 语义，
        //   errorCode 5 新增分支不得把正常 prompt 技能拒之门外（差分证明守卫精确到非 prompt）。
        SkillToolImpl tool = new SkillToolImpl(registryWith(namedSkill("commit")));

        Tool.ValidationResult result = tool.validateInput(
                skillBlock("commit").input(), ctx());

        assertThat(result.ok()).isTrue();
        assertThat(result.errorCode()).isNull();
        assertThat(result.message()).isNull();
    }

    /** 显式 setType(\"prompt\") 命令 → 仍返回 ok（对齐 CC foundCommand.type === 'prompt' 精确语义）。 */
    @Test
    @DisplayName("显式 setType(\"prompt\") 命令 → validateInput 仍返回 ok=true")
    void promptCommand_explicitType_returnsOk() {
        Command cmd = namedSkill("review-pr");
        cmd.setType("prompt");
        SkillToolImpl tool = new SkillToolImpl(registryWith(cmd));

        Tool.ValidationResult result = tool.validateInput(
                skillBlock("review-pr").input(), ctx());

        assertThat(result.ok()).isTrue();
        assertThat(result.errorCode()).isNull();
    }

    /** disableModelInvocation=true → errorCode 4（CC SkillTool.ts:412-418）。 */
    @Test
    @DisplayName("disableModelInvocation=true → ValidationResult(false, \"4\", \"Skill <name> cannot be used with Skill tool due to disable-model-invocation\")")
    void disabledCommand_returnsErrorCode4() {
        // WHY: CC SkillTool.ts:412-418 —— 搜索基座（getAllCommands，含 MCP 搜索视图）不过滤
        //   disableModelInvocation，技能可达后由 validateInput 拒绝 errorCode 4（S3/R2B-DEC-9：
        //   旧实现 MCP 预滤 → errorCode 2「Unknown skill」偏移）。若 errorCode 4 分支被删除，
        //   disableModelInvocation 技能被误放行进入执行链，本测试必 fail。
        Command cmd = namedSkill("debug");
        cmd.setDisableModelInvocation(Boolean.TRUE);
        SkillToolImpl tool = new SkillToolImpl(registryWith(cmd));

        Tool.ValidationResult result = tool.validateInput(
                skillBlock("debug").input(), ctx());

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("4");
        // 消息逐字对齐 CC SkillTool.ts:415-416
        //   `Skill ${normalizedCommandName} cannot be used with Skill tool due to disable-model-invocation`
        assertThat(result.message())
            .isEqualTo("Skill debug cannot be used with Skill tool due to disable-model-invocation");
    }
}
