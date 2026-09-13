package com.nexusai.domain.provider;

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
 * [T3 前置实测探针 · 残留] {@code ProviderService.test(id)} 的会话来源。
 *
 * <p><b>WHY（规则九·验证意图）</b>：设计
 * {@code docs/zjkycode/specs/2026-09-12-provider-custom-headers-design.md §6.7} 要求
 * 「测试连接」也带上 provider 的 {@code extraHeaders}，其中 {@code ${session_id}} 占位符需要一个
 * sessionId。T3 探针当年的结论是：该线程上的 ambient 会话槽**可能读到上个请求残留的、别的会话的
 * id** ⇒ 定案「测试连接**恒传 null**」→ 占位符落 {@code STATIC_FALLBACK}（D4 语义，天然安全）。
 *
 * <p><b>[批 3c] 语义消失（已登记待裁定）</b>：原 T3 探针用它来测「调用线程上 ambient 会话原值」的
 * 那个载体（裸 MDC 会话槽）已随本批<b>整类删除</b>：本仓再无任何 ambient 会话写点，探针问题
 * 「该线程上有没有残留会话」因此<b>在结构上恒为「没有」</b>。于是：
 * <ul>
 *   <li>T3-A（干净线程 → 值必须为 null）：观察装置与断言删除，只保留「test() 真的跑到桩」的端到端断言。</li>
 *   <li>T3-B（线程预置残留值 → test() 会原样读到它）：<b>整条用例删除</b>（装置与断言均无法构造；
 *       该危害的落地防线现由 {@code ProviderTestConnectionHeadersTest#sessionIdMustAlwaysFallBackToStaticConstant}
 *       以「占位符恒落兜底常量」的可执行断言守住）。</li>
 *   <li>T3-C（静态取证）：保留并<b>加强</b>——原「源码不得出现 ambient 会话写点」已随载体删除成为空断言，
 *       改为「ProviderController 全文不得出现 sessionId」。</li>
 * </ul>
 *
 * <p><b>取证方式与限制</b>：本类直接调用真实的 {@code providerService.test(id)}（mock 掉
 * {@link ProviderMapper} 与 {@link CryptoUtil}，用 JDK {@link HttpServer} 桩充当
 * {@code GET {baseUrl}/models}），故断言的是真实调用链走到底。
 *
 * @since 2026-09-12 provider-custom-headers 前置实测
 */
@DisplayName("[T3 探针 · 残留] ProviderService.test 的会话来源")
class ProviderTestSessionIdProbeTest {

    private static final Logger log = LoggerFactory.getLogger(ProviderTestSessionIdProbeTest.class);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
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
    @DisplayName("T3-A · 测试连接端到端跑到底（会话来源不参与；[批 3c] ambient 观察装置已删）")
    void t3a_testConnectionReachesStub() throws IOException {
        String baseUrl = startStub();
        ProviderService svc = newService(baseUrl);

        // [批 3c] 语义消失：原此处 clear() + 读 ambient 会话值并断言为 null（证明「全新线程无残留」）。
        //   ambient 会话槽已整类删除 ⇒ 观察装置与断言删除；本用例保留端到端断言。
        TestConnectionResponse resp = svc.test("prov-probe-1");

        System.out.println("[T3 探针] T3-A 线程=" + Thread.currentThread().getName()
            + " → test() 返回 ok=" + resp.ok() + " message=" + resp.message());
        log.info("[T3 探针] T3-A 线程={} test() 返回 ok={} message={}",
            Thread.currentThread().getName(), resp.ok(), resp.message());

        assertThat(resp.ok()).as("桩返回 200 → 测试连接应成功（证明 test() 真的跑到了底）").isTrue();
    }

    // [批 3c] T3-B（线程预置残留会话 → test() 原样读到）整条删除：其装置（写 ambient 会话槽）
    //   与断言（读回该值）都依赖已被整类删除的载体，无法构造。该危害的现行防线 =
    //   ProviderTestConnectionHeadersTest#sessionIdMustAlwaysFallBackToStaticConstant
    //   （断言 ${session_id} 恒落 STATIC_FALLBACK）。已登记在批报告里待裁定。

    @Test
    @DisplayName("T3-C · 静态证据：ProviderController.test 无 sessionId 入参、全文无 sessionId")
    void t3c_staticEvidence_noMdcSourceOnProviderTestPath() {
        // 本用例是「静态取证」：把源码事实写成断言，防止未来有人给测试连接加回会话来源而本结论悄悄失效。
        String controllerSrc = readSource(
            "src/main/java/com/nexusai/apis/provider/ProviderController.java");
        assertThat(controllerSrc)
            .as("ProviderController.test 只有 @PathVariable String id —— 不接收 sessionId")
            .contains("public TestConnectionResponse test(@PathVariable String id)");
        assertThat(controllerSrc)
            .as("ProviderController 全文不得出现 sessionId（T3 结论：测试连接无会话源 ⇒ ${session_id} 恒落兜底常量）")
            .doesNotContain("sessionId");

        System.out.println("[T3 探针] T3-C 静态取证通过：ProviderController 无 sessionId 入参、无会话来源");
        log.info("[T3 探针] T3-C 静态取证通过：ProviderController 无 sessionId 入参、无会话来源");
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
