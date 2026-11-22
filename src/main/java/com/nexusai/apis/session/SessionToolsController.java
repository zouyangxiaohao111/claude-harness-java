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
import com.nexusai.infra.exception.ConflictException;
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
 * <p><b>[tool-categories] 分类与批量开关</b>：每条工具带 {@code category}（CU/KB/BR/MCP/SYS，由
 * {@link SessionToolCategory#of} 判定）与 {@code description} 原文；{@code PATCH /tools} 按分类
 * 批量启用/禁用（跳过核心编排工具），返回整表供前端一次替换。MCP 类另带 {@code mcpServer}，
 * 支持「只开关某个 MCP server」的中间一级（{@code {category:"MCP", mcpServer:"rea"}}）。
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
    /** [V329] IM 组「全部启用」时把 Brief 一起打开（SendUserMessage 与 /brief 同一份状态）。 */
    @Autowired(required = false) private com.nexusai.domain.session.SessionModeService sessionModeService;

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
        sessionService.getDisabledTools(sessionId);
        return buildList(sessionId);
    }

    /**
     * 分类（或 MCP server）批量启用/禁用 · PATCH /api/v1/sessions/{sessionId}/tools。
     *
     * <p><b>WHY</b>: 前端一级目录每类的开关只发一次请求（⛔ 不是循环 N 次单工具 PATCH）。
     * {@code enabled=false} → 该分类全部工具加入禁用集合，<b>跳过</b>核心编排工具
     * （Agent/TaskStop/SendMessage 后端禁用会 400，跳过而不是整组失败）；
     * {@code enabled=true} → 该分类全部移出禁用集合。
     *
     * <p><b>[tool-categories] MCP 三级联动</b>：{@code category=MCP} 且 {@code mcpServer} 非空时，
     * 只操作该 server 的工具（{@code mcpServerOf(name).equals(mcpServer)}）；{@code mcpServer}
     * 为空 → 整个 MCP 分类（等价于「关闭全部 MCP 工具」）。{@code mcpServer} 与其它分类组合 → 400。
     *
     * <p><b>起点口径</b>：若该会话 {@code disabled_tools} 为 null（从未设置），起点用默认禁用集
     * （电脑操作 + 知识库）——否则「打开浏览器组」会把默认禁用的 50 项一起算成未禁用。
     *
     * @param sessionId 会话 ID（路径变量）
     * @param req       { category, enabled, mcpServer? }
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
        String mcpServer = req.mcpServer() == null ? null : req.mcpServer().trim();
        if (mcpServer != null && mcpServer.isEmpty()) {
            mcpServer = null;
        }
        // mcpServer 只对 MCP 分类有意义：与其它分类组合必须明确失败（⛔ 不静默忽略）
        if (mcpServer != null && !SessionToolCategory.MCP.equals(category)) {
            throw new ValidationException("mcpServer is only supported with category=MCP (got category="
                + category + ", mcpServer=" + mcpServer + ")");
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
            // MCP 三级联动的中间一级：只收该 server 的工具（null = 整类）
            if (mcpServer != null && !mcpServer.equals(SessionToolCategory.mcpServerOf(t.name()))) {
                continue;
            }
            categoryNames.add(t.name());
            if (!req.enabled() && CORE_ORCHESTRATION_TOOLS.contains(t.name())) {
                skippedCore++;
            }
        }
        // [V330] IM 组：其余工具走增量覆盖（默认关）；SendUserMessage 只由 Brief 状态控制。
        if (SessionToolCategory.IM.equals(category)) {
            Map<String, Boolean> patch = new java.util.LinkedHashMap<>();
            for (String n : categoryNames) {
                if (!ToolNameConstants.BRIEF_TOOL_NAME.equals(n) && !CORE_ORCHESTRATION_TOOLS.contains(n)) {
                    patch.put(n, req.enabled());
                }
            }
            if (!patch.isEmpty()) {
                sessionService.patchImToolOverrides(sessionId, patch);
            }
            if (req.enabled() && sessionModeService != null
                    && categoryNames.contains(ToolNameConstants.BRIEF_TOOL_NAME)) {
                sessionModeService.setBrief(sessionId, true);
            }
            if (log.isInfoEnabled()) {
                log.info("SessionToolsController.setCategoryEnabled: session={} IM enabled={} tools={}（V330 增量）",
                        sessionId, req.enabled(), patch.keySet());
            }
            return buildList(sessionId);
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
            log.info("SessionToolsController.setCategoryEnabled: session={} category={} mcpServer={} enabled={} "
                    + "affected={} skippedCore={}（[tool-categories] 批量开关）",
                sessionId, category, mcpServer, req.enabled(), categoryNames.size(), skippedCore);
        }
        return buildList(sessionId);
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
        // [V330] SendUserMessage 只由 Brief 状态控制；IM 组其余工具走增量覆盖（不写 disabled_tools）。
        if (ToolNameConstants.BRIEF_TOOL_NAME.equals(toolName)) {
            if (sessionModeService == null) {
                throw new ValidationException("Brief 状态服务未接线，无法切换 " + toolName);
            }
            // [Q8] 关 Brief 且无人值守开启：未带确认 ⇒ 409 + 可读文案（前端据此弹确认框）；
            //   带确认 ⇒ 域服务一次原子写入两个状态。
            try {
                sessionModeService.setBrief(sessionId, req.enabled(),
                    Boolean.TRUE.equals(req.cascadeExitUnattended()));
            } catch (IllegalStateException e) {
                throw new ConflictException(e.getMessage());
            }
        } else if (SessionToolCategory.IM_TOOL_NAMES.contains(toolName)) {
            sessionService.patchImToolOverrides(sessionId, Map.of(toolName, req.enabled()));
        } else {
            // 起点：DB 实际值（含默认禁用推断），否则「打开一个默认禁用的工具」会被默认集盖回
            Set<String> disabled = new LinkedHashSet<>(effectiveDisabledNames(sessionId));
            if (req.enabled()) {
                disabled.remove(toolName);
            } else {
                disabled.add(toolName);
            }
            sessionService.setDisabledTools(sessionId, disabled);
        }
        // [真值回读 · 2026-11-15] 必须按**写入后的实际生效集合**回报，⛔ 不能 `!req.enabled()` 硬算：
        //   硬算会让前端显示与真实状态分叉（真机：点「启用」后仍显示「已禁用」，
        //   而日志里只能看到前端算出来的值，无法区分是写入失败还是显示错误）。
        //   口径与列表接口、LLM schema 剔除完全同一份（SessionToolVisibility.capture）。
        boolean nowDisabled = com.nexusai.application.agent.tool.SessionToolVisibility
            .capture(sessionId).blocks(toolName);
        if (log.isInfoEnabled()) {
            log.info("SessionToolsController.toggle: session={} tool={} disabled={}（V34 列，"
                    + "LLM schema 剔除语义对齐 CC blanket deny tools.ts:262-269）",
                sessionId, toolName, nowDisabled);
        }
        return new SessionToolDto(target.name(), target.userFacingName(),
            SessionToolCategory.of(target), target.description(), nowDisabled,
            SessionToolCategory.mcpServerOf(target.name()));
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
        Set<String> disabled = com.nexusai.application.agent.config.SessionToolDisableConfig
            .getDisabledTools(sessionId);
        // [2026-11-18 用户拍板 · 面板与模型同一口径]
        //   默认禁用集与人格点名冲突时人格优先 —— 装配侧已在 LlmAgentLoop.sessionVisibleTools
        //   做同款豁免；面板不做就会「显示已禁用、模型实际能调用」。
        //   ⛔ 只豁免默认集：用户显式写入 disabled_tools 的项仍显示禁用（那是明确意图）。
        if (!disabled.isEmpty()
                && com.nexusai.application.agent.config.SessionToolDisableConfig
                    .isDefaultDisabledSet(sessionId)) {
            Set<String> named = com.nexusai.application.agent.LlmAgentLoop.agentNamedTools(sessionId);
            if (!named.isEmpty()) {
                Set<String> merged = new LinkedHashSet<>(disabled);
                merged.removeAll(named);
                if (merged.size() != disabled.size()) {
                    if (log.isInfoEnabled()) {
                        log.info("SessionToolsController: 人格优先豁免默认禁用 session={} 豁免={}",
                            sessionId, named.stream().filter(disabled::contains).toList());
                    }
                    return merged;
                }
            }
        }
        return disabled;
    }

    /**
     * 组装工具列表（GET / 批量 PATCH 共用）。
     *
     * @param sessionId 会话 ID
     * @param disabled  生效禁用集合
     * @return 按工具名排序的完整列表
     */
    private List<SessionToolDto> buildList(String sessionId) {
        // [V330] 面板与模型同源：会话禁用 + 人格豁免 + Brief/IM 增量 + 无人值守 Ask，全部取自同一解析器。
        com.nexusai.application.agent.tool.SessionToolVisibility.Snapshot snapshot =
            com.nexusai.application.agent.tool.SessionToolVisibility.capture(sessionId);
        List<Tool> all = toolRegistry.all();
        // [session-id-short] sessionId 已 short，直传（不再 canonicalUuid 派生 UUID）
        ToolUseContext tuc = new ToolUseContext(
            null,
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
            // [structured-output 2026-11-20 · 面板与模型同源] StructuredOutput 是内部 dispatch 工具：
            //   模型 tools 数组经 ToolRegistry 的 SPECIAL_TOOLS 过滤恒不含它（唯一例外是非交互会话由
            //   主循环 post-hoc 追加 schema 专用实例，属实现细节、非会话可配置工具）；面板若照旧列出，
            //   就会出现「面板有、模型拿不到」的幽灵条目（交互式协调者尤其明显 —— 白名单候选里保留了它）。
            //   ⛔ 只滤 StructuredOutput：ListMcpResourcesTool/ReadMcpResourceTool 同属 SPECIAL_TOOLS，
            //   但它们对 LLM 实际可见（S04 过滤收窄），滤掉会造成反向不一致。
            if (ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME.equals(t.name())) {
                continue;
            }
            // [V329] 面板与模型同一可见集：会话禁用（disabled_tools）之外，无人值守会话的
            //   AskUserQuestion 也必须显示为「不可用」（模型 schema 已剔除，原因 = unattended）。
            //   被剔除的工具仍保留在列表（disabled=true），前端才能看到原因并恢复。
            boolean disabledFlag = snapshot.blocks(t.name())
                    || (snapshot.askExcluded()
                        && com.nexusai.application.agent.config.ImUnattendedConfig
                            .ASK_USER_QUESTION_TOOL.equals(t.name()));
            result.add(new SessionToolDto(t.name(), t.userFacingName(),
                SessionToolCategory.of(t), t.description(), disabledFlag,
                SessionToolCategory.mcpServerOf(t.name())));
        }
        result.sort(Comparator.comparing(SessionToolDto::name));
        if (log.isDebugEnabled()) {
            log.debug("SessionToolsController.buildList: session={} tools={} disabled={}",
                sessionId, result.size(), snapshot.disabled());
        }
        return result;
    }
}
