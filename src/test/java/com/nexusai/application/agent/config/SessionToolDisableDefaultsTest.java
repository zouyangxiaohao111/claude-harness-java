package com.nexusai.application.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.SessionToolCategory;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.kb.agent.tool.FakeKbToolForCategoryTest;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 默认禁用推断的三态契约 · ⛔ 纯 Mockito，不启动 Spring（不碰用户真库）。
 *
 * <p><b>WHY 这组断言必须存在</b>（[tool-categories] 2026-11-12 用户裁定）：
 * <ol>
 *   <li><b>列 null（从未设置）→ 默认禁用电脑操作 + 知识库</b>：新老会话一致生效，
 *       否则用户装了 computer-use 却不知道模型已能操作他的桌面；</li>
 *   <li><b>列 "[]"（显式全开）→ 空集</b>：用户把默认禁用的 50 项全部手动打开后，
 *       必须真的打开 —— 若这里回落默认集，用户的打开操作被静默吞掉
 *       （这正是 setDisabledTools 空集存 "[]" 而非 null 的原因）；</li>
 *   <li><b>列非空 → 用列里的集合</b>：用户显式设定优先。</li>
 * </ol>
 *
 * <p>⚠️ 第 2 条是这组测试的核心价值：它区分了「null」与「"[]"」两种看起来都"空"的状态。
 */
class SessionToolDisableDefaultsTest {

    private SessionMapper mapper;
    private SessionRecord record;

    static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "t " + name;
            }

            @Override
            public JsonNode inputSchema() {
                return null;
            }

            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @BeforeEach
    void setUp() {
        SessionToolCategory.reset();
        SessionToolDisableConfig.reset();

        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(List.of(
            tool("mcp__computer-use__screenshot"),
            new FakeKbToolForCategoryTest("search_knowledge"),
            tool("mcp__nexusai-in-chrome__read_page"), // BR：⛔ 不在默认禁用内
            tool("Bash"),
            tool("Agent")));
        SessionToolCategory.setToolRegistry(registry);

        mapper = mock(SessionMapper.class);
        record = new SessionRecord();
        record.setId("sess-1");
        when(mapper.selectOneById(any())).thenReturn(record);
        SessionToolDisableConfig.setSessionMapper(mapper);
    }

    @AfterEach
    void tearDown() {
        SessionToolDisableConfig.reset();
        SessionToolCategory.reset();
    }

    @Test
    @DisplayName("列 null（从未设置）→ 默认禁用 CU + KB，且不含 BR / SYS")
    void nullColumn_appliesDefaultDisabled() {
        record.setDisabledTools(null);

        Set<String> disabled = SessionToolDisableConfig.getDisabledTools("sess-1");

        assertThat(disabled).contains("mcp__computer-use__screenshot", "search_knowledge");
        assertThat(disabled)
            .as("⛔ 浏览器组默认「开」，未装扩展只是 0 项，不得算进默认禁用")
            .doesNotContain("mcp__nexusai-in-chrome__read_page");
        assertThat(disabled).as("⛔ 系统工具默认开").doesNotContain("Bash", "Agent");
        assertThat(disabled).hasSize(2);
    }

    @Test
    @DisplayName("⭐ 列 \"[]\"（用户显式全开）→ 空集，⛔ 不回落默认禁用")
    void explicitEmptyArray_meansAllEnabled() {
        record.setDisabledTools("[]");

        Set<String> disabled = SessionToolDisableConfig.getDisabledTools("sess-1");

        assertThat(disabled)
            .as("用户把默认禁用项全部打开后，读侧必须返回空集；否则打开操作被静默吞掉")
            .isEmpty();
    }

    @Test
    @DisplayName("列非空 → 用列里的集合（用户显式设定优先）")
    void nonEmptyColumn_wins() {
        record.setDisabledTools("[\"Bash\"]");

        Set<String> disabled = SessionToolDisableConfig.getDisabledTools("sess-1");

        assertThat(disabled).containsExactly("Bash");
        assertThat(disabled).as("显式设定后不应再叠加默认禁用").doesNotContain("search_knowledge");
    }

    @Test
    @DisplayName("注册表未注入（如 Spring 未就绪）→ 默认禁用为空（fail-open，不误禁）")
    void registryMissing_noDefaultDisabled() {
        SessionToolCategory.reset();
        record.setDisabledTools(null);

        assertThat(SessionToolDisableConfig.getDisabledTools("sess-1")).isEmpty();
    }
}
