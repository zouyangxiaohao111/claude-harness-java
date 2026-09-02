package com.nexusai.application.agent.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * [impl-I-4 T8] normalizeUrl 对齐 CC 测试 · 验证 JS URL.href 语义（RFC 3986 §6.2.2）：
 * 小写主机 + 剥默认端口 + 保留 userinfo/fragment + strip 尾 {@code /}。
 *
 * <p>WHY（规则九）：URL 身份判定是连接缓存键 / 去重 / 权限匹配的地基（Q-28）。旧实现
 * （URI 重建）保留大小写 + 保留 :443 + 丢弃 userinfo，方向与 CC 相反（MCP-08 D-1）——
 * 若不做本测试，isOfficialMcpUrl 命中率随大小写/端口漂移静默下降。
 */
class NormalizeUrlAlignTest {

    // ── officialRegistry.ts:19-27 语义：去 query + strip 尾 / + 失败 → null ──

    @Test
    void official_lowercasesHost_dropsDefaultPort_stripsTrailingSlash() {
        // 旧行为：https://Example.COM:443/path/ → https://Example.COM:443/path（大写+保留:443）
        assertEquals("https://example.com/path",
            OfficialMcpRegistry.normalizeUrl("https://Example.COM:443/path/"));
    }

    @Test
    void official_stripsQuery() {
        assertEquals("https://example.com/path",
            OfficialMcpRegistry.normalizeUrl("https://Example.com/path?foo=1&bar=2"));
    }

    @Test
    void official_preservesUserInfoAndFragment() {
        // JS URL.href 保留 userinfo（含大小写，仅 scheme+host 小写）+ fragment；旧实现丢弃两者
        assertEquals("https://User:Pass@example.com/path#frag",
            OfficialMcpRegistry.normalizeUrl("https://User:Pass@example.com/path#frag"));
    }

    @Test
    void official_emptyPathGetsRootSlashStripped() {
        // JS URL.href("https://example.com") = "https://example.com/" → strip → 无斜杠
        assertEquals("https://example.com",
            OfficialMcpRegistry.normalizeUrl("https://example.com"));
    }

    @Test
    void official_nonDefaultPortPreserved() {
        assertEquals("https://example.com:8443/path",
            OfficialMcpRegistry.normalizeUrl("https://Example.com:8443/path/"));
    }

    @Test
    void official_invalidUrl_returnsNull() {
        assertNull(OfficialMcpRegistry.normalizeUrl("not a url at all"));
    }

    @Test
    void official_null_returnsNull() {
        assertNull(OfficialMcpRegistry.normalizeUrl(null));
    }

    // ── xaa.ts:61-67 语义：保留 query + 失败 → 原样 strip 尾 / ──
    // Xaa.java 已删除（Q-07 拍板），normalizeUrl 提取至 McpUrlNormalizer.normalizeXaa（同语义，I-4 T8）

    @Test
    void xaa_lowercasesHost_dropsDefaultPort_keepsQuery() {
        // 与 official 差异：不去 query。注意 query 结尾 → .replace(/\/$/,'') 只剥字符串末尾的斜杠，
        // /path/ 后跟 ?q=1 不以斜杠结尾 → 保留（JS URL.href 实测一致）
        assertEquals("https://example.com/path/?q=1",
            McpUrlNormalizer.normalizeXaa("https://Example.COM:443/path/?q=1"));
    }

    @Test
    void xaa_invalidUrl_stripsTrailingSlashOnly() {
        // catch 分支：url.replace(/\/$/, '')
        assertEquals("not a url at all", McpUrlNormalizer.normalizeXaa("not a url at all"));
        assertEquals("http://bad url/", McpUrlNormalizer.normalizeXaa("http://bad url//"));
    }

    @Test
    void xaa_null_returnsEmpty() {
        assertEquals("", McpUrlNormalizer.normalizeXaa(null));
    }

    // ── McpUrlNormalizer 直接验证（两份副本语义分离，规则七） ──

    @Test
    void normalizer_officialAndXaa_failureSemanticsDiffer() {
        assertNull(McpUrlNormalizer.normalizeOfficial("###not-valid###"));
        assertEquals("###not-valid###", McpUrlNormalizer.normalizeXaa("###not-valid###"));
    }
}
