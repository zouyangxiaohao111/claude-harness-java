package com.nexusai.kb.app.ingest.span;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.kb.api.dto.KnowledgeSpansDto;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbProcessingSpanRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code kb_processing_span} 的行装配成前端时间线要的 span 树 · 对齐源站
 * WeKnora {@code internal/handler/knowledge.go:922-1041} 的 {@code buildSpanTree}。
 *
 * <p><b>纯逻辑（不碰数据库）</b>：行由调用方（controller）查好传进来。这样单测可以
 * 直接构造行对象跑真值表，不用假装一个 Mapper。
 *
 * <p><b>⭐ 恒定五段</b>：{@link #ALL_STAGES} 里的阶段即使库里没有行也要补一条
 * {@code kind=stage / status=pending} 的占位（无 {@code span_id}、无 {@code started_at}），
 * 前端时间线永远渲染五段。老文档（span 表为空但 {@code parse_status} 已是终态）的占位状态
 * 按 {@code parse_status} 推断 —— 否则「早就完成的文档」会永远显示成「排队中」。
 *
 * <p><b>⭐ 子节点顺序</b>：源站把占位<b>追加</b>在真实 stage 之后；本仓改为
 * 「五段按 {@link #ALL_STAGES} 的固定顺序排列（真实行或占位都按位落座）」，
 * 否则只记录了 {@code embedding} 的文档，时间线顺序会变成
 * {@code embedding, docreader, chunking, ...} —— 与「恒定五段」的展示契约冲突。
 * 这是本批唯一有意偏离源站的地方。
 */
public final class KbSpanTreeBuilder {

    /** 五大主流水线阶段（顺序即时间线顺序）· 与 {@link KbSpanTracker} 的 ALL_STAGES 同源。 */
    public static final List<String> ALL_STAGES =
            List.of("docreader", "chunking", "embedding", "multimodal", "postprocess");

    /**
     * 多久没有活动算「卡住」· 与前端 {@code PROCESSING_STALL_THRESHOLD_MS}
     * 和源站 {@code stallHintAfter = 20 * time.Minute} 逐字一致（plan §9.2）。
     */
    public static final long STALL_THRESHOLD_MS = 20L * 60L * 1000L;

    /** 还在排队（队列里仍有本文件的 queued/running 任务）。 */
    public static final String STALL_QUEUED = "queued";

    /** 没排队但长时间无进展。 */
    public static final String STALL_STALLED = "stalled";

    /** 合成 root 的名字（与 tracker 的 ROOT_NAME 逐字一致）。 */
    public static final String ROOT_NAME = "knowledge_processing";

    /**
     * 阶段上游依赖 · 与 {@link KbSpanTracker} 的私有 {@code STAGE_UPSTREAM} 逐条一致。
     * ⚠️ tracker 里那份是 private（本批不许改 tracker）⇒ 这里重复一份；
     * 两侧若漂移，占位状态的「下游取消」会与真实运行期的级联不一致。
     */
    private static final Map<String, List<String>> STAGE_UPSTREAM = Map.of(
            "docreader", List.of(),
            "chunking", List.of("docreader"),
            "embedding", List.of("chunking"),
            "multimodal", List.of("chunking"),
            "postprocess", List.of("embedding", "multimodal"));

    /** 状态常量（取自 {@link KbSpanStatus}，此处不重复定义取值）。 */
    private static final String[] IN_FLIGHT_STATUSES = {
            "pending", "processing", "finalizing"};

    private static final ObjectMapper JSON = new ObjectMapper();

    private KbSpanTreeBuilder() {
    }

    /**
     * 装配结果（controller 直接铺进 {@link KnowledgeSpansDto}）。
     *
     * @param trace          根节点（永不为 null —— 前端恒定期望一个 trace 对象）
     * @param currentStage   正在跑 / 最近跑过的阶段；无 ⇒ 空串
     * @param lastError      最近一次失败；无 ⇒ null
     * @param lastActivityAt 最近活动时间（{@code yyyy-MM-dd HH:mm:ss}）；解析不支持时 null
     * @param stallState     {@code queued} / {@code stalled} / null
     * @param inFlight       parse_status 是否处于「还在跑」（pending/processing/finalizing）
     */
    public record Built(
            KnowledgeSpansDto.SpanNode trace,
            String currentStage,
            KnowledgeSpansDto.LastError lastError,
            String lastActivityAt,
            String stallState,
            boolean inFlight) {
    }

    /**
     * 建树。
     *
     * @param knowledgeId          知识条目 ID（用于合成 root / 占位）
     * @param attempt              本次描述的 attempt（源站 = 显式入参或 span 表最新）
     * @param rows                 本 attempt 的全部 span 行（{@code id ASC}）
     * @param parseStatus          {@code kb_knowledge.parse_status}
     * @param knowledgeErrorMessage {@code kb_knowledge.error_message}（无 span 失败时的兜底）
     * @param knowledgeUpdatedAt   {@code kb_knowledge.updated_at}
     * @param queuedWork           该文件当前是否还有排队/执行中的入库任务（决定 queued vs stalled）
     * @param now                  当前时间（测试可注入固定值）
     * @return 装配结果
     */
    public static Built build(String knowledgeId, int attempt, List<KbProcessingSpanRecord> rows,
                              String parseStatus, String knowledgeErrorMessage,
                              String knowledgeUpdatedAt, boolean queuedWork, LocalDateTime now) {
        List<KbProcessingSpanRecord> safeRows = rows == null ? List.of() : rows;
        LocalDateTime clock = now == null ? LocalDateTime.now() : now;

        // ① 建索引：spanId → 节点；顺带认出 root / 各 stage 行 / 正在跑的 stage
        Map<String, KnowledgeSpansDto.SpanNode> nodes = new LinkedHashMap<>();
        KbProcessingSpanRecord rootRow = null;
        Map<String, KbProcessingSpanRecord> stageRowByName = new LinkedHashMap<>();
        String currentStage = "";
        KbProcessingSpanRecord lastFailure = null;
        for (KbProcessingSpanRecord row : safeRows) {
            if (row == null || row.getSpanId() == null) {
                continue;
            }
            nodes.put(row.getSpanId(), toNode(row));
            if (KbSpanKind.ROOT.equals(row.getKind()) && rootRow == null) {
                rootRow = row;
            }
            if (KbSpanKind.STAGE.equals(row.getKind()) && row.getName() != null) {
                stageRowByName.putIfAbsent(row.getName(), row);
            }
            if (KbSpanStatus.RUNNING.equals(row.getStatus())
                    && KbSpanKind.STAGE.equals(row.getKind())
                    && currentStage.isEmpty()) {
                currentStage = row.getName() == null ? "" : row.getName();
            }
            if (KbSpanStatus.FAILED.equals(row.getStatus())
                    && isLaterFailure(row, lastFailure)) {
                lastFailure = row;
            }
        }
        if (currentStage.isEmpty()) {
            currentStage = stageOfRunningSpan(safeRows);
        }
        if (currentStage.isEmpty()) {
            currentStage = firstFailedStage(stageRowByName);
        }
        if (currentStage.isEmpty()) {
            currentStage = lastNonPendingStage(stageRowByName);
        }

        // ② 老文档（库无 span 行但 parse_status 已终态）的占位状态推断
        String syntheticStatus = syntheticStatusOf(parseStatus);

        // ③ root：库里有就用真行，没有就合成（前端恒定期望一个 trace 对象）
        KnowledgeSpansDto.SpanNode root;
        if (rootRow == null) {
            root = new KnowledgeSpansDto.SpanNode(
                    "", null, ROOT_NAME, KbSpanKind.ROOT, syntheticStatus,
                    null, null, null, null, null, null, null, null, new ArrayList<>());
        } else {
            root = nodes.get(rootRow.getSpanId());
        }

        // ④ 挂子节点：按 rows 的插入序（id ASC），未知父 / 无父 ⇒ 挂到 root
        for (KbProcessingSpanRecord row : safeRows) {
            if (row == null || row.getSpanId() == null) {
                continue;
            }
            KnowledgeSpansDto.SpanNode node = nodes.get(row.getSpanId());
            if (node == null || node == root) {
                continue;
            }
            String parentId = row.getParentSpanId();
            KnowledgeSpansDto.SpanNode parent = parentId == null || parentId.isBlank()
                    ? null : nodes.get(parentId);
            if (parent == null) {
                root.children().add(node);
            } else {
                parent.children().add(node);
            }
        }

        // ⑤ 恒定五段：真实 stage 与占位一律按 ALL_STAGES 的顺序落座
        applyCanonicalStageOrder(root, stageRowByName, syntheticStatus, knowledgeId, attempt);

        // ⑥ 派生字段
        LocalDateTime lastActivity = lastActivityOf(knowledgeUpdatedAt, safeRows);
        boolean inFlight = isInFlight(parseStatus);
        String lastActivityAt = KbTimeFormat.format(lastActivity);
        String stallState = null;
        if (inFlight && lastActivity != null
                && Duration.between(lastActivity, clock).toMillis() >= STALL_THRESHOLD_MS) {
            stallState = queuedWork ? STALL_QUEUED : STALL_STALLED;
        }
        KnowledgeSpansDto.LastError lastError =
                lastErrorOf(lastFailure, parseStatus, knowledgeErrorMessage, knowledgeUpdatedAt);

        return new Built(root, currentStage == null ? "" : currentStage,
                lastError, lastActivityAt, stallState, inFlight);
    }

    // ── ① 节点转换 ─────────────────────────────────────────────────

    private static KnowledgeSpansDto.SpanNode toNode(KbProcessingSpanRecord row) {
        return new KnowledgeSpansDto.SpanNode(
                row.getSpanId(),
                row.getParentSpanId(),
                row.getName(),
                row.getKind(),
                row.getStatus(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getDurationMs(),
                row.getErrorCode(),
                row.getErrorMessage(),
                parseJsonQuietly(row.getInput()),
                parseJsonQuietly(row.getOutput()),
                parseJsonQuietly(row.getMetadata()),
                new ArrayList<>());
    }

    /**
     * JSON 文本 → 对象。⛔ 解析失败<b>不抛</b>：一条脏 input / metadata 不该让整个
     * 时间线端点 500（前端自己会把它当字符串渲染）。
     */
    static Object parseJsonQuietly(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    // ── ② 状态推断 ─────────────────────────────────────────────────

    /**
     * parse_status → 占位 stage 的合成状态。
     *
     * <p>⭐ 与源站的一处<b>有意偏离</b>（本批派单 §2.6 第 3 条）：源站对
     * {@code failed} 的老文档把五个占位都标成 {@code failed}；本仓改标 {@code pending}
     * —— 失败发生在哪个阶段并不知晓，全标 failed 等于凭空断言五段都跑过且都失败，
     * {@code pending} 让前端显示「未执行」，真正的失败原因由 {@code last_error} 给出。
     */
    static String syntheticStatusOf(String parseStatus) {
        if (parseStatus == null) {
            return KbSpanStatus.PENDING;
        }
        return switch (parseStatus) {
            case "completed" -> KbSpanStatus.DONE;
            case "cancelled" -> KbSpanStatus.CANCELLED;
            // failed（无 span 行）：不知道该段失败 ⇒ pending「未执行」
            default -> KbSpanStatus.PENDING;
        };
    }

    private static boolean isInFlight(String parseStatus) {
        if (parseStatus == null) {
            return false;
        }
        for (String s : IN_FLIGHT_STATUSES) {
            if (s.equals(parseStatus)) {
                return true;
            }
        }
        return false;
    }

    // ── ③ 恒定五段排序 ─────────────────────────────────────────────

    private static void applyCanonicalStageOrder(KnowledgeSpansDto.SpanNode root,
                                                 Map<String, KbProcessingSpanRecord> stageRowByName,
                                                 String syntheticStatus,
                                                 String knowledgeId, int attempt) {
        // 真实 stage 节点（按名字索引）与其它的顶层子节点分开
        Map<String, KnowledgeSpansDto.SpanNode> realStageNodes = new LinkedHashMap<>();
        List<KnowledgeSpansDto.SpanNode> others = new ArrayList<>();
        for (KnowledgeSpansDto.SpanNode child : root.children()) {
            if (KbSpanKind.STAGE.equals(child.kind()) && ALL_STAGES.contains(child.name())) {
                realStageNodes.putIfAbsent(child.name(), child);
            } else {
                others.add(child);
            }
        }

        List<KnowledgeSpansDto.SpanNode> ordered =
                new ArrayList<>(ALL_STAGES.size() + others.size());
        for (String name : ALL_STAGES) {
            KnowledgeSpansDto.SpanNode real = realStageNodes.get(name);
            ordered.add(real != null ? real
                    : placeholderStage(knowledgeId, attempt, name,
                            missingStageStatus(name, stageRowByName, syntheticStatus)));
        }
        ordered.addAll(others);

        root.children().clear();
        root.children().addAll(ordered);
    }

    /** 缺失阶段的占位节点：无 span_id、无 started_at（前端按 pending 画空条）。 */
    private static KnowledgeSpansDto.SpanNode placeholderStage(String knowledgeId, int attempt,
                                                               String name, String status) {
        return new KnowledgeSpansDto.SpanNode(
                null, null, name, KbSpanKind.STAGE, status,
                null, null, null, null, null, null, null, null, new ArrayList<>());
    }

    /**
     * 缺失阶段的状态：若有真实 stage 失败，则「失败位之后的阶段 + 依赖它的阶段」都算
     * cancelled（它们不可能跑过），其余按 parse_status 的合成状态（源站同款）。
     */
    static String missingStageStatus(String name,
                                     Map<String, KbProcessingSpanRecord> stageRowByName,
                                     String syntheticStatus) {
        int failedIdx = -1;
        for (int i = 0; i < ALL_STAGES.size(); i++) {
            KbProcessingSpanRecord row = stageRowByName.get(ALL_STAGES.get(i));
            if (row != null && KbSpanStatus.FAILED.equals(row.getStatus())) {
                failedIdx = i;
                break;
            }
        }
        if (failedIdx < 0) {
            return syntheticStatus;
        }
        String failedStage = ALL_STAGES.get(failedIdx);
        int nameIdx = ALL_STAGES.indexOf(name);
        if (nameIdx > failedIdx || stagesDependingOn(failedStage).contains(name)) {
            return KbSpanStatus.CANCELLED;
        }
        return syntheticStatus;
    }

    /** 求「以 stage 为上游（直接或间接）的阶段」传递闭包（与 tracker 同算法）。 */
    private static List<String> stagesDependingOn(String stage) {
        List<String> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        List<String> frontier = new ArrayList<>(List.of(stage));
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String candidate : ALL_STAGES) {
                if (seen.contains(candidate)) {
                    continue;
                }
                for (String dependency : STAGE_UPSTREAM.getOrDefault(candidate, List.of())) {
                    if (frontier.contains(dependency)) {
                        seen.add(candidate);
                        out.add(candidate);
                        next.add(candidate);
                        break;
                    }
                }
            }
            frontier = next;
        }
        return out;
    }

    // ── ④ current_stage ───────────────────────────────────────────

    /**
     * 从「仍 running 的非 root span」向上走到它所属的 stage（源站
     * {@code stageOfRunningSpan}）。倒序扫行：最后开工的那条最有代表性。
     */
    private static String stageOfRunningSpan(List<KbProcessingSpanRecord> rows) {
        Map<String, KbProcessingSpanRecord> bySpanId = new LinkedHashMap<>();
        for (KbProcessingSpanRecord row : rows) {
            if (row != null && row.getSpanId() != null) {
                bySpanId.put(row.getSpanId(), row);
            }
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            KbProcessingSpanRecord row = rows.get(i);
            if (row == null || !KbSpanStatus.RUNNING.equals(row.getStatus())
                    || KbSpanKind.ROOT.equals(row.getKind())) {
                continue;
            }
            KbProcessingSpanRecord cursor = row;
            for (int depth = 0; cursor != null && depth < 64; depth++) {
                if (KbSpanKind.STAGE.equals(cursor.getKind())) {
                    return cursor.getName() == null ? "" : cursor.getName();
                }
                String parentId = cursor.getParentSpanId();
                cursor = parentId == null ? null : bySpanId.get(parentId);
            }
        }
        return "";
    }

    /**
     * 没有 running 时的兜底 ①：第一个失败的 stage（按时间线顺序）。
     *
     * <p>派单 §2.6 第 4 条：{@code currentStage} 优先 running，其次 failed，
     * 最后才是「最后一个非 pending 的」。
     */
    private static String firstFailedStage(Map<String, KbProcessingSpanRecord> stageRowByName) {
        for (String name : ALL_STAGES) {
            KbProcessingSpanRecord row = stageRowByName.get(name);
            if (row != null && KbSpanStatus.FAILED.equals(row.getStatus())) {
                return name;
            }
        }
        return "";
    }

    /**
     * 没有 running / failed 时的兜底 ②：时间线上最后一个「非 pending」的真实阶段。
     * 都没有（老文档 / 刚创建）⇒ 空串（源站同款：前端按「未开始」渲染）。
     */
    private static String lastNonPendingStage(Map<String, KbProcessingSpanRecord> stageRowByName) {
        String last = "";
        for (String name : ALL_STAGES) {
            KbProcessingSpanRecord row = stageRowByName.get(name);
            if (row != null && row.getStatus() != null
                    && !KbSpanStatus.PENDING.equals(row.getStatus())) {
                last = name;
            }
        }
        return last;
    }

    // ── ⑤ last_error ──────────────────────────────────────────────

    /** 取「结束时间最晚」的失败行（同一秒内按 id 兜底，保持确定性）。 */
    private static boolean isLaterFailure(KbProcessingSpanRecord candidate,
                                          KbProcessingSpanRecord current) {
        if (current == null) {
            return true;
        }
        LocalDateTime candidateAt = parseTimeQuietly(candidate.getFinishedAt());
        LocalDateTime currentAt = parseTimeQuietly(current.getFinishedAt());
        if (candidateAt != null && currentAt != null && !candidateAt.equals(currentAt)) {
            return candidateAt.isAfter(currentAt);
        }
        long candidateId = candidate.getId() == null ? 0L : candidate.getId();
        long currentId = current.getId() == null ? 0L : current.getId();
        return candidateId >= currentId;
    }

    private static KnowledgeSpansDto.LastError lastErrorOf(KbProcessingSpanRecord spanFailure,
                                                           String parseStatus,
                                                           String knowledgeErrorMessage,
                                                           String knowledgeUpdatedAt) {
        if (spanFailure != null) {
            return new KnowledgeSpansDto.LastError(
                    spanFailure.getName(),
                    spanFailure.getErrorCode(),
                    spanFailure.getErrorMessage(),
                    spanFailure.getFinishedAt());
        }
        // 兜底（源站同款）：没有 span 失败但文档已 failed ⇒ 用 kb_knowledge.error_message
        if (!"failed".equals(parseStatus)
                || knowledgeErrorMessage == null || knowledgeErrorMessage.isBlank()) {
            return null;
        }
        String code = "UNKNOWN";
        if ("Task interrupted due to application restart".equalsIgnoreCase(
                knowledgeErrorMessage.trim())) {
            code = "SERVER_RESTART";
        }
        return new KnowledgeSpansDto.LastError(ROOT_NAME, code, knowledgeErrorMessage,
                knowledgeUpdatedAt);
    }

    // ── ⑥ last_activity_at ────────────────────────────────────────

    /**
     * 最近一次活动的近似值：{@code kb_knowledge.updated_at} 与所有 span 行
     * 写入时间的最大值。span 每次 upsert 都会刷新 {@code update_time}，
     * 所以长阶段里子 span 的写入能把「还在干活」反映出来（源站同思路）。
     */
    private static LocalDateTime lastActivityOf(String knowledgeUpdatedAt,
                                                List<KbProcessingSpanRecord> rows) {
        LocalDateTime last = parseTimeQuietly(knowledgeUpdatedAt);
        for (KbProcessingSpanRecord row : rows) {
            if (row == null) {
                continue;
            }
            LocalDateTime rowTime = latestOf(
                    parseTimeQuietly(row.getUpdateTime()),
                    parseTimeQuietly(row.getFinishedAt()),
                    parseTimeQuietly(row.getStartedAt()),
                    parseTimeQuietly(row.getCreateTime()));
            if (rowTime != null && (last == null || rowTime.isAfter(last))) {
                last = rowTime;
            }
        }
        return last;
    }

    private static LocalDateTime latestOf(LocalDateTime... values) {
        LocalDateTime last = null;
        for (LocalDateTime value : values) {
            if (value != null && (last == null || value.isAfter(last))) {
                last = value;
            }
        }
        return last;
    }

    private static LocalDateTime parseTimeQuietly(String value) {
        try {
            return KbTimeFormat.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
