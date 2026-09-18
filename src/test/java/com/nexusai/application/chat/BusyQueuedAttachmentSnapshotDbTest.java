package com.nexusai.application.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [busy 附件快照] busy-queued（agent 流式输出中发的消息）→ drain 落库 → {@code messages.user_attachments} 快照
 * → F5（{@code GET /messages} = {@link MessageService#listRawForTranscript}）重拉气泡附件仍在。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：busy 消息的 user 行由 <b>drain 时点</b>落库
 * （controller busy 分支不落库，见 {@code ChatController:119-147}）→ 空闲路径那条
 * {@code MessageService.updateUserAttachments} 回写（{@code ChatService:885}）<b>永不可达</b>；
 * 改动前 {@code ChatService.persistAppendedMessage} user 分支透传的 {@code m.userAttachments()} 在
 * busy 链路上恒 null（全仓无任何地方给 drain 产出的 DTO 设 userAttachments）⇒
 * <b>DB 快照恒 NULL ⇒ F5 后气泡上的附件胶囊消失、{@code /attachments/content/…} 预览 url 拼不出</b>
 * （前端 {@code MessageList.tsx:631} 按 {@code userAttachments} 渲染非图片 chip）。
 *
 * <p>本类用<b>真实链路</b>（真实 {@code ChatService.enqueueBusyPrompt} 入队 → 真实 {@code LlmAgentLoop.run}
 * turn-0 drain → 真实 {@code ChatService.armRealTimePersist} 落库 → 真实 {@link MessageService} → 真实 SQLite）
 * 把「入队 → drain → 落库 → F5 重拉」整链串起来断言，并同时钉住三条红线：
 * <ol>
 *   <li><b>不污染气泡</b>：DB {@code content} = 用户原文（模型侧附件说明只进 prompt，不落库/不进快照）——
 *       带图 busy 消息的模型侧 content 是「多模态提示 + contentId 说明」，DB 若跟着它走即污染；</li>
 *   <li><b>图片不双份</b>：图片走 {@code image_paste_ids}（V46）通道，<b>不进</b> user_attachments 快照
 *       （前端对本就有的图片双通道会画两次）；</li>
 *   <li><b>无附件零变化</b>：纯文本 busy 消息两列恒 NULL（不写空快照）。</li>
 * </ol>
 *
 * <p><b>DB 装配</b>：本测试类运行独占一个随机 SQLite 文件（{@code target/flex-dbtest/e2e-<random>/flex.db}）
 * + Flyway 全量迁移 + {@code resetAndStart}，每用例唯一 sessionId + {@code tearDown} 自清 —— 无跨次/跨 JVM 污染。
 */
@DisplayName("[busy 附件快照] busy-queued → drain 落库 → user_attachments（F5 气泡附件胶囊 + 预览 url）")
class BusyQueuedAttachmentSnapshotDbTest {

    /** 1x1 PNG（base64 无前缀，前端 ≤5MB 图片直传产物）。 */
    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    /** ≤5MB 直传 Word（后端 busy 链路不解析内容，仅作附件载荷；值须为合法 base64 串）。 */
    private static final String DOCX_BASE64 = "UEsDBBQAAAAIAAAAIQ==";
    private static final String DOCX_MEDIA =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String ORIGINAL_ID = "msg-orig-turn1";
    private static final String ORIGINAL_TEXT = "上一轮原文（controller 已落库）";
    /** path 附件经附件表注册后拿到的 contentId（mock 桩值 —— 原始请求里 path 附件没有它）。 */
    private static final long PATH_CONTENT_ID = 77L;

    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    private String session;
    private MessageService messageService;
    private ChatService chatService;
    private NotificationQueue queue;
    /** [attach-busy-resolve] path 通道激活 + mock 附件表注册（path 附件 → contentId 的**唯一**来源）。 */
    private AttachmentService attachmentService;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest",
            "busy-attach-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("flex.db").toAbsolutePath());
        ds.setBusyTimeout(10_000);
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds, MessageMapper.class, SessionMapper.class, ToolCallMapper.class);
        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(MessageMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
        toolCallMapper = MybatisFlexBootstrap.getInstance().getMapper(ToolCallMapper.class);
    }

    @BeforeEach
    void setUp() {
        session = "sess-busy-att-" + UUID.randomUUID().toString().substring(0, 8);

        SessionRecord s = new SessionRecord();
        s.setId(session);
        s.setModelTag("DS");
        s.setModelName("test-model");
        s.setTitle("busy attach snapshot");
        s.setTime("刚刚");
        s.setSessionGroup("default");
        sessionMapper.insertSelective(s);

        // 真实 MessageService（仅替换 mapper，无 Spring 上下文）
        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper", toolCallMapper);

        // 真实 ChatService（落库宿主 + busy 入队点；队列为真实 NotificationQueue，发布器为 mock）
        queue = new NotificationQueue();
        chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(chatService, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(chatService, "messageService", messageService);
        ReflectionTestUtils.setField(chatService, "notificationQueue", queue);
        ReflectionTestUtils.setField(chatService, "queueEventPublisher", mock(QueueEventPublisher.class));
        // [attach-busy-resolve] busy 入队跑同源 resolveAttachments：path 通道需 localRead + 附件表注册
        //   （mock 附件表 —— path 附件 contentId 的唯一来源；真实库无 attachments 表装配，用桩值断言透传）
        attachmentService = mock(AttachmentService.class);
        when(attachmentService.register(anyString(), anyString(), any(), any(), anyLong(), anyString()))
            .thenReturn(PATH_CONTENT_ID);
        ReflectionTestUtils.setField(chatService, "attachmentService", attachmentService);
        ReflectionTestUtils.setField(chatService, "localRead", true);
    }

    @AfterEach
    void tearDown() {
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", session));
        sessionMapper.deleteById(session);
    }

    // ════════════════════════════════════════════════════════════════════
    // 主用例：busy 携 Word + 上传媒体 + 图片
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("busy 携 Word+上传媒体+图片 → drain 落库 user_attachments=非图片快照（含 contentId）；content 仍原文；图片只走 imagePasteIds；F5 重拉 url 可拼")
    void busyWithAttachments_persistsSnapshot_rawContentImageOnlyPasteIds_f5RereadKeepsSnapshot() {
        String content = "请分析这份文档并给出要点";
        String busyId = "msg-busy-att-" + UUID.randomUUID().toString().substring(0, 8);
        // ① ≤5MB 直传 Word（type=file + base64，无 contentId —— 前端 Composer ≤5MB 分支产物）
        AttachmentRequest docx = new AttachmentRequest("file", null, "季度报表.docx", DOCX_MEDIA, DOCX_BASE64, null);
        // ② path 附件（local-read 大文件，前端只传本地绝对路径）→ 入队侧 resolveAttachments 注册附件表
        //    得 contentId（mock 桩 77）→ 快照必须取【已解析】值（取原始请求则 contentId=null，F5 无预览 url）
        AttachmentRequest video = new AttachmentRequest("video", null, "clip.mp4", "video/mp4", null,
            tempPath("clip.mp4"));
        // ③ ≤5MB 直传图片（走 imagePasteIds 通道，contentId=5 为 image-cache id）
        AttachmentRequest img = new AttachmentRequest("image", "5", "shot.png", "image/png", PNG_BASE64, null);
        SendMessageRequest req = new SendMessageRequest(content, null, null, List.of(docx, video, img),
            null, null, null, null, null);

        // 生产前半段：本 turn 的原 user 行已由 controller 落库（busy 消息此刻【不落库】）
        messageService.createQueuedUserMessage(session, ORIGINAL_ID, ORIGINAL_TEXT, OffsetDateTime.now(), false);

        // 生产唯一入队点（busy 分支）：快照在此构造并随 QueueItem 携带
        chatService.enqueueBusyPrompt(session, busyId, req);

        // 真实 run：turn-0 drain 消费 busy-queued → append → ChatService 实时落库（真实 DB 写入）
        runLoop(ORIGINAL_ID, ORIGINAL_TEXT);

        // ── ① DB：该 user 行 user_attachments = 非图片快照（F5 气泡 chip 数据源）──
        MessageRecord row = rowById(busyId);
        assertThat(row).as("busy-queued user 行必须已由 drain 实时落库").isNotNull();
        assertThat(row.getUserAttachments())
            .as("★ 缺陷点：busy 路径 user_attachments 曾恒 NULL ⇒ F5 后气泡附件胶囊消失")
            .isNotNull();
        List<Map<String, Object>> snap = parseJsonArray(row.getUserAttachments());
        assertThat(snap).as("快照含 2 条非图片附件（Word + 上传媒体）").hasSize(2);
        assertThat(snap).extracting(m -> String.valueOf(m.get("type")))
            .as("类型 = file/video（图片被显式排除 —— 图片走 imagePasteIds，不双份）")
            .containsExactlyInAnyOrder("file", "video");
        assertThat(snap).extracting(m -> String.valueOf(m.get("filename")))
            .containsExactlyInAnyOrder("季度报表.docx", "clip.mp4");
        assertThat(entryOf(snap, "clip.mp4").get("contentId"))
            .as("path 附件 contentId（= resolveAttachments 注册附件表所得）入快照 —— 取原始请求则此处为 null，"
                + "F5 预览 url 拼不出")
            .isEqualTo(String.valueOf(PATH_CONTENT_ID));
        assertThat(snap).noneMatch(m -> "image".equals(m.get("type")))
            .as("图片绝不出现在 user_attachments（双份会画两遍）");

        // ── ② 不污染：DB content = 原文（模型侧「本地路径/vision_analyze」说明只进 prompt，不落库）──
        assertThat(row.getContent()).as("DB 气泡/历史 content 必须是用户原文").isEqualTo(content);
        assertThat(row.getUserAttachments())
            .as("快照只含附件元数据，不得掺入模型侧说明文本")
            .doesNotContain("本地路径").doesNotContain("vision_analyze").doesNotContain("contentId=");

        // ── ③ 图片仍只走 image_paste_ids（本批不得影响该列）──
        assertThat(parseStringArray(row.getImagePasteIds()))
            .as("图片 id 仍落 V46 列（F5 前端按 id batch 拉缩略图）")
            .containsExactly("5");

        // ── ④ F5 语义：GET /messages 重拉（listRawForTranscript → toDto）后附件仍在 + url 可拼 ──
        ChatMessageDto re = findById(messageService.listRawForTranscript(session), busyId);
        assertThat(re).as("F5 重拉必须能拿到该 user 消息").isNotNull();
        assertThat(re.content()).as("F5 重拉 content 仍为原文").isEqualTo(content);
        assertThat(re.userAttachments()).as("★ F5 后气泡附件快照仍在（本批核心交付）").hasSize(2);
        ChatMessageDto.UserAttachmentInfo reVideo = re.userAttachments().stream()
            .filter(a -> "clip.mp4".equals(a.filename())).findFirst().orElseThrow();
        assertThat(reVideo.contentId()).isEqualTo(String.valueOf(PATH_CONTENT_ID));
        assertThat(reVideo.url())
            .as("★ path 附件在 busy 路径下 F5 后必须有预览 url（靠入队侧注册得的 contentId 拼）")
            .isEqualTo("/attachments/content/" + session + "/" + PATH_CONTENT_ID);
        ChatMessageDto.UserAttachmentInfo reDocx = re.userAttachments().stream()
            .filter(a -> "季度报表.docx".equals(a.filename())).findFirst().orElseThrow();
        assertThat(reDocx.contentId())
            .as("≤5MB base64 直传无附件表 contentId（与空闲路径同语义）").isNull();
        assertThat(reDocx.url()).as("无 contentId → 无 url（前端 chip 降级纯文本）").isNull();
        assertThat(re.imagePasteIds()).as("重拉后图片通道不受影响").containsExactly("5");
        assertThat(re.queuedOrigin()).as("busy-queued 标记不受本批影响").isEqualTo("busy-queued");
    }

    // ════════════════════════════════════════════════════════════════════
    // ⭐ 调和点 2：快照必须取【已解析】列表（path 附件才有 contentId）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("busy 携 path 附件（local-read Word）→ 快照 contentId = 附件表注册值；F5 重拉有预览 url（取原始请求则 null ⇒ 本条转红）")
    void busyPathAttachment_snapshotCarriesResolvedContentId_f5UrlAvailable() {
        String busyId = "msg-busy-path-" + UUID.randomUUID().toString().substring(0, 8);
        String path = tempPath("local-read-报告.docx");
        // 前端 path 通道产物：只有本地绝对路径，<b>没有 contentId</b>（contentId 由后端 resolveAttachments 注册产生）
        AttachmentRequest word = new AttachmentRequest("file", null, "local-read-报告.docx",
            DOCX_MEDIA, null, path);
        SendMessageRequest req = new SendMessageRequest("忙时发一个本地大文件", null, null, List.of(word),
            null, null, null, null, null);

        messageService.createQueuedUserMessage(session, ORIGINAL_ID, ORIGINAL_TEXT, OffsetDateTime.now(), false);
        chatService.enqueueBusyPrompt(session, busyId, req);
        runLoop(ORIGINAL_ID, ORIGINAL_TEXT);

        // ① DB 快照：contentId = 注册桩值（证明快照取自已解析列表而非原始请求）
        MessageRecord row = rowById(busyId);
        assertThat(row).as("busy path 附件行必须落库").isNotNull();
        List<Map<String, Object>> snap = parseJsonArray(row.getUserAttachments());
        assertThat(snap).as("path 附件必须进快照（原实现按类型丢弃 ⇒ 快照 NULL）").hasSize(1);
        assertThat(entryOf(snap, "local-read-报告.docx").get("contentId"))
            .as("★ 必须是 resolveAttachments 注册后的 contentId（题点：用 req.attachments() 构造则此处 null）")
            .isEqualTo(String.valueOf(PATH_CONTENT_ID));
        assertThat(entryOf(snap, "local-read-报告.docx").get("type")).isEqualTo("file");
        assertThat(row.getContent()).as("content 仍原文").isEqualTo("忙时发一个本地大文件");

        // ② F5：出站 url 必须可拼（前端靠它点开 /content 预览）
        ChatMessageDto re = findById(messageService.listRawForTranscript(session), busyId);
        ChatMessageDto.UserAttachmentInfo info = re.userAttachments().stream()
            .filter(a -> "local-read-报告.docx".equals(a.filename())).findFirst().orElseThrow();
        assertThat(info.contentId()).isEqualTo(String.valueOf(PATH_CONTENT_ID));
        assertThat(info.url())
            .as("★ path 附件在 busy 路径下 F5 后必须有预览 url（旧基线结论「有胶囊无 url」已作废）")
            .isEqualTo("/attachments/content/" + session + "/" + PATH_CONTENT_ID);

        // ③ 守卫：path 附件确实过了校验门（register 被调用 = 存在性/非目录/≤200MB/防穿越全过）
        verify(attachmentService, times(1))
            .register(eq(session), eq(Path.of(path).toAbsolutePath().normalize().toString()),
                any(), any(), anyLong(), eq("path"));
    }

    // ════════════════════════════════════════════════════════════════════
    // 零变化守卫：纯文本 busy（无附件）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("纯文本 busy（无附件）→ user_attachments / image_paste_ids 两列恒 NULL（不写空快照，零行为变化）")
    void busyWithoutAttachments_bothColumnsStayNull() {
        String busyId = "msg-busy-txt-" + UUID.randomUUID().toString().substring(0, 8);
        messageService.createQueuedUserMessage(session, ORIGINAL_ID, ORIGINAL_TEXT, OffsetDateTime.now(), false);
        chatService.enqueueBusyPrompt(session, busyId,
            new SendMessageRequest("忙时纯文本追问", null, null, List.of(), null, null, null, null, null));

        runLoop(ORIGINAL_ID, ORIGINAL_TEXT);

        MessageRecord row = rowById(busyId);
        assertThat(row).as("纯文本 busy 行仍落库").isNotNull();
        assertThat(row.getUserAttachments()).as("无附件 → user_attachments 恒 NULL").isNull();
        assertThat(row.getImagePasteIds()).as("无图 → image_paste_ids 恒 NULL").isNull();
        assertThat(row.getContent()).isEqualTo("忙时纯文本追问");
    }

    // ════════════════════════════════════════════════════════════════════
    // 轮末补落分支（实时落库漏落时的兜底）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("轮末补落分支：registry 携快照 → 补落行 user_attachments 非空（与实时落库同源）；无快照 → 列恒 NULL")
    void persistInjectedQueuedMessages_carriesSnapshotFromRegistry() {
        AgentState state = new AgentState("sys", session, null);
        String withSnap = "msg-queued-snap-" + UUID.randomUUID().toString().substring(0, 8);
        String noSnap = "msg-queued-plain-" + UUID.randomUUID().toString().substring(0, 8);
        List<ChatMessageDto.UserAttachmentInfo> snapshot = List.of(
            new ChatMessageDto.UserAttachmentInfo("file", "季度报表.docx", DOCX_MEDIA, null, null),
            new ChatMessageDto.UserAttachmentInfo("video", "clip.mp4", "video/mp4", "77", null));
        state.addInjectedQueuedMessage(withSnap, "忙时带附件追问", "busy-queued", snapshot);
        state.addInjectedQueuedMessage(noSnap, "忙时纯文本追问", "busy-queued");

        ReflectionTestUtils.invokeMethod(chatService, "persistInjectedQueuedMessages", state, session);

        MessageRecord withSnapRow = rowById(withSnap);
        assertThat(withSnapRow).as("补落分支必须落库").isNotNull();
        assertThat(withSnapRow.getContent()).as("补落 content 仍为原文").isEqualTo("忙时带附件追问");
        List<Map<String, Object>> snap = parseJsonArray(withSnapRow.getUserAttachments());
        assertThat(snap).as("registry 快照 → 补落行 user_attachments（与实时落库同源，两条路径无二义）").hasSize(2);
        assertThat(snap).extracting(m -> String.valueOf(m.get("filename")))
            .containsExactlyInAnyOrder("季度报表.docx", "clip.mp4");
        assertThat(entryOf(snap, "clip.mp4").get("contentId")).isEqualTo("77");

        MessageRecord noSnapRow = rowById(noSnap);
        assertThat(noSnapRow).as("无快照项仍补落（原行为不变）").isNotNull();
        assertThat(noSnapRow.getUserAttachments()).as("无快照 → 列恒 NULL（零变化）").isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 空闲路径回归（本批不得改动该路径）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("空闲路径回归：createUserMessage 快照语义不变（含图片项，contentId 规则不变）")
    void idlePath_createUserMessage_snapshotSemanticsUnchanged() {
        AttachmentRequest docx = new AttachmentRequest("file", null, "季度报表.docx", DOCX_MEDIA, DOCX_BASE64, null);
        AttachmentRequest video = new AttachmentRequest("video", "77", "clip.mp4", "video/mp4", null, "/tmp/clip.mp4");
        AttachmentRequest img = new AttachmentRequest("image", "5", "shot.png", "image/png", PNG_BASE64, null);
        SendMessageRequest req = new SendMessageRequest("空闲发一条带附件消息", null, null,
            List.of(docx, video, img), null, null, null, null, null);

        String idleId = messageService.createUserMessage(session, req).userMessageId();

        MessageRecord row = rowById(idleId);
        assertThat(row.getContent()).isEqualTo("空闲发一条带附件消息");
        List<Map<String, Object>> snap = parseJsonArray(row.getUserAttachments());
        assertThat(snap).as("空闲路径快照覆盖全类型（含图片）—— 该语义本批不得改动")
            .hasSize(3);
        assertThat(snap).extracting(m -> String.valueOf(m.get("type")))
            .containsExactlyInAnyOrder("file", "video", "image");
        assertThat(entryOf(snap, "clip.mp4").get("contentId")).isEqualTo("77");
        assertThat(entryOf(snap, "季度报表.docx").get("contentId")).isNull();
        assertThat(parseStringArray(row.getImagePasteIds())).containsExactly("5");
    }

    // ════════════════════════════════════════════════════════════════════
    // 驱动真实 run（生产同构：队列 drain → append → armRealTimePersist 落库）
    // ════════════════════════════════════════════════════════════════════

    /** 真实驱动一次 {@link LlmAgentLoop#run}：busy-queued 由 turn-0 drain 消费并实时落库（生产同构）。 */
    private AgentState runLoop(String initialUserMessageId, String prompt) {
        LlmProvider provider = stopProvider("reply");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setMessageService(messageService);           // 真实 MessageService（真实 DB resume）
        loop.setImageAttachmentStore(new ImageAttachmentStore()); // busy 带图消费点（registerRunPromptImages）
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        contextFactory.setNotificationQueue(queue);       // 与 ChatService 同一队列实例
        loop.setContextFactory(contextFactory);
        loop.setStreamContext(null, session, initialUserMessageId);
        // 生产落库武装（ChatService.processUserMessage 同款）：append 时点实时落库到真实 DB
        loop.setPostHistoryPersistEnabler(state ->
            chatService.armRealTimePersist(state, session, null, null, initialUserMessageId));

        return loop.run(RunRequest.session(prompt, session, null,
            ProviderConfig.empty(), "test-model", null, null));
    }

    /** 假 provider：首调即回 stop 纯文本 → loop 正常退出（不碰外部模型）。 */
    private static LlmProvider stopProvider(String text) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept(text);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage(text, "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    // ─────────────────────────── DB / 断言 helpers ───────────────────────────

    /** 建一个真实存在的临时文件（path 通道的 Files.exists 门需要）→ 返回绝对路径串。 */
    private static String tempPath(String filename) {
        try {
            Path p = Files.createTempFile("nexusai-busy-att-", "-" + filename);
            Files.writeString(p, "bytes");
            return p.toString();
        } catch (Exception e) {
            throw new AssertionError("临时文件夹具创建失败: " + filename, e);
        }
    }

    private MessageRecord rowById(String id) {
        for (MessageRecord r : messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", session).orderBy("seq", true))) {
            if (id.equals(r.getId())) {
                return r;
            }
        }
        return null;
    }

    private static ChatMessageDto findById(List<ChatMessageDto> msgs, String id) {
        for (ChatMessageDto m : msgs) {
            if (m != null && id.equals(m.id())) {
                return m;
            }
        }
        return null;
    }

    /** DB JSON 列 → List（保序）；空白/非法 → 空列表（断言侧 fail loud）。 */
    private static List<Map<String, Object>> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            throw new AssertionError("JSON 列解析失败: " + json, e);
        }
    }

    /** DB JSON 字符串数组列（image_paste_ids 等）→ List&lt;String&gt;；空白/非法 → 空列表（断言侧 fail loud）。 */
    private static List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new AssertionError("JSON 字符串数组列解析失败: " + json, e);
        }
    }

    private static Map<String, Object> entryOf(List<Map<String, Object>> snap, String filename) {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> m : snap) {
            if (filename.equals(m.get("filename"))) {
                hits.add(m);
            }
        }
        if (hits.size() != 1) {
            throw new AssertionError("快照中 filename=" + filename + " 命中 " + hits.size() + " 条: " + snap);
        }
        return hits.get(0);
    }
}
