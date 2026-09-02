package com.nexusai.application;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.TaskBudget;
import com.nexusai.application.agent.tasks.MainSessionBackgroundService;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.chat.ChatService;
import com.nexusai.apis.verify.VerifyChatController;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [IMP2-10 · MISS-2 · OD-13] taskBudget 生产入口注入（ChatService / MainSessionBackgroundService /
 * VerifyChatController）· 意图验证。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: 探查确认 loop 内结转/注入链路完整（CC query.ts:291/508-515/
 * 699-706/1138-1146 → LlmAgentLoop applyTaskBudgetCarryover → TaskBudgetParam → provider 请求体），
 * 唯三生产入口（ChatService:207 / MainSessionBackgroundService:341 / VerifyChatController:81-84）
 * 构造 RunRequest 时 taskBudget 恒传 null → 结转死计算（MISS-2 · OD-13 追加登记）。
 * 本测试逐入口捕获真实构造的 RunRequest，断言 taskBudget 非 null 且 {total} 输入契约语义正确
 * （remaining 不是输入契约 —— CC query.ts:197 仅 {total}，remaining 是 queryLoop 局部量，见
 * {@code TaskBudget} record 契约）。
 *
 * <p><b>RED 证据</b>: 实施前三个入口均传 null → 本测试断言 fail（expected not null）。
 *
 * <p>入口驱动方式对齐既有测试约定：手工 new + ReflectionTestUtils 注入 mock 依赖
 * （MainSessionBackgroundServiceTest / R32B7b2_ProductionWiringTest 同式样），不启动 Spring 容器。
 */
@DisplayName("[IMP2-10] taskBudget 生产入口注入 · 三入口构造非 null + {total} 语义")
class TaskBudgetProductionEntryCcTest {

    // ════════════════════════════════════════════════════════════════════
    // 1. VerifyChatController 入口（请求参数 path · OD-13 来源链首级）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("VerifyChatController: chat() 构造的 RunRequest.taskBudget 非 null（请求参数 150_000）")
    void verifyChatController_entry_injectsTaskBudgetFromRequestParam() {
        VerifyChatController controller = new VerifyChatController();
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        when(loop.run(any())).thenReturn(new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null));
        ReflectionTestUtils.setField(controller, "llmProviderFactory", mock(LlmProviderFactory.class));
        ReflectionTestUtils.setField(controller, "toolRegistry", mock(ToolRegistry.class));
        ReflectionTestUtils.setField(controller, "loopProvider", provider);
        // taskBudgetTotalConfigured 不设置（=0）→ 请求参数优先（OD-13 来源链首级）
        controller.chat(new VerifyChatController.VerifyChatRequest(
            "hello", null, null, null, null, 150_000, null, false));

        ArgumentCaptor<RunRequest> cap = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(cap.capture());
        assertThat(cap.getValue().taskBudget())
            .as("MISS-2: VerifyChatController 入口 taskBudget 必须非 null（请求参数 path）")
            .isNotNull();
        assertThat(cap.getValue().taskBudget().total())
            .as("请求参数优先于配置/默认值（OD-13 来源链: 请求参数→配置→默认值）")
            .isEqualTo(150_000);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. MainSessionBackgroundService 入口（配置/默认值 path）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MainSessionBackgroundService: runBackgroundQuery 构造的 RunRequest.taskBudget 非 null（默认值 path）")
    void mainSessionBackgroundService_entry_injectsTaskBudget() {
        MainSessionBackgroundService service = new MainSessionBackgroundService();
        SdkEventQueue sdkEventQueue = new SdkEventQueue();
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        when(loop.run(any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "taskFrameworkService", new TaskFrameworkService(sdkEventQueue));
        ReflectionTestUtils.setField(service, "loopProvider", provider);
        ReflectionTestUtils.setField(service, "sdkEventQueue", sdkEventQueue);
        ReflectionTestUtils.setField(service, "notificationQueue", new NotificationQueue());
        // 同步执行器：startBackgroundSession 内 loop.run 在调用线程执行（MainSessionBackgroundServiceTest 同式样）
        ReflectionTestUtils.setField(service, "backgroundExecutor", (Executor) Runnable::run);
        // taskBudgetTotalConfigured 不设置（=0）→ 走默认值

        service.startBackgroundSession(
            "sess-x", "bg", List.of(), null, "hi", "mock-fast", ProviderConfig.empty(), null);
        ArgumentCaptor<RunRequest> cap = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(cap.capture());
        assertThat(cap.getValue().taskBudget())
            .as("MISS-2: MainSessionBackgroundService 入口 taskBudget 必须非 null（默认值 path）")
            .isNotNull();
        assertThat(cap.getValue().taskBudget().total())
            .as("请求参数与配置均缺席 → 默认值 RunRequest.DEFAULT_TASK_BUDGET_TOTAL")
            .isEqualTo(RunRequest.DEFAULT_TASK_BUDGET_TOTAL);
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. ChatService 入口（配置/默认值 path）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ChatService: processUserMessage 构造的 RunRequest.taskBudget 非 null（默认值 path）")
    void chatService_entry_injectsTaskBudget() {
        ChatService service = new ChatService();
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(any())).thenReturn(mock(SessionRecord.class));
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        when(loop.run(any())).thenReturn(new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null));
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(mock(LlmProvider.class));
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "messageMapper", mock(MessageMapper.class));
        ReflectionTestUtils.setField(service, "modelMapper", mock(ModelMapper.class));
        ReflectionTestUtils.setField(service, "providerMapper", mock(ProviderMapper.class));
        ReflectionTestUtils.setField(service, "settingsMapper", mock(SettingsMapper.class));
        ReflectionTestUtils.setField(service, "llmProviderFactory", factory);
        ReflectionTestUtils.setField(service, "providerService", mock(ProviderService.class));
        ReflectionTestUtils.setField(service, "toolRegistry", mock(ToolRegistry.class));
        ReflectionTestUtils.setField(service, "loopProvider", provider);
        ReflectionTestUtils.setField(service, "toolCallMapper", mock(ToolCallMapper.class));
        // taskBudgetTotalConfigured 不设置（=0）→ 走默认值

        service.processUserMessage("sess-x", "msg-1",
            new SendMessageRequest("hi", null, null, null, null, null, null, null, null),
            mock(SimpMessagingTemplate.class));

        // 二分定位: 断言流程至少推进到 loop 接线点（若失败 → 前置 mock 依赖缺失）
        verify(sessionMapper).selectOneById("sess-x");
        verify(loop).setStreamContext(any(), any(), any());
        ArgumentCaptor<RunRequest> cap = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(cap.capture());
        assertThat(cap.getValue().taskBudget())
            .as("MISS-2: ChatService 入口 taskBudget 必须非 null（默认值 path）")
            .isNotNull();
        assertThat(cap.getValue().taskBudget().total())
            .as("请求参数与配置均缺席 → 默认值 RunRequest.DEFAULT_TASK_BUDGET_TOTAL")
            .isEqualTo(RunRequest.DEFAULT_TASK_BUDGET_TOTAL);
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. OD-13 来源链解析（请求参数 → 配置 → 默认值）+ 输入契约
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("来源链: 请求参数优先于配置（CC --task-budget main.tsx:982-988 等价通道）")
    void resolve_requestParam_precedesConfig() {
        assertThat(RunRequest.resolveTaskBudget(150_000, 50_000).total()).isEqualTo(150_000);
    }

    @Test
    @DisplayName("来源链: 无请求参数 + 配置>0 → 配置值（nexusai.agent.task-budget.total）")
    void resolve_configUsed_whenNoRequestParam() {
        assertThat(RunRequest.resolveTaskBudget(null, 50_000).total()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("来源链: 请求参数与配置均缺席（0/负）→ 默认值 DEFAULT_TASK_BUDGET_TOTAL（恒非 null）")
    void resolve_default_whenAbsent() {
        assertThat(RunRequest.resolveTaskBudget(null, 0).total())
            .isEqualTo(RunRequest.DEFAULT_TASK_BUDGET_TOTAL);
        assertThat(RunRequest.resolveTaskBudget(null, -1).total())
            .isEqualTo(RunRequest.DEFAULT_TASK_BUDGET_TOTAL);
    }

    @Test
    @DisplayName("请求参数非正整型 → IllegalArgumentException（CC main.tsx:984-986 must be a positive integer）")
    void resolve_nonPositiveRequestParam_rejects() {
        assertThatThrownBy(() -> RunRequest.resolveTaskBudget(0, 100_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> RunRequest.resolveTaskBudget(-1, 100_000))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("输入契约: TaskBudget 仅 {total} 组件（remaining 非输入 · loop 局部量 query.ts:291）")
    void taskBudgetRecord_inputContract_onlyTotal() {
        assertThat(TaskBudget.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("total");
    }
}
