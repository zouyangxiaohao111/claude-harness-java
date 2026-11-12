package com.nexusai.kb.app.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.annotation.Order;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PaddleOCR-VL 解析器意图测试 · 纯单测（⛔ 不启 Spring、⛔ 不连真服务）。
 *
 * <p><b>钉住的语义</b>：
 * <ol>
 *   <li><b>请求体契约</b>：{@code file}(base64) + {@code fileType}(0=PDF/1=图像) + 两个可选开关。
 *       契约写错服务端只会回 errorCode，问题会被误读成「OCR 效果差」。</li>
 *   <li><b>页序与拼接</b>：{@code layoutParsingResults} 按页序 ⇒ 正文必须按原顺序拼接（页间空行），
 *       ⛔ 不能因为并发/遍历写错把第 2 页排到第 1 页前面。</li>
 *   <li><b>失败可见</b>：HTTP 非 200 / {@code errorCode != 0} 必须抛 {@link KbParseException}，
 *       而不是返回空文本冒充「解析成功」。</li>
 *   <li><b>未配置</b>：endpoint 为空 ⇒ {@link IllegalStateException}（环境问题，不是文件问题），
 *       且文案里带配置键，用户能照着改。</li>
 * </ol>
 */
@DisplayName("[解析引擎] PaddleOCR-VL 外部服务解析器")
class KbPaddleOcrVlParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;

    /** 假服务收到的请求体（按到达顺序）。 */
    private final List<String> receivedBodies = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 起一个本地假服务，返回 base URL（不含 /layout-parsing）。 */
    private String startServer(int status, String responseJson) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/layout-parsing", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 造一张真实 PNG 的 base64（用于图片落盘断言）。 */
    private static String pngBase64() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, 0xFF00FF00);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean ok = ImageIO.write(img, "png", out);
        img.flush();
        assertThat(ok).as("本机 ImageIO 必须支持 png（否则夹具无效）").isTrue();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static Path writeFile(Path dir, String name, byte[] content) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, content);
        return file;
    }

    // ── 1. 请求体 ────────────────────────────────────────────────────

    @Test
    @DisplayName("buildRequestBody：file(base64) + fileType + 两个可选开关")
    void buildRequestBodyCarriesFileAndType() throws Exception {
        byte[] bytes = "PDF-BYTES".getBytes(StandardCharsets.UTF_8);

        JsonNode pdf = JSON.readTree(KbPaddleOcrVlParser.buildRequestBody(bytes, KbPaddleOcrVlParser.FILE_TYPE_PDF));
        assertThat(pdf.path("file").asText())
            .isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(pdf.path("fileType").asInt()).isEqualTo(0);
        assertThat(pdf.path("useLayoutDetection").asBoolean()).isTrue();
        assertThat(pdf.path("prettifyMarkdown").asBoolean()).isTrue();

        JsonNode image = JSON.readTree(KbPaddleOcrVlParser.buildRequestBody(bytes, KbPaddleOcrVlParser.FILE_TYPE_IMAGE));
        assertThat(image.path("fileType").asInt()).isEqualTo(1);

        JsonNode custom = JSON.readTree(KbPaddleOcrVlParser.buildRequestBody(
            bytes, KbPaddleOcrVlParser.FILE_TYPE_IMAGE, false, false));
        assertThat(custom.path("useLayoutDetection").asBoolean()).isFalse();
        assertThat(custom.path("prettifyMarkdown").asBoolean()).isFalse();

        assertThatThrownBy(() -> KbPaddleOcrVlParser.buildRequestBody(bytes, 9))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fileType");
    }

    @Test
    @DisplayName("fileTypeOf：pdf → 0，图片 → 1（大小写不敏感）")
    void fileTypeFromExtension() {
        assertThat(KbPaddleOcrVlParser.fileTypeOf("报告.PDF")).isEqualTo(KbPaddleOcrVlParser.FILE_TYPE_PDF);
        assertThat(KbPaddleOcrVlParser.fileTypeOf("scan.png")).isEqualTo(KbPaddleOcrVlParser.FILE_TYPE_IMAGE);
        assertThat(KbPaddleOcrVlParser.fileTypeOf("无扩展名")).isEqualTo(KbPaddleOcrVlParser.FILE_TYPE_IMAGE);
    }

    // ── 2. 假服务：两页 → 按页序拼接 + 图片落盘 ──

    @Test
    @DisplayName("两页响应：按页序拼接 markdown.text，页间空行；图片解到 workDir")
    void parseTwoPagesInOrder(@TempDir Path tmp) throws Exception {
        String png = pngBase64();
        String body = "{\"errorCode\":0,\"errorMsg\":\"Success\",\"result\":{\"layoutParsingResults\":["
            + "{\"markdown\":{\"text\":\"第一页正文\"}},"
            + "{\"markdown\":{\"text\":\"第二页正文\",\"images\":[\"" + png + "\",\"@@不是base64@@\"]}}"
            + "]}}";
        String baseUrl = startServer(200, body);

        Path file = writeFile(tmp, "样例.pdf", "PDF".getBytes(StandardCharsets.UTF_8));
        Path workDir = Files.createDirectories(tmp.resolve("work"));
        KbPaddleOcrVlParser parser = new KbPaddleOcrVlParser(baseUrl + "/", "");

        KbParsedDocument doc = parser.parse(file, KbParseContext.defaults().withWorkDir(workDir));

        assertThat(doc.text()).isEqualTo("第一页正文" + KbPaddleOcrVlParser.PAGE_SEPARATOR + "第二页正文");
        assertThat(doc.metadata().get(KbParsedDocument.META_PARSER)).isEqualTo("paddleocr_vl");
        assertThat(doc.metadata().get(KbParsedDocument.META_PAGE_COUNT)).isEqualTo("2");
        assertThat(doc.metadata().get(KbPaddleOcrVlParser.META_PAGE_CHARS)).isEqualTo("5,5");
        // 5 + 页间空行(2) + 5 = 12，长度口径必须与实际 text 一致
        assertThat(doc.metadata().get(KbParsedDocument.META_TEXT_LENGTH)).isEqualTo("12");
        assertThat(doc.metadata().get(KbParsedDocument.META_SOURCE_BLOCKS)).isEqualTo("0");

        // 图片：合法 PNG 落盘，坏 base64 被跳过（不整份失败）
        assertThat(doc.images()).hasSize(1);
        KbImageRef ref = doc.images().get(0);
        assertThat(ref.pageIndex()).isEqualTo(2);
        assertThat(ref.sourceKind()).isEqualTo(KbPaddleOcrVlParser.KIND_OCR_LAYOUT);
        Path saved = Path.of(ref.imagePath());
        assertThat(Files.exists(saved)).as("图片必须真的落盘: %s", saved).isTrue();
        assertThat(saved.getFileName().toString()).endsWith(".png");
        assertThat(saved.getParent()).isEqualTo(workDir.toAbsolutePath());

        // 请求体：真正发出去了，且 fileType 按 .pdf 判成 0
        assertThat(receivedBodies).hasSize(1);
        JsonNode sent = JSON.readTree(receivedBodies.get(0));
        assertThat(sent.path("fileType").asInt()).isEqualTo(0);
        assertThat(sent.path("file").asText())
            .isEqualTo(Base64.getEncoder().encodeToString("PDF".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("空页：不占正文位置，但页数与每页字符数如实记录")
    void blankPageIsRecordedButNotInBody(@TempDir Path tmp) throws Exception {
        String body = "{\"errorCode\":0,\"result\":{\"layoutParsingResults\":["
            + "{\"markdown\":{\"text\":\"\"}},"
            + "{\"markdown\":{\"text\":\"第二页有内容\"}}"
            + "]}}";
        String baseUrl = startServer(200, body);
        Path file = writeFile(tmp, "空白页.pdf", "PDF".getBytes(StandardCharsets.UTF_8));

        KbParsedDocument doc = new KbPaddleOcrVlParser(baseUrl, "")
            .parse(file, KbParseContext.defaults().withWorkDir(Files.createDirectories(tmp.resolve("w"))));

        assertThat(doc.text()).isEqualTo("第二页有内容");
        assertThat(doc.metadata().get(KbParsedDocument.META_PAGE_COUNT)).isEqualTo("2");
        assertThat(doc.metadata().get(KbPaddleOcrVlParser.META_PAGE_CHARS)).isEqualTo("0,6");
    }

    @Test
    @DisplayName("endpoint 已带 /layout-parsing 时不重复拼接")
    void endpointWithRoutePathIsNotDuplicated(@TempDir Path tmp) throws Exception {
        String baseUrl = startServer(200, "{\"errorCode\":0,\"result\":{\"layoutParsingResults\":"
            + "[{\"markdown\":{\"text\":\"一页\"}}]}}");
        Path file = writeFile(tmp, "a.png", "IMG".getBytes(StandardCharsets.UTF_8));

        KbParsedDocument doc = new KbPaddleOcrVlParser(baseUrl + "/layout-parsing", "")
            .parse(file, KbParseContext.defaults().withWorkDir(Files.createDirectories(tmp.resolve("w"))));

        assertThat(doc.text()).isEqualTo("一页");
        assertThat(receivedBodies).hasSize(1);  // 请求真的到了那唯一的路由上
        assertThat(JSON.readTree(receivedBodies.get(0)).path("fileType").asInt()).isEqualTo(1);
    }

    // ── 3. 失败可见 ──────────────────────────────────────────────────

    @Test
    @DisplayName("errorCode != 0 ⇒ KbParseException（带 errorCode 与 errorMsg）")
    void errorCodeFails(@TempDir Path tmp) throws Exception {
        String baseUrl = startServer(200,
            "{\"errorCode\":1,\"errorMsg\":\"布局解析失败\",\"result\":null}");
        Path file = writeFile(tmp, "坏.pdf", "PDF".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new KbPaddleOcrVlParser(baseUrl, "")
            .parse(file, KbParseContext.defaults()))
            .isInstanceOf(KbParseException.class)
            .hasMessageContaining("errorCode=1")
            .hasMessageContaining("布局解析失败");
    }

    @Test
    @DisplayName("HTTP 非 200 ⇒ KbParseException（不是空文本假装成功）")
    void non200Fails(@TempDir Path tmp) throws Exception {
        String baseUrl = startServer(500, "{\"error\":\"boom\"}");
        Path file = writeFile(tmp, "x.pdf", "PDF".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new KbPaddleOcrVlParser(baseUrl, "")
            .parse(file, KbParseContext.defaults()))
            .isInstanceOf(KbParseException.class)
            .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("endpoint 为空 ⇒ IllegalStateException（含配置键）")
    void blankEndpointThrows(@TempDir Path tmp) throws Exception {
        Path file = writeFile(tmp, "x.pdf", "PDF".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new KbPaddleOcrVlParser("  ", "")
            .parse(file, KbParseContext.defaults()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PaddleOCR-VL 服务未配置")
            .hasMessageContaining("nexusai.knowledge.ocr.paddleocr-vl.endpoint");

        // Spring 字段默认值同样是空串 ⇒ 未配置时 parse 也必须立刻失败
        assertThatThrownBy(() -> new KbPaddleOcrVlParser().parse(file, KbParseContext.defaults()))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── 4. 能力声明 / 路由位置 ───────────────────────────────────────

    @Test
    @DisplayName("supports / supportedExtensions / displayName / @Order(40)")
    void capabilities() {
        KbPaddleOcrVlParser parser = new KbPaddleOcrVlParser("http://127.0.0.1:1", "");

        for (String ext : new String[]{"pdf", "jpg", "jpeg", "png", "bmp", "tiff", "webp"}) {
            assertThat(parser.supports("a." + ext, null)).as("必须支持 .%s", ext).isTrue();
            assertThat(parser.supports("a." + ext.toUpperCase(), null)).as("扩展名大小写不敏感 .%s", ext).isTrue();
        }
        assertThat(parser.supports("a.docx", null)).isFalse();
        assertThat(parser.supports("a.txt", null)).isFalse();
        assertThat(parser.supports("无扩展名", "application/pdf")).isTrue();
        assertThat(parser.supports("无扩展名", "text/plain")).isFalse();

        assertThat(parser.supportedExtensions())
            .containsExactlyInAnyOrder("pdf", "jpg", "jpeg", "png", "bmp", "tiff", "webp");
        assertThat(parser.displayName()).isEqualTo("PaddleOCR-VL（外部服务）");

        Order order = KbPaddleOcrVlParser.class.getAnnotation(Order.class);
        assertThat(order).as("注册表按 @Order 升序路由，缺注解 = 排到最后").isNotNull();
        assertThat(order.value()).as("必须排在文本兜底(30)之后，默认路由才不变").isEqualTo(40);

        // 可用性：没配地址 ⇒ 路由会回落默认，而不是选中一个必然失败的外部服务
        assertThat(new KbPaddleOcrVlParser("", "").isAvailable()).isFalse();
        assertThat(new KbPaddleOcrVlParser("", "").unavailableReason())
            .contains("nexusai.knowledge.ocr.paddleocr-vl.endpoint");
        assertThat(new KbPaddleOcrVlParser("http://127.0.0.1:1", "").isAvailable()).isTrue();
        assertThat(new KbPaddleOcrVlParser("http://127.0.0.1:1", "").unavailableReason()).isEmpty();
    }

    @Test
    @DisplayName("默认路由不变：PDF(10) / 图片(15) 的 @Order 仍小于本解析器(40)")
    void defaultRoutingUnchanged() {
        Order pdf = KbPdfParser.class.getAnnotation(Order.class);
        Order image = KbImageParser.class.getAnnotation(Order.class);
        Order self = KbPaddleOcrVlParser.class.getAnnotation(Order.class);
        assertThat(pdf).isNotNull();
        assertThat(image).isNotNull();
        assertThat(self).isNotNull();
        assertThat(pdf.value()).isLessThan(self.value());
        assertThat(image.value()).isLessThan(self.value());
        // 前两者认的扩展名与本解析器重叠 ⇒ 默认路径必然由它们命中，本解析器只有被规则点名才走到
        assertThat(pdf.value()).isEqualTo(10);
        assertThat(image.value()).isEqualTo(15);
        assertThat(self.value()).isEqualTo(40);
    }
}
