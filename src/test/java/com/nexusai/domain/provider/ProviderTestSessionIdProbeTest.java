package com.nexusai.domain.provider;

import com.nexusai.common.RequestContext;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.provider.dto.TestConnectionResponse;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [T3 前置实测探针] {@code ProviderService.test(id)} 所在线程上
 * {@code RequestContext.sessionId()}（= MDC）是 null 还是有值？
 *
 * <p><b>WHY（规则九·验证意图）</b>：设计
 * {@code docs/zjkycode/specs/2026-09-12-provider-custom-headers-design.md §6.7} 要求
 * 「测试连接」也带上 provider 的 {@code extraHeaders}，其中 {@code ${session_id}} 占位符需要一个
 * sessionId。若该线程上 MDC **有值**，则「测试连接」能发出真实的会话亲和头；若是 null，
 * 只能落 {@code STATIC_FALLBACK}（D4 语义，天然安全）。**本探针只回答这个问题**，
 * 不改变设计。
 *
 * <p><b>取证方式与限制</b>：本类直接调用真实的 {@code providerService.test(id)}（mock 掉
 * {@link ProviderMapper} 与 {@link CryptoUtil}，用 JDK {@link HttpServer} 桩充当
 * {@code GET {baseUrl}/models}），因此测的是**调用线程**上的 MDC 原值。
 *
 * <p>两种情形：
 * <ol>
 *   <li><b>clean</b>：调用线程不带任何 MDC → 模拟「全新 Tomcat 工作线程」。</li>
 *   <li><b>leaked</b>：调用线程预置一个「上一个请求留下的 sessionId」→ 模拟 Tomcat 线程复用
 *       残留（本仓 {@code MemoryController:143} / {@code TaskController:145} /
 *       {@code TeamController:95} 都调 {@code RequestContext.setSession(...)} 但**均无
 *       {@code RequestContext.clear()}**，而 {@code CommandController:363} 的注释自证
 *       「防 Tomcat 线程复用残留（无 Filter 写 MDC）」——即该残留机制在本仓是被承认存在的）。</li>
 * </ol>
 *
 * <p><b>未覆盖</b>：未启动真实 Tomcat 观察生产线程复用。生产上的取值 = 「通常是 null，
 * 但可能是上一个请求残留的**别的会话**的 sessionId」。见测试报告。
 *
 * @since 2026-09-12 provider-custom-headers 前置实测
 */
@DisplayName("[T3 探针] ProviderService.test 线程的 MDC")
class ProviderTestSessionIdProbeTest {

    private static final Logger log = LoggerFactory.getLogger(ProviderTestSessionIdProbeTest.class);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        RequestContext.clear();
    }

    /** 起桩：{@code GET /models} → 200 + 空 JSON 体。 */
    private String startStub() throws IOException {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
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

    /** 装配 ProviderService（mock mapper + crypto），provider 指向本地桩。 */
    private ProviderService newService(String baseUrl) {
        ProviderRecord r = new ProviderRecord();
        r.setId("prov-probe-1");
        r.setName("probe");
        r.setType("anthropic");
        r.setBaseUrl(baseUrl);
        r.setApiKeyEncrypted("enc-key");

        ProviderMapper providerMapper = Mockito.mock(ProviderMapper.class);
        Mockito.when(providerMapper.selectOneById("prov-probe-1")).thenReturn(r);

        CryptoUtil cryptoUtil = Mockito.mock(CryptoUtil.class);
        Mockito.when(cryptoUtil.decrypt("enc-key")).thenReturn("k");

        ProviderService svc = new ProviderService();
        ReflectionTestUtils.setField(svc, "providerMapper", providerMapper);
        ReflectionTestUtils.setField(svc, "cryptoUtil", cryptoUtil);
        return svc;
    }

    @Test
    @DisplayName("T3-A · 干净线程（无 MDC）：test() 内 RequestContext.sessionId() == null")
    void t3a_cleanThread_mdcIsNull() throws IOException {
        String baseUrl = startStub();
        ProviderService svc = newService(baseUrl);

        RequestContext.clear(); // 模拟全新 Tomcat 工作线程（无任何残留）
        String beforeCall = RequestContext.sessionId();

        TestConnectionResponse resp = svc.test("prov-probe-1");

        System.out.println("[T3 探针] T3-A 线程=" + Thread.currentThread().getName()
            + " 调用前 sessionId=" + beforeCall
            + " → test() 返回 ok=" + resp.ok() + " message=" + resp.message());
        log.info("[T3 探针] T3-A 线程={} 调用前 sessionId={} test() 返回 ok={} message={}",
            Thread.currentThread().getName(), beforeCall, resp.ok(), resp.message());

        assertThat(resp.ok()).as("桩返回 200 → 测试连接应成功（证明 test() 真的跑到了底）").isTrue();
        assertThat(RequestContext.sessionId()).as("干净线程上 MDC 必须为空").isNull();
    }

    @Test
    @DisplayName("T3-B · 线程残留 MDC（模拟复用）：test() 读到的是【上一个请求的】sessionId")
    void t3b_leakedThread_mdcCarriesStaleSessionId() throws IOException {
        String baseUrl = startStub();
        ProviderService svc = newService(baseUrl);

        // 模拟「同一个 Tomcat 工作线程先服务过 /memory/files?sessionId=sess-STALE，
        //        该端点 setSession 但从不 clear」→ 线程残留别的会话 id
        RequestContext.setSession("sess-STALE-FROM-PREVIOUS-REQUEST");

        TestConnectionResponse resp = svc.test("prov-probe-1");

        String observed = RequestContext.sessionId();
        System.out.println("[T3 探针] T3-B 线程=" + Thread.currentThread().getName()
            + " test() 执行时 sessionId=" + observed + "（应为上个请求残留值，非本次请求）"
            + " test() 返回 ok=" + resp.ok());
        log.info("[T3 探针] T3-B 线程={} test() 执行时 sessionId={}（残留，非本请求）",
            Thread.currentThread().getName(), observed);

        assertThat(resp.ok()).as("桩返回 200 → test() 真的跑到了底").isTrue();
        assertThat(observed)
            .as("线程残留的 sessionId 会被 test() 原样读到（无 clear 机制）——这正是危害所在")
            .isEqualTo("sess-STALE-FROM-PREVIOUS-REQUEST");
    }

    @Test
    @DisplayName("T3-C · 静态证据：ProviderController.test 无 sessionId 入参、无 Filter/Interceptor 写 MDC")
    void t3c_staticEvidence_noMdcSourceOnProviderTestPath() {
        // 本用例是「静态取证」：把源码事实写成断言，防止未来有人加了 Filter 而本结论悄悄失效。
        String controllerSrc = readSource(
            "src/main/java/com/nexusai/apis/provider/ProviderController.java");
        assertThat(controllerSrc)
            .as("ProviderController.test 只有 @PathVariable String id —— 不接收 sessionId")
            .contains("public TestConnectionResponse test(@PathVariable String id)");
        assertThat(controllerSrc)
            .as("ProviderController 全文不得出现 RequestContext.setSession（本探针结论的前提之一）")
            .doesNotContain("RequestContext.setSession");

        System.out.println("[T3 探针] T3-C 静态取证通过：ProviderController 无 sessionId 入参、无 MDC 写点");
        log.info("[T3 探针] T3-C 静态取证通过：ProviderController 无 sessionId 入参、无 MDC 写点");
    }

    /** 读源码文件（相对 backend 模块根，即 surefire 默认 user.dir）。 */
    private static String readSource(String relativePath) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(relativePath));
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + relativePath
                + "（user.dir=" + System.getProperty("user.dir") + "）", e);
        }
    }
}
