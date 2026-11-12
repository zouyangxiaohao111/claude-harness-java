package com.nexusai.kb.app.image;

import com.nexusai.kb.app.config.LocalFileStorage;
import com.nexusai.kb.app.parser.KbParseContext;
import com.nexusai.kb.app.parser.KbVisionCaller;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * smartocr 图片管线的「观察属性 ⇒ 决定要不要花第二次调用做 OCR」判别力测试。
 *
 * <h2>WHY（为什么不能用旧用例替代）</h2>
 * 旧实现只判「有没有文字」（{@code HAS_TEXT: yes|no}），等于<b>永远走 OCR</b>：
 * 图表/照片也各多花一次调用，而「观察失败」这类回包被当成「有文字」——
 * 恰好把源站 {@code ocr_on_unobserved} 这条兜底条款掩盖掉了。
 * ⇒ 这里逐条钉住源站 {@code DecideOCR} 的三种结论：
 * <ul>
 *   <li>{@code contain.text == block} 或 {@code contain.data_visual == true} ⇒ OCR；</li>
 *   <li>策略读的属性都观察到了且都为否定 ⇒ 跳过 OCR；</li>
 *   <li>有属性<b>没观察到</b>（观察失败 / 空白 / 只答一半）⇒ 看
 *       {@code ocr_on_unobserved}（默认 true = 继续 OCR）。</li>
 * </ul>
 *
 * <p>⛔ 纯单测：不启 Spring、不连库（mapper / storage / vision 全是桩）。
 */
@DisplayName("[图片属性] smartocr 先观察属性、再决定是否 OCR")
class KbImageAttrDecisionTest {

    private static final String KB_ID = "k-attr";

    @TempDir
    Path tmp;

    @Nested
    @DisplayName("属性判定纯函数（源站 DecideOCR 等价）")
    class DecisionTable {

        @Test
        @DisplayName("contain.text=block ⇒ OCR")
        void blockTextRunsOcr() {
            assertThat(KbImageAttrDecision.decideOcr(
                Map.of("contain.text", "block", "contain.data_visual", false), false))
                .as("有正文的图必须读，即使 ocr_on_unobserved=false")
                .isTrue();
        }

        @Test
        @DisplayName("contain.data_visual=true ⇒ OCR（图表文字 sparse 也要读）")
        void dataVisualRunsOcr() {
            assertThat(KbImageAttrDecision.decideOcr(
                Map.of("contain.text", "sparse", "contain.data_visual", true), false))
                .isTrue();
        }

        @Test
        @DisplayName("两个属性都观察到且都为否定 ⇒ 跳过 OCR")
        void observedNegativeSkipsOcr() {
            assertThat(KbImageAttrDecision.decideOcr(
                Map.of("contain.text", "none", "contain.data_visual", false), true))
                .as("这是「观察到了否定」，不是「没观察到」⇒ 兜底开关不该把它翻成 OCR")
                .isFalse();
        }

        @Test
        @DisplayName("属性没观察到 ⇒ 看 ocr_on_unobserved（true 继续 / false 跳过）")
        void unobservedHonorsFallback() {
            Map<String, Object> attrs = Map.of("contain.text", "sparse");
            assertThat(KbImageAttrDecision.decideOcr(attrs, true))
                .as("默认 true：宁可多跑一次，也不静默丢字").isTrue();
            assertThat(KbImageAttrDecision.decideOcr(attrs, false))
                .as("显式关掉兜底 ⇒ 没答案就不读").isFalse();
            assertThat(KbImageAttrDecision.decideOcr(Map.of(), true))
                .as("观察整体失败（无任何属性）也走兜底").isTrue();
        }
    }

    @Nested
    @DisplayName("观察回包解析（源站属性行协议）")
    class Parsing {

        @Test
        @DisplayName("标准属性行 + DESCRIPTION")
        void standardLines() {
            KbImagePrompts.Observed o = KbImagePrompts.parseObserve(
                "contain.text: block\ncontain.data_visual: true\nDESCRIPTION: 设备铭牌照片");
            assertThat(o.attrs())
                .containsEntry("contain.text", "block")
                .containsEntry("contain.data_visual", true);
            assertThat(o.caption()).isEqualTo("设备铭牌照片");
            assertThat(o.observed()).isTrue();
        }

        @Test
        @DisplayName("下划线写法 / 大小写 / Markdown 前缀都归一")
        void normalizedKeys() {
            KbImagePrompts.Observed o = KbImagePrompts.parseObserve(
                "- **Contain_Text**: BLOCK\n* Contain.Data_Visual: FALSE");
            assertThat(o.attrs())
                .containsEntry("contain.text", "block")
                .containsEntry("contain.data_visual", false);
        }

        @Test
        @DisplayName("值不在封闭集 ⇒ 属性保持缺失（⛔ 不填默认值）")
        void illegalValueStaysAbsent() {
            KbImagePrompts.Observed o = KbImagePrompts.parseObserve(
                "contain.text: maybe_a_lot\nDESCRIPTION: 一张图");
            assertThat(o.attrs()).doesNotContainKey("contain.text");
            assertThat(o.observed()).as("没有一行可用属性").isFalse();
            assertThat(o.caption()).isEqualTo("一张图");
        }

        @Test
        @DisplayName("空白 / 纯散文回包 ⇒ 无属性（是否 OCR 交给兜底）")
        void proseOrBlankHasNoAttrs() {
            assertThat(KbImagePrompts.parseObserve(null).attrs()).isEmpty();
            assertThat(KbImagePrompts.parseObserve("   ").attrs()).isEmpty();
            KbImagePrompts.Observed prose = KbImagePrompts.parseObserve("这是一张设备铭牌照片。");
            assertThat(prose.attrs()).isEmpty();
            assertThat(prose.caption()).isEqualTo("这是一张设备铭牌照片。");
        }
    }

    @Nested
    @DisplayName("端到端：观察属性决定第二次调用")
    class EndToEnd {

        @Test
        @DisplayName("⭐ 有正文（contain.text=block）⇒ 再跑 OCR，文字落库")
        void blockRunsOcr() throws Exception {
            createImages(1);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(1))));
            StubVision vision = new StubVision(Map.of(
                "observe", "contain.text: block\ncontain.data_visual: false\nDESCRIPTION: 设备铭牌照片",
                "ocr", "型号 XJ-200 额定功率 5.5kW"));

            int done = service(mapper, vision).enrichKnowledge(KB_ID, null, smartOcr(true));

            assertThat(vision.prompts).as("观察 + OCR = 2 次")
                .containsExactly(KbImagePrompts.OBSERVE, KbImagePrompts.OCR);
            assertThat(done).isEqualTo(1);
            assertThat(mapper.updates.get(0).getImageInfo())
                .contains("\"caption\":\"设备铭牌照片\"")
                .contains("型号 XJ-200 额定功率 5.5kW");
        }

        @Test
        @DisplayName("⭐⭐ 无正文（text=none / data_visual=false）⇒ 跳过 OCR（每图只 1 次调用）")
        void noneSkipsOcr() throws Exception {
            createImages(2);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(2))));
            StubVision vision = new StubVision(Map.of(
                "observe", "contain.text: none\ncontain.data_visual: false\nDESCRIPTION: 一张风景照"));

            int done = service(mapper, vision).enrichKnowledge(KB_ID, null, smartOcr(true));

            assertThat(vision.prompts).as("2 张图 × 只有观察 = 2 次")
                .hasSize(2).allMatch(p -> p.equals(KbImagePrompts.OBSERVE));
            assertThat(vision.prompts).doesNotContain(KbImagePrompts.OCR);
            assertThat(done).isEqualTo(2);
            assertThat(mapper.updates.get(0).getImageInfo())
                .contains("\"caption\":\"一张风景照\"")
                .contains("\"ocr_text\":\"\"");
        }

        @Test
        @DisplayName("⭐⭐ 观察调用失败 + 默认 ocr_on_unobserved=true ⇒ 兜底继续 OCR")
        void observationFailureFallsBackToOcr() throws Exception {
            createImages(1);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(1))));
            StubVision vision = new StubVision(Map.of("ocr", "图内文字"));
            vision.throwOnObserve = true;

            int done = service(mapper, vision).enrichKnowledge(KB_ID, null, smartOcr(true));

            assertThat(vision.prompts).as("观察失败后仍要 OCR")
                .containsExactly(KbImagePrompts.OBSERVE, KbImagePrompts.OCR);
            assertThat(done).isEqualTo(1);
            assertThat(mapper.updates.get(0).getImageInfo()).contains("\"ocr_text\":\"图内文字\"");
        }

        @Test
        @DisplayName("⭐ 观察失败 + ocr_on_unobserved=false ⇒ 不兜底，跳过 OCR")
        void observationFailureHonorsDisabledFallback() throws Exception {
            createImages(1);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(1))));
            StubVision vision = new StubVision(Map.of("ocr", "图内文字"));
            vision.throwOnObserve = true;

            service(mapper, vision).enrichKnowledge(KB_ID, null, smartOcr(false));

            assertThat(vision.prompts).as("兜底显式关闭 ⇒ 只有那一次观察调用")
                .hasSize(1).allMatch(p -> p.equals(KbImagePrompts.OBSERVE));
            assertThat(vision.prompts).doesNotContain(KbImagePrompts.OCR);
        }

        @Test
        @DisplayName("⭐ 回包解析不出属性（纯散文）⇒ 走兜底继续 OCR")
        void unparseableFallsBackToOcr() throws Exception {
            createImages(1);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(1))));
            StubVision vision = new StubVision(Map.of(
                "observe", "这是一张设备铭牌照片。", "ocr", "型号 XJ-200"));

            service(mapper, vision).enrichKnowledge(KB_ID, null, smartOcr(true));

            assertThat(vision.prompts).contains(KbImagePrompts.OCR);
        }

        @Test
        @DisplayName("⭐ 只观察到一半（缺 data_visual）⇒ 也算「没答案」，默认继续 OCR")
        void partialObservationFallsBackToOcr() throws Exception {
            createImages(1);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(1))));
            StubVision vision = new StubVision(Map.of(
                "observe", "contain.text: sparse\nDESCRIPTION: 路牌照片", "ocr", "限速 40"));

            service(mapper, vision).enrichKnowledge(KB_ID, null, smartOcr(true));

            assertThat(vision.prompts)
                .as("sparse 没命中，但 data_visual 没答 ⇒ 不能当成自信的否定")
                .contains(KbImagePrompts.OCR);
        }

        @Test
        @DisplayName("⭐⭐ default 管线不受影响：仍是 caption + OCR 各一次")
        void defaultPipelineUnaffected() throws Exception {
            createImages(2);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(2))));
            StubVision vision = new StubVision(Map.of("caption", "示意", "ocr", "文字"));

            int done = service(mapper, vision).enrichKnowledge(KB_ID, null,
                new KbImageSettings(true, true, KbImageSettings.PIPELINE_DEFAULT));

            assertThat(done).isEqualTo(2);
            assertThat(vision.prompts).as("2 张图 × (caption + OCR) = 4 次，且不碰观察提示词")
                .hasSize(4).doesNotContain(KbImagePrompts.OBSERVE);
        }
    }

    // ── 组装 / 桩 ───────────────────────────────────────────────────

    private static KbImageSettings smartOcr(boolean ocrOnUnobserved) {
        return new KbImageSettings(true, true,
            KbImageSettings.PIPELINE_SMART_OCR, ocrOnUnobserved);
    }

    private KbImageEnrichService service(MapperStub mapper, StubVision vision) {
        KbImageStorage storage = new KbImageStorage((LocalFileStorage) null) {
            @Override
            public Path localPath(String storageKey) {
                return tmp.resolve(storageKey);
            }
        };
        return new KbImageEnrichService(mapper.asMapper(), storage, vision);
    }

    private void createImages(int n) throws Exception {
        Path dir = tmp.resolve(KB_ID);
        Files.createDirectories(dir);
        for (int i = 0; i < n; i++) {
            Files.write(dir.resolve("img" + i + ".png"), new byte[]{1, 2, 3});
        }
    }

    private static KbChunkRecord chunk(String imageInfo) {
        KbChunkRecord c = new KbChunkRecord();
        c.setId("c1");
        c.setKnowledgeId(KB_ID);
        c.setChunkIndex(0);
        c.setChunkType("image");
        c.setImageInfo(imageInfo);
        return c;
    }

    /** 造 n 张图的 image_info（每张带 attrs.storage_key + 文件名式占位 caption）。 */
    private static String imageInfo(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"url\":\"/api/v1/kb/images/").append(KB_ID).append("/img").append(i).append(".png\",")
                .append("\"caption\":\"img").append(i).append(".png\",")
                .append("\"ocr_text\":\"\",")
                .append("\"attrs\":{\"storage_key\":\"").append(KB_ID).append("/img").append(i).append(".png\"}}");
        }
        return sb.append(']').toString();
    }

    /** 记录提示词并按提示词返回应答的视觉桩；可让观察调用抛异常。 */
    private static final class StubVision implements KbVisionCaller {

        final List<String> prompts = new CopyOnWriteArrayList<>();
        private final Map<String, String> replies;
        volatile boolean throwOnObserve;

        StubVision(Map<String, String> replies) {
            this.replies = new HashMap<>(replies);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describeImage(Path imageFile, String prompt, KbParseContext ctx) {
            throw new UnsupportedOperationException("生产代码应走 describeImageWithModel");
        }

        @Override
        public String describeImageWithModel(Path imageFile, String prompt, KbParseContext ctx,
                                             String modelIdOrName) {
            prompts.add(prompt);
            String key = prompt.equals(KbImagePrompts.CAPTION) ? "caption"
                : prompt.equals(KbImagePrompts.OCR) ? "ocr" : "observe";
            if (throwOnObserve && "observe".equals(key)) {
                throw new IllegalStateException("模拟观察调用超时");
            }
            String reply = replies.get(key);
            if (reply == null) {
                throw new IllegalStateException("本用例未准备该提示词的应答: " + key);
            }
            return reply;
        }
    }

    /** KbChunkMapper 动态代理桩（只实现本用例走到的两个方法）。 */
    private static final class MapperStub {

        final List<KbChunkRecord> chunks;
        final List<KbChunkRecord> updates = new CopyOnWriteArrayList<>();
        final AtomicInteger queryCount = new AtomicInteger();

        MapperStub(List<KbChunkRecord> chunks) {
            this.chunks = new ArrayList<>(chunks);
        }

        KbChunkMapper asMapper() {
            return (KbChunkMapper) Proxy.newProxyInstance(
                KbChunkMapper.class.getClassLoader(),
                new Class<?>[]{KbChunkMapper.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "selectListByQuery":
                            queryCount.incrementAndGet();
                            return chunks;
                        case "update":
                            updates.add((KbChunkRecord) args[0]);
                            return 1;
                        case "insert":
                            return 1;
                        case "deleteByQuery":
                            return 0;
                        case "toString":
                            return "MapperStub";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                "MapperStub 未实现：" + method.getName());
                    }
                });
        }
    }
}
