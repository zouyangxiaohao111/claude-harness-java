package com.nexusai.apis.permission;

import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.PermissionUpdatePersister;
import com.nexusai.application.agent.permission.source.LocalSettingsLoader;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.PermissionSourceLoader;
import com.nexusai.application.agent.permission.source.ProjectSettingsLoader;
import com.nexusai.application.agent.permission.source.UserSettingsLoader;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 权限规则管理 REST + 重试被拒工具通道 · 对齐 CC {@code /permissions} 命令
 * （commands/permissions/index.ts + permissions.tsx + components/permissions/rules/PermissionRuleList.tsx）。
 *
 * <p><b>[OPD-WF8-01-T4] 后端补能力</b>：CC 在终端内管理 allow/deny 规则
 * （{@code getAllowRules/getAskRules/getDenyRules} + {@code addPermissionRulesToSettings} +
 * {@code deletePermissionRuleFromSettings}）；Java 无终端命令总线，本控制器为 nexusai-ui
 * 提供等价 REST 数据面（规则读取/新增/删除），并对齐 CC {@code onRetryDenials →
 * createPermissionRetryMessage} 的重试被拒工具通道。
 *
 * <h2>端点</h2>
 * <ul>
 *   <li>{@code GET    /api/v1/permissions/rules} —— 读取全部可编辑规则（按行为分组）</li>
 *   <li>{@code POST   /api/v1/permissions/rules} —— 新增规则（对齐 CC addPermissionRulesToSettings）</li>
 *   <li>{@code DELETE /api/v1/permissions/rules} —— 删除规则（对齐 CC deletePermissionRuleFromSettings）</li>
 *   <li>{@code POST   /api/v1/sessions/{sessionId}/permission-retry} —— 重试被拒工具
 *       （对齐 CC onRetryDenials → createPermissionRetryMessage）</li>
 * </ul>
 *
 * <p><b>可写范围</b>：仅 3 个 editable disk source（userSettings / projectSettings /
 * localSettings）。policySettings / flagSettings / command 只读、cliArg / session 运行时
 * 不写盘 —— 与 CC deletePermissionRule 抛「Cannot delete permission rules from read-only
 * settings」语义一致（permissions.ts:1333-1337）。
 */
@RestController
public class PermissionRulesController {

    private static final Logger log = LoggerFactory.getLogger(PermissionRulesController.class);

    @Autowired
    private UserSettingsLoader userSettingsLoader;
    @Autowired
    private ProjectSettingsLoader projectSettingsLoader;
    @Autowired
    private LocalSettingsLoader localSettingsLoader;
    @Autowired
    private PermissionUpdatePersister permissionUpdatePersister;
    @Autowired
    private PermissionRuleValueParser ruleValueParser;
    /** 持久化会话消息通道（重试被拒工具落库）。 */
    @Autowired(required = false)
    private MessageService messageService;
    /** 运行中会话 AgentState 注册表（重试被拒工具注入活跃 turn 的消息列表）。 */
    @Autowired(required = false)
    private SessionAgentStateRegistry sessionAgentStateRegistry;

    // ────────────────────────────────────────────────────────────────────
    // 规则读取 · 对齐 CC getAllowRules/getAskRules/getDenyRules
    // （permissions.ts:122-231，从 ToolPermissionContext 读取 → Java 从可编辑 settings 源读取）
    // ────────────────────────────────────────────────────────────────────

    /**
     * 读取全部可编辑规则（userSettings/projectSettings/localSettings 三源合并）。
     *
     * <p>对齐 CC PermissionRuleList 的 allow/ask/deny Tab 数据源（getAllowRules /
     * getAskRules / getDenyRules）。响应元素 = {@link RuleDto}
     * {@code {source, behavior, ruleValue}}。
     *
     * @return 规则列表（按 loader 顺序 + 文件顺序）
     */
    @GetMapping("/api/v1/permissions/rules")
    public List<RuleDto> list() {
        List<PermissionSourceLoader> loaders =
            List.of(userSettingsLoader, projectSettingsLoader, localSettingsLoader);
        List<RuleDto> result = new ArrayList<>();
        for (PermissionSourceLoader loader : loaders) {
            for (PermissionRule rule : loader.load()) {
                result.add(new RuleDto(
                    ccSourceName(rule.source()),
                    ccBehaviorName(rule.ruleBehavior()),
                    rule.ruleValue().toRuleString()));
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[PermissionRulesController] 读取规则列表: 返回 {} 条（3 editable 源合并）", result.size());
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────
    // 规则新增 / 删除 · 对齐 CC addPermissionRulesToSettings / deletePermissionRuleFromSettings
    // ────────────────────────────────────────────────────────────────────

    /**
     * 新增规则 · 对齐 CC addPermissionRulesToSettings（permissionsLoader.ts:229-296）。
     *
     * <p>经 {@link PermissionUpdatePersister#persist} 的 AddRules 增量写盘
     * （roundtrip 归一化去重），由前端经 待前端对接.md §17 消费。
     *
     * @param req 请求体 {@code {destination, behavior, rules[]}}
     * @return 新增成功的规则数
     */
    @PostMapping("/api/v1/permissions/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> add(@RequestBody RuleWriteRequest req) {
        PermissionUpdate.Destination dest = parseDestination(req.destination());
        PermissionBehavior behavior = parseBehavior(req.behavior());
        List<PermissionRule> rules = parseRules(dest, behavior, req.rules());
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("rules 列表为空或全部解析失败");
        }
        permissionUpdatePersister.persist(new PermissionUpdate.AddRules(dest, rules, behavior));
        log.info("[PermissionRulesController] 新增 {} 条 {} 规则到 {}（对齐 CC addPermissionRulesToSettings）",
            rules.size(), ccBehaviorName(behavior), req.destination());
        return Map.of("added", rules.size(), "destination", req.destination(), "behavior", req.behavior());
    }

    /**
     * 删除规则 · 对齐 CC deletePermissionRuleFromSettings（permissionsLoader.ts:163-227）。
     *
     * <p>只读 source（policySettings/flagSettings/command）抛 400 —— 与 CC
     * deletePermissionRule「Cannot delete permission rules from read-only settings」
     * 语义一致（permissions.ts:1333-1337）。
     *
     * @param req 请求体 {@code {destination, behavior, rules[]}}
     * @return 删除操作的规则数（best-effort，未匹配项静默跳过 —— CC 同语义）
     */
    @DeleteMapping("/api/v1/permissions/rules")
    public Map<String, Object> delete(@RequestBody RuleWriteRequest req) {
        PermissionUpdate.Destination dest = parseDestination(req.destination());
        PermissionBehavior behavior = parseBehavior(req.behavior());
        List<PermissionRule> rules = parseRules(dest, behavior, req.rules());
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("rules 列表为空或全部解析失败");
        }
        permissionUpdatePersister.persist(new PermissionUpdate.RemoveRules(dest, rules, behavior));
        log.info("[PermissionRulesController] 删除 {} 条 {} 规则从 {}（对齐 CC deletePermissionRuleFromSettings）",
            rules.size(), ccBehaviorName(behavior), req.destination());
        return Map.of("removed", rules.size(), "destination", req.destination(), "behavior", req.behavior());
    }

    // ────────────────────────────────────────────────────────────────────
    // 重试被拒工具通道 · 对齐 CC onRetryDenials → createPermissionRetryMessage
    // （permissions.tsx:7-8 + utils/messages.ts createPermissionRetryMessage）
    // ────────────────────────────────────────────────────────────────────

    /**
     * 重试被拒工具 · 对齐 CC {@code onRetryDenials → createPermissionRetryMessage(commands)}。
     *
     * <p>CC 语义（permissions.tsx:7-8）：用户在 /permissions 面板点击「重试被拒」后，
     * 把被拒工具命令列表追加为一条 {@code subtype:'permission_retry'} 系统消息到会话，
     * LLM 下一轮据此重新发射对应工具调用。Java 等价：向会话消息列表（活跃 AgentState +
     * 持久化 MessageService）追加 permission_retry 消息；前端随后按既有 POST /messages 流程
     * 触发新一轮。
     *
     * <p>消息形状对齐 CC {@code createPermissionRetryMessage}（messages.ts:4354-4362）：
     * <pre>{@code
     * {
     *   type: 'system', subtype: 'permission_retry',
     *   content: `Allowed ${commands.join(', ')}`,
     *   commands, level: 'info', isMeta: false
     * }
     * }</pre>
     *
     * @param sessionId 会话 ID
     * @param req       请求体 {@code {commands: ["Bash", "Read(/a)"]}}
     * @return 追加的重试消息 id
     */
    @PostMapping("/api/v1/sessions/{sessionId}/permission-retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> retryDenials(@PathVariable String sessionId,
                                            @RequestBody RetryRequest req) {
        List<String> commands = req.commands();
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("commands 列表不能为空");
        }
        // CC createPermissionRetryMessage content = `Allowed ${commands.join(', ')}`
        String content = "Allowed " + String.join(", ", commands);
        ChatMessageDto retryMsg = buildPermissionRetryMessage(sessionId, content, commands);

        // 1) 注入活跃会话 AgentState（若在运行中）—— CC setMessages 追加到当前会话
        // [session-id-short] sessionId 已 short 直键 registry（不再 UUID.fromString）
        if (sessionAgentStateRegistry != null) {
            com.nexusai.application.agent.AgentState state =
                sessionAgentStateRegistry.get(sessionId);
            if (state != null) {
                state.appendMessage(retryMsg);
            }
        }
        // 2) 持久化到消息库（MessageService 未注入 → 跳过，仅日志）
        if (messageService != null) {
            messageService.appendMessage(retryMsg);
        }
        log.info("[PermissionRulesController] 重试被拒工具: sessionId={} commands={}（对齐 CC onRetryDenials → createPermissionRetryMessage）",
            sessionId, commands);
        return Map.of("messageId", retryMsg.id());
    }

    /**
     * 入参校验失败 → 400（显式失败 · CLAUDE.md 规则十二）。
     *
     * <p>对齐 CC deletePermissionRule 只读 source 抛错语义（permissions.ts:1333-1337）
     * 与 malformed 规则拒绝；独立于全局异常处理器（standalone MockMvc 测试可验证）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onIllegalArgument(IllegalArgumentException ex) {
        log.warn("[PermissionRulesController] 入参校验失败: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    /** 构建 CC createPermissionRetryMessage 等价消息（subtype='permission_retry'）。 */
    private static ChatMessageDto buildPermissionRetryMessage(String sessionId, String content,
                                                              List<String> commands) {
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("commands", commands);
        return new ChatMessageDto(
            UUID.randomUUID().toString(), sessionId, Role.system, "system",
            content, null, List.of(), com.nexusai.model.session.dto.FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), structuredOutput, false, false,
            "permission_retry");
    }

    /** destination 字符串 → 可持久化 Destination；只读/运行时 source 拒绝。 */
    private static PermissionUpdate.Destination parseDestination(String destination) {
        if (destination == null) {
            throw new IllegalArgumentException("destination 不能为空");
        }
        return switch (destination) {
            case "userSettings" -> PermissionUpdate.Destination.USER_SETTINGS;
            case "projectSettings" -> PermissionUpdate.Destination.PROJECT_SETTINGS;
            case "localSettings" -> PermissionUpdate.Destination.LOCAL_SETTINGS;
            default -> throw new IllegalArgumentException(
                "仅支持可编辑 source（userSettings/projectSettings/localSettings），收到: " + destination
                    + "（对齐 CC deletePermissionRule 只读 source 拒绝语义）");
        };
    }

    /** behavior 字符串 → PermissionBehavior；非法值拒绝。 */
    private static PermissionBehavior parseBehavior(String behavior) {
        if (behavior == null) {
            throw new IllegalArgumentException("behavior 不能为空");
        }
        return switch (behavior) {
            case "allow" -> PermissionBehavior.ALLOW;
            case "deny" -> PermissionBehavior.DENY;
            case "ask" -> PermissionBehavior.ASK;
            default -> throw new IllegalArgumentException(
                "behavior 仅支持 allow/deny/ask，收到: " + behavior);
        };
    }

    /** 规则字符串 → PermissionRule 列表；malformed（parse null）→ 400。 */
    private List<PermissionRule> parseRules(PermissionUpdate.Destination dest,
                                            PermissionBehavior behavior,
                                            List<String> ruleStrings) {
        if (ruleStrings == null || ruleStrings.isEmpty()) {
            throw new IllegalArgumentException("rules 列表不能为空");
        }
        PermissionRuleSource source = sourceOf(dest);
        List<PermissionRule> rules = new ArrayList<>();
        for (String ruleString : ruleStrings) {
            PermissionRuleValue value = ruleValueParser.parse(ruleString);
            if (value == null) {
                throw new IllegalArgumentException("规则字符串无法解析: " + ruleString);
            }
            rules.add(new PermissionRule(source, behavior, value));
        }
        return rules;
    }

    /** Destination → PermissionRuleSource（可持久化 3 source）。 */
    private static PermissionRuleSource sourceOf(PermissionUpdate.Destination dest) {
        return switch (dest) {
            case USER_SETTINGS -> PermissionRuleSource.USER_SETTINGS;
            case PROJECT_SETTINGS -> PermissionRuleSource.PROJECT_SETTINGS;
            case LOCAL_SETTINGS -> PermissionRuleSource.LOCAL_SETTINGS;
            case CLI_ARG, SESSION -> throw new IllegalArgumentException(
                "运行时 source 不可写盘: " + dest);
        };
    }

    /** Java source 枚举 → CC source 字面量。 */
    private static String ccSourceName(PermissionRuleSource source) {
        return switch (source) {
            case USER_SETTINGS -> "userSettings";
            case PROJECT_SETTINGS -> "projectSettings";
            case LOCAL_SETTINGS -> "localSettings";
            case FLAG_SETTINGS -> "flagSettings";
            case POLICY_SETTINGS -> "policySettings";
            case CLI_ARG -> "cliArg";
            case COMMAND -> "command";
            case SESSION -> "session";
        };
    }

    /** Java behavior 枚举 → CC behavior 字面量。 */
    private static String ccBehaviorName(PermissionBehavior behavior) {
        return switch (behavior) {
            case ALLOW -> "allow";
            case DENY -> "deny";
            case ASK -> "ask";
        };
    }

    /** 规则读取 DTO · CC PermissionRule 的 JSON 表达 {@code {source, behavior, ruleValue}}。 */
    public record RuleDto(String source, String behavior, String ruleValue) {
    }

    /** 规则新增/删除请求体 · CC EditPermissionRuleArgs 等价 {@code {destination, behavior, rules}}。 */
    public record RuleWriteRequest(String destination, String behavior, List<String> rules) {
    }

    /** 重试被拒工具请求体 · CC createPermissionRetryMessage(commands) 的 commands 入参。 */
    public record RetryRequest(List<String> commands) {
    }
}
