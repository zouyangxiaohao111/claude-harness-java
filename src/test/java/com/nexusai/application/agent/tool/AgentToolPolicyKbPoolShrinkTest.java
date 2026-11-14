package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库会话「工具池塌缩」现象的可执行说明（2026-11-14 真机排查产物）。
 *
 * <p><b>现象</b>：智能推理（ReAct）会话里模型回答「当前会话可调用的工具仅有 Read、
 * AskUserQuestion」——4 个 KB 工具消失。真机日志（同事机器 info(1).log 17:50:44）：
 * <pre>
 * 会话工具策略入口  池=108  池内知识库工具=4
 * 会话工具策略入口  池=2    池内知识库工具=0
 * WARN 名单有 4 个名字在当轮工具池中不存在 [search_knowledge, read_document, list_documents, query_knowledge_graph]
 * </pre>
 *
 * <p><b>本测试要说明的三件事</b>：
 * <ol>
 *   <li>显式名单分支是<b>安全</b>的：完整池 + {@code smart-reasoning} 名单 ⇒ 6 个工具全在
 *       （4 个 KB 专属 + Read + AskUserQuestion）。</li>
 *   <li><b>通配分支会把 4 个 KB 工具吃掉</b>：同一份 6 个工具再按通配过一遍 ⇒ 只剩 2 个
 *       （{@code @ExclusiveTool} 只允许被点名）。日志里的「池=2」正是这一步的产物。</li>
 *   <li>用「已经塌缩的 2 个」再按名单解析 ⇒ 仍然是 2，并报 4 个名字缺失
 *       —— 这就是那条 WARN 的来处，它只是<b>症状</b>，不是原因。</li>
 * </ol>
 *
 * <p>⇒ 结论（测试固化）：只要发现「池内知识库工具=4 → 池=2」，
 * 就说明这条链上**多了一次通配 apply**。排查方向应该是「谁把 6 个的池又喂回了通配分支」，
 * 而不是去查 KB 工具本身或模型能力。
 */
class AgentToolPolicyKbPoolShrinkTest {

    /**
     * {@code KbBuiltInAgentDefs.TOOLS_SMART_REASONING} 的逐字副本。
     *
     * <p>⛔ 该常量在 {@code com.nexusai.kb.agent} 包内是包私有，跨包测试读不到；
     * 这里复制一份并断言「名字必须能在池里解析到」——名单漂移时本测试会红。
     */
    private static final List<String> SMART_REASONING_TOOLS = List.of(
        "search_knowledge",
        "read_document",
        "list_documents",
        "query_knowledge_graph",
        "AskUserQuestion",
        "Read"
    );

    /** 可控 name 的普通工具（{@link ToolScope} 判定读类注解，字段不作数）。 */
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
        public AgentToolResult<?> execute(ToolUseBlock call) {
            return null;   // 本测试只关心与可见性相关的 name / scope
        }
    }

    /** 专属工具：必须用真实的 {@code @ExclusiveTool} 注解，否则判定不到 EXCLUSIVE。 */
    @ExclusiveTool
    static class ExclusiveFakeTool extends CommonTool {
        ExclusiveFakeTool(String name) {
            super(name);
        }
    }

    /**
     * 复刻真机工具池形状：104 个通用工具 + 4 个 KB 专属工具（总数 108，与日志
     * {@code 池=108 池内知识库工具=4} 同形）。
     */
    private static List<Tool> fullPool() {
        List<Tool> pool = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            pool.add(new CommonTool("generic_tool_" + i));
        }
        pool.add(new CommonTool("Bash"));
        pool.add(new CommonTool("Read"));
        pool.add(new CommonTool("AskUserQuestion"));
        pool.add(new ExclusiveFakeTool("search_knowledge"));
        pool.add(new ExclusiveFakeTool("list_documents"));
        pool.add(new ExclusiveFakeTool("read_document"));
        pool.add(new ExclusiveFakeTool("query_knowledge_graph"));
        return pool;
    }

    private static List<String> namesOf(List<Tool> tools) {
        return tools.stream().map(Tool::name).toList();
    }

    @Test
    @DisplayName("① 完整池 + smart-reasoning 名单 ⇒ 6 个工具全在（显式分支不吃专属工具）")
    void explicitListKeepsAllSix() {
        List<Tool> pool = fullPool();
        assertThat(pool).hasSize(108);
        assertThat(pool).filteredOn(t -> ToolScope.of(t) == ToolScope.EXCLUSIVE).hasSize(4);

        List<Tool> resolved = AgentToolPolicy.apply(
            pool, Optional.of(SMART_REASONING_TOOLS), Optional.empty());

        assertThat(namesOf(resolved)).containsExactlyElementsOf(SMART_REASONING_TOOLS);
    }

    @Test
    @DisplayName("② ⭐ 同一份 6 个再走通配 ⇒ 只剩 Read + AskUserQuestion（= 日志里的 池=2）")
    void wildcardOverSixShrinksToTwo() {
        List<Tool> six = AgentToolPolicy.apply(
            fullPool(), Optional.of(SMART_REASONING_TOOLS), Optional.empty());

        List<Tool> wildcarded = AgentToolPolicy.apply(six, Optional.empty(), Optional.empty());

        assertThat(namesOf(wildcarded))
            .containsExactlyInAnyOrder("Read", "AskUserQuestion")
            .doesNotContain("search_knowledge", "read_document",
                "list_documents", "query_knowledge_graph");
    }

    @Test
    @DisplayName("③ 用塌缩后的 2 个再按名单解析 ⇒ 仍 2 个（那条 WARN 只是症状，不是原因）")
    void resolvingShrunkPoolLosesNothingMore() {
        List<Tool> six = AgentToolPolicy.apply(
            fullPool(), Optional.of(SMART_REASONING_TOOLS), Optional.empty());
        List<Tool> two = AgentToolPolicy.apply(six, Optional.empty(), Optional.empty());

        List<Tool> again = AgentToolPolicy.apply(
            two, Optional.of(SMART_REASONING_TOOLS), Optional.empty());

        assertThat(namesOf(again)).containsExactlyInAnyOrder("Read", "AskUserQuestion");
        // 幂等：再套一次名单不会继续减少，也不会复活专属工具
        assertThat(namesOf(AgentToolPolicy.apply(
            again, Optional.of(SMART_REASONING_TOOLS), Optional.empty())))
            .containsExactlyInAnyOrder("Read", "AskUserQuestion");
    }

    @Test
    @DisplayName("④ 完整池走通配 ⇒ 104 个（专属工具全隐），不是 2 —— 2 必然来自「已经收窄过的池」")
    void wildcardOverFullPoolYields104() {
        List<Tool> wildcarded = AgentToolPolicy.apply(
            fullPool(), Optional.empty(), Optional.empty());

        assertThat(wildcarded).hasSize(104);
        assertThat(namesOf(wildcarded)).doesNotContain(
            "search_knowledge", "read_document", "list_documents", "query_knowledge_graph");
    }

    @Test
    @DisplayName("⑤ 名单里点名的工具若不在池中 ⇒ 被丢弃（真机那条「4 个名字不存在」WARN 的成因）")
    void missingNamesAreDropped() {
        List<Tool> onlyCommon = List.of(
            new CommonTool("Read"), new CommonTool("AskUserQuestion"));

        List<Tool> resolved = AgentToolPolicy.apply(
            onlyCommon, Optional.of(SMART_REASONING_TOOLS), Optional.empty());

        assertThat(namesOf(resolved))
            .containsExactlyInAnyOrder("Read", "AskUserQuestion");
    }
}
