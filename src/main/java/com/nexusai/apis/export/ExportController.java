package com.nexusai.apis.export;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Export REST 端点 · 对齐 CC /export, /copy, /share 命令.
 *
 * <p>FIX-CMD-2: 导出 session 为 markdown / 复制 session / 分享 session.
 * <p>FIX-R10-4: 真渲染 session markdown, 查 SessionService + MessageMapper.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>GET /api/v1/export/{sessionId}?format=md - 导出为 markdown (text/markdown)</li>
 *   <li>POST /api/v1/export/{sessionId}/copy - 复制 (返回 markdown 字符数)</li>
 *   <li>POST /api/v1/export/{sessionId}/share - 创建 shareable URL</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/export")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    @Autowired
    private SessionService sessionService;

    @Autowired
    private MessageMapper messageMapper;

    @GetMapping(value = "/{sessionId}", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public ResponseEntity<String> export(@PathVariable String sessionId,
                                          @RequestParam(defaultValue = "md") String format) {
        log.info("[ExportController] export sessionId={} format={}", sessionId, format);
        SessionDto session = sessionService.getById(sessionId);
        // [seq NULL 兜底] 位置序 = seq；ASC 侧**必须** NULLS LAST，不能裸 seq ASC：
        //   裸 seq ASC 下 SQLite 视 NULL 为最小 → 存量 seq 为 NULL 的行（V71 触发器之前写入 / 未升级的库）
        //   会冒充「会话首条」被排到导出正文最前 → 导出的 Markdown 以一条位置未知的脏行开头。
        //   语义口径：ASC 侧 NULL 排**末尾**（位置未知，一律不冒充「会话首条」）。
        //   片段自带方向（SQLite 文法要求方向在 NULLS LAST 之前），故不得再叠加 .orderBy("seq", ...)；
        //   排序键仍是索引列 seq → 查询计划与裸 seq 完全相同（WHY 见 MessageService.SEQ_ASC_NULLS_LAST_ORDER）。
        List<MessageRecord> messages = messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", sessionId)
                    .orderByUnSafely(MessageService.SEQ_ASC_NULLS_LAST_ORDER));
        String body = renderMarkdown(session, messages);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + sessionId + "." + format + "\"")
                .body(body);
    }

    @PostMapping("/{sessionId}/copy")
    public Map<String, Object> copy(@PathVariable String sessionId) {
        log.info("[ExportController] copy sessionId={}", sessionId);
        SessionDto session = sessionService.getById(sessionId);
        // [seq NULL 兜底] 位置序 = seq；ASC 侧**必须** NULLS LAST，不能裸 seq ASC：
        //   裸 seq ASC 下 SQLite 视 NULL 为最小 → 存量 seq 为 NULL 的行（V71 触发器之前写入 / 未升级的库）
        //   会冒充「会话首条」被排到导出正文最前 → 导出的 Markdown 以一条位置未知的脏行开头。
        //   语义口径：ASC 侧 NULL 排**末尾**（位置未知，一律不冒充「会话首条」）。
        //   片段自带方向（SQLite 文法要求方向在 NULLS LAST 之前），故不得再叠加 .orderBy("seq", ...)；
        //   排序键仍是索引列 seq → 查询计划与裸 seq 完全相同（WHY 见 MessageService.SEQ_ASC_NULLS_LAST_ORDER）。
        List<MessageRecord> messages = messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", sessionId)
                    .orderByUnSafely(MessageService.SEQ_ASC_NULLS_LAST_ORDER));
        String md = renderMarkdown(session, messages);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("action", "copied");
        body.put("chars", md.length());
        body.put("messages", messages.size());
        return body;
    }

    @PostMapping("/{sessionId}/share")
    public Map<String, Object> share(@PathVariable String sessionId) {
        log.info("[ExportController] share sessionId={}", sessionId);
        sessionService.getById(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("shareUrl", "https://share.nexusai.com/s/" + sessionId);
        body.put("expiresIn", "7d");
        return body;
    }

    /**
     * 渲染 session markdown. 格式:
     * <pre>
     * # {title}
     *
     * **Model**: {modelName}
     * **Created**: {createdAt}
     *
     * ---
     *
     * ## User
     * {content}
     *
     * ## Assistant
     * {content}
     *
     * ## Tool: {toolName}
     * Result: {result}
     * </pre>
     */
    private static String renderMarkdown(SessionDto session, List<MessageRecord> messages) {
        StringBuilder sb = new StringBuilder();
        String title = session.title() != null && !session.title().isBlank() ? session.title() : "Session " + session.id();
        sb.append("# ").append(title).append("\n\n");

        if (session.modelName() != null && !session.modelName().isBlank()) {
            sb.append("**Model**: ").append(session.modelName()).append("\n");
        }
        if (session.createdAt() != null) {
            sb.append("**Created**: ").append(session.createdAt().toString()).append("\n");
        }
        if (session.model() != null) {
            sb.append("**Tag**: ").append(session.model().name()).append("\n");
        }
        sb.append("**Messages**: ").append(messages.size()).append("\n\n");
        sb.append("---\n\n");

        for (MessageRecord m : messages) {
            String role = m.getRole() != null ? m.getRole() : "unknown";
            String content = m.getContent() != null ? m.getContent() : "";
            // [P2-21 2026-09-12] 两标记列接线 · 对齐 CC 导出的两类渲染语义
            //   （commands/export/export.tsx → utils/exportRenderer.tsx 以
            //   {@code screen="prompt"} 渲染 <Messages> → messages.ts:500 isTranscriptMode=false）。
            //   ② isCompactSummary=true 的 user 消息：Message.tsx:141 走 CompactSummary 专用渲染
            //      （不复用普通用户气泡）→ 本处给独立小节标题，不再当 '## User' 导出。
            //   ① isVisibleInTranscriptOnly=true 的 <b>user</b> 消息：
            //      shouldShowUserMessage(msg, false) 命中 messages.ts:5115 `!isTranscriptMode → return false`
            //      → 该消息不渲染 → 本处跳过（CC 该过滤仅作用于 type==='user'）。
            //   ⚠️ [P2-21 裁决 b 2026-09-12 · <b>有意偏离 CC 的判定顺序</b>]
            //      CC 是「先 ① 后 ②」：full-compact 摘要**同时带两标志**（compact.ts:643-650）时先命中 ①
            //      → 导出里**整条不出现**，压缩摘要正文丢失。.md 是**归档件**（无实时 UI 兜底），
            //      丢摘要等于丢压缩上下文 ⇒ 用户裁定改为「先 ② 后 ①」：带 isCompactSummary 的行
            //      一律走摘要小节（保住正文），仅"只带 transcript-only、不带摘要"的行才跳过。
            //      差异仅出现在「两标志并存」这一种行（即 full/SM compact 摘要），partial（kept 非空，
            //      compact.ts:1068-1077 只带 isCompactSummary）两种顺序下行为本来一致。
            if ("user".equals(role) && Boolean.TRUE.equals(m.getIsCompactSummary())) {
                sb.append("## Compact Summary\n\n");
                sb.append(content).append("\n\n");
                continue;
            }
            if ("user".equals(role) && Boolean.TRUE.equals(m.getIsVisibleInTranscriptOnly())) {
                continue;
            }
            switch (role) {
                case "user" -> {
                    sb.append("## User\n\n");
                    sb.append(content).append("\n\n");
                }
                case "assistant" -> {
                    sb.append("## Assistant\n\n");
                    sb.append(content).append("\n\n");
                    if (m.getReasoning() != null && !m.getReasoning().isBlank()) {
                        sb.append("> **Reasoning**: ").append(m.getReasoning()).append("\n\n");
                    }
                }
                case "system" -> {
                    sb.append("## System\n\n");
                    sb.append(content).append("\n\n");
                }
                case "tool" -> {
                    String toolName = m.getAuthor() != null && !m.getAuthor().isBlank() ? m.getAuthor() : "tool";
                    sb.append("## Tool: ").append(toolName).append("\n\n");
                    sb.append("Result: ").append(content).append("\n\n");
                }
                default -> {
                    sb.append("## ").append(capitalize(role)).append("\n\n");
                    sb.append(content).append("\n\n");
                }
            }
        }

        sb.append("---\n\n");
        sb.append("_Exported from NexusAI Backend · ").append(OffsetDateTime.now().toString()).append("_\n");
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}