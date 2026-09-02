package com.nexusai.application.agent.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-E2] WsMcpTransport 基础设施对齐 · 流中断不挂起 + 状态机。
 *
 * <p><b>WHY (规则九)</b>: CC client.ts:1333-1337 明确 WS 无 3-strike 终端错误计数（纯惰性重连），
 * Java WS 主动退避重连为部署基础设施保留（TR-E3-D-1 🔒）。本测试锁定 IMP-E2 相关不变量：
 * <ul>
 *   <li>连接超时常量（WS 建连 fail-fast，不永久挂起；Q-11-8 ready.get(timeout)）</li>
 *   <li>onError（java.net.http 契约：onError 后必跟 onClose）→ 状态兜底 CLOSED + ready 异常完成
 *       （start() fail-fast，不挂死建连线程）</li>
 *   <li>onClose 状态迁移 CONNECTED → CLOSED + failAllPending（在途请求 reject，防悬挂）</li>
 * </ul>
 */
class WsMcpTransportTest {

    @Test
    @DisplayName("[IMP-E2] WS 建连超时常量 = 10s（Q-11-8 fail-fast，不永久挂起）")
    void connectTimeout_failsFast() {
        // WHY: WS 断线自动重连依赖 start() fail-fast——重连尝试若挂死，自动重连即失效。
        assertThat(WsMcpTransport.CONNECT_TIMEOUT_MS).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("[IMP-E2] onError 在未 start 时不崩溃且不悬挂（ready 未初始化兜底）")
    void errorBeforeStart_noCrash_noHang() {
        // WHY: WS onError 在 ready 未初始化（未 start）时不得 NPE（对齐 Q-11-8 onError 兜底）。
        WsMcpTransport t = new WsMcpTransport();
        t.handleWsError(new RuntimeException("boom"));
        // 未抛异常即通过；状态保持 NOT_CONNECTED（未连接）
        assertThat(t.getState()).isEqualTo(McpTransport.State.NOT_CONNECTED);
    }

    @Test
    @DisplayName("[IMP-E2] 连接期失败（onOpen 未触发）→ 状态 NOT_CONNECTED，不触发断开 notifier（无 3-strike）")
    void connectTimeFailure_doesNotTriggerNotifier() {
        // WHY: CC client.ts:1333-1337 WS 无 3-strike；连接期失败由 start() 抛错走批连接 fail-soft，
        // 不得重复触发主动重连（否则死 server 反复建连）。
        WsMcpTransport t = new WsMcpTransport();
        boolean[] notified = {false};
        t.setDisconnectNotifier(a -> notified[0] = true);
        t.handleWsClose(1006, "abnormal closure");
        assertThat(notified[0]).isFalse();
    }

    @Test
    @DisplayName("[IMP-E2] 连接中断（CONNECTED → onError+onClose）→ 状态 CLOSED，断开 notifier 触发（清缓存+重连）")
    void connectionDrop_marksClosedAndNotifies() {
        // WHY: CC client.onerror/onclose（client.ts:1374-1402）：流中断 → 清缓存 → 惰性重连；
        // Java WsMcpTransport.handleWsClose failAllPending + notifier 承载该语义（防悬挂）。
        WsMcpTransport t = new WsMcpTransport();
        t.setStateForTest(McpTransport.State.CONNECTED);
        boolean[] notified = {false};
        boolean[] auth = {true};
        t.setDisconnectNotifier(a -> {
            notified[0] = true;
            auth[0] = a;
        });
        // java.net.http.WebSocket 契约：onError 之后必跟 onClose
        t.handleWsError(new RuntimeException("connection reset"));
        t.handleWsClose(1006, "connection reset");

        assertThat(t.getState()).isEqualTo(McpTransport.State.CLOSED);
        assertThat(notified[0]).as("连接期断开应触发 notifier（清缓存 + 退避重连）").isTrue();
        assertThat(auth[0]).as("非 4003 断开 → authRequired=false").isFalse();
    }

    @Test
    @DisplayName("[IMP-E2] close 4003 认证关闭 + 刷新失败 → authRequired=true（needs-auth）")
    void close4003_refreshFails_authRequired() {
        // WHY: WS 4003 = 认证失败（对齐 HttpMcpTransport 401 → McpAuthError 降级语义）——token 过期
        // 且刷新无法恢复 → 上层需走 needs-auth + S1 OAuth（对齐 CC WS 4003 refreshHeaders→reconnect）。
        WsMcpTransport t = new WsMcpTransport();
        t.setStateForTest(McpTransport.State.CONNECTED);
        boolean[] auth = {false};
        t.setDisconnectNotifier(a -> auth[0] = a);
        t.handleWsClose(4003, "unauthorized");
        assertThat(auth[0]).isTrue();
    }
}
