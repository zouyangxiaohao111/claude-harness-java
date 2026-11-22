package com.nexusai.application.agent.config;

import com.alibaba.fastjson2.JSON;
import com.nexusai.application.agent.tool.SessionToolCategory;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.session.SessionTakeoverResolver;
import com.nexusai.common.SessionKeys;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 会话级禁用工具集合统一读取 · Java 独有增强（CC {@code tools.ts} 无「会话内临时禁用」内置机制，
 * G24 用户拍板：Web UX 产品功能，Java 端以「会话级禁用集合 + llmToolsArray 追加过滤」复刻
 * CC blanket deny 的 schema 阶段剔除效果，tools.ts:262-269「A tool is filtered out if there's a
 * deny rule matching its name ... before the model sees them」）。
 *
 * <p><b>静态桥接</b>：{@link #getDisabledTools(String)} 为静态（{@code LlmAgentLoop.llmToolsArray}
 * 位于 static 上下文，:8363），Spring 注入值经构造器/桥接方法写入静态字段。POJO {@code new}
 * 场景（单测）不触发 Spring → 桥接为 null → 返回空集（不剔除任何工具）；测试用 {@link #reset()}
 * 防跨测试污染（对齐 {@link MemoryBareModeConfig} 同款静态桥接惯例）。
 *
 * <p><b>会话键归一化</b>：与 {@code MemoryBareModeConfig.normalizeSessionKey} 同款——
 * {@link SessionKeys#originalKey(String)}（"sess-xxx" 原样 / 派生 UUID 反解），不可反解 →
 * 原样当 DB 键（对齐 EffortCommand.resolveSessionKey）。
 *
 * <p>消费方：{@code LlmAgentLoop.sessionVisibleTools}（在基链 {@code sessionVisibleToolsBase}
 * —— bare → deny → coordinator —— 之后应用会话禁用剔除，R3 校正：非「deny 后、coordinator
 * 前」；{@code AgentToolUtils.applyCoordinatorToolFilter} 为纯过滤、可交换，语义等价；
 * CC 顺序「simpleTools 选择先于 filterToolsByDenyRules，tools.ts:297」，bare 是最外层）
 * + {@code SessionToolsController}（GET 工具列表 disabled 标志）。
 */
@Component
public class SessionToolDisableConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionToolDisableConfig.class);

    /** 会话级禁用工具集合 DB 读取桥接（sessions.disabled_tools，V34 列）；null = 未注入（POJO 单测/无 Spring）→ 空集 */
    private static volatile SessionMapper sessionMapper;

    /**
     * 「是否已被真实用户接管」判定组件（可选注入）· 持久化读取职责归
     * {@link com.nexusai.application.session.SessionTakeoverResolver}，本类只消费判定结果。
     *
     * <p>null = 未注入（POJO 单测 / 无 Spring）→ 判定为「不是用户发过」→ 派生会话保持默认禁用 Ask
     * （宁可保守：判定不出来就按未接管处理）。
     */
    private static volatile SessionTakeoverResolver takeoverResolver;

    /**
     * Spring 注入 SessionMapper 桥接（setter 注入 → 静态字段，对齐 {@code MemoryBareModeConfig}
     * 同款静态桥接惯例；{@code @Autowired} 不支持静态字段/方法）。
     *
     * <p>会话级禁用工具读取（V34 列 disabled_tools）依赖该 mapper；POJO {@code new} 场景（单测）
     * 不触发 Spring → 桥接为 null → 返回空集。
     *
     * @param mapper 会话 mapper（Spring 注入，可为 null → 跳过桥接）
     */
    @Autowired(required = false)
    public void bridgeSessionMapper(SessionMapper mapper) {
        setSessionMapper(mapper);
    }

    /**
     * 会话 mapper 静态桥接 setter · 测试（跨包）+ Spring 桥接共用。
     */
    public static void setSessionMapper(SessionMapper mapper) {
        SessionMapper previous = sessionMapper;
        sessionMapper = mapper;
        // [F1 · 2026-09-18 返工] 只在桥接**真的发生变化**时记一行（原：mapper 非 null 就无条件 INFO）。
        //   本 setter 由 LlmAgentLoop.setSessionMapper 静态桥在**每次 prototype 实例化**时调用
        //   （LlmAgentLoop = @Component @Scope("prototype")）——调用方除了 ChatService 每轮新建 loop，
        //   还有 CronIdleExecutor.canDispatch() 的第三层「通道可用」探针（loopProvider.getObject()）。
        //   无条件 INFO 的代价 = 队列非空时每个探针一拍一行（≈1 行/3s ≈ 2.9 万行/天，与本批
        //   亲手修掉的 57600 行/天 同一量级），而这一行**不携带任何新信息**（注入的还是同一个单例 bean）。
        //   语义上值得留痕的是「桥接从未装 → 装上」或「换了另一个实现」，故判据 = 引用发生变化；
        //   ⛔ 不是「把噪声挪到别处」：同一事物的重复报告被消除，真实事件（首装 / 换实现）一行不少。
        if (mapper != null && mapper != previous) {
            log.info("SessionToolDisableConfig 注入 SessionMapper（会话级禁用工具集合读取可用，V34 列）");
        }
    }

    /**
     * 读取会话级禁用工具集合（sessions.disabled_tools，V34 列 JSON 数组）。
     *
     * <p><b>取值优先级（[tool-categories] 2026-11-12 用户裁定）</b>：
     * <ol>
     *   <li>列<b>非空</b> → 用该 JSON 数组（用户已显式设定，含 {@code "[]"} = 显式全部启用）；</li>
     *   <li>列<b>为 null / blank</b>（该会话从未设置过）→ 返回 {@link SessionToolCategory#defaultDisabledNamesStatic()}
     *       （电脑操作 + 知识库默认禁用，浏览器控制<b>不</b>默认禁用）；</li>
     *   <li>无会话上下文 / SessionMapper 未注入 / 会话查不到 / DB 读取失败 → 空集（不剔除任何工具）。</li>
     * </ol>
     *
     * <p><b>WHY 判据必须读原始串</b>：{@code null} 与 {@code "[]"} 语义不同 —— 前者「从未设置」
     * 走默认禁用，后者「用户把默认禁用的 50 项全部手动打开」必须读回空集。若只看
     * {@code Set} 是否为空就分不清两者，用户的打开操作会被默认规则静默吞掉
     * （{@code SessionService.setDisabledTools} 已改为空集存 {@code "[]"}）。
     *
     * <p><b>本方法是默认禁用的唯一推断点</b>：两条消费链同时生效 ——
     * {@code SessionToolsController.list}（面板显示 disabled=true）与
     * {@code LlmAgentLoop.sessionVisibleTools}（模型 schema 剔除）。
     *
     * @param sessionId 会话标识（TUC.sessionId() 派生 UUID / MDC "sess-xxx" / null）
     * @return 禁用工具名集合（不可变；显式全开 → 空集）
     */
    /**
     * Spring 注入「真人接管」判定组件（可选；setter 注入 → 静态字段，同 {@link #bridgeSessionMapper} 惯例）。
     *
     * @param resolver 判定组件（可为 null → 一律按「未接管」处理）
     */
    @Autowired(required = false)
    public void bridgeTakeoverResolver(SessionTakeoverResolver resolver) {
        setTakeoverResolver(resolver);
    }

    /**
     * 判定组件静态桥接 setter · 测试 + Spring 桥接共用。
     */
    public static void setTakeoverResolver(SessionTakeoverResolver resolver) {
        takeoverResolver = resolver;
    }

    /**
     * 会话是否已被真实用户接管（委托 {@link SessionTakeoverResolver}）。
     *
     * @param sessionId 已归一化的会话键
     * @return true = 有过真实用户消息；组件未注入 → false（保守）
     */
    static boolean hasRealUserMessage(String sessionId) {
        SessionTakeoverResolver resolver = takeoverResolver;
        return resolver != null && resolver.hasRealUserMessage(sessionId);
    }

    /**
     * 按会话禁用集过滤工具池（元素级，保序）· 供「需要与 LLM schema 同源」的旁路池使用。
     *
     * <p><b>WHY 抽出这个方法</b>：会话禁用的剔除原先只写在
     * {@code LlmAgentLoop.sessionVisibleTools}（发给模型的 tools schema）里，但下游还有两处
     * <b>自己拿原始池</b>做判断的地方：
     * <ol>
     *   <li>{@code ToolSearchTool} 的候选集 —— 用原始池 ⇒ 模型看不见禁用的工具，却仍能把它
     *       {@code select} 出来，返回的 {@code tool_reference} 进历史后下一轮直接 400；</li>
     *   <li>{@code deferred_tools_delta} 的 announce diff —— 用原始池 ⇒ 关闭工具时该工具仍在
     *       {@code poolNames} 里，{@code removed} 判定永不成立 ⇒ <b>不会</b>告诉模型「这些工具
     *       已不可用，别去搜」，模型继续 tool_reference 那些已下架的工具。</li>
     * </ol>
     *
     * @param tools     原始工具池（可为 null）
     * @param sessionId 会话标识（null → 原样返回）
     * @return 过滤后的新列表；无禁用 → 原引用（零行为变化、零分配）
     */
    public static List<Tool> filterDisabled(List<Tool> tools, String sessionId) {
        if (tools == null || tools.isEmpty() || sessionId == null) {
            return tools;
        }
        Set<String> disabled = getDisabledTools(sessionId);
        if (disabled.isEmpty()) {
            return tools;
        }
        List<Tool> out = new ArrayList<>(tools.size());
        for (Tool t : tools) {
            if (t != null && !disabled.contains(t.name())) {
                out.add(t);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("会话禁用过滤工具池：session={} {}→{}（剔除 {}）",
                sessionId, tools.size(), out.size(), tools.size() - out.size());
        }
        return out;
    }

    /**
     * 该工具是否被<b>本会话显式禁用</b>（只读 {@code sessions.disabled_tools} 的实际值）。
     *
     * <p>⛔ 不用「默认禁用推断」：默认禁用的 CU/KB 可被人格点名豁免（2026-11-18 用户拍板），
     * 执行入口若按默认集拦，会把 schema 已经放行的人格工具又挡回去。
     *
     * <p>本批（V329）消费它的两个场景都是<b>显式写入</b>：
     * Brief 关时写 SendUserMessage、用户手动禁用的工具；因此只看显式列值即可，
     * 且不会误伤默认集/人格豁免。
     *
     * @param toolName  工具名（null → false）
     * @param sessionId 会话 id（null → false）
     * @return true = 本会话显式禁用
     */
    public static boolean isDisabledForSession(String toolName, String sessionId) {
        return toolName != null && getExplicitDisabledTools(sessionId).contains(toolName);
    }

    /**
     * 读 {@code sessions.disabled_tools} 的<b>显式</b>值（不做默认禁用推断）。
     *
     * <p>供执行器在「本轮开始」时快照一次（StreamingToolExecutor），使运行中切换工具/模式
     * 只影响下一轮，不撤销本轮已装配的工具池。
     *
     * @param sessionId 会话 id
     * @return 显式禁用集合；列 null/blank、无 mapper、读失败 → 空集
     */
    public static Set<String> getExplicitDisabledTools(String sessionId) {
        String key = normalizeSessionKey(sessionId);
        SessionMapper mapper = sessionMapper;
        if (key == null || mapper == null) {
            return Set.of();
        }
        try {
            SessionRecord s = mapper.selectOneById(key);
            if (s == null) {
                return Set.of();
            }
            String raw = s.getDisabledTools();
            if (raw == null || raw.isBlank()) {
                return Set.of();
            }
            List<String> list = JSON.parseArray(raw, String.class);
            return list == null || list.isEmpty() ? Set.of() : Set.copyOf(list);
        } catch (RuntimeException e) {
            log.warn("会话级禁用工具显式读取失败（按未禁用处理）: 会话 '{}' 原因={}", key, e.toString());
            return Set.of();
        }
    }

    /**
     * 该会话当前生效的禁用集合是否来自<b>默认禁用集</b>（{@code disabled_tools} 为 null/blank）。
     *
     * <p><b>为什么需要这个判据</b>：2026-11-18 用户拍板「人格优先」——默认禁用集与人格点名
     * 冲突时，人格指定的工具生效，非冲突项照旧禁用。但这条规则<b>只适用于默认集</b>：
     * 用户显式写进 {@code disabled_tools} 的项是用户的明确意图，⛔ 不允许被人格定义覆盖。
     *
     * @param sessionId 会话 id
     * @return true = 走默认禁用集（可被豁免）；false = 显式列值 / 无会话 / 读失败（按显式处理）
     */
    public static boolean isDefaultDisabledSet(String sessionId) {
        String key = normalizeSessionKey(sessionId);
        SessionMapper mapper = sessionMapper;
        if (key == null || mapper == null) {
            return false;
        }
        try {
            SessionRecord s = mapper.selectOneById(key);
            if (s == null) {
                return false;
            }
            String raw = s.getDisabledTools();
            return raw == null || raw.isBlank();
        } catch (RuntimeException e) {
            // 读失败 ⇒ 按「显式列值」处理（不豁免），宁可少放行不可多放行
            log.warn("会话级禁用工具来源判定失败（按显式列值处理）: 会话 '{}' 原因={}", key, e.toString());
            return false;
        }
    }

    public static Set<String> getDisabledTools(String sessionId) {
        String key = normalizeSessionKey(sessionId);
        SessionMapper mapper = sessionMapper;
        if (key != null && mapper != null) {
            try {
                SessionRecord s = mapper.selectOneById(key);
                if (s != null) {
                    String raw = s.getDisabledTools();
                    if (raw == null || raw.isBlank()) {
                        // 从未设置 → 默认禁用（电脑操作 + 知识库）
                        Set<String> defaults = SessionToolCategory.defaultDisabledNamesStatic();
                        // [session-create-derived · 2026-11-22 用户拍板]
                        //   派生/平级会话（SessionCreate 创建，derived_depth 非空）默认禁用 AskUserQuestion：
                        //   需要信息时先走 SendMessage 问发起会话，别反复打断用户。
                        //   ⚠️ 这是本仓有意偏离 CC：CC 2.1.328 只对 agent 子代理禁用该工具
                        //   （I8t/filterToolsForAgent），平级 peer 会话并不禁用 —— 文档/注释不得写成对齐。
                        //
                        //   ⭐接管放开：真实用户在该会话发过消息（{@link #hasRealUserMessage}）后
                        //   不再追加 Ask。当前轮工具列表已构造完不可变 ⇒ 实际是**下一轮**才生效，
                        //   ⛔ 不为当轮重造工具列表。
                        //   ⭐IM 渠道入站**不算**接管：IM 入站行 role=user 但 is_meta=true
                        //   （ImInboundRouter:252-257 → CronIdleExecutor:1197-1218 以 isCron 落库），
                        //   由 SessionTakeoverResolver 的严格白名单排除。
                        //   ⭐无人值守优先级更高：unattended_mode 的 Ask 剔除在
                        //   SessionToolVisibility.capture 的 askExcluded 层，与本处无关。
                        //
                        //   只在 raw 为空时追加：用户显式调整过工具列表 ⇒ 以用户列表为准。
                        //   派生会话默认禁用 AskUserQuestion（见上）。
                        if (s.getDerivedDepth() != null && !hasRealUserMessage(key)) {
                            Set<String> withAsk = new java.util.LinkedHashSet<>(defaults);
                            withAsk.add(ToolNameConstants.ASK_USER_QUESTION_TOOL_NAME);
                            defaults = java.util.Collections.unmodifiableSet(withAsk);
                        }
                        // [诊断 · 2026-11-18] 「池 110 → 2」只剩这一条能解释的路径：默认禁用集过大。
                        //   升级为 INFO 并打样例，重启后一次复现即可判定（诊断完可降回 DEBUG）。
                        if (log.isInfoEnabled()) {
                            log.info("会话级禁用工具集合：会话 '{}' disabled_tools 未设置 → 默认禁用 {} 个"
                                    + "（CU+KB，[tool-categories]）样例={}", key, defaults.size(),
                                    defaults.stream().limit(8).toList());
                        }
                        return defaults;
                    }
                    List<String> list = JSON.parseArray(raw, String.class);
                    if (list != null && !list.isEmpty()) {
                        if (log.isDebugEnabled()) {
                            log.debug("会话级禁用工具集合：会话 '{}' disabled_tools={} 生效（V34 列）",
                                    key, list);
                        }
                        return Set.copyOf(list);
                    }
                    // "[]" = 用户显式全部启用（不回落默认禁用）
                    if (log.isDebugEnabled()) {
                        log.debug("会话级禁用工具集合：会话 '{}' disabled_tools 为 [] → 显式全开，不启动默认禁用", key);
                    }
                    return Set.of();
                }
                if (log.isDebugEnabled()) {
                    log.debug("会话级禁用工具集合：会话 '{}' 查不到 → 空集（不启用默认禁用）", key);
                }
            } catch (Exception e) {
                log.warn("会话级禁用工具集合：会话 '{}' disabled_tools 读取失败（{}），降级空集",
                        key, e.getMessage());
            }
        } else if (log.isDebugEnabled()) {
            log.debug("会话级禁用工具集合：无会话上下文（sessionId={}）或 SessionMapper 未注入 → 空集",
                    sessionId == null ? "null" : sessionId);
        }
        return Set.of();
    }

    /**
     * 会话键归一化 · [session-id-short] 入参已 short 直返（不再 originalKey 反解——调用方传
     * ctx.sessionId() 已统一形态；存量旧行读取可保留 originalKey 兜底，阶段2 删）。
     */
    private static String normalizeSessionKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String original = SessionKeys.originalKey(sessionId);
        return original != null ? original : sessionId;
    }

    /**
     * 测试辅助：重置静态桥接（防跨测试 / 跨 Spring 上下文污染）。
     */
    public static void reset() {
        sessionMapper = null;
        takeoverResolver = null;
    }
}
