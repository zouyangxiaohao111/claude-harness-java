package com.nexusai.kb.agent;

import com.nexusai.application.agent.subagent.AgentDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [KB 内置 Agent 对齐 · 阶段 A] {@link KbBuiltInAgentDefs} 结构测试。
 *
 * <p><b>本测试不读人格 md</b>：{@code all()} 会触发 {@code KbPromptLoader} 读文件，
 * 而 md 是阶段 B 的产出。这里只验「定义的结构正确」（id / mode / type / 工具清单），
 * 通过直接调各项 {@code smartReasoning()} 等方法的 <b>元数据</b>来验 ——
 * 但这些方法内部 {@code build()} 不读 md（读 md 发生在 {@code getSystemPrompt} 调用时），
 * 所以本测试可以安全调用。
 */
class KbBuiltInAgentDefsTest {

    @Test
    @DisplayName("6 个 agent id 与源站 yaml 一致（assistant 已去 WeKnora 改名）")
    void agent_ids_match_source_yaml() {
        List<AgentDefinition> defs = KbBuiltInAgentDefs.all();

        assertThat(defs).hasSize(6);
        assertThat(defs.stream().map(AgentDefinition::agentType).collect(Collectors.toList()))
            .containsExactly(
                "builtin-quick-answer",
                "builtin-smart-reasoning",
                "builtin-data-analyst",
                "builtin-wiki-researcher",
                "builtin-wiki-fixer",
                "builtin-assistant"
            );
    }

    @Test
    @DisplayName("没有 builtin-skill-installer（用户裁定不做）")
    void skill_installer_excluded() {
        Set<String> ids = KbBuiltInAgentDefs.all().stream()
            .map(AgentDefinition::agentType)
            .collect(Collectors.toSet());

        assertThat(ids).doesNotContain("builtin-skill-installer");
    }

    @Test
    @DisplayName("没有 builtin-weknora-assistant 原 id（界面不出现 WeKnora）")
    void weknora_id_renamed() {
        Set<String> ids = KbBuiltInAgentDefs.all().stream()
            .map(AgentDefinition::agentType)
            .collect(Collectors.toSet());

        assertThat(ids).doesNotContain("builtin-weknora-assistant");
        assertThat(ids).contains("builtin-assistant");
    }

    @Test
    @DisplayName("quick-answer 是 quick-answer 模式且零工具、无 kbAgentType")
    void quick_answer_shape() {
        AgentDefinition def = KbBuiltInAgentDefs.quickAnswer();

        assertThat(def.agentMode()).hasValue("quick-answer");
        assertThat(def.kbAgentType()).as("源站 quick-answer 下 agent_type 被忽略").isEmpty();
        assertThat(def.tools()).hasValue(List.of());
    }

    @Test
    @DisplayName("其余 5 个都是 smart-reasoning 且有 kbAgentType")
    void smart_reasoning_shape() {
        assertThat(KbBuiltInAgentDefs.smartReasoning().agentMode()).hasValue("smart-reasoning");
        assertThat(KbBuiltInAgentDefs.smartReasoning().kbAgentType()).hasValue("rag-qa");

        assertThat(KbBuiltInAgentDefs.dataAnalyst().agentMode()).hasValue("smart-reasoning");
        assertThat(KbBuiltInAgentDefs.dataAnalyst().kbAgentType()).hasValue("data-analysis");

        assertThat(KbBuiltInAgentDefs.wikiResearcher().agentMode()).hasValue("smart-reasoning");
        assertThat(KbBuiltInAgentDefs.wikiResearcher().kbAgentType()).hasValue("wiki-qa");

        assertThat(KbBuiltInAgentDefs.wikiFixer().agentMode()).hasValue("smart-reasoning");
        assertThat(KbBuiltInAgentDefs.wikiFixer().kbAgentType()).hasValue("custom");

        assertThat(KbBuiltInAgentDefs.assistant().agentMode()).hasValue("smart-reasoning");
        assertThat(KbBuiltInAgentDefs.assistant().kbAgentType()).hasValue("custom");
    }

    @Test
    @DisplayName("工具清单条数与源站 yaml 一致（5/3/4/9/14）")
    void tool_counts_match_source() {
        assertThat(KbBuiltInAgentDefs.smartReasoning().tools()).hasValueSatisfying(
            t -> assertThat(t).hasSize(5));
        assertThat(KbBuiltInAgentDefs.dataAnalyst().tools()).hasValueSatisfying(
            t -> assertThat(t).hasSize(3));
        assertThat(KbBuiltInAgentDefs.wikiResearcher().tools()).hasValueSatisfying(
            t -> assertThat(t).hasSize(4));
        assertThat(KbBuiltInAgentDefs.wikiFixer().tools()).hasValueSatisfying(
            t -> assertThat(t).hasSize(9));
        assertThat(KbBuiltInAgentDefs.assistant().tools()).hasValueSatisfying(
            t -> assertThat(t).hasSize(14));
    }

    @Test
    @DisplayName("工具名用本仓真名（AskUserQuestion / TodoWrite，不是源站 ask_user / todo_write）")
    void tool_names_use_nexus_names() {
        Set<String> allTools = new HashSet<>();
        KbBuiltInAgentDefs.all().forEach(d -> d.tools().ifPresent(allTools::addAll));

        assertThat(allTools).contains("AskUserQuestion");
        assertThat(allTools).contains("TodoWrite");
        assertThat(allTools).doesNotContain("ask_user");
        assertThat(allTools).doesNotContain("todo_write");
    }

    @Test
    @DisplayName("web_search / web_fetch 不进 allowed_tools（源站 yaml :326-328：走开关不走白名单）")
    void web_tools_not_in_whitelist() {
        Set<String> allTools = new HashSet<>();
        KbBuiltInAgentDefs.all().forEach(d -> d.tools().ifPresent(allTools::addAll));

        assertThat(allTools).doesNotContain("web_search");
        assertThat(allTools).doesNotContain("WebSearch");
        assertThat(allTools).doesNotContain("WebFetch");
    }

    @Test
    @DisplayName("人格正文由外置 md 提供 · 6 份全部能加载且非空")
    void md_loads_non_empty() {
        // WHY: 空 system prompt = 静默缺陷（模型自由发挥且零报错）。
        // 阶段 B 起 6 份 md 已落盘，这里验证每条都能真读到正文。
        for (AgentDefinition def : KbBuiltInAgentDefs.all()) {
            String prompt = def.getSystemPrompt("model", List.of());
            assertThat(prompt)
                .as("%s 的人格正文（kb/agent/%s.md）", def.agentType(), def.agentType())
                .isNotBlank()
                .contains("# 角色");
        }
    }

    @Test
    @DisplayName("人格正文不含品牌名（界面不出现 WeKnora）")
    void md_has_no_brand_name() {
        for (AgentDefinition def : KbBuiltInAgentDefs.all()) {
            String prompt = def.getSystemPrompt("model", List.of());
            assertThat(prompt)
                .as("%s 的人格正文", def.agentType())
                .doesNotContain("WeKnora")
                .doesNotContain("weknora")
                .doesNotContain("Tencent")
                .doesNotContain("腾讯");
        }
    }

    @Test
    @DisplayName("人格正文里的工具名全部在本仓真实存在或属于阶段 D 计划产出")
    void md_tool_names_are_real_or_planned() {
        // WHY: 抄提示词前先核工具契约 —— 提示词里写了不存在的工具，
        // 模型会反复调用然后每次撞墙（历史教训：源站提示词的 doc://N / mode= 本仓全无）。
        //
        // 本测试的两类白名单：
        //   ① 已存在：4 个 CC 工具 + 4 个 kb 工具
        //   ② 阶段 D 计划产出：9 个 wiki_* + 2 个 data_*
        // 阶段 D 完成后应把②并入①（届时本测试仍绿）。
        Set<String> existing = Set.of(
            "AskUserQuestion", "TodoWrite", "WebSearch", "WebFetch",
            "search_knowledge", "read_document", "list_documents", "query_knowledge_graph"
        );
        Set<String> plannedPhaseD = Set.of(
            "wiki_search", "wiki_read_page", "wiki_write_page", "wiki_replace_text",
            "wiki_rename_page", "wiki_delete_page", "wiki_flag_issue",
            "wiki_read_issue", "wiki_update_issue",
            "data_schema", "data_analysis"
        );
        Set<String> allowed = new HashSet<>(existing);
        allowed.addAll(plannedPhaseD);

        // 形如反引号包裹的 snake_case / CamelCase 标识符，再排除已知的非工具词
        Set<String> notTools = Set.of(
            "dataset", "id", "offset", "next_offset", "context", "mode", "hybrid",
            "keyword", "semantic", "knowledge_base_ids", "knowledge_id", "old_text",
            "slug"
        );

        for (AgentDefinition def : KbBuiltInAgentDefs.all()) {
            String prompt = def.getSystemPrompt("model", List.of());
            for (java.util.regex.Matcher m =
                     java.util.regex.Pattern.compile("`([A-Za-z_][A-Za-z0-9_]*)`").matcher(prompt);
                 m.find(); ) {
                String token = m.group(1);
                if (notTools.contains(token)) continue;
                assertThat(allowed)
                    .as("%s 的人格提到了未知标识符 `%s` —— 要么拼错，要么该工具不存在", def.agentType(), token)
                    .contains(token);
            }
        }
    }

    @Test
    @DisplayName("每个 agent 的 whenToUse 非空且不含 WeKnora")
    void when_to_use_is_clean() {
        for (AgentDefinition def : KbBuiltInAgentDefs.all()) {
            assertThat(def.whenToUse())
                .as("%s 的 whenToUse", def.agentType())
                .isNotBlank()
                .doesNotContain("WeKnora");
        }
    }
}
