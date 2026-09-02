package com.nexusai.application.agent.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpRemoteSessionsApi 传输原语测试 · 对齐 CC utils/teleport/api.ts。
 *
 * <p><b>WHY（规则九）</b>: T1 缺口 = sendEventToRemoteSession（POST events，api.ts:361-417）
 * 与 updateSessionTitle（PATCH title，api.ts:425-466）两个原语。RemoteSessionManager.sendMessage
 * 依赖 sendEventToRemoteSession；标题同步依赖 updateSessionTitle。测试用注入执行器锁死
 * 请求方法/URL/头/请求体形状与成功判定（200/201 vs 其余），防"假接线"。
 */
@DisplayName("[W6-B] HttpRemoteSessionsApi 传输原语（sendEvent/updateTitle，对齐 CC api.ts）")
class HttpRemoteSessionsApiTest {

    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastUrl = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private HttpRemoteSessionsApi api(int responseStatus, boolean authAvailable) {
        HttpRemoteSessionsApi.HttpGetExecutor get = (url, h, p, t) ->
            new HttpRemoteSessionsApi.HttpResponse(404, "{}");
        HttpRemoteSessionsApi.HttpPostExecutor post = (url, h, body, t) -> {
            lastMethod.set("POST");
            lastUrl.set(url);
            lastBody.set(body);
            return new HttpRemoteSessionsApi.HttpResponse(responseStatus, "{}");
        };
        HttpRemoteSessionsApi.HttpPatchExecutor patch = (url, h, body, t) -> {
            lastMethod.set("PATCH");
            lastUrl.set(url);
            lastBody.set(body);
            return new HttpRemoteSessionsApi.HttpResponse(responseStatus, "{}");
        };
        return new HttpRemoteSessionsApi(() -> "https://api.anthropic.com", () -> {
            if (!authAvailable) {
                return null;
            }
            return new HttpRemoteSessionsApi.AuthContext("token-1", "org-1");
        }, get, post, patch);
    }

    @Test
    @DisplayName("sendEvent: POST /v1/sessions/{id}/events + 头 + 请求体形状；201 → true（CC :361-407）")
    void sendEventPostsUserEventShape() {
        HttpRemoteSessionsApi api = api(201, true);

        boolean ok = api.sendEventToRemoteSession("sess-1",
            List.of(Map.of("type", "text", "text", "hello")), "my-uuid");

        assertThat(ok).isTrue();
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastUrl.get()).isEqualTo("https://api.anthropic.com/v1/sessions/sess-1/events");
        // 请求体：{events:[{uuid, session_id, type:'user', parent_tool_use_id:null, message:{role:'user', content}}]}
        assertThat(lastBody.get()).contains("\"uuid\":\"my-uuid\"");
        assertThat(lastBody.get()).contains("\"session_id\":\"sess-1\"");
        assertThat(lastBody.get()).contains("\"type\":\"user\"");
        assertThat(lastBody.get()).contains("\"parent_tool_use_id\":null");
        assertThat(lastBody.get()).contains("\"role\":\"user\"");
        assertThat(lastBody.get()).contains("\"text\":\"hello\"");
    }

    @Test
    @DisplayName("sendEvent: 非 200/201 → false（CC :409-412）")
    void sendEventNon2xxIsFalse() {
        HttpRemoteSessionsApi api = api(400, true);
        assertThat(api.sendEventToRemoteSession("sess-1", "hi", null)).isFalse();
    }

    @Test
    @DisplayName("sendEvent: 无 auth（prepareApiRequest 抛错）→ false 不抛（CC :366-367/:413-416）")
    void sendEventWithoutAuthIsFalse() {
        HttpRemoteSessionsApi api = api(200, false);
        assertThat(api.sendEventToRemoteSession("sess-1", "hi", null)).isFalse();
        assertThat(lastUrl.get()).isNull();
    }

    @Test
    @DisplayName("updateTitle: PATCH /v1/sessions/{id} body {title}；200 → true（CC :425-456）")
    void updateTitlePatchesTitle() {
        HttpRemoteSessionsApi api = api(200, true);

        boolean ok = api.updateSessionTitle("sess-1", "远程代码审查");

        assertThat(ok).isTrue();
        assertThat(lastMethod.get()).isEqualTo("PATCH");
        assertThat(lastUrl.get()).isEqualTo("https://api.anthropic.com/v1/sessions/sess-1");
        assertThat(lastBody.get()).contains("\"title\":\"远程代码审查\"");
    }

    @Test
    @DisplayName("updateTitle: 非 200 → false（CC :457-461）")
    void updateTitleNon200IsFalse() {
        HttpRemoteSessionsApi api = api(500, true);
        assertThat(api.updateSessionTitle("sess-1", "x")).isFalse();
    }

    @Test
    @DisplayName("updateTitle: 无 auth → false（CC :430/:462-465）")
    void updateTitleWithoutAuthIsFalse() {
        HttpRemoteSessionsApi api = api(200, false);
        assertThat(api.updateSessionTitle("sess-1", "x")).isFalse();
        assertThat(lastUrl.get()).isNull();
    }
}
