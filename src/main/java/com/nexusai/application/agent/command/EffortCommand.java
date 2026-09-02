package com.nexusai.application.agent.command;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionKeys;
import com.nexusai.infra.llm.EffortSupport;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * `/effort` 斜杠命令 handler · 对齐 CC commands/effort/effort.tsx（全文 Read 实证）+ utils/effort.ts。
 *
 * <p>L1 语义：设置/查看模型思考深度档位。无参/current/status → 显示当前档位（env 覆盖 →
 * 会话 effortValue → 模型默认的优先级链，effort.tsx:62-75）；low/medium/high/max → 写<b>当前会话</b>
 * sessions.effort_level（V31 列，经 {@link SessionMapper}）+ 写会话级 AgentState.effortValue；
 * auto/unset → 清当前会话 effort_level + 会话 effortValue；help/-h/--help → 用法；非法值 → 报错列合法值。
 *
 * <p><b>R2 会话级返工</b>（用户拍板 multi-session-vs-cc-single-session：Web 多会话 vs CC 单会话，
 * effort 必须会话级）：CC 单会话以 appState.effortValue 存档位，Web 多会话以 sessions.effort_level
 * 承载（V31）——/effort 不再写全局 settings.effortLevel（V29 列，R3 删除）。对齐 CC effort.tsx:16-27
 * updateSettingsForSource('userSettings', {effortLevel}) 的<b>会话级等价</b>：每会话一份档位，跨 run 结转。
 *
 * <p><b>env 覆盖检测</b>（对齐 effort.tsx:32-51）：CLAUDE_CODE_EFFORT_LEVEL 在
 * resolveAppliedEffort 时点胜出。仅当 env 覆盖与会话用户请求<b>冲突</b>（env 钉死另一档位或
 * env='unset'/'auto' 显式抑制）才提示；env 与请求一致则 "Set effort to X" 为真、提示是噪音
 * （effort.tsx:33-34 注释）。
 *
 * <p>L3（Java idiom）：TS `onDone(message)` → Java 返回 {@link EffortCommandResult}（message +
 * effortValue 会话更新值）；TS `useAppState`/`setAppState` → 经
 * {@link SessionAgentStateRegistry} 按 MDC sessionId 解析主会话 {@link AgentState}（P1-6 注册表）；
 * TS `updateSettingsForSource('userSettings', {effortLevel})` → 会话级 {@link SessionMapper}
 * （写 sessions.effort_level，V31 列）。
 */
@Component
public class EffortCommand {

    private static final Logger log = LoggerFactory.getLogger(EffortCommand.class);

    /** CC effort.tsx:9 COMMON_HELP_ARGS = ['help', '-h', '--help']。 */
    private static final List<String> COMMON_HELP_ARGS = List.of("help", "-h", "--help");

    /** CC effort.tsx:174-175 call() onDone 帮助字面量（原样镜像）。 */
    private static final String HELP_TEXT = "Usage: /effort [low|medium|high|max|auto]\n\n"
        + "Effort levels:\n"
        + "- low: Quick, straightforward implementation\n"
        + "- medium: Balanced approach with standard testing\n"
        + "- high: Comprehensive implementation with extensive testing\n"
        + "- max: Maximum capability with deepest reasoning (Opus 4.6 only)\n"
        + "- auto: Use the default effort level for your model";

    /**
     * 环境变量读取器 · 测试可覆写接缝（对齐 BuiltInCommands.envProvider 模式，同包测试覆写后
     * 须在 finally 还原）。默认 {@link System#getenv}。
     */
    static volatile java.util.function.Function<String, String> envProvider = System::getenv;

    private final SessionMapper sessionMapper;
    private final SessionAgentStateRegistry sessionAgentStateRegistry;

    @Autowired
    public EffortCommand(SessionMapper sessionMapper,
                         SessionAgentStateRegistry sessionAgentStateRegistry) {
        this.sessionMapper = sessionMapper;
        this.sessionAgentStateRegistry = sessionAgentStateRegistry;
    }

    /** 读取 CLAUDE_CODE_EFFORT_LEVEL 三态 · 经 {@link #envProvider} 接缝（测试可覆写）。 */
    private EffortSupport.EffortEnvOverride readEnvOverride() {
        return EffortSupport.parseEnvState(envProvider.apply("CLAUDE_CODE_EFFORT_LEVEL"));
    }

    /** 命令输出 · 对齐 CC effort.tsx:10-15 EffortCommandResult {message, effortUpdate?:{value}}。 */
    public record EffortCommandResult(String message, String effortValue) {}

    /**
     * 主入口 · 对齐 CC effort.tsx:171-182 call() 参数分支（trim → help → current → executeEffort）。
     *
     * @param args 斜杠命令参数字符串（可为 null/空）
     */
    public EffortCommandResult handle(String args) {
        String trimmed = args == null ? "" : args.trim();
        if (COMMON_HELP_ARGS.contains(trimmed)) {
            if (log.isDebugEnabled()) {
                log.debug("[EffortCommand] /effort 请求帮助（args='{}'）→ 返回用法说明", trimmed);
            }
            return new EffortCommandResult(HELP_TEXT, null);
        }
        if (trimmed.isEmpty() || "current".equals(trimmed) || "status".equals(trimmed)) {
            return showCurrentEffort();
        }
        return executeEffort(trimmed);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 对齐 CC effort.tsx 各分支
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 显示当前档位 · CC original: {@code showCurrentEffort}（effort.tsx:62-75）。
     *
     * <p>effectiveValue = envOverride === null ? undefined : envOverride ?? appStateEffort
     * （effort.tsx:64）：env='unset'/'auto' 显式抑制 → 无有效值；env 档位覆盖 → 覆盖值；
     * env 缺失/无效 → 会话 effortValue。无有效值 → 展示 resolveAppliedEffort 模型默认
     * （{@code Effort level: auto (currently X)}，getDisplayedEffortLevel 兜底 'high'）；有有效值 →
     * {@code Current effort level: X (desc)}。
     */
    private EffortCommandResult showCurrentEffort() {
        String appStateEffort = resolveSessionEffortValue();
        String model = resolveCurrentModel();
        EffortSupport.EffortEnvOverride env = readEnvOverride();
        String effectiveValue;
        if (env.suppress()) {
            effectiveValue = null;      // CC envOverride === null → undefined
        } else if (env.level() != null) {
            effectiveValue = env.level();   // envOverride ?? appStateEffort
        } else {
            effectiveValue = appStateEffort;
        }
        if (effectiveValue == null) {
            String level = EffortSupport.getDisplayedEffortLevel(model, appStateEffort);
            String message = "Effort level: auto (currently " + level + ")";
            if (log.isDebugEnabled()) {
                log.debug("[EffortCommand] /effort 当前档位展示: auto（会话 effort={}，模型={}，展示档位={}）",
                    appStateEffort, model, level);
            }
            return new EffortCommandResult(message, null);
        }
        String description = EffortSupport.getEffortValueDescription(effectiveValue);
        String message = "Current effort level: " + effectiveValue + " (" + description + ")";
        if (log.isDebugEnabled()) {
            log.debug("[EffortCommand] /effort 当前档位展示: '{}'（env 覆盖={}，会话 effort={}，模型={}）",
                effectiveValue, env.raw(), appStateEffort, model);
        }
        return new EffortCommandResult(message, null);
    }

    /**
     * 执行 set 分支 · CC original: {@code executeEffort}（effort.tsx:107-118）。
     *
     * <p>auto/unset → {@link #unsetEffortLevel()}；非 {@code EFFORT_LEVELS}（low/medium/high/xhigh/max）→
     * 非法值报错（effort.tsx:113-115 字面量；[用户拍板 2026-08-22] 合法值加 xhigh，对齐 CC 新版本 UI）；否则
     * {@link #setEffortValue}。
     */
    private EffortCommandResult executeEffort(String args) {
        String normalized = args.toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized) || "unset".equals(normalized)) {
            return unsetEffortLevel();
        }
        // [V32] ultracode 特殊档：ultracode = xhigh effort + workflows 编排启用（用户拍板后端应有此概念）。
        //   effort 层落 xhigh（对齐前端 mapToBackend），sessions.ultracode_enabled 置 true；workflows 编排
        //   执行 Java 未实现（WorkflowTool stub，workflow-align 后置），本期持久化开关 + 展示。
        if ("ultracode".equals(normalized)) {
            return setUltracodeMode();
        }
        if (!EffortSupport.isEffortLevel(normalized)) {
            String message = "Invalid argument: " + args + ". Valid options are: low, medium, high, xhigh, max, ultracode, auto";
            if (log.isDebugEnabled()) {
                log.debug("[EffortCommand] /effort 非法参数 '{}' → 报错列合法值", args);
            }
            return new EffortCommandResult(message, null);
        }
        return setEffortValue(normalized);
    }

    /**
     * 启用 ultracode 模式（[V32]）· 会话级：sessions.ultracode_enabled=true + effort_level=xhigh。
     *
     * <p>ultracode = xhigh effort + workflows 编排启用（用户定义）。本期持久化开关 + effort 落 xhigh；
     * workflows 编排执行 Java 未实现（WorkflowTool stub，探查 workflow-align P0-P3 后置）——待编排就绪后
     * 本模式自动触发 workflow 脚本执行。env 冲突判定同 setEffortValue（CLAUDE_CODE_EFFORT_LEVEL 覆盖 effort 层）。
     */
    private EffortCommandResult setUltracodeMode() {
        try {
            writeSessionUltracode(true);
        } catch (Exception e) {
            log.warn("[EffortCommand] /effort ultracode 写入会话失败: {}", e.getMessage());
            return new EffortCommandResult("Failed to enable ultracode: " + e.getMessage(), null);
        }
        // effort 层落 xhigh
        EffortSupport.EffortEnvOverride env = readEnvOverride();
        String message = env.hasOverride() && !java.util.Objects.equals(env.level(), "xhigh")
            ? "Ultracode enabled (effort xhigh), but CLAUDE_CODE_EFFORT_LEVEL=" + env.raw() + " overrides this session"
            : "Ultracode enabled: xhigh effort + workflows（workflows 编排待 Java 实现）";
        if (log.isDebugEnabled()) {
            log.debug("[EffortCommand] /effort ultracode 启用：会话 ultracode_enabled=true + effort_level=xhigh");
        }
        applyEffortValue("xhigh");
        return new EffortCommandResult(message, "xhigh");
    }

    /**
     * 设置档位 · CC original: {@code setEffortValue}（effort.tsx:16-61）。
     *
     * <p><b>R2 会话级返工</b>：写<b>当前会话</b> sessions.effort_level（V31 列，经
     * {@link SessionMapper}，对齐 CC effort.tsx:16-27 updateSettingsForSource 持久化的会话级等价）
     * —— 不再写全局 settings.effortLevel。low/medium/high/max 全量落会话（CC toPersistableEffort
     * 的"settings 落盘 vs 会话级"二分随 settings 删除消失；max 本就是 CC 会话级档位，effort.ts:95-105）。
     * env 冲突判定 {@code envOverride !== undefined && envOverride !== effortValue}
     * （effort.tsx:35-51）：冲突 → 会话值仍生效（effortUpdate）但消息提示 env 覆盖胜出；无冲突 →
     * 成功消息 {@code Set effort level to X: desc}。写会话失败 → 仅报错不设会话（CC 返回无 effortUpdate）。
     */
    private EffortCommandResult setEffortValue(String effortValue) {
        try {
            writeSessionEffort(effortValue);
        } catch (Exception e) {
            log.warn("[EffortCommand] /effort 写入会话 effort_level 失败: {} - {}", effortValue,
                e.getMessage());
            return new EffortCommandResult("Failed to set effort level: " + e.getMessage(), null);
        }
        EffortSupport.EffortEnvOverride env = readEnvOverride();
        if (env.hasOverride() && !java.util.Objects.equals(env.level(), effortValue)) {
            // CC effort.tsx:35-51 env 冲突分支（含 env 显式抑制态 —— level=null 恒 ≠ 请求档位）。
            // R2：settings 二分已删（全会话级），统一提示 env 覆盖胜出；会话值仍写入（effortUpdate）。
            String message = "CLAUDE_CODE_EFFORT_LEVEL=" + env.raw()
                + " overrides this session — clear it and " + effortValue + " takes over";
            if (log.isDebugEnabled()) {
                log.debug("[EffortCommand] /effort set '{}' 被 env 覆盖（{}）→ 会话仍写但提示清 env",
                    effortValue, env.raw());
            }
            applyEffortValue(effortValue);
            return new EffortCommandResult(message, effortValue);
        }
        String description = EffortSupport.getEffortValueDescription(effortValue);
        String message = "Set effort level to " + effortValue + ": " + description;
        if (log.isDebugEnabled()) {
            log.debug("[EffortCommand] /effort set '{}' 成功（env 无冲突）→ 会话写入",
                effortValue);
        }
        applyEffortValue(effortValue);
        return new EffortCommandResult(message, effortValue);
    }

    /**
     * 清除档位（auto/unset）· CC original: {@code unsetEffortLevel}（effort.tsx:76-106）。
     *
     * <p><b>R2 会话级返工</b>：清当前会话 sessions.effort_level（V31 列置 NULL，MyBatis-Flex
     * update(entity) 默认忽略 null → 显式 update(entity, false)，对齐 ProjectSessionBindingService.unbind）
     * + 会话 effortValue=null。env 钉死档位（pinsLevel）→ 提示 env 仍控制本会话；否则
     * {@code Effort level set to auto}。
     */
    private EffortCommandResult unsetEffortLevel() {
        try {
            writeSessionEffort(null);   // effortValue=null 时已在 writeSessionEffort 内连带清 ultracode（V32）
        } catch (Exception e) {
            log.warn("[EffortCommand] /effort auto 清除会话 effort_level 失败: {}", e.getMessage());
            return new EffortCommandResult("Failed to set effort level: " + e.getMessage(), null);
        }
        EffortSupport.EffortEnvOverride env = readEnvOverride();
        if (env.pinsLevel()) {
            String message = "Cleared effort from session, but CLAUDE_CODE_EFFORT_LEVEL=" + env.raw()
                + " still controls this session";
            if (log.isDebugEnabled()) {
                log.debug("[EffortCommand] /effort auto 已清会话，但 env {} 钉死本会话 → 提示", env.raw());
            }
            applyEffortValue(null);
            return new EffortCommandResult(message, null);
        }
        if (log.isDebugEnabled()) {
            log.debug("[EffortCommand] /effort auto 清除完成（会话 effort_level + AgentState），无 env 钉死 → 成功");
        }
        applyEffortValue(null);
        return new EffortCommandResult("Effort level set to auto", null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 当前会话解析 / 会话级持久化（对齐 CC updateSettingsForSource 的会话级等价）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 解析当前会话 DB 主键 · 经 {@link RequestContext#sessionId()}（MDC）取当前会话标识。
     *
     * <p>[session-id-short] MDC sessionId 已统一 short（sess-xxx）→ 直返；存量旧 DB 行
     * （派生 UUID 串）保留 {@link SessionKeys#originalKey(String)} 兜底分支（@Deprecated 兼容层）。
     * 无 MDC sessionId（命令缺会话上下文）→ null（写会话跳过，保既有 null-safe 模式）。
     */
    private String resolveSessionKey() {
        String sessionIdStr = RequestContext.sessionId();
        if (sessionIdStr == null) {
            return null;
        }
        String key = SessionKeys.originalKey(sessionIdStr);
        return key != null ? key : sessionIdStr;
    }

    /**
     * 写当前会话 sessions.effort_level · 对齐 CC effort.tsx:16-27
     * updateSettingsForSource('userSettings', {effortLevel}) 持久化的<b>会话级等价</b>（V31 列）。
     *
     * <p>load-modify-update：selectOneById → setEffortLevel → update。清除（effortValue=null）需
     * 显式 {@code update(s, false)}（MyBatis-Flex {@code update(entity)} 默认
     * {@code $$ignoreNulls=true} 忽略 null 字段，ProviderUtil.isIgnoreNulls），对齐
     * ProjectSessionBindingService.unbind 同款；设置非 null 走 {@code update(s)}。无 MDC sessionId /
     * 会话不存在 → debug skip（不阻断命令；AgentState 运行时写入由 {@link #applyEffortValue} 独立处理）。
     *
     * @param effortValue 档位（low/medium/high/max）或 null（= 清除会话 effort_level）
     */
    /**
     * [V32] 写会话 ultracode 开关 + effort 层（ultracode_enabled + effort_level=xhigh 同步）。
     *
     * @param enabled true = 启用 ultracode（effort_level=xhigh）；false = 关闭（effort_level 置 xhigh 兜底）
     */
    private void writeSessionUltracode(boolean enabled) {
        String sessionKey = resolveSessionKey();
        if (sessionKey == null) {
            if (log.isDebugEnabled()) {
                log.debug("[EffortCommand] 无 MDC sessionId → ultracode 会话写入跳过（仅 AgentState 运行时）");
            }
            return;
        }
        SessionRecord s = sessionMapper.selectOneById(sessionKey);
        if (s == null) {
            if (log.isDebugEnabled()) {
                log.debug("[EffortCommand] 会话 '{}' 不存在 → ultracode 写入跳过", sessionKey);
            }
            return;
        }
        s.setUltracodeEnabled(enabled ? 1 : 0);
        s.setEffortLevel("xhigh");
        sessionMapper.update(s);
        if (log.isDebugEnabled()) {
            log.debug("[EffortCommand] 会话 ultracode 写入: session='{}' enabled={} effort=xhigh（V32）", sessionKey, enabled);
        }
    }

    private void writeSessionEffort(String effortValue) {
        String sessionKey = resolveSessionKey();
        if (sessionKey == null) {
            if (log.isDebugEnabled()) {
                log.debug("[EffortCommand] 无 MDC sessionId（无会话上下文）→ 会话 effort_level 写入跳过（仅 AgentState 运行时写入）");
            }
            return;
        }
        SessionRecord s = sessionMapper.selectOneById(sessionKey);
        if (s == null) {
            if (log.isDebugEnabled()) {
                log.debug("[EffortCommand] 会话 '{}' 不存在 → effort_level 写入跳过（会话级持久化不可用）", sessionKey);
            }
            return;
        }
        s.setEffortLevel(effortValue);
        if (effortValue == null) {
            // [V32] auto/unset 连带清 ultracode 开关（单次 update 完成，避免双调用）
            s.setUltracodeEnabled(0);
            sessionMapper.update(s, false);   // 显式写 NULL（update(entity) 默认忽略 null 字段）
        } else {
            sessionMapper.update(s);
        }
        if (log.isDebugEnabled()) {
            log.debug("[EffortCommand] 会话 effort_level 已写入: session='{}' effort='{}'（对齐 CC effort.tsx updateSettingsForSource 会话级等价）",
                sessionKey, effortValue);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 会话 AgentState 解析 / 写入（对齐 CC setAppState effortValue 语义）
    // ════════════════════════════════════════════════════════════════════════

    /** 会话级 effort 值 · 对齐 CC appState.effortValue（AgentState.java:652-671 [C-31]）。 */
    private String resolveSessionEffortValue() {
        AgentState state = resolveSessionState();
        return state != null ? state.effortValue() : null;
    }

    /** 会话当前模型 · 对齐 CC useMainLoopModel → AgentState.currentModel()（RES-C7）。 */
    private String resolveCurrentModel() {
        AgentState state = resolveSessionState();
        return state != null ? state.currentModel() : null;
    }

    /**
     * 写会话级 effort 值 · 对齐 CC ApplyEffortAndClose（effort.tsx:134-170
     * {@code setAppState(prev => ({...prev, effortValue}))}）。
     *
     * <p>经 {@link SessionAgentStateRegistry} 按 MDC sessionId 解析主会话 AgentState；
     * 无会话上下文 / 未注册 → debug skip（保测试兼容，对齐 CommandController 既有 null-safe 模式）。
     */
    private void applyEffortValue(String effortValue) {
        AgentState state = resolveSessionState();
        if (state == null) {
            if (log.isDebugEnabled()) {
                log.debug("[EffortCommand] 会话 AgentState 不可得（无 MDC sessionId 或未注册）→ 会话 effortValue 写入跳过");
            }
            return;
        }
        state.setEffortValue(effortValue);
        if (log.isDebugEnabled()) {
            log.debug("[EffortCommand] 会话 AgentState.effortValue 已写入: '{}'（对齐 CC setAppState effortValue）",
                effortValue);
        }
    }

    /** 按 MDC sessionId 解析主会话 AgentState（P1-6 注册表），不可得 → null。
     *  [session-id-short] MDC sessionId 已 short 直键 registry（不再 UUID.fromString）。 */
    private AgentState resolveSessionState() {
        if (sessionAgentStateRegistry == null) {
            return null;
        }
        String sessionIdStr = RequestContext.sessionId();
        if (sessionIdStr == null) {
            return null;
        }
        return sessionAgentStateRegistry.get(sessionIdStr);
    }
}
