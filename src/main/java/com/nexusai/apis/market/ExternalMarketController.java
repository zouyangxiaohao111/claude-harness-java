package com.nexusai.apis.market;

import com.nexusai.domain.market.ExternalMarketService;
import com.nexusai.model.market.dto.ExternalMarketDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部插件市场 REST 端点（自建 MinIO / 静态 HTTP）。
 *
 * <p>腾讯 workbuddy 市场源已按用户 2026-10-26 裁定删除；本端点只保留
 * {@code GET /api/market/external} 供前端「建科数字插件市场」解析 marketplace.json。
 */
@RestController
@RequestMapping("/api/market")
public class ExternalMarketController {

    @Autowired
    private ExternalMarketService marketService;

    /**
     * 解析外部插件市场。{@code url} 指向 marketplace.zip 或 marketplace.json。
     */
    @GetMapping("/external")
    public ExternalMarketDto listExternal(@RequestParam String url) {
        return marketService.listExternalMarket(url);
    }
}
