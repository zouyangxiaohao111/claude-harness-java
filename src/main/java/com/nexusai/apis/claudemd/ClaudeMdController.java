package com.nexusai.apis.claudemd;

import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.project.ClaudeMdIncludeApprovalStore;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * claude-md 记忆引擎 Web 等价 REST 载体 · 对齐 CC {@code utils/claudemd.ts}（OPD-CM5-F-09 /
 * IMP-F1-7 · FR-7 前端审批通道）。
 *
 * <p><b>CC 真源</b>：CC 无 REPL 审批 REST 通道——审批态直接落 <b>project config</b>
 * （{@code config.hasClaudeMdExternalIncludesApproved}，字段声明 config.ts:115 / :116，默认
 * config.ts:146 / :147），由 {@code getMemoryFiles} 消费：
 * {@code includeExternal = forceIncludeExternal || config.hasClaudeMdExternalIncludesApproved || false}
 * （claudemd.ts:798-801）。Java 无 getCurrentProjectConfig → ClaudemdEngine 以注入式
 * {@code Function<String sessionId, Boolean>} 装配缝承接（{@code ClaudemdEngine}
 * 的 {@code setHasClaudeMdExternalIncludesApproved} / {@code setHasClaudeMdExternalIncludesWarningShown}）。
 * <b>本控制器即生产注入点</b>（F1-7 缺口「hasClaudeMdExternalIncludesApproved/WarningShown 无生产注入」闭环）
 * ——前端审批对话框接受/拒绝后 POST 本端点置位。
 *
 * <p>⭐ <b>[T15-3 2026-09-14] 键 = 项目根（已修「进程级单例」的跨项目泄漏）</b>：
 * <p><b>原来的偏离</b>：本控制器曾把两份态存成<b>进程级单例 volatile（无任何键）</b>。而 CC 侧这两个标志的
 * 宿主是 <b>project config</b>（消费点 {@code const config = getCurrentProjectConfig()}，
 * claudemd.ts:796 / :1420）⇒ <b>CC 语义 = 每个项目一份审批态</b>（同项目的另一个会话「本来就该」受影响，
 * 不同项目互不影响）。进程级单例比 CC 的键 <b>更宽</b> ⇒ <b>A 项目点过「允许」会静默影响 B 项目</b>。
 * <p><b>现修法</b>：存储键 = 由请求显式传入的 {@code sessionId} 解析出的 <b>项目键</b>
 * （{@link #resolveProjectKey(String, String)}），两条 REST 端点各加显式 {@code sessionId} 入参。
 * ⛔ <b>刻意不按 sessionId 存</b>：那会让「同项目跨会话不再共享」，是<b>发明 CC 没有的判据</b>。
 * <p><b>键的三级推导（与 CC 对齐）</b>：
 * <ol>
 *   <li>{@code sessionId} → {@link SessionProjectRoot#lookup(String)}（会话冻结表 / miss 回源 DB）
 *       → 会话绑定项目根。CC 对照物 = {@code State.projectRoot}（state.ts:45-50「stable project root …
 *       use for project identity」+ state.ts:261-279 启动冻结 realpath+NFC），
 *       ⛔ <b>不用</b> {@code CwdResolution.getOriginalCwdLayer}：后者 L1 是 worktree 重锚层
 *       （{@code EnterWorktreeTool} 写 worktreePath），会把同一个项目按 worktree <b>分叉成多个键</b>，
 *       而 CC 恰恰是<b>折叠</b>的（见下条）；</li>
 *   <li>项目根 → {@link AutoMemPaths#findCanonicalGitRoot(String)}
 *       （CC {@code findCanonicalGitRoot}，git.ts:195 / :197-210 → resolveCanonicalRoot git.ts:123-183：
 *       worktree 经 {@code .git} 的 {@code gitdir:} + {@code commondir} 解析到<b>主仓库根</b>）；
 *       <b>非 git ⇒ null ⇒ 回落项目根本身</b>——该回落是 CC 的<b>显式语义</b>而非静默兜底
 *       （{@code getAutoMemBase() = findCanonicalGitRoot(getProjectRoot()) ?? getProjectRoot()}，
 *       memdir/paths.ts:204；approval 侧同构：{@code getProjectPathForConfig} 非 git 回落
 *       {@code resolve(originalCwd)}，config.ts:1596-1607）。
 *       <p>⚠️ 折叠导致「同一 git 仓的不同子目录 / worktree 共享一份审批态」——<b>CC 本就如此</b>
 *       （同两条 CC 源码），不是本仓引入的新行为。</li>
 * </ol>
 *
 * <p>⭐ <b>[acc2 2026-09-15] 审批态已<b>持久化</b>（跨重启存活）</b>：
 * <p><b>原来的偏离</b>：两份态仅存进程内 {@code ConcurrentHashMap} ⇒ <b>进程重启即丢</b> ⇒ 用户每次
 * 重启都被重复弹一次审批。而 CC 的宿主是 <b>project config</b>（config.ts:115-116，跨进程存盘）。
 * <p><b>现修法</b>：内存 map 降为 L1 写穿镜像，事实源 = {@code claude_md_include_approval} 表（V74，
 * 经 {@link ClaudeMdIncludeApprovalStore}）。POST 写内存 + 写穿 DB（两列一次 upsert）；读路径内存 miss
 * ⇒ 回源 DB 并回填（{@link #refillFromStore}）。⛔ <b>不引入 TTL</b>：本仓单 JVM、无第二写入方 ⇒
 * 写穿 + 回源即一致。
 * <p>⚠️ <b>DB 键 ≠ 内存键</b>：DB 侧 = 归一化键（{@code ProjectService.normalizePathKey}，折叠斜杠方向
 * + 大小写，<b>在 store 内单点执行</b>）；内存侧 = canonical 原样（Windows 上含反斜杠）。
 * 归一是本批的命门 —— 不做则「写侧 {@code D:\x\y}、读侧 {@code D:/x/y}」查不到 ⇒
 * <b>「批准了但不加载」的静默失效</b>。
 *
 * <p><b>缓存失效</b>：{@code getMemoryFiles} memoize（CC lodash memoize claudemd.ts:790）——
 * 审批态翻转后若不失效，主路径仍返回旧列表（不含新批准的外部 @include）。对齐 CC
 * {@code clearMemoryFileCaches}「purely for correctness … settings sync」（claudemd.ts:1110-1122）
 * 语义：置位后调用 {@link ClaudemdEngine#clearMemoryFileCaches()} 失效（不触发 InstructionsLoaded
 * hook，纯正确性失效）。<b>另有第二道保障</b>：{@link ClaudemdEngine} 的 memoize 键已纳入审批态
 * （否则不同项目会共用同键 → 跨项目串值）。
 *
 * <p><b>缺值策略</b>（[T15-3] 按改造铁律）：
 * <ul>
 *   <li>请求体缺失 / {@code approved} 缺失 → <b>400</b>（审批态二值契约，拒绝隐式缺省）；</li>
 *   <li>{@code sessionId} 缺失 / 空白 → <b>400</b>（(a) 类「本该有却没有」）；</li>
 *   <li>解析不到项目根（会话存在但未绑定 / 无此会话 / <b>解析失败</b>）→ <b>400</b>（同上；
 *       先例 = {@code SessionMemoryExportController} 的四个失败出口全 400）。⛔ 绝不回落
 *       config home / user.dir 冒充项目根。</li>
 * </ul>
 *
 * <p><b>失败语义</b>：claudemd 引擎未接线 → 500 fail loud（无静默降级，对齐
 * ExtractMemoriesController.resolveMemoryStorage 同语义）。
 *
 * <p><b>鉴权说明</b>：与同级只读载体 {@code /api/v1/context/analyze} 一致，未纳入
 * {@code BearerTokenAuthFilterConfig} 白名单（无鉴权过滤）——若安全姿态要求保护，需同步登记
 * 白名单（align away-summary/dream 先例）。
 */
@RestController
@RequestMapping("/api/v1/claude-md")
public class ClaudeMdController {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdController.class);

    /** claude-md 记忆引擎 · @Bean 自动装配（ToolRegistrationConfig.claudemdEngine），
     *  required=false 容错单测反射注入；null → fail loud（resolveEngine）。 */
    @Autowired(required = false)
    private ClaudemdEngine claudemdEngine;

    /**
     * 审批态持久化 store（DB，跨重启存活）· Spring 注入；测试 {@code new} 实例 / 未接线 → null
     * → 回落纯内存行为（改造前语义，不抛）。
     *
     * <p><b>WHY 落库</b>：CC 的两个标志宿主是 <b>project config</b>（config.ts:115-116，跨进程存盘）
     * ⇒ 重启后不重复弹审批；纯内存 {@code ConcurrentHashMap} 进程重启即丢 ⇒ 每次重启重复弹窗。
     *
     * <p><b>键归一的唯一落点</b>：归一在 {@link ClaudeMdIncludeApprovalStore} 内<b>单点</b>执行
     * （{@code ProjectService.normalizePathKey}，全仓只此一份），故本控制器传入的键可以是
     * canonical 原样（Windows 上含反斜杠）—— ⛔ 本类不另写第二份归一。
     */
    @Autowired(required = false)
    private ClaudeMdIncludeApprovalStore store;

    /** 测试/自定义接线入口（Spring 之外注入持久化 store）。 */
    public void setStore(ClaudeMdIncludeApprovalStore store) {
        this.store = store;
    }

    /**
     * 外部 include 审批态 · <b>按项目键分区</b> · CC original: {@code config.hasClaudeMdExternalIncludesApproved}
     * （config.ts:115，缺省 false :146）。
     *
     * <p>键 = {@link #resolveProjectKey(String, String)}（项目根 → canonical 键）；缺键视为
     * {@code false}（CC {@code DEFAULT_PROJECT_CONFIG}，config.ts:146）。
     * <p>⛔ <b>不是 sessionId 键</b>（那会发明 CC 没有的判据）；⛔ <b>不是进程级单例</b>（那会跨项目泄漏
     * ——本批要治的正是这个）。
     * <p><b>[acc2] 本 map 只是写穿镜像 / 读路径的 L1 快路径</b>：POST 写内存 + 写穿 DB；
     * 读 miss ⇒ 回源 {@link #store} 并回填（见 {@link #refillFromStore}）。
     * ⚠️ 本 map 的键是 <b>canonical 原样</b>（Windows 上含反斜杠），与 DB 侧的<b>归一化键</b>不同 ——
     * 归一被钉在 store 内单点（⛔ 本类不另写第二份归一）；两条路径各自自洽，故不影响正确性。
     */
    private final Map<String, Boolean> externalIncludesApprovedByProject = new ConcurrentHashMap<>();

    /**
     * 外部 include 警告已示态 · <b>按项目键分区</b> · CC original: {@code config.hasClaudeMdExternalIncludesWarningShown}
     * （config.ts:116，缺省 false :147）。CC Dialog onDone 批准/拒绝**均**置 true（config.ts:123-131）
     * ——拒绝后 {@code shouldShowClaudeMdExternalIncludesWarning} 返回 false（不再弹窗）。键同
     * {@link #externalIncludesApprovedByProject}。
     */
    private final Map<String, Boolean> externalIncludesWarningShownByProject = new ConcurrentHashMap<>();

    /**
     * 引擎侧接缝读到「解析不出项目键」时的告警闸 · <b>按原因分键（三类各一道）</b>。
     *
     * <p>WHY 一次性：agent 真实加载路径每次 {@code getMemoryFiles} 都会读接缝，逐次 WARN 会刷屏
     * （本仓既有惯例 = {@code SessionProjectRoot.NULL_SESSION_LOOKUP_WARNED}）。
     *
     * <p>⛔ <b>为什么不共用一个闸</b>：三个原因是<b>三种不同缺陷</b>，共用一个 {@code AtomicBoolean}
     * 会让「先到者」把「后到者」的可观测性<b>结构性抹掉</b> —— 例如 JVM 早期只要出现过一次
     * 「sessionId 为空」，此后「会话存在但无绑定项目根」（= 数据链路异常，按用户裁定「每个会话一定有
     * 绑定目录」属<b>真缺陷</b>）在日志里<b>永不出现</b>。这类「一次性闸把『≥WARN 可观测』变成
     * 『每 JVM 一行』，缺陷越普遍可观测性越差」已被本改造计划 §九.C.3 点名，且 P1a 批实证过
     * 「全进程一个 AtomicBoolean 会让消费侧告警结构上不可达」⇒ 理由不共享，闸也不共享。
     * <p>⚠️ 三闸<b>只覆盖 (b) 类</b>（「本就不需要」⇒ 返回 false + WARN）；对 (a) 类「本该有却没有」
     * （解析失败）是<b>抛</b>，不在此闸内。
     */
    private final AtomicBoolean blankSessionIdWarned = new AtomicBoolean(false);
    /** 见 {@link #blankSessionIdWarned}（「会话存在但无绑定项目根」独立一道）。 */
    private final AtomicBoolean unboundSessionWarned = new AtomicBoolean(false);
    /** 见 {@link #blankSessionIdWarned}（「无此会话」独立一道）。 */
    private final AtomicBoolean unknownSessionWarned = new AtomicBoolean(false);

    /**
     * 前端审批对话框审批外部 @include · POST /api/v1/claude-md/include-approval。
     *
     * <p>流程: 校验 {@code approved} 二值 + 解析项目键（失败 → 400）→ 更新本项目键下的两份态
     * <b>+ 写穿 DB（[acc2] 两列一次 upsert，跨重启存活）</b> →
     * 以 {@code sessionId -> 项目键查表} 闭包注入引擎（CC claudemd.ts:798-801 includeExternal 门控，
     * 引擎侧 {@code getMemoryFiles} / {@code shouldShowClaudeMdExternalIncludesWarning} <b>两侧同一个闭包
     * 同一个键</b>）→ {@link ClaudemdEngine#clearMemoryFileCaches()} 失效 memoize 缓存（CC settings-sync
     * 正确性失效，claudemd.ts:1110-1122）→ 回显 {@code {approved}}。
     *
     * @param request POST JSON 请求体（{@code { "approved": boolean, "sessionId": string }}，均必填）
     * @return 200 回显审批态 {@code {approved: boolean}}
     */
    @PostMapping(value = "/include-approval", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IncludeApprovalResponse> includeApproval(
            @RequestBody IncludeApprovalRequest request) {
        if (request == null || request.approved() == null) {
            log.warn("[ClaudeMdController] /include-approval 拒绝：请求体/approved 缺失（审批态二值契约，"
                + "拒绝隐式缺省）→ 400");
            throw new ValidationException("approved is required (include-approval)");
        }
        ClaudemdEngine engine = resolveEngine();
        String projectKey = resolveProjectKey(request.sessionId(), "/include-approval");
        boolean approved = request.approved();
        this.externalIncludesApprovedByProject.put(projectKey, approved);
        // CC Dialog onDone 批准/拒绝均置 WarningShown=true（config.ts:123-131）——拒绝后不再弹窗；
        // shouldShowClaudeMdExternalIncludesWarning 因 warningShown=true 返回 false（claudemd.ts:1423-1426）
        this.externalIncludesWarningShownByProject.put(projectKey, true);
        // [acc2] 写穿 DB（两列一次 upsert）——CC 宿主是 project config（跨进程存盘）⇒ 重启后不重复弹审批
        persistIncludeApproval(projectKey, approved);
        wireEngine(engine);
        // CC claudemd.ts:1119-1122 clearMemoryFileCaches（settings sync 纯正确性失效）——
        // 否则 memoize 的 getMemoryFiles(false, ...) 缓存不含新批准的外部 @include
        engine.clearMemoryFileCaches();
        if (log.isInfoEnabled()) {
            log.info("[ClaudeMdController] /include-approval 审批态更新: approved={} sessionId={} projectKey={}"
                + "（CC config.ts:115 hasClaudeMdExternalIncludesApproved，claudemd.ts:798-801 includeExternal "
                + "门控；WarningShown=true 置位对齐 CC Dialog onDone config.ts:123-131；getMemoryFiles 缓存已失效）",
                approved, request.sessionId(), projectKey);
        }
        return ResponseEntity.ok(new IncludeApprovalResponse(approved));
    }

    /**
     * 查询 CLAUDE.md 外部 @import 审批状态 · GET /api/v1/claude-md/include-status?sessionId=…
     *
     * <p>前端在<b>会话激活后</b>主动 GET 查询（用户拍板仅 GET，不用 STOMP 推送），判断
     * 「CLAUDE.md 外部 @import 是否待审批并弹窗」。对齐 CC 启动时同步检测
     * {@code shouldShowClaudeMdExternalIncludesWarning()}（interactiveHelpers.tsx:164 →
     * ClaudeMdExternalIncludesDialog）+ {@code getExternalClaudeMdIncludes(await getMemoryFiles(true))}
     * （interactiveHelpers.tsx:165）。
     *
     * <p><b>语义</b>：
     * <ul>
     *   <li>{@code needsApproval} = 引擎 {@code shouldShowClaudeMdExternalIncludesWarning(sessionId)}
     *       —— 本会话<b>项目键</b>下未审批 && 未示警 && 有外部 include → true（claudemd.ts:1420-1430）。</li>
     *   <li>{@code files} = 引擎 {@code getExternalClaudeMdIncludes(getMemoryFiles(true, sessionId), sessionId)}
     *       —— forceIncludeExternal=true 探测外部 include（不受审批门控）。</li>
     * </ul>
     *
     * <p><b>失败语义</b>：{@code sessionId} 缺失/解析不到项目键 → <b>400</b>（[T15-3]：本端点的答案
     * <b>按项目定义</b>，无项目 ⇒ 无法判定 —— ⛔ 不再沿用旧「无会话入参 ⇒ 传 null 跳过」的 (b) 分类，
     * 该分类的前提「审批态与会话无关」已被本批推翻）；claudemd 引擎未接线 → 500 fail loud。
     *
     * @param sessionId 会话标识（必填；{@code ?sessionId=}）
     * @return 200 {@link IncludeStatusResponse}
     */
    @GetMapping(value = "/include-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public IncludeStatusResponse includeStatus(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        ClaudemdEngine engine = resolveEngine();
        // 400 门禁 + 数据流日志（键由引擎侧闭包再解析一次：同一 helper、同一语义；命中为内存查表，
        // miss 为一次 PK 回源，代价可忽略）
        String projectKey = resolveProjectKey(sessionId, "/include-status");
        wireEngine(engine);
        // 对齐 CC shouldShowClaudeMdExternalIncludesWarning（interactiveHelpers.tsx:164 + claudemd.ts:1422-1429）
        boolean needsApproval = engine.shouldShowClaudeMdExternalIncludesWarning(sessionId);
        // 对齐 CC getExternalClaudeMdIncludes(await getMemoryFiles(true))（interactiveHelpers.tsx:165）——
        // forceIncludeExternal=true 探测外部 include，不受审批门控
        List<String> files = engine.getExternalClaudeMdIncludes(engine.getMemoryFiles(true, sessionId), sessionId)
            .stream().map(ClaudemdEngine.ExternalClaudeMdInclude::path).toList();
        if (log.isInfoEnabled()) {
            log.info("[ClaudeMdController] GET /include-status: sessionId={} projectKey={} needsApproval={} "
                + "externalIncludeFiles={}", sessionId, projectKey, needsApproval, files.size());
        }
        return new IncludeStatusResponse(needsApproval, files);
    }

    /**
     * [T15-3] 解析请求显式传入的 {@code sessionId} → <b>项目键</b>（三级推导见类 javadoc）。
     *
     * <p><b>失败出口全部 400（⛔ 无一处静默、⛔ 绝不回落 user.dir）</b>：
     * <ol>
     *   <li>sessionId 缺失/空白 ⇒ (a)「本该有却没有」⇒ 400；</li>
     *   <li>会话存在但无绑定项目根 / 绑定失效 ⇒ 数据链路异常 ⇒ 400；</li>
     *   <li>无此会话（合成/伪造/已删 id）⇒ 400（⛔ 刻意不走「无会话出口」——那会返回与请求方无关的
     *       进程 user.dir，正是本批要消灭的「冒充项目根」）；</li>
     *   <li>解析失败 / 无法判定（回源解析器未接线 / 回源抛错 / 违约返回 null）⇒ 400，且文案与上一条
     *       <b>可辨识</b>（⛔ 不把装配异常混报成「无此会话」）。</li>
     * </ol>
     *
     * @param sessionId 请求显式传入的会话标识
     * @param endpoint  端点字面量（仅用于日志/错误消息）
     * @return 项目键（恒非空 —— 否则抛）
     * @throws ValidationException 上述任一失败出口（REST → 400）
     */
    private String resolveProjectKey(String sessionId, String endpoint) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[ClaudeMdController] {} 需要项目键，但缺少 sessionId → 400"
                + "（⛔ 不回落 config home / user.dir 冒充项目根）", endpoint);
            throw new ValidationException("sessionId is required (" + endpoint + ")");
        }
        SessionProjectRoot.Lookup lookup = SessionProjectRoot.lookup(sessionId);
        String projectRoot = lookup.projectRoot();
        if (projectRoot == null || projectRoot.isBlank()) {
            log.warn("[ClaudeMdController] {} 需要项目键，但 sessionId={} 解析不到（sessionKnown={} "
                + "resolutionFailed={}：{}）→ 400（⛔ 不回落 config home / user.dir 冒充项目根）",
                endpoint, sessionId, lookup.sessionKnown(), lookup.resolutionFailed(),
                lookup.resolutionFailed()
                    ? "项目根**无法判定**（回源解析器未接线 / 回源抛错 / 违约返回 null）"
                    : lookup.sessionKnown() ? "会话存在但无绑定项目根/绑定失效" : "无此会话（合成/伪造/已删 id）");
            throw new ValidationException("sessionId has no bound project root (" + endpoint + ")");
        }
        String canonical = AutoMemPaths.findCanonicalGitRoot(projectRoot);
        // CC memdir/paths.ts:204 `findCanonicalGitRoot(getProjectRoot()) ?? getProjectRoot()`：
        //   非 git 目录 ⇒ 回落项目根本身（CC 显式语义，非静默兜底）
        String projectKey = canonical != null ? canonical : projectRoot;
        if (log.isDebugEnabled()) {
            log.debug("[ClaudeMdController] {} 项目键解析: sessionId={} projectRoot={} canonicalGitRoot={} "
                + "projectKey={}", endpoint, sessionId, projectRoot, canonical, projectKey);
        }
        return projectKey;
    }

    /**
     * [T15-3] 把「{@code sessionId → 本项目键下的审批态}」闭包注入引擎（幂等）。
     *
     * <p><b>WHY 在两端点都注入</b>：引擎的两个消费点（{@code getMemoryFiles} 与
     * {@code shouldShowClaudeMdExternalIncludesWarning}）读的是<b>同一个闭包</b>；只由 POST 注入会让
     * 「进程内从未 POST 过」时接缝为 null（虽然结果同为默认 false，但「两侧同一个闭包」这一性质
     * 依赖调用顺序 —— 本方法把它变成<b>与顺序无关</b>）。
     *
     * <p><b>闭包缺值策略</b>（[T15-3] 按改造铁律，⛔ 与 REST 侧 400 不同：agent 真实加载路径<u>不抛</u>）：
     * <ul>
     *   <li>会话存在但未绑定 / 无此会话 ⇒ (b)「本就不需要」⇒ <b>返回 false + 一次性 ≥WARN</b>
     *       （= CC 缺省 false，config.ts:146）；</li>
     *   <li>解析失败 / 无法判定 ⇒ (a)「本该有却没有」⇒ <b>抛</b>。WHY 不静默返回 false：同一引擎同一
     *       sessionId 上，{@code resolveOriginalCwd}（CwdResolution:280-284）对该态本来就抛，
     *       此处若吞掉就新造「一处抛一处吞」的不一致（正是本改造要消灭的东西）。</li>
     * </ul>
     */
    private void wireEngine(ClaudemdEngine engine) {
        engine.setHasClaudeMdExternalIncludesApproved(this::approvedForSession);
        engine.setHasClaudeMdExternalIncludesWarningShown(this::warningShownForSession);
    }

    /**
     * 见 {@link #wireEngine} 的契约（(b) 类返回 false + 一次性 WARN；(a) 类抛）。
     *
     * <p>[acc2] 内存命中即返回；miss ⇒ 回源 store 并回填（{@link #refillFromStore}）。
     */
    private boolean approvedForSession(String sessionId) {
        String projectKey = projectKeyForEngineRead(sessionId);
        if (projectKey == null) {
            return false;
        }
        Boolean cached = this.externalIncludesApprovedByProject.get(projectKey);
        if (cached != null) {
            return cached;
        }
        ClaudeMdIncludeApprovalStore.State state = refillFromStore(projectKey);
        return state != null && state.approved();
    }

    /** 见 {@link #wireEngine} 的契约（同 {@link #approvedForSession}）。 */
    private boolean warningShownForSession(String sessionId) {
        String projectKey = projectKeyForEngineRead(sessionId);
        if (projectKey == null) {
            return false;
        }
        Boolean cached = this.externalIncludesWarningShownByProject.get(projectKey);
        if (cached != null) {
            return cached;
        }
        ClaudeMdIncludeApprovalStore.State state = refillFromStore(projectKey);
        return state != null && state.warningShown();
    }

    /**
     * [acc2] 内存 miss ⇒ <b>回源 DB 并回填两份内存镜像</b>（等价
     * {@code SessionProjectRoot.lookup:310-314} + {@code McpNeedsAuthCache.isCached:77-100} 范式）。
     *
     * <p><b>WHY 不加 TTL</b>：本仓<b>单 JVM</b> ⇒ 写穿 + miss 回源即足够一致（无第二个写入方），
     * 与 {@code McpNeedsAuthCacheStore} 需要 TTL 的多实例场景不同。
     *
     * <p><b>一行两列 ⇒ 一次查询</b>：两个访问器都走本方法，先到者回填<b>两份</b>镜像 ⇒ 后到者内存命中，
     * 故一次读路径最多一次 PK 查询。
     *
     * <p><b>失败语义</b>：store 缺席（测试 {@code new} 实例 / 未接线）⇒ 返回 null（回落纯内存，
     * 改造前语义不变）；无行 ⇒ 返回 null（⛔ <b>不把 false 写进内存</b> —— 负缓存会钉死「先读 miss
     * → 后 POST」）；DB 读失败 ⇒ fail-open 返回 null（按 CC 缺省 false，config.ts:146，不阻断
     * agent 加载路径）+ WARN。
     */
    private ClaudeMdIncludeApprovalStore.State refillFromStore(String projectKey) {
        ClaudeMdIncludeApprovalStore s = this.store;
        if (s == null) {
            return null;
        }
        try {
            ClaudeMdIncludeApprovalStore.State state = s.read(projectKey);
            if (state == null) {
                return null;
            }
            this.externalIncludesApprovedByProject.put(projectKey, state.approved());
            this.externalIncludesWarningShownByProject.put(projectKey, state.warningShown());
            if (log.isDebugEnabled()) {
                log.debug("[ClaudeMdController] 审批态内存 miss → 回源 DB 并回填: projectKey={} "
                    + "approved={} warningShown={}", projectKey, state.approved(), state.warningShown());
            }
            return state;
        } catch (Exception e) {
            log.warn("[ClaudeMdController] 审批态回源 DB 失败 projectKey={}（fail-open 按 CC 缺省 false，"
                + "config.ts:146，不阻断加载路径）: {}", projectKey, e.toString());
            return null;
        }
    }

    /**
     * [acc2] POST 后写穿 DB（<b>两列一次 upsert</b>，⛔ 不做两次 DB 往返）。
     *
     * <p><b>调用顺序 = 先内存后 DB</b>：DB 写失败仅 WARN 并降级纯内存（改造前语义），不阻断审批
     * 端点（对齐 {@code McpNeedsAuthCache.setCached} 的 best-effort 吞错；
     * CC 侧 setMcpAuthCacheEntry 的 {@code .catch} 同义，client.ts:306-308）。
     *
     * <p>store 缺席（测试 {@code new} 实例 / 未接线）⇒ 静默 no-op（不抛）。
     */
    private void persistIncludeApproval(String projectKey, boolean approved) {
        ClaudeMdIncludeApprovalStore s = this.store;
        if (s == null) {
            return;
        }
        try {
            s.save(projectKey, approved, true);
        } catch (Exception e) {
            log.warn("[ClaudeMdController] 审批态写穿 DB 失败 projectKey={}（降级纯内存，重启后丢）: {}",
                projectKey, e.toString());
        }
    }

    /**
     * 引擎侧读路径的项目键解析（[T15-3] 与 {@link #resolveProjectKey} 的差别：<b>不抛 400，按四态分流</b>）。
     *
     * @return 项目键；null = 解析不出且属 (b) 类（调用方按「未审批 / 未示警」处理）
     * @throws IllegalStateException 解析失败 / 无法判定（(a) 类，fail loud）
     */
    private String projectKeyForEngineRead(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            warnBlankSessionIdOnce();
            return null;
        }
        SessionProjectRoot.Lookup lookup = SessionProjectRoot.lookup(sessionId);
        if (lookup.resolutionFailed()) {
            // (a) 本该有却没有 ⇒ fail loud（⛔ 不降级为 false —— 同一输入上 resolveOriginalCwd 已抛，
            //     此处吞掉会造出「一处抛一处吞」的不一致）
            log.error("[ClaudeMdController] 引擎读审批态时项目根**无法判定**（回源解析器未接线 / 回源抛错 / "
                + "违约返回 null）⇒ fail loud（⛔ 不得当作『未审批』静默返回 false）： sessionId={}", sessionId);
            throw new IllegalStateException("claude-md include approval state unresolvable: sessionId="
                + sessionId + " (project root resolution failed)");
        }
        String projectRoot = lookup.projectRoot();
        if (projectRoot == null || projectRoot.isBlank()) {
            if (lookup.sessionKnown()) {
                warnUnboundSessionOnce(sessionId);
            } else {
                warnUnknownSessionOnce(sessionId);
            }
            return null;
        }
        String canonical = AutoMemPaths.findCanonicalGitRoot(projectRoot);
        return canonical != null ? canonical : projectRoot;
    }

    /** (b)-1 原因：sessionId 为空/空白 ⇒ 一次性 ≥WARN（独立闸，见 {@link #blankSessionIdWarned}）。 */
    private void warnBlankSessionIdOnce() {
        if (blankSessionIdWarned.compareAndSet(false, true)) {
            log.warn("[ClaudeMdController] 引擎读外部 include 审批态时 sessionId 为空/空白 ⇒ 解析不出项目键，"
                + "按 CC 缺省返回 false（config.ts:146）—— 即视为「未审批 / 未示警」。⛔ 绝不回落 config home / "
                + "user.dir 冒充项目根；本原因只打印一次（agent 加载路径逐次调用）");
        }
    }

    /**
     * (b)-2 原因：会话存在但无绑定项目根 / 绑定失效 ⇒ 一次性 ≥WARN（<b>独立闸</b>）。
     *
     * <p>按用户裁定「每个会话一定有绑定目录，查不到就是严重 bug」⇒ 本原因属<b>真缺陷线索</b>，
     * ⛔ 不得与「无此会话」等无害态共用一道闸（否则它会被先到者抹掉、永不可见）。
     */
    private void warnUnboundSessionOnce(String sessionId) {
        if (unboundSessionWarned.compareAndSet(false, true)) {
            log.warn("[ClaudeMdController] 引擎读外部 include 审批态时**会话存在但无绑定项目根/绑定失效**"
                + "（数据链路异常 ⇒ 按用户裁定属严重 bug 线索）⇒ 解析不出项目键，按 CC 缺省返回 false"
                + "（config.ts:146）。sessionId={}；本原因只打印一次（agent 加载路径逐次调用）", sessionId);
        }
    }

    /** (b)-3 原因：无此会话（合成 / 伪造 / 已删 id）⇒ 一次性 ≥WARN（<b>独立闸</b>）。 */
    private void warnUnknownSessionOnce(String sessionId) {
        if (unknownSessionWarned.compareAndSet(false, true)) {
            log.warn("[ClaudeMdController] 引擎读外部 include 审批态时**无此会话**（合成/伪造/已删 id）"
                + "⇒ 解析不出项目键，按 CC 缺省返回 false（config.ts:146）。sessionId={}；"
                + "本原因只打印一次（agent 加载路径逐次调用）", sessionId);
        }
    }

    /**
     * 解析 claude-md 引擎 · 未接线 → 500（fail loud：审批态不落地 = 功能无效，无静默降级）。
     */
    private ClaudemdEngine resolveEngine() {
        ClaudemdEngine engine = claudemdEngine;
        if (engine == null) {
            log.error("[ClaudeMdController] claudemdEngine 未接线 → /include-approval / /include-status 不可用（fail loud）");
            throw new IllegalStateException(
                "claudemdEngine not wired (ClaudeMdController /include-approval|/include-status unavailable)");
        }
        return engine;
    }

    /**
     * /include-approval 请求体 · CC original: {@code config.hasClaudeMdExternalIncludesApproved}
     * （config.ts:115 布尔审批态）。前端审批对话框接受 → true，拒绝 → false。
     *
     * <p>[T15-3] 加 {@code sessionId}：CC 的审批态宿主是 <b>project config</b>（claudemd.ts:796/:1420）
     * ⇒ 服务端必须知道「哪个项目」，而唯一合法入口是请求显式传入的会话标识（⛔ 不读任何隐式通道）。
     *
     * @param approved  外部 @include 是否获准（必填；null → 400）
     * @param sessionId 会话标识（必填；解析不出项目键 → 400）
     */
    public record IncludeApprovalRequest(Boolean approved, String sessionId) {}

    /**
     * /include-approval 响应 · 回显已落地的审批态（前端确认对话框关闭后刷新门控展示）。
     *
     * @param approved 已登记到本项目键下的审批态
     */
    public record IncludeApprovalResponse(boolean approved) {}

    /**
     * /include-status 响应 · 前端判断「CLAUDE.md 外部 @import 是否待审批并弹窗」。
     *
     * @param needsApproval 是否需审批（本会话项目键下：存在外部 include 且未审批且未示警；CC shouldShowClaudeMdExternalIncludesWarning）
     * @param files         外部 @import 文件绝对路径列表（CC getExternalClaudeMdIncludes，不受审批门控）
     */
    public record IncludeStatusResponse(boolean needsApproval, List<String> files) {}
}
