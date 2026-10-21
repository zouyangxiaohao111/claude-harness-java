package com.nexusai.apis.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.market.MarketPublishService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 插件市场管理 REST 入口（设置页「市场管理」数据源）。
 *
 * <ul>
 *   <li>{@code POST /api/market/manage/publish} — 上架本地技能/专家目录</li>
 *   <li>{@code GET  /api/market/manage/list} — 市场清单（含每个插件的版本历史）</li>
 *   <li>{@code POST /api/market/manage/rollback} — 回滚到某个历史版本（只改清单指向，不删包）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/market/manage")
public class MarketManageController {

    private static final Logger log = LoggerFactory.getLogger(MarketManageController.class);

    @Autowired
    private MarketPublishService marketPublishService;

    /** 上架：手填版本优先；不填自动 patch +1；内容指纹未变则判为 unchanged。 */
    @PostMapping("/publish")
    public MarketPublishService.PublishResult publish(@RequestBody MarketPublishService.PublishRequest req) {
        return marketPublishService.publish(req);
    }

    /** 市场清单（管理页）。远端不可达时返回 502 + 中文说明，不抛 500 裸栈。 */
    @GetMapping("/list")
    public ResponseEntity<?> list() {
        try {
            JsonNode manifest = marketPublishService.manage();
            return ResponseEntity.ok(manifest);
        } catch (IOException e) {
            log.warn("[MarketManage] 读取市场清单失败: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                "error", "无法读取市场清单",
                "detail", e.getMessage()));
        }
    }

    /** 回滚：把清单的 version/source/fingerprint 指回指定历史版本。 */
    @PostMapping("/rollback")
    public MarketPublishService.PublishResult rollback(@RequestParam("name") String name,
                                                       @RequestParam("version") String version) {
        return marketPublishService.rollback(name, version);
    }
}
