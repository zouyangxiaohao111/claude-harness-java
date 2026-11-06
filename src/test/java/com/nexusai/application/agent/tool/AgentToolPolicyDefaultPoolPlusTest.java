package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「引入知识库」开关的池装配测试（QA-12）。
 *
 * <p>守的是一条容易写错的语义：<b>只放行点名的 4 个 KB 工具，不能顺带放行其它专属工具</b>。
 * 写成「池里全部工具名当白名单」会看起来能用，实际上把别的专属工具也一起开了。
 */
class AgentToolPolicyDefaultPoolPlusTest {

    /** 可控 name 的普通工具。 */
    static class CommonTool implements Tool {
        private final String name;

        CommonTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }

        @Override
        public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
            com.nexusai.application.agent.tool.ToolUseBlock call) {
            return null;   // 本测试只关心 name / scope，不真执行
        }
    }

    /**
     * ⭐ 专属工具：**必须**用真实的 {@code @ExclusiveTool} 注解，
     * 因为 {@code ToolScope.of} 是读类注解判定的。用一个「自称专属」的假类
     * 会让测试恒绿（判定走的是注解，不是字段）。
     */
    @ExclusiveTool
    static class ExclusiveFakeTool extends CommonTool {
        ExclusiveFakeTool(String name) {
            super(name);
        }
    }

    private static final List<String> KB = List.of(
        "search_knowledge", "list_documents", "read_document", "query_knowledge_graph");

    private static List<Tool> pool() {
        return List.of(
            new CommonTool("Bash"),
            new CommonTool("Read"),
            new ExclusiveFakeTool("search_knowledge"),
            new ExclusiveFakeTool("list_documents"),
            new ExclusiveFakeTool("read_document"),
            new ExclusiveFakeTool("query_knowledge_graph"),
            new ExclusiveFakeTool("other_exclusive_tool"));
    }

    @Test
    @DisplayName("⭐ 默认池 + 4 个 KB 工具：只多出这 4 个")
    void defaultPoolPlusKbTools() {
        Optional<List<String>> names = AgentToolPolicy.defaultPoolPlus(pool(), KB);
        assertThat(names).isPresent();
        assertThat(names.get())
            .contains("Bash", "Read")
            .contains("search_knowledge", "list_documents", "read_document", "query_knowledge_graph");
    }

    @Test
    @DisplayName("⛔ 不放行其它专属工具（这是最容易写错的地方）")
    void doesNotUnlockOtherExclusiveTools() {
        Optional<List<String>> names = AgentToolPolicy.defaultPoolPlus(pool(), KB);
        assertThat(names.get()).doesNotContain("other_exclusive_tool");
    }

    @Test
    @DisplayName("池里不存在的名字被忽略（不制造幽灵名单项）")
    void unknownNamesIgnored() {
        Optional<List<String>> names = AgentToolPolicy.defaultPoolPlus(
            pool(), List.of("search_knowledge", "nonexistent_tool"));
        assertThat(names.get()).contains("search_knowledge").doesNotContain("nonexistent_tool");
    }

    @Test
    @DisplayName("空池 ⇒ Optional.empty（调用方回落通配）")
    void emptyPoolReturnsEmpty() {
        assertThat(AgentToolPolicy.defaultPoolPlus(List.of(), KB)).isEmpty();
        assertThat(AgentToolPolicy.defaultPoolPlus(null, KB)).isEmpty();
    }

    @Test
    @DisplayName("走显式分支应用后：KB 工具可见，其它专属仍不可见")
    void appliedThroughPolicy() {
        Optional<List<String>> names = AgentToolPolicy.defaultPoolPlus(pool(), KB);
        List<Tool> resolved = AgentToolPolicy.apply(pool(), names, Optional.empty());

        assertThat(resolved).extracting(Tool::name)
            .contains("Bash", "Read", "search_knowledge", "list_documents",
                "read_document", "query_knowledge_graph")
            .doesNotContain("other_exclusive_tool");
    }

    @Test
    @DisplayName("开关关闭（通配分支）：4 个 KB 工具都不可见（= 改造前行为）")
    void wildcardHidesAllExclusive() {
        List<Tool> resolved = AgentToolPolicy.apply(pool(), Optional.empty(), Optional.empty());
        assertThat(resolved).extracting(Tool::name)
            .contains("Bash", "Read")
            .doesNotContain("search_knowledge", "list_documents",
                "read_document", "query_knowledge_graph", "other_exclusive_tool");
    }

    @Test
    @DisplayName("名单保序且无重复")
    void noDuplicates() {
        Optional<List<String>> names = AgentToolPolicy.defaultPoolPlus(
            pool(), List.of("search_knowledge", "search_knowledge", "Bash"));
        assertThat(names.get()).doesNotHaveDuplicates();
    }
}
