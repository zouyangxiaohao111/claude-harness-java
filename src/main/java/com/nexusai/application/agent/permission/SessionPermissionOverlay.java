package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [批 A2b] <b>会话级 SESSION 档授权载体</b> · sessions.session_permission_rules（V75 列）的
 * 编解码 + 读侧上下文构造 + 写侧 RMW 单点。
 *
 * <h2>WHY（本仓为什么需要这个载体）</h2>
 * <p>CC 的 {@code appState.toolPermissionContext} 是<b>长驻进程内的内存对象</b>：文件类弹窗的
 * 「Yes, during this session」/「Yes, allow all edits during this session」
 * （{@code destination='session'}，{@code usePermissionHandler.ts:123}；选项集合
 * {@code permissionOptions.tsx:49-52} 结构上只有 accept-once / accept-session / reject）经
 * {@code setToolPermissionContext} 写进 appState 后，后续每次权限检查读同一对象。
 *
 * <p>本仓 Web 端每个 HTTP send 都是<b>新的</b> {@code LlmAgentLoop}
 * （{@code @Scope("prototype")}，{@code ChatService:900 loopProvider.getObject()}）⇒
 * {@code appStateRef}（实例字段）每 send 恒空 ⇒ SESSION 档授权活不过下一条用户消息。
 * Web 多会话无进程级 appState 单例 ⇒ 会话级状态存 <b>sessions 表列</b>
 * （铁律 {@code multi-session-vs-cc-single-session}；todos V43 / effort_level V31 同款范式）。
 *
 * <h2>⛔ 本类**不落盘**（本批最要命的一条）</h2>
 * <p>CC {@code supportsPersistence} 明确排除 {@code session}
 * （{@code PermissionUpdate.ts:208-216}）——SESSION 档在 CC 是「本次会话」语义，
 * <b>刻意不落盘</b>。本类只写 <b>DB 会话列</b>，绝不触碰
 * {@code settings.json} / {@code settings.local.json}（落盘 = 把「本次会话」变成「永久」，
 * 改坏 CC 语义）。生产落盘路径仍只有 {@link PermissionUpdatePersister}，其
 * {@code supportsPersistence(SESSION)==false} 原样保留、本批未动。
 *
 * <h2>规范形（读写共用同一对函数）</h2>
 * <pre>
 *   { "mode": "acceptEdits",                                   // 可缺省 = 无 SESSION setMode
 *     "alwaysAllowRules": [{"toolName":"Read","ruleContent":"//tmp/x/**"}],
 *     "alwaysDenyRules":   [],
 *     "alwaysAskRules":    [],
 *     "additionalWorkingDirectories": ["/tmp/extra"] }
 * </pre>
 * <p>字段名对齐 CC {@code PermissionRuleValue} 的<b>扁平</b>形状（{@code toolName} + 可选
 * {@code ruleContent}，同 {@link PermissionUpdate.WireSerializer}）；source 不写（桶 key 恒 =
 * SESSION）、ruleBehavior 不写（桶名即 behavior）—— 只承载 source=SESSION 的条目。
 * 照 {@code TodoWriteTool.todosMapToJson/todosJsonToMap} 的「三通道共用同一规范形」先例。
 *
 * <h2>写侧复用 {@link PermissionUpdateApplier}（不重写 6 型匹配逻辑）</h2>
 * <p>会话列 RMW = 读列 → 还原成只含 SESSION 桶的 {@link ToolPermissionContext} → 用
 * <b>同一个</b> applier {@code applyAll} 应用 destination=SESSION 的更新 → 再抽回 SESSION
 * 桶写列。语义与内存路径逐字同源（含 {@code RemoveRules} 单桶语义、read-only source 守卫、
 * {@code ReplaceRules} 原子替换），不存在「列里一套规则、内存里另一套规则」的双实现漂移。
 *
 * <h2>线程安全</h2>
 * <p>列 RMW 有「读-改-写」窗口：同一会话的并发授权（并行工具调用各自弹窗）会丢更新。
 * 加<b>全局静态锁</b>：授权是人工驱动、低频（每会话每秒最多个位数），持锁期只有一次
 * PK 查询 + 一次单行 update，亚毫秒级；换来的是「不丢 grant」的确定性。
 */
public final class SessionPermissionOverlay {

    private static final Logger log = LoggerFactory.getLogger(SessionPermissionOverlay.class);

    /** 规范形 JSON 编解码器（Jackson；与 {@code WebSocketPermissionPrompter.JSON_FACTORY} 同款裸 mapper）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** applier 无状态（纯函数式），静态单例复用（桶语义唯一真源）。 */
    private static final PermissionUpdateApplier APPLIER = new PermissionUpdateApplier();

    /** 会话列 RMW 序列化锁（见类 javadoc「线程安全」）。 */
    private static final Object RMW_LOCK = new Object();

    // ── 规范形字段名（单一来源，读写同源） ──
    private static final String F_MODE = "mode";
    private static final String F_ALLOW = "alwaysAllowRules";
    private static final String F_DENY = "alwaysDenyRules";
    private static final String F_ASK = "alwaysAskRules";
    private static final String F_DIRS = "additionalWorkingDirectories";
    private static final String F_TOOL_NAME = "toolName";
    private static final String F_RULE_CONTENT = "ruleContent";

    private SessionPermissionOverlay() {
        // 工具类，禁止实例化
    }

    // ════════════════════════════════════════════════════════════════════
    // 读侧 · sessions.session_permission_rules → appState 注入用 ToolPermissionContext
    // ════════════════════════════════════════════════════════════════════

    /**
     * [批 A2b 读侧] 会话列 JSON → <b>只含 SESSION 桶</b>的 {@link ToolPermissionContext}
     * （doRun 入口注入 {@code appStateRef.toolPermissionContext} 用）。
     *
     * <p>返回的 ctx 会被 {@code AgentLoopContext.mergeAppStatePermissionRules}（:1486）按
     * 「8 source 全桶 ∪ mode ∪ 附加目录」并进 per-turn ctx ⇒ 本方法只需给 SESSION 条目的最小载体。
     *
     * <p><b>mode 的两种来源（重要）</b>：A2 的合并语义是「appState 侧 mode 胜出」
     * （{@code AgentLoopContext:1622-1624}）⇒ 注入 ctx 的 mode 必须是
     * <b>本 run 的 per-turn 基线 mode</b>（调用方传 {@code baseTuc.permissionContext().mode()}），
     * 否则会把本次 run 的 mode 覆盖掉；只有<b>列里存了 SESSION setMode</b>（用户真的在弹窗里选了
     * 「本次会话」档）时才用列里的值 —— 那正是「本次会话允许编辑」跨 send 存活的语义本体。
     *
     * @param columnJson 会话列 JSON 串（可 null/空白）
     * @param baseMode   本 run 的 per-turn 基线 mode（列里无 SESSION setMode 时用它占位，
     *                   使 A2 合并对该字段成为 no-op；非 null —— 调用方从
     *                   {@code baseTuc.permissionContext()} 取，取不到传 {@link PermissionMode#DEFAULT}）
     * @return 只含 SESSION 条目的 ToolPermissionContext；<b>列无 SESSION 条目 / 解析失败 → null</b>
     *         （null = 不注入，调用方不得建 appState 键 —— 对齐 todos 回读空态语义）
     */
    public static ToolPermissionContext toContext(String columnJson, PermissionMode baseMode) {
        Parsed parsed = parse(columnJson);
        if (parsed == null || parsed.isEmpty()) {
            return null;
        }
        PermissionMode mode = (parsed.mode() != null)
            ? parsed.mode()
            : (baseMode != null ? baseMode : PermissionMode.DEFAULT);
        return buildContext(parsed, mode);
    }

    // ════════════════════════════════════════════════════════════════════
    // 写侧 · 批准流 apply 单点挂钩（RMW 到会话列）
    // ════════════════════════════════════════════════════════════════════

    /**
     * [批 A2b 写侧] 把 destination=SESSION 的更新<b>合并写进会话列</b>
     * （跨 send 唯一通道；其余 destination 一律不动 —— 它们走既有落盘路径）。
     *
     * <p><b>调用点</b>：两条「apply + persist」单点 ——
     * {@code WebSocketPermissionPrompter.applyAndPersistUpdates}（用户弹窗批准路径）与
     * {@code ToolPermissionGate.applyAndPersistPermissionUpdates}（hook allow 携带
     * updatedPermissions 路径，CC {@code permissions.ts:436-451}）。两者都是 CC
     * {@code handleUserAllow → persistPermissions + setToolPermissionContext} 的等价物。
     *
     * <p><b>6 型全覆盖</b>：{@code AddRules} / {@code ReplaceRules} / {@code RemoveRules} /
     * {@code SetMode} / {@code AddDirectories} / {@code RemoveDirectories}。全部经
     * {@link PermissionUpdateApplier#applyAll} 应用到「只含 SESSION 桶的列表 ctx」——
     * 其中 {@code RemoveRules} 会<b>真的从桶里删掉</b>对应项（匹配 = toolName + ruleContent），
     * 杜绝「删了却还在列里 = 规则复活」。
     *
     * <p><b>可空容忍（fail-loud 边界）</b>：无 SESSION 档更新 → 直接返回（debug，本就不需要）；
     * 有 SESSION 档更新但写不进（mapper 未注入 / sessionId 空 / 会话行不存在 / 写异常）→
     * <b>≥WARN</b> 留痕（不许静默失效）。本方法<b>永不抛</b>（批准流不得因列写失败而中断）。
     *
     * @param sessionMapper 会话 mapper（可 null = 未注入，跳过 + WARN）
     * @param sessionId     会话 id（short；null/空白 = 无会话腿，跳过 + WARN）
     * @param updates       已批准的权限更新（可为 null/空）
     * @param origin        调用点标识（仅日志，如 {@code "ws:req-1"} / {@code "gate:call-1"}）
     */
    public static void persistSessionUpdates(SessionMapper sessionMapper, String sessionId,
                                             List<PermissionUpdate> updates, String origin) {
        List<PermissionUpdate> sessionUpdates = filterSessionUpdates(updates);
        if (sessionUpdates.isEmpty()) {
            // 本就不需要（无 SESSION 档更新）→ 不是失效，debug 足够。
            if (log.isDebugEnabled()) {
                log.debug("[批 A2b] 无 destination=SESSION 更新，跳过会话列写: origin={} updates={}",
                    origin, updates != null ? updates.size() : 0);
            }
            return;
        }
        if (sessionMapper == null || sessionId == null || sessionId.isBlank()) {
            log.warn("[批 A2b] SESSION 档授权无法写入会话列（{} 缺失）⇒ 本条授权<b>不跨 send 存活</b>: "
                    + "origin={} sessionId={} sessionUpdates={}",
                sessionMapper == null ? "sessionMapper" : "sessionId", origin, sessionId, sessionUpdates.size());
            return;
        }
        try {
            synchronized (RMW_LOCK) {
                SessionRecord sessionRecord = sessionMapper.selectOneById(sessionId);
                if (sessionRecord == null) {
                    log.warn("[批 A2b] SESSION 档授权写入会话列跳过：会话 {} 不存在 ⇒ 本条授权不跨 send 存活"
                        + "（origin={} sessionUpdates={}）", sessionId, origin, sessionUpdates.size());
                    return;
                }
                Parsed before = parse(sessionRecord.getSessionPermissionRules());
                // 列表 ctx 的 mode 用「列里已有 mode ?? DEFAULT」占位：applier 的 setMode 会经
                // newCtx 重建；真正的「本会话 mode」由下面显式 bookkeeping 决定（占位值不落列）。
                ToolPermissionContext listCtx = buildContext(before,
                    before.mode() != null ? before.mode() : PermissionMode.DEFAULT);
                ToolPermissionContext applied = APPLIER.applyAll(sessionUpdates, listCtx);
                Parsed after = fromContext(applied, resolveMode(before.mode(), sessionUpdates));
                String json = toJson(after);
                sessionRecord.setSessionPermissionRules(json);
                if (json == null) {
                    // 列被清空（如 RemoveRules 删掉最后一条）→ 显式写 NULL
                    // （MyBatis-Flex update(entity) 默认忽略 null 字段；镜像 TodoWriteTool:883）。
                    sessionMapper.update(sessionRecord, false);
                } else {
                    sessionMapper.update(sessionRecord);
                }
                if (log.isInfoEnabled()) {
                    log.info("[批 A2b] SESSION 档授权已写入会话列: session={} origin={} updates={} "
                            + "→ allow={} deny={} ask={} dirs={} mode={}（跨 send 通道 = V75 列；⛔ 不落盘）",
                        sessionId, origin, sessionUpdates.size(),
                        after.rules().get(PermissionBehavior.ALLOW).size(),
                        after.rules().get(PermissionBehavior.DENY).size(),
                        after.rules().get(PermissionBehavior.ASK).size(),
                        after.directories().size(), after.mode());
                }
            }
        } catch (Exception e) {
            log.warn("[批 A2b] SESSION 档授权写入会话列失败（不中断批准流）: session={} origin={} err={}",
                sessionId, origin, e.toString());
        }
    }

    /**
     * 过滤出 destination=SESSION 的更新（6 型判别；其余 destination 由既有落盘路径负责）。
     */
    private static List<PermissionUpdate> filterSessionUpdates(List<PermissionUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return List.of();
        }
        List<PermissionUpdate> out = new ArrayList<>();
        for (PermissionUpdate update : updates) {
            if (update == null) {
                continue;
            }
            if (update instanceof PermissionUpdate.AddRules u) {
                if (u.destination() == PermissionUpdate.Destination.SESSION) {
                    out.add(u);
                }
            } else if (update instanceof PermissionUpdate.RemoveRules u) {
                if (u.destination() == PermissionUpdate.Destination.SESSION) {
                    out.add(u);
                }
            } else if (update instanceof PermissionUpdate.ReplaceRules u) {
                if (u.destination() == PermissionUpdate.Destination.SESSION) {
                    out.add(u);
                }
            } else if (update instanceof PermissionUpdate.SetMode u) {
                if (u.destination() == PermissionUpdate.Destination.SESSION) {
                    out.add(u);
                }
            } else if (update instanceof PermissionUpdate.AddDirectories u) {
                if (u.destination() == PermissionUpdate.Destination.SESSION) {
                    out.add(u);
                }
            } else if (update instanceof PermissionUpdate.RemoveDirectories u) {
                if (u.destination() == PermissionUpdate.Destination.SESSION) {
                    out.add(u);
                }
            }
        }
        return out;
    }

    /**
     * 本会话 SESSION 档 mode 的 bookkeeping（模型：{@code 上一次值 → 本次 setMode}）。
     *
     * <p>为什么不直接读 {@code applied.mode()}：无 SESSION setMode 时列表 ctx 的 mode 是
     * <b>占位值</b>（DEFAULT），读它会往列里写一个「本会话 mode = default」的假事实 ⇒
     * 下次 run 回读时会覆盖 per-turn 基线 mode（A2 合并「appState mode 胜出」）。
     * 只有 UPDATE 列表里真的出现 SESSION {@code setMode} 才记录。
     *
     * <p>{@link PermissionMode#BUBBLE} 不进列：它是 fork 子 agent 的冒泡内部标记
     * （CC {@code types/permissions.ts:34-41} 用户可寻址集合不含 bubble；
     * {@code AgentLoopContext:1623} 同判不采纳），且 {@code modeToCcString→fromString}
     * 往返会把 "bubble" 折成 DEFAULT（假事实），故写侧直接拒收 + debug 留痕。
     */
    private static PermissionMode resolveMode(PermissionMode stored,
                                              List<PermissionUpdate> sessionUpdates) {
        PermissionMode mode = stored;
        for (PermissionUpdate update : sessionUpdates) {
            if (update instanceof PermissionUpdate.SetMode setMode) {
                if (setMode.mode() == PermissionMode.BUBBLE) {
                    if (log.isDebugEnabled()) {
                        log.debug("[批 A2b] SESSION setMode=BUBBLE 不入会话列（fork 子 agent 冒泡内部标记，"
                            + "非用户可寻址档；对齐 AgentLoopContext:1623 BUBBLE 守卫）");
                    }
                    continue;
                }
                mode = setMode.mode();
            }
        }
        return mode;
    }

    // ════════════════════════════════════════════════════════════════════
    // 规范形编解码（读写共用）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 只含 source=SESSION 条目的 ctx → 规范形 JSON（Bubble/空 → null）·
     * 写侧唯一序列化出口（与 {@link #toContext} 成对）。
     */
    static String toJson(Parsed parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return null;
        }
        ObjectNode root = JSON.createObjectNode();
        if (parsed.mode() != null) {
            root.put(F_MODE, ToolPermissionGate.modeToCcString(parsed.mode()));
        }
        root.set(F_ALLOW, rulesToArray(parsed.rules().get(PermissionBehavior.ALLOW)));
        root.set(F_DENY, rulesToArray(parsed.rules().get(PermissionBehavior.DENY)));
        root.set(F_ASK, rulesToArray(parsed.rules().get(PermissionBehavior.ASK)));
        ArrayNode dirs = root.putArray(F_DIRS);
        for (String dir : parsed.directories()) {
            dirs.add(dir);
        }
        return root.toString();
    }

    private static ArrayNode rulesToArray(List<PermissionRule> rules) {
        ArrayNode arr = JSON.createArrayNode();
        for (PermissionRule rule : rules) {
            ObjectNode node = arr.addObject();
            node.put(F_TOOL_NAME, rule.ruleValue().toolName());
            if (rule.ruleValue().ruleContent() != null) {
                node.put(F_RULE_CONTENT, rule.ruleValue().ruleContent());
            }
        }
        return arr;
    }

    /**
     * 规范形 JSON → {@link Parsed}（fail-soft：null/空白/坏 JSON/非对象 → 空 Parsed，不抛）。
     *
     * <p>坏条目逐条跳过 + WARN（一条坏规则不得让整列消失 —— 但也不许静默：每条都留痕）。
     * {@code mode} 缺失/"bubble"/未知 → mode=null（回落 per-turn 基线 mode）。
     */
    static Parsed parse(String columnJson) {
        Parsed empty = Parsed.empty();
        if (columnJson == null || columnJson.isBlank()) {
            return empty;
        }
        try {
            JsonNode root = JSON.readTree(columnJson);
            if (root == null || !root.isObject()) {
                log.warn("[批 A2b] 会话列 session_permission_rules 非 JSON 对象，按空处理: {}", columnJson);
                return empty;
            }
            PermissionMode mode = null;
            JsonNode modeNode = root.get(F_MODE);
            if (modeNode != null && modeNode.isTextual()) {
                String literal = modeNode.asText();
                if ("bubble".equals(literal)) {
                    log.warn("[批 A2b] 会话列含非法 mode='bubble'（内部标记不可寻址），按无 SESSION mode 处理");
                } else {
                    mode = PermissionMode.fromString(literal);
                }
            }
            Map<PermissionBehavior, List<PermissionRule>> rules = new EnumMap<>(PermissionBehavior.class);
            rules.put(PermissionBehavior.ALLOW, parseRules(root.get(F_ALLOW), PermissionBehavior.ALLOW));
            rules.put(PermissionBehavior.DENY, parseRules(root.get(F_DENY), PermissionBehavior.DENY));
            rules.put(PermissionBehavior.ASK, parseRules(root.get(F_ASK), PermissionBehavior.ASK));
            List<String> dirs = new ArrayList<>();
            JsonNode dirsNode = root.get(F_DIRS);
            if (dirsNode != null && dirsNode.isArray()) {
                for (JsonNode dir : dirsNode) {
                    if (dir != null && dir.isTextual() && !dir.asText().isBlank()) {
                        dirs.add(dir.asText());
                    }
                }
            }
            return new Parsed(mode, rules, dirs);
        } catch (Exception e) {
            log.warn("[批 A2b] 会话列 session_permission_rules 解析失败（按空处理，不阻断 run）: {}", e.toString());
            return empty;
        }
    }

    private static List<PermissionRule> parseRules(JsonNode node, PermissionBehavior behavior) {
        List<PermissionRule> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                log.warn("[批 A2b] 会话列 {} 桶含非对象条目，跳过: {}", behavior, item);
                continue;
            }
            JsonNode toolName = item.get(F_TOOL_NAME);
            if (toolName == null || !toolName.isTextual() || toolName.asText().isBlank()) {
                log.warn("[批 A2b] 会话列 {} 桶条目缺 toolName，跳过: {}", behavior, item);
                continue;
            }
            JsonNode ruleContent = item.get(F_RULE_CONTENT);
            String content = (ruleContent != null && ruleContent.isTextual()) ? ruleContent.asText() : null;
            try {
                out.add(new PermissionRule(PermissionRuleSource.SESSION, behavior,
                    new PermissionRuleValue(toolName.asText(), content)));
            } catch (Exception e) {
                log.warn("[批 A2b] 会话列 {} 桶条目构造失败，跳过: {} err={}", behavior, item, e.toString());
            }
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // ctx ↔ Parsed（只抽 source=SESSION 的条目）
    // ════════════════════════════════════════════════════════════════════

    /**
     * {@link Parsed} → 只含 SESSION 桶的 {@link ToolPermissionContext}。
     *
     * <p>4 个「非 SESSION 语义」字段（{@code isBypassPermissionsModeAvailable} /
     * {@code isAutoModeAvailable} / {@code shouldAvoidPermissionPrompts} /
     * {@code awaitAutomatedChecksBeforeDialog} / {@code prePlanMode}）不参与条目承载，
     * 由 {@code mergeAppStatePermissionRules} 的「刻意不取」约定排除（AgentLoopContext:1565-1568）
     * ⇒ 此处给最严格值（false / null），不会经 appState 回流覆盖 per-turn 基线。
     */
    private static ToolPermissionContext buildContext(Parsed parsed, PermissionMode mode) {
        Map<PermissionRuleSource, Set<PermissionRule>> allow = sessionBucket(parsed, PermissionBehavior.ALLOW);
        Map<PermissionRuleSource, Set<PermissionRule>> deny = sessionBucket(parsed, PermissionBehavior.DENY);
        Map<PermissionRuleSource, Set<PermissionRule>> ask = sessionBucket(parsed, PermissionBehavior.ASK);
        Map<String, AdditionalWorkingDirectory> dirs = new LinkedHashMap<>();
        for (String dir : parsed.directories()) {
            dirs.put(dir, new AdditionalWorkingDirectory(dir, PermissionRuleSource.SESSION));
        }
        return new ToolPermissionContext(mode, allow, deny, ask, dirs,
            false, false, Map.of(), false, false, null);
    }

    private static Map<PermissionRuleSource, Set<PermissionRule>> sessionBucket(
            Parsed parsed, PermissionBehavior behavior) {
        List<PermissionRule> rules = parsed.rules().get(behavior);
        if (rules == null || rules.isEmpty()) {
            return Map.of();
        }
        Map<PermissionRuleSource, Set<PermissionRule>> map = new EnumMap<>(PermissionRuleSource.class);
        map.put(PermissionRuleSource.SESSION, new LinkedHashSet<>(rules));
        return map;
    }

    /**
     * {@link ToolPermissionContext} → {@link Parsed}（只抽 source=SESSION 的规则与目录）。
     *
     * <p>入参 ctx 是「只含 SESSION 桶」的列表 ctx，但抽取仍按 {@code source==SESSION} 过滤 ——
     * 防止未来有人把别的 source 的条目塞进来污染会话列。目录同理
     * （applier {@code applyAddDirectories} 已按 {@code destination} 给 source）。
     */
    private static Parsed fromContext(ToolPermissionContext ctx, PermissionMode mode) {
        Map<PermissionBehavior, List<PermissionRule>> rules = new EnumMap<>(PermissionBehavior.class);
        rules.put(PermissionBehavior.ALLOW, extract(ctx.alwaysAllowRules()));
        rules.put(PermissionBehavior.DENY, extract(ctx.alwaysDenyRules()));
        rules.put(PermissionBehavior.ASK, extract(ctx.alwaysAskRules()));
        List<String> dirs = new ArrayList<>();
        for (Map.Entry<String, AdditionalWorkingDirectory> e : ctx.additionalWorkingDirectories().entrySet()) {
            if (e.getValue() != null && e.getValue().source() == PermissionRuleSource.SESSION) {
                dirs.add(e.getKey());
            }
        }
        return new Parsed(mode, rules, dirs);
    }

    private static List<PermissionRule> extract(Map<PermissionRuleSource, Set<PermissionRule>> bucket) {
        Set<PermissionRule> sessionRules = bucket.get(PermissionRuleSource.SESSION);
        return sessionRules == null ? List.of() : new ArrayList<>(sessionRules);
    }

    /**
     * 会话列负载（只含 source=SESSION 的条目；{@code mode} 可空 = 无 SESSION setMode）。
     *
     * @param mode        本会话 SESSION 档 mode（null = 未设，读侧回落 per-turn 基线 mode）
     * @param rules       3 个 behavior 桶（键恒在，值可空列表）
     * @param directories SESSION 归属的附加工作目录（有序）
     */
    record Parsed(PermissionMode mode,
                  Map<PermissionBehavior, List<PermissionRule>> rules,
                  List<String> directories) {

        Parsed {
            Map<PermissionBehavior, List<PermissionRule>> copy = new EnumMap<>(PermissionBehavior.class);
            for (PermissionBehavior behavior : PermissionBehavior.values()) {
                List<PermissionRule> value = rules != null ? rules.get(behavior) : null;
                copy.put(behavior, value != null ? List.copyOf(value) : List.of());
            }
            rules = copy;
            directories = directories != null ? List.copyOf(directories) : List.of();
        }

        static Parsed empty() {
            return new Parsed(null, Map.of(), List.of());
        }

        /** 无任何 SESSION 条目（读侧据此跳过注入 / 写侧据此写 NULL）。 */
        boolean isEmpty() {
            return mode == null
                && rules.get(PermissionBehavior.ALLOW).isEmpty()
                && rules.get(PermissionBehavior.DENY).isEmpty()
                && rules.get(PermissionBehavior.ASK).isEmpty()
                && directories.isEmpty();
        }
    }
}
