package com.nexusai.application.agent.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Marketplace git 同步服务 · 对齐 CC {@code utils/plugins/marketplaceManager.ts} L2 同步层.
 *
 * <p><b>CC 对应</b>：
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #gitClone}</td><td>{@code gitClone}</td><td>marketplaceManager.ts:803-985</td></tr>
 *   <tr><td>{@link #gitPull}</td><td>{@code gitPull}</td><td>marketplaceManager.ts:528-582</td></tr>
 *   <tr><td>{@link #gitSubmoduleUpdate}</td><td>{@code gitSubmoduleUpdate}</td><td>marketplaceManager.ts:609-644</td></tr>
 *   <tr><td>{@link #reconcileSparseCheckout}</td><td>{@code reconcileSparseCheckout}</td><td>marketplaceManager.ts:1034-1061</td></tr>
 *   <tr><td>{@link #cacheMarketplaceFromGit}</td><td>{@code cacheMarketplaceFromGit}</td><td>marketplaceManager.ts:1084-1177</td></tr>
 *   <tr><td>{@link #isGitHubSshLikelyConfigured}</td><td>{@code isGitHubSshLikelyConfigured}</td><td>marketplaceManager.ts:723-761</td></tr>
 * </table>
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>所有 git 命令经 {@link GitProcessRunner}（args 数组 + cwd + env + timeout + destroyForcibly），
 *       绝不走 shell 字符串；</li>
 *   <li>pull 失败 → rm + reclone 兜底；clone 失败 → force rm 后抛（对齐 CC :1130-1176）；</li>
 *   <li>非零退出不抛（返回 {@link GitResult}），仅 cacheMarketplaceFromGit 层在 rm/clone 兜底耗尽后抛。</li>
 * </ul>
 */
public class MarketplaceSyncService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceSyncService.class);

    /** 命令结果 · CC gitPull/gitClone 返回值 {@code { code, stderr }}（:532/:807）. */
    public record GitResult(int code, String stderr) {
        public boolean ok() {
            return code == 0;
        }
    }

    /** SSH 探测注入点（测试 mock 网络探测；生产默认经 GitProcessRunner 真跑）。 */
    public interface SshProbe {
        boolean isConfigured();
    }

    private final GitProcessRunner runner;
    private final SshProbe sshProbe;

    public MarketplaceSyncService() {
        this(new GitProcessRunner(), null);
    }

    public MarketplaceSyncService(GitProcessRunner runner, SshProbe sshProbe) {
        this.runner = runner;
        this.sshProbe = sshProbe;
    }

    // ── git clone ─────────────────────────────────────────────────────────

    /**
     * git clone · CC gitClone（:803-985）。
     *
     * <p>args 顺序精确（:810-832）：
     * {@code -c core.sshCommand=ssh -o BatchMode=yes -o StrictHostKeyChecking=yes clone --depth 1}
     * + sparse {@code --filter=blob:none --no-checkout} / 全量 {@code --recurse-submodules --shallow-submodules}
     * + ref {@code --branch ref} + {@code gitUrl targetPath}。
     *
     * <p>clone 成功后 sparse → {@code sparse-checkout set --cone -- paths} + {@code checkout HEAD}
     * （:861-895，ref 已由 --branch 应用，checkout HEAD 物化默认/目标分支）。
     */
    public GitResult gitClone(String gitUrl, String targetPath, String ref, List<String> sparsePaths) {
        boolean useSparse = sparsePaths != null && !sparsePaths.isEmpty();
        List<String> args = new ArrayList<>();
        args.add("-c");
        args.add("core.sshCommand=ssh -o BatchMode=yes -o StrictHostKeyChecking=yes");
        args.add("clone");
        args.add("--depth");
        args.add("1");

        if (useSparse) {
            // Partial clone: skip blob download until checkout, defer checkout until
            // after sparse-checkout is configured (CC :818-823).
            args.add("--filter=blob:none");
            args.add("--no-checkout");
        } else {
            args.add("--recurse-submodules");
            args.add("--shallow-submodules");
        }

        if (ref != null && !ref.isBlank()) {
            args.add("--branch");
            args.add(ref);
        }

        args.add(gitUrl);
        args.add(targetPath);

        long timeoutMs = GitProcessRunner.getPluginGitTimeoutMs();
        if (log.isDebugEnabled()) {
            log.debug("git clone：url={} ref={} timeout={}ms",
                GitProcessRunner.redactUrlCredentials(gitUrl), ref == null ? "default" : ref, timeoutMs);
        }

        Map<String, String> env = GitProcessRunner.gitNoPromptEnv();
        GitProcessRunner.Result result = runner.run(args, null, env, timeoutMs);

        // 凭据脱敏：CC :845-854 execa error/stderr 可能内嵌带凭据 URL
        String redactedUrl = GitProcessRunner.redactUrlCredentials(gitUrl);
        String stderr = scrub(result.stderr(), gitUrl, redactedUrl);
        if (result.ok()) {
            if (useSparse) {
                GitProcessRunner.Result sparse = runner.run(
                    sparseSetArgs(sparsePaths), targetPath, env, timeoutMs);
                if (!sparse.ok()) {
                    return new GitResult(sparse.exitCode(),
                        "git sparse-checkout set failed: " + sparse.stderr());
                }
                GitProcessRunner.Result checkout = runner.run(
                    List.of("checkout", "HEAD"), targetPath, env, timeoutMs);
                if (!checkout.ok()) {
                    return new GitResult(checkout.exitCode(),
                        "git checkout after sparse-checkout failed: " + checkout.stderr());
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("git clone 成功：{}", redactedUrl);
            }
            return new GitResult(0, "");
        }

        if (log.isDebugEnabled()) {
            log.debug("git clone 失败：url={} code={} stderr={}", redactedUrl, result.exitCode(), stderr);
        }
        return enhanceGitCloneErrorMessages(result, stderr, timeoutMs, gitUrl);
    }

    /** sparse-checkout set 参数 · CC :863 {@code sparse-checkout set --cone -- ...paths}. */
    private static List<String> sparseSetArgs(List<String> sparsePaths) {
        List<String> args = new ArrayList<>();
        args.add("sparse-checkout");
        args.add("set");
        args.add("--cone");
        args.add("--");
        args.addAll(sparsePaths);
        return args;
    }

    /**
     * 增强 clone 错误提示 · CC gitClone 失败分支（:906-984）。
     * timeout（error 字段）/ SSH host key / 认证失败 / 网络 分场景给指引。
     */
    private GitResult enhanceGitCloneErrorMessages(
        GitProcessRunner.Result result, String stderr, long timeoutMs, String gitUrl) {
        String error = result.errorOrBlank();
        if (error.contains("timed out")) {
            return new GitResult(result.exitCode(),
                "Git clone timed out after " + Math.round(timeoutMs / 1000.0)
                    + "s. The repository may be too large for the current timeout. "
                    + "Set CLAUDE_CODE_PLUGIN_GIT_TIMEOUT_MS to increase it "
                    + "(e.g., 300000 for 5 minutes).\n\nOriginal error: " + stderr);
        }
        if (stderr != null) {
            if (stderr.contains("REMOTE HOST IDENTIFICATION HAS CHANGED")) {
                String host = extractSshHost(gitUrl);
                String hint = host != null ? "ssh-keygen -R " + host : "ssh-keygen -R <host>";
                return new GitResult(result.exitCode(),
                    "SSH host key has changed (server key rotation or possible MITM). "
                        + "Remove the stale known_hosts entry:\n  " + hint
                        + "\nThen connect once manually to verify and accept the new key."
                        + "\n\nOriginal error: " + stderr);
            }
            if (stderr.contains("Host key verification failed")) {
                return new GitResult(result.exitCode(),
                    "SSH host key is not in your known_hosts file. To add it, connect once manually "
                        + "(this will show the fingerprint for you to verify).\n\n"
                        + "Or use an HTTPS URL instead (recommended for public repos)."
                        + "\n\nOriginal error: " + stderr);
            }
            if (stderr.contains("Permission denied (publickey)")
                || stderr.contains("Could not read from remote repository")) {
                return new GitResult(result.exitCode(),
                    "SSH authentication failed. Please ensure your SSH keys are configured for GitHub, "
                        + "or use an HTTPS URL instead.\n\nOriginal error: " + stderr);
            }
            if (isAuthenticationError(stderr)) {
                return new GitResult(result.exitCode(),
                    "HTTPS authentication failed. Please ensure your credential helper is configured "
                        + "(e.g., gh auth login).\n\nOriginal error: " + stderr);
            }
            if (stderr.contains("timed out") || stderr.contains("timeout")
                || stderr.contains("Could not resolve host")) {
                return new GitResult(result.exitCode(),
                    "Network error or timeout while cloning repository. "
                        + "Please check your internet connection and try again.\n\nOriginal error: " + stderr);
            }
        }
        if (stderr == null || stderr.isEmpty()) {
            // gh-28373：git 可能失败但无 stderr（stdout / signal / 凭据助手吞输出）
            return new GitResult(result.exitCode(),
                error != null && !error.isEmpty()
                    ? error
                    : "git clone exited with code " + result.exitCode()
                        + " (no stderr output). Run with --debug to see the full command.");
        }
        return new GitResult(result.exitCode(), stderr);
    }

    /** CC isAuthenticationError（:767-775）. */
    private static boolean isAuthenticationError(String stderr) {
        return stderr.contains("Authentication failed")
            || stderr.contains("could not read Username")
            || stderr.contains("terminal prompts disabled")
            || stderr.contains("403")
            || stderr.contains("401");
    }

    /** CC extractSshHost（:781-784）{@code user@host:path}. */
    private static String extractSshHost(String gitUrl) {
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("^[^@]+@([^:]+):").matcher(gitUrl);
        return m.find() ? m.group(1) : null;
    }

    // ── git pull ──────────────────────────────────────────────────────────

    /**
     * git pull · CC gitPull（:528-582）。
     *
     * <p>ref 存在 → fetch origin ref → checkout ref → pull origin ref（:539-569）；
     * 否则 → pull origin HEAD（:572-576）；成功后 gitSubmoduleUpdate（sparse 跳过）。
     *
     * @param disableCredentialHelper 追加 {@code -c credential.helper=}（CC :535-537）
     */
    public GitResult gitPull(String cwd, String ref, boolean disableCredentialHelper,
                             List<String> sparsePaths) {
        if (log.isDebugEnabled()) {
            log.debug("git pull：cwd={} ref={}", cwd, ref == null ? "default" : ref);
        }
        Map<String, String> env = GitProcessRunner.gitNoPromptEnv();
        long timeoutMs = GitProcessRunner.getPluginGitTimeoutMs();
        List<String> credentialArgs = disableCredentialHelper
            ? List.of("-c", "credential.helper=") : List.of();

        if (ref != null && !ref.isBlank()) {
            GitProcessRunner.Result fetch = runner.run(
                concat(credentialArgs, List.of("fetch", "origin", ref)), cwd, env, timeoutMs);
            if (!fetch.ok()) {
                return enhanceGitPullErrorMessages(fetch);
            }
            GitProcessRunner.Result checkout = runner.run(
                concat(credentialArgs, List.of("checkout", ref)), cwd, env, timeoutMs);
            if (!checkout.ok()) {
                return enhanceGitPullErrorMessages(checkout);
            }
            GitProcessRunner.Result pull = runner.run(
                concat(credentialArgs, List.of("pull", "origin", ref)), cwd, env, timeoutMs);
            if (!pull.ok()) {
                return enhanceGitPullErrorMessages(pull);
            }
            gitSubmoduleUpdate(cwd, credentialArgs, env, sparsePaths);
            return new GitResult(0, "");
        }

        GitProcessRunner.Result result = runner.run(
            concat(credentialArgs, List.of("pull", "origin", "HEAD")), cwd, env, timeoutMs);
        if (!result.ok()) {
            return enhanceGitPullErrorMessages(result);
        }
        gitSubmoduleUpdate(cwd, credentialArgs, env, sparsePaths);
        return new GitResult(0, "");
    }

    /**
     * git submodule update（非致命）· CC gitSubmoduleUpdate（:609-644）。
     * sparse clone 跳过（CC :615）；.gitmodules 不存在跳过（CC :616-622）；
     * 失败仅 warn（CC :638-643）。
     */
    void gitSubmoduleUpdate(String cwd, List<String> credentialArgs, Map<String, String> env,
                            List<String> sparsePaths) {
        if (sparsePaths != null && !sparsePaths.isEmpty()) {
            return;
        }
        if (!Files.exists(Paths.get(cwd, ".gitmodules"))) {
            return;
        }
        List<String> args = new ArrayList<>();
        args.add("-c");
        args.add("core.sshCommand=ssh -o BatchMode=yes -o StrictHostKeyChecking=yes");
        args.addAll(credentialArgs);
        args.add("submodule");
        args.add("update");
        args.add("--init");
        args.add("--recursive");
        args.add("--depth");
        args.add("1");
        GitProcessRunner.Result result = runner.run(args, cwd, env);
        if (!result.ok()) {
            log.warn("git submodule update failed (non-fatal): {}", result.stderr());
        }
    }

    /**
     * 增强 pull 错误提示 · CC enhanceGitPullErrorMessages（:649-709）。
     * timeout（error 字段含 timed out）/ SSH host key 变更 / 未信任 / 认证 / 网络。
     */
    private GitResult enhanceGitPullErrorMessages(GitProcessRunner.Result result) {
        if (result.ok()) {
            return new GitResult(0, "");
        }
        String error = result.errorOrBlank();
        String stderr = result.stderr();
        if (error.contains("timed out")) {
            long timeoutSec = Math.round(GitProcessRunner.getPluginGitTimeoutMs() / 1000.0);
            return new GitResult(result.exitCode(),
                "Git pull timed out after " + timeoutSec
                    + "s. Try increasing the timeout via CLAUDE_CODE_PLUGIN_GIT_TIMEOUT_MS "
                    + "environment variable.\n\nOriginal error: " + stderr);
        }
        if (stderr != null) {
            if (stderr.contains("REMOTE HOST IDENTIFICATION HAS CHANGED")) {
                return new GitResult(result.exitCode(),
                    "SSH host key for this marketplace's git host has changed (server key rotation "
                        + "or possible MITM). Remove the stale entry with: ssh-keygen -R <host>\n"
                        + "Then connect once manually to accept the new key.\n\nOriginal error: " + stderr);
            }
            if (stderr.contains("Host key verification failed")) {
                return new GitResult(result.exitCode(),
                    "SSH host key verification failed while updating marketplace. "
                        + "The host key is not in your known_hosts file. Connect once manually to add it "
                        + "(e.g., ssh -T git@<host>), or remove and re-add the marketplace with an HTTPS URL."
                        + "\n\nOriginal error: " + stderr);
            }
            if (stderr.contains("Permission denied (publickey)")
                || stderr.contains("Could not read from remote repository")) {
                return new GitResult(result.exitCode(),
                    "SSH authentication failed while updating marketplace. "
                        + "Please ensure your SSH keys are configured.\n\nOriginal error: " + stderr);
            }
            if (stderr.contains("timed out") || stderr.contains("Could not resolve host")) {
                return new GitResult(result.exitCode(),
                    "Network error while updating marketplace. "
                        + "Please check your internet connection.\n\nOriginal error: " + stderr);
            }
        }
        return new GitResult(result.exitCode(), stderr == null ? "" : stderr);
    }

    // ── sparse reconcile ──────────────────────────────────────────────────

    /**
     * 对齐磁盘 sparse-checkout 状态 · CC reconcileSparseCheckout（:1034-1061）。
     *
     * <p>sparsePaths 非空 → {@code sparse-checkout set --cone -- paths}（幂等）；
     * 否则查 {@code config --get core.sparseCheckout}，为 true 返 code 1（触发 rm+reclone，
     * 避免 {@code --filter=blob:none} partial clone 上 disable 导致的整仓 lazy fetch，CC :1026-1028）。
     * 失败（ENOENT / 非仓库）无害——gitPull 也会失败走 clone 路径。
     */
    public GitResult reconcileSparseCheckout(String cwd, List<String> sparsePaths) {
        Map<String, String> env = GitProcessRunner.gitNoPromptEnv();
        if (sparsePaths != null && !sparsePaths.isEmpty()) {
            return toGitResult(runner.run(sparseSetArgs(sparsePaths), cwd, env));
        }
        GitProcessRunner.Result check = runner.run(
            List.of("config", "--get", "core.sparseCheckout"), cwd, env);
        if (check.ok() && "true".equals(check.stdout().trim())) {
            return new GitResult(1,
                "sparsePaths removed from config but repository is sparse; re-cloning for full checkout");
        }
        return new GitResult(0, "");
    }

    // ── cacheMarketplaceFromGit ───────────────────────────────────────────

    /**
     * 从 git 仓库缓存 marketplace · CC cacheMarketplaceFromGit（:1084-1177）。
     *
     * <p>先 reconcile sparse（失败即跳 pull 走 rm+clone）；reconcile 成功 → gitPull，成功即返回。
     * 否则 rm（非 ENOENT 失败抛指引错误）→ gitClone（失败 force rm + 抛）。pull-first
     * 规避 stat-before-operate TOCTOU（CC :1094-1096）。
     *
     * @throws IOException rm/clone 兜底失败（带指引，不静默）
     */
    public void cacheMarketplaceFromGit(String gitUrl, String cachePath, String ref,
                                        List<String> sparsePaths,
                                        boolean disableCredentialHelper) throws IOException {
        GitResult reconcile = reconcileSparseCheckout(cachePath, sparsePaths);
        if (reconcile.ok()) {
            GitResult pull = gitPull(cachePath, ref, disableCredentialHelper, sparsePaths);
            if (pull.ok()) {
                return;
            }
            log.warn("git pull failed, will re-clone: {}", pull.stderr());
        } else {
            if (log.isDebugEnabled()) {
                log.debug("sparse-checkout reconcile requires re-clone: {}", reconcile.stderr());
            }
        }

        try {
            Files.walk(Paths.get(cachePath))
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
        } catch (NoSuchFileException e) {
            // ENOENT —— 全新安装，无需清理
        } catch (IOException rmError) {
            throw new IOException(
                "Failed to clean up existing marketplace directory. Please manually delete "
                    + "the directory at " + cachePath + " and try again.\n\nTechnical details: "
                    + rmError.getMessage(), rmError);
        }

        GitResult clone = gitClone(gitUrl, cachePath, ref, sparsePaths);
        if (!clone.ok()) {
            // clone 失败清理部分目录（best-effort），下次调用自动检测
            try {
                Path cp = Paths.get(cachePath);
                if (Files.exists(cp)) {
                    Files.walk(cp).sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
                }
            } catch (Exception ignored) {
                // best-effort
            }
            throw new IOException("Failed to clone marketplace repository: " + clone.stderr());
        }
    }

    // ── SSH 探测 ──────────────────────────────────────────────────────────

    /**
     * 判断 GitHub SSH 是否可用 · CC isGitHubSshLikelyConfigured（:723-761）。
     *
     * <p>{@code ssh -T -o BatchMode=yes -o ConnectTimeout=2 -o StrictHostKeyChecking=yes git@github.com}
     * 3s 超时；configured = code==1 && stderr/stdout 含 'successfully authenticated'
     * （github 对已验证连接固定返回 exit 1，CC :744-749）。strict 检查使未知 host 快速失败走 HTTPS。
     */
    public boolean isGitHubSshLikelyConfigured() {
        if (sshProbe != null) {
            return sshProbe.isConfigured();
        }
        List<String> args = List.of(
            "ssh",
            "-T",
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=2",
            "-o",
            "StrictHostKeyChecking=yes",
            "git@github.com");
        GitProcessRunner.Result result = runner.run(args, null, null, 3000);
        String both = (result.stdout() == null ? "" : result.stdout())
            + (result.stderr() == null ? "" : result.stderr());
        boolean configured =
            result.exitCode() == 1 && both.contains("successfully authenticated");
        if (log.isDebugEnabled()) {
            log.debug("SSH config check: code={} configured={}", result.exitCode(), configured);
        }
        return configured;
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    private static GitResult toGitResult(GitProcessRunner.Result r) {
        return new GitResult(r.exitCode(), r.stderr() == null ? "" : r.stderr());
    }

    private static String scrub(String s, String url, String redactedUrl) {
        if (s == null) {
            return "";
        }
        return url.equals(redactedUrl) ? s : s.replace(url, redactedUrl);
    }
}
