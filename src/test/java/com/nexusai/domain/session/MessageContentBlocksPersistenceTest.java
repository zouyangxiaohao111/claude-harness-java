package com.nexusai.domain.session;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.ContentBlockDto;
import com.nexusai.model.session.dto.MessageContentBlocks;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [content-blocks V331] assistant 内容块顺序的落库/读回（真临时 SQLite + 全量 Flyway）。
 *
 * <p><b>WHY 必须真库</b>：判据是「列里 JSON 的块顺序与 apiBlockIndex」「GET 读回投影一致」
 * 「老行仍为 null」——内存假实现证明不了列映射（MyBatis-Flex camelCase→snake_case）与读回链。
 *
 * <p>覆盖派单 a/b/c/e：
 * <ol>
 *   <li>a. 一轮含「工具1、工具2、文本、工具3」按真实顺序落块，apiBlockIndex 连续；</li>
 *   <li>b. 读回投影（listRawForTranscript / getById）与写入一致；</li>
 *   <li>c. 旧行（无 content_blocks）读回 blocks=null（前端走旧渲染）；</li>
 *   <li>e. 压缩写回（replaceSessionMessages 重插）不破坏块顺序。</li>
 * </ol>
 */
@DisplayName("[content-blocks V331] 内容块顺序落库与读回（真 sqlite）")
class MessageContentBlocksPersistenceTest {

    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    private String session;
    private MessageService messageService;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest",
            "content-blocks-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("cb.db").toAbsolutePath());
        ds.setBusyTimeout(10_000);
        // 全量迁移：直接读源目录（真文件），保证含最新 V331。
        Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:" + Path.of("src", "main", "resources", "db", "migration")
                .toAbsolutePath().toString().replace('\\', '/'))
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
        session = "sess-cb-" + UUID.randomUUID().toString().substring(0, 8).replace('-', '0');
        SessionRecord s = new SessionRecord();
        s.setId(session);
        s.setModelTag("DS");
        s.setModelName("test-model");
        s.setTitle("content blocks target");
        s.setTime("刚刚");
        s.setSessionGroup("default");
        sessionMapper.insertSelective(s);
        SessionProjectRoot.setForSession(session,
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper", toolCallMapper);
    }

    @AfterEach
    void tearDown() {
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", session));
        sessionMapper.deleteById(session);
        SessionProjectRoot.reset();
    }

    private static ChatMessageDto assistantWithBlocks(String id, List<ContentBlockDto> blocks) {
        List<ToolCallDto> toolCalls = new ArrayList<>();
        for (ContentBlockDto b : blocks) {
            if ("tool_use".equals(b.type())) {
                toolCalls.add(new ToolCallDto(b.id(), b.name(), b.input(), null, Boolean.FALSE));
            }
        }
        ChatMessageDto dto = new ChatMessageDto(
            id, null, Role.assistant, "assistant", "text after tools", "thinking body",
            toolCalls, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
        return dto.withBlocks(blocks);
    }

    /** \u201c\u5de5\u51771\uff0c\u5de5\u51772\uff0c\u6587\u672c\uff0c\u5de5\u51773\u201d\u7684\u5230\u8fbe\u987a\u5e8f\uff08\u975e\u89c4\u8303\u987a\u5e8f\uff09\u3002 */
    private static List<ContentBlockDto> arrivalOrderBlocks() {
        return List.of(
            ContentBlockDto.toolUse("call_1", "Bash", "{\"cmd\":\"pwd\"}", 5),
            ContentBlockDto.toolUse("call_2", "Read", "{\"file\":\"a.txt\"}", 5),
            ContentBlockDto.text("text after tools", 5),
            ContentBlockDto.toolUse("call_3", "Edit", "{\"file\":\"b.txt\"}", 5));
    }

    @Test
    @DisplayName("a. 一轮「工具1、工具2、文本、工具3」按到达顺序落块且 apiBlockIndex 连续")
    void writesBlocksInArrivalOrder() {
        String id = "msg-cb-a-1";
        messageService.appendMessage(assistantWithBlocks(id, arrivalOrderBlocks()).withSessionId(session));

        String json = messageMapper.selectOneById(id).getContentBlocks();
        assertNotNull(json, "assistant \u884c\u5fc5\u987b\u6709 content_blocks\uff08\u975e null\uff09");
        List<ContentBlockDto> stored = MessageContentBlocks.fromJson(json);
        assertNotNull(stored);
        assertEquals(4, stored.size());
        assertEquals(List.of("tool_use", "tool_use", "text", "tool_use"),
            stored.stream().map(ContentBlockDto::type).toList(), "\u5757\u987a\u5e8f = \u5230\u8fbe\u987a\u5e8f");
        for (int i = 0; i < stored.size(); i++) {
            assertEquals(i, stored.get(i).apiBlockIndex(), "apiBlockIndex \u5fc5\u987b\u8fde\u7eed\u9012\u589e");
        }
        assertEquals("call_1", stored.get(0).id());
        assertEquals("call_2", stored.get(1).id());
        assertEquals("text after tools", stored.get(2).text());
        assertEquals("call_3", stored.get(3).id());
        assertTrue(json.contains("apiBlockIndex"), "JSON \u5fc5\u987b\u5e26 apiBlockIndex");
    }

    @Test
    @DisplayName("b. 读回投影与写入一致（listRawForTranscript / getById）")
    void readsBackSameProjection() {
        String id = "msg-cb-b-1";
        messageService.appendMessage(assistantWithBlocks(id, arrivalOrderBlocks()).withSessionId(session));

        ChatMessageDto fromList = messageService.listRawForTranscript(session).stream()
            .filter(m -> id.equals(m.id())).findFirst().orElse(null);
        assertNotNull(fromList);
        assertNotNull(fromList.blocks(), "\u8bfb\u56de\u5fc5\u987b\u5e26 blocks");
        assertEquals(4, fromList.blocks().size());
        assertEquals("tool_use", fromList.blocks().get(0).type());
        assertEquals("text", fromList.blocks().get(2).type());
        assertEquals(2, fromList.blocks().get(2).apiBlockIndex());

        ChatMessageDto single = messageService.getById(id);
        assertNotNull(single.blocks());
        assertEquals(fromList.blocks(), single.blocks(), "\u4e24\u6761\u8bfb\u901a\u9053\u5fc5\u987b\u540c\u6e90");
    }

    @Test
    @DisplayName("c. 旧行（无 content_blocks）读回 blocks=null")
    void legacyRowHasNullBlocks() {
        String id = "msg-cb-c-1";
        // 直接插一行 V331 之前形态的历史行（content_blocks 列为 NULL）。
        MessageRecord rec = new MessageRecord();
        rec.setId(id);
        rec.setSessionId(session);
        rec.setRole("assistant");
        rec.setAuthor("assistant");
        rec.setContent("legacy text");
        rec.setCreatedAt(OffsetDateTime.now().toString());
        rec.setSeq(1L);
        messageMapper.insert(rec);
        assertNull(messageMapper.selectOneById(id).getContentBlocks(), "历史行该列必须为 NULL");

        ChatMessageDto read = messageService.getById(id);
        assertNotNull(read);
        assertNull(read.blocks(), "旧行读回 blocks=null（前端走旧渲染）");
    }

    @Test
    @DisplayName("e. 压缩写回（replaceSessionMessages 重插）不破坏块顺序")
    void compactionRewriteKeepsOrder() {
        String userId = "msg-cb-e-u1";
        String assistantId = "msg-cb-e-a1";
        ChatMessageDto user = new ChatMessageDto(
            userId, session, Role.user, "user", "hi", null,
            null, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
        ChatMessageDto assistant = assistantWithBlocks(assistantId, arrivalOrderBlocks())
            .withSessionId(session);

        messageService.replaceSessionMessages(session, List.of(user, assistant));

        List<ContentBlockDto> blocks = messageService.getById(assistantId).blocks();
        assertNotNull(blocks, "\u91cd\u63d2\u540e\u5757\u987a\u5e8f\u4e0d\u5f97\u4e22\u5931");
        assertEquals(4, blocks.size());
        assertEquals(List.of("tool_use", "tool_use", "text", "tool_use"),
            blocks.stream().map(ContentBlockDto::type).toList());
        for (int i = 0; i < blocks.size(); i++) {
            assertEquals(i, blocks.get(i).apiBlockIndex());
        }
    }
}
