package com.nexusai.kb.repo;

import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识条目<b>状态机原子操作</b> · 对齐 WeKnora
 * {@code internal/application/repository/knowledge.go}（{@code SetFinalizing} / {@code FinalizeSubtask} /
 * {@code CompleteProcessingWithoutSubtasks}）。
 *
 * <p><b>⭐⭐ 为什么必须「带条件 UPDATE + 看影响行数」</b>
 *
 * <p>源侧注释记录了一个真实 bug：{@code finalizing} 行永久卡死、
 * {@code pending_subtasks_count} 停在非零。根因是<b>先 SELECT 判计数再 UPDATE</b>
 * —— 读写分离时 SELECT 可能读到副本滞后值，即使主库已归零，所有调用方都读到非零
 * ⇒ <b>没有任何人执行提升</b>。
 *
 * <p>⇒ 正确写法只有一种：把判断条件写进 {@code WHERE}，让数据库在<b>同一个写操作</b>里
 * 完成「比较 + 修改」，再用影响行数判断「我是不是那个翻转状态的人」。
 * ⛔ 不要改成先查后改。
 *
 * <p><b>两条 UPDATE 必须同事务</b>（{@link #finalizeSubtask}）：只递减不提升 ⇒
 * 计数归零但没人提升，同样永久卡死。
 *
 * <p><b>为什么用 String 时间参数</b>：本类的 SQL 是注解原生 SQL，
 * 参数按 Java 类型绑定（String → VARCHAR → SQLite TEXT）。
 * 这绕开了 {@code UpdateChain} 的坑（它按实体字段类型 {@code LocalDateTime}
 * 挂 TypeHandler，传 String 会 ClassCastException，实测）。
 * 时间串统一由 {@link KbTimeFormat#now()} 产出，落库形态与
 * {@code datetime('now')} 逐字节同款。
 */
@Repository
public class KbKnowledgeStateRepository {

    private static final Logger log = LoggerFactory.getLogger(KbKnowledgeStateRepository.class);

    /** 解析状态常量（与 {@code KbKnowledgeRecord#parseStatus} 取值一致）。 */
    public static final String STATUS_UNPROCESSED = "unprocessed";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_FINALIZING = "finalizing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_DELETING = "deleting";
    public static final String STATUS_CANCELLED = "cancelled";

    private final KbKnowledgeMapper mapper;

    public KbKnowledgeStateRepository(KbKnowledgeMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 一次子任务终态处理的结果。
     *
     * @param decremented 是否成功递减了一个槽位（false = 计数已为 0，重复回调）
     * @param promoted    是否是<b>本调用</b>把状态从 {@code finalizing} 翻成 {@code completed}
     */
    public record FinalizeResult(boolean decremented, boolean promoted) {
    }

    /**
     * 进入 {@code finalizing} 并播种槽位数（源侧 {@code SetFinalizing}）。
     *
     * <p>{@code WHERE parse_status = 'processing'} 是<b>必需</b>的：用户可能在
     * 「解析完成」与「后处理开始」之间点了取消/删除，不能被劫持回 {@code finalizing}。
     *
     * @param knowledgeId      知识条目 ID
     * @param expectedSubtasks 预计子任务数（必须 &gt; 0）
     * @return true = 真的转移了
     */
    public boolean setFinalizing(String knowledgeId, int expectedSubtasks) {
        if (expectedSubtasks <= 0) {
            throw new IllegalArgumentException("expectedSubtasks 必须为正数，实际 " + expectedSubtasks);
        }
        boolean transitioned = mapper.setFinalizing(
            knowledgeId, expectedSubtasks, KbTimeFormat.now()) > 0;
        if (transitioned) {
            log.info("[知识入库] 进入 finalizing id={} 待完成子任务={}", knowledgeId, expectedSubtasks);
        } else {
            log.info("[知识入库] 未进入 finalizing（行已不在 processing）id={}", knowledgeId);
        }
        return transitioned;
    }

    /**
     * 释放一个后处理槽位，并在计数归零时提升为 {@code completed}
     * （源侧 {@code FinalizeSubtask}）。
     *
     * <p><b>⭐ 两步语义</b>：
     * <ol>
     *   <li>递减：{@code SET count = count - 1 WHERE count > 0}（clamp 在 0）</li>
     *   <li>提升：{@code SET status='completed' WHERE status='finalizing' AND count = 0}
     *       —— 每次都无条件尝试</li>
     * </ol>
     *
     * <p>两步在同一事务内：只递减不提升会让行永久停在 finalizing。
     *
     * @param knowledgeId 知识条目 ID
     * @return 两步各自的真实结果
     */
    @Transactional
    public FinalizeResult finalizeSubtask(String knowledgeId) {
        String now = KbTimeFormat.now();
        boolean decremented = mapper.decrementSubtask(knowledgeId, now) > 0;
        boolean promoted = mapper.promoteIfDrained(knowledgeId, now) > 0;

        if (promoted) {
            log.info("[知识入库] 全部子任务完成，已提升为 completed id={}", knowledgeId);
        } else if (!decremented) {
            log.debug("[知识入库] 槽位已为 0，忽略重复回调 id={}", knowledgeId);
        }
        return new FinalizeResult(decremented, promoted);
    }

    /**
     * 无后处理子任务时直接完成（源侧 {@code CompleteProcessingWithoutSubtasks}）。
     *
     * @param knowledgeId 知识条目 ID
     * @return true = 真的完成了
     */
    public boolean completeWithoutSubtasks(String knowledgeId) {
        boolean completed = mapper.completeWithoutSubtasks(knowledgeId, KbTimeFormat.now()) > 0;
        if (!completed) {
            log.info("[知识入库] 未完成（行已不在 processing）id={}", knowledgeId);
        }
        return completed;
    }

    /**
     * 带守卫地进入 {@code processing}。
     *
     * @param knowledgeId 知识条目 ID
     * @return true = 成功置为 processing
     */
    public boolean markProcessing(String knowledgeId) {
        return mapper.markProcessing(knowledgeId, KbTimeFormat.now()) > 0;
    }

    /**
     * 带守卫地标记失败（只在 {@code processing} 时生效）。
     *
     * @param knowledgeId  知识条目 ID
     * @param errorMessage 失败原因（截断到 2000 字符）
     * @return true = 真的标了失败
     */
    public boolean markFailed(String knowledgeId, String errorMessage) {
        String msg = errorMessage == null ? "" : errorMessage;
        if (msg.length() > 2000) {
            msg = msg.substring(0, 2000);
        }
        return mapper.markFailed(knowledgeId, msg, KbTimeFormat.now()) > 0;
    }

    /**
     * 用户取消 · 保留行与已有分块（源侧 cancelled 语义）。
     *
     * @param knowledgeId 知识条目 ID
     * @return true = 真的取消了
     */
    public boolean markCancelled(String knowledgeId) {
        return mapper.markCancelled(knowledgeId, KbTimeFormat.now()) > 0;
    }

    /**
     * 分块与索引落库完成 · 对齐源侧 {@code finalizeIndexedKnowledgeState}。
     *
     * <p><b>⭐⭐ 检索可见性的唯一开关</b>：检索链路只查 {@code enable_status='enabled'}。
     * 创建时置 {@code disabled}，此处转 {@code enabled} —— 必须发生在分块写入之后，
     * 否则解析未落库的文档会出现在检索结果里。
     *
     * <p>⚠️ 本实现里 {@code storageSize} 是<b>分块内容 UTF-8 字节总和</b>的近似
     * （源侧用向量存储估算 `EstimateStorageSize`，依赖 B3 的向量库；B3 落地后应改用它）。
     *
     * @param knowledgeId 知识条目 ID
     * @param storageSize 存储占用估算（字节）
     * @return true = 真的置了
     */
    public boolean markIndexed(String knowledgeId, long storageSize) {
        return mapper.markIndexed(knowledgeId, storageSize, KbTimeFormat.now()) > 0;
    }

    /**
     * 开始新一轮尝试：{@code parse_attempt + 1} 并置 {@code pending}。
     *
     * <p>增量在 SQL 里做（原子），不由调用方读后写。
     *
     * @param knowledgeId 知识条目 ID
     * @return 新的 attempt 值；行不存在或在 {@code deleting} 时返回 null
     */
    @Transactional
    public Integer beginAttempt(String knowledgeId) {
        if (mapper.beginAttempt(knowledgeId, KbTimeFormat.now()) <= 0) {
            return null;
        }
        return mapper.selectParseAttempt(knowledgeId);
    }

    /**
     * 读取当前 attempt（供任务执行前比对）。
     *
     * @param knowledgeId 知识条目 ID
     * @return 当前 attempt；行不存在时 null
     */
    public Integer currentAttempt(String knowledgeId) {
        return mapper.selectParseAttempt(knowledgeId);
    }

    /**
     * 读取知识条目（供编排器守卫判断）。
     *
     * @param knowledgeId 知识条目 ID
     * @return 行；不存在时 null
     */
    public KbKnowledgeRecord find(String knowledgeId) {
        return mapper.selectOneById(knowledgeId);
    }
}
