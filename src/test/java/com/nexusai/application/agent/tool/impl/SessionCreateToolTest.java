package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.PromptAlignSettingsResolver;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.model.session.dto.SessionCreateRequest;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionUpdateRequest;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionCreate 契约：schema / 能力门 / 继承 / 护栏 / 起跑与输出协议。
 */
@DisplayName("SessionCreate · 契约")
class SessionCreateToolTest {

    private static final String PARENT = "sess-parent1";
    private static final String CHILD = "sess-child01";

    private SessionCreateTool tool;
    private SessionService sessionService;
    private MessageService messageService;
    private ChatService chatService;
    private PeerMessageRouter peerMessageRouter;
    private ModelMapper modelMapper;
    private ProviderMapper providerMapper;
    private SessionRecord parent;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        messageService = mock(MessageService.class);
        chatService = mock(ChatService.class);
        peerMessageRouter = mock(PeerMessageRouter.class);
        modelMapper = mock(ModelMapper.class);
        providerMapper = mock(ProviderMapper.class);

        parent = new SessionRecord();
        parent.setId(PARENT);
        parent.setTitle("父会话");
        parent.setModelTag("DS");
        parent.setModelName("deepseek-chat");
        parent.setMainProjectId("proj-1");
        parent.setBareMode(1);
        parent.setPermissionMode("acceptEdits");
        parent.setMainThreadAgent("worker");
        parent.setCoordinatorMode(1);
        parent.setKbToolsEnabled(1);
        parent.setDerivedDepth(0);

        when(sessionService.getRecord(PARENT)).thenReturn(parent);
        when(sessionService.countDerivedFrom(PARENT)).thenReturn(0L);
        when(sessionService.countAllDerived()).thenReturn(0L);
        when(sessionService.create(any(SessionCreateRequest.class)))
            .thenReturn(sessionDto(CHILD, "竞品调研"));
        when(messageService.createUserMessage(eq(CHILD), any(SendMessageRequest.class)))
            .thenReturn(new MessageCreatedResponse("msg-1", "msg-stub-pending",
                "/topic/sessions/" + CHILD + "/stream", false));
        when(peerMessageRouter.sendSessionCreateLaunch(any(), any(), anyString()))
            .thenReturn(new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED,
                "peer-launch-test", "enqueued", null));

        tool = new SessionCreateTool();
        tool.setSessionService(sessionService);
        tool.setMessageService(messageService);
        tool.setChatService(chatService);
        tool.setPeerMessageRouter(peerMessageRouter);
        tool.setModelMapper(modelMapper);
        tool.setProviderMapper(providerMapper);

        // 工具池断言夹具不接 DB 回源：显式声明「本环境确无会话」，避免 cwd 域 fail-loud。
        com.nexusai.test.support.SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
        System.clearProperty("nexusai.feature.messaging-beyond-own-agents");
        // 静态槽逐项复位，防跨测试污染（同 CoordinatorSessionModeTest resetStaticSeams）。
        PromptAlignSettingsResolver.setStaticResolver(null);
        LlmAgentLoop.setCoordinatorMode(null);
        MemoryBareModeConfig.reset();
        com.nexusai.test.support.SessionProjectRootTestSupport.clearNoDatabase();
    }

    // ─────────────────────────── schema / 门控 ───────────────────────────

    @Test
    @DisplayName("name / schema：prompt+title+model+inherit，required 为空，additionalProperties=false")
    void schemaContract() {
        assertThat(tool.name()).isEqualTo("SessionCreate");
        ObjectNode schema = (ObjectNode) tool.inputSchema();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").has("prompt")).isTrue();
        assertThat(schema.get("properties").has("title")).isTrue();
        assertThat(schema.get("properties").has("model")).isTrue();
        assertThat(schema.get("properties").get("model").get("type").asText()).isEqualTo("string");
        assertThat(schema.get("properties").get("model").get("description").asText())
            .as("model 描述必须指向 ListModels，并写明只接受对话类模型")
            .contains("ListModels")
            .contains("conversation-capable");
        assertThat(schema.get("properties").has("inherit")).isTrue();
        assertThat(schema.get("properties").get("inherit").get("type").asText()).isEqualTo("boolean");
        // 派生会话恒为普通模式（用户裁定）⇒ schema 不暴露 coordinator 开关，模型无法要求协调者派生。
        assertThat(schema.get("properties").has("coordinator"))
            .as("派生会话恒显式 coordinator_mode=0 ⇒ schema 不得暴露 coordinator 参数")
            .isFalse();
        // prompt 参数文案必须与实际投递形态一致（peer 首条任务，submitted 而非 running/user 消息）。
        String promptDesc = schema.get("properties").get("prompt").get("description").asText();
        assertThat(promptDesc)
            .as("prompt 描述必须说明首条任务经 peer 通道投递")
            .contains("peer")
            .doesNotContain("first user message")
            .doesNotContain("starts running");
        assertThat(schema.get("required").size()).isZero();
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("能力门跟随跨会话投递开关")
    void gate() {
        TaskSystemConfig.clearForTest();
        System.setProperty("nexusai.feature.messaging-beyond-own-agents", "false");
        assertThat(tool.isEnabled()).isFalse();
        System.setProperty("nexusai.feature.messaging-beyond-own-agents", "true");
        assertThat(tool.isEnabled()).isTrue();
    }

    // ─────────────────────────── 失败面 ───────────────────────────

    @Test
    @DisplayName("无会话上下文 → 明确报错")
    void noParentSession() {
        String out = String.valueOf(tool.execute(call("{}"), null).data());
        assertThat(out).contains("requires an active session context");
        verify(sessionService, never()).create(any());
    }

    @Test
    @DisplayName("父会话不存在 → 明确报错")
    void parentNotFound() {
        when(sessionService.getRecord("sess-missing")).thenReturn(null);
        String out = String.valueOf(tool.execute(call("{}"), tuc("sess-missing")).data());
        assertThat(out).contains("parent session sess-missing not found");
    }

    @Test
    @DisplayName("派生深度超限（父=2 → 新=3）→ 拒绝")
    void depthLimit() {
        parent.setDerivedDepth(2);
        String out = String.valueOf(tool.execute(call("{}"), tuc(PARENT)).data());
        assertThat(out).contains("derived depth limit");
        verify(sessionService, never()).create(any());
    }

    @Test
    @DisplayName("单父派生数达上限 → 拒绝")
    void perParentLimit() {
        when(sessionService.countDerivedFrom(PARENT)).thenReturn(20L);
        String out = String.valueOf(tool.execute(call("{}"), tuc(PARENT)).data());
        assertThat(out).contains("limit 20");
        verify(sessionService, never()).create(any());
    }

    @Test
    @DisplayName("全局派生总数达上限 → 拒绝")
    void globalLimit() {
        when(sessionService.countAllDerived()).thenReturn(200L);
        String out = String.valueOf(tool.execute(call("{}"), tuc(PARENT)).data());
        assertThat(out).contains("too many derived sessions");
        verify(sessionService, never()).create(any());
    }

    // ─────────────────────────── 成功面 ───────────────────────────

    @Test
    @DisplayName("无 prompt：建空会话 + 继承（模型除外）+ 登记 endpoint，输出 status=idle")
    void createIdle() {
        String out = String.valueOf(tool.execute(call("{\"title\":\"竞品调研\"}"), tuc(PARENT)).data());

        ArgumentCaptor<SessionCreateRequest> createCap = ArgumentCaptor.forClass(SessionCreateRequest.class);
        verify(sessionService).create(createCap.capture());
        SessionCreateRequest req = createCap.getValue();
        assertThat(req.title()).isEqualTo("竞品调研");
        // 模型不继承：即使父会话有 tag/名，也一律走系统默认（null ⇒ tag 默认 + 运行时解析默认模型）
        assertThat(req.model())
            .as("派生会话不得复制父会话模型 tag")
            .isNull();
        assertThat(req.modelName())
            .as("派生会话不得复制父会话模型名称，留 null 走系统默认解析")
            .isNull();
        assertThat(req.mainProjectId()).isEqualTo("proj-1");
        assertThat(req.bareMode()).isTrue();

        // 其余会话列走 update 继承；协调者模式**不继承**（父=1 也不抄，派生默认普通）。
        SessionUpdateRequest upd = captureUpdate();
        assertThat(upd.permissionMode()).isEqualTo("acceptEdits");
        assertThat(upd.mainThreadAgent()).isEqualTo("worker");
        assertThat(upd.kbToolsEnabled()).isTrue();
        assertThat(upd.coordinatorMode())
            .as("派生会话必须显式写普通模式（false），不得抄父会话的 1")
            .isFalse();

        verify(sessionService).markDerived(CHILD, PARENT, 1);
        verify(chatService).ensurePeerEndpointLive(CHILD);
        verify(messageService, never()).createUserMessage(anyString(), any());

        assertThat(out).contains("created session: " + CHILD);
        assertThat(out).contains("status: idle");
        assertThat(out).contains("SendMessage({ to: \"" + CHILD + "\"");
    }

    @Test
    @DisplayName("带 prompt：走 peer 投递（父→子），输出 submitted + launch id + reply_to，且不再走用户消息直起轮")
    void createAndRun() {
        String out = String.valueOf(tool.execute(
            call("{\"title\":\"竞品调研\",\"prompt\":\"调研竞品 A/B/C\"}"), tuc(PARENT)).data());

        ArgumentCaptor<PeerMessageRouter.SendRequest> sendCap =
            ArgumentCaptor.forClass(PeerMessageRouter.SendRequest.class);
        verify(peerMessageRouter).sendSessionCreateLaunch(sendCap.capture(), any(), anyString());
        assertThat(sendCap.getValue().targetSessionId()).isEqualTo(CHILD);
        assertThat(sendCap.getValue().sourceSessionId()).isEqualTo(PARENT);
        assertThat(sendCap.getValue().body()).isEqualTo("调研竞品 A/B/C");

        // ⛔ 不得再走「普通用户消息 + 直接起轮」——否则同一条任务可能被执行两次
        verify(messageService, never()).createUserMessage(any(), any());
        verify(chatService, never()).processUserMessage(any(), any(), any(), any());

        assertThat(out).contains("status: submitted")
            .contains("first_task_message_id: peer-launch-test")
            .contains("reply_to: " + PARENT)
            .contains("completion is not yet confirmed");
    }

    @Test
    @DisplayName("peer 投递被拒：会话已建则部分成功带 session_id，绝不引导重建")
    void submissionRejected_partialSuccess() {
        when(peerMessageRouter.sendSessionCreateLaunch(any(), any(), anyString()))
            .thenReturn(new PeerMessageRouter.Receipt(PeerMessageRouter.Status.SENDER_PACED,
                "peer-launch-x", "paced", null));

        String out = String.valueOf(tool.execute(
            call("{\"title\":\"竞品调研\",\"prompt\":\"调研\"}"), tuc(PARENT)).data());

        assertThat(out).contains("created session: " + CHILD)
            .contains("status: created_start_failed")
            .contains("dispatch_state: SENDER_PACED")
            .contains("do_not_recreate: true");
    }

    @Test
    @DisplayName("peer 路由未接线 + 有 prompt：create 前 error，零创建")
    void peerRouterMissing_rejectedBeforeCreate() {
        tool.setPeerMessageRouter(null);

        String out = String.valueOf(tool.execute(
            call("{\"prompt\":\"调研\"}"), tuc(PARENT)).data());

        assertThat(out).contains("peer delivery is not wired");
        verify(sessionService, never()).create(any());
    }

    @Test
    @DisplayName("inherit=false：不继承权限/主线程 agent/KB/模型，项目绑定仍沿用父会话；协调者仍默认普通")
    void inheritFalse() {
        tool.execute(call("{\"title\":\"独立会话\",\"inherit\":false}"), tuc(PARENT));

        ArgumentCaptor<SessionCreateRequest> createCap = ArgumentCaptor.forClass(SessionCreateRequest.class);
        verify(sessionService).create(createCap.capture());
        SessionCreateRequest req = createCap.getValue();
        assertThat(req.model()).isNull();
        assertThat(req.modelName()).isNull();
        assertThat(req.bareMode()).isNull();
        assertThat(req.mainProjectId()).isEqualTo("proj-1");
        // inherit=false → 不写继承列，但协调者仍显式写普通模式（派生会话默认普通，与 inherit 无关）
        SessionUpdateRequest upd = captureUpdate();
        assertThat(upd.permissionMode()).isNull();
        assertThat(upd.mainThreadAgent()).isNull();
        assertThat(upd.kbToolsEnabled()).isNull();
        assertThat(upd.coordinatorMode())
            .as("inherit=false 也不得继承协调者模式 ⇒ 显式 false")
            .isFalse();
    }

    @Test
    @DisplayName("未给 title：用默认标题「新会话」（占位值，允许自动标题覆盖）")
    void defaultTitle() {
        tool.execute(call("{}"), tuc(PARENT));
        ArgumentCaptor<SessionCreateRequest> createCap = ArgumentCaptor.forClass(SessionCreateRequest.class);
        verify(sessionService).create(createCap.capture());
        assertThat(createCap.getValue().title()).isEqualTo("新会话");
        // 「新会话」在占位标题集合里 ⇒ titleExplicit=0，首轮后允许自动标题覆盖（以代码为准）
        assertThat(ChatService.isDefaultTitle(createCap.getValue().title(), null))
            .as("默认标题是占位值 ⇒ 允许首轮后自动生成标题")
            .isTrue();
    }

    // ─────────────────────────── 显式 model 参数（2026-11-21） ───────────────────────────

    @Test
    @DisplayName("model 参数：provider/name 命中 → 写规范全名 + 模型行 tag 到新会话")
    void modelParam_fullName_storedAsOverride() {
        stubModel("prov-1", "deepseek", "deepseek-chat", "MM");

        tool.execute(call("{\"title\":\"x\",\"model\":\"deepseek/deepseek-chat\"}"), tuc(PARENT));

        ArgumentCaptor<SessionCreateRequest> createCap = ArgumentCaptor.forClass(SessionCreateRequest.class);
        verify(sessionService).create(createCap.capture());
        SessionCreateRequest req = createCap.getValue();
        assertThat(req.modelName())
            .as("显式 model 按 provider/name 规范全名落 sessions.model_name（会话 override）")
            .isEqualTo("deepseek/deepseek-chat");
        assertThat(req.model())
            .as("展示 tag 取模型行自己的 tag（MM），而不是默认 DS")
            .isEqualTo("MM");
    }

    @Test
    @DisplayName("model 参数未命中 → fail-loud 拒绝，不建会话（不落脏 override）")
    void modelParam_unknownModel_rejected() {
        // 未 stub 任何 provider/model 行 ⇒ ModelNameResolver 全名路径 + 兼容路径均未命中
        String out = String.valueOf(tool.execute(
            call("{\"title\":\"x\",\"model\":\"nope/nope\"}"), tuc(PARENT)).data());

        assertThat(out).contains("not found or not enabled");
        verify(sessionService, never()).create(any());
    }

    @Test
    @DisplayName("model 参数：embedding 类型 → 拒绝，不建会话（类型白名单）")
    void modelParam_embeddingType_rejected() {
        // WHY：聊天路径（LlmProviderFactory）不校验 model.type，embedding 写进 sessions.model_name
        //   后要到第一次模型调用才以奇怪方式失败 ⇒ 必须在工具入口 fail-loud。
        stubModel("prov-embed", "embed", "text-embedding-v4", "DS", "embedding", true);

        String out = String.valueOf(tool.execute(
            call("{\"title\":\"x\",\"model\":\"embed/text-embedding-v4\"}"), tuc(PARENT)).data());

        assertThat(out).contains("only conversation models")
            .contains("Call ListModels");
        verify(sessionService, never()).create(any());
    }

    @Test
    @DisplayName("model 参数：provider 已停用 → 拒绝，不建会话（补 ModelNameResolver 缺的那道门）")
    void modelParam_disabledProvider_rejected() {
        // 真库反例：xm provider enabled=0，其下模型仍 enabled=1。ModelNameResolver 只查 model.enabled。
        stubModel("prov-x", "xm", "mimo-v2.6-pro", "DS", "multimodal", false);

        String out = String.valueOf(tool.execute(
            call("{\"title\":\"x\",\"model\":\"xm/mimo-v2.6-pro\"}"), tuc(PARENT)).data());

        assertThat(out).contains("disabled provider")
            .contains("Call ListModels");
        verify(sessionService, never()).create(any());
    }

    @Test
    @DisplayName("prompt：主动协作场景 + ListModels + 类型/默认模型 + 安全边界")
    void promptCoversPeerSemantics() {
        String p = tool.prompt();
        assertThat(p).contains("peer session")
            .contains("proactively")
            .contains("SendMessage")
            .contains("ListModels")
            .contains("provider/modelName")
            .contains("conversation-capable")
            .contains("globally configured main model")
            .contains("never the user's approval")
            .contains("derived depth 2");
        assertThat(tool.description()).contains("peer session");
    }

    @Test
    @DisplayName("model 参数为空串/空白 → 视为未传，走系统默认（modelName=null）")
    void modelParam_blank_usesSystemDefault() {
        tool.execute(call("{\"title\":\"x\",\"model\":\"  \"}"), tuc(PARENT));

        ArgumentCaptor<SessionCreateRequest> createCap = ArgumentCaptor.forClass(SessionCreateRequest.class);
        verify(sessionService).create(createCap.capture());
        assertThat(createCap.getValue().modelName()).isNull();
        assertThat(createCap.getValue().model()).isNull();
    }

    // ─────────────────────────── 协调者模式不继承（2026-11-20 修缺陷） ───────────────────────────

    @Test
    @DisplayName("a) 协调者父会话 → 派生会话 coordinator_mode=false（不继承父会话的 1）")
    void coordinatorParent_derivedNotCoordinator() {
        // WHY：真实模型 E2E 缺陷 —— 协调者用 SessionCreate 建出的会话继承了 coordinator_mode=1，
        //   顶层工具池被裁成协调者白名单（8 项），没有 Read/Bash/Edit，派实活会卡住。
        //   变异点：把这行改回 toBoolean(parent.getCoordinatorMode()) → coordinatorMode()=true → 红。
        parent.setCoordinatorMode(1);

        tool.execute(call("{\"title\":\"worker\"}"), tuc(PARENT));

        SessionUpdateRequest upd = captureUpdate();
        assertThat(upd.coordinatorMode())
            .as("派生会话默认普通模式：父会话 coordinator=1 不得被继承")
            .isFalse();
    }

    @Test
    @DisplayName("b) 派生会话（按实际写入列值）工具集是全量：含 Read/Bash/Edit，不是协调者 8 项白名单")
    void derivedSession_fullToolSet_notCoordinatorWhitelist() {
        // WHY：断言「写入值」与「模型可见工具集」闭环 —— 只测字段等于 false 不足以证明修好了现象。
        //   读侧走真实生产链：PromptAlignSettingsResolver 按 sessionId 读 sessions.coordinator_mode
        //   → LlmAgentLoop 顶层工具池过滤。
        //   变异点：写入值变 true（恢复继承）→ 子会话工具池被裁 ⇒ 不含 Read/Bash/Edit → 红。
        parent.setCoordinatorMode(1);
        tool.execute(call("{\"title\":\"worker\"}"), tuc(PARENT));

        SessionUpdateRequest upd = captureUpdate();
        Boolean written = upd.coordinatorMode();

        // 末层取最不利形态：env 开（feature 关 + env="1" 也开）——若写入值不是显式 false，
        //   回落层会把派生会话判成协调者。
        PromptAlignSettingsResolver.setStaticResolver(
            resolverWithCoordinatorColumn(CHILD, written == null ? null : (written ? 1 : 0)));
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> "1"));
        MemoryBareModeConfig.reset();

        List<String> childTools = modelToolNames(CHILD);
        assertThat(childTools)
            .as("派生会话工具集必须是全量（含实质执行工具），不是协调者 8 项白名单")
            .contains("Bash", "Read", "Edit", "WebSearch")
            .hasSizeGreaterThan(8);

        // 对照组：同一判据下父会话（列=1）确实被裁 —— 证明测试夹具真的走到了协调者门，
        //   而不是「门根本没生效所以两个会话都全量」的假绿。
        PromptAlignSettingsResolver.setStaticResolver(resolverWithCoordinatorColumn(PARENT, 1));
        List<String> parentTools = modelToolNames(PARENT);
        assertThat(parentTools)
            .as("对照组：父会话（协调者）只看到编排白名单")
            .doesNotContain("Bash", "Read", "Edit", "WebSearch")
            .contains("SendMessage", "SessionCreate");
    }

    @Test
    @DisplayName("c) 普通会话建派生会话：行为不变（其余列照旧继承，协调者=false）")
    void normalParent_behaviorUnchanged() {
        parent.setCoordinatorMode(0);

        tool.execute(call("{\"title\":\"peer\"}"), tuc(PARENT));

        SessionUpdateRequest upd = captureUpdate();
        assertThat(upd.permissionMode()).isEqualTo("acceptEdits");
        assertThat(upd.mainThreadAgent()).isEqualTo("worker");
        assertThat(upd.kbToolsEnabled()).isTrue();
        assertThat(upd.coordinatorMode()).isFalse();
        // create 侧继承：模型始终不继承（走系统默认），其余列照旧
        ArgumentCaptor<SessionCreateRequest> createCap = ArgumentCaptor.forClass(SessionCreateRequest.class);
        verify(sessionService).create(createCap.capture());
        assertThat(createCap.getValue().model())
            .as("inherit=true 也不复制父会话模型 tag")
            .isNull();
        assertThat(createCap.getValue().modelName())
            .as("inherit=true 也不复制父会话模型名称")
            .isNull();
        assertThat(createCap.getValue().bareMode()).isTrue();
    }

    @Test
    @DisplayName("c2) 父会话 coordinator 列 NULL（未设置）→ 派生会话仍显式 false，不靠回落层")
    void normalParentNullColumn_derivedExplicitFalse() {
        parent.setCoordinatorMode(null);

        tool.execute(call("{\"title\":\"peer\"}"), tuc(PARENT));

        assertThat(captureUpdate().coordinatorMode())
            .as("未设置不得变成「靠 feature+env 回落」（env 开时派生会话会变协调者）")
            .isFalse();
    }

    @Test
    @DisplayName("d) 显式 coordinator=true 也被忽略 → 派生会话恒写普通模式（恒显式 0）")
    void explicitCoordinatorTrue_ignored() {
        parent.setCoordinatorMode(0);

        tool.execute(call("{\"title\":\"co\",\"coordinator\":true}"), tuc(PARENT));

        assertThat(captureUpdate().coordinatorMode())
            .as("用户裁定：派生会话恒显式 coordinator_mode=0，不接受覆盖参数")
            .isFalse();
    }

    @Test
    @DisplayName("d2) inherit=false + 显式 coordinator=true → 仍恒写普通模式，其余列不继承")
    void explicitCoordinatorWithNoInherit_ignored() {
        tool.execute(call("{\"title\":\"co\",\"inherit\":false,\"coordinator\":true}"), tuc(PARENT));

        SessionUpdateRequest upd = captureUpdate();
        assertThat(upd.coordinatorMode())
            .as("含 inherit=false 路径也必须显式写 0")
            .isFalse();
        assertThat(upd.permissionMode()).isNull();
        assertThat(upd.kbToolsEnabled()).isNull();
    }

    @Test
    @DisplayName("e) 整条继承写失败（如 mainThreadAgent 已删除）→ 仍补写 coordinator=false，不留 NULL 回落 env")
    void inheritUpdateFails_coordinatorStillWrittenFalse() {
        // WHY：单列继承失败不阻断是既有设计（整条 update 抛异常 ⇒ 其余列不继承）。但
        //   coordinator_mode 留在 NULL 会回落 env —— 协调者环境里派生会话又变成 8 个工具。
        //   变异点：删掉 catch 里的补写 ⇒ 该列仍为 NULL ⇒ 断言 calls(2) 不成立 → 红。
        parent.setCoordinatorMode(1);
        when(sessionService.update(eq(CHILD), any(SessionUpdateRequest.class)))
            .thenThrow(new RuntimeException("mainThreadAgent not found"))
            .thenReturn(sessionDto(CHILD, "worker"));

        String out = String.valueOf(tool.execute(call("{\"title\":\"worker\"}"), tuc(PARENT)).data());

        ArgumentCaptor<SessionUpdateRequest> cap = ArgumentCaptor.forClass(SessionUpdateRequest.class);
        verify(sessionService, times(2)).update(eq(CHILD), cap.capture());
        List<SessionUpdateRequest> calls = cap.getAllValues();
        assertThat(calls.get(0).coordinatorMode()).isFalse();
        assertThat(calls.get(1).coordinatorMode())
            .as("补写仍必须是普通模式")
            .isFalse();
        assertThat(calls.get(1).permissionMode()).isNull();
        assertThat(calls.get(1).kbToolsEnabled()).isNull();
        assertThat(out).contains("created session: " + CHILD);
    }

    // ─────────────────────────── fixtures ───────────────────────────

    /** 让 ModelNameResolver.resolve("provider/name") 命中一行 enabled 模型（全名路径 + toFullName 都够用）。 */
    private void stubModel(String providerId, String providerName, String modelName, String tag) {
        stubModel(providerId, providerName, modelName, tag, null, true);
    }

    /** 带 type / provider.enabled 的夹具（2026-11-21 新增两道门：类型白名单 + provider 启用）。 */
    private void stubModel(String providerId, String providerName, String modelName, String tag,
                           String type, boolean providerEnabled) {
        ProviderRecord provider = new ProviderRecord();
        provider.setId(providerId);
        provider.setName(providerName);
        provider.setEnabled(providerEnabled);
        ModelRecord model = new ModelRecord();
        model.setId("model-1");
        model.setName(modelName);
        model.setProviderId(providerId);
        model.setEnabled(true);
        model.setTag(tag);
        model.setType(type);
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider);
        when(modelMapper.selectOneByQuery(any())).thenReturn(model);
        when(providerMapper.selectOneById(eq(providerId))).thenReturn(provider);
    }

    /** 捕获唯一的会话设置更新请求（协调者模式下仍需落库）。 */
    private SessionUpdateRequest captureUpdate() {        ArgumentCaptor<SessionUpdateRequest> cap = ArgumentCaptor.forClass(SessionUpdateRequest.class);
        verify(sessionService).update(eq(CHILD), cap.capture());
        return cap.getValue();
    }

    /** 真 resolver + mock SessionMapper：按 sessionId 返回 coordinator 列值（生产读侧链）。 */
    private static PromptAlignSettingsResolver resolverWithCoordinatorColumn(
        String sessionId, Integer columnValue) {
        PromptAlignSettingsResolver r = new PromptAlignSettingsResolver();
        SessionMapper mapper = mock(SessionMapper.class);
        SessionRecord row = new SessionRecord();
        row.setId(sessionId);
        row.setCoordinatorMode(columnValue);
        when(mapper.selectOneById(sessionId)).thenReturn(row);
        r.setSessionMapper(mapper);
        return r;
    }

    /** 模拟主循环全量工具池：协调者白名单 8 项 + 实质执行工具 + 普通 MCP。 */
    private static List<Tool> fullToolPool() {
        return List.of(
            tool("Agent"), tool("TaskStop"), tool("SendMessage"), tool("SessionCreate"),
            tool("Skill"), tool("Workflow"), tool("ListAgents"), tool("StructuredOutput"),
            tool("Bash"), tool("Read"), tool("Edit"), tool("WebSearch"), tool("Glob"),
            tool("mcp__random.server.tool"));
    }

    private static Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.isEnabled()).thenReturn(true);
        return t;
    }

    /** 模型 schema 里的工具名（USER 顶层循环口径）。 */
    private static List<String> modelToolNames(String sessionId) {
        ToolUseContext poolCtx = new ToolUseContext(
            com.nexusai.application.agent.subagent.AgentContext.createAgentId(), sessionId, PermissionMode.DEFAULT, Map.of(), fullToolPool(),
            null, AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
        ArrayNode schema = LlmAgentLoop.llmToolsArray(poolCtx, QuerySource.USER);
        return StreamSupport.stream(schema.spliterator(), false)
            .map(n -> n.path("function").path("name").asText())
            .toList();
    }

    private static ToolUseBlock call(String json) {
        return new ToolUseBlock(UUID.randomUUID().toString(), SessionCreateTool.NAME, readJson(json));
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolUseContext tuc(String sessionId) {
        return new ToolUseContext(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null,
            PermissionMode.DEFAULT);
    }

    private static SessionDto sessionDto(String id, String title) {
        return new SessionDto(
            id, "DS", "deepseek-chat", title, "现在", com.nexusai.model.session.dto.SessionGroup.current,
            null, "proj-1", 0, OffsetDateTime.now(), OffsetDateTime.now(),
            null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
