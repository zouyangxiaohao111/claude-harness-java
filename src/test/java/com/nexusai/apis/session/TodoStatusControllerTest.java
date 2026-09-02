package com.nexusai.apis.session;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import com.nexusai.application.agent.tool.impl.TodoWriteTool.TodoItem;
import com.nexusai.apis.session.TodoStatusController.TodoSnapshotDto;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TodoStatusController 意图测试 · [todo-rest-stream] GET /api/v1/sessions/{sessionId}/todos。
 *
 * <p><b>WHY 本测试验证意图</b>：前端 todo 面板刷新页面 / 初始拉取走本 REST（推流实时 > REST
 * 快照兜底）。契约：
 * <ul>
 *   <li>主桶 todoKey = sessionId（写侧 resolveTodoKey 主线程回退键收敛，防 EV-TDV3-TV1-033）——
 *       若 REST 用别的键读主桶，前端刷新后看到空面板；</li>
 *   <li>无会话态 / 无主桶 → 200 + 空 todos 数组（空列表是合法状态 = CC allDone 清空，不用 404/204）；</li>
 *   <li>子 agent 桶不污染主桶（availableTodoKeys 暴露聚合信号，主桶保持 sessionId 隔离）；</li>
 *   <li>路由 /api/v1/sessions/{sessionId}/todos 接线，无 404 冲突。</li>
 * </ul>
 *
 * <p>纯单元测试（无 Spring 上下文）：直构 {@code new TodoStatusController(registry)}（registry 为真
 * SessionAgentStateRegistry），路由用 standalone MockMvc 验证。
 */
@DisplayName("[todo-rest-stream] TodoStatusController 会话 todo 状态 REST")
class TodoStatusControllerTest {

    private static final String SID = "sess-abc";

    @Test
    @DisplayName("主桶：register + setTodos(sessionId) → DTO 主桶正确（todoKey=sessionId, content/status/activeForm）")
    void returnsMainBucket() {
        // WHY: 写侧（TodoWriteTool execute Step5.5）以 sessionId 为主桶键；REST 必须以同一键读回，
        //   否则前端刷新后 todo 面板读空（EV-TDV3-TV1-033 复发路径）。
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = new AgentState("test");
        registry.register(SID, state);
        state.setTodos(SID, List.of(
            new TodoItem("A", TodoWriteTool.TodoStatus.PENDING, "Doing A"),
            new TodoItem("B", TodoWriteTool.TodoStatus.IN_PROGRESS, "Doing B")));

        TodoStatusController controller = new TodoStatusController(registry);
        TodoSnapshotDto dto = controller.getTodos(SID);

        assertThat(dto.todoKey()).as("todoKey 必须 = sessionId（主线程读侧收敛）").isEqualTo(SID);
        assertThat(dto.todos()).as("主桶 todos 数组必须含 2 项").hasSize(2);
        assertThat(dto.todos().get(0).get("content").asText()).isEqualTo("A");
        assertThat(dto.todos().get(1).get("status").asText()).isEqualTo("in_progress");
        assertThat(dto.todos().get(0).get("activeForm").asText()).isEqualTo("Doing A");
        assertThat(dto.updatedAt()).as("快照时刻必须为当前时间戳（>0）").isGreaterThan(0);
    }

    @Test
    @DisplayName("无会话态：registry 未注入 → 200 空 todos 数组 + availableTodoKeys 空（不用 404/204）")
    void noSessionStateReturnsEmpty() {
        // WHY: 空列表是合法状态（CC allDone 清空）——前端免特判；若 404/204 前端要分支处理。
        TodoStatusController controller = new TodoStatusController(null);   // registry 未注入
        TodoSnapshotDto dto = controller.getTodos(SID);

        assertThat(dto.todoKey()).isEqualTo(SID);
        assertThat(dto.todos()).as("registry null 时 todos 必须为空数组").isEmpty();
        assertThat(dto.availableTodoKeys()).as("registry null 时 availableTodoKeys 必须为空").isEmpty();
    }

    @Test
    @DisplayName("无活跃 run（registry 未命中）→ 200 空 todos 数组（空面板为正常初态）")
    void unregisteredSessionReturnsEmpty() {
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();   // 空 registry
        TodoStatusController controller = new TodoStatusController(registry);
        TodoSnapshotDto dto = controller.getTodos(SID);

        assertThat(dto.todos()).as("未注册会话 must be empty array").isEmpty();
        assertThat(dto.availableTodoKeys()).isEmpty();
    }

    @Test
    @DisplayName("仅子 agent 桶：主桶空 + availableTodoKeys 暴露 agentId（聚合信号，主桶不受污染）")
    void subagentBucketDoesNotPolluteMainBucket() {
        // WHY: 子 agent TodoWrite 经 resolver 落主 AgentState.todos（key=agentId UUID）——
        //   主桶读 sessionId 必须为空（不被子 agent 桶污染），availableTodoKeys 暴露子 agent 桶
        //   供前端未来聚合（agentId 非隐私数据）。
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = new AgentState("test");
        registry.register(SID, state);
        state.setTodos("agent-uuid-0001", List.of(new TodoItem("sub", TodoWriteTool.TodoStatus.PENDING, "Doing sub")));

        TodoStatusController controller = new TodoStatusController(registry);
        TodoSnapshotDto dto = controller.getTodos(SID);

        assertThat(dto.todos()).as("主桶（sessionId）必须为空（子 agent 桶不污染主桶）").isEmpty();
        assertThat(dto.availableTodoKeys())
            .as("availableTodoKeys 必须含子 agent 桶键（聚合信号）")
            .containsExactly("agent-uuid-0001");
    }

    @Test
    @DisplayName("路由：GET /api/v1/sessions/{sid}/todos 接线返回 200 + JSON 载荷")
    void routeIsWired() throws Exception {
        // WHY: 前端按 /api/v1/sessions/{sessionId}/todos 拉取——路由未接线/被其它 /api/v1/sessions/**
        //   拦截会 404，前端面板初始拉取失败。
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = new AgentState("test");
        registry.register(SID, state);
        state.setTodos(SID, List.of(new TodoItem("A", TodoWriteTool.TodoStatus.IN_PROGRESS, "Doing A")));

        TodoStatusController controller = new TodoStatusController(registry);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/todos", SID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.todoKey").value(SID))
            .andExpect(jsonPath("$.todos[0].content").value("A"))
            .andExpect(jsonPath("$.todos[0].status").value("in_progress"))
            .andExpect(jsonPath("$.availableTodoKeys[0]").value(SID));
    }

    // ═══════════════════════ [R3 持久升级] DB-first 读取（sessions.todos 列 V43）═══════════════════════

    @Test
    @DisplayName("[R3] sessionMapper 注入 + DB JSON → GET /todos 主桶读 DB（DB 优先）")
    void dbBucketsTakePriority() {
        // WHY: sessions.todos 列（V43）是跨 send/重启真源（TodoWrite Step5.6 写）——DB 列非空时
        //   REST 必须读 DB 而非 V1 内存（否则重开面板读最近一次 send 快照，持久化失效）。
        SessionMapper sessionMapper = mock(SessionMapper.class);
        SessionRecord s = new SessionRecord();
        s.setId(SID);
        s.setTodos("{\"sess-abc\":[{\"content\":\"DB-A\",\"status\":\"in_progress\",\"activeForm\":\"Doing DB-A\"}]}");
        when(sessionMapper.selectOneById(SID)).thenReturn(s);

        TodoStatusController controller = new TodoStatusController(new SessionAgentStateRegistry());
        controller.setSessionMapper(sessionMapper);
        TodoSnapshotDto dto = controller.getTodos(SID);

        assertThat(dto.todos()).as("DB 列非空时主桶必须读 DB（DB-first）").hasSize(1);
        assertThat(dto.todos().get(0).get("content").asText()).isEqualTo("DB-A");
        assertThat(dto.todos().get(0).get("status").asText()).isEqualTo("in_progress");
    }

    @Test
    @DisplayName("[R3] DB 列 null → 回退 AgentState.todos（V1 兜底不回归）")
    void dbNullFallsBackToAgentState() {
        // WHY: 旧会话从未 TodoWrite → todos 列 null → 回退 V1 AgentState.todos（最近一次 send
        //   内存快照），覆盖 V1 语义；若 DB null 时直接返回空，V1 活跃 run 的 todo 面板读空。
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(SID)).thenReturn(new SessionRecord());   // todos null

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = new AgentState("test");
        registry.register(SID, state);
        state.setTodos(SID, List.of(new TodoItem("V1-A", TodoWriteTool.TodoStatus.PENDING, "Doing V1-A")));

        TodoStatusController controller = new TodoStatusController(registry);
        controller.setSessionMapper(sessionMapper);
        TodoSnapshotDto dto = controller.getTodos(SID);

        assertThat(dto.todos()).as("DB 列 null → 回退 V1 AgentState 桶").hasSize(1);
        assertThat(dto.todos().get(0).get("content").asText()).isEqualTo("V1-A");
    }

    @Test
    @DisplayName("[R3] DB 仅子 agent 桶 → 主桶空 + availableTodoKeys 含子键")
    void dbSubagentBucketOnly() {
        // WHY: DB 侧子 agent 桶（agentId UUID）不污染主桶（sessionId）——主桶空，availableTodoKeys
        //   暴露子 agent 桶供前端未来聚合（与 V1 子桶隔离语义一致）。
        SessionMapper sessionMapper = mock(SessionMapper.class);
        SessionRecord s = new SessionRecord();
        s.setId(SID);
        s.setTodos("{\"agent-uuid-0001\":[{\"content\":\"sub\",\"status\":\"pending\",\"activeForm\":\"Doing sub\"}]}");
        when(sessionMapper.selectOneById(SID)).thenReturn(s);

        TodoStatusController controller = new TodoStatusController(new SessionAgentStateRegistry());
        controller.setSessionMapper(sessionMapper);
        TodoSnapshotDto dto = controller.getTodos(SID);

        assertThat(dto.todos()).as("DB 仅子 agent 桶 → 主桶（sessionId）必须空").isEmpty();
        assertThat(dto.availableTodoKeys()).as("availableTodoKeys 必须含 DB 子 agent 桶键").containsExactly("agent-uuid-0001");
    }
}
