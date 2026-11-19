package com.nexusai.application.agent.subagent;

import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [CC 对齐] 技能名三策略解析 · 对齐 CCB {@code runAgent.ts:931-959 resolveSkillName}
 * （exe v2.1.325 同形 {@code Zu(e,n,s)}）：直接匹配 → 插件前缀 → 后缀匹配。
 *
 * <p><b>WHY</b>：agent frontmatter 的 {@code skills:} 里写短名（插件命名空间技能）时，旧实现
 * 只做 {@code registry.findCommand} 三维匹配 → 查不到 → 该技能被静默跳过。本测试锁定三策略顺序，
 * 其中第 ② ③ 步是本次对齐新增的可观测行为。
 */
class SkillPreloaderSkillNameResolutionTest {

    private static Command cmd(String name) {
        Command c = new Command();
        c.setName(name);
        return c;
    }

    @Test
    @DisplayName("① 直接匹配：name / aliases 命中")
    void directMatch() {
        Command alpha = cmd("alpha");
        Command beta = cmd("beta");
        beta.setAliases(List.of("b-alias"));
        List<Command> all = List.of(alpha, beta);

        assertThat(SkillPreloader.resolveSkillName("alpha", all, "plug:agent")).isSameAs(alpha);
        assertThat(SkillPreloader.resolveSkillName("b-alias", all, "plug:agent")).isSameAs(beta);
    }

    @Test
    @DisplayName("② 插件前缀：agentType 'plug:agent' → 命中 'plug:my-skill'")
    void pluginPrefixMatch() {
        Command namespaced = cmd("plug:my-skill");
        List<Command> all = List.of(cmd("other"), namespaced);

        assertThat(SkillPreloader.resolveSkillName("my-skill", all, "plug:agent")).isSameAs(namespaced);
    }

    @Test
    @DisplayName("③ 后缀匹配：name 以 ':my-skill' 结尾，首个命中者胜")
    void suffixMatch() {
        Command first = cmd("aaa:my-skill");
        Command second = cmd("bbb:my-skill");
        List<Command> all = List.of(cmd("other"), first, second);

        assertThat(SkillPreloader.resolveSkillName("my-skill", all, "plug:agent")).isSameAs(first);
    }

    @Test
    @DisplayName("全不命中 → null；agentType 为 null 时仍走后缀匹配")
    void notFoundReturnsNull() {
        Command namespaced = cmd("aaa:my-skill");
        List<Command> all = List.of(cmd("other"), namespaced);

        assertThat(SkillPreloader.resolveSkillName("nope", all, "plug:agent")).isNull();
        // agentType=null → 跳过前缀策略，但后缀策略仍生效（CCB 第 3 步无 agentType 依赖）
        assertThat(SkillPreloader.resolveSkillName("my-skill", all, null)).isSameAs(namespaced);
    }
}
