package com.nexusai.kb.app.ingest;

import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.app.chunk.KbSplitterConfig;
import com.nexusai.kb.app.chunk.KbTextSplitter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行尾归一（任务 0）的意图测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：{@code KbTextSplitter.split} <b>不</b>内部归一行尾
 * （见 {@code KbTextSplitter.java:360} 注释：不改文本以免破坏调用方坐标），而图片归属
 * {@code assignImages} 用「页标题在原文里的字符偏移」定位。若切分与归属拿到不同的行尾形态，
 * 块边界与图片锚点就落在两套坐标系上 ⇒ <b>同一份内容因上传方式（浏览器粘贴 LF / 文件上传 CRLF）
 * 把图片挂到不同块</b>，而且没有任何报错。
 *
 * <p><b>判据</b>：拿同一逻辑内容的 LF 版与 CRLF 版，分别走「切分 + 归属」，
 * 断言两者得到<b>相同的块数、相同的块文本、相同的图片归属</b>。若把上游归一删掉，CRLF 版的
 * 块边界会整体漂移，本测试变红。
 */
@DisplayName("[P0-E 任务0] 行尾归一：图片归属不随 CRLF 漂移")
class KbLineEndingNormalizationTest {

    /**
     * 4 页 markdown · 每页 6 行正文（>512 字 ⇒ 必然切成多块）。
     *
     * <p>⚠️ 行数固定为 6 是刻意的：实测 6 行时「第 3 页」在 CRLF 下落到 block 1、
     * 在 LF 下落到 block 0 —— 正是要守护的那个漂移点。
     */
    private static String doc(String nl) {
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= 4; p++) {
            sb.append("## 第 ").append(p).append(" 页").append(nl).append(nl);
            for (int i = 1; i <= 6; i++) {
                sb.append("第 ").append(p).append(" 页第 ").append(i)
                    .append(" 行正文内容，用于把文档撑到多块，验证块边界是否随行尾漂移。").append(nl);
            }
            sb.append(nl);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("归一行尾后：块边界与图片归属与 LF 版逐项一致")
    void normalizationKeepsChunkBoundariesAndImageOwnership() {
        String lf = doc("\n");
        String crlf = doc("\r\n");

        // ⭐ 这就是 KnowledgeParseProcessor 现在的做法：切分与归属都用归一文
        String normalized = KbTextSplitter.normalizeLineEndings(crlf);

        assertThat(KnowledgeParseProcessor.countCr(crlf))
            .as("夹具必须真的含 CR，否则本测试在守护空气")
            .isGreaterThan(0);
        assertThat(normalized).isEqualTo(lf);

        KbSplitterConfig cfg = KbSplitterConfig.defaults();
        List<KbChunk> fromLf = KbTextSplitter.split(lf, cfg);
        List<KbChunk> fromCrlfNormalized = KbTextSplitter.split(normalized, cfg);

        assertThat(fromCrlfNormalized).as("块数必须一致").hasSameSizeAs(fromLf);
        for (int i = 0; i < fromLf.size(); i++) {
            assertThat(fromCrlfNormalized.get(i).start()).isEqualTo(fromLf.get(i).start());
            assertThat(fromCrlfNormalized.get(i).end()).isEqualTo(fromLf.get(i).end());
            assertThat(fromCrlfNormalized.get(i).content()).isEqualTo(fromLf.get(i).content());
        }

        // 图片归属（PDF：按 "## 第 N 页" 定位）
        List<KbChunk.Image> images = new ArrayList<>();
        for (int page = 1; page <= 4; page++) {
            images.add(pageImage(page));
        }
        Map<Integer, Integer> seqByPageLf = ownership(fromLf, lf, images);
        Map<Integer, Integer> seqByPageCrlf = ownership(fromCrlfNormalized, normalized, images);

        assertThat(seqByPageCrlf)
            .as("同一份内容：CRLF 上传与 LF 上传必须把每页的图挂到同一块")
            .isEqualTo(seqByPageLf);
    }

    @Test
    @DisplayName("反证：不归一（把 split 与 assignImages 都喂原文 CRLF）会得出不同块边界")
    void withoutNormalizationBoundariesDrift() {
        String lf = doc("\n");
        String crlf = doc("\r\n");
        KbSplitterConfig cfg = KbSplitterConfig.defaults();

        List<KbChunk> fromLf = KbTextSplitter.split(lf, cfg);
        List<KbChunk> fromCrlf = KbTextSplitter.split(crlf, cfg);

        // 内容整体变长（每行多一个 \r）⇒ 块边界必然不同。
        // 这条断言的意义：证明本测试的夹具真的有判别力（不是「两种输入本来就一样」）。
        boolean boundariesDiffer = fromLf.size() != fromCrlf.size();
        for (int i = 0; i < Math.min(fromLf.size(), fromCrlf.size()); i++) {
            if (fromLf.get(i).start() != fromCrlf.get(i).start()) {
                boundariesDiffer = true;
            }
        }
        assertThat(boundariesDiffer)
            .as("若 CRLF 与 LF 得出完全相同的块边界，说明夹具不足以证明归一的价值")
            .isTrue();
    }

    // ── 与 KnowledgeParseProcessor.assignImages 同口径的最小复刻 ──────────────

    private static KbChunk.Image pageImage(int page) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("page_index", page);
        return new KbChunk.Image("k" + page, "u" + page, "", "", "",
            Map.copyOf(attrs), 1);
    }

    /** 页号 → 所属块 seq（复刻 chunkSeqAt：start <= pos < end，否则取之前最近的块）。 */
    private static Map<Integer, Integer> ownership(List<KbChunk> chunks, String text,
                                                  List<KbChunk.Image> images) {
        Map<Integer, Integer> out = new HashMap<>();
        for (KbChunk.Image img : images) {
            int page = (int) img.attrs().get("page_index");
            int charPos = text.indexOf("## 第 " + page + " 页");
            int cpPos = charPos < 0 ? 0 : text.codePointCount(0, charPos);
            out.put(page, seqAt(chunks, cpPos));
        }
        return out;
    }

    private static int seqAt(List<KbChunk> chunks, int cpPos) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        for (KbChunk c : chunks) {
            if (c.start() <= cpPos && cpPos < c.end()) {
                return c.seq();
            }
        }
        int best = 0;
        for (KbChunk c : chunks) {
            if (c.start() <= cpPos) {
                best = c.seq();
            }
        }
        return best;
    }
}
