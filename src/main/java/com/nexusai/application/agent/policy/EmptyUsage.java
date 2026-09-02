package com.nexusai.application.agent.policy;

import java.util.List;

/**
 * Zero-initialized usage 对象 · 对齐 CC services/api/emptyUsage.ts EMPTY_USAGE.
 *
 * <p>L1 语义: 提供一个全零字段的 NonNullableUsage 哨兵值, 用于"无 usage 数据"场景 (例如 logging.ts
 *            在 LLM 调用未发生时被引用; bridge/replBridge.ts import 而不引入 api/errors.ts 间接依赖).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 11 个字段 (4 tokens + server_tool_use{2} + service_tier + cache_creation{2} + inference_geo + iterations + speed) 与 CC EMPTY_USAGE 字段对齐</li>
 *   <li><b>A2 Golden Trace</b>: 各 numeric 字段 == 0; service_tier="standard"; cache_creation 嵌套 2 字段 == 0; iterations 列表为空</li>
 *   <li><b>A3</b>: record 不可变 (Java record 语义); speed="standard"</li>
 *   <li><b>A4</b>: inference_geo 默认空字符串 (非 null)</li>
 *   <li><b>A5</b>: 服务端 usage 追踪启动时使用此 EMPTY 避免 NPE</li>
 * </ul>
 *
 * <p>L3 (Java idiom): record 替代 TS Readonly&lt;NonNullableUsage&gt;; 嵌套 ServerToolUse / CacheCreation 子 record;
 *                    immutable List.of() 替代 TS readonly array.
 */
public record EmptyUsage(
    long inputTokens,
    long cacheCreationInputTokens,
    long cacheReadInputTokens,
    long outputTokens,
    ServerToolUse serverToolUse,
    String serviceTier,
    CacheCreation cacheCreation,
    String inferenceGeo,
    List<Object> iterations,
    String speed
) {

    /** CC EMPTY_USAGE 哨兵值 — 所有 numeric 字段 == 0, 字符串为 'standard' / ''. */
    public static final EmptyUsage EMPTY = new EmptyUsage(
        0L, 0L, 0L, 0L,
        new ServerToolUse(0, 0),
        "standard",
        new CacheCreation(0L, 0L),
        "",
        List.of(),
        "standard"
    );

    public record ServerToolUse(int webSearchRequests, int webFetchRequests) {}
    public record CacheCreation(long ephemeral1hInputTokens, long ephemeral5mInputTokens) {}
}