package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-9 第 5 源（COMMANDS 内置命令）并入 SkillRegistry 的聚合测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>getAllCommands 必须含内置命令</b>——CC loadAllCommands 合并序最后追加 COMMANDS()
 *       （commands.ts:467），Java loadAllCommands 第 5 源 {@link BuiltInCommands#getAll()} 使
 *       clear/compact/init 出现在命令聚合中（React 经 REST 消费）。若第 5 源未接线 → 本测试 fail。</li>
 *   <li><b>getModelInvocableCommands 不含内置命令</b>——CC getSkillToolCommands :570
 *       {@code source !== 'builtin'}：内置命令 source='builtin' 不进模型可调用清单。Java :628 现成过滤
 *       自动排除（init type='prompt' 但 source=BUILTIN → 被挡）；若 COMMANDS 源 source 误标非 BUILTIN
 *       → init 泄漏进清单，本测试 fail（对齐 BuiltInCommandsTest 的 source 契约）。</li>
 *   <li><b>getSlashCommandToolSkills 不含内置命令</b>——CC getSlashCommandToolSkills :593
 *       {@code source !== 'builtin'}：内置命令不进斜杠技能集（getSkillInfo 数据源）。</li>
 *   <li><b>findCommand 三维命中内置命令</b>——'clear' 精确名、'continue' alias→resume、'/clear'
 *       前导 '/' 剥除。内置命令仍进 findCommand 消费面（SkillTool 搜索基座可命中），仅模型/斜杠
 *       两套过滤链排除。</li>
 * </ol>
 */
class SkillRegistryBuiltInCommandsTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        // 隔离跨测试泄漏的 bundled 注册集（内置命令源 BuiltInCommands 为静态不可变，无需 clear）
        BundledSkills.clear();
        registry = new SkillRegistry(tempDir.toString());
    }

    private static final List<String> BUILTIN_NAMES =
        List.of("clear", "compact", "config", "help", "init", "memory",
            "model", "output-style", "resume", "session");

    @Test
    @DisplayName("getAllCommands 含内置命令 clear/compact/init（第 5 源并入，CC commands.ts:467 ...COMMANDS()）")
    void getAllCommands_containsBuiltin() {
        List<Command> all = registry.getAllCommands();

        assertThat(all).extracting(Command::getName).contains("clear", "compact", "init");
        assertThat(all).extracting(Command::getName).containsAll(BUILTIN_NAMES);
    }

    @Test
    @DisplayName("getModelInvocableCommands 不含任何内置命令（CC commands.ts:570 source!=='builtin'，Java :628 现成过滤）")
    void getModelInvocableCommands_excludesBuiltin() {
        List<Command> invocable = registry.getModelInvocableCommands();

        assertThat(invocable).extracting(Command::getName).doesNotContainAnyElementsOf(BUILTIN_NAMES);
    }

    @Test
    @DisplayName("getSlashCommandToolSkills 不含内置命令（CC commands.ts:593 source!=='builtin'，Java :780 现成过滤）")
    void getSlashCommandToolSkills_excludesBuiltin() {
        List<Command> slash = registry.getSlashCommandToolSkills();

        assertThat(slash).extracting(Command::getName).doesNotContainAnyElementsOf(BUILTIN_NAMES);
    }

    @Test
    @DisplayName("findCommand('clear') 精确名命中内置命令（findCommand 消费面含 BUILTIN）")
    void findCommand_clear() {
        Command hit = registry.findCommand("clear");
        assertThat(hit).isNotNull();
        assertThat(hit.getName()).isEqualTo("clear");
        assertThat(hit.getSource().name()).isEqualTo("BUILTIN");
    }

    @Test
    @DisplayName("findCommand('continue') 经 alias 命中 resume（内置命令 alias 三维匹配）")
    void findCommand_alias_continue_to_resume() {
        Command hit = registry.findCommand("continue");
        assertThat(hit).isNotNull();
        assertThat(hit.getName()).isEqualTo("resume");
    }

    @Test
    @DisplayName("findCommand('/clear') 前导 '/' 剥除命中内置命令")
    void findCommand_stripsLeadingSlash() {
        Command hit = registry.findCommand("/clear");
        assertThat(hit).isNotNull();
        assertThat(hit.getName()).isEqualTo("clear");
    }
}
