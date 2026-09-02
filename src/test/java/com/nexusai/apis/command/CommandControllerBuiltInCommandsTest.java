package com.nexusai.apis.command;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.command.EffortCommand;
import com.nexusai.application.agent.lsp.PromptCacheBreakDetection;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.subagent.ResumeAgentResult;
import com.nexusai.application.agent.subagent.ResumeService;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.command.CommandService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DEC-9 CommandController 内置命令端点测试（plain JUnit，mock CommandService + SkillRegistry，
 * MockMvcBuilders.standaloneSetup 风格）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>GET /api/command/builtins 返回 10 条内置命令</b>——React 命令源（对齐 CC commands.ts:258
 *       COMMANDS 子集），且 BuiltInCommandDto 携带 type（CommandDto 缺 type gap 的修复）——React 需
 *       type 区分 prompt/local/local-jsx 渲染/触发。若端点缺失/未接线第 5 源 → 本测试 fail。</li>
 *   <li><b>POST /builtins/{name}/execute 按 name/alias 解析返回元数据</b>——薄触发语义（DEC-9 明确
 *       「不复制 CC TUI 分发逻辑」，React 拿到 type/name 自行触发 web 行为）。'clear' 精确名命中 → 200
 *       + type='local'；未知名 → 404（REST 语义，区别于 CC getCommand 抛 ReferenceError，G-2 已登记）。</li>
 *   <li><b>路由 literal-priority 锁定</b>——GET {@code /builtins} 字面路径必须优先于 {@code /{id}}
 *       路径变量（Spring literal > path variable）：{@code GET /api/command/builtins} 命中内置列表端点
 *       而非 getById；{@code GET /api/command/random-id} 仍走 getById（404 语义不变）。若未来 {id} 语义
 *       漂移吞掉 /builtins → 本测试 fail。</li>
 * </ol>
 */
class CommandControllerBuiltInCommandsTest {

    private CommandController controller;
    private CommandService commandService;
    private SkillRegistry skillRegistry;
    private ResumeService resumeService;
    private SessionAgentStateRegistry sessionAgentStateRegistry;
    private TaskFrameworkService taskFrameworkService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new CommandController();
        commandService = mock(CommandService.class);
        skillRegistry = mock(SkillRegistry.class);
        resumeService = mock(ResumeService.class);
        sessionAgentStateRegistry = mock(SessionAgentStateRegistry.class);
        taskFrameworkService = mock(TaskFrameworkService.class);
        // 注入私有 @Autowired 字段（plain JUnit 无 Spring 容器）
        ReflectionTestUtils.setField(controller, "commandService", commandService);
        ReflectionTestUtils.setField(controller, "skillRegistry", skillRegistry);
        ReflectionTestUtils.setField(controller, "resumeService", resumeService);
        ReflectionTestUtils.setField(controller, "sessionAgentStateRegistry", sessionAgentStateRegistry);
        ReflectionTestUtils.setField(controller, "taskFrameworkService", taskFrameworkService);
        // DEC-8 一致性过滤：内置命令 availability=null 恒放行（client-env 过滤对内置命令恒真）
        when(skillRegistry.filterByClientEnv(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        // [RES-④] resume 测试设置了 MDC sessionId，清理避免线程复用泄漏
        RequestContext.clear();
    }

    @Test
    @DisplayName("GET /api/command/builtins 返回 11 条内置命令 + type 字段（React 命令源，CC commands.ts:258 子集 + effort）")
    void listBuiltins_returnsElevenWithType() throws Exception {
        mockMvc.perform(get("/api/command/builtins"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(11))
            .andExpect(jsonPath("$[0].name").value("clear"))
            // type gap 修复：BuiltInCommandDto 携带 type（CommandDto 无 type 字段）
            .andExpect(jsonPath("$[0].type").value("local"))
            .andExpect(jsonPath("$[0].source").value("BUILTIN"))
            .andExpect(jsonPath("$[3].name").value("help"))
            // output-style 隐藏命令仍列出（React 自行过滤 isHidden）
            .andExpect(jsonPath("$[7].name").value("output-style"))
            .andExpect(jsonPath("$[7].isHidden").value(true))
            // resume aliases 携带（React 触发 alias 解析）
            .andExpect(jsonPath("$[8].name").value("resume"))
            .andExpect(jsonPath("$[8].aliases[0]").value("continue"))
            // [E2] effort 命令注册（type='local-jsx' + argumentHint 对齐 CC effort/index.ts:4-13）
            .andExpect(jsonPath("$[10].name").value("effort"))
            .andExpect(jsonPath("$[10].type").value("local-jsx"))
            .andExpect(jsonPath("$[10].argumentHint").value("[low|medium|high|max|auto]"));
    }

    @Test
    @DisplayName("POST /api/command/builtins/clear/execute 200 + type='local' 元数据（薄触发）")
    void executeBuiltin_clear_ok() throws Exception {
        mockMvc.perform(post("/api/command/builtins/clear/execute"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("clear"))
            .andExpect(jsonPath("$.type").value("local"))
            .andExpect(jsonPath("$.source").value("BUILTIN"))
            .andExpect(jsonPath("$.builtin").doesNotExist()); // BuiltInCommandDto 无 builtin 字段（React 不需要）
    }

    @Test
    @DisplayName("POST /api/command/builtins/{name}/execute 支持 alias（continue → resume）+ 前导 '/' 剥除")
    void executeBuiltin_aliasAndSlash() throws Exception {
        // [RES-④] continue 是 resume 的 alias（BuiltInCommands aliases），命中 resume → 走真实后端
        //   分支，无请求体 → 400（resume 需 agentId）。这验证 alias 解析正确导向 resume 分支。
        mockMvc.perform(post("/api/command/builtins/continue/execute"))
            .andExpect(status().isBadRequest());
        // 前导 '/' 剥除（REST 路径变量含 '/' 需编码 %2F，直接传未编码斜杠在路径中不合法 → 此处验证
        // findByName 的 '/' 剥除语义已在 BuiltInCommandsTest 覆盖；端点经 name 变量透传）。
        // init 非 resume 命令 → 仍返回元数据 DTO（薄触发不变）
        mockMvc.perform(post("/api/command/builtins/init/execute"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("init"))
            .andExpect(jsonPath("$.type").value("prompt"));
    }

    @Test
    @DisplayName("POST 未知名内置命令 → 404（REST 语义，区别于 CC getCommand 抛 ReferenceError，G-2 已登记）")
    void executeBuiltin_unknown_404() throws Exception {
        mockMvc.perform(post("/api/command/builtins/nope/execute"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("路由 literal-priority：GET /api/command/builtins 命中内置端点（而非 /{id}）")
    void route_literalBuiltins_winsOverPathVariable() throws Exception {
        // 若 Spring 把 /builtins 匹配到 getById(id="builtins")，则返回 404（getById mock 抛未找到）
        // —— 本断言 200 + 11 条证明 literal 优先（Spring literal > path variable）。
        mockMvc.perform(get("/api/command/builtins"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(11));
    }

    @Test
    @DisplayName("路由回归：GET /api/command/random-id 仍走 getById 404 语义不变")
    void route_randomId_stillGoesThroughGetById() throws Exception {
        when(commandService.getById("random-id"))
            .thenThrow(new NotFoundException("Command random-id not found"));
        mockMvc.perform(get("/api/command/random-id"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[RES-④] POST /builtins/resume/execute + {agentId,prompt} → ResumeAgentResult（真实后端重建）")
    void executeBuiltin_resume_returnsResumeAgentResult() throws Exception {
        // WHY: 用户拍板 resume 走后端真实重建（CC resumeAgentBackground），前端消费
        // {agentId, description, outputFile} 轮询任务输出。若返回元数据 DTO 而非 ResumeAgentResult
        // → 前端无法 poll → resume 功能失效。
        RequestContext.setSession("00000000-0000-0000-0000-00000000000b");
        // [R-A6 · A-6] web resume 链路需主会话 AgentState.currentModel()（对齐 CC resumeAgent.ts:131
        //   options.mainLoopModel）：mock registry 返已注册 AgentState（LlmAgentLoop.run() 模型解析后
        //   setCurrentModel + register 的真实形状）。缺此 stub → registry.get 返 null → executeResume
        //   fail loud 500，无法验证 resume 主路径。
        AgentState mainState = new AgentState("test-system-prompt");
        mainState.setCurrentModel("claude-sonnet-4-6");
        when(sessionAgentStateRegistry.get(org.mockito.ArgumentMatchers.any())).thenReturn(mainState);
        // [R2-DELETE] resumeAgentBackground 已对齐 CC 直接传参：新增第 5 参 parentContentReplacementState
        //   （web 端点传 null，受控残留 = CC :1006 feature off）。
        String expectedOutputFile = com.nexusai.application.agent.tasks.BackgroundTaskRunner.taskOutputPath("agent-x");
        when(resumeService.resumeAgentBackground(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("continue"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(new ResumeAgentResult("agent-x", "My Agent", expectedOutputFile));

        mockMvc.perform(post("/api/command/builtins/resume/execute")
                .contentType("application/json")
                .content("{\"agentId\":\"00000000-0000-0000-0000-00000000000a\",\"prompt\":\"continue\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agentId").value("agent-x"))
            .andExpect(jsonPath("$.description").value("My Agent"))
            .andExpect(jsonPath("$.outputFile").value(expectedOutputFile));
    }

    @Test
    @DisplayName("[RES-④] resume 请求体缺 agentId → 400 ValidationException")
    void executeBuiltin_resume_missingAgentId_400() throws Exception {
        mockMvc.perform(post("/api/command/builtins/resume/execute")
                .contentType("application/json")
                .content("{\"prompt\":\"continue\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[RES-④] resume 无 MDC sessionId（无会话上下文）→ 500")
    void executeBuiltin_resume_noSession_500() throws Exception {
        RequestContext.clear(); // 确保无 MDC sessionId
        mockMvc.perform(post("/api/command/builtins/resume/execute")
                .contentType("application/json")
                .content("{\"agentId\":\"00000000-0000-0000-0000-00000000000a\",\"prompt\":\"continue\"}"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("[R-A6 · A-6] resume 主会话 AgentState 未注册（currentModel 不可得）→ fail loud 500（WF-G-UN-1）")
    void executeBuiltin_resume_unregisteredAgentState_500() throws Exception {
        // WHY: web resume 链依赖主会话 AgentState.currentModel()（对齐 CC resumeAgent.ts:131
        //       options.mainLoopModel，fork 父提示重建 + 父模型继承）。mock registry.get() 默认返 null
        //       （AgentState 未注册）→ executeResume 必须 fail loud 抛错而非用假字节续跑（A-6 决策）。
        //       若该门禁缺失，AgentState 未注册时 resume 会静默降级 → fork 父提示无法重建。
        RequestContext.setSession("00000000-0000-0000-0000-00000000000e");
        mockMvc.perform(post("/api/command/builtins/resume/execute")
                .contentType("application/json")
                .content("{\"agentId\":\"00000000-0000-0000-0000-00000000000a\",\"prompt\":\"continue\"}"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("[OPD-TP-19] /clear preservedAgentIds：后台化任务 agentId 保留，前台化任务不保留（CC conversation.ts:93）")
    void executeBuiltin_clear_preservesBackgroundedAgentSkills() throws Exception {
        // WHY: /clear 必须让后台化会话（Ctrl+B / POST background）不受影响 —— 其 agentId 进 preserved 集合，
        //   invokedSkills 只清主会话/null-agent（CC conversation.ts:93-106 + state.ts:1543-1555）。若
        //   后台化 agentId 未保留，/clear 会把后台会话已加载的 skill 内容清掉，后续继续查询时模型失忆。
        RequestContext.setSession("00000000-0000-0000-0000-00000000000c");
        AgentState state = new AgentState("test-system-prompt");
        when(sessionAgentStateRegistry.get(org.mockito.ArgumentMatchers.any())).thenReturn(state);
        java.util.UUID bgAgent = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        java.util.UUID fgAgent = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        // 后台化主会话任务（isBackgrounded=true，agentId=taskId UUID 视图）
        BackgroundTask bgTask = new BackgroundTask("s12345678", TaskType.LOCAL_AGENT,
            BackgroundTaskStatus.RUNNING, "bg", null, System.currentTimeMillis(), null, null,
            "/tmp/nexusai-sessions/sess-x/tasks/s12345678.output", 0L, false, bgAgent, true);
        // 前台化任务（isBackgrounded=false）→ 应被 shouldKillTask 排除
        BackgroundTask fgTask = new BackgroundTask("a12345678", TaskType.LOCAL_AGENT,
            BackgroundTaskStatus.RUNNING, "fg", null, System.currentTimeMillis(), null, null,
            "/tmp/nexusai-sessions/sess-x/tasks/a12345678.output", 0L, false, fgAgent, false);
        when(taskFrameworkService.listAll()).thenReturn(java.util.List.of(bgTask, fgTask));
        // 预置 invokedSkills：后台化 agent + 前台化 agent + 主会话（null-agent）各 1 条
        state.addInvokedSkill("bg-skill", "/s/bg.md", "c", bgAgent);
        state.addInvokedSkill("fg-skill", "/s/fg.md", "c", fgAgent);
        state.addInvokedSkill("main-skill", "/s/main.md", "c", null);

        mockMvc.perform(post("/api/command/builtins/clear/execute"))
            .andExpect(status().isOk());

        // 后台化 agent 的 skill 跨 /clear 存活；前台化 + null-agent（主会话）skill 被清
        // （CC state.ts:1551：保留 agentId∈preserved，删 null-agent 与未保留条目）
        assertThat(state.getInvokedSkillsForAgent(bgAgent)).hasSize(1);
        assertThat(state.getInvokedSkillsForAgent(fgAgent)).isEmpty();
        assertThat(state.getInvokedSkillsForAgent(null)).isEmpty();
    }

    @Test
    @DisplayName("[IMP-SP2-08] /clear 无 preserved → resetPromptCacheBreakDetection 清空 PREVIOUS（CC caches.ts:63）")
    void executeBuiltin_clear_resetsPromptCacheBreakDetection() throws Exception {
        // WHY: CC clearSessionCaches（caches.ts:47-63）在 !hasPreserved（无后台化任务保留）时调
        //       resetPromptCacheBreakDetection() 清空 previousStateBySource（promptCacheBreakDetection.ts:704-706）。
        //       Java /clear 分支此前不接线 → feature 开后子 agent 的 PREVIOUS 残留跨 /clear 存活。
        //       本用例先经 detector record 一条，断言 /clear 后 getTrackedSourceCount()==0。
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(events -> {}); // enabled=true
        detector.recordPromptState(new PromptCacheBreakDetection.PromptStateSnapshot(
            java.util.List.of(java.util.Map.of("type", "text", "text", "sys")),
            java.util.List.of(java.util.Map.of("name", "toolA")),
            "agent:default", "claude-sonnet-4-6", "agent-1",
            false, "", java.util.List.of(), false, false, false, null, null));
        assertThat(detector.getTrackedSourceCount()).as("record 后 PREVIOUS 有 1 条").isEqualTo(1);
        // 注入 detector（懒建字段直接注入实例；PREVIOUS 为类级静态 Map，任意实例共享）
        ReflectionTestUtils.setField(controller, "promptCacheBreak", detector);

        mockMvc.perform(post("/api/command/builtins/clear/execute"))
            .andExpect(status().isOk());

        assertThat(detector.getTrackedSourceCount())
            .as("/clear 无 preserved → resetPromptCacheBreakDetection 清空 PREVIOUS（CC caches.ts:63）")
            .isZero();
    }

    @Test
    @DisplayName("[IMP-SP2-08] /clear 有 preserved 后台化任务 → 不 reset PREVIOUS（CC caches.ts:63 !hasPreserved 门控）")
    void executeBuiltin_clear_withPreserved_skipsReset() throws Exception {
        // WHY: preserved 非空（后台化任务存在）时 hasPreserved=true → CC caches.ts:63 跳过 reset。
        //       若 Java 无条件 reset，后台化子 agent 的 cache-break tracking 会被误清（跨 /clear
        //       续跑时首次 record 无 prev 参照 → 缓存 break 误报）。
        RequestContext.setSession("00000000-0000-0000-0000-00000000000d");
        AgentState state = new AgentState("test-system-prompt");
        when(sessionAgentStateRegistry.get(org.mockito.ArgumentMatchers.any())).thenReturn(state);
        java.util.UUID bgAgent = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        BackgroundTask bgTask = new BackgroundTask("s12345678", TaskType.LOCAL_AGENT,
            BackgroundTaskStatus.RUNNING, "bg", null, System.currentTimeMillis(), null, null,
            "/tmp/nexusai-sessions/sess-x/tasks/s12345678.output", 0L, false, bgAgent, true);
        when(taskFrameworkService.listAll()).thenReturn(java.util.List.of(bgTask));

        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(events -> {});
        detector.recordPromptState(new PromptCacheBreakDetection.PromptStateSnapshot(
            java.util.List.of(java.util.Map.of("type", "text", "text", "sys")),
            java.util.List.of(java.util.Map.of("name", "toolA")),
            "agent:default", "claude-sonnet-4-6", "agent-1",
            false, "", java.util.List.of(), false, false, false, null, null));
        assertThat(detector.getTrackedSourceCount()).isEqualTo(1);
        ReflectionTestUtils.setField(controller, "promptCacheBreak", detector);

        mockMvc.perform(post("/api/command/builtins/clear/execute"))
            .andExpect(status().isOk());

        assertThat(detector.getTrackedSourceCount())
            .as("preserved 非空 → !hasPreserved 门控关 → PREVIOUS 保留（CC caches.ts:63）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("[E2] POST /builtins/effort/execute {args:low} → 写当前会话 effort_level + 会话 effortValue + 成功消息")
    void executeBuiltin_effort_setLow_persistsAndSetsSession() throws Exception {
        // WHY: R2 会话级 —— /effort 对齐 CC effort.tsx setEffortValue，写当前会话 sessions.effort_level
        //   （V31 列，经 SessionMapper）+ 会话 AgentState.effortValue（ApplyEffortAndClose 语义），
        //   返回 "Set effort level to X: desc"（getEffortValueDescription）。不再写全局 settings.effortLevel。
        SessionMapper sessionMapper = mock(SessionMapper.class);
        SessionRecord session = new SessionRecord();
        when(sessionMapper.selectOneById(any())).thenReturn(session);
        EffortCommand effortCommand = new EffortCommand(sessionMapper, sessionAgentStateRegistry);
        ReflectionTestUtils.setField(controller, "effortCommand", effortCommand);
        RequestContext.setSession("00000000-0000-0000-0000-00000000000f");
        AgentState state = new AgentState("test-system-prompt");
        when(sessionAgentStateRegistry.get(any())).thenReturn(state);

        mockMvc.perform(post("/api/command/builtins/effort/execute")
                .contentType("application/json")
                .content("{\"args\":\"low\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value(
                "Set effort level to low: Quick, straightforward implementation with minimal overhead"))
            .andExpect(jsonPath("$.effortValue").value("low"));

        verify(sessionMapper).update(session);
        assertThat(session.getEffortLevel()).isEqualTo("low");
        assertThat(state.effortValue()).isEqualTo("low");
    }

    @Test
    @DisplayName("[E2] POST /builtins/effort/execute 缺省 args → 显示当前档位（无写操作）")
    void executeBuiltin_effort_noArgs_showsCurrent() throws Exception {
        // WHY: CC effort.tsx:177-179 无参/current/status → ShowCurrentEffort（不写会话、不 setAppState）。
        //   会话 effortValue 未设置 + env 缺失 → "Effort level: auto (currently high)"（默认 high）。
        SessionMapper sessionMapper = mock(SessionMapper.class);
        EffortCommand effortCommand = new EffortCommand(sessionMapper, sessionAgentStateRegistry);
        ReflectionTestUtils.setField(controller, "effortCommand", effortCommand);

        mockMvc.perform(post("/api/command/builtins/effort/execute"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Effort level: auto (currently high)"));
    }

    @Test
    @DisplayName("[E2] EffortCommand 未接线 → POST /builtins/effort/execute fail loud 500")
    void executeBuiltin_effort_notWired_500() throws Exception {
        // WHY: effort 后端执行依赖 EffortCommand 组件；未接线（plain JUnit 未注入）→ fail loud
        //   （对齐 executeResume 的 resumeService 未接线 → 500 模式），不静默降级。
        mockMvc.perform(post("/api/command/builtins/effort/execute"))
            .andExpect(status().isInternalServerError());
    }
}
