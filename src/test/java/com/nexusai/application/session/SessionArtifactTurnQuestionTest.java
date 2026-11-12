package com.nexusai.application.session;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.model.session.dto.SessionArtifactDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 产物自带「那一轮问了什么」· 右侧栏按对话分组的组头数据源。
 *
 * <p><b>WHY 需要这一批断言</b>：分组标题此前由前端从聊天窗口的轮次里算，而聊天窗口只保留
 * 最近 2 轮（{@code FOLD_DEFAULT_TURNS=2}）⇒ 更早轮次的提问查不到，那些产物只能被堆进
 * 「历史产物」组（真库实测 sess-7b664661：5 个交付物分属 5 个提问，前端只看到 1 组 + 3 条历史）。
 * 现改由后端按 {@code source_message_id} 联查消息表直出。
 *
 * <p>⚠️ 这里<b>不加任何 Spring 上下文注解</b>（{@code @SpringBootTest} 会迁移用户真库），
 * 全部用 mock + {@code new SessionArtifactService()} 直构。
 */
@DisplayName("[产物组头] 产物自带那一轮的提问原文")
class SessionArtifactTurnQuestionTest {

    private static final String SESSION = "sess-turn-question";

    @TempDir
    Path tempDir;

    private SessionArtifactMapper artifactMapper;
    private MessageMapper messageMapper;
    private SessionArtifactService service;

    @BeforeEach
    void setUp() {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());
        artifactMapper = mock(SessionArtifactMapper.class);
        messageMapper = mock(MessageMapper.class);
        service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", artifactMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    // ── 正例 ───────────────────────────────────────────────

    @Test
    @DisplayName("真人提问 ⇒ 组头拿到压成单行的提问原文 + 原始 created_at（错了则组头是一坨换行/时间为空）")
    void realUserQuestionIsFlattenedAndExposed() {
        givenArtifacts(artifact("art-1", "msg-u1"));
        givenMessages(message("msg-u1", "user", "  第一行\n\n第二行   ", "2026-11-12T10:00:00"));

        SessionArtifactDto dto = onlyDeliverable(service.list(SESSION));

        assertThat(dto.turnQuestion())
                .as("⛔ 不压空白 ⇒ 组头被换行撑成多行，侧栏变形")
                .isEqualTo("第一行 第二行");
        assertThat(dto.turnCreatedAt())
                .as("⛔ 不在 DTO 上带时间 ⇒ 前端只能拿产物 updated_at 冒充提问时间")
                .isEqualTo("2026-11-12T10:00:00");
        assertThat(dto.sourceMessageId()).isEqualTo("msg-u1");
    }

    @Test
    @DisplayName("is_meta=true 的注入行 ⇒ 两字段 null，但 sourceMessageId 仍保留（判成真人提问会把提示语当组头）")
    void metaMessageYieldsNullQuestion() {
        givenArtifacts(artifact("art-2", "msg-meta"));
        MessageRecord meta = message("msg-meta", "user", "系统注入的上下文", "2026-11-12T10:01:00");
        meta.setIsMeta(true);
        givenMessages(meta);

        SessionArtifactDto dto = onlyDeliverable(service.list(SESSION));

        assertThat(dto.turnQuestion())
                .as("⛔ 把 is_meta 行当提问 ⇒ 组头显示的是系统提示语，用户看不懂")
                .isNull();
        assertThat(dto.turnCreatedAt()).isNull();
        assertThat(dto.sourceMessageId())
                .as("归属 id 本身没变 —— 只是「不是真人提问」⇒ 前端据此归入历史组")
                .isEqualTo("msg-meta");
    }

    @Test
    @DisplayName("subtype=tool_use_summary ⇒ 两字段 null（系统摘要行不是提问）")
    void subtypedMessageYieldsNullQuestion() {
        givenArtifacts(artifact("art-3", "msg-sub"));
        MessageRecord sub = message("msg-sub", "user", "本轮工具使用摘要", "2026-11-12T10:02:00");
        sub.setSubtype("tool_use_summary");
        givenMessages(sub);

        SessionArtifactDto dto = onlyDeliverable(service.list(SESSION));

        assertThat(dto.turnQuestion())
                .as("⛔ subtype 行是系统生成的摘要 ⇒ 当组头会与真实提问错位")
                .isNull();
        assertThat(dto.turnCreatedAt()).isNull();
    }

    @Test
    @DisplayName("user_message_id 指向别的消息 ⇒ 两字段 null（属于别的 flow 的注入行，不是本轮提问）")
    void foreignAnchorYieldsNullQuestion() {
        givenArtifacts(artifact("art-4", "msg-injected"));
        MessageRecord injected = message("msg-injected", "user", "别轮 flow 里的注入内容", "2026-11-12T10:03:00");
        injected.setUserMessageId("msg-other-flow");
        givenMessages(injected);

        SessionArtifactDto dto = onlyDeliverable(service.list(SESSION));

        assertThat(dto.turnQuestion())
                .as("⛔ 锚点不自洽 ⇒ 组头会张冠李戴（显示别轮的提问）")
                .isNull();
        assertThat(dto.turnCreatedAt()).isNull();
    }

    @Test
    @DisplayName("消息表查不到该 id（孤儿键）⇒ 两字段 null，产物条目不丢")
    void orphanKeyYieldsNullQuestion() {
        givenArtifacts(artifact("art-5", "msg-gone"));
        givenMessages();   // 消息表空

        SessionArtifactDto dto = onlyDeliverable(service.list(SESSION));

        assertThat(dto.turnQuestion()).isNull();
        assertThat(dto.turnCreatedAt()).isNull();
        assertThat(dto.id())
                .as("⛔ 查不到提问就把产物丢掉 ⇒ 右侧栏凭空少一个交付物")
                .isEqualTo("art-5");
    }

    @Test
    @DisplayName("提问超过 500 字 ⇒ 截断到 500 且以 … 结尾（不截断则整段原文塞进组头/响应体）")
    void longQuestionIsTruncated() {
        givenArtifacts(artifact("art-6", "msg-long"));
        givenMessages(message("msg-long", "user", "啊".repeat(600), "2026-11-12T10:04:00"));

        SessionArtifactDto dto = onlyDeliverable(service.list(SESSION));

        assertThat(dto.turnQuestion())
                .as("⛔ 不截断 ⇒ 一整篇粘贴的正文进组头，响应体与渲染都被拖死")
                .hasSize(501)
                .endsWith("…");
    }

    @Test
    @DisplayName("messageMapper 未注入（直构单测）⇒ list 不抛异常、两字段 null、产物条数不变")
    void missingMessageMapperDegradesGracefully() {
        MessageMapper absent = null;
        ReflectionTestUtils.setField(service, "messageMapper", absent);
        givenArtifacts(artifact("art-7", "msg-u7"), artifact("art-8", "msg-u8"));

        SessionArtifactService.ArtifactBuckets buckets = service.list(SESSION);

        assertThat(buckets.deliverables())
                .as("⛔ NPE 会把整个产物列表打成 500 ⇒ 右侧栏全空")
                .hasSize(2)
                .allSatisfy(dto -> {
                    assertThat(dto.turnQuestion()).isNull();
                    assertThat(dto.turnCreatedAt()).isNull();
                });
    }

    @Test
    @DisplayName("listByMessage 同样带组头字段（按轮取也要能显示该轮提问）")
    void listByMessageAlsoCarriesQuestion() {
        givenArtifacts(artifact("art-9", "msg-u9"));
        givenMessages(message("msg-u9", "user", "本轮的问题", "2026-11-12T10:05:00"));

        SessionArtifactDto dto = onlyDeliverable(service.listByMessage(SESSION, "msg-u9"));

        assertThat(dto.turnQuestion()).isEqualTo("本轮的问题");
        assertThat(dto.turnCreatedAt()).isEqualTo("2026-11-12T10:05:00");
    }

    // ── 辅助 ───────────────────────────────────────────────

    private void givenArtifacts(SessionArtifactRecord... rows) {
        when(artifactMapper.selectListByQuery(any())).thenReturn(List.of(rows));
    }

    private void givenMessages(MessageRecord... rows) {
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(rows));
    }

    private static SessionArtifactDto onlyDeliverable(SessionArtifactService.ArtifactBuckets buckets) {
        assertThat(buckets.deliverables()).hasSize(1);
        return buckets.deliverables().get(0);
    }

    private static SessionArtifactRecord artifact(String id, String owner) {
        SessionArtifactRecord r = new SessionArtifactRecord();
        r.setId(id);
        r.setSessionId(SESSION);
        r.setKind("deliverable");
        r.setTitle(id + ".docx");
        r.setSourceMessageId(owner);
        r.setUpdatedAt("2026-11-12T00:00:00");
        return r;
    }

    private static MessageRecord message(String id, String role, String content, String createdAt) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId(SESSION);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(createdAt);
        return m;
    }
}
