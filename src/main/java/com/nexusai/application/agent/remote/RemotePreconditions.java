package com.nexusai.application.agent.remote;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remote Preconditions · 对齐 CC utils/background/remote/preconditions.ts.
 *
 * <p>FIX-BG-PRECON: 简化版 remote 登录/git/remote/GitHub 前置检查.
 */
@Component
public class RemotePreconditions {

    public enum CheckResult { OK, MISSING_TOKEN, MISSING_GIT, INVALID_REMOTE, GH_AUTH_FAIL }

    private final Map<String, Boolean> ghAuthCache = new ConcurrentHashMap<>();

    public CheckResult checkGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            int rc = p.waitFor();
            return rc == 0 ? CheckResult.OK : CheckResult.MISSING_GIT;
        } catch (Exception e) {
            return CheckResult.MISSING_GIT;
        }
    }

    public CheckResult checkRemote(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return CheckResult.INVALID_REMOTE;
        if (!remoteUrl.startsWith("git@") && !remoteUrl.startsWith("https://")) {
            return CheckResult.INVALID_REMOTE;
        }
        return CheckResult.OK;
    }

    public CheckResult checkGitHubAuth() {
        Boolean cached = ghAuthCache.get("default");
        if (cached != null) {
            return cached ? CheckResult.OK : CheckResult.GH_AUTH_FAIL;
        }
        try {
            Process p = new ProcessBuilder("gh", "auth", "status").redirectErrorStream(true).start();
            int rc = p.waitFor();
            boolean ok = rc == 0;
            ghAuthCache.put("default", ok);
            return ok ? CheckResult.OK : CheckResult.GH_AUTH_FAIL;
        } catch (Exception e) {
            return CheckResult.GH_AUTH_FAIL;
        }
    }

    public void invalidateCache() {
        ghAuthCache.clear();
    }
}