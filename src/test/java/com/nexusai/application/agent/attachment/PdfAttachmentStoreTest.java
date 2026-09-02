package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.attachment.PdfAttachmentStore.StoredPdf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PdfAttachmentStore U1 路径通道行为测试（RED→GREEN）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图 · U1 混合传输 C）：
 * <ol>
 *   <li><b>上传落盘 → 磁盘字节与输入一致 + 返回 contentId</b>——&gt;5MB 大 PDF 走 multipart 上传落盘，
 *       后端按<b>路径</b>解析（CC 路径通道）；若落盘字节损坏 → 下游 PdfSupport.readPDF 读到损坏内容。</li>
 *   <li><b>id 按会话独立自增</b>——contentId 是跨 turn 引用键（SendMessageRequest.attachments 携带），
 *       每会话从 1 递增、跨会话不串号，否则引用错 PDF。</li>
 *   <li><b>路径确定性（getPath 不依赖内存索引）</b>——CC 路径通道文件在盘、路径可重建；
 *       即使内存索引被逐出 / 服务重启，下游仍能按 contentId 还原磁盘路径。</li>
 *   <li><b>200 上限 FIFO 逐出内存索引、磁盘文件保留</b>——同 ImageAttachmentStore（CC imageStore.ts:115-124）。</li>
 *   <li><b>cleanupSession 清内存 + 删目录</b>——会话删除时孤儿 PDF 清理。</li>
 * </ol>
 */
class PdfAttachmentStoreTest {

    private final PdfAttachmentStore store = new PdfAttachmentStore();

    @TempDir
    Path configHome;

    @BeforeEach
    void setUp() {
        // G5 适配：PdfAttachmentStore 写 nexusai 自有根（AttachmentStoreBase.storeDir），
        //   改唯一 appName 隔离（防写真实 ~/.nexusai）。
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
        NexusaiPaths.setAppNameOverride(null);
    }

    private static byte[] pdfBytes() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("store → 落盘 {configHome}/pdf-cache/{session}/1.pdf + 字节一致 + 返回 contentId · U1 路径通道")
    void store_persistsFileAndPath() throws Exception {
        byte[] bytes = pdfBytes();
        StoredPdf stored = store.store("sess-1", new ByteArrayInputStream(bytes), bytes.length, "report.pdf");

        assertThat(stored).isNotNull();
        assertThat(stored.id()).isEqualTo(1);
        Path expected = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "pdf-cache", "sess-1", "1.pdf");
        assertThat(stored.path()).isEqualTo(expected.toString());
        assertThat(Files.exists(expected)).isTrue();
        assertThat(Files.readAllBytes(expected)).isEqualTo(bytes);
        assertThat(stored.filename()).isEqualTo("report.pdf");
        assertThat(stored.size()).isEqualTo(bytes.length);

        // 内存索引命中（get / getFilename）
        assertThat(store.get("sess-1", 1)).isNotNull();
        assertThat(store.getFilename("sess-1", 1)).isEqualTo("report.pdf");
    }

    @Test
    @DisplayName("路径确定性：getPath 不依赖内存索引，恒还原 {pdf-cache}/{session}/{id}.pdf · CC 路径通道")
    void getPath_isDeterministicWithoutMemoryIndex() {
        String path = store.getPath("sess-1", 42);
        assertThat(path).isEqualTo(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "pdf-cache", "sess-1", "42.pdf").toString());
        // 未 store 过也能还原路径（CC 路径通道：文件在盘、路径可重建）
        assertThat(store.get("sess-1", 42)).isNull();
    }

    @Test
    @DisplayName("id 按会话独立自增：同会话 1→2，新会话回到 1 · CC config.ts:55")
    void store_autoIdPerSession() {
        byte[] bytes = pdfBytes();
        StoredPdf s1 = store.store("sess-1", new ByteArrayInputStream(bytes), bytes.length, "a.pdf");
        StoredPdf s2 = store.store("sess-1", new ByteArrayInputStream(bytes), bytes.length, "b.pdf");
        StoredPdf s3 = store.store("sess-2", new ByteArrayInputStream(bytes), bytes.length, "c.pdf");
        assertThat(s1.id()).isEqualTo(1);
        assertThat(s2.id()).isEqualTo(2);
        assertThat(s3.id()).isEqualTo(1);
    }

    @Test
    @DisplayName("200 上限 FIFO 逐出内存索引、磁盘文件保留 · 对齐 ImageAttachmentStore/CC imageStore.ts:115-124")
    void evict_oldestFirst_atCap() throws Exception {
        byte[] bytes = pdfBytes();
        for (int i = 1; i <= PdfAttachmentStore.MAX_STORED_PDF_PATHS + 1; i++) {
            store.store("sess-1", new ByteArrayInputStream(bytes), bytes.length, i + ".pdf");
        }
        // id=1 在第 201 次插入时被逐出（FIFO 删最早插入键）
        assertThat(store.get("sess-1", 1)).isNull();
        assertThat(store.get("sess-1", 2)).isNotNull();
        assertThat(store.get("sess-1", PdfAttachmentStore.MAX_STORED_PDF_PATHS + 1)).isNotNull();
        // 逐出只删内存索引不删磁盘文件（CC 同）
        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "pdf-cache", "sess-1", "1.pdf"))).isTrue();
        // 路径仍可确定性还原（文件在盘）
        assertThat(store.getPath("sess-1", 1))
                .isEqualTo(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "pdf-cache", "sess-1", "1.pdf").toString());
        // 不同会话不受影响（每会话独立计数）
        assertThat(store.get("sess-other", 1)).isNull();
    }

    @Test
    @DisplayName("cleanupSession 清内存桶 + 删 {pdf-cache}/{session}/ 目录 · 会话结束孤儿清理")
    void cleanupSession_removesDirAndMemory() throws Exception {
        byte[] bytes = pdfBytes();
        store.store("sess-1", new ByteArrayInputStream(bytes), bytes.length, "a.pdf");
        assertThat(store.get("sess-1", 1)).isNotNull();

        store.cleanupSession("sess-1");

        assertThat(store.get("sess-1", 1)).isNull();
        assertThat(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "pdf-cache", "sess-1"))).isFalse();
    }
}
