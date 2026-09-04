package com.nexusai.model.market.dto;

/**
 * 腾讯 workbuddy 技能市场「连接器」统一 DTO · 后端代调腾讯返回（远端连接器，remote=true）。
 *
 * <p>腾讯连接器 registry2c/list 响应无独立 id（data.list[] 以 name 标识），故 marketId = name。
 *
 * @param marketId    连接器标识（= name，腾讯连接器无独立 id）
 * @param name        连接器名
 * @param scope       作用域（如 personal/org 等）
 * @param status      连接状态文本
 * @param authType    鉴权类型
 * @param isConnected 是否已连接（is_connected）
 * @param remote      恒 true（远端市场条目标记）
 */
public record MarketConnectorDto(
        String marketId,
        String name,
        String scope,
        String status,
        String authType,
        boolean isConnected,
        boolean remote
) {}
