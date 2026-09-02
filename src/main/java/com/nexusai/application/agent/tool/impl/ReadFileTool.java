package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ReadPermissionChecker;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.FileReadingLimits;
import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.tool.ImageDimensions;
import com.nexusai.application.agent.tool.FileReadListenerRegistry;
import com.nexusai.application.agent.bash.BashRuleMatcher;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseContext.ReadState;
import com.nexusai.infra.util.ImageResizeError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.function.Predicate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read File 工具 · 对齐 CC {@code FileReadTool.ts}（{@code src/tools/FileReadTool/}，Open-Claude-Code 2.1.88）。
 *
 * <p>生产级特性：
 * <ul>
 *   <li><b>PathGuard</b>：路径逃逸 → {@link SecurityException} → 错误结果（不让 LLM 读到 /etc/passwd）</li>
 *   <li><b>limit 参数</b>：限制返回行数 + 截断提示（避免 LLM 误读超大文件爆 context）</li>
 *   <li><b>offset 参数</b>：1-based 起始行（默认 1；与 CC 对齐）。让 LLM 精准读片段，无需每次读全文件</li>
 *   <li><b>concurrency-safe</b>：只读 → true（可与其它 read 并发）</li>
 * </ul>
 *
 * <p><b>Session L 增强</b>（对齐 CC FileReadTool.ts 完整契约）：
 * <ul>
 *   <li><b>[GAP-A]</b> {@link #isReadOnly} → true</li>
 *   <li><b>[GAP-B]</b> {@link #checkPermissions} → 委托 {@link ReadPermissionChecker}</li>
 *   <li><b>[GAP-C]</b> {@link #validateInput} → 6 步链（pages 格式/超限 → UNC 提前 pass →
 *       binary 扩展名 → BLOCKED_DEVICE_PATHS）</li>
 *   <li><b>[GAP-E]</b> 多类型输出 dispatch（.ipynb → notebook, .png/.jpg → image,
 *       .pdf → pdf/parts（[P-CC-01] pdfbox 完整解析：readPDF document 块 + extractPDFPages
 *       页图提取，对齐 CC FileReadTool.ts:893-1017），其余 → text）+ file_unchanged dedup</li>
 *   <li><b>[GAP-T5]</b> {@link FileReadListenerRegistry} 接线：仅 text 分支成功后通知</li>
 * </ul>
 *
 * <p><b>Session L+ 增强</b>（对齐 CC FileReadTool.ts 完整 dedup 契约）：
 * <ul>
 *   <li><b>[R1]</b> {@code readFileState} 从实例字段上提到 {@link ToolUseContext#readFileState()}
 *       （会话级 + 跨工具共享，对齐 CC {@code QueryEngine.ts:191} + {@code runAgent.ts:377/705}）</li>
 *   <li><b>[R3]</b> dedup 完整对齐 CC {@code FileReadTool.ts:530-575}：
 *       killswitch ({@code tengu_read_dedup_killswitch}) + isPartialView 守卫 +
 *       offset/limit range 严格匹配 + mtimeMs 严格匹配 + stat 失败 fall-through +
 *       {@code file_read_dedup} analytics（Java 端走 slf4j info log）</li>
 * </ul>
 */
@Component
public class ReadFileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int RESULT_SIZE_LIMIT = 100_000;   // 100k 字符 ≈ 25000 tokens (1 token ≈ 4 chars, GAP-D 对齐 CC DEFAULT_MAX_OUTPUT_TOKENS=25000)
    /** [GAP-D 拍板] full read 全文件大小上限 · 对齐 CC MAX_OUTPUT_SIZE = 256KB (limits.ts:65, FileReadTool.ts:1026). */
    private static final int MAX_SIZE_BYTES = 256 * 1024;
    // [OPD-D1-01] 上述两个常量现仅作默认值文档（= FileReadingLimits.DEFAULT_MAX_OUTPUT_TOKENS*4 /
    //   DEFAULT_MAX_SIZE_BYTES）：生效上限已改由 executeInternal 的 FileReadingLimits.resolve（env/GB）+
    //   ctx.fileReadingLimits() override 解析（CC FileReadTool.ts:502-516），不再直读本常量。
    /** 默认读取行数上限 · CC original: MAX_LINES_TO_READ = 2000（FileReadTool/prompt.ts:11，prompt 模板引用）。 */
    private static final int MAX_LINES_TO_READ = 2000;
    /** 阻塞型设备路径清单 · 对齐 CC FileReadTool.ts:98-115. {@code /dev/null} 故意不在此列. */
    private static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
        "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
        "/dev/stdin", "/dev/tty", "/dev/console",
        "/dev/stdout", "/dev/stderr",
        "/dev/fd/0", "/dev/fd/1", "/dev/fd/2"
    );

    /**
     * 二进制扩展名（CC BINARY_EXTENSIONS 删掉 pdf + 5 种图片扩展名后剩余部分，
     * 对齐 CC {@code FileReadTool.ts:469-481} 的 hasBinaryExtension 排除逻辑）。
     * 90+ 项的精简版：只取项目内常见的，注释附 CC src/constants/files.ts:5-112 行号
     * 提示完整清单位置。
     */
    private static final Set<String> NON_TEXT_BINARY_EXTENSIONS = Set.of(
        // Images · [G33①] .bmp/.ico/.tiff/.tif 补入拒绝集（CC files.ts:11-14 BINARY_EXTENSIONS 含之，
        //   IMAGE_EXTENSIONS 白名单仅 png/jpg/jpeg/gif/webp —— CC FileReadTool validateInput
        //   hasBinaryExtension 对 .bmp/.ico/.tiff 生效 → 拒绝；旧 Java 注释误列为不在此列）
        ".bmp", ".ico", ".tiff", ".tif",
        // Videos
        ".mp4", ".mov", ".avi", ".mkv", ".webm", ".wmv", ".flv", ".m4v", ".mpeg", ".mpg",
        // Audio
        ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a", ".wma", ".aiff", ".opus",
        // Archives
        ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar", ".xz", ".z", ".tgz", ".iso",
        // Executables/binaries
        ".exe", ".dll", ".so", ".dylib", ".bin", ".o", ".a", ".obj", ".lib", ".app",
        ".msi", ".deb", ".rpm",
        // Documents (excluding pdf which is allowed)
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".odt", ".ods", ".odp",
        // Fonts
        ".ttf", ".otf", ".woff", ".woff2", ".eot",
        // Bytecode / VM
        ".pyc", ".pyo", ".class", ".jar", ".war", ".ear", ".node", ".wasm", ".rlib",
        // Database
        ".sqlite", ".sqlite3", ".db", ".mdb", ".idx",
        // Design / 3D
        ".psd", ".ai", ".eps", ".sketch", ".fig", ".xd", ".blend", ".3ds", ".max",
        // Flash
        ".swf", ".fla",
        // Lock / profiling
        ".lockb", ".dat", ".data"
        // 注: .pdf 与 5 种图片扩展名（.png/.jpg/.jpeg/.gif/.webp）不在此列——
        //   pdf 由 validateInput 排除（ext.equals(".pdf")）；5 种图片走 IMAGE_EXTENSIONS 白名单。
        //   [G33①] .bmp/.ico/.tiff/.tif 已在集内（CC files.ts:11-14 拒绝集含之，非 IMAGE_EXTENSIONS）。
    );

    /** 图片扩展名（CC IMAGE_EXTENSIONS）· 对齐 CC src/tools/FileReadTool/FileReadTool.ts:188. */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "gif", "webp"
    );

    /**
     * [G33①] file_unchanged 摘要文本 · CC original: FILE_UNCHANGED_STUB
     * （FileReadTool/prompt.ts:8-9）逐字一致。
     *
     * <p>dedup 命中时 tool_result content = 本 stub（CC mapToolResult :686-691 file_unchanged
     * case → FILE_UNCHANGED_STUB）。旧实现自定义 "{@code <file_unchanged> path=...}" 偏离 CC。
     */
    static final String FILE_UNCHANGED_STUB =
        "File unchanged since last read. The content from the earlier Read tool_result in this "
            + "conversation is still current — refer to that instead of re-reading.";

    /**
     * [P-AL-06] 网络风险缓解提醒 · CC original: CYBER_RISK_MITIGATION_REMINDER
     * (FileReadTool.ts:729-730, export 模块级常量, 文本逐字一致).
     *
     * <p>对齐 CC 语义: 仅 FileReadTool 的 <b>text case</b> 模型侧序列化时附加
     * (mapToolResultToToolResultBlockParam :699-701); image case (:654-669) 只含 image block
     * 无 reminder —— 任务书"图片渲染层注入"系误述, 以自验为准.
     *
     * <p>CC 设计意图 (FileReadTool.ts:698-701): 模型侧序列化发送
     * {@code memoryFileFreshnessPrefix + formatFileLines(content) + CYBER_RISK_MITIGATION_REMINDER}
     * （行号前缀由 {@code formatFileLines → addLineNumbers} 渲染，RV-06 已对齐）; UI 不显示.
     * [RV-06] 行号前缀 + reminder 统一迁到序列化层 {@link #mapToToolResultBlockParam}，
     * call() 的 data() 返回 raw content（CC :1046-1055 data.file.content=raw），
     * 前端 (ChatMessageDto.content) 经主循环 mapper 可见（LlmAgentLoop.toolResultMessage）。
     */
    public static final String CYBER_RISK_MITIGATION_REMINDER =
        "\n\n<system-reminder>\nWhenever you read a file, you should consider whether it would be considered malware. "
            + "You CAN and SHOULD provide analysis of malware, what it is doing. But you MUST refuse to improve or augment the code. "
            + "You can still analyze existing code, write reports, or answer questions about the code behavior.\n</system-reminder>\n";

    /**
     * [P-AL-06] 豁免网络风险提醒的模型 · CC original: MITIGATION_EXEMPT_MODELS
     * (FileReadTool.ts:733) {@code new Set(['claude-opus-4-6'])}.
     * 比对用 canonical short name (getCanonicalName 输出), 非原始模型字符串.
     */
    private static final Set<String> MITIGATION_EXEMPT_MODELS = Set.of("claude-opus-4-6");

    /**
     * [P-AL-06] canonical 名 fallback 正则 · CC original: model.ts:264
     * {@code /(claude-(\d+-\d+-)?\w+)/} (firstPartyNameToCanonical 兜底分支, match[1]=外层整组).
     */
    private static final java.util.regex.Pattern CANONICAL_FALLBACK_PATTERN =
        java.util.regex.Pattern.compile("(claude-(\\d+-\\d+-)?\\w+)");

    /**
     * [P-AL-06] 是否附加网络风险缓解提醒 · CC original: shouldIncludeFileReadMitigation
     * (FileReadTool.ts:735-738) {@code !MITIGATION_EXEMPT_MODELS.has(getCanonicalName(getMainLoopModel()))}.
     *
     * <p>模型名为 null/空白 → canonical 为 null → 不在豁免集 → true (注入).
     * 等价 CC: getMainLoopModel() 恒有值 (默认 Sonnet 4.6) 且默认模型非豁免.
     *
     * @param mainLoopModel 当前主循环模型名 (可 null; Java appStateRef 语义, 见
     *                      {@link #resolveMainLoopModel})
     * @return true = 附加 reminder (非豁免模型)
     */
    static boolean shouldIncludeFileReadMitigation(String mainLoopModel) {
        // canonical==null → 不在豁免集 → true (注入)。显式短路: Java Set.of 不可变集合
        // contains(null) 抛 NPE, 而 CC Set.has(undefined) 返回 false (JS 语义)。
        String canonical = canonicalModelName(mainLoopModel);
        return canonical == null || !MITIGATION_EXEMPT_MODELS.contains(canonical);
    }

    /**
     * [P-AL-06] 模型名 → canonical short name · CC original: firstPartyNameToCanonical
     * (model.ts:217-270) + getCanonicalName (:279-282, resolveOverriddenModel 环节 Java N/A ——
     * Java 无 settings.modelOverrides 概念 (全仓 grep 0 命中), 恒原样, 已登记).
     *
     * <p>纯字符串匹配 (lowercase + includes), 剥离日期/provider 后缀:
     * 'claude-opus-4-6-20250805' / 'us.anthropic.claude-opus-4-6-v1:0' → 'claude-opus-4-6'.
     * 顺序与 CC 一致 (更具体版本在前: 4-6/4-5/4-1 先于 4); 兜底正则 + 原样返回.
     *
     * @param modelName 完整模型名 (可 null/空白)
     * @return canonical short name; null/空白 → null
     */
    static String canonicalModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        String name = modelName.toLowerCase();
        if (name.contains("claude-opus-4-6")) return "claude-opus-4-6";
        if (name.contains("claude-opus-4-5")) return "claude-opus-4-5";
        if (name.contains("claude-opus-4-1")) return "claude-opus-4-1";
        if (name.contains("claude-opus-4")) return "claude-opus-4";
        if (name.contains("claude-sonnet-4-6")) return "claude-sonnet-4-6";
        if (name.contains("claude-sonnet-4-5")) return "claude-sonnet-4-5";
        if (name.contains("claude-sonnet-4")) return "claude-sonnet-4";
        if (name.contains("claude-haiku-4-5")) return "claude-haiku-4-5";
        if (name.contains("claude-3-7-sonnet")) return "claude-3-7-sonnet";
        if (name.contains("claude-3-5-sonnet")) return "claude-3-5-sonnet";
        if (name.contains("claude-3-5-haiku")) return "claude-3-5-haiku";
        if (name.contains("claude-3-opus")) return "claude-3-opus";
        if (name.contains("claude-3-sonnet")) return "claude-3-sonnet";
        if (name.contains("claude-3-haiku")) return "claude-3-haiku";
        java.util.regex.Matcher m = CANONICAL_FALLBACK_PATTERN.matcher(name);
        if (m.find()) {
            return m.group(1);
        }
        return name;
    }

    private final PathGuard guard;
    private final FileReadListenerRegistry listenerRegistry;
    private final ReadPermissionChecker permissionChecker;

    /**
     * [L+ R3] dedup killswitch · 对齐 CC {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_read_dedup_killswitch', false)}.
     * 3P default = killswitch off = dedup enabled. Java 端走 Spring @Value 注入, 默认 true.
     * 字段默认值设 true: Spring @Value 注入会覆盖 (生产路径), 非 Spring 测试 (直接 new) 走默认值.
     * GB 可在 yml / -D 系统属性关闭.
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.read-dedup.enabled:true}")
    private boolean dedupEnabled = true;

    /**
     * [RV-06] compact 行号前缀开关 · CC original: isCompactLinePrefixEnabled()
     * (utils/file.ts:278-285) {@code !getFeatureValue_CACHED_MAY_BE_STALE('tengu_compact_line_prefix_killswitch', false)}.
     *
     * <p>3P default = killswitch off = compact 格式启用（{@code N\t} 前缀）；killswitch on 走
     * padded-arrow（{@code "     N→"}，padStart(6,' ')+'→'，file.ts:310-317）。Java 端走 Spring
     * {@code @Value} 注入，默认 true（同 dedupEnabled 模式）：生产路径 Spring 注入覆盖，
     * 非 Spring 测试（直接 new）走字段默认 true。GB 可在 yml / -D 关闭切换 padded-arrow
     * 兜底格式（对齐既有 dedupEnabled 正向 flag 约定）。
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.compact-line-prefix.enabled:true}")
    private boolean compactLinePrefixEnabled = true;

    /**
     * [OPD-D1-01] 遥测通道 · 对齐 CC logEvent('tengu_file_read_limits_override', ...)
     * （FileReadTool.ts:511-516）。@Autowired(required=false)：无 bean 时跳过（POJO 测试不破，
     * 同 BashTool.analyticsTracker 短路语义）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AnalyticsTracker analyticsTracker;

    /** [OPD-D1-01] 遥测通道注入（非 Spring 场景 / 测试）。 */
    public void setAnalyticsTracker(AnalyticsTracker analyticsTracker) {
        this.analyticsTracker = analyticsTracker;
        if (log.isDebugEnabled()) {
            log.debug("[ReadFileTool] [OPD-D1-01] analyticsTracker 注入={}（CC tengu_file_read_limits_override）",
                analyticsTracker == null ? "null" : "wired");
        }
    }

    /**
     * [OPD-D1-01] GB tengu_amber_wren 模拟 · maxTokens / maxSizeBytes · 对齐 CC limits.ts:53-92
     * {@code getDefaultFileReadingLimits()}：maxTokens = env(CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS) &gt; GB &gt; DEFAULT；
     * maxSizeBytes = GB &gt; DEFAULT。
     *
     * <p>@Value 注入空串默认 → 未设置 = null（走 DEFAULT）；非 Spring 测试（直接 new）字段为 null。
     * 解析见 {@link #parsePositiveInt}（对齐 CC defensive 校验：非法/&lt;=0 → 忽略）。
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.file-reading-limits.max-tokens:}")
    private String gbFileReadingLimitsMaxTokensRaw;
    @org.springframework.beans.factory.annotation.Value("${nexusai.file-reading-limits.max-size-bytes:}")
    private String gbFileReadingLimitsMaxSizeBytesRaw;

    /** GB maxTokens · 空/非法/&lt;=0 → null（对齐 CC limits.ts:67-74 GB 校验: Number.isFinite && >0）。 */
    private Integer gbFileReadingLimitsMaxTokens() {
        return parsePositiveInt(gbFileReadingLimitsMaxTokensRaw);
    }

    /** GB maxSizeBytes · 空/非法/&lt;=0 → null（对齐 CC limits.ts:60-65: Number.isFinite && >0）。 */
    private Integer gbFileReadingLimitsMaxSizeBytes() {
        return parsePositiveInt(gbFileReadingLimitsMaxSizeBytesRaw);
    }

    /** 解析正整数 · 对齐 CC getEnvMaxTokens (limits.ts:24-33) parseInt + isNaN + >0 防御。 */
    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // P1-2: 动态技能管理器 · 对齐 CC FileReadTool.ts:579-590。@Autowired(required=false)：
    //   无 bean 时跳过（POJO 测试不破）。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.skill.DynamicSkillsManager dynamicSkillsManager;
    public void setDynamicSkillsManager(com.nexusai.application.agent.skill.DynamicSkillsManager m) {
        this.dynamicSkillsManager = m;
    }

    // ── IMP-M-P1-2 (DEL-M-36 接线): auto-memory 文件新鲜度标记 · 对齐 CC FileReadTool.ts:747-753 + :1056-1058 ──
    // memoryFileMtimes WeakMap（data 对象身份）→ memoryFileFreshnessPrefix(data)（mapToolResultToToolResultBlockParam :697）。
    // Java 端用两字段: memoryFileDetection.isAutoMemFile(path) 判定 + memoryAge.memoryFreshnessNote(mtime) 前缀。
    // 前缀只进展示的 ToolResult，不入 readFileState（readFileState 存纯内容供 Edit/Write stale-write 比对）。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.memory.MemoryAge memoryAge;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.memory.MemoryFileDetection memoryFileDetection;
    public void setMemoryAge(com.nexusai.application.agent.memory.MemoryAge memoryAge) {
        this.memoryAge = memoryAge;
    }
    public void setMemoryFileDetection(com.nexusai.application.agent.memory.MemoryFileDetection memoryFileDetection) {
        this.memoryFileDetection = memoryFileDetection;
    }

    // ── [pdf-vision-align] 模型能力 / 图片缓存注入 · 字段注入（不碰既有 3 参构造 :365-390，避免破坏测试便捷构造）──
    /** 模型 mapper · dispatchPdfFull 文本模型页图注册判定用（null → PdfSupport.isPDFSupported 回落 1 参 CC 契约）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelMapper modelMapper;
    /** 提供商 mapper · 模型名解析（null → ModelNameResolver 按 name 兼容路径）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProviderMapper providerMapper;
    /** 图片附件缓存 · 文本模型 PDF 页图注册目标（null → dispatchPdfFull 回落 CC error 文案）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ImageAttachmentStore imageAttachmentStore;

    /** 测试 / 非 Spring 场景注入 ModelMapper（同 PdfAttachmentProcessor.setPdfAttachmentStore 模式）。 */
    public void setModelMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    /** 测试 / 非 Spring 场景注入 ProviderMapper。 */
    public void setProviderMapper(ProviderMapper providerMapper) {
        this.providerMapper = providerMapper;
    }

    /** 测试 / 非 Spring 场景注入 ImageAttachmentStore（文本模型 PDF 页图注册）。 */
    public void setImageAttachmentStore(ImageAttachmentStore imageAttachmentStore) {
        this.imageAttachmentStore = imageAttachmentStore;
    }

    /**
     * [L+ R1 收尾] dedup 缓存已彻底上提到 {@link ToolUseContext#readFileState()} 字段,
     * 会话级 + 跨工具共享（ReadFileTool / EditFileTool / WriteFileTool 都能读写同一 cache）,
     * 对齐 CC {@code QueryEngine.ts:191} + {@code runAgent.ts:377/705/828} +
     * {@code FileEditTool.ts:520} + {@code BashTool.tsx:404}.
     *
     * <p><b>WHY 移除实例级 fallback 字段（拒绝 fallback 半成品）</b>:
     * 早期 R1 半成品曾保留一个 {@code Map<String,ReadState>} 实例字段供 ctx==null 路径使用。
     * 该路径有 3 个问题：(1) 单例工具多 session 共享 → 跨 session dedup 误判;
     * (2) 无 ctx 调用方（测试直调等）仅做单次读, 根本不需要 dedup;
     * (3) CC 端无任何 fallback, 只有 ctx 路径。结论: ctx==null 时完全跳过 dedup
     * (既不读也不写), 只损失一次优化, 不产生正确性事故。
     *
     * <p><b>ctx==null 语义</b>: execute(call) (无 ctx 重载) → 完全跳过 dedup,
     * 每次都 full read; execute(call, ctx) → 走 ctx.readFileState() 共享 dedup。
     * 现有无 ctx 调用方不变, 行为仅由"会 dedup"退化为"总 full read",
     * 正确性等价, 仅多一次磁盘 IO。
     */

    @Autowired
    public ReadFileTool(PathGuard guard,
                        FileReadListenerRegistry listenerRegistry,
                        ReadPermissionChecker permissionChecker) {
        if (guard == null) throw new IllegalArgumentException("guard is null");
        this.guard = guard;
        this.listenerRegistry = listenerRegistry;
        this.permissionChecker = permissionChecker;
        if (log.isInfoEnabled()) {
            log.info("[ReadFileTool] 初始化完成，listenerRegistry={} permissionChecker={}",
                listenerRegistry == null ? "null" : "wired",
                permissionChecker == null ? "null" : "wired");
        }
    }

    /** 测试便捷构造器（保留旧 API，向后兼容无 ctx 直调用例）。 */
    public ReadFileTool(PathGuard guard) {
        this(guard, null, null);
    }

    /** 测试便捷构造器：可注入 listenerRegistry（permissionChecker=null → checkPermissions 调用期 fail-loud ISE，Session M.4.4 收尾）。 */
    public ReadFileTool(PathGuard guard, FileReadListenerRegistry listenerRegistry) {
        this(guard, listenerRegistry, null);
    }

    @Override
    public String name() { return "Read"; }

    // [IMP-C3 删除] 旧 snake_case 'read_file' alias 已删除（DC-A2-01/TR-D1-⊕-1）：
    // CC FileReadTool.ts:338 name='Read'（FILE_READ_TOOL_NAME=prompt.ts:5），真源无 aliases 声明，
    // 全仓 read_file 仅 MCP 工具名前缀（classifyForCollapse.ts:334）。未上线可破约（决策清单 组2-2）。
    // 不保留兼容壳：aliases() 继承 Tool 基类默认 List.of()（空）。

    @Override
    public String description() {
        return "Read the contents of a file. Path is resolved relative to the workspace root; " +
               "paths escaping the workspace are rejected. Returns the file content (UTF-8). " +
               "Optional 'offset' (1-based start line, default 1) and 'limit' (max lines) parameters " +
               "let callers read a precise portion of the file instead of the whole content.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        // CC FileReadTool.ts:228 z.strictObject → additionalProperties:false（未知键拒绝，
        //   由 ToolInputValidator:230-232 跟随广告层 UNSPECIFIED 策略逐键拒绝）
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");

        ObjectNode filePath = JSON.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "The absolute path to the file to read (K-4 对齐 CC FileReadTool.ts:229 file_path)");
        properties.set("file_path", filePath);

        ObjectNode offset = JSON.createObjectNode();
        offset.put("type", "integer");
        // [G12] 对齐 CC FileReadTool.ts:230 `semanticNumber(z.number().int().nonnegative().optional())`
        //   —— nonnegative（0 合法）。CC call :1020 `offset === 0 ? 0 : offset - 1` 显式放行 offset=0。
        offset.put("minimum", 0);
        offset.put("description", "1-based start line (default 1). Combined with 'limit' this reads " +
            "a precise range of the file without loading the whole content.");
        properties.set("offset", offset);

        ObjectNode limit = JSON.createObjectNode();
        limit.put("type", "integer");
        limit.put("description", "Maximum number of lines to return starting at 'offset'. " +
            "If the file is longer, an error is returned; use offset and limit for larger files (GAP-D 对齐 CC :233-234).");
        properties.set("limit", limit);

        // [P-CC-01] pages 字段恢复声明 · 对齐 CC FileReadTool.ts:236-241
        //   WHY: GAP-D 方案 B-变体（A-lite error 时删声明防误导）已随 PDF 解析实现作废。
        //   PDF 解析（pdfbox）已完整实现 readPDF/extractPDFPages（用户 2026-08-05 拍板
        //   「严格对齐 CC，许可添加 POM」）；schema 恢复声明 pages，LLM 可传分页参数。
        ObjectNode pages = JSON.createObjectNode();
        pages.put("type", "string");
        pages.put("description",
            "Page range for PDF files (e.g., \"1-5\", \"3\", \"10-20\"). Only applicable to PDF files. Maximum "
                + PdfSupport.PDF_MAX_PAGES_PER_READ + " pages per request.");
        properties.set("pages", pages);

        ArrayNode required = JSON.createArrayNode();
        required.add("file_path");
        root.set("required", required);
        return root;
    }

    /**
     * 路径扩展点 · CC original: {@code getPath({file_path}) → file_path || getCwd()}
     * （{@code FileReadTool.ts:385}）。
     *
     * <p>权限管线（CC {@code filesystem.ts:1035-1041}）用本方法取本次读取路径做权限检查。
     * Java 端 file_path 为必填（schema required），缺失返回 null → 走 ask（CC getCwd() 兜底
     * 语义差异登记：Java 无 getCwd 概念，缺 file_path 即非法调用）。
     *
     * @param input 工具输入（含 {@code file_path}）
     * @return 本次读取的绝对路径；缺失返回 null（等价 CC getPath 未定义 → ask）
     */
    @Override
    public String getPath(JsonNode input) {
        return input == null ? null : input.path("file_path").asText(null);
    }

    /**
     * [FIX-A backfill-observable] 观察者输入回填 · 对齐 CC {@code FileReadTool.ts:388-393}
     * {@code backfillObservableInput}：{@code if (typeof input.file_path === 'string')
     * input.file_path = expandPath(input.file_path)}。
     *
     * <p>hooks.mdx 约定 file_path 为绝对路径；在 hook/canUseTool 观察前把 {@code file_path}
     * 展开为绝对路径，防 {@code ~}/相对路径绕过 hook allowlist（CC FileReadTool.ts:389-390
     * 注释语义）。Java 端 schema 键与 CC 一致为 {@code file_path}（见 {@link #getPath}）。
     *
     * <p><b>幂等 + 非抛异常</b>（CC Tool.ts:475-484 契约）：绝对路径（展开后不变）或缺
     * 字段 → 返回原引用；null 字节/非法输入 → 返回原引用。调用方
     * {@link com.nexusai.application.agent.permission.InputSanitizer#backfill} 已做防御性
     * deepCopy，原 input 永不被 in-place 改动。
     */
    @Override
    public JsonNode backfillObservableInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            return input;
        }
        JsonNode pathNode = input.get("file_path");
        if (pathNode == null || !pathNode.isTextual()) {
            return input;  // 缺 file_path 字段 → 返回原引用（幂等，CC typeof 非 string 跳过）
        }
        String raw = pathNode.asText();
        String expanded;
        try {
            expanded = PathGuard.expandPath(raw, guard.workdir().toString());
        } catch (IllegalArgumentException e) {
            // null 字节等非法输入 → 返回原引用（backfill 阶段不阻断工具）
            if (log.isDebugEnabled()) {
                log.debug("ReadFileTool.backfillObservableInput: 路径展开失败返回原引用: file_path={} cause={}",
                    raw, e.getMessage());
            }
            return input;
        }
        if (expanded.equals(raw)) {
            return input;  // 已绝对/归一化不变 → 返回原引用（幂等，非抛异常）
        }
        ObjectNode copy = input.deepCopy();
        copy.put("file_path", expanded);
        if (log.isDebugEnabled()) {
            log.debug("ReadFileTool.backfillObservableInput: file_path 绝对化 {} → {} (CC FileReadTool.ts:388-393)",
                raw, expanded);
        }
        return copy;
    }

    /**
     * 权限规则内容匹配器 · CC original: {@code preparePermissionMatcher}
     * （{@code FileReadTool.ts:395}）→ {@code pattern => matchWildcardPattern(pattern, file_path)}。
     *
     * <p>hook if 条件（如 {@code Read(/abs/src/*.java)}）的 ruleContent 与本次 file_path 做
     * 通配匹配（锚定全匹配 + 尾随 {@code " *"} 可选，对齐 CC shellRuleMatching.ts:90-154）。
     *
     * @param input 工具输入（含 {@code file_path}）
     * @return 内容匹配谓词（pattern → boolean）
     */
    @Override
    public Predicate<String> preparePermissionMatcher(JsonNode input) {
        String filePath = input == null ? null : input.path("file_path").asText(null);
        return pattern -> filePath != null && BashRuleMatcher.matchWildcardPattern(pattern, filePath);
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;  // 读操作永远可并发
    }

    /**
     * [GAP-A / Session L] ReadFileTool 是只读操作 · 对齐 CC
     * {@code FileReadTool.ts:376-378} {@code isReadOnly() { return true }}.
     *
     * <p>WHY: 标记只读可让上游 PermissionPipeline 跳过该工具的写权限检查、
     * YoloClassifier 跳过分类等。本类读取 = 文件内容读，无副作用。
     */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /**
     * 自动分类器输入 · 对齐 CC {@code FileReadTool.ts:379-381}
     * {@code toAutoClassifierInput(input) { return input.file_path }}。
     *
     * <p>[OPD-24 G1] 接线：Read 是高安全相关工具，若未 override 会走默认 {@code ''}
     * （CC Tool.ts:767）导致 auto-mode 空串短路 ALLOW（yoloClassifier.ts:411/:1021-1024），
     * 文件读取不被分类 —— G6 阻断安全缺口。本投影让分类器拿到被读路径。
     * Java 端 schema 键与 CC 一致（{@code file_path}，见 {@link #getPath}）。
     *
     * @param input 工具输入（含 {@code file_path}）
     * @return 被读路径；缺失 → {@code ''}
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        String filePath = input == null ? "" : input.path("file_path").asText("");
        if (log.isDebugEnabled()) {
            log.debug("ReadFileTool.toAutoClassifierInput: file_path 投影完成, 长度={} (CC FileReadTool.ts:379 input.file_path)",
                filePath.length());
        }
        return filePath;
    }

    /**
     * [R32-b8 #2] ReadFileTool 是读取操作 · 对齐 CC
     * {@code FileReadTool.ts:382-384} 返回 {@code {isSearch: false, isRead: true}}.
     */
    @Override
    public SearchReadKind searchReadKind(JsonNode input) {
        return SearchReadKind.IS_READ;
    }


    /**
     * CC 对齐：{@code Tool.ts:462-465 maxResultSizeChars: Infinity}。
     * <p>Read 工具结果不落盘——如果落盘，LLM 下次 Read 落盘文件又会触发落盘→循环。
     * 设为 {@link Long#MAX_VALUE} 杜绝此路径。
     */
    @Override
    public long maxResultSizeChars() {
        return Long.MAX_VALUE;
    }

    /**
     * 严格模式 · 对齐 CC {@code FileReadTool.ts:343 strict: true}（buildTool 配置块相邻三行之一）。
     * 严格模式下 API 更严格遵循工具指令与参数 schema，模型不可注入额外字段。
     */
    @Override
    public boolean strict() {
        return true;
    }

    /**
     * [G9] 工具使用摘要 · 对齐 CC {@code FileReadTool.ts:368 getToolUseSummary}
     * （UI.tsx:174-184：无 file_path → null；否则 {@code getDisplayPath(file_path)}）。
     * PreToolUse hook（StreamingToolExecutor:1393）用本摘要做 prompt-based 决策。
     */
    @Override
    public String getToolUseSummary(java.util.Map<String, Object> processedInput) {
        Object fp = processedInput == null ? null : processedInput.get("file_path");
        return fp == null ? null : String.valueOf(fp);
    }

    /**
     * [G10] prompt · 对齐 CC {@code FileReadTool.ts:347-360}：
     * {@code renderPromptTemplate(pickLineFormatInstruction(), maxSizeInstruction, offsetInstruction)}
     * （FileReadTool/prompt.ts:26-57）。
     *
     * <p>Java 端默认 limits（FileReadingLimits.DEFAULT）：includeMaxSizeInPrompt=undefined →
     * maxSizeInstruction 为空串；targetedRangeNudge=undefined → OFFSET_INSTRUCTION_DEFAULT。
     * PDF 指令段按 {@link PdfSupport#isPDFSupported}（Java 无 ctx 时视为支持，等价 CC
     * getMainLoopModel 非 haiku）。
     */
    @Override
    public String prompt() {
        String maxSizeInstruction = "";  // 默认 includeMaxSizeInPrompt=undefined → 空（CC limits.ts:55-57）
        String offsetInstruction =
            "- You can optionally specify a line offset and limit (especially handy for long files), but "
                + "it's recommended to read the whole file by not providing these parameters";  // OFFSET_INSTRUCTION_DEFAULT
        String pdfInstruction = PdfSupport.isPDFSupported(null)
            ? "\n- This tool can read PDF files (.pdf). For large PDFs (more than 10 pages), you MUST provide "
                + "the pages parameter to read specific page ranges (e.g., pages: \"1-5\"). Reading a large "
                + "PDF without the pages parameter will fail. Maximum " + PdfSupport.PDF_MAX_PAGES_PER_READ
                + " pages per request."
            : "";
        return "Reads a file from the local filesystem. You can access any file directly by using this tool.\n"
            + "Assume this tool is able to read all files on the machine. If the User provides a path to a file assume that path is valid. It is okay to read a file that does not exist; an error will be returned.\n"
            + "\n"
            + "Usage:\n"
            + "- The file_path parameter must be an absolute path, not a relative path\n"
            + "- By default, it reads up to " + MAX_LINES_TO_READ + " lines starting from the beginning of the file"
            + maxSizeInstruction + "\n"
            + offsetInstruction + "\n"
            + "- Results are returned using cat -n format, with line numbers starting at 1\n"
            + "- This tool allows NexusAI to read images (eg PNG, JPG, etc). When reading an image file "
            + "the contents are presented visually as NexusAI is a multimodal LLM." + pdfInstruction + "\n"
            + "- This tool can read Jupyter notebooks (.ipynb files) and returns all cells with their outputs, "
            + "combining code, text, and visualizations.\n"
            + "- This tool can only read files, not directories. To read a directory, use an ls command via the "
            + com.nexusai.application.agent.tool.ToolNameConstants.BASH_TOOL_NAME + " tool.\n"
            + "- You will regularly be asked to read screenshots. If the user provides a path to a screenshot, "
            + "ALWAYS use this tool to view the file at the path. This tool will work with all temporary file paths.\n"
            + "- If you read a file that exists but has empty contents you will receive a system reminder warning in place of file contents.";
    }

    /**
     * 搜索提示 · 对齐 CC {@code FileReadTool.ts:339 searchHint = 'read files, images, PDFs, notebooks'}。
     * 供 ToolSearch 关键词匹配（CC Tool.ts:378 可选字段，3-10 词、无尾句号约束）。
     */
    @Override
    public String searchHint() {
        return "read files, images, PDFs, notebooks";
    }

    // ════════════════════════════════════════════════════════════════════════
    // [GAP-B] checkPermissions · 委托 ReadPermissionChecker
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [GAP-B / Session L] 对齐 CC {@code FileReadTool.ts:398-405} 委托语义.
     *
     * <p>Java 无原生 {@code checkReadPermissionForTool} → 本类新建
     * {@link ReadPermissionChecker} 组合已有原语（RuleQuery + TUC），
     * 按 CC filesystem.ts:1030-1193 的 8 步顺序实现（无 internal-path 白名单等不可
     * 等价步骤做合理简化，记 concerns）。
     *
     * <p>[Session M.4.4 收尾] 生产链路（{@code @Autowired} 3 参构造）恒注入
     * permissionChecker（Spring 启动注入失败即挂）；缺失时 fail-loud
     * {@link IllegalStateException}（对齐 ReadPermissionChecker 同款 fail-loud 模式），
     * 不再静默 Allow（Pattern #11 关闭）。CC filesystem.ts:1030-1193 无 null 守卫。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (permissionChecker == null) {
            // [Session M.4.4 收尾] 依赖缺失静默 Allow = Pattern #11 门禁绕过 →
            // fail-loud ISE（对齐 ReadPermissionChecker 同款 fail-loud 模式）
            throw new IllegalStateException(
                "permissionChecker 未注入, 无法执行读权限检查");
        }
        return permissionChecker.check(this, input, ctx);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [GAP-C] validateInput · 6 步链（对齐 CC FileReadTool.ts:418-495）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [GAP-C / Session L] 对齐 CC {@code FileReadTool.ts:418-495} validateInput.
     *
     * <p>6 步链顺序固定（CC 原顺序）：
     * <ol>
     *   <li>pages 格式（CC errorCode=7）</li>
     *   <li>pages>20 页（CC errorCode=8）</li>
     *   <li>expandPath + read deny 规则（CC errorCode=1）</li>
     *   <li>UNC 路径 → 提前 pass（决策推迟到 checkPermissions，WHY 防 NTLM 凭据泄露）</li>
     *   <li>二进制扩展名（排除 pdf + 5 种图片，CC errorCode=4）</li>
     *   <li>BLOCKED_DEVICE_PATHS（CC errorCode=9）</li>
     * </ol>
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String path = input == null ? "" : input.path("file_path").asText("");
        JsonNode pagesNode = input == null ? null : input.get("pages");

        // 步骤 1: pages 格式（纯字符串解析，无 I/O）
        if (pagesNode != null && !pagesNode.isNull()) {
            String pages = pagesNode.asText("");
            Optional<PdfPageRange.Range> parsed = PdfPageRange.parse(pages);
            if (parsed.isEmpty()) {
                return ValidationResult.fail("7",
                    "Invalid pages parameter: \"" + pages + "\". Use formats like \"1-5\", \"3\", or \"10-20\". Pages are 1-indexed.");
            }
            // 步骤 2: pages > 20（P-CC-01 常量收敛到 PdfSupport · CC apiLimits.ts:77）
            int rangeSize = parsed.get().sizeOrOverLimit(PdfSupport.PDF_MAX_PAGES_PER_READ);
            if (rangeSize > PdfSupport.PDF_MAX_PAGES_PER_READ) {
                return ValidationResult.fail("8",
                    "Page range \"" + pages + "\" exceeds maximum of " + PdfSupport.PDF_MAX_PAGES_PER_READ +
                    " pages per request. Please use a smaller range.");
            }
        }

        // 步骤 3: expandPath + deny 规则
        String fullFilePath = path.startsWith("~")
            ? System.getProperty("user.home", "") + path.substring(1)
            : path;
        if (ctx != null && ctx.permissionContext() != null && permissionChecker != null) {
            // 用 ReadPermissionChecker 内置的 expandPath 逻辑（保持单点）→ 直接调内部 deny 步骤
            // 简化：仅调用 RuleQuery.getDenyRuleByContentsForTool + toolMatchesRule (whole-tool deny)
            PermissionResult denyCheck = permissionChecker.check(this, input, ctx);
            if (denyCheck instanceof PermissionResult.Deny d) {
                return ValidationResult.fail("1", d.message());
            }
        }
        // 注：ctx/permCtx 为 null 时跳过 deny 检查（向后兼容，注释 WHY）—

        // 步骤 4: UNC 路径提前 pass（防 NTLM 凭据泄露，决策推迟到 checkPermissions）
        if (fullFilePath.startsWith("\\\\") || fullFilePath.startsWith("//")) {
            return ValidationResult.pass();
        }

        // 步骤 5: 二进制扩展名（排除 pdf + 5 种图片）
        String lower = fullFilePath.toLowerCase();
        int lastDot = lower.lastIndexOf('.');
        // 只在最后一段含 '.' 时才取扩展名，避免 .bashrc 误判
        int lastSlash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String ext = (lastDot > lastSlash) ? lower.substring(lastDot) : "";
        if (!ext.isEmpty()
            && NON_TEXT_BINARY_EXTENSIONS.contains(ext)
            && !IMAGE_EXTENSIONS.contains(ext.length() > 1 ? ext.substring(1) : "")
            && !ext.equals(".pdf")) {
            return ValidationResult.fail("4",
                "This tool cannot read binary files. The file appears to be a binary " + ext +
                " file. Please use appropriate tools for binary file analysis.");
        }

        // 步骤 6: BLOCKED_DEVICE_PATHS（防设备文件永久阻塞）
        if (BLOCKED_DEVICE_PATHS.contains(fullFilePath)
            || (fullFilePath.startsWith("/proc/")
                && (fullFilePath.endsWith("/fd/0")
                    || fullFilePath.endsWith("/fd/1")
                    || fullFilePath.endsWith("/fd/2")))) {
            return ValidationResult.fail("9",
                "Cannot read '" + path + "': this device file would block or produce infinite output.");
        }

        return ValidationResult.pass();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [GAP-E] execute 多类型 dispatch + dedup
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return executeInternal(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        return executeInternal(call, ctx);
    }

    private ToolResult executeInternal(ToolUseBlock call, ToolUseContext ctx) {
        String relPath = call.input().path("file_path").asText("");
        // [L+ GAP-B] offset/limit 改为 Integer: 无入参 → null (= full read, 对齐 CC
        // FileReadTool.ts:1035-1036 存 undefined). 旧实现默认 (1, 2000) 使 full read
        // 参与 dedup 返回 file_unchanged stub, LLM 拿不到全文件内容 — 偏离 CC
        // (CC full read offset=undefined → :550 守卫 existingState.offset !== undefined
        // 自然拒绝 dedup). null = full read 与 CC undefined 等价.
        // [GAP-D 严格对齐 2026-08-04] offset 默认 1 (对齐 CC FileReadTool.ts:497 `offset = 1`);
        //   full read = offset=1 + limit=null (CC undefined) — 旧 null 表示 full read 使 dedup
        //   守卫失去 CC 语义 (见 dedup 段注释). null 仅保留为 dispatchText 内防御分支.
        Integer offset = call.input().has("offset") ? call.input().get("offset").asInt() : 1;
        Integer limit = call.input().has("limit") ? call.input().get("limit").asInt() : null;
        // [P-CC-01] pages 参数 · 对齐 CC FileReadTool.ts:497 `pages`（PDF 分页读取，schema 已恢复声明 :236-241）
        String pages = call.input().has("pages") && !call.input().get("pages").isNull()
            ? call.input().get("pages").asText("") : null;

        // [OPD-D1-01] Read 输出上限覆盖 · 对齐 CC FileReadTool.ts:502-516:
        //   maxSizeBytes = fileReadingLimits?.maxSizeBytes ?? getDefaultFileReadingLimits().maxSizeBytes
        //   maxTokens   = fileReadingLimits?.maxTokens   ?? getDefaultFileReadingLimits().maxTokens
        // getDefaultFileReadingLimits()（limits.ts:53-92）: env(CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS) >
        //   GB(tengu_amber_wren) > DEFAULT. Java: env = System.getenv, GB = @Value 注入属性模拟.
        FileReadingLimits.Override fileReadingLimitsOverride = ctx != null ? ctx.fileReadingLimits() : null;
        FileReadingLimits.Limits defaultLimits = FileReadingLimits.resolve(
            () -> System.getenv("CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS"),
            this::gbFileReadingLimitsMaxTokens,
            this::gbFileReadingLimitsMaxSizeBytes);
        int effectiveMaxSizeBytes = fileReadingLimitsOverride != null && fileReadingLimitsOverride.maxSizeBytes() != null
            ? fileReadingLimitsOverride.maxSizeBytes() : defaultLimits.maxSizeBytes();
        int effectiveMaxTokens = fileReadingLimitsOverride != null && fileReadingLimitsOverride.maxTokens() != null
            ? fileReadingLimitsOverride.maxTokens() : defaultLimits.maxTokens();
        // [OPD-D1-01] override 埋点 · 对齐 CC FileReadTool.ts:511-516:
        //   仅 override 有值才触发（低流量，事件数 = override 频率）；metadata = hasMaxTokens/hasMaxSizeBytes 布尔
        //   （CC 记布尔而非数值 — hasMaxTokens: fileReadingLimits.maxTokens !== undefined）。
        //   Java 端无 analytics 服务时跳过（short-circuit，同 BashTool 短路语义）。
        if (fileReadingLimitsOverride != null && analyticsTracker != null) {
            analyticsTracker.logEvent("tengu_file_read_limits_override",
                java.util.Map.<String, Object>of(
                    "hasMaxTokens", fileReadingLimitsOverride.maxTokens() != null,
                    "hasMaxSizeBytes", fileReadingLimitsOverride.maxSizeBytes() != null));
            if (log.isDebugEnabled()) {
                log.debug("ReadFileTool: tengu_file_read_limits_override 触发 hasMaxTokens={} hasMaxSizeBytes={}（CC FileReadTool.ts:511-516）",
                    fileReadingLimitsOverride.maxTokens() != null,
                    fileReadingLimitsOverride.maxSizeBytes() != null);
            }
        }

        if (relPath.isBlank()) {
            return ToolResult.error(call.id(), "path is empty");
        }
        // 校验仅对显式入参执行; null (full read) 跳过 (对齐 CC 无 minimum 校验语义).
        // [G12] offset=0 放行（CC FileReadTool.ts:230 nonnegative + :1020 `offset === 0 ? 0 : offset - 1`）。
        if (offset != null && offset < 0) {
            return ToolResult.error(call.id(),
                "offset must be >= 0 (1-based start line; 0 reads from first line); got " + offset);
        }
        if (limit != null && limit < 1) {
            return ToolResult.error(call.id(),
                "limit must be >= 1; got " + limit);
        }

        Path file;
        try {
            file = guard.resolve(relPath);
        } catch (SecurityException se) {
            log.warn("ReadFileTool: blocked path escape: {}", relPath);
            return ToolResult.error(call.id(), se.getMessage());
        }

        // ── dedup: 同 path + offset/limit + mtime 未变 → file_unchanged（CC :536-573）──
        // [L+ R1 收尾] 无 ctx → 完全跳过 dedup (既不读也不写); 有 ctx → 走 ctx.readFileState().
        //   WHY: 实例级 fallback 会被多 session 共享污染 (R1 半成品问题), CC 无此概念.
        //   代价: execute(call) 无 ctx 调用方退化为每次 full read; 正确性等价, 仅多一次 IO.
        // [P-CC-02] 类型由 Caffeine Cache 改为 FileStateCache (双限真 LRU, 对齐 CC utils/fileStateCache.ts:30-93).
        //   .getIfPresent(key) → .get(key) (CC FileStateCache 命名, fileStateCache.ts:41-43).
        FileStateCache dedupCache = ctx != null ? ctx.readFileState() : null;
        long mtime;
        // [L+ R3] stat 失败 → 不参与 dedup, 直接 fall through 到 full read.
        // CC FileReadTool.ts:565-567 "stat failed — fall through to full read" 等价.
        // WHY 用独立 flag 而非 mtime==0 哨兵: mtime=0 是合法的文件时间戳(epoch),
        // 用哨兵会让 epoch 文件被误判为 stat 失败, 反之 stat 失败也可能与 prevState.mtimeMillis()==0 假匹配.
        boolean mtimeAvailable;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
            mtimeAvailable = true;
        } catch (Exception e) {
            mtime = 0L;
            mtimeAvailable = false;
            if (log.isDebugEnabled()) {
                log.debug("ReadFileTool: stat 失败, 跳过 dedup 强制 full read: path={} cause={}",
                    relPath, e.toString());
            }
        }
        // [L+ R3] dedup killswitch 守护 · 对齐 CC tengu_read_dedup_killswitch
        // 3P default = killswitch off = dedup enabled; Java 端默认 true.
        // 同时守护: dedupCache==null (ctx==null) → 直接跳过整个 dedup 块.
        if (dedupEnabled && dedupCache != null) {
            // [L+ round 3] 用与 dispatchText put 一致的归一化 key, 避免
            //   "Edit 用 raw 写 → Read 用归一化查不到" 的死循环.
            String keyForCache = ToolUseContext.keyForReadFileState(guard, relPath);
            ReadState prevState = dedupCache.get(keyForCache);
            // [L+ R3] 严格守卫: isPartialView=true (memory 注入/内容与磁盘不一致) → 不参与 dedup.
            // CC FileReadTool.ts:549 `!existingState.isPartialView` 等价.
            // [GAP-D 严格对齐 2026-08-04] 撤销 L+ GAP-B "full read 永不 dedup" 错误对齐:
            //   CC :497 调用侧 offset=1 默认 (非 undefined!), full read = offset=1 + limit=undefined.
            //   entry 侧 :550 `existingState.offset !== undefined` (Read 恒存 offset, Edit/Write 存 undefined);
            //   :553 rangeMatch `offset === offset && limit === limit` 无 undefined 特判
            //   (undefined===undefined → full read 后二次 full read 命中 dedup, 返回 file_unchanged).
            //   Java 等价: 调用侧 offset 默认 1, Objects.equals(null,null)=true (undefined 等价).
            //   旧守卫 (offset!=null && limit!=null) 使 full read 永不 dedup — 偏离 CC.
            if (mtimeAvailable
                && prevState != null
                && !prevState.isPartialView()
                && prevState.offset() != null
                && Objects.equals(prevState.offset(), offset)
                && Objects.equals(prevState.limit(), limit)
                // [L+ R3] 严格守卫: mtime 必须与上次读取时完全一致, 否则文件已被外部修改 → full read.
                // CC FileReadTool.ts:557 `if (mtimeMs === existingState.timestamp)` 等价.
                // WHY 这条不可省: 缺了它, "读文件 → 文件被改 → 再读同 range" 会命中 dedup
                // 返回 <file_unchanged>, 让 LLM 拿到 stale content —— 正是 dedup 要防的事故.
                && prevState.mtimeMillis() == mtime) {
                if (log.isDebugEnabled()) {
                    log.debug("ReadFileTool: dedup 命中 file_unchanged: path={} offset={} limit={}",
                        relPath, offset, limit);
                }
                // [L+ R3] file_read_dedup analytics · 对齐 CC FileReadTool.ts:559 logEvent('tengu_file_read_dedup', {ext}).
                // Java 端无 analytics 服务, 走 slf4j info log 标记 (CC analytics pipeline 等价).
                int lastDot = relPath.lastIndexOf('.');
                String ext = lastDot >= 0 ? relPath.substring(lastDot + 1) : "";
                log.info("ReadFileTool: dedup 命中 file_read_dedup: path={} ext={}",
                    relPath, ext);
                // [G33①] tool_result content 对齐 CC FILE_UNCHANGED_STUB（FileReadTool.ts:686-691），
                //   旧自定义 "<file_unchanged> path=..." 偏离 CC 已删。
                return ToolResult.fileUnchanged(call.id(), FILE_UNCHANGED_STUB, relPath);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("ReadFileTool: dedup killswitch 关闭, 强制 full read: path={}", relPath);
            }
        }

        // ── 动态技能发现 + 条件技能激活（CC FileReadTool.ts:575-591 在 dedup 之后）──
        // [P-AL-07 R1] 顺序对齐 CC：dedup 命中早返（:562-567 return file_unchanged）→ 技能链零执行；
        //   Java 旧实现 trigger 在 dedup 前，dedup 命中仍执行完整技能链（同步 discover + triggers.add
        //   + 后台 addSkillDirectories + activate）——偏离 CC。移至 dedup 之后、读取之前。
        //   WHY: 技能发现服务"将要发生的读取"（嵌套 .claude/skills 记录 + 后台加载 + 条件激活），
        //   dedup 命中根本没有读取发生，执行技能链是纯浪费（CC 早返语义）。
        triggerDynamicSkillDiscovery(ctx, file);

        // 文件存在性检查（原 trigger 前，随 R1 移至 trigger 后）：
        //   CC 无文件存在早返——技能链（:575-591）在 callInner（:593，ENOENT 友好错误 :611）之前执行，
        //   对不存在文件同样触发技能发现（O1 决策，progress/P-AL-07.md §2 登记；Java 存在检查等价
        //   callInner 的 ENOENT 边界，置于技能链之后）。
        if (!Files.exists(file)) {
            return ToolResult.error(call.id(), "File not found: " + relPath);
        }
        if (!Files.isRegularFile(file)) {
            return ToolResult.error(call.id(), "Not a regular file: " + relPath);
        }
        // ── dispatch by extension ──
        String fileName = file.getFileName().toString().toLowerCase();
        int lastDotIdx = fileName.lastIndexOf('.');
        String ext = lastDotIdx >= 0 ? fileName.substring(lastDotIdx + 1) : "";

        try {
            // .ipynb → notebook
            if ("ipynb".equals(ext)) {
                // [OPD-D1-01] 上限覆盖透传：CC :826-838 notebook 分支同样消费 call() 顶部解析的
                //   maxSizeBytes/maxTokens（validateContentTokens :838）
                return dispatchNotebook(call, file, relPath, offset, limit, ctx,
                    effectiveMaxSizeBytes, effectiveMaxTokens);
            }
            // 图片 → image
            if (IMAGE_EXTENSIONS.contains(ext)) {
                // [D4] 图片 token 预算透传有效 maxTokens（override ?? default，同 dispatchText/dispatchNotebook）·
                //   对齐 CC FileReadTool.ts:869 readImageWithTokenBudget(resolvedFilePath, maxTokens)（:507）
                return dispatchImage(call, file, relPath, ext, ctx, effectiveMaxTokens);
            }
            // .pdf → pdf/parts（[P-CC-01] pdfbox 完整解析 · 对齐 CC FileReadTool.ts:893-1017）
            if ("pdf".equals(ext)) {
                return dispatchPdf(call, file, relPath, pages, ctx);
            }
            // 其余 → text
            return dispatchText(call, file, relPath, offset, limit, mtime, ctx,
                effectiveMaxSizeBytes, effectiveMaxTokens);
        } catch (Exception e) {
            log.error("ReadFileTool: error reading {}", file, e);
            return ToolResult.error(call.id(), "Read error: " + e.getMessage());
        }
    }

    /**
     * [ODF-B4R-LAZY 返工 finding 1] 触发集生产者 · 对齐 CC FileReadTool.ts:848/870/1038
     * {@code context.nestedMemoryAttachmentTriggers?.add(fullFilePath)}。
     *
     * <p><b>WHY</b>: nested memory lazy-load 触发链是"文件工具读成功后写触发集 → loop 每轮
     * getNestedMemoryAttachments 消费"（CC attachments.ts:2165-2190）。缺此生产者时触发集恒空，
     * getNestedMemoryAttachments 恒快速返回，三个孤儿加载子函数生产运行时不执行（死代码）。
     * 读成功（text/image/notebook 三分支）才 add —— CC 在 readFileState.set 后 / 读取返回前调用。
     * 经共享 Set 写入（ToolUseContext keepOrCopyMutableSet 保持同一 KeySetView 实例），loop 消费端可见。
     */
    private void triggerNestedMemoryAttachment(ToolUseContext ctx, Path file) {
        if (ctx != null) {
            ctx.nestedMemoryAttachmentTriggers().add(file.toString());
            if (log.isDebugEnabled()) {
                log.debug("ReadFileTool: 写入 nestedMemoryAttachmentTriggers (lazy-load 触发): {}",
                    file);
            }
        }
    }

    /**
     * text 分支（含 dedup state 写入 + listener 通知）· 对齐 CC FileReadTool.ts:1019-1085.
     *
     * <p><b>[L+ round 3] CRLF 归一化</b>: 写入 {@code ReadState.content} 之前, 先把每行
     * 末尾的 {@code \r} 剥离 (CC {@code readFileInRange.ts:165-167,177-179}). 这样:
     * <ul>
     *   <li>存进 cache 的 {@code content} 是 LF-only 形式, 与 CC readFileState 对齐</li>
     *   <li>Edit/Write 写回时也用同一规则归一化后存 (CRLF 写回文件不会变 LF)</li>
     *   <li>stale-write 内容比对时, 两侧都是 LF-only → CRLF 文件不会被误判为"内容已变"</li>
     * </ul>
     */
    private ToolResult dispatchText(ToolUseBlock call, Path file, String relPath,
                                    Integer offset, Integer limit, long mtime, ToolUseContext ctx,
                                    int maxSizeBytes, int maxTokens) throws Exception {
        // [GAP-D 拍板 2026-08-04] pre-read maxSizeBytes 检查 · 对齐 CC FileReadTool.ts:1026
        //   (readFileWithLimits 仅 limit===undefined 传 maxSizeBytes) + limits.ts:65 maxSizeBytes=256KB:
        //   full read (无显式 limit) 全文件 > maxSizeBytes → error, 提示用 offset/limit (CC :350 prompt 语义).
        // [OPD-D1-01] maxSizeBytes 为覆盖后的有效值：ctx.fileReadingLimits().maxSizeBytes ?? getDefault()（CC :505-506）。
        long fileSize = Files.size(file);
        if (limit == null && fileSize > maxSizeBytes) {
            return ToolResult.error(call.id(),
                "File size (" + fileSize + " bytes) exceeds maximum allowed size ("
                    + maxSizeBytes + " bytes). Use offset and limit parameters to read specific "
                    + "portions of the file.");
        }

        // [G13④] readFileInRange 流式读 · 对齐 CC readFileInRange.ts:224-304（流式路径内存语义）：
        //   仅累积选中行（[lineOffset, endLine)），非选中行计数后丢弃 —— 读超大文件局部行不会
        //   全文件入内存而 OOM。旧实现 Files.readString 全文件 + split（limit!=null 部分读在
        //   超大文件上仍全量加载，OOM 风险，G13④）。语义对齐 CC fast/streaming 两路径：
        //   - lineOffset = offset===0 ? 0 : offset-1（CC FileReadTool.ts:1020）
        //   - endLine = limit===undefined ? Infinity : offset+limit（CC readFileInRange.ts:135/:360）
        //   - 行内 CRLF 归一（BufferedReader.readLine 剥 \n/\r\n 行终止符；CC :165-167 剥行尾 \r）
        //   - selectedLines join('\n')：无尾随换行仅当文件不以换行结尾；尾随换行/空文件在循环后
        //     补一行空 fragment（见下方返工块，CC :174-182/:306-327）
        //   - totalLines 全文件行数（CC :182/:327 计数全行 + 尾随 fragment，内存有界）
        int lineOffset = (offset == null || offset == 0) ? 0 : offset - 1;
        int endLine = limit == null ? Integer.MAX_VALUE : lineOffset + limit;
        java.util.List<String> selectedLines = new java.util.ArrayList<>();
        int totalLines = 0;
        int currentLine = 0;
        boolean firstPhysicalLine = true;
        try (java.io.BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // [G13④] UTF-8 BOM 剥除 · 对齐 CC readFileInRange.ts:138（fast 路径首字符
                //   `raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw`）+ :225-230（streaming 首 chunk
                //   `chunk.charCodeAt(0) === 0xfeff && (chunk = chunk.slice(1))`）。UTF-8 BOM（EF BB BF）
                //   经 BufferedReader 解码为首行首字符 U+FEFF，仅出现在文件首物理行；剥除后写入
                //   ReadState.content 为无 BOM 形式，避免与 Edit/Write 的 CRLF 归一 content 比对失配
                //   （模型经本工具读到的首行无 BOM，Edit findActualString/stale-write 以无 BOM 为基准）。
                if (firstPhysicalLine) {
                    firstPhysicalLine = false;
                    // BOM 首字符检测：文件首字节 EF BB BF 经 UTF-8 解码为首字符 U+FEFF
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }
                }
                if (currentLine >= lineOffset && currentLine < endLine) {
                    selectedLines.add(line);
                }
                currentLine++;
                totalLines++;
            }
        }
        // [G13④ 返工] 尾随空行 fragment · 对齐 CC readFileInRange.ts:174-182（fast）/ :306-327（streaming）：
        //   readLine 循环只覆盖"换行符分隔的完整行"，尾随换行/空文件在循环后还有一行空 fragment——
        //   CC 对循环后的 final fragment 无条件 lineIndex++ 并（选中区间内）push（空串）。
        //   Java BufferedReader.readLine() 剥行终止符不返回尾随空行，需显式补：文件以换行结尾或
        //   为空 → totalLines++ 且（currentLine 在选中区间内）selectedLines.add("")，使尾随换行
        //   文件 content='hello\n'、行号渲染含末尾 "N\t" 空行、空文件 totalLines=1、ReadState
        //   dedup content 含尾随 \n（对齐 CC :184/:329 join('\n') + :327 lineIndex++）。
        boolean fileEndsWithNewline = false;
        if (fileSize == 0) {
            fileEndsWithNewline = true; // 空文件 → CC readFileInRangeFast 空串 final fragment（lineIndex=1）
        } else {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
                raf.seek(fileSize - 1);
                fileEndsWithNewline = raf.read() == '\n'; // 末字节 '\n'（含 CRLF）→ CC 尾随空 fragment
            } catch (java.io.IOException e) {
                log.warn("ReadFileTool: 判定尾随空行 fragment 读取末字节失败 path={} 原因={}（fallback: 不追加）",
                    relPath, e.toString());
            }
        }
        if (fileEndsWithNewline) {
            if (currentLine >= lineOffset && currentLine < endLine) {
                selectedLines.add("");
            }
            currentLine++;
            totalLines++;
        }
        // [GAP-D 对齐] full read (limit=null) 数据流日志: 读全文件; dedup 由 offset=1+limit=null
        //   rangeMatch 语义决定 (二次 full read 命中 file_unchanged, 对齐 CC :553).
        if (log.isDebugEnabled()) {
            log.debug("ReadFileTool: full read (limit=null): path={} totalLines={}",
                relPath, totalLines);
        }

        // [OPD-TOOL-06-3a] 切片 join 对齐 CC readFileInRange.ts:162-184 `selectedLines.join('\n')`：
        //   行间追加 '\n'（而非逐行尾随 append('\n')），窗口读 raw content 由 "b\nc\n" 修正为
        //   "b\nc"，消除行号化后的伪空行 "4\t"。CC fast/streaming 两路径均 join('\n')，无尾随换行
        //   （仅在文件以换行结尾时末尾多一个空 fragment）。
        // [IMP-C5] "N more lines after offset+limit" / "offset N is past end" 后缀删除（TR-D1-⊕-3）：
        //   CC 读文件输出无该后缀（FileReadTool.ts:692-715 text case 只渲染
        //   freshness + formatFileLines(content) + reminder）；offset-past-end/空文件由
        //   mapper 的 data.file.content falsy 分支（CC :703-707）以 system-reminder 提示。
        String rawContent = String.join("\n", selectedLines);
        // [GAP-D 拍板 2026-08-04] 输出超限 → throw 错误 (对齐 CC MaxFileReadTokenExceededError
        //   FileReadTool.ts:175-185 + :769-771 throw): Java 字符级近似 (1 token ≈ 4 chars,
        //   25000 tokens ≈ 100k chars, 无 tokenizer 依赖 — ReadFileToolTest 禁止加 pom 依赖).
        //   旧实现截断返回部分内容偏离 CC (CC 无截断概念); 截断标记 isPartialView 已被 L+ 移除,
        //   此处连截断本身一并移除 → 超限路径不写 dedup state (throw 后不入 cache, 对齐 CC).
        // [RV-06] 校验对象改为 rawContent (无行号/无 suffix), 对齐 CC validateContentTokens(content)
        //   (FileReadTool.ts:769-771 序列化前校验原始内容, 行号在渲染层才加).
        // [OPD-D1-01] maxTokens 为覆盖后的有效值：ctx.fileReadingLimits().maxTokens ?? getDefault()（CC :507）；
        //   字符上限 = maxTokens * 4（沿用 GAP-D 1 token ≈ 4 chars 近似）。
        long maxTokensChars = (long) maxTokens * 4;
        if (rawContent.length() > maxTokensChars) {
            return ToolResult.error(call.id(),
                "File content (" + (rawContent.length() / 4) + " tokens estimated) exceeds maximum allowed tokens ("
                    + maxTokens + "). Use offset and limit parameters to read specific "
                    + "portions of the file, or search for specific content instead of reading the whole file.");
        }
        if (log.isInfoEnabled()) {
            log.info("ReadFileTool: 读取文本 {} 字节 path={} offset={} limit={} 行 {}-{} / {}",
                fileSize, relPath, offset, limit, lineOffset + 1, lineOffset + selectedLines.size(), totalLines);
        }

        // 写入 dedup state（CC readFileState.set fullFilePath ...）
        // [L+ R1 收尾] ctx==null → 跳过写; 有 ctx → 走 ctx.readFileState() (跨工具共享).
        // [L+ GAP-D] isPartialView 恒 false — CC 全仓 isPartialView 仅 memory 注入写入
        // (attachments.ts:1739-1749 + REPL.tsx:3815, 语义=注入内容与磁盘不一致), Read 路径
        // 从不写 (FileReadTool.ts:1032-1037 set 无 isPartialView 字段). 旧实现按
        // RESULT_SIZE_LIMIT 截断标记 true 是 Java 自创, 截断后同 range 永不 dedup 偏离 CC.
        // [L+ GAP-B] offset/limit 存本次入参; null = full read (对齐 CC :1035-1036 存 undefined).
        // [L+ round 3] key 派生统一用 ToolUseContext.keyForReadFileState(guard, relPath);
        //   content 字段填充 CRLF 归一化后的 rawContent (供后续 Edit/Write stale-write 内容兜底比对).
        // [IMP-M-P1-2] 存纯内容 rawContent（不含新鲜度前缀/行号/reminder）；渲染层才加行号+前缀
        //   （DEL-M-36 接线，对齐 CC FileReadTool.ts:1056-1058 + memoryFileFreshnessPrefix :697）。
        // [RV-06] cache 存 raw（无行号）: CC readFileState 缓存干净 content (FileReadTool.ts:1032-1037),
        //   formatFileLines 只在渲染层 (:697-701) 加行号 — Edit/Write stale-write 比对不能带行号偏移.
        if (ctx != null) {
            String keyForCache = ToolUseContext.keyForReadFileState(guard, relPath);
            ctx.readFileState().set(keyForCache,
                new ReadState(mtime, offset, limit, false, rawContent));
        }

        // [ODF-B4R-LAZY] 触发集生产者 · 对齐 CC FileReadTool.ts:1038（text 分支读成功 → 写触发集）
        triggerNestedMemoryAttachment(ctx, file);

        // [RV-06] 行号前缀迁到序列化层 mapToToolResultBlockParam · 对齐 CC FileReadTool.ts:692-715
        //   + utils/file.ts:290-319 addLineNumbers。call() 返回 raw content（data()=rawContent，
        //   对齐 CC FileReadTool.ts:1046-1055 data.file.content=raw 无行号），行号/reminder/前缀
        //   统一由 mapper 序列化时拼接。WHY: MagicDocsService.updateSingle 经 ReadFileTool 重读后用
        //   data() 做 detect，行号前缀击穿 MAGIC_DOC_HEADER 首行匹配（# MAGIC DOC 变 1\t# MAGIC DOC）；
        //   raw data 让 detect 自然命中（CC 真源 data.file.content 即 raw）。呈现元数据
        //   (startLine/suffix/freshnessNote/injectReminder) 经 structuredOutput 透传 mapper
        //   （EditFileTool 先例），cache/listener/size-check 保持 raw 不变。
        int startLine = offset == null ? 1 : offset;

        // [P-AL-06] CYBER_RISK_MITIGATION_REMINDER 注入决策 · 对齐 CC FileReadTool.ts:699-701
        //   text case 模型侧序列化附加（mapToolResultToToolResultBlockParam :692-715）；image case
        //   :654-669 只含 image block 无 reminder。空文件不注入（CC :695 data.file.content truthy
        //   检查，空串 → :703-707 warning 分支）。
        String mainLoopModel = resolveMainLoopModel(ctx);
        // [G13④] 空内容判定改用选中内容 rawContent（对齐 CC :695 `if (data.file.content)`
        //   —— mapper 检查的是选中 range 内容非空，非全文件；流式读不再持有全文件 content 串）。
        boolean injectReminder = !rawContent.isEmpty() && shouldIncludeFileReadMitigation(mainLoopModel);
        if (log.isDebugEnabled()) {
            log.debug("ReadFileTool: 文本读取 CYBER_RISK_MITIGATION_REMINDER 决策: path={} model={} canonical={} injectReminder={} contentEmpty={}",
                relPath, mainLoopModel, canonicalModelName(mainLoopModel), injectReminder, rawContent.isEmpty());
        }

        // [IMP-M-P1-2 DEL-M-36 接线] auto-memory 文件新鲜度标记 · 对齐 CC FileReadTool.ts:749-753
        //   memoryFileFreshnessPrefix(data) = memoryFreshnessNote(mtimeMs)（>1 day 才非空）。
        String freshnessNote = "";
        if (memoryFileDetection != null && memoryAge != null
                && memoryFileDetection.isAutoMemFile(file.toString())) {
            freshnessNote = memoryAge.memoryFreshnessNote(mtime);
            if (log.isDebugEnabled()) {
                log.debug("ReadFileTool: auto-memory 文件注入新鲜度标记: path={} mtime={} noteLen={}",
                    relPath, mtime, freshnessNote.length());
            }
        }

        // 拼接顺序对齐 CC FileReadTool.ts:698-701: freshness + formatFileLines(content) + reminder.
        //   [IMP-C5] suffix ("N more lines"/"offset past end") 已删除（TR-D1-⊕-3）；offset-past-end
        //   由 mapper 空内容 warning 分支承接（CC :703-707）。呈现元数据经 structuredOutput
        //   透传 mapper（本地持久化，EditFileTool 先例可接受）。totalLines 供 mapper 空内容
        //   warning 分支（CC :703-707 data.file.totalLines）。
        java.util.Map<String, Object> presentationMeta = new java.util.LinkedHashMap<>();
        presentationMeta.put("startLine", startLine);
        presentationMeta.put("totalLines", totalLines);
        presentationMeta.put("freshnessNote", freshnessNote);
        presentationMeta.put("injectReminder", injectReminder);
        ToolResult success = ToolResult.successWithStructuredOutput(call.id(), rawContent, presentationMeta);

        // listener 通知（仅 text 分支成功，CC :1040-1044）
        // [L+ GAP-A] 裸调用无 try/catch — CC FileReadTool.ts:1042-1044 直接
        // `for (const listener of fileReadListeners.slice()) { listener(...) }`,
        // 一 listener 抛异常即中断后续 listener 并向上传播. Java 端异常经
        // executeInternal 统一 catch (line ~541) 转 ToolResult.error (fail-loud),
        // 是工具框架返回契约 (Tool.execute 返回 AgentToolResult) 下最接近 CC 的表达.
        // listener 实现方必须自 catch 业务异常 (FileReadListener JavaDoc 已注明;
        // MagicDocsService.onFileRead 自 catch 实证存在).
        if (listenerRegistry != null) {
            // [IMP-C5] FileReadEvent 退役 → CC 二元签名 (filePath, content)（TR-D1-⊕-2）；
            //   content 传本次读取的 range 内容 rawContent（非全文件 content），对齐 CC
            //   FileReadTool.ts:1040-1044 listener(resolvedFilePath, content)（W6/R2 range vs 全文件）。
            listenerRegistry.notifyRead(relPath, rawContent);
        }



        return success;
    }

    /**
     * [OPD-TOOL-06-3a] cat -n 行号渲染 · CC original: addLineNumbers (file.ts:290-319)。
     *
     * <p>逐字镜像 CC：
     * <ul>
     *   <li>content null/空 → {@code ""}（CC {@code if (!content) return ''}）</li>
     *   <li>{@code split("\n", -1)} —— Java 内容已 CRLF 归一化 LF-only（dispatchText 逐行剥
     *       {@code \r}），等价 CC {@code split(/\r?\n/)}；{@code -1} 保留尾随空 fragment
     *       （对齐 JS split 保留尾随空串语义，否则 "a\nb\nc\n" 会丢末尾 "4\t" 空行）</li>
     *   <li>compact 启用（{@link #compactLinePrefixEnabled}）→ 每行 {@code `${i+startLine}\t${line}`}
     *       以 {@code '\n'} join（行间 join，末行不追加）</li>
     *   <li>否则 padded-arrow → numStr 长度 ≥6 用 {@code `${num}→${line}`}，否则
     *       {@code padStart(6,' ')+'→'+line}</li>
     * </ul>
     *
     * @param content   原始（无行号）内容，可能为空
     * @param startLine 1-indexed 起始行（对齐 CC data.file.startLine = offset）
     * @return cat -n 格式行号化文本
     */
    private String addLineNumbers(String content, int startLine) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder();
        if (compactLinePrefixEnabled) {
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    out.append('\n');
                }
                out.append(i + startLine).append('\t').append(lines[i]);
            }
        } else {
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    out.append('\n');
                }
                String numStr = String.valueOf(i + startLine);
                if (numStr.length() >= 6) {
                    out.append(numStr).append('→').append(lines[i]);
                } else {
                    out.append(" ".repeat(6 - numStr.length())).append(numStr).append('→').append(lines[i]);
                }
            }
        }
        return out.toString();
    }

    /**
     * [RV-06] tool_result 块 · 对齐 CC {@code FileReadTool.ts:692-715}
     * mapToolResultToToolResultBlockParam 的 case 'text' 分支（成功路径被调 toolExecution.ts:1292）。
     *
     * <p>行号前缀迁到本序列化层：dispatchText call() 返回 raw data()（CC :1046-1055
     * {@code data.file.content}=raw 无行号），本 mapper 读 structuredOutput 呈现元数据重建
     * CC 同款 content（CC :697-700）:
     * {@code memoryFileFreshnessPrefix(data) + formatFileLines(data.file) + (shouldIncludeFileReadMitigation() ? CYBER_RISK_MITIGATION_REMINDER : '')}。
     * formatFileLines → addLineNumbers(file)（:725-727 + utils/file.ts:290-319）。
     *
     * <p>非 text（image/pdf/notebook/file_unchanged，data 为 JsonNode）或 error → 委托
     * {@link Tool#mapToToolResultBlockParam} 默认实现（renderToolResultPayloadText 渲染，
     * 与 CC pdf/parts/file_unchanged 摘要 case :672-691 对齐）。
     *
     * @param result 工具执行结果（data 为 raw content String + structuredOutput 含呈现元数据）
     * @return tool_result 块（tool_use_id/type/content/is_error）
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (!(result instanceof ToolResult<?> tr)) {
            return null;
        }
        if (isError) {
            return Tool.super.mapToToolResultBlockParam(result, toolUseId, isError);
        }
        Object data = tr.data();
        // [IMP-C5] image 独立块送达 · 对齐 CC FileReadTool.ts:652-669 case 'image'：
        //   tool_result content 为独立 image block [{type:'image', source:{type:'base64',
        //   data: data.file.base64, media_type: data.file.type}}]，模型可视而非 base64 JSON 文本
        //   （TR-D1 W1/R1 HIGH，H-4）。data(JsonNode) 经 ToolResult.image 工厂携带
        //   META_IMAGE_BASE64 / META_IMAGE_MEDIA_TYPE。
        if (data instanceof JsonNode node
                && "image".equals(node.path(ToolResult.META_OUTPUT_TYPE).asText())) {
            String base64 = node.path(ToolResult.META_IMAGE_BASE64).asText();
            String mediaType = node.path(ToolResult.META_IMAGE_MEDIA_TYPE).asText();
            if (!base64.isEmpty() && !mediaType.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("ReadFileTool.mapToToolResultBlockParam: image 独立块送达 id={} mediaType={} base64Len={}（CC FileReadTool.ts:654-669）",
                        toolUseId, mediaType, base64.length());
                }
                return ToolResultBlockParam.blocksContent(toolUseId,
                    java.util.List.of(ContentBlockParam.ImageBlockParam.of(mediaType, base64)));
            }
            // base64/mediaType 缺失（异常 data）→ 默认渲染器兜底（fail-loud 不外泄半成品）
            return Tool.super.mapToToolResultBlockParam(result, toolUseId, isError);
        }
        // [IMP-C2 返工] successWithStructuredOutput 折入 data(Map) 后，text 分支的 raw content
        //   在 "summary" 键（ReadFileTool.java:981 successWithStructuredOutput(call.id(), rawContent,
        //   presentationMeta)）。此处从 Map 提取 raw；String data（旧契约/防御）同样支持。
        String raw;
        if (data instanceof String s) {
            raw = s;
        } else if (data instanceof Map<?, ?> m && m.get("summary") instanceof String sum) {
            raw = sum;
        } else {
            // 非 text 分支（pdf/notebook/file_unchanged JsonNode）→ 默认渲染器
            return Tool.super.mapToToolResultBlockParam(result, toolUseId, isError);
        }
        // [IMP-C2] structuredOutput 折入 data（Map）；无呈现元数据时（本工具 text 输出总有
        //   presentationMeta 折入）回退默认渲染器。
        Map<String, Object> so = ToolResult.presentationMeta(tr);
        Object startLineVal = so.get("startLine");
        if (startLineVal instanceof Number startLineNum) {
            int startLine = startLineNum.intValue();
            String freshnessNote = so.get("freshnessNote") instanceof String fn ? fn : "";
            boolean injectReminder = Boolean.TRUE.equals(so.get("injectReminder"));
            int totalLines = so.get("totalLines") instanceof Number n ? n.intValue() : 0;
            // [IMP-C5] suffix 已删（TR-D1-⊕-3）；空内容走 CC :703-707 warning 分支：
            //   data.file.content falsy → totalLines===0 ? 空文件警告 : offset-past-end 警告。
            String content;
            if (raw.isEmpty()) {
                content = totalLines == 0
                    ? "<system-reminder>Warning: the file exists but the contents are empty.</system-reminder>"
                    : "<system-reminder>Warning: the file exists but is shorter than the provided offset ("
                        + startLine + "). The file has " + totalLines + " lines.</system-reminder>";
            } else {
                content = freshnessNote + addLineNumbers(raw, startLine)
                    + (injectReminder ? CYBER_RISK_MITIGATION_REMINDER : "");
            }
            if (log.isDebugEnabled()) {
                log.debug("ReadFileTool.mapToToolResultBlockParam: id={} startLine={} rawLen={} contentLen={} injectReminder={}（CC FileReadTool.ts:698-701）",
                    toolUseId, startLine, raw.length(), content.length(), injectReminder);
            }
            return new ToolResultBlockParam(toolUseId, "tool_result", content, false);
        }
        // 无呈现元数据（非本工具 text 输出，理论不可达）→ 默认渲染器兜底
        return Tool.super.mapToToolResultBlockParam(result, toolUseId, isError);
    }

    /**
     * pdf 分支 · 对齐 CC FileReadTool.ts:893-1017（readPDF / extractPDFPages）。
     *
     * <p>两条子路径（CC 原顺序）：
     * <ul>
     *   <li><b>pages 提供</b>（CC :895-946）：parsePDFPageRange 解析（非法 → null → 全量提取，
     *       CC :896 {@code parsedRange ?? undefined}）→ {@link PdfSupport#extractPDFPages} →
     *       失败转 error（CC :901-903 throw）；成功 → tengu_pdf_page_extraction telemetry
     *       + logFileOperation 等价 + ToolResult.parts（[P-AL-01] 页图 image blocks 经
     *       newMessages 送达：单条 isMeta user 消息，CC :938-945）</li>
     *   <li><b>无 pages</b>（CC :948-1016）：页数 &gt; 10 → error 提示用 pages（CC :949-955）→
     *       shouldExtractPages = !isPDFSupported() || size &gt; 3MB → 提取（结果仅用于 telemetry，
     *       CC :962-977 真实行为）→ !isPDFSupported → error（CC :979-985）→ readPDF（CC :987-990）
     *       → ToolResult.pdf（[P-AL-01] document block 经 newMessages 送达：isMeta user 消息，
     *       CC :1001-1015；tool_result 载荷只含 summary 文本）</li>
     * </ul>
     *
     * <p>不写 readFileState、不通知 listener（CC :1042-1044 仅 text 分支通知）。
     */
    private ToolResult dispatchPdf(ToolUseBlock call, Path file, String relPath,
                                   String pages, ToolUseContext ctx) {
        if (pages != null && !pages.isBlank()) {
            return dispatchPdfPages(call, file, relPath, pages, ctx);
        }
        return dispatchPdfFull(call, file, relPath, ctx);
    }

    /**
     * pages 分支 · 对齐 CC FileReadTool.ts:895-946。
     */
    private ToolResult dispatchPdfPages(ToolUseBlock call, Path file, String relPath,
                                        String pages, ToolUseContext ctx) {
        // CC :896-899 parsePDFPageRange(pages) — 非法字符串 → null → 全量提取（parsedRange ?? undefined）
        PdfPageRange.Range parsedRange = PdfPageRange.parse(pages).orElse(null);
        PdfSupport.PdfExtractResult extractResult = PdfSupport.extractPDFPages(
            file,
            pdfOutputDir(ctx),
            parsedRange != null ? parsedRange.firstPage() : null,
            parsedRange != null ? parsedRange.lastPage() : null);
        if (!extractResult.success()) {
            // CC :901-903 throw new Error(extractResult.error.message) → executeInternal catch → error result
            if (log.isInfoEnabled()) {
                log.info("ReadFileTool: PDF pages 提取失败 path={} reason={} message={}",
                    relPath, extractResult.error().reason(), extractResult.error().message());
            }
            return ToolResult.error(call.id(), extractResult.error().message());
        }
        PdfSupport.PdfExtractData data = extractResult.data();
        // CC :904-909 logEvent('tengu_pdf_page_extraction', {success:true, pageCount, fileSize, hasPageRange:true})
        // Java 无 analytics 服务, 走 slf4j info log 标记 (CC analytics pipeline 等价, 同 dedup 模式)
        if (log.isInfoEnabled()) {
            log.info("ReadFileTool: tengu_pdf_page_extraction success=true pageCount={} fileSize={} hasPageRange=true path={}",
                data.count(), data.originalSize(), relPath);
        }
        // CC :910-915 logFileOperation({operation:'read', content:`PDF pages ${pages}`})
        if (log.isInfoEnabled()) {
            log.info("ReadFileTool: 读取 PDF 页图 path={} pages={} count={} outputDir={}",
                relPath, pages, data.count(), data.outputDir());
        }
        // CC :938-945 data = extractResult.data + newMessages 页图 image blocks
        //   [P-AL-01] 页图 image blocks 真实送达：单条 isMeta user 消息携带全部页图（CC
        //   createUserMessage({content: imageBlocks, isMeta: true})），Provider 层渲染
        List<com.nexusai.model.session.dto.ChatMessageDto> newMessages =
            buildPageImagesMetaMessage(ctx, data);
        if (log.isInfoEnabled()) {
            log.info("ReadFileTool: PDF 页图 image blocks 送达 newMessages path={} count={} blocks={}",
                relPath, data.count(), newMessages.isEmpty() ? 0
                    : newMessages.get(0).contentBlocks() == null ? 0 : newMessages.get(0).contentBlocks().size());
        }
        return ToolResult.parts(call.id(),
            "PDF pages extracted: " + data.count() + " page(s) from " + relPath
                + " (" + com.nexusai.application.agent.tool.ToolResultStorage.formatFileSize(data.originalSize()) + ")",
            relPath, data.originalSize(), data.count(), data.outputDir(), newMessages);
    }

    /**
     * 无 pages 分支 · 对齐 CC FileReadTool.ts:948-1016。
     */
    private ToolResult dispatchPdfFull(ToolUseBlock call, Path file, String relPath, ToolUseContext ctx) {
        // CC :948-955 页数阈值检查（pdfinfo → Java pdfbox）
        Integer pageCount = PdfSupport.getPDFPageCount(file);
        if (pageCount != null && pageCount > PdfSupport.PDF_AT_MENTION_INLINE_THRESHOLD) {
            if (log.isInfoEnabled()) {
                log.info("ReadFileTool: PDF 页数超阈值拒绝全读 path={} pages={} (CC :949-955)",
                    relPath, pageCount);
            }
            return ToolResult.error(call.id(),
                "This PDF has " + pageCount + " pages, which is too many to read at once. "
                    + "Use the pages parameter to read specific page ranges (e.g., pages: \"1-5\"). "
                    + "Maximum " + PdfSupport.PDF_MAX_PAGES_PER_READ + " pages per request.");
        }

        // CC :957-960 shouldExtractPages = !isPDFSupported() || stats.size > PDF_EXTRACT_SIZE_THRESHOLD
        String mainLoopModel = resolveMainLoopModel(ctx);
        // [pdf-vision-align] 3 参重载：按当前请求模型能力判定（mappers 注入 → supportsImage；null → 回落 1 参 CC 契约）
        boolean pdfSupported = PdfSupport.isPDFSupported(modelMapper, providerMapper, mainLoopModel);
        long fileSize;
        try {
            fileSize = Files.size(file);
        } catch (Exception e) {
            return ToolResult.error(call.id(), "Read error: " + e.getMessage());
        }
        boolean shouldExtractPages = !pdfSupported || fileSize > PdfSupport.getExtractSizeThreshold();
        // [pdf-vision-align] 提为局部变量捕获：文本模型分支复用（避免二次渲染 CPU/内存浪费）；telemetry 日志不变
        PdfSupport.PdfExtractResult extractResult = null;
        if (shouldExtractPages) {
            // CC :962-977 提取结果仅用于 telemetry（真实 CC 行为——返回值不被消费，只发事件）
            extractResult = PdfSupport.extractPDFPages(
                file, pdfOutputDir(ctx), null, null);
            if (extractResult.success()) {
                // CC :965-969 logEvent success {pageCount, fileSize}
                if (log.isInfoEnabled()) {
                    log.info("ReadFileTool: tengu_pdf_page_extraction success=true pageCount={} fileSize={} path={}",
                        extractResult.data().count(), extractResult.data().originalSize(), relPath);
                }
            } else {
                // CC :971-975 logEvent success=false {available: reason !== 'unavailable', fileSize}
                if (log.isInfoEnabled()) {
                    log.info("ReadFileTool: tengu_pdf_page_extraction success=false available={} fileSize={} reason={} path={}",
                        extractResult.error().reason() != PdfSupport.ErrorReason.UNAVAILABLE,
                        fileSize, extractResult.error().reason(), relPath);
                }
            }
        }

        // CC :979-985 !isPDFSupported → throw（提示语保留 CC 结构与 model 指引；
        //   poppler-utils 安装句删除——Java 无 poppler 依赖, pdfbox 进程内渲染）
        if (!pdfSupported) {
            // [pdf-vision-align] 文本模型 PDF 分支：imageStore 注入（Spring 生产）→ 页图注册 + vision_analyze
            //   说明（不回 400，不发 document/image block 给文本模型 → deepseek 400 根因防线）；store 未注入
            //   （非 Spring 单测）→ 回落原 CC error 文案（既有 executePdfHaikuModelReturnsUnsupportedError 不回归）。
            if (imageAttachmentStore != null) {
                return registerTextModelPdfPages(call, file, relPath, ctx, extractResult);
            }
            return ToolResult.error(call.id(),
                "Reading full PDFs is not supported with this model. Use a newer model (Sonnet 3.5 v2 or later), "
                    + "or use the pages parameter to read specific page ranges (e.g., pages: \"1-5\", maximum "
                    + PdfSupport.PDF_MAX_PAGES_PER_READ + " pages per request).");
        }

        // CC :987-990 readPDF → !success → throw
        PdfSupport.PdfReadResult readResult = PdfSupport.readPDF(file);
        if (!readResult.success()) {
            if (log.isInfoEnabled()) {
                log.info("ReadFileTool: PDF 读取失败 path={} reason={} message={}",
                    relPath, readResult.error().reason(), readResult.error().message());
            }
            return ToolResult.error(call.id(), readResult.error().message());
        }
        // CC :992-997 logFileOperation content = base64 → Java 只记 size/mediaType（base64 不落日志）
        if (log.isInfoEnabled()) {
            log.info("ReadFileTool: 读取 PDF path={} size={}B mediaType={}",
                relPath, readResult.data().originalSize(), PdfSupport.PDF_MEDIA_TYPE);
        }
        // CC :999-1016 data = pdfData + newMessages document block
        //   [P-AL-01] document block 真实送达：isMeta user 消息携带 document block（CC
        //   createUserMessage({content:[document block], isMeta:true})），tool_result 载荷只留
        //   summary 文本（renderToolResultPayloadText）—— 20MB base64 不再进单条 tool_result
        List<com.nexusai.model.session.dto.ChatMessageDto> newMessages = java.util.List.of(
            buildDocumentMetaMessage(ctx, readResult.data().base64()));
        if (log.isInfoEnabled()) {
            log.info("ReadFileTool: PDF document block 送达 newMessages path={} size={}B",
                relPath, readResult.data().originalSize());
        }
        return ToolResult.pdf(call.id(),
            "PDF file read: " + relPath + " ("
                + com.nexusai.application.agent.tool.ToolResultStorage.formatFileSize(readResult.data().originalSize()) + ")",
            readResult.data().base64(), PdfSupport.PDF_MEDIA_TYPE, readResult.data().originalSize(),
            newMessages);
    }

    /**
     * [pdf-vision-align] 文本模型 PDF → 页图注册（deepseek 400 根因防线）。
     *
     * <p>复用 {@code dispatchPdfFull} 的 {@code shouldExtractPages} 已提取 extractResult
     * （避免二次渲染 CPU/内存浪费）；失败/未捕获 → 重试 {@link PdfSupport#extractPDFPages} 一次，
     * 再失败 → {@link ToolResult#error}。逐页 JPEG {@code Files.readAllBytes} →
     * {@code imageAttachmentStore.store(sessionId, base64, "image/jpeg")} 收集 contentId
     * （逐页读-注册-释放，不批量驻留全部页 base64）。contentIds 空 → error（fail loud）。
     * 成功返回<b>纯文本</b>成功（含 contentId + vision_analyze 引导），不发 document/image block 给文本模型。
     *
     * @param call          工具调用
     * @param file          PDF 磁盘路径
     * @param relPath       PDF 相对路径（日志/说明用）
     * @param ctx           工具上下文（sessionId 取图片缓存分桶；可 null）
     * @param extractResult shouldExtractPages 已提取结果（可 null = 未提取/防御）
     * @return 文本成功（页图注册 contentId）/ error
     */
    private ToolResult registerTextModelPdfPages(ToolUseBlock call, Path file, String relPath,
                                                 ToolUseContext ctx, PdfSupport.PdfExtractResult extractResult) {
        PdfSupport.PdfExtractData data;
        if (extractResult != null && extractResult.success()) {
            data = extractResult.data();
        } else {
            // 文本模型分支复用失败 / 未捕获（防御）→ 重试提取一次
            PdfSupport.PdfExtractResult retry = PdfSupport.extractPDFPages(
                file, pdfOutputDir(ctx), null, null);
            if (!retry.success()) {
                if (log.isInfoEnabled()) {
                    log.info("ReadFileTool: 文本模型 PDF 页图提取失败 path={} reason={} message={}",
                        relPath, retry.error().reason(), retry.error().message());
                }
                return ToolResult.error(call.id(), retry.error().message());
            }
            data = retry.data();
        }
        String sessionId = ctx != null ? ctx.sessionId() : null;
        java.util.List<Long> contentIds = new java.util.ArrayList<>();
        Path outputDir = Path.of(data.outputDir());
        if (Files.isDirectory(outputDir)) {
            try (var stream = Files.list(outputDir)) {
                java.util.List<Path> jpegs = stream
                    .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                    .sorted()
                    .toList();
                for (Path img : jpegs) {
                    byte[] bytes = Files.readAllBytes(img);
                    ImageAttachmentStore.StoredImage stored = imageAttachmentStore.store(
                        sessionId, Base64.getEncoder().encodeToString(bytes), "image/jpeg");
                    if (stored != null) {
                        contentIds.add(stored.id());
                    }
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("ReadFileTool: 文本模型 PDF 页图注册失败 outputDir={} cause={}", outputDir, e.toString());
                }
                return ToolResult.error(call.id(), "PDF 页图注册失败: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        if (contentIds.isEmpty()) {
            return ToolResult.error(call.id(), "PDF 页图注册为空（渲染失败）");
        }
        // [pdf-vision-align] 页图已注册到 ImageAttachmentStore（base64 进内存/磁盘缓存），
        //   源 JPEG 目录不再需要 → 删除防磁盘累积（Reflect medium；多模态路径保留目录对齐 CC）
        deleteRecursively(outputDir);
        if (log.isInfoEnabled()) {
            log.info("ReadFileTool: 文本模型 PDF → 页图注册 path={} pages={} contentId={}（源页图目录已清理）",
                relPath, contentIds.size(), contentIds);
        }
        return ToolResult.success(call.id(),
            "PDF 当前模型无法直接读取（文本模型）。已把 " + contentIds.size()
                + " 页渲染为图片注册到图片缓存，contentId=" + contentIds
                + "（文件路径=" + relPath + "）。请用 vision_analyze 工具逐页分析：type=analyze, contentId=<页码对应 id>, prompt=<对该页的提问>。");
    }

    /** 递归删除目录（页图源目录清理，防磁盘累积）。文件删除失败 → 忽略（debug 日志，不影响主流程）。 */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug("ReadFileTool: 页图目录清理跳过 {} cause={}", p, e.toString());
                        }
                    }
                });
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("ReadFileTool: 页图目录清理失败 dir={} cause={}", dir, e.toString());
            }
        }
    }

    /**
     * [P-AL-01] document block isMeta user 消息 · 对齐 CC FileReadTool.ts:1001-1015
     * {@code createUserMessage({content: [{type:'document', source:{type:'base64',
     * media_type:'application/pdf', data}}], isMeta: true})}。
     *
     * <p>WHY isMeta=true：模型可见（document block 进 LLM 上下文）、UI 隐藏（CC 语义）。
     * contentBlocks 透传 JsonNode（ChatMessageDto 既有约定，List<JsonNode>），
     * Provider role=user 序列化分支渲染为 content 数组（P-AL-01 新增通道）。
     *
     * @param ctx    工具上下文（null 容忍——纯测试直调；生产恒有 ctx 提供 sessionId）
     * @param base64 PDF 文件 base64（与 data 通道同源）
     * @return isMeta=true 的 user 消息（contentBlocks=[document block]）
     */
    private static com.nexusai.model.session.dto.ChatMessageDto buildDocumentMetaMessage(
            ToolUseContext ctx, String base64) {
        com.fasterxml.jackson.databind.node.ObjectNode block = JSON.createObjectNode();
        block.put("type", "document");
        com.fasterxml.jackson.databind.node.ObjectNode source = block.putObject("source");
        source.put("type", "base64");
        source.put("media_type", PdfSupport.PDF_MEDIA_TYPE);
        source.put("data", base64);
        return metaUserMessageWithBlocks(ctx, java.util.List.of(block));
    }

    /**
     * [P-AL-01] 页图 image blocks isMeta user 消息 · 对齐 CC FileReadTool.ts:916-945：
     * readdir(outputDir) 过滤 .jpg 排序（page-01.jpg, page-02.jpg …）→ base64 →
     * 单条 {@code createUserMessage({content: imageBlocks, isMeta: true})}。
     *
     * <p>CC 有 maybeResizeAndDownsampleImageBuffer（:922-926 resizer）；Java 无 resizer
     * 等价物（12.1-1/△ 登记），media_type 固定 image/jpeg（PdfSupport 100 DPI JPEG 渲染）。
     * 页图读取失败 → 抛异常 → executeInternal catch → error result（CC Promise.all reject →
     * call() throw 同语义）。
     *
     * @param ctx  工具上下文（null 容忍）
     * @param data 提取结果（outputDir + count）
     * @return isMeta=true 的 user 消息（contentBlocks=页图 image blocks）；0 页 → 空列表
     */
    private static List<com.nexusai.model.session.dto.ChatMessageDto> buildPageImagesMetaMessage(
            ToolUseContext ctx, PdfSupport.PdfExtractData data) {
        java.util.List<com.fasterxml.jackson.databind.node.ObjectNode> blocks =
            new java.util.ArrayList<>();
        Path outputDir = Path.of(data.outputDir());
        if (Files.isDirectory(outputDir)) {
            try (java.util.stream.Stream<Path> stream = Files.list(outputDir)) {
                java.util.List<Path> jpegs = stream
                    .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                    .sorted()   // page-01.jpg < page-02.jpg …（CC :917 sort）
                    .toList();
                for (Path imgPath : jpegs) {
                    byte[] imgBytes = Files.readAllBytes(imgPath);
                    String base64 = java.util.Base64.getEncoder().encodeToString(imgBytes);
                    com.fasterxml.jackson.databind.node.ObjectNode block = JSON.createObjectNode();
                    block.put("type", "image");
                    com.fasterxml.jackson.databind.node.ObjectNode source = block.putObject("source");
                    source.put("type", "base64");
                    source.put("media_type", "image/jpeg");
                    source.put("data", base64);
                    blocks.add(block);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("PDF 页图读取失败: " + outputDir + " (" + e.getMessage() + ")", e);
            }
        }
        if (blocks.isEmpty()) {
            return java.util.List.of();
        }
        return java.util.List.of(metaUserMessageWithBlocks(ctx, blocks));
    }

    /**
     * [P-AL-01] isMeta=true user 消息公共构造 · CC createUserMessage({content, isMeta:true})。
     * content 为 String 字段（null）+ contentBlocks 承载块数组（Java record 内部表示，
     * Provider role=user 分支渲染为 content 数组）。
     */
    private static com.nexusai.model.session.dto.ChatMessageDto metaUserMessageWithBlocks(
            ToolUseContext ctx, List<com.fasterxml.jackson.databind.node.ObjectNode> blocks) {
        return new com.nexusai.model.session.dto.ChatMessageDto(
            UUID.randomUUID().toString(),
            ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null,
            com.nexusai.model.session.dto.Role.user,
            "user",
            null, null, null, null, null, null, null, null,
            null, null,
            null, blocks, java.util.List.of(),
            null, true);
    }

    /**
     * PDF 页图输出目录 · 对齐 CC pdf.ts:218 {@code join(getToolResultsDir(), 'pdf-{uuid}')}
     * （getToolResultsDir = projectDir/sessionId/tool-results，toolResultStorage.ts:97-105）。
     *
     * <p>Java 映射：{@code ToolResultStorage.getToolResultsDir(guard.workdir(), sessionId)} +
     * {@code pdf-{uuid}}；ctx == null（纯测试直调，生产恒有 ctx）回落系统临时目录，
     * 不污染 workspace。
     */
    private Path pdfOutputDir(ToolUseContext ctx) {
        Path base = ctx != null
            ? com.nexusai.application.agent.tool.ToolResultStorage.getToolResultsDir(
                guard.workdir(), ctx.sessionId())
            : java.nio.file.Path.of(System.getProperty("java.io.tmpdir", "."));
        return base.resolve("pdf-" + UUID.randomUUID());
    }

    /**
     * 当前主循环模型 · Java appStateRef 语义（LlmAgentLoop:1392 读取键 'mainLoopModel'，
     * 仅 skill inline override 写入）；null/空白 → null（{@link PdfSupport#isPDFSupported} 视为支持，
     * 等价 CC getMainLoopModel() 非 haiku → supported=true）。
     */
    private String resolveMainLoopModel(ToolUseContext ctx) {
        if (ctx == null) {
            return null;
        }
        // [pdf-vision-align 2026-09-02] 优先 appState mainLoopModel（skill inline override），
        //   回落 ctx.effectiveModelName()（AgentLoopContext.toolExecContext 从 state.currentModel()
        //   注入的当前 turn 真实模型，见 ToolUseContext.effectiveModelName）。
        //   WHY：原实现只读 appState 键，该键正常 turn 恒为 null（仅 SkillTool/AdvisorCommand 写入）
        //   → 3 参 isPDFSupported(null model + 生产 mapper 非 null) 经 ModelCapabilityResolver
        //   supportsImage 保守返回 false → 多模态模型读 PDF 被误判文本模型（Reflect HIGH 回归）。
        String fromAppState = null;
        if (ctx.getAppState() != null) {
            try {
                java.util.Map<String, Object> snapshot = ctx.getAppState().apply(null);
                if (snapshot != null) {
                    Object model = snapshot.get("mainLoopModel");
                    if (model != null) {
                        fromAppState = String.valueOf(model);
                    }
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("ReadFileTool: appState 读取 mainLoopModel 失败, 回落 effectiveModelName: cause={}", e.toString());
                }
            }
        }
        if (fromAppState != null && !fromAppState.isBlank()) {
            return fromAppState;
        }
        String eff = ctx.effectiveModelName();
        return (eff == null || eff.isBlank()) ? null : eff;
    }

    /**
     * image 分支 · 对齐 CC FileReadTool.ts:866-891.
     *
     * <p>[OPD-TOOL-06-3b] 原字节直发改为 token 预算 + 标准缩放 + 超预算激进压缩：
     * {@link ImageResizer#readImageWithTokenBudget(byte[], int)} 镜像 CC
     * FileReadTool.ts:1097-1183 readImageWithTokenBudget + imageResizer.ts。防大图 base64
     * 击穿上下文窗口。不写入 readFileState、不通知 listener（CC 注释："images aren't cached in
     * readFileState so won't match here"）。
     *
     * <p>[D4/R2] {@code maxTokens} 为有效值（ctx.fileReadingLimits().maxTokens ?? 默认，CC
     * FileReadTool.ts:507）—— 与 {@code dispatchText}/{@code dispatchNotebook} 同源
     * {@code effectiveMaxTokens}，由调用侧 {@code executeInternal} 统一解析后透传（对齐 CC
     * call() 单一 maxTokens 变量 :507 供各分支消费）。R2 遗留：旧实现硬编码
     * {@link FileReadingLimits#DEFAULT_MAX_OUTPUT_TOKENS}，per-session override 对图片无效。
     *
     * @param maxTokens token 预算（override ?? default 的有效值）
     */
    private ToolResult dispatchImage(ToolUseBlock call, Path file, String relPath,
                                     String ext, ToolUseContext ctx, int maxTokens) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        long originalSize = bytes.length;
        try {
            // CC FileReadTool.ts:869 readImageWithTokenBudget(resolvedFilePath, maxTokens)
            //   maxTokens = 有效值（ctx.fileReadingLimits().maxTokens ?? getDefaultFileReadingLimits().maxTokens，
            //   CC FileReadTool.ts:507）—— 无 override 时等于 DEFAULT_MAX_OUTPUT_TOKENS=25000
            ImageResizer.Result resized = ImageResizer.readImageWithTokenBudget(
                bytes, maxTokens);
            String mediaType = resized.mediaType();
            ImageDimensions dimensions = resized.dimensions();
            if (log.isInfoEnabled()) {
                log.info("ReadFileTool: 读取图片 {} 字节 path={} mediaType={} 原始尺寸={}x{} display尺寸={}x{}",
                    originalSize, relPath, mediaType,
                    dimensions != null && dimensions.originalWidth() != null ? dimensions.originalWidth() : "?",
                    dimensions != null && dimensions.originalHeight() != null ? dimensions.originalHeight() : "?",
                    dimensions != null && dimensions.displayWidth() != null ? dimensions.displayWidth() : "?",
                    dimensions != null && dimensions.displayHeight() != null ? dimensions.displayHeight() : "?");
            }

            // [ODF-B4R-LAZY] 触发集生产者 · 对齐 CC FileReadTool.ts:870（image 分支读成功 → 写触发集）
            triggerNestedMemoryAttachment(ctx, file);

            // [rv-b-r1 gap2] 图片 metadata 文本消息 · 对齐 CC FileReadTool.ts:879-890：
            //   data.file.dimensions ? createImageMetadataText(dimensions) : null；非 null 时
            //   newMessages=[createUserMessage({content: metadataText, isMeta:true})]。
            //   仅 standalone image 分支调用（notebook cell 输出图片不调，CC utils/notebook.ts:134-153）。
            String metadataText = dimensions != null ? createImageMetadataText(dimensions) : null;
            java.util.List<com.nexusai.model.session.dto.ChatMessageDto> newMessages =
                metadataText != null
                    ? java.util.List.of(metaTextUserMessage(ctx, metadataText))
                    : java.util.List.of();
            if (metadataText != null && log.isDebugEnabled()) {
                log.debug("ReadFileTool: 图片 metadata 文本消息 path={} metadata={}", relPath, metadataText);
            }

            return ToolResult.image(call.id(),
                "Read image " + relPath + " (" + originalSize + " bytes, " + mediaType + ")",
                resized.base64(), mediaType, originalSize, dimensions, newMessages);
        } catch (ImageResizeError e) {
            // 对齐 CC throw（imageResizer.ts:424/:572）→ 用户友好图片错误（原字节直发路径已删，无兼容壳）
            if (log.isWarnEnabled()) {
                log.warn("ReadFileTool: 图片处理失败 (超预算且压缩失败): path={} cause={}",
                    relPath, e.getMessage());
            }
            return ToolResult.error(call.id(), e.getMessage());
        }
    }

    /**
     * [rv-b-r1 gap2] 图片 metadata 文本 · 对齐 CC {@code imageResizer.ts:835-880}
     * {@code createImageMetadataText(dims, sourcePath?)}。FileReadTool 调用时不传 sourcePath。
     *
     * <p>CC 真源（不信注释）：display dims 缺失/&lt;=0 或（未 resize 且无 sourcePath）返回 null；
     * wasResized=originalWidth!==displayWidth||originalHeight!==displayHeight 时返回
     * {@code [Image: original WxH, displayed at wxh. Multiply coordinates by N.NN to map to original image.]}。
     */
    private static String createImageMetadataText(ImageDimensions dims) {
        Integer originalWidth = dims.originalWidth();
        Integer originalHeight = dims.originalHeight();
        Integer displayWidth = dims.displayWidth();
        Integer displayHeight = dims.displayHeight();
        if (originalWidth == null || originalHeight == null
                || displayWidth == null || displayHeight == null
                || originalWidth <= 0 || originalHeight <= 0
                || displayWidth <= 0 || displayHeight <= 0) {
            return null;
        }
        boolean wasResized = !originalWidth.equals(displayWidth) || !originalHeight.equals(displayHeight);
        if (!wasResized) {
            // 无 sourcePath 且未 resize → 无有用 metadata（CC :861 return null）
            return null;
        }
        double scaleFactor = (double) originalWidth / displayWidth;
        return "[Image: original " + originalWidth + "x" + originalHeight
                + ", displayed at " + displayWidth + "x" + displayHeight
                + ". Multiply coordinates by "
                + String.format(java.util.Locale.ROOT, "%.2f", scaleFactor)
                + " to map to original image.]";
    }

    /**
     * [rv-b-r1 gap2] 文本 isMeta user 消息 · 对齐 CC {@code createUserMessage({content, isMeta:true})}
     * （FileReadTool.ts:886-888）。与 {@link #metaUserMessageWithBlocks} 同源，content 为纯文本 String
     * （非 contentBlocks）。
     */
    private static com.nexusai.model.session.dto.ChatMessageDto metaTextUserMessage(
            ToolUseContext ctx, String text) {
        return new com.nexusai.model.session.dto.ChatMessageDto(
            UUID.randomUUID().toString(),
            ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null,
            com.nexusai.model.session.dto.Role.user,
            "user",
            text,
            null, null, null, null, null, null, null,
            null, null,
            null, null, java.util.List.of(),
            null, true);
    }

    /**
     * notebook 分支 · 对齐 CC FileReadTool.ts:822-863.
     *
     * <p>读 .ipynb JSON → {@link NotebookCellProcessor#processNotebook} 处理 cells →
     * ToolResult.notebook（data(JsonNode) 折入处理后的 cells）。
     * <p>[OPD-TOOL-06-3c] 补 CC 缺失的两段校验：① size 上限（cellsJson UTF-8 字节 &gt; 256KB →
     * 报错附 Bash jq 提示，对齐 CC :826-835）；② token 上限（cellsJson 字符 &gt; 100k →
     * 报错，对齐 CC validateContentTokens :838 + MaxFileReadTokenExceededError :175-185）。
     * <p>[rv-b-r1 gap1] readFileState.set（CC :842 notebook 分支确有写入）：content=处理后的
     * cellsJson（非 raw 文件），timestamp=独立 stat mtime（CC :841），offset/limit 存本次入参。
     */
    private ToolResult dispatchNotebook(ToolUseBlock call, Path file, String relPath,
                                        Integer offset, Integer limit, ToolUseContext ctx,
                                        int maxSizeBytes, int maxTokens) throws Exception {
        String content = Files.readString(file);
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (Exception e) {
            return ToolResult.error(call.id(),
                "Invalid notebook JSON in " + relPath + ": " + e.getMessage());
        }
        // [rv-b-r1 gap3] readNotebook 处理 cells → NotebookCellSource[]（CC FileReadTool.ts:822-824
        //   readNotebook(resolvedFilePath) 返回已处理 cells → jsonStringify(cells)）。
        java.util.List<NotebookCellProcessor.NotebookCellSource> cells =
            NotebookCellProcessor.processNotebook(root);
        String cellsJson = NotebookCellProcessor.serializeCells(cells);
        ArrayNode cellsNode = NotebookCellProcessor.cellsToJsonNode(cells);
        String renderedCells = NotebookCellProcessor.renderCells(cells);

        // [OPD-TOOL-06-3c] ① size 上限校验 · 对齐 CC FileReadTool.ts:826-835：
        //   cellsJsonBytes = Buffer.byteLength(cellsJson)（UTF-8 字节）；> maxSizeBytes
        //   → 报错附 Bash jq 提示（4 条示例逐字对齐 CC :831-834）。Java 用
        //   cellsJson.getBytes(UTF_8).length 等价 Buffer.byteLength（多字节字符按 UTF-8 计字节，
        //   而非 cellsJson.length() 字符数）。
        // [OPD-D1-01] maxSizeBytes 为覆盖后的有效值：ctx.fileReadingLimits().maxSizeBytes ?? getDefault()（CC :505-506）。
        int cellsJsonBytes = cellsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (cellsJsonBytes > maxSizeBytes) {
            return ToolResult.error(call.id(),
                "Notebook content ("
                    + com.nexusai.application.agent.tool.ToolResultStorage.formatFileSize(cellsJsonBytes)
                    + ") exceeds maximum allowed size ("
                    + com.nexusai.application.agent.tool.ToolResultStorage.formatFileSize(maxSizeBytes)
                    + "). Use " + com.nexusai.application.agent.tool.ToolNameConstants.BASH_TOOL_NAME
                    + " with jq to read specific portions:\n"
                    + "  cat \"" + relPath + "\" | jq '.cells[:20]' # First 20 cells\n"
                    + "  cat \"" + relPath + "\" | jq '.cells[100:120]' # Cells 100-120\n"
                    + "  cat \"" + relPath + "\" | jq '.cells | length' # Count total cells\n"
                    + "  cat \"" + relPath + "\" | jq '.cells[] | select(.cell_type==\"code\") | .source' # All code sources");
        }

        // [OPD-TOOL-06-3c] ② token 上限校验 · 对齐 CC FileReadTool.ts:838 validateContentTokens
        //   → MaxFileReadTokenExceededError（CC :175-185）。Java 沿用 dispatchText 字符近似口径
        //   （1 token ≈ 4 chars，25000 tokens ≈ 100k chars，无 tokenizer 依赖 — ReadFileToolTest
        //   禁止加 pom 依赖）。
        // [OPD-D1-01] maxTokens 为覆盖后的有效值：ctx.fileReadingLimits().maxTokens ?? getDefault()（CC :507）；
        //   字符上限 = maxTokens * 4（沿用 GAP-D 1 token ≈ 4 chars 近似）。
        if (cellsJson.length() > (long) maxTokens * 4) {
            return ToolResult.error(call.id(),
                "File content (" + (cellsJson.length() / 4) + " tokens estimated) exceeds maximum allowed tokens ("
                    + maxTokens + "). Use offset and limit parameters to read specific "
                    + "portions of the file, or search for specific content instead of reading the whole file.");
        }

        // [OPD-TOOL-06-3c] ③ 数据流日志（slf4j + 中文 + isDebug 包裹，硬约束 4）
        if (log.isDebugEnabled()) {
            log.debug("ReadFileTool: notebook 读取 cellsJson 字节={} token 估算={} path={} cells={}",
                cellsJsonBytes, cellsJson.length() / 4, relPath, cells.size());
        }

        if (log.isInfoEnabled()) {
            log.info("ReadFileTool: 读取 notebook path={} cells={}", relPath, cells.size());
        }

        // [rv-b-r1 gap1] readFileState.set · 对齐 CC FileReadTool.ts:841-847：notebook 分支读成功后
        //   写 ReadState（content=处理后的 cellsJson，非 raw 文件内容；timestamp=独立 stat mtime；
        //   offset/limit 存本次入参）。CC :841 独立 stat（callInner 与 dedup stat 分离），Java 复刻。
        if (ctx != null) {
            long notebookMtime;
            try {
                notebookMtime = Files.getLastModifiedTime(file).toMillis();
            } catch (Exception e) {
                notebookMtime = 0L;
                if (log.isDebugEnabled()) {
                    log.debug("ReadFileTool: notebook stat 失败, mtime=0 path={} cause={}",
                        relPath, e.toString());
                }
            }
            String keyForCache = ToolUseContext.keyForReadFileState(guard, relPath);
            ctx.readFileState().set(keyForCache,
                new ReadState(notebookMtime, offset, limit, false, cellsJson));
        }

        // [ODF-B4R-LAZY] 触发集生产者 · 对齐 CC FileReadTool.ts:848（notebook 分支读成功 → 写触发集）
        triggerNestedMemoryAttachment(ctx, file);

        return ToolResult.notebook(call.id(),
            "Read notebook " + relPath + " (" + cells.size() + " cells)",
            cellsNode, cellsJson, renderedCells, relPath);
    }

    /**
     * P1-2: 动态技能发现 + 条件技能激活 · 对齐 CC FileReadTool.ts:575-591。
     *
     * <p>CC original（FileReadTool.ts:579-590）：
     * <pre>
     *   if (!isEnvTruthy(process.env.CLAUDE_CODE_SIMPLE)) {
     *     const newSkillDirs = await discoverSkillDirsForPaths([fullFilePath], cwd)
     *     if (newSkillDirs.length > 0) {
     *       for (const dir of newSkillDirs) context.dynamicSkillDirTriggers?.add(dir)
     *       addSkillDirectories(newSkillDirs).catch(() => {})     // fire-and-forget
     *     }
     *     activateConditionalSkillsForPaths([fullFilePath], cwd)
     *   }
     * </pre>
     * Java 会话级 bare 判定（用户 2026-08-23 拍板 bareMode 随会话走，V33 列 bare_mode →
     * 回落 {@code nexusai.memory.bare-mode} / env CLAUDE_CODE_SIMPLE / false）：bare 会话跳过
     * 技能目录遍历；非 bare 恒触发；try-catch 不阻塞主链。
     *
     * @param ctx          工具调用上下文（null → 跳过）
     * @param fullFilePath 归一化绝对文件路径
     */
    private void triggerDynamicSkillDiscovery(ToolUseContext ctx, Path fullFilePath) {
        if (dynamicSkillsManager == null || ctx == null || ctx.effectiveCwd() == null) {
            return;
        }
        // [G24-bare] 动态技能发现门控 · 对齐 CC FileReadTool.ts:578
        //   `if (!isEnvTruthy(process.env.CLAUDE_CODE_SIMPLE))` —— bare（SIMPLE）模式跳过
        //   discoverSkillDirsForPaths + activateConditionalSkillsForPaths（skill 目录遍历）。
        //   Web 端无 simple mode 概念 → Java 会话级判定（bareMode 随会话走，V33 列）。
        if (MemoryBareModeConfig.isBareMode(ctx.sessionId())) {
            if (log.isDebugEnabled()) {
                log.debug("ReadFileTool: bare 模式跳过动态技能发现（CC FileReadTool.ts:578 SIMPLE 门控, 会话 {}）",
                    ctx.sessionId());
            }
            return;
        }
        try {
            // CC :579 discoverSkillDirsForPaths([fullFilePath], cwd)
            java.util.List<String> newSkillDirs = dynamicSkillsManager.discoverSkillDirsForPaths(
                java.util.List.of(fullFilePath.toString()), ctx.effectiveCwd());
            if (!newSkillDirs.isEmpty()) {
                // CC :583 context.dynamicSkillDirTriggers?.add(dir)
                for (String dir : newSkillDirs) {
                    ctx.dynamicSkillDirTriggers().add(dir);
                }
                // CC :586 addSkillDirectories(newSkillDirs).catch(()=>{}) —— fire-and-forget
                // WHY 后台执行: CC FileReadTool.ts:585 "Don't await - let skill loading happen
                //   in the background" —— 目录扫描 + SKILL.md 解析是 IO，同步执行会阻塞 Read
                //   工具调用链。CompletableFuture.runAsync 不 await（项目既有 fire-and-forget
                //   模式，Grove.java:137/AgentLoopContext.java:600 先例）；异常吞掉 = CC
                //   .catch(()=>{})。并发安全: manager 内 4 状态全 ConcurrentHashMap，
                //   onChange→SkillRegistry.refresh() 清 ConcurrentHashMap 缓存（A19 对齐）。
                java.util.List<String> dirsToLoad = java.util.List.copyOf(newSkillDirs);
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        dynamicSkillsManager.addSkillDirectories(dirsToLoad);
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug("ReadFileTool: 后台动态技能加载失败（不阻塞工具调用，CC .catch(()=>{}) 等价）: cause={}",
                                e.toString());
                        }
                    }
                });
            }
            // CC :590 activateConditionalSkillsForPaths([fullFilePath], cwd)
            dynamicSkillsManager.activateConditionalSkillsForPaths(
                java.util.List.of(fullFilePath.toString()), ctx.effectiveCwd());
        } catch (Exception e) {
            // 技能发现失败不阻塞读取（CC .catch(()=>{}) 等价）
            if (log.isDebugEnabled()) {
                log.debug("ReadFileTool: 动态技能发现失败, 不阻塞工具: cause={}", e.toString());
            }
        }
    }
}
