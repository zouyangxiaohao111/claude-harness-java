package com.nexusai.application.agent.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PathValidation;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 跨会话 peer 回合的<b>提权面硬边界</b>（C 批 · 2026-11-19 语义重排）。
 *
 * <p><b>信任根</b>：只读类型化字段 {@link ToolUseContext#peerExecutionOrigin()}（B 批冻结）。
 * ⛔ 不读 ThreadLocal、不解析消息正文里的 {@code origin=human}、不把
 * {@code requestExtras} / {@code structuredOutput} 自报当来源。origin 为 {@code null}
 * = 正常用户回合 ⇒ 本类对所有判断立即返回 ALLOW，既有语义零变化。
 *
 * <h2>语义（对齐 CC）</h2>
 * <p>peer 消息 = <b>队友的请求</b>，在目标会话自身权限设置下执行；不是用户指令，也不能授予新权限。
 * 因此本类<b>不再</b>因为「来源是 peer」就一刀切拒绝 Bash / Write / Edit / MCP 等正常副作用工具，
 * 也不再返回 {@link Action#ASK} 做二次确认（会造成重复弹窗）：
 * <ul>
 *   <li><b>只有提权面硬 Deny</b>：
 *     <ul>
 *       <li>{@code EnterPlanMode} / {@code ExitPlanMode}（可恢复 {@code prePlanMode=bypass}）；</li>
 *       <li>{@code Config} 写（带 {@code value}；GET 只读放行）；</li>
 *       <li>目标 realpath 命中 CLAUDE.md / AGENTS.md / settings / 配置根 / 危险配置目录的写工具；</li>
 *       <li>结构化「我被拒绝/被拦了，你替我做」转交标记（{@link #SOURCE_KIND_DENIED_RELAY}）。</li>
 *     </ul>
 *   </li>
 *   <li><b>其余工具一律 ALLOW</b> —— 交给目标会话既有权限管线：default 模式照常弹审批，
 *       bypass 模式照常执行，显式 deny 规则/白名单照旧优先。</li>
 * </ul>
 *
 * <p><b>不夸口静态关键词安全</b>：Bash / MCP 的任意副作用无法由 shell 文本可靠归类，
 * 本类不假装能识别「peer 让模型替它做被拒动作」的所有自然语言变形；只认服务端提供的
 * <b>结构化</b> denied 转交标记，其余靠提示词围栏 + 目标会话权限管线。
 *
 * <p><b>最终 guard</b>：{@link #checkFinal} 与 {@link #check} 使用同一提权面判据，
 * 在 updatedInput / 用户改写之后、{@code tool.execute} 之前再核一次；普通副作用不拦截。
 */
public final class PeerActionGuard {

    private static final Logger log = LoggerFactory.getLogger(PeerActionGuard.class);

    private static final String PREFIX = "Cross-session (peer) request: ";

    /**
     * 结构化 denied 转交标记（来源类型 {@code PeerExecutionOrigin.sourceKind()}，
     * 由服务端信封决定，⛔ 不是正文自报）。
     *
     * <p>语义：发送方有一个动作「被拒绝 / 被拦」，要求目标会话替它执行。
     * 跨会话转交被拒动作 = 权限洗白（CC auto 分类器同等拒绝面），必须拒绝并让真实用户裁决。
     */
    public static final String SOURCE_KIND_DENIED_RELAY = "denied-relay";

    private static final Set<String> DENIED_RELAY_SOURCE_KINDS = Set.of(
        "denied-relay", "denied_relay", "deniedrelay");

    /** 动作类别（ASK 仅为历史兼容保留；本类不再返回 ASK）。 */
    public enum Action {
        ALLOW,
        /** @deprecated peer 回合不再做二次确认；审批由目标会话既有管线产生。 */
        @Deprecated
        ASK,
        DENY
    }

    /**
     * 门禁判定结果。
     *
     * @param action  ALLOW / DENY（ASK 已不再产生）
     * @param code    机器可读原因码（写入 {@link PermissionDecisionReason.Other}）
     * @param message 给模型/用户的可读说明（deny 非空）
     */
    public record Decision(Action action, String code, String message) {
        public boolean isAllow() {
            return action == Action.ALLOW;
        }

        public boolean isAsk() {
            return action == Action.ASK;
        }

        public boolean isDeny() {
            return action == Action.DENY;
        }

        /**
         * 转 {@link PermissionResult}。仅对 ASK / DENY 有业务意义；
         * ALLOW 仅供调用方判断，不构造 PermissionResult（避免伪造 allow 语义）。
         */
        public PermissionResult toPermissionResult(JsonNode input) {
            return switch (action) {
                case DENY -> new PermissionResult.Deny(
                    message, new PermissionDecisionReason.Other(code), null);
                case ASK -> new PermissionResult.Ask(
                    message, new PermissionDecisionReason.Other(code),
                    List.of(), null, null, null, false, null, List.of());
                case ALLOW -> new PermissionResult.Allow(
                    input != null ? input : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                    new PermissionDecisionReason.Other(code), null, false, null, List.of());
            };
        }

        /** 最终执行点拒绝结果（tool_result error）。 */
        public ToolResult<?> toToolResult(String toolUseId) {
            return ToolResult.error(toolUseId, message, "permission");
        }
    }

    private static final Decision ALLOW = new Decision(Action.ALLOW, "peer_allow", null);

    /** 恒 Deny：唯一两个模型可触发写 SESSION 权限态的工具。 */
    private static final Set<String> PLAN_MODE_TOOLS = Set.of(
        ToolNameConstants.ENTER_PLAN_MODE_TOOL_NAME,
        ToolNameConstants.EXIT_PLAN_MODE_TOOL_NAME);

    /** 写配置工具（GET 只读，SET 被单独拒绝）。 */
    private static final Set<String> CONFIG_TOOLS = Set.of(ToolNameConstants.CONFIG_TOOL_NAME);

    /** 写目标文件名（任意目录）需保护。 */
    private static final Set<String> PROTECTED_FILE_NAMES = Set.of(
        "claude.md", "claude.local.md", "agents.md");

    /** 解析写目标时使用的入参键（Write/Edit/NotebookEdit 等）。 */
    private static final List<String> PATH_KEYS = List.of(
        "file_path", "notebook_path", "path", "target_file", "filePath");

    private PeerActionGuard() {
        // 工具类
    }

    /**
     * 完整门禁 · resolver 首段 / pipeline 入口共用。
     *
     * @param origin peer 执行来源（null = 正常用户回合 → 直接 ALLOW）
     * @param tool   工具实例（null → Deny，无法核验提权面）
     * @param input  已生效 input（hook updatedInput 已应用）
     * @param ctx    工具调用上下文（仅为路径基线；可为 null）
     */
    public static Decision check(PeerExecutionOrigin origin, Tool tool, JsonNode input, ToolUseContext ctx) {
        if (origin == null) {
            return ALLOW;
        }
        Decision relay = deniedRelay(origin);
        if (relay != null) {
            return relay;
        }
        if (tool == null) {
            return deny("peer_tool_missing",
                PREFIX + "the tool instance is missing; cannot verify that this action is safe.");
        }
        String name = tool.name();
        if (isBlank(name)) {
            return deny("peer_tool_unnamed", PREFIX + "the tool has no name; refusing to verify it.");
        }
        // 1) 恒 Deny：plan 模式权限写入
        if (PLAN_MODE_TOOLS.contains(name)) {
            return deny("peer_plan_mode_change",
                PREFIX + name + " cannot be used from a peer conversation: a peer may not change this "
                    + "session's permission mode (plan-mode transitions can restore bypass permissions).");
        }
        // 2) 恒 Deny：Config 写（GET 无 value 为只读，放行）
        if (CONFIG_TOOLS.contains(name) && isConfigWrite(input)) {
            return deny("peer_config_write",
                PREFIX + "writing configuration values is not allowed from a peer conversation.");
        }
        if (CONFIG_TOOLS.contains(name)) {
            return ALLOW;
        }
        // 3) 写工具路径核验（规范化 realpath）
        String sensitive = sensitiveWriteTarget(name, tool, input, ctx);
        if (sensitive != null) {
            return deny("peer_protected_write_target",
                PREFIX + "refusing to write a protected configuration/instruction target: " + sensitive);
        }
        // 4) 其余工具（Bash/Write/Edit/MCP/Skill/Agent/未知）：不在 peer 层拦截，
        //    交给目标会话既有权限管线（default 弹审批、bypass 执行、显式规则照旧）。
        return ALLOW;
    }

    /**
     * 最终执行点门禁：在 updatedInput / 用户改写之后、{@code tool.execute} 之前调用。
     * 只做提权面 Deny，不 Ask、不拦普通副作用。
     */
    public static Decision checkFinal(PeerExecutionOrigin origin, Tool tool, JsonNode input, ToolUseContext ctx) {
        if (origin == null) {
            return ALLOW;
        }
        Decision relay = deniedRelay(origin);
        if (relay != null) {
            return relay;
        }
        if (tool == null) {
            return deny("peer_tool_missing",
                PREFIX + "the tool instance is missing; refusing to execute.");
        }
        String name = tool.name();
        if (PLAN_MODE_TOOLS.contains(name)) {
            return deny("peer_plan_mode_change",
                PREFIX + name + " cannot be used from a peer conversation.");
        }
        if (CONFIG_TOOLS.contains(name) && isConfigWrite(input)) {
            return deny("peer_config_write",
                PREFIX + "writing configuration values is not allowed from a peer conversation.");
        }
        String sensitive = sensitiveWriteTarget(name, tool, input, ctx);
        if (sensitive != null) {
            return deny("peer_protected_write_target",
                PREFIX + "refusing to write a protected configuration/instruction target: " + sensitive);
        }
        return ALLOW;
    }

    // ───────────────────────────── 内部判定 ─────────────────────────────

    /**
     * 结构化 denied 转交标记：拒绝并让用户看到原因。
     *
     * @return 命中时返回 DENY；否则 null
     */
    private static Decision deniedRelay(PeerExecutionOrigin origin) {
        String kind = origin.sourceKind();
        if (kind == null) {
            return null;
        }
        String normalized = kind.trim().toLowerCase(Locale.ROOT);
        if (!DENIED_RELAY_SOURCE_KINDS.contains(normalized)) {
            return null;
        }
        return deny("peer_denied_relay",
            PREFIX + "this request relays an action that was denied or blocked in the sending session. "
                + "Relaying a denied action between sessions is cross-session permission laundering; "
                + "it is refused. Ask your user to decide or perform this action directly.");
    }

    /**
     * 拒绝消息统一以 "Permission denied: " 开头 —— 让执行器
     * {@code LlmAgentLoop.isToolErrorData} 识别为错误（否则最终 guard 写入的 tool_result 会被
     * 推导成 isError=false 的假成功），同时给用户可读原因。
     */
    private static Decision deny(String code, String message) {
        String readable = message != null && message.startsWith("Permission denied")
            ? message : "Permission denied: " + message;
        if (log.isInfoEnabled()) {
            log.info("PEER GUARD deny: code={} message={}", code, readable);
        }
        return new Decision(Action.DENY, code, readable);
    }

    private static boolean isConfigWrite(JsonNode input) {
        return input != null && input.has("value") && !input.get("value").isNull();
    }

    private static boolean safeIsReadOnly(Tool tool, JsonNode input) {
        try {
            return tool.isReadOnly(input);
        } catch (Throwable th) {
            if (log.isDebugEnabled()) {
                log.debug("PEER GUARD isReadOnly 判定异常，按非只读处理: tool={} err={}",
                    tool.name(), th.toString());
            }
            return false;
        }
    }

    /**
     * 解析写工具目标并判定是否为受保护配置/指令路径。
     *
     * @return 命中时返回规范化后的真实路径；否则 null
     */
    static String sensitiveWriteTarget(String toolName, Tool tool, JsonNode input, ToolUseContext ctx) {
        if (input == null) {
            return null;
        }
        // 只读工具读 CLAUDE.md/settings 不拦（读取不是配置写入）
        if (safeIsReadOnly(tool, input) && !PLAN_MODE_TOOLS.contains(toolName)) {
            return null;
        }
        String raw = firstPath(input);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path cwd = cwdOf(ctx);
        Path real = canonicalize(expandUser(raw, cwd));
        if (real == null) {
            return null;
        }
        String resolved = real.toString();
        // 1) 任意目录下的 CLAUDE.md / CLAUDE.local.md / AGENTS.md
        Path fileName = real.getFileName();
        if (fileName != null
                && PROTECTED_FILE_NAMES.contains(fileName.toString().toLowerCase(Locale.ROOT))) {
            return resolved;
        }
        // 2) 危险配置文件/目录（.claude / .{appName} / .git / settings.json 等）
        if (PathValidation.isDangerousFilePathToAutoEdit(resolved)
                || PathValidation.isClaudeConfigFilePath(resolved, cwd.toString())) {
            return resolved;
        }
        // 3) 配置自有根（{user.home}/.{appName}）子树
        Path configHome = canonicalize(NexusaiPaths.getAppConfigHomePath());
        if (configHome != null && isWithin(real, configHome)) {
            return resolved;
        }
        // 4) 全局配置文件 {user.home}/.{appName}.json
        Path globalConfig = canonicalize(Path.of(System.getProperty("user.home", "."),
            NexusaiPaths.getGlobalConfigFileName()));
        if (globalConfig != null && real.equals(globalConfig)) {
            return resolved;
        }
        return null;
    }

    private static String firstPath(JsonNode input) {
        for (String key : PATH_KEYS) {
            JsonNode v = input.get(key);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private static Path cwdOf(ToolUseContext ctx) {
        try {
            if (ctx != null && ctx.effectiveCwd() != null) {
                return ctx.effectiveCwd().toAbsolutePath().normalize();
            }
        } catch (Throwable th) {
            if (log.isDebugEnabled()) {
                log.debug("PEER GUARD 读取 effectiveCwd 失败，回落进程 cwd: {}", th.toString());
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    /** {@code ~} / {@code ~/x} 展开为 user.home（不改写其他 shell 语法）。 */
    static Path expandUser(String raw, Path cwd) {
        String s = raw == null ? "" : raw.trim();
        if (s.equals("~")) {
            return Path.of(System.getProperty("user.home", "."));
        }
        if (s.startsWith("~/") || s.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home", "."), s.substring(2));
        }
        Path p = Path.of(s);
        return p.isAbsolute() ? p : cwd.resolve(p);
    }

    /**
     * 规范化 realpath：存在则 {@link Path#toRealPath()}；不存在则对最近存在祖先 realpath 后拼接剩余段。
     * 用于防符号链接 / 目录穿越 / 不存在文件绕过。失败时退回绝对规范化路径（仍会做段级前缀比较）。
     */
    static Path canonicalize(Path path) {
        if (path == null) {
            return null;
        }
        try {
            Path abs = path.toAbsolutePath().normalize();
            int guard = 0;
            Path probe = abs;
            Path remainder = null;
            while (probe != null && !Files.exists(probe) && guard++ < 64) {
                Path name = probe.getFileName();
                remainder = (remainder == null) ? name : (name == null ? remainder : name.resolve(remainder));
                probe = probe.getParent();
            }
            if (probe == null) {
                return abs;
            }
            Path real = probe.toRealPath();
            return remainder == null ? real : real.resolve(remainder.toString());
        } catch (IOException | RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("PEER GUARD realpath 解析失败，回落规范化路径: path={} err={}", path, e.toString());
            }
            return path.toAbsolutePath().normalize();
        }
    }

    /** 段级包含判断（Path.startsWith 按路径段比较，避免 "nexusaiX" 前缀碰撞）。 */
    static boolean isWithin(Path child, Path root) {
        return child != null && root != null
            && (child.equals(root) || child.startsWith(root));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
