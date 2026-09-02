package com.nexusai.application.agent.command;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.team.Teammate;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.common.RequestContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Agent color 命令 · 对齐 CC commands/color/color.ts:20-93 call.
 *
 * <p>L1 语义: /color [name] — 设置 subagent color; teammate 不能改; reset aliases ('default'/'reset'/...)
 *            → reset to default; 无效 color → error; 有效 → save + AppState update.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `execute(args, env) → CommandResult` 签名</li>
 *   <li><b>A2 Golden Trace</b>: teammate → system error; empty args → 列出可用 colors; reset aliases → "reset to default" + save 'default'; 有效 color → save + set AppState; 无效 color → error</li>
 *   <li><b>A3</b>: 大小写不敏感 (CC `args.trim().toLowerCase()`)</li>
 *   <li><b>A4</b>: null args → empty args; whitespace-only → empty args; reset aliases 优先于有效 colors</li>
 *   <li><b>A5</b>: 真实 — args="BLUE" (大写) → 小写后匹配 → "Session color set to: blue"</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Consumer&lt;String&gt; onDone; Supplier&lt;Boolean&gt; isTeammate + Supplier&lt;UUID&gt; sessionId +
 *                    BiFunction&lt;UUID,String,CF&lt;Void&gt;&gt; saveAgentColor + Supplier&lt;List&lt;String&gt;&gt; agentColors 注入测试可控.
 *
 * <p><b>R-B1 · B-1 颜色 API 暴露（D4+D11 数据通道 → 前端可见）</b>：本类由 @Component 升级为
 * {@code @RestController}（@RestController 本身即 @Component 元注解，斜杠命令注册与依赖注入不受影响），
 * 新增 {@code GET /api/agents/{type}/color} 端点——暴露 {@link SubagentTool#getAgentColor} 读侧
 * （CC agentColorManager.ts:36-50 唯一真源），使 agentColorMap 由"只写不读"变为前端可查。
 * （探查 GAP-5/M-6/M-7/WF-C-UN-1：Java agentColors 只写不读，颜色不进入任何 UI/事件/DTO。）
 */
@RestController
public class AgentColorCommand {

    private static final Logger log = LoggerFactory.getLogger(AgentColorCommand.class);

    /** CC color.ts:18 — reset aliases. */
    public static final List<String> RESET_ALIASES = List.of("default", "reset", "none", "gray", "grey");

    @Autowired(required = false)
    private UserInputDispatcher userInputDispatcher;

    @Autowired(required = false)
    private SessionAgentStateRegistry sessionAgentStateRegistry;

    /**
     * 颜色读侧数据源 · 对齐 CC agentColorManager.ts:36-50（R-B1 · B-1 颜色 API 暴露）。
     *
     * <p>{@code @Autowired(required=false)}：plain JUnit（无 Spring 容器）缺省 null →
     * 颜色 API 返回 null（不 NPE，保测试兼容）。生产装配后经 {@link SubagentTool#getAgentColor}
     * 读取 agentColorMap 主题色 key（如 "blue_FOR_SUBAGENTS_ONLY"）。
     */
    @Autowired(required = false)
    private SubagentTool subagentTool;

    /**
     * /color 生产注册 · 对齐 CC commands.ts COMMANDS 中 color 命令注册（D4/去重③）。
     *
     * <p>WHY（探查 GAP-3/M-3/C15）：UserInputDispatcher 生产仅注册 /compact，/color 未注册 →
     * AgentColorCommand 0 生产调用方、命令不可达。本 @PostConstruct 在 Spring 装配后把
     * "/color" 注册进 {@link UserInputDispatcher#registerSlashCommand}，handler 用生产 Env
     * 执行（session 经 RequestContext MDC 解析，颜色落 {@link AgentState#setColor}）。
     * plain JUnit（无 Spring 容器）缺省 null → 注册跳过并 log.warn（fail loud）。
     */
    @PostConstruct
    public void registerSlashCommand() {
        if (userInputDispatcher == null) {
            log.warn("[AgentColorCommand] UserInputDispatcher 未注入，/color 生产注册跳过");
            return;
        }
        userInputDispatcher.registerSlashCommand("color", args -> {
            Env env = buildProductionEnv();
            CommandResult r = execute(args, env);
            if (log.isDebugEnabled()) {
                log.debug("[AgentColorCommand] /color 执行完成: handled={} message={} display={}",
                    r.handled(), r.message(), r.display());
            }
        });
        log.info("[AgentColorCommand] /color 已注册为生产 slash command（对齐 CC color.ts /color 命令）");
    }

    /**
     * 生产 Env 装配 · 对齐 CC color.ts call() 依赖（teammate/sessionId/transcriptPath/
     * saveAgentColor/setAppState 均由运行时上下文解析）。
     *
     * <p>web 后端映射（Java idiom，接口 Spring）：
     * <ul>
     *   <li>{@code isTeammate} → {@link Teammate#isTeammate()}（CC utils/teammate.ts:125-137）</li>
     *   <li>{@code sessionId} → {@link RequestContext#sessionId()}（MDC，ChatService 已 set）</li>
     *   <li>{@code transcriptPath} → {@link SessionStorage#resolveExistingTranscript}（工作区 + sessionId，
     *       D3 读兼容：仅 nexusai 自有 transcript）</li>
     *   <li>{@code agentColors} → {@link SubagentTool#AGENT_COLORS} 共享常量真源（探查 △-3）</li>
     *   <li>{@code saveAgentColor} → {@link SessionStorage#reAppendSessionMetadata} 持久化
     *       agent-color entry + {@link AgentState#setColor} 缓存 currentSessionAgentColor</li>
     *   <li>{@code setAppStateColor} → 会话级 {@link AgentState#setColor}（CC standaloneAgentContext.color）</li>
     *   <li>{@code onDone} → log（web 无 TUI，CommandResult 承载展示文本）</li>
     * </ul>
     */
    private Env buildProductionEnv() {
        return new Env(
            Teammate::isTeammate,
            AgentColorCommand::resolveSessionUuid,
            AgentColorCommand::resolveTranscriptPath,
            () -> SubagentTool.AGENT_COLORS,
            this::persistAgentColor,
            this::setAppStateColor,
            msg -> {
                if (log.isDebugEnabled()) {
                    log.debug("[AgentColorCommand] onDone: {}", msg);
                }
            });
    }

    /** 从 RequestContext（MDC）解析当前会话 UUID · null = 无会话上下文。 */
    private static UUID resolveSessionUuid() {
        String raw = RequestContext.sessionId();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** transcript 路径（CC getTranscriptPath() · sessionStorage.ts）· 无会话 → null。
     *  <b>D3 读兼容</b>：走 {@link SessionStorage#resolveExistingTranscript} 读 nexusai
     *  自有 transcript（仅 nexusai 会话，无 claude ~/.claude/projects 回落）。 */
    private static String resolveTranscriptPath() {
        UUID sid = resolveSessionUuid();
        if (sid == null) {
            return null;
        }
        Path transcript = SessionStorage.resolveExistingTranscript(workspaceDir(), sid.toString());
        return transcript != null ? transcript.toString() : null;
    }

    /**
     * 会话存档根 · 对齐 CC {@code sessionStorage.ts:202-205 getTranscriptPath()}:
     * {@code projectDir = getSessionProjectDir() ?? getProjectDir(getOriginalCwd())}。
     *
     * <p><b>WF-1C / DEL-05 / G8</b>：旧实现恒返回 {@code user.dir}（JVM 启动目录），
     * 导致会话绑定项目场景下 transcript 落到启动目录而非项目目录，与 CC 行为漂移。
     * 现走统一入口 {@link CwdResolution#getOriginalCwdLayer(String)}（绑定项目层 ?? user.dir 兜底，
     * D-1 裁决不读 resolve() 回落链），使存档根跟随会话绑定的项目目录。
     *
     * <p>统一入口内含 normalizeCwd（realpath+NFC），各层 safeGet 异常回 null，最终恒非 null 不抛。
     */
    private static Path workspaceDir() {
        return workspaceDirFor(RequestContext.sessionId());
    }

    /**
     * 按显式 sessionId 解析会话存档根（异步线程入口）· 同 {@link #workspaceDir()} 语义，
     * 但不依赖 RequestContext MDC（避免 ForkJoinPool 跨线程丢失 ThreadLocal）。
     *
     * @param sessionId 会话 ID（null/空 → 回落 user.dir 兜底）
     */
    private static Path workspaceDirFor(String sessionId) {
        String root = CwdResolution.getOriginalCwdLayer(sessionId);
        return Path.of(root != null && !root.isBlank() ? root : System.getProperty("user.dir", "."));
    }

    /** 持久化 agent-color entry + 会话缓存（CC sessionStorage.ts saveAgentColor:2838-2852）。 */
    private CompletableFuture<Void> persistAgentColor(UUID sessionId, String color) {
        if (sessionId == null) {
            return CompletableFuture.completedFuture(null);
        }
        // WF-1C: 必须在进入异步线程前解析存档根——CompletableFuture.runAsync 跑在 ForkJoinPool，
        // 不传播 RequestContext MDC（ThreadLocal），异步内取 RequestContext.sessionId() 会得 null
        // → 回落 user.dir（绑定项目场景 transcript 漂移）。sessionId 已是入参，直接据此解析统一入口，
        // 跨线程稳定（对齐 CC saveAgentColor 在异步内用模块级 getSessionId/getSessionProjectDir 而非线程局部态）。
        Path ws = workspaceDirFor(sessionId.toString());
        return CompletableFuture.runAsync(() -> {
            try {
                Path transcript = SessionStorage.getTranscriptPath(ws, sessionId.toString());
                if (transcript != null) {
                    SessionStorage.reAppendSessionMetadata(ws, sessionId.toString(),
                        new SessionStorage.SessionMetadata(null, null, null, null, color,
                            null, null, null, null, null, null));
                }
            } catch (Exception e) {
                log.warn("[AgentColorCommand] saveAgentColor 持久化失败: session={} error={}",
                    sessionId, e.getMessage());
            }
            setAppStateColor(color);
        });
    }

    /** 会话级颜色状态载体（CC standaloneAgentContext.color · color.ts:53-60/82-89）。 */
    private void setAppStateColor(String color) {
        UUID sid = resolveSessionUuid();
        if (sid == null || sessionAgentStateRegistry == null) {
            return;
        }
        AgentState state = sessionAgentStateRegistry.get(sid);
        if (state != null) {
            state.setColor(color);
        }
    }

    public record Env(
        Supplier<Boolean> isTeammate,
        Supplier<UUID> sessionId,
        Supplier<String> transcriptPath,
        Supplier<List<String>> agentColors,
        BiFunction<UUID, String, CompletableFuture<Void>> saveAgentColor,
        Consumer<String> setAppStateColor,
        Consumer<String> onDone
    ) {}

    public record CommandResult(boolean handled, String message, String display) {}

    public CommandResult execute(String args, Env env) {
        if (env.isTeammate.get()) {
            String msg = "Cannot set color: This session is a swarm teammate. Teammate colors are assigned by the team leader.";
            env.onDone.accept(msg);
            return new CommandResult(false, msg, "system");
        }

        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty()) {
            String msg = "Please provide a color. Available colors: " + String.join(", ", env.agentColors.get()) + ", default";
            env.onDone.accept(msg);
            return new CommandResult(false, msg, "system");
        }

        String colorArg = trimmed.toLowerCase();
        if (RESET_ALIASES.contains(colorArg)) {
            UUID sid = env.sessionId.get();
            env.saveAgentColor.apply(sid, "default").join();
            env.setAppStateColor.accept(null);  // CC: color: undefined
            String msg = "Session color reset to default";
            env.onDone.accept(msg);
            return new CommandResult(true, msg, "system");
        }

        List<String> validColors = env.agentColors.get();
        if (!validColors.contains(colorArg)) {
            String msg = "Invalid color \"" + colorArg + "\". Available colors: " + String.join(", ", validColors) + ", default";
            env.onDone.accept(msg);
            return new CommandResult(false, msg, "system");
        }

        UUID sid = env.sessionId.get();
        env.saveAgentColor.apply(sid, colorArg).join();
        env.setAppStateColor.accept(colorArg);
        String msg = "Session color set to: " + colorArg;
        env.onDone.accept(msg);
        return new CommandResult(true, msg, "system");
    }

    /**
     * 颜色读 API · 对齐 CC {@code getAgentColor(agentType)} (agentColorManager.ts:36-50)。
     *
     * <p><b>R-B1 · B-1 颜色 API 暴露（D4+D11 数据通道 → 前端可见）</b>：使 {@link SubagentTool#getAgentColor}
     * 读侧生产可达——前端按 agentType 查子代理颜色。CC 语义（唯一真源）：
     * <ul>
     *   <li>{@code general-purpose} → undefined（早返，不查 map）</li>
     *   <li>agentColorMap 命中且颜色在 {@code AGENT_COLORS} 清单 → 返回主题色 key（如 "blue_FOR_SUBAGENTS_ONLY"）</li>
     *   <li>否则 → undefined</li>
     * </ul>
     *
     * <p>web 后端映射（Java idiom，接口 Spring）：返回 {@link AgentColorResponse}——
     * {@code themeColor}=CC 主题色 key（等价 getAgentColor 返回值）；{@code color}=原始色名
     * （仅当 themeColor 非空时取值，保证 general-purpose / 未命中 / 非法色 → color 与 themeColor 同 null，
     * 对齐 CC undefined 语义）。{@code subagentTool} 未注入（plain JUnit）→ 两者均 null（不 NPE）。
     *
     * @param agentType agent type（路径变量，可为 null → CC undefined）
     * @return {@link AgentColorResponse}（agentType 回显 + color + themeColor）
     */
    @GetMapping("/api/agents/{type}/color")
    public AgentColorResponse getColor(@PathVariable("type") String agentType) {
        if (subagentTool == null) {
            log.warn("[AgentColorCommand] SubagentTool 未注入，颜色 API 返回空（plain JUnit 无 Spring 容器）");
            return new AgentColorResponse(agentType, null, null);
        }
        String themeColor = subagentTool.getAgentColor(agentType);
        // 仅当主题色非空才取原始色名（对齐 CC undefined：general-purpose / 未命中 / 非法色 → 双 null）
        String color = themeColor != null ? subagentTool.getAgentColorMap().get(agentType) : null;
        if (log.isDebugEnabled()) {
            log.debug("[AgentColorCommand] GET /api/agents/{}/color → color={} themeColor={}",
                agentType, color, themeColor);
        }
        return new AgentColorResponse(agentType, color, themeColor);
    }

    /**
     * 颜色 API 响应 · agentType 回显 + 原始色名 + CC 主题色 key。
     *
     * @param agentType  请求的 agent type（回显）
     * @param color      原始色名（red/blue/...）；无颜色 / general-purpose → null
     * @param themeColor CC 主题色 key（如 "blue_FOR_SUBAGENTS_ONLY"）；无颜色 → null
     */
    public record AgentColorResponse(String agentType, String color, String themeColor) {}
}