package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.hook.SessionFileAccessHooks;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * [A#1 tuc-invoking-req] <b>产出端</b>覆盖 · 子代理身份「谁盖的、盖的是不是 agentDefinition 的值」。
 *
 * <h2>WHY 需要本类（补齐单侧覆盖）</h2>
 * {@code SubagentNameExplicitCarrierTest} 自己造 TUC（{@code ToolUseContext.of(...)
 * .withSubagentIdentity(...)}）——只覆盖<b>消费端</b>（{@code subagentProps} 会读字段 + 隐私映射），
 * 对<b>产出端</b>零覆盖：把 {@code SubagentExecutor} Step 20 的盖章改成
 * {@code withSubagentIdentity(null, false)}，那个类 4 条全绿。本类补上产出端的两件事：
 * <ol>
 *   <li><b>真值</b>（{@link #identityOf_builtInAgent_returnsTypeNameAndBuiltInTrue} /
 *       {@link #identityOf_customAgent_returnsTypeNameAndBuiltInFalse} /
 *       {@link #identity_roundTrip_yieldsExpectedSubagentNameAttribute}）：用<b>真实
 *       {@link AgentDefinition}</b> 驱动 {@code SubagentIdentity.of(def)}，再经
 *       {@code withSubagentIdentity} 盖到 TUC，由<b>真实消费方</b> {@code SessionFileAccessHooks}
 *       读回事件属性 —— 断言的是「值对不对」。</li>
 *   <li><b>接线</b>（{@link #executeStreaming_stampsIdentityFromSingleDerivationPoint}）：源级守卫，
 *       只守「唯一产出点仍以 {@code SubagentIdentity.of(defForLoop)} 盖章、没被删/改成别的来源」。</li>
 * </ol>
 *
 * <p><b>为什么接线只能源级守</b>：{@code SubagentExecutor.executeStreaming} 到 Step 20 之间是
 * ~19 步重依赖（会话目录 / transcript / MCP / 任务登记 / 摘要服务…），本仓既有测试多处明文记载
 * 「runSubagentQueryLoop 依赖 LLM 循环等重依赖无法在单测跑全流程」
 * （{@code SubagentExecutorInvokedSkillCleanupTest:22}），故 Step 20 在单测里<b>到不了</b>。
 * 因此本类把「值对不对」压到 static seam（真值断言），把「有没有盖章」压到源级守卫，并在守卫的
 * javadoc 里写明它<b>不</b>守值正确性。
 */
class SubagentIdentityProducerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_MEMORY_PATH =
        Path.of(System.getProperty("user.home"), ".claude", "session-memory", "abc.md").toString();

    /** 真实内置 agent（CC isBuiltInAgent = source === 'built-in' 的 Java 等价物）。 */
    private static AgentDefinition builtIn(String agentType) {
        return AgentDefinition.BuiltInAgentDefinition
            .builder(agentType, "when to use", (modelId, dirs) -> "prompt")
            .build();
    }

    /** 真实自定义 agent（.claude/agents/*.md 解析产物 · source='userSettings'）。 */
    private static AgentDefinition custom(String agentType) {
        return AgentDefinition.CustomAgentDefinition
            .create(agentType, "when to use", List.of(), "prompt", "userSettings");
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 派生真值（CC AgentTool.tsx:721-723）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SubagentIdentity.of(内置 agent) → (agentType, true)"
        + "（CC AgentTool.tsx:721-723 + loadAgentsDir.ts:168-172）")
    void identityOf_builtInAgent_returnsTypeNameAndBuiltInTrue() {
        SubagentExecutor.SubagentIdentity id = SubagentExecutor.SubagentIdentity.of(builtIn("Explore"));

        assertThat(id.subagentName())
            .as("subagentName 必须 = agentDefinition.agentType()（CC selectedAgent.agentType）")
            .isEqualTo("Explore");
        assertThat(id.isBuiltIn())
            .as("内置 agent（BuiltInAgentDefinition，source()=='built-in'）→ isBuiltIn=true")
            .isTrue();
    }

    @Test
    @DisplayName("SubagentIdentity.of(自定义 agent) → (agentType, false)"
        + "（CC isBuiltInAgent(source!='built-in') = false）")
    void identityOf_customAgent_returnsTypeNameAndBuiltInFalse() {
        SubagentExecutor.SubagentIdentity id =
            SubagentExecutor.SubagentIdentity.of(custom("my-private-agent-name"));

        assertThat(id.subagentName()).isEqualTo("my-private-agent-name");
        assertThat(id.isBuiltIn())
            .as("CustomAgentDefinition（source()='userSettings'）→ isBuiltIn=false → 下游映射 'user-defined'")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 派生 → 盖章 → 真实消费方读回（值链路端到端）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("产出链路真值: SubagentIdentity.of(def) → withSubagentIdentity(TUC) → 真实 "
        + "SessionFileAccessHooks 事件 subagent_name 与定义一致（内置=类型名 / 自定义='user-defined'）")
    void identity_roundTrip_yieldsExpectedSubagentNameAttribute() {
        // WHY: 本断言的不是「TUC 字段被赋值」（那与 wither 同义反复），而是
        //   「用真实 agentDefinition 派生出的身份，经生产盖章 API 后，被**真实消费方**读成
        //    正确的 subagent_name（含 CC :148-150 隐私映射）」—— 即产出端到消费端的值一致性。
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(recordingTelemetry(new CopyOnWriteArrayList<>()));

        // 内置 agent：subagent_name = 类型名
        ToolUseContext builtInTuc = stamp(builtIn("Explore"));
        assertThat(subagentNameOf(hooks, builtInTuc))
            .as("内置 agent → 事件带类型名（CC agentContext.ts:148）")
            .isEqualTo("Explore");

        // 自定义 agent：隐私映射为字面量
        ToolUseContext customTuc = stamp(custom("my-private-agent-name"));
        assertThat(subagentNameOf(hooks, customTuc))
            .as("自定义 agent → 恒 'user-defined'（CC agentContext.ts:150：自定义名是用户数据，不进 analytics）")
            .isEqualTo("user-defined");
    }

    /** 生产盖章 API（= Step 20 对 TUC 做的那一下）· 输入是真实派生结果，非手写字面量。 */
    private static ToolUseContext stamp(AgentDefinition def) {
        SubagentExecutor.SubagentIdentity id = SubagentExecutor.SubagentIdentity.of(def);
        return ToolUseContext.of(null, "sess-identity-test")
            .withSubagentIdentity(id.subagentName(), id.isBuiltIn());
    }

    /** 经真实消费方读回 subagent_name（无该属性 → null）。 */
    private static String subagentNameOf(SessionFileAccessHooks hooks, ToolUseContext ctx) {
        List<Map<String, Object>> attrs = new CopyOnWriteArrayList<>();
        SessionFileAccessHooks fresh =
            new SessionFileAccessHooks(recordingTelemetry(attrs));
        ObjectNode input = MAPPER.createObjectNode();
        input.put("file_path", SESSION_MEMORY_PATH);
        fresh.handleSessionFileAccess("Read", input, ctx);
        return attrs.isEmpty() ? null : (String) attrs.get(0).get("subagent_name");
    }

    private static Telemetry recordingTelemetry(List<Map<String, Object>> sink) {
        Telemetry telemetry = mock(Telemetry.class);
        doAnswer(inv -> {
            sink.add(inv.getArgument(1));
            return null;
        }).when(telemetry).recordEvent(eq("tengu_session_memory_accessed"), any());
        return telemetry;
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 接线源级守卫（只守「没被删掉/换来源」，不守「值对不对」）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("接线守卫: SubagentExecutor 以 SubagentIdentity.of(defForLoop) 单点盖章"
        + "（⚠️ 本测试只守接线语句存在且来源未被替换，不守 agentDefinition 的值正确性 —— 值由 #1/#2 守）")
    void executeStreaming_stampsIdentityFromSingleDerivationPoint() throws Exception {
        // ══════════════════════════════════════════════════════════════════
        // ⚠️ 本测试是**源级守卫**，其守护范围被明确限定为：
        //    ① 全文件只有 1 处 withSubagentIdentity( 调用（产出点唯一，不新增旁路）；
        //    ② 该语句的实参来自 final SubagentIdentity.of(defForLoop) 的绑定变量
        //       （即 Step 20 仍以「同一份 agentDefinition」为唯一来源）。
        //    它**不**守「agentDefinition 解析出来的值对不对」，也**不**守
        //    「buildSubagentAgentContext 与 TUC 盖章是否同源」的值一致性 —— 后者由
        //    SubagentIdentity 单派生点结构性保证，值语义由本类 #1/#2 断言。
        //    WHY 需要文字级：executeStreaming 到 Step 20 有 ~19 步重依赖，单测到不了
        //    （见类 javadoc）；真实线程消费端由 SubagentNameExplicitCarrierTest 覆盖。
        // ══════════════════════════════════════════════════════════════════
        Path src = Path.of("src/main/java/com/nexusai/application/agent/tool/impl/SubagentExecutor.java");
        String text = stripComments(Files.readString(src, StandardCharsets.UTF_8));

        int callSites = text.split("\\.withSubagentIdentity\\(", -1).length - 1;
        assertThat(callSites)
            .as("产出点必须唯一（新增第二个盖章点 = 绕过单派生点，须显式评审）")
            .isEqualTo(1);

        // 取出盖章语句所在的整条赋值语句（到第一个 ';' 为止）
        int call = text.indexOf(".withSubagentIdentity(");
        int stmtStart = text.lastIndexOf("final ToolUseContext ctxForLoop", call);
        assertThat(stmtStart)
            .as("盖章必须发生在 executeStreaming 的 ctxForLoop（子代理 loop 承载 TUC）上")
            .isGreaterThan(0);
        String stmt = text.substring(stmtStart, text.indexOf(';', call));

        assertThat(stmt)
            .as("盖章实参必须来自 identityForLoop（= SubagentIdentity.of(defForLoop) 单派生点），"
                + "不得硬编码或在仓内另起来源")
            .contains("identityForLoop.subagentName()")
            .contains("identityForLoop.isBuiltIn()");

        assertThat(text)
            .as("identityForLoop 必须绑定到 SubagentIdentity.of(defForLoop)（同一份 agentDefinition）")
            .contains("SubagentIdentity.of(defForLoop)");

        assertThat(stmt)
            .as("回归护栏：不得退回硬编码（null/false 或字面量常量）")
            .doesNotContain("withSubagentIdentity(null");
    }

    /**
     * 去掉注释后再做源级断言 · <b>本测试自查实测到的假绿教训</b>：
     * 初版守卫直接断言原文含 {@code "SubagentIdentity.of(defForLoop)"} —— 而 Step 20 上方的
     * <b>注释</b>里恰好写了这串字，于是把代码改成 {@code SubagentIdentity.of(null)}（换来源）
     * 守卫仍绿（变异实测复现）。源级守卫必须只看代码，否则注释会替坏代码背书。
     *
     * <p>简化实现（本文件守卫区内无含 {@code //} 的字符串字面量）：先删块注释再删行注释。
     */
    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "");
    }
}
