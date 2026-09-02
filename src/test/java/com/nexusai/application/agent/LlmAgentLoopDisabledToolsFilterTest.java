package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [gap29] 主循环工具 schema 会话级禁用过滤 · 复刻 CC blanket deny 的 schema 阶段剔除
 * （tools.ts:262-269「A tool is filtered out if there's a deny rule matching its name ...
 * before the model sees them」）。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>: 前端「点 × 临时禁用 → 该工具从模型 schema 移除，会话内生效」
 * （待前端对接 §29 #2）——被禁工具不得出现在 LLM tools schema（模型看不到即不会请求），
 * 与 deny 过滤同层（schema 阶段，非运行时拦截）。
 * 变异点：
 * <ul>
 *   <li>llmToolsArray 不接会话禁用集合 → Bash 重新出现在 schema → 红</li>
 *   <li>禁用集合空/未设置 → 误剔除任何工具 → 红</li>
 *   <li>禁用与 bare 叠加顺序错（bare 后回灌被禁工具）→ Bash 重现 → 红</li>
 * </ul>
 *
 * <p>桥接：mock SessionMapper → SessionRecord.disabledTools（JSON 数组）→
 * {@link SessionToolDisableConfig#setSessionMapper}（static 桥接，镜像 LlmAgentLoopCoordinatorFilterTest
 * 的 bridgeSessionBareMode 形态）。
 */
class LlmAgentLoopDisabledToolsFilterTest {

    /** 仅按名 mock 的 Tool（isEnabled=true 保证 schema 阶段可见）。 */
    private static Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.isEnabled()).thenReturn(true);
        return t;
    }

    /** 10 参构造器 per-turn TUC：availableTools + 空权限上下文（对齐 LlmAgentLoopToolsArrayDenyTest 形态）。 */
    private static ToolUseContext tuc(List<Tool> availableTools) {
        return new ToolUseContext(
                UUID.randomUUID(),
                "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                PermissionMode.DEFAULT,
                Map.of(),
                availableTools,
                null,
                AbortController.NOOP,
                List.of(),
                null,
                PermissionMode.DEFAULT);
    }

    private static List<Tool> pool() {
        return List.of(tool("Bash"), tool("Read"), tool("Edit"), tool("WebSearch"), tool("Agent"));
    }

    private static List<String> schemaNames(ArrayNode schema) {
        return java.util.stream.StreamSupport.stream(schema.spliterator(), false)
                .map(n -> n.path("function").path("name").asText())
                .toList();
    }

    /** 桥接会话 mapper：disabled_tools JSON 串 → SessionToolDisableConfig static 字段。 */
    private static void bridgeDisabledTools(String disabledToolsJson) {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionRecord r = new SessionRecord();
        r.setDisabledTools(disabledToolsJson);
        when(mapper.selectOneById(anyString())).thenReturn(r);
        SessionToolDisableConfig.setSessionMapper(mapper);
    }

    @BeforeEach
    void resetStatics() {
        // 隔离：coordinator 关 + bare 全局关（防跨测试污染）
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));
        MemoryBareModeConfig.reset();
        SessionToolDisableConfig.reset();
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.setCoordinatorMode(null);
        MemoryBareModeConfig.reset();
        SessionToolDisableConfig.reset();
    }

    @Test
    @DisplayName("会话禁用 [Bash] → llmToolsArray schema 不含 Bash，其余保留")
    void llmToolsArray_disabledBash_schemaOmitsBash() {
        // WHY: 禁用集合命中 → 该工具从 LLM schema 剔除（模型看不到即不会请求，对齐 CC blanket deny
        //   tools.ts:262-269 schema 阶段剔除）。变异点：llmToolsArray 不接禁用集合 → Bash 重现 → 红。
        bridgeDisabledTools("[\"Bash\"]");

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(pool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("会话禁用 [Bash] → schema 不含 Bash（gap29 · V34 disabled_tools）")
                .contains("Read", "Edit", "WebSearch", "Agent")
                .doesNotContain("Bash");
    }

    @Test
    @DisplayName("禁用集合空（disabled_tools 未设置 null）→ 全部保留，不误剔除")
    void llmToolsArray_disabledUnset_allRetained() {
        // WHY: null/未禁用 → 空集 → 不剔除任何工具（对齐 V33 bare_mode 可空语义）。
        //   变异点：null 误判空集以外（如抛异常）→ schema 丢失 → 红。
        bridgeDisabledTools(null);

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(pool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("disabled_tools=null → schema 保留全量（含 Bash）")
                .contains("Bash", "Read", "Edit", "WebSearch", "Agent");
    }

    @Test
    @DisplayName("禁用集合空数组 [] → 全部保留")
    void llmToolsArray_disabledEmptyArray_allRetained() {
        bridgeDisabledTools("[]");

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(pool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("disabled_tools=[] → schema 保留全量（含 Bash）")
                .contains("Bash", "Read", "Edit", "WebSearch", "Agent");
    }

    @Test
    @DisplayName("bare + 会话禁用叠加：bare 裁剪 [Bash,Read,Edit] → 禁用 Bash → schema=[Read,Edit]")
    void llmToolsArray_barePlusDisabled_combinedTrim() {
        // WHY: bare 是最外层（CC simpleTools 选择先于 filterToolsByDenyRules，tools.ts:297），
        //   会话禁用插在 deny 后 —— bare 先裁为 [Bash,Read,Edit]，禁用再剔除 Bash。
        //   变异点：禁用过滤置于 bare 前 → Bash 先被禁、bare 裁剪不含它 → 结果 [Read,Edit] 恰好相同，
        //   但若禁用 Read（bare 内）→ 顺序错 → 误判。此处直接断言 bare+禁用叠加语义。
        bridgeDisabledTools("[\"Bash\"]");
        new MemoryBareModeConfig(true);   // 全局 bare=true

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(pool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .as("bare 裁 [Bash,Read,Edit] + 禁用 Bash → [Read, Edit]（gap29 叠加）")
                .containsExactlyInAnyOrder("Read", "Edit")
                .doesNotContain("Bash", "WebSearch", "Agent");
    }

    @Test
    @DisplayName("禁用集合含 READ → 仅 Read 被剔除（命中项精确剔除，非前缀/模糊）")
    void llmToolsArray_disabledRead_preciseMatchOnly() {
        // WHY: disabled 过滤按工具名精确命中（V34 列存储精确名）。变异点：前缀/模糊匹配 →
        //   误删 ReadFile 别名或误保留 → 红。
        bridgeDisabledTools("[\"Read\"]");

        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc(pool()), QuerySource.USER);

        assertThat(schemaNames(schema))
                .contains("Bash", "Edit", "WebSearch", "Agent")
                .doesNotContain("Read");
    }
}
