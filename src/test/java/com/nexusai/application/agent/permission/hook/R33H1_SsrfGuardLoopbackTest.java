package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Session H1] SsrfGuard loopback 允许策略对齐 CC · 对齐
 * CC Open-ClaudeCode/src/utils/hooks/ssrfGuard.ts:55-125 (isBlockedV4/V6) +
 * :216-283 (ssrfGuardedLookup).
 *
 * <p>WHY: CC 明确允许 loopback (127.0.0.0/8, ::1) 用于本地 dev policy server,
 * 这是 HTTP hook 的主要用例; Java 当前拒绝 loopback → 本地 dev hook 全部不可用.
 * 同时 CC 仅拦截特定 private/link-local 范围, 不拦截 multicast/reserved/NAT64/doc,
 * Java 多拦截了 4 段地址, 语义错位. 另补 ssrfGuardedLookup 实现 DNS rebinding 防御
 * (解析后校验, 任一地址 private 即拒绝), 供 H6 ExecHttpHook 连接前用.
 *
 * <h2>测试用例 (5 项, 覆盖 H1.md 步骤 2)</h2>
 * <ol>
 *   <li>{@link #ssrf_allowsLoopbackV4_127_0_0_1()} — CC ssrfGuard.ts:68 允许 127, Java 当前拒绝</li>
 *   <li>{@link #ssrf_allowsLoopbackV6__1()} — CC ssrfGuard.ts:92 允许 ::1, Java 当前拒绝</li>
 *   <li>{@link #ssrf_doesNotBlockMulticast224()} — CC 不拦截 224.0.0.0/4 multicast</li>
 *   <li>{@link #ssrf_doesNotBlockReserved240()} — CC 不拦截 240.0.0.0/4 reserved</li>
 *   <li>{@link #ssrfGuardedLookup_rejectsPrivateResolution()} — DNS rebinding 防御: 解析到 private 即拒绝</li>
 * </ol>
 *
 * @since Session H1 (P0)
 */
@DisplayName("[H1] SsrfGuard loopback 允许策略对齐 CC")
class R33H1_SsrfGuardLoopbackTest {

    private final SsrfGuard guard = new SsrfGuard();

    @Test
    @DisplayName("127.0.0.1 loopback 应被允许 (CC ssrfGuard.ts:68)")
    void ssrf_allowsLoopbackV4_127_0_0_1() {
        // WHY: CC isBlockedV4 line 68 `if (a === 127) return false` 显式允许 loopback
        assertThat(guard.isPrivateIp(InetAddress.getLoopbackAddress()))
            .as("127.0.0.0/8 loopback 应被 CC 允许, 不应判为 private")
            .isFalse();
    }

    @Test
    @DisplayName("::1 IPv6 loopback 应被允许 (CC ssrfGuard.ts:92)")
    void ssrf_allowsLoopbackV6__1() {
        // WHY: CC isBlockedV6 line 92 `if (lower === '::1') return false` 显式允许 ::1
        try {
            byte[] loopback = new byte[16];
            loopback[15] = 1;
            InetAddress addr = InetAddress.getByAddress(loopback);
            assertThat(guard.isPrivateIp(addr))
                .as("::1 IPv6 loopback 应被 CC 允许, 不应判为 private")
                .isFalse();
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError("构造 ::1 失败", e);
        }
    }

    @Test
    @DisplayName("224.0.0.0/4 multicast 应不被拦截 (CC isBlockedV4 不含此段)")
    void ssrf_doesNotBlockMulticast224() {
        // WHY: CC isBlockedV4 (ssrfGuard.ts:55-86) 仅拦截 0/10/169.254/172.16/100.64/192.168,
        // 不含 224.0.0.0/4 multicast; Java 当前 line 119 拦截, 语义错位
        try {
            InetAddress multicast = InetAddress.getByAddress(new byte[]{(byte) 224, 0, 0, 1});
            assertThat(guard.isPrivateIp(multicast))
                .as("224.0.0.0/4 multicast CC 不拦截, 不应判为 private")
                .isFalse();
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError("构造 224.0.0.1 失败", e);
        }
    }

    @Test
    @DisplayName("240.0.0.0/4 reserved 应不被拦截 (CC isBlockedV4 不含此段)")
    void ssrf_doesNotBlockReserved240() {
        // WHY: CC isBlockedV4 不拦截 240.0.0.0/4 reserved; Java 当前 line 120 拦截
        try {
            InetAddress reserved = InetAddress.getByAddress(new byte[]{(byte) 240, 0, 0, 1});
            assertThat(guard.isPrivateIp(reserved))
                .as("240.0.0.0/4 reserved CC 不拦截, 不应判为 private")
                .isFalse();
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError("构造 240.0.0.1 失败", e);
        }
    }

    @Test
    @DisplayName("169.254.169.254 拦截：SsrfBlockedException 携带 CC 消息 + code + hostname/address（CC ssrfGuard.ts:285-294）")
    void ssrf_blockedLiteral_throwsSsrfBlockedExceptionWithCcContract() {
        // WHY: CC ssrfGuardedLookup (ssrfGuard.ts:216-283) 解析 hostname 后逐地址校验,
        // 任一 private 抛 code='ERR_HTTP_HOOK_BLOCKED_ADDRESS' + hostname/address 属性 +
        // 固定消息 `HTTP hook blocked: ${host} resolves to ${addr} (private/link-local address).
        // Loopback (127.0.0.1, ::1) is allowed for local dev.`（[CCJ-EXEC-07]）。
        // 169.254.169.254 是 IP 字面量, 应被直接拒绝 (cloud metadata SSRF 防御核心场景)。
        // NOTE: [CCJ-EXEC-07] 旧 formatBlocked 'Blocked <ip> (<reason>)' 已删除——消费方按
        // code 判类（ExecHttpHook catch SecurityException 取 getMessage() 进 error 字段）。
        assertThatThrownBy(() -> guard.ssrfGuardedLookup("169.254.169.254"))
            .isInstanceOf(SsrfGuard.SsrfBlockedException.class)
            .satisfies(e -> {
                SsrfGuard.SsrfBlockedException se = (SsrfGuard.SsrfBlockedException) e;
                assertThat(se.code()).isEqualTo(SsrfGuard.ERR_HTTP_HOOK_BLOCKED_ADDRESS);
                assertThat(se.hostname()).isEqualTo("169.254.169.254");
                assertThat(se.address()).isEqualTo("169.254.169.254");
                assertThat(se.getMessage()).isEqualTo(
                    "HTTP hook blocked: 169.254.169.254 resolves to 169.254.169.254 "
                        + "(private/link-local address). Loopback (127.0.0.1, ::1) is allowed for local dev.");
            });
    }
}