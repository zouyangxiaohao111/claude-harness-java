package com.nexusai.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [批 P16 · 接线层守护] {@code ChatService.appendReasoningDurationToTranscript} 的
 * <b>flat transcript 写侧锚</b>必须是 <b>稳定会话绑定项目根</b>
 * （{@link SessionStorage#sessionProjectRoot(String)}），
 * ⛔ 不得回落到随 worktree 重锚的 {@code originalCwd} 槽
 * （{@link CwdResolution#getOriginalCwdLayer(String)}）。
 *
 * <h2>WHY（规则九 · 测试验证意图；为什么必须另立一类）</h2>
 * 批 P13 把写侧锚由 {@code CwdResolution.getOriginalCwdLayer(sessionId)} 换成
 * {@code Path.of(SessionStorage.sessionProjectRoot(sessionId))}（{@code ChatService:1926-1927}）。
 * 同批 {@code SessionStorageTranscriptRootParityTest} 的守护④（{@code flatTranscriptWriteAnchor_isStable}）
 * <b>只断言 {@code SessionStorage.sessionProjectRoot} 这个 seam 自己</b>——它<b>不经过 ChatService</b>。
 *
 * <p>实证（主 agent 验收 P13 时的变异 E）：把 {@code ChatService} 该行改回
 * {@code CwdResolution.getOriginalCwdLayer(sessionId)}，该批<b>18 run 全绿</b> ⇒
 * 「生产写侧是否真接了新锚」<b>没有任何一条断言守着</b>。
 *
 * <h2>为什么本类必须以「生产链路 + 真实落盘」为唯一入口</h2>
 * 本类<b>不</b>直接调 {@code SessionStorage}、也不反射调私有方法，而是走既有生产链：
 * {@code armRealTimePersist} 武装 appendListener → {@code state.appendMessage(...)} →
 * {@code persistAppendedMessage} → {@code appendReasoningDurationToTranscript} → 真实写盘。
 * 断言的是<b>文件实际落在哪个 slug 目录</b>——写侧锚换成哪个槽，落点就换哪个目录，无法掩盖。
 *
 * <h2>RED 条件（反向实验配方 · 实测见批 P16 报告）</h2>
 * 把 {@code ChatService:1926-1927} 的
 * {@code Path.of(SessionStorage.sessionProjectRoot(sessionId))} 改回
 * {@code Path.of(CwdResolution.getOriginalCwdLayer(sessionId))} ⇒
 * 文件落到 worktree slug ⇒ 本类两条断言（exists / doesNotExist）同时翻红。
 *
 * <h2>夹具要点（⛔ 不要删）</h2>
 * <ul>
 *   <li><b>必须显式 {@code SessionProjectRoot.setForSession}</b>：单测环境里
 *       {@code NoDatabaseSessionProjectRootExtension} 对<b>任意</b> sessionId 答
 *       {@code sessionlessEnvironment()}（见 {@link SessionProjectRootTestSupport}）。不显式登记，
 *       两槽会落到同一个「无会话命名出口」值（进程 {@code user.dir}）⇒ 断言无鉴别力（假绿）。</li>
 *   <li><b>必须让两槽取不同值</b>（绑定根 = temp 目录，originalCwd 层 = 另一个 temp 目录）：
 *       这是鉴别力的唯一来源。</li>
 *   <li>config-home 经 {@link ClaudePaths#setConfigDirOverride} + {@link NexusaiPaths#setAppNameOverride}
 *       重定向到 {@code @TempDir}（防污染真实用户 config-home；对齐既有同域测试）。</li>
 * </ul>
 */
@DisplayName("[批 P16] ChatService 接线：flat transcript 写侧锚 = 稳定会话绑定项目根")
class ChatServiceTranscriptWriteAnchorWiringTest {

    private static final String SESSION = "sess-p16-flat";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private ChatService service;

    @BeforeEach
    void setUp() {
        // 夹具不接 DB 回源（见 SessionProjectRootTestSupport javadoc）；本类会话经 setForSession 显式登记。
        SessionProjectRootTestSupport.declareNoDatabase();
        service = new ChatService();
        MessageMapper messageMapper = mock(MessageMapper.class);
        ToolCallMapper toolCallMapper = mock(ToolCallMapper.class);
        MessageService messageService = mock(MessageService.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "messageService", messageService);
        ClaudePaths.setConfigDirOverride(tempDir.toString());
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        NexusaiPaths.setAppNameOverride(null);
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionProjectRoot.reset();
        SessionCwdHolder.clearOriginalCwd(SESSION);
    }

    /** 会话绑定项目根（会话身份锚 · 稳定）· 真实目录。 */
    private Path boundProject() throws Exception {
        return Files.createDirectories(tempDir.resolve("proj-bound")).toRealPath();
    }

    /** worktree 目录（一次性隔离目录 · originalCwd 重锚后的值）· 真实目录。 */
    private Path worktree() throws Exception {
        return Files.createDirectories(tempDir.resolve("proj-worktree")).toRealPath();
    }

    /** 三槽夹具 + 装置上膛证明（两槽必须取不同值，否则本类无鉴别力）。 */
    private void armTwoDistinctSlots(Path proj, Path wt) {
        SessionProjectRoot.setForSession(SESSION, proj.toString());
        SessionCwdHolder.setOriginalCwd(SESSION, wt.toString());
        assertThat(CwdResolution.getOriginalCwdLayer(SESSION))
            .as("夹具前置：originalCwd 层 = worktreePath（EnterWorktreeTool.applySessionCwd 的效果）"
                + "—— 否则「改回旧锚」与「新锚」同值 ⇒ 本类无鉴别力")
            .isEqualTo(wt.toString());
        assertThat(CwdResolution.getOriginalCwdLayer(SESSION))
            .as("夹具前置：originalCwd 层必须 ≠ 绑定根（本类全部断言的前提）")
            .isNotEqualTo(proj.toString());
    }

    /** 生产链路触发：武装实时落库 listener 后逐条 append（对齐 doRun「先 arm 后 append」）。 */
    private void armAndAppend(AgentState state, ChatMessageDto... messages) {
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, mock(SimpMessagingTemplate.class), "msg-user");
        for (ChatMessageDto m : messages) {
            state.appendMessage(m);
        }
    }

    private static ChatMessageDto assistantWithReasoning(String id, String content, long durationMs) {
        return new ChatMessageDto(
            id, SESSION, Role.assistant, null, content, "思考",
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false).withReasoningDurationMs(durationMs);
    }

    @Test
    @DisplayName("落点在稳定绑定根 slug 下；worktree slug 下零文件（反向实验：改回旧锚 ⇒ 红）")
    void flatTranscriptLandsUnderStableBoundProject_notWorktree() throws Exception {
        Path proj = boundProject();
        Path wt = worktree();
        armTwoDistinctSlots(proj, wt);

        AgentState state = new AgentState("sys");
        armAndAppend(state, assistantWithReasoning("a-final", "最终回复", 1500L));

        Path onStableRoot = SessionStorage.getTranscriptPath(Path.of(SessionStorage.sessionProjectRoot(SESSION)), SESSION);
        Path onOriginalCwd = SessionStorage.getTranscriptPath(Path.of(CwdResolution.getOriginalCwdLayer(SESSION)), SESSION);

        assertThat(onStableRoot)
            .as("reasoning-duration 双轨 entry 必须落在【稳定会话绑定项目根】的 slug 下"
                + "（ChatService:1926-1927 接线；反向实验：改回 getOriginalCwdLayer ⇒ 落到 worktree slug ⇒ 红）")
            .exists();
        assertThat(onOriginalCwd)
            .as("⛔ worktree（originalCwd）slug 下不得出现该 entry —— 写侧锚一旦随 worktree 重锚，"
                + "同一次会话的逐条 entry 会分裂到两个 slug（实测：sess-afbae75d 的 257 条全落在 worktree slug）")
            .doesNotExist();

        // 绝对锚（结构性 · 零 IO）：落点 = {configHome}/projects/{slug(bound)}/{sid}.jsonl
        assertThat(onStableRoot.getParent())
            .as("落点的父目录必须就是绑定项目根的 slug 目录（不是 worktree 的）")
            .isEqualTo(SessionStorage.getProjectDir(proj));

        // 内容校验：证明这是本次写入产生的 entry（不是别的来源留下的空文件）
        JsonNode line = JSON.readTree(Files.readString(onStableRoot).trim());
        assertThat(line.path("type").asText()).isEqualTo("reasoning-duration");
        assertThat(line.path("sessionId").asText()).isEqualTo(SESSION);
        assertThat(line.path("messageId").asText()).isEqualTo("a-final");
        assertThat(line.path("reasoningDurationMs").asLong()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("对照：无 reasoning ⇒ 两侧都不写（排除「文件恒存在」的假装置）")
    void noReasoning_noFileOnEitherRoot() throws Exception {
        Path proj = boundProject();
        Path wt = worktree();
        armTwoDistinctSlots(proj, wt);

        AgentState state = new AgentState("sys");
        armAndAppend(state, new ChatMessageDto(
            "a-final", SESSION, Role.assistant, null, "回复", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false));

        assertThat(SessionStorage.getTranscriptPath(Path.of(SessionStorage.sessionProjectRoot(SESSION)), SESSION))
            .as("装置上膛证明：无 reasoning 时本链路确实不写文件 ⇒ 上一条用例的 exists() 具备鉴别力")
            .doesNotExist();
        assertThat(SessionStorage.getTranscriptPath(Path.of(CwdResolution.getOriginalCwdLayer(SESSION)), SESSION))
            .doesNotExist();
    }
}
