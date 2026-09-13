package com.nexusai.domain.provider;

import com.nexusai.infra.llm.DynamicHeaderExpander;
import com.nexusai.infra.llm.ProviderHeaderInjector;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.provider.dto.TestConnectionResponse;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [任务 8 · 修 B3] {@code ProviderService.test(id)}（「测试连接」）必须带上该 provider 的自定义 header。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图而非行为）</b>：{@code ProviderService.test} 是
 * <b>独立实现</b>——它直接 {@code new java.net.http.HttpClient} 并硬编码 header，与两个 SDK provider 的
 * {@code buildClient} <b>无任何代码共享</b>（规范 §2.2 的 B3）。所以「提供商自定义 header 已接进真实
 * LLM 链路」这件事，对「测试连接」<b>一格都不生效</b>。危害是<b>用户可见且误导</b>：
 * 用户明明配了自建网关所需的 header，点「测试连接」却报失败（或反过来，在没有该 header 时从缓存/代理
 * 拿到 200 而误报成功）——而真实推理链路是好的。本类把「测试连接与真实链路带同一组 header」钉死为
 * 可执行契约。
 *
 * <p><b>取证方式（强取证，不是看返回值）</b>：JDK 自带 {@link HttpServer} 起本地桩，让
 * {@code test(id)} 真打向它，桩内抓 {@code getRequestHeaders()}——断言的是<b>真实出站 HTTP 的请求头</b>，
 * 而非 SDK/方法的返回值。桩回最小合法响应（200 + {@code {"data":[]}}），故请求<b>走完了整个调用</b>，
 * 不是「头到了但调用抛错」的弱取证。范式照抄同包的
 * {@link ProviderTestSessionIdProbeTest}（T3 探针，{@code :73-87} 桩 + {@code :90-108} 装 mock）。
 *
 * <p><b>⚠️ 抓的是「同一 header 名的全部值」而非首值</b>：{@code HttpRequest.Builder.header()} 是
 * <b>追加</b>语义（JDK 25.0.3 桩实测：{@code header("A","x"); header("A","y")} → 线上发出 {@code [x, y]}，
 * 两个值都在；只有 {@code setHeader()} 才是替换）。若只断言首值，就会<b>漏掉「脏值和内置值并列发出」</b>
 * 这一种真实存在的坏结局——那正是「内置凭据被顶掉」的另一种形态。故本类抓 {@code List<String>}。
 *
 * <p><b>两条关键语义（规范 §6.7 定案，本类负责钉住）</b>：
 * <ol>
 *   <li><b>sessionId 恒传 {@code null}</b>（绝不从任何 ambient 会话槽读取）：原隐患是「该线程的
 *       会话载体可能是<b>上一个请求残留的、别的会话的 sessionId</b>（Tomcat 线程复用；本仓
 *       {@code MemoryController:143} / {@code TaskController:145} / {@code TeamController:95} 三处
 *       连 ambient 写点均无清理）」，而 {@code ProviderController.test(:38)} 的签名是
 *       {@code test(@PathVariable String id)}——<b>无 sessionId 入参</b>。读它 = 把 <b>A 会话的亲和 id
 *       发给 B 会话的测试连接请求</b>。故占位符一律落
 *       {@link DynamicHeaderExpander#STATIC_FALLBACK}（{@code nexusai-static}），零损失零风险。
 *       <b>[批 3c]</b>：ambient 会话载体（裸 MDC 会话槽）已整类删除 ⇒ 「残留」这一诱饵已无法构造，
 *       但「必须落兜底常量」的断言仍钉住 {@code apply} 第三参恒为 {@code null} 这个接缝。</li>
 *   <li><b>保留头冲突以内置为准</b>：内置 {@code Authorization} 不得被用户配置顶掉。</li>
 * </ol>
 *
 * <p><b>⚠️ 反向实验的判读记录（本类的守护力边界，务必先读再改）</b>：
 * <ul>
 *   <li><b>【有效·单点】删掉 {@code ProviderHeaderInjector.apply(...)} 整段</b> →
 *       {@link #customHeadersAreSentOnTestConnection} 与
 *       {@link #sessionIdMustAlwaysFallBackToStaticConstant} <b>红</b>。</li>
 *   <li><b>【有效·单点】把 {@code apply} 的第三参从 {@code null} 改成任一 ambient 会话读取</b>
 *       → {@link #sessionIdMustAlwaysFallBackToStaticConstant} <b>红</b>（该用例断言占位符落兜底常量；
 *       {@link #customHeadersAreSentOnTestConnection} 在同样的读取下也会一致通过 ⇒ 需两条用例并存以
 *       分别钉住「静态头照发」与「占位符落常量」两件事）。
 *       <br><b>[批 3c] 补偿说明</b>：批 3c 删除了 ambient 会话载体 ⇒ 「残留值被采纳」这一形态在结构上
 *       已不可能；本用例当前钉的是「第三参恒 null」这个接缝本身，鉴别力来自「若有人把接缝改成任何
 *       非 null 来源（显式或 ambient），断言即红」。</li>
 *   <li><b>【有效·必须多点】{@code Authorization} 冲突</b>：<b>只反转设置顺序 → 恒绿，这是预期不是缺口</b>
 *       ——因为 {@code authorization} 在 {@code DynamicHeaderExpander} 的禁止清单里，脏数据在
 *       {@code apply} 内部就被过滤掉，<b>冲突方压根没进来</b>，顺序成了空操作。按本批已立的 §9.6.1
 *       判读规则（「被测实现对某输入有两处以上独立守卫时，单点变异必然绿」），必须<b>同时</b>做两处
 *       变异才可检出：<b>(a) 把 {@code authorization} 从禁止清单里删掉 + (b) 反转设置顺序</b> →
 *       {@link #builtinAuthorizationNotOverriddenByDirtyUserAuthorization} <b>红</b>。
 *       <br>实测补记（2026-09-13）：<b>(a) 单独一处就足以让它红</b>（脏值 + 内置值并列发出，
 *       本类断言「只许有内置值」故红）。这说明<b>真正兜住这条的是禁止清单，不是设置顺序</b>——
 *       顺序本身不可被任何变异检出。
 *       <br><b>2026-09-13 裁定后的落地</b>：既然「顺序」保不住内置赢，生产代码已改用
 *       {@code setHeader}（**替换**语义）；又因该行的效果在可达路径上无法用行为测试证伪，
 *       改为以 {@link #builtinAuthorizationMustUseSetHeader} 的**源码级断言**守护。</li>
 * </ul>
 *
 * @since 2026-09-13 provider-custom-headers 任务 8
 */
@DisplayName("[任务 8] 测试连接带上提供商自定义 header（修 B3）")
class ProviderTestConnectionHeadersTest {

    private static final Logger log = LoggerFactory.getLogger(ProviderTestConnectionHeadersTest.class);

    private static final String PROVIDER_ID = "prov-hdr-1";
    private static final String ENCRYPTED_KEY = "enc-key";
    private static final String PLAIN_KEY = "k";

    /** 存量脏数据里的假凭据（用于验证内置 Authorization 不被顶掉）。 */
    private static final String HIJACKED_AUTH = "Bearer HIJACKED-STALE-DATA";

    private HttpServer server;

    /** 桩抓到的最新一次请求的全部 header（键小写 → 该名的**全部值**；读在测试线程、写在桩线程 → 原子引用）。 */
    private final AtomicReference<Map<String, List<String>>> capturedHeaders =
        new AtomicReference<>(Map.of());

    /**
     * 把 gate 显式钉为 {@code true}。
     *
     * <p>WHY：gate 是<b>进程级静态状态</b>（{@code ProviderHeaderInjector.installGateSource}），
     * 同 JVM 内其它测试类（{@code ProviderHeaderInjectorTest}）会安装/卸载它，用例顺序一变就可能
     * 影响本类。显式安装既保证确定性，也复现生产语义（V72 列默认值 1 = 开）。
     * 注意本类**不靠 gate 才能断言兜底常量**——{@code gate=false} 时同样落常量；钉住它是为了让
     * 「残留 MDC 不得泄漏」那条变异可被确定性地检出（gate 关时会误判为绿）。
     */
    @BeforeEach
    void pinGateOn() {
        ProviderHeaderInjector.installGateSource(() -> true);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        // 复位静态读源，避免泄漏到其它测试类（与 ProviderHeaderInjectorTest.resetGateSource 同款）。
        ProviderHeaderInjector.installGateSource(null);
    }

    /** 起桩：{@code GET /models} → 200 + 空 JSON 体；同时把请求头快照进 {@link #capturedHeaders}。 */
    private String startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            Map<String, List<String>> snapshot = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (k != null && v != null && !v.isEmpty()) {
                    snapshot.put(k.toLowerCase(Locale.ROOT), new ArrayList<>(v));
                }
            });
            capturedHeaders.set(snapshot);
            log.info("[任务 8 桩] 收到 {} {} · header 数={} · keys={}",
                exchange.getRequestMethod(), exchange.getRequestURI(), snapshot.size(), snapshot.keySet());

            byte[] bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 装配 ProviderService（mock mapper + crypto），provider 指向本地桩。
     *
     * <p>{@code extraHeaders} 经 {@link ProviderService#serializeHeaders} 转成 JSON 字符串落库形态
     * ——即模拟真实的 {@code providers.extra_headers} 列内容（**注意 {@code Provider.extraHeaders} 是
     * JSON String，不是 Map**，这个同名不同型是规范 §2.2 反复强调的坑）。
     */
    private ProviderService newService(String baseUrl, Map<String, String> extraHeaders) {
        ProviderRecord r = new ProviderRecord();
        r.setId(PROVIDER_ID);
        r.setName("hdr");
        r.setType("anthropic");
        r.setBaseUrl(baseUrl);
        r.setApiKeyEncrypted(ENCRYPTED_KEY);
        r.setExtraHeaders(ProviderService.serializeHeaders(extraHeaders));

        ProviderMapper providerMapper = Mockito.mock(ProviderMapper.class);
        Mockito.when(providerMapper.selectOneById(PROVIDER_ID)).thenReturn(r);

        CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);
        Mockito.when(cryptoUtil.decrypt(ENCRYPTED_KEY)).thenReturn(PLAIN_KEY);

        ProviderService svc = new ProviderService();
        ReflectionTestUtils.setField(svc, "providerMapper", providerMapper);
        ReflectionTestUtils.setField(svc, "cryptoUtil", cryptoUtil);
        return svc;
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 主契约：自定义 header 必须上到出站请求
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("测试连接必须带上用户配置的自定义 header（B3：这条路原先与 SDK 注入点零共享）")
    void customHeadersAreSentOnTestConnection() throws IOException {
        ProviderService svc = newService(startStub(), Map.of(
            "x-opencode-session", DynamicHeaderExpander.SESSION_ID_TOKEN,
            "x-tenant", "acme"));

        TestConnectionResponse resp = svc.test(PROVIDER_ID);

        assertThat(resp.ok()).as("桩返回 200 → 测试连接必须成功（证明请求真的走完了整个调用）").isTrue();
        Map<String, List<String>> got = capturedHeaders.get();
        assertThat(got.get("x-tenant"))
            .as("静态自定义 header 必须出现在出站请求上，否则用户点「测试连接」会得到误导性结果")
            .containsExactly("acme");
        assertThat(got.get("x-opencode-session"))
            .as("占位符必须被展开（sessionId 恒传 null → 落兜底常量），绝不能把字面量 ${session_id} 发上线")
            .containsExactly(DynamicHeaderExpander.STATIC_FALLBACK);
    }

    @Test
    @DisplayName("未配置自定义 header 时行为不变（apply 的空短路不得影响内置 Authorization / Accept）")
    void noCustomHeadersConfigured_behavesAsBefore() throws IOException {
        ProviderService svc = newService(startStub(), null);

        TestConnectionResponse resp = svc.test(PROVIDER_ID);

        assertThat(resp.ok()).as("桩返回 200 → 必须成功").isTrue();
        Map<String, List<String>> got = capturedHeaders.get();
        assertThat(got.get("authorization")).as("内置 Authorization 照旧发出，且只有一个值")
            .containsExactly("Bearer " + PLAIN_KEY);
        assertThat(got.get("accept")).as("内置 Accept 照旧发出").contains("application/json");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 保留头冲突：内置 Authorization 必须赢
    // ════════════════════════════════════════════════════════════════════

    /**
     * 存量脏数据场景：库里的 {@code extra_headers} 含 {@code Authorization}（V72 之前手写序列化时代
     * 的旧值 / 绕过 REST 直改 DB）。写侧 D5 已拒新写入，但**脏数据必须仍被挡住**。
     *
     * <p><b>断言为什么是「只许有内置值」而不是「首值是内置值」</b>：{@code header()} 是追加语义，
     * 两处都设就会发出两个 Authorization。只查首值会漏掉「脏值与内置值并列上线」这种坏结局。
     *
     * <p><b>本用例的变异判读</b>：只反转设置顺序 → <b>恒绿</b>（禁止清单已把 {@code authorization}
     * 过滤掉，冲突方压根没进来）。要检出必须<b>多点同时变异</b>：删掉清单里的 {@code authorization}
     * + 反转顺序 → 红。实测补记：删清单那**一处**单独也足以致红 ⇒ <b>真正兜住的是禁止清单</b>，
     * 设置顺序本身不可被任何变异检出（故生产已改用 {@code setHeader}，见
     * {@link #builtinAuthorizationMustUseSetHeader}）。
     */
    @Test
    @DisplayName("内置 Authorization 不得被存量脏数据顶掉（多点变异才可检出，见类注释）")
    void builtinAuthorizationNotOverriddenByDirtyUserAuthorization() throws IOException {
        ProviderService svc = newService(startStub(), Map.of("Authorization", HIJACKED_AUTH));

        TestConnectionResponse resp = svc.test(PROVIDER_ID);

        assertThat(resp.ok()).as("桩返回 200 → 必须成功").isTrue();
        List<String> authValues = capturedHeaders.get().get("authorization");
        assertThat(authValues)
            .as("内置 Authorization 必须赢：出站请求上**只能有内置值**（脏值应在注入侧被禁止清单跳过并 warn）")
            .containsExactly("Bearer " + PLAIN_KEY);
        assertThat(authValues)
            .as("脏凭据绝不得出现在出站请求上（哪怕是并列的第二个值）")
            .doesNotContain(HIJACKED_AUTH);
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. sessionId 恒 null —— 线程残留 MDC 绝不得泄漏
    // ════════════════════════════════════════════════════════════════════

    /**
     * <b>源码级守卫</b>（本地无行为测试可写，理由见下）：内置 {@code Authorization} 必须用
     * {@code setHeader}（替换语义），不得回退成 {@code header}（**追加**语义）。
     *
     * <p><b>为什么没有行为测试</b>：{@code header()} 与 {@code setHeader()} 的差别只在「同名冲突」
     * 时才可观测，而唯一的冲突来源（脏 {@code Authorization}）在上一步 {@code apply} 内部就被禁止清单
     * 过滤掉了 —— <b>可达路径不可构造</b>，行为测试必然恒绿（本类已实跑确认：单点变异顺序亦恒绿）。
     *
     * <p>但这不是「无需守护」：它是一个**真实的行为差异**，且在「禁止清单被放宽」的那天会立刻变成
     * 凭据劫持。故照 {@code ProviderSessionIdWiringGuardTest} 的路子做源码级断言，
     * 防止未来有人把 {@code setHeader} 「简化」回 {@code header}。
     */
    @Test
    @DisplayName("源码级守卫：内置 Authorization 必须用 setHeader（替换），不得回退成 header（追加）")
    void builtinAuthorizationMustUseSetHeader() throws IOException {
        String src = Files.readString(
                Path.of("src/main/java/com/nexusai/domain/provider/ProviderService.java"))
            .replace("\r\n", "\n");

        assertThat(src)
            .as("必须用 setHeader（替换语义）：实测 header() 是追加，同名两次会并列发出，保不住内置凭据")
            .contains("rb.setHeader(\"Authorization\"");
        assertThat(src)
            .as("不得回退成 rb.header(\"Authorization\")：那会让「内置的赢」名存实亡（脏值与真凭据并列上线）")
            .doesNotContain("rb.header(\"Authorization\"");
    }


    /**
     * 占位符 {@code ${session_id}} 必须<b>恒落</b>兜底常量（{@code apply} 第三参恒传 {@code null}）。
     *
     * <p>原用例模拟 Tomcat 线程复用：本线程先服务过别的会话的请求，残留了<b>别的会话</b>的 sessionId，
     * 随后被复用来跑「测试连接」；若实现读该残留值，用户会把 <b>A 会话的亲和 id 发给 B 会话的请求</b>
     * ——不报错、不落常量，静默串话。
     *
     * <p><b>[批 3c] 语义消失（已登记待裁定）</b>：ambient 会话载体（裸 MDC 会话槽）已整类删除
     * ⇒ 「写残留值」的装置与本用例的「前置条件」断言（断言残留值确实存在）已无法构造并已删除。
     * <b>下面的断言文本原样保留、未改弱</b>：它仍钉住「测试连接的 sessionId 恒为 null → 占位符恒落
     * {@code STATIC_FALLBACK}」这条真实契约；如果将来有人把该接缝改成任何非 null 来源（实测注入的
     * 会话或新 ambient 槽），本用例会立刻变红。
     */
    @Test
    @DisplayName("测试连接的 sessionId 恒传 null → ${session_id} 必须落兜底常量（不得泄漏任何会话 id）")
    void sessionIdMustAlwaysFallBackToStaticConstant() throws IOException {
        ProviderService svc = newService(startStub(), Map.of(
            "x-opencode-session", DynamicHeaderExpander.SESSION_ID_TOKEN));

        // [批 3c] 语义消失：原此处 setSession("sess-STALE-FROM-PREVIOUS-REQUEST") 造「别的会话残留」诱饵
        //   并断言诱饵存在；该 ambient 会话槽（连同其写点）已整类删除 ⇒ 装置与前置条件断言删除。
        TestConnectionResponse resp = svc.test(PROVIDER_ID);

        assertThat(resp.ok()).as("桩返回 200 → 必须成功").isTrue();
        assertThat(capturedHeaders.get().get("x-opencode-session"))
            .as("必须落兜底常量：把任何会话 id 发出去 = 静默串话/无谓泄漏，比 null 更坏")
            .containsExactly(DynamicHeaderExpander.STATIC_FALLBACK);
    }
}
