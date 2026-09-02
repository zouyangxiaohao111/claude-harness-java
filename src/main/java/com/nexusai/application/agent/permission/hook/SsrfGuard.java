package com.nexusai.application.agent.permission.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * SSRF Guard · 对齐 CC utils/ssrfGuard.ts (294 行).
 *
 * <p>FIX-HK5: URL 白名单检查防止 hook 调用触发 SSRF.
 * <p>FIX-R9: 完整 IPv4 + IPv6 private range 校验 + IPv4-mapped-IPv6 解包 + DNS rebinding 防御.
 *
 * <h2>L1 行为</h2>
 * <ul>
 *   <li>{@link #ssrfGuardedLookup(String)} — DNS rebinding 防御式解析: 给定 URL 的 host,
 *       解析并校验全部地址, 任一 private/reserved IP 则抛 SecurityException</li>
 *   <li>{@link #isPrivateIp(InetAddress)} — 判定 InetAddress 是否属于 private/reserved range</li>
 * </ul>
 *
 * <h2>L2 契约不变量</h2>
 * <ul>
 *   <li>错误码常量 {@link #ERR_HTTP_HOOK_BLOCKED_ADDRESS} 字符串不变</li>
 *   <li>拦截错误 = {@link SsrfBlockedException}（code/hostname/address 属性 + CC 固定消息
 *       {@code HTTP hook blocked: ${host} resolves to ${addr} (private/link-local address).
 *       Loopback (127.0.0.1, ::1) is allowed for local dev.}，对齐 CC ssrfGuard.ts:285-294）</li>
 *   <li>公开 API 方法名 {@code ssrfGuardedLookup(url)} / {@code isPrivateIp(ip)} 不变
 *       （validate/isAllowed 已随 DEL-SS-01 删除, 见下方删除说明）</li>
 * </ul>
 */
@Component
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    /** L2 契约: 错误码字符串, 其他模块依赖. */
    public static final String ERR_HTTP_HOOK_BLOCKED_ADDRESS = "ERR_HTTP_HOOK_BLOCKED_ADDRESS";

    /**
     * [IMPL-09] DEL-SS-01: BLOCKED_HOSTS / validate / isAllowed 已删除（0 生产调用，
     * EV-SS-001；CC ssrfGuard.ts 无 URL 级校验、无 hostname blocklist，cloud metadata
     * 靠 IP 范围 169.254/16 + 100.64/10 拦截）。保留 ssrfGuardedLookup（ExecHttpHook
     * 唯一生产消费，DNS rebinding 防御）+ isPrivateIp（校验原语）。
     */

    /**
     * DNS rebinding 防御式解析 · 对齐 CC ssrfGuard.ts:216-283 (ssrfGuardedLookup).
     *
     * <p>WHY: 普通 validate 在解析与连接之间存在 TOCTOU 窗口 (解析时安全, 连接时 DNS
     * 重绑定到 private). CC 通过把校验过的 IP 直接交给 socket 连接消除该窗口. Java 端
     * HttpClient 无法注入 lookup, 故返回首个校验过的 {@link InetAddress}, 由 H6
     * ExecHttpHook 用该 InetAddress 显式构造请求, 确保连接到的就是校验过的 IP.
     *
     * <p>行为契约 (镜像 CC):
     * <ul>
     *   <li>hostname 为 IP 字面量 → 直接校验, 通过则返回该地址</li>
     *   <li>hostname 为域名 → {@code getAllByName} 解析全部地址, 逐个校验,
     *       任一 private 抛 {@link SecurityException} 含 ERR_HTTP_HOOK_BLOCKED_ADDRESS</li>
     *   <li>解析无结果 → 抛 SecurityException (ENOTFOUND 语义)</li>
     *   <li>返回首个校验通过的安全地址 (供 H6 连接用)</li>
     * </ul>
     *
     * @param hostname 主机名或 IP 字面量
     * @return 首个校验通过的安全 InetAddress
     * @throws SecurityException 若解析到 private/link-local 地址或解析失败
     */
    public InetAddress ssrfGuardedLookup(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            throw new SecurityException("empty hostname");
        }
        // IP 字面量直接校验, 不走 DNS
        byte[] literal = parseIpLiteral(hostname);
        if (literal != null) {
            try {
                InetAddress addr = InetAddress.getByAddress(literal);
                if (isPrivateIp(addr)) {
                    log.warn("SSRF guarded lookup blocked literal: {} resolved to private ip={}",
                        hostname, addr.getHostAddress());
                    // CC ssrfGuard.ts:232-234 — IP 字面量 blocked → ssrfError(hostname, hostname)
                    throw blocked(hostname, hostname);
                }
                if (log.isDebugEnabled()) {
                    log.debug("SSRF guarded lookup passed literal: {}", hostname);
                }
                return addr;
            } catch (UnknownHostException e) {
                throw new SecurityException("dns resolution failed: " + e.getMessage());
            }
        }
        // 域名: 解析全部地址, 任一 private 即拒绝 (DNS rebinding 防御)
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(hostname);
        } catch (UnknownHostException e) {
            throw new SecurityException("dns resolution failed: " + e.getMessage());
        }
        if (addrs.length == 0) {
            throw new SecurityException("ENOTFOUND");
        }
        for (InetAddress addr : addrs) {
            if (isPrivateIp(addr)) {
                log.warn("SSRF guarded lookup blocked: host={} resolved to private ip={}",
                    hostname, addr.getHostAddress());
                // CC ssrfGuard.ts:252-255 — 域名任一 blocked 地址 → ssrfError(hostname, address)
                throw blocked(hostname, addr.getHostAddress());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("SSRF guarded lookup passed for host={} ({} addrs)", hostname, addrs.length);
        }
        return addrs[0];
    }

    /**
     * 构造 SSRF 拦截异常 · 对齐 CC ssrfGuard.ts:285-294 {@code ssrfError}:
     * <pre>
     *   message: `HTTP hook blocked: ${hostname} resolves to ${address} (private/link-local address).
     *             Loopback (127.0.0.1, ::1) is allowed for local dev.`
     *   code:    'ERR_HTTP_HOOK_BLOCKED_ADDRESS'
     *   hostname / address 属性附着
     * </pre>
     *
     * <p>[CCJ-EXEC-07] 旧实现 {@code formatBlocked}（'Blocked &lt;ip&gt; (&lt;reason&gt;)'）已删除 —
     * CC 消费方按 code 判类（ExecHttpHook catch SecurityException 取 getMessage() 进 error 字段，
     * 表面不变；R33H1 测试断言同步为 CC 文本/code）。
     *
     * @param hostname 被校验的 host（域名或 IP 字面量）
     * @param address  解析出的 blocked 地址
     */
    private static SsrfBlockedException blocked(String hostname, String address) {
        return new SsrfBlockedException(
            "HTTP hook blocked: " + hostname + " resolves to " + address
                + " (private/link-local address). Loopback (127.0.0.1, ::1) is allowed for local dev.",
            hostname, address);
    }

    /**
     * SSRF 拦截异常 · 对齐 CC ssrfGuard.ts:285-294 的错误语义:
     * {@code code='ERR_HTTP_HOOK_BLOCKED_ADDRESS'} + {@code hostname}/{@code address} 属性 +
     * CC 固定消息文本（消费方可按 code 判类，不再依赖消息字符串）。
     *
     * <p>继承 {@link SecurityException}：ExecHttpHook 既有 {@code catch (SecurityException)}
     * 分支（:274-280）零改动接管。
     */
    public static final class SsrfBlockedException extends SecurityException {
        private final String code;
        private final String hostname;
        private final String address;

        SsrfBlockedException(String message, String hostname, String address) {
            super(message);
            this.code = ERR_HTTP_HOOK_BLOCKED_ADDRESS;
            this.hostname = hostname;
            this.address = address;
        }

        /** CC original: err.code（ssrfGuard.ts:290）— 'ERR_HTTP_HOOK_BLOCKED_ADDRESS' */
        public String code() {
            return code;
        }

        /** CC original: err.hostname（ssrfGuard.ts:291）— 被校验的 host */
        public String hostname() {
            return hostname;
        }

        /** CC original: err.address（ssrfGuard.ts:292）— 解析出的 blocked 地址 */
        public String address() {
            return address;
        }
    }

    /**
     * 尝试将 hostname 解析为 IP 字面量 (v4 或 v6), 非字面量返回 null.
     */
    private byte[] parseIpLiteral(String hostname) {
        if (hostname == null) return null;
        // IPv4: 4 段数字
        if (isIpv4Literal(hostname)) {
            String[] parts = hostname.split("\\.");
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) {
                b[i] = (byte) Integer.parseInt(parts[i]);
            }
            return b;
        }
        // IPv6 字面量: 委托 InetAddress.getByName (仅字面量场景, 不触发 DNS)
        if (hostname.indexOf(':') >= 0) {
            try {
                InetAddress addr = InetAddress.getByName(hostname);
                // getByName 对字面量不触发 DNS, 返回的 host 应等于输入
                if (hostname.equals(addr.getHostAddress()) || addr.getHostAddress().equals(hostname)) {
                    return addr.getAddress();
                }
            } catch (UnknownHostException e) {
                return null;
            }
        }
        return null;
    }

    private boolean isIpv4Literal(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty()) return false;
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    public boolean isPrivateIp(InetAddress ip) {
        if (ip == null) return true;
        byte[] bytes = ip.getAddress();
        if (bytes.length == 4) {
            return isPrivateIpV4(bytes);
        }
        if (bytes.length == 16) {
            return isPrivateIpV6(bytes);
        }
        return true;
    }

    private boolean isPrivateIpV4(byte[] b) {
        int o0 = b[0] & 0xFF;
        int o1 = b[1] & 0xFF;

        // WHY: 对齐 CC ssrfGuard.ts:68 `if (a === 127) return false` —— loopback 显式允许,
        // 本地 dev policy server 是 HTTP hook 主要用例, 不应拦截.
        if (o0 == 127) return false;
        if (o0 == 0) return true;                         // 0.0.0.0/8
        if (o0 == 10) return true;                        // 10.0.0.0/8
        if (o0 == 100 && o1 >= 64 && o1 <= 127) return true; // 100.64.0.0/10 CGNAT
        if (o0 == 169 && o1 == 254) return true;          // 169.254.0.0/16 link-local
        if (o0 == 172 && o1 >= 16 && o1 <= 31) return true;  // 172.16.0.0/12
        if (o0 == 192 && o1 == 168) return true;          // 192.168.0.0/16
        // WHY: 对齐 CC ssrfGuard.ts:55-86 —— CC 不拦截 224.0.0.0/4 multicast 与
        // 240.0.0.0/4 reserved, 原多拦截的 2 段已移除 (语义错位修复).
        return false;
    }

    private boolean isPrivateIpV6(byte[] b) {
        if (isIpv4MappedIpv6(b)) {
            byte[] v4 = new byte[4];
            System.arraycopy(b, 12, v4, 0, 4);
            if (log.isDebugEnabled()) {
                log.debug("IPv4-mapped-IPv6 detected, unwrapped to v4: {}.{}.{}.{}",
                    v4[0] & 0xFF, v4[1] & 0xFF, v4[2] & 0xFF, v4[3] & 0xFF);
            }
            return isPrivateIpV4(v4);
        }

        int o0 = b[0] & 0xFF;
        int o1 = b[1] & 0xFF;
        int o2 = b[2] & 0xFF;
        int o3 = b[3] & 0xFF;

        // WHY: 对齐 CC ssrfGuard.ts:92 `if (lower === '::1') return false` ——
        // ::1 IPv6 loopback 显式允许, 本地 dev hook 用例.
        if (isAllZero(b, 0, 15) && (b[15] & 0xFF) == 1) {
            return false;
        }

        if (isAllZero(b, 0, 16)) {
            return true;
        }

        // fc00::/7 unique local (CC ssrfGuard.ts:106-109)
        if ((o0 & 0xFE) == 0xFC) {
            return true;
        }

        // fe80::/10 link-local (CC ssrfGuard.ts:113-122)
        if (o0 == 0xFE && (o1 & 0xC0) == 0x80) {
            return true;
        }

        // WHY: 对齐 CC ssrfGuard.ts:88-125 —— CC 不拦截 64:ff9b::/96 NAT64 与
        // 2001:db8::/32 doc, 原多拦截的 2 段已移除 (语义错位修复).
        return false;
    }

    private boolean isIpv4MappedIpv6(byte[] b) {
        if (b.length != 16) return false;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
    }

    private static boolean isAllZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

}