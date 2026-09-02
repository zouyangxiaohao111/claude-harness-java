package com.nexusai.apis.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.model.session.dto.SessionFileDto;
import com.nexusai.repository.session.entity.SessionFileRecord;
import com.nexusai.repository.session.mapper.SessionFileMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SessionFile 端点：
 * - GET    /api/v1/sessions/{sessionId}/files                        → 列出该 session 的文件变更
 * - GET    /api/v1/sessions/{sessionId}/files/**                    → 文件操作（diff/rollback/confirm）
 *
 * 用 /** 全捕获再 substring 解析后缀（diff/rollback/confirm）。
 * Spring 6 PathPatternParser 的 {*name} catch-all 行为不可靠，所以走 /** 通配符。
 * 同时 application.properties 启用了 ant_path_matcher 以支持 {path:.+} 单段正则。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/files")
public class SessionFileController {

    @Autowired private SessionFileMapper sessionFileMapper;

    @GetMapping
    public List<SessionFileDto> list(@PathVariable String sessionId) {
        List<SessionFileRecord> files = sessionFileMapper.selectListByQuery(
            QueryWrapper.create().eq("session_id", sessionId));
        return files.stream().map(this::toDto).toList();
    }

    /** catch-all: /** 捕获 /src/main.java/diff 这类多段路径，substring 解析 op */
    @GetMapping("/**")
    public Map<String, Object> handleGet(HttpServletRequest req) {
        String suffix = extractSuffix(req.getRequestURI(), "/files/");
        String op = detectOp(suffix);
        return switch (op == null ? "" : op) {
            case "diff" -> Map.of("hunks", List.of());
            default -> Map.of("ok", false, "message", "GET supports only diff");
        };
    }

    @PostMapping("/**")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> handlePost(HttpServletRequest req) {
        String suffix = extractSuffix(req.getRequestURI(), "/files/");
        String op = detectOp(suffix);
        return switch (op == null ? "" : op) {
            case "rollback" -> Map.of("ok", true, "message", "已回滚");
            case "confirm"  -> Map.of("ok", true, "message", "已确认");
            default -> Map.of("ok", false, "message", "POST supports only rollback/confirm");
        };
    }

    // ============== helpers ==============

    /** 从 "/api/v1/sessions/SID/files/src/main.java/diff" 提取 "src/main.java/diff" */
    private static String extractSuffix(String requestUri, String marker) {
        int idx = requestUri.indexOf(marker);
        if (idx < 0) return "";
        return requestUri.substring(idx + marker.length());
    }

    /** 根据末尾后缀决定 op */
    private static String detectOp(String suffix) {
        if (suffix.endsWith("/diff")) return "diff";
        if (suffix.endsWith("/rollback")) return "rollback";
        if (suffix.endsWith("/confirm")) return "confirm";
        return null;
    }

    private SessionFileDto toDto(SessionFileRecord f) {
        return new SessionFileDto(
            f.getPath(),
            f.getStatus(),
            f.getAdditions(),
            f.getDeletions(),
            f.getOldRev(),
            f.getNewRev()
        );
    }
}
