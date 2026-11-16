package com.nexusai.infra.llm;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本机 HTTP 代理探测 / 连通性实测（「出站线路」区块的「自动检测」与「记住前实测」）。
 *
 * <p><b>为什么必须放后端</b>：浏览器 / Tauri WebView 不能主动连本机端口
 * （混合内容、CORS、无 raw socket），只有后端能做 TCP 与代理握手。
 *
 * <p><b>为什么「端口开着」不等于「是可用代理」</b>：7890 这类端口常被 Clash 的 mixed
 * 模式占用，但它同时接受 SOCKS5；按 HTTP 代理去连会握手失败。还有端口被别的服务
 * （IDE、开发服务器）占用的情况。故判据 = <b>真的经该代理取到一次 HTTPS 204</b>：
 * <ol>
 *   <li>用 {@link ProxySelector#of(InetSocketAddress)} 把该端口当 <b>HTTP 代理</b>；</li>
 *   <li>请求 {@code https://www.gstatic.com/generate_204}；</li>
 *   <li>2xx/3xx 才算通过（SOCKS、非代理服务、只接受 CONNECT 但转发不通的都会失败）。</li>
 * </ol>
 *
 * <p>⛔ 探测范围仅 {@code 127.0.0.1}：不扫局域网、不做端口段扫描——那是端口扫描器行为，
 * 既慢又可能触发对方告警。
 *
 * <p>⛔ 每个 {@code HttpClient} 用<b>自己的</b> {@link ProxySelector}，不碰 JVM 默认 selector、
 * 不读 {@code https.proxyHost} 系统属性（否则探测会污染正在使用的出站链路）。
 */
public final class ProxyProbe {

    private ProxyProbe() {
    }

    private static final Logger log = LoggerFactory.getLogger(ProxyProbe.class);

    /**
     * 常见本机 HTTP 代理端口，按「更可能是 HTTP 代理」排序：
     * Clash 7897 / 7890、v2rayN 10809 / 10808、通用 1081 / 1080 / 8888 / 8080。
     */
    static final List<Integer> CANDIDATE_PORTS = List.of(7897, 7890, 10809, 10808, 1081, 1080, 8888, 8080);

    /** 验证目标：204 无正文，最轻量且全球可达。 */
    private static final String CHECK_URL = "https://www.gstatic.com/generate_204";

    /** 单端口连接超时；8 个端口并发 ⇒ 整体约 1 秒内出结果。 */
    private static final int CONNECT_TIMEOUT_MS = 350;

    /** 单端口请求（含 CONNECT 与 TLS）超时。 */
    private static final int REQUEST_TIMEOUT_MS = 1200;

    /** 探测命中项。 */
    public record Candidate(int port, long latencyMs) {
    }

    /** 实测结果；{@code ok=false} 时 {@code error} 一定非空（给界面直接显示）。 */
    public record TestResult(boolean ok, long latencyMs, String error) {
    }

    /**
     * 并发探测本机常见代理端口，返回<b>真实可转发 HTTPS</b> 的那些，按延迟升序。
     *
     * @return 命中列表（可能为空）；永不抛
     */
    public static List<Candidate> probeLocal() {
        long started = System.nanoTime();
        List<Candidate> found = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Candidate>> futures = new ArrayList<>(CANDIDATE_PORTS.size());
            for (int port : CANDIDATE_PORTS) {
                futures.add(pool.submit(() -> tryPort(port)));
            }
            for (Future<Candidate> f : futures) {
                try {
                    Candidate c = f.get(REQUEST_TIMEOUT_MS + 400L, TimeUnit.MILLISECONDS);
                    if (c != null) {
                        found.add(c);
                    }
                } catch (Exception perPortMiss) {
                    // 单端口失败/超时不影响其余端口
                }
            }
        } catch (RuntimeException e) {
            log.warn("[ProxyProbe] 探测执行异常（按「未找到」处理）: {}", e.toString());
        }
        found.sort(Comparator.comparingLong(Candidate::latencyMs));
        if (log.isInfoEnabled()) {
            log.info("[ProxyProbe] 本机代理探测完成：候选端口={} 命中={} 耗时={}ms",
                CANDIDATE_PORTS.size(), found.size(), (System.nanoTime() - started) / 1_000_000);
        }
        return found;
    }

    /** 单个端口：能通过它取到 204 才算命中。 */
    private static Candidate tryPort(int port) {
        long t0 = System.nanoTime();
        TestResult r = test("http://127.0.0.1:" + port);
        if (!r.ok()) {
            return null;
        }
        long ms = Math.max(1L, (System.nanoTime() - t0) / 1_000_000);
        return new Candidate(port, ms);
    }

    /**
     * 实测一条代理是否可用（按 HTTP 代理语义）。
     *
     * @param proxyUrl 形如 {@code http://127.0.0.1:7897}（{@code host:port} 也接受）
     * @return 结果；失败时 {@code error} 是可直接展示的短句
     */
    public static TestResult test(String proxyUrl) {
        long t0 = System.nanoTime();
        InetSocketAddress addr = parse(proxyUrl);
        if (addr == null) {
            return new TestResult(false, 0, "代理地址写法不对，应为 http://host:port");
        }
        HttpClient client = HttpClient.newBuilder()
            .proxy(ProxySelector.of(addr))
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(CHECK_URL))
            .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
            .GET()
            .build();
        try {
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int code = resp.statusCode();
            if (code >= 200 && code < 400) {
                return new TestResult(true, ms, null);
            }
            return new TestResult(false, ms, "代理回了 HTTP " + code);
        } catch (java.net.http.HttpTimeoutException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new TestResult(false, ms, "连接超时（" + REQUEST_TIMEOUT_MS + "ms）");
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new TestResult(false, ms, shortReason(e));
        }
    }

    /** 把异常压成一句给人看的话（界面直接显示，不塞整段堆栈）。 */
    private static String shortReason(Exception e) {
        String msg = e.getMessage();
        String type = e.getClass().getSimpleName();
        if (msg == null || msg.isBlank()) {
            return type;
        }
        String oneLine = msg.replace('\n', ' ');
        return oneLine.length() > 120 ? type + ": " + oneLine.substring(0, 120) + "…" : type + ": " + oneLine;
    }

    /**
     * 解析 {@code http://host:port} / {@code host:port}。
     *
     * <p>只支持无认证代理（与 {@link OutboundProxy} 的口径一致：当前不实现 Proxy-Authorization）。
     *
     * @return 解析失败 → null
     */
    static InetSocketAddress parse(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return null;
        }
        String s = proxyUrl.trim();
        if (s.startsWith("http://")) {
            s = s.substring("http://".length());
        } else if (s.startsWith("https://")) {
            s = s.substring("https://".length());
        }
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            return null;
        }
        String host = s.substring(0, colon).trim();
        String portText = s.substring(colon + 1).trim();
        if (host.isEmpty()) {
            return null;
        }
        try {
            int port = Integer.parseInt(portText);
            if (port <= 0 || port > 65535) {
                return null;
            }
            return new InetSocketAddress(host, port);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
