package com.nexusai.kb.domain;

import com.nexusai.kb.app.ingest.KbIngestTaskQueue;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 知识条目入库服务 · 对外入口（提交解析 / 取消）。
 *
 * <p><b>为什么「提交解析」= 先自增 attempt 再入队</b>：
 * attempt 是本系统的<b>唯一并发闸门</b>。自增之后，所有旧任务在
 * {@link com.nexusai.kb.app.ingest.KnowledgeParseProcessor} 的守卫里
 * 都会被判定为陈旧并放弃 ⇒ 不会出现「上一轮的分块写进新轮」。
 * ⛔ 顺序不能反：先入队再自增，任务拿到的 attempt 就是新的，旧任务反而躲过检查。
 *
 * <p><b>错误契约</b>（与 B1a 一致，{@code GlobalExceptionHandler} 转 RFC 7807）：
 * <ul>
 *   <li>{@link NotFoundException} → 404</li>
 *   <li>{@link ValidationException} → 400</li>
 *   <li>{@link ConflictException} → 409（正在删除）</li>
 * </ul>
 */
@Service
public class KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbKnowledgeStateRepository stateRepository;
    private final KbIngestTaskQueue queue;

    public KnowledgeIngestService(
        KbKnowledgeMapper knowledgeMapper,
        KbKnowledgeStateRepository stateRepository,
        KbIngestTaskQueue queue
    ) {
        this.knowledgeMapper = knowledgeMapper;
        this.stateRepository = stateRepository;
        this.queue = queue;
    }

    /**
     * 提交（或重新提交）一次解析。
     *
     * @param knowledgeId 知识条目 ID
     * @return 新任务 ID
     * @throws NotFoundException 条目不存在
     * @throws ConflictException 条目正在删除
     */
    public long submitParse(String knowledgeId) {
        KbKnowledgeRecord knowledge = requireKnowledge(knowledgeId);
        if (KbKnowledgeStateRepository.STATUS_DELETING.equals(knowledge.getParseStatus())) {
            throw new ConflictException("知识条目正在删除，不能提交解析: " + knowledgeId);
        }

        // 作废旧轮次里还没被认领的任务（已被认领的由 attempt 守卫自行放弃）
        queue.cancelQueued(knowledgeId);

        Integer attempt = stateRepository.beginAttempt(knowledgeId);
        if (attempt == null) {
            // beginAttempt 只在「行不存在」或「正在删除」时失败；上面已排除，此处兜底
            throw new ConflictException("知识条目状态已变，无法开始解析: " + knowledgeId);
        }

        long taskId = queue.enqueue(
            knowledgeId, KbIngestTaskRecord.TYPE_PARSE, attempt, null);
        log.info("[入库] 已提交解析 knowledge={} attempt={} task={}", knowledgeId, attempt, taskId);
        return taskId;
    }

    /**
     * 取消解析 · 保留行与已生成的分块（源侧 cancelled 语义）。
     *
     * @param knowledgeId 知识条目 ID
     * @throws NotFoundException 条目不存在
     * @throws ConflictException 当前状态不可取消
     */
    public void cancel(String knowledgeId) {
        KbKnowledgeRecord knowledge = requireKnowledge(knowledgeId);
        queue.cancelQueued(knowledgeId);
        boolean cancelled = stateRepository.markCancelled(knowledgeId);
        if (!cancelled) {
            throw new ConflictException(
                "当前状态不可取消: " + knowledge.getParseStatus() + "（id=" + knowledgeId + "）");
        }
        log.info("[入库] 已取消解析 knowledge={} 原状态={}", knowledgeId, knowledge.getParseStatus());
    }

    private KbKnowledgeRecord requireKnowledge(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            throw new ValidationException("知识条目 ID 不能为空");
        }
        KbKnowledgeRecord knowledge = knowledgeMapper.selectOneById(knowledgeId);
        if (knowledge == null) {
            throw new NotFoundException("知识条目 " + knowledgeId + " 不存在");
        }
        return knowledge;
    }
}
