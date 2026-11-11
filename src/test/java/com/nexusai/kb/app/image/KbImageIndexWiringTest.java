package com.nexusai.kb.app.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接线守卫 · 图内文字必须真的进索引（源码级断言）。
 *
 * <h2>为什么不能用行为测试</h2>
 * {@link KbImageEnrichService#imageTextForIndex} 是纯函数，单独测它永远绿 ——
 * 但真正决定「OCR 有没有用」的是 {@code KbIndexWriter} 有没有<b>调用</b>它。
 * 实测过：把 {@code indexTextOf} 里那一行删掉，纯函数测试<b>全绿</b>，
 * 而功能已经完全失效（文字在库里、检索命中不到，没有任何报错）。
 *
 * <p>本仓禁 {@code @SpringBootTest}（会迁移用户真库），跑不了端到端 ⇒
 * 用源码级断言钉住这两处调用点。
 */
class KbImageIndexWiringTest {

    /** 仓库根（surefire 的 user.dir = backend/）。 */
    private static final Path INDEX_WRITER = Path.of(
        "src", "main", "java", "com", "nexusai", "kb", "app", "retrieval", "KbIndexWriter.java");

    private static String source() throws IOException {
        assertThat(INDEX_WRITER).as("找不到 KbIndexWriter.java（相对路径应按 backend/ 解析）")
            .exists();
        return Files.readString(INDEX_WRITER, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("⭐⭐ 向量索引文本必须拼接图内文字")
    void embeddingTextIncludesImageText() throws IOException {
        String s = source();
        int i = s.indexOf("private static String indexTextOf(KbChunkRecord c)");
        assertThat(i).as("找不到 indexTextOf").isGreaterThan(0);
        String body = s.substring(i, Math.min(i + 600, s.length()));
        assertThat(body)
            .as("indexTextOf 没拼 imageTextForIndex ⇒ 图内文字永远进不了向量索引"
                + "（OCR 白跑，且库里明明有字就是搜不到）")
            .contains("imageTextForIndex");
    }

    @Test
    @DisplayName("⭐⭐ FTS 关键词索引也必须拼接图内文字（⛔ 只修向量路会半边生效）")
    void ftsIncludesImageText() throws IOException {
        String s = source();
        int i = s.indexOf("private void writeFts(");
        assertThat(i).as("找不到 writeFts").isGreaterThan(0);
        String body = s.substring(i, Math.min(i + 1800, s.length()));
        assertThat(body)
            .as("writeFts 没拼 imageTextForIndex ⇒ 向量能召回、关键词召回不到（两路不一致）")
            .contains("imageTextForIndex");
    }

    @Test
    @DisplayName("⭐ imageTextForIndex 必须是唯一的拼接来源（⛔ 两条路各自拼会让口径漂移）")
    void singleSourceOfTruth() throws IOException {
        String s = source();
        int count = s.split("imageTextForIndex\\(", -1).length - 1;
        // 两处调用（向量 + FTS）。再多说明有人又手写了一份拼接逻辑。
        assertThat(count)
            .as("imageTextForIndex 的调用点应为 2 处（向量 + FTS），实际 " + count)
            .isEqualTo(2);
    }
}
