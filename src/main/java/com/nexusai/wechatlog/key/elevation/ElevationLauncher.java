package com.nexusai.wechatlog.key.elevation;

import java.time.Duration;
import java.util.List;

/**
 * 提权启动器：把一条命令行交给系统（Windows 上是 {@code ShellExecuteEx} + {@code runas}）
 * 启动并等待结束。
 *
 * <p>抽成接口的原因：真实实现会弹 UAC、读进程内存，无法在单测里跑；
 * 而「退出码 → 用户文案」「用户取消不算成功」「临时文件读后即删」这些逻辑必须可测。
 */
public interface ElevationLauncher {

    /** 启动状态。 */
    enum Status {
        /** 子进程已结束，{@link LaunchResult#exitCode()} 有效。 */
        COMPLETED,
        /** 用户在 UAC 里点了取消（子进程没有起来）。 */
        CANCELLED,
        /** 无法启动（被系统策略/安全软件拦截、超时、句柄获取失败等）。 */
        UNAVAILABLE
    }

    /**
     * 启动结果。
     *
     * @param status   状态
     * @param exitCode 子进程退出码（仅 COMPLETED 有效）
     * @param detail   失败细节（仅 UNAVAILABLE 有意义，可能为空）
     */
    record LaunchResult(Status status, int exitCode, String detail) {

        public static LaunchResult completed(int exitCode) {
            return new LaunchResult(Status.COMPLETED, exitCode, null);
        }

        public static LaunchResult cancelled() {
            return new LaunchResult(Status.CANCELLED, -1, null);
        }

        public static LaunchResult unavailable(String detail) {
            return new LaunchResult(Status.UNAVAILABLE, -1, detail);
        }
    }

    /**
     * 启动并等待。
     *
     * @param command 完整命令行，第 0 个是可执行文件，其余是参数
     * @param timeout 最长等待时长
     */
    LaunchResult launch(List<String> command, Duration timeout);
}
