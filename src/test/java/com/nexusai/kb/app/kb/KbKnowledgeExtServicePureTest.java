package com.nexusai.kb.app.kb;

import com.nexusai.infra.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link KbKnowledgeExtService} 的纯函数测试 · 批次 W4。
 *
 * <p>只测不需要数据库的部分：URL 校验（SSRF 边界）、文件名推断、内容类型判定。
 * 其余方法要真实库 ⇒ 走真机 E2E（W4 已验 21 个端点）。
 */
class KbKnowledgeExtServicePureTest {

    @Nested
    @DisplayName("URL 校验 · SSRF 边界")
    class UrlValidation {

        @Test
        @DisplayName("正常 http/https 放行")
        void allowsHttpAndHttps() {
            assertThat(KbKnowledgeExtService.validateUrl("https://example.com/a.txt").getHost())
                .isEqualTo("example.com");
            assertThat(KbKnowledgeExtService.validateUrl("http://example.com/a.txt").getHost())
                .isEqualTo("example.com");
        }

        @Test
        @DisplayName("非 http/https 协议一律拒绝")
        void rejectsOtherSchemes() {
            for (String bad : new String[]{
                "file:///etc/passwd", "ftp://example.com/x", "gopher://example.com/x",
                "javascript:alert(1)", "data:text/plain,hi"}) {
                assertThatThrownBy(() -> KbKnowledgeExtService.validateUrl(bad))
                    .as("应拒绝 %s", bad)
                    .isInstanceOf(ValidationException.class);
            }
        }

        @Test
        @DisplayName("本地回环 / 内网段全部拒绝")
        void rejectsPrivateAddresses() {
            for (String bad : new String[]{
                "http://localhost/x", "http://127.0.0.1/x", "http://127.1.2.3/x",
                "http://10.0.0.1/x", "http://192.168.1.1/x", "http://169.254.169.254/latest"}) {
                assertThatThrownBy(() -> KbKnowledgeExtService.validateUrl(bad))
                    .as("应拒绝 %s", bad)
                    .isInstanceOf(ValidationException.class);
            }
        }

        @Test
        @DisplayName("172.16/12 只有第 2 段 16..31 才算内网")
        void rejects172OnlyInPrivateRange() {
            // 内网：应拒绝
            assertThatThrownBy(() -> KbKnowledgeExtService.validateUrl("http://172.16.0.1/x"))
                .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> KbKnowledgeExtService.validateUrl("http://172.31.255.255/x"))
                .isInstanceOf(ValidationException.class);
            // 公网：应放行（172.15 与 172.32 都不是私网段）
            assertThat(KbKnowledgeExtService.validateUrl("http://172.15.0.1/x").getHost())
                .isEqualTo("172.15.0.1");
            assertThat(KbKnowledgeExtService.validateUrl("http://172.32.0.1/x").getHost())
                .isEqualTo("172.32.0.1");
        }

        @Test
        @DisplayName("空 / 畸形 URL 报 400")
        void rejectsEmptyAndMalformed() {
            assertThatThrownBy(() -> KbKnowledgeExtService.validateUrl(null))
                .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> KbKnowledgeExtService.validateUrl("   "))
                .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> KbKnowledgeExtService.validateUrl("http://"))
                .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("文件名推断")
    class InferName {

        @Test
        @DisplayName("自定义名优先")
        void customNameWins() {
            assertThat(KbKnowledgeExtService.inferName(
                URI.create("https://example.com/a.txt"), "自定义.md"))
                .isEqualTo("自定义.md");
        }

        @Test
        @DisplayName("从路径末段取名")
        void fromPath() {
            assertThat(KbKnowledgeExtService.inferName(
                URI.create("https://example.com/docs/report.pdf"), null))
                .isEqualTo("report.pdf");
        }

        @Test
        @DisplayName("无扩展名补 .txt")
        void appendsTxtWhenNoExtension() {
            assertThat(KbKnowledgeExtService.inferName(
                URI.create("https://example.com/docs/readme"), null))
                .isEqualTo("readme.txt");
        }

        @Test
        @DisplayName("路径为空时生成随机名")
        void randomWhenEmptyPath() {
            String name = KbKnowledgeExtService.inferName(URI.create("https://example.com/"), null);
            assertThat(name).startsWith("url-").endsWith(".txt");
        }

        @Test
        @DisplayName("不支持的扩展名报 400")
        void rejectsUnsupportedExtension() {
            assertThatThrownBy(() -> KbKnowledgeExtService.inferName(
                URI.create("https://example.com/x.exe"), null))
                .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("纯文本判定")
    class PlainTextDetection {

        @Test
        @DisplayName("Content-Type 为 text/* 或含 json/xml/markdown 算纯文本")
        void byContentType() {
            assertThat(KbKnowledgeExtService.isPlainText("text/plain", "x")).isTrue();
            assertThat(KbKnowledgeExtService.isPlainText("text/html; charset=utf-8", "x")).isTrue();
            assertThat(KbKnowledgeExtService.isPlainText("application/json", "x")).isTrue();
            assertThat(KbKnowledgeExtService.isPlainText("application/xml", "x")).isTrue();
        }

        @Test
        @DisplayName("没有 Content-Type 时按扩展名判断")
        void byExtensionWhenNoContentType() {
            assertThat(KbKnowledgeExtService.isPlainText("", "a.md")).isTrue();
            assertThat(KbKnowledgeExtService.isPlainText(null, "a.csv")).isTrue();
            assertThat(KbKnowledgeExtService.isPlainText("", "a.pdf")).isFalse();
            assertThat(KbKnowledgeExtService.isPlainText("", "a.docx")).isFalse();
        }

        @Test
        @DisplayName("二进制 Content-Type 即使扩展名像文本也不算")
        void binaryContentTypeWins() {
            // application/octet-stream 的 .txt 可能是压缩包 ⇒ 按二进制处理
            assertThat(KbKnowledgeExtService.isPlainText("application/octet-stream", "a.pdf"))
                .isFalse();
        }
    }
}
