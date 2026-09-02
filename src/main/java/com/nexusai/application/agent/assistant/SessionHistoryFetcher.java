package com.nexusai.application.agent.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session history fetcher · 对齐 CC assistant/sessionHistory.ts.
 *
 * <p>L1 语义: claude.ai history 页 fetcher. 创建 auth ctx 后翻页拉 session events.
 *            fetchLatestEvents 用 anchor_to_latest=true 取最新页;
 *            fetchOlderEvents 用 before_id=beforeId 取前 (更老) 一页.
 *            返回 HistoryPage 含 events/firstId/hasMore.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: HISTORY_PAGE_SIZE = 100;
 *       HistoryPage 3 字段 (events/firstId/hasMore);
 *       HistoryAuthCtx 2 字段 (baseUrl/headers);
 *       createHistoryAuthCtx(sessionId) → 拼接 ${baseUrl}/v1/sessions/${sessionId}/events
 *       + headers (Bearer + anthropic-beta + x-organization-uuid).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — createHistoryAuthCtx → fetchLatestEvents (anchor_to_latest=true, limit=100)
 *       → GET → 解析 events/first_id/has_more → 返回 HistoryPage;
 *       翻页 — fetchOlderEvents (before_id=&lt;firstId&gt;, limit=100).</li>
 *   <li><b>A3</b>: 2 种 page 类型 (LATEST / OLDER);失败 → null (catch all + logForDebugging).</li>
 *   <li><b>A4</b>: 非 200 状态码 → null (validateStatus accept all 但 status check);
 *       data.data 非数组 → events=[];first_id 缺失 → null.</li>
 *   <li><b>A5</b>: 真实场景 — 拉第一页 history → firstId 用作下一更老页 cursor → fetchOlderEvents 循环.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `axios.get` → 注入式 HttpGetExecutor (testable, no real HTTP);
 *                    TS `getOauthConfig().BASE_API_URL` → 注入式 Supplier&lt;String&gt;;
 *                    TS `prepareApiRequest()` → 注入式 Function&lt;String, AuthInfo&gt;;
 *                    TS `SDKMessage[]` → Java List&lt;Map&lt;String,Object&gt;&gt; (未知 JSON 结构);
 *                    TS `Record&lt;string,string&gt;` headers → Java Map&lt;String,String&gt;.
 */
public final class SessionHistoryFetcher {

    private static final Logger log = LoggerFactory.getLogger(SessionHistoryFetcher.class);

    /** CC HISTORY_PAGE_SIZE — 默认 page size. */
    public static final int HISTORY_PAGE_SIZE = 100;

    private final Supplier<String> baseUrlSupplier;
    private final Function<String, AuthInfo> authPreparer;
    private final HttpGetExecutor httpGet;
    private final DebugLogger debugLog;

    public SessionHistoryFetcher(Supplier<String> baseUrlSupplier,
                                 Function<String, AuthInfo> authPreparer,
                                 HttpGetExecutor httpGet,
                                 DebugLogger debugLog) {
        this.baseUrlSupplier = Objects.requireNonNull(baseUrlSupplier);
        this.authPreparer = Objects.requireNonNull(authPreparer);
        this.httpGet = Objects.requireNonNull(httpGet);
        this.debugLog = Objects.requireNonNull(debugLog);
    }

    /** CC AuthInfo — prepareApiRequest 返回 (accessToken + orgUUID). */
    public record AuthInfo(String accessToken, String orgUUID) {}

    /** CC HistoryPage — 3 字段分页响应. */
    public record HistoryPage(
        List<Map<String, Object>> events,
        String firstId,
        boolean hasMore
    ) {}

    /** CC HistoryAuthCtx — auth + headers + base URL. */
    public record HistoryAuthCtx(String baseUrl, Map<String, String> headers) {}

    /** CC SessionEventsResponse — server 响应 (4 字段). */
    public record SessionEventsResponse(
        List<Map<String, Object>> data,
        Boolean hasMore,
        String firstId,
        String lastId
    ) {}

    /** 调试日志 (注入;CC 端是 logForDebugging). */
    @FunctionalInterface
    public interface DebugLogger {
        void log(String label, String message);
    }

    /** HTTP GET 执行器 (注入). */
    @FunctionalInterface
    public interface HttpGetExecutor {
        HttpGetResult get(String url, Map<String, String> headers, Map<String, Object> params)
            throws Exception;
    }

    public record HttpGetResult(Integer status, SessionEventsResponse body) {
        public boolean ok() { return status != null && status == 200; }
    }

    /** ResponseParser — 把 JSON 字符串解析成 SessionEventsResponse (注入). */
    @FunctionalInterface
    public interface ResponseParser {
        SessionEventsResponse parse(String body);
    }

    /** CC createHistoryAuthCtx — 一次性准备 auth + base URL. */
    public HistoryAuthCtx createHistoryAuthCtx(String sessionId) {
        AuthInfo auth = authPreparer.apply(sessionId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer " + auth.accessToken());
        headers.put("anthropic-beta", "ccr-byoc-2025-07-29");
        headers.put("x-organization-uuid", auth.orgUUID());
        return new HistoryAuthCtx(
            baseUrlSupplier.get() + "/v1/sessions/" + sessionId + "/events",
            headers);
    }

    /** CC fetchLatestEvents — anchor_to_latest=true. */
    public HistoryPage fetchLatestEvents(HistoryAuthCtx ctx, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", limit);
        params.put("anchor_to_latest", true);
        return fetchPage(ctx, params, "fetchLatestEvents");
    }

    /** CC fetchLatestEvents 默认 limit = HISTORY_PAGE_SIZE. */
    public HistoryPage fetchLatestEvents(HistoryAuthCtx ctx) {
        return fetchLatestEvents(ctx, HISTORY_PAGE_SIZE);
    }

    /** CC fetchOlderEvents — before_id=beforeId. */
    public HistoryPage fetchOlderEvents(HistoryAuthCtx ctx, String beforeId, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", limit);
        params.put("before_id", beforeId);
        return fetchPage(ctx, params, "fetchOlderEvents");
    }

    public HistoryPage fetchOlderEvents(HistoryAuthCtx ctx, String beforeId) {
        return fetchOlderEvents(ctx, beforeId, HISTORY_PAGE_SIZE);
    }

    private HistoryPage fetchPage(HistoryAuthCtx ctx, Map<String, Object> params, String label) {
        HttpGetResult result;
        try {
            result = httpGet.get(ctx.baseUrl(), ctx.headers(), params);
        } catch (Exception e) {
            debugLog.log(label, "HTTP error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            log.debug("[{}] HTTP error: {}", label, e.getMessage());
            return null;
        }
        if (result == null || !result.ok()) {
            debugLog.log(label, "HTTP " + (result == null ? "error" : result.status()));
            return null;
        }
        SessionEventsResponse body = result.body();
        List<Map<String, Object>> events = (body != null && body.data() instanceof List)
            ? new ArrayList<>(body.data())
            : new ArrayList<>();
        String firstId = body == null ? null : body.firstId();
        boolean hasMore = body != null && Boolean.TRUE.equals(body.hasMore());
        return new HistoryPage(events, firstId, hasMore);
    }
}
