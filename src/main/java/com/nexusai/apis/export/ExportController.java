package com.nexusai.apis.export;

import com.mybatisflex.core.query.QueryWrapper;
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
        List<MessageRecord> messages = messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", sessionId).orderBy("created_at"));
        String body = renderMarkdown(session, messages);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + sessionId + "." + format + "\"")
                .body(body);
    }

    @PostMapping("/{sessionId}/copy")
    public Map<String, Object> copy(@PathVariable String sessionId) {
        log.info("[ExportController] copy sessionId={}", sessionId);
        SessionDto session = sessionService.getById(sessionId);
        List<MessageRecord> messages = messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", sessionId).orderBy("created_at"));
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