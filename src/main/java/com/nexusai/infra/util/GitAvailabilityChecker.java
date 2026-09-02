package com.nexusai.infra.util;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * GitAvailabilityChecker · 对齐 CC utils/plugins/gitAvailability.ts.
 *
 * <p>L1 语义: 检查 git 是否在 PATH 中可用;会话级缓存结果;失败后允许强制设 false。
 * 用于 GitHub marketplace 安装前置条件 (CC 注释: bash 跳过 marketplace)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: isCommandAvailable(command)→Boolean + checkGitAvailable()→Boolean (cached) + markGitUnavailable() (force false) + clearCache (test-only)</li>
 *   <li><b>A2 Golden Trace</b>: 首次 check 调 isCommandAvailable → true/false;后续读 cache;markGitUnavailable → clear cache + 强制 false</li>
 *   <li><b>A3 不可变外</b>: 内部 AtomicReference cache,可重置 (clear)</li>
 *   <li><b>A4 边界</b>: null command → false;Supplier throws → false</li>
 *   <li><b>A5 业务场景</b>: GitHub marketplace 安装前 checkGitAvailable → false → 跳过 (用户未装 git)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS lodash memoize → Java AtomicReference cache;
 * TS which → 注入式 Supplier&lt;String&gt; (caller-wired to actual path check);
 * TS cache.set(undefined, false) → Java forceUnavailable setter。
 */
public final class GitAvailabilityChecker {

    private final Supplier<String> whichFn;
    private final AtomicReference<Boolean> cache = new AtomicReference<>(null);
    private final AtomicReference<Boolean> forcedUnavailable = new AtomicReference<>(false);

    public GitAvailabilityChecker(Supplier<String> whichFn) {
        this.whichFn = whichFn;
    }

    /** Returns true if {@code command} resolves to an executable via {@code whichFn}. */
    public boolean isCommandAvailable(String command) {
        if (command == null || command.isEmpty()) return false;
        try {
            String path = whichFn.get();
            return path != null && !path.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Returns the cached git-availability result, computing on first call. */
    public boolean checkGitAvailable() {
        Boolean forced = forcedUnavailable.get();
        if (Boolean.TRUE.equals(forced)) return false;
        Boolean cached = cache.get();
        if (cached != null) return cached;
        Boolean computed = isCommandAvailable("git");
        cache.set(computed);
        return computed;
    }

    /**
     * Force subsequent {@link #checkGitAvailable()} calls to return false.
     * Mirrors CC markGitUnavailable (used when 'xcrun: error' surfaces).
     */
    public void markGitUnavailable() {
        forcedUnavailable.set(true);
        cache.set(false);
    }

    /** Test-only: clear both cache and forced flag. */
    public void clearCache() {
        cache.set(null);
        forcedUnavailable.set(false);
    }
}
