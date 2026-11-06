package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.session.SessionArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 产物 / 来源采集 · PostToolUse hook（对齐 CC「工具执行后采集文件改动」的通用模式）。
 *
 * <p><b>WHY 改成 hook 而不是逐工具埋点</b>（2026-11-04 用户裁定）：
 * 原实现是「在工具里埋点」——
 * <ul>
 *   <li>{@code WebSearchTool} / {@code WebFetchTool} 各自 {@code @Autowired SessionArtifactService}
 *       再调 {@code recordSource}；</li>
 *   <li>{@code SessionFilesRecorder.record()}（会话改动文件漏斗）顺带调
 *       {@code syncDeliverableForPath}。</li>
 * </ul>
 * 埋点式实现的问题是<b>每加一个工具就要补一次</b>，且子代理 / MCP 工具走的是别的路径、容易漏。
 * 改成 PostToolUse hook 后：<b>一个 hook 覆盖所有工具</b>（含子代理 —— 子代理与主循环共用
 * {@code StreamingToolExecutor.executePostToolUse}），路径与结果都从 hook 的标准入参拿。
 *
 * <p><b>本 hook 覆盖什么</b>：
 * <ul>
 *   <li><b>产物（deliverable）</b>：Write / Edit 工具写盘后，文件路径落在会话交付物目录
 *       （{@code {configHome}/artifacts/<sid>/deliverables/}）之下 ⇒ 当场入库 + 推送。</li>
 *   <li><b>来源（source）</b>：WebSearch / WebFetch 成功后，从<b>工具结果</b>里收集 url。</li>
 * </ul>
 *
 * <p><b>⛔ 本 hook 换不掉交付物目录扫描</b>：Bash / cp / 脚本写出来的文件，工具 hook 看不见
 * （Bash 的入参只有 {@code command} 字符串，没有路径）⇒ 那条路仍由
 * {@code SessionArtifactService.startTurnWatch}（轮内每 2s 扫目录）承担。
 * ⇒ 本 hook 与目录扫描是<b>互补</b>关系，不是替代关系。
 *
 * <p><b>策略闸门</b>（用户 2026-11-04 拍板「跟着停」）：本 hook 注册为 programmatic PostToolUse hook，
 * 因此 {@code policySettings.disableAllHooks=true} 时它与其它 hook 一起被跳过
 * （见 {@code HookRegistry#executePostToolUse} 入口短路）⇒ 语义与 CC 一致，不另开第二条执行路径。
 *
 * <p><b>失败语义</b>：采集失败只 WARN，绝不影响工具返回值（采集是旁路增强）。
 *
 * @see SessionArtifactService
 * @see SessionFileAccessHooks（同款 PostToolUse 注册器模式）
 */
public final class ArtifactCollectionHooks {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCollectionHooks.class);

    /** 会产出<b>产物</b>的工具（写盘类）· 路径取自工具结果。 */
    private static final String[] DELIVERABLE_TOOLS = {
            ToolNameConstants.FILE_WRITE_TOOL_NAME,
            ToolNameConstants.FILE_EDIT_TOOL_NAME,
    };

    /** 会产出<b>来源</b>的工具 · 名称按子串匹配（{@code mcp__server__webfetch} 也命中）。 */
    private static final String[] SOURCE_TOOL_KEYWORDS = {"websearch", "web_search", "webfetch", "web_fetch"};

    /** 产物服务（可注入）· null → 跳过采集（单测直构 / 非 Spring 场景）。 */
    private final SessionArtifactService artifactService;

    /**
     * 会话在飞状态注册表 · 用来取<b>本轮 userMessageId</b>（产物归属键）。
     *
     * <p><b>WHY 需要它</b>：PostToolUse 的 {@link ToolUseContext} 只带 sessionId，没有 messageId；
     * 而 {@code AgentState.lastUserMessageId()} 是 loop 每轮维护的权威值
     * （与流式 chunk、终态事件、落库三处同源）。⇒ 经注册表按 sessionId 取。
     *
     * <p>可为 null（单测直构）⇒ 归属退化为「未知」（source_message_id 存 NULL）。
     */
    private final com.nexusai.application.agent.SessionAgentStateRegistry stateRegistry;

    public ArtifactCollectionHooks(SessionArtifactService artifactService) {
        this(artifactService, null);
    }

    /**
     * 全参构造。
     *
     * @param artifactService 产物服务
     * @param stateRegistry   会话在飞状态注册表（取本轮 userMessageId）
     */
    public ArtifactCollectionHooks(SessionArtifactService artifactService,
                                   com.nexusai.application.agent.SessionAgentStateRegistry stateRegistry) {
        this.artifactService = artifactService;
        this.stateRegistry = stateRegistry;
    }

    /**
     * 注册产物/来源采集 PostToolUse hooks。
     *
     * <p>与 {@link SessionFileAccessHooks#registerSessionFileAccessHooks} 同款模式：
     * 逐工具注册 + hook 内按 {@code toolName} 过滤（等价 CC 的 matcher 语义）。
     *
     * @param registrar PostToolUse 注册目标（生产传 {@code HookRegistry}）
     */
    public void registerArtifactCollectionHooks(SessionFileAccessHooks.PostToolUseRegistrar registrar) {
        if (registrar == null) {
            log.warn("[ArtifactCollectionHooks] PostToolUseRegistrar 未注入, 跳过注册");
            return;
        }
        for (String tool : DELIVERABLE_TOOLS) {
            registrar.registerPostToolUseInternal("artifactCollection:" + tool,
                    (toolName, input, result, ctx, stopHookActive) -> {
                        if (toolName != null && toolName.equals(tool)) {
                            collectDeliverable(toolName, input, result, ctx);
                        }
                        return GenericHook.HookResult.proceed();
                    });
        }
        // 来源工具：WebSearch / WebFetch —— 按名字子串匹配（与 SessionArtifactService.isSourceTool 同款口径）
        registrar.registerPostToolUseInternal("artifactCollection:sources",
                (toolName, input, result, ctx, stopHookActive) -> {
                    if (isSourceTool(toolName)) {
                        collectSources(toolName, result, ctx);
                    }
                    return GenericHook.HookResult.proceed();
                });
        log.info("[ArtifactCollectionHooks] 注册 {} 个产物 hook + 1 个来源 hook",
                DELIVERABLE_TOOLS.length);
    }

    /**
     * 采集一条<b>产物</b>（写盘类工具）。
     *
     * <p><b>路径取自工具结果而非入参</b>：Write / Edit 的入参 {@code file_path} 可能是<b>相对路径</b>
     * （基准 = 会话 cwd，随会话/工作树变化），而工具结果里的 {@code filePath} 是
     * <b>写盘时算出的绝对路径</b>（{@code WriteFileTool}/{@code EditFileTool} 都把
     * {@code file.toAbsolutePath().normalize()} 放进结构化输出）⇒ 用结果更稳。
     * 结果里取不到时回落入参（并按会话 cwd 解析）。
     *
     * @param toolName 工具名
     * @param input    工具入参（回落取路径时用）
     * @param result   工具结果（优先取路径）
     * @param ctx      工具上下文（sessionId 来源）
     */
    private void collectDeliverable(String toolName, JsonNode input, ToolResult<?> result, ToolUseContext ctx) {
        if (artifactService == null) {
            return;
        }
        String sessionId = sessionIdOf(ctx);
        if (sessionId == null) {
            return;
        }
        String absPath = extractAbsolutePath(result);
        if (absPath == null) {
            absPath = extractPathFromInput(input);
        }
        if (absPath == null || absPath.isBlank()) {
            return;
        }
        try {
            // syncDeliverableForPath 内部已判「是否在交付物目录之下」+ 源码黑名单 + 幂等 upsert，
            // 这里只管把路径喂给它。
            artifactService.syncDeliverableForPath(sessionId, absPath, userMessageIdOf(sessionId));
        } catch (Throwable t) {
            log.warn("[ArtifactCollectionHooks] 产物采集失败（不影响工具结果） tool={} path={}: {}",
                    toolName, absPath, t.toString());
        }
    }

    /**
     * 采集<b>来源</b>（网络工具结果里的 url）。
     *
     * <p>结果形状（两个工具不同，不能写死路径）：
     * <ul>
     *   <li>WebSearch：{@code {query, results:[{tool_use_id, content:[{title,url}]}, "注释"], durationSeconds}}
     *       ⇒ url 嵌在 {@code results[].content[]}；</li>
     *   <li>WebFetch：{@code {bytes, code, codeText, result, durationMs, url}} ⇒ url 在<b>顶层</b>。</li>
     * </ul>
     * ⇒ 按<b>形状</b>递归收集：任何对象里同时有 {@code url} 就取它 + 同层的 {@code title}。
     *
     * <p>⛔ 只收 http(s)（内部跳转 / 相对路径 / {@code file://} 用户点不开）。
     */
    private void collectSources(String toolName, ToolResult<?> result, ToolUseContext ctx) {
        if (artifactService == null) {
            return;
        }
        String sessionId = sessionIdOf(ctx);
        if (sessionId == null) {
            return;
        }
        String resultJson = resultText(result);
        if (resultJson == null || resultJson.isBlank()) {
            return;
        }
        Map<String, String> found = new LinkedHashMap<>();
        try {
            JsonNode root = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .build().readTree(resultJson);
            collectUrlPairs(root, found);
        } catch (Exception e) {
            // 非 JSON（纯文本错误信息等）→ 无来源，⛔ 不抛、不猜
            if (log.isDebugEnabled()) {
                log.debug("[ArtifactCollectionHooks] 来源结果非 JSON，跳过 tool={}: {}", toolName, e.toString());
            }
            return;
        }
        for (Map.Entry<String, String> e : found.entrySet()) {
            try {
                artifactService.recordSource(sessionId, e.getValue(), e.getKey(), null,
                        userMessageIdOf(sessionId));
            } catch (Throwable t) {
                log.warn("[ArtifactCollectionHooks] 来源入库失败（不影响工具结果） url={}: {}",
                        e.getKey(), t.toString());
            }
        }
    }

    /**
     * 会话 id（**显式无会话 → null**）。
     *
     * <p>⛔ {@link com.nexusai.common.SessionKeys#NO_SESSION} 哨兵 = 「本条路径结构上不属于
     * 任何会话」⇒ **不采集**（不能把来源/产物挂到一个假会话上 —— 那是伪造归属）。
     *
     * @param ctx 工具上下文（null → 无会话）
     * @return 真实会话 id；无会话 → null
     */
    /**
     * 取本轮的 userMessageId（产物/来源的归属键）。
     *
     * <p>取不到（registry 未注入 / 无在飞 state / 字段为空）→ <b>null</b>，
     * 表示「归属未知」—— ⛔ 不编一个 id 出来：伪造归属比没有归属更难排查。
     *
     * @param sessionId 会话 id
     * @return userMessageId；取不到 → null
     */
    private String userMessageIdOf(String sessionId) {
        if (stateRegistry == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            com.nexusai.application.agent.AgentState state = stateRegistry.get(sessionId);
            if (state == null) {
                return null;
            }
            String id = state.lastUserMessageId();
            return id == null || id.isBlank() ? null : id;
        } catch (Throwable t) {
            log.warn("[ArtifactCollectionHooks] 取本轮 userMessageId 失败（归属记为未知） session={}: {}",
                    sessionId, t.toString());
            return null;
        }
    }

    private static String sessionIdOf(ToolUseContext ctx) {
        if (ctx == null) {
            return null;
        }
        String sid = ctx.sessionId();
        if (sid == null || sid.isBlank()) {
            return null;
        }
        if (com.nexusai.common.SessionKeys.isNoSession(sid)) {
            return null;
        }
        return sid;
    }

    // ───────────────────────── 结果/入参解析 ─────────────────────────

    /**
     * 工具结果 → 文本（供 JSON 解析）。
     *
     * <p>WebSearch / WebFetch 的 {@code data} 是<b>String</b>（JSON 串），但不同工具可能给 Map /
     * 对象 ⇒ 这里统一转成文本（Map / 对象交给 Jackson 序列化）。
     */
    private static String resultText(ToolResult<?> result) {
        if (result == null) {
            return null;
        }
        Object data = result.data();
        if (data == null) {
            return null;
        }
        if (data instanceof String s) {
            return s;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从工具结果的呈现元数据里取<b>绝对路径</b>。
     *
     * <p>Write / Edit 的结构化输出都带 {@code filePath}（写盘时的绝对路径）。
     */
    private static String extractAbsolutePath(ToolResult<?> result) {
        if (result == null) {
            return null;
        }
        Map<String, Object> meta = ToolResult.presentationMeta(result);
        Object fp = meta.get("filePath");
        if (fp instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    /** 从工具入参取路径键（结果里没有时的回落）。 */
    private static String extractPathFromInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }
        for (String key : new String[]{"file_path", "path", "absolute_path", "notebook_path"}) {
            JsonNode v = input.get(key);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return null;
    }

    /** 工具名是否是「会产出来源」的网络工具（大小写/前缀不敏感，子串匹配）。 */
    static boolean isSourceTool(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.toLowerCase();
        for (String kw : SOURCE_TOOL_KEYWORDS) {
            if (n.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归收集 {@code {title, url}} 形状的节点。
     *
     * <p>刻意做成<b>形状匹配</b>而不是按固定路径取：WebSearch 的 hits 嵌在
     * {@code results[].content[]}，WebFetch 的 url 在顶层 —— 两处形状不同，
     * 但「同一个对象里既有 url 又有 title」是它们的共同点。
     */
    private static void collectUrlPairs(JsonNode node, Map<String, String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            String url = firstTextual(node, "url");
            if (url != null && isHttpUrl(url)) {
                String title = firstTextual(node, "title");
                out.put(url, title != null && !title.isBlank() ? title : url);
            }
            node.elements().forEachRemaining(child -> collectUrlPairs(child, out));
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectUrlPairs(child, out));
        }
    }

    /** 取对象里第一个命中的文本字段。 */
    private static String firstTextual(JsonNode obj, String key) {
        JsonNode v = obj.get(key);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
    }

    /** 只收 http(s) 绝对地址（内部跳转 / 相对路径用户点不开）。 */
    private static boolean isHttpUrl(String url) {
        String u = url.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }
}
