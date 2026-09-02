package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SUB-05 D5 返工] SubagentTool async_launched data 发射聚焦测试
 * 断言 executeAsync 发射的 async_launched data 形状 == CC asyncOutputSchema
 * (AgentTool.tsx:146-153) 六字段 {status, agentId, description, prompt, outputFile, canReadOutputFile}。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>: D5 是 HIGH 前端契约缺口 — 前端按 CC 契约从 async_launched data
 * 取 agentId/description/outputFile 渲染异步 agent 卡片。若 data 形状偏离 CC（如回归为旧 Java 自定义
 * 形状 {@code {summary(JSON串), status, taskId, agentType}}），前端契约解析即断。此测试锁死形状契约，
 * 业务逻辑变更（字段增删/改名）必须同步改断言，否则红。
 */
@DisplayName("IMP-SUB-05 D5 · async_launched data 形状 == CC asyncOutputSchema 六字段")
class SubagentToolAsyncLaunchedDataTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("buildAsyncLaunchedData: 六字段形状 == CC asyncOutputSchema (AgentTool.tsx:146-153)")
    void buildAsyncLaunchedData_producesCcSixFieldShape() {
        // GIVEN: executeAsync 发射点输入（taskId===agentId 合一, LocalAgentTask.tsx:197-262）
        String taskId = "agent-abc-123";
        String description = "帮我分析代码";
        String prompt = "读取 src/main 并总结";
        String outputFile = "/tmp/agent-agent-abc-123.out";
        boolean canReadOutputFile = true;

        // WHEN
        ObjectNode data = SubagentTool.buildAsyncLaunchedData(
            taskId, description, prompt, outputFile, canReadOutputFile);

        // THEN: 恰好六字段（无旧 summary/taskId/agentType 残留）
        assertThat(data.size()).isEqualTo(6);
        assertThat(data.has("status")).isTrue();
        assertThat(data.has("agentId")).isTrue();
        assertThat(data.has("description")).isTrue();
        assertThat(data.has("prompt")).isTrue();
        assertThat(data.has("outputFile")).isTrue();
        assertThat(data.has("canReadOutputFile")).isTrue();

        // THEN: 字段值对齐 CC
        assertThat(data.path("status").asText())
            .as("CC :147 status='async_launched'").isEqualTo("async_launched");
        assertThat(data.path("agentId").asText())
            .as("CC :148/:758 agentId=agentBackgroundTask.agentId (taskId 合一)").isEqualTo(taskId);
        assertThat(data.path("description").asText())
            .as("CC :149/:759 description=input.description").isEqualTo(description);
        assertThat(data.path("prompt").asText())
            .as("CC :150/:760 prompt=input.prompt").isEqualTo(prompt);
        assertThat(data.path("outputFile").asText())
            .as("CC :151/:761 outputFile=getTaskOutputPath(agentId)").isEqualTo(outputFile);
        assertThat(data.path("canReadOutputFile").asBoolean())
            .as("CC :152/:753 canReadOutputFile 透传").isTrue();
    }

    @Test
    @DisplayName("buildAsyncLaunchedData: canReadOutputFile=false 正确透传")
    void buildAsyncLaunchedData_passesCanReadOutputFileFalse() {
        ObjectNode data = SubagentTool.buildAsyncLaunchedData(
            "agent-x", "d", "p", "/tmp/agent-x.out", false);

        assertThat(data.path("canReadOutputFile").asBoolean())
            .as("canReadOutputFile=false 必须透传（调用方无 Read/Bash 工具）").isFalse();
    }

    @Test
    @DisplayName("buildAsyncLaunchedData: 不含旧 Java 自定义字段 (summary/taskId/agentType)")
    void buildAsyncLaunchedData_hasNoLegacyCustomFields() {
        ObjectNode data = SubagentTool.buildAsyncLaunchedData(
            "agent-x", "d", "p", "/tmp/agent-x.out", true);

        // WHY: 旧 Java 形状 {summary(JSON串), status, taskId, agentType} 是 D5 对齐 CC 前的前端断契约根因；
        //   若这些字段回归，前端按 CC 契约取 agentId/description/outputFile 会读到 null。
        assertThat(data.has("summary")).as("旧字段 summary 必须消失").isFalse();
        assertThat(data.has("taskId")).as("旧字段 taskId 必须消失（CC 用 agentId）").isFalse();
        assertThat(data.has("agentType")).as("旧字段 agentType 必须消失").isFalse();
    }

    @Test
    @DisplayName("hasReadOrBashTool: name 命中 Read/Bash → true (CC toolMatchesName Tool.ts:346-352)")
    void hasReadOrBashTool_matchesByName() {
        Tool readTool = toolStub("Read", List.of());
        Tool bashTool = toolStub("Bash", List.of());
        Tool otherTool = toolStub("Glob", List.of());

        assertThat(SubagentTool.hasReadOrBashTool(List.of(readTool)))
            .as("name='Read' 命中 FILE_READ_TOOL_NAME").isTrue();
        assertThat(SubagentTool.hasReadOrBashTool(List.of(bashTool)))
            .as("name='Bash' 命中 BASH_TOOL_NAME").isTrue();
        assertThat(SubagentTool.hasReadOrBashTool(List.of(otherTool)))
            .as("无关工具 → false").isFalse();
        assertThat(SubagentTool.hasReadOrBashTool(List.of(otherTool, readTool)))
            .as("含 Read 即可 → true").isTrue();
    }

    @Test
    @DisplayName("hasReadOrBashTool: alias 命中 Read/Bash → true (CC aliases?.includes Tool.ts:349)")
    void hasReadOrBashTool_matchesByAlias() {
        // WHY: CC toolMatchesName 同时查 name 和 aliases (Tool.ts:346-352)；Read/Bash 的别名
        //   必须同样命中，否则别名工具（如重命名后的 read）会被误判 canReadOutputFile=false。
        Tool readAliasTool = toolStub("ReadFile", List.of("Read"));
        Tool bashAliasTool = toolStub("Shell", List.of("Bash"));

        assertThat(SubagentTool.hasReadOrBashTool(List.of(readAliasTool)))
            .as("alias='Read' 命中").isTrue();
        assertThat(SubagentTool.hasReadOrBashTool(List.of(bashAliasTool)))
            .as("alias='Bash' 命中").isTrue();
    }

    @Test
    @DisplayName("hasReadOrBashTool: null/空列表 → false (防御)")
    void hasReadOrBashTool_nullOrEmptyReturnsFalse() {
        assertThat(SubagentTool.hasReadOrBashTool(null))
            .as("null 工具列表防御 → false").isFalse();
        assertThat(SubagentTool.hasReadOrBashTool(List.of()))
            .as("空列表 → false").isFalse();
    }

    /** 最小 Tool 桩（仅覆盖 hasReadOrBashTool 所需的 name/aliases；其余走接口默认）。 */
    private static Tool toolStub(String name, List<String> aliases) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub");
            }
            @Override public List<String> aliases() { return aliases; }
        };
    }
}
