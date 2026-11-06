package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.session.SessionArtifactService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物 / 来源采集 · PostToolUse hook（2026-11-04 用户裁定「应该按 tool post 的 hook 做到」）。
 *
 * <h2>WHY（规则九：测意图不测行为）</h2>
 * <p>原实现是「工具内埋点」：{@code WebSearchTool} / {@code WebFetchTool} 各自持有
 * {@code SessionArtifactService} 再调 {@code recordSource}；{@code SessionFilesRecorder.record()}
 * 顺带调 {@code syncDeliverableForPath}。埋点式实现的结构性问题是：
 * <b>每加一个工具就要补一次</b>，且子代理 / MCP 工具走别的路径容易漏。
 * ⇒ 改成 PostToolUse hook：<b>一个 hook 覆盖所有工具</b>（子代理与主循环共用
 * {@code StreamingToolExecutor.executePostToolUse}）。
 *
 * <p>本测试钉住三件事：
 * <ul>
 *   <li>Write / Edit 的<b>绝对路径</b>（在工具结果里）⇒ 走产物入库；</li>
 *   <li>WebSearch 的<b>嵌套</b>结果形状 / WebFetch 的<b>顶层</b> url ⇒ 都能收出来源；</li>
 *   <li>非网络工具 ⇒ 不产出来源；非 http(s) ⇒ 不收。</li>
 * </ul>
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>把 hook 里的路径来源从「结果」改回「入参」⇒ 用例 ① 可能仍绿（入参也有路径），
 *       但用例 ①b（入参相对、结果绝对）会红；</li>
 *   <li>把递归收集改成「只取顶层 url」⇒ 用例 ②（WebSearch 嵌套）红；</li>
 *   <li>去掉 http(s) 过滤 ⇒ 用例 ④ 红。</li>
 * </ul>
 */
@DisplayName("[2026-11-04] 产物/来源采集走 PostToolUse hook")
class ArtifactCollectionHooksTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SessionArtifactService artifactService = mock(SessionArtifactService.class);

    /** 记录注册的 hook（按名字）· 模拟 HookRegistry 的注册表。 */
    private final Map<String, PostToolUseHook> registered = new LinkedHashMap<>();

    private final SessionFileAccessHooks.PostToolUseRegistrar registrar = new SessionFileAccessHooks.PostToolUseRegistrar() {
        @Override
        public void registerPostToolUse(String name, PostToolUseHook hook) {
            registered.put(name, hook);
        }
    };

    private void register() {
        new ArtifactCollectionHooks(artifactService).registerArtifactCollectionHooks(registrar);
    }

    /** 触发某个 hook（模拟 StreamingToolExecutor 的 executePostToolUse）。 */
    private void fire(String hookName, String toolName, JsonNode input, ToolResult<?> result, ToolUseContext ctx) {
        PostToolUseHook hook = registered.get(hookName);
        assertThat(hook).as("hook 应已注册: " + hookName).isNotNull();
        hook.onPostToolUse(toolName, input, result, ctx, false);
    }

    /**
     * 真实 ToolUseContext（record 不能 mock）· 用仓库既有的 {@code ToolUseContext.of} 最小构造器。
     *
     * <p>⚠️ {@code ToolUseContext} 的 sessionId **不可为 null**（构造器直接抛）⇒
     * 「无会话」用 {@link com.nexusai.common.SessionKeys#NO_SESSION} 哨兵表示
     * （仓内唯一的「确无会话」显式哨兵）。
     */
    private ToolUseContext ctx(String sessionId) {
        String sid = sessionId == null
                ? com.nexusai.common.SessionKeys.NO_SESSION
                : sessionId;
        return ToolUseContext.of(java.util.UUID.randomUUID(), sid,
                com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
                java.util.List.of(), "", null, java.util.List.of(), null,
                com.nexusai.application.agent.permission.PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("Write 工具：结果里的绝对路径 ⇒ 当场入库为产物")
    void writeToolResultPathBecomesDeliverable() {
        register();
        when(artifactService.syncDeliverableForPath(anyString(), anyString(), any())).thenReturn(true);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "create");
        meta.put("filePath", "/abs/path/deliverables/报告.docx");
        ToolResult<?> result = ToolResult.successWithStructuredOutput("call-1", "ok", meta);
        JsonNode input = JSON.createObjectNode().put("file_path", "相对路径.docx");

        fire("artifactCollection:Write", "Write", input, result, ctx("sess-hook"));

        // WHY 用结果而不是入参：入参可能是相对路径（基准 = 会话 cwd，随会话变化），
        //   结果里的 filePath 是写盘时算出的绝对路径 ⇒ 更稳。
        verify(artifactService).syncDeliverableForPath(eq("sess-hook"), eq("/abs/path/deliverables/报告.docx"), any());
    }

    @Test
    @DisplayName("Write 工具：入参只有相对路径、结果才有绝对路径 ⇒ 仍用结果的绝对路径")
    void absolutePathWinsOverRelativeInput() {
        register();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("filePath", "/abs/交付物/设计稿.html");
        ToolResult<?> result = ToolResult.successWithStructuredOutput("call-1", "ok", meta);
        JsonNode input = JSON.createObjectNode().put("file_path", "设计稿.html");

        fire("artifactCollection:Write", "Write", input, result, ctx("sess-hook"));

        verify(artifactService).syncDeliverableForPath(eq("sess-hook"), eq("/abs/交付物/设计稿.html"), any());
    }

    @Test
    @DisplayName("WebSearch：嵌套形状的结果（results[].content[]）⇒ 收出来源")
    void webSearchNestedResultYieldsSource() {
        register();
        String out = """
            {"query":"q","results":[{"tool_use_id":"t","content":[
              {"title":"标题A","url":"https://example.com/a"},
              {"title":"标题B","url":"https://example.com/b"}]},"注释文本"],
             "durationSeconds":1}
            """;
        ToolResult<?> result = ToolResult.success("call-1", out);

        fire("artifactCollection:sources", "WebSearch", null, result, ctx("sess-hook"));

        verify(artifactService).recordSource(eq("sess-hook"), eq("标题A"), eq("https://example.com/a"), isNull(), any());
        verify(artifactService).recordSource(eq("sess-hook"), eq("标题B"), eq("https://example.com/b"), isNull(), any());
    }

    @Test
    @DisplayName("WebFetch：url 在顶层 ⇒ 收出来源（标题缺省用 url）")
    void webFetchTopLevelUrlYieldsSource() {
        register();
        String out = """
            {"bytes":100,"code":200,"codeText":"OK","result":"内容","durationMs":5,
             "url":"https://example.com/page"}
            """;
        ToolResult<?> result = ToolResult.success("call-1", out);

        fire("artifactCollection:sources", "WebFetch", null, result, ctx("sess-hook"));

        verify(artifactService).recordSource(eq("sess-hook"), eq("https://example.com/page"),
                eq("https://example.com/page"), isNull(), any());
    }

    @Test
    @DisplayName("非 http(s) 的 url（file:// / 相对路径）⇒ 不收（用户点不开）")
    void nonHttpUrlIsRejected() {
        register();
        String out = """
            {"url":"file:///etc/passwd","other":"relative/path.md"}
            """;
        ToolResult<?> result = ToolResult.success("call-1", out);

        fire("artifactCollection:sources", "WebFetch", null, result, ctx("sess-hook"));

        verify(artifactService, never()).recordSource(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("非网络工具 ⇒ 不产出来源（不误收别的工具的结果）")
    void nonNetworkToolProducesNoSource() {
        register();
        String out = """
            {"url":"https://example.com/should-not-be-collected"}
            """;
        ToolResult<?> result = ToolResult.success("call-1", out);

        fire("artifactCollection:sources", "Read", null, result, ctx("sess-hook"));

        verify(artifactService, never()).recordSource(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("采集失败（服务抛异常）⇒ 不影响工具链（旁路增强，绝不冒泡）")
    void collectionFailureDoesNotPropagate() {
        register();
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(artifactService).recordSource(anyString(), anyString(), anyString(), any(), any());
        String out = """
            {"url":"https://example.com/x"}
            """;
        ToolResult<?> result = ToolResult.success("call-1", out);

        // 不抛 = 通过
        fire("artifactCollection:sources", "WebFetch", null, result, ctx("sess-hook"));
    }

    @Test
    @DisplayName("无会话（NO_SESSION 哨兵）⇒ 不采集（不能把来源挂到假会话上 = 伪造归属）")
    void noSessionMeansNoCollection() {
        register();
        String out = """
            {"url":"https://example.com/x"}
            """;
        ToolResult<?> result = ToolResult.success("call-1", out);

        fire("artifactCollection:sources", "WebFetch", null, result, ctx(null));

        verify(artifactService, never()).recordSource(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("mcp__server__webfetch 这类前缀工具名 ⇒ 也算来源工具（子串匹配）")
    void mcpPrefixedToolNameIsSourceTool() {
        register();
        String out = """
            {"url":"https://example.com/mcp"}
            """;
        ToolResult<?> result = ToolResult.success("call-1", out);

        fire("artifactCollection:sources", "mcp__server__webfetch", null, result, ctx("sess-hook"));

        verify(artifactService).recordSource(eq("sess-hook"), anyString(), eq("https://example.com/mcp"), any(), any());
    }

    @Test
    @DisplayName("产物服务为 null ⇒ 采集静默跳过（不 NPE）")
    void nullServiceIsSafe() {
        new ArtifactCollectionHooks(null).registerArtifactCollectionHooks(registrar);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("filePath", "/abs/x.docx");
        ToolResult<?> result = ToolResult.successWithStructuredOutput("call-1", "ok", meta);

        fire("artifactCollection:Write", "Write", null, result, ctx("sess-hook"));
        fire("artifactCollection:sources", "WebFetch", null,
                ToolResult.success("call-2", "{\"url\":\"https://example.com\"}"), ctx("sess-hook"));
    }

    @Test
    @DisplayName("hook 注册名固定（注册表按名字覆盖 ⇒ 幂等）")
    void hookNamesAreStable() {
        register();
        assertThat(registered).containsKeys(
                "artifactCollection:Write",
                "artifactCollection:Edit",
                "artifactCollection:sources");
        // 幂等：重复注册同名 → 覆盖，不叠加
        int before = registered.size();
        register();
        assertThat(registered).hasSize(before);
    }

}
