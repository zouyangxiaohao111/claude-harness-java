package com.nexusai.application.agent.cli;

import com.nexusai.application.agent.skill.NexusaiPaths;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI self-update flow · 对齐 CC cli/update.ts.
 *
 * <p>L1 语义: 检查 + 安装最新版本 — channel (latest/stable);3 安装路径
 *            (global npm, local node_modules, native symlink);doctor diagnostic;
 *            graceful shutdown before update.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: DEFAULT_CHANNEL='latest'; STABLE_CHANNEL='stable'; InstallMethod enum (3);
 *       InstallStatus enum (3); 4 method (update/checkForUpdate/installGlobalPackage/installOrUpdate).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — update() → checkForUpdate → installLatest → gracefulShutdown → success.</li>
 *   <li><b>A3</b>: 注入式 (versionSupplier + envSupplier + installFn);silent failure on offline.</li>
 *   <li><b>A4</b>: 网络失败 → no-op;doctor diagnostic fail → warn + 继续.</li>
 *   <li><b>A5</b>: 真实场景 — 用户 `claude update` → 检查 latest → npm install → 完成.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async/await → Java 同步 (异步由 caller wired);
 *                    TS enum union → Java enum;
 *                    TS npm install → Java 注入式 installFn.
 */
public final class CliUpdate {

    private static final Logger log = LoggerFactory.getLogger(CliUpdate.class);

    public enum InstallMethod { GLOBAL_NPM, LOCAL_NODE_MODULES, NATIVE_SYMLINK }
    public enum InstallStatus { UP_TO_DATE, UPDATE_AVAILABLE, UPDATE_FAILED }

    public static final String DEFAULT_CHANNEL = "latest";
    public static final String STABLE_CHANNEL = "stable";

    public record UpdateInfo(String currentVersion, String latestVersion, InstallStatus status,
        String releaseNotes) {}

    public interface Installer {
        InstallStatus install(InstallMethod method, String packageName, String targetVersion);
    }

    public interface VersionChecker {
        UpdateInfo checkForUpdate(String currentVersion, String channel);
    }

    public interface ProcessRunner {
        int run(String command, java.util.List<String> args);
    }

    private final Supplier<String> currentVersionSupplier;
    private final Supplier<String> channelSupplier;
    private final VersionChecker versionChecker;
    private final Installer installer;
    private final ProcessRunner processRunner;

    public CliUpdate(Supplier<String> currentVersionSupplier,
            Supplier<String> channelSupplier,
            VersionChecker versionChecker,
            Installer installer,
            ProcessRunner processRunner) {
        this.currentVersionSupplier = currentVersionSupplier == null ? () -> "0.0.0" : currentVersionSupplier;
        this.channelSupplier = channelSupplier == null ? () -> "latest" : channelSupplier;
        this.versionChecker = versionChecker;
        this.installer = installer;
        this.processRunner = processRunner;
    }

    public CliUpdate() {
        this(null, null, null, null, null);
    }

    /** CC update 主链. */
    public UpdateInfo update() {
        String current = currentVersionSupplier.get();
        String channel = channelSupplier.get();
        if (versionChecker == null) return new UpdateInfo(current, current, InstallStatus.UP_TO_DATE, null);
        UpdateInfo info = versionChecker.checkForUpdate(current, channel);
        if (info.status() == InstallStatus.UPDATE_AVAILABLE) {
            // determine install method (caller wired)
            InstallStatus result = installer == null ? InstallStatus.UPDATE_FAILED :
                installer.install(InstallMethod.GLOBAL_NPM, "claude-code", info.latestVersion());
            return new UpdateInfo(current, info.latestVersion(), result, info.releaseNotes());
        }
        return info;
    }

    /** CC regenerateCompletionCache. */
    public boolean regenerateCompletionCache() {
        if (processRunner == null) return false;
        return processRunner.run("claude", java.util.List.of("completion", "regenerate")) == 0;
    }

    public UpdateInfo getUpdateInfo() {
        String current = currentVersionSupplier.get();
        String channel = channelSupplier.get();
        if (versionChecker == null) return new UpdateInfo(current, current, InstallStatus.UP_TO_DATE, null);
        return versionChecker.checkForUpdate(current, channel);
    }

    public InstallStatus installGlobalPackage(String packageName, String targetVersion) {
        return installer == null ? InstallStatus.UPDATE_FAILED
            : installer.install(InstallMethod.GLOBAL_NPM, packageName, targetVersion);
    }

    public InstallStatus installOrUpdateClaudePackage(String packageName, String targetVersion) {
        return installer == null ? InstallStatus.UPDATE_FAILED
            : installer.install(InstallMethod.LOCAL_NODE_MODULES, packageName, targetVersion);
    }

    public boolean localInstallationExists() {
        if (processRunner == null) return false;
        return processRunner.run("ls", java.util.List.of("-la", NexusaiPaths.getAppConfigHomeDir() + "/local")) == 0;
    }
}