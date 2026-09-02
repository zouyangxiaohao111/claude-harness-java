package com.nexusai.apis.command;

import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.domain.command.CommandService;
import com.nexusai.model.command.ClientEnv;
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
 * {@link CommandController#list} 合并数据源 + client-env 门控回归测试
 * （plain JUnit，mock SkillRegistry + CommandService，对齐 CommandControllerBuiltInCommandsTest）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图 · 联调三问题·skill 根因）:
 * <ol>
 *   <li><b>GET /api/command 不再恒空</b>——与 /api/v1/skills 同源（前端 '/ 面板' 与设置页都消费
 *       命令/技能列表）。修复后合并 SkillRegistry 内存真实技能，同名去重且 SkillRegistry 权威。</li>
 *   <li><b>X-Client-Env 头仍作用于合并集</b>——DEC-8 client-env 门控不回归：合并后仍须过
 *       {@code filterByClientEnv}（CC commands.ts:417-443 的 web 扩展镜像），否则前端环境声明失效。</li>
 * </ol>
 */
class CommandControllerListMergeTest {

    private CommandController controller;
    private SkillRegistry skillRegistry;
    private CommandService commandService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new CommandController();
        skillRegistry = mock(SkillRegistry.class);
        commandService = mock(CommandService.class);
        ReflectionTestUtils.setField(controller, "skillRegistry", skillRegistry);
        ReflectionTestUtils.setField(controller, "commandService", commandService);
        // client-env 过滤默认放行（DEC-8 无环境头兼容）
        when(skillRegistry.filterByClientEnv(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(commandService.toDtos(any())).thenAnswer(inv ->
            ((List<Command>) inv.getArgument(0)).stream().map(CommandControllerListMergeTest::dtoOf).toList());
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
    @DisplayName("mock getAllCommands() 2 项 + listAllDomain() 1 项同名 → 去重后 2 项且 SkillRegistry 优先")
    void sameName_deduped_registryWins() throws Exception {
        Command registryA = command("skill-a", "registry desc");
        Command registryB = command("skill-b", "B");
        Command dbGhostA = command("skill-a", "db desc");
        when(skillRegistry.getAllCommands()).thenReturn(List.of(registryA, registryB));
        when(commandService.listAllDomain()).thenReturn(List.of(dbGhostA));

        mockMvc.perform(get("/api/command"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        ArgumentCaptor<List<Command>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandService).toDtos(captor.capture());
        assertThat(captor.getValue())
            .as("同名 skill-a 去重为 1 项（SkillRegistry 源），skill-b 补入 → 共 2 项")
            .extracting(Command::getName)
            .containsExactlyInAnyOrder("skill-a", "skill-b");
    }

    @Test
    @DisplayName("X-Client-Env 头仍作用于合并集：filterByClientEnv 收到合并后命令集 + ClientEnv.REACT（DEC-8 不回归）")
    void clientEnvHeader_stillFiltersMergedSet() throws Exception {
        Command registryA = command("skill-a", "A");
        Command dbGhostB = command("skill-b", "B");
        when(skillRegistry.getAllCommands()).thenReturn(List.of(registryA));
        when(commandService.listAllDomain()).thenReturn(List.of(dbGhostB));

        mockMvc.perform(get("/api/command").header("X-Client-Env", "react"))
            .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Command>> captor = ArgumentCaptor.forClass((Class<List<Command>>) (Class<?>) List.class);
        ArgumentCaptor<ClientEnv> envCaptor = ArgumentCaptor.forClass(ClientEnv.class);
        verify(skillRegistry).filterByClientEnv(captor.capture(), envCaptor.capture());
        assertThat(captor.getValue())
            .as("client-env 过滤输入 = 合并集（SkillRegistry + DB/磁盘 ghost）")
            .extracting(Command::getName)
            .containsExactlyInAnyOrder("skill-a", "skill-b");
        assertThat(envCaptor.getValue())
            .as("X-Client-Env: react 头必须解析为 REACT 并透传过滤链")
            .isEqualTo(ClientEnv.REACT);
    }
}
