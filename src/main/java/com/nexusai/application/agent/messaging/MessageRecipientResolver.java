package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.subagent.AgentNameRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * SendMessage 收件人解析（判类顺序 session → main → agent → Nexus {@code sess-} → name）。
 *
 * <p><b>硬语义</b>：
 * <ul>
 *   <li><b>会话目标以 DB 为准</b>（session 形与 name 形）：先查会话记录（存在且未删除），
 *       再查运行期注册表拿<b>运行状态 / endpoint 代次</b>；内存没有 endpoint 时按需激活
 *       （{@link PeerEndpointActivator}）。⛔ 不是「内存为空才回落 DB」：DB 说已删除 / 不存在
 *       时直接拒绝，<b>内存残留 endpoint 不得复活</b>。</li>
 *   <li>CC session 类（{@code uds:}/{@code bridge:}/{@code did:}/{@code *.sock}/{@code \pipe\}/\
 *       {@code local_*}/{@code session_*}/{@code cse_*}）本仓无远端传输 ⇒ 一律
 *       {@link Kind#UNSUPPORTED_REMOTE}，<b>绝不放本地 mailbox</b>。</li>
 *   <li>AGENT / NAME_AGENT 必须 owner 匹配来源会话，否则 UNRESOLVED + mustNotFallback
 *       （同名跨 session 不串、无回落）。</li>
 *   <li><b>teammate 名 → 代理解析</b>（对齐 CC roster-first `Qt()`）：名册里的非 lead 名字
 *       在命名注册表（活跃 → 退役）命中即 NAME_AGENT，未注册 ⇒ UNRESOLVED（not-found），
 *       ⛔ 不再落 team inbox；{@link Kind#TEAM_MAILBOX} 只保留给 {@code team-lead}。</li>
 *   <li>同机重名会话（无 ref）⇒ AMBIGUOUS，明确列出，绝不随机选。</li>
 *   <li><b>发给自身 = 拒绝</b>（对齐 CC 2.1.328 {@code WSe/lCt}）：目标会话就是发送方<b>主会话本身</b>
 *       时 {@code selfSent = true}，调用方（SendMessageTool）必须直接报错，零投递。
 *       子代理 / teammate 的 {@code sessionId} 继承父会话（{@code createSubagentContext}），
 *       它们发回主会话<b>不是</b>「发给自己」⇒ 必须传 {@code senderAgentId}，否则会被误判。</li>
 * </ul>
 */
@Component
public class MessageRecipientResolver {

    /** 解析类别。 */
    public enum Kind {
        SESSION,
        MAIN,
        AGENT,
        NAME_SESSION,
        NAME_AGENT,
        TEAM_MAILBOX,
        UNRESOLVED,
        UNSUPPORTED_REMOTE,
        AMBIGUOUS
    }

    /**
     * 发给自身（主会话 → 自己的地址）· CC 2.1.328 原文（exe 355938023 {@code lCt}）。
     *
     * <p>CC 里模型可见文案由 {@code WSe(e.to, B$(o))} 给出，其中主会话分支为
     * {@code '<to>' is this session itself — there is no one else at that address to send to.}；
     * 用户实测引用的是同族 {@code lCt} 那句（含 "message or file"），本仓逐字采用，仅把
     * {@code ${e}} 换成实际写入的 {@code to}。编码为 {@code \u2014}（em dash）。
     */
    public static final String SELF_ADDRESS_FAILURE_TEMPLATE =
        "'%s' is this session's own address \u2014 a message or file sent there would only "
            + "come back to this conversation; there is no one else at that address to send to.";

    /** 自身地址拒绝文案（{@code to} 原样写入，去首尾空白）。 */
    public static String selfAddressFailure(String rawTo) {
        return String.format(SELF_ADDRESS_FAILURE_TEMPLATE, rawTo == null ? "" : rawTo.trim());
    }

    /**
     * 解析请求。
     *
     * @param rawTo          原始收件人串
     * @param sourceSessionId 发送方会话
     * @param teamName       当前 team（无 team → null）
     * @param allowTeamMailbox 是否允许 team mailbox 类别
     * @param teamMemberNames <b>团队名册</b>（teamContext.teammates 名 + team config members 名 + team-lead）。
     *        CC resolver 的 mailbox 分支只认名册内真实存在的收件人（exe `me(...)`：teamContext.teammates
     *        精确名 / 读 roster `Nv()` 后 find(name)）；名册外的名字必须落到 UNRESOLVED，⛔ 不得写 inbox。
     *        null / 空 = 无从校验 ⇒ 同样按「未命中」处理（fail-closed，对齐 CC）。
     * @param senderAgentId 发送方 agent 身份（{@code ctx.agentId()}）：<b>null = 主会话本身</b>；
     *        非 null = 子代理 / teammate（其 sessionId 继承父会话）。只有主会话才可能「发给自己」。
     */
    public record Request(String rawTo, String sourceSessionId, String teamName,
                          boolean allowTeamMailbox, List<String> teamMemberNames,
                          String senderAgentId) {

        /** 5 参兼容构造（发送方 = 主会话本身，既有调用零改动）。 */
        public Request(String rawTo, String sourceSessionId, String teamName,
                       boolean allowTeamMailbox, List<String> teamMemberNames) {
            this(rawTo, sourceSessionId, teamName, allowTeamMailbox, teamMemberNames, null);
        }

        /** 4 参兼容构造（无团队名册 ⇒ team mailbox 不可用，对齐 CC「名册未命中不落 mailbox」）。 */
        public Request(String rawTo, String sourceSessionId, String teamName, boolean allowTeamMailbox) {
            this(rawTo, sourceSessionId, teamName, allowTeamMailbox, null, null);
        }

        /** 发送方是否「主会话本身」（决定目标会话等于来源会话时是否算发给自己）。 */
        public boolean senderIsMainConversation() {
            return senderAgentId == null || senderAgentId.isBlank();
        }

        /** 收件名是否在团队名册里（大小写/空白归一化后比较，与 AgentNameRegistry 同名规则一致）。 */
        public boolean hasTeamMember(String name) {
            if (name == null || name.isBlank() || teamMemberNames == null || teamMemberNames.isEmpty()) {
                return false;
            }
            String normalized = PeerAddressing.normalizeName(name);
            for (String member : teamMemberNames) {
                if (member != null && !member.isBlank()
                        && PeerAddressing.normalizeName(member).equals(normalized)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 解析结果。
     *
     * @param selfSent       目标就是发送方自身（main/自身会话）
     * @param mustNotFallback true = 不得降级到 team mailbox / 旧 mailbox
     */
    public record Target(Kind kind, String raw, String sessionId, long endpointEpoch,
                         String agentId, String ownerSessionId, String displayName, String ref,
                         boolean selfSent, boolean mustNotFallback, String errorMessage) {

        static Target unresolved(String raw, String message, boolean mustNotFallback) {
            return new Target(Kind.UNRESOLVED, raw, null, -1L, null, null, null, null,
                false, mustNotFallback, message);
        }

        static Target unsupportedRemote(String raw) {
            return new Target(Kind.UNSUPPORTED_REMOTE, raw, null, -1L, null, null, null, null,
                false, true, "remote cross-session messaging is not supported in this build");
        }

        static Target ambiguous(String raw, String message) {
            return new Target(Kind.AMBIGUOUS, raw, null, -1L, null, null, null, null,
                false, true, message);
        }
    }

    @Autowired
    private PeerSessionRegistry peerSessionRegistry;

    @Autowired
    private AgentNameRegistry agentNameRegistry;

    /**
     * 会话记录（DB）→ endpoint 的按需登记缝（解析第一跳：DB 判存在 / 未删除）。
     *
     * <p><b>⛔ 用 ObjectProvider 惰性取</b>：实现是 {@code ChatPeerEndpointActivator} → 委托
     * ChatService，而 ChatService 依赖 ToolRegistry → SendMessageTool → 本类 —— 直接注入会形成启动期循环依赖。
     *
     * <p>null / 无实现（非 Spring 单测）⇒ 无 DB 通道，只能按运行期注册表寻址（既有单测路径）。
     */
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<PeerEndpointActivator> endpointActivatorProvider;

    /** 测试/接线用 setter。 */
    public void setPeerSessionRegistry(PeerSessionRegistry registry) {
        this.peerSessionRegistry = registry;
    }

    /** 测试/接线用 setter。 */
    public void setAgentNameRegistry(AgentNameRegistry registry) {
        this.agentNameRegistry = registry;
    }

    /** 测试/接线用 setter（按需登记缝；null = 只认运行期注册表）。 */
    public void setEndpointActivator(PeerEndpointActivator activator) {
        this.endpointActivatorProvider = activator == null ? null
            : new org.springframework.beans.factory.ObjectProvider<>() {
                @Override
                public PeerEndpointActivator getObject() {
                    return activator;
                }
            };
    }

    /** 取按需登记缝（未接线 → null；实现抛异常 → null，⛔ 不阻断解析）。 */
    private PeerEndpointActivator endpointActivator() {
        org.springframework.beans.factory.ObjectProvider<PeerEndpointActivator> provider =
            endpointActivatorProvider;
        if (provider == null) {
            return null;
        }
        try {
            return provider.getIfAvailable();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 解析目标。
     *
     * @param request 解析请求
     * @return 目标（永不返回 null）
     */
    public Target resolve(Request request) {
        String raw = request == null || request.rawTo() == null ? "" : request.rawTo().trim();
        String sourceSessionId = request == null ? null : request.sourceSessionId();
        if (raw.isEmpty()) {
            return Target.unresolved(raw, "to must not be empty", true);
        }

        PeerAddressing.Parsed parsed = PeerAddressing.classify(raw);
        switch (parsed.kind()) {
            case SESSION:
                // 本仓所有 session-like 形态都是远端（无本地 UDS/bridge 实现）
                return Target.unsupportedRemote(raw);
            case MAIN:
                return resolveMain(raw, sourceSessionId, senderIsMain(request));
            case AGENT:
                return resolveAgentId(raw, sourceSessionId);
            case NEXUS_SESSION:
                return resolveSession(raw, sourceSessionId, raw, senderIsMain(request));
            case NAME:
            default:
                return resolveName(request, raw, sourceSessionId);
        }
    }

    /**
     * {@code main} = 来源会话自己的地址。
     *
     * @param senderIsMain 发送方是否主会话本身；子代理 / teammate 发 {@code main} 是发回父会话，
     *                     不是「发给自己」（CC 另有一条文案，但用户已裁定本仓必须放行）
     */
    private Target resolveMain(String raw, String sourceSessionId, boolean senderIsMain) {
        if (sourceSessionId == null || sourceSessionId.isBlank()) {
            return Target.unresolved(raw, "main is not addressable without a source session", true);
        }
        if (peerSessionRegistry == null) {
            return Target.unresolved(raw, "no live session " + sourceSessionId, false);
        }
        return peerSessionRegistry.resolve(sourceSessionId)
            .map(live -> new Target(Kind.MAIN, raw, live.sessionId(), live.endpointEpoch(),
                null, null, live.addressName(), live.ref(), senderIsMain, false, null))
            .orElseGet(() -> Target.unresolved(raw, "no live session " + sourceSessionId, false));
    }

    private Target resolveSession(String raw, String sourceSessionId, String sessionId,
                                  boolean senderIsMain) {
        // [DB 优先] ① 先问会话记录（存在且未删除）：activateSession 内部就是「查会话行 →
        //   记录在才登记 endpoint（幂等：已登记只前进 modeVersion，不换代次）」。
        //   ⛔ 顺序不能反：若先查内存，已删除会话的残留 endpoint 仍会命中并被投递（不得复活）。
        PeerEndpointActivator activator = endpointActivator();
        PeerEndpointActivator.Result activation = null;
        if (activator != null) {
            activation = activator.activateSession(sessionId);
            if (activation == PeerEndpointActivator.Result.NOT_FOUND) {
                return Target.unresolved(raw, inactiveSessionMessage(sessionId, activation), false);
            }
        }
        // ② 再从内存注册表取运行状态 / endpoint 代次（唯一代次真源）。
        Optional<PeerSessionRegistry.LiveSession> live = peerSessionRegistry == null
            ? Optional.empty()
            : peerSessionRegistry.resolve(sessionId);
        final PeerEndpointActivator.Result activationResult = activation;
        return live
            .map(found -> new Target(Kind.SESSION, raw, found.sessionId(), found.endpointEpoch(),
                null, null, found.addressName(), found.ref(),
                senderIsMain && sessionId.equals(sourceSessionId), false, null))
            .orElseGet(() -> Target.unresolved(raw,
                inactiveSessionMessage(sessionId, activationResult), false));
    }

    /**
     * 会话地址解析失败文案（必须能区分「不存在/已删除」与「暂不可投递」）。
     *
     * <ul>
     *   <li>{@code NOT_FOUND} —— DB 会话记录不存在（会话表无软删除列：删除即消失）：
     *       明确说「不存在或已删除」，⛔ 不冒充「暂时不在线」；</li>
     *   <li>{@code UNAVAILABLE} / {@code ACTIVATED} —— 记录在但当前不可投递（登记失败/非 accepting）：</li>
     *   <li>null（未接线）—— 无 DB 通道，保持 {@code no live session <id>}。</li>
     * </ul>
     */
    private static String inactiveSessionMessage(String sessionId, PeerEndpointActivator.Result activation) {
        if (activation == PeerEndpointActivator.Result.NOT_FOUND) {
            return "no session with id '" + sessionId + "' on this machine \u2014 it does not exist or was deleted.";
        }
        if (activation == PeerEndpointActivator.Result.UNAVAILABLE) {
            return "session '" + sessionId + "' exists but is not accepting messages right now "
                + "(no live session on this machine could be opened for it).";
        }
        return "no live session " + sessionId;
    }

    private Target resolveAgentId(String raw, String sourceSessionId) {
        if (agentNameRegistry == null) {
            return Target.unresolved(raw, "no live agent " + raw, true);
        }
        AgentNameRegistry.AgentNameEntry entry = agentNameRegistry.entryById(raw);
        if (entry == null || !ownerMatches(entry.ownerSessionId(), sourceSessionId)) {
            // 明确不降级：agent id 形地址不得落到 team mailbox / 别的会话同名 agent
            return Target.unresolved(raw, "no live agent " + raw + " in this session", true);
        }
        return new Target(Kind.AGENT, raw, entry.ownerSessionId(), -1L, entry.agentId(),
            entry.ownerSessionId(), entry.name(), PeerAddressing.refFor("agent", entry.agentId()),
            false, true, null);
    }

    private Target resolveName(Request request, String raw, String sourceSessionId) {
        PeerAddressing.ParsedName parsed = PeerAddressing.parseNameWithRef(raw);
        String name = parsed.name();
        String ref = parsed.ref();
        if (ref == null) {
            // ListAgents 对 teammate 行打印 `name [-]`（teammate 无 ref，CC 同形）：
            // 模型把这个形态粘回来时必须按裸名解析，否则名册/注册表都命中不了。
            name = stripNoRefMarker(name);
        }

        // 1. 本会话子代理（owner 限定，名字归一化）
        if (agentNameRegistry != null) {
            AgentNameRegistry.AgentNameEntry entry = agentNameRegistry.resolveEntry(sourceSessionId, name);
            if (entry != null) {
                return new Target(Kind.NAME_AGENT, raw, entry.ownerSessionId(), -1L, entry.agentId(),
                    entry.ownerSessionId(), entry.name(), PeerAddressing.refFor("agent", entry.agentId()),
                    false, false, null);
            }
        }

        // 2. team mailbox —— **只剩 team-lead**（保留在会话寻址之前，语义同 CC roster-first）。
        //
        //    对齐 CC 2.1.328 roster-first（`Qt()` 偏移 356127769 / 356099285：先查
        //    roster/team → teammate，否则才读 sidecar taskKind）：名册里的**非 lead**
        //    成员一律按 teammate 走代理队列（活跃表 → 退役表 → not-found），⛔ 不再落
        //    `<team>/inboxes/<name>.json`。team-lead 不是可拉起的 local_agent 任务
        //    （CC 的 `Z()` 只认 `tasks[n].type === "local_agent"`），它的收件路径仍是
        //    lead 会话自己的 inbox（resolver `me(...)` mailbox 分支），故保留。
        //    ⛔ 先于 DB 会话寻址：名册内的 team-lead 不被同名会话抢走（CC roster-first）。
        if (request != null && request.allowTeamMailbox()
            && request.teamName() != null && !request.teamName().isBlank()
            && isTeamLeadName(name) && request.hasTeamMember(name)) {
            return new Target(Kind.TEAM_MAILBOX, raw, null, -1L, null, null, name, null,
                false, false, null);
        }

        // 3. [DB 优先] 会话记录（标题）→ 按需登记 endpoint → 再从内存取状态/代次。
        //
        //    ⛔ 不是「内存 miss 才查 DB」：DB 明确没有该标题的会话时（NOT_FOUND）跳过内存寻址，
        //    否则已删除会话的残留 endpoint 会按旧标题被复活。命中多条仍走 AMBIGUOUS（不随机挑）。
        PeerEndpointActivator activator = endpointActivator();
        boolean dbSaysMissing = false;
        if (activator != null && peerSessionRegistry != null) {
            PeerEndpointActivator.Result activation = activator.activateSessionsByName(name);
            if (activation == PeerEndpointActivator.Result.NOT_FOUND) {
                dbSaysMissing = true;
            } else if (activation == PeerEndpointActivator.Result.ACTIVATED) {
                Target byDb = nameSessionTarget(request, raw, name, ref, sourceSessionId);
                if (byDb != null) {
                    return byDb;
                }
            }
        }

        // 4. 内存寻址（DB 无法判定时才作数：未接线 / 会话表读失败）
        if (!dbSaysMissing) {
            Target byMemory = nameSessionTarget(request, raw, name, ref, sourceSessionId);
            if (byMemory != null) {
                return byMemory;
            }
        }

        // 5. raw 精确等于本会话子代理 agentId
        if (agentNameRegistry != null) {
            AgentNameRegistry.AgentNameEntry byId = agentNameRegistry.entryById(raw);
            if (byId != null && ownerMatches(byId.ownerSessionId(), sourceSessionId)) {
                return new Target(Kind.NAME_AGENT, raw, byId.ownerSessionId(), -1L, byId.agentId(),
                    byId.ownerSessionId(), byId.name(), PeerAddressing.refFor("agent", byId.agentId()),
                    false, false, null);
            }
        }

        // 6. 未命中；带 ref / agentId 形必须不回落
        boolean looksLikeAgentId = PeerAddressing.classify(raw).kind() == PeerAddressing.Kind.AGENT;
        boolean mustNotFallback = ref != null || looksLikeAgentId;
        return Target.unresolved(raw, unresolvedMessage(raw, name, sourceSessionId, request),
            mustNotFallback);
    }

    /**
     * 按 {@code name [ref]} 从<b>内存注册表</b>取会话目标（状态/代次唯一真源）。
     *
     * @return 单命中 → NAME_SESSION；多命中 → AMBIGUOUS；无命中 → null（调用方继续后续步骤）
     */
    private Target nameSessionTarget(Request request, String raw, String name, String ref,
                                     String sourceSessionId) {
        if (peerSessionRegistry == null) {
            return null;
        }
        List<PeerSessionRegistry.LiveSession> matches = peerSessionRegistry.findByAddress(name, ref);
        if (matches.size() == 1) {
            PeerSessionRegistry.LiveSession live = matches.get(0);
            return new Target(Kind.NAME_SESSION, raw, live.sessionId(), live.endpointEpoch(),
                null, null, live.addressName(), live.ref(),
                senderIsMain(request) && live.sessionId().equals(sourceSessionId), false, null);
        }
        if (matches.size() > 1) {
            StringBuilder detail = new StringBuilder("ambiguous name \"").append(name)
                .append("\" matches ").append(matches.size()).append(" sessions:");
            for (PeerSessionRegistry.LiveSession live : matches) {
                detail.append(' ').append(live.sessionId()).append(" [").append(live.ref()).append(']');
            }
            detail.append(" — use \"name [ref]\"");
            return Target.ambiguous(raw, detail.toString());
        }
        return null;
    }

    /**
     * 未命中时的回执文案 · 对齐 CC exe not-found 分支：
     * {@code No agent named '<to>' is reachable. Did you mean: a, b?} /
     * {@code …Check the spelling, or use the agent ID from a background agent's spawn result.}
     *
     * <p>候选来自「本会话已注册子代理名 + 团队名册名」；含 ≥3 字符唯一前缀匹配（CC `$e` 的规则）
     * 与包含匹配兜底，最多列 5 个。
     */
    private String unresolvedMessage(String raw, String name, String sourceSessionId,
                                     Request request) {
        List<String> candidates = closestNames(name, sourceSessionId, request);
        if (candidates.isEmpty()) {
            return "No agent named '" + raw + "' is reachable. "
                + "Check the spelling, or use the agent ID from a background agent's spawn result.";
        }
        return "No agent named '" + raw + "' is reachable. Did you mean: "
            + String.join(", ", candidates) + "?";
    }

    /** 候选收件名（名册 + 本会话注册子代理）：前缀 → 包含，去重取前 5。 */
    private List<String> closestNames(String name, String sourceSessionId, Request request) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (request != null && request.teamMemberNames() != null) {
            all.addAll(request.teamMemberNames());
        }
        if (agentNameRegistry != null && sourceSessionId != null) {
            for (AgentNameRegistry.AgentNameEntry entry : agentNameRegistry.entriesForOwner(sourceSessionId)) {
                all.add(entry.name());
            }
        }
        return closestNames(name, all, 5);
    }

    /**
     * 候选收件名（通用）· 对齐 CC not-found 的 `Did you mean` 生成：≥3 字符前缀匹配优先，
     * 再退包含匹配；归一化后比较，去重，最多 {@code limit} 个。
     *
     * <p>public static：SendMessageTool 在「resolver 未注入」的旧路径也要产出同一份候选
     * （⛔ 不允许两条路径各写一套、文案漂移）。
     */
    public static List<String> closestNames(String name, java.util.Collection<String> pool, int limit) {
        String normalized = PeerAddressing.normalizeName(name);
        if (normalized.isEmpty() || pool == null || pool.isEmpty()) {
            return List.of();
        }
        List<String> prefix = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String candidate : pool) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String cn = PeerAddressing.normalizeName(candidate);
            if (cn.equals(normalized)) {
                continue;
            }
            if (normalized.length() >= 3 && cn.startsWith(normalized)) {
                prefix.add(candidate);
            } else if (cn.contains(normalized) || normalized.contains(cn)) {
                contains.add(candidate);
            }
        }
        List<String> out = new ArrayList<>();
        for (String c : prefix) {
            if (out.size() >= limit) break;
            out.add(c);
        }
        for (String c : contains) {
            if (out.size() >= limit) break;
            if (!out.contains(c)) out.add(c);
        }
        return out;
    }

    /**
     * 是否 team-lead（归一化比较）· 唯一仍走 team mailbox 的收件名。
     *
     * <p>CC 依据：`Z()`（偏移 356069451）只对 {@code tasks[n].type === "local_agent"} 判
     * agent 状态；team-lead 从来不是 local_agent 任务（它是 lead 会话本身），name@team
     * 形态也被 {@code SendMessageTool.validateInput} 拒绝 ⇒ 它的收件通道只能是 lead
     * 会话的 inbox（resolver `me(...)` 的 mailbox 分支）。
     */
    private static boolean isTeamLeadName(String name) {
        return "team-lead".equals(PeerAddressing.normalizeName(name));
    }

    /**
     * 去掉 ListAgents 对「无 ref 行」打印的尾部 {@code  [-]}。
     *
     * <p>teammate 在 CC 列表里没有 ref（本仓 {@code ListAgentsFormatter.refOf} 同理打印
     * {@code "-"}），模型把整行粘回 SendMessage 时形如 {@code alice [-]}。这里只剥这一种
     * 显式标记（不是 ref 解析、也不放宽 pin 门：pin 只认 6..12 位 hex 与 pinned 形态）。
     * <p>public static：SendMessageTool 判断「这个收件名是不是名册里的 teammate」时也用同一份
     * 归一化（⛔ 不允许两条路径各写一套、名册命中漂移）。
     */
    public static String stripNoRefMarker(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.endsWith("[-]")) {
            String head = trimmed.substring(0, trimmed.length() - 3).trim();
            if (!head.isEmpty()) {
                return head;
            }
        }
        return trimmed;
    }

    /** 发送方是否主会话本身（null/空白 senderAgentId = 主会话，见 {@link Request#senderAgentId}）。 */
    private static boolean senderIsMain(Request request) {
        return request == null || request.senderIsMainConversation();
    }

    private static boolean ownerMatches(String owner, String sourceSessionId) {
        if (owner == null) {
            // 旧裸名 API（无 owner）只在来源也无会话时可用（测试/兼容）
            return sourceSessionId == null;
        }
        return owner.equals(sourceSessionId);
    }
}
