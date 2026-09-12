package com.nexusai.application.chat;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.skill.SkillCatalog;
import com.nexusai.application.agent.skill.SkillListingSentRegistry;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.command.Command;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [skill-listing 端到端 · 2026-09-10] <b>真实 run → 真实 MessageService → 真实 SQLite → listRawForTranscript(seq) 重放</b>。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 验证意图）</b>：skill_listing 回归 CC 的一致性验证此前<b>只到</b>两层——
 * ① 内存态 {@code state.rawMessages()} 索引断言（{@code LlmAgentLoopSkillListingInjectTest}）；
 * ② 手工构造 DTO 调落库分支（{@code ChatServiceSkillListingPersistTest} / {@code ChatServiceSkillListingSeqStampTest}）。
 * <b>从未有一条真实 run 把「注入 → 落库 → 重放」整链串起来</b>：位置（清单在用户消息之后）到底能不能经
 * DB 的 {@code messages.seq} 还原，只是被单测分别推断，未被同一条 run 观察。
 *
 * <p><b>本测试补的正是该缺口</b>：真实驱动 {@link LlmAgentLoop#run}（假 LLM provider 只回 stop 纯文本，
 * 不碰外部模型；真实 {@link MessageService} + 真实临时 SQLite（Flyway 全量迁移）），断言：
 * <ol>
 *   <li>DB 出现且仅出现一条 {@code author=attachment / subtype=skill_listing} 行；</li>
 *   <li>该行 {@code seq} 严格大于本会话用户消息行的 {@code seq}（= CC processTextPrompt.ts:97
 *       {@code [userMessage, ...attachmentMessages]} 的尾随位置）；</li>
 *   <li>{@link MessageService#listRawForTranscript}（{@code ORDER BY seq}）重放中清单行<b>位于用户消息之后</b>
 *       （{@code [..., user, ...attachmentMessages]}，CC 不保证紧邻）—— 重放能还原 live 同一位置；</li>
 *   <li>同会话第二次 run 不新增 skill_listing 行（resume 抑制，不重注）；</li>
 *   <li>技能集合出现新技能 → 只追加增量行（内容只含新技能名，不重发整份）；</li>
 *   <li>落库行形状 = CC 产物契约（role=user / author=attachment / subtype=skill_listing / isMeta=true /
 *       content 包裹 {@code <system-reminder>…</system-reminder>}）。</li>
 * </ol>
 *
 * <p><b>为什么用 busy-queued 的当前用户消息</b>（而非 controller 预落库的主 prompt 路径）：生产主路径里
 * 「当前用户消息行」由 {@code ChatController.createUserMessage} <b>在 run 之前</b>写好，其 seq 恒早于 run 内
 * 落库的清单行 —— 此时「注入点先于/后于用户消息」在 DB 的 seq 上<b>不可观测</b>（位置即便被挪到用户消息之前，
 * 清单行的 seq 仍大于预落库的用户行）。要让「清单行 seq &gt; 用户行 seq」这条链路真正 load-bearing，用户行必须
 * <b>由本次 run 自己落库</b>。生产里唯一由 run 落库的 user 行是「忙时排队消息」（busy-queued，
 * {@code ChatService.persistAppendedMessage} user 分支 → {@code createQueuedUserMessage} 取 seq）。
 * 故本测试把当前用户消息经生产队列以 busy-queued 注入 → 用户行在 turn-0 drain 时落库（取 seq），
 * 清单行紧随其后注射并落库（取更大 seq）。这样「注入点必须在 turn-0 drain 之后」才在 DB 的 seq 上成立
 * —— 把注入点挪回循环之前 → 清单行 seq 反而小于用户行 → 断言 2/3 RED（见任务变异验证）。
 *
 * <p><b>不许 moke 的：</b>绕过 {@link LlmAgentLoop#run} / 绕过真实 {@link MessageService} / 绕过真实 DB。
 * 本类全部真实；仅 LLM provider 为假（不真调外部模型）、{@link SkillCatalog} 为受控 stub（技能集合是输入，
 * 非被测链路）。队列用生产 {@link NotificationQueue} 子类只为抑制 loop 自行入队的 turn-0 标准 prompt，
 * 保证队列里只有一条当前用户消息（真实 drain/persist 链路不变）。
 *
 * <p><b>DB 装配（hermetic · 2026-09-10 修复轮）</b>：<b>本次测试类运行独占</b>的 SQLite 文件
 * （{@code target/flex-dbtest/e2e-<random>/flex.db}，每 JVM 一次随机目录）+ Flyway 全量迁移 +
 * {@code resetAndStart}。WHY 不用共享 {@code target/flex-dbtest/flex.db}：并发 JVM（同机多会话同时
 * 跑本测试）会争同一文件 → {@code SQLITE_BUSY} 被落库 listener 的 try/catch 吞掉、resume 读取失败回落
 * {@code resume=false} → 偶发「整份重发 / 位置倒挂」假 RED；且前次运行的残留行也落在同一文件。
 * 独占文件 + 每用例唯一 sessionId + {@link #tearDown} 自清本会话行 → 无跨次/跨 JVM 污染，测试幂等。
 */
@DisplayName("[skill-listing 端到端] 真实 run → 真实 MessageService → 真实 SQLite → listRawForTranscript(seq) 重放")
class SkillListingRealRunDbE2eTest {

    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    /** 本次测试类运行独占的 DB 文件路径（@BeforeAll 内随机目录，杜绝跨 JVM/跨次共享）。 */
    private static Path dbPath;

    /** 本用例唯一会话（独占 DB 上隔离；tearDown 自清）。 */
    private String session;

    private MessageService messageService;
    private ChatService chatService;

    /** 受控技能集合（run 间可改，模拟「中途新增技能」）。 */
    private final AtomicReference<List<Command>> catalogCommands = new AtomicReference<>(List.of());

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // hermetic：本次 class 运行独占一个随机 DB 目录 —— 不碰共享 target/flex-dbtest/flex.db，
        //   并发 JVM / 前次运行的残留行都影响不到本类（见类 javadoc DB 装配）。
        Path dir = Path.of("target", "flex-dbtest",
            "e2e-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        dbPath = dir.resolve("flex.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        // 独占文件亦给足 busy_timeout：SQLiteDataSource 默认 3000ms，单 JVM 内足够；显式压实意图。
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
        SkillListingSentRegistry.reset();
        session = "sess-e2e-" + UUID.randomUUID().toString().substring(0, 8);

        // 真实 sessions 行（listRawForTranscript 会校验存在）
        SessionRecord s = new SessionRecord();
        s.setId(session);
        s.setModelTag("DS");
        s.setModelName("test-model");
        s.setTitle("skill-listing e2e");
        s.setTime("刚刚");
        s.setSessionGroup("default");
        sessionMapper.insertSelective(s);

        // 真实 MessageService（仅替换 mapper，无 Spring 上下文）
        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper", toolCallMapper);

        // 真实 ChatService 落库通道（生产 armRealTimePersist 的宿主）
        chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(chatService, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(chatService, "messageService", messageService);
    }

    @AfterEach
    void tearDown() {
        SkillListingSentRegistry.reset();
        // 自清：删除本用例会话的全部消息行 + 会话行。DB 已独占，此处仍清以防「同一次 class run 内
        //   TestA 的残留行被 TestB 的 resume 判定误读」（sessionId 已隔离，此为纵深防御 + 显式无残留）。
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", session));
        sessionMapper.deleteById(session);
    }

    // ════════════════════════════════════════════════════════════════════
    // 主用例：一次真实 run 的落库 + 重放
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("真实 run → DB 落一行 skill_listing（author=attachment）；seq 在用户行之后；listRawForTranscript 重放还原位置")
    void realRun_persistsListingAfterUser_rowShapeAndReplayOrder() {
        // 全量 = [commit, review]
        catalogCommands.set(List.of(cmd("commit"), cmd("review")));

        AgentState state = runOnce(uid(), "first query");

        // 内存态也已注入（sanity：证明 run 真跑了注入链）
        assertThat(listingContent(state.rawMessages())).as("真实 run 内已注入 skill_listing").isNotNull();

        List<MessageRecord> rows = dbRowsBySeq();
        List<MessageRecord> listings = rows.stream()
            .filter(r -> "skill_listing".equals(r.getSubtype())).toList();

        // ① 恰好一行 skill_listing，且 author/subtype 契约
        assertThat(listings).as("真实 run 后 DB 恰有 1 行 subtype=skill_listing").hasSize(1);
        MessageRecord listing = listings.get(0);
        assertThat(listing.getAuthor()).as("author=attachment（CC attachment 契约）").isEqualTo("attachment");
        assertThat(listing.getRole()).isEqualTo(Role.user.name());

        // 找本会话用户消息行（run 自己落库的 busy-queued 当前用户消息）
        MessageRecord userRow = rows.stream()
            .filter(r -> Role.user.name().equals(r.getRole())
                && !"skill_listing".equals(r.getSubtype()))
            .findFirst().orElseThrow(() -> new AssertionError(
                "DB 缺当前用户消息行（busy-queued 当前用户消息未被 run 落库）: "
                    + rows.stream().map(r -> r.getRole() + "/" + r.getSubtype()).toList()));

        // ② 清单行 seq 严格大于用户行 seq（= 紧随用户消息之后；历史上曾注入到用户消息之前）
        assertThat(listing.getSeq())
            .as("skill_listing 行必须取 seq（缺号 → ORDER BY seq 下 NULL 排最前 → 清单飞到上下文最前）")
            .isNotNull();
        assertThat(userRow.getSeq()).as("用户行 seq 非空").isNotNull();
        assertThat(listing.getSeq())
            .as("清单行 seq 必须严格大于用户行 seq —— CC [userMessage, ...attachmentMessages] 尾随位置（本批最易错处）")
            .isGreaterThan(userRow.getSeq());

        // ③ listRawForTranscript（ORDER BY seq）重放顺序 = [..., user, skill_listing] → 还原 live 同一位置
        List<ChatMessageDto> replay = messageService.listRawForTranscript(session);
        List<String> replayIds = replay.stream().map(ChatMessageDto::id).toList();
        assertThat(replayIds)
            .as("重放顺序 = DB seq 顺序（live 位置经 DB 可还原）")
            .containsExactlyElementsOf(rows.stream().map(MessageRecord::getId).toList());

        int userIdx = indexOfId(replay, userRow.getId());
        int listingIdx = indexOfId(replay, listing.getId());
        assertThat(userIdx).as("用户消息行在重放中").isGreaterThanOrEqualTo(0);
        // [2026-09-10 修复轮] 只断言「在用户消息之后」，不断言「紧邻」（userIdx+1）：CC 的语义是
        //   processTextPrompt.ts:97 messages:[userMessage, ...attachmentMessages]，attachmentMessages 是
        //   有序列表，user 与 skill_listing 之间可夹其它 turn-0 attachment（如 agent_listing_delta，
        //   attachments.ts:903 先于 skill_listing:927 注册）。nexusai 当前无其它 turn-0 attachment producer
        //   （AttachmentMessageDto 自注「Java 无 agent_listing_delta producer」）故恰好 +1，但把「碰巧相邻」
        //   固化成 CC 契约，会在将来补齐其它 attachment 时对符合 CC 的实现误报 RED。核心语义 = 严格在用户之后。
        assertThat(listingIdx)
            .as("重放中清单必须在用户消息之后（[..., user, ...attachmentMessages]；CC 不保证紧邻）")
            .isGreaterThan(userIdx);

        // ⑥ 行形状 = CC 产物契约（以实际实现为准并记录）
        ChatMessageDto replayListing = replay.get(listingIdx);
        assertThat(replayListing.role()).as("role=user（CC createUserMessage）").isEqualTo(Role.user);
        assertThat(replayListing.author()).as("author=attachment").isEqualTo("attachment");
        assertThat(replayListing.subtype()).as("subtype=skill_listing（重放判别键）").isEqualTo("skill_listing");
        assertThat(replayListing.isMeta()).as("isMeta=true（前端隐藏元消息）").isTrue();
        assertThat(replayListing.content())
            .as("content = CC messages.ts:4160-4170 渲染包裹")
            .startsWith("<system-reminder>\nThe following skills are available for use with the Skill tool:\n\n")
            .contains("- commit")
            .contains("- review")
            .endsWith("\n</system-reminder>");
        assertThat(listing.getIsMeta()).as("DB is_meta 列 = 1").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 同会话跨 run：resume 抑制 + 增量只追加
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("第二次 run（无新技能）→ 不新增 skill_listing 行；第三次 run（新增技能）→ 只追加增量行（不重发整份）")
    void secondRunNoDelta_noNewRow_thirdRunDelta_appendsOnlyNewSkill() {
        catalogCommands.set(List.of(cmd("commit"), cmd("review")));

        // ── run 1：全新会话 → 整份注入 ──
        runOnce(uid(), "first query");
        assertThat(listingRows()).as("run1 后 1 行清单").hasSize(1);
        assertThat(listingRows().get(0).getContent())
            .as("整份含 commit+review")
            .contains("commit").contains("review");

        // ── run 2：resume（DB 有历史）+ 无新技能 → 不注入、不新增行 ──
        assertThat(dbRowsBySeq()).as("run2 前已有历史（resume 判据成立）").isNotEmpty();
        List<String> listingIdsBeforeRun2 = listingRows().stream().map(MessageRecord::getId).toList();
        AgentState s2 = runOnce(uid(), "second query");
        // 内存态历史注入会带出 run1 的清单行 —— 断言「没有新增清单消息（id 均为 run2 前既有 id）」
        assertThat(listingIds(s2.rawMessages()))
            .as("无新技能 → 内存态不追加任何新清单消息（历史注入的 run1 行除外）")
            .allMatch(listingIdsBeforeRun2::contains);
        assertThat(listingRows().stream().map(MessageRecord::getId).toList())
            .as("④ 第二次 run 不新增 skill_listing 行（resume 抑制，不重注）")
            .containsExactlyElementsOf(listingIdsBeforeRun2);

        // ── run 3：新增 new-skill → 只追加增量行（内容只含 new-skill） ──
        catalogCommands.set(List.of(cmd("commit"), cmd("review"), cmd("new-skill")));
        AgentState s3 = runOnce(uid(), "third query");
        List<MessageRecord> all = listingRows();
        assertThat(all).as("⑤ 增量 → 追加一行（旧行累积保留，不重发整份）").hasSize(2);
        MessageRecord delta = all.get(all.size() - 1);
        assertThat(delta.getContent())
            .as("差量行内容只含新技能名（不重发整份 commit/review）")
            .contains("- new-skill")
            .doesNotContain("commit")
            .doesNotContain("review");
        // run3 内存态新注入的清单行 id = 差量行 id（历史注入的 run1 行 id 不同）
        assertThat(listingIds(s3.rawMessages())).as("run3 内存态新增注入差量清单").contains(delta.getId());

        // 差量行的 seq 仍紧随其当前用户消息（run 自己落库的 busy-queued user 行）
        List<MessageRecord> rows = dbRowsBySeq();
        MessageRecord user3 = lastUserRow(rows);
        assertThat(delta.getSeq())
            .as("差量清单行 seq 亦严格大于同轮用户行 seq")
            .isGreaterThan(user3.getSeq());

        // 重放：清单行按 seq 序 = [整份(run1), 差量(run3)]，且差量行紧随其同轮用户行
        List<ChatMessageDto> replay = messageService.listRawForTranscript(session);
        List<String> subOrder = replay.stream()
            .filter(m -> "skill_listing".equals(m.subtype()))
            .map(ChatMessageDto::id).toList();
        assertThat(subOrder).as("重放顺序 = [整份(run1), 差量(run3)]").hasSize(2)
            .containsExactly(all.get(0).getId(), all.get(1).getId());
        assertThat(indexOfId(replay, delta.getId()))
            .as("重放中差量清单行亦在其同轮用户行之后（[..., user3, ...attachmentMessages]；不强求紧邻）")
            .isGreaterThan(indexOfId(replay, user3.getId()));
    }

    // ════════════════════════════════════════════════════════════════════
    // 驱动真实 run
    // ════════════════════════════════════════════════════════════════════

    /**
     * 真实驱动一次 {@link LlmAgentLoop#run}：
     * 当前用户消息经生产队列以 busy-queued 注入（run 自身落库 → 取 seq，使写序在 DB 上可观测），
     * 落库经 {@link ChatService#armRealTimePersist}（生产 appendListener 宿主）→ 真实 MessageService。
     */
    private AgentState runOnce(String userMessageId, String prompt) {
        SkillCatalog catalog = mockCatalog();

        Tool skillTool = mock(Tool.class);
        when(skillTool.name()).thenReturn("Skill");
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.all()).thenReturn(List.of(skillTool));

        LlmProvider provider = stopProvider("reply for " + userMessageId);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // 当前用户消息：busy-queued（run 的 turn-0 drain 消费并落库，取 seq）
        SeedOnlyQueue queue = new SeedOnlyQueue();
        queue.seed(new NotificationQueue.QueueItem(
            prompt, NotificationQueue.MODE_PROMPT, null, null, userMessageId,
            false, "busy-queued", false, null, session, null, null));
        // 抑制 loop 自行入队的 turn-0 标准 prompt（本测试的当前用户消息走 busy-queued 种子）
        queue.dropFutureEnqueues();

        LlmAgentLoop loop = new LlmAgentLoop(factory, null, toolRegistry);
        loop.setMessageService(messageService);   // 真实 MessageService（真实 DB 读取 resume）
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        ReflectionTestUtils.setField(contextFactory, "skillCatalog", catalog);
        contextFactory.setNotificationQueue(queue);
        loop.setContextFactory(contextFactory);
        loop.setStreamContext(null, session, userMessageId);
        // 生产落库武装（ChatService.processUserMessage 同款）：doRun 历史注入后回调 → 逐条 append 实时落库
        loop.setPostHistoryPersistEnabler(state ->
            chatService.armRealTimePersist(state, session, null, null, userMessageId));

        return loop.run(RunRequest.session(prompt, session, null,
            ProviderConfig.empty(), "test-model", null, null));
    }

    /** 假 provider：首调即回 stop 纯文本 → loop 正常退出（对齐 LlmAgentLoopSkillListingInjectTest）。 */
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
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /** SkillCatalog stub：全量由 {@link #catalogCommands} 受控；formatListing 文本由入参子集派生（区分整份 vs 增量）。 */
    private SkillCatalog mockCatalog() {
        SkillCatalog catalog = mock(SkillCatalog.class);
        when(catalog.getModelInvocableCommandsForListing()).thenAnswer(inv -> catalogCommands.get());
        when(catalog.getModelInvocableCommands()).thenAnswer(inv -> catalogCommands.get());
        when(catalog.getCharBudget(any())).thenReturn(1000);
        when(catalog.formatListing(anyList(), any())).thenAnswer(inv -> {
            // 文本由「传入的 Command 子集」派生 → 整份 vs 增量在注入消息内容上可区分
            List<Command> passed = inv.getArgument(0);
            return passed.stream().map(c -> "- " + c.getName())
                .collect(java.util.stream.Collectors.joining("\n"));
        });
        return catalog;
    }

    /**
     * 生产 {@link NotificationQueue} 子类：只允许预置的种子入队，抑制 loop 自行入队的 turn-0 标准 prompt。
     * 队列真实（drain/persist 链不变），仅隔离出「唯一一条当前用户消息」。
     */
    private static final class SeedOnlyQueue extends NotificationQueue {
        private boolean dropEnqueues = false;

        void seed(QueueItem item) {
            super.enqueue(item);
        }

        void dropFutureEnqueues() {
            this.dropEnqueues = true;
        }

        @Override
        public synchronized void enqueue(QueueItem item) {
            if (dropEnqueues) {
                return;
            }
            super.enqueue(item);
        }
    }

    // ─────────────────────────── DB / 断言 helpers ───────────────────────────

    /** 该会话全部行按 seq ASC（listRawForTranscript 同序）—— 真实 DB 顺序。 */
    private List<MessageRecord> dbRowsBySeq() {
        return messageMapper.selectListByQuery(
            QueryWrapper.create().eq("session_id", session).orderBy("seq", true));
    }

    private List<MessageRecord> listingRows() {
        List<MessageRecord> out = new ArrayList<>();
        for (MessageRecord r : dbRowsBySeq()) {
            if ("skill_listing".equals(r.getSubtype())) {
                out.add(r);
            }
        }
        return out;
    }

    private static MessageRecord lastUserRow(List<MessageRecord> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            MessageRecord r = rows.get(i);
            if (Role.user.name().equals(r.getRole()) && !"skill_listing".equals(r.getSubtype())) {
                return r;
            }
        }
        throw new AssertionError("DB 无用户消息行");
    }

    private static int indexOfId(List<ChatMessageDto> msgs, String id) {
        for (int i = 0; i < msgs.size(); i++) {
            if (id.equals(msgs.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    /** 内存态中全部 subtype=skill_listing 消息的 id（区分「新注入」与「历史注入带出」）。 */
    private static List<String> listingIds(List<ChatMessageDto> msgs) {
        List<String> ids = new ArrayList<>();
        for (ChatMessageDto m : msgs) {
            if (m != null && "skill_listing".equals(m.subtype())) {
                ids.add(m.id());
            }
        }
        return ids;
    }

    private static String listingContent(List<ChatMessageDto> msgs) {
        for (ChatMessageDto m : msgs) {
            if (m != null && "skill_listing".equals(m.subtype())) {
                return m.content();
            }
        }
        return null;
    }

    /** 唯一 userMessageId（= busy-queued 行主键）· 防跨用例主键冲突（messages.id 为全局主键）。 */
    private static String uid() {
        return "msg-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static Command cmd(String name) {
        Command c = new Command();
        c.setName(name);
        c.setType("prompt");
        return c;
    }
}
