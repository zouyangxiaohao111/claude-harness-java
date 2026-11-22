package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 重启后从磁盘重建退役表（{@link RetiredAgentRegistry}）。
 *
 * <h2>WHY</h2>
 * <p>退役表与活跃名册都在内存，进程重启即空 ⇒ 重启后按名给「已完成」的子代理发消息会
 * not-found，{@code transcript} 恢复（8 步判定的 agent-evicted 分支）走不到。
 * CC 的名册由 appState 持有，但 agent 的 sidecar（{@code subagents/agent-<id>.meta.json} +
 * {@code agent-<id>.jsonl}）在磁盘上，故重启后可以按 sidecar 重建「这批名字曾经存在」。
 *
 * <p><b>只读磁盘、只写退役表</b>：不注册活跃、不启动线程、不占配额、不写 DB。
 *
 * <p><b>名字来源（对齐 CC）</b>：优先用主会话 DB 消息里 tool_use（{@code Agent}/{@code Task}
 * 入参 {@code name}）与 tool_result（{@code status:"async_launched"} + {@code agentId}）的配对
 * 结果（{@link AgentNameRecovery}），<b>缺失才回落</b> sidecar meta 的 {@code name}。历史数据
 * 的 meta 可能没有 name，而主会话 transcript 的调用链一定有 —— 故 DB 配对是权威来源。
 *
 * <p><b>扫描位置</b>：{@code <sessionDir>/<sessionId>/subagents/agent-*.meta.json}；同目录下
 * 必须有同名 {@code agent-<id>.jsonl}（没有 transcript 的条目恢复不了，跳过）。
 * 会话目录不存在（= 会话已删除，{@link com.nexusai.application.agent.tool.SessionStorage#deleteSessionFiles}
 * 会递归删掉 {@code <sessionId>/}）⇒ 整个会话不扫描，避免把已删除会话复活。
 *
 * <p><b>幂等/容错</b>：每次调用都按磁盘当前内容<b>全量重算并覆盖</b>（不用目录 mtime 记账跳过，
 * 重复调用结果一致）；同一 agentId 以最新 meta 为准（{@link RetiredAgentRegistry#retireScanned}）；
 * meta 损坏 / 缺 {@code name} / 缺 transcript 只计数并 WARN，不抛出、不影响其它条目。
 *
 * <p><b>终态</b>：优先读 meta 的 {@code finished}（子代理结束时由
 * {@link AgentTranscript#markFinished} 落盘）；读不到（旧数据）再按 transcript 末行推断兜底。
 */
public final class RetiredAgentRebuilder {

    private static final Logger log = LoggerFactory.getLogger(RetiredAgentRebuilder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SUBAGENTS_DIR = "subagents";
    private static final String META_PREFIX = "agent-";
    private static final String META_SUFFIX = ".meta.json";

    private RetiredAgentRebuilder() { /* utility */ }

    /**
     * 一次扫描的计数（日志/测试/观测用）。
     *
     * @param metaFiles           扫到的 meta 文件数
     * @param rebuilt             实际写入退役表的条目数
     * @param skippedNoTranscript meta 存在但 transcript 缺失
     * @param skippedCorrupt      meta 不可读 / 非 JSON 对象
     * @param skippedNoName       meta 与 DB 配对都没有可用的 name（旧 subagent meta 只有 agentType）
     * @param nameFromDb          名字来自主会话 DB 的 tool_use/tool_result 配对
     * @param nameFromMeta        名字回落 meta.name
     */
    public record ScanResult(int metaFiles, int rebuilt, int skippedNoTranscript,
                             int skippedCorrupt, int skippedNoName,
                             int nameFromDb, int nameFromMeta) {

        public static final ScanResult EMPTY = new ScanResult(0, 0, 0, 0, 0, 0, 0);

        public int skipped() {
            return skippedNoTranscript + skippedCorrupt + skippedNoName;
        }
    }

    /** 3 参兼容重载（无 DB 名字恢复来源 ⇒ 只用 meta.name）。 */
    public static ScanResult scan(Path sessionDir, String sessionId, RetiredAgentRegistry registry) {
        return scan(sessionDir, sessionId, registry, null);
    }

    /**
     * 扫描某会话的 subagents 目录并重建退役表。
     *
     * <p>名字 = 主会话 DB tool_use/tool_result 配对结果（{@link AgentNameRecovery}）优先，
     * 缺失回落 meta 的 {@code name}；两者都没有 → 计数 {@code skippedNoName} 并跳过。
     *
     * @param sessionDir    会话根目录（{@link ResumeService#resolveSessionDir} 同源）
     * @param sessionId     会话 ID
     * @param registry      退役表落点
     * @param messageSource 主会话 DB 消息读取入口；null = 只读 meta.name（无 DB / 测试）
     * @return 计数；目录不存在 / 入参不可用 → {@link ScanResult#EMPTY}
     */
    public static ScanResult scan(Path sessionDir, String sessionId, RetiredAgentRegistry registry,
                                  AgentNameRecovery.MessageSource messageSource) {
        if (sessionDir == null || sessionId == null || sessionId.isBlank() || registry == null) {
            return ScanResult.EMPTY;
        }
        Path sessionRoot = sessionDir.resolve(sessionId);
        Path dir = sessionRoot.resolve(SUBAGENTS_DIR);
        // 会话不存在（被删除的会话其 <sessionId>/ 整目录已被递归删除）→ 不扫描、不复活。
        if (!Files.isDirectory(sessionRoot) || !Files.isDirectory(dir)) {
            return ScanResult.EMPTY;
        }

        List<Path> metas = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(RetiredAgentRebuilder::isMetaFile).sorted().forEach(metas::add);
        } catch (Exception e) {
            log.warn("[retired-rebuild] 列 subagents 目录失败 session={} dir={}: {}",
                sessionId, dir, e.toString());
            return ScanResult.EMPTY;
        }

        // 主会话 DB 配对（tool_use name ↔ tool_result async_launched/agentId）· 只读、失败降级空
        AgentNameRecovery.Result recovered = AgentNameRecovery.recover(messageSource, sessionId);

        int noTranscript = 0;
        int corrupt = 0;
        int noName = 0;
        int nameFromDb = 0;
        int nameFromMeta = 0;
        // meta 名字先写（稳定序）；DB 名字按配对时序后写 ⇒ 同名以最后一次 spawn 为准。
        List<RetiredAgentRegistry.RetiredAgent> metaNamed = new ArrayList<>();
        Map<String, RetiredAgentRegistry.RetiredAgent> dbNamed = new LinkedHashMap<>();
        for (Path meta : metas) {
            String agentId = agentIdOf(meta);
            if (agentId == null) {
                corrupt++;
                log.warn("[retired-rebuild] meta 文件名不合 agent-<id>.meta.json 形态，跳过: {}", meta);
                continue;
            }
            Path transcript = AgentTranscript.getTranscriptPath(sessionDir, sessionId, agentId);
            if (!Files.isRegularFile(transcript)) {
                noTranscript++;
                log.warn("[retired-rebuild] 无 transcript，跳过 agentId={} session={}", agentId, sessionId);
                continue;
            }
            String metaName;
            try {
                JsonNode node = JSON.readTree(Files.readString(meta));
                if (node == null || !node.isObject()) {
                    corrupt++;
                    log.warn("[retired-rebuild] meta 不是 JSON 对象，跳过 agentId={}: {}", agentId, meta);
                    continue;
                }
                metaName = node.hasNonNull("name") ? node.get("name").asText(null) : null;
            } catch (Exception e) {
                corrupt++;
                log.warn("[retired-rebuild] meta 不可读，跳过 agentId={} file={}: {}",
                    agentId, meta, e.toString());
                continue;
            }
            // 名字：DB 配对（权威，对齐 CC）优先 → 回落 meta.name → 都没有则跳过（⛔ 不猜）。
            String dbName = recovered.nameOf(agentId);
            boolean fromDb = dbName != null && !dbName.isBlank();
            String name = fromDb ? dbName : metaName;
            if (name == null || name.isBlank()) {
                // 旧 subagent meta 只有 agentType/worktreePath/description/cwd，且主会话无配对 ⇒ 无法按名寻址。
                noName++;
                log.info("[retired-rebuild] meta 与 DB 配对均无 name，无法按名重建，跳过 agentId={} session={}",
                    agentId, sessionId);
                continue;
            }
            // 终态：优先读 meta 的 finished（子代理结束时显式落盘）；读不到（旧数据）再按
            // transcript 末行推断兜底。finished:true 但末条显式报错仍判 failed（失败保真）；
            // 其余判 completed —— 末条是 tool/user 也不会再被误判成 incomplete。
            String inferred = AgentTranscript.inferStatus(sessionDir, sessionId, agentId);
            boolean finished = AgentTranscript.readFinishedFlag(sessionDir, sessionId, agentId)
                .orElse(false);
            String terminalStatus = finished
                ? ("failed".equals(inferred) ? "failed" : "completed")
                : inferred;
            String stopReason = AgentTranscript.isStoppedByUserOrMeta(sessionDir, sessionId, agentId)
                ? "user" : null;
            RetiredAgentRegistry.RetiredAgent entry = new RetiredAgentRegistry.RetiredAgent(
                sessionId, name, agentId, terminalStatus, transcript.toString(),
                stopReason, retiredAt(meta, transcript));
            if (fromDb) {
                dbNamed.put(agentId, entry);
                nameFromDb++;
            } else {
                metaNamed.add(entry);
                nameFromMeta++;
            }
        }

        // 写退役表：meta 名字先写（稳定序）；DB 名字按配对时序后写 ⇒ 同名以「最后一次 spawn」为准。
        for (RetiredAgentRegistry.RetiredAgent entry : metaNamed) {
            registry.retireScanned(entry);
        }
        for (AgentNameRecovery.Pair pair : recovered.orderedPairs()) {
            RetiredAgentRegistry.RetiredAgent entry = dbNamed.remove(pair.agentId());
            if (entry != null) {
                registry.retireScanned(entry);
            }
        }
        // 兜底：DB 名字条目未出现在 orderedPairs（理论不可达，防御）。
        for (RetiredAgentRegistry.RetiredAgent entry : dbNamed.values()) {
            registry.retireScanned(entry);
        }

        int rebuilt = nameFromDb + nameFromMeta;
        ScanResult result = new ScanResult(metas.size(), rebuilt, noTranscript, corrupt, noName,
            nameFromDb, nameFromMeta);
        if (result.metaFiles() > 0 || result.skipped() > 0) {
            log.info("[retired-rebuild] session={} 扫描完成 meta={} rebuilt={} (nameFromDb={} "
                    + "nameFromMeta={}) skipped(noTranscript={} corrupt={} noName={})",
                sessionId, result.metaFiles(), result.rebuilt(), result.nameFromDb(),
                result.nameFromMeta(), result.skippedNoTranscript(), result.skippedCorrupt(),
                result.skippedNoName());
        }
        return result;
    }

    private static boolean isMetaFile(Path p) {
        String n = p.getFileName().toString();
        return n.startsWith(META_PREFIX) && n.endsWith(META_SUFFIX) && Files.isRegularFile(p);
    }

    private static String agentIdOf(Path meta) {
        String n = meta.getFileName().toString();
        if (!n.startsWith(META_PREFIX) || !n.endsWith(META_SUFFIX)) {
            return null;
        }
        String id = n.substring(META_PREFIX.length(), n.length() - META_SUFFIX.length());
        return id.isBlank() ? null : id;
    }

    private static String retiredAt(Path meta, Path transcript) {
        try {
            return Instant.ofEpochMilli(Files.getLastModifiedTime(meta).toMillis()).toString();
        } catch (Exception ignore) {
            try {
                return Instant.ofEpochMilli(Files.getLastModifiedTime(transcript).toMillis()).toString();
            } catch (Exception e) {
                return Instant.now().toString();
            }
        }
    }
}
