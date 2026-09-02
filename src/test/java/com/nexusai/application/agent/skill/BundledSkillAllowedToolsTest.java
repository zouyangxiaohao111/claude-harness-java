package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-5 schedule/update-config allowedTools 透传回归锁定 · 对齐 CC
 * scheduleRemoteAgents.ts:335 + updateConfig.ts:450 + bundledSkills.ts:81。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>Command 级（端到端）是"透传不丢失"唯一未锁环节</b>——Definition 级已由
 *       {@link BundledSkillDefinitionTest}（scheduleRegisterCarriesAllowedTools /
 *       updateConfigRegisterCarriesAllowedTools）锁定，keybindings 的 Command 级已由
 *       BundledSkillsP08RegistrationTest（:56-58）锁定；但 schedule/update-config 的 Command 级
 *       链路（register() → def.allowedTools → BundledSkillsBootstrapper.register →
 *       {@link BundledSkillDefinition#toCommand()} → Command.getAllowedTools()）无任何测试锁定。
 *       若未来某 Registrar 退回无 allowedTools 的碎片 def（E8/E9 根因回归），或 toCommand()
 *       漏映射，本测试必红——工具权限丢失即被暴露（规则十二 fail loud）。</li>
 *   <li><b>name 映射 + 顺序即契约</b>——schedule 的 allowedTools 必须按 CC 声明顺序
 *       [RemoteTrigger, AskUserQuestion]（scheduleRemoteAgents.ts:335，常量
 *       RemoteTriggerTool/prompt.ts:1='RemoteTrigger'、AskUserQuestionTool/prompt.ts:3=
 *       'AskUserQuestion'）；update-config=[Read]（updateConfig.ts:450）。CC bundledSkills.ts:81
 *       是直传 definition.allowedTools，顺序即语义，不得倒置/漏项。</li>
 * </ol>
 *
 * <p>注册路径：{@code new BundledSkillsBootstrapper().run(null)} —— DEFAULTS feature flags
 * （agentTriggersRemote=true → schedule 注册门控通过，CC bundled/index.ts:56）；update-config
 * 无条件注册。
 */
class BundledSkillAllowedToolsTest {

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @Test
    @DisplayName("run() 后 schedule Command.getAllowedTools()=[RemoteTrigger,AskUserQuestion]（CC scheduleRemoteAgents.ts:335，修 E8）")
    void bootstrapperRun_scheduleCommandCarriesAllowedTools() {
        new BundledSkillsBootstrapper().run(null);

        Command schedule = byName("schedule");
        assertThat(schedule)
            .as("schedule 必须已注册（DEFAULTS agentTriggersRemote=true 门控通过，CC bundled/index.ts:56）")
            .isNotNull();
        assertThat(schedule.getAllowedTools())
            .as("E8 透传断链：schedule Command.getAllowedTools() 必须=[RemoteTrigger,AskUserQuestion]"
                + "（CC scheduleRemoteAgents.ts:335；常量 RemoteTriggerTool/prompt.ts:1 / AskUserQuestionTool/prompt.ts:3）")
            .containsExactly("RemoteTrigger", "AskUserQuestion");
        assertThat(schedule.getEnabled())
            .as("schedule isEnabled 桩 ()->false&&false → Command.enabled=false"
                + "（CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_surreal_dali',false)=false 默认关，语义一致；P2-4 遗留仅记录不接线）")
            .isFalse();
    }

    @Test
    @DisplayName("run() 后 update-config Command.getAllowedTools()=[Read]（CC updateConfig.ts:450，修 E9）")
    void bootstrapperRun_updateConfigCommandCarriesAllowedTools() {
        new BundledSkillsBootstrapper().run(null);

        Command updateConfig = byName("update-config");
        assertThat(updateConfig)
            .as("update-config 必须已注册（CC bundled/index.ts 无条件）")
            .isNotNull();
        assertThat(updateConfig.getAllowedTools())
            .as("E9 透传断链：update-config Command.getAllowedTools() 必须=[Read]（CC updateConfig.ts:450）")
            .containsExactly("Read");
        assertThat(updateConfig.getEnabled())
            .as("update-config isEnabled 未提供 → Command.enabled 默认 true（toCommand() 不 set）")
            .isTrue();
    }

    private static Command byName(String name) {
        Map<String, Command> byName = BundledSkills.getAll().stream()
            .collect(Collectors.toMap(Command::getName, Function.identity()));
        return byName.get(name);
    }
}
