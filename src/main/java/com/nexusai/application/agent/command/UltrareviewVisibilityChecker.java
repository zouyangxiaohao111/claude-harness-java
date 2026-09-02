package com.nexusai.application.agent.command;

import java.util.Map;
import java.util.function.Supplier;

/**
 * UltrareviewVisibilityChecker · 对齐 CC commands/review/ultrareviewEnabled.ts.
 *
 * <p>L1 语义: 运行时 /ultrareview 命令的可见性门控。读取 GrowthBook 缓存特性
 * {@code tengu_review_bughunter_config} 的 {@code enabled} 字段;
 * 仅当 {@code enabled === true} 时返回 true,否则 false。CC 源使用
 * {@code getFeatureValue_CACHED_MAY_BE_STALE} 标识该读取允许返回值陈旧以避免阻塞。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #isUltrareviewEnabled()} () → boolean,空安全</li>
 *   <li><b>A2 Golden Trace</b>: Supplier null → false; Map null → false;
 *       Map 非 null + enabled==true → true;其余 → false</li>
 *   <li><b>A3 纯函数</b>: 依赖注入式 Supplier,无内部可变状态;可被并发调用</li>
 *   <li><b>A4 边界</b>: null config / null enabled / 任意类型强制 boolean 严格相等</li>
 *   <li><b>A5 业务场景</b>: CC GrowthBook 未配置 → false (命令从 getCommands() 过滤掉)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): 注入 {@link Supplier} 替代 CC 的全局 cache,便于测试替换;
 * Map 强制 {@code .get("enabled")} 返回 Boolean 严格 {@code == Boolean.TRUE} 判断。
 */
public final class UltrareviewVisibilityChecker {

    private static final String FEATURE_KEY = "tengu_review_bughunter_config";

    private final Supplier<Map<String, Object>> featureSupplier;

    public UltrareviewVisibilityChecker(Supplier<Map<String, Object>> featureSupplier) {
        this.featureSupplier = featureSupplier;
    }

    /**
     * Returns true iff GrowthBook {@code tengu_review_bughunter_config.enabled === true}.
     * Mirrors CC isUltrareviewEnabled(): cfg?.enabled === true.
     */
    @SuppressWarnings("unchecked")
    public boolean isUltrareviewEnabled() {
        Map<String, Object> cfg;
        try {
            cfg = featureSupplier.get();
        } catch (RuntimeException e) {
            return false;
        }
        if (cfg == null) {
            return false;
        }
        Object enabled = cfg.get("enabled");
        return enabled == Boolean.TRUE;
    }
}
