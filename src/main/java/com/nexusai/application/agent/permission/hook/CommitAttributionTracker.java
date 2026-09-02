package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.common.RequestContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Commit attribution 追踪器 · 对齐 CC {@code commitAttribution.ts}
 * (Open-ClaudeCode/src/utils/commitAttribution.ts)。
 *
 * <p><b>L1 语义</b>: 维护 CC {@code AttributionState.fileStates} 等价物 —— 按归一化相对路径
 * 记录每个文件由 Claude 贡献的字符数 ({@code claudeContribution})，供 commit/PR attribution
 * 文本渲染消费（等价 CC getEnhancedPRAttribution / getAttributionTexts 读取
 * {@code appState.attribution.fileStates}）。attribution hook（见
 * {@link RegisterAttributionHooks}）在 Edit/Write 工具 PostToolUse 时调用本类更新。
 *
 * <p><b>L2 契约（5 Release Gate，逐行对齐 CC commitAttribution.ts）</b>:
 * <ul>
 *   <li><b>A1</b>: {@link FileState}（CC original: {@code FileAttributionState}
 *       commitAttribution.ts:371-375，字段 contentHash/claudeContribution/mtime）+
 *       {@link #trackFileModification}（CC :402-433）+ {@link #trackFileCreation}（CC :439-447）
 *       + {@link #trackFileDeletion}（CC :453-480）+ {@link #computeFileModificationContribution}
 *       （CC {@code computeFileModificationState} :325-380 claudeContribution 计算）+ {@link #sha256}
 *       （CC :244-246）+ {@link #normalizeFilePath}（CC :252-291）</li>
 *   <li><b>A2 Golden Trace</b>: 首次 Edit "abc"→"abcdef" → contribution=3（新增 3 字符）→
 *       fileStates[file]=claudeContribution:3；二次 Edit 追加 → 累加</li>
 *   <li><b>A3</b>: 纯内存状态 + 内容缓存（CC attributionHooks 模块的 file content cache，由
 *       {@link #clearAttributionCaches} / {@link #sweepFileContentCache} 管理）</li>
 *   <li><b>A4 边界</b>: 空内容按全量计入（CC :338-341 oldContent=='' || newContent=='' →
 *       内容长度）；同长替换走 common prefix/suffix 精确 diff（CC :342-364，杜绝
 *       Math.abs 同长返回 0）</li>
 *   <li><b>A5 业务场景</b>: /commit 前由 attribution hook 积累每文件 claudeContribution，
 *       PR attribution 渲染 "93% 3-shotted by claude-opus-4-5"（attribution.ts:374）</li>
 * </ul>
 *
 * <p><b>L3（Java idiom）</b>: TS Map/对象字面量 → Java {@code Map<String, FileState>}；
 * TS {@code createHash('sha256')} → Java {@link MessageDigest}；TS {@code Date.now()} →
 * {@link System#currentTimeMillis()}；TS {@code relative(cwd, path)} → Java
 * {@link Path#relativize}。repoRoot 以 {@link Supplier} 注入（测试可注入 @TempDir）。
 *
 * <p><b>已知限制（fail-loud）</b>: CC attributionHooks 模块（注册 PostToolUse hook 的具体
 * 接线）在 CC 源码仓库缺失（setup.ts:355 动态 import './utils/attributionHooks.js'，
 * 基线 6618ab1 无此文件）——本类只实现 CC <b>可观测</b> 的追踪函数语义（commitAttribution.ts），
 * hook 接线见 {@link RegisterAttributionHooks}，matcher 集合按 commitAttribution.ts:400-401
 * "Called after Edit/Write tool completes" 假设。
 */
public final class CommitAttributionTracker {

    private static final Logger log = LoggerFactory.getLogger(CommitAttributionTracker.class);

    /**
     * CC original: {@code FileAttributionState}（commitAttribution.ts:371-375）。
     *
     * @param contentHash        CC original: contentHash（:372）— SHA-256(newContent)
     * @param claudeContribution CC original: claudeContribution（:373）— 累计 Claude 贡献字符数
     * @param mtime              CC original: mtime（:374）— 最近修改时间（ms）
     */
    public record FileState(String contentHash, long claudeContribution, long mtime) { }

    /** CC original: {@code AttributionState.fileStates}（commitAttribution.ts:173）—— 归一化路径 → 文件状态. */
    private final Map<String, FileState> fileStates = new HashMap<>();

    /**
     * 文件内容缓存（CC attributionHooks 模块内部 file content cache —— 由
     * {@code sweepFileContentCache()} / {@code clearAttributionCaches()} 管理，
     * postCompactCleanup.ts:73 / clear/caches.ts:106）。用于 Edit/Write PostToolUse 时
     * 取 oldContent 做 diff（首次见到无缓存 → 按空 oldContent = 全量计入）。
     */
    private final Map<String, String> contentCache = new HashMap<>();

    private final Supplier<Path> repoRootSupplier;

    /**
     * CC original: {@code getAttributionRepoRoot}（commitAttribution.ts:83-85）——
     * {@code findGitRoot(getCwd()) ?? getOriginalCwd()} 完整链。
     *
     * <p>CC 实源（已亲读）: {@code const cwd = getCwd(); return findGitRoot(cwd) ?? getOriginalCwd()}
     * —— git root 回落处理 {@code cd subdir} 场景，非 git 目录回落 originalCwd。Java 端 findGitRoot
     * 层复用 {@link AutoMemPaths#findCanonicalGitRoot(String)}（AutoMemPaths.java:555-564，已对齐
     * CC findCanonicalGitRoot git.ts:97-109+123-183+195，多模块复用）；originalCwd 兜底层经
     * {@link CwdResolution#getOriginalCwdLayer(String)}。
     *
     * @param sessionId 会话 ID（null → getCwd/getOriginalCwdLayer 回落 user.dir）
     * @return 恒非 null 的 attribution repo root
     */
    public static String getAttributionRepoRoot(String sessionId) {
        String cwd = CwdResolution.getCwd(sessionId);
        String gitRoot = AutoMemPaths.findCanonicalGitRoot(cwd);
        return gitRoot != null ? gitRoot : CwdResolution.getOriginalCwdLayer(sessionId);
    }

    /** 默认：repoRoot = CC getAttributionRepoRoot 完整链（{@link #getAttributionRepoRoot(String)}
     *  findGitRoot(getCwd()) ?? getOriginalCwd()，commitAttribution.ts:83-85；经
     *  RequestContext.sessionId() 取会话 cwd，findGitRoot 处理 cd subdir 场景）。 */
    public CommitAttributionTracker() {
        this(() -> Path.of(getAttributionRepoRoot(RequestContext.sessionId())));
    }

    /** 完整构造器（测试注入 repoRoot · @TempDir 隔离，镜像 SessionFileAccessHooks 注入式构造器）. */
    public CommitAttributionTracker(Supplier<Path> repoRootSupplier) {
        this.repoRootSupplier = repoRootSupplier;
    }

    // ════════════════════════════════════════════════════════════════
    // 1. 追踪入口 · CC commitAttribution.ts:402-480
    // ════════════════════════════════════════════════════════════════

    /**
     * 追踪文件修改 · CC {@code trackFileModification} (commitAttribution.ts:402-433).
     *
     * <p>计算本文件 Claude 字符贡献（common prefix/suffix diff，见
     * {@link #computeFileModificationContribution}），与既有贡献累加后写入 fileStates。
     *
     * @param filePath   工具 file_path（绝对或相对 repoRoot）
     * @param oldContent 修改前完整文件内容（空 = 新建/未知，按全量计入）
     * @param newContent 修改后完整文件内容
     * @param mtime      修改时间（ms）· CC default Date.now()
     */
    public synchronized void trackFileModification(
            String filePath, String oldContent, String newContent, long mtime) {
        String normalized = normalizeFilePath(filePath);
        long contribution = computeFileModificationContribution(
            oldContent == null ? "" : oldContent, newContent == null ? "" : newContent);
        FileState existing = fileStates.get(normalized);
        long existingContribution = existing != null ? existing.claudeContribution() : 0L;
        FileState newState = new FileState(sha256(newContent == null ? "" : newContent),
            existingContribution + contribution, mtime);
        fileStates.put(normalized, newState);
        if (log.isDebugEnabled()) {
            log.debug("[CommitAttributionTracker] 追踪文件修改: path={} contribution={} 累计={}",
                normalized, contribution, newState.claudeContribution());
        }
    }

    /**
     * 追踪文件创建 · CC {@code trackFileCreation} (commitAttribution.ts:439-447) —— 等价
     * trackFileModification(state, path, '', content, false, mtime)（从空到新内容）。
     */
    public synchronized void trackFileCreation(String filePath, String content, long mtime) {
        trackFileModification(filePath, "", content == null ? "" : content, mtime);
    }

    /**
     * 追踪文件删除 · CC {@code trackFileDeletion} (commitAttribution.ts:453-480) ——
     * 已删字符数计入贡献（contentHash 置空），保留 fileStates 条目供删除净变化计算。
     */
    public synchronized void trackFileDeletion(String filePath, String oldContent) {
        String normalized = normalizeFilePath(filePath);
        FileState existing = fileStates.get(normalized);
        long existingContribution = existing != null ? existing.claudeContribution() : 0L;
        long deletedChars = oldContent == null ? 0L : oldContent.length();
        FileState newState = new FileState("",
            existingContribution + deletedChars, System.currentTimeMillis());
        fileStates.put(normalized, newState);
        if (log.isDebugEnabled()) {
            log.debug("[CommitAttributionTracker] 追踪文件删除: path={} 删除字符={} 累计={}",
                normalized, deletedChars, newState.claudeContribution());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 2. claudeContribution 计算 · CC computeFileModificationState :325-380
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算一次文件修改的 Claude 字符贡献 · CC {@code computeFileModificationState}
     * (commitAttribution.ts:336-365)。
     *
     * <ul>
     *   <li>oldContent=='' 或 newContent==''（新建/全删）→ 内容长度（CC :338-341）</li>
     *   <li>否则 common prefix/suffix 精确 diff，贡献 = max(oldChangedLen, newChangedLen)
     *       （CC :342-364）——同长替换（"Esc"→"esc"）Math.abs 为 0 而本算法正确识别</li>
     * </ul>
     */
    static long computeFileModificationContribution(String oldContent, String newContent) {
        if (oldContent == null || newContent == null) {
            return 0L;
        }
        if (oldContent.isEmpty() || newContent.isEmpty()) {
            return oldContent.isEmpty() ? newContent.length() : oldContent.length();
        }
        int minLen = Math.min(oldContent.length(), newContent.length());
        int prefixEnd = 0;
        while (prefixEnd < minLen
                && oldContent.charAt(prefixEnd) == newContent.charAt(prefixEnd)) {
            prefixEnd++;
        }
        int suffixLen = 0;
        while (suffixLen < minLen - prefixEnd
                && oldContent.charAt(oldContent.length() - 1 - suffixLen)
                    == newContent.charAt(newContent.length() - 1 - suffixLen)) {
            suffixLen++;
        }
        int oldChangedLen = oldContent.length() - prefixEnd - suffixLen;
        int newChangedLen = newContent.length() - prefixEnd - suffixLen;
        return Math.max(oldChangedLen, newChangedLen);
    }

    // ════════════════════════════════════════════════════════════════
    // 3. 内容缓存管理 · CC attributionHooks 模块接口
    // ════════════════════════════════════════════════════════════════

    /** 缓存某路径当前内容（供下次 diff 取 oldContent）· 归一化键. */
    public synchronized void updateCachedContent(String filePath, String content) {
        contentCache.put(normalizeFilePath(filePath), content);
    }

    /** 取缓存内容（无 → null = 首次见到，按空 oldContent 全量计入）. */
    public synchronized String cachedContent(String filePath) {
        return contentCache.get(normalizeFilePath(filePath));
    }

    /** CC original: {@code clearAttributionCaches()}（clear/caches.ts:106）—— 清空内容缓存. */
    public synchronized void clearAttributionCaches() {
        contentCache.clear();
        if (log.isDebugEnabled()) {
            log.debug("[CommitAttributionTracker] clearAttributionCaches: 内容缓存已清空");
        }
    }

    /** CC original: {@code sweepFileContentCache()}（postCompactCleanup.ts:73）—— 压缩后清扫缓存. */
    public synchronized void sweepFileContentCache() {
        contentCache.clear();
        if (log.isDebugEnabled()) {
            log.debug("[CommitAttributionTracker] sweepFileContentCache: 压缩后内容缓存已清扫");
        }
    }

    /** 会话归零（新 run 语义）· 清空 fileStates + 内容缓存. */
    public synchronized void reset() {
        fileStates.clear();
        contentCache.clear();
    }

    /** fileStates 不可变快照（供 attribution 文本渲染 / 测试断言）. */
    public synchronized Map<String, FileState> snapshotFileStates() {
        return new HashMap<>(fileStates);
    }

    // ════════════════════════════════════════════════════════════════
    // 4. 辅助 · CC commitAttribution.ts:244-246 / :252-291
    // ════════════════════════════════════════════════════════════════

    /** SHA-256 hex · CC {@code computeContentHash} (commitAttribution.ts:244-246). */
    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 归一化文件路径 · CC {@code normalizeFilePath} (commitAttribution.ts:252-291)。
     *
     * <p>绝对路径且位于 repoRoot 内 → 相对 repoRoot 的路径 + 正斜杠（键与 git diff 输出一致，
     * Windows 反斜杠统一）；repoRoot 外 / 相对路径 → 原样（仅统一分隔符）。Java 端不做
     * realpath 符号链接解析（跨平台差异，CC :260-275 macOS /tmp vs /private/tmp 专属）。
     */
    static String normalizeFilePath(String filePath, Path repoRoot) {
        if (filePath == null || filePath.isBlank()) {
            return filePath;
        }
        String forward = filePath.replace('\\', '/');
        try {
            Path p = Path.of(filePath);
            if (p.isAbsolute() && repoRoot != null) {
                Path root = repoRoot.toAbsolutePath().normalize();
                Path rel = root.relativize(p.normalize());
                if (!rel.toString().startsWith("..")) {
                    return rel.toString().replace('\\', '/');
                }
            }
        } catch (Exception e) {
            // 非法路径 → 原样返回（fail-loud 不吞异常，仅退化键）
        }
        return forward;
    }

    /** 实例版（注入 repoRoot）· 供 hook 链调用. */
    public String normalizeFilePath(String filePath) {
        return normalizeFilePath(filePath, repoRootSupplier.get());
    }

    /** 当前 repoRoot（hook 读文件用）· 测试可注入 @TempDir. */
    public Path repoRoot() {
        Path root = repoRootSupplier.get();
        return root != null ? root : Path.of(".");
    }

    /** 供日志/遥测的事件名小写形式（对齐 CC 事件命名惯例）. */
    static String lowerCamel(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
