package com.nexusai.apis.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.NexusAiApplication;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.DynamicHeaderExpander;
import com.nexusai.model.provider.dto.ProviderCreateRequest;
import com.nexusai.model.provider.dto.ProviderDto;
import com.nexusai.model.provider.dto.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [provider-custom-headers] 自定义请求头 · <b>真实 SQLite + 真实 HTTP 端点</b>端到端集成测试。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：本批所有单测都在「内存 Map」层面验证序列化与校验，
 * 但用户真正走的那条路是 <b>Service → MyBatis-Flex → SQLite 列 → 读回 → DTO → 出线 JSON</b>。
 * 中间任何一环做了一次「顺手规范化」（列类型、连接参数、Jackson 配置、Flex 的列映射），
 * 值就会在<b>没人看的地方</b>被改动，而症状只是「网关偶发 400」。故本类把三件事钉在真实库上：
 * <ol>
 *   <li><b>毒字符逐字符往返</b>：{@code ,} {@code "} {@code \} {@code {}} {@code :} 及
 *       {@code Accept: application/json, text/event-stream} 组合值，写库 → 读回 → 逐字符相等。
 *       这是 §6.5「手写朴素 JSON 会把含逗号的值切碎」修复的<b>唯一端到端守卫</b>——
 *       序列化单测只证明「String → String」，证明不了「过了 SQLite 列还是它」。</li>
 *   <li><b>占位符在存储层不得被展开</b>：{@code ${session_id}} 是<b>运行时</b>概念
 *       （{@link DynamicHeaderExpander} 在每次请求时展开），落库必须原样保留 ——
 *       否则「按会话展开」在写库那一刻就被固化成一个常量，开关和会话亲和全部失效。</li>
 *   <li><b>写侧拒绝真的是 HTTP 400（不是 500）</b>：本批用 {@code ValidationException} 达成 400
 *       （见规范 §6.4 的实测纠正）。这条<b>必须在真实 HTTP 栈上验</b>：单测只断言异常类型，
 *       断言不了 {@code GlobalExceptionHandler} 把它映射成哪个状态码 —— 而 §6.4 整节的价值
 *       恰恰在于「不是 500」（IAE 会落兜底 handler → 500）。</li>
 * </ol>
 *
 * <p><b>为什么必须是 RANDOM_PORT 而非 MOCK</b>：同仓 {@code SessionListOrderIntegrationTest}
 * 已实测记录——{@code BrowserWebSocketConfig} 是无条件 {@code @Configuration}，其
 * {@code ServletServerContainerFactoryBean} 依赖容器提供的
 * {@code jakarta.websocket.server.ServerContainer} ServletContext 属性；MOCK 环境无真实容器
 * → 上下文加载失败。RANDOM_PORT 起真实 Tomcat，上下文可正常加载，且能让
 * {@link TestRestTemplate} 走<b>真实 HTTP 往返</b>取状态码（这是本类第 ③ 条的唯一取证手段）。
 *
 * <p><b>与计划步骤 1 的一处实测偏差（已上报，非静默迁就）</b>：计划把「中文」列在 create 的毒字符清单里，
 * 但<b>写侧校验会拒绝非 ASCII 值</b>（{@code DynamicHeaderExpander.isValidHeaderValue} =
 * {@code ^[\x20-\x7E\t]*$}，见规范 §6.4「值不得含 \r\n」那一段的实现）。实测确认
 * {@code 会话标识} 不匹配该正则 → {@code ValidationException} → 400，<b>无法经 create 落库</b>。
 * 中文的往返由两层分别覆盖、本类不重复造第三条：
 * <ul>
 *   <li>序列化层：{@code ProviderHeaderSerializeTest.chineseValueRoundTrips}（不经写侧校验）</li>
 *   <li>存储/读回层（本类）：{@link #nonAsciiLegacyRowInDb_isReadBackByteExact()}
 *       —— 直接写库模拟「写侧校验上线前落下的存量行」，验证读链对非 ASCII 逐字符忠实</li>
 * </ul>
 *
 * <p><b>前置自证（fail loud · 规则十二）</b>：{@link #precondition_cleanTablesAndEndpointAlive()}
 * 先证明「清表后确实为空」且「合法 POST 真的能 201」—— 否则后面那条 400 断言可能只是
 * 「端点根本没通」的假红，失去鉴别力。
 *
 * <p><b>变异自证（预期，未在本次提交中实跑）</b>：把 {@code PostMapping} 的 {@code create}
 * 改回抛 {@code IllegalArgumentException} ⇒ {@link #forbiddenHeaderIsRejectedAsHttp400Not500()} 变红（得到 500）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = NexusAiApplication.class)
@DisplayName("[provider-headers] 自定义请求头 · 真实 SQLite + 真实 HTTP 端点集成测试")
class ProviderHeaderRoundTripIntegrationTest {

    /** 独立 ObjectMapper（**不是** ProviderService 内部那个 HEADERS_JSON）：读回产物必须能被外部解析器读懂。 */
    private static final ObjectMapper INDEPENDENT_JSON = new ObjectMapper();

    private static final String CREATE_URL = "/api/v1/providers";

    // CleanupMode.NEVER：SQLite 文件（.db/-shm/-wal）测试结束时仍被 Hikari 锁定，JUnit 默认清理
    // 会因「文件被占用」抛 IOException（Windows 实测，同 SessionListOrderIntegrationTest）。
    @TempDir(cleanup = CleanupMode.NEVER)
    static Path tempDir;

    @DynamicPropertySource
    static void freshDb(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", () -> "jdbc:sqlite:"
            + tempDir.resolve("provider-headers.db").toAbsolutePath()
            + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=5000");
    }

    @Autowired private ProviderService providerService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestRestTemplate restTemplate;

    /**
     * 每个用例从干净表开始：本类用例共享同一个 @TempDir 库文件，不清表则上一用例的行会混进来。
     * models 先删（providers 是其逻辑父表）。
     */
    @BeforeEach
    void cleanProviderTables() {
        jdbcTemplate.update("DELETE FROM models");
        jdbcTemplate.update("DELETE FROM providers");
    }

    /** 唯一 provider 名（providers.name 有 UNIQUE 约束，且清表与插入之间存在同库并发写入的可能）。 */
    private static String uniqueName() {
        return "prov-hdr-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String rawExtraHeaders(String providerId) {
        return jdbcTemplate.queryForObject(
            "SELECT extra_headers FROM providers WHERE id = ?", String.class, providerId);
    }

    private static Map<String, String> parseWithIndependentMapper(String json) {
        try {
            return INDEPENDENT_JSON.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new AssertionError("列里的 extra_headers 不是合法 JSON（说明落库形态被破坏）：" + json, e);
        }
    }

    private ProviderDto createWithHeaders(Map<String, String> headers) {
        return providerService.create(new ProviderCreateRequest(
            uniqueName(), ProviderType.openai_compatible,
            "https://gw.example.com/v1", "sk-live-headers-test", headers, Boolean.TRUE));
    }

    // ════════════════════════════════════════════════════════════════════
    // 前置自证：清表确实为空 + 端点确实通（否则 400 断言没有鉴别力）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("前置：清表后 providers/models 为空，且一个合法 POST 真的返回 201")
    void precondition_cleanTablesAndEndpointAlive() {
        Integer providers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM providers", Integer.class);
        assertEquals(0, providers == null ? -1 : providers.intValue(), "前置假设失败：清表后 providers 应为空");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", uniqueName());
        body.put("type", "openai_compatible");
        body.put("baseUrl", "https://gw.example.com/v1");
        body.put("apiKey", "sk-live-precondition");
        body.put("extraHeaders", Map.of("x-tenant", "acme"));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> ok = restTemplate.postForEntity(CREATE_URL, new HttpEntity<>(body, h), String.class);

        assertEquals(HttpStatus.CREATED, ok.getStatusCode(),
            "前置假设失败：合法 POST 未返回 201 —— 后续 400 断言将失去鉴别力（无法区分「校验拒绝」与「端点不通」）。"
            + "实际响应体=" + ok.getBody());
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 毒字符逐字符往返（真实 SQLite 列）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("毒字符 header 经真实 SQLite 往返后逐字符完整（, \" \\ { } : + Accept 组合值）")
    void poisonCharHeaders_surviveDbRoundTripByteForByte() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-opencode-comma", "application/json, text/event-stream");
        headers.put("x-quote-backslash", "a\"b\\c");
        headers.put("x-braces", "{\"k\":\"v\"}");
        headers.put("x-colon-url", "https://gw.example.com:8443/v1");
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("x-full-accept-line", "Accept: application/json, text/event-stream");

        ProviderDto created = createWithHeaders(headers);

        // ① 从 DB 读原始 JSON —— 不经任何应用层代码，用独立解析器读懂它
        String raw = rawExtraHeaders(created.id());
        assertNotNull(raw, "配了 header 就不得落库为 NULL；实际=NULL");
        Map<String, String> fromDb = parseWithIndependentMapper(raw);
        assertEquals(headers, fromDb,
            "毒字符值必须逐字符完整（Map#equals 是逐字符 String 比较）；实际落库 JSON=[" + raw + "]");

        // ② 再经应用层读链（deserializeHeaders → ProviderDto）读回同一份
        assertEquals(headers, providerService.getById(created.id()).extraHeaders(),
            "getById 出的 extraHeaders 也必须与写入值逐字符相同");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 占位符在**存储层**不得被展开
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("${session_id} 在存储层原样保留，不被展开成会话 ID 或兜底常量")
    void sessionIdPlaceholder_isNotExpandedAtStorageLayer() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-opencode-session", DynamicHeaderExpander.SESSION_ID_TOKEN);
        headers.put("x-prefixed", "sess-" + DynamicHeaderExpander.SESSION_ID_TOKEN);

        ProviderDto created = createWithHeaders(headers);

        String raw = rawExtraHeaders(created.id());
        assertNotNull(raw);
        assertTrue(raw.contains(DynamicHeaderExpander.SESSION_ID_TOKEN),
            "落库 JSON 必须原样含占位符字面量；实际=[" + raw + "]");
        // 反向守卫：存储层一旦有人「顺手展开」，会把占位符换成一个当时的值 —— 那时这两条必然命中。
        assertFalse(raw.contains(DynamicHeaderExpander.STATIC_FALLBACK),
            "存储层不得写入兜底常量（那是**请求时**的取值，不是配置值）；实际=[" + raw + "]");
        assertEquals(DynamicHeaderExpander.SESSION_ID_TOKEN, parseWithIndependentMapper(raw).get("x-opencode-session"),
            "读回的值必须是精确的占位符字面量");
        assertEquals(headers, providerService.getById(created.id()).extraHeaders(),
            "占位符必须逐字符往返（大小写敏感，错一个字母就静默失效）");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 写侧拒绝 = HTTP 400（**不是 500**）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("敏感头（authorization / expect）经真实 POST 返回 400 而非 500，且不落库")
    void forbiddenHeaderIsRejectedAsHttp400Not500() {
        for (String name : new String[]{"authorization", "expect"}) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", uniqueName());
            body.put("type", "openai_compatible");
            body.put("baseUrl", "https://gw.example.com/v1");
            body.put("apiKey", "sk-live-forbidden");
            body.put("extraHeaders", Map.of(name, "whatever"));

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> res = restTemplate.postForEntity(CREATE_URL, new HttpEntity<>(body, h), String.class);

            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode(),
                "header [" + name + "] 必须被写侧拒为 400（§6.4：抛 ValidationException 而非 IllegalArgumentException，"
                + "后者会落 GlobalExceptionHandler 的兜底分支变成 500）。实际=" + res.getStatusCode()
                + "，响应体=" + res.getBody());
            assertNotNull(res.getBody());
            assertTrue(res.getBody().contains(name),
                "响应体应指明是哪个 header 被拒（否则用户不知道改哪一行）：" + res.getBody());
        }

        // 校验发生在 insert 之前 ⇒ 不得留下任何脏行
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM providers", Integer.class);
        assertEquals(0, rows == null ? -1 : rows.intValue(),
            "写侧拒绝必须发生在落库之前，库中不得留下被拒的 provider");
    }

    @Test
    @DisplayName("Service 层同一判据：authorization / ${SESSION_ID}（拼写错）都抛 ValidationException")
    void forbiddenAndMalformedAreRejectedAtServiceLayerToo() {
        // 与上一条互补：上一条走真实 HTTP（证状态码），这一条走 Service（证「拒绝发生在哪一层」，
        //   且不依赖 TestRestTemplate 的可用性）。两条都红才说明防线整个不见了。
        assertThrows(ValidationException.class, () -> createWithHeaders(Map.of("X-Ok", "1", "Authorization", "Bearer x")),
            "凭据头应在 Service 层被拒");
        assertThrows(ValidationException.class, () -> createWithHeaders(Map.of("x-sess", "${SESSION_ID}")),
            "占位符拼写错误应在 Service 层被拒（否则会被原样当字面量发到线上）");
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 非 ASCII：写侧拒绝 + 存量行读回逐字符忠实（计划步骤 1 里「中文」那条的归宿）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("非 ASCII（中文）值被写侧拒为 400（值只允许可见 ASCII）")
    void nonAsciiHeaderValue_isRejectedAtWriteSide() {
        // 规范 §6.4：值不得含 \r\n，且只能是可见 ASCII —— 中文不在 \x20-\x7E 内。
        // 这条把「UI 镜像判据」与「后端判据」的同一性钉住：前端 KvEditor 的 VALID_HEADER_VALUE
        // 是同一正则，若两边分叉，用户会看到「前端不标红、后端 400」的自相矛盾。
        assertThrows(ValidationException.class, () -> createWithHeaders(Map.of("x-cn", "会话标识")),
            "非 ASCII 值必须在写侧被拒（前端 KvEditor 用同一正则提前标红）");
    }

    @Test
    @DisplayName("存量非 ASCII 行（写侧校验上线前落下的）读回逐字符忠实 —— 不做任何字符集改写")
    void nonAsciiLegacyRowInDb_isReadBackByteExact() {
        // WHY：写侧只拦**新写**；库里可能已有旧行（或有人直接改库）。读链若做了任何
        //   「转义 / 编码归一 / 截断」，用户会看到值悄悄变了却没有任何报错。
        //   直接 INSERT 模拟该形态（绕开写侧校验），再走完整的应用层读链。
        String id = "prov-legacy-cn";
        String legacyJson = "{\"x-cn\":\"会话标识\",\"x-mixed\":\"前缀-中文-后缀\"}";
        jdbcTemplate.update(
            "INSERT INTO providers (id, name, type, base_url, api_key_hash, api_key_masked, extra_headers,"
                + " enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id, uniqueName(), "openai_compatible", "https://gw.example.com/v1",
            "hash", "sk-****legacy", legacyJson, 1,
            "2026-09-13T10:00:00+08:00", "2026-09-13T10:00:00+08:00");

        Map<String, String> headers = providerService.getById(id).extraHeaders();
        assertNotNull(headers, "存量行有 extra_headers 就必须读出 Map");
        assertEquals("会话标识", headers.get("x-cn"), "中文值必须逐字符读回，不得被转义或改写");
        assertEquals("前缀-中文-后缀", headers.get("x-mixed"));

        // 前置自证：库里那份 JSON 确实是它本身（排除「写进去就被改了」）
        String raw = rawExtraHeaders(id);
        assertEquals(legacyJson, raw, "列里的原始 JSON 不得被写入路径改写");
    }
}
