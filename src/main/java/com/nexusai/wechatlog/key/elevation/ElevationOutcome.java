package com.nexusai.wechatlog.key.elevation;

import java.util.List;

/**
 * 一次提权取密钥的结果。
 *
 * <p>用结构化状态而不是「返回空列表表示失败」：父进程必须能区分
 * 「用户取消」「没找到微信」「0 候选」「提权不可用」，否则就会回到
 * 「无权限和版本不支持都甩同一句请用管理员重启」的老问题。
 */
public record ElevationOutcome(Status status, List<byte[]> candidates, int exitCode, String detail) {

    public enum Status {
        /** 成功，候选已读出。 */
        SUCCESS,
        /** 用户在 UAC 里点了取消。 */
        CANCELLED,
        /** 无法启动提权子进程（系统策略/安全软件/临时文件无法安全创建）。 */
        UNAVAILABLE,
        /** 没找到运行中的微信进程。 */
        NO_PROCESS,
        /** 读到了进程但 0 个候选。 */
        NO_CANDIDATES,
        /** 扫描预算不足，未覆盖到特征数据。 */
        SCAN_BUDGET_EXHAUSTED,
        /** 当前微信版本未适配。 */
        VERSION_UNSUPPORTED,
        /** 其它提取失败。 */
        EXTRACT_FAILED
    }

    public static ElevationOutcome success(List<byte[]> candidates) {
        return new ElevationOutcome(Status.SUCCESS, candidates, ElevatedKeyExtractProtocol.EXIT_OK, null);
    }

    public static ElevationOutcome cancelled() {
        return new ElevationOutcome(Status.CANCELLED, List.of(), -1, "user cancelled UAC");
    }

    public static ElevationOutcome unavailable(String detail) {
        return new ElevationOutcome(Status.UNAVAILABLE, List.of(), -1, detail);
    }

    public static ElevationOutcome noProcess() {
        return new ElevationOutcome(Status.NO_PROCESS, List.of(),
                ElevatedKeyExtractProtocol.EXIT_NO_PROCESS, null);
    }

    public static ElevationOutcome noCandidates() {
        return new ElevationOutcome(Status.NO_CANDIDATES, List.of(),
                ElevatedKeyExtractProtocol.EXIT_NO_CANDIDATES, null);
    }

    public static ElevationOutcome scanBudgetExhausted(String detail) {
        return new ElevationOutcome(Status.SCAN_BUDGET_EXHAUSTED, List.of(),
                ElevatedKeyExtractProtocol.EXIT_SCAN_BUDGET_EXHAUSTED, detail);
    }

    public static ElevationOutcome versionUnsupported(String detail) {
        return new ElevationOutcome(Status.VERSION_UNSUPPORTED, List.of(),
                ElevatedKeyExtractProtocol.EXIT_VERSION_UNSUPPORTED, detail);
    }

    public static ElevationOutcome extractFailed(int exitCode, String detail) {
        return new ElevationOutcome(Status.EXTRACT_FAILED, List.of(), exitCode, detail);
    }
}
