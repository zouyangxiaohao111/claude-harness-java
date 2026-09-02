package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-9 COMMANDS 内置命令源单元测试 · 对齐 CC commands.ts:258 COMMANDS 数组子集
 * （clear/compact/config/help/init/memory/model/output-style/resume/session/effort）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>内置命令必须 source=BUILTIN/builtin=true</b>——SkillRegistry 过滤链
 *       getModelInvocableCommands:628 / getSlashCommandToolSkills:780 靠 {@code source != BUILTIN}
 *       排除内置命令（对齐 CC commands.ts:570/:593 source!=='builtin'）；若 source 标错（如误标
 *       USER），init（type='prompt'）会泄漏进模型可调用清单 —— 本测试锁定 source 契约。</li>
 *   <li><b>type 必须取 CC 真实类型</b>——clear/compact='local'、config/help/memory/model/
 *       output-style/resume/session='local-jsx'、init='prompt'（React 靠 type 区分渲染/触发，
 *       BuiltInCommandDto 携带）。</li>
 *   <li><b>aliases 逐命令对齐 CC</b>——clear ['reset','new']（clear/index.ts:14）/ config ['settings']
 *       （config/index.ts:4）/ resume ['continue']（resume/index.ts:7）/ session ['remote']
 *       （session/index.ts:6）；findCommand 三维匹配与 execute 端点按 alias 解析依赖别名正确性。</li>
 *   <li><b>output-style isHidden=true</b>（output-style/index.ts:7）——React 默认不渲染隐藏命令。</li>
 *   <li><b>compact argumentHint 非空</b>（compact/index.ts:11）——UI 提示参数输入。</li>
 *   <li><b>findByName 前导 '/' 归一化 + alias 三维命中</b>——execute 端点 REST 语义（'continue'
 *       命中 resume、'/clear' 命中 clear、未知 null→404）。</li>
 * </ol>
 */
class BuiltInCommandsTest {

    @Test
    @DisplayName("getAll() 产出 11 命令，全部 source=BUILTIN/builtin=true（对齐 CC commands.ts:467 合并源）")
    void getAll_elevenCommands_allBuiltin() {
        List<Command> all = BuiltInCommands.getAll();

        assertThat(all).hasSize(11);
        assertThat(all).extracting(Command::getName).containsExactly(
            "clear", "compact", "config", "help", "init", "memory",
            "model", "output-style", "resume", "session", "effort");
        assertThat(all).allSatisfy(c -> {
            assertThat(c.getSource()).isEqualTo(CommandSource.BUILTIN);
            assertThat(c.getBuiltin()).isTrue();
        });
    }

    @Test
    @DisplayName("type 对齐 CC 真实类型：clear/compact='local'，config/help/memory/model/output-style/resume/session='local-jsx'，init='prompt'")
    void type_matchesCcRealType() {
        List<Command> all = BuiltInCommands.getAll();
        assertThat(byName(all, "clear").getType()).isEqualTo("local");
        assertThat(byName(all, "compact").getType()).isEqualTo("local");
        assertThat(byName(all, "config").getType()).isEqualTo("local-jsx");
        assertThat(byName(all, "help").getType()).isEqualTo("local-jsx");
        assertThat(byName(all, "memory").getType()).isEqualTo("local-jsx");
        assertThat(byName(all, "model").getType()).isEqualTo("local-jsx");
        assertThat(byName(all, "output-style").getType()).isEqualTo("local-jsx");
        assertThat(byName(all, "resume").getType()).isEqualTo("local-jsx");
        assertThat(byName(all, "session").getType()).isEqualTo("local-jsx");
        assertThat(byName(all, "init").getType()).isEqualTo("prompt");
    }

    @Test
    @DisplayName("aliases 逐命令对齐 CC：clear [reset,new] / config [settings] / resume [continue] / session [remote]")
    void aliases_matchCc() {
        List<Command> all = BuiltInCommands.getAll();
        assertThat(byName(all, "clear").getAliases()).containsExactly("reset", "new");
        assertThat(byName(all, "config").getAliases()).containsExactly("settings");
        assertThat(byName(all, "resume").getAliases()).containsExactly("continue");
        assertThat(byName(all, "session").getAliases()).containsExactly("remote");
    }

    @Test
    @DisplayName("output-style isHidden=true（output-style/index.ts:7）；其余 9 命令默认可见")
    void outputStyle_isHidden() {
        List<Command> all = BuiltInCommands.getAll();
        assertThat(byName(all, "output-style").getIsHidden()).isTrue();
        assertThat(all.stream().filter(c -> !"output-style".equals(c.getName())))
            .allSatisfy(c -> assertThat(c.getIsHidden()).isFalse());
    }

    @Test
    @DisplayName("compact argumentHint 非空 + init progressMessage（compact/index.ts:11 / init.ts:237）")
    void compact_argumentHint_init_progressMessage() {
        List<Command> all = BuiltInCommands.getAll();
        assertThat(byName(all, "compact").getArgumentHint())
            .isNotBlank()
            .isEqualTo("<optional custom summarization instructions>");
        assertThat(byName(all, "init").getProgressMessage()).isEqualTo("analyzing your codebase");
    }

    @Test
    @DisplayName("compact isEnabled 门控：DISABLE_COMPACT env 缺省启用、truthy 禁用（compact/index.ts:9 + envUtils.ts:32-36）")
    void compact_isEnabledEnvGate() {
        // CC compact/index.ts:9 isEnabled: () => !isEnvTruthy(process.env.DISABLE_COMPACT) ——
        // JDK 9+ 强封装下 System.getenv 不可就地设置（AgentModelResolverTest:16），经 envProvider 测试
        // 接缝覆写（对齐 AutoCompactorCcContractTest.setEnvProvider 模式）。
        java.util.function.Function<String, String> saved = BuiltInCommands.envProvider;
        try {
            // env 缺省 → 启用（isCommandEnabled 每调用新鲜求值）
            BuiltInCommands.envProvider = key -> null;
            assertThat(BuiltInCommands.findByName("compact").isCommandEnabled())
                .as("DISABLE_COMPACT 缺省 → compact 启用")
                .isTrue();
            // truthy → 禁用（CC isEnvTruthy：'1'/'true'/'yes'/'on' 大小写不敏感为真）
            BuiltInCommands.envProvider = key -> "DISABLE_COMPACT".equals(key) ? "true" : null;
            assertThat(BuiltInCommands.findByName("compact").isCommandEnabled())
                .as("DISABLE_COMPACT=true → compact 禁用")
                .isFalse();
            BuiltInCommands.envProvider = key -> "DISABLE_COMPACT".equals(key) ? "1" : null;
            assertThat(BuiltInCommands.findByName("compact").isCommandEnabled())
                .as("DISABLE_COMPACT=1 → compact 禁用")
                .isFalse();
            BuiltInCommands.envProvider = key -> "DISABLE_COMPACT".equals(key) ? "YES" : null;
            assertThat(BuiltInCommands.findByName("compact").isCommandEnabled())
                .as("DISABLE_COMPACT=YES（大小写不敏感）→ compact 禁用")
                .isFalse();
            // falsy 值 → 启用（envUtils.ts:34 ['1','true','yes','on'].includes 外 → false）
            BuiltInCommands.envProvider = key -> "DISABLE_COMPACT".equals(key) ? "0" : null;
            assertThat(BuiltInCommands.findByName("compact").isCommandEnabled())
                .as("DISABLE_COMPACT=0 → compact 启用")
                .isTrue();
        } finally {
            BuiltInCommands.envProvider = saved;
        }
    }

    @Test
    @DisplayName("findByName 三维命中：精确名 / alias（continue→resume）/ 前导 '/' 剥除 / 未知 null")
    void findByName_threeDim() {
        assertThat(BuiltInCommands.findByName("clear")).isNotNull();
        assertThat(BuiltInCommands.findByName("clear").getName()).isEqualTo("clear");
        // alias：continue → resume
        assertThat(BuiltInCommands.findByName("continue")).isNotNull();
        assertThat(BuiltInCommands.findByName("continue").getName()).isEqualTo("resume");
        // alias：remote → session / settings → config / reset|new → clear
        assertThat(BuiltInCommands.findByName("remote").getName()).isEqualTo("session");
        assertThat(BuiltInCommands.findByName("settings").getName()).isEqualTo("config");
        assertThat(BuiltInCommands.findByName("reset").getName()).isEqualTo("clear");
        // 前导 '/' 剥除（对齐 SkillRegistry.findCommand 归一化 + execute 端点 REST 语义）
        assertThat(BuiltInCommands.findByName("/clear").getName()).isEqualTo("clear");
        assertThat(BuiltInCommands.findByName("/continue").getName()).isEqualTo("resume");
        // 未知 → null（CC findCommand 返回 undefined → REST 404）
        assertThat(BuiltInCommands.findByName("nope")).isNull();
        assertThat(BuiltInCommands.findByName(null)).isNull();
        assertThat(BuiltInCommands.findByName("  ")).isNull();
    }

    /** 按 name 查找命令（测试辅助） */
    private static Command byName(List<Command> all, String name) {
        return all.stream()
            .filter(c -> name.equals(c.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("命令 " + name + " 不存在"));
    }
}
