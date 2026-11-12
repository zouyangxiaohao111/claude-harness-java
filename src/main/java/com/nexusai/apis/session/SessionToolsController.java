package com.nexusai.apis.session;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.SessionToolCategory;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.SessionToolCategoryToggleRequest;
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
 * 会话级工具列表 REST · GET/PATCH /api/v1/sessions/{sessionId}/tools。
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
 *
 * <p><b>[tool-categories] 分类与批量开关</b>：每条工具带 {@code category}（CU/KB/BR/SYS，由
 * {@link SessionToolCategory#of} 判定）与 {@code description} 原文；{@code PATCH /tools} 按分类
 * 批量启用/禁用（跳过核心编排工具），返回整表供前端一次替换。
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
     * （对齐 CC simpleTools tools.ts:287）。permissionContext 传 null → deny 过滤 null-safe 跳过。
     *
     * <p><b>disabled 口径</b>：{@code sessionService.getDisabledTools} 返回<b>DB 实际值</b>
     * （不含默认禁用推断）；面板要显示「默认禁用」状态，故 disabled 判据用
     * {@code SessionToolDisableConfig.getDisabledTools}（含默认推断）——见
     * {@link #effectiveDisabledNames(String)}。
     *
     * @param sessionId 会话 ID（路径变量）
     * @return 200 当前会话可见工具列表（按工具名排序，稳定序）
     */
    @GetMapping
    public List<SessionToolDto> list(@PathVariable String sessionId) {
        // session 校验（getDisabledTools 抛 NotFoundException → 404）
        return buildList(sessionId, effectiveDisabledNames(sessionId));
    }

    /**
     * 分类批量启用/禁用 · PATCH /api/v1/sessions/{sessionId}/tools。
     *
     * <p><b>WHY</b>: 前端一级目录每类的开关只发一次请求（⛔ 不是循环 N 次单工具 PATCH）。
     * {@code enabled=false} → 该分类全部工具加入禁用集合，<b>跳过</b>核心编排工具
     * （Agent/TaskStop/SendMessage 后端禁用会 400，跳过而不是整组失败）；
     * {@code enabled=true} → 该分类全部移出禁用集合。
     *
     * <p><b>起点口径</b>：若该会话 {@code disabled_tools} 为 null（从未设置），起点用默认禁用集
     * （电脑操作 + 知识库）——否则「打开浏览器组」会把默认禁用的 50 项一起算成未禁用。
     *
     * @param sessionId 会话 ID（路径变量）
     * @param req       { category, enabled }
     * @return 200 更新后的完整列表（与 GET 同形，前端整表替换）
     */
    @PatchMapping
    public List<SessionToolDto> setCategoryEnabled(@PathVariable String sessionId,
                                                   @RequestBody SessionToolCategoryToggleRequest req) {
        String category = req.category();
        if (category == null || !SessionToolCategory.ALL.contains(category)) {
            throw new ValidationException("Invalid tool category: " + category
                + " (expected one of " + SessionToolCategory.ALL + ")");
        }
        // 起点：DB 实际值；列为 null（从未设置）→ 默认禁用集
        Set<String> disabled = new LinkedHashSet<>(effectiveDisabledNames(sessionId));
        List<String> categoryNames = new ArrayList<>();
        int skippedCore = 0;
        for (Tool t : toolRegistry.all()) {
            if (t == null || t.name() == null) {
                continue;
            }
            if (!category.equals(SessionToolCategory.of(t))) {
                continue;
            }
            categoryNames.add(t.name());
            if (!req.enabled() && CORE_ORCHESTRATION_TOOLS.contains(t.name())) {
                skippedCore++;
            }
        }
        if (req.enabled()) {
            disabled.removeAll(categoryNames);
        } else {
            for (String n : categoryNames) {
                if (!CORE_ORCHESTRATION_TOOLS.contains(n)) {
                    disabled.add(n);
                }
            }
        }
        sessionService.setDisabledTools(sessionId, disabled);
        if (log.isInfoEnabled()) {
            log.info("SessionToolsController.setCategoryEnabled: session={} category={} enabled={} "
                    + "affected={} skippedCore={}（[tool-categories] 批量开关）",
                sessionId, category, req.enabled(), categoryNames.size(), skippedCore);
        }
        return buildList(sessionId, effectiveDisabledNames(sessionId));
    }

    /**
     * 会话级工具禁用/恢复 · PATCH /api/v1/sessions/{sessionId}/tools/{toolName}。
     *
     * <p><b>WHY</b>: 前端「点 × 临时禁用 → 该工具从模型 schema 移除，会话内生效」——
     * PATCH {@code {enabled:false}} 写 sessions.disabled_tools（V34 列，随会话持久化，
     * 跨 turn / 重开会话生效）；{@code {enabled:true}} 恢复（工具回到 schema）。
     *
     * <p><b>校验</b>: toolName 不在注册工具集 → 404；禁用核心编排工具
     * （Agent/TaskStop/SendMessage）→ 400（防锁死会话）。
     *
     * @param sessionId 会话 ID（路径变量）
     * @param toolName  工具名（路径变量，如 "Bash"）
     * @param req       请求体 { enabled: false=禁用 / true=恢复 }
     * @return 200 更新后工具状态
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
        // 起点：DB 实际值（含默认禁用推断），否则「打开一个默认禁用的工具」会被默认集盖回
        Set<String> disabled = new LinkedHashSet<>(effectiveDisabledNames(sessionId));
        if (req.enabled()) {
            disabled.remove(toolName);
        } else {
            disabled.add(toolName);
        }
        sessionService.setDisabledTools(sessionId, disabled);
        boolean nowDisabled = !req.enabled();
        if (log.isInfoEnabled()) {
            log.info("SessionToolsController.toggle: session={} tool={} disabled={}（V34 列，"
                    + "LLM schema 剔除语义对齐 CC blanket deny tools.ts:262-269）",
                sessionId, toolName, nowDisabled);
        }
        return new SessionToolDto(target.name(), target.userFacingName(),
            SessionToolCategory.of(target), target.description(), nowDisabled);
    }

    /**
     * 当前会话**生效**的禁用工具名集合（含默认禁用推断）。
     *
     * <p><b>为什么不用 {@code sessionService.getDisabledTools}</b>：那是 DB 实际值，列 null 时返回空集；
     * 而面板必须显示「默认禁用」状态（电脑操作 + 知识库），故用
     * {@code SessionToolDisableConfig.getDisabledTools} —— 它把 null 判成默认禁用集，
     * 且与 LLM schema 剔除（{@code LlmAgentLoop.sessionVisibleTools}）同源，避免两套口径。
     *
     * @param sessionId 会话 ID
     * @return 生效禁用集合（可能含默认禁用项）
     */
    private Set<String> effectiveDisabledNames(String sessionId) {
        // SessionService.getDisabledTools 先做存在性校验（不存在 → 404）
        sessionService.getDisabledTools(sessionId);
        return com.nexusai.application.agent.config.SessionToolDisableConfig.getDisabledTools(sessionId);
    }

    /**
     * 组装工具列表（GET / 批量 PATCH 共用）。
     *
     * @param sessionId 会话 ID
     * @param disabled  生效禁用集合
     * @return 按工具名排序的完整列表
     */
    private List<SessionToolDto> buildList(String sessionId, Set<String> disabled) {
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
            result.add(new SessionToolDto(t.name(), t.userFacingName(),
                SessionToolCategory.of(t), t.description(), disabled.contains(t.name())));
        }
        result.sort(Comparator.comparing(SessionToolDto::name));
        if (log.isDebugEnabled()) {
            log.debug("SessionToolsController.buildList: session={} tools={} disabled={}",
                sessionId, result.size(), disabled);
        }
        return result;
    }
}
