package com.nexusai.application.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.common.NexusaiDataRoots;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.ArtifactContentUpdateResponse;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.SessionArtifactDto;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 会话「产物 / 来源」抽取服务 —— 轮末<b>异步、fire-and-forget</b>地从全部历史消息里抽取
 * 「用户最终要的结果文件」（deliverable）与「引用的外部来源」（source），落
 * {@code session_artifact} 表供前端只读轮询。
 *
 * <p><b>WHY 需要它</b>：产物/来源不是消息里的结构化字段，只能由模型从长对话里归纳；
 * 而归纳必须<b>完全离线于对话</b>（用户不该等它），所以做成轮末旁路任务：
 * <ul>
 *   <li>调用点：{@code ChatService} 轮末 {@code refreshTitleAfterTurn} 之后一行
 *       {@link #scheduleExtract(String)} —— 已经是「收尾已完成」之后的独立语句，
 *       ⛔ 不在 finally 之前、不阻塞 status=idle 事件。</li>
 *   <li>失败语义：本类<b>全流程 try/catch</b>，任何异常只 WARN，绝不向调用线程抛出。</li>
 *   <li>开关：{@code nexusai.artifacts.extract-enabled}（默认 true）—— 关掉即完全不触发。</li>
 * </ul>
 *
 * <p><b>幂等</b>：重复抽取按 (session_id, kind, title) UPSERT —— 同一交付物的多版本只留最终版，
 * 不堆历史版本。
 *
 * <p><b>规则五</b>：模型只做「判断/提取」（哪些算产物、哪些算来源），路径解析、存在性校验、
 * 落库全部是确定性代码。
 */
@Component
public class SessionArtifactService {

    private static final Logger log = LoggerFactory.getLogger(SessionArtifactService.class);

    /** 项目惯例：各服务静态 ObjectMapper。 */
    private static final ObjectMapper JSON = new ObjectMapper();


    /** 每会话防抖窗口：10 秒内重复触发合并成一次。 */
    private static final long DEBOUNCE_MS = 10_000L;

    /** 防抖表清理阈值 / 存活窗口（防长跑进程里会话数无界增长）。 */
    private static final int DEBOUNCE_PRUNE_SIZE = 2000;
    private static final long DEBOUNCE_PRUNE_AGE_MS = 10 * 60_000L;

    /** 列容量护栏（V205 注释里的语义长度；SQLite TEXT 不强制，这里按展示容量截断）。 */
    private static final int TITLE_MAX = 512;
    private static final int PATH_MAX = 1024;
    private static final int URL_MAX = 2048;
    private static final int SUMMARY_MAX = 2000;

    /**
     * 出站的「那一轮提问」正文上限（字）。
     *
     * <p>WHY 要截断：{@code messages.content} 是整段原始提问（可能带长附件说明 / 整篇粘贴），
     * 产物列表是**分组标题**用途，几百 KB 的正文会把整个响应体和前端渲染拖死。
     * 500 足够放下正常的提问句；超长加 {@code …} 明示「还有后文」。
     */
    private static final int TURN_QUESTION_MAX = 500;

    private static final String KIND_DELIVERABLE = "deliverable";
    private static final String KIND_SOURCE = "source";

    /** 产物来源通道：写进会话交付物目录（Agent 按提示词主动交付）。 */
    static final String ORIGIN_DELIVERABLES = "deliverables";

    /** 产物来源通道：Write/Edit 写过的项目内文档（V279 起，用户 2026-11-10 裁定）。 */
    static final String ORIGIN_PROJECT_WRITE = "project-write";

    /**
     * 项目内文档白名单扩展名（**大小写不敏感**）。
     *
     * <p><b>WHY 要有白名单，而不是只靠 {@link #SOURCE_CODE_EXTENSIONS} 黑名单</b>：
     * 黑名单只枚举了「绝对不收」的源码/构建扩展名，对**无扩展名**文件（{@code exclude}、
     * {@code .gitignore}、{@code Makefile}）和黑名单没列到的二进制（{@code .db}、{@code .bin}）
     * 一律放行 ⇒ 产物面板会被配置文件和数据库文件刷屏。白名单是正向声明「什么算用户会看的文档」。
     *
     * <p>实测口径（近 2000 次 Write/Edit）：java 1076 / tsx 318 / ts 149 / py 145 全被黑名单拦，
     * 真正该收的 {@code md} 有 187 次 —— 白名单就是为这一类开的门。
     */
    private static final Set<String> DOC_EXTENSIONS = Set.of(
            "md", "markdown", "html", "htm", "pdf",
            "docx", "doc", "xlsx", "xls", "pptx", "ppt",
            "txt", "csv", "rtf", "odt", "epub");

    /**
     * 项目内文档采集的路径排除段（**任一段命中即不收**）。
     *
     * <p>WHY：{@code node_modules} 里几万个 {@code .md}、{@code target} 里编译期生成的
     * 报告、{@code dist} 里打包产物 —— 都不是「用户要的东西」，收进来只会把面板淹掉。
     */
    private static final Set<String> EXCLUDED_PATH_SEGMENTS = Set.of(
            "node_modules", ".git", "target", "build", "dist", ".next", "__pycache__",
            ".venv", "venv", ".gradle", ".idea", ".vscode", "coverage");

    /** 归档根目录名：{@code {configHome}/artifacts} · 与 {@code image-cache} 同族（决策 D1 自有根）。 */
    private static final String ARTIFACTS_DIR_NAME = "artifacts";

    /**
     * 单文件归档上限（MB）。
     *
     * <p>WHY 有上限：抽取是轮末旁路任务，复制一个几百 MB 的模型/视频文件会长时间占用抽取线程 + 磁盘；
     * 超限时不复制、只记录原路径（WARN），条目照常可见（降级而非丢弃）。
     */
    static final int ARCHIVE_MAX_MB = 50;

    /** 归档文件名里的路径指纹长度（sha256 十六进制前 12 位）。 */
    private static final int ARCHIVE_HASH_LEN = 12;

    /**
     * 产物内容预览上限（512KB）。
     *
     * <p>WHY 有上限：内容接口是给浏览器预览用的，一个 200MB 的 PDF/模型文件直接读进来会
     * 打爆响应体与前端内存；超限只回前 512KB 并置 {@code truncated=true}（前端明说「已截断」）。
     */
    static final int CONTENT_MAX_BYTES = 512 * 1024;

    /**
     * 原始字节下发 / 文本写回的单文件上限（50MB）。
     *
     * <p><b>WHY 取 50MB</b>：与 {@link #ARCHIVE_MAX_MB}（归档副本上限）对齐 —— 归档成功的产物副本
     * 天然 ≤50MB，所以这个上限只在「归档失败、{@code local_path} 回落到用户原文件」时才可能触发；
     * 那种情况下文件可能是任意大小 ⛔ 不能无界读进内存（打爆响应体与前端内存）。
     * 与 {@code ProjectService.readFileBytes} 的 100MB 不同：本仓项目文件通道服务的是用户自选目录，
     * 而产物通道服务的是「我们自己归档的副本」，50MB 已覆盖绝大多数交付物（docx / pdf / xlsx）。
     */
    static final long RAW_MAX_BYTES = 50L * 1024L * 1024L;

    /**
     * 会产出来源的网络工具名（按名字匹配，大小写/前缀不敏感）。
     *
     * <p><b>WHY 用工具结果而不是 LLM 判断</b>（2026-11-02 用户裁定「来源从 websearch 和 webfetch
     * 能拿到啊，不需要了」）：这两个工具的**结果里天然带 url**（WebSearch 的 hits 是
     * {@code {title,url}}、WebFetch 的 outputSchema 有 {@code url} 字段）⇒
     * 直接读结果就是确定性来源，不需要再让模型读一遍会话去「猜」哪些链接值得记录。
     *
     * <p>⛔ 与「交付物」不同：来源**没有磁盘文件**可扫，只能来自工具结果。所以这条通道
     * 是来源功能的**唯一**确定性来源（LLM 抽取那条路随之可退）。
     */
    private static final String[] SOURCE_TOOL_KEYWORDS = {"websearch", "web_search", "webfetch", "web_fetch"};

    @Autowired private SessionArtifactMapper artifactMapper;

    /**
     * 消息表只读通道 · 用于把 {@code source_message_id} 还原成「那一轮问了什么」。
     *
     * <p><b>WHY {@code required = false}</b>：现有 12 个产物测试类都用
     * {@code new SessionArtifactService()} + {@code ReflectionTestUtils.setField(..., "artifactMapper", mock)}
     * 直构，本字段会是 null ⇒ 必须走「全部 turnQuestion=null」的降级分支，⛔ 绝不能 NPE。
     * 非 Spring 运行时（直构单测）也只是退化：产物照常出站，只是组头没有提问原文。
     */
    @Autowired(required = false) private com.nexusai.repository.session.mapper.MessageMapper messageMapper;

    /**
     * [产物实时推送 2026-11-02] STOMP 会话级 topic 的发送器。
     *
     * <p><b>WHY 需要</b>：抽取是**轮末异步**的，此前前端只能靠 15 秒轮询感知新产物 ⇒
     * 「Agent 写完 html 立刻在右侧看到」做不到（最坏延迟 15s）。
     * 推送后前端即时插入列表 → 侧栏随之自动展开 ⇒ 真正的「写完就出现」。
     *
     * <p>{@code @Autowired(required = false)} 字段注入（镜像 PartialCompactService 既有模式，
     * **不动构造器签名** —— 直构单测逐位不变）：非 STOMP 运行时为 null ⇒ 不推送，
     * 抽取照常完成，仅退化为轮询（与无 wsTemplate 时的其它链路同款降级）。
     */
    @Autowired(required = false) private org.springframework.messaging.simp.SimpMessagingTemplate wsTemplate;

    /** 测试注入 STOMP 模板（生产走 {@code @Autowired} 字段注入）。 */
    public void setWsTemplate(org.springframework.messaging.simp.SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
    }

    /** 产物 topic 后缀 · 与 compact-progress 同构：{@code /topic/sessions/{sid}/artifact}。 */
    public static final String TOPIC_PREFIX = "/topic/sessions/";
    public static final String TOPIC_SUFFIX = "/artifact";

    /** 会话消息读取（全量内部通道）· required=false 容错直接构造的单测。 */
    @Autowired(required = false) private MessageService messageService;

    /** 抽取开关（nexusai.artifacts.extract-enabled，默认 true）· 字段初值 true = 非 Spring 直构默认开。 */
    @Value("${nexusai.artifacts.extract-enabled:true}")
    private boolean extractEnabled = true;

    /**
     * 产物归档开关（nexusai.artifacts.archive-enabled，默认 true）。
     *
     * <p>false ⇒ 不做复制，deliverable 仍按原路径入库（旧语义，供磁盘紧张 / 排障时关掉）。
     * 字段初值 true = 非 Spring 直构（单测）默认开。
     */
    @Value("${nexusai.artifacts.archive-enabled:true}")
    private boolean archiveEnabled = true;

    /**
     * 抽取专用线程池 · 2 条 daemon 线程、命名可检索。
     * ⛔ 刻意不复用 chatExecutor：抽取是旁路任务，绝不能和对话争抢对话线程。
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "session-artifact-extract");
        t.setDaemon(true);
        return t;
    });

    /**
     * 轮内产物目录监看调度器 · 1 条 daemon 线程。
     *
     * <p><b>WHY 单独一条</b>：{@link #executor} 是抽取专用池；轮内扫描是轻量 `Files.list`，
     * 两者混用一个池时，长抽取会把扫描饿死（扫描要按 2s 节拍才有「实时」含义）。
     */
    private final java.util.concurrent.ScheduledExecutorService turnWatchScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-artifact-turn-watch");
            t.setDaemon(true);
            return t;
        });

    /** 轮内监看扫描节拍（毫秒）· 见 {@link #startTurnWatch}。 */
    private static final long TURN_WATCH_INTERVAL_MS = 2000L;

    /**
     * 轮内监看状态 · sessionId → (定时任务, 已见文件签名)。
     *
     * <p><b>为什么需要签名去重</b>：{@link #upsert} **无条件**推 STOMP（没有「未变更」分支），
     * 若每 2s 对目录里所有文件重跑一遍 {@link #saveDeliverable}，就会每 2s 重推一次
     * ⇒ 前端每次推送都把产物面板切回最新那一个（用户正在看 A，却被弹回 B）。
     * 故扫描时先比对 `size:mtime`，只有真正变过的文件才入库+推送。
     */
    private final Map<String, TurnWatch> turnWatches = new ConcurrentHashMap<>();

    /**
     * 轮内监看的句柄。
     *
     * @param future        定时任务
     * @param signatures    文件签名（size:mtime）
     * @param userMessageId <b>本轮归属</b> —— 扫描发现的产物一律挂到这条用户消息上
     */
    private record TurnWatch(java.util.concurrent.ScheduledFuture<?> future,
                             Map<String, String> signatures,
                             String userMessageId) {}

    /** 每会话上次触发时刻（防抖）· sessionId → epoch millis。 */
    private final Map<String, Long> lastRun = new ConcurrentHashMap<>();

    /** 在飞会话集合（即使超过防抖窗口，同一会话也不并发跑两份）。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 只读列表 · GET /api/v1/sessions/{sessionId}/artifacts。
     *
     * <p>⛔ 只读 DB，不触发抽取（抽取只由轮末触发；前端定时轮询不能变成抽取放大器）。
     *
     * @param sessionId 会话 id（空白 → 空列表）
     * @return {deliverables, sources}；两桶各自按 updated_at 倒序；未知 kind 的行忽略
     */
    public ArtifactBuckets list(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new ArtifactBuckets(List.of(), List.of());
        }
        List<SessionArtifactRecord> rows = artifactMapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("session_id", sessionId)
                        .orderBy("updated_at", false));   // desc（新→旧）
        return toBuckets(rows, loadTurnInfo(sessionId, sourceMessageIdsOf(rows)));
    }

    /**
     * 某一轮（用户消息）产出的产物/来源。
     *
     * <p><b>WHY 单独一个方法</b>（2026-11-05 用户裁定「展示的可访问产物是轮次的」）：
     * 会话级列表给右侧栏看总量，而对话流里每轮气泡下只该展示<b>本轮</b>产出的东西。
     * 二者数据源相同、口径不同，都从同一张表取，⛔ 不另存一份。
     *
     * <p>⚠️ {@code messageId} 为空 → 返回**归属未知**的那批（历史行），
     * 便于前端把它们归入「历史」分组而不是丢掉。
     *
     * @param sessionId 会话 id
     * @param messageId 用户消息 id（null/空白 = 归属未知）
     * @return 该轮的两个桶
     */
    public ArtifactBuckets listByMessage(String sessionId, String messageId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new ArtifactBuckets(List.of(), List.of());
        }
        QueryWrapper q = QueryWrapper.create()
                .eq("session_id", sessionId)
                .orderBy("updated_at", false);
        if (messageId == null || messageId.isBlank()) {
            q.and("source_message_id IS NULL");
        } else {
            q.eq("source_message_id", messageId);
        }
        List<SessionArtifactRecord> rows = artifactMapper.selectListByQuery(q);
        return toBuckets(rows, loadTurnInfo(sessionId, sourceMessageIdsOf(rows)));
    }

    /**
     * 从产物行里收集<b>非空</b>的 {@code source_message_id}（去重由 {@link #loadTurnInfo} 负责）。
     *
     * @param rows 产物行（null / 空 → 空集合）
     */
    private static java.util.Collection<String> sourceMessageIdsOf(List<SessionArtifactRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(rows.size());
        for (SessionArtifactRecord row : rows) {
            if (row != null && row.getSourceMessageId() != null && !row.getSourceMessageId().isBlank()) {
                ids.add(row.getSourceMessageId());
            }
        }
        return ids;
    }

    /**
     * 归属消息 id → 「那一轮问了什么」（**一次查询，⛔ 不 N+1**）。
     *
     * <p><b>WHY 要联查消息表</b>：右侧栏按对话分组的组头要显示提问原文，而前端聊天窗口只保留
     * 最近 2 轮（{@code FOLD_DEFAULT_TURNS=2}）⇒ 更早轮次的提问在前端查不到。产物行的
     * {@code source_message_id} 本身是准确的（孤儿键 0 条），所以真源就在消息表里。
     *
     * <p><b>「真人提问」五条判据</b>（任一不满足 ⇒ 该 id 不入 Map ⇒ 出站两字段为 null）：
     * <ol>
     *   <li>{@code role} == {@code "user"}；</li>
     *   <li>{@code is_meta} 不是 TRUE；</li>
     *   <li>{@code subtype} 为 null 或空白（{@code tool_use_summary} 之类是系统行）；</li>
     *   <li>锚点自洽：{@code user_message_id} 为 null <b>或</b> 等于自身 {@code id}
     *       （不等 ⇒ 这是属于别的 flow 的注入行，不是本轮提问）；</li>
     *   <li>{@code content} 压空白后非空。</li>
     * </ol>
     *
     * <p>⛔ 正文层的前缀判定（{@code <system-reminder>} / {@code <task-notification>} /
     * Stop hook 之类）由<b>前端既有判据</b>负责，这里不重复实现 —— 后端只负责
     * 「这条消息到底是不是真人提问」的结构性判定。
     *
     * <p>正文压单行：{@code content.replaceAll("\\s+", " ").trim()}；超过
     * {@link #TURN_QUESTION_MAX} 字取前 N 字加 {@code …}。
     *
     * @param sessionId  会话 id（查询必须带它，避免跨会话拿到同 id 的行）
     * @param messageIds 候选归属 id（null/空白被过滤、去重）
     * @return id → 提问简报；空集 / 未装配 mapper / 查询失败 → 空 Map（⛔ 不抛）
     */
    private Map<String, TurnBrief> loadTurnInfo(String sessionId, java.util.Collection<String> messageIds) {
        if (messageMapper == null || sessionId == null || sessionId.isBlank()
                || messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (String id : messageIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            List<com.nexusai.repository.session.entity.MessageRecord> rows = messageMapper.selectListByQuery(
                    QueryWrapper.create()
                            .select("id", "role", "content", "created_at", "is_meta", "subtype", "user_message_id")
                            .where("session_id = ?", sessionId)
                            .in("id", ids));
            if (rows == null || rows.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-turn] 归属消息全部查不到（孤儿键） session={} 请求={} 条",
                            sessionId, ids.size());
                }
                return Map.of();
            }
            Map<String, TurnBrief> out = new LinkedHashMap<>();
            for (com.nexusai.repository.session.entity.MessageRecord rec : rows) {
                if (rec == null || rec.getId() == null) {
                    continue;
                }
                // ① 只认真人提问：role / is_meta / subtype / 锚点自洽 / 正文非空
                if (!"user".equals(rec.getRole())) {
                    continue;
                }
                if (Boolean.TRUE.equals(rec.getIsMeta())) {
                    continue;
                }
                if (rec.getSubtype() != null && !rec.getSubtype().isBlank()) {
                    continue;
                }
                String anchor = rec.getUserMessageId();
                if (anchor != null && !anchor.isBlank() && !anchor.equals(rec.getId())) {
                    continue;
                }
                String question = oneLine(rec.getContent());
                if (question == null) {
                    continue;
                }
                out.put(rec.getId(), new TurnBrief(question, rec.getCreatedAt()));
            }
            if (log.isDebugEnabled()) {
                log.debug("[artifact-turn] 归属提问解析 session={} 请求={} 条 取到真人提问={} 条",
                        sessionId, ids.size(), out.size());
            }
            return out;
        } catch (Exception e) {
            // 旁路增强：查不到就降级成 null（⛔ 不能让消息表异常把产物列表打成 500）
            log.warn("[artifact-turn] 归属提问查询失败（降级为 null） session={} 请求={} 条: {}",
                    sessionId, ids.size(), e.toString());
            return Map.of();
        }
    }

    /** 提问正文压成单行 + 截断（空 → null）。 */
    private static String oneLine(String content) {
        if (content == null) {
            return null;
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return null;
        }
        return flat.length() <= TURN_QUESTION_MAX ? flat : flat.substring(0, TURN_QUESTION_MAX) + "…";
    }

    /**
     * 一轮提问的简报。
     *
     * @param question  提问原文（已压单行 + 截断到 {@link #TURN_QUESTION_MAX}）
     * @param createdAt 该消息的 {@code created_at} 原始串（DB 直出）
     */
    private record TurnBrief(String question, String createdAt) {}

    /** 行 → 两桶（未知 kind 忽略并留调试日志，存量脏行不抛错）。 */
    private ArtifactBuckets toBuckets(List<SessionArtifactRecord> rows, Map<String, TurnBrief> turns) {
        List<SessionArtifactDto> deliverables = new ArrayList<>();
        List<SessionArtifactDto> sources = new ArrayList<>();
        if (rows != null) {
            for (SessionArtifactRecord row : rows) {
                String owner = row.getSourceMessageId();
                TurnBrief turn = (turns == null || owner == null) ? null : turns.get(owner);
                String question = turn == null ? null : turn.question();
                String createdAt = turn == null ? null : turn.createdAt();
                if (KIND_DELIVERABLE.equals(row.getKind())) {
                    deliverables.add(SessionArtifactDto.from(row, isDirectoryArtifact(row.getLocalPath()),
                            question, createdAt));
                } else if (KIND_SOURCE.equals(row.getKind())) {
                    sources.add(SessionArtifactDto.from(row, isDirectoryArtifact(row.getLocalPath()),
                            question, createdAt));
                } else if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 忽略未知 kind={} id={}（存量脏行不抛错）",
                            row.getKind(), row.getId());
                }
            }
        }
        return new ArtifactBuckets(List.copyOf(deliverables), List.copyOf(sources));
    }

    /** {@link #list(String)} 的分桶结果（Controller 直接序列化出站）。 */
    public record ArtifactBuckets(List<SessionArtifactDto> deliverables, List<SessionArtifactDto> sources) {}

    // ───────────────────────── 产物文件通道（预览 / 原始字节 / 编辑） ─────────────────────────

    /**
     * 产物内容预览出站形状（Controller 直接序列化）。
     *
     * @param content   文本内容；{@code binary=true} 时恒 null
     * @param mime      按扩展名推断的 MIME
     * @param size      文件<b>实际</b>字节数（截断时仍是完整大小，前端据 {@code truncated} 提示）
     * @param truncated 是否因超过 {@link #CONTENT_MAX_BYTES} 被截断
     * @param binary    二进制/未知类型（true ⇒ 不下发内容，⛔ 不返回 base64：会把响应体打爆）
     */
    public record ArtifactContent(String content, String mime, long size, boolean truncated, boolean binary) {}

    /**
     * 读取一条产物的内容 · GET /api/v1/sessions/{sessionId}/artifacts/{artifactId}/content。
     *
     * <p><b>WHY 需要独立端点</b>：产物的 {@code local_path} 是<b>归档副本</b>的绝对路径
     * （{@code {configHome}/artifacts/{sessionId}/...}，见 {@link #archiveFile}），它不属于任何项目根
     * ⇒ 浏览器端走项目文件通道（{@code /projects/{id}/file?path=}）必然被判「文件路径非法」。
     * 产物只能走本通道。
     *
     * <p><b>安全边界</b>（两道）：
     * <ol>
     *   <li><b>归属</b>：按 artifactId 查库后比对 {@code session_id}，不属于该会话一律 404
     *       （不暴露「存在但属于别人」）；</li>
     *   <li><b>路径</b>：解析后的绝对路径必须落在 {@code {configHome}/artifacts/{sessionId}} 内
     *       （normalize 后 {@code startsWith}，再叠一层 {@code toRealPath} 拦符号链接越界），否则 400。</li>
     * </ol>
     *
     * <p>⛔ 只读：不写库、不改文件。
     *
     * @param sessionId  会话 id（sess-xxx）
     * @param artifactId 产物行 id（art-xxx）
     * @return 内容 + mime + 大小 + 截断/二进制标志
     * @throws NotFoundException   产物行不存在 / 不属于该会话 / 文件已不存在
     * @throws ValidationException 路径非法（越出本会话归档目录）
     */
    public ArtifactContent readContent(String sessionId, String artifactId) {
        SessionArtifactRecord row = requireOwnedArtifact(sessionId, artifactId, "artifact-content");
        return readArtifactFile(sessionId, artifactId, row.getLocalPath());
    }

    /**
     * 读产物文件本体（包级可见，供纯单测直调，无需 Spring / 数据库）。
     *
     * <p>路径校验 + 编码回落 + 截断的全部规则都在这里，见 {@link #readContent} 的「安全边界」。
     *
     * @param sessionId  会话 id（决定允许的目录）
     * @param artifactId 产物 id（只用于日志）
     * @param localPath  DB 里的 {@code local_path}（绝对路径）
     * @return {@link ArtifactContent}
     */
    static ArtifactContent readArtifactFile(String sessionId, String artifactId, String localPath) {
        // ⛔ 必须先判「是不是目录」：目录**不会被归档**（archiveFile 只复制普通文件）
        // ⇒ 它的路径必然在会话归档目录之外 ⇒ 不先判就会先撞上「产物路径非法」，
        //    而真相是「它是目录」⇒ 错误信息必须诚实。
        if (isDirectoryArtifact(localPath)) {
            log.warn("[artifact-file] 该产物是目录，无法预览 session={} artifactId={} path={}",
                    sessionId, artifactId, localPath);
            throw new ValidationException("该产物是目录，无法预览");
        }
        Path resolved = resolveArtifactPath(sessionId, artifactId, localPath);

        String fileName = resolved.getFileName() == null ? "" : resolved.getFileName().toString();
        String ext = ArtifactFileKind.extensionOf(fileName);
        String mime = ArtifactFileKind.mimeOf(ext);
        long size;
        try {
            size = Files.size(resolved);
        } catch (IOException e) {
            log.warn("[artifact-content] 产物大小读取失败 session={} artifactId={} path={} err={}",
                    sessionId, artifactId, resolved, e.toString());
            throw new NotFoundException("产物文件不存在（可能已被删除）");
        }
        if (!ArtifactFileKind.isText(ext)) {
            log.info("[artifact-content] 读取产物内容完成（二进制不预览） session={} artifactId={} 字节数={} 类型={}",
                    sessionId, artifactId, size, mime);
            return new ArtifactContent(null, mime, size, false, true);
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(resolved)) {
            bytes = in.readNBytes(CONTENT_MAX_BYTES);
        } catch (IOException e) {
            log.warn("[artifact-content] 产物读取失败 session={} artifactId={} path={} err={}",
                    sessionId, artifactId, resolved, e.toString());
            throw new NotFoundException("产物文件不存在（可能已被删除）");
        }
        boolean truncated = size > CONTENT_MAX_BYTES;
        String content = decodeText(bytes, !truncated);
        log.info("[artifact-content] 读取产物内容成功 session={} artifactId={} 字节数={} 截断={} 类型={}",
                sessionId, artifactId, size, truncated, mime);
        return new ArtifactContent(content, mime, size, truncated, false);
    }

    /**
     * 取「属于该会话」的一条产物行 —— 归属校验的唯一入口（三个产物端点共用）。
     *
     * <p><b>WHY 必须比对 {@code session_id}</b>：{@code artifactId} 是全局唯一串，只按 id 查到行
     * 并不够 —— 无鉴权形态下，A 会话拿着 B 会话的 artifactId 就能读到 B 的产物文件。
     * 不属于该会话一律 404（不暴露「存在但属于别人」）。
     *
     * @param logTag 日志前缀（{@code artifact-content} / {@code artifact-raw} / {@code artifact-edit}）
     * @return 该会话名下的产物行（一定有 {@code local_path}）
     * @throws NotFoundException 产物行不存在 / 不属于该会话 / 无本地路径
     */
    private SessionArtifactRecord requireOwnedArtifact(String sessionId, String artifactId, String logTag) {
        if (sessionId == null || sessionId.isBlank() || artifactId == null || artifactId.isBlank()) {
            throw new NotFoundException("产物不存在");
        }
        if (artifactMapper == null) {
            log.warn("[{}] SessionArtifactMapper 未装配，无法读取产物 session={} artifactId={}",
                    logTag, sessionId, artifactId);
            throw new NotFoundException("产物不存在");
        }
        SessionArtifactRecord row = artifactMapper.selectOneByQuery(
                QueryWrapper.create().eq("id", artifactId));
        if (row == null || !sessionId.equals(row.getSessionId())) {
            log.warn("[{}] 产物不存在或不属于该会话 session={} artifactId={}", logTag, sessionId, artifactId);
            throw new NotFoundException("产物不存在");
        }
        String localPath = row.getLocalPath();
        if (localPath == null || localPath.isBlank()) {
            // 来源类条目（local_path 恒 null）；或产物行从未归档成功
            log.warn("[{}] 产物无本地路径 session={} artifactId={} kind={}",
                    logTag, sessionId, artifactId, row.getKind());
            throw new NotFoundException("产物文件不存在（可能已被删除）");
        }
        return row;
    }

    /**
     * 产物 {@code local_path} 是否指向<b>目录</b>（**读时判定 · 不落库**）。
     *
     * <p><b>WHY 要单独判</b>：目录**不会被归档**（{@link #archiveFile} 只复制普通文件）
     * ⇒ 它的路径必然落在 {@code {configHome}/artifacts/{sessionId}} 之外
     * ⇒ 直接走路径校验只会得到「产物路径非法」，而真相是「它是目录」⇒ 三个产物端点
     * 必须先判这一条，才能给出诚实的错误信息（前端也要靠它画文件夹图标）。
     *
     * @param localPath 产物本地路径（空 → false；路径无法解析 → false，⛔ 吞异常时不当成 true）
     */
    static boolean isDirectoryArtifact(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return false;
        }
        try {
            return Files.isDirectory(Paths.get(localPath));
        } catch (InvalidPathException e) {
            if (log.isDebugEnabled()) {
                log.debug("[artifact-file] 产物路径无法解析（不按目录处理） path={}", localPath);
            }
            return false;
        }
    }

    /**
     * 产物路径校验（安全边界 · 三个产物端点共用同一口径，⛔ 不各写一份）。
     *
     * <p>两道边界：
     * <ol>
     *   <li><b>文本路径</b>：{@code toAbsolutePath().normalize()} 后必须落在
     *       {@code {configHome}/artifacts/{sessionId}} 内，否则 400；</li>
     *   <li><b>符号链接</b>：再叠一层 {@code toRealPath()} —— 目录内若被塞进指向目录外的链接，
     *       文本路径的 {@code startsWith} 拦不住，必须按解析后的真实路径再判一次，否则 400。</li>
     * </ol>
     *
     * @return 校验通过的绝对路径
     * @throws ValidationException 路径非法（越出本会话归档目录 / 符号链接越界）
     * @throws NotFoundException   文件不存在 / 已删除
     */
    static Path resolveArtifactPath(String sessionId, String artifactId, String localPath) {
        Path sessionDir = sessionArtifactsDir(sessionId);
        Path resolved;
        try {
            resolved = Paths.get(localPath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("[artifact-file] 产物路径非法（无法解析） session={} artifactId={} path={}",
                    sessionId, artifactId, localPath);
            throw new ValidationException("产物路径非法");
        }
        // ⛔ 这一行就是安全边界：产物只允许读本会话归档目录内的文件
        if (!resolved.startsWith(sessionDir)) {
            log.warn("[artifact-file] 拒绝访问：产物路径不在本会话归档目录内 session={} artifactId={} path={} dir={}",
                    sessionId, artifactId, resolved, sessionDir);
            throw new ValidationException("产物路径非法");
        }
        if (!Files.isRegularFile(resolved)) {
            log.warn("[artifact-file] 产物文件不存在 session={} artifactId={} path={}",
                    sessionId, artifactId, resolved);
            throw new NotFoundException("产物文件不存在（可能已被删除）");
        }
        // 符号链接防越界：目录内若被塞进指向目录外的链接，上面的文本路径 startsWith 拦不住
        try {
            if (!resolved.toRealPath().startsWith(sessionDir.toRealPath())) {
                log.warn("[artifact-file] 拒绝访问：产物符号链接指向归档目录之外 session={} artifactId={} path={}",
                        sessionId, artifactId, resolved);
                throw new ValidationException("产物路径非法");
            }
        } catch (IOException e) {
            log.warn("[artifact-file] 产物路径解析失败 session={} artifactId={} path={} err={}",
                    sessionId, artifactId, resolved, e.toString());
            throw new NotFoundException("产物文件不存在（可能已被删除）");
        }
        return resolved;
    }

    /**
     * 产物原始字节出站（Controller 直接序列化）。
     *
     * @param bytes      文件原始字节
     * @param fileName   展示用文件名（DB 行 title；空则用副本 basename）
     * @param contentType HTTP Content-Type（{@link ArtifactFileKind#contentTypeOf(String)}）
     * @param size       文件字节数
     */
    public record ArtifactRaw(byte[] bytes, String fileName, String contentType, long size) {}

    /**
     * 读取产物原始字节 · GET /api/v1/sessions/{sessionId}/artifacts/{artifactId}/raw。
     *
     * <p><b>WHY 需要它</b>：{@code /content} 只下发文本（二进制只回 {@code binary=true}）⇒
     * docx / pdf / xlsx / 图片在浏览器里根本打不开（用户报「异常」）。本端点下发原始字节，
     * 浏览器按 {@code Content-Type} 直接渲染 / 下载。
     *
     * <p><b>安全边界与 {@code /content} 完全同款</b>（归属 404 / 目录越界 400 / 符号链接 400），
     * ⛔ 只读：不写库、不改文件。
     *
     * @throws NotFoundException   产物行不存在 / 不属于该会话 / 文件已不存在
     * @throws ValidationException 路径非法 / 超过 {@link #RAW_MAX_BYTES}
     */
    public ArtifactRaw readRaw(String sessionId, String artifactId) {
        SessionArtifactRecord row = requireOwnedArtifact(sessionId, artifactId, "artifact-raw");
        // ⛔ 先判「是不是目录」：目录没被归档 ⇒ 路径必然在归档目录外 ⇒ 不先判只会得到
        //    误导性的「产物路径非法」。
        if (isDirectoryArtifact(row.getLocalPath())) {
            log.warn("[artifact-raw] 该产物是目录，无法预览 session={} artifactId={} path={}",
                    sessionId, artifactId, row.getLocalPath());
            throw new ValidationException("该产物是目录，无法预览");
        }
        Path resolved = resolveArtifactPath(sessionId, artifactId, row.getLocalPath());
        long size;
        try {
            size = Files.size(resolved);
        } catch (IOException e) {
            log.warn("[artifact-raw] 产物大小读取失败 session={} artifactId={} path={} err={}",
                    sessionId, artifactId, resolved, e.toString());
            throw new NotFoundException("产物文件不存在（可能已被删除）");
        }
        if (size > RAW_MAX_BYTES) {
            log.warn("[artifact-raw] 产物过大，拒绝下发 session={} artifactId={} size={} 上限字节={}",
                    sessionId, artifactId, size, RAW_MAX_BYTES);
            throw new ValidationException("产物超过 " + (RAW_MAX_BYTES / 1024 / 1024) + "MB 上限，无法预览");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(resolved);
        } catch (IOException e) {
            log.warn("[artifact-raw] 产物读取失败 session={} artifactId={} path={} err={}",
                    sessionId, artifactId, resolved, e.toString());
            throw new NotFoundException("产物文件不存在（可能已被删除）");
        }
        // MIME 按「文件真实扩展名」判定（不是 title —— title 是展示名，可能压根没有扩展名）
        String ext = ArtifactFileKind.extensionOf(resolved.getFileName() == null
                ? "" : resolved.getFileName().toString());
        String contentType = ArtifactFileKind.contentTypeOf(ext);
        String fileName = displayFileName(row, resolved);
        log.info("[artifact-raw] 下发产物原始字节 session={} artifactId={} 字节数={} 类型={} 文件名={}",
                sessionId, artifactId, bytes.length, contentType, fileName);
        return new ArtifactRaw(bytes, fileName, contentType, bytes.length);
    }

    /**
     * 展示用文件名：DB 行 {@code title}（空则用副本 basename）。
     *
     * <p>WHY 用 title：它是模型抽取时给的「用户视角的名字」（如「季度报告.docx」），
     * 比 {@code {hash12}-{basename}} 的副本名可读。⛔ 头里做 percent 编码（RFC 5987），
     * 控制字符 / 换行被编码掉 ⇒ 不会有头注入。
     */
    private static String displayFileName(SessionArtifactRecord row, Path resolved) {
        String title = row.getTitle();
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        Path fileName = resolved.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    /**
     * 文本编辑写回 · PUT /api/v1/sessions/{sessionId}/artifacts/{artifactId}/content。
     *
     * <p><b>WHY 只能改文本类</b>：{@code docx / xlsx / pdf / 图片} 是二进制，写进去的字符串
     * 会把文件当场写坏（用户以为保存成功，其实交付物已损坏）⇒ 二进制一律 400。
     * 判据与 {@code /content}、{@code /raw} 共用 {@link ArtifactFileKind#isText(String)}（单一真源）。
     *
     * <p><b>原子写</b>：先写同目录临时文件，再 {@code Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)}
     * 覆盖 —— 写一半失败不会留下半截文件（用户读到的要么是旧内容要么是新内容）。
     *
     * <p>⛔ 只写这一个文件 + 更新这一行的 {@code size_bytes} / {@code updated_at}，不碰别的产物。
     *
     * @param content 新内容（UTF-8 文本；空串合法 = 清空文件；null = 参数缺失 ⇒ 400）
     * @return {id, sizeBytes, updatedAt}
     * @throws NotFoundException   产物行不存在 / 不属于该会话 / 文件已不存在
     * @throws ValidationException 路径非法 / 非文本类 / 内容过大
     */
    public ArtifactContentUpdateResponse writeTextContent(String sessionId, String artifactId, String content) {
        SessionArtifactRecord row = requireOwnedArtifact(sessionId, artifactId, "artifact-edit");
        // ⛔ 先判「是不是目录」：目录没被归档 ⇒ 路径必然在归档目录外 ⇒ 不先判只会得到
        //    误导性的「产物路径非法」。
        if (isDirectoryArtifact(row.getLocalPath())) {
            log.warn("[artifact-edit] 该产物是目录，无法编辑 session={} artifactId={} path={}",
                    sessionId, artifactId, row.getLocalPath());
            throw new ValidationException("该产物是目录，无法编辑");
        }
        if (content == null) {
            throw new ValidationException("产物内容不能为空（content 必填）");
        }
        Path resolved = resolveArtifactPath(sessionId, artifactId, row.getLocalPath());
        String fileName = resolved.getFileName() == null ? "" : resolved.getFileName().toString();
        String ext = ArtifactFileKind.extensionOf(fileName);
        if (!ArtifactFileKind.isText(ext)) {
            log.warn("[artifact-edit] 非文本产物，拒绝写回 session={} artifactId={} 扩展名={}",
                    sessionId, artifactId, ext);
            throw new ValidationException("该产物类型不支持文本编辑");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > RAW_MAX_BYTES) {
            log.warn("[artifact-edit] 内容过大，拒绝写回 session={} artifactId={} 字节数={} 上限字节={}",
                    sessionId, artifactId, bytes.length, RAW_MAX_BYTES);
            throw new ValidationException("内容超过 " + (RAW_MAX_BYTES / 1024 / 1024) + "MB 上限，无法保存");
        }
        writeAtomically(resolved, bytes);
        long newSize = bytes.length;
        String now = OffsetDateTime.now().toString();
        row.setSizeBytes(newSize);
        row.setUpdatedAt(now);
        // ignoreNulls=true：只更新非空列（size_bytes / updated_at），url / source_message_id 等
        // 本来就是 null 的列保持 null（MyBatis-Flex 默认忽略 null 字段，语义正好对上）。
        artifactMapper.update(row, true);
        log.info("[artifact-edit] 写回产物内容成功 session={} artifactId={} 字节数={} 路径={}",
                sessionId, artifactId, newSize, resolved);
        return new ArtifactContentUpdateResponse(row.getId(), newSize, now);
    }

    /**
     * 原子写（同目录临时文件 → 原子改名覆盖）。
     *
     * <p><b>WHY 临时文件放同目录</b>：原子改名要求源与目标在<b>同一文件系统</b> ⇒ 临时文件必须落在
     * 目标所在目录（写系统临时目录再 move 会退化成「复制 + 删除」，中途崩溃就留半截文件）。
     *
     * <p>失败路径：临时文件一律在 finally 里清掉 ⇔ 不留半截文件；目标文件要么是旧内容、要么是新内容。
     */
    private static void writeAtomically(Path target, byte[] bytes) {
        Path dir = target.getParent();
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, ".artifact-edit-", ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                tmp = null;
            } catch (AtomicMoveNotSupportedException e) {
                // 极少数文件系统不支持原子改名：同目录 rename 退化为普通 move（仍不会留半截文件）
                log.warn("[artifact-edit] 当前文件系统不支持原子改名，退化为普通移动 path={} err={}",
                        target, e.toString());
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                tmp = null;
            }
        } catch (IOException e) {
            log.warn("[artifact-edit] 产物写入失败 path={} err={}", target, e.toString());
            throw new IllegalStateException("产物写入失败：" + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.warn("[artifact-edit] 清理临时文件失败（忽略）path={} err={}", tmp, e.toString());
                }
            }
        }
    }

    /**
     * 字节 → 文本：严格 UTF-8，失败回落 GB18030（国内用户 Windows 产物常见 GBK 编码），
     * 再失败用 ISO-8859-1 兜底（⛔ 宁可显示乱码也不因编码失败丢掉内容）。
     *
     * @param bytes    文件前 {@link #CONTENT_MAX_BYTES} 字节
     * @param complete 是否已读到文件尾（false = 尾部可能被截断在半个多字节字符上，此时容忍尾部不完整）
     */
    private static String decodeText(byte[] bytes, boolean complete) {
        try {
            return decodeStrict(bytes, StandardCharsets.UTF_8, complete);
        } catch (CharacterCodingException e) {
            if (log.isDebugEnabled()) {
                log.debug("[artifact-content] UTF-8 解码失败，回落 GB18030 字节数={}", bytes.length);
            }
        }
        try {
            return decodeStrict(bytes, Charset.forName("GB18030"), complete);
        } catch (CharacterCodingException e) {
            log.warn("[artifact-content] GB18030 解码也失败，按 ISO-8859-1 输出（内容可能含乱码）字节数={}", bytes.length);
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** 严格解码（畸形字节报错而不是替换成 U+FFFD）。 */
    private static String decodeStrict(byte[] bytes, Charset charset, boolean complete)
            throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer out = CharBuffer.allocate(Math.max(16, bytes.length * 2));
        CoderResult result = decoder.decode(ByteBuffer.wrap(bytes), out, complete);
        if (result.isError()) {
            result.throwException();
        }
        if (complete) {
            CoderResult flushed = decoder.flush(out);
            if (flushed.isError()) {
                flushed.throwException();
            }
        }
        out.flip();
        return out.toString();
    }

    /**
     * 异步触发抽取（fire-and-forget）· ChatService 轮末调用。
     *
     * <p><b>防抖</b>：同一会话 10 秒内重复触发只跑一次（长会话每轮都触发是常态，不防抖会连轴烧模型）。
     * <b>并发保护</b>：同一会话同时最多一份在飞（防抖窗口过了但上一次还没跑完的情况）。
     *
     * <p>本方法<b>永不抛出</b>：任何异常（含线程池拒绝）只 WARN。
     */
    public void scheduleExtract(String sessionId, String userMessageId) {
        try {
            if (!extractEnabled) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 开关关闭（nexusai.artifacts.extract-enabled=false），跳过 session={}",
                            sessionId);
                }
                return;
            }
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            long now = System.currentTimeMillis();
            Long previous = lastRun.put(sessionId, now);
            if (previous != null && now - previous < DEBOUNCE_MS) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 防抖合并（距上次 {}ms < {}ms）session={}",
                            now - previous, DEBOUNCE_MS, sessionId);
                }
                return;
            }
            pruneDebounceIfNeeded(now);
            executor.submit(() -> {
                if (!inFlight.add(sessionId)) {
                    if (log.isDebugEnabled()) {
                        log.debug("[artifact-extract] 上次抽取仍在跑，跳过本轮 session={}", sessionId);
                    }
                    return;
                }
                try {
                    extractNow(sessionId, userMessageId);
                } catch (Throwable t) {
                    // 双保险：extractNow 内部已兜底，这里防「兜底本身出错 / OOM 之类」漏出去
                    log.warn("[artifact-extract] 异步抽取异常（不影响对话） session={}: {}", sessionId, t.toString());
                } finally {
                    inFlight.remove(sessionId);
                }
            });
        } catch (Throwable t) {
            log.warn("[artifact-extract] 触发异步抽取失败（不影响对话） session={}: {}", sessionId, t.toString());
        }
    }

    /**
     * 同步执行一次抽取（测试 / 手动补跑用；生产只经 {@link #scheduleExtract(String, String)}）。
     *
     * <p>⛔ 同样<b>不抛异常</b>：失败只 WARN —— 让调用方（测试之外）永远不可能因产物抽取影响对话。
     *
     * @param sessionId 会话 id
     */
    public void extractNow(String sessionId, String userMessageId) {
        long startedAt = System.currentTimeMillis();
        try {
            if (!extractEnabled || sessionId == null || sessionId.isBlank()) {
                return;
            }
            // ══════════ 确定性通道（不花模型钱，永远开）══════════
            // [2026-11-02 做法 A] 交付物：扫 Agent 按提示词写入的固定目录。
            //   ⚠️ 不受 llmExtractEnabled 影响 —— 这是纯文件系统操作，与「要不要调模型」无关。
            try {
                syncDeliverablesFromDir(sessionId, userMessageId);
            } catch (Throwable t) {
                log.warn("[artifact-extract] 交付物目录扫描异常 session={}: {}", sessionId, t.toString());
            }
            List<ChatMessageDto> messages = readNonMetaMessages(sessionId);
            // [2026-11-02] 来源**兜底扫描**：工具已在抓取/搜索成功时当场入库
            //   （[2026-11-04 起] 由 ArtifactCollectionHooks 这个 PostToolUse hook 采集）。
            //   这里再扫一遍是为了
            //   补「升级前产生的历史工具结果」与「工具注入缺失（单测直构）时漏掉的」——
            //   幂等（按 title upsert）⇒ 正常路径下这里 0 新增，不会重复。
            try {
                syncSourcesFromToolResults(sessionId, messages, userMessageId);
            } catch (Throwable t) {
                log.warn("[artifact-extract] 工具结果来源兜底收集异常 session={}: {}", sessionId, t.toString());
            }
            // ══════════ LLM 通道已删除（2026-11-05 用户裁定）══════════
            // WHY：产物/来源的两条确定性通道（目录扫描 + 工具结果）已覆盖全部正常路径，
            //   LLM 抽取是「兜底中的兜底」，代价是一次额外的模型调用（慢 + 花钱），
            //   且它读的是**整段会话**而不是当前轮 ⇒ 会把旧产物重复归属到新轮次。
            //   ⇒ 整段删除（含 SYSTEM_PROMPT / buildExtractionInput / parse）。
            //   ⛔ 不要再加回来：产物归属由「提示词约定固定目录 + 轮次扫描 + 工具 hook」保证。
        } catch (Throwable t) {
            log.warn("[artifact-extract] 抽取失败（不影响对话） session={}: {}", sessionId, t.toString());
        }
    }

    // ───────────────────────── 读取与输入拼装 ─────────────────────────

    /**
     * 读会话全部<b>非 meta</b> 消息（与 {@code GET /messages/count} 同口径：{@code is_meta} 非 1，null 计入）。
     *
     * <p>走 {@link MessageService#listRawForTranscript(String)}：它是仓库里「后端内部全量读」的
     * 唯一通道（DTO 自带 toolCalls，seq ASC 稳定位置序）。代价是该通道按消息逐条查 tool_calls，
     * 长会话会有 N 次小查询 —— 抽取在 daemon 线程异步跑，不影响对话，故优先保证语义一致。
     */
    private List<ChatMessageDto> readNonMetaMessages(String sessionId) {
        if (messageService == null) {
            log.warn("[artifact-extract] MessageService 未装配，跳过抽取 session={}", sessionId);
            return List.of();
        }
        List<ChatMessageDto> all = messageService.listRawForTranscript(sessionId);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<ChatMessageDto> out = new ArrayList<>(all.size());
        for (ChatMessageDto m : all) {
            if (m.isMeta()) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    // ───────────────────────── 清洗 + UPSERT ─────────────────────────

    /**
     * 产物清洗 + 入库。
     *
     * <p>丢弃口径（前端不可用的条目一律不入库，避免死链）：
     * <ol>
     *   <li>无 path（没有路径的「产物」前端无法预览）</li>
     *   <li>相对路径但会话工作目录解析不出（无法定位文件）</li>
     *   <li>路径非法 / 文件不存在（⛔ 不展示点不开的死链）</li>
     * </ol>
     *
     * @return true = 已入库；false = 被丢弃
     */
    /**
     * 源码 / 构建产物扩展名黑名单（**确定性**判定，不依赖模型）。
     *
     * <p><b>WHY 需要这一层</b>：抽取提示词里已经写明「⛔ 排除源码改动」，
     * 但模型偶尔仍把 `Foo.java`、`bar.tsx` 当作「交付物」报上来（概率问题，不是能力问题）。
     * 规则五的边界正是这里：**「什么算交付物」交模型判断（提示词），「这个扩展名绝对不是」交代码**。
     *
     * <p>判据取**归档副本的真实文件名**（{@code localPath}）而非 title —— 标题经常没有扩展名。
     */
    private static final java.util.Set<String> SOURCE_CODE_EXTENSIONS = java.util.Set.of(
            // JVM / 后端
            "java", "kt", "kts", "scala", "groovy", "class", "jar", "war",
            // 前端 / JS 生态
            "ts", "tsx", "js", "jsx", "mjs", "cjs", "vue", "svelte", "css", "scss", "sass", "less",
            // 脚本 / 其它语言（⚠️ py 曾漏 —— 单测 SessionArtifactSourceCodeFilterTest 抓到）
            "py", "pyw", "pyi", "ipynb", "rb", "php", "pl", "pm", "lua", "r", "jl", "dart",
            "hs", "ml", "ex", "exs", "erl", "clj", "nim", "zig", "f90",
            // 系统 / 脚本 / 编译
            "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "swift", "m", "mm",
            "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "asm", "s",
            // 声明 / 协议 / 基础设施
            "tf", "proto", "graphql", "gql", "tfvars", "hcl", "cmake", "mk", "gradle", "bzl",
            // 构建 / 依赖锁（⚠️ json/yaml/xml **不在此列** —— 配置与接口契约本身常是交付物）
            // 测试 / 构建中间产物
            "log", "tmp", "cache", "map", "d", "o", "a", "so", "dylib", "dll", "exe"
    );

    /** 该文件是否是源码 / 构建中间产物（是 ⇒ 不能当交付物）。 */
    private static boolean isSourceCodeFile(java.nio.file.Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return false;   // 无扩展名（目录 / 纯文本）→ 不算源码，交给后续逻辑判
        }
        return SOURCE_CODE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private boolean saveDeliverable(String sessionId, SessionArtifactParser.ParsedArtifact item,
                                    String cwd, String now, String sourceMessageId) {
        String title = clip(item.title(), TITLE_MAX);
        if (title == null) {
            return false;
        }
        String rawPath = item.path();
        if (rawPath == null || rawPath.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] 丢弃无路径产物 session={} title={}", sessionId, title);
            }
            return false;
        }
        Path resolved = resolveFile(rawPath, cwd);
        if (resolved == null) {
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] 丢弃无法解析的产物路径 session={} title={} path={}",
                        sessionId, title, rawPath);
            }
            return false;
        }
        // [2026-11-02 用户实测「代码之类的不能当做产物」] **确定性过滤**：提示词已明确要求排除源码，
        //   但模型仍会偶发把 .java / .ts 改动当成交付物报上来（提示词是判断层，不能保证 100%）。
        //   这里按扩展名再拦一道 —— 规则五：确定性转换交给代码，不指望模型每次都听话。
        if (isSourceCodeFile(resolved)) {
            log.info("[artifact-extract] 丢弃源码文件（不是交付物） session={} title={} path={}",
                    sessionId, title, resolved);
            return false;
        }
        if (!Files.exists(resolved)) {
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] 丢弃不存在的产物文件（防死链） session={} title={} path={}",
                        sessionId, title, resolved);
            }
            return false;
        }
        Long sizeBytes = null;
        if (Files.isRegularFile(resolved)) {
            try {
                sizeBytes = Files.size(resolved);
            } catch (Exception e) {
                // 不可读（权限/被占用）→ 大小未知，但路径仍然可点，保留该条
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 产物大小读取失败，size=null session={} path={} err={}",
                            sessionId, resolved, e.toString());
                }
            }
        }
        // ── 归档：先把文件复制进本应用自有目录，再写库 ────────────────────────
        // WHY：只存原路径时，用户一移走/删掉原文件，产物就永久丢失（2026-10-29 用户指出）。
        // 复制成功 ⇒ localPath = 副本（不依赖用户目录）；失败/超限/目录/符号链接 ⇒ 回落原路径
        // （条目仍在列表上，只是仍旧语义）。⛔ 归档失败绝不能让整条抽取失败。
        String localPath = resolved.toString();
        // [2026-11-02 交付物目录] 文件**已经在自有产物目录内**（Agent 按提示词直接写进 deliverables/）
        // ⇒ 跳过归档复制。否则会把自己复制成 {hash}-xxx.docx，与原件并列出现两个条目（自我复制）。
        boolean alreadyInArtifactStore = resolved.startsWith(artifactsRoot());
        if (archiveEnabled && !alreadyInArtifactStore) {
            Path archived = archiveFile(resolved, sessionId, ARCHIVE_MAX_MB);
            if (archived != null) {
                localPath = archived.toString();
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[artifact-extract] 归档开关关闭（nexusai.artifacts.archive-enabled=false），只记录原路径 session={} src={}",
                    sessionId, resolved);
        }
        upsert(sessionId, KIND_DELIVERABLE, title, localPath, null, sizeBytes,
                clip(item.summary(), SUMMARY_MAX), now, sourceMessageId, ORIGIN_DELIVERABLES);
        return true;
    }

    /** 来源清洗 + 入库：url 为空整条丢弃（展示形态只支持链接）。 */
    private boolean saveSource(String sessionId, SessionArtifactParser.ParsedArtifact item, String now,
                               String sourceMessageId) {
        String title = clip(item.title(), TITLE_MAX);
        if (title == null) {
            return false;
        }
        String url = clip(item.url(), URL_MAX);
        if (url == null) {
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] 丢弃无链接来源 session={} title={}", sessionId, title);
            }
            return false;
        }
        upsert(sessionId, KIND_SOURCE, title, null, url, null,
                clip(item.summary(), SUMMARY_MAX), now, sourceMessageId);
        return true;
    }

    /**
     * 幂等写入：按 (session_id, kind, title) 查已有行 → 更新；否则插入。
     *
     * <p>「同一交付物多版本只留最终版」由唯一索引 uk_session_artifact + 这里先查后写共同保证；
     * 两次并发抽取撞同一个键时，唯一索引会让后写者报错 → 捕获后 WARN（下一轮会覆盖成最终值）。
     */
    /**
     * 该行是否「自上次记录以来没变过」（**产物没变 ⇒ 不刷时间戳、不重推**）。
     *
     * <p><b>WHY 需要它</b>（2026-11-04 用户实测「新轮次会默认打开之前的产物」）：
     * 轮末目录扫描 / 轮内监看每轮都会把目录里**所有**文件重走一遍 upsert，而 upsert 原先
     * <b>无条件</b>刷 {@code updated_at} ⇒ 上一轮的旧产物被顶到「最新」⇒ 前端按 updated_at
     * 倒序取 {@code list[0]} 就把**旧产物当新的**弹了出来。
     *
     * <p><b>判据</b>：
     * <ul>
     *   <li><b>产物</b>（有 localPath）：大小相同 <b>且</b> 文件 mtime <b>不晚于</b>行上的
     *       {@code updated_at}（= 我们上次确认它的时间）⇒ 之后没人改过它；</li>
     *   <li><b>来源</b>（无 localPath）：url / 大小 / 摘要全同 ⇒ 没变。</li>
     * </ul>
     *
     * <p><b>⛔ 只比大小不够</b>：同尺寸重写（"cat" → "dog"）会被误判成「没变」⇒ 把轮内监看
     * （按 {@code size:mtime} 精确去重）的判断压掉 ⇒ 编辑过程中不再实时推送。
     * ⇒ 必须带上 mtime。
     *
     * @param existing  DB 里已有的行
     * @param localPath 产物本地路径（null = 来源类条目）
     * @param url       来源 url（产物为 null）
     * @param sizeBytes 本次读到的大小
     * @param summary   本次摘要
     * @return true = 没变（调用方应跳过时间戳刷新与推送）
     */
    private static boolean isUnchangedArtifact(SessionArtifactRecord existing, String localPath, String url,
                                               Long sizeBytes, String summary) {
        if (!java.util.Objects.equals(existing.getUrl(), url)
                || !java.util.Objects.equals(existing.getSizeBytes(), sizeBytes)
                || !java.util.Objects.equals(existing.getSummary(), summary)) {
            return false;
        }
        if (localPath == null || localPath.isBlank()) {
            return true;   // 来源类：url/大小/摘要全同 ⇒ 没变
        }
        if (!java.util.Objects.equals(existing.getLocalPath(), localPath)) {
            return false;  // 路径变了 ⇒ 换了文件 ⇒ 变了
        }
        // mtime 与 updated_at 比较：文件是否在「我们上次确认它」之后被改过。
        String updatedAt = existing.getUpdatedAt();
        if (updatedAt == null || updatedAt.isBlank()) {
            return false;  // 无上次确认时间 ⇒ 保守当「变了」（刷新一次时间戳）
        }
        try {
            long mtime = Files.getLastModifiedTime(Paths.get(localPath)).toMillis();
            long lastConfirmed = java.time.OffsetDateTime.parse(updatedAt).toInstant().toEpochMilli();
            return mtime <= lastConfirmed;
        } catch (Exception e) {
            // 读不到 mtime（文件已删 / 路径异常）⇒ 保守当「变了」
            return false;
        }
    }

    private void upsert(String sessionId, String kind, String title, String localPath, String url,
                        Long sizeBytes, String summary, String now, String sourceMessageId) {
        upsert(sessionId, kind, title, localPath, url, sizeBytes, summary, now, sourceMessageId, null);
    }

    /**
     * 幂等写入（带来源通道 version）。
     *
     * @param origin 产物来源通道 · {@code deliverables} / {@code project-write}；
     *               {@code null} = 不标注（来源类条目、或调用方不关心）
     */
    private void upsert(String sessionId, String kind, String title, String localPath, String url,
                        Long sizeBytes, String summary, String now, String sourceMessageId,
                        String origin) {
        try {
            // ⭐ 幂等键 = (session, kind, title) · **不含轮次**（V279 起）。
            //   WHY 去掉了 sourceMessageId（2026-11-10 用户裁定）：原键含轮次，而轮末
            //   syncDeliverablesFromDir 把「本轮 id」喂给目录里**每一个**文件 ⇒ 同一文件每轮
            //   插一行。实测某会话 28 个真实文件产生 889 行、横跨 108 个轮次 ⇒「按对话分组」
            //   会变成上百份重复。现在一轮文件只有一行，归属 = **首次产出那一轮**（见下方
            //   「只在原本为空时写入」），这才是「哪个对话产出的」的正确答案。
            QueryWrapper key = QueryWrapper.create()
                    .eq("session_id", sessionId)
                    .eq("kind", kind)
                    .eq("title", title);
            SessionArtifactRecord existing = artifactMapper.selectOneByQuery(key);
            if (existing != null) {
                // [2026-11-04] **产物没变 ⇒ 不刷新 updated_at、不推送**。
                // WHY：轮末目录扫描 / 轮内监看每轮都会把目录里**所有**文件重新走一遍 upsert。
                //   原实现无条件 `setUpdatedAt(now)` ⇒ 上一轮的旧产物被顶到「最新」⇒
                //   前端按 updated_at 倒序取 list[0] 就把**旧产物当新的**弹了出来
                //   （用户实测「新轮次会默认打开之前的产物」）。
                //
                //   判据 = 「这个文件自上次记录以来**没有再被改过**」：
                //     · 大小相同 **且** 文件的 mtime **不晚于** 行上的 updated_at
                //       （updated_at = 我们上次确认它的时间；mtime > 它 ⇒ 文件在那之后被改过）。
                //   ⛔ 只比大小**不够**：同尺寸重写（"cat"→"dog"）会被误判成「没变」⇒
                //       会把轮内监看（按 mtime 精确去重）的判断给压掉 ⇒ 编辑中不再实时推送。
                //   ⛔ source 没有磁盘文件 ⇒ 走「url/大小/摘要全同」的记录级判据。
                boolean unchanged = isUnchangedArtifact(existing, localPath, url, sizeBytes, summary);
                if (unchanged) {
                    if (log.isDebugEnabled()) {
                        log.debug("[artifact-extract] UPSERT 未变（跳过刷新时间戳+推送） session={} kind={} title={}",
                                sessionId, kind, title);
                    }
                    return;
                }
                // 旧副本路径必须在覆盖前抓下来：新副本名带「路径 hash」，路径不变时同名覆盖，
                // 路径变了（用户换了文件）则旧副本成为孤儿 —— 见下方 cleanupReplacedCopy。
                String oldLocalPath = existing.getLocalPath();
                String existingId = existing.getId();
                existing.setLocalPath(localPath);
                existing.setUrl(url);
                existing.setSizeBytes(sizeBytes);
                existing.setSummary(summary);
                existing.setUpdatedAt(now);
                // ⭐ 归属与通道**只在原本为空时补写**，已有值一律不覆盖。
                // WHY：既然键里没有轮次，每轮都会走到这个分支；若无条件覆盖归属，这个文件就会
                //   在历史轮次之间「搬家」（用户 2026-11-05 报过的正是这个）。首次产出那一轮
                //   才是它的归属。
                if (isBlank(existing.getSourceMessageId()) && !isBlank(sourceMessageId)) {
                    existing.setSourceMessageId(sourceMessageId);
                }
                if (isBlank(existing.getOrigin()) && !isBlank(origin)) {
                    existing.setOrigin(origin);
                }
                // update(entity, false) = **显式写 NULL**（MyBatis-Flex 默认忽略 null 字段）：
                // 本表语义是「本行 = 最终版抽取结果」，清空的 summary/size 必须真的落成 NULL，
                // 否则旧值会一直挂在列表上（EffortCommand/SessionPermissionOverlay 同款用法）。
                artifactMapper.update(existing, false);
                // 清理被替换掉的旧副本。刻意放在 update <b>成功之后</b>：若先删后写、写库失败，
                // 行仍指向已删文件 ⇒ 死链；反过来只可能留一个孤儿副本（下次同路径抽取会复用同名文件）。
                cleanupReplacedCopy(sessionId, existingId, oldLocalPath, localPath);
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] UPSERT 更新 session={} kind={} title={} path={} url={}",
                            sessionId, kind, title, localPath, url);
                }
                // [产物实时推送] 内容变了 → 推给前端（前端按 id 幂等更新，不重复插行）
                pushArtifactUpsert(sessionId, existing, false);
                return;
            }
            SessionArtifactRecord row = new SessionArtifactRecord();
            row.setId("art-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            row.setSessionId(sessionId);
            row.setKind(kind);
            row.setTitle(title);
            row.setLocalPath(localPath);
            row.setUrl(url);
            row.setSizeBytes(sizeBytes);
            row.setSummary(summary);
            // ⭐ 归属到触发它的那一轮用户消息（V205 列语义，2026-11-05 起真正写入）
            row.setSourceMessageId(
                    sourceMessageId == null || sourceMessageId.isBlank() ? null : sourceMessageId);
            row.setOrigin(origin);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            artifactMapper.insert(row);
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] UPSERT 新增 session={} kind={} title={} path={} url={} origin={}",
                        sessionId, kind, title, localPath, url, origin);
            }
            // [产物实时推送] 新产物 → 前端即时插入并按需自动展开侧栏
            pushArtifactUpsert(sessionId, row, true);
        } catch (Exception e) {
            log.warn("[artifact-extract] 入库失败（跳过该条，不影响其它条目） session={} kind={} title={}: {}",
                    sessionId, kind, title, e.toString());
        }
    }

    /** 字符串 null/空白判定（归属/通道「只在原本为空时写入」用）。 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ───────────────────────── 产物归档（复制到自有目录） ─────────────────────────

    /**
     * 归档根目录：{@code {configHome}/artifacts}。
     *
     * <p><b>为什么是 configHome</b>：本仓「本机自有资产」的唯一真源是
     * {@link NexusaiPaths#getAppConfigHomeDir()}（= {@code {user.home}/.{appName}}，appName 由
     * {@code spring.application.name} 派生 —— 场景版即 {@code ~/.nexusai-scene}）。它与 Rust 侧由
     * {@code NEXUSAI_APP_NAME} 派生的目录同源，且 {@code image-cache} / {@code file-history} 等
     * 会话级落盘资产都在这棵树下（与 {@code ImageAttachmentStore.storeDir} 同一口径）。
     * ⛔ 不硬编码 {@code user.home} / {@code ~/.nexusai}（换 appName 会写到两处）。
     *
     * @return 归档根绝对路径（已 normalize）
     */
    static Path artifactsRoot() {
        // [data-root] 安装目录 <install>/data/workspace/<user> 优先，configHome 兜底（用户 2026-11-12 裁定）
        return NexusaiDataRoots.sub(ARTIFACTS_DIR_NAME);
    }

    /** 交付物目录名（会话产物目录下的子目录，Agent 按系统提示词写入这里）。 */
    public static final String DELIVERABLES_DIR_NAME = "deliverables";

    /** 截图目录名（会话产物目录下的子目录，computer-use observe 的截图产物写这里）。 */
    public static final String SCREENSHOTS_DIR_NAME = "screenshots";

    /**
     * 会话截图目录：{@code {artifactsRoot}/{sessionId}/screenshots}。
     *
     * <p><b>WHY 单独一个子目录</b>（2026-11-03 用户裁定）：截图<b>不是交付物</b> ——
     * 交付物是「用户要的东西」，进产物面板（ArtifactDock）会让用户看到一堆临时截图。
     * <br>⇒ 截图与 {@code deliverables/} 平级但独立：同根（便于随会话一起清理）、
     * 不同目录（不进产物面板）。
     * <br>⛔ 该目录必须留在 {@code artifactsRoot} 之内：归档逻辑遇到「已经在自有目录里」
     * 的文件会跳过复制，避免自我复制出重复副本。
     *
     * @param sessionId 会话 id（空白 → {@code unknown}，与 {@link #sessionArtifactsDir} 同口径）
     * @return 截图目录绝对路径（不保证已存在）
     */
    public static Path screenshotsDir(String sessionId) {
        return sessionArtifactsDir(sessionId).resolve(SCREENSHOTS_DIR_NAME);
    }

    /**
     * 会话交付物目录：{@code {artifactsRoot}/{sessionId}/deliverables}。
     *
     * <p><b>WHY 单独一个子目录</b>（2026-11-02 用户裁定「做法 A：提示词约定固定目录 + 后端自己扫」）：
     * 现有 {@code {artifactsRoot}/{sessionId}/} 里放的是**事后归档副本**（{@code {hash}-{name}.{ext}}），
     * 文件名被打乱、且是抽取链路自己生成的 ⇒ 无法作为「Agent 主动放文件的地方」。
     * 加一层 {@code deliverables/} 后语义清晰：
     * <ul>
     *   <li>Agent 按提示词**直接写**进这里，文件名保持用户可读；</li>
     *   <li>后端**扫这个目录**就知道本次会话有哪些交付物 ⇒ ⛔ 不需要模型回报路径，
     *       也不需要从工具入参里猜（Bash 脚本写的文件正是靠这一条才收得到）。</li>
     * </ul>
     *
     * <p>⛔ 该目录必须留在 artifactsRoot 之内：归档逻辑遇到「已经在自有目录里」的文件会跳过复制
     * （见 {@code saveDeliverable}），避免自我复制出 {@code {hash}-xxx.docx} 的重复副本。
     *
     * @param sessionId 会话 id（空白 → {@code unknown}，与 {@link #sessionArtifactsDir} 同口径）
     * @return 交付物目录绝对路径（不保证已存在）
     */
    public static Path deliverablesDir(String sessionId) {
        return sessionArtifactsDir(sessionId).resolve(DELIVERABLES_DIR_NAME);
    }

    /**
     * 会话归档目录：{@code {artifactsRoot}/{sessionId}}。
     *
     * @param sessionId 会话 id（空白 → {@code unknown}；非法文件名字符替换为 {@code _}）
     * @return 会话归档目录绝对路径
     */
    static Path sessionArtifactsDir(String sessionId) {
        return artifactsRoot().resolve(sanitizeSessionId(sessionId)).normalize();
    }

    /**
     * 复制产物到会话归档目录。成功返回副本绝对路径；任何「不该复制 / 复制不了」的情况返回 null
     * （调用方回落记录原路径）。
     *
     * <p>丢弃/跳过口径（均只 WARN 或 debug，⛔ 绝不抛出）：
     * <ul>
     *   <li>源不是普通文件（目录 / 设备）或不是普通符号链接 → 跳过</li>
     *   <li>符号链接 → 跳过（复制链接目标会让归档随目标一起消失，与「固定副本」目标相反）</li>
     *   <li>大小 &gt; {@code limitMb} → 跳过（{@code limitMb <= 0} = 不限制）</li>
     *   <li>源已经在本会话归档目录内 → 直接复用，避免「副本的副本」</li>
     *   <li>建目录 / 复制失败（权限、磁盘满、文件被独占）→ 返回 null</li>
     * </ul>
     *
     * <p>文件名 = {@code {sha256(原绝对路径) 前12位}-{原 basename}}：同一路径重复抽取命中同名文件
     * （{@code REPLACE_EXISTING} 覆盖）⇒ 不产生垃圾副本。
     *
     * @param source    源文件（绝对路径）
     * @param sessionId 会话 id
     * @param limitMb   单文件上限（MB）；{@code <= 0} 表示不限制
     * @return 副本绝对路径；跳过/失败 → null
     */
    static Path archiveFile(Path source, String sessionId, int limitMb) {
        try {
            if (source == null) {
                return null;
            }
            Path src = source.toAbsolutePath().normalize();
            if (!Files.exists(src)) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 归档跳过：源不存在 session={} src={}", sessionId, src);
                }
                return null;
            }
            if (Files.isSymbolicLink(src)) {
                log.warn("[artifact-extract] 归档跳过：符号链接只记录原路径 session={} src={}", sessionId, src);
                return null;
            }
            if (!Files.isRegularFile(src)) {
                log.warn("[artifact-extract] 归档跳过：非普通文件（目录/设备）只记录原路径 session={} src={}",
                        sessionId, src);
                return null;
            }
            Path sessionDir = sessionArtifactsDir(sessionId);
            if (src.startsWith(sessionDir)) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 归档跳过：源已在会话归档目录内，直接复用 session={} src={}",
                            sessionId, src);
                }
                return src;
            }
            long size = Files.size(src);
            if (limitMb > 0 && size > (long) limitMb * 1024L * 1024L) {
                log.warn("[artifact-extract] 归档跳过：超过 {}MB 只记录原路径 session={} src={} size={}",
                        limitMb, sessionId, src, size);
                return null;
            }
            Path target = archiveTarget(src, sessionId);
            if (target == null) {
                return null;
            }
            // 已有副本且「大小 + 修改时间」与原文件一致 ⇒ 已是同一份，跳过复制。
            // WHY：抽取每轮都会跑，对几十 MB 的交付物重复复制会白白占抽取线程与磁盘 IO。
            // 判据用「源的大小/修改时间」而非「副本内容 hash」：复制时保留了源修改时间，
            // 源一旦被编辑 mtime 必变 ⇒ 走正常覆盖分支（最多一次多余的复制，不会留下旧内容）。
            if (isUpToDateCopy(src, target, size)) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 副本已是最新，跳过复制 session={} src={} dst={}",
                            sessionId, src, target);
                }
                return target;
            }
            Files.createDirectories(target.getParent());
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            // 保留最后修改时间（失败不影响「副本已就位」这一事实，故只 debug）
            try {
                Files.setLastModifiedTime(target, Files.getLastModifiedTime(src));
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 副本修改时间保留失败（忽略） dst={}: {}", target, e.toString());
                }
            }
            log.info("[artifact-extract] 归档产物 session={} src={} dst={} size={}", sessionId, src, target, size);
            return target;
        } catch (Exception e) {
            log.warn("[artifact-extract] 归档产物失败（回落记录原路径）session={} src={}: {}",
                    sessionId, source, e.toString());
            return null;
        }
    }

    /** 副本目标路径（{@code {hash12}-{basename}}）；基名为空 → null。 */
    static Path archiveTarget(Path source, String sessionId) {
        Path fileName = source.getFileName();
        if (fileName == null || fileName.toString().isBlank()) {
            return null;
        }
        String hash = pathHash(source);
        if (hash == null) {
            return null;
        }
        return sessionArtifactsDir(sessionId).resolve(hash + "-" + fileName.toString()).normalize();
    }

    /** 目标副本是否已与原文件一致（存在 + 大小相同 + 修改时间相同）；任何读取异常 → 视为不一致。 */
    private static boolean isUpToDateCopy(Path source, Path target, long sourceSize) {
        try {
            if (!Files.isRegularFile(target) || Files.size(target) != sourceSize) {
                return false;
            }
            return Files.getLastModifiedTime(target).toMillis()
                    == Files.getLastModifiedTime(source).toMillis();
        } catch (Exception e) {
            return false;
        }
    }

    /** 原<b>绝对路径</b>的 sha256 十六进制前 {@value #ARCHIVE_HASH_LEN} 位；摘要不可用 → null。 */
    private static String pathHash(Path source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    source.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(ARCHIVE_HASH_LEN);
            for (int i = 0; sb.length() < ARCHIVE_HASH_LEN && i < bytes.length; i++) {
                sb.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
                if (sb.length() < ARCHIVE_HASH_LEN) {
                    sb.append(Character.forDigit(bytes[i] & 0xF, 16));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[artifact-extract] 计算产物路径指纹失败（不归档）: {}", e.toString());
            return null;
        }
    }

    /**
     * 会话 id → 目录名安全串（只做文件名非法字符替换；会话 id 来自 DB，正常形态 {@code sess-xxxxxxxx}）。
     *
     * <p>⛔ 防路径穿越：{@code /} {@code \} {@code :} 等一律替换，且单段 {@code .} / {@code ..} 归一为
     * {@code unknown}（否则 {@code resolve} 会跳出归档根）。
     */
    private static String sanitizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "unknown";
        }
        String trimmed = sessionId.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean illegal = c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c < 0x20;
            sb.append(illegal ? '_' : c);
        }
        String safe = sb.toString();
        return ".".equals(safe) || "..".equals(safe) ? "unknown" : safe;
    }

    /**
     * 删除被本次抽取替换掉的旧副本（仅限<b>本会话归档目录内</b>）。
     *
     * <p>⛔ 绝不删归档目录之外的任何文件：{@code local_path} 可能指向用户原文件（归档失败时的回落值），
     * 那类路径必须原样保留。判定用 {@code startsWith(artifactsRoot)} 再叠加本会话目录（双重严格）。
     *
     * @param existingId   正在更新的行 id（引用计数时排除自身）
     * @param oldLocalPath 旧行的 local_path（null / 空 → 无副本可清）
     * @param newLocalPath 本次要写入的 local_path（与旧值相同 → 同名覆盖，无需清理）
     */
    /**
     * [产物实时推送 2026-11-02] 把一条产物 upsert 推给该会话的前端。
     *
     * <p><b>失败绝不冒泡</b>：推送是旁路增强，出错只能退化为「靠 15 秒轮询发现」，
     * ⛔ 不能让 STOMP 异常把已成功的入库回滚或打断后续条目的抽取。
     *
     * <p>载荷字段与 {@code SessionArtifactDto} 对齐（前端直接复用同一接口类型插入列表）：
     * {@code {action:'upsert', artifact:{...}}}；{@code action} 预留给将来的删除事件。
     *
     * @param created true = 新插入（前端据此判断「要不要自动展开侧栏」），false = 更新已有行
     */
    private void pushArtifactUpsert(String sessionId, SessionArtifactRecord row, boolean created) {
        if (wsTemplate == null || sessionId == null || sessionId.isBlank() || row == null) return;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = JSON.createObjectNode();
            node.put("action", "upsert");
            node.put("created", created);
            node.put("sessionId", sessionId);
            com.fasterxml.jackson.databind.node.ObjectNode a = node.putObject("artifact");
            a.put("id", row.getId());
            a.put("kind", row.getKind());
            a.put("title", row.getTitle());
            a.put("localPath", row.getLocalPath());
            a.put("url", row.getUrl());
            if (row.getSizeBytes() == null) a.putNull("sizeBytes"); else a.put("sizeBytes", row.getSizeBytes());
            a.put("summary", row.getSummary());
            // ⚠️ 推送与轮询（SessionArtifactDto）字段必须逐字一致，否则前端两条路径拿到的
            //    origin 一个有值一个没有 ⇒ 角标时有时无。
            a.put("origin", row.getOrigin());
            a.put("sourceMessageId", row.getSourceMessageId());
            a.put("updatedAt", row.getUpdatedAt());
            // 目录判定是**读时**做的（不落库）⇒ 推送时按路径补一次，与 list 接口口径一致
            a.put("directory", row.getLocalPath() != null && Files.isDirectory(toPathQuietly(row.getLocalPath())));
            wsTemplate.convertAndSend(TOPIC_PREFIX + sessionId + TOPIC_SUFFIX, node.toString());
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] 实时推送 session={} id={} created={} title={}",
                        sessionId, row.getId(), created, row.getTitle());
            }
        } catch (Throwable t) {
            // ⛔ 只 WARN：推送失败不影响已入库的数据，前端仍能靠轮询拿到
            log.warn("[artifact-extract] 实时推送失败（不影响入库，前端可轮询补偿） session={} id={}: {}",
                    sessionId, row == null ? null : row.getId(), t.toString());
        }
    }

    /** 宽松路径解析（非法路径 → null，不抛）。 */
    private static java.nio.file.Path toPathQuietly(String p) {
        try {
            return java.nio.file.Paths.get(p);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 扫描会话交付物目录 → 逐条入库（**确定性收集**，不经过任何模型）。
     *
     * <p><b>WHY（2026-11-02 用户裁定「做法 A：固定目录 + 后端自己查」）</b>：
     * 原链路只有「轮末旁路 LLM 读会话猜路径」一条，而 Bash 脚本生成的文件**根本猜不到**
     * （{@code BashTool} 的入参是 {@code command}，没有路径键 ⇒ 只能靠轮内目录扫描兜住）。
     * 改为「Agent 按系统提示词把交付物写进固定目录 → 后端扫目录」后：
     * <ul>
     *   <li>脚本产物也能收到（只要写进目录）；</li>
     *   <li>不消耗模型调用；</li>
     *   <li>目录本身就是声明 ⇒ ⛔ 不需要模型回报路径，也没有「它忘了回报」这种失败模式。</li>
     * </ul>
     *
     * <p><b>幂等</b>：以文件名为 title 走既有 {@code upsert}（键 = session_id + kind + title），
     * 同一文件被改写（重新生成）只会更新 size/时间，不会产生第二行。
     *
     * <p><b>容错</b>：目录不存在 = 本轮没有交付物（正常情况）；任何 IO 异常只 WARN，
     * ⛔ 绝不冒泡（抽取是旁路，不能影响对话）。
     *
     * @param sessionId 会话 id
     * @return 本次新入库/更新的条数（用于日志与测试断言）
     */
    public int syncDeliverablesFromDir(String sessionId, String sourceMessageId) {
        if (!extractEnabled || sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Path dir = deliverablesDir(sessionId);
        if (!Files.isDirectory(dir)) {
            return 0;   // 本轮没往目录里写东西 —— 正常，不是错误
        }
        int saved = 0;
        String now = OffsetDateTime.now().toString();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                try {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    // 源码黑名单复用同一条判据：即便模型把 .py 写进了目录也不收
                    if (isSourceCodeFile(file)) {
                        if (log.isDebugEnabled()) {
                            log.debug("[artifact-scan] 跳过源码文件 session={} file={}", sessionId, file.getFileName());
                        }
                        continue;
                    }
                    String name = file.getFileName() == null ? null : file.getFileName().toString();
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    var item = new SessionArtifactParser.ParsedArtifact(name, file.toString(), null, null);
                    if (saveDeliverable(sessionId, item, dir.toString(), now, sourceMessageId)) {
                        saved++;
                    }
                } catch (Throwable t) {
                    log.warn("[artifact-scan] 单条处理失败（跳过，不影响其它） session={} file={}: {}",
                            sessionId, file, t.toString());
                }
            }
        } catch (Throwable t) {
            log.warn("[artifact-scan] 扫描交付物目录失败（不影响对话） session={} dir={}: {}",
                    sessionId, dir, t.toString());
        }
        if (saved > 0) {
            log.info("[artifact-scan] 交付物目录扫描完成 session={} dir={} 入库/更新={} 条", sessionId, dir, saved);
        }
        return saved;
    }

    /**
     * 写盘瞬间同步单条产物（**供 {@code SessionFilesRecorder} 在工具写盘时调用**）。
     *
     * <p><b>WHY 需要这个入口</b>（2026-11-02 用户实测「编辑过程中侧栏没有自动展开」）：
     * 推送此前只在 {@link #scheduleExtract} 里发生，而它挂在 {@code ChatService} 的**轮末**
     * （{@code status=idle} 之后）。日志实测：每次 {@code [artifact-scan]} 都紧跟
     * {@code STOMP → status=idle} —— 即<b>整轮执行期间零推送</b>。
     * 而前端「自动展开」的门控恰好是「本轮仍在跑」（{@code turnRunningRef.current}）⇒
     * 那个分支<b>结构上永不成立</b>：等到推送到达，轮次早已结束，门控刚把面板按回去。
     *
     * <p>本方法在**文件真正落盘的那一刻**跑一遍：只处理「路径在 {@link #deliverablesDir}
     * 之下」的那一个文件（{@link #syncDeliverablesFromDir} 是整目录扫，用它会把每写一个文件
     * 变成整目录重扫）。走 {@link #saveDeliverable} 同一入库 + {@link #pushArtifactUpsert} 同一推送，
     * 保证与轮末抽取逐字段一致（幂等 UPSERT，重复不产生第二行）。
     *
     * <p><b>绝不抛</b>：写盘已经成功，告警/实时性都不能反过来影响它。
     *
     * @param sessionId 会话 id（null/空白 → 直接返回）
     * @param absPath   刚写入的绝对路径
     * @return true = 已作为产物入库并推送；false = 不在产物目录 / 被过滤 / 未启用
     */
    public boolean syncDeliverableForPath(String sessionId, String absPath, String sourceMessageId) {
        if (!extractEnabled || sessionId == null || sessionId.isBlank() || absPath == null || absPath.isBlank()) {
            return false;
        }
        try {
            Path dir = deliverablesDir(sessionId).toAbsolutePath().normalize();
            Path file = Path.of(absPath).toAbsolutePath().normalize();
            // 双重严格（与 syncDeliverablesFromDir 同口径）：必须在产物目录**之下**，且不是目录本身
            if (!file.startsWith(dir) || file.equals(dir) || !Files.isRegularFile(file)) {
                return false;
            }
            String name = file.getFileName() == null ? null : file.getFileName().toString();
            if (name == null || name.isBlank()) {
                return false;
            }
            var item = new SessionArtifactParser.ParsedArtifact(name, file.toString(), null, null);
            boolean saved = saveDeliverable(sessionId, item, dir.toString(), OffsetDateTime.now().toString(),
                    sourceMessageId);
            if (saved && log.isInfoEnabled()) {
                log.info("[artifact-scan] 写盘即入库 session={} file={}（轮中实时推送）", sessionId, name);
            }
            return saved;
        } catch (Throwable t) {
            log.warn("[artifact-scan] 写盘即入库失败（不影响写盘） session={} path={}: {}",
                    sessionId, absPath, t.toString());
            return false;
        }
    }

    /**
     * 项目内文档入库（Write/Edit 写了 {@code docs/xxx.md} 这类文件时调用）· V279 起。
     *
     * <p><b>WHY 需要这条通道</b>（2026-11-10 用户裁定「项目目录里的文件也算产物」）：
     * 此前只收 {@link #deliverablesDir} 之下的文件，而实际工作里大量交付文档是直接写在
     * 项目目录里的（{@code docs/zjkycode/plans/*.md}、{@code docs/specs/*.md}）——
     * 用户在原会话里指出的两个 md 正是这类，它们在产物表里 **0 行**。
     *
     * <p><b>⛔ 必须归档复制</b>：产物读取端点有路径边界
     * （{@link #resolveArtifactPath}：不在本会话归档目录内一律 400）。项目内文件直接入库
     * 前端一打开就报「产物路径非法」⇒ 必须用 {@link #archiveFile} 复制进
     * {@code {configHome}/artifacts/{sid}/}，{@code local_path} 存**副本**。
     *
     * <p><b>⛔ 归档失败就不入库</b>：回落记录原路径的话，条目在列表上点不开（死链）。
     * 与 {@code saveDeliverable}「不展示点不开的死链」同口径。
     *
     * <p><b>过滤三层</b>（顺序不能换）：
     * <ol>
     *   <li>黑名单 {@link #isSourceCodeFile}：源码/构建产物绝对不收；</li>
     *   <li>白名单 {@link #DOC_EXTENSIONS}：只有「用户会看的文档」才收（无扩展名文件在此被挡）；</li>
     *   <li>路径段排除 {@link #EXCLUDED_PATH_SEGMENTS}：{@code node_modules} / {@code target}
     *       之类编译产物目录不收。</li>
     * </ol>
     *
     * <p><b>绝不抛</b>：写盘已经成功，采集只是旁路增强。
     *
     * @param sessionId        会话 id（null/空白 → false）
     * @param absPath          Write/Edit 写盘后的绝对路径
     * @param sourceMessageId  本轮 userMessageId（归属键；可为 null）
     * @return true = 已作为产物入库并推送
     */
    public boolean syncProjectDocForPath(String sessionId, String absPath, String sourceMessageId) {
        if (!extractEnabled || isBlank(sessionId) || isBlank(absPath)) {
            return false;
        }
        try {
            Path file = Path.of(absPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                return false;
            }
            // ① 黑名单（源码绝对不收）
            if (isSourceCodeFile(file)) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-project-doc] 丢弃源码文件 session={} path={}", sessionId, file);
                }
                return false;
            }
            // ② 白名单（只有文档类扩展名才收）
            String name = file.getFileName() == null ? "" : file.getFileName().toString();
            String ext = ArtifactFileKind.extensionOf(name);
            if (!DOC_EXTENSIONS.contains(ext.toLowerCase())) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-project-doc] 丢弃非文档扩展名 session={} path={} ext={}",
                            sessionId, file, ext);
                }
                return false;
            }
            // ③ 路径段排除（编译产物目录）— 同时排除 configHome 内部路径（那是应用自有资产，不是用户文档）
            if (hasExcludedSegment(file) || isInsideConfigHome(file)) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-project-doc] 丢弃排除路径 session={} path={}", sessionId, file);
                }
                return false;
            }
            // ④ 归档复制（不复制的话产物端点会以「产物路径非法」400）
            Path archived = archiveFile(file, sessionId, ARCHIVE_MAX_MB);
            if (archived == null) {
                log.warn("[artifact-project-doc] 归档失败，不入库（避免点不开的死链）session={} path={}",
                        sessionId, file);
                return false;
            }
            Long sizeBytes = null;
            try {
                sizeBytes = Files.size(archived);
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-project-doc] 副本大小读取失败 size=null path={}: {}",
                            archived, e.toString());
                }
            }
            upsert(sessionId, KIND_DELIVERABLE, name, archived.toString(), null, sizeBytes, null,
                    OffsetDateTime.now().toString(), sourceMessageId, ORIGIN_PROJECT_WRITE);
            log.info("[artifact-project-doc] 项目文档入库 session={} name={} src={} copy={} bytes={}",
                    sessionId, name, file, archived, sizeBytes);
            return true;
        } catch (Throwable t) {
            log.warn("[artifact-project-doc] 项目文档采集失败（不影响写盘） session={} path={}: {}",
                    sessionId, absPath, t.toString());
            return false;
        }
    }

    /** 路径任一段命中 {@link #EXCLUDED_PATH_SEGMENTS}（大小写不敏感）。 */
    private static boolean hasExcludedSegment(Path file) {
        for (Path seg : file) {
            String s = seg.toString();
            if (s.isEmpty()) {
                continue;
            }
            if (EXCLUDED_PATH_SEGMENTS.contains(s.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 是否落在应用自有配置目录内（{@code {configHome}} 下的文件不是「用户文档」）。 */
    private static boolean isInsideConfigHome(Path file) {
        try {
            Path home = Paths.get(NexusaiPaths.getAppConfigHomeDir()).toAbsolutePath().normalize();
            return file.startsWith(home);
        } catch (Exception e) {
            return false;   // 解析不出就当不在（宁可多收一条，也不能吞掉用户文档）
        }
    }

    /**
     * 该路径是否在本会话交付物目录之下（供 hook 分流用）。
     *
     * <p>与 {@link #syncDeliverableForPath} 的入口判据同源（都折算成绝对路径再比），
     * ⛔ 不另写一份 normalize 逻辑，否则两处口径可能漂移。
     *
     * @param sessionId 会话 id
     * @param absPath   绝对路径
     * @return true = 在交付物目录之下（含目录本身 → false，目录不是文件）
     */
    public boolean isInDeliverablesDir(String sessionId, String absPath) {
        if (isBlank(sessionId) || isBlank(absPath)) {
            return false;
        }
        try {
            Path dir = deliverablesDir(sessionId).toAbsolutePath().normalize();
            Path file = Path.of(absPath).toAbsolutePath().normalize();
            return file.startsWith(dir) && !file.equals(dir);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 开始「轮内监看」该会话的交付物目录（**每 {@value #TURN_WATCH_INTERVAL_MS}ms 扫一次**）。
     *
     * <p><b>WHY 需要它</b>（2026-11-03 用户提问「copy 了是不是要写监听器，不然怎么动态感知」）：
     * 提示词允许「按技能要求在原位置生成 → 再复制到产物目录」，而 <b>cp / 脚本复制不经过任何
     * 写盘钩子</b>（{@code SessionFilesRecorder} 只覆盖 Write/Edit 工具）⇒ 此前复制进来的文件
     * 只能等轮末抽取，而前端「自动展开产物面板」的门控是「本轮仍在跑」⇒ 又是永不重叠。
     *
     * <p><b>为什么是「轮内」而不是常驻监听</b>：
     * <ul>
     *   <li>自动展开只在本轮执行中有意义（轮末的产物卡由用户自己点）⇒ 扫描窗口与之天然对齐；</li>
     *   <li>常驻 WatchService 要为每个会话动态注册/注销（macOS kqueue 不递归），生命周期与
     *       泄漏面都更复杂；而这里每次只 `Files.list` 一个目录，几个文件的量级。</li>
     * </ul>
     *
     * <p><b>幂等</b>：只扫 `size:mtime` 变化的文件（见 {@link #turnWatches} 的 WHY），
     * 未变更不入库、不推送。重复调用 startTurnWatch 不会叠加任务。
     *
     * @param sessionId 会话 id（null/空白 / 开关关闭 → no-op）
     */
    public void startTurnWatch(String sessionId, String userMessageId) {
        if (!extractEnabled || sessionId == null || sessionId.isBlank()) {
            return;
        }
        turnWatches.compute(sessionId, (sid, existing) -> {
            if (existing != null) {
                return existing;   // 已在监看（同一轮重复触发）→ 不叠加
            }
            Map<String, String> signatures = new ConcurrentHashMap<>();
            // [2026-11-04] **先播种签名表**：目录里**已存在**的文件（上一轮 / 更早的产物）
            //   先记下 size:mtime ⇒ 第一拍扫描时它们「未变更」⇒ 不会重推、不会入库。
            //   WHY：此前签名表是空的 ⇒ 第一拍把目录里所有旧文件都当成「变过了」
            //   （signatures.put 返回 null ≠ prev）⇒ 逐个 syncDeliverableForPath ⇒ upsert
            //   ⇒ 推 STOMP ⇒ 前端在新轮次里收到推送 → 自动打开**上一轮**的产物
            //   （用户实测「新轮次会默认打开之前的产物」）。
            //   播种只记「已存在」，不改动任何文件、不入库、不推送。
            seedTurnWatchSignatures(sid, signatures);
            var future = turnWatchScheduler.scheduleWithFixedDelay(
                () -> scanTurnWatchedDir(sid, signatures, userMessageId),
                TURN_WATCH_INTERVAL_MS, TURN_WATCH_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled()) {
                log.debug("[artifact-watch] 开始轮内监看 session={} 间隔={}ms 已播种文件={}",
                        sid, TURN_WATCH_INTERVAL_MS, signatures.size());
            }
            return new TurnWatch(future, signatures, userMessageId);
        });
    }

    /**
     * 结束该会话的轮内监看（**轮次收口必达点**）。
     *
     * <p>幂等：没有在监看 → no-op。**必须**与 {@link #startTurnWatch} 成对调用，
     * 否则定时任务会跨轮常驻（虽然只做目录 list，但每 2s 一次、且会让「已结束的轮」
     * 继续自动展开产物面板）。
     *
     * @param sessionId 会话 id
     */
    public void stopTurnWatch(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        TurnWatch w = turnWatches.remove(sessionId);
        if (w != null) {
            w.future().cancel(false);
            // ⭐ 轮末补扫一次：监看是每 2s 一拍，最后 2s 内落盘的文件否则会漏收
            //   （LLM 抽取通道已删除，这里是唯一的轮末兜底）。用同一个 userMessageId 归属。
            try {
                scanTurnWatchedDir(sessionId, w.signatures(), w.userMessageId());
            } catch (Throwable t) {
                log.warn("[artifact-watch] 轮末补扫失败（不影响对话） session={}: {}", sessionId, t.toString());
            }
            if (log.isDebugEnabled()) {
                log.debug("[artifact-watch] 结束轮内监看 session={}", sessionId);
            }
        }
    }

    /**
     * 播种轮内监看的签名表（**只读，不入库、不推送**）。
     *
     * <p><b>WHY 需要它</b>（2026-11-04 用户实测「新轮次会默认打开之前的产物」）：
     * 监看开始时签名表是空的 ⇒ 第一拍扫描时目录里**每一个已存在的文件**都会被判成
     * 「变过了」（{@code signatures.put} 首次返回 null，与 prev 不等）⇒ 逐个走
     * {@link #syncDeliverableForPath} ⇒ 入库 + 推 STOMP ⇒ 前端在<b>新轮次</b>里收到推送
     * ⇒ 自动展开面板 ⇒ 弹出来的是<b>上一轮</b>的产物。
     *
     * <p>⇒ 监看开始前先把「已存在文件」的 {@code size:mtime} 记进签名表 ⇒ 第一拍它们就是
     * 「未变更」⇒ 不重推。**本轮真正新写/新复制**的文件不在播种范围内 ⇒ 仍会被正常感知。
     *
     * <p>⛔ 播种只读目录元信息，不读文件内容、不碰 DB、不发推送。
     *
     * @param sessionId  会话 id
     * @param signatures 签名表（原地写入）
     */
    private void seedTurnWatchSignatures(String sessionId, Map<String, String> signatures) {
        try {
            Path dir = deliverablesDir(sessionId);
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    String name = file.getFileName() == null ? null : file.getFileName().toString();
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    try {
                        signatures.put(name, Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis());
                    } catch (Exception e) {
                        // 读不到 stat → 不播种（下一拍当新文件处理，会入库一次，可接受）
                    }
                }
            }
        } catch (Throwable t) {
            // 播种失败只降级（下一拍当新文件处理 ⇒ 最多多重推一次旧产物，不影响对话）
            log.warn("[artifact-watch] 播种签名表失败（降级继续） session={}: {}", sessionId, t.toString());
        }
    }

    /** 单次扫描：只处理 `size:mtime` 变过的文件（未变 ⇒ 不入库、不推送）。 */
    private void scanTurnWatchedDir(String sessionId, Map<String, String> signatures,
                                    String userMessageId) {
        try {
            Path dir = deliverablesDir(sessionId);
            if (!Files.isDirectory(dir)) {
                return;
            }
            java.util.Set<String> present = new java.util.HashSet<>();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    String name = file.getFileName() == null ? null : file.getFileName().toString();
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    present.add(name);
                    String sig;
                    try {
                        sig = Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
                    } catch (Exception e) {
                        continue;   // 读不到 stat → 下一拍再试（文件可能正在被写）
                    }
                    String prev = signatures.put(name, sig);
                    if (sig.equals(prev)) {
                        continue;   // 未变更 → ⛔ 不重复入库/推送
                    }
                    syncDeliverableForPath(sessionId, file.toString(), userMessageId);
                }
            }
            // 文件被删掉 → 忘掉签名，之后同名重建能再次触发
            signatures.keySet().retainAll(present);
        } catch (Throwable t) {
            // 轮内扫描失败只降级（下一拍继续），⛔ 绝不能影响对话
            log.warn("[artifact-watch] 轮内扫描失败 session={}: {}", sessionId, t.toString());
        }
    }

    /**
     * 记录一条来源（**供工具在抓取成功时当场调用**）。
     *
     * <p><b>WHY 需要这个公开入口</b>（2026-11-02 用户裁定「在 websearch / webfetch 中就存好当前」）：
     * 工具本来就持有 url（入参就是它），当场入库比「轮末回头扫消息再解析结果 JSON」更直接、
     * 更快（前端即时可见）、也不依赖「结果 JSON 是什么形状」这个假设。
     *
     * <p>幂等：以 title 为键 upsert（同 url 多次抓到只留一行）。
     *
     * @param sessionId 会话 id（null/空白 → 直接返回，不报错）
     * @param title     展示标题（通常传 url 或页面标题）
     * @param url       来源链接（必须是 http(s)，否则 {@link #saveSource} 会丢弃）
     * @param summary   可选摘要
     * @return true = 已入库/更新；false = 被丢弃（参数不合格）
     */
    public boolean recordSource(String sessionId, String title, String url, String summary,
                                String sourceMessageId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return saveSource(sessionId,
                new SessionArtifactParser.ParsedArtifact(title, null, url, summary),
                OffsetDateTime.now().toString(), sourceMessageId);
    }

    /**
     * 从网络工具（WebSearch / WebFetch）的**结果**里抽取来源并入库（确定性，不经过任何模型）。
     *
     * <p><b>WHY（2026-11-02 用户裁定）</b>：用户原话「来源 我们从 websearch 和 webfetch 能拿到啊」。
     * 这两个工具的结果本来就带 URL：
     * <ul>
     *   <li>WebSearch：{@code {query, results:[{tool_use_id, content:[{title,url}]}, "注释"]}}；</li>
     *   <li>WebFetch：{@code {bytes, code, codeText, result, durationMs, url}}。</li>
     * </ul>
     * ⇒ 直接读结果里的 url 就是**确定性**来源，不需要再让模型读一遍会话「猜」哪些链接值得记。
     *
     * <p><b>只认 http(s)</b>：内部工具跳转地址 / 相对路径没有意义（用户点不开），
     * 与 {@code saveSource} 的「url 必填」口径一致。
     *
     * <p><b>幂等</b>：以 url 为 title 走既有 {@code upsert}（键 = session_id + kind + title）
     * ⇒ 同一个链接被搜到多次也只留一行；title 优先用工具报的页面标题。
     *
     * <p><b>容错</b>：结果不是 JSON / 结构不认识 ⇒ 跳过该条（⛔ 不抛、不猜）。
     *
     * @param sessionId 会话 id
     * @param messages  本轮消息（含 toolCalls，从中找网络工具结果）
     * @return 本次新入库/更新的来源条数
     */
    public int syncSourcesFromToolResults(String sessionId, List<ChatMessageDto> messages,
                                          String sourceMessageId) {
        if (sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return 0;
        }
        String now = OffsetDateTime.now().toString();
        int saved = 0;
        for (ChatMessageDto m : messages) {
            List<ToolCallDto> calls = m.toolCalls();
            if (calls == null || calls.isEmpty()) {
                continue;
            }
            for (ToolCallDto tc : calls) {
                if (!isSourceTool(tc.name())) {
                    continue;
                }
                try {
                    saved += extractUrls(tc.result()).size() > 0
                            ? saveExtractedSources(sessionId, extractUrls(tc.result()), now, sourceMessageId)
                            : 0;
                } catch (Throwable t) {
                    log.warn("[artifact-sources] 单条工具结果解析失败（跳过） session={} tool={}: {}",
                            sessionId, tc.name(), t.toString());
                }
            }
        }
        if (saved > 0) {
            log.info("[artifact-sources] 从网络工具结果收集到来源 session={} 条数={}", sessionId, saved);
        }
        return saved;
    }

    /** 工具名是否是「会产出来源」的网络工具（大小写/前缀不敏感）。 */
    private static boolean isSourceTool(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.toLowerCase();
        // mcp__server__webfetch 这类前缀也命中（子串匹配）
        for (String kw : SOURCE_TOOL_KEYWORDS) {
            if (n.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 从一条工具结果 JSON 里收集 {title, url} 对（去重按 url）。 */
    private List<SessionArtifactParser.ParsedArtifact> extractUrls(String result) {
        if (result == null || result.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(result);
        } catch (Exception e) {
            return List.of();   // 非 JSON（纯文本错误信息等）→ 无来源
        }
        if (root == null || root.isNull()) {
            return List.of();
        }
        // url → title（后面的覆盖前面的：同一 url 后出现的标题通常更准）
        Map<String, String> found = new LinkedHashMap<>();
        collectUrlPairs(root, found);
        if (found.isEmpty()) {
            return List.of();
        }
        List<SessionArtifactParser.ParsedArtifact> out = new ArrayList<>(found.size());
        for (Map.Entry<String, String> e : found.entrySet()) {
            out.add(new SessionArtifactParser.ParsedArtifact(e.getValue(), null, e.getKey(), null));
        }
        return out;
    }

    /**
     * 递归收集 {@code {title, url}} 形状的节点。
     *
     * <p>刻意做成**形状匹配**而不是按固定路径取：WebSearch 的 hits 嵌在
     * {@code results[].content[]}，WebFetch 的 url 在顶层 —— 两处形状不同，
     * 但「同一个对象里既有 url 又有 title」是它们的共同点。
     */
    private void collectUrlPairs(JsonNode node, Map<String, String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            String url = firstTextual(node, "url");
            if (url != null && isHttpUrl(url)) {
                String title = firstTextual(node, "title");
                out.put(url, title != null && !title.isBlank() ? title : url);
            }
            node.elements().forEachRemaining(child -> collectUrlPairs(child, out));
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectUrlPairs(child, out));
        }
    }

    /** 取对象里第一个命中的文本字段。 */
    private static String firstTextual(JsonNode obj, String key) {
        JsonNode v = obj.get(key);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
    }

    /** 只收 http(s) 绝对地址（内部跳转 / 相对路径用户点不开）。 */
    private static boolean isHttpUrl(String url) {
        String u = url.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /** 逐条入库（复用 saveSource 的清洗与幂等）。 */
    private int saveExtractedSources(String sessionId, List<SessionArtifactParser.ParsedArtifact> items,
                                     String now, String sourceMessageId) {
        int n = 0;
        for (SessionArtifactParser.ParsedArtifact item : items) {
            if (saveSource(sessionId, item, now, sourceMessageId)) {
                n++;
            }
        }
        return n;
    }

    private void cleanupReplacedCopy(String sessionId, String existingId,
                                     String oldLocalPath, String newLocalPath) {
        if (oldLocalPath == null || oldLocalPath.isBlank() || oldLocalPath.equals(newLocalPath)) {
            return;
        }
        try {
            Path old = Paths.get(oldLocalPath).toAbsolutePath().normalize();
            if (!old.startsWith(artifactsRoot()) || !old.startsWith(sessionArtifactsDir(sessionId))) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 旧路径不在本会话归档目录内（用户原文件），不删 session={} old={}",
                            sessionId, old);
                }
                return;
            }
            // 同一副本可能被同会话另一行引用（两行指向同一原文件 ⇒ 同一 hash+basename ⇒ 同一副本）。
            // 仍被引用则不删，否则会把另一行的可点路径变成死链。
            if (artifactMapper != null) {
                long referenced = artifactMapper.selectCountByQuery(QueryWrapper.create()
                        .eq("session_id", sessionId)
                        .eq("local_path", oldLocalPath)
                        .ne("id", existingId));
                if (referenced > 0) {
                    if (log.isDebugEnabled()) {
                        log.debug("[artifact-extract] 旧副本仍被其它行引用（{} 行），不删 session={} old={}",
                                referenced, sessionId, old);
                    }
                    return;
                }
            }
            boolean deleted = Files.deleteIfExists(old);
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] 清理被替换的旧副本 session={} old={} deleted={}",
                        sessionId, old, deleted);
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] 清理旧副本失败（忽略） session={} old={}: {}",
                        sessionId, oldLocalPath, e.toString());
            }
        }
    }

    /**
     * 删除该会话的全部产物/来源<b>行</b>（DB 侧）。
     *
     * <p><b>WHY 必须在应用层删</b>：V205 建表<b>没有</b> {@code session_id} 外键级联，而
     * {@code SessionService.delete} 只显式删 messages / session_files —— 不补这一步，删会话后
     * 本表会永久留下孤儿行（前端按 sessionId 查仍正常，所以不会报错，只是白占库、且永远无法回收）。
     * ⛔ 不改迁移（V205 字节已冻结），故由应用层显式删除。
     *
     * <p>⛔ 本方法<b>不抛异常</b>：删除会话是主流程，旁路清理失败只 WARN。
     *
     * @param sessionId 会话 id（null/空 / mapper 未装配 → 0）
     * @return 实际删除条数（失败 0）
     */
    public int deleteRows(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        if (artifactMapper == null) {
            log.warn("[artifact-extract] SessionArtifactMapper 未装配，跳过删除产物行 session={}", sessionId);
            return 0;
        }
        try {
            int deleted = artifactMapper.deleteByQuery(QueryWrapper.create().eq("session_id", sessionId));
            log.info("[artifact-extract] 删除会话产物行 session={} 条数={}", sessionId, deleted);
            return deleted;
        } catch (Exception e) {
            log.warn("[artifact-extract] 删除会话产物行失败（忽略，不影响会话删除） session={}: {}",
                    sessionId, e.toString());
            return 0;
        }
    }

    /**
     * 会话删除时清理该会话的归档目录（{@code {artifactsRoot}/{sessionId}}），随后在根目录为空时删掉根。
     *
     * <p>WHY：归档是「本应用自己的副本」，会话没了就没有任何入口能再访问它们 —— 不清理会随会话数
     * 无界占用用户磁盘（与 {@code AttachmentStoreBase.cleanupSession} 同一口径）。
     *
     * <p>⛔ 本方法<b>永不抛出</b>：全部异常只 WARN（删除会话是主流程，不能被旁路清理拖垮）。
     *
     * @param sessionId 会话 id（空白 → no-op）
     */
    public void purgeSessionFiles(String sessionId) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            Path root = artifactsRoot();
            Path dir = sessionArtifactsDir(sessionId);
            if (!dir.startsWith(root) || dir.equals(root)) {
                log.warn("[artifact-extract] 拒绝清理：目录不在归档根内 session={} dir={}", sessionId, dir);
                return;
            }
            if (!Files.exists(dir)) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 会话无归档目录，跳过清理 session={} dir={}", sessionId, dir);
                }
                return;
            }
            boolean ok = deleteTreeQuietly(dir);
            log.info("[artifact-extract] 清理会话归档目录 session={} dir={} ok={}", sessionId, dir, ok);
            // 根目录空了就一并删掉（失败/非空都忽略）
            try (Stream<Path> remaining = Files.list(root)) {
                if (remaining.findAny().isEmpty()) {
                    Files.deleteIfExists(root);
                    if (log.isDebugEnabled()) {
                        log.debug("[artifact-extract] 归档根已空，删除 {}", root);
                    }
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 归档根判空/删除失败（忽略） root={}: {}", root, e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("[artifact-extract] 清理会话归档目录失败（忽略） session={}: {}", sessionId, e.toString());
        }
    }

    /** 递归删除（先深后浅）；单个文件失败只 debug，返回整体是否全部成功。 */
    private static boolean deleteTreeQuietly(Path dir) {
        boolean ok = true;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    ok = false;
                    log.warn("[artifact-extract] 删除归档文件失败（忽略） path={}: {}", path, e.toString());
                }
            }
        } catch (Exception e) {
            ok = false;
            log.warn("[artifact-extract] 遍历归档目录失败（忽略） dir={}: {}", dir, e.toString());
        }
        return ok;
    }

    // ───────────────────────── 模型 / 路径解析 ─────────────────────────


    /**
     * 会话工作目录（相对路径的解析基准）。
     *
     * <p>复用 {@link CwdResolution#getCwd(String)}（仓库唯一 pwd/getCwd 语义入口：sessionCwd →
     * boundProject → DB 回源），⛔ 不自己拼 project.path。
     * 解析不出（会话未绑定项目 / 数据链路异常）→ null（该轮相对路径产物全部丢弃，绝对路径照常）。
     */
    private String resolveSessionCwd(String sessionId) {
        try {
            return CwdResolution.getCwd(sessionId);
        } catch (RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] 会话工作目录解析失败，相对路径产物将丢弃 session={}: {}",
                        sessionId, e.toString());
            }
            return null;
        }
    }

    /**
     * 路径 → 绝对 Path（不校验存在性，存在性由调用方判）。
     *
     * @param rawPath 模型给出的路径（绝对 / 相对 / ~ 开头）
     * @param cwd     会话工作目录（相对路径基准；null = 相对路径无法解析）
     * @return 绝对路径；无法解析 → null
     */
    private static Path resolveFile(String rawPath, String cwd) {
        try {
            String candidate = expandHome(rawPath.trim());
            Path path = Paths.get(candidate);
            if (!path.isAbsolute()) {
                if (cwd == null || cwd.isBlank()) {
                    return null;
                }
                path = Paths.get(cwd).resolve(path);
            }
            return path.normalize().toAbsolutePath();
        } catch (InvalidPathException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 展开 {@code ~} 前缀（模型常给出 {@code ~/xxx}；Windows 下 user.home 同样适用）。 */
    private static String expandHome(String path) {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return path;
        }
        if (path.equals("~")) {
            return home;
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return home + path.substring(1);
        }
        return path;
    }

    /** trim + 截断 + 空转 null。 */
    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    // ───────────────────────── 杂项 ─────────────────────────

    /** 防抖表自救清理：条目过多时丢掉过期项（不引入额外定时器）。 */
    private void pruneDebounceIfNeeded(long now) {
        if (lastRun.size() <= DEBOUNCE_PRUNE_SIZE) {
            return;
        }
        Map<String, Long> survivors = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : lastRun.entrySet()) {
            if (now - e.getValue() < DEBOUNCE_PRUNE_AGE_MS) {
                survivors.put(e.getKey(), e.getValue());
            }
        }
        lastRun.clear();
        lastRun.putAll(survivors);
        if (log.isDebugEnabled()) {
            log.debug("[artifact-extract] 防抖表清理完成，剩余 {} 个会话", lastRun.size());
        }
    }

    /** 容器关闭时收掉线程池（daemon 线程不会阻止 JVM 退出，这里只是及时释放）。 */
    @PreDestroy
    public void shutdown() {
        for (TurnWatch w : turnWatches.values()) {
            w.future().cancel(false);
        }
        turnWatches.clear();
        turnWatchScheduler.shutdownNow();
        executor.shutdownNow();
    }
}
