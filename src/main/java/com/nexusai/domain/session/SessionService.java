package com.nexusai.domain.session;

import com.alibaba.fastjson2.JSON;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.SkillImprovementSuggestionStore;
import com.nexusai.application.agent.subagent.AgentDefinitionRegistry;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.application.chat.ChatService;
import com.nexusai.common.SessionKeys;
import com.nexusai.model.provider.dto.ModelTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.nexusai.model.session.dto.SessionCreateRequest;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionGroup;
import com.nexusai.model.session.dto.SessionUpdateRequest;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionFileMapper;

/**
 * Session 业务逻辑：
 * - list：返回全部会话
 * - getById：单查
 * - create：生成 ID + 默认 time/group/messageCount
 * - update：PATCH 语义（局部更新）
 * - delete：级联删 messages + session_files（FK 已配但显式删更安全）
 */
@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    @Autowired private SessionMapper sessionMapper;
    @Autowired private MessageMapper messageMapper;
    @Autowired private SessionFileMapper sessionFileMapper;
    // Phase 4: 注入 ChatService — session 删除前调 closeSession 触发 ScheduleService.cleanupBySession
    //   SESSION-scope 调度任务在 session 关闭时同步清理 (对齐 CC cronScheduler.ts:329 removeSessionCronTasks)
    @Autowired(required = false) private ChatService chatService;
    // DEL-SH-01: 注入 SkillImprovementSuggestionStore — session 删除时清理该 session 的 suggestion 条目
    //   (对齐 CC AppState.skillImprovement.suggestion 随会话消亡; best-effort, required=false 防循环依赖/缺失)
    @Autowired(required = false) private SkillImprovementSuggestionStore suggestionStore;
    // [S4] 注入 SimpMessagingTemplate —— delete 时先 cancel 在飞 turn（对齐 CC onCancel abort('user-cancel')
    //   REPL.tsx:2147；防止已删会话 in-flight 继续推 STOMP 到已删 topic）。best-effort，required=false。
    @Autowired(required = false) private SimpMessagingTemplate wsTemplate;
    // [A3] 注入 TeamHelpers — session 删除时清理该会话创建的孤儿 team 目录（会话桶 sessionCreatedTeams）
    //   (对齐 CC 进程退出 cleanupSessionTeams; best-effort, required=false 防循环依赖/缺失,
    //   domain.session → application.agent.team 单向无环)
    @Autowired(required = false) private TeamHelpers teamHelpers;
    // [A2] 注入 SpawnInProcess —— delete 时经 registry().cleanupSession  abort 本会话 in-process teammate
    //   （对齐 CC spawnInProcess.ts:184-188 registerCleanup abort；Java 无进程退出，会话删除即触发点）。
    //   best-effort，required=false。
    @Autowired(required = false) private SpawnInProcess spawnInProcess;
    // [cache-hit-fix B] 注入 SessionGitStatusRegistry — session 删除时释放该会话 git status 快照
    //   （对齐 CC 进程随会话结束退出无泄漏；Java 常驻 JVM，会话删除时由外层 evict 防注册表无界增长）。
    //   best-effort，required=false（null → 跳过，不阻塞删除主流程）。
    @Autowired(required = false) private com.nexusai.application.agent.prompt.SessionGitStatusRegistry sessionGitStatusRegistry;
    // [B3] 注入 SubagentTool — mainThreadAgent 写侧校验数据源（registryForSession → findAgent）。
    //   best-effort，required=false：plain JUnit 无容器 → null → 走原逻辑不校验（fail-open）；
    //   生产注入 → 未命中 agentType fail-loud 400（对齐 permissionMode isSettable 范式）。
    //   domain.session → application.agent.tool.impl 单向依赖（与 teamHelpers/spawnInProcess 同向）。
    //   [REWORK 2026-09-04 循环依赖修复] @Lazy 破环：SubagentTool.setAvailableTools(List<Tool>) 注入
    //   全量 Tool bean 含 SendMessageTool，而 SendMessageTool @Autowired SessionService → 若此处直接注入
    //   SubagentTool 即形成 SessionService→SubagentTool→SendMessageTool→SessionService 环，Spring Boot 3.5
    //   默认禁循环依赖 → APPLICATION FAILED TO START。@Lazy 注入懒代理，首次使用才解析真 bean，环已破。
    @Autowired(required = false)
    @Lazy
    private SubagentTool subagentTool;

    public List<SessionDto> list() {
        List<SessionRecord> all = sessionMapper.selectAll();
        List<SessionDto> result = new ArrayList<>(all.size());
        for (SessionRecord s : all) {
            result.add(toDto(s));
        }
        return result;
    }

    public SessionDto getById(String id) {
        SessionRecord s = sessionMapper.selectOneById(id);
        if (s == null) throw new NotFoundException("Session " + id + " not found");
        return toDto(s);
    }

    public SessionDto create(SessionCreateRequest req) {
        // 业务校验：title 与 modelName 至少传一个
        boolean hasTitle = req.title() != null && !req.title().isBlank();
        boolean hasModelName = req.modelName() != null && !req.modelName().isBlank();
        if (!hasTitle && !hasModelName) {
            throw new ValidationException("Session requires at least one of: title, modelName");
        }

        // T2.2 对齐 CC: model_name 仅在显式传入时落库（会话 override），否则 null。
        //   null 合法 —— 读时由 ChatService.resolveModelNameForSession 运行时解析:
        //   req.modelName → session.model_name → settings.main_model_id → DEFAULT_MODEL

        SessionRecord s = new SessionRecord();
        s.setId(generateId("sess"));
        s.setModelTag((req.model() != null ? req.model() : ModelTag.DS).name());
        s.setModelName(hasModelName ? req.modelName() : null);
        s.setTitle(hasTitle ? req.title() : req.modelName());
        // [title-cc-align V66] 显式命名标志 · 对齐 CC initialName → hasExplicitTitle=true
        //   （initReplBridge.ts:299-311）：创建传真实标题（非占位）→ titleExplicit=1（count3 不覆盖，
        //   视为显式命名）；占位/默认（"新会话"/=modelName/null）→ 0（count1/count3 可自动生成）。
        //   判定复用 ChatService.isDefaultTitle（MAJOR-4：package-private → public static 供跨包调用）。
        s.setTitleExplicit(ChatService.isDefaultTitle(req.title(), req.modelName()) ? 0 : 1);
        s.setTime("现在");
        s.setSessionGroup(SessionGroup.current.name());
        s.setTabId(null);
        s.setMainProjectId(req.mainProjectId());
        s.setMessageCount(0);
        // [V33] bare（精简）模式会话级开关：仅显式传入时落库（会话 override），null 不设（回落 env/默认 false）
        if (req.bareMode() != null) {
            s.setBareMode(req.bareMode() ? 1 : 0);
        }
        String now = OffsetDateTime.now().toString();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);

        sessionMapper.insert(s);
        return toDto(s);
    }

    public SessionDto update(String id, SessionUpdateRequest req) {
        SessionRecord s = sessionMapper.selectOneById(id);
        if (s == null) throw new NotFoundException("Session " + id + " not found");

        if (req.title() != null) {
            // [title-cc-align V66] 显式 /rename → titleExplicit=1（永不自动覆盖）· 对齐 CC
            //   getCurrentSessionTitle（initReplBridge.ts:349-351 hasExplicitTitle → return true）
            s.setTitle(req.title());
            s.setTitleExplicit(1);
        }
        if (req.model() != null) s.setModelTag(req.model().name());
        if (req.modelName() != null) s.setModelName(req.modelName());
        if (req.mainProjectId() != null) s.setMainProjectId(req.mainProjectId());
        // [V33] bare（精简）模式会话级开关：PATCH 语义，仅显式传入时更新（null 不改动）
        if (req.bareMode() != null) s.setBareMode(req.bareMode() ? 1 : 0);
        // [V44] 会话级权限模式覆盖：PATCH 语义，仅显式传入时更新（null 不改动）。写侧 isSettable
        //   校验——值必须在 6 值集合（default/plan/acceptEdits/bypassPermissions/dontAsk/auto），
        //   否则 fail-loud（ValidationException 400）而非静默折叠最严格 DEFAULT（列必须存 CC 串
        //   acceptEdits 而非枚举 name ACCEPT_EDITS，双防）。存 CC 串原样（round-trip 保真），非枚举 name。
        if (req.permissionMode() != null) {
            if (!PermissionMode.isSettable(req.permissionMode())) {
                throw new ValidationException(
                    "permissionMode 非法（允许 default/plan/acceptEdits/bypassPermissions/dontAsk/auto）");
            }
            s.setPermissionMode(req.permissionMode().trim());
        }
        // [SP-03] 会话指定主线程 agent（V58 列 main_thread_agent）：PATCH 语义，仅显式传入时更新
        //   （null 不改动）。存 CC agentType 串原样（AgentDefinitionRegistry.findAgent 等价 lookup）；
        //   空串清除 = 回落默认组装链（对齐 permissionMode trim 范式）。
        // [B3] 写侧校验：trim 后非空 → 必须命中 registry.findAgent（按会话解析 registry，
        //   registryForSession），否则 fail-loud 400（对齐 permissionMode isSettable 范式）；
        //   SubagentTool 未接线（plain JUnit）→ log.warn 后走原逻辑不校验（fail-open）。
        if (req.mainThreadAgent() != null) {
            String agentType = req.mainThreadAgent().trim();
            if (agentType.isEmpty()) {
                // 空串清除 = 回落默认组装链（对齐 permissionMode trim 范式）
                s.setMainThreadAgent("");
                if (log.isDebugEnabled()) {
                    log.debug("[SessionService] update: session={} mainThreadAgent 清空（回落默认 agent 组装链）", id);
                }
            } else if (subagentTool != null) {
                AgentDefinitionRegistry registry = subagentTool.registryForSession(id);
                if (registry.findAgent(agentType) == null) {
                    String available = String.join(", ",
                        registry.listAgents().stream().map(a -> a.agentType()).toList());
                    throw new ValidationException(
                        "agent 类型不存在: " + agentType + "，可用: " + available);
                }
                s.setMainThreadAgent(agentType);
                log.info("[SessionService] update: session={} 设置 mainThreadAgent={}（registry 命中，按会话解析）",
                    id, agentType);
            } else {
                log.warn("[SessionService] update: SubagentTool 未接线，mainThreadAgent={} 跳过校验走原逻辑（fail-open）",
                    agentType);
                s.setMainThreadAgent(agentType);
            }
        }
        s.setUpdatedAt(OffsetDateTime.now().toString());

        sessionMapper.update(s);
        return toDto(s);
    }

    public void delete(String id) {
        SessionRecord s = sessionMapper.selectOneById(id);
        if (s == null) throw new NotFoundException("Session " + id + " not found");
        // [S4] 先 cancel 在飞 turn 再清理调度（对齐 CC onCancel REPL.tsx:2147；cancel-first 幂等）
        //   否则已删会话 in-flight turn 继续跑并向已删 topic 推 STOMP（探查 S4）。
        //   best-effort：wsTemplate/chatService 缺失或推送异常不阻塞会话删除主流程。
        if (chatService != null && wsTemplate != null) {
            try {
                chatService.cancelSession(id, wsTemplate);
                if (log.isInfoEnabled()) {
                    log.info("[SessionService] delete: cancelSession session={}", id);
                }
            } catch (Exception e) {
                log.warn("[SessionService] delete: cancelSession 失败 session={}: {}", id, e.toString());
            }
        }
        // [A2] abort 本会话 in-process teammate（对齐 CC spawnInProcess.ts:184-188 registerCleanup abort；
        //   先 abort 后删 team 目录 = CC cleanupSessionTeams kill-panes→rm-dirs 顺序）。
        //   runner 线程（runTeammateLoop）轮循 isAborted 检测 abort 后自然退出。
        if (spawnInProcess != null) {
            try {
                spawnInProcess.registry().cleanupSession(id);
            } catch (Exception e) {
                log.warn("[SessionService] delete: cleanupSession 失败 session={}: {}", id, e.toString());
            }
        }
        // Phase 4: 关闭前先调 ChatService.closeSession 清理 SESSION-scope 调度任务
        //   (best-effort — 内部 try/catch 吞异常, 不阻塞 session 删除主流程)
        if (chatService != null) {
            chatService.closeSession(id);
        }
        // [MG-6 · A2-4 补充登记 6] removeSessionState 接线 · 会话删除 → 释放 SESSION_STATES 桶
        //   （决策登记 6 A2-4：CC 进程随会话结束退出无泄漏；Java 常驻 JVM，会话删除时由外层移除
        //   该会话 cached-MC 桶，防 SESSION_STATES 内存累积。null/未知会话 no-op，不阻塞删除主流程。）
        MicroCompactor.removeSessionState(id);
        // [cache-hit-fix B] 会话删除 → 释放该会话 git status 快照（防 SessionGitStatusRegistry 无界增长）。
        //   best-effort：null（未接线）→ 跳过；未知会话 evict no-op，不阻塞删除主流程。
        if (sessionGitStatusRegistry != null) {
            sessionGitStatusRegistry.evict(id);
        }
        // DEL-SH-01: 会话删除时清理该 session 的 skill improvement suggestion store 条目
        //   (对齐 CC AppState.skillImprovement.suggestion 随会话消亡; removeBySession 静默忽略 null/未知)
        // [session-id-short] id 已 short 直键 store（不再 parseSessionUuid）
        if (suggestionStore != null) {
            suggestionStore.removeBySession(id);
        }
        // [R3] 双通道同步——文件侧清理（config-home transcript + sidecar）best-effort：
        //   解绑 projectRoot 冻结 → 项目根（boundProject/originalCwd 层，deleteSessionFiles 内部
        //   经 getProjectDir 派生 slug）→ 删除该会话全部文件侧产物
        //   （{slug}/{sessionId}.jsonl + {slug}/{sessionId}/ 递归）。文件不存在/删失败 → log.warn
        //   不抛（fail-loud 但不断 DB 删行）。对齐 CC cleanupTaskOutput/cleanupOldSessionFiles。
        try {
            // [删除清理 2026-09-03 顺序修复] 必须先取 originalCwd 再 clearSession：clearSession 会清掉
            //   SessionProjectRoot 冻结（boundProject 层），随后 getOriginalCwdLayer 拿不到冻结 → 回落
            //   user.dir（后端启动目录）→ deleteSessionFiles 打错 slug（实测 sess-4b06118b boundProject=
            //   桌面报告目录，却删到 D--code-ai-project-nexusai-backend slug），真正 transcript/session-memory
            //   残留未清。先取值 → 再 clear → 再删（顺序关键）。
            java.nio.file.Path projectRoot = java.nio.file.Path.of(
                com.nexusai.application.agent.agent.CwdResolution.getOriginalCwdLayer(id));
            com.nexusai.common.SessionProjectRoot.clearSession(id);
            SessionStorage.deleteSessionFiles(projectRoot, id);
        } catch (Exception e) {
            log.warn("[SessionService] delete 文件侧清理失败（best-effort）: session={} err={}", id, e.getMessage());
        }
        // [A3] 会话删除 → 清理该会话创建的孤儿 team 目录（对齐 CC 进程退出 cleanupSessionTeams）·
        //   会话桶 sessionCreatedTeams 按 SessionKeys.canonicalUuid 归一，只清本会话 teams，杜绝跨会话误删。
        //   best-effort 吞异常不阻塞删 DB（工具路径派生 UUID 与 HTTP 路径 sess-xxx 同键）。
        if (teamHelpers != null) {
            try {
                teamHelpers.cleanupSessionTeams(id);
            } catch (Exception e) {
                log.warn("[SessionService] delete: cleanupSessionTeams 失败 session={}: {}", id, e.toString());
            }
        }
        // 级联删 messages + session_files（FK ON DELETE CASCADE 已配，但显式更安全）
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", id));
        sessionFileMapper.deleteByQuery(QueryWrapper.create().eq("session_id", id));
        sessionMapper.deleteById(id);
    }

    /**
     * 更新会话 conversationId · 对齐 CC REPL.tsx:4971 {@code setConversationId(randomUUID())}
     * （partial 压缩后新 conversationId，使前端 Messages row key 变化触发重渲染）。
     *
     * @param sessionId        会话 ID
     * @param newConversationId 新 conversationId（调用方生成 randomUUID()）
     */
    public void updateConversationId(String sessionId, String newConversationId) {
        SessionRecord s = sessionMapper.selectOneById(sessionId);
        if (s == null) throw new NotFoundException("Session " + sessionId + " not found");
        s.setConversationId(newConversationId);
        s.setUpdatedAt(OffsetDateTime.now().toString());
        sessionMapper.update(s);
        log.info("[SessionService] updateConversationId: session={} 更新 conversationId={}（REPL.tsx:4971）",
            sessionId, newConversationId);
    }

    /**
     * 读取会话级禁用工具集合（V34 列 disabled_tools，JSON 数组）· 待前端对接 §29。
     *
     * <p><b>WHY</b>: 前端「点 × 临时禁用工具」需会话级持久化 + 跨 turn / 重开会话生效；
     * llmToolsArray 在 schema 阶段按本集合剔除（复刻 CC blanket deny 效果，tools.ts:262-269）。
     * null/空/解析失败 → 空集合（不 NPE，对齐 V33 bare_mode 可空语义）。
     *
     * @param sessionId 会话 ID（DB 键，如 "sess-xxx"）
     * @return 禁用工具名集合（不可变快照；未禁用 → 空集合）
     * @throws NotFoundException session 不存在（对齐 getById :60）
     */
    public Set<String> getDisabledTools(String sessionId) {
        SessionRecord s = sessionMapper.selectOneById(sessionId);
        if (s == null) throw new NotFoundException("Session " + sessionId + " not found");
        String json = s.getDisabledTools();
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> list = JSON.parseArray(json, String.class);
            if (list == null || list.isEmpty()) {
                return Set.of();
            }
            return new LinkedHashSet<>(list);
        } catch (Exception e) {
            log.warn("[SessionService] getDisabledTools 解析失败（降级空集合）session={}: {}", sessionId, e.toString());
            return Set.of();
        }
    }

    /**
     * 写入会话级禁用工具集合（V34 列 disabled_tools，JSON 数组）· 待前端对接 §29。
     *
     * <p>空集合 → 存 null（读回空集合）；非空 → JSON 数组串。session 不存在 → NotFoundException。
     *
     * @param sessionId 会话 ID（DB 键）
     * @param disabled  禁用工具名集合（null 视为空集 → 存 null）
     * @throws NotFoundException session 不存在
     */
    public void setDisabledTools(String sessionId, Set<String> disabled) {
        SessionRecord s = sessionMapper.selectOneById(sessionId);
        if (s == null) throw new NotFoundException("Session " + sessionId + " not found");
        Set<String> norm = disabled == null ? Set.of() : new LinkedHashSet<>(disabled);
        norm.remove(null);
        String json = norm.isEmpty() ? null : JSON.toJSONString(new ArrayList<>(norm));
        s.setDisabledTools(json);
        s.setUpdatedAt(OffsetDateTime.now().toString());
        sessionMapper.update(s);
        if (log.isInfoEnabled()) {
            log.info("[SessionService] setDisabledTools: session={} disabled={}（V34 列 disabled_tools，"
                    + "gap29 会话级工具禁用）", sessionId, norm);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [A4] 会话级 teamContext（V39 列 team_context）· 对齐 CC appState.teamContext
    // ════════════════════════════════════════════════════════════════════

    /**
     * 读取会话级 swarm teamContext（V39 列 team_context，TEXT JSON 对象）· CC original:
     * appState.teamContext（TeamCreateTool.ts:201-216 setAppState，session-global 稳定态）。
     *
     * <p><b>WHY (A4 漂移修复)</b>：ToolUseContext.getAppState 生产为 per-request no-op，teamContext
     * 内存态跨工具即失；会话列承载让 TeamCreateTool 写 / TeamDeleteTool 清 / SendMessageTool 读
     * 跨工具/跨回合/重开会话存活（multi-session-vs-cc-single-session 铁律，effort_level/bare_mode/
     * disabled_tools 同款会话列范式）。
     *
     * <p>fail-soft：session 不存在 / 列 null / 解析失败 → null（不抛，对齐 {@link TeamHelpers#readConfig}
     * ENOENT→null 容错，供工具回退 appState）。
     *
     * @param sessionId 会话标识（派生 UUID 或 "sess-xxx"，经 {@link #resolveRowKey} 归一 DB 键）
     * @return teamContext Map；无则 null
     */
    public Map<String, Object> getTeamContext(String sessionId) {
        SessionRecord s = sessionMapper.selectOneById(resolveRowKey(sessionId));
        if (s == null) {
            return null;
        }
        return parseTeamContext(s.getTeamContext());
    }

    /**
     * team_context 列 JSON → Map · null/空白/非对象/解析失败 → null（fail-soft，对齐 getTeamContext
     * :311-314 容错，不抛）。
     *
     * <p>供 {@link #toDto} / {@link com.nexusai.application.project.ProjectSessionBindingService#bind}
     * 复用 —— DTO 透出会话级 teamContext（P2：GET /sessions/{id} + /sessions 返回该字段）。
     */
    public static Map<String, Object> parseTeamContext(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object parsed = JSON.parse(json);
            if (!(parsed instanceof Map<?, ?> map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) map;
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            // 降级 null（不抛，对齐 TeamHelpers.readConfig ENOENT→null 容错）
            return null;
        }
    }

    /**
     * sessions.todos 列 JSON → 解析态 Map · null/空白/非对象/解析失败 → null（fail-soft，对齐
     * {@link #parseTeamContext} 容错，不抛）。
     *
     * <p>[R3 持久升级] 会话 todo 面板刷新/重开拉取读本字段（SessionDto.todos 透出，REST
     * SessionController.get → getById 自动携带）。返回规范形 {todoKey:[{content,status,activeForm}]}，
     * status 保持小写（pending|in_progress|completed，CC types.ts:4-6 值域）——不转 TodoItem，
     * 避免 model → application.agent.tool.impl 依赖。
     *
     * <p>供 {@link #toDto} 复用（对齐 parseTeamContext :310-326 模式）。
     */
    public static Map<String, Object> parseTodos(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object parsed = JSON.parse(json);
            if (!(parsed instanceof Map<?, ?> map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) map;
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            // 降级 null（不抛，对齐 parseTeamContext 容错）
            return null;
        }
    }

    /**
     * 写入会话级 swarm teamContext（V39 列 team_context）· CC original: appState.teamContext。
     *
     * <p>null map → 存 null（清）；非 null → {@code JSON.toJSONString(map)}。session 不存在 → warn +
     * no-op（fail-soft，工具路径 sessionId 可能为派生 UUID 而 DB 行缺失，不阻塞工具执行）。
     *
     * @param sessionId   会话标识（派生 UUID 或 "sess-xxx"，经 {@link #resolveRowKey} 归一 DB 键）
     * @param teamContext teamContext Map（null = 清）
     */
    public void setTeamContext(String sessionId, Map<String, Object> teamContext) {
        SessionRecord s = sessionMapper.selectOneById(resolveRowKey(sessionId));
        if (s == null) {
            log.warn("[SessionService] setTeamContext: session 不存在, no-op session={}", sessionId);
            return;
        }
        if (teamContext == null) {
            // [team-panel-backend-bugfix2 修正] 清空：mybatis-flex update(entity) 默认忽略 null 字段
            //   （ignoreNulls=true NOT_NULL 策略）→ team_context 列被 SET 跳过 → DB 残留原值。
            //   必须 update(entity, false) 显式写 NULL（对齐 EffortCommand:354 / ProjectSessionBindingService:80）。
            //   ⚠️ 不能用稀疏 patch + updateByQuery(patch, false, qw)：false 会把 patch 所有 null 字段
            //   写 NULL，model_tag（NOT NULL）等列违反约束 → 500（前端联调实测）。
            //   用 s（selectOneById 完整实体）置 null，非目标列保留 DB 原值，仅 team_context 写 NULL。
            s.setTeamContext(null);
            s.setUpdatedAt(OffsetDateTime.now().toString());
            sessionMapper.update(s, false);
            if (log.isDebugEnabled()) {
                log.debug("[SessionService] setTeamContext: session={} 清空 teamContext（update(entity,false) 显式写 NULL，仅 team_context 列；V40 列 team_context）",
                        sessionId);
            }
            return;
        }
        s.setTeamContext(JSON.toJSONString(teamContext));
        s.setUpdatedAt(OffsetDateTime.now().toString());
        sessionMapper.update(s);
        if (log.isDebugEnabled()) {
            log.debug("[SessionService] setTeamContext: session={} teamContext={}（V40 列 team_context，A4 会话级化）",
                    sessionId, teamContext.get("teamName"));
        }
    }

    /** 清空会话级 teamContext · 对齐 CC TeamDeleteTool.ts:118-124 setAppState 移除 teamContext。 */
    public void clearTeamContext(String sessionId) {
        setTeamContext(sessionId, null);
    }

    /**
     * 会话标识 → DB 主键归一 · [session-id-short] sessionId 已统一 short 直返
     * （不再需要 originalKey 反解；存量旧行（派生 UUID 串）经 {@link SessionKeys#originalKey(String)}
     * 兼容兜底保留，阶段2 删）。
     */
    private static String resolveRowKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return sessionId;
        }
        String original = SessionKeys.originalKey(sessionId);
        return original != null ? original : sessionId;
    }


    // ============== helpers ==============

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static SessionDto toDto(SessionRecord s) {
        return new SessionDto(
            s.getId(),
            s.getModelTag() != null ? ModelTag.valueOf(s.getModelTag()) : null,
            s.getModelName(),
            s.getTitle(),
            s.getTime(),
            s.getSessionGroup() != null ? SessionGroup.valueOf(s.getSessionGroup()) : null,
            s.getTabId(),
            s.getMainProjectId(),
            s.getMessageCount(),
            parseDateTime(s.getCreatedAt()),
            parseDateTime(s.getUpdatedAt()),
            s.getEffortLevel(),
            // [V32] ultracode 会话级开关透出（0/1 → Boolean，null 保持 null）
            s.getUltracodeEnabled() != null ? s.getUltracodeEnabled() != 0 : null,
            // [V33] bare 会话级开关透出（0/1 → Boolean，null 保持 null）
            s.getBareMode() != null ? s.getBareMode() != 0 : null,
            // [P2] team_context 列解析态透出（未建 team / 解析失败 → null）
            parseTeamContext(s.getTeamContext()),
            // [R3] todos 列解析态透出（从未 TodoWrite / 解析失败 → null，前端会话 todo 面板刷新/重开拉取）
            parseTodos(s.getTodos()),
            // [V44] 会话级权限模式覆盖透出（V44 列 permission_mode；null = 未覆盖 → 回落全局
            //   settings.permission_mode → 磁盘 settings.json defaultMode → default）。存 CC 串原样。
            s.getPermissionMode(),
            // [V48] 会话累计花费（元）· 前端会话底部 token/金额汇总（F5 恢复 · sessions 表持久化）
            s.getTotalCostYuan(),
            sumTokensFromModelUsage(s.getModelUsageJson()),
            // [SP-03] 会话指定主线程 agent 透出（V58 列 main_thread_agent；null = 未指定，agent 分支休眠）
            s.getMainThreadAgent()
        );
    }

    /**
     * 会话累计 token 汇总 · 解析 model_usage_json（各模型桶 {inputTokens, outputTokens}）求和。
     * null/空白/解析失败 → 0（fail-soft，对齐 parseTeamContext 容错）。
     * public static 供 ProjectSessionBindingService.toSessionDto 复用（同 parseTeamContext 模式）。
     */
    public static long sumTokensFromModelUsage(String modelUsageJson) {
        if (modelUsageJson == null || modelUsageJson.isBlank()) {
            return 0L;
        }
        try {
            com.alibaba.fastjson2.JSONObject root = JSON.parseObject(modelUsageJson);
            long total = 0;
            for (var entry : root.entrySet()) {
                if (!(entry.getValue() instanceof com.alibaba.fastjson2.JSONObject bucket)) continue;
                total += bucket.getLongValue("inputTokens");
                total += bucket.getLongValue("outputTokens");
            }
            return total;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static OffsetDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
