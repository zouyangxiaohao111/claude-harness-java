package com.nexusai.apis.market;

import com.nexusai.domain.connector.ConnectorOAuthService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.connector.dto.ConnectorListResponse;
import com.nexusai.model.connector.dto.ManualCredentialRequest;
import com.nexusai.model.connector.dto.PollResponse;
import com.nexusai.model.connector.dto.RevokedResponse;
import com.nexusai.model.connector.dto.StartResponse;
import com.nexusai.model.connector.dto.StatusResponse;
import com.nexusai.model.connector.dto.TestResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连接器市场 REST 端点（路径冻结在 {@code /api/market/connectors}，形状按实施计划 §1.1）。
 *
 * <p>与 IM 的 {@code ImOAuthController} 形状对齐（start / poll / status 三件套 + missingScopes），
 * 但流程是<b>授权码 + loopback 回调</b>：{@code start} 返回 {@code authorizeUrl} 而不是 deviceCode。
 * 返回全部为强类型 record（不返回裸 Map）。
 */
@RestController
@RequestMapping("/api/market/connectors")
public class ConnectorController {

    private final ConnectorOAuthService connectorService;

    public ConnectorController(ConnectorOAuthService connectorService) {
        this.connectorService = connectorService;
    }

    /** 连接器列表（默认不含 hidden 条目）。 */
    @GetMapping
    public ConnectorListResponse list(
            @RequestParam(name = "includeHidden", defaultValue = "false") boolean includeHidden) {
        return connectorService.list(includeHidden);
    }

    /** 发起授权：返回授权页 URL + state + 回调地址（前端用系统浏览器打开并轮询 poll）。 */
    @PostMapping("/{id}/start")
    public StartResponse start(@PathVariable String id) {
        return connectorService.start(id);
    }

    /**
     * 轮询授权结果：{@code state} ∈ PENDING | AUTHORIZED | ERROR。
     *
     * <p><b>缺 {@code state} 必须 400 而不是 500</b>：Spring 的
     * {@code MissingServletRequestParameterException} 会被全局兜底
     * {@code @ExceptionHandler(Exception.class)} 转成 500（影响全站，不在本批改）。
     * 因此这里用 {@code required=false} + 显式判空抛 {@link ValidationException}（400），
     * 把「参数错」的语义收敛在本端点内。
     */
    @GetMapping("/{id}/poll")
    public PollResponse poll(@PathVariable String id,
                             @RequestParam(name = "state", required = false) String state) {
        if (state == null || state.isBlank()) {
            throw new ValidationException("缺少必填参数 state（请传 start 返回的 state）");
        }
        return connectorService.poll(id, state);
    }

    /** 连接状态 + 缺哪些 scope。 */
    @GetMapping("/{id}/status")
    public StatusResponse status(@PathVariable String id) {
        return connectorService.status(id);
    }

    /** 手动凭据（DCR 不可用 / 非 oauth2 类型）。 */
    @PostMapping("/{id}/credentials")
    public StatusResponse saveCredentials(@PathVariable String id,
                                          @RequestBody ManualCredentialRequest request) {
        return connectorService.saveManualCredentials(id, request);
    }

    /** 真实探测 MCP 端点（带 Bearer），返回可达性与凭据有效性。 */
    @PostMapping("/{id}/test")
    public TestResponse test(@PathVariable String id) {
        return connectorService.test(id);
    }

    /** 断开连接（幂等）。 */
    @DeleteMapping("/{id}")
    public RevokedResponse revoke(@PathVariable String id) {
        return connectorService.revoke(id);
    }
}
