package com.nexusai.kb.domain;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbAuditLogRecord;
import com.nexusai.kb.repo.mapper.KbAuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 知识库活动流（{@code kb_audit_log}）· 批次 W4 · V269 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code audit_logs}（000044）+ {@code GET /knowledge-bases/{id}/activity}。
 *
 * <h2>⭐⭐ 写日志绝不能把主业务拖挂</h2>
 * 活动流是**旁路证据**：日志写失败不应该让「删除知识库」这种操作回滚。
 * 所以 {@link #record} 内部吞掉异常并记 WARN —— 这是本类唯一允许吞异常的地方，
 * 且必须打日志（否则排查「为什么活动流少了一条」时无从下手）。
 *
 * <h2>⭐ 游标翻页用自增 id</h2>
 * 源侧 {@code after_id} 是整数游标。用 UUID 主键就没有稳定的「比这条更新」比较，
 * 翻页会漏行 —— V269 把主键定成 {@code INTEGER PRIMARY KEY AUTOINCREMENT} 就是为此。
 */
@Service
public class KbActivityService {

    private static final Logger log = LoggerFactory.getLogger(KbActivityService.class);

    /** 单页默认条数。 */
    private static final int DEFAULT_LIMIT = 20;

    /** 单页上限（防止前端一次拉全表）。 */
    private static final int MAX_LIMIT = 200;

    /** 合法的 outcome 取值。 */
    private static final Set<String> ALLOWED_OUTCOMES = Set.of("success", "failure", "denied");

    private final KbAuditLogMapper mapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    public KbActivityService(KbAuditLogMapper mapper,
                             org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    /**
     * 记录一条活动。
     *
     * <p>⚠️ 吞异常。见类注释「写日志绝不能把主业务拖挂」。
     *
     * <p>⭐⭐ <b>如果当前在事务里 ⇒ 推迟到提交后写</b>。
     * 原因：SQLite 单写锁。若在 {@code @Transactional} 方法内用另一个连接写这张表，
     * 外层事务已持有写锁 ⇒ 必然 {@code SQLITE_BUSY}（实测：活动流一条都没写进去，
     * 而且因为本方法吞异常，主业务看起来完全正常 —— 这是一条「静默全丢」的链路）。
     * 与缺陷 5（{@code @Transactional} + 原生 JDBC 写 FTS）是同一根因。
     *
     * @param knowledgeBaseId 作用域（知识库 ID）
     * @param action          动作（见本类的 {@code A_*} 常量）
     * @param outcome         success / failure / denied
     * @param targetType      目标类型（knowledge / chunk / tag / wiki_page，可空）
     * @param targetId        目标 ID（可空）
     * @param details         结构化明细（JSON 文本，可空）
     */
    public void record(String knowledgeBaseId, String action, String outcome,
                       String targetType, String targetId, String details) {
        ActivityEntry entry = new ActivityEntry(knowledgeBaseId, action, outcome,
            targetType, targetId, details);
        if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive()) {
            // 有活跃事务 ⇒ 提交后再写（回滚了就不写：那条活动没真正发生）
            org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            writeQuietly(entry);
                        }
                    });
            return;
        }
        writeQuietly(entry);
    }

    /** 真正落库（吞异常）。 */
    private void writeQuietly(ActivityEntry e) {
        try {
            // ⚠️ 用参数化 JDBC 而不是 Flex 的 mapper.insert：
            //    · 本表主键是 INTEGER PRIMARY KEY AUTOINCREMENT（不是应用层 UUID），
            //      源侧 after_id 游标依赖真实自增 id；
            //    · 实测 Flex 的 insert 在这张表上抛 NPE（getMessage() 为空）。
            jdbc.update(
                "INSERT INTO kb_audit_log (scope_type, scope_id, target_type, target_id, "
                    + "target_user_id, actor_user_id, actor_role, action, outcome, "
                    + "request_method, request_path, details, created_at, tenant_id) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "knowledge_base",
                e.knowledgeBaseId(),
                e.targetType(),
                e.targetId(),
                "",
                // 单机应用没有登录用户 ⇒ 固定 system；接入权限后从上下文取。
                "system",
                "owner",
                e.action(),
                e.outcome(),
                "",
                "",
                e.details(),
                KbTimeFormat.format(LocalDateTime.now()),
                0L);
        } catch (RuntimeException ex) {
            log.warn("[活动流] 写入失败（已忽略，不影响主业务）kb={} action={} 异常={} 原因={}",
                e.knowledgeBaseId(), e.action(), ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    /** 待写的一条活动（事务提交后落库）。 */
    private record ActivityEntry(String knowledgeBaseId, String action, String outcome,
                                 String targetType, String targetId, String details) {
        ActivityEntry {
            knowledgeBaseId = knowledgeBaseId == null ? "" : knowledgeBaseId;
            action = action == null ? "" : action;
            outcome = outcome == null ? "success" : outcome;
            targetType = targetType == null ? "" : targetType;
            targetId = targetId == null ? "" : targetId;
            details = details == null || details.isBlank() ? "{}" : details;
        }
    }

    /** 便捷重载：成功 + 无明细。 */
    public void record(String knowledgeBaseId, String action) {
        record(knowledgeBaseId, action, "success", "", "", null);
    }

    /**
     * 按知识库倒序翻页。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param afterId         游标：只返回 id 小于它的行（null = 从最新开始）
     * @param limit           条数（默认 20，上限 200）
     * @param action          动作过滤（可空）
     * @param outcome         结果过滤（可空；非法值 400）
     * @param actor           操作者过滤（可空）
     */
    public List<KbAuditLogRecord> list(String knowledgeBaseId, Long afterId, Integer limit,
                                       String action, String outcome, String actor) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
        if (outcome != null && !outcome.isBlank() && !ALLOWED_OUTCOMES.contains(outcome)) {
            throw new ValidationException("outcome 只能是 success / failure / denied，收到: " + outcome);
        }
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        QueryWrapper q = QueryWrapper.create()
            .where("scope_id = ?", knowledgeBaseId);
        if (afterId != null && afterId > 0) {
            q.and("id < ?", afterId);
        }
        if (action != null && !action.isBlank()) {
            q.and("action = ?", action.trim());
        }
        if (outcome != null && !outcome.isBlank()) {
            q.and("outcome = ?", outcome.trim());
        }
        if (actor != null && !actor.isBlank()) {
            q.and("actor_user_id = ?", actor.trim());
        }
        q.orderBy("id", false).limit(size);
        return new ArrayList<>(mapper.selectListByQuery(q));
    }

    /** 时间格式化（出站 JSON 用；与其它知识域 DTO 同款）。 */
    public static String formatTime(LocalDateTime t) {
        return KbTimeFormat.format(t);
    }

    // ── 动作常量（⛔ 别在 Service 里散写字面量）──────────────────────
    public static final String A_CREATE = "create";
    public static final String A_UPDATE = "update";
    public static final String A_DELETE = "delete";
    public static final String A_UPLOAD = "upload";
    public static final String A_REPARSE = "reparse";
    public static final String A_CLEAR = "clear";
    public static final String A_COPY = "copy";
    public static final String A_PIN = "pin";
    public static final String A_UNPIN = "unpin";
    public static final String A_MOVE = "move";
    public static final String A_TAG = "tag";
    public static final String A_REBUILD_INDEX = "rebuild_index";
    public static final String A_PROFILE_GENERATE = "profile_generate";
    public static final String A_CHUNK_EDIT = "chunk_edit";
    public static final String A_CHUNK_REVERT = "chunk_revert";
    public static final String A_CHUNK_DELETE = "chunk_delete";
}
