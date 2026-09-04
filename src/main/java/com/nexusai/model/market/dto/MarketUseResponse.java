package com.nexusai.model.market.dto;

/**
 * POST /api/market/expert/{marketId}/use 响应（真闭环结果）。
 *
 * @param mainThreadAgent 已注册并设为主线程的本地 agentType（= "wb-" + 腾讯 agent_name）
 * @param displayName     该专家的中文展示名（display_name_zh）
 */
public record MarketUseResponse(
        String mainThreadAgent,
        String displayName
) {}
