package com.nexusai.kb.app.image;

import com.nexusai.kb.app.config.LocalFileStorage;
import com.nexusai.kb.app.parser.KbParseContext;
import com.nexusai.kb.app.parser.KbVisionCaller;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片理解 · <b>模型调用并发 / 写库串行</b>的判别力测试。
 *
 * <h2>WHY（这条链最容易「看起来改了、其实没改」）</h2>
 * <ul>
 *   <li><b>并发是效果，不是代码形态</b>：把 for 循环换成 {@code submit(...)} 也可能因为
 *       池大小 = 1、或调用仍在主线程里执行，而<b>实际全串行</b>（用户看到的是「还是一样的慢」）。
 *       所以这里用<b>计数桩</b>直接量「最大同时调用数」。</li>
 *   <li><b>写库必须串行</b>：SQLite 单写锁，多个工作线程同时 UPDATE 会撞
 *       {@code SQLITE_BUSY}（本仓已真实踩过）。这里记录每次 UPDATE 的线程名，
 *       断言只有<b>一个</b>线程写库，且就是调用方线程。</li>
 *   <li><b>fail-soft 不能被并发改坏</b>：一张图抛异常，其它图仍要成功，返回值只数成功数。</li>
 * </ul>
 *
 * <p>⛔ 纯单测：不启 Spring、不连库（mapper / storage / vision 全是桩）。
 */
@DisplayName("[图片理解] 模型调用并发 + 写库串行")
class KbImageEnrichConcurrencyTest {

    private static final String KB_ID = "k-concurrency";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("⭐⭐ 多图时模型调用确实并发（最大同时调用数 > 1），且 UPDATE 只在调用线程上发生")
    void modelCallsRunConcurrentlyAndWritesStaySerial() throws Exception {
        createImages(4);
        MapperStub mapper = new MapperStub(List.of(chunk("c1", imageInfo(4))));
        CountingVision vision = new CountingVision();

        int done = service(mapper, localStorage(), vision).enrichKnowledge(KB_ID, null);

        assertThat(vision.maxInFlight.get())
            .as("并发度默认 8 + 4 张图 ⇒ 应有多张图同时在模型调用中；"
                + "若为 1 说明又退回串行（这正是本用例要拦的回归）")
            .isGreaterThan(1);
        assertThat(vision.overlapTimeouts.get())
            .as("串行执行会让「等第二个调用到达」超时")
            .isZero();
        assertThat(vision.calls.get())
            .as("4 张图 × (caption + OCR) = 8 次模型调用（提示词/调用次数不能变）")
            .isEqualTo(8);
        assertThat(done).as("4 张图全部成功").isEqualTo(4);

        assertThat(mapper.updates).as("一个分块只 UPDATE 一次").hasSize(1);
        assertThat(mapper.updateThreads).as("写库必须串行（单一线程）").hasSize(1);
        assertThat(mapper.updateThreads.get(0))
            .as("写库必须发生在调用方线程，而不是线程池里的工作线程")
            .isEqualTo(Thread.currentThread().getName());

        String json = mapper.updates.get(0).getImageInfo();
        assertThat(json).contains("caption:img0.png").contains("ocr:img0.png")
            .contains("caption:img3.png").contains("ocr:img3.png");

        assertThat(hasLiveEnrichThread())
            .as("方法返回后线程池必须已 shutdown，不允许残留 kb-image-enrich-* 线程")
            .isFalse();
    }

    @Test
    @DisplayName("⭐⭐ 单图异常不影响其它图（fail-soft），返回值只数成功图")
    void failingImageDoesNotBreakOthers() throws Exception {
        createImages(3);
        MapperStub mapper = new MapperStub(List.of(chunk("c1", imageInfo(3))));
        CountingVision vision = new CountingVision();
        vision.failing.add("img1.png");

        int done = service(mapper, localStorage(), vision).enrichKnowledge(KB_ID, null);

        assertThat(done).as("img0 / img2 成功，img1 失败 ⇒ 2").isEqualTo(2);
        assertThat(mapper.updates).as("仍有图片成功 ⇒ 分块仍要回写").hasSize(1);

        String json = mapper.updates.get(0).getImageInfo();
        assertThat(json).contains("caption:img0.png").contains("caption:img2.png")
            .contains("ocr:img0.png").contains("ocr:img2.png");
        assertThat(json)
            .as("失败图保留原占位 caption（不能清空、不能写成假描述）")
            .contains("\"caption\":\"img1.png\"")
            .doesNotContain("caption:img1.png");
        assertThat(vision.maxInFlight.get())
            .as("失败图的存在不能把整批打回串行")
            .isGreaterThan(1);
    }

    @Test
    @DisplayName("⭐ 并发度可配且有界：改成 2 时最大同时调用数恰为 2（不会无限起线程）")
    void concurrencyIsBoundedByConfiguredValue() throws Exception {
        createImages(6);
        MapperStub mapper = new MapperStub(List.of(chunk("c1", imageInfo(6))));
        CountingVision vision = new CountingVision();
        KbImageEnrichService svc = service(mapper, localStorage(), vision);
        setImageConcurrency(svc, 2);

        int done = svc.enrichKnowledge(KB_ID, null);

        assertThat(done).isEqualTo(6);
        assertThat(vision.maxInFlight.get())
            .as("池大小 2 ⇒ 不可能超过 2 个同时在飞；小于 2 说明没并发")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ 拿不到本地路径的图照旧跳过（返回 0、不 UPDATE、不调模型）")
    void imagesWithoutLocalPathAreSkipped() throws Exception {
        MapperStub mapper = new MapperStub(List.of(chunk("c1", imageInfo(2))));
        CountingVision vision = new CountingVision();
        KbImageStorage remoteOnly = remoteOnlyStorage();

        int done = service(mapper, remoteOnly, vision).enrichKnowledge(KB_ID, null);

        assertThat(done).isZero();
        assertThat(vision.calls.get()).as("不会为拿不到的图白跑模型").isZero();
        assertThat(mapper.updates).isEmpty();
        assertThat(hasLiveEnrichThread()).isFalse();
    }

    // ── 组装 ────────────────────────────────────────────────────────

    private KbImageEnrichService service(MapperStub mapper, KbImageStorage storage,
                                         KbVisionCaller vision) {
        return new KbImageEnrichService(mapper.asMapper(), storage, vision);
    }

    private KbImageStorage localStorage() {
        // 直接匿名子类而不是 Mockito：少一层 agent 引导，测试更快，也说明「只用到 localPath」
        return new KbImageStorage((LocalFileStorage) null) {
            @Override
            public Path localPath(String storageKey) {
                return tmp.resolve(storageKey);
            }
        };
    }

    /** 拿不到本地路径的存储后端（对象存储形态）。 */
    private static KbImageStorage remoteOnlyStorage() {
        return new KbImageStorage((LocalFileStorage) null) {
            @Override
            public Path localPath(String storageKey) {
                return null;
            }
        };
    }

    private void createImages(int n) throws Exception {
        Path dir = tmp.resolve("k-concurrency");
        Files.createDirectories(dir);
        for (int i = 0; i < n; i++) {
            Files.write(dir.resolve("img" + i + ".png"), new byte[]{1, 2, 3});
        }
    }

    private static KbChunkRecord chunk(String id, String imageInfo) {
        KbChunkRecord c = new KbChunkRecord();
        c.setId(id);
        c.setKnowledgeId(KB_ID);
        c.setChunkIndex(0);
        c.setChunkType("image");
        c.setImageInfo(imageInfo);
        return c;
    }

    /** 造 n 张图的 image_info（每张都带可解析的 attrs.storage_key）。 */
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

    private static void setImageConcurrency(KbImageEnrichService svc, int value) throws Exception {
        Field f = KbImageEnrichService.class.getDeclaredField("imageConcurrency");
        f.setAccessible(true);
        f.setInt(svc, value);
    }

    private static boolean hasLiveEnrichThread() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            boolean live = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && t.getName().startsWith("kb-image-enrich-"));
            if (!live) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }

    // ── 桩 ──────────────────────────────────────────────────────────

    /**
     * 计数视觉桩：记录「同时在飞的调用数」峰值。
     *
     * <p>⭐ 前两个调用用 {@link CountDownLatch} <b>互相等</b>：串行执行时第一个调用会等满
     * 超时（{@code overlapTimeouts > 0}），并发执行时两者立刻放行 —— 判据不依赖经验性的 sleep 时长。
     */
    private static final class CountingVision implements KbVisionCaller {

        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger overlapTimeouts = new AtomicInteger();
        final Set<String> failing = ConcurrentHashMap.newKeySet();
        final CountDownLatch twoArrived = new CountDownLatch(2);

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
                                             String modelIdOrName) throws Exception {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            calls.incrementAndGet();
            try {
                if (twoArrived.getCount() > 0) {
                    twoArrived.countDown();
                    if (!twoArrived.await(2, TimeUnit.SECONDS)) {
                        overlapTimeouts.incrementAndGet();
                    }
                }
                String name = imageFile.getFileName().toString();
                if (failing.contains(name)) {
                    throw new IllegalStateException("stub-fail-" + name);
                }
                return prompt == KbImagePrompts.OCR ? "ocr:" + name : "caption:" + name;
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    /** KbChunkMapper 动态代理桩：只实现本用例会走到的两个方法（其余一律炸，防静默绕过）。 */
    private static final class MapperStub {

        final List<KbChunkRecord> chunks;
        final List<KbChunkRecord> updates = new CopyOnWriteArrayList<>();
        final List<String> updateThreads = new CopyOnWriteArrayList<>();
        /** 图片子块（image_caption / image_ocr）插入行。 */
        final List<KbChunkRecord> inserts = new CopyOnWriteArrayList<>();
        final AtomicInteger deletes = new AtomicInteger();

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
                            return chunks;
                        case "update":
                            updateThreads.add(Thread.currentThread().getName());
                            updates.add((KbChunkRecord) args[0]);
                            return 1;
                        // ⭐ 图片独立子块写入（V-E5）：本桩只管「分块回写」的并发/线程口径，
                        //    子块行收下来但不在本用例断言（见 KbImageSubChunkTest）。
                        case "insert":
                            inserts.add((KbChunkRecord) args[0]);
                            return 1;
                        case "deleteByQuery":
                            deletes.incrementAndGet();
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
