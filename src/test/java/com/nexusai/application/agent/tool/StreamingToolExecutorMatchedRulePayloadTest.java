package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.ClassifierApprovals;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-WF3-TC-01] 工具结果 payload 附带 matchedRule（前端显示自动批准规则）·
 * 对齐 CC {@code utils/classifierApprovals.ts} + {@code UserToolSuccessMessage.tsx:47-50}.
 *
 * <p><b>WHY（规则九 · 意图验证）</b>: CC 在工具被 classifier（bash）自动批准时
 * {@code setClassifierApproval(toolUseID, matchedRule)} 写入放行规则（classifierApprovals.ts:19-30，
 * Java 侧等价 {@code ToolPermissionGate:1118} 已写入），工具结果渲染时
 * {@code getClassifierApproval(toolUseID)} 读取并显示 "Auto-approved · matched "rule""
 * （UserToolSuccessMessage.tsx:88-94），随后 {@code deleteClassifierApproval} 一次性清理
 * （UserToolSuccessMessage.tsx:49-51，防 Map 线性增长）。前端 nexusai 当前不消费审批存储，
 * 决策（用户 2026-08-16）为混合方案：服务端保留清理 + 工具结果附带 matchedRule + 前端显示。
 *
 * <p>本测试验证两条链（RED 验证：删除本实现任一环节即变红）：
 * <ol>
 *   <li><b>服务端捕获链</b>：StreamingToolExecutor 工具成功出口 {@code releaseClassifierApproval}
 *       读取 {@code getClassifierApproval} → 按 toolUseId 暂存 {@code AgentState.classifierMatchedRules}
 *       → {@code deleteClassifierApproval} 清理 store（DC-WF3-TC-01 服务端保留清理）。</li>
 *   <li><b>渲染附带链</b>：AgentLoopContext 构建工具结果 payload 时 {@code takeClassifierMatchedRule}
 *       取走一次性注入 {@code ChatMessageDto.matchedRule}（前端渲染展示）。</li>
 * </ol>
 */
@DisplayName("[IMP-WF3-TC-01] 工具结果 payload 附带 matchedRule")
class StreamingToolExecutorMatchedRulePayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void resetStaticState() {
        // 静态 store 前后清理（与 ClassifierApprovalsFeatureGateTest 同先例），防跨用例污染
        ClassifierApprovals.clearClassifierApprovals();
    }

    @Test
    @DisplayName("classifier 自动批准工具 → 工具完成后 AgentState 暂存 matchedRule 且 store 已清理（CC classifierApprovals.ts:19-39/80-82）")
    void classifierApproval_capturedInState_andStoreCleaned() throws Exception {
        // WHY: CC UserToolSuccessMessage 渲染时 getClassifierApproval 读取放行规则；
        //     Java 无 UI 渲染层，服务端在工具成功出口（releaseClassifierApproval）读取
        //     matchedRule → 暂存 AgentState（供渲染点附带 payload）→ delete 清理 store。
        // RED: 若 releaseClassifierApproval 不暂存 / 不清理，本断言变红。
        AgentState state = new AgentState("system prompt");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub_done");
            }
        });
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, context(), null, null, null);
        exec.setAgentState(state);

        String toolUseId = "toolu_matched_1";
        // bash classifier 自动批准（对齐 ToolPermissionGate:1118 写入路径）
        ClassifierApprovals.setClassifierApproval(toolUseId, "Bash(git status:*)", v -> true);
        assertThat(ClassifierApprovals.getClassifierApproval(toolUseId, v -> true))
            .as("前置：approval 已写入 store")
            .isEqualTo("Bash(git status:*)");

        exec.add(call(toolUseId, "stub"));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        // [IMP-C2] ToolResult 4 字段契约删 isError → 以 isToolErrorData(data) 推导（master 同款模式）
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data())).isFalse();
        // 捕获链：matchedRule 已按 toolUseId 暂存 AgentState（渲染点取走注入 payload）
        assertThat(state.takeClassifierMatchedRule(toolUseId))
            .as("工具成功出口必须把 classifier 放行规则暂存 AgentState（CC UserToolSuccessMessage 渲染读取）")
            .isEqualTo("Bash(git status:*)");
        // 服务端保留清理：deleteClassifierApproval 已清理 store（CC UserToolSuccessMessage.tsx:49-51）
        assertThat(ClassifierApprovals.getClassifierApproval(toolUseId, v -> true))
            .as("服务端保留清理：approval 一次性读取后必须 delete（防 Map 线性增长）")
            .isNull();
    }

    @Test
    @DisplayName("无 classifier 放行（无 matchedRule）→ AgentState 无暂存、store 无写入（CC classifierApprovals.ts:32-39 get 返回 undefined）")
    void noClassifierApproval_stateEmpty() throws Exception {
        // WHY: 未放行工具不写入 matchedRule → 渲染点 take 返回 null → ChatMessageDto.matchedRule 保持 null，
        //     前端不显示"已自动批准"。RED: 若无条件伪造 matchedRule，本断言变红。
        AgentState state = new AgentState("system prompt");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub_done");
            }
        });
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, context(), null, null, null);
        exec.setAgentState(state);

        exec.add(call("toolu_no_rule", "stub"));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(state.takeClassifierMatchedRule("toolu_no_rule"))
            .as("无 classifier 放行 → 无 matchedRule 暂存（CC get 返回 undefined）")
            .isNull();
    }

    @Test
    @DisplayName("ChatMessageDto.withMatchedRule 附带 matchedRule 字段（payload 载体，前端渲染展示）")
    void withMatchedRule_carriesRule() {
        // WHY: 渲染点经 withMatchedRule 把 matchedRule 注入 ChatMessageDto 载荷，
        //     前端读取 matchedRule 字段显示"✓ 已自动批准（规则X）"（FNT-TC-01）。
        // RED: 若 ChatMessageDto 无 matchedRule 字段 / withMatchedRule 不携带，本断言变红。
        ChatMessageDto base = new ChatMessageDto(
            UUID.randomUUID().toString(), null,
            com.nexusai.model.session.dto.Role.tool, "tool", "out",
            null, List.of(), null, null, null, null, null,
            "toolu_render", null, null, List.of(), List.of(), null,
            false, false, null, null,
            false, null, null, null,
            null, null, null, null, null, false, false, null, null, null);

        ChatMessageDto attached = base.withMatchedRule("Bash(git status:*)");

        assertThat(base.matchedRule()).as("base 未附带 → null").isNull();
        assertThat(attached.matchedRule())
            .as("withMatchedRule 必须携带 matchedRule 到 payload 字段")
            .isEqualTo("Bash(git status:*)");
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers · 镜像 StreamingToolExecutorDispatchTest/MessageInjectionTest
    // ════════════════════════════════════════════════════════════════════

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    private static ToolUseContext context() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", new AbortController(), List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> Collections.unmodifiableSet(java.util.Set.of()));
    }
}
