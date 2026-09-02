package com.nexusai.application.agent.skill;

import com.nexusai.model.command.ClientEnv;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-8 client-env 前端环境声明过滤测试 · 对齐 CC commands.ts:417-443 meetsAvailabilityRequirement
 * 的 web 扩展镜像（SkillRegistry.filterByClientEnv）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>前端环境声明是 web 端 availability 门控的入口</b>——CC CLI 无 client-env 概念（E2:
 *       CC 不从任何请求头读环境），DEC-8 是 Java web 扩展：controller 接收 X-Client-Env
 *       请求头（react|mobile）透传到命令过滤链，按声明环境过滤 availability 不匹配的命令。
 *       语义镜像 CC meetsAvailabilityRequirement：无 availability → universal 放行（:418）、
 *       声明中任一环境命中 → 放行（:419-441）、否则排除（:442）。</li>
 *   <li><b>无环境声明默认放行（web 兼容）</b>——前端不传 X-Client-Env 时（如旧客户端 / 未升级
 *       web 端）不得过滤任何命令，否则回归全量命令列表。用例 (c) 锁定该兼容默认。</li>
 *   <li><b>映射是单点可调的 web 猜测</b>——react→CONSOLE / mobile→CLAUDE_AI 是待主 agent+用户
 *       确认的默认映射（CC 无 client-env），隔离在 {@code ClientEnv.satisfies} 单点；测试锁定
 *       当前默认，反转映射时只需改 ClientEnv.satisfies + 本测试断言方向。</li>
 *   <li><b>空 availability 列表 ≠ universal</b>——JS {@code !cmd.availability} 对 {@code []} 为
 *       false，空数组落循环无命中 → return false（:442），命令恒不可用（与既有
 *       SkillRegistryAvailabilityGateTest 空列表用例一致）。用例 (f) 锁定该 CC truthiness 边界。</li>
 *   <li><b>filterByClientEnv 是纯函数</b>——无状态变更，直接构造 Command+availability 喂入即可测
 *       （不经 DB / 磁盘 / 认证状态），与 {@code SkillRegistry} 既有 availability 认证门控
 *       （:377-401，auth 态信号源）正交，非双实现漂移。</li>
 * </ol>
 */
class SkillRegistryClientEnvFilterTest {

    private final SkillRegistry registry = new SkillRegistry(".claude/skills");

    private static Command command(String name, List<CommandAvailability> availability) {
        Command c = new Command();
        c.setName(name);
        c.setAvailability(availability);
        return c;
    }

    @Test
    @DisplayName("声明 react → [console] 命令放行、[claude-ai] 命令被过滤（DEC-8 映射 react→CONSOLE）")
    void reactEnv_consolePassed_claudeAiFiltered() {
        List<Command> input = List.of(
            command("console-cmd", List.of(CommandAvailability.CONSOLE)),
            command("claude-cmd", List.of(CommandAvailability.CLAUDE_AI)));

        List<Command> out = registry.filterByClientEnv(input, ClientEnv.REACT);

        assertThat(out)
            .extracting(Command::getName)
            .containsExactly("console-cmd");
    }

    @Test
    @DisplayName("声明 mobile → [claude-ai] 命令放行、[console] 命令被过滤（DEC-8 映射 mobile→CLAUDE_AI）")
    void mobileEnv_claudeAiPassed_consoleFiltered() {
        List<Command> input = List.of(
            command("console-cmd", List.of(CommandAvailability.CONSOLE)),
            command("claude-cmd", List.of(CommandAvailability.CLAUDE_AI)));

        List<Command> out = registry.filterByClientEnv(input, ClientEnv.MOBILE);

        assertThat(out)
            .extracting(Command::getName)
            .containsExactly("claude-cmd");
    }

    @Test
    @DisplayName("无环境声明(null) → 含 availability 声明命令全部放行（web 兼容默认，过滤链不激活）")
    void noEnvDeclared_allPassed() {
        List<Command> input = List.of(
            command("console-cmd", List.of(CommandAvailability.CONSOLE)),
            command("claude-cmd", List.of(CommandAvailability.CLAUDE_AI)));

        List<Command> out = registry.filterByClientEnv(input, null);

        assertThat(out)
            .extracting(Command::getName)
            .containsExactly("console-cmd", "claude-cmd");
    }

    @Test
    @DisplayName("availability=null → universal，任意环境下放行（CC commands.ts:418）")
    void nullAvailability_universalInAnyEnv() {
        List<Command> input = List.of(command("universal", null));

        assertThat(registry.filterByClientEnv(input, ClientEnv.REACT))
            .extracting(Command::getName)
            .containsExactly("universal");
        assertThat(registry.filterByClientEnv(input, ClientEnv.MOBILE))
            .extracting(Command::getName)
            .containsExactly("universal");
    }

    @Test
    @DisplayName("多环境 any-hit：[claude-ai, console] 命令在 react 与 mobile 下均放行（CC :419-441）")
    void multiEnvAnyHit_passedInBoth() {
        List<Command> input = List.of(command("multi",
            List.of(CommandAvailability.CLAUDE_AI, CommandAvailability.CONSOLE)));

        assertThat(registry.filterByClientEnv(input, ClientEnv.REACT))
            .extracting(Command::getName)
            .containsExactly("multi");
        assertThat(registry.filterByClientEnv(input, ClientEnv.MOBILE))
            .extracting(Command::getName)
            .containsExactly("multi");
    }

    @Test
    @DisplayName("空 availability 列表 ≠ universal → 排除（CC :442 truthiness 边界）")
    void emptyAvailabilityList_excluded() {
        List<Command> input = List.of(command("empty", List.of()));

        assertThat(registry.filterByClientEnv(input, ClientEnv.REACT)).isEmpty();
        assertThat(registry.filterByClientEnv(input, ClientEnv.MOBILE)).isEmpty();
    }

    @Test
    @DisplayName("fromHeader 严格解析：react/mobile 命中、null/blank/未知值 → null（universal，web 兼容）")
    void fromHeader_parsesStrictly() {
        assertThat(ClientEnv.fromHeader("react")).isEqualTo(ClientEnv.REACT);
        assertThat(ClientEnv.fromHeader("mobile")).isEqualTo(ClientEnv.MOBILE);
        assertThat(ClientEnv.fromHeader("REACT")).isEqualTo(ClientEnv.REACT);
        assertThat(ClientEnv.fromHeader(null)).isNull();
        assertThat(ClientEnv.fromHeader("")).isNull();
        assertThat(ClientEnv.fromHeader("  ")).isNull();
        // 未知值 → null（universal，前端拼写错误静默放行但 log.warn）
        assertThat(ClientEnv.fromHeader("desktop")).isNull();
        assertThat(ClientEnv.fromHeader("web")).isNull();
    }
}
