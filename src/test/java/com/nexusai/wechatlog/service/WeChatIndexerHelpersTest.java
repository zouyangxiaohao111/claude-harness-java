package com.nexusai.wechatlog.service;

import com.nexusai.wechatlog.store.ZstdDecompressor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 索引写入的纯函数部分与 zstd 探测的测试。
 *
 * <h2>⚠️ 为什么不测 indexer.index()</h2>
 * {@link WeChatMessageIndexer} 依赖 {@code JdbcTemplate}（Spring bean）。
 * 项目规则：{@code @SpringBootTest} 会迁移用户真库，一律不跑。
 * 所以这里只测能纯函数化的部分（截断 / 转义 / zstd 探测），
 * SQL 本身另有迁移文件的 sqlite3 执行验证。
 */
class WeChatIndexerHelpersTest {

    @Test
    @DisplayName("preview 截断到 120 并压平空白")
    void previewTruncatesAndFlattens() {
        String longText = "a".repeat(300);
        String p = WeChatMessageIndexer.preview(longText);
        assertTrue(p.length() <= 121, "应截断（含省略号最多 121）: " + p.length());
        assertTrue(p.endsWith("…"), "截断后应带省略号");

        assertEquals("a b c", WeChatMessageIndexer.preview("  a\n\tb   c  "));
        assertNull(WeChatMessageIndexer.preview(null) == null ? null : null);
        assertEquals("", WeChatMessageIndexer.preview(null));
    }

    @Test
    @DisplayName("searchText 截断到 500 但不加省略号（回收索引用）")
    void searchTextTruncatesWithoutEllipsis() {
        String longText = "字".repeat(1000);
        String s = WeChatMessageIndexer.searchText(longText);
        assertEquals(500, s.length(), "应正好截到 500");
        assertFalse(s.endsWith("…"), "检索文本不加省略号");
    }

    @Test
    @DisplayName("LIKE 转义：% 和 _ 不能当通配符")
    void escapesLikeWildcards() {
        assertEquals("100\\%", WeChatMessageIndexer.escapeLike("100%"));
        assertEquals("a\\_b", WeChatMessageIndexer.escapeLike("a_b"));
        assertEquals("c\\\\d", WeChatMessageIndexer.escapeLike("c\\d"));
    }

    @Test
    @DisplayName("zstd 魔数识别：只有 28 b5 2f fd 开头才算压缩")
    void detectsZstdMagic() {
        assertTrue(ZstdDecompressor.looksCompressed(
                new byte[]{0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0x00}));
        assertFalse(ZstdDecompressor.looksCompressed(
                "普通文本".getBytes(StandardCharsets.UTF_8)));
        assertFalse(ZstdDecompressor.looksCompressed(null));
        assertFalse(ZstdDecompressor.looksCompressed(new byte[]{0x28}));
    }

    @Test
    @DisplayName("非压缩数据按 UTF-8 直接解码（这是最常见的情况）")
    void decodesPlainText() {
        String text = "你好，世界";
        assertEquals(text, ZstdDecompressor.toText(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals("", ZstdDecompressor.toText(null));
        assertEquals("", ZstdDecompressor.toText(new byte[0]));
    }

    @Test
    @DisplayName("压缩标记但解不出时返回空串，而不是把二进制当文本吐乱码")
    void doesNotReturnGarbageForUndecodableZstd() {
        // 伪造一个 zstd 魔数 + 垃圾体
        byte[] fake = new byte[]{0x28, (byte) 0xb5, 0x2f, (byte) 0xfd,
                0x01, 0x02, 0x03, 0x04, 0x05};
        String out = ZstdDecompressor.toText(fake);
        if (!ZstdDecompressor.isAvailable()) {
            assertEquals("", out, "无 zstd 库时应返回空串（明确告知无法解压）");
        } else {
            // 有 zstd 库时解压失败也应得到空串，而不是乱码
            assertEquals("", out, "解压失败应返回空串");
        }
    }

    @Test
    @DisplayName("zstd 可用性可查询（部署侧据此判断是否需要加依赖）")
    void reportsAvailability() {
        // 不断言具体真假（取决于类路径），只断言可调用且稳定
        boolean a = ZstdDecompressor.isAvailable();
        boolean b = ZstdDecompressor.isAvailable();
        assertEquals(a, b);
    }
}
