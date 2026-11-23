package com.nexusai.application.agent.app;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 应用「启动」语义的共享判据 + defaultOpen 种子桥。
 *
 * <p><b>终稿语义</b>（用户 2026-11-23）：启动应用 = 进 schema 的门（会话级、持久）；
 * 会话列 {@code enabled_apps} 未设置时按「defaultOpen 种子」计算。
 * 应用层只含 MCP：connector 安装的=false，其余=true（浏览器 / CU 回工具面板，不走应用层）。
 *
 * <p>为什么需要静态桥：{@code SessionToolVisibility.capture} 是全局静态（schema/执行/面板共用），
 * 拿不到 Spring bean；由 {@link AppCatalogService} 构造时安装种子供应器，POJO 单测未安装 →
 * 返回 null（= 不裁剪，保持旧行为）。
 */
public final class AppEnablement {

    private static volatile Supplier<Set<String>> defaultOpenKeysSupplier;

    private AppEnablement() {}

    /** 安装 defaultOpen 种子供应器（唯一生产安装点 = AppCatalogService 构造）。 */
    public static void installDefaultOpenKeysSupplier(Supplier<Set<String>> supplier) {
        defaultOpenKeysSupplier = supplier;
    }

    /** 测试辅助：重置桥接（防跨测试污染）。 */
    public static void reset() {
        defaultOpenKeysSupplier = null;
    }

    /**
     * defaultOpen 种子（未安装供应器 → null = 不裁剪）。
     *
     * @return 种子应用 key 集合；null = 不可用（非 Spring / 单测）
     */
    public static Set<String> defaultOpenKeys() {
        Supplier<Set<String>> supplier = defaultOpenKeysSupplier;
        if (supplier == null) {
            return null;
        }
        Set<String> keys = supplier.get();
        // supplier 返回 null = 种子读取失败 ⇒ 上层按「不裁剪」处理（⛔ 不能退化成空集=全裁）
        return keys == null ? null : Set.copyOf(keys);
    }

    /**
     * MCP 应用的 defaultOpen 规则（应用层只含 MCP）：connector 安装=false，其余=true。
     *
     * @param connectorInstalled 是否由 connector 安装
     * @return true = 未显式设置时默认启动
     */
    public static boolean defaultOpenMcp(boolean connectorInstalled) {
        return !connectorInstalled;
    }
}
