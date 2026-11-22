package com.nexusai.wechatlog.key.elevation;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * 父进程侧：组织一次「建私有临时文件 → runas 子进程 → 读结果 → 立刻删除」。
 *
 * <p>它不判断「要不要提权」（那是 {@code WeChatKeyExtractionSupport} 的事），
 * 只负责把一次提权执行得安全、可回收：
 * 临时文件权限收紧失败 ⇒ 不启动子进程；无论走哪条分支，{@code finally} 都删除临时文件。
 */
public final class WindowsElevatedKeyExtractor {

    /** 默认临时根：{@code ~/.nexusai/wechat-log/.elevated}。 */
    public static Path defaultTempRoot() {
        return Paths.get(System.getProperty("user.home"), ".nexusai", "wechat-log", ".elevated");
    }

    private final ElevatedCommandBuilder commandBuilder;
    private final ElevationLauncher launcher;
    private final Path tempRoot;
    private final AclRestrictor aclRestrictor;
    private final Duration timeout;

    public WindowsElevatedKeyExtractor() {
        this(ElevatedCommandBuilder.forCurrentJvm(),
                new WindowsElevationLauncher(),
                defaultTempRoot(),
                new OwnerOnlyAclRestrictor(),
                Duration.ofSeconds(300));
    }

    public WindowsElevatedKeyExtractor(ElevatedCommandBuilder commandBuilder,
                                       ElevationLauncher launcher,
                                       Path tempRoot,
                                       AclRestrictor aclRestrictor,
                                       Duration timeout) {
        this.commandBuilder = commandBuilder;
        this.launcher = launcher;
        this.tempRoot = tempRoot;
        this.aclRestrictor = aclRestrictor;
        this.timeout = timeout;
    }

    public ElevationOutcome extract(Path accountDir) {
        PrivateTempDir temp;
        try {
            temp = PrivateTempDir.create(tempRoot, aclRestrictor);
        } catch (IOException e) {
            return ElevationOutcome.unavailable("无法创建安全的临时交接文件: " + e.getMessage());
        }

        try {
            List<String> command = commandBuilder.build(accountDir, temp.outputFile());
            ElevationLauncher.LaunchResult result = launcher.launch(command, timeout);

            if (result.status() == ElevationLauncher.Status.CANCELLED) {
                return ElevationOutcome.cancelled();
            }
            if (result.status() == ElevationLauncher.Status.UNAVAILABLE) {
                return ElevationOutcome.unavailable(result.detail());
            }

            int code = result.exitCode();
            if (code == ElevatedKeyExtractProtocol.EXIT_OK) {
                List<byte[]> candidates;
                try {
                    candidates = ElevatedKeyExtractProtocol.readCandidates(temp.outputFile());
                } catch (IOException e) {
                    return ElevationOutcome.extractFailed(code, "读取结果失败: " + e.getMessage());
                }
                if (candidates.isEmpty()) {
                    return ElevationOutcome.extractFailed(code,
                            "子进程返回成功但没有写入密钥候选");
                }
                return ElevationOutcome.success(candidates);
            }
            String reason = ElevatedKeyExtractProtocol.readReason(temp.outputFile());
            return switch (code) {
                case ElevatedKeyExtractProtocol.EXIT_NO_PROCESS -> ElevationOutcome.noProcess();
                case ElevatedKeyExtractProtocol.EXIT_NO_CANDIDATES -> ElevationOutcome.noCandidates();
                case ElevatedKeyExtractProtocol.EXIT_SCAN_BUDGET_EXHAUSTED
                        -> ElevationOutcome.scanBudgetExhausted(reason);
                case ElevatedKeyExtractProtocol.EXIT_VERSION_UNSUPPORTED
                        -> ElevationOutcome.versionUnsupported(reason);
                default -> ElevationOutcome.extractFailed(code, "exit=" + code + (reason == null ? "" : ": " + reason));
            };
        } finally {
            temp.deleteAllQuietly();
        }
    }
}
