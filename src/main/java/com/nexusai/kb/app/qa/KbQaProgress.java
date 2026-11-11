package com.nexusai.kb.app.qa;

import com.nexusai.eventbus.ws.MessageToolCallEvent;
import com.nexusai.eventbus.ws.MessageToolResultEvent;
import com.nexusai.eventbus.ws.StreamEventSeq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 RAG 检索 / 查询改写阶段包装成「工具调用」事件推给前端。
 *
 * <p><b>源站</b>：{@code internal/application/service/chat_pipeline/progress.go}（306 行）
 * 的 {@code BeginRetrievalProgress} / {@code EndRetrievalProgress} /
 * {@code BeginQueryUnderstandProgress} / {@code EndQueryUnderstandProgress}。
 * 语义照抄，语法换成本仓事件类（{@code MessageToolCallEvent} / {@code MessageToolResultEvent}，
 * 经 {@link StreamEventSeq#send} 盖会话内序号后投递）。
 *
 * <p><b>⭐ 核心语义（源站 :185-199）</b>：{@code count} 必须是「真正进入上下文的条数」，
 * 不是召回条数。检索短路进兜底回答（源站 {@code ErrSearchNothing}）时没有任何 chunk 到达模型，
 * 也就没有任何 chunk 可被引用；把原始召回数当结果数报出去，会让时间线承诺一个答案从未见过的
 * 结果条数、并给出一个点开却没有引用的条目。原始命中改报为「候选」，才是阈值把它们筛掉时
 * 真正有用的信息。
 *
 * <p><b>开关为什么用 {@code @Value} 而不是 {@code KbConfigService}</b>：
 * {@code KbConfigService} 没有通用 key getter（只有 {@code readQaParams()} 等专用方法），
 * 而 {@code QaParams} 是既有 record，加字段会连坐它全部构造点。为一个布尔去动配置表不划算，
 * 故本类用 {@code @Value("${kb.qa.emit-tool-progress:true}")} 私接开关，本批不改配置表。
 *
 * <p><b>fail-soft</b>：所有推送 try/catch {@code RuntimeException}，只 {@code log.warn}，
 * ⛔ 绝不把异常抛给问答主链 —— 进度事件是附加展示，推失败不该让用户拿不到答案。
 */
public final class KbQaProgress {

    private static final Logger log = LoggerFactory.getLogger(KbQaProgress.class);

    /** 检索窗口工具名（源站 :15）。 */
    public static final String TOOL_KNOWLEDGE_SEARCH = "knowledge_search";

    /** 改写阶段工具名（源站 :16）。 */
    public static final String TOOL_QUERY_UNDERSTAND = "query_understand";

    /** 检索来源枚举（源站 :18-20）。 */
    public static final String SOURCE_KNOWLEDGE = "knowledge";
    public static final String SOURCE_WEB = "web";
    public static final String SOURCE_MIXED = "mixed";

    /** 三态文案（源站 :216-222，逐字）。 */
    private static final String TEXT_FOUND_PREFIX = "检索到 ";
    private static final String TEXT_FOUND_SUFFIX = " 条相关内容";
    private static final String TEXT_CANDIDATE_PREFIX = "命中 ";
    private static final String TEXT_CANDIDATE_SUFFIX = " 条候选，相关性不足，未用于回答";
    private static final String TEXT_NOTHING = "未检索到相关内容";

    /** 改写完成文案（源站 :151，逐字）。 */
    private static final String TEXT_QUERY_UNDERSTAND_DONE = "已完成问题理解";

    /** 未配对窗口的清理阈值：超过此龄的登记视为孤儿（begin 后主链抛异常没走到 end）。 */
    private static final long STALE_WINDOW_MS = 10 * 60 * 1000L;

    /**
     * 开关桥接：{@code null} = Spring 未注入（单测直构）⇒ 默认 {@code true}。
     *
     * <p>与 {@code MemoryRemoteModeConfig} 同款「构造器桥接到静态字段」写法 —— 本类是静态工具类，
     * {@code @Value} 注不进静态字段，只能靠一个被组件扫描的嵌套配置类转一手。
     */
    private static volatile Boolean emitToolProgress;

    /**
     * {@link Stage} 只带 toolCallId/toolName/startedAtMs（规格如此），推送所需的
     * ws + topic + 三个消息 ID 按 toolCallId 登记在这里，{@code end*} 取用并移除。
     *
     * <p>孤儿登记（begin 后未 end）由 {@link #register} 按 10 分钟龄惰性清理。
     */
    private static final Map<String, Transport> TRANSPORTS = new ConcurrentHashMap<>();

    private KbQaProgress() {}

    /**
     * 一个进行中的进度窗口。{@code null} 表示未开启（开关关闭、无 ws 或推送失败）。
     *
     * @param toolCallId  本次工具调用 ID（与 tool_call / tool_result 配对）
     * @param toolName    工具名
     * @param startedAtMs 开始时间（毫秒）；{@code end*} 据此算耗时
     */
    public record Stage(String toolCallId, String toolName, long startedAtMs) {}

    /**
     * 检索开始：发一条 {@code message.tool_call}（源站 :79-106）。
     *
     * @param searchSource {@code knowledge} / {@code web} / {@code mixed}；未知值按 knowledge
     * @return 进行中的窗口；{@code null} = 没发（开关关闭 / 无 ws / 推送失败）
     */
    public static Stage beginRetrieval(SimpMessagingTemplate ws, String topic,
                                       String sessionId, String userMessageId,
                                       String assistantMessageId,
                                       String query, String searchSource) {
        if (!enabled() || ws == null) {
            return null;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("search_source", normalizeSource(searchSource));
        if (query != null && !query.isEmpty()) {
            args.put("query", query);
        }
        String toolCallId = UUID.randomUUID().toString();
        return begin(ws, topic, sessionId, userMessageId, assistantMessageId,
            toolCallId, TOOL_KNOWLEDGE_SEARCH, args);
    }

    /**
     * 检索结束：发配对的 {@code message.tool_result}（源站 :173-249）。
     *
     * <p>⭐ {@code count} = 真正进入上下文的条数。
     * ⭐ {@code searchNothing=true}（短路进兜底）时 count 归零、改报 candidateCount；
     * candidateCount 未给（{@code <= 0}）就退回原始召回数，等价源站
     * {@code candidateCount = count; count = 0}。
     *
     * <p>三态文案逐字对齐源站 :216-222。
     */
    public static void endRetrieval(Stage stage, boolean searchNothing,
                                    int count, int candidateCount, String errorMessage) {
        if (stage == null) {
            return;
        }
        boolean success = searchNothing || isBlank(errorMessage);

        int effectiveCount = count;
        int effectiveCandidate = candidateCount;
        if (searchNothing) {
            if (effectiveCandidate <= 0) {
                effectiveCandidate = count;
            }
            effectiveCount = 0;
        }

        String result;
        if (!success) {
            // 源站 :213-222：失败时 output 为空串，错误文本走独立 Error 字段。
            // 本仓 MessageToolResultEvent 无 Error 字段，错误文本只能落在 result，
            // 否则失败原因全丢（isError=true 已能区分语义）。
            result = errorMessage;
        } else if (effectiveCount > 0) {
            result = TEXT_FOUND_PREFIX + effectiveCount + TEXT_FOUND_SUFFIX;
        } else if (effectiveCandidate > 0) {
            result = TEXT_CANDIDATE_PREFIX + effectiveCandidate + TEXT_CANDIDATE_SUFFIX;
        } else {
            result = TEXT_NOTHING;
        }

        emitResult(stage, result, !success, "检索");
    }

    /**
     * 改写开始：{@code message.tool_call}，工具名 {@code query_understand}（源站 :108-134）。
     *
     * <p>「是否该发」由调用方判断（源站 {@code ShouldEmitQueryUnderstandProgress}：改写开关打开
     * 或带图），本方法只负责发。
     */
    public static Stage beginQueryUnderstand(SimpMessagingTemplate ws, String topic,
                                             String sessionId, String userMessageId,
                                             String assistantMessageId, String query) {
        if (!enabled() || ws == null) {
            return null;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (query != null && !query.isEmpty()) {
            args.put("query", query);
        }
        String toolCallId = UUID.randomUUID().toString();
        return begin(ws, topic, sessionId, userMessageId, assistantMessageId,
            toolCallId, TOOL_QUERY_UNDERSTAND, args);
    }

    /** 改写结束：配对的 {@code message.tool_result}（源站 :136-171）。 */
    public static void endQueryUnderstand(Stage stage, boolean ok, String errorMessage) {
        if (stage == null) {
            return;
        }
        String result = ok ? TEXT_QUERY_UNDERSTAND_DONE : errorMessage;
        emitResult(stage, result, !ok, "改写");
    }

    // ---------------------------------------------------------------- 内部

    /** 发开始帧并登记推送上下文；推送失败 → 不登记、返回 null。 */
    private static Stage begin(SimpMessagingTemplate ws, String topic,
                               String sessionId, String userMessageId,
                               String assistantMessageId, String toolCallId,
                               String toolName, Map<String, Object> args) {
        // 开始时间在发送前取（源站由调用方在 Begin 之前取 start）⇒ 耗时覆盖整段窗口
        long startedAtMs = System.currentTimeMillis();
        try {
            StreamEventSeq.send(ws, topic, new MessageToolCallEvent(
                sessionId, userMessageId, assistantMessageId, toolCallId, toolName, args));
        } catch (RuntimeException e) {
            log.warn("[问答进度] {} 开始事件推送失败，本轮不展示进度 原因={}", toolName, e.getMessage());
            return null;
        }
        register(toolCallId, new Transport(
            ws, topic, sessionId, userMessageId, assistantMessageId, startedAtMs));
        return new Stage(toolCallId, toolName, startedAtMs);
    }

    /**
     * 发结果帧。耗时 = 当前时间 - 开始时间；时钟回拨钳到 0（前端「耗时」不可为负）。
     *
     * <p>无登记（已 end 过 / 从未 begin / 开关中途关闭）→ 静默跳过。
     */
    private static void emitResult(Stage stage, String result, boolean isError, String label) {
        Transport t = TRANSPORTS.remove(stage.toolCallId());
        if (!enabled() || t == null) {
            return;
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - stage.startedAtMs());
        try {
            StreamEventSeq.send(t.ws(), t.topic(), new MessageToolResultEvent(
                t.sessionId(), t.userMessageId(), t.assistantMessageId(),
                stage.toolCallId(), result, isError, durationMs));
            log.debug("[问答进度] {} 结束事件已推送 tool={} isError={} durationMs={}",
                label, stage.toolName(), isError, durationMs);
        } catch (RuntimeException e) {
            log.warn("[问答进度] {} 结束事件推送失败 原因={}", label, e.getMessage());
        }
    }

    /** 登记推送上下文，顺带惰性清理超过 {@link #STALE_WINDOW_MS} 的孤儿登记。 */
    private static void register(String toolCallId, Transport transport) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Transport>> it = TRANSPORTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Transport> e = it.next();
            if (now - e.getValue().registeredAtMs() > STALE_WINDOW_MS) {
                it.remove();
            }
        }
        TRANSPORTS.put(toolCallId, transport);
    }

    private static boolean enabled() {
        Boolean v = emitToolProgress;
        return v == null || v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 未知 / 空来源按 knowledge（源站 :262-273 默认分支）。 */
    private static String normalizeSource(String source) {
        if (SOURCE_WEB.equals(source) || SOURCE_MIXED.equals(source)) {
            return source;
        }
        return SOURCE_KNOWLEDGE;
    }

    /** 一次进度窗口的推送上下文（{@link Stage} 装不下，单独登记）。 */
    private record Transport(SimpMessagingTemplate ws, String topic, String sessionId,
                             String userMessageId, String assistantMessageId,
                             long registeredAtMs) {}

    /**
     * 生产开关：{@code kb.qa.emit-tool-progress}（默认 true），经构造器参数桥接静态字段。
     *
     * <p>单构造器 + 构造器参数 {@code @Value}：Spring 注入即写静态字段，不需要
     * {@code @PostConstruct}，也不会有「双构造器未标 @Autowired」的装配歧义
     * （测试直接 {@code new ProgressSwitchConfig(false)}）。
     */
    @Component
    public static class ProgressSwitchConfig {

        ProgressSwitchConfig(@Value("${kb.qa.emit-tool-progress:true}") boolean emit) {
            KbQaProgress.setEnabled(emit);
        }
    }

    /** 开关写入（供配置类与测试）。 */
    static void setEnabled(boolean emit) {
        emitToolProgress = emit;
    }

    /** 测试辅助：回到「未注入」状态（默认 true），防跨测试污染。 */
    static void reset() {
        emitToolProgress = null;
        TRANSPORTS.clear();
    }
}
