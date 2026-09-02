package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.PermissionResult;

/**
 * [s03 P3 #12 修补] tool.checkPermissions 结果共享 cache (ThreadLocal per-call).
 *
 * <p>L1 对齐 CC permissions.ts:1114-1149 {@code toolPermissionResult} 共享
 * （OPD-WF5-02-06 行号修正：原写 :1210-1216，实际定义在 :1114，见探查 EV-FS-061）:
 * <ul>
 *   <li>1c 调 {@code tool.checkPermissions()} 一次</li>
 *   <li>1d 复用 1c 的结果(不再调 tool.checkPermissions 第二次)</li>
 *   <li>1e 同样复用</li>
 * </ul>
 *
 * <p>Java 端实现: ThreadLocal per-call cache。{@link #clear()} 在
 * {@code applyPermissionFilter} 入口调用,确保 per-call isolation;
 * 同一 call 内的 1c/1d/1e 共享 ThreadLocal map。
 *
 * <p>为什么不改 CheckLayer interface signature:
 * <ul>
 *   <li>CheckLayer 是 {@code @FunctionalInterface},增加参数破坏 9 个实现</li>
 *   <li>Spring DI 配置不变 — ThreadLocal 不需要注入</li>
 *   <li>L1 一致: CC 内部共享 in-scope variable,Java 用 ThreadLocal 模拟</li>
 * </ul>
 *
 * <p>诚实标注:
 * <ul>
 *   <li>ThreadLocal 在并发 batch 时不 race,因为 {@code applyPermissionFilter} 是
 *       sequential per call(同一 thread 内串行调用 10 层)。</li>
 *   <li>如果未来引入 s15 Team (multi-agent),每个 agent 的 call 必须 clear cache
 *       否则会跨 agent 污染。s15 暂未实现,本方案无 race。</li>
 *   <li>如果 tool.checkPermissions 是非纯函数,缓存会引入 stale state。但 5 工具
 *       当前均纯函数(CheckLayer1c/1d Javadoc 已有诚实标注)。</li>
 * </ul>
 */
public final class ToolCheckCache {

    private static final ThreadLocal<java.util.Map<String, PermissionResult>> CACHE =
        ThreadLocal.withInitial(java.util.LinkedHashMap::new);

    private ToolCheckCache() {
        throw new AssertionError("utility class");
    }

    /** applyPermissionFilter 入口调用,清 per-call cache */
    public static void clear() {
        CACHE.get().clear();
    }

    /** 1c 调 tool.checkPermissions 后 put */
    public static void put(String toolName, PermissionResult result) {
        if (toolName != null && result != null) {
            CACHE.get().put(toolName, result);
        }
    }

    /** 1d/1e 复用 — null 表示 cache miss,fallback 重调 tool.checkPermissions */
    public static PermissionResult get(String toolName) {
        if (toolName == null) return null;
        return CACHE.get().get(toolName);
    }

    /**
     * 取 1c 结果回带的 updatedInput，无则回落原始 input · 对齐 CC
     * {@code getUpdatedInputOrFallback(toolPermissionResult, input)}
     * （Open-ClaudeCode/src/utils/permissions/permissions.ts:1477-1486）。
     *
     * <p>CC 语义：{@code ('updatedInput' in permissionResult
     * ? permissionResult.updatedInput : undefined) ?? fallback}。Java 端 1c 结果经
     * {@link #put} 存入 ThreadLocal cache（同 call 覆盖），2a/2b/3 层消费本方法取回：
     * <ul>
     *   <li>{@link PermissionResult.Allow} — updatedInput 强制非空，直接返回</li>
     *   <li>{@link PermissionResult.Ask} — updatedInput 可空，非空才用，否则回落</li>
     *   <li>Passthrough / cache miss — 无 updatedInput 概念，回落原始 input</li>
     * </ul>
     *
     * @param toolName 工具名（cache 键，1c 结果）
     * @param fallback 原始 input（CC {@code input}）
     * @return 1c 结果回带的 updatedInput；无则 {@code fallback}
     */
    public static com.fasterxml.jackson.databind.JsonNode getUpdatedInputOrFallback(
            String toolName, com.fasterxml.jackson.databind.JsonNode fallback) {
        PermissionResult cached = get(toolName);
        if (cached instanceof PermissionResult.Allow allow) {
            return allow.updatedInput();
        }
        if (cached instanceof PermissionResult.Ask ask && ask.updatedInput() != null) {
            return ask.updatedInput();
        }
        return fallback;
    }
}
