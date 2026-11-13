package com.nexusai.application.agent.toolsearch;

import com.nexusai.infra.llm.LlmApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端点拒绝 {@code defer_loading} 的识别与会话级降级 · 对齐 CC 2.1.323。
 *
 * <p><b>CC 真源</b>：{@code jJ = eo => Nqe(eo) && (!wFe() || /\bdefer_loading\b/.test(eo.message))
 * && (eo.status===400 || eo.status===422) && …}，命中后发文案
 * {@code [ToolSearch] HTTP 400 …; sending the request again without deferred tools}，
 * 并在会话内记住（不再试）。
 */
class ToolSearchRefusalFallbackTest {

    @AfterEach
    void reset() {
        ToolSearchService.clearRefusedSessionsForTest();
    }

    private static LlmApiException api(int status, String body) {
        return new LlmApiException(status, Map.of(), body);
    }

    @Test
    @DisplayName("400/422 且消息含 defer_loading → 判为拒绝")
    void detectsRefusal() {
        assertTrue(ToolSearchService.isDeferLoadingRefusal(
                api(400, "{\"error\":{\"message\":\"Unexpected field: defer_loading\"}}")));
        assertTrue(ToolSearchService.isDeferLoadingRefusal(
                api(422, "tools.0.defer_loading: Extra inputs are not permitted")));
        // 边界：整词匹配（\b），`defer_loadingx` 不算
        assertTrue(ToolSearchService.isDeferLoadingRefusal(
                api(400, "unknown field 'defer_loading'")));
    }

    @Test
    @DisplayName("非 400/422、或不含 defer_loading、或异常类型不符 → 不判拒绝")
    void ignoresOtherErrors() {
        assertFalse(ToolSearchService.isDeferLoadingRefusal(api(500, "defer_loading boom")), "500 不是拒绝");
        assertFalse(ToolSearchService.isDeferLoadingRefusal(api(400, "messages.3: bad tool_use")), "无关 400");
        assertFalse(ToolSearchService.isDeferLoadingRefusal(api(400, "defer_loadingx is bad")),
                "\\b 整词边界：defer_loadingx 不应命中");
        assertFalse(ToolSearchService.isDeferLoadingRefusal(new RuntimeException("defer_loading")), "非 LlmApiException");
        assertFalse(ToolSearchService.isDeferLoadingRefusal(null));
    }

    @Test
    @DisplayName("会话级标记：只影响本会话，且幂等")
    void sessionScopedFlag() {
        assertFalse(ToolSearchService.isToolSearchRefused("s-a"));
        ToolSearchService.markToolSearchRefused("s-a");
        assertTrue(ToolSearchService.isToolSearchRefused("s-a"));
        assertFalse(ToolSearchService.isToolSearchRefused("s-b"), "别的会话不受影响");
        ToolSearchService.markToolSearchRefused("s-a");
        assertTrue(ToolSearchService.isToolSearchRefused("s-a"), "重复标记幂等");
        assertFalse(ToolSearchService.isToolSearchRefused(null), "null 会话永不判拒绝");
        ToolSearchService.markToolSearchRefused(null);
        assertFalse(ToolSearchService.isToolSearchRefused(null), "null 标记是 no-op");
    }

    @Test
    @DisplayName("body 为空时回落到 message 判定（body 可能被上游丢弃）")
    void fallsBackToMessage() {
        LlmApiException ex = new LlmApiException(400, Map.of(), "");
        // message = "HTTP 400: " —— 不含 defer_loading，应判否
        assertFalse(ToolSearchService.isDeferLoadingRefusal(ex));
        // 构造一个 body 含关键字的正常异常作为对照
        assertTrue(ToolSearchService.isDeferLoadingRefusal(api(400, "defer_loading")));
    }
}
