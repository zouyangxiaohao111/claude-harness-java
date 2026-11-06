package com.nexusai.kb.app.memory;

import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.dto.memory.KbMemoryDocView;
import com.nexusai.kb.dto.memory.KbMemoryItemDto;
import com.nexusai.kb.dto.memory.KbMemorySettingsDto;
import com.nexusai.kb.dto.memory.KbMemoryTopicView;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbMemoryDocAffinityRecord;
import com.nexusai.kb.repo.entity.KbMemoryItemRecord;
import com.nexusai.kb.repo.entity.KbMemorySubjectRecord;
import com.nexusai.kb.repo.entity.KbMemoryTopicStatRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 长期记忆业务（批次 W9）· 源侧 {@code internal/application/service/memory/service.go} 的对应物。
 *
 * <h2>⭐⭐ 写入四道闸（顺序不能变）</h2>
 * <ol>
 *   <li><b>清洗</b>：压成单行、删控制字符、截 300 码点 —— 记忆会进系统提示词，
 *       一条记忆就能伪造提示词结构</li>
 *   <li><b>脱敏</b>：13 条正则；脱完剩不到 6 个码点 ⇒ <b>拒绝</b>（存下来只是个洞）</li>
 *   <li><b>墓碑</b>：用户删过的不能因后台重抽而复活（精确指纹 + 源消息窗口）</li>
 *   <li><b>去重</b>：同键同内容 ⇒ 返回旧行；同 kind 内容互相包含 ⇒ 合并</li>
 * </ol>
 *
 * <h2>⭐ 双时态</h2>
 * 新条目与 active 条目同 {@code normalizedKey} ⇒ 旧的标 superseded + invalidAt + supersededBy，
 * <b>内容保留</b>。「上个月它以为是什么」必须还答得出来。
 */
@Service
public class KbMemoryService {

    private static final Logger log = LoggerFactory.getLogger(KbMemoryService.class);

    /**
     * 墓碑的「源消息」回溯窗口（秒）。
     *
     * <p>⚠️ 只在这个窗口内认为「同一条消息产出的记忆都被拒过」。
     * 窗口外重新抽取是合理的（用户可能改了主意）；窗口内是「刚删完又冒出来」。
     */
    static final int REJECTED_MESSAGE_WINDOW_SECONDS = 7 * 24 * 3600;

    /** 「立刻整理」的最小间隔（秒）—— 单独的时钟，避免每日整理把按钮挡掉。 */
    static final int FORCED_CONSOLIDATE_MIN_INTERVAL_SECONDS = 60;

    @Autowired private KbMemoryRepository repo;
    /**
     * 知识库域配置（R0 · V273）：记忆开关/写入模式/容量/阈值已迁至 kb_config。
     * ⚠️ 不再经 settings —— 知识库的配置放独立表，整块拆走时不用带公共偏好表。
     */
    @Autowired private com.nexusai.kb.config.KbConfigService kbConfigService;

    // ── 设置 ──────────────────────────────────────────────

    /** 当前有效的记忆设置（已经合并了工作区开关与用户开关）。 */
    public KbMemorySettingsDto settings() {
        KbMemoryScope scope = KbMemoryScope.current();
        KbMemorySubjectRecord subject = repo.findSubject(scope);

        boolean workspaceEnabled = readWorkspaceEnabled();
        boolean userEnabled = subject == null || subject.getEnabled() == null || subject.getEnabled() != 0;
        int itemCount = (int) repo.countItems(scope, KbMemoryTypes.STATUS_ACTIVE);

        return new KbMemorySettingsDto(
            workspaceEnabled,
            userEnabled,
            workspaceEnabled && userEnabled,
            readWriteMode(),
            itemCount,
            readMaxItems());
    }

    /**
     * 改用户自己的开关。
     *
     * <p>⚠️ 只改 {@code enabled}，不动工作区开关 —— 工作区关着时这个值仍然记着，
     * 管理员重新打开后用户的位置不丢（源侧同）。
     */
    public KbMemorySettingsDto setEnabled(boolean enabled) {
        repo.updateSubjectEnabled(KbMemoryScope.current(), enabled);
        return settings();
    }

    /** 记忆此刻是否可用于读（工作区开关 ∧ 用户开关）。 */
    public boolean isUsable() {
        if (!readWorkspaceEnabled()) {
            return false;
        }
        KbMemorySubjectRecord subject = repo.findSubject(KbMemoryScope.current());
        return subject == null || subject.getEnabled() == null || subject.getEnabled() != 0;
    }

    // ── 条目 ──────────────────────────────────────────────

    public List<KbMemoryItemDto> listItems(String status, int limit, int offset) {
        if (!KbMemoryTypes.isValidStatusFilter(status)) {
            throw new ValidationException("unsupported status: " + status);
        }
        KbMemoryScope scope = KbMemoryScope.current();
        return repo.listItems(scope, status, limit, offset).stream().map(this::toDto).toList();
    }

    public long countItems(String status) {
        return repo.countItems(KbMemoryScope.current(), status);
    }

    /**
     * 手工新增一条记忆。
     *
     * <p>⚠️ 来源记为 {@code manual}，<b>不走墓碑拦截</b> —— 用户亲自打的字必须写得进去。
     */
    public KbMemoryItemDto createItem(String kind, String content, int importance) {
        requireEnabled();
        String k = KbMemoryTypes.isValidKind(kind) ? kind.trim().toLowerCase() : KbMemoryTypes.KIND_FACT;
        int imp = importance <= 0 ? KbMemoryTypes.DEFAULT_IMPORTANCE : KbMemoryTypes.clampImportance(importance);

        KbMemoryItemRecord draft = new KbMemoryItemRecord();
        draft.setKind(k);
        draft.setContent(content);
        draft.setImportance(imp);
        draft.setOrigin(KbMemoryTypes.ORIGIN_MANUAL);
        return toDto(write(draft));
    }

    /**
     * 编辑一条记忆。
     *
     * <p>⚠️ 编辑后来源变成 {@code manual}：用户的手改不能被下一轮抽取悄悄改回去。
     */
    public KbMemoryItemDto updateItem(String id, String content, int importance) {
        KbMemoryScope scope = KbMemoryScope.current();
        KbMemoryItemRecord existing = requireItem(scope, id);

        String sanitized = sanitizeOrThrow(content);
        String normalizedKey = KbMemoryText.itemKey(existing.getTopic(), sanitized);
        int imp = KbMemoryTypes.clampImportance(
            importance <= 0 ? existing.getImportance() : importance);

        repo.updateItemContent(scope, id, sanitized, normalizedKey, imp);
        rebuildBlock(scope);
        return toDto(requireItem(scope, id));
    }

    /**
     * 忘掉一条。
     *
     * <p>⚠️ <b>先记墓碑再删行</b>：删掉一条抽取任务正要重新推导的记忆，
     * 是用户「删了两次同一件东西」并从此不再信任这个功能的原因。
     */
    public void deleteItem(String id) {
        KbMemoryScope scope = KbMemoryScope.current();
        KbMemoryItemRecord existing = requireItem(scope, id);
        repo.addTombstone(scope, existing.getTopic(),
            KbMemoryText.fingerprint(existing.getContent()), existing.getSourceMessageId());
        repo.deleteItem(scope, id);
        rebuildBlock(scope);
    }

    /**
     * 全部忘掉。
     *
     * <p>⚠️ 墓碑同样留下 —— 清空后下一轮不该把刚清掉的话再抽回来。
     *
     * @return 删掉的条数
     */
    public long clear() {
        KbMemoryScope scope = KbMemoryScope.current();
        // 先给每条 active 记墓碑，再一次性删
        for (KbMemoryItemRecord r : repo.listItems(scope, null, 20000, 0)) {
            repo.addTombstone(scope, r.getTopic(),
                KbMemoryText.fingerprint(r.getContent()), r.getSourceMessageId());
        }
        long removed = repo.deleteAllItems(scope);
        rebuildBlock(scope);
        log.info("[记忆] 已清空 {} 条（墓碑保留）", removed);
        return removed;
    }

    /** 导出全部（快照，不是分页）。 */
    public List<KbMemoryItemDto> export() {
        return repo.listItems(KbMemoryScope.current(), null, 20000, 0).stream()
            .map(this::toDto).toList();
    }

    /**
     * 确认一条推断出来的记忆 ⇒ 开始生效。
     *
     * <p>⚠️ 只有 {@code pending} 能确认。其它状态确认没有意义（已经把这条当真了），
     * 静默成功会让人以为「系统又确认了一次」。
     */
    public KbMemoryItemDto confirmItem(String id) {
        KbMemoryScope scope = KbMemoryScope.current();
        KbMemoryItemRecord existing = requireItem(scope, id);
        if (!KbMemoryTypes.STATUS_PENDING.equals(existing.getStatus())) {
            throw new ConflictException("只有待确认的记忆才能确认（当前状态：" + existing.getStatus() + "）");
        }
        repo.updateStatus(scope, id, KbMemoryTypes.STATUS_ACTIVE);
        rebuildBlock(scope);
        return toDto(requireItem(scope, id));
    }

    /**
     * 否决一条推断出来的记忆。
     *
     * <p>⚠️ 删行 + 记墓碑：否则下周又被抽出来提一次。
     */
    public void rejectItem(String id) {
        KbMemoryScope scope = KbMemoryScope.current();
        KbMemoryItemRecord existing = requireItem(scope, id);
        repo.addTombstone(scope, existing.getTopic(),
            KbMemoryText.fingerprint(existing.getContent()), existing.getSourceMessageId());
        repo.deleteItem(scope, id);
        rebuildBlock(scope);
    }

    // ── 主题 ──────────────────────────────────────────────

    public List<KbMemoryTopicView> listTopics(int limit, int offset) {
        KbMemoryScope scope = KbMemoryScope.current();
        int threshold = readInterestThreshold();
        List<KbMemoryTopicView> out = new ArrayList<>();
        for (KbMemoryTopicStatRecord s : repo.listUnpromotedTopics(scope, limit, offset)) {
            out.add(new KbMemoryTopicView(
                s.getId(), s.getTopic(), parseAliases(s.getAliases()), s.getHits(), threshold,
                s.getLastSeenAt()));
        }
        return out;
    }

    public long countTopics() {
        return repo.countUnpromotedTopics(KbMemoryScope.current());
    }

    /**
     * 立即记为长期关注（不等剩余次数）。
     */
    public KbMemoryItemDto promoteTopic(String topicId) {
        requireEnabled();
        KbMemoryScope scope = KbMemoryScope.current();
        KbMemoryTopicStatRecord stat = repo.getTopicById(scope, topicId);
        if (stat == null) {
            throw new NotFoundException("主题 " + topicId + " 不存在");
        }
        repo.markTopicPromoted(scope, topicId);

        KbMemoryItemRecord draft = new KbMemoryItemRecord();
        draft.setKind(KbMemoryTypes.KIND_INTEREST);
        draft.setTopic(stat.getTopic());
        draft.setContent(stat.getTopic());
        draft.setImportance(KbMemoryTypes.DEFAULT_IMPORTANCE);
        draft.setOrigin(KbMemoryTypes.ORIGIN_EXPLICIT);
        return toDto(write(draft));
    }

    /**
     * 停止跟踪一个主题。
     *
     * <p>⚠️ <b>不删行，打拒绝标记</b>：删了行下次又会被自动提升，
     * 用户会觉得「说了别记还记」。
     */
    public void deleteTopic(String topicId) {
        KbMemoryScope scope = KbMemoryScope.current();
        KbMemoryTopicStatRecord stat = repo.getTopicById(scope, topicId);
        if (stat == null) {
            throw new NotFoundException("主题 " + topicId + " 不存在");
        }
        repo.markTopicRejected(scope, topicId);
    }

    // ── 文档亲和 ──────────────────────────────────────────

    public List<KbMemoryDocView> listDocuments(int limit, int offset) {
        KbMemoryScope scope = KbMemoryScope.current();
        return repo.listDocuments(scope, limit, offset).stream()
            .map(d -> new KbMemoryDocView(d.getId(), d.getKnowledgeId(), d.getKnowledgeBaseId(),
                d.getTitle(), d.getHits(), d.getLastUsedAt()))
            .toList();
    }

    public long countDocuments() {
        return repo.countDocuments(KbMemoryScope.current());
    }

    public void deleteDocument(String id) {
        KbMemoryScope scope = KbMemoryScope.current();
        KbMemoryDocAffinityRecord existing = repo.getDocById(scope, id);
        if (existing == null) {
            throw new NotFoundException("文档亲和 " + id + " 不存在");
        }
        repo.deleteDocument(scope, id);
    }

    /** 记录回答引用了哪些文档（知识问答链调用）。 */
    public void recordAnswerSources(List<KbMemoryDocView> refs) {
        if (refs == null || refs.isEmpty() || !isUsable()) {
            return;
        }
        KbMemoryScope scope = KbMemoryScope.current();
        for (KbMemoryDocView ref : refs) {
            try {
                repo.bumpDocAffinity(scope, ref.knowledgeId(), ref.knowledgeBaseId(), ref.title());
            } catch (RuntimeException e) {
                // 记亲和失败不能影响回答
                log.warn("[记忆] 记录文档亲和失败 knowledgeId={} 原因={}", ref.knowledgeId(), e.getMessage());
            }
        }
    }

    /** 计数一个主题（问答链每轮调用）。返回是否因此自动提升为兴趣。 */
    public boolean observeTopic(String topic) {
        if (topic == null || topic.isBlank() || !isUsable()) {
            return false;
        }
        KbMemoryScope scope = KbMemoryScope.current();
        String key = KbMemoryText.normalizeTopicKey(topic);
        if (key.isEmpty()) {
            return false;
        }
        KbMemoryTopicStatRecord stat = repo.bumpTopic(scope, key, KbMemoryText.sanitizeTopic(topic));
        if (stat == null) {
            return false;
        }
        // 已提升 / 用户已拒绝 ⇒ 不再自动提升
        if (stat.getPromotedAt() != null || stat.getRejectedAt() != null) {
            return false;
        }
        if (stat.getHits() != null && stat.getHits() >= readInterestThreshold()) {
            try {
                repo.markTopicPromoted(scope, stat.getId());
                KbMemoryItemRecord draft = new KbMemoryItemRecord();
                draft.setKind(KbMemoryTypes.KIND_INTEREST);
                draft.setTopic(stat.getTopic());
                draft.setContent(stat.getTopic());
                draft.setImportance(KbMemoryTypes.DEFAULT_IMPORTANCE);
                draft.setOrigin(KbMemoryTypes.ORIGIN_EXTRACTED);
                write(draft);
                return true;
            } catch (RuntimeException e) {
                log.warn("[记忆] 自动提升兴趣失败 topic={} 原因={}", topic, e.getMessage());
            }
        }
        return false;
    }

    // ── 注入 ──────────────────────────────────────────────

    /**
     * 取该注入系统提示词的记忆文本。
     *
     * <p>⚠️ 任何一级关闭都返回<b>空串而不是异常</b>：
     * 注入点每轮都会调用，⛔ 不能因为记忆被关掉就让整个对话失败。
     */
    public String promptBlock() {
        try {
            if (!isUsable()) {
                return "";
            }
            KbMemoryScope scope = KbMemoryScope.current();
            KbMemorySubjectRecord subject = repo.ensureSubject(scope);
            return subject.getBlockText() == null ? "" : subject.getBlockText();
        } catch (RuntimeException e) {
            log.warn("[记忆] 读取常驻块失败，本轮不注入: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 取「提问者背景」文本（兴趣 + 常查资料）· 供<b>查询改写</b>使用。
     *
     * <p><b>⭐⭐ 与 {@link #promptBlock()} 的分工</b>（对齐源侧
     * {@code query_understand.go:memoryBackground}）：
     * <ul>
     *   <li>{@link #promptBlock()} —— 常驻块正文，进<b>答案</b>的系统提示词（「你是谁」）；</li>
     *   <li>本方法 —— 摘要式背景，进<b>改写</b>提示词（「这人在关注什么」）。</li>
     * </ul>
     * 分开的理由：改写只需要「他做医疗影像」这种短背景来补全检索词，
     * 把整块记忆塞进改写提示词会挤掉真正的对话历史，且与本轮问题无关。
     *
     * <p>⚠️ 与源侧同一取舍：背景是<b>建议</b>不是过滤器 —— 不缩小检索范围、
     * 不排除任何知识库。一条过时的兴趣项不该让本季度的文档变得不可达。
     *
     * @return 背景文本（形如「长期关注：A、B\n常查资料：C」）；无数据 / 关闭 → 空串
     */
    /**
     * 批量读「这些文档各被用过多少次」（阶段 5c 记忆亲和的读侧）。
     *
     * <p>语义与 {@link #rewriteBackground()} 里的「常查资料」同源（都读
     * {@code kb_memory_doc_affinity}），但这里要的是<b>次数</b>而不是标题 ——
     * 检索加成得按使用频次算曲线。
     *
     * @param knowledgeIds 文档 id
     * @return 文档 id → 命中次数；记忆关闭/无数据 ⇒ 空 map（⛔ 不抛）
     */
    public java.util.Map<String, Integer> documentAffinity(java.util.List<String> knowledgeIds) {
        try {
            if (!isUsable() || knowledgeIds == null || knowledgeIds.isEmpty()) {
                return java.util.Map.of();
            }
            return repo.documentAffinity(KbMemoryScope.current(), knowledgeIds);
        } catch (RuntimeException e) {
            // 亲和是增强：读失败不该让问答失败
            log.warn("[记忆] 读取文档亲和失败，本轮不加成: {}", e.getMessage());
            return java.util.Map.of();
        }
    }

    public String rewriteBackground() {
        try {
            if (!isUsable()) {
                return "";
            }
            KbMemoryScope scope = KbMemoryScope.current();
            List<String> interests = new ArrayList<>();
            for (KbMemoryTopicStatRecord s : repo.listUnpromotedTopics(scope, BACKGROUND_TOPICS, 0)) {
                String topic = s.getTopic();
                if (topic != null && !topic.isBlank()) {
                    interests.add(topic.trim());
                }
            }
            List<String> documents = new ArrayList<>();
            for (KbMemoryDocAffinityRecord d : repo.listDocuments(scope, BACKGROUND_DOCS, 0)) {
                String title = d.getTitle();
                if (title != null && !title.isBlank()) {
                    documents.add(title.trim());
                }
            }
            if (interests.isEmpty() && documents.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (!interests.isEmpty()) {
                sb.append("长期关注：").append(String.join("、", interests));
            }
            if (!documents.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("常查资料：").append(String.join("、", documents));
            }
            return sb.toString();
        } catch (RuntimeException e) {
            // 背景是增强：读失败不该让改写失败
            log.warn("[记忆] 读取改写背景失败，本轮不注入: {}", e.getMessage());
            return "";
        }
    }

    /** 改写背景里的兴趣条数上限（源侧只取「有代表性的几个」）。 */
    private static final int BACKGROUND_TOPICS = 5;

    /** 改写背景里的常查资料条数上限。 */
    private static final int BACKGROUND_DOCS = 5;

    // ── 写入核心 ──────────────────────────────────────────

    /**
     * 写入一条记忆（四道闸 + 双时态 + 容量）。
     *
     * @param draft 草稿（kind/content/topic/importance/origin 已填）
     * @return 最终落库的条目（可能返回已有行 —— 见去重）
     */
    public KbMemoryItemRecord write(KbMemoryItemRecord draft) {
        KbMemoryScope scope = KbMemoryScope.current();
        repo.ensureSubject(scope);

        // ① 清洗
        String content = KbMemoryText.sanitizeContent(draft.getContent());
        if (content.isEmpty()) {
            throw new ValidationException("记忆内容不能为空");
        }

        // ② 脱敏
        KbMemoryText.Redaction redaction = KbMemoryText.redactSensitive(content);
        if (redaction.changed()) {
            if (KbMemoryText.isMostlyRedacted(redaction.text())) {
                throw new ValidationException("这条内容几乎全是敏感信息，不能作为长期记忆保存");
            }
            content = KbMemoryText.sanitizeContent(redaction.text());
        }

        if (!KbMemoryTypes.isValidKind(draft.getKind())) {
            draft.setKind(KbMemoryTypes.KIND_FACT);
        }

        // ③ 墓碑（只对后台抽取生效）
        boolean extracted = KbMemoryTypes.ORIGIN_EXTRACTED.equals(draft.getOrigin());
        if (repo.hasTombstone(scope, KbMemoryText.fingerprint(content))) {
            throw new ConflictException("这条记忆此前被你删过，不再重新记录");
        }
        if (extracted && repo.hasTombstoneForMessage(
            scope, draft.getSourceMessageId(), REJECTED_MESSAGE_WINDOW_SECONDS)) {
            throw new ConflictException("这条消息产出的记忆此前被你否决过，不再重新记录");
        }

        String topic = KbMemoryText.sanitizeTopic(draft.getTopic());
        String normalizedKey = KbMemoryText.itemKey(topic, content);

        // ④ 去重 / 双时态
        KbMemoryItemRecord existing = repo.findActiveByKey(scope, normalizedKey);
        if (existing != null
            && KbMemoryText.sanitizeContent(existing.getContent()).equals(content)) {
            // 同一主题同一句话 ⇒ 什么都不用改，保持原时间戳，不要每轮刷行
            return existing;
        }

        KbMemoryItemRecord stored = new KbMemoryItemRecord();
        stored.setId("mem-" + UUID.randomUUID().toString().substring(0, 8));
        stored.setSubjectId(scope.subjectId());
        stored.setKind(draft.getKind());
        stored.setContent(content);
        stored.setTopic(topic);
        stored.setNormalizedKey(normalizedKey);
        stored.setImportance(KbMemoryTypes.clampImportance(
            draft.getImportance() == null ? KbMemoryTypes.DEFAULT_IMPORTANCE : draft.getImportance()));
        stored.setOrigin(draft.getOrigin() == null ? KbMemoryTypes.ORIGIN_EXTRACTED : draft.getOrigin());
        stored.setStatus(draft.getStatus() == null ? KbMemoryTypes.STATUS_ACTIVE : draft.getStatus());
        stored.setSourceSessionId(draft.getSourceSessionId());
        stored.setSourceMessageId(draft.getSourceMessageId());
        stored.setValidFrom(KbTimeFormat.now());
        stored.setExpiresAt(draft.getExpiresAt());
        stored.setUseCount(0);

        repo.insertItem(stored);

        // 双时态：同键的旧 active 条目标 superseded（内容保留）
        if (existing != null) {
            repo.supersede(scope, existing.getId(), stored.getId());
            log.info("[记忆] 取代旧条目 old={} new={} key={}", existing.getId(), stored.getId(), normalizedKey);
        }

        enforceCapacity(scope);
        rebuildBlock(scope);
        return stored;
    }

    /**
     * 容量约束：超出上限时归档排名最低的 active 条目。
     *
     * <p>⚠️ 这是系统里<b>唯一</b>的自动遗忘。排序按 importance 升序、创建时间升序
     * —— 重要性低的、旧的最先走。
     */
    private void enforceCapacity(KbMemoryScope scope) {
        int max = readMaxItems();
        List<KbMemoryItemRecord> all = repo.listItems(scope, KbMemoryTypes.STATUS_ACTIVE, max + 500, 0);
        if (all.size() <= max) {
            return;
        }
        // 列表已按 importance DESC, create_time DESC 排 ⇒ 末尾就是最该归档的
        int toArchive = all.size() - max;
        for (int i = all.size() - 1; i >= 0 && toArchive > 0; i--, toArchive--) {
            repo.updateStatus(scope, all.get(i).getId(), KbMemoryTypes.STATUS_ARCHIVED);
        }
        log.info("[记忆] 超出容量上限 {}，已归档 {} 条", max, all.size() - max);
    }

    /**
     * 重建常驻块（profile / preference / interest 的 active 条目）。
     *
     * <p>⚠️ 写入后必须重建：读路径只查一次主键，块必须事先渲染好。
     */
    public void rebuildBlock(KbMemoryScope scope) {
        List<KbMemoryItemRecord> resident = repo.listResident(scope);
        List<KbMemoryText.RenderedItem> rendered = resident.stream()
            .map(r -> new KbMemoryText.RenderedItem(r.getKind(), r.getContent()))
            .toList();
        String block = KbMemoryText.renderBlock(rendered);
        int activeCount = (int) repo.countItems(scope, KbMemoryTypes.STATUS_ACTIVE);
        repo.updateSubjectBlock(scope, block, activeCount);
    }

    // ── 内部 ──────────────────────────────────────────────

    private KbMemoryItemRecord requireItem(KbMemoryScope scope, String id) {
        KbMemoryItemRecord r = repo.getItem(scope, id);
        if (r == null) {
            throw new NotFoundException("记忆 " + id + " 不存在");
        }
        return r;
    }

    private void requireEnabled() {
        if (!isUsable()) {
            throw new ValidationException("长期记忆已关闭，请先在记忆中打开");
        }
    }

    private String sanitizeOrThrow(String content) {
        String sanitized = KbMemoryText.sanitizeContent(content);
        if (sanitized.isEmpty()) {
            throw new ValidationException("记忆内容不能为空");
        }
        KbMemoryText.Redaction redaction = KbMemoryText.redactSensitive(sanitized);
        if (redaction.changed()) {
            if (KbMemoryText.isMostlyRedacted(redaction.text())) {
                throw new ValidationException("这条内容几乎全是敏感信息，不能作为长期记忆保存");
            }
            return KbMemoryText.sanitizeContent(redaction.text());
        }
        return sanitized;
    }

    private boolean readWorkspaceEnabled() {
        try {
            return kbConfigService.readMemoryEnabled();
        } catch (RuntimeException e) {
            // fail-soft：读不到按「关」处理 —— 宁可不记，也不要因为 DB 抖动让每轮都跑抽取
            log.warn("[记忆] 读取工作区开关失败，按关闭处理: {}", e.getMessage());
            return false;
        }
    }

    private String readWriteMode() {
        try {
            String mode = kbConfigService.readMemoryWriteMode();
            return (mode == null || mode.isBlank()) ? KbMemoryTypes.defaultWriteMode() : mode;
        } catch (RuntimeException e) {
            return KbMemoryTypes.defaultWriteMode();
        }
    }

    private int readMaxItems() {
        try {
            Integer v = kbConfigService.readMemoryMaxItems();
            return (v == null || v <= 0) ? KbMemoryTypes.DEFAULT_MAX_ITEMS : v;
        } catch (RuntimeException e) {
            return KbMemoryTypes.DEFAULT_MAX_ITEMS;
        }
    }

    private int readInterestThreshold() {
        try {
            Integer v = kbConfigService.readMemoryInterestThreshold();
            return (v == null || v <= 0) ? KbMemoryTypes.DEFAULT_INTEREST_THRESHOLD : v;
        } catch (RuntimeException e) {
            return KbMemoryTypes.DEFAULT_INTEREST_THRESHOLD;
        }
    }

    /** 后台抽取是否开启（工作区开关 ∧ 写入模式 = auto）。 */
    public boolean isAutoExtractEnabled() {
        return readWorkspaceEnabled() && KbMemoryTypes.WRITE_AUTO.equals(readWriteMode());
    }

    private static List<String> parseAliases(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                });
        } catch (Exception e) {
            return List.of();
        }
    }

    private KbMemoryItemDto toDto(KbMemoryItemRecord r) {
        return new KbMemoryItemDto(
            r.getId(), r.getKind(), r.getContent(), r.getTopic(), r.getNormalizedKey(),
            r.getImportance(), r.getOrigin(), r.getStatus(),
            r.getSourceSessionId(), r.getSourceMessageId(),
            r.getValidFrom(), r.getInvalidAt(), r.getExpiresAt(), r.getSupersededBy(),
            r.getLastUsedAt(), r.getUseCount() == null ? 0 : r.getUseCount(),
            r.getCreateTime() == null ? null : KbTimeFormat.format(r.getCreateTime()));
    }

    /** 供整理服务用：当前主体的全部 active 条目。 */
    public List<KbMemoryItemRecord> activeItemsForConsolidation() {
        return repo.listItems(KbMemoryScope.current(), KbMemoryTypes.STATUS_ACTIVE, 500, 0);
    }

    /** 供整理服务用：归档一条。 */
    public void archive(KbMemoryScope scope, String id) {
        repo.updateStatus(scope, id, KbMemoryTypes.STATUS_ARCHIVED);
    }

    /** 供整理服务用：标一次整理时刻。 */
    public void markConsolidated(KbMemoryScope scope, boolean forced) {
        repo.markConsolidated(scope, forced);
    }

    /** 供整理服务用：读上次整理时刻。 */
    public LocalDateTime lastConsolidatedAt(boolean forced) {
        KbMemorySubjectRecord s = repo.findSubject(KbMemoryScope.current());
        if (s == null) {
            return null;
        }
        String v = forced ? s.getForcedConsolidatedAt() : s.getConsolidatedAt();
        return KbTimeFormat.parse(v);
    }

    /** 供抽取服务用：仓库直通。 */
    public KbMemoryRepository repository() {
        return repo;
    }

    /** 供抽取服务用：当前作用域。 */
    public KbMemoryScope scope() {
        return KbMemoryScope.current();
    }
}
