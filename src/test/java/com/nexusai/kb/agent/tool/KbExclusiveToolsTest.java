package com.nexusai.kb.agent.tool;

import com.nexusai.application.agent.tool.AgentToolPolicy;
import com.nexusai.application.agent.tool.ExclusiveTool;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库工具的「默认不激活」语义 · 批次 W10。
 *
 * <p><b>⭐ 为什么这是本批最重要的断言</b>：用户裁定「默认不激活，在 MD 中指定就行」。
 * 如果这个语义错了，有两种后果，而且都不报错：
 * <ul>
 *   <li><b>太宽</b>（默认就激活）⇒ 所有会话都能检索知识库，等于绕过了智能体配置；</li>
 *   <li><b>太窄</b>（点名了也不激活）⇒ 用户写了 {@code tools: search_knowledge}
 *       却没生效，只能看到「模型不去查知识库」的怪现象。</li>
 * </ul>
 *
 * <p>这里直接用**真实的** {@link AgentToolPolicy}（不 mock），因为要验的正是它的分支。
 */
@DisplayName("[W10] 知识库工具默认不激活")
class KbExclusiveToolsTest {

    /** 最小 Tool 实现（只为 name 与 @ExclusiveTool 注解服务）。 */
    @ExclusiveTool
    static class FakeKbTool implements Tool {
        private final String name;
        FakeKbTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "fake"; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
            com.nexusai.application.agent.tool.ToolUseBlock call) {
            return null;
        }
    }

    /** 普通工具（无注解）。 */
    static class FakeCommonTool implements Tool {
        @Override public String name() { return "Read"; }
        @Override public String description() { return "common"; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
            com.nexusai.application.agent.tool.ToolUseBlock call) {
            return null;
        }
    }

    private static List<Tool> pool() {
        return List.of(new FakeCommonTool(), new FakeKbTool("search_knowledge"),
            new FakeKbTool("list_documents"), new FakeKbTool("read_document"),
            new FakeKbTool("query_knowledge_graph"));
    }

    @Test
    @DisplayName("⭐ 通配（tools 缺省）⇒ 专属工具全部不可见")
    void wildcardHidesExclusive() {
        List<Tool> out = AgentToolPolicy.apply(pool(), Optional.empty(), null);
        assertThat(out).extracting(Tool::name).containsExactly("Read");
    }

    @Test
    @DisplayName("⭐ ['*'] 也当作通配 ⇒ 专属工具仍不可见")
    void starWildcardHidesExclusive() {
        List<Tool> out = AgentToolPolicy.apply(pool(), Optional.of(List.of("*")), null);
        assertThat(out).extracting(Tool::name).containsExactly("Read");
    }

    @Test
    @DisplayName("⭐ 显式点名 ⇒ 该工具可见")
    void explicitNameReveals() {
        List<Tool> out = AgentToolPolicy.apply(pool(),
            Optional.of(List.of("search_knowledge")), null);
        assertThat(out).extracting(Tool::name).containsExactly("search_knowledge");
    }

    @Test
    @DisplayName("点名多个 ⇒ 按名单顺序返回")
    void explicitMultiple() {
        List<Tool> out = AgentToolPolicy.apply(pool(),
            Optional.of(List.of("query_knowledge_graph", "search_knowledge")), null);
        assertThat(out).extracting(Tool::name)
            .containsExactly("query_knowledge_graph", "search_knowledge");
    }

    @Test
    @DisplayName("⭐ disallowedTools 仍能挡掉已点名的专属工具")
    void disallowedWins() {
        List<Tool> out = AgentToolPolicy.apply(pool(),
            Optional.of(List.of("search_knowledge")), Optional.of(List.of("search_knowledge")));
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("空名单 ⇒ 空工具集（不等于通配）")
    void emptyListMeansNoTools() {
        List<Tool> out = AgentToolPolicy.apply(pool(), Optional.of(List.of()), null);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("四个工具都标了 @ExclusiveTool（源码级护栏）")
    void allFourAnnotated() {
        assertThat(ToolScope.of(new KbSearchKnowledgeTool())).isEqualTo(ToolScope.EXCLUSIVE);
        assertThat(ToolScope.of(new KbListDocumentsTool())).isEqualTo(ToolScope.EXCLUSIVE);
        assertThat(ToolScope.of(new KbReadDocumentTool())).isEqualTo(ToolScope.EXCLUSIVE);
        assertThat(ToolScope.of(new KbQueryGraphTool())).isEqualTo(ToolScope.EXCLUSIVE);
    }

    @Test
    @DisplayName("工具名对齐源侧（search_knowledge / list_documents / read_document / query_knowledge_graph）")
    void toolNamesMatchSource() {
        assertThat(new KbSearchKnowledgeTool().name()).isEqualTo("search_knowledge");
        assertThat(new KbListDocumentsTool().name()).isEqualTo("list_documents");
        assertThat(new KbReadDocumentTool().name()).isEqualTo("read_document");
        assertThat(new KbQueryGraphTool().name()).isEqualTo("query_knowledge_graph");
    }
}
