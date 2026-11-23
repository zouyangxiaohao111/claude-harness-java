package com.nexusai.apis.mcp;

import com.nexusai.domain.mcp.McpOAuthFlowService;
import com.nexusai.infra.exception.ValidationException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 管理页的 OAuth2 REST（[2026-11-23] 对比报告缺口 ④）· 前缀 {@code /api/v1/mcp/{id}/oauth}。
 *
 * <ul>
 *   <li>{@code POST /start} — 异步发起授权，返回 {@code authorizeUrl + state}（不阻塞 300s）</li>
 *   <li>{@code GET /poll?state=} — PENDING / AUTHORIZED / ERROR（缺 state → 400）</li>
 *   <li>{@code DELETE /} — 吊销（复用 {@code McpAuth.revokeServerTokens}）+ 清本地凭据</li>
 * </ul>
 *
 * <p>与连接器市场的 {@code ConnectorController} 形状一致；区别是这里面向「设置 → MCP」里的
 * 任意远程 server（id 是 mcp_servers 主键）。缺参 400 在控制器显式判空（全局兜底会把
 * MissingServletRequestParameterException 变 500，本次不动全局处理器）。
 */
@RestController
@RequestMapping("/api/v1/mcp/{id}/oauth")
public class McpOAuthController {

    private final McpOAuthFlowService oauthFlowService;

    public McpOAuthController(McpOAuthFlowService oauthFlowService) {
        this.oauthFlowService = oauthFlowService;
    }

    @PostMapping("/start")
    public McpOAuthFlowService.StartResult start(@PathVariable String id) {
        return oauthFlowService.start(id);
    }

    @GetMapping("/poll")
    public McpOAuthFlowService.PollResult poll(
            @PathVariable String id,
            @RequestParam(name = "state", required = false) String state) {
        if (state == null || state.isBlank()) {
            throw new ValidationException("缺少必填参数 state（请传 start 返回的 state）");
        }
        return oauthFlowService.poll(id, state);
    }

    /**
     * 断开授权（前端 {@code front/src/api/mcp.ts} 实际用的是 DELETE）· 保持主路径。
     */
    @DeleteMapping
    public McpOAuthFlowService.RevokedResult logout(@PathVariable String id) {
        return oauthFlowService.logout(id);
    }

    /**
     * 断开授权（POST 别名）：协调者给 P2 的契约写的是 {@code /oauth/logout}；
     * 两种方法都指向同一实现，前端用哪个都行。
     */
    @PostMapping("/logout")
    public McpOAuthFlowService.RevokedResult logoutPost(@PathVariable String id) {
        return oauthFlowService.logout(id);
    }
}
