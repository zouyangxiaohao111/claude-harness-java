package com.nexusai.application.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AgentToolPolicy 语义测试（规则九：测意图）。
 *
 * <p><b>WHY（意图）</b>：专家模式的 {@code tools:} 此前只在子代理路径生效，主线程拿到的是全量池
 * ——「专家只换人格、工具不受限」。本测试钉死两条用户裁定（2026-10-23）：
 * <ol>
 *   <li>绑定 agent → 白名单是<b>替换</b>（名单外一律不可见，含 Skill/WebSearch 等通用工具）；</li>
 *   <li>专属工具（{@link ExclusiveTool}）<b>不进默认池/通配池</b>，只有逐名点名才可见。</li>
 * </ol>
 * 并且权限链优先：被 {@code disallowedTools} 挡掉的工具，即使白名单点名也不能复活。
 */
@DisplayName("AgentToolPolicy: 白名单替换 + 专属工具仅点名可见")
class AgentToolPolicyTest {

    private static final Tool READ = new FakeTool("Read");
    private static final Tool BASH = new FakeTool("Bash");
    private static final Tool SKILL = new FakeTool("Skill");
    private static final Tool OA = new ExclusiveFakeTool("OaTodoList");

    private static List<Tool> pool() {
        return List.of(READ, BASH, SKILL, OA);
    }

    @Test
    @DisplayName("通配（tools 缺省）→ 默认池：剔除专属工具，其余全保留")
    void wildcardAbsent_excludesExclusive() {
        List<Tool> out = AgentToolPolicy.apply(pool(), Optional.empty(), Optional.empty());

        assertThat(out).extracting(Tool::name).containsExactly("Read", "Bash", "Skill");
        // WHY: 默认会话不能摸到 OA 专属工具 —— 否则「专属」只对绑了别的 agent 的会话成立。
        assertThat(out).extracting(Tool::name).doesNotContain("OaTodoList");
    }

    @Test
    @DisplayName("通配 ['*'] → 与缺省同义：依然剔除专属工具")
    void wildcardStar_excludesExclusive() {
        List<Tool> out = AgentToolPolicy.apply(pool(), Optional.of(List.of("*")), Optional.empty());

        assertThat(out).extracting(Tool::name).containsExactly("Read", "Bash", "Skill");
    }

    @Test
    @DisplayName("显式白名单 → 替换：只返回名单内的工具（通用工具没写也拿不到）")
    void explicitList_replacesPool() {
        List<Tool> out = AgentToolPolicy.apply(pool(), Optional.of(List.of("Read", "Grep")), Optional.empty());

        // Grep 不在池里 → 忽略；名单外（Bash/Skill）不可见
        assertThat(out).extracting(Tool::name).containsExactly("Read");
    }

    @Test
    @DisplayName("显式点名专属工具 → 可见（这是专属工具唯一可见的路径）")
    void explicitList_includesExclusiveWhenNamed() {
        List<Tool> out = AgentToolPolicy.apply(pool(),
                Optional.of(List.of("Read", "OaTodoList")), Optional.empty());

        // 顺序跟白名单声明，不是池顺序（对齐 CC by-name 解析）
        assertThat(out).extracting(Tool::name).containsExactly("Read", "OaTodoList");
    }

    @Test
    @DisplayName("显式空列表 [] → 空工具集（CC 语义：显式空 ≠ 通配）")
    void explicitEmpty_returnsNoTools() {
        List<Tool> out = AgentToolPolicy.apply(pool(), Optional.of(List.of()), Optional.empty());

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("disallowedTools 优先于白名单：点名了也不能复活")
    void disallowedWinsOverWhitelist() {
        List<Tool> out = AgentToolPolicy.apply(pool(),
                Optional.of(List.of("Read", "OaTodoList")),
                Optional.of(List.of("OaTodoList")));

        assertThat(out).extracting(Tool::name).containsExactly("Read");
    }

    @Test
    @DisplayName("通配 + disallowedTools：默认池同样剔除黑名单")
    void wildcard_appliesDisallowed() {
        List<Tool> out = AgentToolPolicy.apply(pool(), Optional.empty(), Optional.of(List.of("Bash")));

        assertThat(out).extracting(Tool::name).containsExactly("Read", "Skill");
    }

    @Test
    @DisplayName("ToolScope.of: 注解工具 = EXCLUSIVE，普通工具 = COMMON，null 兜底 COMMON")
    void toolScope_of() {
        assertThat(ToolScope.of(OA)).isEqualTo(ToolScope.EXCLUSIVE);
        assertThat(ToolScope.of(READ)).isEqualTo(ToolScope.COMMON);
        assertThat(ToolScope.of(null)).isEqualTo(ToolScope.COMMON);
    }

    @Test
    @DisplayName("ToolRegistry: 注册时登记专属工具名，remove 时清理（供工具列表/诊断）")
    void toolRegistry_tracksExclusiveNames() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(READ);
        registry.register(OA);

        assertThat(registry.isExclusive("Read")).isFalse();
        assertThat(registry.isExclusive("OaTodoList")).isTrue();
        assertThat(registry.exclusiveToolNames()).containsExactly("OaTodoList");

        registry.remove("OaTodoList");
        assertThat(registry.isExclusive("OaTodoList")).isFalse();
    }

    // ── 测试替身：只实现 Tool 的 4 个抽象方法 ──────────────────────────────

    private static class FakeTool implements Tool {
        private final String name;

        FakeTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "fake " + name;
        }

        @Override
        public JsonNode inputSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(null, "ok");
        }
    }

    @ExclusiveTool
    private static final class ExclusiveFakeTool extends FakeTool {
        ExclusiveFakeTool(String name) {
            super(name);
        }
    }
}
