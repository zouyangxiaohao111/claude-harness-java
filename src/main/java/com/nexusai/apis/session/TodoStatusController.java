package com.nexusai.apis.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import com.nexusai.application.agent.tool.impl.TodoWriteTool.TodoItem;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>会话 Todo 状态查询 REST</b> · GET /api/v1/sessions/{sessionId}/todos（待前端对接 · todo 展示）。
 *
 * <p><b>语义（R3 DB-first）</b>：优先读 sessions.todos 列（V43，DB 真源，跨 send/重启存活，
 * 由 {@link TodoWriteTool} execute Step5.6 写入）；DB 空 / sessionMapper 未注入 → 回退
 * {@link SessionAgentStateRegistry} 持有的主会话 AgentState.todos（V1 内存桶，Step5.5 写入，
 * 与推流 {@code TodoUpdateEvent} 同源）——供前端刷新页面 / 初始拉取。
 *
 * <p><b>读侧键收敛</b>：主 todoKey = sessionId（写侧 resolveTodoKey 主线程回退键），防
 * EV-TDV3-TV1-033 复发（主线程 todoKey 与读侧回退键不一致）。{@code availableTodoKeys} 暴露
 * 全部桶键（含子 agent UUID 桶，供前端未来聚合——agentId 非隐私数据，前端仅渲染主桶即可）。
 *
 * <p><b>空态语义</b>：无会话态（registry 未注入 / 未命中）或主桶无 → 一律 200 + 空 todos 数组
 * （空列表是合法状态 = CC allDone 清空，不用 404/204 避免前端特判；SkillImprovementController
 * 的 204 先例在此不适用）。
 *
 * <p><b>[R3 持久升级]</b>：sessions.todos 列（V43）+ TodoWriteTool Step5.6 双写 DB + doRun
 * 回读注入 + SessionDto.todos 持久读已落地，本端点 DB-first（DB 空→V1 AgentState 兜底）。
 *
 * <p>路由与既有 /api/v1/sessions/** 无冲突（ChatController /{sessionId}、SessionFileController
 * /files、SessionToolsController /tools、PermissionRulesController POST /permission-retry）。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/todos")
public class TodoStatusController {

    private static final Logger log = LoggerFactory.getLogger(TodoStatusController.class);

    /** 会话 AgentState 注册表 · 构造注入（@Autowired(required=false)：非 Spring 直构测试传 null 降级空桶）。 */
    private final SessionAgentStateRegistry registry;

    /**
     * [R3 持久升级] 会话级 SessionMapper · GET /todos DB-first 读取 sessions.todos 列（V43，
     * 跨 send/重启会话 todo 真源）。@Autowired(required=false)：非 Spring 直构测试缺省 null →
     * 跳过 DB 读回退 V1 AgentState（存量测试行为不变）。
     */
    private SessionMapper sessionMapper;

    @Autowired(required = false)
    public TodoStatusController(SessionAgentStateRegistry registry) {
        this.registry = registry;
    }

    /** [R3 持久升级] setter 注入 SessionMapper（测试直构注入用）。 */
    public void setSessionMapper(SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    /**
     * 会话 todo 状态 · GET /api/v1/sessions/{sessionId}/todos。
     *
     * @param sessionId 会话 ID（short 形态 sess-xxx；SessionAgentStateRegistry.get(String) 直键）
     * @return 200 TodoSnapshotDto（无态/无主桶 → todos 空数组 + availableTodoKeys 空）
     */
    @GetMapping
    public TodoSnapshotDto getTodos(@PathVariable String sessionId) {
        // [R3] DB-first：优先读 sessions.todos 列（DB 真源，跨 send/重启存活）；DB 空 / 异常 →
        // 回退 V1 AgentState.todos（registry null → 空桶）。TodoSnapshotDto 形状不变，仅数据源换 DB。
        Map<String, List<TodoItem>> buckets = readBuckets(sessionId);
        // 主桶 todoKey = sessionId（与写侧 resolveTodoKey 主线程回退键收敛，防 EV-TDV3-TV1-033）
        List<TodoItem> main = buckets.getOrDefault(sessionId, List.of());
        List<String> availableKeys = new ArrayList<>(buckets.keySet());
        availableKeys.sort(String::compareTo);
        TodoSnapshotDto dto = new TodoSnapshotDto(
            sessionId, TodoWriteTool.todoListToArray(main), System.currentTimeMillis(), availableKeys);
        if (log.isDebugEnabled()) {
            log.debug("TodoStatusController.getTodos: sessionId={} buckets={} mainItems={} availableKeys={}",
                sessionId, buckets.size(), main.size(), availableKeys);
        }
        return dto;
    }

    /**
     * 读取 todo 桶 · [R3] DB-first，AgentState V1 兜底。
     *
     * <p>sessionMapper 注入且 selectOneById 命中且 todos 列解析非空 → DB 桶（DB 真源）；否则
     * 回退 {@code registry.get(sessionId).todos()}（V1 内存兜底，registry null → 空桶）。查询异常
     * warn 降级不抛（前端刷新面板不应因 DB 故障 500）。
     */
    private Map<String, List<TodoItem>> readBuckets(String sessionId) {
        if (sessionMapper != null) {
            try {
                SessionRecord s = sessionMapper.selectOneById(sessionId);
                if (s != null) {
                    Map<String, List<TodoItem>> parsed = TodoWriteTool.todosJsonToMap(s.getTodos());
                    if (!parsed.isEmpty()) {
                        return parsed;
                    }
                }
            } catch (Exception e) {
                log.warn("TodoStatusController DB 读取降级（回退 AgentState V1）: sessionId={} err={}",
                    sessionId, e.getMessage());
            }
        }
        AgentState state = registry != null ? registry.get(sessionId) : null;
        return state != null ? state.todos() : Map.of();
    }

    /**
     * Todo 状态快照 DTO · todoKey（主桶键）+ todos 数组（status 已小写）+ updatedAt（读侧快照时刻）
     * + availableTodoKeys（全部桶键排序列表，暴露子 agent 桶供前端未来聚合）。
     */
    public record TodoSnapshotDto(
        String todoKey,
        JsonNode todos,
        long updatedAt,
        List<String> availableTodoKeys
    ) {
    }
}
