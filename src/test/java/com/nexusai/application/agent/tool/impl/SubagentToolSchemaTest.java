package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S2] SubagentTool inputSchema 条件 omit + outputSchema union 测试 · 对齐 CC AgentTool.tsx:99,110-125,141-155。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>:
 * <ol>
 *   <li>run_in_background 条件 omit（CC :122-124）— fork gate on / background disabled 时省略无效字段，LLM 不会发出注定被忽略的调用。</li>
 *   <li>isolation enum 收窄 ['worktree']（CC :99）— external build 恒走 worktree 分支，remote 是 ant-only，Java 无 CCR 实现。</li>
 *   <li>cwd 条件 omit（CC :111-113）— !feature('KAIROS') 外部 build 恒 false → cwd 不暴露。</li>
 *   <li>mode 改 enum（CC :96 permissionModeSchema = ['acceptEdits','bypassPermissions','default','dontAsk','plan']）。</li>
 *   <li>outputSchema union（CC :141-155）— LLM 需知道 Agent 返回结构（sync completed + async async_launched）。</li>
 * </ol>
 */
@DisplayName("Session S2 · SubagentTool inputSchema 条件 omit + outputSchema union")
class SubagentToolSchemaTest {

    @Test
    @DisplayName("inputSchema: backgroundTasksDisabled=true → 无 run_in_background (CC :122-124)")
    void inputSchema_omitsRunInBackground_whenBackgroundDisabled() {
        // GIVEN: backgroundTasksDisabled=true（CC CLAUDE_CODE_DISABLE_BACKGROUND_TASKS）
        SubagentTool tool = new SubagentTool();
        tool.setBackgroundTasksDisabled(true);
        tool.setForkGate(false, false, false); // fork gate off → 只看 background 分支

        // WHEN
        JsonNode schema = tool.inputSchema();

        // THEN: run_in_background 被 omit
        assertThat(schema.path("properties").has("run_in_background")).isFalse();
    }

    @Test
    @DisplayName("inputSchema: fork gate on → 无 run_in_background (CC :122-124)")
    void inputSchema_omitsRunInBackground_whenForkEnabled() {
        // GIVEN: fork gate on（featureOn=true, coordinator=false, nonInteractive=false）
        SubagentTool tool = new SubagentTool();
        tool.setBackgroundTasksDisabled(false);
        tool.setForkGate(true, false, false);

        // WHEN
        JsonNode schema = tool.inputSchema();

        // THEN: run_in_background 被 omit（isForkSubagentEnabled() true）
        assertThat(schema.path("properties").has("run_in_background")).isFalse();
    }

    @Test
    @DisplayName("inputSchema: 默认 fork off + background off → run_in_background 保留")
    void inputSchema_keepsRunInBackground_whenNoGate() {
        // GIVEN: 默认（fork off, background off）— 但默认构造 featureOn=true → fork on。需显式关
        SubagentTool tool = new SubagentTool();
        tool.setBackgroundTasksDisabled(false);
        tool.setForkGate(false, false, false);

        // WHEN
        JsonNode schema = tool.inputSchema();

        // THEN: run_in_background 保留（CC :122-124 两条件都不满足）
        assertThat(schema.path("properties").has("run_in_background")).isTrue();
    }

    @Test
    @DisplayName("inputSchema: isolation enum == ['worktree'] 不含 remote (CC :99 external build)")
    void inputSchema_isolationEnumExcludesRemote_forExternalBuild() {
        // GIVEN
        SubagentTool tool = new SubagentTool();

        // WHEN
        JsonNode isolation = tool.inputSchema().path("properties").path("isolation");

        // THEN: enum == ['worktree']（CC "external" === 'ant' 恒 false → z.enum(['worktree'])）
        List<String> enumValues = new ArrayList<>();
        isolation.path("enum").forEach(n -> enumValues.add(n.asText()));
        assertThat(enumValues).containsExactly("worktree");
    }

    @Test
    @DisplayName("inputSchema: mode 是 enum (CC :96 PERMISSION_MODES 5 值)")
    void inputSchema_modeIsEnum_permissionModes() {
        // GIVEN
        SubagentTool tool = new SubagentTool();

        // WHEN
        JsonNode mode = tool.inputSchema().path("properties").path("mode");

        // THEN: CC types/permissions.ts:16-22 EXTERNAL_PERMISSION_MODES
        List<String> enumValues = new ArrayList<>();
        mode.path("enum").forEach(n -> enumValues.add(n.asText()));
        assertThat(enumValues).containsExactly(
            "acceptEdits", "bypassPermissions", "default", "dontAsk", "plan");
    }

    @Test
    @DisplayName("inputSchema: cwd 条件 omit (CC :111-113 !KAIROS 外部 build 恒 false)")
    void inputSchema_omitsCwd_whenKairosDisabled() {
        // GIVEN: kairosEnabled=false（默认，对齐外部 build feature('KAIROS') 恒 false）
        SubagentTool tool = new SubagentTool();
        tool.setKairosEnabled(false);

        // WHEN
        JsonNode schema = tool.inputSchema();

        // THEN: cwd 被 omit
        assertThat(schema.path("properties").has("cwd")).isFalse();
    }

    @Test
    @DisplayName("inputSchema: kairosEnabled=true → cwd 保留 (CC :111-113 KAIROS 分支)")
    void inputSchema_keepsCwd_whenKairosEnabled() {
        // GIVEN: kairosEnabled=true（对齐 CC feature('KAIROS') 开启）
        SubagentTool tool = new SubagentTool();
        tool.setKairosEnabled(true);

        // WHEN
        JsonNode schema = tool.inputSchema();

        // THEN: cwd 保留
        assertThat(schema.path("properties").has("cwd")).isTrue();
    }

    @Test
    @DisplayName("outputSchema: anyOf 含 sync(completed) + async(async_launched) 两分支 (CC :141-155)")
    void outputSchema_isUnionOfSyncAndAsync() {
        // GIVEN
        SubagentTool tool = new SubagentTool();

        // WHEN
        JsonNode schema = tool.outputSchema();

        // THEN: 非 null；anyOf 2 分支
        assertThat(schema).isNotNull();
        assertThat(schema.path("anyOf").size()).isEqualTo(2);

        JsonNode sync = schema.path("anyOf").get(0);
        JsonNode async = schema.path("anyOf").get(1);
        // sync: status const 'completed' + agentToolResultSchema 字段 (CC agentToolUtils.ts:227-258)
        assertThat(sync.path("properties").path("status").path("const").asText()).isEqualTo("completed");
        assertThat(sync.path("properties").has("agentId")).isTrue();
        assertThat(sync.path("properties").has("content")).isTrue();
        assertThat(sync.path("properties").has("totalToolUseCount")).isTrue();
        assertThat(sync.path("properties").has("totalDurationMs")).isTrue();
        assertThat(sync.path("properties").has("totalTokens")).isTrue();
        assertThat(sync.path("properties").has("usage")).isTrue();
        assertThat(sync.path("properties").has("prompt")).isTrue();
        // async: status const 'async_launched' + async 字段 (CC :146-153)
        assertThat(async.path("properties").path("status").path("const").asText()).isEqualTo("async_launched");
        assertThat(async.path("properties").has("agentId")).isTrue();
        assertThat(async.path("properties").has("description")).isTrue();
        assertThat(async.path("properties").has("prompt")).isTrue();
        assertThat(async.path("properties").has("outputFile")).isTrue();
        assertThat(async.path("properties").has("canReadOutputFile")).isTrue();
    }

    @Test
    @DisplayName("outputSchema: usage 子字段类型对齐 CC agentToolUtils.ts:243-255 (server_tool_use/cache_creation=object, service_tier=enum)")
    void outputSchema_usageSubfields_matchCC() {
        // WHY (CLAUDE.md 规则九): CC agentToolUtils.ts:238-256 明确定义 usage 为
        //   server_tool_use=object{web_search_requests, web_fetch_requests} (nullable, :243-248)
        //   service_tier=enum['standard','priority','batch'] (nullable, :249)
        //   cache_creation=object{ephemeral_1h_input_tokens, ephemeral_5m_input_tokens} (nullable, :250-255)
        // Java 曾以 numberType()/stringType() 简化，LLM 拿不到 usage 结构 → 输出校验失真。
        // 规则三：CC 复杂 Java 就复杂，禁止简单化。若此断言失效说明 schema 偏离 CC，必须修复而非放宽断言。
        // GIVEN
        SubagentTool tool = new SubagentTool();

        // WHEN
        JsonNode sync = tool.outputSchema().path("anyOf").get(0);
        JsonNode usageProps = sync.path("properties").path("usage").path("properties");

        // THEN: server_tool_use 是 object 且含两个 number 属性 (CC :243-248)
        assertThat(usageProps.path("server_tool_use").path("type").asText()).isEqualTo("object");
        assertThat(usageProps.path("server_tool_use").path("properties").path("web_search_requests").path("type").asText())
            .isEqualTo("number");
        assertThat(usageProps.path("server_tool_use").path("properties").path("web_fetch_requests").path("type").asText())
            .isEqualTo("number");

        // THEN: service_tier 是 enum ['standard','priority','batch'] (CC :249)
        JsonNode serviceTierEnum = usageProps.path("service_tier").path("enum");
        assertThat(usageProps.path("service_tier").path("type").asText()).isEqualTo("string");
        assertThat(serviceTierEnum.size()).isEqualTo(3);
        assertThat(serviceTierEnum.get(0).asText()).isEqualTo("standard");
        assertThat(serviceTierEnum.get(1).asText()).isEqualTo("priority");
        assertThat(serviceTierEnum.get(2).asText()).isEqualTo("batch");

        // THEN: cache_creation 是 object 且含两个 number 属性 (CC :250-255)
        assertThat(usageProps.path("cache_creation").path("type").asText()).isEqualTo("object");
        assertThat(usageProps.path("cache_creation").path("properties").path("ephemeral_1h_input_tokens").path("type").asText())
            .isEqualTo("number");
        assertThat(usageProps.path("cache_creation").path("properties").path("ephemeral_5m_input_tokens").path("type").asText())
            .isEqualTo("number");
    }
}
