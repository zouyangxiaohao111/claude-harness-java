package com.nexusai.domain.session;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.apis.export.ExportController;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.subagent.ResumeService;
import com.nexusai.application.chat.ChatService;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.provider.dto.ModelTag;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionGroup;
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
import org.mockito.ArgumentCaptor;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [seq NULL 兜底 · 其余 6 处读路径 · 真 SQLite + 真 MyBatis-Flex] 把「NULL 一律不冒充任何真实位置」
 * 这条口径钉死在 {@link MessageService} 两条主读路径<b>之外</b>的每一处 seq 排序点上。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：{@code SEQ_ASC_NULLS_LAST_ORDER} /
 * {@code SEQ_DESC_NULLS_LAST_ORDER} 已在 {@code listBySession} / {@code listPageBySession} 落地，
 * 但同一批裸 {@code ORDER BY seq} 还散在 6 处（导出 / 复制 / resume 链首 / 标题两条输入 / 最近 N 条），
 * 其中<b>两处是功能性错误而不只是「顺序难看」</b>：
 * <ol>
 *   <li><b>{@link ResumeService} 取「链首」→ firstCwd</b>：裸 {@code seq ASC} 下 NULL 排最前 → 拿到的
 *       「链首」可能是一条 seq 未知的存量脏行，其 cwd 也就成了恢复出的工作目录 →
 *       <b>子代理在错的目录下干活</b>；</li>
 *   <li><b>{@link ChatService} 取「首条 title-worthy 用户消息」→ 会话标题</b>：同上，脏行冒充「首条」
 *       → <b>标题用错消息</b>。</li>
 * </ol>
 *
 * <p><b>为什么不是死代码（别以为 V71 之后 NULL 不可能出现）</b>：V71 的回填 + NULL 拒绝触发器只在
 * 「已升级并重启到新构建」的机器上跑过；未升级的库 / 从旧备份还原的库 / 被外部工具直写的库仍可能带
 * NULL 行。读侧不能假设全世界的库都干净 → 这 6 处是<b>纵深防御</b>，本类就是它们不回退的守卫。
 *
 * <p><b>数据装配</b>：真 Flyway 全量迁移（V1..V71，与生产启动同路径）+ 真 {@link MessageMapper}
 * （走生产同一条 MyBatis-Flex 渲染链），再<b>刻意 DROP 掉 V71 的两个 NULL 拒绝触发器</b>后直插 NULL 行
 * —— 以此重建「升级前的库」的数据形态（否则触发器会把 NULL 行挡在门外，本类根本造不出被考察的场景）。
 *
 * <p><b>RED 条件（变异验证，逐条实测过）</b>：
 * <ul>
 *   <li>第 3 处改回裸 {@code .orderBy("seq", true)} → {@link #resumeChainHeadCwdNeverComesFromNullRow()} 红
 *       （链首变成脏行，cwd 恢复成 {@code D:/dirty/wrong-cwd}）；</li>
 *   <li>第 5 处改回裸 {@code .orderBy("seq", true)} → {@link #firstTitleWorthyUserMessageNeverComesFromNullRow()}
 *       与 {@link #titleGenerationFeedsRealFirstUserMessageNotNullRow()} 红（标题输入变成脏行）；</li>
 *   <li>第 1/2 处改回裸 {@code .orderBy("seq")} → {@link #exportAndCopyPutNullRowsLast()} 红
 *       （导出正文以脏行开头）。</li>
 * </ul>
 */
@DisplayName("[seq NULL 兜底·其余 6 处] 导出/复制 · resume 链首 · 标题输入 · 最近 N 条（真 SQLite）")
class SeqNullsLastReadPathsRealSqliteTest {

    /** 真实链首（seq 最小）消息的 cwd —— resume 必须恢复成「这个」。 */
    private static final String REAL_CWD = "D:/code/ai_project/real-project-root";

    /** 脏行的 cwd —— resume <b>绝不能</b>恢复成「这个」（否则子代理在错的目录下干活）。 */
    private static final String DIRTY_CWD = "D:/dirty/wrong-cwd";

    /** 脏行内容标记（断言「没被当成首条」时按这个找）。 */
    private static final String DIRTY_MARK = "【脏行·位置未知】";

    private static final String U1 = "真实首条用户消息：帮我修复登录按钮";
    private static final String A1 = "真实助手回复：好的，我先看看登录按钮的实现。";
    private static final String U2 = "真实第二条用户消息：顺便看看退出登录";
    private static final String D1 = DIRTY_MARK + "脏行一内容";
    private static final String D2 = DIRTY_MARK + "脏行二内容";

    /** 真实行的 seq（模拟雪花量级，恒大于 V70 回填的 1..N）。 */
    private static final long SEQ_U1 = 1000L;
    private static final long SEQ_A1 = 1001L;
    private static final long SEQ_U2 = 1002L;

    private static Path dbPath;
    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    /** 本用例独占会话（每个 @BeforeEach 新建；tearDown 自清）。 */
    private String session;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // hermetic：本次 class 运行独占随机 DB 目录（不碰共享 target/flex-dbtest/flex.db）
        Path dir = Path.of("target", "flex-dbtest", "seq-nulls-rest-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        dbPath = dir.resolve("flex.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        ds.setBusyTimeout(10_000);
        // 真 Flyway 全量迁移（与生产启动同路径）→ 真表 + 真索引 idx_messages_session_seq（V70:49）
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").baselineOnMigrate(true)
            .load().migrate();
        // 刻意摘掉 V71 的 NULL 拒绝触发器：不摘就插不进 NULL 行，本类无从构造被考察的场景。
        //   这不是「绕过生产约束」，而是重建「未升级到本构建 / 从旧备份还原 / 被外部工具直写」的库形态
        //   （正是读侧 NULL 兜底要服务的世界）。生产库里触发器仍会把新 NULL 挡在门外。
        dropV71NullTriggers();
        MybatisFlexDbTestSupport.resetAndStart(ds, MessageMapper.class, SessionMapper.class, ToolCallMapper.class);
        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(MessageMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
        toolCallMapper = MybatisFlexBootstrap.getInstance().getMapper(ToolCallMapper.class);
    }

    @BeforeEach
    void setUp() {
        SessionCwdHolder.reset();
        session = "sess-seq-rest-" + UUID.randomUUID().toString().substring(0, 8);
        SessionRecord s = new SessionRecord();
        s.setId(session);
        s.setModelTag("DS");
        s.setModelName("test-model");
        s.setTitle("新会话");
        s.setTime("刚刚");
        s.setSessionGroup("default");
        sessionMapper.insertSelective(s);
    }

    @AfterEach
    void tearDown() {
        SessionCwdHolder.reset();
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", session));
        sessionMapper.deleteById(session);
    }

    // ═══════════════════════════ 第 1/2 处：导出 / 复制 ═══════════════════════════

    @Test
    @DisplayName("导出 / 复制（ASC）：正文按真实 seq 序，NULL 脏行<不>冒充「会话首条」被排到最前")
    void exportAndCopyPutNullRowsLast() {
        // GIVEN：真实三段 + 两条 seq 未知的脏行（脏行内容极具辨识度）
        seed("u1", U1, SEQ_U1, "user", REAL_CWD);
        seed("a1", A1, SEQ_A1, "assistant", REAL_CWD);
        seed("u2", U2, SEQ_U2, "user", REAL_CWD);
        seed("d1", D1, null, "user", DIRTY_CWD);
        seed("d2", D2, null, "user", DIRTY_CWD);

        // WHEN：走真实 REST 端点方法（真 SessionService + 真 MessageMapper）
        ExportController controller = exportController();
        String body = controller.export(session, "md").getBody();
        Map<String, Object> copyResult = controller.copy(session);

        // THEN：真实消息按 seq 升序；脏行全部落在真实消息之后（位置未知 → 不冒充首条）
        assertThat(body).as("导出正文非空").isNotNull();
        assertThat(body.indexOf(U1))
            .as("真实首条必须在真实助手回复之前（seq ASC 的正常形）").isLessThan(body.indexOf(A1));
        assertThat(body.indexOf(A1)).isLessThan(body.indexOf(U2));
        assertThat(body.indexOf("脏行"))
            .as("【核心】seq 为 NULL 的脏行不得出现在真实末条之前 —— 裸 seq ASC 下它会冒充「会话首条」"
                + "被排到导出正文最前（导出内容从一条位置未知的脏消息开始）")
            .isGreaterThan(body.indexOf(U2));
        assertThat(copyResult.get("messages")).as("复制统计的条数 = 全部 5 行（不因排序丢行）").isEqualTo(5);
        assertThat((Integer) copyResult.get("chars")).as("复制正文长度非 0").isGreaterThan(0);
    }

    // ═══════════════════════════ 第 3 处：resume 链首 → firstCwd ═══════════════════════════

    @Test
    @DisplayName("resume 链首（ASC LIMIT 1）：取到的是 seq 最小的<真实>消息 → cwd 不来自 NULL 脏行")
    void resumeChainHeadCwdNeverComesFromNullRow() {
        // GIVEN：真实链首带正确 cwd；另有一条 seq 未知的脏行带一个「错的」cwd
        seed("u1", U1, SEQ_U1, "user", REAL_CWD);
        seed("a1", A1, SEQ_A1, "assistant", REAL_CWD);
        seed("d1", D1, null, "user", DIRTY_CWD);

        // WHEN：走 resume 的目录恢复（私有方法：ResumeService.restoreSessionCwd，生产由 resume 主流程调用）
        ResumeService service = new ResumeService();
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.invokeMethod(service, "restoreSessionCwd", session);

        // THEN：恢复出的会话 cwd = 真实链首的 cwd（normalizeCwd 对不存在的 ASCII 路径回原值+NFC）
        assertThat(SessionCwdHolder.get(session))
            .as("【核心·功能缺陷】resume 必须用真实链首的 cwd 恢复工作目录；裸 seq ASC 下链首变成 NULL 脏行"
                + " → 恢复出 " + DIRTY_CWD + " → 子代理在错的目录下干活")
            .isEqualTo(REAL_CWD);
    }

    // ═══════════════════════════ 第 5 处：首条 title-worthy 用户消息 ═══════════════════════════

    @Test
    @DisplayName("首条 title-worthy 用户消息（ASC LIMIT 1）：不取 NULL 脏行（否则标题用错消息）")
    void firstTitleWorthyUserMessageNeverComesFromNullRow() {
        seed("u1", U1, SEQ_U1, "user", REAL_CWD);
        seed("a1", A1, SEQ_A1, "assistant", REAL_CWD);
        seed("d1", D1, null, "user", DIRTY_CWD);

        ChatService chatService = chatService();

        String firstUser = ReflectionTestUtils.invokeMethod(chatService, "extractFirstUserContent", session);

        assertThat(firstUser)
            .as("【核心·功能缺陷】标题 count1 的输入必须是真实首条用户消息；裸 seq ASC 下 NULL 脏行冒充「首条」"
                + " → 标题用错消息")
            .isEqualTo(U1);
    }

    @Test
    @DisplayName("端到端：maybeGenerateTitle（公开入口）真正喂给模型的输入来自真实首条用户消息，不含脏行")
    void titleGenerationFeedsRealFirstUserMessageNotNullRow() {
        // GIVEN：真实首条用户消息 + 一条 seq 未知的脏行；标题为占位（looksLikeDefault）
        seed("u1", U1, SEQ_U1, "user", REAL_CWD);
        seed("d1", D1, null, "user", DIRTY_CWD);

        LlmProvider provider = mock(LlmProvider.class);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        when(resolver.resolveFastModelName(anyString())).thenReturn("mock-fast");
        when(factory.getProvider(any(ProviderConfig.class), anyString())).thenReturn(provider);
        when(provider.chatWithOptions(any(ProviderConfig.class), anyString(), anyString(), anyString(),
            any(LlmProvider.ChatRequestOptions.class))).thenReturn("{\"title\":\"修复登录按钮\"}");

        ChatService chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(chatService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(chatService, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(chatService, "modelConfigResolver", resolver);
        ReflectionTestUtils.setField(chatService, "llmProviderFactory", factory);

        SessionRecord s = new SessionRecord();
        s.setId(session);
        s.setModelName("test-model");
        s.setTitle("新会话");        // 占位 → isDefaultTitle=true → count1 触发
        s.setTitleExplicit(0);

        // WHEN
        chatService.maybeGenerateTitle(s, "msg-1", "", null);

        // THEN：喂给模型的 userMessage 含真实首条用户消息原文、<不>含脏行内容
        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        verify(provider).chatWithOptions(any(ProviderConfig.class), anyString(), anyString(),
            userMessage.capture(), any(LlmProvider.ChatRequestOptions.class));
        assertThat(userMessage.getValue())
            .as("标题输入 = 真实首条用户消息（count1 语义：最早那条 title-worthy 用户消息）")
            .contains(U1);
        assertThat(userMessage.getValue())
            .as("【核心】seq 未知的脏行不得进入标题输入（裸 seq ASC 下它会冒充「首条」）")
            .doesNotContain(DIRTY_MARK);
        assertThat(s.getTitle()).as("标题真被生成并应用（链路端到端跑通）").isEqualTo("修复登录按钮");
    }

    // ═══════════════════════════ 第 4 处：会话正文尾部 1000 字符 ═══════════════════════════

    @Test
    @DisplayName("会话正文尾部（ASC）：拼接序把 NULL 脏行推到最末，尾部窗口配比不被脏行带偏")
    void conversationTextTailKeepsNullRowAtTheVeryEnd() {
        // GIVEN：真实正文合计 > 1000 字符（窗口会被真实内容填满），另加一条脏行
        String realUser = "甲".repeat(600);
        String realAssistant = "乙".repeat(600);
        String dirty = DIRTY_MARK + "丙".repeat(200);
        seed("u1", realUser, SEQ_U1, "user", REAL_CWD);
        seed("a1", realAssistant, SEQ_A1, "assistant", REAL_CWD);
        seed("d1", dirty, null, "user", DIRTY_CWD);

        ChatService chatService = chatService();

        // WHEN
        String tail = ReflectionTestUtils.invokeMethod(chatService, "extractConversationTextTail", session);

        // THEN：期望串 = 真实行（seq 升序）在前、脏行在末尾，取尾部 1000 字符
        String expected = realUser + "\n" + realAssistant + "\n" + dirty;
        assertThat(tail)
            .as("【核心】裸 seq ASC 下脏行被排到最前 → 整个 1000 字符窗口后移（脏行长度+1 位）→ 与期望不等")
            .isEqualTo(expected.substring(expected.length() - 1000));
    }

    // ═══════════════════════════ 第 6 处：最近 N 条（DESC） ═══════════════════════════

    @Test
    @DisplayName("最近 N 条（DESC LIMIT）：拿到的必须是真实最新行，NULL 脏行<不>冒充「最近」")
    void loadRecentHistoryDescNeverTakesNullRow() {
        seed("u1", U1, SEQ_U1, "user", REAL_CWD);
        seed("a1", A1, SEQ_A1, "assistant", REAL_CWD);
        seed("u2", U2, SEQ_U2, "user", REAL_CWD);
        seed("d1", D1, null, "user", DIRTY_CWD);
        seed("d2", D2, null, "user", DIRTY_CWD);

        ChatService chatService = chatService();

        @SuppressWarnings("unchecked")
        List<ChatMessageDto> recent =
            ReflectionTestUtils.invokeMethod(chatService, "loadRecentHistory", session, 3);

        assertThat(recent).as("取最近 3 条").hasSize(3);
        assertThat(recent.stream().map(ChatMessageDto::id).toList())
            .as("【核心】最近 3 条 = 真实最新的 u1/a1/u2；若 NULL 冒充「最近」被算进 LIMIT 3，"
                + "这里会出现脏行或丢掉真实最新那条")
            .containsExactlyInAnyOrderElementsOf(List.of("u1", "a1", "u2"));
        assertThat(recent.stream().map(ChatMessageDto::content).toList())
            .as("脏行内容不得出现在「最近 N 条」里")
            .noneSatisfy(c -> assertThat(c).contains(DIRTY_MARK));
    }

    @Test
    @DisplayName("DESC 侧实测：`seq DESC` 与 `seq DESC NULLS LAST` 逐行等价 = 意图显式化，不是行为修复")
    void descNullsLastIsExplicitDeclarationNotBehaviorChange() throws Exception {
        seed("u1", U1, SEQ_U1, "user", REAL_CWD);
        seed("a1", A1, SEQ_A1, "assistant", REAL_CWD);
        seed("u2", U2, SEQ_U2, "user", REAL_CWD);
        seed("d1", D1, null, "user", DIRTY_CWD);
        seed("d2", D2, null, "user", DIRTY_CWD);

        try (Connection conn = open()) {
            List<String> bare = ids(conn, "SELECT id FROM messages WHERE session_id='" + session
                + "' ORDER BY seq DESC");
            List<String> nullsLast = ids(conn, "SELECT id FROM messages WHERE session_id='" + session
                + "' ORDER BY " + MessageService.SEQ_DESC_NULLS_LAST_ORDER);
            List<String> nullsFirst = ids(conn, "SELECT id FROM messages WHERE session_id='" + session
                + "' ORDER BY seq DESC NULLS FIRST");

            System.out.println("[seq-nulls-last][实测] DESC 裸             = " + bare);
            System.out.println("[seq-nulls-last][实测] DESC NULLS LAST   = " + nullsLast);
            System.out.println("[seq-nulls-last][实测] DESC NULLS FIRST  = " + nullsFirst);

            assertThat(nullsLast)
                .as("SQLite 视 NULL 为最小 → 裸 seq DESC 下 NULL 本来就排最后；DESC NULLS LAST 是"
                    + "「把该语义显式钉住」（防后来者写成 NULLS FIRST），逐行等价 → 非行为变更")
                .isEqualTo(bare);
            assertThat(nullsFirst)
                .as("反例固化：NULLS FIRST 才会改变行为（NULL 顶成「最近」）——正是片段要拦住的东西")
                .isNotEqualTo(bare);
            assertThat(nullsFirst.subList(0, 2))
                .as("NULLS FIRST 下头两条就是脏行（冒充「最近」）")
                .containsExactlyInAnyOrder("d1", "d2");
        }
    }

    // ═══════════════════════ 有意不改的那一处：seedMaxSeq（取 max(seq)） ═══════════════════════

    @Test
    @DisplayName("seedMaxSeq（裸 seq DESC LIMIT 1）：DESC 下 NULL 排最后 → 拿到 max(非 NULL)，故<有意>不加 NULLS LAST")
    void seedMaxSeqBareDescStillReturnsMaxNonNullSeq() {
        seed("u1", U1, SEQ_U1, "user", REAL_CWD);
        seed("u2", U2, SEQ_U2, "user", REAL_CWD);
        seed("d1", D1, null, "user", DIRTY_CWD);
        seed("d2", D2, null, "user", DIRTY_CWD);

        MessageService service = new MessageService();
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);

        Long max = ReflectionTestUtils.invokeMethod(service, "seedMaxSeq", session);

        assertThat(max)
            .as("裸 seq DESC + SQLite「NULL 最小」= LIMIT 1 恰好给出 max(非 NULL seq)；"
                + "此处若补 NULLS LAST 不改变任何一行 → 是噪音（MessageService seedMaxSeq 已注释说明）")
            .isEqualTo(SEQ_U2);
    }

    // ═══════════════ 六处生产调用点：flex 实际发出的 SQL + 查询计划（不许废索引） ═══════════════

    @Test
    @DisplayName("六处调用点发出的 SQL 都含 NULLS LAST；在真表+真索引上均走 idx_messages_session_seq 且无 TEMP B-TREE")
    void allSixProductionSitesEmitNullsLastAndKeepIndexUsable() throws Exception {
        String asc = MessageService.SEQ_ASC_NULLS_LAST_ORDER;
        String desc = MessageService.SEQ_DESC_NULLS_LAST_ORDER;

        List<String> emitted = new ArrayList<>();

        // ① 导出（ExportController.export）
        emitted.add(emittedSql(asc, "导出 GET /api/v1/export/{id}",
            m -> exportController(m).export("sess-x", "md")));
        // ② 复制（ExportController.copy）
        emitted.add(emittedSql(asc, "复制 POST /{id}/copy",
            m -> exportController(m).copy("sess-x")));
        // ③ resume 链首（ResumeService.restoreSessionCwd）
        emitted.add(emittedSql(asc, "resume 链首 firstCwd", m -> {
            ResumeService s = new ResumeService();
            ReflectionTestUtils.setField(s, "messageMapper", m);
            return ReflectionTestUtils.invokeMethod(s, "restoreSessionCwd", "sess-x");
        }));
        // ④ 会话正文尾部（ChatService.extractConversationTextTail）
        emitted.add(emittedSql(asc, "标题 count3 正文尾部",
            m -> ReflectionTestUtils.invokeMethod(chatService(m), "extractConversationTextTail", "sess-x")));
        // ⑤ 首条 title-worthy 用户消息（ChatService.extractFirstUserContent）
        emitted.add(emittedSql(asc, "标题 count1 首条用户消息",
            m -> ReflectionTestUtils.invokeMethod(chatService(m), "extractFirstUserContent", "sess-x")));
        // ⑥ 最近 N 条（ChatService.loadRecentHistory）
        emitted.add(emittedSql(desc, "最近 N 条",
            m -> ReflectionTestUtils.invokeMethod(chatService(m), "loadRecentHistory", "sess-x", 50)));

        // ── 断言 1：flex 原样发出含 NULLS LAST 的片段（不包装/不转义/不追加方向）──
        for (String sql : emitted) {
            assertThat(sql)
                .as("六处调用点都必须经两条 public 常量发出 NULLS LAST（不得硬编码裸 seq）；实际 SQL=" + sql)
                .contains("NULLS LAST");
            assertThat(sql)
                .as("方向已含在片段里，调用方不得再叠加第二个排序键（出现 `NULLS LAST,` 即表示有人加回 orderBy）"
                    + "；实际 SQL=" + sql)
                .doesNotContain("NULLS LAST,");
            assertThat(sql)
                .as("旧表达式前置键（废索引元凶）不得复活；实际 SQL=" + sql)
                .doesNotContain("seq IS NULL,");
        }

        // ── 断言 2：真表 + 真索引上，查询计划必须由 idx_messages_session_seq 出序（无全量临时排序）──
        try (Connection conn = open()) {
            for (String sql : emitted) {
                List<String> plan = explainQueryPlan(conn, sql);
                System.out.println("[seq-nulls-last][生产栈] SQL  = " + sql);
                System.out.println("[seq-nulls-last][生产栈] PLAN = " + plan);
                assertThat(plan)
                    .as("必须走 idx_messages_session_seq（否则是全表扫 + 排序）；SQL=" + sql + "；实际计划=" + plan)
                    .anySatisfy(line -> assertThat(line).contains("USING INDEX idx_messages_session_seq"));
                assertThat(plan)
                    .as("【核心】排序键必须是索引列 seq，不得退化为全量临时排序；SQL=" + sql + "；实际计划=" + plan)
                    .noneSatisfy(line -> assertThat(line).contains("TEMP B-TREE"));
            }
        }
    }

    // ═══════════════════════════════ 装配 / 夹具 / 基建 ═══════════════════════════════

    /** 生产导出端点 + 真 mapper + mock SessionService（本用例只需会话 DTO）。 */
    private ExportController exportController() {
        return exportController(messageMapper);
    }

    private ExportController exportController(MessageMapper mapper) {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getById(anyString())).thenReturn(sessionDto());
        ExportController controller = new ExportController();
        ReflectionTestUtils.setField(controller, "sessionService", sessionService);
        ReflectionTestUtils.setField(controller, "messageMapper", mapper);
        return controller;
    }

    private static SessionDto sessionDto() {
        return new SessionDto("sess-x", ModelTag.DS, "test-model", "导出示例", "刚刚", SessionGroup.current,
            null, null, null, OffsetDateTime.now(), OffsetDateTime.now(),
            null, null, null, null, null, null, null, null, null);
    }

    /** 真 ChatService（真 mapper），仅替换标题链路需要的外部依赖。 */
    private ChatService chatService() {
        return chatService(messageMapper);
    }

    private ChatService chatService(MessageMapper mapper) {
        ChatService service = new ChatService();
        ReflectionTestUtils.setField(service, "messageMapper", mapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "modelConfigResolver", mock(ModelConfigResolver.class));
        ReflectionTestUtils.setField(service, "llmProviderFactory", mock(LlmProviderFactory.class));
        return service;
    }

    /**
     * 用 mock mapper 接住生产方法发起的查询 → 取 flex 实际渲染出的 SQL，断言含期望片段，
     * 并返回<b>可 EXPLAIN 的生产形态 SQL</b>。
     *
     * <p><b>补表名那一步的 WHY</b>：生产链路里 {@code messageMapper.selectListByQuery(wrapper)} 由
     * MyBatis-Flex 按 mapper 的实体（{@code MessageRecord} → 表 {@code messages}）在执行期补上
     * FROM 与列清单；单独调 {@code wrapper.toSQL()} 时表名位为空（渲染成 {@code SELECT * FROM  WHERE}），
     * 语句不可执行。故这里把空表名位补回 {@code messages}（列清单用 {@code *}，与排序/过滤无关），
     * 得到与生产实发 SQL 同形的语句供 {@code EXPLAIN QUERY PLAN} 使用；补名失败会显式报错（不静默）。
     */
    private static String emittedSql(String expectFragment, String what,
                                     Function<MessageMapper, Object> call) {
        MessageMapper mapper = mock(MessageMapper.class);
        when(mapper.selectListByQuery(any())).thenReturn(List.of());
        when(mapper.selectCountByQuery(any())).thenReturn(0L);
        call.apply(mapper);
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        try {
            verify(mapper).selectListByQuery(captor.capture());
        } catch (Throwable t) {
            throw new AssertionError("[" + what + "] 生产方法未发起 selectListByQuery（装配错了？）", t);
        }
        String rendered = captor.getValue().toSQL();
        assertThat(rendered).as("[" + what + "] 必须用 " + expectFragment + " 片段；实际=" + rendered)
            .contains(expectFragment);
        String planSql = rendered.replaceFirst("FROM\\s+WHERE", "FROM messages WHERE");
        assertThat(planSql)
            .as("[" + what + "] 补表名失败（flex 渲染形状变了？）→ EXPLAIN 用的语句不可执行；rendered=" + rendered)
            .isNotEqualTo(rendered)
            .contains("FROM messages WHERE");
        return planSql;
    }

    /** 直插一行消息（可带 seq=null；需 V71 触发器已摘，见 {@link #setUpDatabase()}）。 */
    private void seed(String id, String content, Long seq, String role, String cwd) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO messages(id, session_id, role, content, created_at, seq, cwd, is_meta) "
                     + "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, session);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setString(5, "2026-09-11T10:00:00+08:00");
            if (seq == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setLong(6, seq);
            }
            ps.setString(7, cwd);
            ps.setNull(8, java.sql.Types.INTEGER);   // is_meta NULL = 存量旧行形态（非 meta）
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("seed 失败 id=" + id, e);
        }
    }

    /** 摘掉 V71 的 NULL 拒绝触发器（见 {@link #setUpDatabase()} 的 WHY）。 */
    private static void dropV71NullTriggers() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TRIGGER IF EXISTS trg_messages_seq_not_null_insert");
            st.executeUpdate("DROP TRIGGER IF EXISTS trg_messages_seq_not_null_update");
        }
    }

    private static Connection open() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private static List<String> ids(Connection conn, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** {@code EXPLAIN QUERY PLAN} 的 detail 列（第 4 列）逐行。 */
    private static List<String> explainQueryPlan(Connection conn, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            while (rs.next()) {
                out.add(rs.getString(4));
            }
        }
        return out;
    }
}
