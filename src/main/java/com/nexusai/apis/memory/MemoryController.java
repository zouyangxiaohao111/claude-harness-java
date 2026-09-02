package com.nexusai.apis.memory;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.context.ClaudemdMemoryType;
import com.nexusai.application.agent.context.MemoryFileInfo;
import com.nexusai.application.agent.memory.ConsolidationLock;
import com.nexusai.application.agent.memory.MemoryStorage;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.exception.ForbiddenException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /memory 命令 REST 等价 · OPD-CM3-25 用户拍板（2026-08-15）：实现 REST 等价——/memory 查看记忆
 * + /session 查看会话的 HTTP 端点。本控制器承载 <b>/memory</b> 语义（/session 由
 * {@link com.nexusai.apis.session.SessionController} 会话 CRUD 承载，复用不重建）。
 *
 * <p>对齐 CC {@code commands/memory/memory.tsx} 命令行为（F/CM-F3 MISS-1/MISS-2）：
 * <table>
 *   <tr><th>CC 行为</th><th>CC 锚点</th><th>REST 等价</th></tr>
 *   <tr><td>缓存预热（call 先 clear+get 再渲染）</td><td>memory.tsx:83-89</td>
 *       <td>GET /files 先 {@code clearMemoryFileCaches()} + {@code getMemoryFiles()}</td></tr>
 *   <tr><td>状态行（auto-memory/auto-dream toggle + dream 状态）</td><td>MemoryFileSelector.tsx:206-254</td>
 *       <td>GET/PUT /config 返回/更新 {@code {autoMemoryEnabled, autoDreamEnabled, dreamStatus, lastConsolidatedAtMs}}</td></tr>
 *   <tr><td>查看记忆文件列表（三档 type：Managed/User/Project）</td><td>MemoryFileSelector.tsx:50-65</td>
 *       <td>GET /files 返回 {@code {path,type,content,parent,exists,file,editable}} 列表</td></tr>
 *   <tr><td>文件创建（mkdir configHome + writeFile 'wx'，EEXIST 保留原内容）</td><td>memory.tsx:21-42</td>
 *       <td>POST /files 幂等创建并返回 {@code {path,relativePath,created,content,message}}</td></tr>
 *   <tr><td>文件更新（writeFile 'w' 覆盖写，已存在文件）</td><td>memory.tsx:32-41</td>
 *       <td>PUT /files[?sessionId=] type-based 覆盖写并返回 {@code {type,file,path,relativePath,content,message}}</td></tr>
 * </table>
 *
 * <p>「编辑器流」的 web 载体：CC TUI 调 {@code editFileInEditor(memoryPath)}（memory.tsx:42）由前端
 * 编辑器承担 —— 本端点保证文件存在并回传 content（存在则以磁盘现有内容回传），前端直接渲染编辑。
 *
 * <p>路径显示（"Opened memory file at {relativePath}"，memory.tsx:56）：{@link #getRelativeMemoryPath}
 * 对齐 CC {@code getRelativeMemoryPath}（MemoryUpdateNotification.tsx:7-20）：home 相对（{@code ~/...}）
 * 与 cwd 相对（{@code ./...}）取短者。
 *
 * <p><b>失败处理</b>（对齐 03 §4.2「REST 端点错误 → 4xx/5xx 结构化返回」）：path/type 空/缺失 →
 * {@link ValidationException}（400）；文件系统 IO 失败 → 500（CC onDone
 * "Error opening memory file: {error}" 的 REST 表达）。ClaudemdEngine 未接线 → 500（fail loud，
 * 记忆查看是本端点唯一职责，无静默降级）。
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    /** claudemd 记忆文件引擎（getMemoryFiles / clearMemoryFileCaches）· @Bean 注册自动装配
     *  （ToolRegistrationConfig.claudemdEngine），required=false 容错单测反射注入。 */
    @Autowired(required = false)
    private ClaudemdEngine claudemdEngine;
    /** memory 存储层（dream 锁 mtime 读取 · {@link ConsolidationLock} 锁目录）· @Bean 注册自动
     *  装配（ToolRegistrationConfig.memoryStorage），required=false 容错单测反射注入。 */
    @Autowired(required = false)
    private MemoryStorage memoryStorage;
    /** 遥测发射器（CC logEvent 等价 · Telemetry.recordEvent）· @Autowired(required=false) 容错
     *  单测不注入（null → 静默跳过，对齐 CC logEvent 可空上下文惯例）。 */
    @Autowired(required = false)
    private Telemetry telemetry;

    /** 工作目录 · CC original: getOriginalCwd()（MemoryFileSelector.tsx:52 projectMemoryPath）。
     *  经统一入口 {@link CwdResolution#getOriginalCwdLayer()}（REST 无 MDC sessionId → 回落
     *  user.dir，对齐 CC 进程启动 cwd；与 ClaudemdEngine originalCwdSupplier 同源，ClaudemdEngine:163-171）。 */
    private String originalCwd() {
        String resolved = CwdResolution.getOriginalCwdLayer();
        if (log.isDebugEnabled()) {
            log.debug("[MemoryController] originalCwd 解析: {}", resolved);
        }
        return resolved;
    }

    /**
     * GET /api/v1/memory/files[?sessionId=] · 查看记忆（缓存预热 + 三档 type 列表）。
     *
     * <p>对齐 CC {@code call}（memory.tsx:83-89）：{@code clearMemoryFileCaches(); await getMemoryFiles();}
     * —— REST 每次查看先失效再加载，等价 CC 打开 /memory 对话框前的预热（TUI 的 Suspense fallback
     * flash 规避在 web 端无对应物，预热语义保留）。
     *
     * <p>列表装配为<b>三档 type 契约</b>（前端记忆编辑器从"传 path"改为"传 type"）：
     * <ol>
     *   <li><b>Managed</b>（全局只读）：{@code getManagedFilePath()/CLAUDE.md}，editable=<b>false</b>，
     *       content=磁盘内容（无则空串）；</li>
     *   <li><b>User</b>（用户可写）：{@code nexusaiHome/CLAUDE.md}（读 nexusai 缺失回落 ~/.claude/CLAUDE.md），
     *       editable=<b>true</b>；</li>
     *   <li><b>Project</b>（会话级可写）：{@code getMemoryFiles(false)} 中 type==PROJECT 的文件，
     *       file=相对 boundProject 路径，editable=true（<b>[D6 严格化] .claude 段恒 false</b>，
     *       只读回落仍列出供只读展示；项目根 CLAUDE.md 保留可写）；<b>无 sessionId → 空列表</b>
     *       （读宽容，不 400）。</li>
     * </ol>
     * 顺序：Managed → User → Project。path 字段=绝对路径（仅展示，前端不回传）。
     *
     * <p>会话机制：解析 sessionId（query {@code ?sessionId=} → MDC 兜底），非 null 先
     * {@code RequestContext.setSession(sessionId)} 写 MDC → {@code CwdResolution.getOriginalCwdLayer()}
     * 自动走 boundProject（SessionProjectRoot.getForSession）。GET/PUT 的 Project 档都先
     * {@code clearMemoryFileCaches()} 再 {@code getMemoryFiles(false)}（防 memoize 跨会话污染）。
     *
     * @param sessionIdParam query {@code ?sessionId=}（可选；Project 档会话锚定）
     * @return 记忆文件视图列表（{path,type,content,parent,exists,file,editable}）
     */
    @GetMapping(value = "/files", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<MemoryFileView> listFiles(
            @RequestParam(value = "sessionId", required = false) String sessionIdParam) {
        ClaudemdEngine engine = resolveEngine();
        if (log.isInfoEnabled()) {
            log.info("[MemoryController] GET /memory/files 查看记忆开始（缓存预热 + 加载）");
        }
        // 解析 sessionId：query ?sessionId= → MDC 兜底；非 null 写 MDC（驱动 CwdResolution boundProject 层）
        String sessionId = (sessionIdParam != null && !sessionIdParam.isBlank())
            ? sessionIdParam : RequestContext.sessionId();
        if (sessionId != null) {
            RequestContext.setSession(sessionId);
        }
        // memory.tsx:86-87 缓存预热：clear + get（CC call 在渲染前预热）
        engine.clearMemoryFileCaches();
        List<MemoryFileInfo> existing = engine.getMemoryFiles(false);

        List<MemoryFileView> views = new ArrayList<>();

        // 1. Managed（全局只读）：getManagedFilePath()/CLAUDE.md
        Path managedPath = Paths.get(ClaudePaths.getManagedFilePath(), "CLAUDE.md");
        views.add(new MemoryFileView(managedPath.toString(), "Managed", readContentIfExists(managedPath),
            null, Files.isRegularFile(managedPath), "CLAUDE.md", false));

        // 2. User（用户可写）：{nexusaiHome}/CLAUDE.md；读 nexusai 缺失回落 claude（决策 D1/D2 兼容读）
        Path userPath = userMemoryPath();
        Path userReadPath = Files.isRegularFile(userPath)
            ? userPath : Paths.get(ClaudePaths.getClaudeConfigHomeDir(), "CLAUDE.md");
        views.add(new MemoryFileView(userPath.toString(), "User", readContentIfExists(userReadPath),
            null, Files.isRegularFile(userPath), "CLAUDE.md", true));

        // 3. Project（会话级可写）：getMemoryFiles 中 type==PROJECT 的文件，file=相对 boundProject
        if (sessionId != null) {
            String boundProject = CwdResolution.getOriginalCwdLayer();
            Path boundProjectAbs = Paths.get(boundProject).toAbsolutePath().normalize();
            for (MemoryFileInfo file : existing) {
                if (file.type() == ClaudemdMemoryType.PROJECT) {
                    Path fileAbs = Paths.get(file.path()).toAbsolutePath().normalize();
                    String rel = boundProjectAbs.relativize(fileAbs).toString();
                    // [D6 严格化] editable 同步收敛：.claude 段恒 false（只读回落，仍列出供只读展示）；
                    //   项目根 CLAUDE.md（非 .claude 非 .nexusai）保留可写（决策点 A）
                    boolean editable = !containsClaudeDir(fileAbs);
                    views.add(new MemoryFileView(file.path(), "Project", file.content(),
                        file.parent(), true, rel, editable));
                }
            }
        }
        // 无 sessionId → Project 档返回空列表（读宽容，不 400）
        if (log.isInfoEnabled()) {
            log.info("[MemoryController] GET /memory/files 完成: {} 个文件（sessionId={}）",
                views.size(), sessionId);
        }
        return views;
    }

    /**
     * 读磁盘文件内容 · 文件不存在或非普通文件 → 空串（GET 展示宽容；读取 IO 失败 → warn + 空串，
     * 不阻断列表装配 —— Managed/User 档 content 的磁盘源）。
     */
    /**
     * User 档记忆文件（决策 D1：nexusai 自有根）：{@code ~/.{appName}/CLAUDE.md}。
     * 写 nexusai、不写 claude；读缺失时由调用方回落 {@code ~/.claude/CLAUDE.md}（兼容读）。
     */
    private static Path userMemoryPath() {
        return Paths.get(NexusaiPaths.getAppConfigHomeDir(), "CLAUDE.md");
    }

    private static String readContentIfExists(Path p) {
        if (Files.isRegularFile(p)) {
            try {
                return Files.readString(p);
            } catch (IOException e) {
                log.warn("[MemoryController] 读取记忆文件内容失败（回退空串）: path={} err={}", p, e.getMessage());
                return "";
            }
        }
        return "";
    }
    /**
     * GET /api/v1/memory/config · /memory UI 状态行 REST 承载（toggle + dream 状态）。
     *
     * <p>对齐 CC MemoryFileSelector（MemoryFileSelector.tsx:206-233）：
     * <ul>
     *   <li><b>auto-memory toggle</b>（:206/:240-245）—— {@code isAutoMemoryEnabled()}
     *       （paths.ts:30-56 全链：env 禁用/bare/remote/settings/默认 true）→
     *       {@link BundledSkillEnabledGates#isAutoMemoryEnabled()}。</li>
     *   <li><b>auto-dream toggle</b>（:207/:247-252）—— {@code isAutoDreamEnabled()}
     *       （config.ts:13-21）→ <b>[V56 · 用户 2026-08-30 拍板]</b> DB settings 列
     *       auto_dream_enabled 优先（{@link BundledSkillEnabledGates#readAutoDreamEnabledSetting()}，
     *       仅读 DB 列，弃 settings.json 文件承载键）→ env NEXUSAI_AUTO_DREAM 可选覆盖 →
     *       默认 true（默认开）。与运行时门控 AutoDreamConsolidator
     *       {@code isAutoDreamEnabledBySettingsOrEnv} 同源，GET config 不再与运行态矛盾。</li>
     *   <li><b>dream 状态</b>（:216-233）—— {@code readLastConsolidatedAt()} 锁 mtime
     *       （consolidationLock.ts:29-36，0 = 从未）→ status {@code never}/{@code last_ran} +
     *       lastConsolidatedAtMs。CC {@code running}（AppState tasks 进程内任务面板状态）REST
     *       快照无订阅等价物；运行中锁 mtime 已于 fork 时推进至 now → last_ran(刚刚) 近等价
     *       （✗-3 低危展示性，差异登记 progress/IMP-MV2-16.md）。</li>
     * </ul>
     *
     * @return {@code {autoMemoryEnabled, autoDreamEnabled, dreamStatus, lastConsolidatedAtMs}}
     */
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public MemoryConfigView getConfig() {
        MemoryStorage storage = resolveMemoryStorage();
        // CC isAutoMemoryEnabled()（paths.ts:30-56）—— 有效门控（env/settings/默认）
        boolean autoMemory = BundledSkillEnabledGates.isAutoMemoryEnabled();
        // CC isAutoDreamEnabled()（config.ts:13-21）—— [V56] DB auto_dream_enabled 优先 →
        //   env NEXUSAI_AUTO_DREAM 可选覆盖 → 默认 true（与运行时门控 AutoDreamConsolidator
        //   isAutoDreamEnabledBySettingsOrEnv 一致，前端 toggle 不再与实际 auto-dream 运行矛盾）
        boolean autoDream = isAutoDreamEnabledByRuntimeChain();
        // CC readLastConsolidatedAt()（consolidationLock.ts:29-36）—— 锁 mtime，0 = 从未
        long lastConsolidatedAtMs = new ConsolidationLock(storage.memoryDir()).readLastConsolidatedAt();
        String dreamStatus = lastConsolidatedAtMs == 0 ? "never" : "last_ran";
        if (log.isInfoEnabled()) {
            log.info("[MemoryController] GET /memory/config: autoMemory={} autoDream={} dreamStatus={} lastConsolidatedAtMs={}",
                autoMemory, autoDream, dreamStatus, lastConsolidatedAtMs);
        }
        return new MemoryConfigView(autoMemory, autoDream, dreamStatus, lastConsolidatedAtMs);
    }

    /**
     * PUT /api/v1/memory/config · auto-memory/auto-dream toggle 写（部分更新）。
     *
     * <p>对齐 CC {@code updateSettingsForSource('userSettings', {autoMemoryEnabled|autoDreamEnabled})}
     * （MemoryFileSelector.tsx:240-254 + settings.ts:416-524）：<b>[V56 · 用户 2026-08-30 拍板]</b>
     * autoDreamEnabled 仅落 DB settings 列（V56 auto_dream_enabled，弃 settings.json 文件承载键）；
     * autoMemoryEnabled 落 DB 列 + settings.json 双写（读链回落兼容）。null 字段不触碰 —— 运行期
     * 门控（BundledSkillEnabledGates / AutoDreamConsolidator）读 DB 列，写后立即生效。返回更新后
     * 完整状态视图。
     *
     * <p>遥测（MemoryFileSelector.tsx:244/:251 {@code logEvent('tengu_auto_memory_toggled' |
     * 'tengu_auto_dream_toggled', {enabled})}）：非 null 字段各自发射对应事件（enabled=新值），
     * 对齐 CC logEvent 语义；telemetry 未注入（null）→ 静默跳过。
     *
     * @param update 部分更新 {@code {autoMemoryEnabled?, autoDreamEnabled?}}
     * @return 更新后 {@code {autoMemoryEnabled, autoDreamEnabled, dreamStatus, lastConsolidatedAtMs}}
     */
    @PutMapping(value = "/config", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public MemoryConfigView updateConfig(@RequestBody(required = false) MemoryConfigUpdate update) {
        Boolean autoMemory = update == null ? null : update.autoMemoryEnabled();
        Boolean autoDream = update == null ? null : update.autoDreamEnabled();
        if (autoMemory != null || autoDream != null) {
            BundledSkillEnabledGates.writeAutoMemoryToggles(autoMemory, autoDream);
            // MemoryFileSelector.tsx:244/:251：toggle 事件（enabled=新值）
            if (telemetry != null) {
                if (autoMemory != null) {
                    telemetry.recordEvent("tengu_auto_memory_toggled", Map.of("enabled", autoMemory));
                }
                if (autoDream != null) {
                    telemetry.recordEvent("tengu_auto_dream_toggled", Map.of("enabled", autoDream));
                }
            }
        }
        return getConfig();
    }

    /**
     * 解析 memory 存储层 · 未接线 → 500（fail loud：dream 状态依赖锁 mtime，无静默降级）。
     */
    private MemoryStorage resolveMemoryStorage() {
        MemoryStorage storage = memoryStorage;
        if (storage == null) {
            log.error("[MemoryController] memoryStorage 未接线 → /memory config（dream 状态）不可用（fail loud）");
            throw new IllegalStateException("memoryStorage not wired (MemoryController /memory config unavailable)");
        }
        return storage;
    }

    /**
     * [V56 · 用户 2026-08-30 拍板] auto-dream 有效门控 · 镜像运行时门控链
     * {@code AutoDreamConsolidator.isAutoDreamEnabledBySettingsOrEnv()}（DB 优先 → env 可选覆盖 →
     * 默认 true）：
     * <ol>
     *   <li>DB settings 列 auto_dream_enabled（{@link BundledSkillEnabledGates
     *       #readAutoDreamEnabledSetting()}，V56 建列，弃 settings.json 文件承载键）有值用之
     *       （前端可配，对齐 autoMemory V34 先例）；</li>
     *   <li>未配置 DB → env NEXUSAI_AUTO_DREAM 可选强制覆盖（property 优先，测试可注入 ·
     *       AutoDreamConsolidator.resolveEnv 同源；非 blank 经 {@link TaskSystemConfig#isEnvTruthy}
     *       判定）；</li>
     *   <li>DB 与 env 均未配置 → <b>默认 true（默认开）</b>。</li>
     * </ol>
     * <b>修复 P0 矛盾</b>：旧实现恒 false（OPD-CM3-24 Q1 默认关对齐 CC 需显式开启）与运行时门控
     * 默认开直接矛盾——前端 toggle 显示 off 而 auto-dream 实际在跑。现 GET /memory/config 与
     * 运行时门控同源一致。
     */
    private static boolean isAutoDreamEnabledByRuntimeChain() {
        Boolean setting = BundledSkillEnabledGates.readAutoDreamEnabledSetting();
        if (setting != null) {
            return setting;
        }
        String v = System.getProperty("NEXUSAI_AUTO_DREAM");
        if (v == null) {
            v = System.getenv("NEXUSAI_AUTO_DREAM");
        }
        if (v != null && !v.isBlank()) {
            return TaskSystemConfig.isEnvTruthy(v);
        }
        return true;
    }


    /**
     * POST /api/v1/memory/files · 创建记忆文件（幂等，存在不覆盖）。
     *
     * <p>对齐 CC {@code handleSelectMemoryFile}（memory.tsx:21-42）：
     * <ol>
     *   <li><b>mkdir configHome</b>（memory.tsx:24-28）：路径含 configHome → 递归创建
     *       {@code getClaudeConfigHomeDir()}（幂等）；</li>
     *   <li><b>writeFile 'wx'</b>（memory.tsx:32-41）：文件不存在 → 创建空文件；存在 →
     *       {@code EEXIST} 捕获（Java {@link FileAlreadyExistsException}）→ 保留原内容不覆盖；</li>
     *   <li><b>回传</b>：path + {@code getRelativeMemoryPath} 相对路径（memory.tsx:56
     *       "Opened memory file at ..."）+ content（web 编辑器渲染原料）+ created 标志。</li>
     * </ol>
     *
     * <p><b>创建面白名单（IMP-MV2-17）</b>：CC 创建目标仅限选择器可达路径
     * （MemoryFileSelector.tsx:50-65 现有文件 + User/Project 槽位，无自由路径输入）——
     * 本端点对请求路径执行 {@link #isCreatableMemoryPath} 白名单校验（user/project 槽位 +
     * {@code getMemoryFiles} 现有记忆文件，归一化比较），白名单外任意绝对路径（含
     * {@code ..} 逃逸）→ 400，消除任意路径空文件创建面。
     *
     * <p>CC 的 {@code editFileInEditor}（memory.tsx:42）在 web 端由前端编辑器承担（后端保证文件存在
     * 并回传 content，前端直接渲染/保存）。
     *
     * @param request 创建请求 {@code {path: 记忆文件绝对路径（白名单内）}}
     * @return {@code {path, relativePath, created, content, message}}
     */
    @PostMapping(value = "/files", produces = MediaType.APPLICATION_JSON_VALUE)
    public MemoryCreateResponse createFile(@RequestBody(required = false) MemoryCreateRequest request) {
        String path = request == null ? null : request.path();
        if (path == null || path.isBlank()) {
            log.warn("[MemoryController] POST /memory/files: path 缺失 → 400");
            throw new ValidationException("memory file path is required");
        }
        // [F-5 登记 · IMP-MV2-40] △-1 错误表达 REST 化设计声明（memory.tsx:66-69）：path 缺失 →
        //   ValidationException → 400（GlobalExceptionHandler 结构化错误体）；IO/装配错误 →
        //   RuntimeException → 500 fail-loud。CC 为 CLI 内联 throw/回调错误 —— 平台表达差异
        //   （REST 载体结构化 400/500），登记声明；MemoryControllerTest.createFile_missingPathIs400
        //   固化 400 语义。
        // IMP-MV2-17 路径面收敛：创建目标白名单（user/project 槽位 + 现有记忆文件），
        // 拒绝任意绝对路径逃逸（CC 创建面 = 选择器可达路径，无自由路径输入）
        if (!isCreatableMemoryPath(path)) {
            log.warn("[MemoryController] POST /memory/files: path 不在记忆文件创建面内 → 400: {}", path);
            throw new ValidationException(
                "memory file path must be the user/project memory slot or an existing memory file");
        }
        if (log.isInfoEnabled()) {
            log.info("[MemoryController] POST /memory/files 创建记忆文件: {}", path);
        }
        // memory.tsx:24-28：路径含 configHome → mkdir(configHome, {recursive:true})（幂等）
        // 决策 D1/D2：User 档目标已迁移 nexusai 自有根 → 仅 mkdir nexusai home，弃 ~/.claude mkdir 兼容
        //   （写 nexusai、不写 claude；claude 仅作读取回落源，GET /files 的 User 档读取回落保留）
        String nexusaiHome = NexusaiPaths.getAppConfigHomeDir();
        if (path.contains(nexusaiHome)) {
            ensureMemoryHome(nexusaiHome, "nexusai config");
        }
        boolean created;
        try {
            // memory.tsx:33-36 writeFile(path, '', {encoding:'utf8', flag:'wx'}) —— 'wx' 存在即失败
            Files.createFile(Paths.get(path));
            created = true;
        } catch (FileAlreadyExistsException e) {
            // memory.tsx:37-41：EEXIST 捕获 → 保留现有内容（不覆盖）
            created = false;
        } catch (IOException e) {
            log.error("[MemoryController] 创建记忆文件失败: {} (memory.tsx:32-41)", e.getMessage());
            throw new RuntimeException("Failed to create memory file: " + e.getMessage(), e);
        }
        String content;
        try {
            content = Files.exists(Paths.get(path)) ? Files.readString(Paths.get(path)) : "";
        } catch (IOException e) {
            log.error("[MemoryController] 读取记忆文件内容失败: {} ", e.getMessage());
            throw new RuntimeException("Failed to read memory file content: " + e.getMessage(), e);
        }
        // B5 锚点修正：CC getRelativeMemoryPath 用 getCwd()（MemoryUpdateNotification.tsx:9-10），非 getOriginalCwd
        String relativePath = getRelativeMemoryPath(path, System.getProperty("user.home"), CwdResolution.getCwd());
        String message = "Opened memory file at " + relativePath;
        if (log.isInfoEnabled()) {
            log.info("[MemoryController] POST /memory/files 完成: path={} created={} contentLen={}",
                path, created, content.length());
        }
        return new MemoryCreateResponse(path, relativePath, created, content, message);
    }

    /**
     * PUT /api/v1/memory/files[?sessionId=] · 更新记忆文件（type-based 三档，覆盖写）。
     *
     * <p>前端「记忆编辑器」（2026-08-23 spec §4.1 / D3）保存契约：GET /files 三档列表 + content →
     * textarea 编辑 → 本端点按 {@code type} 保存。请求体 {@code {type, file?, content, sessionId?}}
     * （type 必填；file 仅 Project 必填；content 必填）。对齐 CC 编辑器保存语义
     * {@code writeFile(path, content, {flag:'w'})}（覆盖写）——POST /files 为 'wx' 只创建不覆盖
     * （memory.tsx:32-41），<b>本端点不做创建</b>（文件不存在 → 404，创建仍走 POST /files）。
     *
     * <p><b>三档映射</b>（type 值严格用 CC ccName）：
     * <table>
     *   <tr><th>档</th><th>type</th><th>定位文件</th><th>会话依赖</th><th>可写</th></tr>
     *   <tr><td>全局</td><td>{@code Managed}</td><td>{@code getManagedFilePath()/CLAUDE.md}</td><td>无</td><td>❌ 只读 → 403</td></tr>
     *   <tr><td>用户</td><td>{@code User}</td><td>{@code nexusaiHome/CLAUDE.md}（写 nexusai，读回落 claude）</td><td>无</td><td>✅</td></tr>
     *   <tr><td>项目</td><td>{@code Project}</td><td>会话 boundProject 下多文件</td><td>✅ sessionId</td><td>✅</td></tr>
     * </table>
     *
     * <p><b>会话机制</b>：解析 sessionId 三源（body.sessionId → query {@code ?sessionId=} → MDC），
     * 非 null 先 {@code RequestContext.setSession(sessionId)} 写 MDC。Project 档必须最终有 sessionId，
     * 否则 400。boundProject 取 {@code CwdResolution.getOriginalCwdLayer()}（MDC 已 setSession）。
     *
     * <p><b>Project 白名单（IMP-MV2-17 扩展）</b>：{@code clearMemoryFileCaches()} +
     * {@code getMemoryFiles(false)} 过滤 PROJECT 得白名单 path 集合（归一化
     * {@code toAbsolutePath().normalize()}）；{@code targetPath = Paths.get(boundProject, file)} 归一化后
     * <b>精确集合比对</b>（非前缀/contains）——覆盖 {@code ..} 逃逸 / 绝对路径注入 / 根 / UNC（归一化后
     * 必然不在集合）。额外拒绝 {@code Files.isSymbolicLink(targetPath)}（防 symlink 指向白名单外）。
     *
     * <p><b>失败处理</b>（对齐 03 §4.2「REST 端点错误 → 4xx/5xx 结构化返回」）：type 缺失/非法 → 400；
     * content null → 400（空串合法=清空）；Managed → {@link ForbiddenException}（403）；Project 缺
     * sessionId / file → 400；白名单外 → 400；文件不存在 → {@link NotFoundException}（404）；写盘 IO
     * 失败 → {@link RuntimeException}（500，fail loud）。
     *
     * @param sessionIdParam query {@code ?sessionId=}（可选；body.sessionId → query → MDC 三源）
     * @param request        更新请求 {@code {type, file?, content, sessionId?}}
     * @return {@code {type, file, path, relativePath, content, message}}
     */
    @PutMapping(value = "/files", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public MemoryUpdateResponse updateFile(
            @RequestParam(value = "sessionId", required = false) String sessionIdParam,
            @RequestBody(required = false) MemoryUpdateRequest request) {
        String type = request == null ? null : request.type();
        String file = request == null ? null : request.file();
        String content = request == null ? null : request.content();
        if (log.isDebugEnabled()) {
            log.debug("[MemoryController] PUT /memory/files 收到更新请求: type={} file={} contentLen={}",
                type, file, content == null ? -1 : content.length());
        }
        // 解析 sessionId 三源：body.sessionId → query ?sessionId= → MDC；非 null 写 MDC
        String sessionId = null;
        if (request != null && request.sessionId() != null && !request.sessionId().isBlank()) {
            sessionId = request.sessionId();
        } else if (sessionIdParam != null && !sessionIdParam.isBlank()) {
            sessionId = sessionIdParam;
        } else {
            sessionId = RequestContext.sessionId();
        }
        if (sessionId != null) {
            RequestContext.setSession(sessionId);
        }
        // type 校验：null/blank → 400；非法 → 400（值域仅 Managed/User/Project）
        if (type == null || type.isBlank()) {
            log.warn("[MemoryController] PUT /memory/files: type 缺失 → 400");
            throw new ValidationException("memory type is required");
        }
        if (!"Managed".equals(type) && !"User".equals(type) && !"Project".equals(type)) {
            log.warn("[MemoryController] PUT /memory/files: 非法 type → 400: {}", type);
            throw new ValidationException("invalid memory type: " + type);
        }
        // content 校验：null → 400（空串合法 = 清空文件，覆盖写语义）
        if (content == null) {
            log.warn("[MemoryController] PUT /memory/files: content 缺失 → 400");
            throw new ValidationException("memory file content is required");
        }
        // type=Managed → 403（全局托管只读）
        if ("Managed".equals(type)) {
            log.warn("[MemoryController] PUT /memory/files: Managed 全局只读 → 403");
            throw new ForbiddenException("managed memory file is read-only");
        }
        Path targetPath;
        String displayFile;
        if ("User".equals(type)) {
            // User：主文件唯一（nexusaiHome/CLAUDE.md），file 忽略（D1：写 nexusai，不写 claude）
            targetPath = userMemoryPath().toAbsolutePath().normalize();
            displayFile = "CLAUDE.md";
        } else {
            // Project：会话级，白名单精确比对
            if (sessionId == null) {
                log.warn("[MemoryController] PUT /memory/files: Project 缺 sessionId → 400");
                throw new ValidationException("sessionId is required for project memory");
            }
            if (file == null || file.isBlank()) {
                log.warn("[MemoryController] PUT /memory/files: Project 缺 file → 400");
                throw new ValidationException("file is required for project memory");
            }
            ClaudemdEngine engine = resolveEngine();
            engine.clearMemoryFileCaches();
            List<MemoryFileInfo> existing = engine.getMemoryFiles(false);
            Set<Path> whitelist = new HashSet<>();
            for (MemoryFileInfo f : existing) {
                if (f.type() == ClaudemdMemoryType.PROJECT) {
                    Path fileAbs = Paths.get(f.path()).toAbsolutePath().normalize();
                    // [D6 严格化] 白名单跳过 .claude 段 PROJECT 条目（.claude 只读回落，
                    //   不参与 nexusai 可写面）——targetPath 若含 .claude 段将落白名单外 → 400
                    if (containsClaudeDir(fileAbs)) {
                        continue;
                    }
                    whitelist.add(fileAbs);
                }
            }
            String boundProject = CwdResolution.getOriginalCwdLayer();
            // resolve（非 Paths.get(first, more) 拼接——后者把绝对 file 当段拼进 boundProject 会抛
            // InvalidPathException，Windows 盘符冒号即触发）：绝对 file 返回自身（归一化后必不在白名单 → 400），
            // 相对 file 拼 boundProject 后归一化（.. 逃逸同样归一化后不在白名单 → 400）
            targetPath = Paths.get(boundProject).resolve(file).toAbsolutePath().normalize();
            // 精确白名单比对（归一化集合成员）：防 .. 逃逸 / 绝对路径注入 / 根 / UNC
            if (!whitelist.contains(targetPath)) {
                log.warn("[MemoryController] PUT /memory/files: target 不在项目白名单 → 400: {}", targetPath);
                throw new ValidationException("memory file not in project memory whitelist");
            }
            // 额外拒绝 symlink：防指向白名单外（安全纵深层）
            if (Files.isSymbolicLink(targetPath)) {
                log.warn("[MemoryController] PUT /memory/files: target 为符号链接 → 400: {}", file);
                throw new ValidationException("memory file cannot be a symbolic link");
            }
            displayFile = file;
        }
        // 404：文件不存在（创建仍走 POST /files）
        if (!Files.exists(targetPath)) {
            log.warn("[MemoryController] PUT /memory/files: 文件不存在 → 404: {}", targetPath);
            throw new NotFoundException("memory file not found: " + targetPath);
        }
        try {
            // CC writeFile flag 'w' 覆盖写（空串=清空文件）
            Files.writeString(targetPath, content);
        } catch (IOException e) {
            log.error("[MemoryController] 保存记忆文件失败: path={} err={}", targetPath, e.getMessage());
            throw new RuntimeException("Failed to save memory file: " + e.getMessage(), e);
        }
        // 记忆改动立即生效：写后缓存失效（对齐 GET /files 预热语义 memory.tsx:86-87）
        resolveEngine().clearMemoryFileCaches();
        String relativePath = getRelativeMemoryPath(targetPath.toString(),
            System.getProperty("user.home"), CwdResolution.getCwd());
        String message = "Updated memory file at " + relativePath;
        if (log.isInfoEnabled()) {
            log.info("[MemoryController] PUT /memory/files 完成: type={} file={} path={} contentLen={}",
                type, displayFile, targetPath, content.length());
        }
        return new MemoryUpdateResponse(type, displayFile, targetPath.toString(),
            relativePath, content, message);
    }

    /**
     * [D6 严格化] 归一化路径任一 segment 为 .claude → 命中（勿用 toString().contains，
     * 防 .my.claude/含名误判）。
     *
     * <p>WHY (决策 D6 只写 nexusai)：.claude 是 CC 只读兼容源（D3/D4 读取回落），
     * 不参与 nexusai 记忆文件的可写面 —— 创建 / 白名单 / editable 三处统一收敛。
     *
     * @param normalized 已归一化绝对路径（segment 级比较）
     * @return true = 路径含 .claude 段（禁止写入）
     */
    private static boolean containsClaudeDir(Path normalized) {
        for (int i = 0; i < normalized.getNameCount(); i++) {
            if (normalized.getName(i).toString().equals(".claude")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 记忆文件创建面白名单 · IMP-MV2-17 路径面收敛。
     *
     * <p>CC original: memory.tsx 创建目标仅限选择器可达路径（MemoryFileSelector.tsx:50-65
     * 现有文件 + User/Project 缺失槽位；UI 选项约束，无自由路径输入）—— Java POST /files
     * 曾接受任意绝对路径（任意路径空文件创建面，F2 R-1），现白名单收敛为 CC 创建面：
     * <ol>
     *   <li><b>userMemoryPath</b>（{@code nexusaiHome/CLAUDE.md}，决策 D1；CC 原 configHome 源
 *       MemoryFileSelector.tsx:55 已镜像为 nexusai 自有根）；</li>
     *   <li><b>projectMemoryPath</b>（{@code getOriginalCwd()/CLAUDE.md}，MemoryFileSelector.tsx:56）；</li>
     *   <li><b>现有记忆文件</b>（{@code engine.getMemoryFiles(false)} 全列表，含 AutoMem/TeamMem
     *       入口——POST 到已存在文件为 EEXIST 幂等 no-op，CC 选择器同面）。</li>
     * </ol>
     * 比较前双方 {@code toAbsolutePath().normalize()}（防 {@code ..} 归一化逃逸）；白名单外 → 400。
     *
     * @param path 请求路径（可为相对路径——按 JVM cwd 归一化后与绝对槽位比较）
     * @return 是否在记忆文件创建面内
     */
    /** mkdir configHome（memory.tsx:24-28 幂等 · recursive:true）。 */
    private static void ensureMemoryHome(String home, String label) {
        try {
            Files.createDirectories(Paths.get(home));
        } catch (IOException e) {
            log.error("[MemoryController] mkdir {} 失败: {} (memory.tsx:24-28)", label, e.getMessage());
            throw new RuntimeException("Failed to create " + label + " directory: " + e.getMessage(), e);
        }
    }

    private boolean isCreatableMemoryPath(String path) {
        Path target = Paths.get(path).toAbsolutePath().normalize();
        // [D6 严格化] .claude 段路径一律不可创建（放循环前：.claude 恒不进入记忆创建面，
        //   CC 只读兼容源不可被 nexusai 创建空文件）
        if (containsClaudeDir(target)) {
            if (log.isDebugEnabled()) {
                log.debug("[MemoryController] POST /memory/files: 路径含 .claude 段 → 不可创建 (D6): {}", target);
            }
            return false;
        }
        Path userSlot = userMemoryPath().toAbsolutePath().normalize();
        Path projectSlot = Paths.get(originalCwd(), "CLAUDE.md").toAbsolutePath().normalize();
        if (target.equals(userSlot) || target.equals(projectSlot)) {
            return true;
        }
        for (MemoryFileInfo file : resolveEngine().getMemoryFiles(false)) {
            if (target.equals(Paths.get(file.path()).toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 claudemd 引擎 · 未接线 → 500（fail loud：查看记忆是本端点唯一职责，无静默降级）。
     */
    private ClaudemdEngine resolveEngine() {
        ClaudemdEngine engine = claudemdEngine;
        if (engine == null) {
            log.error("[MemoryController] claudemdEngine 未接线 → /memory 查看记忆不可用（fail loud）");
            throw new IllegalStateException("claudemdEngine not wired (MemoryController /memory view unavailable)");
        }
        return engine;
    }

    /**
     * 相对路径显示 · CC original: {@code getRelativeMemoryPath}（MemoryUpdateNotification.tsx:7-20）
     * <pre>
     *   relativeToHome = path.startsWith(homeDir) ? '~' + slice : null
     *   relativeToCwd  = path.startsWith(cwd) ? './' + relative(cwd, path) : null
     *   return relativeToHome && relativeToCwd ? 短者 : (relativeToHome || relativeToCwd || path)
     * </pre>
     * 注：CC {@code startsWith} 为字符串前缀判定（非路径边界），Java 以 {@link String#startsWith} 复刻。
     *
     * @param path    记忆文件绝对路径
     * @param homeDir homedir()（System.getProperty("user.home")）
     * @param cwd     getCwd()（CC MemoryUpdateNotification.tsx:9-10 —— 动态 pwd/cwd 层，<b>非</b> getOriginalCwd；
     *                两概念在 CC 源码中不同：getCwd = override ?? STATE.cwd，getOriginalCwd = STATE.originalCwd）
     * @return 相对路径（home 相对 / cwd 相对取短；均不适用 → 绝对路径）
     */
    static String getRelativeMemoryPath(String path, String homeDir, String cwd) {
        String relativeToHome = path.startsWith(homeDir) ? "~" + path.substring(homeDir.length()) : null;
        String relativeToCwd = path.startsWith(cwd) ? "./" + relativize(cwd, path) : null;
        if (relativeToHome != null && relativeToCwd != null) {
            return relativeToHome.length() <= relativeToCwd.length() ? relativeToHome : relativeToCwd;
        }
        return relativeToHome != null ? relativeToHome
            : (relativeToCwd != null ? relativeToCwd : path);
    }

    // [F-6 登记 · IMP-MV2-40] △-2 relativePath 微差（MemoryUpdateNotification.tsx:16 ↔ 本方法）：
    //   Java 以 toAbsolutePath().normalize() 归一后 relativize + String.startsWith 前缀复刻 CC
    //   path.relative —— normalize 消解 "."/".." 段与 CC 纯字符串前缀判定的条件性差异（cwd 基准
    //   形态不一致时显示路径可能不同）。低危显示面（无行为/权限影响），登记不修。

    /** Node {@code path.relative(from, to)} 等价 · Path.relativize 基于绝对路径的纯词法相对。 */
    private static String relativize(String from, String to) {
        Path fromAbs = Paths.get(from).toAbsolutePath().normalize();
        Path toAbs = Paths.get(to).toAbsolutePath().normalize();
        return fromAbs.relativize(toAbs).toString();
    }

    /**
     * 记忆文件视图 · CC original: MemoryFileSelector 的 {@code allMemoryFiles} 元素
     * （MemoryFileSelector.tsx:55-65，ExtendedMemoryFileInfo 形状：{path,type,content,exists}）+ 编辑器
     * 契约扩展（file 相对定位标识 + editable 可写标志）。
     *
     * @param path     记忆文件绝对路径（CC original: path；仅展示，前端不回传）
     * @param type     记忆文件类型（CC original: type，'Managed'/'User'/'Project' ccName 字面量）
     * @param content  文件内容（磁盘无则空串，CC original: content）
     * @param parent   @include 父文件路径（顶层 null，CC original: parent?）
     * @param exists   是否已存在于磁盘（CC original: exists，缺失 → 前端 "创建" 入口）
     * @param file     相对定位标识（Managed/User → "CLAUDE.md"；Project → 相对 boundProject 路径）
     * @param editable 是否可写（Managed 只读 → false；User/Project → true）
     */
    public record MemoryFileView(
            String path,
            String type,
            String content,
            String parent,
            boolean exists,
            String file,
            boolean editable) {}

    /**
     * 创建请求 · {@code {path: 记忆文件绝对路径}}（前端从 GET 列表选择或输入）。
     *
     * @param path 记忆文件绝对路径（内存文件创建目标）
     */
    public record MemoryCreateRequest(String path) {}

    /**
     * 创建响应 · CC original: handleSelectMemoryFile 的 onDone 结果（memory.tsx:56）
     * {@code "Opened memory file at {relativePath}"} + web 编辑器渲染原料。
     *
     * @param path         记忆文件绝对路径
     * @param relativePath 相对路径显示（getRelativeMemoryPath，MemoryUpdateNotification.tsx:7-20）
     * @param created      本次是否新建（EEXIST → false，保留原内容）
     * @param content      文件内容（新建空串；已存在则磁盘现有内容 —— web 编辑器渲染原料）
     * @param message      操作提示（CC original: "Opened memory file at {relativePath}"）
     */
    public record MemoryCreateResponse(
            String path,
            String relativePath,
            boolean created,
            String content,
            String message) {}

    /**
     * 更新请求 · 前端「记忆编辑」契约 {@code {type, file?, content, sessionId?}}
     * （每次传完整新内容，覆盖写；type 必填，file 仅 Project 必填）。
     *
     * @param type      记忆文件类型（'Managed'/'User'/'Project'，CC ccName 字面量）
     * @param file      相对定位标识（仅 Project 必填：相对 boundProject 路径；User 忽略）
     * @param content   完整新文件内容（空串 = 清空文件，覆盖写语义；null → 400）
     * @param sessionId 会话 ID（可选；Project 档会话锚定，与 query/MDC 三源）
     */
    public record MemoryUpdateRequest(String type, String file, String content, String sessionId) {}

    /**
     * 更新响应 · 更新后文件视图（前端提示词：type/file/path/content/message）。
     *
     * @param type         记忆文件类型（'Managed'/'User'/'Project'，CC ccName 字面量）
     * @param file         相对定位标识（Managed/User → "CLAUDE.md"；Project → 请求 file）
     * @param path         记忆文件绝对路径
     * @param relativePath 相对路径显示（getRelativeMemoryPath，MemoryUpdateNotification.tsx:7-20）
     * @param content      更新后文件内容（覆盖写结果）
     * @param message      操作提示（"Updated memory file at {relativePath}"）
     */
    public record MemoryUpdateResponse(
            String type,
            String file,
            String path,
            String relativePath,
            String content,
            String message) {}
    /**
     * /memory 状态视图 · CC original: MemoryFileSelector toggle + dream 状态行
     * （MemoryFileSelector.tsx:206-233）。
     *
     * @param autoMemoryEnabled    auto-memory 开关有效值（CC isAutoMemoryEnabled，paths.ts:30-56）
     * @param autoDreamEnabled     auto-dream 开关有效值（CC isAutoDreamEnabled，config.ts:13-21）
     * @param dreamStatus          dream 状态：{@code never}（无锁 = 从未合并）/ {@code last_ran}（锁存在）
     * @param lastConsolidatedAtMs 上次合并时间戳 ms（锁 mtime；0 = 从未 · consolidationLock.ts:29-36）
     */
    public record MemoryConfigView(
            boolean autoMemoryEnabled,
            boolean autoDreamEnabled,
            String dreamStatus,
            long lastConsolidatedAtMs) {}

    /**
     * 状态更新请求 · CC original: handleToggleAutoMemory/AutoDream 的
     * {@code updateSettingsForSource('userSettings', {...})} 载荷（MemoryFileSelector.tsx:240-254）。
     * 字段可选：null = 不覆盖（部分更新）；非 null 布尔 → 写 {@code {configHome}/settings.json}。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemoryConfigUpdate(Boolean autoMemoryEnabled, Boolean autoDreamEnabled) {}
}
