package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1.4] 渠道外发载荷：四字段 + 脱敏 + 分档截断。
 *
 * <p>WHY（照抄旧源码最容易犯的错）：渠道消息会落到第三方平台。把用户输入里的 api_key /
 * bearer token / 长 base64 原样发出去 = 二次泄露。本测试用「假 token 不得出现在出站文本里」
 * 把这条钉死。
 */
@DisplayName("[P1.4] ChannelPermissionPayload · 脱敏与截断")
class ChannelPermissionPayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("输入里塞假 token/密钥/长 base64 ⇒ 出站文本一律打码")
    void secretsAreMasked() {
        ObjectNode input = JSON.createObjectNode();
        input.put("api_key", "sk-live-abcdefghijk12345");
        input.put("authorization", "Bearer abcdefghijklmnopqrstuvwxyz");
        input.put("blob", "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqaw==");

        String text = ChannelPermissionPayload.toText("abcde", "Bash", "运行命令", input);

        assertThat(text).contains("[权限确认] Bash").contains("yes abcde").contains("no abcde");
        assertThat(text)
            .as("假 token / bearer / 长 base64 都不得原样外发")
            .doesNotContain("sk-live-abcdefghijk12345")
            .doesNotContain("abcdefghijklmnopqrstuvwxyz")
            .doesNotContain("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqaw");
    }

    @Test
    @DisplayName("description 截 3500 码点；input_preview 走 15000 预算（保留头尾）")
    void truncationBounds() {
        String longDesc = "a".repeat(4000);
        String text = ChannelPermissionPayload.toText("abcde", "Read", longDesc, null);
        assertThat(text.codePointCount(0, text.length())).isLessThan(4000 + 200);

        String big = "x".repeat(20_000);
        String truncated = ChannelPermissionPayload.truncateBudget(big, ChannelPermissionPayload.INPUT_PREVIEW_BUDGET);
        assertThat(truncated.length()).isLessThanOrEqualTo(ChannelPermissionPayload.INPUT_PREVIEW_BUDGET);
        assertThat(truncated).contains("[截断]");

        // 码点截断不能切坏 emoji
        String emoji = "😀".repeat(10);
        assertThat(ChannelPermissionPayload.truncateCodepoints(emoji, 3)).isEqualTo("😀😀😀…");
    }

    @Test
    @DisplayName("掩码矩阵（≥12 例）：该打的都打了，普通文本/短串/路径末两段保持可读")
    void maskMatrix() {
        // ── 该打的都打了（无论熵值高低） ──────────────────────────────
        assertMasked("sk-abcdefghijk12345", "abcdefghijk12345");
        assertMasked("ghp_abcdefghij1234567890", "ghp_abcdefghij");
        assertMasked("xoxb-1234567890-abcdefghij", "1234567890-abcdefghij");
        assertMasked("{\"api_key\":\"value12345\"}", "value12345");
        assertMasked("Authorization: Bearer abcdefghijklmnop", "abcdefghijklmnop");
        assertMasked("authorization: Basic dXNlcjpwYXNzd29yZA==", "dXNlcjpwYXNzd29yZA");
        assertMasked("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl", "eyJhbGci");
        assertMasked("https://user:pa55w0rd@host/x", "pa55w0rd");
        assertMasked("?access_token=abcd1234", "abcd1234");
        assertMasked("?secret=shhh1234", "shhh1234");
        assertMasked("?password=p@ssw0rd", "p@ssw0rd");
        // 高熵串（长度 >=32 且字符类 >=3 / 熵 >=3.5）
        assertMasked("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqaw==", "QUJD");
        assertMasked("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", "9f86d081");
        assertMasked("aB3dE6gH9jK2mN5pQ8sT1vW4yZ7bC0eF", "aB3dE6gH9");

        // ── 普通内容可读 ─────────────────────────────────────────────
        String longPath = "/home/user/projects/nexusai/frontend/src/components/very/deeply/nested/File.tsx";
        assertThat(ChannelPermissionPayload.maskSecrets(longPath))
            .as("路径保留末两段可读").contains("/File.tsx").doesNotContain("velY");

        assertThat(ChannelPermissionPayload.maskSecrets("short-token")).isEqualTo("short-token");
        assertThat(ChannelPermissionPayload.maskSecrets("运行命令：查看文件")).isEqualTo("运行命令：查看文件");
        assertThat(ChannelPermissionPayload.maskSecrets("a".repeat(40)))
            .as("低熵长串（重复字符）不属高熵 ⇒ 保持可读")
            .isEqualTo("a".repeat(40));
        assertThat(ChannelPermissionPayload.maskSecrets("?client_id=abc123"))
            .as("非敏感 query 键保持可读")
            .isEqualTo("?client_id=abc123");
    }

    private static void assertMasked(String input, String secretPart) {
        assertThat(ChannelPermissionPayload.maskSecrets(input))
            .as("必须打码: %s", input)
            .doesNotContain(secretPart);
    }
}
