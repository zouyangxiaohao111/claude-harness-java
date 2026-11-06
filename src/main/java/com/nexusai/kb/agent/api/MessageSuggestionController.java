package com.nexusai.kb.agent.api;

import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回答后推荐问题 · 批次 W10 · 2 条（源侧
 * {@code GET|POST /sessions/{sid}/messages/{mid}/suggestions}）。
 *
 * <p><b>⭐ 语义</b>：用户看到一条回答后，系统给出「你可能还想问」的几个问题。
 * 源侧是「按需生成 + 缓存」：{@code GET} 只读缓存（没有就返回空），
 * {@code POST} 才真的生成。
 *
 * <p><b>⚠️ 本仓不调 LLM 生成</b>：这需要额外一次模型调用（花钱 + 慢），
 * 而本批的目标是「端点与缓存语义对齐」。⇒ {@code POST} 只创建**占位记录**并如实说明
 * {@code generated:false}，⛔ 不编造问题文本。
 *
 * <p><b>为什么内存存储</b>：推荐问题是一次性的辅助信息，丢了不影响任何业务事实；
 * 落表会让「消息删除/会话删除」都要加级联清理。源侧同样是内存。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/messages/{messageId}/suggestions")
public class MessageSuggestionController {

    private static final Logger log = LoggerFactory.getLogger(MessageSuggestionController.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** key = sessionId + '/' + messageId → 生成记录。 */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 确保生成推荐问题（源侧 {@code POST .../suggestions}）。
     *
     * <p>已存在则直接返回（幂等，对齐源侧「确保生成」语义）。
     *
     * @param sessionId 会话
     * @param messageId 消息
     * @return 状态（本仓 {@code generated:false}，原因见 note）
     */
    @PostMapping
    public Map<String, Object> ensure(@PathVariable String sessionId, @PathVariable String messageId) {
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(messageId, "messageId");
        String key = key(sessionId, messageId);
        Cached c = cache.computeIfAbsent(key, k -> new Cached(now()));
        log.info("[推荐问题] POST session={} message={}（本仓不调模型生成，如实返回空）",
            sessionId, messageId);
        return toDto(key, c);
    }

    /**
     * 读推荐问题（源侧 {@code GET .../suggestions}）。
     *
     * <p>没有生成过 ⇒ 返回空列表（不是 404 —— 「还没生成」是正常状态）。
     *
     * @param sessionId 会话
     * @param messageId 消息
     * @return 状态
     */
    @GetMapping
    public Map<String, Object> get(@PathVariable String sessionId, @PathVariable String messageId) {
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(messageId, "messageId");
        Cached c = cache.get(key(sessionId, messageId));
        if (c == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("session_id", sessionId);
            m.put("message_id", messageId);
            m.put("questions", List.of());
            m.put("generated", false);
            m.put("note", "尚未生成；POST 同一路径可创建记录");
            return m;
        }
        return toDto(key(sessionId, messageId), c);
    }

    // ── 内部 ───────────────────────────────────────────────

    private static String key(String sessionId, String messageId) {
        return sessionId + '/' + messageId;
    }

    private static void requireNonBlank(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ValidationException(field + " 不能为空");
        }
    }

    private static Map<String, Object> toDto(String key, Cached c) {
        String[] parts = key.split("/", 2);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", parts.length > 0 ? parts[0] : null);
        m.put("message_id", parts.length > 1 ? parts[1] : null);
        m.put("questions", new ArrayList<>(c.questions));
        m.put("generated", false);
        m.put("created_at", c.createdAt);
        // ⚠️ 如实说明：没有接生成链路，不是「生成失败」，是「本批不生成」
        m.put("note", "本仓未接入推荐问题生成链路（需额外 LLM 调用）；记录已创建，questions 恒为空");
        return m;
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    /** 缓存条目（不可变问题列表，当前恒空）。 */
    private static final class Cached {
        private final List<String> questions = List.of();
        private final String createdAt;

        private Cached(String createdAt) {
            this.createdAt = createdAt;
        }
    }
}
