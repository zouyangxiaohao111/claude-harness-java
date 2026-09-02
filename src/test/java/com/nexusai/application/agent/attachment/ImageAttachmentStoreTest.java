package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.attachment.ImageAttachmentStore.Base64Content;
import com.nexusai.application.agent.attachment.ImageAttachmentStore.PastedImage;
import com.nexusai.application.agent.attachment.ImageAttachmentStore.StoredImage;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ImageAttachmentStore 对齐 CC imageStore.ts 行为测试（RED→GREEN）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>落盘后读回 base64 字节一致</b>——CC 用 base64 写盘（imageStore.ts:66），读取必须还原原字节，
 *       否则图片直接发送 / 多模态工具读到的内容损坏。</li>
 *   <li><b>200 上限按 FIFO 逐出</b>——CC {@code evictOldestIfAtCap}（imageStore.ts:115-124）删<b>最早插入</b>键，
 *       且只删内存 Map 不删磁盘文件；Java 必须同语义，否则内存索引与磁盘内容漂移。</li>
 *   <li><b>孤儿清理删除非当前会话目录</b>——CC {@code cleanupOldImageCaches}（imageStore.ts:129-167）
 *       清理历史会话缓存，保留当前会话；否则磁盘图片缓存无限膨胀。</li>
 *   <li><b>id 按会话独立自增</b>——CC {@code PastedContent.id}「Sequential numeric ID」（config.ts:55）
 *       每会话从 1 递增，跨会话不串号。</li>
 *   <li><b>失败返回 null 不抛异常</b>——CC storeImage catch 后返回 null（imageStore.ts:75-78）；
 *       非法 base64 不能打崩会话。</li>
 * </ol>
 */
class ImageAttachmentStoreTest {

    private final ImageAttachmentStore store = new ImageAttachmentStore();

    @TempDir
    Path configHome;

    @BeforeEach
    void setUp() {
        // G5 适配：ImageAttachmentStore 写 nexusai 自有根（NexusaiPaths.getAppConfigHomeDir），
        //   不再经 ClaudePaths 覆写 ~/.claude —— 改为唯一 appName 隔离（防写真实 ~/.nexusai）。
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
        NexusaiPaths.setAppNameOverride(null);
    }

    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @Test
    @DisplayName("store → 落盘 {configHome}/image-cache/{session}/1.png + 读回 base64 一致 · CC imageStore.ts:54-79")
    void store_persistsFileAndRoundTripsBase64() throws Exception {
        StoredImage stored = store.store("sess-1", PNG_BASE64, "image/png");

        assertThat(stored).isNotNull();
        Path expected = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "image-cache", "sess-1", "1.png");
        assertThat(stored.path()).isEqualTo(expected.toString());
        assertThat(Files.exists(expected)).isTrue();
        // 写盘 = base64 解码后的原字节（对齐 CC fh.writeFile(encoding:'base64')）
        assertThat(Files.readAllBytes(expected))
                .isEqualTo(Base64.getDecoder().decode(PNG_BASE64));

        assertThat(store.getStoredImagePath("sess-1", 1)).isEqualTo(expected.toString());
        Base64Content content = store.getBase64("sess-1", 1);
        assertThat(content).isNotNull();
        assertThat(content.mediaType()).isEqualTo("image/png");
        assertThat(content.base64()).isEqualTo(PNG_BASE64);
        assertThat(store.getDataUrl("sess-1", 1))
                .isEqualTo("data:image/png;base64," + PNG_BASE64);
    }

    @Test
    @DisplayName("mediaType 派生扩展名：image/jpeg→1.jpeg、null→2.png · CC imageStore.ts:33-36")
    void store_mediaTypeDrivesExtension() throws Exception {
        store.storeWithId("sess-1", 1, PNG_BASE64, "image/jpeg");
        store.storeWithId("sess-1", 2, PNG_BASE64, null);
        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "image-cache", "sess-1", "1.jpeg"))).isTrue();
        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "image-cache", "sess-1", "2.png"))).isTrue();
    }

    @Test
    @DisplayName("id 按会话独立自增：同会话 1→2，新会话回到 1 · CC config.ts:55")
    void store_autoIdPerSession() throws Exception {
        StoredImage s1 = store.store("sess-1", PNG_BASE64, "image/png");
        StoredImage s2 = store.store("sess-1", PNG_BASE64, "image/png");
        StoredImage s3 = store.store("sess-2", PNG_BASE64, "image/png");
        assertThat(s1.id()).isEqualTo(1);
        assertThat(s2.id()).isEqualTo(2);
        assertThat(s3.id()).isEqualTo(1);
    }

    @Test
    @DisplayName("200 上限 FIFO 逐出：最早插入 id 出内存但文件仍在盘 · CC imageStore.ts:115-124")
    void evict_oldestFirst_atCap() throws Exception {
        for (int i = 1; i <= ImageAttachmentStore.MAX_STORED_IMAGE_PATHS + 1; i++) {
            store.storeWithId("sess-1", i, PNG_BASE64, "image/png");
        }
        // id=1 是第 201 次插入时被逐出（FIFO 删最早插入键）
        assertThat(store.get("sess-1", 1)).isNull();
        assertThat(store.getStoredImagePath("sess-1", 1)).isNull();
        assertThat(store.get("sess-1", 2)).isNotNull();
        assertThat(store.get("sess-1", ImageAttachmentStore.MAX_STORED_IMAGE_PATHS + 1)).isNotNull();
        // CC 逐出只删内存 Map 不删磁盘文件
        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "image-cache", "sess-1", "1.png"))).isTrue();
        // 不同会话不受影响（每会话独立计数）
        assertThat(store.get("sess-other", 1)).isNull();
    }

    @Test
    @DisplayName("storeImages 批量落盘 + 失败项跳过 · CC imageStore.ts:84-99")
    void storeImages_batch() throws Exception {
        Map<Long, String> paths = store.storeImages("sess-1", List.of(
                new PastedImage(1, PNG_BASE64, "image/png"),
                new PastedImage(2, "!!!not-base64!!!", "image/png")));
        assertThat(paths.keySet()).containsExactly(1L);
        assertThat(Files.exists(Paths.get(paths.get(1L)))).isTrue();
        // 非法 base64 项不落盘也不入结果
        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "image-cache", "sess-1", "2.png"))).isFalse();
    }

    @Test
    @DisplayName("非法 base64 → storeWithId 返回 null 不抛异常 · CC imageStore.ts:75-78")
    void storeWithId_invalidBase64_returnsNull() {
        StoredImage stored = store.storeWithId("sess-1", 1, "not-base64!!", "image/png");
        assertThat(stored).isNull();
    }

    @Test
    @DisplayName("cleanupSession 清内存桶 + 删 {session}/ 目录 · 会话结束孤儿清理")
    void cleanupSession_removesDirAndMemory() throws Exception {
        store.store("sess-1", PNG_BASE64, "image/png");
        assertThat(store.get("sess-1", 1)).isNotNull();
        store.cleanupSession("sess-1");
        assertThat(store.get("sess-1", 1)).isNull();
        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "image-cache", "sess-1"))).isFalse();
    }

    @Test
    @DisplayName("cleanupOldImageCaches 删非当前会话目录、保留当前、空根目录删除 · CC imageStore.ts:129-167")
    void cleanupOldImageCaches_removesOtherSessions() throws Exception {
        store.store("sess-current", PNG_BASE64, "image/png");
        store.store("sess-old", PNG_BASE64, "image/png");

        store.cleanupOldImageCaches("sess-current");

        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "image-cache", "sess-old"))).isFalse();
        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "image-cache", "sess-current"))).isTrue();
        assertThat(store.get("sess-old", 1)).isNull();
        assertThat(store.get("sess-current", 1)).isNotNull();
        // 全清空后根目录一并删除（imageStore.ts:156-162）
        store.cleanupSession("sess-current");
        store.cleanupOldImageCaches("sess-current");
        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "image-cache"))).isFalse();
    }
}
