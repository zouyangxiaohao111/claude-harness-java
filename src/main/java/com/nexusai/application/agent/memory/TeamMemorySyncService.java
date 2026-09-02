package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.team.TeamMemorySyncTypes.SyncState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Team Memory 真实 sync 流编排 · 对齐 CC {@code Open-ClaudeCode/src/services/teamMemorySync/index.ts}
 * pullTeamMemory / pushTeamMemory / syncTeamMemory。
 *
 * <p>CC 真源（2026-08-06 grep -n 自验）：{@code pullTeamMemory} index.ts:770-867（skipEtagCache :
 * 805、serverChecksums 刷新 :839-849、writeRemoteEntriesToLocal :851）；{@code pushTeamMemory}
 * :889-1146（本地读取一次 :921-923、localHashes 一次 :949-952、delta 计算 :966-972、batch 分片
 * :999、412 冲突 → hashes 探针 :1117-1137）；{@code syncTeamMemory} :1153-1191（pull(skipEtagCache=true)
 * 先行 → push）。
 *
 * <p><b>语义（REQ-M-11 / INV-10）</b>：
 * <ul>
 *   <li>push 只上传 delta（内容 hash 与 serverChecksums 不同的 key）· 304 不门控：旧实现「304 →
 *       skip push」丢失本地编辑；CC push 独立于 fetch 的 ETag，304 只影响 pull 的盘写。</li>
 *   <li>删除不传播（DEL-M-15）：本地删文件不上传删除请求，下次 pull 恢复。</li>
 *   <li>412 冲突本地胜：本地编辑不因队友并发 push 被静默丢弃（CC :879-888 注释）。</li>
 *   <li>不写盘镜像（旧实现 PushWithRetry 成功后 merged 写回本地是偏差）—— 服务端新 key 由下次 pull 传播。</li>
 * </ul>
 *
 * <p><b>SyncState 数据对象</b>（DEL-M-16）：由 watcher 每 session 创建，穿过所有 sync 函数；
 * 不是状态机。
 */
@Component
public class TeamMemorySyncService {

    private static final Logger log = LoggerFactory.getLogger(TeamMemorySyncService.class);

    /** 单条目大小上限 · CC MAX_FILE_SIZE_BYTES=250_000（index.ts:75，服务端 per-entry cap 预滤）。 */
    private static final int MAX_FILE_SIZE_BYTES = 250_000;
    /** PUT body 上限 · CC MAX_PUT_BODY_BYTES=200_000（index.ts:89，gateway 413 规避）。 */
    private static final int MAX_PUT_BODY_BYTES = 200_000;
    /** 冲突重试上限 · CC MAX_CONFLICT_RETRIES=2（index.ts:91）。 */
    private static final int MAX_CONFLICT_RETRIES = 2;

    private final TeamMemoryHttpClient httpClient;
    private final TeamMemPaths teamMemPaths;
    private final com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine;

    /** 遥测注入 · null → 不发射（对齐 CC logEvent 可空上下文）。 */
    private volatile com.nexusai.application.agent.telemetry.Telemetry telemetry;

    public TeamMemorySyncService(TeamMemoryHttpClient httpClient, TeamMemPaths teamMemPaths,
                                 com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine) {
        this.httpClient = httpClient;
        this.teamMemPaths = teamMemPaths;
        this.claudemdEngine = claudemdEngine;
    }

    /** 注入遥测（tengu_team_mem_* · index.ts:661/935/1205/1233）· Spring @Component 自动装配（required=false 容错）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTelemetry(com.nexusai.application.agent.telemetry.Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * 遥测双发射 · CC original: {@code logEvent}（index.ts:661/935/1205/1233）。
     * recordEvent + logOTelEvent 双发射 · HookRegistry:278-279 惯例。telemetry 未注入 → 静默跳过。
     */
    private void emitTelemetry(String eventName, Map<String, ?> attributes) {
        if (telemetry == null) {
            return;
        }
        Map<String, Object> attrs = attributes == null
            ? Map.of() : new java.util.HashMap<>(attributes);
        telemetry.recordEvent(eventName, attrs);
        telemetry.logOTelEvent(eventName, attrs);
    }

    /** pull 结果 · 对齐 CC pullTeamMemory 返回（index.ts:770-779）。httpStatus = 失败时 HTTP 状态码
     * （TMS-09：logPull 失败路径遥测属性，index.ts:1195-1216）。 */
    public record PullResult(
        boolean success,
        int filesWritten,
        int entryCount,
        boolean notModified,
        String error,
        String errorType,
        Integer httpStatus
    ) {
        public static PullResult ok(int filesWritten, int entryCount) {
            return new PullResult(true, filesWritten, entryCount, false, null, null, null);
        }
        public static PullResult notModifiedResult() {
            return new PullResult(true, 0, 0, true, null, null, null);
        }
        public static PullResult emptyOk() {
            return new PullResult(true, 0, 0, false, null, null, null);
        }
        public static PullResult fail(String error, String errorType, Integer httpStatus) {
            return new PullResult(false, 0, 0, false, error, errorType, httpStatus);
        }
    }

    /** sync 结果 · 对齐 CC syncTeamMemory 返回（index.ts:1153-1158）。 */
    public record SyncResult(boolean success, int filesPulled, int filesPushed, String error) {}

    /** 本地读取结果（readLocalTeamMemory）· 含被 secret 跳过的文件（PSR M22174）。 */
    record LocalRead(Map<String, String> entries,
                     List<com.nexusai.application.agent.team.TeamMemorySyncTypes.SkippedSecretFile> skippedSecrets) {}

    /** 当前工作目录（getGithubRepo 起点 · CC git.ts:504 getGithubRepo → getRemoteUrl →
     *  resolveGitDir(getCwd())，起点 = getCwd()）。进程级后台线程无会话上下文 → sessionId=null →
     *  {@link CwdResolution#getCwd} 回落 user.dir（测试 System.setProperty user.dir 缝仍生效）。 */
    private Path cwd() {
        return Paths.get(CwdResolution.getCwd(null));
    }

    // ─── Pull · CC pullTeamMemory（index.ts:770-867）────────────────

    /**
     * 从服务端 pull team memory 到本地目录 · CC original: {@code pullTeamMemory}（index.ts:770-867）。
     * skipEtagCache=true 时忽略 lastKnownChecksum（full sync 用）。
     *
     * @return PullResult（filesWritten = 实际写盘数，entryCount = 服务端返回条目数）
     */
    public PullResult pullTeamMemory(SyncState state, String baseUrl, boolean skipEtagCache) {
        long startTime = System.currentTimeMillis();
        if (!httpClient.isAuthAvailable()) {
            return emitPull(startTime, PullResult.fail("OAuth not available", "no_oauth", null));
        }
        String repoSlug = GitRemoteResolver.getGithubRepo(cwd());
        if (repoSlug == null) {
            return emitPull(startTime, PullResult.fail("No git remote found", "no_repo", null));
        }
        String etag = skipEtagCache ? null : state.lastKnownChecksum;
        TeamMemoryHttpClient.FetchResult result = httpClient.fetch(state, baseUrl, repoSlug, etag);
        if (!result.success()) {
            // TMS-09：fetch 失败 → errorType + httpStatus 透传 logPull（CC index.ts:808-812）
            return emitPull(startTime, PullResult.fail(result.error(), result.errorType(),
                result.httpStatus()));
        }
        if (result.notModified()) {
            return emitPull(startTime, PullResult.notModifiedResult());
        }
        if (result.isEmpty() || result.data() == null) {
            // 服务端无数据 —— 清 stale serverChecksums，下次 push 不跳过它认为服务端已持有的 key
            state.serverChecksums.clear();
            return emitPull(startTime, PullResult.emptyOk());
        }
        Map<String, String> entries = result.data().content().entries();
        Map<String, String> responseChecksums = result.data().content().entryChecksums();
        // 从服务端 per-key hash 刷新 serverChecksums（需 #283027；缺失则下次 push 全量，push 成功自愈）
        state.serverChecksums.clear();
        if (responseChecksums != null && !responseChecksums.isEmpty()) {
            state.serverChecksums.putAll(responseChecksums);
        }
        int filesWritten = writeRemoteEntriesToLocal(entries);
        // CC original: clearMemoryFileCaches（index.ts:851-855）—— 写盘后必须清 getMemoryFiles
        // memoize 缓存，否则 claudemd 读到陈旧 team memory（verify-report 唯一接线缺口）
        if (filesWritten > 0) {
            claudemdEngine.clearMemoryFileCaches();
        }
        if (log.isInfoEnabled()) {
            log.info("team-memory-sync: pulled {} files", filesWritten);
        }
        return emitPull(startTime, PullResult.ok(filesWritten, entries.size()));
    }

    /**
     * 拉取遥测 · CC original: {@code logPull}（index.ts:1195-1216，tengu_team_mem_sync_pull）。
     * 属性对齐 CC：success/files_written/not_modified/duration_ms 恒发；errorType/status 仅失败
     * 路径发射（undefined 省略；含 no_oauth/no_repo 门失败与 fetch 失败）。发射后返回原 result。
     */
    private PullResult emitPull(long startTime, PullResult result) {
        if (telemetry == null) {
            return result;
        }
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("success", result.success());
        attrs.put("files_written", result.filesWritten());
        attrs.put("not_modified", result.notModified());
        attrs.put("duration_ms", System.currentTimeMillis() - startTime);
        if (result.errorType() != null) {
            attrs.put("errorType", result.errorType());
        }
        if (result.httpStatus() != null) {
            attrs.put("status", result.httpStatus());
        }
        emitTelemetry("tengu_team_mem_sync_pull", attrs);
        return result;
    }

    // ─── Local file operations · index.ts:567-755 ──────────────────

    /**
     * 读取本地 team memory 目录为 flat key-value map · CC original: {@code readLocalTeamMemory}
     * （index.ts:567-673）。递归 walk；key = 相对 team 目录路径（反斜杠→正斜杠）；空文件含（内容空串）；
     * 超大文件（>250KB）跳过；每个文件先扫描 secret（PSR M22174），命中则跳过并记入 skippedSecrets
     * （不离开机器）。学到的 maxEntries 非 null 时按字母序裁剪。
     */
    LocalRead readLocalTeamMemory(Integer maxEntries) throws IOException {
        Path teamDir = Paths.get(teamMemPaths.getTeamMemPath());
        Map<String, String> entries = new LinkedHashMap<>();
        List<com.nexusai.application.agent.team.TeamMemorySyncTypes.SkippedSecretFile> skippedSecrets = new ArrayList<>();
        walkDir(teamDir, teamDir, entries, skippedSecrets);

        List<String> keys = new ArrayList<>(entries.keySet()).stream().sorted().collect(Collectors.toList());
        if (maxEntries != null && keys.size() > maxEntries) {
            List<String> dropped = keys.subList(maxEntries, keys.size());
            if (log.isWarnEnabled()) {
                log.warn("team-memory-sync: {} local entries exceeds server cap of {}; {} file(s) will NOT sync: {}",
                    keys.size(), maxEntries, dropped.size(), String.join(", ", dropped));
            }
            // CC index.ts:661 tengu_team_mem_entries_capped（total_entries/dropped_count/max_entries）
            emitTelemetry("tengu_team_mem_entries_capped", java.util.Map.of(
                "total_entries", keys.size(),
                "dropped_count", dropped.size(),
                "max_entries", maxEntries));
            Map<String, String> truncated = new LinkedHashMap<>();
            for (String key : keys.subList(0, maxEntries)) {
                truncated.put(key, entries.get(key));
            }
            return new LocalRead(truncated, skippedSecrets);
        }
        return new LocalRead(entries, skippedSecrets);
    }

    /**
     * 递归 walk 目录 · CC original: {@code walkDir}（index.ts:575-633）。
     *
     * <p><b>G-66（F1，M-3 升级）</b>：readdir 错误传播语义 —— CC 仅吞 {ENOENT, EACCES, EPERM}
     * （index.ts:625-628），其余 readdir 错误（EIO/ENOTDIR 等）显式 rethrow（:624-632）→ push 显式
     * 失败。旧实现 {@code listFiles()==null → return} 整体静默跳过子树（EIO 类下 Java 静默漏传，
     * fail-open 比 CC 更宽容）。本实现用 {@link Files#list} 使错误可分类：NoSuchFileException（ENOENT）
     * 与 AccessDeniedException（EACCES/EPERM）吞掉，其余 IOException rethrow（readLocalTeamMemory
     * 已声明 throws，pushTeamMemory 捕获后显式失败）。
     *
     * <p>单文件不可读（stat/readFile 失败）仍跳过（CC :618-620 catch，与 readdir 错误不同层）。
     *
     * <p><b>IMP-D-1（OPD-CM5-D-03，P0 安全项）</b>：symlink 不跟随 —— CC 用 readdir withFileTypes
     * 的 Dirent.isDirectory()/isFile()（index.ts:581），Node Dirent 对 symlink 返回 false（isSymbolicLink
     * 而非 isDirectory/isFile）→ team 目录内 symlink 既不递归也不读取。本实现用
     * {@link Files#isDirectory(Path, java.nio.file.LinkOption...)} /
     * {@link Files#isRegularFile(Path, java.nio.file.LinkOption...)} 带 NOFOLLOW_LINKS 对齐 Dirent
     * 语义：symlink→外部目录/文件不被遍历/读取/上传（旧 File.isDirectory()/isFile() 跟链，fail-open）。
     */
    private void walkDir(Path root, Path dir, Map<String, String> entries,
                         List<com.nexusai.application.agent.team.TeamMemorySyncTypes.SkippedSecretFile> skippedSecrets)
            throws IOException {
        List<Path> children;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            children = stream.collect(Collectors.toList());
        } catch (java.nio.file.NoSuchFileException e) {
            // ENOENT → 吞掉（CC :625-628：目录并发删除 / 从未存在）
            return;
        } catch (java.nio.file.AccessDeniedException e) {
            // EACCES/EPERM → 吞掉（CC :626）
            return;
        }
        // 其余 IOException（NotDirectoryException/EIO 等）→ rethrow（CC :624-632，push 显式失败）
        // [B-5 登记 · IMP-MV2-40] △-15：本循环串行遍历 vs CC walkDir `await Promise.all(dirEntries.map(...))`
        //   （index.ts:578-582 并行）；writeRemoteEntriesToLocal（:305-351）同规格（CC :692 并行）。
        //   功能语义一致（跳过/错误传播/secret 处理相同；排序截断两端一致），仅大团队库首 pull
        //   性能差异 —— 登记不修（纯性能，无正确性影响）。
        for (Path child : children) {
            // [IMP-D-1 · OPD-CM5-D-03] △-9：CC walkDir 用 readdir withFileTypes 的
            //   Dirent.isDirectory()/isFile()（index.ts:581）—— Node Dirent 对 symlink 返回 false
            //   （isSymbolicLink 而非 isDirectory/isFile）→ team 目录内 symlink 既不递归也不读取。
            //   旧 Java File.isDirectory()/isFile() 跟链 → symlink→外部目录/文件被读取并上传（fail-open）。
            //   Files.isDirectory/isRegularFile(NOFOLLOW_LINKS) 对齐 Dirent 语义：symlink 直接跳过。
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                walkDir(root, child, entries, skippedSecrets);
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    long size = Files.size(child);
                    if (size > MAX_FILE_SIZE_BYTES) {
                        if (log.isInfoEnabled()) {
                            log.info("team-memory-sync: skipping oversized file {} ({} > {} bytes)",
                                child.getFileName(), size, MAX_FILE_SIZE_BYTES);
                        }
                        continue;
                    }
                    String content = Files.readString(child, StandardCharsets.UTF_8);
                    String relPath = root.relativize(child).toString().replace('\\', '/');
                    // PSR M22174：上传前先扫描 secret —— 命中则整文件跳过，永不离开机器
                    List<TeamMemorySecretScanner.SecretMatch> secretMatches =
                        TeamMemorySecretScanner.scanForSecrets(content);
                    if (!secretMatches.isEmpty()) {
                        TeamMemorySecretScanner.SecretMatch first = secretMatches.get(0);
                        skippedSecrets.add(new com.nexusai.application.agent.team.TeamMemorySyncTypes.SkippedSecretFile(
                            relPath, first.ruleId(), first.label()));
                        if (log.isWarnEnabled()) {
                            log.warn("team-memory-sync: skipping \"{}\" — detected {}", relPath, first.label());
                        }
                        continue;
                    }
                    entries.put(relPath, content);
                } catch (IOException e) {
                    // 不可读文件跳过（CC :618-620 catch）
                }
            }
        }
    }

    /**
     * 写服务端条目到本地目录 · CC original: {@code writeRemoteEntriesToLocal}（index.ts:689-755）。
     * 每个条目独立：validateTeamMemKey（PathTraversalError → 跳过 + warn）、超大跳过、磁盘内容已匹配
     * 跳过（保持 mtime 不触发 watcher 事件）、mkdir parent + 写。
     *
     * @return 实际写盘文件数
     */
    int writeRemoteEntriesToLocal(Map<String, String> entries) {
        int written = 0;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String relPath = e.getKey();
            String content = e.getValue();
            String validatedPath;
            try {
                validatedPath = teamMemPaths.validateTeamMemKey(relPath);
            } catch (TeamMemPaths.PathTraversalError pt) {
                if (log.isWarnEnabled()) {
                    log.warn("team-memory-sync: {}", pt.getMessage());
                }
                continue;
            } catch (IOException io) {
                // [B-10 登记 · IMP-MV2-40] △-20：validateTeamMemKey 的 IOException（非 PathTraversalError）
                //   Java warn+跳过该条目继续 vs CC 非 PTE 全 rethrow → 整个 pull 失败（fail-closed，
                //   index.ts:696-703）。可触发性极低（唯一裸 IOException 逃逸面 = realpathDeepestExisting
                //   内 isSymbolicLink 竞态，TeamMemPaths:235）；跳过而非放行，无路径逃逸风险 —— 登记不修。
                if (log.isWarnEnabled()) {
                    log.warn("team-memory-sync: validateTeamMemKey failed for \"{}\": {}", relPath, io.getMessage());
                }
                continue;
            }
            int sizeBytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (sizeBytes > MAX_FILE_SIZE_BYTES) {
                if (log.isInfoEnabled()) {
                    log.info("team-memory-sync: skipping oversized remote entry \"{}\"", relPath);
                }
                continue;
            }
            Path file = Paths.get(validatedPath);
            try {
                if (Files.isRegularFile(file) && Files.readString(file).equals(content)) {
                    // 磁盘已匹配 —— 跳过，避免 mtime 变化触发 watcher 事件（CC :717-720）
                    continue;
                }
            } catch (IOException e2) {
                // ENOENT 等 → fall through 到写
            }
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
                written++;
            } catch (IOException e2) {
                if (log.isWarnEnabled()) {
                    log.warn("team-memory-sync: failed to write \"{}\": {}", relPath, e2.getMessage());
                }
            }
        }
        return written;
    }

    // ─── Push · CC pushTeamMemory（index.ts:889-1146）────────────────

    /**
     * 本地 team memory 文件推送服务端（乐观锁）· CC original: {@code pushTeamMemory}（index.ts:889-1146）。
     * delta 上传：只 PUT 本地 hash 与 serverChecksums 不同的 key。412 冲突 → GET ?view=hashes 探针
     * 刷新 serverChecksums、重算 delta（队友已推同内容自动排除）、重试。无 merge 无写盘 —— 队友并发
     * push 的服务端新 key 由下次 pull 传播。本地胜（CC :879-888 注释）。
     */
    public TeamMemoryHttpClient.PushResult pushTeamMemory(SyncState state, String baseUrl) {
        long startTime = System.currentTimeMillis();
        int conflictRetries = 0;
        if (!httpClient.isAuthAvailable()) {
            return emitPush(startTime, new TeamMemoryHttpClient.PushResult(false, 0, null, false,
                "OAuth not available", "no_oauth", null, null, null, null, null), conflictRetries, null);
        }
        String repoSlug = GitRemoteResolver.getGithubRepo(cwd());
        if (repoSlug == null) {
            return emitPush(startTime, new TeamMemoryHttpClient.PushResult(false, 0, null, false,
                "No git remote found", "no_repo", null, null, null, null, null), conflictRetries, null);
        }
        // 本地读一次（含 secret 扫描）；冲突解析不重读盘 —— delta 对刷新后的 serverChecksums 计算，
        // 自然排除服务端来源内容，用户本地编辑不可能被 clobber（CC :916-920 注释）
        LocalRead localRead;
        try {
            localRead = readLocalTeamMemory(state.serverMaxEntries);
        } catch (IOException e) {
            // [B-7 登记 · IMP-MV2-40] △-17：本地读失败 Java 返回失败 PushResult（'unknown'）vs CC
            //   readLocalTeamMemory 异常上抛逃逸到 watcher（index.ts:921 无 try-catch）。用户可见均为
            //   push 失败；Java 更稳（不逃逸）—— 登记不修。
            return emitPush(startTime, new TeamMemoryHttpClient.PushResult(false, 0, null, false,
                "read local team memory failed: " + e.getMessage(), "unknown", null, null, null, null, null),
                conflictRetries, null);
        }
        Map<String, String> entries = localRead.entries();
        List<com.nexusai.application.agent.team.TeamMemorySyncTypes.SkippedSecretFile> skippedSecrets = localRead.skippedSecrets();
        if (!skippedSecrets.isEmpty()) {
            // 用户可见 warn：列跳过文件与类型 label。不阻塞 push —— 只排除这些文件。secret 值永不记录
            String summary = skippedSecrets.stream()
                .map(s -> "\"" + s.path() + "\" (" + s.label() + ")")
                .collect(Collectors.joining(", "));
            if (log.isWarnEnabled()) {
                log.warn("team-memory-sync: {} file(s) skipped due to detected secrets: {}. "
                    + "Remove the secret(s) to enable sync for these files.", skippedSecrets.size(), summary);
            }
            // CC index.ts:935 tengu_team_mem_secret_skipped（file_count + rule_ids，仅 gitleaks 规则 ID，
            //   不泄路径/值 —— 路径可能泄露 repo 结构，CC 注释）
            emitTelemetry("tengu_team_mem_secret_skipped", java.util.Map.of(
                "file_count", skippedSecrets.size(),
                "rule_ids", skippedSecrets.stream().map(s -> s.ruleId()).collect(Collectors.joining(","))));
        }
        // 每个本地条目 hash 一次（循环内重算 delta，本地 hash 稳定）
        Map<String, String> localHashes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            localHashes.put(e.getKey(), TeamMemoryDelta.hashContent(e.getValue()));
        }

        boolean sawConflict = false;
        for (int conflictAttempt = 0; conflictAttempt <= MAX_CONFLICT_RETRIES; conflictAttempt++) {
            // delta：只上传本地 hash 与认为服务端持有的不同的 key（CC :966-972）
            Map<String, String> delta = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : localHashes.entrySet()) {
                String key = e.getKey();
                String localHash = e.getValue();
                String serverChecksum = state.serverChecksums.get(key);
                if (serverChecksum == null || !serverChecksum.equals(localHash)) {
                    delta.put(key, entries.get(key));
                }
            }
            if (delta.isEmpty()) {
                // 无上传 —— fresh pull 后无本地编辑的期望快路径；也是 412 收敛点。
                // CC original: skippedSecrets（index.ts:986 `...(skippedSecrets.length > 0 && { skippedSecrets })`）
                return emitPush(startTime, new TeamMemoryHttpClient.PushResult(true, 0, null, sawConflict,
                    null, null, null, null, null, null,
                    skippedSecrets.isEmpty() ? null : skippedSecrets), conflictRetries, null);
            }

            List<Map<String, String>> batches = batchDeltaByBytes(delta);
            int filesUploaded = 0;
            TeamMemoryHttpClient.UploadResult result = null;
            for (Map<String, String> batch : batches) {
                result = httpClient.upload(state, baseUrl, repoSlug, batch, state.lastKnownChecksum);
                if (!result.success()) {
                    break;
                }
                for (String key : batch.keySet()) {
                    state.serverChecksums.put(key, localHashes.get(key));
                }
                filesUploaded += batch.size();
            }
            if (result != null && result.success()) {
                if (log.isInfoEnabled()) {
                    log.info("team-memory-sync: pushed {} of {} files (delta)", filesUploaded, localHashes.size());
                }
                // CC original: skippedSecrets（index.ts:1042 `...(skippedSecrets.length > 0 && { skippedSecrets })`）
                return emitPush(startTime, new TeamMemoryHttpClient.PushResult(true, filesUploaded,
                    result.checksum(), sawConflict, null, null, null, null, null, null,
                    skippedSecrets.isEmpty() ? null : skippedSecrets),
                    conflictRetries, batches.size() > 1 ? batches.size() : null);
            }

            if (result != null && !result.conflict()) {
                // 结构化 413 学到的 max_entries 缓存，下次 push 裁剪（CC :1053-1059）
                if (result.serverMaxEntries() != null) {
                    state.serverMaxEntries = result.serverMaxEntries();
                    if (log.isWarnEnabled()) {
                        log.warn("team-memory-sync: learned server max_entries={} from 413; next push will truncate to this",
                            result.serverMaxEntries());
                    }
                }
                return emitPush(startTime, new TeamMemoryHttpClient.PushResult(false, filesUploaded, null, false,
                    result.error(), result.errorType(), result.httpStatus(),
                    result.serverErrorCode(), result.serverMaxEntries(), result.serverReceivedEntries(), null),
                    conflictRetries, batches.size() > 1 ? batches.size() : null);
            }

            // 412 冲突 —— 刷新 serverChecksums 后重试（delta 收紧）
            sawConflict = true;
            if (conflictAttempt >= MAX_CONFLICT_RETRIES) {
                if (log.isWarnEnabled()) {
                    log.warn("team-memory-sync: giving up after {} conflict retries", MAX_CONFLICT_RETRIES);
                }
                // [B-11 登记 · IMP-MV2-40] △-21（可达路径）：412 冲突耗尽 → Java PushResult
                //   errorType='conflict'；CC 返回对象无 errorType（index.ts:1099-1104）。遥测与
                //   watcher isPermanentFailure 判定两端一致（均非永久失败）→ 仅返回载荷差异，登记不修。
                return emitPush(startTime, new TeamMemoryHttpClient.PushResult(false, 0, null, true,
                    "Conflict resolution failed after retries", "conflict", null, null, null, null, null),
                    conflictRetries, null);
            }
            conflictRetries++;
            if (log.isInfoEnabled()) {
                log.info("team-memory-sync: conflict (412), probing server hashes (attempt {}/{})",
                    conflictAttempt + 1, MAX_CONFLICT_RETRIES);
            }
            TeamMemoryHttpClient.HashesResult probe = httpClient.fetchHashes(state, baseUrl, repoSlug);
            if (!probe.success() || probe.entryChecksums() == null) {
                // [B-11 登记 · IMP-MV2-40] △-21（可达路径）：hashes 探针失败 → Java PushResult
                //   errorType='conflict'；CC 返回对象无 errorType（index.ts:1127-1133）。同上 ——
                //   遥测属性两端一致，无行为影响，登记不修。
                return emitPush(startTime, new TeamMemoryHttpClient.PushResult(false, 0, null, true,
                    "Conflict resolution hashes probe failed: " + probe.error(), "conflict", null, null, null, null, null),
                    conflictRetries, null);
            }
            state.serverChecksums.clear();
            state.serverChecksums.putAll(probe.entryChecksums());
        }
        return emitPush(startTime, new TeamMemoryHttpClient.PushResult(false, 0, null, true,
            "Unexpected end of conflict resolution loop", "unknown", null, null, null, null, null),
            conflictRetries, null);
    }

    /**
     * 推送遥测 · CC original: {@code logPush}（index.ts:1218-1250，tengu_team_mem_sync_push）。
     * 发射后返回原 result（保持方法链不变）。属性对齐 CC：success/files_uploaded/conflict/
     * conflict_retries/duration_ms/errorType/status/put_batches/error_code/server_max_entries/
     * server_received_entries（undefined 属性省略）。
     */
    private TeamMemoryHttpClient.PushResult emitPush(long startTime,
                                                     TeamMemoryHttpClient.PushResult result,
                                                     int conflictRetries, Integer putBatches) {
        if (telemetry == null) {
            return result;
        }
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("success", result.success());
        attrs.put("files_uploaded", result.filesUploaded());
        attrs.put("conflict", result.conflict());
        attrs.put("conflict_retries", conflictRetries);
        attrs.put("duration_ms", System.currentTimeMillis() - startTime);
        if (result.errorType() != null) {
            attrs.put("errorType", result.errorType());
        }
        if (result.httpStatus() != null) {
            attrs.put("status", result.httpStatus());
        }
        if (putBatches != null) {
            attrs.put("put_batches", putBatches);
        }
        if (result.serverErrorCode() != null) {
            attrs.put("error_code", result.serverErrorCode());
        }
        if (result.serverMaxEntries() != null) {
            attrs.put("server_max_entries", result.serverMaxEntries());
        }
        if (result.serverReceivedEntries() != null) {
            attrs.put("server_received_entries", result.serverReceivedEntries());
        }
        emitTelemetry("tengu_team_mem_sync_push", attrs);
        return result;
    }

    // ─── batchDeltaByBytes · CC index.ts:426-460 ────────────────────

    /**
     * 把 delta 按 MAX_PUT_BODY_BYTES 切为 PUT 批 · CC original: {@code batchDeltaByBytes}（index.ts:426-460）。
     * 贪婪装箱，key 排序 → 跨调用确定性批（ETag 稳定性，冲突循环重试部分提交后仍稳定）。
     * 单条目超过上限 → 独立 solo 批（MAX_FILE_SIZE_BYTES 已限制单文件）。
     */
    static List<Map<String, String>> batchDeltaByBytes(Map<String, String> delta) {
        List<String> keys = new ArrayList<>(delta.keySet());
        keys.sort(String::compareTo);
        List<Map<String, String>> batches = new ArrayList<>();
        if (keys.isEmpty()) {
            return batches;
        }
        // 固定开销 `{"entries":{}}` + 每条目边际字节
        int emptyBodyBytes = "{\"entries\":{}}".getBytes(StandardCharsets.UTF_8).length;
        Map<String, String> current = new LinkedHashMap<>();
        int currentBytes = emptyBodyBytes;
        for (String key : keys) {
            int entryBytes = jsonBytes(key).length + jsonBytes(delta.get(key)).length + 2;
            if (currentBytes + entryBytes > MAX_PUT_BODY_BYTES && !current.isEmpty()) {
                batches.add(current);
                current = new LinkedHashMap<>();
                currentBytes = emptyBodyBytes;
            }
            current.put(key, delta.get(key));
            currentBytes += entryBytes;
        }
        batches.add(current);
        return batches;
    }

    /** JSON 字符串字面量字节数（含引号）· 对齐 CC {@code Buffer.byteLength(jsonStringify(s))}
     *  （index.ts:436-439；{@code jsonStringify} ≡ {@code JSON.stringify}，utils/slowOperations.ts:170-194）。 */
    static byte[] jsonBytes(String s) {
        try {
            byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            // JSON.stringify 转义（ECMAScript QuoteJSONString）：`"` `\` \b \t \n \f \r → 2 字节转义（+1）；
            // 其余 0x00-0x1F（0x00-0x07 / 0x0B / 0x0E-0x1F）→ \\uXXXX 6 字节（原始 1 字节 → +5）。
            // 旧实现对所有 <0x20 仅 +1 → 0x00-0x07/0x0B/0x0E-0x1F 少算 4B/字符 → batch 边界偏移
            // （极端内容下潜在 gateway 413，CM-D1 △-1，IMP-CM-10）。
            int extra = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\' || c == '\b' || c == '\t' || c == '\n'
                    || c == '\f' || c == '\r') {
                    extra++;   // 2 字节转义
                } else if (c < 0x20) {
                    extra += 5;  // \\uXXXX（6 字节）
                }
            }
            byte[] quoted = new byte[raw.length + extra + 2];
            quoted[0] = '"';
            System.arraycopy(raw, 0, quoted, 1, raw.length);
            quoted[quoted.length - 1] = '"';
            return quoted;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    // ─── Sync · CC syncTeamMemory（index.ts:1153-1191）──────────────

    /**
     * 双向 sync：pull（skipEtagCache=true）→ push（冲突解析）· CC original: {@code syncTeamMemory}
     * （index.ts:1153-1191）。服务端条目在冲突时优先（last-write-wins by server）。
     */
    public SyncResult syncTeamMemory(SyncState state, String baseUrl) {
        PullResult pullResult = pullTeamMemory(state, baseUrl, true);
        if (!pullResult.success()) {
            return new SyncResult(false, 0, 0, pullResult.error());
        }
        TeamMemoryHttpClient.PushResult pushResult = pushTeamMemory(state, baseUrl);
        if (!pushResult.success()) {
            return new SyncResult(false, pullResult.filesWritten(), 0, pushResult.error());
        }
        if (log.isInfoEnabled()) {
            log.info("team-memory-sync: synced (pulled {}, pushed {})",
                pullResult.filesWritten(), pushResult.filesUploaded());
        }
        return new SyncResult(true, pullResult.filesWritten(), pushResult.filesUploaded(), null);
    }
}
