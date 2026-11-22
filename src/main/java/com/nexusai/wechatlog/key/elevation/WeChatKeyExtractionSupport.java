package com.nexusai.wechatlog.key.elevation;

import com.sun.jna.platform.win32.Advapi32Util;
import com.nexusai.wechatlog.key.KeyExtractorFactory;
import com.nexusai.wechatlog.key.WeChatKeyExtractor;
import com.nexusai.wechatlog.key.WindowsKeyExtractor;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 取密钥的决策层：<b>先普通权限直接取；只有「打开进程失败（权限）」才尝试临时提权</b>。
 *
 * <h2>为什么不能把提权当默认前置</h2>
 * 本机实测（4.2.0.53）：{@code OpenProcess} 成功、枚举到 2542 个可读区域，
 * 失败点是扫描预算不够。若一上来就弹 UAC，既打扰用户，又会把「0 候选」误归因成权限问题。
 * 因此提权只服务于真正的 {@code PERMISSION} 分支。
 *
 * <h2>分支</h2>
 * <ul>
 *   <li>直接取成功 ⇒ 返回（不弹 UAC）；</li>
 *   <li>直接取失败且不是权限问题 ⇒ 原样抛出（版本/预算/0 候选/没进程）；</li>
 *   <li>权限失败 + 允许提权 + Windows + 非管理员 + 微信在跑 ⇒ 提权子进程一次；</li>
 *   <li>用户取消 ⇒「已取消提权，微信渠道未激活」；</li>
 *   <li>提权不可用 ⇒ 说明原因并保留原始权限错误，不卡死。</li>
 * </ul>
 */
public final class WeChatKeyExtractionSupport {

    private final Supplier<WeChatKeyExtractor> directFactory;
    private final BooleanSupplier windowsCheck;
    private final BooleanSupplier elevatedCheck;
    private final BooleanSupplier weChatRunningCheck;
    private final WindowsElevatedKeyExtractor elevated;

    public WeChatKeyExtractionSupport() {
        this(() -> KeyExtractorFactory.create(60, null, null));
    }

    public WeChatKeyExtractionSupport(Supplier<WeChatKeyExtractor> directFactory) {
        this(directFactory,
                WeChatKeyExtractionSupport::isWindowsOs,
                WeChatKeyExtractionSupport::isProcessElevated,
                WindowsKeyExtractor::isWeChatProcessRunning,
                new WindowsElevatedKeyExtractor());
    }

    public WeChatKeyExtractionSupport(Supplier<WeChatKeyExtractor> directFactory,
                                      BooleanSupplier windowsCheck,
                                      BooleanSupplier elevatedCheck,
                                      BooleanSupplier weChatRunningCheck,
                                      WindowsElevatedKeyExtractor elevated) {
        this.directFactory = directFactory;
        this.windowsCheck = windowsCheck;
        this.elevatedCheck = elevatedCheck;
        this.weChatRunningCheck = weChatRunningCheck;
        this.elevated = elevated;
    }

    /**
     * 取密钥候选。
     *
     * @param allowElevation 是否允许在权限失败时触发一次 UAC（定时任务应传 false）
     */
    public List<byte[]> extract(Path accountDir, boolean allowElevation) {
        try {
            return directFactory.get().extract(accountDir);
        } catch (WeChatKeyExtractor.KeyExtractionException e) {
            if (e.kind() != WeChatKeyExtractor.KeyExtractionException.Kind.PERMISSION) {
                throw e;
            }
            if (!windowsCheck.getAsBoolean() || elevatedCheck.getAsBoolean()) {
                // 非 Windows 没有 runas 助手；已经是管理员还失败说明提权也救不了
                throw e;
            }
            if (!allowElevation) {
                throw WeChatKeyExtractionMessages.elevationNotAttempted();
            }
            if (!weChatRunningCheck.getAsBoolean()) {
                throw WeChatKeyExtractionMessages.noProcess();
            }
            return elevate(accountDir, e);
        }
    }

    private List<byte[]> elevate(Path accountDir, WeChatKeyExtractor.KeyExtractionException permission) {
        ElevationOutcome outcome = elevated.extract(accountDir);
        return switch (outcome.status()) {
            case SUCCESS -> outcome.candidates();
            case CANCELLED -> throw WeChatKeyExtractionMessages.cancelled();
            case NO_PROCESS -> throw WeChatKeyExtractionMessages.noProcess();
            case NO_CANDIDATES -> throw WeChatKeyExtractionMessages.noCandidates();
            case SCAN_BUDGET_EXHAUSTED ->
                    throw WeChatKeyExtractionMessages.scanBudgetExhausted(outcome.detail());
            case VERSION_UNSUPPORTED ->
                    throw WeChatKeyExtractionMessages.versionUnsupported(outcome.detail());
            case UNAVAILABLE ->
                    throw WeChatKeyExtractionMessages.elevationUnavailable(permission.getMessage());
            case EXTRACT_FAILED ->
                    throw WeChatKeyExtractionMessages.extractFailed(outcome.exitCode(), outcome.detail());
        };
    }

    static boolean isWindowsOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static boolean isProcessElevated() {
        try {
            return Advapi32Util.isCurrentProcessElevated();
        } catch (Throwable t) {
            // 探测失败按「非管理员」处理：最多多尝试一次提权，不会误解锁任何东西
            return false;
        }
    }
}
