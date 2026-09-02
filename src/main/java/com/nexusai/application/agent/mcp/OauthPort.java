package com.nexusai.application.agent.mcp;

import java.util.Objects;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth redirect port helpers · 对齐 CC services/mcp/oauthPort.ts.
 *
 * <p>L1 语义: OAuth loopback redirect 端口分配 — RFC 8252 Section 7.3 (loopback redirect
 *            匹配任意端口,只匹配 path); Windows dynamic port range 49152-65535 保留;
 *            默认 3118 fallback; 环境变量 MCP_OAUTH_CALLBACK_PORT 优先.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: REDIRECT_PORT_RANGE (windows: 39152-49151; 其他: 49152-65535);
 *       REDIRECT_PORT_FALLBACK=3118; buildRedirectUri(port) → String;
 *       findAvailablePort() → int; getMcpOAuthCallbackPort() → Optional&lt;Integer&gt;.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — env MCP_OAUTH_CALLBACK_PORT=12345 → 12345;
 *       否则随机选取 range 端口 → 可用; fallback 3118 → 3118.</li>
 *   <li><b>A3</b>: 状态 — port 范围 (min/max); 注入式 IntPredicate 测试端口可用性.</li>
 *   <li><b>A4</b>: env 解析失败 → 0 → undefined; random port 占用 → 跳过; fallback 也占用 → throw.</li>
 *   <li><b>A5</b>: 真实场景 — MCP OAuth flow 启动时分配一个可用端口作为 redirect URI.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS process.env → Java Supplier (注入);
 *                    TS createServer().listen → Java IntPredicate 测试端口可用;
 *                    TS Math.random() → Java Random.
 */
public final class OauthPort {

    private static final Logger log = LoggerFactory.getLogger(OauthPort.class);

    public static final int REDIRECT_PORT_FALLBACK = 3118;

    public record PortRange(int min, int max) {}

    private final boolean isWindows;
    private final Supplier<String> envSupplier;
    private final IntPredicate portAvailableTester;
    private final Random random;

    public OauthPort(boolean isWindows, Supplier<String> envSupplier,
            IntPredicate portAvailableTester, Random random) {
        this.isWindows = isWindows;
        this.envSupplier = Objects.requireNonNull(envSupplier);
        this.portAvailableTester = Objects.requireNonNull(portAvailableTester);
        this.random = Objects.requireNonNull(random);
    }

    public OauthPort() {
        this(false, () -> System.getenv("MCP_OAUTH_CALLBACK_PORT"), p -> true, new Random());
    }

    /** CC REDIRECT_PORT_RANGE. */
    public PortRange getRedirectPortRange() {
        return isWindows ? new PortRange(39152, 49151) : new PortRange(49152, 65535);
    }

    /** CC buildRedirectUri. */
    public String buildRedirectUri(int port) {
        return "http://localhost:" + port + "/callback";
    }

    public String buildRedirectUri() {
        return buildRedirectUri(REDIRECT_PORT_FALLBACK);
    }

    /** CC getMcpOAuthCallbackPort. */
    public Integer getMcpOAuthCallbackPort() {
        String raw = envSupplier.get();
        if (raw == null || raw.isEmpty()) return null;
        try {
            int port = Integer.parseInt(raw);
            return port > 0 ? port : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** CC findAvailablePort — 主链. */
    public int findAvailablePort() {
        Integer configured = getMcpOAuthCallbackPort();
        if (configured != null) return configured;

        PortRange range = getRedirectPortRange();
        int span = range.max() - range.min() + 1;
        int maxAttempts = Math.min(span, 100);

        for (int i = 0; i < maxAttempts; i++) {
            int port = range.min() + random.nextInt(span);
            if (portAvailableTester.test(port)) {
                return port;
            }
        }

        if (portAvailableTester.test(REDIRECT_PORT_FALLBACK)) {
            return REDIRECT_PORT_FALLBACK;
        }
        throw new RuntimeException("No available ports for OAuth redirect");
    }
}