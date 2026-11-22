package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.messaging.PeerAddressing;
import com.nexusai.domain.session.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 子 Agent name→agentId 注册表 · 对齐 CC {@code appState.agentNameRegistry}
 * （Open-ClaudeCode/src/state/AppStateStore.ts，写点 AgentTool.tsx:703-712，读点
 * SendMessageTool.ts:804 {@code appState.agentNameRegistry.get(input.to)}）。
 *
 * <p>IMP-G4（组 11-1）TR-G1 C7 修复：探查证实 Java 全仓 {@code agentNameRegistry}
 * 无写入点（仅 AppState.java:17 文档提及）→ SendMessage 按名路由断。本组件提供
 * 会话级 name→agentId 注册 + CC queuePendingMessage 等价待投递队列。
 *
 * <p><b>写点</b>：{@link SubagentTool} async spawn（CC AgentTool.tsx:703-712
 * {@code if (name) rootSetAppState(... agentNameRegistry: next)}）——post-registerAsyncAgent，
 * spawn 失败不残留；agent 终态 unregister。
 *
 * <p><b>读点</b>：{@link com.nexusai.application.agent.tool.impl.SendMessageTool}
 * 按名路由（CC SendMessageTool.ts:800-813 {@code registered ?? toAgentId(input.to)}）——
 * 已注册且任务存活 → queue 到待投递队列（CC queuePendingMessage）；子 agent 下一轮
 * {@link SubagentExecutor#runSubagentQueryLoop} drain 为 user 消息（CC LocalAgentTask
 * 每轮消费 pendingMessages）。
 *
 * <p><b>WHY 独立 @Component</b>（Pattern #14 static seam 反转）：CC 注册表是 React
 * appState 的一部分（会话作用域）；Java 无 appState 字段化承载 → 独立会话级组件，
 * SubagentTool（写）/SendMessageTool（读）/SubagentExecutor（drain）三方 DI 共享
 * 同一实例（单例 Spring bean），与 CC 会话级 appState 语义等价（多实例共享一致性
 * 登记：单机部署下同进程即会话隔离边界）。
 */
@Component
public class AgentNameRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentNameRegistry.class);

    /** name → agentId · 对齐 CC {@code appState.agentNameRegistry}（Map&lt;string, agentId&gt;） */
    private final Map<String, String> nameToAgentId = new ConcurrentHashMap<>();

    /**
     * name → 父会话 ID · 本仓扩展（CC 单进程单会话；本仓一 JVM 多会话）。
     *
     * <p>用途 = ListAgents 的 Subagents 分节按会话过滤；未传 sessionId 的旧调用点不登记
     * （不归属任何会话，不会出现在列表里）。
     */
    private final Map<String, String> nameToSessionId = new ConcurrentHashMap<>();

    /** owner 限定登记（生产按名路由唯一真源）。 */
    private final Map<OwnerName, String> ownerNameToAgentId = new ConcurrentHashMap<>();

    /** agentId → 条目（跨 owner 查归属）。 */
    private final Map<String, AgentNameEntry> byAgentId = new ConcurrentHashMap<>();

    /**
     * [retirement] 退役表 · 注销时把 name/agentId/终态/transcript 路径/停止原因移到这里，
     * 而不是连名带 id 删掉。对齐 CC「names keep working after an agent completes
     * (a send resumes it from its transcript)」（claude.exe 2.1.328 偏移 356111578）。
     *
     * <p>解析顺序由 {@link #resolveEntry}/{@link #entryById} 保证：<b>活跃表 → 退役表 → not-found</b>。
     */
    private final RetiredAgentRegistry retired = new RetiredAgentRegistry();

    /** [restart-rebuild] 串行化扫描，避免同一时刻重复扫同一目录（不按 mtime 记账跳过）。 */
    private final Object rebuildLock = new Object();

    /** [stop-double-write] 内存停止标记 · 对齐 CC {@code Fat()}；未注入 → 只读磁盘伴生文件。 */
    @Autowired(required = false)
    private StopMarkers stopMarkers;

    /** 测试/接线用 setter。 */
    public void setStopMarkers(StopMarkers stopMarkers) {
        this.stopMarkers = stopMarkers;
    }

    /**
     * [name-recovery] 主会话 DB 消息读取入口 · 重启重建退役表时从 tool_use/tool_result 配对恢复名字
     * （对齐 CC：名字以主会话 transcript 为权威，不依赖子代理 sidecar 的 meta.name）。
     *
     * <p>{@code @Autowired(required = false)}：无 Spring 上下文 / 单测直构时恒 null ⇒ 回落 meta.name。
     */
    @Autowired(required = false)
    private MessageService messageService;

    /** 测试/接线用 setter（DB 名字恢复来源）。 */
    public void setMessageService(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 保留名 · 对齐 CC {@code v1(e)}（claude.exe 2.1.328 @317883761）：
     * {@code r===fy||r===nd||r===grn||r===$fo}，其中 {@code fy="main"} / {@code nd="team-lead"}
     * / {@code grn="user"} / {@code $fo="system"}（@316745656 块）。
     *
     * <p>按<b>归一化后</b>比较（{@link PeerAddressing#normalizeName}：trim + lower-case +
     * 空白折叠为 {@code -}）⇒ {@code MAIN}/{@code Main } 等任意拼写都命中（CC「in any spelling」）。
     *
     * <p>⛔ {@code teammate} 与 {@code *} <b>不是</b>保留名（CC 实测：v1 不含它们；{@code *}
     * 只被名字字符集与地址层 {@code Uc} 挡掉），本仓旧版多算，已按 CC 删除。
     */
    private static final Set<String> RESERVED_NAMES = Set.of("main", "team-lead", "user", "system");

    /**
     * 名字格式正则 · 对齐 CC {@code Hrl=/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/}
     * （claude.exe @316706232；提示语「name must start with a letter or digit and contain only
     * letters, digits, underscores, or hyphens (max 64 chars)」）。
     */
    public static final java.util.regex.Pattern AGENT_NAME_PATTERN =
        java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** 名字格式拒绝文案 · 对齐 CC 同名提示语（@316706232 区块）。 */
    public static final String NAME_FORMAT_HINT = "name must start with a letter or digit and "
        + "contain only letters, digits, underscores, or hyphens (max 64 chars)";

    /**
     * agent id 形 · 对齐 CC {@code AGENT_ID_PATTERN=/^a(?:.+-)?[0-9a-f]{16}$/}
     * （types/ids.ts:35；见 {@link AgentContext#createAgentId} 的自验注释）。
     */
    private static final java.util.regex.Pattern AGENT_ID_SHAPE =
        java.util.regex.Pattern.compile("^a(?:.+-)?[0-9a-f]{16}$");

    /**
     * session id 形（UUID 8-4-4-4-12）：本仓 sessionId 由 {@code UUID.randomUUID()} 产出，
     * 与 CC session id 同形。名字若能伪装成 session id，会让 {@code name [ref]} 解析歧义
     * ⇒ 注册期按 CC 一并拒绝（CC {@code JD}/{@code HD} 的 session-id 形判据）。
     */
    private static final java.util.regex.Pattern SESSION_ID_SHAPE = java.util.regex.Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** owner+name 复合键。 */
    private record OwnerName(String ownerSessionId, String name) {}

    /** 子代理条目。 */
    public record AgentNameEntry(String ownerSessionId, String name, String agentId) {}

    /**
     * 注册结果。
     *
     * @param registered     true = 本次写入生效
     * @param reservedReason 拒绝原因（保留名 / agent-id 形 / session-id 形 / 名字已被占用）；
     *                       字段名沿用旧称以免破坏既有调用点，语义 = 任意拒绝理由
     */
    public record RegistrationOutcome(boolean registered, String reservedReason) {
        public static RegistrationOutcome ok() {
            return new RegistrationOutcome(true, null);
        }

        public static RegistrationOutcome rejected(String reason) {
            return new RegistrationOutcome(false, reason);
        }
    }

    /** agentId → 待投递消息队列（FIFO）· 对齐 CC {@code queuePendingMessage} 待办队列 */
    private final Map<String, ArrayDeque<PendingAgentMessage>> pendingMessages = new ConcurrentHashMap<>();

    /**
     * 已结束 agent 残留消息的补投通道（对齐 CC「a send resumes it from its transcript」）。
     * 可选注入：无 Spring（单测/POJO）→ null，unregister 只 WARN，不改变清理行为。
     */
    @Autowired(required = false)
    private ObjectProvider<ResumeService> resumeServiceProvider;

    /**
     * [agent-notice-persist] 运行态判定 · 对齐 {@code SendMessageTool#isAgentRunning}
     * （taskId === agentId）。用途：入队即落库后，**有活消费点**的 agent 仍保留内存队列
     * （下一轮 drain 交给模型）；**无活消费点**的 agent 只需落库（可见即可，队列无意义且会泄漏）。
     * 未注入（单测/POJO）→ 无法判定 → 视为活（保持旧的纯入队语义，零行为变化）。
     *
     * <p>⚠️ <b>必须 {@code @Lazy}</b>：本字段引入了一条新 bean 边 AgentNameRegistry → BackgroundTaskRunner，
     * 而 BackgroundTaskRunner 的 @Bean 形参含 SpawnInProcess，SpawnInProcess 字段依赖 SubagentExecutor，
     * SubagentExecutor 的 @Bean 又依赖本注册表 ⇒ 不加 @Lazy 时，@Bean 形参在 createBeanInstance 期解析、
     * 拿不到早期引用，启动直接报「dependency cycle ... could not be broken」（2026-11-20 实测复现）。
     * 先例与语义同 {@code TaskConfiguration:95}、{@code ToolRegistrationConfig:753-756}：
     * @Lazy 只把解析推迟到首次使用，不改变任何运行时行为。
     */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.nexusai.application.agent.tasks.BackgroundTaskRunner backgroundTaskRunner;

    /**
     * [agent-notice-persist] 会话目录解析缝 · 生产 = {@link ResumeService#resolveSessionDir}，
     * 单测可注入临时目录（⛔ 不写用户真实 config home）。
     */
    private volatile java.util.function.Function<String, java.nio.file.Path> sessionDirResolver =
        ResumeService::resolveSessionDir;

    /** 测试/接线用 setter（运行态判定）· 对齐 SendMessageTool.setBackgroundTaskRunner。 */
    public void setBackgroundTaskRunner(
            com.nexusai.application.agent.tasks.BackgroundTaskRunner backgroundTaskRunner) {
        this.backgroundTaskRunner = backgroundTaskRunner;
    }

    /** 测试/接线用 setter（会话目录解析缝）· null → 回落生产实现。 */
    public void setSessionDirResolver(
            java.util.function.Function<String, java.nio.file.Path> sessionDirResolver) {
        this.sessionDirResolver = sessionDirResolver != null
            ? sessionDirResolver : ResumeService::resolveSessionDir;
    }

    /**
     * 一条待投递消息 · 对齐 CC {@code queuePendingMessage} 队列项（exe 2.1.328：
     * {@code {text, origin, isMeta, toolsUsed, userInitiatedAt, forwarded, shownAs}}）。
     *
     * <p>本仓额外携带 {@code id}/{@code from}/{@code summary}：CC 入队时无稳定 ID（drain 时才生成
     * {@code source_uuid}），本仓在入队时生成，使「实时推送」与「转录重拉」可按同一 uuid 去重，
     * 且 SendMessage 的 summary（UI 预览）不再在路由层丢失。
     *
     * @param id        稳定消息 ID（= 消费行 ChatMessageDto.id / transcript uuid）
     * @param agentId   目标子代理 UUID
     * @param sessionId 父会话 ID（残留补投时定位 transcript 目录）
     * @param from      发送方 agent 名（null → 前端按「代理」兜底）
     * @param summary   SendMessage 的 5-10 词 UI 摘要（可空）
     * @param text      消息正文
     * @param origin    {@code coordinator} / {@code peer}（对齐 CC origin.kind）
     * @param queuedAt  入队时间（ISO-8601）
     */
    public record PendingAgentMessage(String id, String agentId, String sessionId,
                                      String from, String summary, String text,
                                      String origin, String queuedAt) {}

    /**
     * 注册 name→agentId（旧裸名 API，仅测试/兼容调用）。
     *
     * <p>生产按名路由必须用 {@link #register(String, String, String)}（owner 限定），
     * 否则同名子代理会跨会话串台。
     *
     * @param name    子 agent 名
     * @param agentId 子 agent UUID
     */
    public void register(String name, String agentId) {
        if (name == null || name.isBlank() || agentId == null) {
            return;
        }
        if (registrationRejectionReason(name) != null) {
            log.debug("[AgentNameRegistry] 保留名/ID 形不可占用: name='{}'", name);
            return;
        }
        String prev = nameToAgentId.put(name, agentId);
        nameToSessionId.remove(name);
        byAgentId.put(agentId, new AgentNameEntry(null, name, agentId));
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] 注册 name→agentId（旧裸名 API）: name='{}' agentId={} 覆盖前={}",
                name, agentId, prev);
        }
    }

    /**
     * 注册 owner 限定的 name→agentId（覆盖语义 · teammate / 测试入口）。
     *
     * <p>拒绝（不写任何映射）：保留名 {@code main}/{@code team-lead}/{@code user}/{@code system}
     * （任意拼写）、agent id 形、session id 形 —— 对齐 CC {@code [registerName] refused reserved
     * or agent-id-shaped name}（@342171068）。
     *
     * <p>⚠️ 生产 async 子代理<b>不走</b>本方法（CC 撞名时后者不注册），走
     * {@link #registerIfAbsent(String, String, String)}。teammate 路径的撞名改名在
     * {@code SpawnInProcess} 侧先分配好唯一名，故这里不会撞名。
     *
     * @param ownerSessionId 父会话 ID
     * @param name           子 agent 名（归一化后作键：trim + lower-case + 空白折叠为 '-'）
     * @param agentId        子 agent UUID
     * @return 注册结果
     */
    public RegistrationOutcome register(String ownerSessionId, String name, String agentId) {
        if (name == null || name.isBlank() || agentId == null) {
            return RegistrationOutcome.rejected("name and agentId are required");
        }
        if (ownerSessionId == null || ownerSessionId.isBlank()) {
            return RegistrationOutcome.rejected("owner session is required");
        }
        String rejection = registrationRejectionReason(name);
        if (rejection != null) {
            return RegistrationOutcome.rejected(rejection);
        }
        String normalized = PeerAddressing.normalizeName(name);
        if (normalized.isBlank()) {
            return RegistrationOutcome.rejected("name is blank after normalization");
        }
        OwnerName key = new OwnerName(ownerSessionId, normalized);
        String prev = ownerNameToAgentId.put(key, agentId);
        byAgentId.put(agentId, new AgentNameEntry(ownerSessionId, normalized, agentId));
        if (prev != null && !prev.equals(agentId)) {
            byAgentId.remove(prev);
        }
        persistNameToMeta(ownerSessionId, normalized, agentId);
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] 注册 owner+name: owner={} name='{}' agentId={} 覆盖前={}",
                ownerSessionId, normalized, agentId, prev);
        }
        return RegistrationOutcome.ok();
    }

    /**
     * 注册 owner 限定的 name→agentId，<b>已存在则不覆盖</b> · 对齐 CC async 子代理写点
     * {@code if(He!==void 0 && registry.get(He)===void 0) registerName(...)}（@333963676）。
     *
     * <p>语义：同名已被<b>别的</b> agent 占用 ⇒ 本次不注册（返回 rejected，spawn 照常成功，
     * 先注册者保号）；同名 + 同 agentId ⇒ 幂等 ok。
     *
     * @param ownerSessionId 父会话 ID
     * @param name           子 agent 名
     * @param agentId        子 agent UUID
     * @return 注册结果
     */
    public RegistrationOutcome registerIfAbsent(String ownerSessionId, String name, String agentId) {
        if (name == null || name.isBlank() || agentId == null) {
            return RegistrationOutcome.rejected("name and agentId are required");
        }
        if (ownerSessionId == null || ownerSessionId.isBlank()) {
            return RegistrationOutcome.rejected("owner session is required");
        }
        String rejection = registrationRejectionReason(name);
        if (rejection != null) {
            return RegistrationOutcome.rejected(rejection);
        }
        String normalized = PeerAddressing.normalizeName(name);
        if (normalized.isBlank()) {
            return RegistrationOutcome.rejected("name is blank after normalization");
        }
        OwnerName key = new OwnerName(ownerSessionId, normalized);
        String existing = ownerNameToAgentId.putIfAbsent(key, agentId);
        if (existing != null) {
            if (existing.equals(agentId)) {
                return RegistrationOutcome.ok();
            }
            return RegistrationOutcome.rejected("name already registered to agent " + existing);
        }
        byAgentId.put(agentId, new AgentNameEntry(ownerSessionId, normalized, agentId));
        persistNameToMeta(ownerSessionId, normalized, agentId);
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] registerIfAbsent 写入: owner={} name='{}' agentId={}",
                ownerSessionId, normalized, agentId);
        }
        return RegistrationOutcome.ok();
    }

    /**
     * 名字的注册期拒绝原因（CC {@code registerName} 的唯一拒绝入口，@342171068）。
     *
     * <p>两段文案对齐 CC refine：{@code main} 单独一条；其余保留名 / agent id 形 / session id 形合一条。
     *
     * @param name 原始名字
     * @return null = 可注册；非 null = 拒绝文案
     */
    public static String registrationRejectionReason(String name) {
        if (name == null) {
            return "name is required";
        }
        String normalized = PeerAddressing.normalizeName(name);
        if ("main".equals(normalized)) {
            return "\"main\" is reserved — SendMessage routes it to the main conversation";
        }
        if (RESERVED_NAMES.contains(normalized) || hasAgentIdShape(normalized)
                || hasSessionIdShape(normalized)) {
            return "name must not be a reserved name (\"main\", \"team-lead\", \"user\" or \"system\", "
                + "in any spelling) or have the shape of an agent id or a session id";
        }
        return null;
    }

    /** 保留名判定（按归一化名比较，任意拼写）· 对齐 CC {@code v1}. */
    public static boolean isReservedName(String name) {
        if (name == null) {
            return false;
        }
        return RESERVED_NAMES.contains(PeerAddressing.normalizeName(name));
    }

    /** agent id 形判定（{@code ^a(?:.+-)?[0-9a-f]{16}$}）· 对齐 CC {@code AGENT_ID_PATTERN}. */
    public static boolean hasAgentIdShape(String name) {
        if (name == null) {
            return false;
        }
        return AGENT_ID_SHAPE.matcher(PeerAddressing.normalizeName(name)).matches();
    }

    /** session id 形判定（UUID 8-4-4-4-12）. */
    public static boolean hasSessionIdShape(String name) {
        if (name == null) {
            return false;
        }
        return SESSION_ID_SHAPE.matcher(PeerAddressing.normalizeName(name)).matches();
    }

    /** 名字格式判定 · 对齐 CC {@code Hrl}（{@code ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$}）. */
    public static boolean isValidAgentName(String name) {
        return name != null && AGENT_NAME_PATTERN.matcher(name).matches();
    }

    /** 子代理条目（旧 ListAgents 读取口径）。 */
    public record AgentEntry(String name, String agentId) {}

    /**
     * 列出某会话下注册中的子代理（owner 限定；所有者匹配时忽略大小写/空白差异）。
     *
     * @param sessionId 父会话 ID（null/blank → 空列表）
     * @return 按名字排序的条目
     */
    public List<AgentEntry> listBySession(String sessionId) {
        List<AgentNameEntry> entries = entriesForOwner(sessionId);
        List<AgentEntry> out = new ArrayList<>(entries.size());
        for (AgentNameEntry e : entries) {
            out.add(new AgentEntry(e.name(), e.agentId()));
        }
        return out;
    }

    /**
     * 列出某会话下注册中的子代理条目（ListAgents Subagents 分节数据源）。
     *
     * @param ownerSessionId 父会话 ID（null → 空列表）
     * @return 按名字排序的条目
     */
    public List<AgentNameEntry> entriesForOwner(String ownerSessionId) {
        if (ownerSessionId == null || ownerSessionId.isBlank()) {
            return List.of();
        }
        List<AgentNameEntry> out = new ArrayList<>();
        for (Map.Entry<OwnerName, String> e : ownerNameToAgentId.entrySet()) {
            if (ownerSessionId.equals(e.getKey().ownerSessionId())) {
                AgentNameEntry entry = byAgentId.get(e.getValue());
                if (entry != null && ownerSessionId.equals(entry.ownerSessionId())) {
                    out.add(entry);
                } else {
                    out.add(new AgentNameEntry(ownerSessionId, e.getKey().name(), e.getValue()));
                }
            }
        }
        out.sort(Comparator.comparing(AgentNameEntry::name));
        return List.copyOf(out);
    }

    /**
     * owner 限定按名解析（生产路由唯一入口）。
     *
     * <p>owner 为 null 时退回旧裸名表（仅测试/兼容；生产调用方必须传会话）。
     *
     * @param ownerSessionId 父会话 ID
     * @param name           子 agent 名
     * @return 条目或 null
     */
    public AgentNameEntry resolveEntry(String ownerSessionId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (ownerSessionId == null) {
            String agentId = nameToAgentId.get(name);
            if (agentId != null) {
                return new AgentNameEntry(null, name, agentId);
            }
            RetiredAgentRegistry.RetiredAgent r = retired.find(null, name);
            return r == null ? null : new AgentNameEntry(r.ownerSessionId(), r.name(), r.agentId());
        }
        String normalized = PeerAddressing.normalizeName(name);
        String agentId = ownerNameToAgentId.get(new OwnerName(ownerSessionId, normalized));
        if (agentId == null) {
            // ── 活跃表未命中 → 惰性重建退役表（重启后内存为空）→ 退役表 → not-found ──
            ensureRetiredRebuilt(ownerSessionId);
            RetiredAgentRegistry.RetiredAgent r = retired.find(ownerSessionId, normalized);
            return r == null ? null : new AgentNameEntry(r.ownerSessionId(), r.name(), r.agentId());
        }
        AgentNameEntry entry = byAgentId.get(agentId);
        return entry != null ? entry : new AgentNameEntry(ownerSessionId, normalized, agentId);
    }

    /**
     * 只在<b>活跃表</b>解析（不回落退役表）· SendMessage 用它区分「活/停」与「已逐出」。
     *
     * @return 活跃条目或 null
     */
    public AgentNameEntry resolveActiveEntry(String ownerSessionId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (ownerSessionId == null) {
            String agentId = nameToAgentId.get(name);
            return agentId == null ? null : new AgentNameEntry(null, name, agentId);
        }
        String normalized = PeerAddressing.normalizeName(name);
        String agentId = ownerNameToAgentId.get(new OwnerName(ownerSessionId, normalized));
        if (agentId == null) {
            return null;
        }
        AgentNameEntry entry = byAgentId.get(agentId);
        return entry != null ? entry : new AgentNameEntry(ownerSessionId, normalized, agentId);
    }

    /** owner 限定退役条目解析（活跃表未命中时由路由层显式查询）。 */
    public RetiredAgentRegistry.RetiredAgent resolveRetiredEntry(String ownerSessionId, String name) {
        ensureRetiredRebuilt(ownerSessionId);
        return retired.find(ownerSessionId, name);
    }

    /**
     * [restart-rebuild] 立即按磁盘 sidecar 重建某会话的退役表（公开入口 · 启动钩子/测试用）。
     *
     * <p>正常路径走 {@link #resolveEntry} 的惰性触发；本方法幂等，可被启动就绪事件直接调用。
     *
     * @return 扫描计数（目录不存在 / 会话已删除 → {@link RetiredAgentRebuilder.ScanResult#EMPTY}）
     */
    public RetiredAgentRebuilder.ScanResult rebuildRetiredFromTranscripts(String ownerSessionId) {
        if (ownerSessionId == null || ownerSessionId.isBlank()) {
            return RetiredAgentRebuilder.ScanResult.EMPTY;
        }
        java.nio.file.Path sessionDir = resolveSessionDirSafe(ownerSessionId);
        if (sessionDir == null) {
            return RetiredAgentRebuilder.ScanResult.EMPTY;
        }
        // 每次重建都按磁盘当前内容全量重算并覆盖（天然幂等，不按目录 mtime 跳过）。
        // 名字优先取主会话 DB 的 tool_use/tool_result 配对（AgentNameRecovery），缺失回落 meta.name。
        return RetiredAgentRebuilder.scan(sessionDir, ownerSessionId, retired,
            messageService == null ? null : messageService::listRawForTranscript);
    }

    /**
     * [restart-rebuild] 惰性触发：按名解析未命中时重扫磁盘（每次重算，不按 mtime 记账跳过）。
     * 只写退役表，不注册活跃、不起线程、不占配额；会话已删除 → scan 返回 EMPTY，不复活。
     */
    private void ensureRetiredRebuilt(String ownerSessionId) {
        if (ownerSessionId == null || ownerSessionId.isBlank()) {
            return;
        }
        synchronized (rebuildLock) {
            rebuildRetiredFromTranscripts(ownerSessionId);
        }
    }

    /** agentId 反查退役条目（逐出分支按 agentId 读 meta）。 */
    public RetiredAgentRegistry.RetiredAgent retiredEntryById(String agentId) {
        return retired.findByAgentId(agentId);
    }

    /** 退役条目数（测试/观测）。 */
    public int retiredSize() {
        return retired.size();
    }

    /**
     * owner 限定按名解析 agentId。
     *
     * @param ownerSessionId 父会话 ID
     * @param name           子 agent 名
     * @return agentId 或 null
     */
    public String resolve(String ownerSessionId, String name) {
        AgentNameEntry entry = resolveEntry(ownerSessionId, name);
        return entry == null ? null : entry.agentId();
    }

    /**
     * 跨 owner 查归属。
     *
     * @param agentId 子 agent UUID
     * @return 条目或 null
     */
    public AgentNameEntry entryById(String agentId) {
        if (agentId == null) {
            return null;
        }
        AgentNameEntry entry = byAgentId.get(agentId);
        if (entry != null) {
            return entry;
        }
        RetiredAgentRegistry.RetiredAgent r = retired.findByAgentId(agentId);
        return r == null ? null : new AgentNameEntry(r.ownerSessionId(), r.name(), r.agentId());
    }

    /**
     * owner+name+agentId 三重校验注销（旧代次注销不得删掉新代次映射）。
     *
     * @param ownerSessionId 父会话 ID
     * @param name           子 agent 名
     * @param expectedAgentId 期望的 agentId
     * @return true = 已删除
     */
    public boolean unregister(String ownerSessionId, String name, String expectedAgentId) {
        if (ownerSessionId == null || name == null || name.isBlank()) {
            return false;
        }
        String normalized = PeerAddressing.normalizeName(name);
        OwnerName key = new OwnerName(ownerSessionId, normalized);
        boolean[] removed = {false};
        ownerNameToAgentId.computeIfPresent(key, (k, current) -> {
            if (expectedAgentId != null && !expectedAgentId.equals(current)) {
                return current;
            }
            removed[0] = true;
            return null;
        });
        if (removed[0] && expectedAgentId != null) {
            AgentNameEntry entry = byAgentId.get(expectedAgentId);
            if (entry != null && ownerSessionId.equals(entry.ownerSessionId())) {
                // [retirement] 先写退役表（保留 name/agentId/终态/transcript/停止原因），再删活跃映射。
                retireEntry(ownerSessionId, normalized, expectedAgentId);
                byAgentId.remove(expectedAgentId);
            }
            // [agent-notice-persist] 终态残留补写 · 生产注销走本重载（SubagentTool finally），
            //   而待办队列只被「下一轮 drain」消费 —— agent 恰在一轮结束后才收到消息时，队列
            //   永远没有读者（旧实现只清映射、不动队列 ⇒ 内存泄漏 + 用户永远看不到）。
            //   这里把它们写进子代理 transcript，至少留下可见的未消费通知。
            flushLeftoverPending(expectedAgentId, ownerSessionId);
        }
        return removed[0];
    }

    /**
     * 把一条已结束的 agent 移入退役表并清掉活跃映射（<b>teammate 终态专用公开入口</b>）。
     *
     * <p>对齐 CC：终态后名字仍可寻址，但「活跃 vs 已逐出」必须可区分 —— 生命周期管线
     * （{@link com.nexusai.application.agent.team.InProcessTeammateTaskRegistry}）保证
     * <b>task 记录先被逐出、再退役</b>，否则 SendMessage 会在「task 还在但已退役」的窗口里
     * 误走 subagent resume 分支。
     *
     * <p>与 {@link #unregister(String, String, String)} 的差异：终态由调用方显式传入
     * （teammate 的 taskId ≠ agentId，{@code backgroundTaskRunner.getTask(agentId)} 恒空，
     * 不能靠它推断），且三重校验保证旧代次不得删新代次映射。
     *
     * @param ownerSessionId 父会话 ID（teammate = Lead 会话）
     * @param name           agent 名（归一化后作键）
     * @param agentId        要退役的 agentId（"name@team" 形态对 teammate 合法）
     * @param terminalStatus 终态（{@code completed}/{@code failed}/{@code killed}；不可知 → null）
     * @param stopReason     停止原因（{@code user}={@code killed}=… 按 CC 口径；null=正常完成）
     * @return true = 本次实际把该 agentId 移入退役表
     */
    public boolean retireAgent(String ownerSessionId, String name, String agentId,
                               String terminalStatus, String stopReason) {
        if (ownerSessionId == null || name == null || name.isBlank() || agentId == null) {
            return false;
        }
        String normalized = PeerAddressing.normalizeName(name);
        OwnerName key = new OwnerName(ownerSessionId, normalized);
        boolean[] removed = {false};
        ownerNameToAgentId.computeIfPresent(key, (k, current) -> {
            if (!agentId.equals(current)) {
                return current; // 旧代次不得删掉指向新 agentId 的活跃映射
            }
            removed[0] = true;
            return null;
        });
        AgentNameEntry entry = byAgentId.get(agentId);
        if (entry != null && ownerSessionId.equals(entry.ownerSessionId())) {
            byAgentId.remove(agentId);
        }
        flushLeftoverPending(agentId, ownerSessionId);
        retired.retire(new RetiredAgentRegistry.RetiredAgent(ownerSessionId, normalized, agentId,
            terminalStatus, resolveTranscriptPathSafe(ownerSessionId, agentId), stopReason,
            RetiredAgentRegistry.now()));
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] [retirement] 终态退役: owner={} name='{}' agentId={} "
                    + "status={} stopReason={}", ownerSessionId, normalized, agentId,
                terminalStatus, stopReason);
        }
        return removed[0] || entry != null;
    }

    /**
     * 终态残留待办补写（unregister / retireAgent 共用）· 待办队列只被「下一轮 drain」消费，
     * agent 恰在一轮结束后才收到消息时队列永远没有读者（旧实现只清映射 ⇒ 内存泄漏 + 用户看不到）。
     */
    private void flushLeftoverPending(String agentId, String ownerSessionId) {
        ArrayDeque<PendingAgentMessage> leftover = pendingMessages.remove(agentId);
        if (leftover != null && !leftover.isEmpty()) {
            int visible = 0;
            for (PendingAgentMessage pm : leftover) {
                if (ensureNoticeVisible(pm)) {
                    visible++;
                }
            }
            log.info("[AgentNameRegistry] [agent-notice] 终态注销时残留 {} 条未消费通知，"
                    + "已落子代理转录 {} 条 (agentId={} owner={})",
                leftover.size(), visible, agentId, ownerSessionId);
        }
    }

    /**
     * 把一条结束的 agent 移入退役表 · 对齐 CC「注销后名字仍可寻址」。
     *
     * <p>保留：name / agentId / 终态（runner 任务状态）/ transcript 路径 / 停止原因
     * （{@code user} = 用户 TaskStop；{@code killed} = 系统终止；null = 正常完成）。
     * 无法解析会话目录/任务状态时不编造：对应字段留 null。
     */
    private void retireEntry(String ownerSessionId, String normalizedName, String agentId) {
        String terminalStatus = null;
        if (backgroundTaskRunner != null) {
            terminalStatus = backgroundTaskRunner.getTask(agentId)
                .map(t -> t.status().getStatusString())
                .orElse(null);
        }
        String stopReason = null;
        if (stopMarkers != null && stopMarkers.isStopped(agentId)) {
            stopReason = "user";
        }
        String transcriptPath = resolveTranscriptPathSafe(ownerSessionId, agentId);
        if (transcriptPath != null) {
            String transcriptKey = transcriptKeyOf(agentId);
            java.nio.file.Path sessionDir = resolveSessionDirSafe(ownerSessionId);
            if (stopReason == null && sessionDir != null) {
                try {
                    if (AgentTranscript.isStoppedByUserOrMeta(sessionDir, ownerSessionId, transcriptKey)
                            || AgentTranscript.isStoppedByUserOrMeta(sessionDir, ownerSessionId, agentId)) {
                        stopReason = "user";
                    }
                } catch (Exception ignore) {
                    // 读停止标记失败 → 保持已有判定（不伪造）
                }
            }
        }
        if (stopReason == null && terminalStatus != null
                && "killed".equalsIgnoreCase(terminalStatus)) {
            stopReason = "killed";
        }
        retired.retire(new RetiredAgentRegistry.RetiredAgent(ownerSessionId, normalizedName, agentId,
            terminalStatus, transcriptPath, stopReason, RetiredAgentRegistry.now()));
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] [retirement] 注销转入退役表: owner={} name='{}' agentId={} "
                    + "status={} stopReason={} (CC names keep working after completion)",
                ownerSessionId, normalizedName, agentId, terminalStatus, stopReason);
        }
    }

    /**
     * transcript 键归一化 · a+16hex 原样；其它形态（如 teammate 的 {@code name@team}）原样当键。
     * 旧 UUID 键不再还原（Breaking，按 CC 单一形态）。
     */
    private static String transcriptKeyOf(String agentId) {
        String canonical = AgentContext.canonicalAgentId(agentId);
        return canonical != null ? canonical : agentId;
    }

    /** 解析 transcript 路径；会话目录/路径不可用 → null（不伪造）。 */
    private String resolveTranscriptPathSafe(String ownerSessionId, String agentId) {
        java.nio.file.Path sessionDir = resolveSessionDirSafe(ownerSessionId);
        if (sessionDir == null) {
            return null;
        }
        try {
            return AgentTranscript.getTranscriptPath(sessionDir, ownerSessionId,
                transcriptKeyOf(agentId)).toString();
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 注销 name→agentId · 对齐 CC 无显式注销（React 状态随会话结束 GC）；
     * Java 在子 agent 终态清理时调用，避免 name 映射残留指向已终止 agentId。
     *
     * <p><b>[终态清理 → 对齐 CC 不静默丢]</b>：agent 终态后不再有消费点，残留队列若直接丢弃，
     * 调用方已拿到 success:true 而消息永不到达。CC 的语义是「名字在 agent 完成后仍可寻址，
     * 发送会从 transcript 热启动它」⇒ 本方法对非空残留做 resume 补投（{@link #redeliverOrWarn}），
     * 不可用时至少 WARN 写明丢弃条数。
     *
     * @param name 子 agent 名
     */
    public void unregister(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        // Map.remove 返回被移除的值 = 本 name 映射到的 agentId（清理目标）
        String removed = nameToAgentId.remove(name);
        String owner = nameToSessionId.remove(name);
        if (removed != null) {
            AgentNameEntry entry = byAgentId.get(removed);
            if (entry != null && name.equals(entry.name())) {
                // [retirement] 裸名 API 同样进退役表（owner 取登记时的会话，可能为 null）。
                retireEntry(owner != null ? owner : (entry.ownerSessionId()),
                    PeerAddressing.normalizeName(name), removed);
                byAgentId.remove(removed);
            }
        }
        if (removed != null) {
            ArrayDeque<PendingAgentMessage> dropped = pendingMessages.remove(removed);
            if (dropped != null && !dropped.isEmpty()) {
                // 对齐 CC：名字在 agent 完成后仍可寻址，发送会热启动它 —— 残留消息不静默丢。
                redeliverOrWarn(removed, dropped);
            }
            if (log.isDebugEnabled()) {
                log.debug("[AgentNameRegistry] 注销 name→agentId: name='{}' 移除={} 清空待投递队列条数={}",
                    name, removed, dropped != null ? dropped.size() : 0);
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] 注销 name→agentId: name='{}' 移除=null（未注册，无队列可清）", name);
        }
    }

    /**
     * 已结束 agent 的残留消息补投 · 对齐 CC「names keep working after an agent completes
     * (a send resumes it from its transcript)」。
     *
     * <p>无 ResumeService（单测/POJO）或补投失败 → WARN 写明丢弃条数，不静默。
     */
    private void redeliverOrWarn(String agentId, Collection<PendingAgentMessage> dropped) {
        ResumeService resume = resumeServiceProvider != null
            ? resumeServiceProvider.getIfAvailable() : null;
        String sessionId = dropped.stream()
            .map(PendingAgentMessage::sessionId)
            .filter(s -> s != null && !s.isBlank())
            .findFirst().orElse(null);
        String prompt = dropped.stream()
            .map(PendingAgentMessage::text)
            .filter(t -> t != null && !t.isBlank())
            .collect(Collectors.joining("\n\n"));
        // 仅 a+16hex（CC 单一形态）可 resume 补投；teammate 名与旧 UUID 键不再走补投（Breaking）。
        boolean resumable = agentId != null && agentId.matches("a[0-9a-f]{16}");
        if (resume != null && sessionId != null && resumable && !prompt.isBlank()) {
            try {
                resume.resumeAgentBackground(agentId, prompt,
                    ResumeService.resolveSessionDir(sessionId), sessionId, null, false);
                log.warn("[AgentNameRegistry] agent 已结束但队列有 {} 条未消费消息，已热启动补投 "
                    + "agentId={} session={}（CC a-send-resumes-it 语义）",
                    dropped.size(), agentId, sessionId);
                return;
            } catch (Exception e) {
                log.warn("[AgentNameRegistry] 残留消息补投失败（丢弃 {} 条）agentId={}: {}",
                    dropped.size(), agentId, e.toString());
                return;
            }
        }
        log.warn("[AgentNameRegistry] agent 已结束且无法补投，丢弃未消费消息 {} 条 agentId={}",
            dropped.size(), agentId);
    }

    /**
     * 按名解析 agentId · 对齐 CC SendMessageTool.ts:804
     * {@code appState.agentNameRegistry.get(input.to)}。
     *
     * @param name 子 agent 名
     * @return agentId 或 null（未注册）
     */
    public String resolve(String name) {
        if (name == null) {
            return null;
        }
        String agentId = nameToAgentId.get(name);
        if (agentId != null) {
            return agentId;
        }
        RetiredAgentRegistry.RetiredAgent r = retired.find(null, name);
        return r == null ? null : r.agentId();
    }

    /**
     * 是否存在该 name 注册（路由可用性判定）。
     */
    public boolean contains(String name) {
        return name != null && nameToAgentId.containsKey(name);
    }

    /**
     * 投递待办消息到子 agent · 对齐 CC {@code queuePendingMessage(agentId, message, setAppState)}
     * （SendMessageTool.ts:810-813，子 agent 存活时按名路由的落点）。子 agent 下一轮
     * {@link SubagentExecutor#runSubagentQueryLoop} 经 {@link #drain} 消费。
     *
     * @param agentId 目标子 agent UUID
     * @param message 待投递消息文本
     */
    public void queue(String agentId, String message) {
        queue(agentId, null, null, message, null, null);
    }

    /**
     * 投递带来源/摘要的待办消息（SendMessage 按名路由 · 对齐 CC {@code queuePendingMessage}）。
     *
     * @param agentId   目标子 agent UUID
     * @param sessionId 父会话 ID（残留补投用；可空）
     * @param from      发送方 agent 名（可空）
     * @param message   消息正文
     * @param summary   SendMessage 的 UI 摘要（可空）
     * @param origin    {@code coordinator} / {@code peer}（可空）
     */
    public void queue(String agentId, String sessionId, String from, String message,
                      String summary, String origin) {
        if (agentId == null || message == null) {
            return;
        }
        PendingAgentMessage rec = new PendingAgentMessage(
            UUID.randomUUID().toString(), agentId, sessionId, from, summary, message,
            origin, java.time.Instant.now().toString());
        // [agent-notice-persist] 入队即落子代理 transcript —— 子代理会话视图读的就是这份 JSONL
        //   （GET /sessions/{sid}/subagents/{agentId}/transcript），不落盘则「没被消费 = 永远不可见」。
        //   落盘失败不阻断：仍进内存队列（有活消费点时下一轮 drain 会补落）。
        boolean persisted = persistNotice(rec);
        if (persisted && !isAgentLive(agentId)) {
            // 无活消费点（已结束/空闲）：落库即为终态可见通知，无需内存队列（无读者，且会泄漏）。
            log.info("[AgentNameRegistry] [agent-notice] 目标无活消费点，通知已直接落子代理转录 "
                    + "(agentId={} session={} from={} 消息长度={})",
                agentId, sessionId, from, message.length());
            return;
        }
        pendingMessages.computeIfAbsent(agentId, k -> new ArrayDeque<>()).addLast(rec);
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] 投递待办消息: agentId={} id={} from={} 消息长度={} 已落库={} "
                    + "(CC queuePendingMessage)",
                agentId, rec.id(), from, message.length(), persisted);
        }
    }

    /**
     * 目标 agent 是否有活消费点（taskId === agentId 且 RUNNING）。
     *
     * <p>未注入 runner（单测/POJO）→ 无法判定 → 返回 true（保持旧语义：只入队，等调用方 drain）。
     */
    private boolean isAgentLive(String agentId) {
        if (backgroundTaskRunner == null || agentId == null) {
            return true;
        }
        return backgroundTaskRunner.getTask(agentId)
            .map(t -> t.status()
                == com.nexusai.application.agent.tasks.BackgroundTaskStatus.RUNNING)
            .orElse(false);
    }

    /**
     * [agent-notice-persist] 把一条待办通知写进子代理自己的 transcript（唯一真源，重启后可见）。
     *
     * <p>链尾续写：{@code parentUuid} 取当前 transcript 链的最后一条 uuid —— ⛔ 不能写 null，
     * 那会变成新的链头，{@code getAgentTranscript} 按「最新 leaf」重建时会把之前的历史整段截断。
     *
     * <p>安全阀：会话目录不存在（未知会话 / 单测直构）→ 不凭空建目录，返回 false 交调用方入队；
     * agentId 为 null/空白（无法归一）→ 同样跳过。
     *
     * @param pm 待办记录
     * @return true = 已落盘（或同 uuid 的行已存在）
     */
    private boolean persistNotice(PendingAgentMessage pm) {
        try {
            if (pm == null || pm.sessionId() == null || pm.sessionId().isBlank()
                    || pm.agentId() == null || pm.agentId().isBlank()) {
                return false;
            }
            java.nio.file.Path sessionDir = resolveSessionDirSafe(pm.sessionId());
            if (sessionDir == null) {
                return false;
            }
            // 真实会话目录（{slug}/{sessionId}）不存在 ⇒ 不是本机已知会话，别在用户目录外乱建文件。
            if (!java.nio.file.Files.isDirectory(sessionDir.resolve(pm.sessionId()))) {
                if (log.isDebugEnabled()) {
                    log.debug("[AgentNameRegistry] [agent-notice] 会话目录不存在，跳过入队落库 "
                            + "session={} agentId={}", pm.sessionId(), pm.agentId());
                }
                return false;
            }
            String agentIdHex = ResumeService.parseAgentIdString(pm.agentId());
            if (agentIdHex == null) {
                return false;
            }
            List<AgentMessage> chain = AgentTranscript
                .getAgentTranscript(sessionDir, pm.sessionId(), agentIdHex)
                .map(AgentTranscript.AgentTranscriptResult::messages)
                .orElse(List.of());
            if (chain.stream().anyMatch(m -> pm.id().equals(m.uuid()))) {
                return true; // 幂等：同一通知只落一行
            }
            String parentUuid = chain.isEmpty()
                ? null : chain.get(chain.size() - 1).uuid();
            AgentTranscript.recordSidechainTranscript(sessionDir, pm.sessionId(), agentIdHex,
                new ArrayList<>(List.of(AgentNotificationMessages.toTranscriptMap(pm))),
                parentUuid);
            return true;
        } catch (Exception e) {
            log.warn("[AgentNameRegistry] [agent-notice] 入队落库失败（不阻断，仍入内存队列）"
                    + " session={} agentId={}: {}", pm != null ? pm.sessionId() : null,
                pm != null ? pm.agentId() : null, e.toString());
            return false;
        }
    }

    /**
     * 终态补写：残留待办若不在**当前对话链**里（入队时写成了孤儿分支 / 从未落盘），补一条新行。
     *
     * <p>为什么不能只调 {@link #persistNotice}：同一 uuid 已在文件里但不在当前 leaf 的链上时，
     * 幂等判断会跳过，用户依旧看不到。补写用**新 uuid** + 挂在当前链尾，保证可见且不污染链。
     *
     * @param pm 残留记录
     * @return true = 当前链上已可见 / 已补写
     */
    private boolean ensureNoticeVisible(PendingAgentMessage pm) {
        try {
            if (pm == null || pm.sessionId() == null || pm.sessionId().isBlank()
                    || pm.agentId() == null || pm.agentId().isBlank()) {
                return false;
            }
            java.nio.file.Path sessionDir = resolveSessionDirSafe(pm.sessionId());
            if (sessionDir == null
                    || !java.nio.file.Files.isDirectory(sessionDir.resolve(pm.sessionId()))) {
                return false;
            }
            String agentIdHex = ResumeService.parseAgentIdString(pm.agentId());
            if (agentIdHex == null) {
                return false;
            }
            List<AgentMessage> chain = AgentTranscript
                .getAgentTranscript(sessionDir, pm.sessionId(), agentIdHex)
                .map(AgentTranscript.AgentTranscriptResult::messages)
                .orElse(List.of());
            if (chain.stream().anyMatch(m -> pm.id().equals(m.uuid()))) {
                return true; // 已在当前链上可见
            }
            String parentUuid = chain.isEmpty()
                ? null : chain.get(chain.size() - 1).uuid();
            // 时间戳用「现在」：补写行必须是**最新**候选，否则旧孤儿行（同内容、更早时间）
            // 会在 findLatestLeaf 里胜出，把补写行也排除在对话链之外。
            PendingAgentMessage fresh = new PendingAgentMessage(
                pm.id(), pm.agentId(), pm.sessionId(), pm.from(), pm.summary(), pm.text(),
                pm.origin(), java.time.Instant.now().toString());
            AgentTranscript.recordSidechainTranscript(sessionDir, pm.sessionId(), agentIdHex,
                new ArrayList<>(List.of(AgentNotificationMessages.toTranscriptMap(
                    fresh, UUID.randomUUID().toString()))),
                parentUuid);
            return true;
        } catch (Exception e) {
            log.warn("[AgentNameRegistry] [agent-notice] 终态补写通知失败 session={} agentId={}: {}",
                pm != null ? pm.sessionId() : null,
                pm != null ? pm.agentId() : null, e.toString());
            return false;
        }
    }

    /**
     * [restart-rebuild] 把注册名写进 meta sidecar（重启后按名重建退役表的数据源）。
     *
     * <p>安全阀同 {@code SpawnInProcess#writeTeammateMeta}：会话目录不存在 ⇒ 不写
     * （单测直构 / 未知会话 / 已删除会话不凭空建目录）。失败只 DEBUG，不阻断注册。
     */
    private void persistNameToMeta(String ownerSessionId, String normalizedName, String agentId) {
        if (ownerSessionId == null || ownerSessionId.isBlank()
                || normalizedName == null || normalizedName.isBlank() || agentId == null) {
            return;
        }
        try {
            java.nio.file.Path sessionDir = resolveSessionDirSafe(ownerSessionId);
            if (sessionDir == null || !java.nio.file.Files.isDirectory(sessionDir.resolve(ownerSessionId))) {
                return;
            }
            AgentTranscript.writeMetaName(sessionDir, ownerSessionId,
                transcriptKeyOf(agentId), normalizedName);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentNameRegistry] [restart-rebuild] 写 meta name 失败 owner={} name='{}' "
                    + "agentId={}: {}", ownerSessionId, normalizedName, agentId, e.toString());
            }
        }
    }

    /** 会话目录解析 · 解析器抛错（未绑定项目根等）→ null（不阻断消息投递）。 */
    private java.nio.file.Path resolveSessionDirSafe(String sessionId) {
        try {
            java.util.function.Function<String, java.nio.file.Path> resolver = sessionDirResolver;
            return resolver != null ? resolver.apply(sessionId) : null;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentNameRegistry] [agent-notice] 解析会话目录失败 session={}: {}",
                    sessionId, e.toString());
            }
            return null;
        }
    }

    /**
     * 取出并清空该 agent 的待投递消息（FIFO）· 对齐 CC LocalAgentTask 每轮消费
     * {@code pendingMessages}。无待办 → 空列表。
     *
     * @param agentId 子 agent UUID
     * @return 待投递消息列表（FIFO 序，消费后清除）
     */
    public List<String> drain(String agentId) {
        List<PendingAgentMessage> recs = drainPending(agentId);
        if (recs.isEmpty()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>(recs.size());
        for (PendingAgentMessage r : recs) {
            out.add(r.text());
        }
        return out;
    }

    /**
     * 取出并清空该 agent 的待投递记录（FIFO）· 生产消费入口（LlmAgentLoop / SubagentExecutor）。
     *
     * @param agentId 子 agent UUID
     * @return 待投递记录列表（FIFO 序，消费后清除）
     */
    public List<PendingAgentMessage> drainPending(String agentId) {
        if (agentId == null) {
            return List.of();
        }
        ArrayDeque<PendingAgentMessage> q = pendingMessages.remove(agentId);
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        return List.copyOf(q);
    }

    /**
     * 是否有待投递消息（路由层判定子 agent 是否被阻塞等待）。
     */
    public boolean hasPending(String agentId) {
        ArrayDeque<PendingAgentMessage> q = agentId == null ? null : pendingMessages.get(agentId);
        return q != null && !q.isEmpty();
    }

    /** 当前注册条目数（测试/观测）。 */
    public int size() {
        return nameToAgentId.size();
    }
}
