package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent Transcript 持久化 · 对齐 CC sessionStorage.ts
 *
 * <p>文件结构：
 * <pre>
 * {sessionDir}/{sessionId}/subagents/agent-{agentId}.jsonl
 * {sessionDir}/{sessionId}/subagents/agent-{agentId}.meta.json
 * </pre>
 */
public class AgentTranscript {

    private static final Logger log = LoggerFactory.getLogger(AgentTranscript.class);

    /**
     * 子代理 id 列表缓存（key = sessionDir + sessionId，TTL 8s）。
     *
     * <p>WHY：单会话可达 5513 个 transcript 文件，每次请求都要 stat 全部取 mtime 排序，
     * 实测 1.6~1.8s。列表本身变化很慢（新子代理才会有新文件），缓存几秒即可把重复请求降到毫秒级。
     */
    private static final long LIST_CACHE_TTL_MS = 60_000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, CachedIdList> LIST_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedIdList(List<String> ids, long at) {}
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * [agent-notice-persist] 本进程已落盘的 {@code transcriptPath|uuid}。
     *
     * <p><b>WHY</b>：SendMessage 通知现在「入队即落库」（{@code AgentNameRegistry#queue}），
     * 而消费侧仍会 {@code state.appendMessage(notify)} 走 appendListener → 本方法再写一次
     * 同 uuid 的行。两次写会让子代理视图出现重复通知条，也会让 parentUuid 链分叉。
     * 这里按 uuid 幂等去重：第二次写**跳过落盘/推送**，但调用方的 {@code currentParentUuid}
     * 仍会推进到该 uuid ⇒ 后续消息的链继续走通知行，不会把它变成孤儿 leaf。
     *
     * <p>只在进程内有效：队列本身也是内存态，重启后不存在「同一待办再消费一次」的场景。
     */
    private static final Set<String> RECORDED_KEYS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 去重集合上限 · 超限整体清空（极端长时间进程下防无界增长；清空最坏只多写一次）。 */
    private static final int RECORDED_KEYS_MAX = 100_000;

    /**
     * 转录增量推送口（STOMP）· 由 {@link SubagentTranscriptStreamer} 在 Spring 启动时安装。
     *
     * <p>WHY 放在本类：所有子代理 transcript 的写入（{@code SubagentExecutor} 逐消息录制、
     * tool result 录制、压缩落盘）**都经过** {@link #recordSidechainTranscript} —— 这是唯一漏斗。
     * 在别处推送必然漏路径。
     *
     * <p>null = 未接线（单元测试直构 / 非 Spring 环境）→ 不推送，行为与改造前逐位一致。
     */
    @FunctionalInterface
    public interface IncrementSink {
        void publish(String sessionId, String agentId, List<Map<String, Object>> messages);
    }

    private static volatile IncrementSink incrementSink;

    /** 安装增量推送口（Spring 组件在 {@code @PostConstruct} 调用；测试可替换）。 */
    public static void setIncrementSink(IncrementSink sink) {
        incrementSink = sink;
    }

    /**
     * Agent 元数据 · 对齐 CC AgentMetadata（sessionStorage.ts:264-272）
     *
     * <p>[#25 删字段] 原 Java 扩展 model 字段已删除（open-decisions F2 #25 删字段改现算）——
     * CC AgentMetadata 无 model（sessionStorage.ts:264-272 仅 agentType/worktreePath?/description?），
     * CC resume 取 {@code toolUseContext.options.mainLoopModel} 当前主循环模型现算
     * （resumeAgent.ts:131/151）；Java 对应 = {@code AgentState.currentModel()}
     * （ResumeService.rebuildForkParentSystemPrompt），不再从 meta 持久化/读取。
     * 旧 meta 文件残留的 "model" JSON 字段由 readMetadata 忽略（无害，对齐 CC JSON.parse 容错）。
     *
     * @param agentType   agent 类型 · CC original: {@code agentType}（sessionStorage.ts:265）
     * @param worktreePath 隔离 worktree 路径 · CC original: {@code worktreePath?}（:267）
     * @param description 原始任务描述 · CC original: {@code description?}（:271）
     */
    public record AgentMetadata(
        String agentType,
        String worktreePath,
        String description,
        /**
         * 该 agent 启动时的有效工作目录 · 对齐 CC sidecar 的 {@code cwd}（resume 用它判定
         * {@code subagent_resume_cwd_refused}：记录里的目录已不可进入时拒绝恢复，而不是
         * 悄悄换到别的目录继续跑）。null = 旧 meta / 无法确定（resume 退回当前项目目录）。
         */
        String cwd
    ) {}

    /**
     * 获取 Agent transcript 文件路径 · 对齐 CC getAgentTranscriptPath
     */
    public static Path getTranscriptPath(Path sessionDir, String sessionId, String agentId) {
        return sessionDir.resolve(sessionId)
            .resolve("subagents")
            .resolve("agent-" + agentId + ".jsonl");
    }

    /**
     * 列出某会话**已落盘**的子代理 agentId（按 transcript 文件 mtime 倒序）。
     *
     * <p>为什么需要：右栏「子代理运行状况」原先只接 STOMP 实时事件 + `/tasks` REST 补录，
     * 而 `/tasks` 只含进程内的后台任务（重启即丢）⇒ 历史会话永远是空的。CC 的
     * `subagents/` 目录就是这些记录的唯一真源，必须能列出来。
     *
     * <p>只返回文件名里的 id 部分（去 `agent-` 前缀与 `.jsonl` 后缀）；不读文件内容
     * （历史会话可能有数千个，逐个解析会拖垮接口）。
     *
     * @param sessionDir 会话根目录
     * @param sessionId  会话 ID
     * @param limit      最多返回条数（<=0 → 全部；生产建议 50 以内）
     * @return agentId 列表（最新在前）
     */
    public static List<String> listAgentIds(Path sessionDir, String sessionId, int limit) {
        // 缓存键带**目录 mtime**：目录没变（没有新增/删除 transcript）就直接命中，
        // 不靠固定 TTL 反复重扫 5513 个文件（实测首次 2.4s，命中 5ms）。
        String dirStamp = "";
        try {
            Path subDir = sessionDir.resolve(sessionId).resolve("subagents");
            if (Files.isDirectory(subDir)) {
                dirStamp = String.valueOf(Files.getLastModifiedTime(subDir).toMillis());
            }
        } catch (Exception ignore) {
            // 取不到目录时间 → 退化为仅 TTL 判据
        }
        String cacheKey = sessionDir + "|" + sessionId + "|" + dirStamp;
        CachedIdList cached = LIST_CACHE.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < LIST_CACHE_TTL_MS) {
            List<String> ids = cached.ids();
            return limit > 0 && ids.size() > limit ? ids.subList(0, limit) : ids;
        }
        List<String> all = listAgentIdsUncached(sessionDir, sessionId);
        LIST_CACHE.put(cacheKey, new CachedIdList(all, now));
        return limit > 0 && all.size() > limit ? all.subList(0, limit) : all;
    }

    /** 缓存失效（测试/显式刷新用）。 */
    public static void clearListCache() {
        LIST_CACHE.clear();
    }

    /**
     * transcript 文件的**创建时间**（epoch millis）· 右栏列表按创建时间倒序用。
     *
     * <p>为什么不用 mtime：mtime = 最后一次追加时间（进行中的子代理会一直变），
     * 用户要的是「谁先派出去的」。Windows/NTFS 提供 creationTime；
     * 文件系统不支持时退化为 mtime，两者都取不到返回 {@code null}（前端保持原相对顺序）。
     *
     * @param sessionDir 会话根目录（{@link ResumeService#resolveSessionDir} 同源）
     * @param sessionId  会话 ID
     * @param agentId    transcript 文件名里的 id（a+16hex）
     */
    public static Long fileCreatedAt(Path sessionDir, String sessionId, String agentId) {
        Path f = sessionDir.resolve(sessionId).resolve("subagents").resolve("agent-" + agentId + ".jsonl");
        try {
            return Files.readAttributes(f, BasicFileAttributes.class).creationTime().toMillis();
        } catch (IOException e) {
            try {
                return Files.getLastModifiedTime(f).toMillis();
            } catch (IOException ignore) {
                return null;
            }
        }
    }

    private static List<String> listAgentIdsUncached(Path sessionDir, String sessionId) {
        Path dir = sessionDir.resolve(sessionId).resolve("subagents");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            var files = stream
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("agent-") && n.endsWith(".jsonl");
                })
                .sorted((a, b) -> {
                    try {
                        return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                    } catch (IOException e) {
                        return 0;
                    }
                });
            var ids = files.map(p -> {
                String n = p.getFileName().toString();
                return n.substring("agent-".length(), n.length() - ".jsonl".length());
            });
            return ids.toList();
        } catch (IOException e) {
            log.warn("列子代理 transcript 目录失败: {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 transcript **末行**推断子代理结局（meta.json 里没有状态字段，CC 也没有）。
     *
     * <p>口径（保守，拿不到就 unknown，不编造）：
     * <ul>
     *   <li>末条消息 role=assistant 且正文/思考非空 → {@code completed}</li>
     *   <li>末条带 {@code isError=true}（tool 结果失败 / API 错误）→ {@code failed}</li>
     *   <li>末条是 tool / user（没有最终答复）→ {@code incomplete}（未正常收尾）</li>
     *   <li>空文件 / 解析失败 → {@code unknown}</li>
     * </ul>
     *
     * <p>性能：只读文件末尾 8KB（单文件可达数 MB，不能整读）。
     *
     * @return {@code completed} / {@code failed} / {@code unknown}
     */
    public static String inferStatus(Path sessionDir, String sessionId, String agentId) {
        Path file = getTranscriptPath(sessionDir, sessionId, agentId);
        if (!Files.isRegularFile(file)) {
            return "unknown";
        }
        try {
            long size = Files.size(file);
            int readLen = (int) Math.min(size, 8192L);
            byte[] buf = new byte[readLen];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
                raf.seek(size - readLen);
                raf.readFully(buf);
            }
            String tail = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = tail.split("\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.isEmpty() || !line.startsWith("{")) {
                    continue;
                }
                try {
                    JsonNode node = JSON.readTree(line);
                    if (node.has("isError") && node.path("isError").asBoolean(false)) {
                        return "failed";
                    }
                    String role = node.path("role").asText("");
                    boolean hasBody = !node.path("content").asText("").isBlank()
                        || !node.path("reasoning").asText("").isBlank();
                    if ("assistant".equals(role) && hasBody) {
                        return "completed";
                    }
                    if ("tool".equals(role) || "user".equals(role)) {
                        // 末条是工具结果 / 用户输入 ⇒ 没有最终助手答复 = 未正常收尾
                        //（实测历史会话大量如此：跑完最后一批工具后进程结束，没有收尾文本）
                        return "incomplete";
                    }
                } catch (Exception ignore) {
                    // 末行可能被截断（不是完整 JSON）→ 继续往上找
                }
            }
            return "unknown";
        } catch (Exception e) {
            log.debug("推断子代理状态失败 agentId={}: {}", agentId, e.getMessage());
            return "unknown";
        }
    }

    /**
     * 获取 Agent metadata 文件路径 · 对齐 CC getAgentMetadataPath
     */
    public static Path getMetadataPath(Path sessionDir, String sessionId, String agentId) {
        Path transcriptPath = getTranscriptPath(sessionDir, sessionId, agentId);
        String fileName = transcriptPath.getFileName().toString();
        String metaFileName = fileName.replace(".jsonl", ".meta.json");
        return transcriptPath.resolveSibling(metaFileName);
    }

    /**
     * 写入 Agent metadata · 对齐 CC writeAgentMetadata
     *
     * @param sessionDir  会话目录
     * @param sessionId   会话 ID
     * @param agentId     Agent ID
     * @param metadata    Agent 元数据
     */
    public static void writeMetadata(Path sessionDir, String sessionId, String agentId,
                                     AgentMetadata metadata) throws IOException {
        Path path = getMetadataPath(sessionDir, sessionId, agentId);
        // [restart-rebuild] 保留既有扩展键（name/taskKind/teamName 由
        //   AgentNameRegistry / SpawnInProcess 先写；旧实现整份重建会把这些键冲掉，
        //   导致重启后无法按名重建退役表）。已知四键的 null 语义仍与旧实现一致（删键）。
        synchronized (AgentTranscript.class) {
            Files.createDirectories(path.getParent());
            ObjectNode node;
            try {
                node = Files.exists(path)
                    ? (ObjectNode) JSON.readTree(Files.readString(path))
                    : JSON.createObjectNode();
            } catch (Exception corrupt) {
                // 坏 JSON：按旧行为当作"无 meta"重建，而不是让 spawn 因 sidecar 坏了失败。
                node = JSON.createObjectNode();
            }
            node.put("agentType", metadata.agentType());
            putOrRemove(node, "worktreePath", metadata.worktreePath());
            putOrRemove(node, "description", metadata.description());
            putOrRemove(node, "cwd", metadata.cwd());
            // [#25] model 字段已删（对齐 CC sessionStorage.ts:264-272 AgentMetadata 无 model 字段）——不再写入。
            Files.writeString(path, JSON.writeValueAsString(node));
        }
        log.info("已写入 agent 元数据: agentId={}, type={}", agentId, metadata.agentType());
    }

    /**
     * 把 agent 名写进 meta sidecar（只写 {@code name} 键，保留其它字段）。
     *
     * <p>WHY：{@code name} 原先只对 teammate 落盘（{@code SpawnInProcess#writeTeammateMeta}），
     * 普通 async 子代理的名字只在内存注册表里 ⇒ 进程重启后无从知道「这个名字曾经指向哪个
     * agentId」。{@link RetiredAgentRebuilder} 以 meta 的 {@code name} 为重建数据源，故注册
     * 时必须把名字落到 sidecar。调用方须先确认会话目录存在（⛔ 不凭空建用户目录）。
     */
    public static void writeMetaName(Path sessionDir, String sessionId, String agentId, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        updateMetadataNode(sessionDir, sessionId, agentId, node -> node.put("name", name));
    }

    /**
     * 「被用户停止」伴生标记路径 · 对齐 CC {@code c4o.stoppedByUser}（{@code Qte()} 把
     * {@code agent-x.meta.json} 换成 {@code agent-x.stopped-by-user}）。
     */
    public static Path getStoppedByUserPath(Path sessionDir, String sessionId, String agentId) {
        Path meta = getMetadataPath(sessionDir, sessionId, agentId);
        return meta.resolveSibling(
            meta.getFileName().toString().replace(".meta.json", ".stopped-by-user"));
    }

    /**
     * 伴生标记写失败后的退避序列（毫秒）· 对齐 CC {@code pcr/Scr/aZt/lZt} 的
     * {@code [50,50,50,250,1000,4000]}（claude.exe 2.1.328 偏移 325012255）。
     */
    private static final long[] STOP_MARKER_BACKOFF_MS = {50L, 50L, 50L, 250L, 1000L, 4000L};

    /**
     * 写入「被用户停止」标记（幂等）· 对齐 CC agent-stop sidecar。
     *
     * <p><b>ifAbsent 幂等</b>（CC {@code {precondition:{type:"ifAbsent"}}}，{@code AlreadyExists}
     * 视为成功）：{@code Files.newOutputStream(..., CREATE_NEW)} = 原子「不存在才创建」；
     * 已存在 → 直接成功，<b>不重写</b>（重写会刷新 mtime，误导后续诊断）。
     *
     * <p><b>失败退避</b>：CC 主 sidecar 写失败时先建伴生文件并回填，退避
     * {@code [50,50,50,250,1000,4000]} ms。本仓伴生文件就是主标记，故对同一个「创建」动作
     * 退避重试；全部失败才回落到主 {@code .meta.json} 的 {@code stoppedByUser:true} 字段
     * （{@code subagent_stop_persist:"kept_beside_sidecar"} 的等价兜底），⛔ 不静默丢弃停止意图。
     *
     * <p>停止（TaskStop）时落盘；resume 时若标记存在且<b>不是用户显式发起</b>，拒绝恢复
     * （CC {@code AgentStoppedByUserError}）。
     */
    public static void markStoppedByUser(Path sessionDir, String sessionId, String agentId) {
        Path marker;
        try {
            marker = getStoppedByUserPath(sessionDir, sessionId, agentId);
            Files.createDirectories(marker.getParent());
        } catch (Exception e) {
            log.warn("写入 stopped-by-user 标记失败（路径不可用）agentId={}: {}", agentId, e.getMessage());
            return;
        }
        Exception last = null;
        for (int attempt = 0; attempt <= STOP_MARKER_BACKOFF_MS.length; attempt++) {
            try {
                // ifAbsent 语义：CREATE_NEW 在目标已存在时抛 FileAlreadyExistsException。
                Files.newOutputStream(marker, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE).close();
                log.info("已写入 stopped-by-user 标记: agentId={}", agentId);
                return;
            } catch (java.nio.file.FileAlreadyExistsException already) {
                // 幂等：伴生文件已存在 = 停止意图早已记录（CC AlreadyExists 视为成功）。
                return;
            } catch (Exception e) {
                last = e;
                if (attempt < STOP_MARKER_BACKOFF_MS.length) {
                    try {
                        Thread.sleep(STOP_MARKER_BACKOFF_MS[attempt]);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.warn("写入 stopped-by-user 伴生文件失败 agentId={}（改回填 meta.json）: {}",
            agentId, last == null ? "unknown" : last.toString());
        markStoppedByUserInMetadata(sessionDir, sessionId, agentId);
    }

    /**
     * 主 sidecar 兜底：把 {@code stoppedByUser:true} 写进 {@code <agentId>.meta.json}
     * （保留其它字段）· 对齐 CC {@code Z3t(meta,"stoppedByUser", ...)} 的主记录回填。
     *
     * @return true = 已写入
     */
    public static boolean markStoppedByUserInMetadata(Path sessionDir, String sessionId, String agentId) {
        return updateMetadataNode(sessionDir, sessionId, agentId, node -> node.put("stoppedByUser", true));
    }

    /** 清除 meta.json 的 {@code stoppedByUser} 标记（用户显式 resume 成功后）。 */
    public static void clearStoppedByUserInMetadata(Path sessionDir, String sessionId, String agentId) {
        updateMetadataNode(sessionDir, sessionId, agentId, node -> node.remove("stoppedByUser"));
    }

    /** meta.json 的「本次运行已结束」标记键 · 对齐 CC agent sidecar 的 {@code finished}。 */
    public static final String FINISHED_KEY = "finished";
    /** meta.json 的「复活时刻」键 · 对齐 CC agent sidecar 的 {@code revivedAt}。 */
    public static final String REVIVED_AT_KEY = "revivedAt";

    /**
     * 终态落盘：写 {@code finished:true}（合并保留其它键）。
     *
     * <p>WHY：{@code 重建退役表}若只靠 transcript 末行推断结局，末条是 tool/user 行时会被判成
     * {@code incomplete}（实测历史会话大量如此）；子代理正常结束时显式落盘 {@code finished}
     * 后，重建优先读它，不再误判。
     *
     * @return true = 已写入
     */
    public static boolean markFinished(Path sessionDir, String sessionId, String agentId) {
        return updateMetadataNode(sessionDir, sessionId, agentId,
            node -> node.put(FINISHED_KEY, true));
    }

    /**
     * 读 {@code finished} 三态：文件/键缺失或读失败 → empty（按「旧数据」处理），
     * 有值 → {@code Optional.of(value)}。
     */
    public static Optional<Boolean> readFinishedFlag(Path sessionDir, String sessionId,
                                                     String agentId) {
        if (sessionDir == null || sessionId == null || agentId == null) {
            return Optional.empty();
        }
        try {
            Path path = getMetadataPath(sessionDir, sessionId, agentId);
            if (!Files.exists(path)) {
                return Optional.empty();
            }
            JsonNode node = JSON.readTree(Files.readString(path));
            return node.has(FINISHED_KEY)
                ? Optional.of(node.path(FINISHED_KEY).asBoolean(false))
                : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 复活（resume 起跑前）：写 {@code revivedAt} 并清 {@code finished}（合并保留其它键）。
     *
     * <p>WHY 清 finished：复活后的这一轮在跑，若仍留 {@code finished:true}，重建会把「正在跑」
     * 说成「已完成」。新的一轮结束时由 {@link #markFinished} 重新落盘。
     *
     * @return true = 已写入
     */
    public static boolean markRevived(Path sessionDir, String sessionId, String agentId) {
        return updateMetadataNode(sessionDir, sessionId, agentId, node -> {
            node.put(REVIVED_AT_KEY, Instant.now().toString());
            node.remove(FINISHED_KEY);
        });
    }

    /**
     * 读 meta.json 的 {@code stoppedByUser} 字段 · 三态：文件不存在/无该键 → empty，
     * 读失败 → empty（CC 读 sidecar 失败时按 absent 处理，见 356069-356070 区）；
     * 有值 → Optional.of(value)。
     */
    public static Optional<Boolean> readStoppedByUserFlag(Path sessionDir, String sessionId,
                                                          String agentId) {
        if (sessionDir == null || sessionId == null || agentId == null) {
            return Optional.empty();
        }
        try {
            Path path = getMetadataPath(sessionDir, sessionId, agentId);
            if (!Files.exists(path)) {
                return Optional.empty();
            }
            JsonNode node = JSON.readTree(Files.readString(path));
            return node.has("stoppedByUser") ? Optional.of(node.path("stoppedByUser").asBoolean(false))
                : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** meta.json 可读性三态（evicted 分支区分「不存在」与「读失败」两档，CC dSa / cSa）。 */
    public enum MetadataState { OK, MISSING, UNREADABLE }

    /** 探测 meta.json 状态。 */
    public static MetadataState metadataState(Path sessionDir, String sessionId, String agentId) {
        if (sessionDir == null || sessionId == null || agentId == null) {
            return MetadataState.MISSING;
        }
        Path path = getMetadataPath(sessionDir, sessionId, agentId);
        if (!Files.exists(path)) {
            return MetadataState.MISSING;
        }
        try {
            JSON.readTree(Files.readString(path));
            return MetadataState.OK;
        } catch (Exception e) {
            return MetadataState.UNREADABLE;
        }
    }

    /**
     * 逐出分流用的 sidecar 扩展字段 · 对齐 CC {@code Qt()}（偏移 356127769）：
     * {@code taskKind===undefined → "subagent"}，有 taskKind → {@code "teammate"}。
     *
     * @param agentType agent 类型（meta.agentType，可空）
     * @param taskKind  CC {@code taskKind}（本仓 meta 目前不写 ⇒ null ⇒ subagent）
     * @param name      teammate 名（CC team 文件里有，本仓可空）
     * @param teamName  team 名（可空）
     */
    public record MetaExtras(String agentType, String taskKind, String name, String teamName) {
        public boolean teammate() {
            return taskKind != null && !taskKind.isBlank();
        }
    }

    /** 读逐出分流扩展字段（meta 不可读 → 全 null）。 */
    public static MetaExtras readMetaExtras(Path sessionDir, String sessionId, String agentId) {
        if (sessionDir == null || sessionId == null || agentId == null) {
            return new MetaExtras(null, null, null, null);
        }
        try {
            Path path = getMetadataPath(sessionDir, sessionId, agentId);
            if (!Files.exists(path)) {
                return new MetaExtras(null, null, null, null);
            }
            JsonNode node = JSON.readTree(Files.readString(path));
            return new MetaExtras(
                textOrNull(node, "agentType"),
                textOrNull(node, "taskKind"),
                textOrNull(node, "name"),
                textOrNull(node, "teamName"));
        } catch (Exception e) {
            return new MetaExtras(null, null, null, null);
        }
    }

    /** 写逐出分流扩展字段（保留其它字段；值 null → 删除该键）。 */
    public static void writeMetaExtras(Path sessionDir, String sessionId, String agentId,
                                       MetaExtras extras) {
        if (extras == null) {
            return;
        }
        updateMetadataNode(sessionDir, sessionId, agentId, node -> {
            // agentType 与 taskKind 同属 CC sidecar；读侧 readMetaExtras 四键齐读、
            // 重拉侧（TeammateRelauncher）用 agentType 还原原 agent 定义 ⇒ 写侧必须一并落盘，
            // 否则「写了 MetaExtras 却读不到 agentType」= 半截接线。
            putOrRemove(node, "agentType", extras.agentType());
            putOrRemove(node, "taskKind", extras.taskKind());
            putOrRemove(node, "name", extras.name());
            putOrRemove(node, "teamName", extras.teamName());
        });
    }

    // ────────────────────────────────────────────────────────────────────
    // [resume-quota] restartResumes 台账 · 对齐 CC yYa=5（偏移 315610267）+ 字段
    //   restartResumes / restartResumesSetBackBy（偏移 353422651 countAtCard/spendResume）
    // ────────────────────────────────────────────────────────────────────

    /** 读 {@code restartResumes}（不存在 → 0）。 */
    public static int readRestartResumes(Path sessionDir, String sessionId, String agentId) {
        if (sessionDir == null || sessionId == null || agentId == null) {
            return 0;
        }
        try {
            Path path = getMetadataPath(sessionDir, sessionId, agentId);
            if (!Files.exists(path)) {
                return 0;
            }
            JsonNode node = JSON.readTree(Files.readString(path));
            return node.path("restartResumes").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 是否已达自动续跑上限（CC {@code Pt>=yYa → "resumes_spent"}）。 */
    public static boolean restartResumesExhausted(Path sessionDir, String sessionId, String agentId,
                                                  int limit) {
        return readRestartResumes(sessionDir, sessionId, agentId) >= limit;
    }

    /**
     * {@code spendResume}：先 +1 再返回新值 · 对齐 CC「先写 +1」。
     *
     * @return 自增后的次数
     */
    public static int spendRestartResume(Path sessionDir, String sessionId, String agentId) {
        int[] out = {0};
        updateMetadataNode(sessionDir, sessionId, agentId, node -> {
            int next = node.path("restartResumes").asInt(0) + 1;
            node.put("restartResumes", next);
            out[0] = next;
        });
        return out[0] == 0 ? readRestartResumes(sessionDir, sessionId, agentId) : out[0];
    }

    /**
     * 卡片答「wait」→ 退回一次 · 对齐 CC {@code r-1}。
     *
     * @return 退回后的次数（下限 0）
     */
    public static int refundRestartResume(Path sessionDir, String sessionId, String agentId) {
        int[] out = {0};
        updateMetadataNode(sessionDir, sessionId, agentId, node -> {
            int next = Math.max(0, node.path("restartResumes").asInt(0) - 1);
            node.put("restartResumes", next);
            out[0] = next;
        });
        return out[0];
    }

    /**
     * 卡片批准 → 清零并记 {@code restartResumesSetBackBy=<card tool_use_id>} · 对齐 CC
     * 「写 0 并记 restartResumesSetBackBy（同一张卡不重复清零）」。
     *
     * @param setBackBy 卡片 tool_use_id（null → 只清零）
     * @return true = 本次实际清零；false = 同一张卡已清过（幂等）
     */
    public static boolean clearRestartResumes(Path sessionDir, String sessionId, String agentId,
                                              String setBackBy) {
        boolean[] changed = {false};
        updateMetadataNode(sessionDir, sessionId, agentId, node -> {
            String existing = textOrNull(node, "restartResumesSetBackBy");
            if (setBackBy != null && setBackBy.equals(existing)) {
                return; // 同一张卡不重复清零
            }
            node.put("restartResumes", 0);
            if (setBackBy != null) {
                node.put("restartResumesSetBackBy", setBackBy);
            }
            changed[0] = true;
        });
        return changed[0];
    }

    // ────────────────────────────────────────────────────────────────────
    // meta.json 读改写基础设施（保留其它字段；文件不存在则新建最小对象）
    // ────────────────────────────────────────────────────────────────────

    private static boolean updateMetadataNode(Path sessionDir, String sessionId, String agentId,
                                              java.util.function.Consumer<ObjectNode> mutator) {
        if (sessionDir == null || sessionId == null || agentId == null) {
            return false;
        }
        Path path = getMetadataPath(sessionDir, sessionId, agentId);
        // 进程内串行化同一类的 meta 读改写（不同 agent 文件互不阻塞，元数据量极小）。
        synchronized (AgentTranscript.class) {
            try {
                Files.createDirectories(path.getParent());
                ObjectNode node = Files.exists(path)
                    ? (ObjectNode) JSON.readTree(Files.readString(path))
                    : JSON.createObjectNode();
                mutator.accept(node);
                Files.writeString(path, JSON.writeValueAsString(node));
                return true;
            } catch (Exception e) {
                log.warn("更新 agent 元数据失败 {}: {}", path, e.getMessage());
                return false;
            }
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.path(field).asText(null) : null;
    }

    private static void putOrRemove(ObjectNode node, String field, String value) {
        if (value == null) {
            node.remove(field);
        } else {
            node.put(field, value);
        }
    }

    /** 是否存在「被用户停止」标记。 */
    public static boolean isStoppedByUser(Path sessionDir, String sessionId, String agentId) {
        try {
            return Files.exists(getStoppedByUserPath(sessionDir, sessionId, agentId));
        } catch (Exception e) {
            // 路径非法等 → 视为无标记（不阻断 resume；对齐 CC 读 sidecar 失败 treating as absent）
            return false;
        }
    }

    /** 清除「被用户停止」标记 · 对齐 CC {@code eKt()}（resume 启动成功后删伴生文件）。 */
    public static void clearStoppedByUser(Path sessionDir, String sessionId, String agentId) {
        try {
            Files.deleteIfExists(getStoppedByUserPath(sessionDir, sessionId, agentId));
        } catch (Exception e) {
            log.warn("清除 stopped-by-user 标记失败 agentId={}: {}", agentId, e.getMessage());
        }
        // 双写的另一半：meta.json 的 stoppedByUser 字段也一并清（否则下次 resume 又被它拦）。
        clearStoppedByUserInMetadata(sessionDir, sessionId, agentId);
    }

    /**
     * 「被用户停止」的综合判据 · 对齐 CC 恢复门 {@code (meta.stoppedByUser || 伴生文件存在)}
     * （claude.exe 2.1.328 偏移 333967189：{@code (A||he.found) && !userInitiated && !lineage}）。
     */
    public static boolean isStoppedByUserOrMeta(Path sessionDir, String sessionId, String agentId) {
        return isStoppedByUser(sessionDir, sessionId, agentId)
            || readStoppedByUserFlag(sessionDir, sessionId, agentId).orElse(false);
    }

    /**
     * 读取 Agent metadata · 对齐 CC readAgentMetadata
     */
    public static Optional<AgentMetadata> readMetadata(Path sessionDir, String sessionId, String agentId) {
        Path path = getMetadataPath(sessionDir, sessionId, agentId);
        if (!Files.exists(path)) return Optional.empty();
        try {
            String content = Files.readString(path);
            var node = JSON.readTree(content);
            // [#25] model 字段已删：旧 meta 残留 "model" JSON 字段忽略（对齐 CC JSON.parse 天然容错）
            return Optional.of(new AgentMetadata(
                node.path("agentType").asText(),
                node.path("worktreePath").isMissingNode() ? null : node.path("worktreePath").asText(),
                node.path("description").isMissingNode() ? null : node.path("description").asText(),
                node.path("cwd").isMissingNode() ? null : node.path("cwd").asText()
            ));
        } catch (Exception e) {
            log.warn("读取 agent 元数据失败 {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 记录 sidechain transcript · 对齐 CC recordSidechainTranscript
     *
     * <p>[S5 P0] 对齐 CC sessionStorage.ts:995/1042 sidechain 消息结构: 写入时给每条消息补
     * {@code agentId} + {@code isSidechain=true} + {@code uuid} + {@code parentUuid} 链,
     * 否则 {@link #getAgentTranscript} 的 filter (agentId + isSidechain) 与 leaf 查找
     * (parentUuid) 无法工作.
     *
     * @param messages 消息 Map 列表 (就地补充字段)
     */
    public static void recordSidechainTranscript(Path sessionDir, String sessionId,
                                                 String agentId, List<Map<String, Object>> messages) {
        recordSidechainTranscript(sessionDir, sessionId, agentId, messages, null);
    }

    /**
     * 记录 sidechain transcript（跨调用链版本）· 对齐 CC recordSidechainTranscript
     * (sessionStorage.ts:1451-1462) + insertMessageChain (sessionStorage.ts:993-1080).
     *
     * <p>[W5-03b] 主会话后台化逐消息写 (LocalMainSessionTask.ts:411-419):
     * {@code recordSidechainTranscript([event], taskId, lastRecordedUuid)} — 每次增量写都以
     * 上一已写消息的 uuid 作为 {@code startingParentUuid}, 让新消息链到上一条, 跨独立 write()
     * 调用保持 parent 链完整. 无此参数时跨调用消息各自 parentUuid=null → getAgentTranscript
     * leaf 查找 (uuid 非任何 parentUuid, CC :4210-4214) 命中首条而非末条 → 链重建丢失后半段.
     *
     * @param sessionDir        会话目录
     * @param sessionId         会话 ID
     * @param agentId           Agent ID
     * @param messages          消息 Map 列表 (就地补充字段)
     * @param startingParentUuid 链首消息的 parentUuid (CC original: {@code startingParentUuid?}).
     *                           上一条已写消息的 uuid; null = 链首
     */
    public static void recordSidechainTranscript(Path sessionDir, String sessionId,
                                                 String agentId, List<Map<String, Object>> messages,
                                                 String startingParentUuid) {
        Path path = getTranscriptPath(sessionDir, sessionId, agentId);
        try {
            Files.createDirectories(path.getParent());
            // 追加模式写入
            StringBuilder sb = new StringBuilder();
            String prevUuid = startingParentUuid;
            String pathKey = path.toAbsolutePath().toString();
            List<Map<String, Object>> written = new ArrayList<>(messages.size());
            List<String> newKeys = new ArrayList<>(messages.size());
            List<Map<String, Object>> published = new ArrayList<>(messages.size());
            /** [transcript-timestamp] 本批次上一条已写行的时间戳（同批单调用）。 */
            String prevTimestamp = null;
            int skipped = 0;
            for (Map<String, Object> msg : messages) {
                enrichTranscriptMessage(msg, agentId, prevUuid);
                // [transcript-timestamp] 缺 timestamp 的行在此补齐（唯一写入漏斗 ⇒ 所有写入点覆盖）。
                prevTimestamp = stampWriteTimestamp(msg, prevTimestamp);
                java.util.List<Map<String, Object>> lines = splitBlockLines(msg);
                String msgUuid = String.valueOf(msg.get("uuid"));
                // [agent-notice-persist] 同 uuid 已在本进程落过（入队即落库 + 消费侧 append）
                //   → 跳过重复行；prevUuid 仍推进，保证后续消息的 parent 链走到这条通知上。
                if (RECORDED_KEYS.contains(pathKey + "|" + msgUuid)) {
                    prevUuid = msgUuid;
                    skipped++;
                    continue;
                }
                for (Map<String, Object> blockLine : lines) {
                    String uuid = String.valueOf(blockLine.get("uuid"));
                    sb.append(JSON.writeValueAsString(blockLine)).append("\n");
                    prevUuid = uuid;
                    written.add(blockLine);
                    newKeys.add(pathKey + "|" + uuid);
                    if (!Boolean.TRUE.equals(blockLine.get("blockContinuation"))) {
                        published.add(blockLine);
                    }
                }
            }
            if (sb.length() > 0) {
                Files.writeString(path, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                // 只有落盘成功才登记（写失败时后续重试仍会落盘，不会被误判为已写）
                RECORDED_KEYS.addAll(newKeys);
                if (RECORDED_KEYS.size() > RECORDED_KEYS_MAX) {
                    RECORDED_KEYS.clear();
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("已记录 {} 条消息 agent={} (startingParentUuid={}, 去重跳过={})",
                    written.size(), agentId, startingParentUuid, skipped);
            }
            // 落盘成功后才推实时增量（前端按 uuid 幂等去重；先推后写会在写失败时留下幽灵消息）
            if (!written.isEmpty()) {
                publishIncrement(sessionId, agentId, published);
            }
        } catch (IOException e) {
            log.warn("记录 sidechain transcript 失败: {}", e.getMessage());
        }
    }

    /**
     * 推送本次写入的消息（已 enrich 的同一批 map，与落盘内容逐位一致）。
     *
     * <p>推送失败绝不影响落盘：transcript 是权威源，STOMP 只是实时性优化。
     */
    private static void publishIncrement(String sessionId, String agentId, List<Map<String, Object>> messages) {
        IncrementSink sink = incrementSink;
        if (sink == null || sessionId == null || agentId == null || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            sink.publish(sessionId, agentId, messages);
        } catch (Exception e) {
            log.warn("推送 transcript 增量失败 session={} agent={}: {}", sessionId, agentId, e.getMessage());
        }
    }

    /**
     * [content-blocks] 把一条 Map 按内容块拆成「一块一行」（对齐 CC transcript）。
     *
     * <p>assistant 消息带 blocks 数组（元素含 apiBlockIndex）且长度 > 1 时：
     * 首行保留完整消息包体（含 content/reasoning/toolCalls/blocks）；其余块各写一行，
     * 标 {@code blockContinuation=true} + 该块字段与 apiBlockIndex（uuid = 首行 uuid + "#b{i}"，
     * parentUuid 指向首行）。读侧与前端实时增量均跳过续行 ⇒ 链/模型面零影响。
     *
     * <p>无 blocks / 只有 1 个块 → 原样返回单元素列表（旧格式不变）。
     */
    private static List<Map<String, Object>> splitBlockLines(Map<String, Object> msg) {
        List<Map<String, Object>> single = new ArrayList<>(1);
        single.add(msg);
        Object raw = msg == null ? null : msg.get("blocks");
        if (!(raw instanceof List<?> blocks) || blocks.size() <= 1) {
            return single;
        }
        Object uuidObj = msg.get("uuid");
        String baseUuid = uuidObj == null ? null : String.valueOf(uuidObj);
        if (baseUuid == null || "null".equals(baseUuid)) {
            return single;
        }
        List<Map<String, Object>> out = new ArrayList<>(blocks.size());
        out.add(msg);
        for (int i = 1; i < blocks.size(); i++) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("blockContinuation", true);
            line.put("uuid", baseUuid + "#b" + i);
            line.put("parentUuid", baseUuid);
            line.put("role", msg.get("role"));
            line.put("agentId", msg.get("agentId"));
            line.put("isSidechain", true);
            Object ts = msg.get("timestamp");
            if (ts != null) {
                line.put("timestamp", ts);
            }
            Object block = blocks.get(i);
            if (block instanceof Map<?, ?> bm) {
                for (Map.Entry<?, ?> e : bm.entrySet()) {
                    line.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            out.add(line);
        }
        return out;
    }
    /**
     * 给一条 transcript 消息补 agentId/isSidechain/uuid/parentUuid.
     * 对齐 CC sessionStorage.ts:995 ({@code isSidechain: boolean = false}) + :1042
     * ({@code isSidechain, parentUuid} 序列化) — Java 端 sidechain 恒 true.
     */
    private static void enrichTranscriptMessage(Map<String, Object> msg, String agentId, String prevUuid) {
        if (msg == null) return;
        if (msg.get("agentId") == null) {
            msg.put("agentId", agentId);
        }
        msg.put("isSidechain", true);
        Object existingUuid = msg.get("uuid");
        String uuid = existingUuid == null || "null".equals(String.valueOf(existingUuid))
            ? UUID.randomUUID().toString()
            : String.valueOf(existingUuid);
        msg.put("uuid", uuid);
        if (!msg.containsKey("parentUuid") && prevUuid != null) {
            msg.put("parentUuid", prevUuid);
        }
    }

    /**
     * 给一条待落盘消息判定写入时间戳（**唯一写入漏斗**的补齐点）。
     *
     * <p><b>WHY（2026-11-20 实测定性）</b>：CC 每条 JSONL 行都写 {@code timestamp}（读侧
     * {@code findLatestMessage} 以 {@code Date.parse(msg.timestamp)} 判「最新 leaf」）。本仓此前
     * 工具结果行不写时间戳 —— 根因是 {@code LlmAgentLoop.toolResultMessage} 构造的
     * {@code ChatMessageDto} {@code createdAt=null} ⇒ {@code SubagentExecutor.chatMessageToMap}
     * 整键不落；若干 raw map 写入点（initialMessages 的 user/system 行、外部 agent）同样不写。
     * 结果是真实会话里出现多个 timestamp=null 的叶子，只能靠「文件序最后一条」兜底
     * （{@link #findLatestLeaf}）。兜底只应服务**历史老文件**，不该承担新写入的数据。
     *
     * <p><b>取值</b>：缺时间戳 ⇒ 取**本次写入发生的时刻**（{@code OffsetDateTime.now()}），
     * 与既有行同格式（{@code OffsetDateTime.toString()}，读侧 OffsetDateTime/Instant 均可解析）。
     * 调用方自带的时间戳原样保留（那是该行事件自身的发生时刻，如通知行的入队时刻 {@code queuedAt}），
     * ⛔ 不统一成同一时间。
     *
     * <p><b>同批单调</b>：一次 {@link #recordSidechainTranscript} 调用内，写入顺序必须与时间顺序
     * 一致（允许相等，禁止倒序）。仅当本条比上一条更早（时钟回拨 / 混入更早时刻的旧消息）时把本条
     * 抬到上一条的值；正常单调数据零改动。
     *
     * <p><b>老文件兼容</b>：只影响**新写入**的行，已落盘的历史行不读不改；读侧
     * {@link #findLatestLeaf} 的「全部候选无 timestamp → 取文件序最后一条」兜底保持不变。
     *
     * @param msg           待落盘消息（就地写入 {@code timestamp} 键）
     * @param prevTimestamp 本批次上一条已写行的时间戳（null = 批次首条）
     * @return 本条最终时间戳字符串（供同批次下一条比对）
     */
    private static String stampWriteTimestamp(Map<String, Object> msg, String prevTimestamp) {
        Object raw = msg.get("timestamp");
        String text = raw == null || "null".equals(String.valueOf(raw)) || String.valueOf(raw).isBlank()
            ? null : String.valueOf(raw);
        if (text == null) {
            text = OffsetDateTime.now().toString();
        }
        // 同批单调：只在「本条更早」时抬到上一条（允许相等，禁止倒序）；不可解析则原样保留。
        Long current = parseTimestampMillis(text);
        if (prevTimestamp != null && current != null) {
            Long prev = parseTimestampMillis(prevTimestamp);
            if (prev != null && current < prev) {
                text = prevTimestamp;
            }
        }
        msg.put("timestamp", text);
        return text;
    }

    /**
     * 清理 Agent transcript 子目录 · 对齐 CC clearAgentTranscriptSubdir
     * (sessionStorage.ts:243-245)
     *
     * <p><b>内存语义，不删磁盘文件</b>：CC clearAgentTranscriptSubdir 只做
     * {@code agentTranscriptSubdirs.delete(agentId)}（内存 Map 清理，
     * sessionStorage.ts:244），<b>绝不触碰磁盘</b> transcript/meta 文件
     * （sessionStorage.ts:245 无任何 fs 删除）。Java 无 agentTranscriptSubdirs
     * Map（getAgentTranscriptPath 直接按路径解析，不查 Map），故本方法对齐后为
     * no-op——磁盘 transcript/meta <b>保留</b>，供执行记录查看回读
     * （SubagentController.getAgentTranscript / ResumeService /
     * SummarySummarizerImpl 均读盘）。
     *
     * <p>[Bug A 修复] 旧实现用 Files.deleteIfExists 删除 .jsonl + .meta.json，
     * 导致 SubagentExecutor finally 清理后子代理执行记录查看详情 "No transcript found"。
     *
     * @param sessionDir 会话目录（保留，签名与 SubagentExecutor:1980 调用点对齐）
     * @param sessionId  会话 ID
     * @param agentId    Agent ID
     */
    public static void clearTranscript(Path sessionDir, String sessionId, String agentId) {
        if (log.isDebugEnabled()) {
            log.debug("clearTranscript 对齐 CC clearAgentTranscriptSubdir 内存语义，"
                + "不删除磁盘 transcript/meta（sessionDir={}, sessionId={}, agentId={}）",
                sessionDir, sessionId, agentId);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // [S5 P0 差异 4] getAgentTranscript 读取 · 对齐 CC sessionStorage.ts:4190-4236
    // ────────────────────────────────────────────────────────────────────────

    /**
     * getAgentTranscript 返回值 · 对齐 CC sessionStorage.ts:4190-4192
     * ({@code { messages: Message[]; contentReplacements: ContentReplacementRecord[] }}).
     */
    public record AgentTranscriptResult(
        List<AgentMessage> messages,
        List<ContentReplacementRecord> contentReplacements
    ) {}

    /**
     * 读取 agent transcript 并重建对话链 · 对齐 CC getAgentTranscript
     * (Open-ClaudeCode/src/utils/sessionStorage.ts:4190-4236).
     *
     * <p>流程:
     * <ol>
     *   <li>读 {@code agent-{agentId}.jsonl} 逐行解析 (消息 + contentReplacement 记录)</li>
     *   <li>filter {@code msg.agentId === agentId && msg.isSidechain} (:4201-4203)</li>
     *   <li>找 leaf: uuid 非任何 parentUuid (:4210-4214)</li>
     *   <li>{@link #buildConversationChain} 按 parentUuid 链重建 (:4221)</li>
     *   <li>再 filter agentId (:4223) + 剥离 isSidechain/parentUuid (:4229)</li>
     *   <li>返 {messages, contentReplacements} (:4230-4232); 异常 → Optional.empty (:4233)</li>
     * </ol>
     *
     * @param sessionDir 会话目录 (getTranscriptPath 的根)
     * @param sessionId  会话 ID
     * @param agentId    目标 agent id
     * @return 非空含重建后的消息链 + contentReplacements; 文件不存在/无匹配 → Optional.empty
     */
    public static Optional<AgentTranscriptResult> getAgentTranscript(
            Path sessionDir, String sessionId, String agentId) {
        Path path = getTranscriptPath(sessionDir, sessionId, agentId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            List<AgentMessage> all = new ArrayList<>();
            List<ContentReplacementRecord> contentReplacements = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                if (line == null || line.isBlank()) continue;
                JsonNode node = JSON.readTree(line);
                if (node == null) continue;
                if (isContentReplacementNode(node)) {
                    contentReplacements.addAll(parseContentReplacement(node, agentId));
                } else {
                    AgentMessage msg = parseMessageNode(node);
                    if (msg != null) all.add(msg);
                }
            }

            // filter agentId + isSidechain (CC :4201-4203)
            List<AgentMessage> agentMessages = all.stream()
                .filter(m -> agentId.equals(m.agentId()) && m.isSidechain())
                .toList();
            if (agentMessages.isEmpty()) {
                return Optional.empty();
            }

            // find leaf: uuid 非任何 parentUuid (CC :4210-4214) → 取**时间最新**者
            Set<String> parentUuids = agentMessages.stream()
                .map(AgentMessage::parentUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            AgentMessage leaf = findLatestLeaf(agentMessages, parentUuids);
            if (leaf == null) {
                return Optional.empty();
            }

            // buildConversationChain (CC :4221)
            List<AgentMessage> transcript = buildConversationChain(all, leaf);
            // 再 filter agentId (CC :4223)
            List<AgentMessage> agentTranscript = transcript.stream()
                .filter(m -> agentId.equals(m.agentId()))
                .toList();
            return Optional.of(new AgentTranscriptResult(agentTranscript, contentReplacements));
        } catch (Exception e) {
            log.warn("读取 agent transcript 失败 {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 取「时间最新」的 leaf 候选 · 对齐 CC {@code findLatestMessage}
     * (sessionStorage.ts:4211 调用 / :2046-2059 定义).
     *
     * <p><b>CC original</b>: {@code findLatestMessage(agentMessages, msg => !parentUuids.has(msg.uuid))}
     * —— O(n) 单趟取 {@code Date.parse(msg.timestamp)} 最大者（注释逐字 "Find the most recent leaf
     * message with this agentId"）。
     *
     * <p><b>⛔ 为什么不能沿用「文件序首条」（本批最关键的一处）</b>：转录文件原本只有**一条链**
     * （每条消息的 parentUuid 指向文件里的上一条），此时「文件序首条 leaf」恰好等于「链尾」。
     * 但 compaction 落盘的 compact_boundary 的 {@code parentUuid = null}（CC 语义，
     * sessionStorage.ts:1391-1407 注释逐字 "correct: truncates --continue chain at compact
     * boundary"）⇒ 写入后文件里**有两条链头** ⇒ 「文件序首条 leaf」会挑中**压缩前**那条旧链尾
     * ⇒ {@link #buildConversationChain} 从旧链尾反向走 ⇒ resume 重建出**压缩前全量** ⇒ 压缩白做。
     *
     * <p><b>无 timestamp 候选的处置（对齐边界，非双轨）</b>：{@link AgentMessage#timestamp()} 为
     * null/不可解析 = 该条**无时序信息**，一律让位给任何带 timestamp 的候选；仅当**全部**候选都无
     * timestamp 时，取**文件序最后一条** leaf。并列时间戳取**先出现者**（CC {@code t > maxTime}
     * 严格大于语义，逐位对齐）。
     *
     * <p><b>⚠️ 这条兜底现在只服务「历史老文件」</b>：2026-11-20 起写入侧已在唯一漏斗
     * （{@link #stampWriteTimestamp}）给每一行补 {@code timestamp}（此前工具结果行因
     * {@code ChatMessageDto.createdAt=null} 不写），新数据不会再出现无时序候选。
     * ⛔ 不要把兜底删掉/改成「无 timestamp 就报错」：老 transcript 已落盘且不可回填，
     * 仍必须能被读回并选中正确的叶子。
     *
     * <p><b>⛔ 为什么「全部无 timestamp」时必须取最后一条、不能取第一条（2026-11-20 实测）</b>：
     * 转录是**追加**写入 ⇒ 文件位置越靠后 = 写得越晚。真实会话
     * {@code sess-01c0afd5/agent-ab1bb3af453e9f75f.jsonl}（374 行）有两个 leaf 且 timestamp 均为
     * null：第 72 行 {@code role=tool}（{@code recordToolResultTranscript} 写 tool 行时**不写
     * timestamp**）与第 374 行 {@code role=assistant}。取「首条」⇒ 从第 72 行回溯只有 **71** 条，
     * 而 SendMessage 通知行（第 69 行）挂在同一条旧分支上 ⇒ **通知行被整段截掉**（REST 返回
     * agent_message=0，用户看不到「收到主代理通知」）。取「末条」⇒ 350 条，含通知行。
     *
     * <p>旧注释说「首条 = 旧 findFirst 语义、legacy 零回归」，但那恰恰是本缺陷的来源：首条是
     * **更早写入的旧分支**，不是更新的链。压缩场景（双链头）仍由 timestamp 优先规则覆盖，
     * 本兜底只在**完全无时序信息**时生效。
     *
     * @param agentMessages 该 agent 的 sidechain 消息（文件序）
     * @param parentUuids   所有消息的 parentUuid 集合（leaf 判据：uuid 不在其中）
     * @return 最新 leaf；无候选 → null
     */
    private static AgentMessage findLatestLeaf(List<AgentMessage> agentMessages, Set<String> parentUuids) {
        AgentMessage latestTimed = null;
        long maxTime = Long.MIN_VALUE;
        /** 无 timestamp 的候选：文件序最后一条（= 最晚写入的那条链尾）。 */
        AgentMessage lastUntimed = null;
        for (AgentMessage m : agentMessages) {
            if (m.uuid() == null || parentUuids.contains(m.uuid())) {
                continue;   // 非 leaf 候选（CC predicate: !parentUuids.has(msg.uuid)）
            }
            Long t = parseTimestampMillis(m.timestamp());
            if (t == null) {
                // 无时序信息：先占位，后续候选覆盖 ⇒ 最终留下文件序最后一条
                lastUntimed = m;
                continue;
            }
            if (latestTimed == null || t > maxTime) {
                latestTimed = m;
                maxTime = t;
            }
        }
        return latestTimed != null ? latestTimed : lastUntimed;
    }

    /**
     * ISO-8601 时间戳 → epoch millis · 对齐 CC {@code Date.parse(msg.timestamp)}
     * (sessionStorage.ts:2054).
     *
     * <p>写入侧形态 = {@code OffsetDateTime.toString()}（{@code SubagentExecutor.chatMessageToMap}），
     * 解析尝试顺序：带偏移的 ISO（{@code OffsetDateTime}）→ 瞬时形态（{@code Instant}）。
     * 二者皆失败 → null = 「无时序信息」（调用方按无排序信息处置，⛔ 不抛、不中断读取）。
     *
     * @param timestamp 转录节点的 timestamp 字段值（可 null）
     * @return epoch millis；无/不可解析 → null
     */
    private static Long parseTimestampMillis(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(timestamp).toInstant().toEpochMilli();
        } catch (Exception ignore) {
            // 非 ISO-offset 形态 → 尝试 ISO 瞬时形态（如 "2026-09-16T14:09:42Z"）
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 是否 content replacement 条目 · 对齐 CC sessionStorage.ts:3682-3693
     * ({@code entry.type === 'content-replacement'}).
     *
     * <p><b>CC JSONL 条目结构</b> (sessionStorage.ts:1119-1122 写入侧, 非顶层记录):
     * <pre>
     * { type: 'content-replacement', sessionId, agentId?, replacements: ContentReplacementRecord[] }
     * </pre>
     * 判定只看顶层 {@code type} 字段; 记录本体在 {@code replacements} 数组内
     * ({@code {kind, toolUseId, replacement}}, toolResultStorage.ts:475-479).
     *
     * <p>[S5 审查迭代修复] 旧实现查顶层 {@code toolUseId + preview} 字段与 CC 结构不匹配:
     * 真实写入方 SessionStorage.writeContentReplacement (R28-3.7) 写入的
     * {@code type='content-replacement'} 条目会被误判为消息节点 → contentReplacements 恒空.
     */
    private static boolean isContentReplacementNode(JsonNode node) {
        return "content-replacement".equals(node.path("type").asText());
    }

    /**
     * 解析 content replacement 条目 → 该 agent 的记录列表 · 对齐 CC
     * sessionStorage.ts:3682-3693 (loadTranscriptFile 解析侧) + getAgentTranscript
     * (:4231 {@code agentContentReplacements.get(agentId) ?? []}).
     *
     * <p>归桶语义: {@code entry.agentId} 真值且等于查询 agentId 才归入该 agent 桶;
     * 无 agentId 条目按 sessionId 归桶 (getAgentTranscript 不返回) → 返回空列表.
     *
     * <p>记录字段 CC original: ContentReplacementRecord.kind/toolUseId/replacement
     * (Open-ClaudeCode/src/utils/toolResultStorage.ts:475-479); kind 缺失时默认
     * {@code 'tool-result'} (宽松解析, 对齐 Java 写入方不写 kind 的现状).
     *
     * @param node    content-replacement 条目节点
     * @param agentId 查询 agent id (getAgentTranscript 参数)
     * @return 该 agent 的替换记录列表 (可能为空)
     */
    private static List<ContentReplacementRecord> parseContentReplacement(JsonNode node, String agentId) {
        String entryAgentId = node.has("agentId") && !node.get("agentId").isNull()
            ? node.path("agentId").asText() : null;
        if (entryAgentId == null || entryAgentId.isEmpty() || !agentId.equals(entryAgentId)) {
            return List.of();
        }
        List<ContentReplacementRecord> records = new ArrayList<>();
        JsonNode replacements = node.path("replacements");
        if (replacements.isArray()) {
            for (JsonNode r : replacements) {
                records.add(new ContentReplacementRecord(
                    r.path("kind").asText("tool-result"),
                    r.path("toolUseId").asText(),
                    r.path("replacement").asText()));
            }
        }
        if (log.isDebugEnabled() && !records.isEmpty()) {
            log.debug("解析到 {} 条 content replacement 记录 (agent={})", records.size(), agentId);
        }
        return records;
    }

    /** JSONL 节点 → AgentMessage (role/content/isApiError/agentId/isSidechain/uuid/parentUuid/toolCalls/toolCallId). */
    private static AgentMessage parseMessageNode(JsonNode node) {
        // [content-blocks] 块续行（一块一行的非首行）不是独立消息：
        //   跳过 ⇒ 会话链重建与模型面与改动前逐位一致（块序仅由首行 blocks 数组表达）。
        if (node.path("blockContinuation").asBoolean(false)) {
            return null;
        }
        String role = node.path("role").asText("user");
        String content = node.has("content") && !node.get("content").isNull()
            ? node.path("content").asText("") : "";
        boolean isApiError = node.path("isApiError").asBoolean(false);
        String agentId = node.has("agentId") && !node.get("agentId").isNull()
            ? node.path("agentId").asText() : null;
        boolean isSidechain = node.path("isSidechain").asBoolean(false);
        String uuid = node.has("uuid") && !node.get("uuid").isNull()
            ? node.path("uuid").asText() : null;
        String parentUuid = node.has("parentUuid") && !node.get("parentUuid").isNull()
            ? node.path("parentUuid").asText() : null;
        String toolCallId = node.has("toolCallId") && !node.get("toolCallId").isNull()
            ? node.path("toolCallId").asText() : null;
        // [G2] 时间戳 · CC original: msg.timestamp（sessionStorage.ts:2046-2059 findLatestMessage
        //   以 Date.parse(msg.timestamp) 判「最新 leaf」）。缺失/非字符串 → null（无排序信息）。
        String timestamp = node.has("timestamp") && !node.get("timestamp").isNull()
            ? node.path("timestamp").asText(null) : null;
        List<AgentMessage.ToolCallInfo> toolCalls = new ArrayList<>();
        JsonNode tcs = node.path("toolCalls");
        if (tcs.isArray()) {
            for (JsonNode tc : tcs) {
                toolCalls.add(new AgentMessage.ToolCallInfo(
                    tc.path("id").asText(),
                    tc.path("name").asText(),
                    tc.has("arguments") && !tc.get("arguments").isNull()
                        ? tc.path("arguments").asText() : null));
            }
        }
        String reasoning = node.has("reasoning") && !node.get("reasoning").isNull()
            ? node.path("reasoning").asText(null) : null;
        // [2026-11-02] 系统注入三件套 · CC original: msg.isMeta / msg.author / msg.type(subtype)
        //   旧转录无这些键 → false / null（= CC undefined，非双轨）。
        boolean isMeta = node.path("isMeta").asBoolean(false);
        String author = node.has("author") && !node.get("author").isNull()
            ? node.path("author").asText(null) : null;
        String subtype = node.has("subtype") && !node.get("subtype").isNull()
            ? node.path("subtype").asText(null) : null;
        // [agent-notice] SendMessage 通知行 UI 摘要（惰性键，旧转录无 → null）。
        String summary = node.has("summary") && !node.get("summary").isNull()
            ? node.path("summary").asText(null) : null;
        return new AgentMessage(role, content, isApiError, agentId, isSidechain,
            uuid, parentUuid, toolCalls, toolCallId, timestamp, reasoning,
            isMeta, author, subtype, summary);
    }

    /**
     * 重建对话链 · 对齐 CC buildConversationChain (sessionStorage.ts:2069-2094).
     *
     * <p>从 leaf 沿 parentUuid 反向走 (cycle 检测), 再 reverse 得到正序链.
     * 简化: 不含 CC 的 recoverOrphanedParallelToolResults 后处理 (Java 单 parent 链模型).
     *
     * @param messages    全部消息 (按 uuid 索引)
     * @param leafMessage 链末端消息 (uuid 非任何 parentUuid)
     * @return 正序消息链 (leaf → root reversed)
     */
    static List<AgentMessage> buildConversationChain(List<AgentMessage> messages, AgentMessage leafMessage) {
        Map<String, AgentMessage> byUuid = new HashMap<>();
        for (AgentMessage m : messages) {
            if (m.uuid() != null) byUuid.put(m.uuid(), m);
        }
        List<AgentMessage> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        AgentMessage current = leafMessage;
        while (current != null) {
            if (current.uuid() != null && !seen.add(current.uuid())) {
                log.warn("parentUuid 链检测到环 (消息 {}), 返回部分 transcript.",
                    current.uuid());
                break;
            }
            chain.add(current);
            current = current.parentUuid() != null ? byUuid.get(current.parentUuid()) : null;
        }
        Collections.reverse(chain);
        return chain;
    }
}
