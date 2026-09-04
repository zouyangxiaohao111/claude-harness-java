package com.nexusai.apis.market;

import com.nexusai.domain.market.WorkbuddyMarketService;
import com.nexusai.model.market.dto.MarketConnectorDto;
import com.nexusai.model.market.dto.MarketExpertDto;
import com.nexusai.model.market.dto.MarketSkillDto;
import com.nexusai.model.market.dto.MarketUseRequest;
import com.nexusai.model.market.dto.MarketUseResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 腾讯 workbuddy「技能市场」后端代理 REST 端点（前端市场弹窗数据源）。
 *
 * <p>后端统一代调腾讯接口（本地凭证 + 缓存 + 控频，见 {@link WorkbuddyMarketService}），
 * 向前端暴露统一 DTO（均带 remote=true 标远端市场条目）。腾讯失败/401/非 200 → 502
 * （中文，fail-loud）。
 *
 * <p>路径（对齐任务规格 /api/market）：
 * <ul>
 *   <li>GET  /api/market/expert?page=&amp;page_size=  → List&lt;MarketExpertDto&gt;</li>
 *   <li>GET  /api/market/skill?page=&amp;page_size=   → List&lt;MarketSkillDto&gt;</li>
 *   <li>GET  /api/market/connector                    → List&lt;MarketConnectorDto&gt;</li>
 *   <li>POST /api/market/expert/{marketId}/use body {sessionId} → MarketUseResponse
 *       （真闭环：构造本地 agent + 设会话主线程）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/market")
public class WorkbuddyMarketController {

    @Autowired
    private WorkbuddyMarketService marketService;

    /** 腾讯专家列表代理。 */
    @GetMapping("/expert")
    public List<MarketExpertDto> listExperts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return marketService.listExperts(page, pageSize);
    }

    /** 腾讯技能列表代理。 */
    @GetMapping("/skill")
    public List<MarketSkillDto> listSkills(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return marketService.listSkills(page, pageSize);
    }

    /** 腾讯连接器列表代理。 */
    @GetMapping("/connector")
    public List<MarketConnectorDto> listConnectors() {
        return marketService.listConnectors();
    }

    /**
     * 使用腾讯专家（真闭环）：
     * 构造成本地可驱动 agent → 并入会话 agent registry → 设 sessions.main_thread_agent。
     */
    @PostMapping("/expert/{marketId}/use")
    public MarketUseResponse useExpert(@PathVariable String marketId,
                                       @RequestBody MarketUseRequest req) {
        return marketService.useExpert(marketId, req.sessionId());
    }
}
