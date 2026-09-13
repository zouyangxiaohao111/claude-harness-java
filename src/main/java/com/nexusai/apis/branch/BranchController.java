package com.nexusai.apis.branch;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.worktree.GitCommandRunner;
import com.nexusai.application.agent.worktree.WorktreeCreateResult;
import com.nexusai.application.agent.worktree.WorktreeService;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Branch REST 端点 · 对齐 CC /branch 命令.
 *
 * <p>FIX-CMD-1: 列出/创建/切换 worktree 分支.
 * <p>FIX-R10-2: 真调 {@link WorktreeService} 创建/删除/keep; 列出用 {@code git worktree list --porcelain}.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>GET /api/v1/branches - 列出所有 worktree 分支</li>
 *   <li>POST /api/v1/branches - 创建分支 (WorktreeService.createWorktree)</li>
 *   <li>DELETE /api/v1/branches/{slug} - 删除 worktree + 分支 (WorktreeService.removeWorktree)</li>
 *   <li>POST /api/v1/branches/{slug}/keep - 保留 worktree (WorktreeService.keepWorktree)</li>
 * </ul>
 *
 * <p><b>批 3a 会话态显式化</b>：四个端点均<b>必填</b> query {@code ?sessionId=}（决定 gitRoot 的
 * 会话 boundProject 锚）；缺 / 空白 ⇒ 400（{@link ValidationException}）。旧实现经裸 MDC 兜底，
 * 存在「读到上一请求残留的别的会话 id」的第三态 ⇒ 在错误仓库上操作 worktree。
 */
@RestController
@RequestMapping("/api/v1/branches")
public class BranchController {

    private static final Logger log = LoggerFactory.getLogger(BranchController.class);

    @Autowired
    private WorktreeService worktreeService;

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "sessionId", required = false) String sessionId) {
        requireSessionId(sessionId, "GET /api/v1/branches");
        log.info("[BranchController] list invoked sessionId={}", sessionId);
        Path gitRoot = currentGitRoot(sessionId);
        return listWorktrees(gitRoot);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestParam(value = "sessionId", required = false) String sessionId,
                                                      @RequestBody Map<String, Object> req) {
        requireSessionId(sessionId, "POST /api/v1/branches");
        String slug = (String) req.get("slug");
        if (slug == null || slug.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "error", "missing required field: slug"));
        }
        Path gitRoot = currentGitRoot(sessionId);
        log.info("[BranchController] create slug={} gitRoot={}", slug, gitRoot);
        try {
            WorktreeCreateResult r = worktreeService.createWorktree(gitRoot, slug);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("slug", slug);
            body.put("status", r instanceof WorktreeCreateResult.Resumed ? "resumed" : "created");
            body.put("branch", r.worktreeBranch());
            body.put("path", r.worktreePath().toString());
            body.put("gitRoot", r.gitRoot().toString());
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (IllegalArgumentException e) {
            log.warn("[BranchController] create validation failed slug={}: {}", slug, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "slug", slug,
                    "status", "error",
                    "error", e.getMessage()));
        } catch (WorktreeService.WorktreeException e) {
            log.warn("[BranchController] create failed slug={}: {}", slug, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "slug", slug,
                    "status", "fail",
                    "error", e.getMessage()));
        }
    }

    @DeleteMapping("/{slug}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String slug,
                                                       @RequestParam(value = "sessionId", required = false) String sessionId,
                                                       @RequestParam(defaultValue = "false") boolean discardChanges) {
        requireSessionId(sessionId, "DELETE /api/v1/branches/{slug}");
        Path gitRoot = currentGitRoot(sessionId);
        log.info("[BranchController] remove slug={} discardChanges={}", slug, discardChanges);
        try {
            worktreeService.removeWorktree(gitRoot, slug, discardChanges);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("slug", slug);
            body.put("status", "removed");
            body.put("discardChanges", discardChanges);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            log.warn("[BranchController] remove validation failed slug={}: {}", slug, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "slug", slug,
                    "status", "error",
                    "error", e.getMessage()));
        } catch (WorktreeService.WorktreeException e) {
            log.warn("[BranchController] remove failed slug={}: {}", slug, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "slug", slug,
                    "status", "fail",
                    "error", e.getMessage()));
        }
    }

    @PostMapping("/{slug}/keep")
    public ResponseEntity<Map<String, Object>> keep(@PathVariable String slug,
                                                    @RequestParam(value = "sessionId", required = false) String sessionId) {
        requireSessionId(sessionId, "POST /api/v1/branches/{slug}/keep");
        Path gitRoot = currentGitRoot(sessionId);
        log.info("[BranchController] keep slug={}", slug);
        try {
            worktreeService.keepWorktree(gitRoot, slug);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("slug", slug);
            body.put("status", "kept");
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            log.warn("[BranchController] keep validation failed slug={}: {}", slug, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "slug", slug,
                    "status", "error",
                    "error", e.getMessage()));
        }
    }

    /**
     * 批 3a · REST 入口会话标识必填（(a) 类 fail loud）：缺 / 空白 ⇒ 400。
     *
     * <p>WHY：旧实现经 裸 MDC 的 {@code sessionId()} 兜底，而 MDC 存在「读到上一个
     * 请求残留的、别的会话的 id」第三态 —— 看起来合法却指向错误会话的 boundProject ⇒ 在错误的仓库上
     * 建/删 worktree。改为显式 query 参数，缺值即拒（不再有任何兜底读取）。
     */
    private static void requireSessionId(String sessionId, String endpoint) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[BranchController] {} 缺少会话标识 ?sessionId= → 400（REST 入口会话态显式化，不再回落 MDC）",
                endpoint);
            throw new ValidationException("sessionId is required (" + endpoint + ")");
        }
    }

    /**
     * 当前 git 仓库根目录 · 对齐 CC findCanonicalGitRoot(getCwd())（utils/worktree.ts:926/1061、
     * EnterWorktreeTool.ts:84）。cwd-align-ext：user.dir 硬编码 → 会话 cwd + 复用
     * {@link AutoMemPaths#findCanonicalGitRoot}。
     *
     * <p>批 3a：sessionId 由调用端点显式传入（不再读 MDC）。{@link CwdResolution#getCwd(String)} 恒非
     * null（L4 user.dir 兜底，对齐 CC 进程启动 cwd），故不再需要本地的 user.dir 二次兜底。
     */
    private static Path currentGitRoot(String sessionId) {
        String cwd = CwdResolution.getCwd(sessionId);
        String canonical = AutoMemPaths.findCanonicalGitRoot(cwd);
        return Paths.get(canonical != null && !canonical.isBlank() ? canonical : cwd);
    }

    /**
     * 列出 git worktree. 用 {@code git worktree list --porcelain} 解析.
     */
    private List<Map<String, Object>> listWorktrees(Path gitRoot) {
        GitCommandRunner.Result r = GitCommandRunner.run(gitRoot, "worktree", "list", "--porcelain");
        if (!r.isSuccess()) {
            log.warn("[BranchController] git worktree list failed exit={} stderr={}",
                    r.exitCode(), r.stderr());
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        String[] lines = r.stdout().split("\n");
        Map<String, Object> current = null;
        for (String line : lines) {
            if (line.isBlank()) {
                if (current != null) {
                    result.add(current);
                    current = null;
                }
                continue;
            }
            if (current == null) {
                current = new LinkedHashMap<>();
            }
            if (line.startsWith("worktree ")) {
                current.put("path", line.substring("worktree ".length()).trim());
            } else if (line.startsWith("HEAD ")) {
                current.put("head", line.substring("HEAD ".length()).trim());
            } else if (line.startsWith("branch ")) {
                String branch = line.substring("branch ".length()).trim();
                if (branch.startsWith("refs/heads/")) {
                    branch = branch.substring("refs/heads/".length());
                }
                current.put("branch", branch);
            } else if (line.startsWith("detached")) {
                current.put("detached", true);
            }
        }
        if (current != null) {
            result.add(current);
        }
        log.info("[BranchController] listWorktrees returned {} entries", result.size());
        return result;
    }
}