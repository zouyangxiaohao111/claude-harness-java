package com.nexusai.apis.stats;

import com.nexusai.domain.stats.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计 REST 端点 · 对齐 CC /stats 维度（按天 / 按模型 / 按归类 / 按代理层级 · 前端图表数据源）。
 *
 * <p>{@code GET /api/v1/stats?from=&to=} → {@link StatsService#aggregate(String, String)}：
 * <pre>{ range:{from,to,filtered},
 *   totals:{sessionCount,calls,tokenCount,inputTokens,outputTokens,cacheReadTokens,cacheWriteTokens,
 *           costYuan,costInput,costOutput,costCacheRead,costCacheWrite,costNoCacheInput,
 *           cacheSavingsYuan,cacheHitRate,estimatedCalls,abortedCalls,failedCalls,
 *           legacyCalls,legacyCostYuan},
 *   byDay:[{date,calls,tokenCount,costYuan}],
 *   byModel:[{model,calls,inputTokens,outputTokens,cacheReadInputTokens,cacheCreationInputTokens,
 *             totalTokens,costYuan,costInput,costOutput,costCacheRead,costCacheWrite,cacheHitRate}],
 *   byCategory:[…], byAgent:[…] }</pre>
 *
 * <p><b>区间</b>：{@code from} <b>含</b> / {@code to} <b>不含</b>；两者均可省略（= 全量）。
 * 取值 = ISO-8601 带偏移文本（如 {@code 2026-10-13T00:00:00+08:00}）或 epoch 毫秒；
 * 非法 → {@link com.nexusai.infra.exception.ValidationException} ⇒ 400（⛔ 不是 500）。
 * 过滤用 {@code started_at} <b>字符串比较</b>（命中 V101 单列索引），见
 * {@link StatsService} 类 javadoc 的格式前提。
 *
 * <p>空表 / 无区间内调用 → 200 + 全零 totals + 空数组（fail-soft，不抛）。
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    @Autowired
    private StatsService statsService;

    /**
     * 统计聚合（可带时间区间）。
     *
     * @param from 区间起点（含）· ISO-8601 带偏移 / epoch ms；缺省 = 不限
     * @param to   区间终点（不含）· 同上；缺省 = 不限
     * @return 四维聚合 + totals + 区间回显
     */
    @GetMapping
    public StatsService.StatsResult get(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        return statsService.aggregate(from, to);
    }
}
