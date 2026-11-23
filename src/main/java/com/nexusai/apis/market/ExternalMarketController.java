package com.nexusai.apis.market;

import com.nexusai.application.market.MarketSkillPreviewService;
import com.nexusai.domain.market.ExternalMarketService;
import com.nexusai.model.market.dto.ExternalMarketDto;
import com.nexusai.model.market.dto.MarketSkillPreviewDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** [2026-11-23] 市场技能预览（从 plugin.zip 取 SKILL.md；带缓存与大小上限）。 */
    @Autowired
    private MarketSkillPreviewService skillPreviewService;

    /**
     * 解析外部插件市场。{@code url} 指向 marketplace.zip 或 marketplace.json。
     */
    @GetMapping("/external")
    public ExternalMarketDto listExternal(@RequestParam String url) {
        return marketService.listExternalMarket(url);
    }

    /**
     * 市场技能预览 · {@code GET /api/market/skills/{name}/preview}。
     *
     * <p>version/source 可空（空则读 marketplace.json）；url 可空（给定时必须与市场同源）。
     */
    @GetMapping("/skills/{name}/preview")
    public MarketSkillPreviewDto previewSkill(
            @PathVariable String name,
            @RequestParam(value = "version", required = false) String version,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "url", required = false) String url) {
        return skillPreviewService.preview(name, version, source, url);
    }
}
