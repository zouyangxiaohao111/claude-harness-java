package com.nexusai.application.agent.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TeammateMailbox 定向测试 — 对齐 CC teammateMailbox.ts 文件型 mailbox 核心价值
 * （S6 统一后本类为唯一文件操作层，自 infra/util/FileBackedTeammateMailboxTest 迁移，
 * 断言改为 CC 信封字段 from/text/timestamp/read，color/summary 缺省省略，测试意图不变）。
 *
 * <p>每项测试的 WHY (规则九) 见各方法 JavaDoc. RED-GREEN 双证: 回退为内存 stub 时
 * {@link #writeToMailbox_persistsToFileAcrossInstances_readBackAfterReload()} 必须红,
 * 证明该测试真正校验"文件持久化"而非"内存行为".
 */
class TeammateMailboxTest {

    private static final String TEAM = "research-team";
    private static final String AGENT = "leadAgent";

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // configHome 指向临时目录: {configHome}/teams/{team}/inboxes/{agent}.json
        // (仿 TaskUpdateToolMailboxNotifyTest 先例; TeammateMailbox 经 TaskSystemConfig
        //  解析 nexusai.task.config-dir, 对齐 CC envUtils.ts:7-14 getClaudeConfigHomeDir)
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
    }

    private Path inboxPath(String agent) {
        return tempDir.resolve("teams").resolve(TEAM).resolve("inboxes").resolve(agent + ".json");
    }

    private void write(String from, String text) {
        TeammateMailbox.writeToMailbox(AGENT,
                TeammateMailbox.TeammateMessage.of(from, text, TeammateMailbox.isoNow(), null), TEAM);
    }

    @Test
    void writeToMailbox_persistsToFileAcrossInstances_readBackAfterReload() {
        // WHY: CC 文件型 mailbox 的核心价值是"重启不丢消息" — in-process teammate 重启后能
        //      readMailbox 读到历史消息 (inProcessRunner.ts:763)。若实现仍是内存队列,
        //      新实例读不到旧消息, teammate 状态丢失。
        write("alice", "hello");
        // 断言消息真实落盘 (静态工具类无实例可重建; 文件即状态, 读回即证明跨实例持久化)
        assertTrue(Files.exists(inboxPath(AGENT)), "writeToMailbox 必须落盘 inbox 文件");
        List<TeammateMailbox.TeammateMessage> msgs = TeammateMailbox.readMailbox(AGENT, TEAM);
        assertEquals(1, msgs.size(), "重启后应读到历史消息");
        assertEquals("hello", msgs.get(0).text());
        assertEquals("alice", msgs.get(0).from());
        assertFalse(msgs.get(0).read(), "writeToMailbox 默认 read=false (CC teammateMailbox.ts:173-176)");
        assertNotNull(msgs.get(0).timestamp(), "信封必须携带 ISO-8601 时间戳 (CC :46)");
    }

    @Test
    void writeToMailbox_acquiresFileLock_concurrentWritersSerialized() throws Exception {
        // WHY: CC LOCK_OPTIONS (teammateMailbox.ts:35-41) 重试退避保护并发写,
        //      多实例并发写同一 inbox.json 若无线程互斥会丢消息 (read-modify-write race)。
        //      本用例同时覆盖 TaskUpdate (S12) 与 TeamMessageBus 两写入方并存场景 —
        //      两者经 TaskLock 共用同一锁文件 {inbox}.json.lock, 跨模块互斥成立。
        int writers = 4;
        int perWriter = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CyclicBarrier barrier = new CyclicBarrier(writers);
        CountDownLatch done = new CountDownLatch(writers);
        for (int w = 0; w < writers; w++) {
            final String from = "teammate" + w;
            pool.submit(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < perWriter; i++) {
                        write(from, "m" + i);
                    }
                    done.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写超时");
        pool.shutdownNow();

        List<TeammateMailbox.TeammateMessage> all = TeammateMailbox.readMailbox(AGENT, TEAM);
        assertEquals(writers * perWriter, all.size(), "并发写应无消息丢失 (文件锁互斥)");
    }

    @Test
    void readMailbox_returnsEmptyList_whenInboxFileNotExists() {
        // WHY: CC readMailbox ENOENT 返 [] (teammateMailbox.ts:100-102) 是容错语义,
        //      teammate 首次启动 (无 inbox 文件) 不应崩。
        List<TeammateMailbox.TeammateMessage> msgs = TeammateMailbox.readMailbox("ghostAgent", TEAM);
        assertNotNull(msgs, "读不到文件应返回空 List 而非 null");
        assertTrue(msgs.isEmpty(), "读不到文件应返回空列表");
    }

    @Test
    void clearMailbox_doesNotCreateInboxFile_whenNotExists() {
        // WHY: CC clearMailbox r+ 模式 (teammateMailbox.ts:358) 文件不存在抛 ENOENT 后 return,
        //      不误创建。若误创建空 inbox, 会污染文件系统留垃圾文件。
        TeammateMailbox.clearMailbox("ghostAgent", TEAM);
        assertFalse(Files.exists(inboxPath("ghostAgent")), "clearMailbox 不应创建原本不存在的 inbox 文件");
    }

    @Test
    void clearMailbox_clearsExistingMessages() {
        // WHY: clearMailbox 清空已有 inbox 后, readMailbox 应读回空列表 (CC :349 语义)。
        write("alice", "m1");
        TeammateMailbox.clearMailbox(AGENT, TEAM);
        assertTrue(TeammateMailbox.readMailbox(AGENT, TEAM).isEmpty(), "清空后应无消息");
    }

    @Test
    void markMessageAsReadByIndex_setsReadFlag_persistsToDisk() {
        // WHY: CC TeammateMessage.read (teammateMailbox.ts:47) 是消息级已读标记,
        //      markMessageAsReadByIndex (:201) 锁内 re-read -> 标 read:true -> 写回。
        //      read 字段驱动 readUnreadMessages 过滤, 若不持久化重启后已读变未读。
        write("alice", "m1");
        TeammateMailbox.markMessageAsReadByIndex(AGENT, TEAM, 0);
        List<TeammateMailbox.TeammateMessage> msgs = TeammateMailbox.readMailbox(AGENT, TEAM);
        assertTrue(msgs.get(0).read(), "标记后 read 应持久化为 true");
    }

    @Test
    void markMessageAsReadByIndex_outOfBounds_returnsSilently() {
        // WHY: CC markMessageAsReadByIndex 越界 return (teammateMailbox.ts:230-235) 不抛异常,
        //      teammate 轮询时 index 可能因并发已变, 越界是正常情况不应崩。
        assertDoesNotThrow(() -> TeammateMailbox.markMessageAsReadByIndex(AGENT, TEAM, 0), "空 inbox 越界应静默");
        write("alice", "m1");
        assertDoesNotThrow(() -> TeammateMailbox.markMessageAsReadByIndex(AGENT, TEAM, 5), "超出长度越界应静默");
    }

    @Test
    void markMessageAsReadByIndex_missingInbox_returnsSilently() {
        // WHY: CC markMessageAsReadByIndex ENOENT return (teammateMailbox.ts:253) — 文件不存在不抛异常。
        assertDoesNotThrow(() -> TeammateMailbox.markMessageAsReadByIndex("ghostAgent", TEAM, 0));
    }

    @Test
    void markMessageAsReadByIndex_alreadyRead_isIdempotent() {
        // WHY: CC markMessageAsReadByIndex 已读消息再标 return (teammateMailbox.ts:238),
        //      不重复写、不报错、不产生副作用。
        write("alice", "m1");
        TeammateMailbox.markMessageAsReadByIndex(AGENT, TEAM, 0);
        assertDoesNotThrow(() -> TeammateMailbox.markMessageAsReadByIndex(AGENT, TEAM, 0), "已读重复标不应抛异常");
        List<TeammateMailbox.TeammateMessage> msgs = TeammateMailbox.readMailbox(AGENT, TEAM);
        assertEquals(1, msgs.size(), "已读重复标不应产生额外消息");
        assertTrue(msgs.get(0).read());
    }

    @Test
    void readUnreadMessages_filtersOutRead() {
        // WHY: readUnreadMessages (CC teammateMailbox.ts:115-125) 只返回 read=false 的消息,
        //      驱动 teammate 轮询只看未读 (inProcessRunner.ts:763 后处理未读)。
        write("alice", "m1");
        write("bob", "m2");
        TeammateMailbox.markMessageAsReadByIndex(AGENT, TEAM, 0);
        List<TeammateMailbox.TeammateMessage> unread = TeammateMailbox.readUnreadMessages(AGENT, TEAM);
        assertEquals(1, unread.size(), "应只返回未读消息");
        assertEquals("m2", unread.get(0).text());
    }

    @Test
    void markMessagesAsRead_marksAllRead() {
        // WHY: markMessagesAsRead (CC teammateMailbox.ts:279) 锁内 re-read -> 全部标 read=true 写回。
        write("alice", "m1");
        write("bob", "m2");
        TeammateMailbox.markMessagesAsRead(AGENT, TEAM);
        List<TeammateMailbox.TeammateMessage> msgs = TeammateMailbox.readMailbox(AGENT, TEAM);
        assertTrue(msgs.stream().allMatch(TeammateMailbox.TeammateMessage::read), "全部消息应被标为已读");
    }

    @Test
    void markMessagesAsReadByPredicate_marksMatchingOnly() {
        // WHY: markMessagesAsReadByPredicate (CC teammateMailbox.ts:1101) 只标谓词命中且未读的消息,
        //      其余保持未读 — 用于按消息类型/来源选择性消费。
        write("alice", "hello");
        write("bob", "world");
        TeammateMailbox.markMessagesAsReadByPredicate(AGENT, m -> "bob".equals(m.from()), TEAM);
        List<TeammateMailbox.TeammateMessage> msgs = TeammateMailbox.readMailbox(AGENT, TEAM);
        assertFalse(msgs.get(0).read(), "谓词未命中的消息应保持未读");
        assertTrue(msgs.get(1).read(), "谓词命中的消息应被标为已读");
    }

    @Test
    void writeToMailbox_secondWrite_doesNotRecreateInbox() {
        // WHY: CC writeFile('[]', {flag:'wx'}) (teammateMailbox.ts:150) 首写创建, 后续 EEXIST 忽略 —
        //      第二次写不能重建 inbox 文件覆盖已有消息。
        write("alice", "m1");
        write("bob", "m2");
        List<TeammateMailbox.TeammateMessage> msgs = TeammateMailbox.readMailbox(AGENT, TEAM);
        assertEquals(2, msgs.size(), "第二次写不应重建 inbox 文件覆盖已有消息");
    }

    @Test
    void writeToMailbox_writesCcEnvelopeShape() throws Exception {
        // WHY: 磁盘契约 = CC TeammateMessage 信封 (teammateMailbox.ts:43-50):
        //      from/text/timestamp/read 必填 + color?/summary? 缺省省略 (JSON.stringify 省略 undefined),
        //      供 CC 生态消费侧 (attachments.ts:3532 getTeammateMailboxAttachments) 跨进程读取 —
        //      不得写 Java TeamMessage 全字段 (旧 FileBackedTeammateMailbox 的 S6-5 偏差, 已统一)。
        write("alice", "hello");
        JsonNode messages = json.readTree(Files.readString(inboxPath(AGENT)));
        assertEquals(1, messages.size());
        JsonNode msg = messages.get(0);
        assertEquals("alice", msg.get("from").asText());
        assertEquals("hello", msg.get("text").asText());
        assertTrue(msg.get("timestamp").asText().matches("\\d{4}-\\d{2}-\\d{2}T.*Z"), "timestamp 必须 ISO-8601");
        assertFalse(msg.get("read").asBoolean());
        assertFalse(msg.has("color"), "color 缺省应省略键 (JSON.stringify 省略 undefined)");
        assertFalse(msg.has("summary"), "summary 缺省应省略键");
        assertFalse(msg.has("id"), "信封不得含 Java TeamMessage 专属字段 (id/fromAgentId/type/...)");
    }

    @Test
    void getInboxPath_sanitizesPathTraversal() {
        // WHY: CC tasks.ts:217 sanitizePathComponent (input.replace(/[^a-zA-Z0-9_-]/g,'-'))
        //      防路径穿越: '../' 等恶意 agentName 不得逃逸 inbox 目录写入任意路径。
        Path p = TeammateMailbox.getInboxPath("../etc/passwd", TEAM);
        Path expectedDir = tempDir.resolve("teams").resolve(TEAM).resolve("inboxes");
        assertEquals(expectedDir, p.getParent(), "inbox 文件必须落在 {configHome}/teams/{team}/inboxes 内");
        assertFalse(p.getFileName().toString().contains(".."), "文件名不得含 .. 逃逸");
        assertFalse(p.getFileName().toString().contains("/"));
        assertFalse(p.getFileName().toString().contains("\\"));
    }

    // ═══════════ Batch2 B1 · formatTeammateMessages（对齐 CC teammateMailbox.ts:638-654）═══════════

    @Test
    void formatTeammateMessages_single_rendersTagWithColorAndSummary() {
        // WHY: CC teammateMailbox.ts:638-654 formatTeammateMessages —— leader inbox 消息经此渲染为
        //      <teammate-message> XML 注入 LLM（B1 断链：leader 看到队友回复）。color/summary 非空
        //      才写属性；tag = 'teammate-message'（constants/xml.ts:52 TEAMMATE_MESSAGE_TAG）。
        String out = TeammateMailbox.formatTeammateMessages(List.of(
                TeammateMailbox.TeammateMessage.of("researcher", "找到方案", "2026-08-23T00:00:00.000Z", "cyan")));
        assertEquals("<teammate-message teammate_id=\"researcher\" color=\"cyan\">\n"
                + "找到方案\n</teammate-message>", out);
    }

    @Test
    void formatTeammateMessages_multiple_joinWithDoubleNewlineAndOmitEmptyAttrs() {
        // WHY: 多条消息 \n\n join；color/summary 缺省（null）→ 属性省略（CC m.color ? ... : ''）。
        String out = TeammateMailbox.formatTeammateMessages(List.of(
                TeammateMailbox.TeammateMessage.of("a", "one", "t1", null),
                TeammateMailbox.TeammateMessage.of("b", "two", "t2", "red")));
        assertEquals("<teammate-message teammate_id=\"a\">\n"
                + "one\n</teammate-message>\n\n"
                + "<teammate-message teammate_id=\"b\" color=\"red\">\n"
                + "two\n</teammate-message>", out);
    }

    @Test
    void formatTeammateMessages_emptyOrNull_returnsEmpty() {
        // WHY: 空列表/含 null → 空串（CC map 空数组 → ''），注入点据此跳过不注入。
        assertEquals("", TeammateMailbox.formatTeammateMessages(List.of()));
        assertEquals("", TeammateMailbox.formatTeammateMessages(null));
        // List.of() 不允许 null 元素 → 用 Arrays.asList 构造含 null 的列表（渲染跳过 null）
        assertEquals("", TeammateMailbox.formatTeammateMessages(
                java.util.Arrays.asList((TeammateMailbox.TeammateMessage) null)));
    }
}
