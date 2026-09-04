package com.nexusai.model.market.dto;

/**
 * POST /api/market/expert/{marketId}/use 请求体。
 *
 * @param sessionId 目标会话 ID（sess-xxx）——「使用」的腾讯专家将被注册为该会话可用 agent，
 *                  并设为本会话主线程 agent（sessions.main_thread_agent）
 */
public record MarketUseRequest(
        String sessionId
) {}
