package com.nexusai.apis.skill;

import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.domain.command.CommandService;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import com.nexusai.model.command.dto.CommandDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SkillController#list} 合并数据源测试（plain JUnit，mock SkillRegistry + CommandService）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图 · 联调三问题·skill 根因）:
 * <ol>
 *   <li><b>GET /api/v1/skills 不再恒空</b>——前端 useSkills 消费本端点渲染 SkillsPanel；旧实现只读
 *       DB/磁盘（0 行/空目录）→ 恒返回 [] → 『暂无技能』。修复后必须返回内存 SkillRegistry 真实技能。</li>
 *   <li><b>同名去重且 SkillRegistry 权威</b>——DB 残留同名不同内容时以 live 源为准（CC getCommands
 *       单一真源），避免「DB 幽灵覆盖真实技能」。</li>
 * </ol>
 */
class SkillControllerListMergeTest {

    private SkillController controller;
    private SkillRegistry skillRegistry;
    private CommandService commandService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new SkillController();
        skillRegistry = mock(SkillRegistry.class);
        commandService = mock(CommandService.class);
        ReflectionTestUtils.setField(controller, "skillRegistry", skillRegistry);
        ReflectionTestUtils.setField(controller, "commandService", commandService);
        // toDtos 真实映射（把合并结果转 DTO 供响应体断言）
        when(commandService.toDtos(any())).thenAnswer(inv ->
            ((List<Command>) inv.getArgument(0)).stream().map(SkillControllerListMergeTest::dtoOf).toList());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static Command command(String name, String description) {
        Command c = new Command();
        c.setName(name);
        c.setDescription(description);
        c.setSource(CommandSource.USER);
        c.setEnabled(Boolean.TRUE);
        return c;
    }

    private static CommandDto dtoOf(Command c) {
        return new CommandDto(
            c.getId(), c.getName(), c.getDescription(), c.getVersion(), c.getSource(),
            c.getAliases(), c.getArgumentHint(), c.getWhenToUse(),
            Boolean.TRUE.equals(c.getUserInvocable()),
            Boolean.TRUE.equals(c.getDisableModelInvocation()),
            Boolean.TRUE.equals(c.getIsHidden()),
            Boolean.TRUE.equals(c.getIsSensitive()),
            Boolean.TRUE.equals(c.getImmediate()),
            c.getKind(), c.getContext(), c.getAgent(), c.getAllowedTools(), c.getModel(),
            c.getEffort(), c.getPaths(), c.getHooks(), c.getContent(), c.getContentPath(),
            c.getBaseDir(), c.getProgressMessage(),
            Boolean.TRUE.equals(c.getEnabled()), Boolean.TRUE.equals(c.getBuiltin()),
            c.getType(), null); // pluginName：无插件信息时 null（对齐 CommandService.toDto:554-556）
    }

    @Test
    @DisplayName("mock getAllCommands() 2 技能 + listAllDomain() 空 → GET /api/v1/skills 返回 2 项（修复恒空根因）")
    void registrySkills_areReturned_whenDomainEmpty() throws Exception {
        when(skillRegistry.getAllCommands()).thenReturn(List.of(
            command("skill-a", "A"), command("skill-b", "B")));
        when(commandService.listAllDomain()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        ArgumentCaptor<List<Command>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandService).toDtos(captor.capture());
        assertThat(captor.getValue())
            .as("合并结果 = SkillRegistry 2 技能（DB/磁盘空不吞掉）")
            .extracting(Command::getName)
            .containsExactlyInAnyOrder("skill-a", "skill-b");
    }

    @Test
    @DisplayName("mock 同名 1 项（SkillRegistry + listAllDomain 各 1）→ 去重后 1 项且 SkillRegistry 源胜出")
    void sameName_deduped_registryWins() throws Exception {
        Command registryVersion = command("skill-a", "registry desc");
        Command dbGhost = command("skill-a", "db desc");
        when(skillRegistry.getAllCommands()).thenReturn(List.of(registryVersion));
        when(commandService.listAllDomain()).thenReturn(List.of(dbGhost));

        mockMvc.perform(get("/api/v1/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        ArgumentCaptor<List<Command>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandService).toDtos(captor.capture());
        assertThat(captor.getValue())
            .as("同名去重 → 1 项且 SkillRegistry 实例胜出（DB 幽灵不覆盖 live 源）")
            .hasSize(1)
            .containsExactly(registryVersion);
    }
}
