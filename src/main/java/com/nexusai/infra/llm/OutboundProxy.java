package com.nexusai.infra.llm;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 出站 HTTP 代理（本地开发 / 受限网络下访问 OpenAI、Anthropic 等）。
 *
 * <p><b>为什么要它</b>：JDK 的 {@code java.net.http.HttpClient} 默认只认 JVM 系统属性，
 * OkHttp（openai-java / anthropic-java 的内核）两者都不认。用户挂 Clash 等本地代理时，
 * 不显式接线就会直连 → OpenAI 返回
 * {@code 403 unsupported_country_region_territory}（本仓 2026-11-15 实测）。
 *
 * <p><b>来源优先级</b>：DB {@code settings.http_proxy}（设置页「模型提供商 → 官方卡片 ·
 * 出站线路」，V298）→ 环境变量 {@code HTTPS_PROXY} / {@code https_proxy} /
 * {@code HTTP_PROXY} / {@code http_proxy} → 无（直连）。
 * 支持 {@code http://host:port}、{@code http://user:pass@host:port} 与裸 {@code host:port}。
 *
 * <p>⭐ <b>DB 优先</b>：用户在界面上显式配的比进程环境变量更应生效（环境变量常常是
 * 别的工具顺带设的）；DB 未配置 / 读源未安装（POJO 单测）时才回落环境变量。
 *
 * <p>⛔ <b>本类只服务 LLM</b>：WebSearch / WebFetch 走各自的 {@code settings.proxy}
 * （{@code ProxySelectors}），两条通道互不影响 —— 因此这里绝不读 JVM 系统属性
 * {@code https.proxyHost}（那会改默认 {@code ProxySelector}，web 会静默跟着走）。
 */
public final class OutboundProxy {

    private static final Logger log = LoggerFactory.getLogger(OutboundProxy.class);
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean(false);

    /**
     * [V298] DB 配置读源（由 {@code SettingsService#bridgeHttpProxySource} 在
     * {@code @PostConstruct} 安装 = {@code this::readDbHttpProxy}）。
     *
     * <p>每请求调用、<b>不缓存</b> ⇒ 设置页「记住」后下一次出站即生效（不用重启）。
     * 返回 null / 空串 = DB 未配置 → 回落环境变量。未安装（POJO 单测 / 非 Spring 场景）→ 只读 env。
     */
    private static volatile java.util.function.Supplier<String> dbSource;

    private OutboundProxy() {
    }

    /** [V298] 安装 DB 读源（LLM 专用；见 {@link #endpoint()} 的优先级说明）。 */
    public static void installDbSource(java.util.function.Supplier<String> source) {
        dbSource = source;
        if (log.isInfoEnabled()) {
            log.info("[OutboundProxy] 已安装 DB 出站代理读源（settings.http_proxy；仅 LLM，不含 websearch）");
        }
    }

    /**
     * 解析后的代理地址；无 → null。
     *
     * <p><b>优先级</b>：DB {@code settings.http_proxy}（V298，设置页可改、保存即生效）
     * → 环境变量 → 无（直连）。DB 未安装读源 / 读到 null / 读抛异常 → 回落环境变量。
     *
     * <p>⛔ <b>只读 LLM 专用来源，绝不读 {@code https.proxyHost} 系统属性</b>：
     * 系统属性会改变 JVM 默认 {@code ProxySelector}，而 WebSearch / WebFetch 在不配代理时
     * 不设 selector（{@code ProxySelectors.parseProxySelector(null)} → null）⇒ 会静默跟着走代理。
     * 用户明确要求「ChatGPT 订阅的代理只给 LLM 用，websearch 不能走」。
     * web 侧代理走自己的 {@code settings.proxy}（DB），两条通道互不影响。
     */
    static InetSocketAddress endpoint() {
        java.util.function.Supplier<String> source = dbSource;
        String raw = null;
        String origin = null;
        if (source != null) {
            try {
                String v = source.get();
                if (v != null) {
                    if (v.isBlank()) {
                        // ⭐ 显式直连（设置页「留空保存」）：不得再回落环境变量，
                        //    否则机器上残留 HTTPS_PROXY 会让用户选的「直连」静默变回代理。
                        return null;
                    }
                    raw = v;
                    origin = "设置页";
                }
            } catch (RuntimeException e) {
                // 读源异常不影响出站：回落环境变量（读源失败时宁可沿用昨天的可用链路）
                log.warn("[OutboundProxy] 读 DB 出站代理失败，回落环境变量: {}", e.toString());
            }
        }
        if (raw == null) {
            raw = firstNonBlank(
                System.getenv("HTTPS_PROXY"), System.getenv("https_proxy"),
                System.getenv("HTTP_PROXY"), System.getenv("http_proxy"));
            if (raw != null) {
                origin = "环境变量";
            }
        }
        InetSocketAddress addr = raw == null ? null : parse(raw);
        if (addr != null && ANNOUNCED.compareAndSet(false, true)) {
            log.info("[OutboundProxy] LLM 出站代理已启用 host={} port={}（来源={}；不含 websearch）",
                addr.getHostString(), addr.getPort(), origin);
        }
        return addr;
    }

    /** {@code java.net.http.HttpClient} 用；无代理 → null（调用方保持默认）。 */
    public static ProxySelector selector() {
        InetSocketAddress addr = endpoint();
        return addr == null ? null : new FixedSelector(addr);
    }

    /**
     * {@code java.net.http.HttpClient} 用 —— <b>每次 select 都实时求值</b>的 selector。
     *
     * <p>⭐ 为什么不能直接用 {@link #selector()}：{@code HttpClient.Builder.proxy(...)} 在
     * <b>构造 client 时</b>只取一次；若那时 DB 还没配代理（典型顺序：应用先启动、用户之后
     * 才在设置页填），拿到 null ⇒ 该 client <b>永久直连</b>，之后在界面上怎么改都不生效。
     * 2026-11-16 实测踩过：日志打「已启用 来源=设置页」，上游仍回
     * {@code 403 unsupported_country_region_territory}（curl 同参数带代理是 401）。
     *
     * <p>无代理时返回 {@link Proxy#NO_PROXY} 而不是空列表：空列表会让 JDK 回落默认
     * {@code ProxySelector}（会读系统属性），语义不对。
     */
    public static ProxySelector dynamicSelector() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                InetSocketAddress addr = endpoint();
                return addr == null
                    ? List.of(Proxy.NO_PROXY)
                    : List.of(new Proxy(Proxy.Type.HTTP, addr));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
                log.debug("[OutboundProxy] 代理连接失败 {}: {}", uri, ioe.toString());
            }
        };
    }

    /** OkHttp 用（{@link java.net.Proxy}）；无代理 → null。 */
    public static Proxy httpProxy() {
        InetSocketAddress addr = endpoint();
        return addr == null ? null : new Proxy(Proxy.Type.HTTP, addr);
    }

    /**
     * 按目标地址决定是否走代理（OkHttp）。
     *
     * <p>⭐ <b>只对境外官方域名生效</b>：`OpenAiSdkProvider.buildClient` 是 DeepSeek / Qwen / Azure
     * 等<b>所有 OpenAI 兼容 provider 共用</b>的；若在这里无条件返回代理，给 ChatGPT 订阅配的代理
     * 会把国内模型也一起送进 7897。用户明确要求「不用 ChatGPT 时其他模型不能走代理」。
     *
     * @param urlOrHost baseUrl 或完整 URL（可为 null）
     * @return 命中境外官方域名 → 代理；否则 null（直连）
     */
    public static Proxy httpProxyFor(String urlOrHost) {
        if (!isForeignOfficialHost(hostOf(urlOrHost))) {
            return null;
        }
        return httpProxy();
    }

    /** 按端点决定 ProxySelector（java.net.http）；规则同 {@link #httpProxyFor(String)}。 */
    public static ProxySelector selectorFor(String urlOrHost) {
        if (!isForeignOfficialHost(hostOf(urlOrHost))) {
            return null;
        }
        return selector();
    }

    /** 从 URL 或 host 里取主机名；解析失败 → null。 */
    static String hostOf(String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isBlank()) {
            return null;
        }
        String s = urlOrHost.trim();
        try {
            if (s.contains("://")) {
                String host = URI.create(s).getHost();
                return host == null ? null : host.toLowerCase();
            }
        } catch (RuntimeException ignore) {
            // 非法 URL → 当作 host 继续尝试
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            s = s.substring(0, colon);
        }
        return s.isEmpty() ? null : s.toLowerCase();
    }

    /**
     * 需要代理的域名（按后缀匹配，覆盖子域）。
     *
     * <p>只收「境内直连会被拒或需要跨境」的官方端点；国内厂商（deepseek / dashscope / 智谱…）
     * 一律不在内 ⇒ 直连。新增境外厂商时在这里加一条即可。
     */
    static boolean isForeignOfficialHost(String host) {
        if (host == null) {
            return false;
        }
        return host.equals("chatgpt.com") || host.endsWith(".chatgpt.com")
            || host.equals("openai.com") || host.endsWith(".openai.com")
            || host.equals("anthropic.com") || host.endsWith(".anthropic.com");
    }

    private static InetSocketAddress parse(String raw) {
        String s = raw.trim();
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);   // 去掉 user:pass@（当前不支持认证代理）
        }
        if (s.startsWith("https://")) {
            s = s.substring("https://".length());
        } else if (s.startsWith("http://")) {
            s = s.substring("http://".length());
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.lastIndexOf(':');
        try {
            if (colon > 0 && colon < s.length() - 1) {
                return InetSocketAddress.createUnresolved(
                    s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
            }
            if (!s.isEmpty()) {
                return InetSocketAddress.createUnresolved(s, 80);
            }
        } catch (RuntimeException e) {
            log.warn("[OutboundProxy] 忽略非法代理地址 {}", raw);
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 固定返回同一代理的 selector。 */
    private static final class FixedSelector extends ProxySelector {

        private final List<Proxy> proxies;

        FixedSelector(InetSocketAddress addr) {
            this.proxies = List.of(new Proxy(Proxy.Type.HTTP, addr));
        }

        @Override
        public List<Proxy> select(URI uri) {
            return proxies;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, java.io.IOException e) {
            log.warn("[OutboundProxy] 代理连接失败 {}: {}", sa, e.toString());
        }
    }
}
