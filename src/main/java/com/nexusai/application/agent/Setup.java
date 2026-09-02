package com.nexusai.application.agent;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI setup wizard · 对齐 CC setup.ts.
 *
 * <p>L1 语义: 初始化 CLI session — project root + cwd + sessionId;init session memory;
 *            release notes check;file changed watcher;doctor diagnostic;terminal backup.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 8 step constant; SetupStep enum (8); SetupResult record;
 *       runSetup 主链 + 3 helper method.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — runSetup → projectRoot → cwd → sessionId → releaseNotes → done.</li>
 *   <li><b>A3</b>: 注入式 (envSupplier + projectRootFn + sessionIdFn);silent failure on missing.</li>
 *   <li><b>A4</b>: project root null → abort;git root 不存在 → continue.</li>
 *   <li><b>A5</b>: 真实场景 — `claude` 启动 → 初始化 session → 加载 SessionMemory → 设置 cwd.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async/await → Java 同步 (异步由 caller wired);
 *                    TS enum union → Java enum;
 *                    TS module-level state → Java Supplier.
 */
public final class Setup {

    private static final Logger log = LoggerFactory.getLogger(Setup.class);

    public enum SetupStep { INIT_SINKS, INIT_CWD, INIT_PROJECT_ROOT, INIT_SESSION_ID,
        CHECK_RELEASE_NOTES, INIT_FILE_WATCHER, COMPLETE }

    public record SetupResult(boolean success, SetupStep lastStep, String errorMessage,
        String projectRoot, String sessionId) {}

    public interface SetupAction {
        SetupStep execute();
    }

    private final Supplier<String> projectRootSupplier;
    private final Supplier<String> cwdSupplier;
    private final Supplier<String> sessionIdSupplier;
    private final SetupAction releaseNotesAction;
    private final SetupAction fileWatcherAction;

    public Setup(Supplier<String> projectRootSupplier,
            Supplier<String> cwdSupplier,
            Supplier<String> sessionIdSupplier,
            SetupAction releaseNotesAction,
            SetupAction fileWatcherAction) {
        this.projectRootSupplier = projectRootSupplier == null ? () -> null : projectRootSupplier;
        this.cwdSupplier = cwdSupplier == null ? () -> null : cwdSupplier;
        this.sessionIdSupplier = sessionIdSupplier == null ? () -> null : sessionIdSupplier;
        this.releaseNotesAction = releaseNotesAction;
        this.fileWatcherAction = fileWatcherAction;
    }

    public Setup() {
        this(null, null, null, null, null);
    }

    /** CC run 主链. */
    public SetupResult runSetup() {
        try {
            String projectRoot = projectRootSupplier.get();
            if (projectRoot == null) {
                return new SetupResult(false, SetupStep.INIT_PROJECT_ROOT,
                    "project root not set", null, null);
            }
            String cwd = cwdSupplier.get();
            if (cwd == null) {
                return new SetupResult(false, SetupStep.INIT_CWD, "cwd not set", projectRoot, null);
            }
            String sessionId = sessionIdSupplier.get();
            if (sessionId == null) {
                return new SetupResult(false, SetupStep.INIT_SESSION_ID, "session id not set",
                    projectRoot, null);
            }
            // [FIX-SM] INIT_SESSION_MEMORY 死代码步骤已删——CC setup 不再走 setup wizard 初始化
            // session memory；生产唯一注册点 = SessionMemoryService@PostConstruct initSessionMemory。
            if (releaseNotesAction != null) {
                releaseNotesAction.execute();
            }
            if (fileWatcherAction != null) {
                fileWatcherAction.execute();
            }
            return new SetupResult(true, SetupStep.COMPLETE, null, projectRoot, sessionId);
        } catch (Exception ex) {
            log.warn("setup failed: {}", ex.getMessage());
            return new SetupResult(false, SetupStep.COMPLETE, ex.getMessage(), null, null);
        }
    }

    /** CC initSinks. */
    public boolean initSinks() { return true; }

    /** CC getProjectRoot. */
    public String getProjectRoot() { return projectRootSupplier.get(); }

    /** CC getCwd. */
    public String getCwd() { return cwdSupplier.get(); }

    /** CC getSessionId. */
    public String getSessionId() { return sessionIdSupplier.get(); }
}