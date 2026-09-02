package com.nexusai.apis.session;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.SessionToolDto;
import com.nexusai.model.session.dto.SessionToolToggleRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 会话级工具列表 REST · GET/PATCH /api/v1/sessions/{sessionId}/tools（待前端对接 §29）。
 *
 * <p><b>语义（对齐 CC 工具池过滤链）</b>: 前端工具管理 UI 展示当前会话可见工具（经
 * bare/deny/coordinator 基链过滤，对齐 CC getTools tools.ts:271-327）+ 会话级禁用标志；
 * PATCH 临时禁用/恢复某工具（写入 sessions.disabled_tools，V34 列，随会话持久化）。
 * 禁用效果 = 下一次 POST /messages 的 LLM tools schema 剔除该工具（LlmAgentLoop.sessionVisibleTools
 * 复刻 CC blanket deny 的 schema 阶段剔除，tools.ts:262-269）。
 *
 * <p><b>GET 口径</b>: 用 {@code LlmAgentLoop.sessionVisibleToolsBase}（bare/deny/coordinator，
 * <b>不含</b>会话禁用剔除）——被禁工具必须仍在列表（disabled=true），否则前端无法恢复。
 * LLM schema 口径（含剔除）在 LlmAgentLoop.sessionVisibleTools 内，本控制器不重复实现。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/tools")
public class SessionToolsController {

    private static final Logger log = LoggerFactory.getLogger(SessionToolsController.class);

    /**
     * 核心编排工具白名单 · 禁用拦截（防锁死会话）· 对齐 CC coordinator 白名单（Agent/TaskStop/
     * SendMessage，tools.ts:291-296）——禁用后无编排工具无法恢复。
     */
    private static final Set<String> CORE_ORCHESTRATION_TOOLS = Set.of(
        AgentToolConstants.AGENT_TOOL_NAME,
        ToolNameConstants.TASK_STOP_TOOL_NAME,
        ToolNameConstants.SEND_MESSAGE_TOOL_NAME);

    @Autowired private SessionService sessionService;
    @Autowired private ToolRegistry toolRegistry;

    /**
     * 会话级工具列表 · GET /api/v1/sessions/{sessionId}/tools。
     *
     * <p><b>WHY</b>: 前端工具管理 UI 需要「当前会话可用工具列表」——经 bare/deny/coordinator
     * 基链过滤（与 LLM schema 同源，避免两套口径漂移）后逐项标注 disabled 标志（V34 列）。
     * 被禁工具保留在列表（disabled=true）供恢复；bare 模式仅 [Bash, Read, Edit] 可见
     * （对齐 CC simpleTools tools.ts:287）。permissionContext 传 null → deny 过滤 null-safe 跳过
     * （deny 随权限面/turn 变化，工具管理 UI 以 bare/disabled/coordinator 为主口径）。
     *
     * @param sessionId 会话 ID（路径变量）
     * @return 200 当前会话可见工具列表（按工具名排序，稳定序）
     */
    @GetMapping
    public List<SessionToolDto> list(@PathVariable String sessionId) {
        // session 校验（getDisabledTools 抛 NotFoundException → 404）
        Set<String> disabled = sessionService.getDisabledTools(sessionId);
        List<Tool> all = toolRegistry.all();
        // [session-id-short] sessionId 已 short，直传（不再 canonicalUuid 派生 UUID）
        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(),
            sessionId,
            PermissionMode.DEFAULT,
            Map.of(),
            all,
            null,
            AbortController.NOOP,
            List.of(),
            null,
            PermissionMode.DEFAULT);
        List<Tool> visible = LlmAgentLoop.sessionVisibleToolsBase(tuc, com.nexusai.application.agent.QuerySource.USER);
        List<SessionToolDto> result = new ArrayList<>(visible.size());
        for (Tool t : visible) {
            if (t == null) {
                continue;
            }
            result.add(new SessionToolDto(t.name(), t.userFacingName(), disabled.contains(t.name())));
        }
        result.sort(Comparator.comparing(SessionToolDto::name));
        if (log.isDebugEnabled()) {
            log.debug("SessionToolsController.list: session={} tools={} disabled={}",
                sessionId, result.size(), disabled);
        }
        return result;
    }

    /**
     * 会话级工具禁用/恢复 · PATCH /api/v1/sessions/{sessionId}/tools/{toolName}。
     *
     * <p><b>WHY</b>: 前端「点 × 临时禁用 → 该工具从模型 schema 移除，会话内生效」（待前端对接
     * §29 #2）——PATCH {@code {enabled:false}} 写 sessions.disabled_tools（V34 列，随会话持久化，
     * 跨 turn / 重开会话生效）；{@code {enabled:true}} 恢复（工具回到 schema）。
     *
     * <p><b>校验</b>: toolName 不在注册工具集 → 404（对齐既有 NotFound 惯例）；禁用核心编排工具
     * （Agent/TaskStop/SendMessage）→ 400（防锁死会话，owner 拍板默认拒绝，见实施计划 §5 决策点 3）。
     *
     * @param sessionId 会话 ID（路径变量）
     * @param toolName  工具名（路径变量，如 "Bash"）
     * @param req       请求体 { enabled: false=禁用 / true=恢复 }
     * @return 200 更新后工具状态（name/userFacingName/disabled）
     */
    @PatchMapping("/{toolName}")
    public SessionToolDto toggle(@PathVariable String sessionId,
                                 @PathVariable String toolName,
                                 @RequestBody SessionToolToggleRequest req) {
        List<Tool> all = toolRegistry.all();
        Tool target = null;
        for (Tool t : all) {
            if (t != null && toolName.equals(t.name())) {
                target = t;
                break;
            }
        }
        if (target == null) {
            throw new NotFoundException("Tool " + toolName + " not found");
        }
        if (!req.enabled() && CORE_ORCHESTRATION_TOOLS.contains(toolName)) {
            throw new ValidationException("Tool " + toolName
                + " is a core orchestration tool and cannot be disabled for this session");
        }
        // 会话级禁用集合读写（V34 列 disabled_tools，随会话持久化）
        Set<String> disabled = new LinkedHashSet<>(sessionService.getDisabledTools(sessionId));
        if (req.enabled()) {
            disabled.remove(toolName);
        } else {
            disabled.add(toolName);
        }
        sessionService.setDisabledTools(sessionId, disabled);
        boolean nowDisabled = !req.enabled();
        if (log.isInfoEnabled()) {
            log.info("SessionToolsController.toggle: session={} tool={} disabled={}（gap29 · V34 列，"
                    + "LLM schema 剔除语义对齐 CC blanket deny tools.ts:262-269）",
                sessionId, toolName, nowDisabled);
        }
        return new SessionToolDto(target.name(), target.userFacingName(), nowDisabled);
    }
}
