package com.nexusai.application.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.SessionToolCategory;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.session.SessionTakeoverResolver;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 派生/平级会话的 AskUserQuestion 可见性 · 断言的是<b>模型请求里的 tools 数组</b>
 * （{@link LlmAgentLoop#llmToolsArray(ToolUseContext, QuerySource)} 产物），
 * ⛔ 不是只断言内部禁用集合。
 *
 * <p><b>用户裁定（2026-11-22）</b>：
 * <ol>
 *   <li>派生/平级会话<b>默认禁用</b> AskUserQuestion（保持现状，<b>不做 yml 配置项</b>）；</li>
 *   <li>一旦<b>真实用户</b>在该会话里发过消息（接管），立即开放 Ask（下一轮生效）；</li>
 *   <li>真实用户判定严格：peer 投递 / cron 注入 / 系统提醒 / 内部注入 / 子代理回报都不算；</li>
 *   <li>无人值守是显式开关，优先级更高（发了消息也仍禁 Ask）。</li>
 * </ol>
 *
 * <p>判定数据源 = 已有 {@code messages} 表（不新增 session 字段 / 迁移），故重启后结果不变。
 * ⛔ 纯 Mockito，不启动 Spring（不碰用户真库）。
 */
class DerivedSessionAskVisibilityTest {

    private static final String ASK = "AskUserQuestion";
    private static final String BASH = "Bash";
    private static final String DERIVED = "sess-derived";
    private static final String MAIN = "sess-main";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectNode EMPTY_OBJECT_SCHEMA = MAPPER.createObjectNode().put("type", "object");

    private SessionMapper sessionMapper;
    private MessageMapper messageMapper;

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "t " + name;
            }

            @Override
            public JsonNode inputSchema() {
                return EMPTY_OBJECT_SCHEMA;
            }

            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static SessionRecord session(String id, Integer derivedDepth, Integer unattended) {
        SessionRecord r = new SessionRecord();
        r.setId(id);
        r.setDerivedDepth(derivedDepth);
        r.setUnattendedMode(unattended);
        return r;
    }

    /** 真实用户：人在界面发的普通消息（author/subtype/queued_origin 均为空）。 */
    private static MessageRecord realUserMessage(String sessionId) {
        return message(sessionId, "msg-user-1", false, null, null, null, null, "把这个活干了");
    }

    /** 真实用户排队（busy 期间打字）：queued_origin='busy-queued'。 */
    private static MessageRecord busyQueuedUserMessage(String sessionId) {
        return message(sessionId, "msg-busy-1", false, null, null, "busy-queued", null, "顺便看下日志");
    }

    /** peer 投递来信：is_meta=true + author=来源会话 + subtype=agent_message + queued_origin=peer。 */
    private static MessageRecord peerMessage(String sessionId) {
        return message(sessionId, "msg-peer-1", true, "sess-parent", "agent_message", "peer", null,
            "帮我查一下失败原因");
    }

    /** cron / 定时注入：is_meta=true。 */
    private static MessageRecord cronMessage(String sessionId) {
        return message(sessionId, "msg-cron-1", true, null, null, null, null, "Running scheduled task");
    }

    /** 系统提醒 / 内部注入 / 子代理回报：is_meta=true。 */
    private static MessageRecord systemInjectedMessage(String sessionId) {
        return message(sessionId, "msg-sys-1", true, null, null, null, null,
            "<task-notification>done</task-notification>");
    }

    /**
     * IM 渠道入站：<b>按真实写入点</b> {@code ImInboundRouter:252-257}（入队 workload=WORKLOAD_CRON、
     * isMeta=true）→ {@code CronIdleExecutor:1197-1218} 以 isCron=true 落库。
     *
     * <p>字段形态 = {@code role=user / is_meta=true / author=null / subtype=null /
     * queued_origin=null}；正文是 {@code buildCommand} 包出来的 system-reminder。
     * ⚠️ 与真人桌面行的唯一区别就是 is_meta ⇒ 这条用例钉住 is_meta 不能被去掉。
     */
    private static MessageRecord imInboundMessage(String sessionId) {
        return message(sessionId, "msg-im-1", true, null, null, null, null,
            "<system-reminder>这是一条来自 飞书 的入站消息。\n会话：张三（私聊）\n</system-reminder>\n\n帮我看下这个");
    }

    /**
     * 同会话自投递（子代理 / teammate → 主会话，sameSessionDelivery）：
     * {@code CronIdleExecutor.handlePeerQueuedItem:1466-1472} 写
     * is_meta=true + author=来源 + subtype='agent_message' + queued_origin='peer'。
     */
    private static MessageRecord sameSessionDeliveryMessage(String sessionId) {
        return message(sessionId, "msg-self-1", true, sessionId, "agent_message", "peer", null,
            "<task-notification>子代理完成</task-notification>");
    }

    /**
     * teammate 唤醒注入：{@code AutonomousAgentLoop.injectUserMessage:396-403} 只进进程内队列 +
     * 内存 transcript，<b>不写 messages 表</b> ⇒ 判定源里根本没有这类行。
     * 这里用「同一会话零消息行」代表该形态（同时钉住「无行 ⇒ 未接管」）。
     */
    private static MessageRecord[] teammateWakeupMessages() {
        return new MessageRecord[0];
    }

    private static MessageRecord message(String sessionId, String id, Boolean isMeta, String author,
                                         String subtype, String queuedOrigin, String heldState,
                                         String content) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setRole("user");
        m.setIsMeta(isMeta);
        m.setAuthor(author);
        m.setSubtype(subtype);
        m.setQueuedOrigin(queuedOrigin);
        m.setHeldState(heldState);
        m.setContent(content);
        return m;
    }

    /** 会话行 + 消息行都走真实记录（不造假对象）；判定源是 mapper 返回的行。 */
    private void given(SessionRecord sessionRecord, MessageRecord... messages) {
        sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return sessionRecord.getId() != null && sessionRecord.getId().equals(id) ? sessionRecord : null;
        });
        messageMapper = mock(MessageMapper.class);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(messages));
        SessionModeState.setSessionMapper(sessionMapper);
        SessionToolDisableConfig.setSessionMapper(sessionMapper);
        SessionToolDisableConfig.setTakeoverResolver(new SessionTakeoverResolver(messageMapper));
    }

    /** 模型实际会收到的 tools 数组里的工具名（生产装配链 llmToolsArray）。 */
    private List<String> modelToolNames(String sessionId) {
        ToolUseContext tuc = new ToolUseContext(
                com.nexusai.application.agent.subagent.AgentContext.createAgentId(),
                sessionId,
                PermissionMode.DEFAULT,
                Map.of(),
                List.of(tool(ASK), tool(BASH)),
                null,
                AbortController.NOOP,
                List.of(),
                null,
                PermissionMode.DEFAULT);
        ArrayNode schema = LlmAgentLoop.llmToolsArray(tuc, QuerySource.USER);
        List<String> names = new ArrayList<>();
        for (JsonNode fn : schema) {
            names.add(fn.path("function").path("name").asText());
        }
        return names;
    }

    @BeforeEach
    void setUp() {
        SessionProjectRootTestSupport.declareNoDatabase();
        SessionToolCategory.reset();
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
        MemoryBareModeConfig.reset();

        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(List.of(tool(ASK), tool(BASH)));
        SessionToolCategory.setToolRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
        SessionToolCategory.reset();
        MemoryBareModeConfig.reset();
    }

    @Test
    @DisplayName("派生会话初始：模型 tools 数组不含 AskUserQuestion")
    void derived_noMessages_hidesAsk() {
        given(session(DERIVED, 1, null));

        List<String> names = modelToolNames(DERIVED);

        assertThat(names).as("派生/平级会话默认禁 Ask（保持现状）").doesNotContain(ASK).contains(BASH);
    }

    @Test
    @DisplayName("真实用户发过消息：下一轮 tools 数组含 AskUserQuestion")
    void derived_realUserMessage_allowsAsk() {
        given(session(DERIVED, 1, null), realUserMessage(DERIVED));

        assertThat(modelToolNames(DERIVED)).as("真人接管 ⇒ 放开 Ask").contains(ASK, BASH);
    }

    @Test
    @DisplayName("真实用户 busy 排队消息（busy-queued）同样算接管")
    void derived_busyQueuedUserMessage_allowsAsk() {
        given(session(DERIVED, 1, null), busyQueuedUserMessage(DERIVED));

        assertThat(modelToolNames(DERIVED)).contains(ASK, BASH);
    }

    @Test
    @DisplayName("peer 投递来信：不算接管，tools 数组仍不含 Ask")
    void derived_peerMessage_doesNotAllowAsk() {
        given(session(DERIVED, 1, null), peerMessage(DERIVED));

        assertThat(modelToolNames(DERIVED))
            .as("peer 来信 is_meta=true + author + agent_message + peer ⇒ 不是用户输入")
            .doesNotContain(ASK).contains(BASH);
    }

    @Test
    @DisplayName("cron / 定时注入：不算接管，tools 数组仍不含 Ask")
    void derived_cronMessage_doesNotAllowAsk() {
        given(session(DERIVED, 1, null), cronMessage(DERIVED));

        assertThat(modelToolNames(DERIVED)).doesNotContain(ASK).contains(BASH);
    }

    @Test
    @DisplayName("系统提醒 / 内部注入 / 子代理回报：不算接管，tools 数组仍不含 Ask")
    void derived_systemInjectedMessage_doesNotAllowAsk() {
        given(session(DERIVED, 1, null), systemInjectedMessage(DERIVED));

        assertThat(modelToolNames(DERIVED)).doesNotContain(ASK).contains(BASH);
    }

    @Test
    @DisplayName("IM 渠道入站：不算接管（is_meta=true，author/subtype/origin 全空），tools 数组仍不含 Ask")
    void derived_imInboundMessage_doesNotAllowAsk() {
        given(session(DERIVED, 1, null), imInboundMessage(DERIVED));

        assertThat(modelToolNames(DERIVED))
            .as("IM 入站与真人行的唯一区别是 is_meta=true ⇒ 必须继续禁 Ask"
                + "（人在 IM 那头，桌面没人能答）")
            .doesNotContain(ASK).contains(BASH);
    }

    @Test
    @DisplayName("同会话自投递（子代理/teammate → 主会话）：不算接管，tools 数组仍不含 Ask")
    void derived_sameSessionDelivery_doesNotAllowAsk() {
        given(session(DERIVED, 1, null), sameSessionDeliveryMessage(DERIVED));

        assertThat(modelToolNames(DERIVED))
            .as("peer 形状（is_meta + author + agent_message + peer）⇒ 不是用户输入")
            .doesNotContain(ASK).contains(BASH);
    }

    @Test
    @DisplayName("teammate 唤醒注入：只进内存不落 messages 表 ⇒ 不算接管，tools 数组仍不含 Ask")
    void derived_teammateWakeup_doesNotAllowAsk() {
        given(session(DERIVED, 1, null), teammateWakeupMessages());

        assertThat(modelToolNames(DERIVED))
            .as("AutonomousAgentLoop.injectUserMessage 不写 messages 表 ⇒ 判定源无行")
            .doesNotContain(ASK).contains(BASH);
    }

    @Test
    @DisplayName("peer 投递并起一轮后：下一轮 tools 数组仍不含 Ask（直接断言，不靠推断）")
    void derived_peerMessage_afterNextRound_stillHidesAsk() {
        // 第 1 轮：派生会话未接管
        List<MessageRecord> rows = new ArrayList<>();
        SessionRecord derived = session(DERIVED, 1, null);
        given(derived, rows.toArray(new MessageRecord[0]));
        when(messageMapper.selectListByQuery(any())).thenAnswer(inv -> List.copyOf(rows));

        List<String> round1 = modelToolNames(DERIVED);
        assertThat(round1).as("初始轮：不含 Ask").doesNotContain(ASK).contains(BASH);

        // peer 投递落地（生产形状）并「起一轮」→ 求值一次，再断言下一轮
        rows.add(peerMessage(DERIVED));
        List<String> round2 = modelToolNames(DERIVED);

        assertThat(round2)
            .as("peer 投递不改变接管状态 ⇒ 下一轮仍不含 Ask")
            .doesNotContain(ASK).contains(BASH);
    }

    @Test
    @DisplayName("无人值守优先级更高：即使用户发过消息，仍不含 Ask")
    void derived_unattended_afterUserMessage_stillHidesAsk() {
        given(session(DERIVED, 1, 1), realUserMessage(DERIVED));

        assertThat(modelToolNames(DERIVED))
            .as("显式无人值守 ⇒ Ask 恒不可用（优先于接管放开）")
            .doesNotContain(ASK).contains(BASH);
    }

    @Test
    @DisplayName("重启后仍为已接管（判定源是持久化 messages 表，不靠内存状态）")
    void derived_afterRestart_stillTakenOver() {
        SessionRecord derived = session(DERIVED, 1, null);
        MessageRecord userRow = realUserMessage(DERIVED);
        given(derived, userRow);
        assertThat(modelToolNames(DERIVED)).contains(ASK);

        // 模拟重启：清空全部静态桥接，再按 DB 重新装配（消息行仍在库里）
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
        given(derived, userRow);

        assertThat(modelToolNames(DERIVED))
            .as("重启后按 messages 表重算 ⇒ 仍为已接管")
            .contains(ASK, BASH);
    }

    @Test
    @DisplayName("普通主会话不受影响：tools 数组含 AskUserQuestion")
    void mainSession_seesAsk() {
        given(session(MAIN, null, null));

        assertThat(modelToolNames(MAIN)).contains(ASK, BASH);
    }

    @Test
    @DisplayName("子代理仍恒禁 Ask（CC 子代理规则未被本次改动影响）")
    void subagentRuleUnchanged() {
        assertThat(ToolNameConstants.ALL_AGENT_DISALLOWED_TOOLS).contains(ASK);
    }
}
