package com.nexusai.apis.stats;

import com.nexusai.domain.stats.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计 REST 端点 · 对齐 CC /stats 维度（按天 / 按模型聚合 · 前端图表数据源）。
 *
 * <p>GET /api/v1/stats → {@link StatsService#aggregate()}：
 * {@code {totals:{sessionCount,tokenCount,costYuan}, byDay:[{date,tokenCount,costYuan}],
 * byModel:[{model,inputTokens,outputTokens,cacheReadInputTokens,cacheCreationInputTokens,costUSD}]}}。
 * 空 sessions → 200 {@code {totals:{0,0,0}, byDay:[], byModel:[]}}（fail-soft，不抛）。
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    @Autowired
    private StatsService statsService;

    @GetMapping
    public StatsService.StatsResult get() {
        return statsService.aggregate();
    }
}
