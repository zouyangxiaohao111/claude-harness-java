package com.nexusai.apis.branch;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.worktree.GitCommandRunner;
import com.nexusai.application.agent.worktree.WorktreeCreateResult;
import com.nexusai.application.agent.worktree.WorktreeService;
import com.nexusai.common.RequestContext;
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
 */
@RestController
@RequestMapping("/api/v1/branches")
public class BranchController {

    private static final Logger log = LoggerFactory.getLogger(BranchController.class);

    @Autowired
    private WorktreeService worktreeService;

    @GetMapping
    public List<Map<String, Object>> list() {
        log.info("[BranchController] list invoked");
        Path gitRoot = currentGitRoot();
        return listWorktrees(gitRoot);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        String slug = (String) req.get("slug");
        if (slug == null || slug.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "error", "missing required field: slug"));
        }
        Path gitRoot = currentGitRoot();
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
                                                       @RequestParam(defaultValue = "false") boolean discardChanges) {
        Path gitRoot = currentGitRoot();
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
    public ResponseEntity<Map<String, Object>> keep(@PathVariable String slug) {
        Path gitRoot = currentGitRoot();
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
     * 当前 git 仓库根目录 · 对齐 CC findCanonicalGitRoot(getCwd())（utils/worktree.ts:926/1061、
     * EnterWorktreeTool.ts:84）。cwd-align-ext：user.dir 硬编码 → 会话 cwd + 复用
     * {@link AutoMemPaths#findCanonicalGitRoot}；无 sessionId 回落 user.dir（方案 1，零行为变化）。
     */
    private static Path currentGitRoot() {
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        if (cwd == null || cwd.isBlank()) {
            cwd = System.getProperty("user.dir", ".");
        }
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