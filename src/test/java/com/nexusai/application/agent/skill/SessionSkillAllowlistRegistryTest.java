package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [CC 对齐] 会话级技能白名单 · 对齐 exe {@code Tve(list, sessionSkillAllowlist)} /
 * {@code H$(agentContext)} / dynamic_skill 的 {@code agentId===void 0} 判据。
 *
 * <p><b>WHY</b>：三态（未配置 / 空列表 / 名单）与作用范围（主线程 / 后台主会话 / fork / 普通子代理）
 * 是本次新增的可观测行为，必须逐条锁死，否则很容易退化成「白名单永远不过滤」或「子代理被误过滤」。
 */
class SessionSkillAllowlistRegistryTest {

    /** 非主线程 agentId（CC 单一形态 a+16hex，String）。 */
    private static final String AGENT =
        com.nexusai.application.agent.subagent.AgentContext.createAgentId();

    private static Command cmd(String name) {
        Command c = new Command();
        c.setName(name);
        return c;
    }

    @Test
    @DisplayName("三态：null=不过滤；[]=全禁；名单=只留匹配项")
    void filterTriState() {
        List<Command> all = List.of(cmd("alpha"), cmd("beta"));

        assertThat(SessionSkillAllowlistRegistry.filterByAllowlist(all, null)).hasSize(2);
        assertThat(SessionSkillAllowlistRegistry.filterByAllowlist(all, List.of())).isEmpty();
        assertThat(SessionSkillAllowlistRegistry.filterByAllowlist(all, List.of("beta")))
            .extracting(Command::getName).containsExactly("beta");
    }

    @Test
    @DisplayName("匹配规则：name 精确 或 插件命名空间 :后缀；MCP 不参与后缀匹配")
    void matchesNameAndSuffix() {
        Command plain = cmd("my-skill");
        Command namespaced = cmd("plug:my-skill");
        Command mcpNamespaced = cmd("mcp:my-skill");
        mcpNamespaced.setSource(CommandSource.MCP);
        List<Command> all = List.of(plain, namespaced, mcpNamespaced);

        // 短名 "my-skill" 命中 name 精确 + 插件后缀，MCP 被排除
        assertThat(SessionSkillAllowlistRegistry.filterByAllowlist(all, List.of("my-skill")))
            .extracting(Command::getName).containsExactly("my-skill", "plug:my-skill");
        // MCP 命令仍可按全名精确命中（CC Tve：name===s 优先于 tjn 后缀分支）
        assertThat(SessionSkillAllowlistRegistry.filterByAllowlist(all, List.of("mcp:my-skill")))
            .extracting(Command::getName).containsExactly("mcp:my-skill");
    }

    @Test
    @DisplayName("dynamic_skill 名字过滤走同一套规则")
    void filterNames() {
        List<String> names = List.of("a", "plug:a", "b");
        assertThat(SessionSkillAllowlistRegistry.filterNamesByAllowlist(names, null)).hasSize(3);
        assertThat(SessionSkillAllowlistRegistry.filterNamesByAllowlist(names, List.of()))
            .isEmpty();
        assertThat(SessionSkillAllowlistRegistry.filterNamesByAllowlist(names, List.of("a")))
            .containsExactly("a", "plug:a");
    }

    @Test
    @DisplayName("作用范围：主线程 / 后台主会话 / fork 子代理 套用；普通子代理不套用")
    void applicability() {
        assertThat(SessionSkillAllowlistRegistry.appliesToListing(null, null, false)).isTrue();
        assertThat(SessionSkillAllowlistRegistry.appliesToListing(AGENT, "main-session", false)).isTrue();
        assertThat(SessionSkillAllowlistRegistry.appliesToListing(AGENT, "fork", true)).isTrue();
        assertThat(SessionSkillAllowlistRegistry.appliesToListing(AGENT, "fork", false)).isFalse();
        assertThat(SessionSkillAllowlistRegistry.appliesToListing(AGENT, "researcher", true)).isFalse();

        // dynamic_skill：仅主线程（CC e.agentId===void 0），fork 也不过滤
        assertThat(SessionSkillAllowlistRegistry.appliesToDynamicSkill(null)).isTrue();
        assertThat(SessionSkillAllowlistRegistry.appliesToDynamicSkill(AGENT)).isFalse();
    }

    @Test
    @DisplayName("fromAgent：人格点名技能 → 设白名单；人格未点名（null/空）→ 清除不过滤")
    void putFromAgentSemantics() {
        String sid = "sess-" + UUID.randomUUID();

        SessionSkillAllowlistRegistry.putFromAgent(sid, List.of("a", "b"));
        assertThat(SessionSkillAllowlistRegistry.get(sid)).containsExactly("a", "b");

        SessionSkillAllowlistRegistry.putFromAgent(sid, List.of());
        assertThat(SessionSkillAllowlistRegistry.get(sid)).isNull();

        SessionSkillAllowlistRegistry.putFromAgent(sid, null);
        assertThat(SessionSkillAllowlistRegistry.get(sid)).isNull();
    }

    @Test
    @DisplayName("注册表：put(null)=清除；空列表与未配置可区分")
    void registryLifecycle() {
        String sid = "sess-" + UUID.randomUUID();
        assertThat(SessionSkillAllowlistRegistry.get(sid)).isNull();

        SessionSkillAllowlistRegistry.put(sid, List.of());
        assertThat(SessionSkillAllowlistRegistry.get(sid)).isEmpty();

        SessionSkillAllowlistRegistry.put(sid, List.of("a"));
        assertThat(SessionSkillAllowlistRegistry.get(sid)).containsExactly("a");

        SessionSkillAllowlistRegistry.put(sid, null);
        assertThat(SessionSkillAllowlistRegistry.get(sid)).isNull();
    }
}
