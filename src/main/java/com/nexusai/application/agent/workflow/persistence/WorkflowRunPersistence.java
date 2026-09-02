package com.nexusai.application.agent.workflow.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.workflow.ProgressBus;
import com.nexusai.application.agent.workflow.ProgressEvent;
import com.nexusai.application.agent.workflow.progress.ProgressStore;
import com.nexusai.application.agent.workflow.progress.RunProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * workflow run 状态文件持久化 · CC original: {@code persistence.ts}
 * (Open-ClaudeCode/src/workflow/persistence.ts:1-211)。
 *
 * <p>把终态 RunProgress 原子写入 {@code <runsDir>/<runId>/state.json}，重启后可经
 * {@code getRunAsync / loadPersistedRuns} 恢复。格式（CC persistence.ts:36-39 StateFile）：
 * <pre>
 * { "schemaVersion": 1, "run": { runId, workflowName, status, ... } }
 * </pre>
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 行为意图）</b>：
 * <ol>
 *   <li><b>原子性</b>（persistence.ts:42-45）：write tmp → rename(target)，rename 原子；
 *       最坏残留 tmp，下次写入覆盖。</li>
 *   <li><b>best-effort 不抛</b>（persistence.ts:44-45）：IO 异常仅 log warn——workflow 已成功，
 *       持久化失败只意味着重启后取不回，绝不阻断 bus 其他订阅者（store 等）。</li>
 *   <li><b>fault-tolerant 读取</b>（persistence.ts:65-95）：文件缺失 → null（miss）；
 *       schemaVersion 不符 / 结构非法 → null（静默）；JSON parse 异常 → null（log warn），不 crash。</li>
 *   <li><b>磁盘有界</b>（persistence.ts:20-25 KEEP_MAX_RUNS=50）：run_done 写盘后
 *       cleanupOldRuns 清理最旧目录，孤儿（无 state.json）优先。</li>
 * </ol>
 *
 * <p><b>runsDir 单一源</b>（persistence.ts:27-34 getRunsDir）：与 ports.ts journalStore 同根
 * （{@code <projectRoot>/<WORKFLOW_RUNS_DIR>} = {@code <projectRoot>/.{appName}/workflow-runs}）。本类实例持有 {@code runsDirProvider}
 * —— 生产 = {@code WorkflowPortsImpl.defaultRunsDir} 等价（会话绑定项目根）；测试注入 tmpdir。
 *
 * <p><b>Java 侧 on-disk 格式说明（显式决策）</b>：{@code run.status} 枚举按 Jackson 默认序列化
 * （{@code COMPLETED} 大写）；Java 自写自读同一 mapper 内部一致，与 CC 的小写 status 字符串
 * 不做字节对齐（跨语言独立运行时，state.json 无跨语言消费者）。读取侧开启
 * {@code ACCEPT_CASE_INSENSITIVE_ENUMS} 兜底大小写容错。
 */
public final class WorkflowRunPersistence {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunPersistence.class);

    /** state.json 当前 schema 版本 · CC original: SCHEMA_VERSION (persistence.ts:16)。 */
    public static final int SCHEMA_VERSION = 1;
    /** 终态文件 · CC original: STATE_FILE (persistence.ts:17)。 */
    public static final String STATE_FILE = "state.json";
    /** 原子写入临时文件 · CC original: STATE_TMP (persistence.ts:18)。 */
    public static final String STATE_TMP = "state.json.tmp";
    /** 磁盘 run 目录硬上限 · CC original: KEEP_MAX_RUNS (persistence.ts:25)。 */
    public static final int KEEP_MAX_RUNS = 50;

    /**
     * 序列化 mapper · 容错读取（未知字段忽略 + 枚举大小写不敏感），供 state.json 自写自读。
     */
    private static final ObjectMapper MAPPER = createMapper();

    private static ObjectMapper createMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        m.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
        return m;
    }

    /** runsDir 单一源 · 生产 = WorkflowPortsImpl.defaultRunsDir 等价；测试注入 tmpdir。 */
    private final Supplier<String> runsDirProvider;

    /**
     * 构造 · 类加载即持有 runsDirProvider（persistence.ts:186-193 attachRunStatePersistence 的
     * runsDirProvider 参数等价物；生产默认、测试注入 tmp）。
     *
     * @param runsDirProvider runsDir 解析器（{@code <projectRoot>/.{appName}/workflow-runs}，appName 默认 nexusai）
     */
    public WorkflowRunPersistence(Supplier<String> runsDirProvider) {
        this.runsDirProvider = runsDirProvider != null ? runsDirProvider : () -> null;
    }

    /**
     * 单一 runsDir 源 · CC original: {@code getRunsDir()} (persistence.ts:32-34)。
     *
     * <p>复用调用方传入的 runsDir（Java 端 runsDir 由 {@code runsDirProvider} 解析后传入，
     * 与 CC {@code getRunsDir() = join(getProjectRoot(), '.claude', 'workflow-runs')}
     * 的"单一源"语义等价——此处不重新拼接路径，消除 ports 与持久化间的重复拼接）。
     *
     * @param runsDir 调用方解析的 runsDir
     * @return 原样返回（单一源透传）
     */
    public static String getRunsDir(String runsDir) {
        return runsDir;
    }

    /**
     * 原子覆盖终态 RunProgress 到 {@code <runsDir>/<runId>/state.json} · CC original:
     * {@code writeRunState} (persistence.ts:46-63)。
     *
     * <p>原子性：writeFile(tmp) → rename(tmp, target)（persistence.ts:43）；失败 best-effort：
     * IO 异常仅 log warn 不抛（workflow 已成功，persistence 失败仅意味着重启后取不回）。
     *
     * @param runsDir 持久化根目录（{@code <projectRoot>/.{appName}/workflow-runs}，appName 默认 nexusai）
     * @param run     终态 RunProgress（completed/failed/killed）
     */
    public void writeRunState(String runsDir, RunProgress run) {
        if (runsDir == null || run == null || run.runId() == null) {
            log.warn("[workflow warn] writeRunState 参数缺失，跳过（runsDir={} runId={}）",
                    runsDir, run == null ? "null" : run.runId());
            return;
        }
        Path dir = Path.of(runsDir, run.runId());
        Path target = dir.resolve(STATE_FILE);
        Path tmp = dir.resolve(STATE_TMP);
        try {
            // persistence.ts:55 mkdir(dir, {recursive:true})
            Files.createDirectories(dir);
            // persistence.ts:53 StateFile = {schemaVersion: SCHEMA_VERSION, run}
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("schemaVersion", SCHEMA_VERSION);
            payload.set("run", MAPPER.valueToTree(run));
            // persistence.ts:56 writeFile(tmp) → :57 rename(tmp, target)
            Files.writeString(tmp, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            if (log.isDebugEnabled()) {
                log.debug("writeRunState 完成：runId={} → {}（原子 tmp+rename，persistence.ts:46-63）",
                        run.runId(), target);
            }
        } catch (Exception e) {
            // persistence.ts:58-62：仅 log warn，不抛（不阻断 bus 其他订阅者）
            log.warn("[workflow warn] writeRunState failed for {}: {}（best-effort 不抛，persistence.ts:58-62）",
                    run.runId(), e.getMessage());
        }
    }

    /**
     * 读 {@code <runsDir>/<runId>/state.json} · CC original: {@code readRunState}
     * (persistence.ts:70-95)。
     *
     * <p>fault-tolerant：
     * <ul>
     *   <li>文件不存在 → {@code null}（调用方当 miss，persistence.ts:76-80）</li>
     *   <li>schemaVersion 不符 → {@code null}（persistence.ts:83，静默）</li>
     *   <li>run 非对象 / runId 非 string / status 非 string → {@code null}（persistence.ts:85-87，静默）</li>
     *   <li>JSON parse 异常 → {@code null} + log warn（persistence.ts:89-93）</li>
     * </ul>
     *
     * @param runsDir 持久化根目录
     * @param runId   目标 run id
     * @return RunProgress 或 null（miss/损坏）
     */
    public RunProgress readRunState(String runsDir, String runId) {
        if (runsDir == null || runId == null) {
            return null;
        }
        Path target = Path.of(runsDir, runId, STATE_FILE);
        if (!Files.isRegularFile(target)) {
            // persistence.ts:76-80 文件不存在 → null（静默 miss，不 warn）
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(target.toFile());
            if (root == null || !root.isObject()) {
                return null;
            }
            // persistence.ts:83 schemaVersion 不符 → null（静默）
            JsonNode sv = root.get("schemaVersion");
            if (sv == null || !sv.canConvertToInt() || sv.asInt() != SCHEMA_VERSION) {
                return null;
            }
            JsonNode runNode = root.get("run");
            // persistence.ts:85-87 run 结构校验（非对象 → null）
            if (runNode == null || !runNode.isObject()) {
                return null;
            }
            RunProgress run = MAPPER.treeToValue(runNode, RunProgress.class);
            // persistence.ts:86-87 runId / status 必须是 string（Java record 类型化后为 null 校验）
            if (run == null || run.runId() == null || run.status() == null) {
                return null;
            }
            if (log.isDebugEnabled()) {
                log.debug("readRunState 命中：runId={} status={}（persistence.ts:70-95）",
                        run.runId(), run.status());
            }
            return run;
        } catch (Exception e) {
            // persistence.ts:89-93 JSON parse 失败 → warn + null（不 crash）
            log.warn("[workflow warn] readRunState parse failed for {}: {}（fault-tolerant，persistence.ts:89-93）",
                    runId, e.getMessage());
            return null;
        }
    }

    /**
     * 扫描 runsDir 全部子目录、读各 state.json、返回非 null RunProgress 列表 · CC original:
     * {@code listPersistedRuns} (persistence.ts:106-123)。
     *
     * <ul>
     *   <li>runsDir 不存在 → 空列表（persistence.ts:111-115）</li>
     *   <li>无 state.json 的子目录（半写入）→ 跳过</li>
     *   <li>单个 state.json 损坏 → 跳过该目录、继续扫其余（不中断）</li>
     *   <li>按 updatedAt 降序（与 store.list() 一致，persistence.ts:121）</li>
     *   <li>可选 limit：仅留最新 N 个（loadPersistedRuns 用，防面板被历史淹没；省略则全量）</li>
     * </ul>
     *
     * @param runsDir 持久化根目录
     * @param limit   可空上限（null 或 &lt;0 → 全量；否则取最新 N 个）
     * @return 非 null 的 RunProgress 列表（降序）
     */
    public List<RunProgress> listPersistedRuns(String runsDir, Integer limit) {
        if (runsDir == null) {
            return List.of();
        }
        Path dir = Path.of(runsDir);
        String[] entries;
        try {
            entries = dir.toFile().list();
        } catch (Exception e) {
            // persistence.ts:111-115 readdir 失败 → 空列表
            log.warn("[workflow warn] listPersistedRuns readdir 失败 {}: {}（返回空，persistence.ts:111-115）",
                    dir, e.getMessage());
            return List.of();
        }
        if (entries == null) {
            return List.of();
        }
        List<RunProgress> runs = new ArrayList<>();
        for (String name : entries) {
            RunProgress run = readRunState(runsDir, name);
            if (run != null) {
                runs.add(run);
            }
        }
        // persistence.ts:121 降序
        runs.sort((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()));
        // persistence.ts:122 limit >= 0 才截断；slice(0, limit) 超长返回全量
        if (limit != null && limit >= 0 && runs.size() > limit) {
            return List.copyOf(runs.subList(0, limit));
        }
        return List.copyOf(runs);
    }

    /**
     * GC 过期 run 目录 · CC original: {@code cleanupOldRuns} (persistence.ts:136-171)。
     *
     * <p>按各子目录 state.json.updatedAt 降序排（最新在前），超出 keepMax 的全部递归删除。
     * 无 state.json 的子目录（孤儿：半写入 / 写入中被杀 / 旧 schema 遗留）视作最旧
     * （updatedAt=0）→ 最先被清（persistence.ts:126-131）。
     *
     * <p>best-effort：单目录失败仅 log 不中止全扫（persistence.ts:132-134）；幂等——
     * 已低于 cap 时 no-op。keepMax 负值 clamp 到 0（slice 负参语义反转会保留 N 个最新，
     * 与契约相反；clamp 后最坏清空，persistence.ts:155-157）。
     *
     * @param runsDir 持久化根目录
     * @param keepMax 保留上限（默认 KEEP_MAX_RUNS=50）
     * @return 实际删除的目录数
     */
    public int cleanupOldRuns(String runsDir, int keepMax) {
        if (runsDir == null) {
            return 0;
        }
        Path dir = Path.of(runsDir);
        String[] entries;
        try {
            entries = dir.toFile().list();
        } catch (Exception e) {
            log.warn("[workflow warn] cleanupOldRuns readdir 失败 {}: {}（返回 0，persistence.ts:140-145）",
                    dir, e.getMessage());
            return 0;
        }
        if (entries == null) {
            return 0;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (String name : entries) {
            RunProgress run = readRunState(runsDir, name);
            // persistence.ts:150-151 updatedAt=0 → 无 state.json 的孤儿 → 排最前被清
            candidates.add(new Candidate(name, run != null ? run.updatedAt() : 0));
        }
        // persistence.ts:154 最新在前；孤儿（updatedAt=0）沉底先被清
        candidates.sort((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()));
        // persistence.ts:157 cap clamp(0)
        int cap = Math.max(0, keepMax);
        int removed = 0;
        if (cap >= candidates.size()) {
            return 0;
        }
        for (int i = cap; i < candidates.size(); i++) {
            Candidate v = candidates.get(i);
            try {
                // persistence.ts:162 rm(join(runsDir, v.name), {recursive:true, force:true})
                deleteRecursively(dir.resolve(v.name));
                removed++;
                if (log.isDebugEnabled()) {
                    log.debug("cleanupOldRuns 已删除过时 run 目录：{}（persistence.ts:159-169）", v.name);
                }
            } catch (Exception e) {
                // persistence.ts:164-167 单目录失败仅 log，不中止全扫
                log.warn("[workflow warn] cleanupOldRuns failed to remove {}: {}（best-effort 继续，persistence.ts:164-167）",
                        v.name, e.getMessage());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("cleanupOldRuns 完成：keepMax={} 共删除 {} 个（persistence.ts:136-171）", keepMax, removed);
        }
        return removed;
    }

    /**
     * 订阅 bus 的 run_done → 写终态 RunProgress 到磁盘 state.json · CC original:
     * {@code attachRunStatePersistence} (persistence.ts:190-210)。
     *
     * <p>覆盖三个终态（completed/failed/killed；shutdown-kill 也路由到 run_done killed）。
     * store 先于本订阅注册到 bus，故监听器跑时 {@code store.get(runId)} 已是终态
     * （persistence.ts:176-177）。
     *
     * <p>写盘 best-effort（writeRunState 吞 IO 异常仅 log），不传播——bus 其他订阅者
     * （store 等）不受影响（persistence.ts:179-181）。
     *
     * <p><b>cleanup 在写盘之后</b>（persistence.ts:182-184）：保证刚完成的 run 已在磁盘且
     * 计为最新——绝不把自己清出去（persistence.ts:200-208 fire-and-forget）。
     *
     * @param bus   ProgressBus（Spring 单例，与 store/telemetry 共享）
     * @param store ProgressStore（run_done 时 get 终态）
     * @return 退订 Runnable（测试清理用）
     */
    public Runnable attachRunStatePersistence(ProgressBus bus, ProgressStore store) {
        if (bus == null || store == null) {
            return () -> {
            };
        }
        return bus.subscribe(event -> {
            // persistence.ts:196-197 非 run_done 忽略
            if (!(event instanceof ProgressEvent.RunDone done)) {
                return;
            }
            // persistence.ts:198-199 store.get(runId) 已是终态（store 先订阅）
            RunProgress run = store.get(done.runId());
            if (run == null) {
                return;
            }
            try {
                String dir = runsDirProvider.get();
                // persistence.ts:200 写盘（best-effort 吞异常）
                writeRunState(dir, run);
                // persistence.ts:203-208 写盘后才清（新 run 已在磁盘并计为最新，绝不误删自己）
                cleanupOldRuns(dir, KEEP_MAX_RUNS);
            } catch (Exception e) {
                // persistence.ts:203-206 兜底：cleanupOldRuns 抛了只 log
                log.warn("[workflow warn] cleanupOldRuns after run_done threw: {}（runId={}，persistence.ts:203-206）",
                        e.getMessage(), done.runId());
            }
        });
    }

    /** 候选目录 · CC original: persistence.ts:146 {@code {name, updatedAt}}。 */
    private record Candidate(String name, long updatedAt) {
    }

    /** 递归删除目录（对齐 CC rm recursive:true + force:true）。 */
    private static void deleteRecursively(Path target) throws IOException {
        if (Files.isDirectory(target)) {
            try (var stream = Files.list(target)) {
                var children = stream.toList();
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(target);
    }
}
