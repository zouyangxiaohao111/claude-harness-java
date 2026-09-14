package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.team.TeammateIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S1-T20 · 环 B5b/B6/B7/B14] {@code ToolUseContext} 的 <b>local-only 边界</b> + <b>同引用短路</b> +
 * <b>身份透传/清零分布</b>。
 *
 * <p><b>WHY（规则九）</b>：{@code agentContext} 与 {@code teammateIdentity} 都是**进程内身份载体**，
 * 一旦被序列化进 AgentState / EventPublisher / STOMP / LLM payload 就会泄漏到出站通道
 * （同 budgetTracker / subagentName / readFileState 的 local-only 约束）。本测试锁定三件事：
 * <ol>
 *   <li><b>不出站</b>：Jackson 序列化结果中零 {@code agentContext} / {@code teammateIdentity} 键
 *       —— 且必须有非空 JSON + 存在 {@code sessionId} 键作为「非空洞序列化」的鉴别力锚点
 *       （否则「序列化成空串」也会让断言恒绿）；</li>
 *   <li><b>同引用短路</b>：{@code withAgentContext(same)} / {@code withTeammateIdentity(same)}
 *       返回 {@code this}（引用相等）—— 这是 sparse-edge「同一实例只发一次」的前置闩锁：
 *       派生链若每次都 new，同一实例会被复制成 N 个新实例，emitted 标记各自为 false。</li>
 *   <li><b>清零侧</b>：{@code with(SubagentContextOverrides)} 是「新 agent 的 TUC」构造点，
 *       agentContext / teammateIdentity 必须**显式清零**（不继承父）—— 失败方向取「身份缺失」
 *       而非「身份归到父 agent」（对齐 forkedAgent.ts:449 agentType 仅取 override 的取舍）。</li>
 * </ol>
 */
@DisplayName("S1-T20 · TUC local-only 边界 + 同引用短路 + 身份透传/清零")
class ToolUseContextLocalOnlyBoundaryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static TeammateIdentity identity() {
        return new TeammateIdentity("mate@team", "mate", "team", "#ff0000", true, "parent-sess");
    }

    private static AgentContext agentContext() {
        return new AgentContext.SubagentContext(
            "agent-1", "parent-sess", "researcher", Boolean.FALSE, "req-1", "spawn");
    }

    /** 同时盖上两个 local-only 载体的 TUC（用生产唯一盖章入口，不绕过 wither）。 */
    private static ToolUseContext stampedTuc() {
        return new ToolUseContext(UUID.randomUUID(), "sess-local-only",
                PermissionMode.DEFAULT, Map.of())
            .withAgentContext(agentContext())
            .withTeammateIdentity(identity());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 环 B14 · local-only：序列化边界
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B14 · 序列化后零 agentContext / teammateIdentity 键（且序列化非空洞：含 sessionId、长度 > 0）")
    void serializationOmitsLocalOnlyCarriers() throws Exception {
        ToolUseContext tuc = stampedTuc();

        // 前置：两个载体确实有值（否则断言是空洞的 —— 空值当然不出现）
        assertThat(tuc.agentContext()).as("夹具前提：agentContext 已盖章").isNotNull();
        assertThat(tuc.teammateIdentity()).as("夹具前提：teammateIdentity 已盖章").isNotNull();

        String json = JSON.writeValueAsString(tuc);

        // ⭐ 鉴别力锚点：序列化必须真的产出内容（防「空串 ⇒ 不含任何键」的恒绿陷阱）
        assertThat(json).as("序列化结果非空（非空洞断言的前提）").isNotEmpty();
        assertThat(json).as("非忽略字段仍应出站（证明序列化真的跑过完整 property 枚举）")
            .contains("sessionId");
        assertThat(json).as("local-only 载体不得出站：agentContext").doesNotContain("agentContext");
        assertThat(json).as("local-only 载体不得出站：teammateIdentity").doesNotContain("teammateIdentity");
    }

    @Test
    @DisplayName("B14 · 源码注解双重：两个组件 accessor 均带 @JsonIgnore")
    void localOnlyComponentsCarryJsonIgnore() throws Exception {
        for (String name : List.of("agentContext", "teammateIdentity")) {
            RecordComponent rc = null;
            for (RecordComponent c : ToolUseContext.class.getRecordComponents()) {
                if (c.getName().equals(name)) {
                    rc = c;
                }
            }
            assertThat(rc).as("组件存在: " + name).isNotNull();
            assertThat(rc.getAccessor().getAnnotation(JsonIgnore.class))
                .as("@JsonIgnore 必须标在 accessor 上: " + name).isNotNull();
        }
    }

    @Test
    @DisplayName("B14 · Jackson introspection 层也看不到这两个 property（= 出站 writer 的真实判据）")
    void jacksonIntrospectionHasNoLocalOnlyProperties() {
        var beanDesc = JSON.getSerializationConfig().introspect(
            JSON.getTypeFactory().constructType(ToolUseContext.class));
        List<String> names = beanDesc.findProperties().stream()
            .map(p -> p.getName()).toList();
        assertThat(names).as("introspection 必须枚举到属性（防空集恒绿）").isNotEmpty();
        assertThat(names).as("agentContext 不得成为可序列化 property").doesNotContain("agentContext");
        assertThat(names).as("teammateIdentity 不得成为可序列化 property").doesNotContain("teammateIdentity");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 环 B5b · 同引用短路（sparse-edge 前置闩锁）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B5b · withAgentContext(同一实例) 返回 this（引用相等）")
    void withAgentContext_sameInstanceReturnsThis() {
        ToolUseContext tuc = stampedTuc();
        assertThat(tuc.withAgentContext(tuc.agentContext()))
            .as("同实例 → 必须返回 this（否则派生链把同一实例复制成 N 个新实例，emitted 标记各自 false）")
            .isSameAs(tuc);
    }

    @Test
    @DisplayName("B5b · withTeammateIdentity(同一/相等实例) 返回 this（引用相等）")
    void withTeammateIdentity_sameValueReturnsThis() {
        ToolUseContext tuc = stampedTuc();
        assertThat(tuc.withTeammateIdentity(tuc.teammateIdentity()))
            .as("同一实例 → 返回 this").isSameAs(tuc);
        // record 是值语义：等值但不同实例也应短路（Objects.equals）
        assertThat(tuc.withTeammateIdentity(identity()))
            .as("等值实例（record equals）→ 也应返回 this").isSameAs(tuc);
        // 反方向：真正改值时不得返回 this（证明短路不是恒真）
        assertThat(tuc.withTeammateIdentity(
                new TeammateIdentity("other@team", "other", "team", null, false, "s")))
            .as("改值 → 必须返回新实例").isNotSameAs(tuc);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 环 B7 · 派生链透传（多点变异守则：单点变异必绿，故全部点一起断言）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B7 · 全部公开 wither 透传 teammateIdentity（任一点漏透传 ⇒ 该点断言红）")
    void allWithersPassThroughTeammateIdentity() {
        ToolUseContext tuc = stampedTuc();
        TeammateIdentity id = tuc.teammateIdentity();

        assertThat(tuc.withMcpServerConnections(List.of()).teammateIdentity())
            .as("withMcpServerConnections").isSameAs(id);
        assertThat(tuc.withUserModified(true).teammateIdentity())
            .as("withUserModified").isSameAs(id);
        assertThat(tuc.withFileReadingLimits(new FileReadingLimits.Override(10, 20)).teammateIdentity())
            .as("withFileReadingLimits").isSameAs(id);
        assertThat(tuc.withEffectiveModelName("deepseek-v4").teammateIdentity())
            .as("withEffectiveModelName").isSameAs(id);
        assertThat(tuc.withEffectiveProviderType("openai").teammateIdentity())
            .as("withEffectiveProviderType").isSameAs(id);
        assertThat(tuc.withSubagentIdentity("researcher", true).teammateIdentity())
            .as("withSubagentIdentity").isSameAs(id);
        // 经 copyWith 的三个 public 薄壳（copyWith 是同一透传点）
        assertThat(tuc.withMessages(List.of()).teammateIdentity())
            .as("withMessages(copyWith)").isSameAs(id);
        assertThat(tuc.withNonInteractiveSession(true).teammateIdentity())
            .as("withNonInteractiveSession(copyWith)").isSameAs(id);
        assertThat(tuc.withPermissionContext(tuc.permissionContext(), PermissionMode.DEFAULT)
                .teammateIdentity())
            .as("withPermissionContext(copyWith)").isSameAs(id);
    }

    @Test
    @DisplayName("B7 · 派生链同时透传 agentContext（与 teammateIdentity 同分布）")
    void withersPassThroughAgentContext() {
        ToolUseContext tuc = stampedTuc();
        AgentContext ac = tuc.agentContext();

        assertThat(tuc.withUserModified(true).agentContext()).as("withUserModified").isSameAs(ac);
        assertThat(tuc.withSubagentIdentity("researcher", true).agentContext())
            .as("withSubagentIdentity（身份盖章不覆盖归因上下文）").isSameAs(ac);
        assertThat(tuc.withMessages(List.of()).agentContext()).as("copyWith").isSameAs(ac);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 环 B6 · 清零侧（新 agent 不继承父身份/归因）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B6 · with(SubagentContextOverrides) 把 agentContext 与 teammateIdentity 显式清零（不继承父）")
    void withOverridesClearsLocalOnlyCarriers() {
        ToolUseContext parent = stampedTuc();

        ToolUseContext child = parent.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(child).as("with(overrides) 非 null 时应产出新 TUC").isNotSameAs(parent);
        assertThat(child.agentContext())
            .as("新 agent 不继承父的归因上下文（失败方向取『属性缺失』）").isNull();
        assertThat(child.teammateIdentity())
            .as("新 agent 不继承父的 teammate 身份（否则子代理被误判为父 teammate）").isNull();
        // 反向锚点：非身份字段仍继承父（证明 with(overrides) 不是把所有东西清空）
        assertThat(child.mcpServerConnections())
            .as("非身份字段仍继承父（mcpServerConnections）").isSameAs(parent.mcpServerConnections());
    }

    @Test
    @DisplayName("B6 · 兼容构造器（46 参链）产出 TUC 的两个载体均为 null（新上下文默认非 teammate）")
    void compactCompatibleConstructorLeavesCarriersNull() {
        ToolUseContext tuc = new ToolUseContext(UUID.randomUUID(), "sess-ctor",
            PermissionMode.DEFAULT, Map.of());
        assertThat(tuc.agentContext()).as("兼容 ctor → agentContext null").isNull();
        assertThat(tuc.teammateIdentity()).as("兼容 ctor → teammateIdentity null").isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 硬指标：组件数（第 53 组件 = teammateIdentity）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T1 · record 顶层组件 = 53，且第 53 个（index 52）是 teammateIdentity")
    void recordHasFiftyThreeTopLevelComponents() {
        RecordComponent[] comps = ToolUseContext.class.getRecordComponents();
        assertThat(comps).as("顶层组件数 = 52(原) + 1(S1-T1 teammateIdentity)").hasSize(53);
        assertThat(comps[51].getName()).as("index 51 = agentContext（S1-T1 前的末位，回归锚）")
            .isEqualTo("agentContext");
        assertThat(comps[52].getName()).as("index 52 = teammateIdentity（S1-T1 新增第 53 组件）")
            .isEqualTo("teammateIdentity");
    }
}
