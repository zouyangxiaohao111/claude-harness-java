package com.nexusai.apis.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.model.session.dto.WebSearchResultDto;
import com.nexusai.repository.session.entity.WebSearchResultRecord;
import com.nexusai.repository.session.mapper.WebSearchResultMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <b>2026-08-23 前端仅从工具输入输出（tool_result §33.2 outputShape）抽取 WebSearch 格式，
 * 本查询 API 前端不消费，已停用（无数据写入）；保留类供未来审计，不物理删除。</b>
 *
 * <p>WebSearch 详细搜索结果查询 REST · 通道3（前端凭 sessionId / toolUseId 反查详细 hits）。
 *
 * <p>topic / 事件由 {@code WebSearchTool} 通道2 推送；本控制器提供持久化后查询（AC-8）。
 * 查询口径与事件载荷一致：{@code results} 元素 = CC {@code searchHitSchema} {@code {title, url}}。
 */
@Deprecated
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/websearch-results")
public class WebSearchResultController {

    private static final Logger log = LoggerFactory.getLogger(WebSearchResultController.class);

    @Autowired private WebSearchResultMapper webSearchResultMapper;

    /**
     * GET /api/v1/sessions/{sessionId}/websearch-results[?toolUseId={id}]
     *
     * <p>返回该会话全部 WebSearch 详细结果（created_at 倒序，新→旧）；{@code toolUseId} 过滤
     * 精确反查单条（供前端凭事件内 toolUseId 与消息流 tool_use 块绑定后回查）。
     *
     * @param sessionId 会话 ID（路径变量）
     * @param toolUseId 工具调用 id（可选过滤，WebSearchTool 调用块 id）
     * @return 200 该会话已存 WebSearch 详细结果列表
     */
    @GetMapping
    public List<WebSearchResultDto> list(@PathVariable String sessionId,
                                         @RequestParam(required = false) String toolUseId) {
        QueryWrapper qw = QueryWrapper.create()
                .eq("session_id", sessionId)
                .orderBy("created_at", false);          // desc（新→旧）
        if (toolUseId != null && !toolUseId.isBlank()) {
            qw.eq("tool_use_id", toolUseId);
        }
        List<WebSearchResultRecord> rows = webSearchResultMapper.selectListByQuery(qw);
        if (log.isDebugEnabled()) {
            log.debug("WebSearchResultController.list: session={} toolUseId={} rows={}",
                    sessionId, toolUseId, rows.size());
        }
        return rows.stream().map(WebSearchResultDto::from).toList();
    }
}
