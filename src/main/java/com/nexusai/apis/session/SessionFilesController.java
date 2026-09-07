package com.nexusai.apis.session;

import com.nexusai.application.session.SessionFilesRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话改动文件 REST（Phase 2 · spec 2026-09-07 阶段 2）。
 *
 * <p>数据源 = {@link SessionFilesRecorder}（agent Edit/Write 写盘捕获 + files.changed 推送同源）。
 * <ul>
 *   <li>{@code GET  /api/v1/sessions/{sessionId}/files}           本会话改动文件列表（前端对账/初始渲染）</li>
 *   <li>{@code GET  /api/v1/sessions/{sessionId}/files/diff?path=} 真实 diff（快照 vs 当前 · DiffFile 形状）</li>
 *   <li>{@code POST /api/v1/sessions/{sessionId}/files/revert}     回滚到本会话改动前（body: {path}）</li>
 * </ul>
 * 会话短 id（sess-xxx）经 @RequestMapping 路径变量透传 recorder（与 files.changed 主题键一致）。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/files")
public class SessionFilesController {

    private static final Logger log = LoggerFactory.getLogger(SessionFilesController.class);

    private final SessionFilesRecorder recorder;

    public SessionFilesController(SessionFilesRecorder recorder) {
        this.recorder = recorder;
    }

    /** 本会话改动文件列表（无记录 → 空数组 = 0 态）。 */
    @GetMapping
    public List<SessionFilesRecorder.ChangedFileDto> list(@PathVariable("sessionId") String sessionId) {
        return recorder.list(sessionId);
    }

    /** 真实 diff（前端 DiffModal 直用）。path 无记录/读盘失败 → 404。 */
    @GetMapping("/diff")
    public ResponseEntity<SessionFilesRecorder.DiffFileDto> diff(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "path", required = false) String path) {
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        SessionFilesRecorder.DiffFileDto d = recorder.diff(sessionId, path);
        if (d == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(d);
    }

    /** 回滚到本会话改动前（body: {"path": "..."}）。 */
    @PostMapping("/revert")
    public ResponseEntity<Map<String, Object>> revert(
            @PathVariable("sessionId") String sessionId,
            @RequestBody RevertRequest req) {
        if (req == null || req.path() == null || req.path().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        boolean ok = recorder.revert(sessionId, req.path());
        if (!ok) {
            log.warn("SessionFilesController: revert 无记录/失败 sessionId={} path={}", sessionId, req.path());
        }
        return ResponseEntity.ok(Map.of("success", ok));
    }

    /** revert 请求体。 */
    public record RevertRequest(String path) {
    }
}
