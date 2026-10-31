package com.nexusai.application.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.UsageCategory;
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

    /** 弱档（小快）模型兜底名 · 与 ChatService 标题生成同源（resolveFastModelName 三极回落）。 */
    private static final String FAST_MODEL_FALLBACK = "claude-haiku-4-5-20251001";

    /** 抽取输入总长度上限：超限保留<b>尾部</b>（近期消息更相关）。 */
    private static final int MAX_INPUT_CHARS = 60_000;

    /** 工具返回内容每条截断长度（工具输出通常是噪声，只留开头判断用了什么）。 */
    private static final int TOOL_RESULT_MAX_CHARS = 300;

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

    private static final String KIND_DELIVERABLE = "deliverable";
    private static final String KIND_SOURCE = "source";

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

    /** 工具入参里表示「文件路径」的键（Write/Edit/Read 等工具的实际字段名集合）。 */
    private static final String[] PATH_ARG_KEYS = {
            "file_path", "path", "notebook_path", "filePath",
            "target_file", "output_path", "outputPath", "absolute_path"
    };

    /**
     * 抽取提示词（只输出 JSON）。规则写死在提示词里而不是后处理里：
     * 「什么算产物/来源」是判断问题（规则五：交给模型），后处理只做确定性清洗。
     */
    private static final String SYSTEM_PROMPT = """
            你是「会话产物 / 来源」抽取器。给你一段会话历史（用户、助手、工具调用摘要），
            请从中抽取两类条目，只输出一个 JSON 对象，不要任何解释、不要 Markdown 围栏。

            输出结构（缺的字段用 null，不得编造）：
            {"deliverables":[{"title":"...","path":"...","summary":"..."}],
             "sources":[{"title":"...","url":"...","summary":"..."}]}

            deliverables（产物）—— 只收「用户最终要的结果文件」：
            - 例如：报告、文档、设计稿、演示文稿、导出的表格、生成的 html / 图片 / PDF 等交付物。
            - path 必须是该文件在磁盘上的路径（绝对路径优先；相对路径也可以），
              没有路径的条目不要收（用户点不开）。
            - ⛔ 排除：源码改动（改了哪些 .java / .ts / .tsx / .css 等）、临时脚本、测试文件、
              构建中间产物、日志文件。
            - 同一交付物的多个版本只保留最终版（给最终路径，一条即可）。

            sources（来源）—— 只收「外部引用」：
            - 例如：用户提供的 URL、网页搜索结果、知识库/文档链接、被用户 @ 引用的文件。
            - url 必填（展示形态只支持链接）：网页链接原样写进 url；
              被 @ 引用的本地文件把其绝对路径写进 url（这是唯一可存的引用位置）。
            - ⛔ 排除：助手自己生成或拼出来的链接、没有实际访问的链接、内部工具跳转地址。

            规则：
            - 宁缺毋滥：不确定的整条不要。
            - title 必填、简短（≤60 字）；summary 一句话（≤100 字）说明它是什么。
            - 只输出 JSON。
            """;

    @Autowired private SessionArtifactMapper artifactMapper;

    /** 会话消息读取（全量内部通道）· required=false 容错直接构造的单测。 */
    @Autowired(required = false) private MessageService messageService;

    /** 旁路 LLM 单发 + 记账 · required=false：缺失时抽取整体跳过（WARN，不静默成功）。 */
    @Autowired(required = false) private LlmSideCallClient sideCallClient;

    @Autowired(required = false) private ModelConfigResolver modelConfigResolver;

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
        List<SessionArtifactDto> deliverables = new ArrayList<>();
        List<SessionArtifactDto> sources = new ArrayList<>();
        if (rows != null) {
            for (SessionArtifactRecord row : rows) {
                if (KIND_DELIVERABLE.equals(row.getKind())) {
                    deliverables.add(SessionArtifactDto.from(row));
                } else if (KIND_SOURCE.equals(row.getKind())) {
                    sources.add(SessionArtifactDto.from(row));
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
    public void scheduleExtract(String sessionId) {
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
                    extractNow(sessionId);
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
     * 同步执行一次抽取（测试 / 手动补跑用；生产只经 {@link #scheduleExtract(String)}）。
     *
     * <p>⛔ 同样<b>不抛异常</b>：失败只 WARN —— 让调用方（测试之外）永远不可能因产物抽取影响对话。
     *
     * @param sessionId 会话 id
     */
    public void extractNow(String sessionId) {
        long startedAt = System.currentTimeMillis();
        try {
            if (!extractEnabled || sessionId == null || sessionId.isBlank()) {
                return;
            }
            List<ChatMessageDto> messages = readNonMetaMessages(sessionId);
            if (messages.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 会话无非 meta 消息，跳过 session={}", sessionId);
                }
                return;
            }
            String userPayload = buildExtractionInput(messages);
            if (userPayload.isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[artifact-extract] 抽取输入为空（无可用正文/工具路径），跳过 session={}", sessionId);
                }
                return;
            }
            if (sideCallClient == null) {
                log.warn("[artifact-extract] LlmSideCallClient 未装配，跳过抽取 session={}", sessionId);
                return;
            }
            String modelName = resolveModelName();
            if (modelName == null) {
                log.warn("[artifact-extract] fast 模型未配置（settings fast/weak 与兜底 {} 均不可用），跳过抽取 session={}",
                        FAST_MODEL_FALLBACK, sessionId);
                return;
            }
            ProviderConfig config = resolveConfig(modelName);
            if (config == null || !config.isUsable()) {
                log.warn("[artifact-extract] 模型配置不可用（model={}），跳过抽取 session={}", modelName, sessionId);
                return;
            }
            log.info("[artifact-extract] 开始 session={} 消息数={} 输入字符={} 模型={}",
                    sessionId, messages.size(), userPayload.length(), modelName);

            String raw = sideCallClient.chat(config, modelName, SYSTEM_PROMPT, userPayload, null,
                    LlmSideCallClient.SideCallUsage.inSession(
                            sessionId, null, UsageCategory.SIDE_CALL, "artifact_extract"));

            SessionArtifactParser.ParsedExtraction parsed = SessionArtifactParser.parse(raw);
            if (parsed.deliverables().isEmpty() && parsed.sources().isEmpty()) {
                log.info("[artifact-extract] 模型未返回任何条目 session={} 耗时={}ms",
                        sessionId, System.currentTimeMillis() - startedAt);
                return;
            }

            String cwd = resolveSessionCwd(sessionId);
            String now = OffsetDateTime.now().toString();
            int saved = 0;
            int dropped = 0;
            for (SessionArtifactParser.ParsedArtifact item : parsed.deliverables()) {
                if (!saveDeliverable(sessionId, item, cwd, now)) {
                    dropped++;
                } else {
                    saved++;
                }
            }
            for (SessionArtifactParser.ParsedArtifact item : parsed.sources()) {
                if (!saveSource(sessionId, item, now)) {
                    dropped++;
                } else {
                    saved++;
                }
            }
            log.info("[artifact-extract] 完成 session={} 模型产物={} 模型来源={} 入库={} 丢弃={} 耗时={}ms",
                    sessionId, parsed.deliverables().size(), parsed.sources().size(),
                    saved, dropped, System.currentTimeMillis() - startedAt);
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

    /**
     * 拼抽取输入：role + 正文 + 工具名与<b>参数里的文件路径</b>；工具返回截断到 300 字。
     *
     * <p>⛔ 不拼工具入参原文：Write/Edit 的 arguments 里是整个文件内容，拼进来会把 6 万字符预算
     * 全部吃掉，而模型真正需要的是「写了哪个文件」——只取路径键。
     */
    private String buildExtractionInput(List<ChatMessageDto> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessageDto m : messages) {
            String role = m.role() == null ? "unknown" : m.role().name().toLowerCase();
            String content = m.content();
            List<ToolCallDto> toolCalls = m.toolCalls();
            boolean hasTools = toolCalls != null && !toolCalls.isEmpty();
            if ((content == null || content.isBlank()) && !hasTools) {
                continue;
            }
            sb.append('[').append(role).append("] ");
            if (content != null && !content.isBlank()) {
                sb.append("tool".equals(role) ? truncate(content, TOOL_RESULT_MAX_CHARS) : content);
            }
            if (hasTools) {
                for (ToolCallDto tc : toolCalls) {
                    sb.append("\n  tool: ").append(tc.name() == null ? "unknown" : tc.name());
                    String path = extractPathFromArguments(tc.arguments());
                    if (path != null) {
                        sb.append(" path=").append(path);
                    }
                }
            }
            sb.append('\n');
        }
        String text = sb.toString();
        if (text.length() > MAX_INPUT_CHARS) {
            // 超限保留尾部：抽取关心「最终交付了什么」，早期消息相关性低
            text = text.substring(text.length() - MAX_INPUT_CHARS);
        }
        return text;
    }

    /** 从工具入参 JSON 里取第一个已知「路径」键；解析失败 / 无路径 → null。 */
    private static String extractPathFromArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(arguments);
            if (node == null || !node.isObject()) {
                return null;
            }
            for (String key : PATH_ARG_KEYS) {
                JsonNode value = node.get(key);
                if (value != null && value.isTextual()) {
                    String path = value.asText();
                    if (path != null && !path.isBlank()) {
                        return path.trim();
                    }
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] 工具入参不是合法 JSON，跳过路径提取: {}", e.toString());
            }
        }
        return null;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…(已截断)";
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
    private boolean saveDeliverable(String sessionId, SessionArtifactParser.ParsedArtifact item,
                                    String cwd, String now) {
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
        if (archiveEnabled) {
            Path archived = archiveFile(resolved, sessionId, ARCHIVE_MAX_MB);
            if (archived != null) {
                localPath = archived.toString();
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[artifact-extract] 归档开关关闭（nexusai.artifacts.archive-enabled=false），只记录原路径 session={} src={}",
                    sessionId, resolved);
        }
        upsert(sessionId, KIND_DELIVERABLE, title, localPath, null, sizeBytes,
                clip(item.summary(), SUMMARY_MAX), now);
        return true;
    }

    /** 来源清洗 + 入库：url 为空整条丢弃（展示形态只支持链接）。 */
    private boolean saveSource(String sessionId, SessionArtifactParser.ParsedArtifact item, String now) {
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
                clip(item.summary(), SUMMARY_MAX), now);
        return true;
    }

    /**
     * 幂等写入：按 (session_id, kind, title) 查已有行 → 更新；否则插入。
     *
     * <p>「同一交付物多版本只留最终版」由唯一索引 uk_session_artifact + 这里先查后写共同保证；
     * 两次并发抽取撞同一个键时，唯一索引会让后写者报错 → 捕获后 WARN（下一轮会覆盖成最终值）。
     */
    private void upsert(String sessionId, String kind, String title, String localPath, String url,
                        Long sizeBytes, String summary, String now) {
        try {
            SessionArtifactRecord existing = artifactMapper.selectOneByQuery(
                    QueryWrapper.create()
                            .eq("session_id", sessionId)
                            .eq("kind", kind)
                            .eq("title", title));
            if (existing != null) {
                // 旧副本路径必须在覆盖前抓下来：新副本名带「路径 hash」，路径不变时同名覆盖，
                // 路径变了（用户换了文件）则旧副本成为孤儿 —— 见下方 cleanupReplacedCopy。
                String oldLocalPath = existing.getLocalPath();
                String existingId = existing.getId();
                existing.setLocalPath(localPath);
                existing.setUrl(url);
                existing.setSizeBytes(sizeBytes);
                existing.setSummary(summary);
                existing.setUpdatedAt(now);
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
            // 当前抽取链路没有逐条溯源信息 —— 保持 NULL，不伪造归属（V205 列语义）
            row.setSourceMessageId(null);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            artifactMapper.insert(row);
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] UPSERT 新增 session={} kind={} title={} path={} url={}",
                        sessionId, kind, title, localPath, url);
            }
        } catch (Exception e) {
            log.warn("[artifact-extract] 入库失败（跳过该条，不影响其它条目） session={} kind={} title={}: {}",
                    sessionId, kind, title, e.toString());
        }
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
        return Paths.get(NexusaiPaths.getAppConfigHomeDir(), ARTIFACTS_DIR_NAME)
                .toAbsolutePath().normalize();
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

    /** 弱档模型名（与标题生成同一条 resolveFastModelName 链）；未配置 → null。 */
    private String resolveModelName() {
        if (modelConfigResolver == null) {
            return null;
        }
        try {
            String name = modelConfigResolver.resolveFastModelName(FAST_MODEL_FALLBACK);
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException e) {
            log.warn("[artifact-extract] 解析 fast 模型失败: {}", e.toString());
            return null;
        }
    }

    /** DB 模型名 → 真实 provider 配置（密钥解密等由 resolver 内部完成）；失败 → null。 */
    private ProviderConfig resolveConfig(String modelName) {
        try {
            ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(modelName);
            return resolved == null ? null : resolved.config();
        } catch (RuntimeException e) {
            log.warn("[artifact-extract] 解析模型配置失败 model={}: {}", modelName, e.toString());
            return null;
        }
    }

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
        executor.shutdownNow();
    }
}
