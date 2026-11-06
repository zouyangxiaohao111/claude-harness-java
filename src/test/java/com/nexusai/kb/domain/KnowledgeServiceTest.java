package com.nexusai.kb.domain;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.kb.app.ingest.KbIngestTaskQueue;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.dto.KnowledgeDto;
import com.nexusai.kb.dto.KnowledgePageResponse;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbIngestTaskMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 知识条目服务测试 · 批次 B3。
 *
 * <p><b>WHY 这些用例</b>：
 * <ol>
 *   <li><b>创建时必须是 {@code enable_status=disabled}</b> —— 检索链路只查 enabled。
 *       若创建就置 enabled，解析还没落库的文档会出现在检索结果里（召回空块）。</li>
 *   <li><b>不传 {@code parse_status} 时不返回 {@code deleting}</b> —— 源侧 swagger 明写；
 *       漏了会让用户在列表里看到一堆「删除中」的噪音。</li>
 *   <li><b>同库同内容 ⇒ 409</b>：源侧 {@code DuplicateFileError}。</li>
 *   <li><b>扩展名白名单</b>：源侧 37 个扩展名，视频明确拒绝。</li>
 * </ol>
 */
@DisplayName("[B3] 知识条目（创建 / 列表 / 详情 / 重解析 / 取消 / 删除）")
class KnowledgeServiceTest {

    private static KbKnowledgeMapper knowledgeMapper;
    private static KbKnowledgeBaseMapper kbMapper;
    private static KbIngestTaskMapper taskMapper;
    private static KbKnowledgeStateRepository stateRepository;
    private static KbIngestTaskQueue queue;
    private static KnowledgeService service;
    private static Path dbPath;

    @TempDir
    static Path storageRoot;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds,
            KbKnowledgeMapper.class, KbKnowledgeBaseMapper.class, KbIngestTaskMapper.class,
            com.nexusai.kb.repo.mapper.KbChunkMapper.class,
            com.nexusai.kb.repo.mapper.KbEmbeddingMapper.class);

        knowledgeMapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeMapper.class);
        kbMapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeBaseMapper.class);
        taskMapper = MybatisFlexBootstrap.getInstance().getMapper(KbIngestTaskMapper.class);
        stateRepository = new KbKnowledgeStateRepository(knowledgeMapper);
        queue = new KbIngestTaskQueue(taskMapper);
        // B3：检索器（本测试不测检索，传最小可用装配）
        com.nexusai.kb.app.retrieval.KbHybridSearcher searcher =
            new com.nexusai.kb.app.retrieval.KbHybridSearcher(
                MybatisFlexBootstrap.getInstance().getMapper(
                    com.nexusai.kb.repo.mapper.KbChunkMapper.class),
                MybatisFlexBootstrap.getInstance().getMapper(
                    com.nexusai.kb.repo.mapper.KbEmbeddingMapper.class),
                null,
                ds);
        service = new KnowledgeService(knowledgeMapper, kbMapper, stateRepository, queue,
            searcher,
            // B4：重排阶段（本测试不测重排，传 null —— 未配模型时它会原样返回）
            new com.nexusai.kb.app.retrieval.KbRerankStage(null),
            null,  // B10：本测试不涉及全局默认重排模型
            MybatisFlexBootstrap.getInstance().getMapper(
                com.nexusai.kb.repo.mapper.KbChunkMapper.class),
            // W1：驱动注册表 —— 本测试用真实 sqlite 驱动，保证检索路径与生产一致
            new com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry(
                java.util.List.<com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine>of(
                    new com.nexusai.kb.app.retrieval.engine.KbSqliteRetrievalEngine(
                    searcher,
                    new com.nexusai.kb.app.retrieval.KbIndexWriter(
                        MybatisFlexBootstrap.getInstance().getMapper(
                            com.nexusai.kb.repo.mapper.KbEmbeddingMapper.class),
                        MybatisFlexBootstrap.getInstance().getMapper(
                            com.nexusai.kb.repo.mapper.KbChunkMapper.class),
                        null,
                        ds),
                    MybatisFlexBootstrap.getInstance().getMapper(
                        com.nexusai.kb.repo.mapper.KbEmbeddingMapper.class),
                    ds))),
            // 标签 JOIN 用的原生连接（本测试的列表路径会走到）
            ds);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_ingest_task");
            s.executeUpdate("DELETE FROM kb_knowledge");
            s.executeUpdate("DELETE FROM kb_knowledge_base");
        }
    }

    // ── 创建 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("创建文件知识：落 pending + disabled + 入队，返回 DTO")
    void createFromFileWritesPendingDisabledAndEnqueues() throws Exception {
        String kbId = insertKb("document");
        Path dir = Files.createTempDirectory(storageRoot, "kb-");

        KnowledgeDto dto = service.createFromFile(
            kbId, "测试文档.txt",
            new ByteArrayInputStream("正文内容".getBytes(StandardCharsets.UTF_8)),
            dir, null);

        assertThat(dto.id()).isNotBlank();
        assertThat(dto.knowledgeBaseId()).isEqualTo(kbId);
        assertThat(dto.type()).isEqualTo("file");
        assertThat(dto.channel()).isEqualTo("web");
        assertThat(dto.title()).isEqualTo("测试文档.txt");
        assertThat(dto.fileType()).isEqualTo("txt");
        assertThat(dto.parseStatus())
            .as("创建时必须是 pending（源侧 knowledge_create.go 逐字如此）")
            .isEqualTo("pending");
        assertThat(dto.enableStatus())
            .as("⭐⭐ 创建时必须 disabled —— 检索只查 enabled，解析完才转")
            .isEqualTo("disabled");
        assertThat(dto.pendingSubtasksCount()).isZero();
        assertThat(dto.fileHash()).hasSize(64);
        assertThat(dto.createdAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

        // 已入队
        assertThat(taskCount(dto.id())).isEqualTo(1);
        // attempt 已推进到 1
        assertThat(stateRepository.currentAttempt(dto.id())).isEqualTo(1);
        // 文件真的落盘了
        assertThat(Files.exists(Path.of(dto.filePath()))).isTrue();
    }

    @Test
    @DisplayName("⭐⭐ 上传名带目录前缀 ⇒ 落到 folder_path（子文件夹的唯一来源）")
    void createFromFileSplitsFolderFromName() throws Exception {
        String kbId = insertKb("document");
        Path dir = Files.createTempDirectory(storageRoot, "kb-");

        KnowledgeDto dto = service.createFromFile(
            kbId, "规范/运维/a.txt",
            new ByteArrayInputStream("正文".getBytes(StandardCharsets.UTF_8)), dir, null);

        // 标题只留文件名（目录段不能进标题，否则列表里一串 "规范/运维/a.txt"）
        assertThat(dto.title()).isEqualTo("a.txt");
        assertThat(dto.fileName()).isEqualTo("a.txt");
        // 目录段进 folder_path —— 文件夹树就是从这一列派生的
        assertThat(dto.folderPath())
            .as("⭐ 写死 \"\" 的话子文件夹永远不出现（2026-11-06 修的正是这个）")
            .isEqualTo("规范/运维");
    }

    @Test
    @DisplayName("⛔ 上传名里的路径穿越段被丢弃（../ → 不下沉）")
    void createFromFileDropsTraversalSegments() throws Exception {
        String kbId = insertKb("document");
        Path dir = Files.createTempDirectory(storageRoot, "kb-");

        KnowledgeDto dto = service.createFromFile(
            kbId, "../../etc/passwd/a.txt",
            new ByteArrayInputStream("正文".getBytes(StandardCharsets.UTF_8)), dir, null);

        assertThat(dto.folderPath())
            .as("`..` 段必须丢，只留安全段")
            .isEqualTo("etc/passwd");
        assertThat(dto.title()).isEqualTo("a.txt");
    }

    @Test
    @DisplayName("无目录前缀 ⇒ folder_path 仍为空串（保持旧行为）")
    void createFromFileRootFolderStaysEmpty() throws Exception {
        String kbId = insertKb("document");
        Path dir = Files.createTempDirectory(storageRoot, "kb-");

        KnowledgeDto dto = service.createFromFile(
            kbId, "根目录.txt",
            new ByteArrayInputStream("正文".getBytes(StandardCharsets.UTF_8)), dir, null);

        assertThat(dto.folderPath()).isEmpty();
    }

    @Test
    @DisplayName("同库同内容 ⇒ 409（且不留下孤儿文件）")
    void duplicateFileRejected() throws Exception {
        String kbId = insertKb("document");
        Path dir = Files.createTempDirectory(storageRoot, "kb-");
        byte[] body = "重复内容".getBytes(StandardCharsets.UTF_8);

        service.createFromFile(kbId, "a.txt", new ByteArrayInputStream(body), dir, null);

        assertThatThrownBy(() -> service.createFromFile(
            kbId, "b.txt", new ByteArrayInputStream(body), dir, null))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("文件已存在");

        assertThat(rowCount()).as("重复请求不应留下第二行").isEqualTo(1);
    }

    @Test
    @DisplayName("不支持的类型 ⇒ 400；视频明确拒绝")
    void unsupportedExtensionRejected() throws Exception {
        String kbId = insertKb("document");
        Path dir = Files.createTempDirectory(storageRoot, "kb-");

        assertThatThrownBy(() -> service.createFromFile(
            kbId, "x.exe", new ByteArrayInputStream(new byte[] {1}), dir, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("不支持的文件类型");

        assertThatThrownBy(() -> service.createFromFile(
            kbId, "movie.mp4", new ByteArrayInputStream(new byte[] {1}), dir, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("暂不支持上传视频");
    }

    @Test
    @DisplayName("FAQ 型知识库拒绝文件上传（源侧明确语义）")
    void faqKnowledgeBaseRejectsFileUpload() throws Exception {
        String kbId = insertKb("faq");
        Path dir = Files.createTempDirectory(storageRoot, "kb-");

        assertThatThrownBy(() -> service.createFromFile(
            kbId, "a.txt", new ByteArrayInputStream(new byte[] {1}), dir, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("FAQ 知识库不支持文件上传");
    }

    @Test
    @DisplayName("文件名路径穿越被剥掉（../../etc/passwd → passwd）")
    void pathTraversalSanitized() throws Exception {
        String kbId = insertKb("document");
        Path dir = Files.createTempDirectory(storageRoot, "kb-");

        KnowledgeDto dto = service.createFromFile(
            kbId, "../../etc/passwd.txt",
            new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), dir, null);

        assertThat(dto.title()).isEqualTo("passwd.txt");
        assertThat(Path.of(dto.filePath()).normalize().startsWith(dir.normalize()))
            .as("落盘路径必须在存储根之内")
            .isTrue();
    }

    // ── 列表 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ 不传 parse_status 时不返回 deleting 的文档")
    void listHidesDeletingByDefault() throws Exception {
        String kbId = insertKb("document");
        insertKnowledgeRow(kbId, "正常文档", "completed", "");
        insertKnowledgeRow(kbId, "删除中文档", "deleting", "");

        KnowledgePageResponse<KnowledgeDto> all = service.list(kbId, null, null, null, null, null, null);
        assertThat(all.total()).as("默认不返回 deleting").isEqualTo(1);
        assertThat(all.data()).extracting(KnowledgeDto::title).containsExactly("正常文档");

        KnowledgePageResponse<KnowledgeDto> explicit =
            service.list(kbId, null, null, "deleting", null, null, null);
        assertThat(explicit.total()).as("显式筛 deleting 时才可见").isEqualTo(1);
        assertThat(explicit.data().get(0).title()).isEqualTo("删除中文档");
    }

    @Test
    @DisplayName("分页形状对齐源侧 PageResponse（success/data/page/page_size/total）")
    void listShapeMatchesSourceContract() throws Exception {
        String kbId = insertKb("document");
        for (int i = 0; i < 5; i++) {
            insertKnowledgeRow(kbId, "文档" + i, "completed", "");
        }
        KnowledgePageResponse<KnowledgeDto> page = service.list(kbId, 2, 2, null, null, null, null);

        assertThat(page.success()).isTrue();
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(2);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.data()).hasSize(2);
    }

    @Test
    @DisplayName("关键词 / 文件类型 / 文件夹筛选生效")
    void listFiltersWork() throws Exception {
        String kbId = insertKb("document");
        insertKnowledgeRow(kbId, "年度报告", "completed", "");
        insertKnowledgeRow(kbId, "月度报告", "completed", "");
        insertKnowledgeRow(kbId, "无关文档", "completed", "");

        assertThat(service.list(kbId, null, null, null, null, "报告", null).total()).isEqualTo(2);
        assertThat(service.list(kbId, null, null, null, null, "月", null).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("page_size 超上限被夹到 1000；非法 page 归 1")
    void listClampsPagination() throws Exception {
        String kbId = insertKb("document");
        insertKnowledgeRow(kbId, "a", "completed", "");
        KnowledgePageResponse<KnowledgeDto> r = service.list(kbId, 0, 99999, null, null, null, null);
        assertThat(r.page()).isEqualTo(1);
        assertThat(r.pageSize()).isEqualTo(1000);
    }

    // ── 详情 / 删除 / 重解析 ────────────────────────────────────────

    @Test
    @DisplayName("详情不存在 ⇒ 404")
    void getMissingThrows() {
        assertThatThrownBy(() -> service.getById("no-such-id"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("删除是逻辑删：列表不再返回，物理行仍在")
    void deleteIsLogical() throws Exception {
        String kbId = insertKb("document");
        String id = insertKnowledgeRow(kbId, "待删文档", "completed", "");

        service.delete(id);

        assertThat(service.list(kbId, null, null, null, null, null, null).total()).isZero();
        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(NotFoundException.class);
        assertThat(rawDeletedFlag(id)).as("deleted 列必须为 1（不是物理删除）").isEqualTo(1);
    }

    @Test
    @DisplayName("重新解析：attempt 递增 + 新任务入队 + 状态回 pending")
    void reparseAdvancesAttempt() throws Exception {
        String kbId = insertKb("document");
        Path dir = Files.createTempDirectory(storageRoot, "kb-");
        KnowledgeDto created = service.createFromFile(
            kbId, "a.txt", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), dir, null);

        int before = stateRepository.currentAttempt(created.id());
        long taskId = service.reparse(created.id());

        assertThat(taskId).isPositive();
        assertThat(stateRepository.currentAttempt(created.id()))
            .as("每轮解析 attempt 必须递增（陈旧任务识别依据）")
            .isEqualTo(before + 1);
        assertThat(service.getById(created.id()).parseStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("取消：置 cancelled 并作废队列")
    void cancelParseMarksCancelled() throws Exception {
        String kbId = insertKb("document");
        Path dir = Files.createTempDirectory(storageRoot, "kb-");
        KnowledgeDto created = service.createFromFile(
            kbId, "a.txt", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), dir, null);

        service.cancelParse(created.id());

        assertThat(service.getById(created.id()).parseStatus()).isEqualTo("cancelled");
        assertThat(taskCount(created.id())).as("queued 任务应被作废").isZero();
    }

    // ── 支撑 ────────────────────────────────────────────────────────

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }

    private static String insertKb(String type) throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "INSERT INTO kb_knowledge_base (id, name, type, chunking_config, "
                    + "image_processing_config, cos_config, vlm_config, "
                    + "embedding_model_id, summary_model_id) VALUES ('" + id + "', '测试库', '" + type
                    + "', '{\"chunk_size\": 512, \"chunk_overlap\": 80}', '{}', '{}', '{}', '', '')");
        }
        return id;
    }

    private static String insertKnowledgeRow(
        String kbId, String title, String parseStatus, String folder) throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "INSERT INTO kb_knowledge (id, knowledge_base_id, type, title, source, channel, "
                    + "parse_status, enable_status, file_type, folder_path, pending_subtasks_count, "
                    + "custom_metadata, storage_size, summary_status) VALUES ('" + id + "', '" + kbId
                    + "', 'file', '" + title + "', 'manual', 'web', '" + parseStatus
                    + "', 'enabled', 'txt', '" + folder + "', 0, '{}', 0, 'none')");
        }
        return id;
    }

    private static int taskCount(String knowledgeId) throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT COUNT(*) FROM kb_ingest_task WHERE knowledge_id = '" + knowledgeId
                     + "' AND state = 'queued'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static int rowCount() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM kb_knowledge WHERE deleted = 0")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static int rawDeletedFlag(String id) throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT deleted FROM kb_knowledge WHERE id = '" + id + "'")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
