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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片管线三个开关 + smartocr 分支的判别力测试（V286）。
 *
 * <h2>WHY</h2>
 * 这三个开关的失效方式全是<b>静默的</b>：
 * <ul>
 *   <li>开关关了却仍调模型 ⇒ 用户关了「OCR」照样花钱、照样写图内文字；</li>
 *   <li>开关关了但「两个都 false 不建线程池」没实现 ⇒ 表面上返回 0，实际还起池、还查库；</li>
 *   <li>smartocr 判定「无文字」却仍跑 OCR ⇒ 白跑一次调用（这正是 smartocr 唯一的存在理由）；</li>
 *   <li>观察说「有文字」却不 OCR ⇒ 图内文字丢失，检索不到，且零报错。</li>
 * </ul>
 * ⇒ 这里直接数「用了哪条提示词、调了几次」，不看返回值假装通过。
 *
 * <p>⛔ 纯单测：不启 Spring、不连库（mapper / storage / vision 全是桩）。
 */
@DisplayName("[图片管线] caption/OCR 开关 + smartocr 先观察后决定")
class KbImagePipelineSwitchesTest {

    private static final String KB_ID = "k-switches";

    @TempDir
    Path tmp;

    @Nested
    @DisplayName("default 管线：开关各自生效")
    class DefaultPipeline {

        @Test
        @DisplayName("⭐⭐ 两个开关都 false ⇒ 模型调用 0 次、不查分块（不建线程池）")
        void bothOffMeansZeroCalls() throws Exception {
            createImages(3);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(3))));
            StubVision vision = new StubVision(Map.of());

            int done = service(mapper, vision)
                .enrichKnowledge(KB_ID, null, new KbImageSettings(false, false,
                    KbImageSettings.PIPELINE_DEFAULT));

            assertThat(done).isZero();
            assertThat(vision.prompts).as("两个开关都关 ⇒ 一次模型调用都不该有").isEmpty();
            assertThat(mapper.queryCount.get())
                .as("开关全关时应在读分块之前就返回（用户要求：不建线程池）").isZero();
            assertThat(mapper.updates).isEmpty();
        }

        @Test
        @DisplayName("⭐ 只开 caption ⇒ 只调 caption 提示词，ocr_text 保持空")
        void captionOnly() throws Exception {
            createImages(3);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(3))));
            StubVision vision = new StubVision(Map.of("caption", "一张示意图"));

            int done = service(mapper, vision)
                .enrichKnowledge(KB_ID, null, new KbImageSettings(true, false,
                    KbImageSettings.PIPELINE_DEFAULT));

            assertThat(done).isEqualTo(3);
            assertThat(vision.prompts).as("3 张图 × 只 caption = 3 次")
                .hasSize(3).allMatch(p -> p.equals(KbImagePrompts.CAPTION));
            assertThat(vision.prompts).doesNotContain(KbImagePrompts.OCR);
            assertThat(mapper.updates.get(0).getImageInfo())
                .contains("\"caption\":\"一张示意图\"")
                .contains("\"ocr_text\":\"\"");
        }

        @Test
        @DisplayName("⭐ 只开 OCR ⇒ 只调 OCR 提示词，caption 保持原占位")
        void ocrOnly() throws Exception {
            createImages(3);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(3))));
            StubVision vision = new StubVision(Map.of("ocr", "图里的文字"));

            int done = service(mapper, vision)
                .enrichKnowledge(KB_ID, null, new KbImageSettings(false, true,
                    KbImageSettings.PIPELINE_DEFAULT));

            assertThat(done).isEqualTo(3);
            assertThat(vision.prompts).as("3 张图 × 只 OCR = 3 次")
                .hasSize(3).allMatch(p -> p.equals(KbImagePrompts.OCR));
            assertThat(vision.prompts).doesNotContain(KbImagePrompts.CAPTION);
            String json = mapper.updates.get(0).getImageInfo();
            assertThat(json).contains("\"ocr_text\":\"图里的文字\"");
            assertThat(json).as("没开 caption ⇒ 原占位说明不能被改写")
                .contains("\"caption\":\"img0.png\"");
        }

        @Test
        @DisplayName("两个都开 ⇒ 每图两次调用（回归：默认管线与改造前一致）")
        void bothOnIsTwoCallsPerImage() throws Exception {
            createImages(2);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(2))));
            StubVision vision = new StubVision(Map.of("caption", "示意", "ocr", "文字"));

            int done = service(mapper, vision)
                .enrichKnowledge(KB_ID, null, new KbImageSettings(true, true,
                    KbImageSettings.PIPELINE_DEFAULT));

            assertThat(done).isEqualTo(2);
            assertThat(vision.prompts).hasSize(4);
        }
    }

    @Nested
    @DisplayName("smartocr 管线：先观察后决定")
    class SmartOcrPipeline {

        @Test
        @DisplayName("⭐⭐ 观察判无文字 ⇒ 不 OCR（每图只 1 次调用），描述照常落库")
        void observeNoTextSkipsOcr() throws Exception {
            createImages(2);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(2))));
            StubVision vision = new StubVision(Map.of(
                "observe", "HAS_TEXT: no\nCAPTION: 三根柱子的示意图"));

            int done = service(mapper, vision)
                .enrichKnowledge(KB_ID, null, new KbImageSettings(true, true,
                    KbImageSettings.PIPELINE_SMART_OCR));

            assertThat(vision.prompts).as("2 张图 × 只观察 = 2 次，⛔ 不能跑 OCR")
                .hasSize(2).allMatch(p -> p.equals(KbImagePrompts.OBSERVE));
            assertThat(vision.prompts).doesNotContain(KbImagePrompts.OCR);
            assertThat(done).isEqualTo(2);
            assertThat(mapper.updates.get(0).getImageInfo())
                .contains("\"caption\":\"三根柱子的示意图\"")
                .contains("\"ocr_text\":\"\"");
        }

        @Test
        @DisplayName("⭐⭐ 观察说有文字 ⇒ 再跑 OCR，图内文字落库")
        void observeHasTextRunsOcr() throws Exception {
            createImages(1);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(1))));
            StubVision vision = new StubVision(Map.of(
                "observe", "HAS_TEXT: yes\nCAPTION: 设备铭牌照片",
                "ocr", "型号 XJ-200 额定功率 5.5kW"));

            int done = service(mapper, vision)
                .enrichKnowledge(KB_ID, null, new KbImageSettings(true, true,
                    KbImageSettings.PIPELINE_SMART_OCR));

            assertThat(vision.prompts).as("1 张图：观察 + OCR = 2 次")
                .hasSize(2)
                .containsExactly(KbImagePrompts.OBSERVE, KbImagePrompts.OCR);
            assertThat(done).isEqualTo(1);
            assertThat(mapper.updates.get(0).getImageInfo())
                .contains("\"caption\":\"设备铭牌照片\"")
                .contains("型号 XJ-200 额定功率 5.5kW");
        }

        @Test
        @DisplayName("⭐ smartocr + OCR 开关关 ⇒ 观察到有文字也不 OCR")
        void smartOcrRespectsOcrSwitch() throws Exception {
            createImages(2);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(2))));
            StubVision vision = new StubVision(Map.of(
                "observe", "HAS_TEXT: yes\nCAPTION: 含文字的截图"));

            int done = service(mapper, vision)
                .enrichKnowledge(KB_ID, null, new KbImageSettings(true, false,
                    KbImageSettings.PIPELINE_SMART_OCR));

            assertThat(vision.prompts).as("只有观察调用").hasSize(2)
                .allMatch(p -> p.equals(KbImagePrompts.OBSERVE));
            assertThat(vision.prompts).doesNotContain(KbImagePrompts.OCR);
            assertThat(done).isEqualTo(2);
            assertThat(mapper.updates.get(0).getImageInfo()).contains("\"ocr_text\":\"\"");
        }

        @Test
        @DisplayName("⭐ 观察回包没按格式给 HAS_TEXT ⇒ 按「有文字」继续 OCR（不静默丢字）")
        void unformattedObservationStillRunsOcr() throws Exception {
            createImages(1);
            MapperStub mapper = new MapperStub(List.of(chunk(imageInfo(1))));
            StubVision vision = new StubVision(Map.of(
                "observe", "这是一张设备铭牌照片。",
                "ocr", "型号 XJ-200"));

            service(mapper, vision).enrichKnowledge(KB_ID, null,
                new KbImageSettings(true, true, KbImageSettings.PIPELINE_SMART_OCR));

            assertThat(vision.prompts).contains(KbImagePrompts.OCR);
        }
    }

    @Nested
    @DisplayName("观察回包解析")
    class ObserveParsing {

        @Test
        @DisplayName("标准两行 + 英文/中文标记 + 大小写")
        void standard() {
            KbImagePrompts.Observed o = KbImagePrompts.parseObserve(
                "HAS_TEXT: yes\nCAPTION: 某厂区的平面图");
            assertThat(o.hasText()).isTrue();
            assertThat(o.caption()).isEqualTo("某厂区的平面图");

            KbImagePrompts.Observed n = KbImagePrompts.parseObserve(
                "has_text: NO\ncaption：一张风景照");
            assertThat(n.hasText()).isFalse();
            assertThat(n.caption()).isEqualTo("一张风景照");
        }

        @Test
        @DisplayName("中文标记 / 全角冒号 / 模型回包加粗")
        void chineseKeys() {
            KbImagePrompts.Observed o = KbImagePrompts.parseObserve(
                "是否有文字：有\n描述：一张流程图");
            assertThat(o.hasText()).isTrue();
            assertThat(o.caption()).isEqualTo("一张流程图");

            KbImagePrompts.Observed m = KbImagePrompts.parseObserve(
                "**HAS_TEXT**: no\n**CAPTION**: 纯装饰图");
            assertThat(m.hasText()).isFalse();
            assertThat(m.caption()).isEqualTo("纯装饰图");
        }

        @Test
        @DisplayName("⭐ 没有 HAS_TEXT 标记 ⇒ 视为有文字（宁可多跑一次 OCR）")
        void missingMarkerDefaultsToHasText() {
            KbImagePrompts.Observed o = KbImagePrompts.parseObserve("一张没有遵循格式的说明。");
            assertThat(o.hasText()).isTrue();
            assertThat(o.caption()).isEqualTo("一张没有遵循格式的说明。");
        }

        @Test
        @DisplayName("⭐ 空白回包 ⇒ 无描述、无属性；属性全缺 ⇒ 默认兜底继续 OCR")
        void blankReply() {
            assertThat(KbImagePrompts.parseObserve(null).attrs()).isEmpty();
            assertThat(KbImagePrompts.parseObserve("   ").attrs()).isEmpty();
            assertThat(KbImagePrompts.parseObserve("   ").caption()).isNull();
            assertThat(KbImagePrompts.parseObserve("   ").hasText())
                .as("一条可用属性都没有 ⇒ 按默认 ocr_on_unobserved=true 继续 OCR")
                .isTrue();
        }
    }

    // ── 组装 / 桩 ───────────────────────────────────────────────────

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

    /** 记录「用了哪条提示词」并按提示词返回不同应答的视觉桩。 */
    private static final class StubVision implements KbVisionCaller {

        final List<String> prompts = new CopyOnWriteArrayList<>();
        private final Map<String, String> replies;

        StubVision(Map<String, String> replies) {
            this.replies = replies;
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
        final List<KbChunkRecord> inserts = new CopyOnWriteArrayList<>();
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
                        // ⭐ 图片独立子块写入（V-E5）：开关用例不关心子块内容，收下即可
                        case "insert":
                            inserts.add((KbChunkRecord) args[0]);
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
