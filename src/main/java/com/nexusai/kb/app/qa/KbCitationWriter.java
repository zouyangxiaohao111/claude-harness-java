package com.nexusai.kb.app.qa;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.entity.KbCitationRecord;
import com.nexusai.kb.repo.mapper.KbCitationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 引用落库（{@code kb_citation}）· B5 第④段的收尾一步。
 *
 * <h2>为什么引用必须落库，而不是只推给前端</h2>
 * 前端拿到的引用只在<b>这一次渲染</b>里有效。F5 / 换设备 / 隔天再看这条回答，
 * 就再也说不清「这句话是从哪段来的」—— 而知识库问答的整个价值就在可追溯。
 * 源侧同样把引用随消息一起持久化（{@code chat_pipeline/citation_sources.go}）。
 *
 * <h2>⭐⭐ 序号一致性是本类的唯一硬约束</h2>
 * {@code refIndex} 必须与提示词里给模型的编号（{@code [1]}/{@code [2]}…）一一对应。
 * 一旦错位，用户点「引用 2」看到的是别的段落，而且<b>不会报错</b>。
 * ⇒ 调用方必须把 {@code KbContextBuilder} 产出的<b>同一个列表</b>按<b>同一顺序</b>交给本类，
 * ⛔ 不要在这两处之间做任何过滤或重排。
 *
 * <h2>append-only</h2>
 * 只插入，⛔ 不更新。重新生成 = 新的 {@code assistantMessageId} + 新一批行。
 */
@Component
public class KbCitationWriter {

    private static final Logger log = LoggerFactory.getLogger(KbCitationWriter.class);

    /** 单条引用正文快照上限（防止把整段长文复制进引用表）。 */
    private static final int SNIPPET_MAX_CHARS = 400;

    private final KbCitationMapper citationMapper;

    public KbCitationWriter(KbCitationMapper citationMapper) {
        this.citationMapper = citationMapper;
    }

    /**
     * 一条待落库的引用。
     *
     * @param refIndex       引用序号（1 起，必须与提示词编号一致）
     * @param chunkId        块 ID
     * @param knowledgeId    文档 ID
     * @param knowledgeBaseId 知识库 ID
     * @param knowledgeTitle 文档标题（写入时快照）
     * @param content        块正文（内部会截断成 snippet）
     * @param chunkIndex     块序号
     */
    public record Citation(
        int refIndex, String chunkId, String knowledgeId, String knowledgeBaseId,
        String knowledgeTitle, String content, Integer chunkIndex
    ) {
    }

    /**
     * 批量写入引用。
     *
     * <p><b>失败语义</b>：记 WARN 并<b>继续</b>，⛔ 不抛异常。
     * 引用写失败不该让用户拿不到已经生成好的答案 —— 但这属于要排查的问题，所以是 WARN 而非 debug。
     *
     * @param sessionId          会话 ID
     * @param assistantMessageId 归属的 assistant 消息 ID
     * @param citations          引用列表（可为空）
     * @return 实际写入的条数
     */
    public int saveAll(String sessionId, String assistantMessageId, List<Citation> citations) {
        if (sessionId == null || sessionId.isBlank()
            || assistantMessageId == null || assistantMessageId.isBlank()
            || citations == null || citations.isEmpty()) {
            return 0;
        }
        List<KbCitationRecord> rows = new ArrayList<>(citations.size());
        for (Citation c : citations) {
            if (c == null || c.chunkId() == null || c.chunkId().isBlank()) {
                // 没有 chunkId 的引用点不开，写进去只会污染证据链
                log.warn("[引用] 跳过缺 chunkId 的引用 session={} msg={} 序号={}",
                    sessionId, assistantMessageId, c == null ? null : c.refIndex());
                continue;
            }
            KbCitationRecord row = new KbCitationRecord();
            row.setId(UUID.randomUUID().toString());
            row.setSessionId(sessionId);
            row.setAssistantMessageId(assistantMessageId);
            row.setRefIndex(c.refIndex());
            row.setChunkId(c.chunkId());
            row.setKnowledgeId(c.knowledgeId());
            row.setKnowledgeBaseId(c.knowledgeBaseId());
            row.setKnowledgeTitle(c.knowledgeTitle());
            row.setSnippet(truncate(c.content()));
            row.setChunkIndex(c.chunkIndex());
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return 0;
        }
        int saved = 0;
        try {
            for (KbCitationRecord row : rows) {
                citationMapper.insert(row);
                saved++;
            }
        } catch (RuntimeException e) {
            // ⭐ 引用是附加证据，不是答案本身 ⇒ 失败不打断主流程
            log.warn("[引用] 写入失败（已写入 {} / {} 条）session={} msg={} 原因={}",
                saved, rows.size(), sessionId, assistantMessageId, e.getMessage());
            return saved;
        }
        if (log.isInfoEnabled()) {
            log.info("[引用] 落库完成 session={} msg={} 条数={} 序号区间=[{}, {}]",
                sessionId, assistantMessageId, saved,
                rows.get(0).getRefIndex(), rows.get(rows.size() - 1).getRefIndex());
        }
        return saved;
    }

    /**
     * 按 assistant 消息取引用（前端渲染用）。
     *
     * @param assistantMessageId assistant 消息 ID
     * @return 按 {@code refIndex} 升序；无则空列表
     */
    public List<KbCitationRecord> listByMessage(String assistantMessageId) {
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return List.of();
        }
        try {
            return citationMapper.selectListByQuery(QueryWrapper.create()
                .where("assistant_message_id = ?", assistantMessageId)
                .and("deleted = 0")
                .orderBy("ref_index", true));
        } catch (RuntimeException e) {
            log.warn("[引用] 查询失败 msg={} 原因={}", assistantMessageId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 取某会话最近的引用（阶段 7 补 · 历史引用注入用）。
     *
     * <p>⭐ 按 {@code create_time} 倒序取前 N 条：调用方再按 {@code assistantMessageId}
     * 分组，只取最近那一轮 —— 跨轮混注会引入与当前问题无关的陈旧证据。
     *
     * @param sessionId 会话 ID
     * @param limit     最多取多少条（&lt;= 0 ⇒ 不取）
     * @return 最近的引用（时间倒序）；无数据 / 失败 ⇒ 空列表
     */
    public List<KbCitationRecord> listRecentBySession(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            return citationMapper.selectListByQuery(QueryWrapper.create()
                .where("session_id = ?", sessionId)
                .and("deleted = 0")
                .orderBy("create_time", false)
                .limit(limit));
        } catch (RuntimeException e) {
            log.warn("[引用] 按会话查询失败 session={} 原因={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 把某轮的引用行改挂到**落库后**的 assistant 消息 id 上。
     *
     * <p>WHY：写引用时 assistant 还没落库，只能用受理阶段返回的占位 id（{@code msg-stub-pending} 之类）；
     * 落库后真实 id 是另一条（{@code msg-xxxx}）。不回填的话 {@code GET /messages/{id}/citations}
     * 永远查不到 —— 前端「历史回放引用」正是按真实 id 查的（2026-11-13 用户实测右侧来源面板刷新后为空）。
     *
     * @param sessionId 会话 id（限定范围，防止跨会话误改）
     * @param fromId    写库时的占位 id
     * @param toId      落库后的真实 assistant 消息 id
     * @return 改写的行数（失败 ⇒ 0，⛔ 不抛）
     */
    public int reassignAssistantMessageId(String sessionId, String fromId, String toId) {
        if (sessionId == null || sessionId.isBlank()
            || fromId == null || fromId.isBlank()
            || toId == null || toId.isBlank()
            || fromId.equals(toId)) {
            return 0;
        }
        try {
            List<KbCitationRecord> rows = citationMapper.selectListByQuery(QueryWrapper.create()
                .where("session_id = ?", sessionId)
                .and("assistant_message_id = ?", fromId)
                .and("deleted = 0"));
            int n = 0;
            for (KbCitationRecord row : rows) {
                row.setAssistantMessageId(toId);
                citationMapper.update(row);
                n++;
            }
            log.info("[引用] 回填 assistant id session={} {} → {} 条数={}", sessionId, fromId, toId, n);
            return n;
        } catch (RuntimeException e) {
            log.warn("[引用] 回填 assistant id 失败 session={} {} → {} 原因={}",
                sessionId, fromId, toId, e.getMessage());
            return 0;
        }
    }

    /** 截断到快照上限（按码点，不切断代理对）。 */
    private static String truncate(String content) {
        if (content == null) {
            return null;
        }
        String s = content.strip();
        if (s.codePointCount(0, s.length()) <= SNIPPET_MAX_CHARS) {
            return s;
        }
        int end = s.offsetByCodePoints(0, SNIPPET_MAX_CHARS);
        return s.substring(0, end) + "…";
    }
}
