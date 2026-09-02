package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-3 availability gating 测试 · 对齐 CC commands.ts:417-443 meetsAvailabilityRequirement
 * + :484 getAllCommands 过滤（availability 先于 isEnabled）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>availability 是 auth/provider 门控</b>——CC commands.ts:411-416 注释「This runs before
 *       isEnabled()… provider-gated commands are hidden regardless of feature-flag state」。
 *       无 availability 的命令 universal（:418），声明者仅当用户命中至少一个 auth 类型才可见。
 *       web 端默认无 claude-ai/console 订阅（DEC-8），未注入 AvailabilityAuthState 时全 false/true
 *       默认态下 availability=null → universal 直通，运行时行为零变化 —— 用例 (a) 锁定该默认不回归。</li>
 *   <li><b>'claude-ai' 门控 = isClaudeAISubscriber</b>（:422）——claude.ai OAuth 订阅用户专属。</li>
 *   <li><b>'console' 门控 = 直连 1P API key 用户</b>（:427-433）——三重否定：非订阅 && 非 3P &&
 *       直连 first-party base URL。任一不满足即排除（Bedrock/Vertex/Foundry 用户、自定义 base URL
 *       网关用户、claude.ai 订阅用户全被挡）。</li>
 *   <li><b>dynamic skill 同样过 gate</b>——CC :493-498 dynamicSkills 独立过
 *       meetsAvailabilityRequirement(s)；Java 侧动态技能经 loadAllCommands 合并进 raw 后由
 *       getAllCommands 统一过滤（net 等价）。</li>
 *   <li><b>空数组 availability ≠ universal</b>——JS {@code !cmd.availability} 对 {@code []} 为 false，
 *       空数组落循环无命中 → return false（:442），命令恒不可用。用例 (h) 锁定该 CC truthiness 边界。</li>
 * </ol>
 *
 * <p>夹具：动态技能源（DynamicSkillsManager 子类覆写 getDynamicSkills）喂入带 availability 的命令，
 * 走真实 loadAllCommands → getAllCommands 门控路径（非 FixtureRegistry 绕过）。
 */
class SkillRegistryAvailabilityGateTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        registry = new SkillRegistry(tempDir.toString());
    }

    /** 注入动态技能源（对齐 CC getCommands 叠加 getDynamicSkills，commands.ts:476-517） */
    private void injectDynamicSkill(Command cmd) {
        registry.setDynamicSkillsManager(new FixtureDynamicSkillsManager(List.of(cmd)));
    }

    /** 注入认证状态三元组（对齐 CC setAvailabilityAuthState，auth.ts:1564/:1732 + providers.ts:25） */
    private void setAuthState(boolean subscriber, boolean using3P, boolean firstParty) {
        registry.setAvailabilityAuthState(new SkillRegistry.AvailabilityAuthState(
            () -> subscriber, () -> using3P, () -> firstParty));
    }

    private static Command command(String name, List<CommandAvailability> availability) {
        Command c = new Command();
        c.setName(name);
        c.setAvailability(availability);
        return c;
    }

    @Test
    @DisplayName("availability=null → universal 恒包含（CC commands.ts:418 默认态，运行时零变化）")
    void availabilityNull_universalIncluded() {
        injectDynamicSkill(command("universal-skill", null));

        // 默认 AvailabilityAuthState（subscriber=false / using3P=false / firstParty=true）
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .contains("universal-skill");
    }

    @Test
    @DisplayName("[claude-ai] + subscriber=true → 包含 / subscriber=false → 排除（CC commands.ts:421-423）")
    void claudeAi_subscriberGates() {
        injectDynamicSkill(command("claude-skill", List.of(CommandAvailability.CLAUDE_AI)));

        setAuthState(true, false, true);
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .contains("claude-skill");

        setAuthState(false, false, true);
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .doesNotContain("claude-skill");
    }

    @Test
    @DisplayName("[console] + subscriber=false && using3P=false && firstParty=true → 包含（CC :428-433）")
    void console_directApiKey_included() {
        injectDynamicSkill(command("console-skill", List.of(CommandAvailability.CONSOLE)));

        setAuthState(false, false, true);
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .contains("console-skill");
    }

    @Test
    @DisplayName("[console] + using3P=true(Bedrock/Vertex/Foundry) → 排除（CC :430 !isUsing3PServices）")
    void console_using3P_excluded() {
        injectDynamicSkill(command("console-3p", List.of(CommandAvailability.CONSOLE)));

        setAuthState(false, true, true);
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .doesNotContain("console-3p");
    }

    @Test
    @DisplayName("[console] + firstParty=false(自定义 base URL 网关) → 排除（CC :431 isFirstPartyAnthropicBaseUrl）")
    void console_customBaseUrl_excluded() {
        injectDynamicSkill(command("console-proxy", List.of(CommandAvailability.CONSOLE)));

        setAuthState(false, false, false);
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .doesNotContain("console-proxy");
    }

    @Test
    @DisplayName("[console] + subscriber=true(claude.ai 订阅用户) → 排除（CC :429 !isClaudeAISubscriber）")
    void console_subscriber_excluded() {
        injectDynamicSkill(command("console-sub", List.of(CommandAvailability.CONSOLE)));

        setAuthState(true, false, true);
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .doesNotContain("console-sub");
    }

    @Test
    @DisplayName("dynamic skill 声明 availability → 经 getAllCommands 统一过滤排除（CC :493-498 净等价）")
    void dynamicSkill_availabilityGated() {
        injectDynamicSkill(command("dyn-claude", List.of(CommandAvailability.CLAUDE_AI)));

        // 默认 auth state（subscriber=false）→ claude-ai 门控排除（对齐 CC dynamicSkills 独立过滤）
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .doesNotContain("dyn-claude");
    }

    @Test
    @DisplayName("空 availability 列表 ≠ universal → 排除（JS !cmd.availability 对 [] 为 false → 循环无命中 → :442 false）")
    void emptyAvailabilityList_excluded() {
        injectDynamicSkill(command("empty-avail", List.of()));

        setAuthState(false, false, true);
        assertThat(registry.getAllCommands())
            .extracting(Command::getName)
            .doesNotContain("empty-avail");
    }

    /** 动态技能夹具 · 覆写 getDynamicSkills 直接喂入带 availability 的命令（走真实 getAllCommands 门控路径） */
    private static final class FixtureDynamicSkillsManager extends DynamicSkillsManager {
        private final List<Command> skills;

        FixtureDynamicSkillsManager(List<Command> skills) {
            this.skills = skills;
        }

        @Override
        public List<Command> getDynamicSkills() {
            return skills;
        }
    }
}
