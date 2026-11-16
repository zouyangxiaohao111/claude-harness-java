package com.nexusai.apis.provider;

import com.nexusai.application.agent.oauth.ClaudeOAuthService;
import com.nexusai.application.agent.oauth.CodexOAuthService;
import com.nexusai.application.agent.oauth.OAuthTokenExchangeError;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.model.provider.dto.ProviderDto;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAI / ChatGPT 订阅（Codex）OAuth 登录端点（手动粘贴模式）。
 *
 * <p><b>为什么不监听 1455</b>：授权完成后浏览器会跳到
 * {@code http://localhost:1455/auth/callback}，本应用不监听该端口（避免占用固定端口、也避免
 * 桌面端多实例冲突），故该页打不开属正常 —— 用户把地址栏整条 URL 粘回前端，由
 * {@code POST /{id}/oauth/complete} 完成换 token。
 *
 * <p><b>失败一律 400</b>：登录失败是「用户输入/密码学校验不通过」这一类可纠正问题，
 * 不是服务端故障；返回中文可读消息给前端直接展示。
 *
 * <p><b>provider 不存在仍走 404</b>：{@code ProviderService} 抛的 NotFoundException 不在此捕获，
 * 交给 {@code GlobalExceptionHandler}。
 */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderOAuthController {

    private static final Logger log = LoggerFactory.getLogger(ProviderOAuthController.class);

    @Autowired private CodexOAuthService codexOAuthService;
    @Autowired private ClaudeOAuthService claudeOAuthService;
    @Autowired private ProviderService providerService;

    /** complete 请求体：用户粘贴的整条回调 URL 或裸授权码。 */
    public record CompleteRequest(String input) {
    }

    /**
     * 官方内置 Codex provider（方案 A 顶部固定卡片）。
     *
     * <p>不存在 → 204（无 body）：前端据此显示「未登录」，点击登录时才走 ensure 创建。
     */
    @GetMapping("/official/codex")
    public ResponseEntity<ProviderDto> officialCodex() {
        ProviderDto dto = providerService.getOfficialCodexProvider();
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }

    /** 幂等创建 / 返回官方 Codex provider（点「登录 ChatGPT 订阅」的第一步）。 */
    @PostMapping("/official/codex/ensure")
    public ProviderDto ensureOfficialCodex() {
        return providerService.ensureOfficialCodexProvider();
    }

    /** 生成授权链接：前端拿到 url 后打开浏览器。 */
    @PostMapping("/{id}/oauth/authorize")
    public Map<String, Object> authorize(@PathVariable String id) {
        return Map.of("url", codexOAuthService.buildAuthorizeUrl(id));
    }

    /** 用回调 URL / 授权码完成登录。 */
    @PostMapping("/{id}/oauth/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable String id,
            @RequestBody(required = false) CompleteRequest req) {
        String input = req == null ? null : req.input();
        try {
            codexOAuthService.complete(id, input);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            log.warn("ChatGPT 订阅登录失败 provider={}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("ok", false, "message", readable(e.getMessage())));
        } catch (OAuthTokenExchangeError e) {
            log.warn("ChatGPT 订阅换 token 失败 provider={}: error={}", id, e.errorCode());
            String reason = e.errorCode() != null && !e.errorCode().isBlank()
                ? "provider 返回 " + e.errorCode()
                : "token 端点拒绝了本次请求";
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                "message", "授权码兑换失败（" + reason + "），请重新点击登录获取新的授权链接"));
        }
    }

    /** 退出登录：清凭据并回落到 API key 模式。 */
    @DeleteMapping("/{id}/oauth")
    public ResponseEntity<Void> logout(@PathVariable String id) {
        codexOAuthService.logout(id);
        return ResponseEntity.noContent().build();
    }

    // ───────────── Claude（Anthropic）订阅登录 ─────────────
    //
    // 与 Codex 的差异（⛔ 不要「统一」）：Claude 后端自己监听 127.0.0.1:53692 接住浏览器回调
    // （redirect_uri 写 localhost，刻意不一致）；token 端点只吃 JSON body。手动粘贴仍是端口
    // 被占用时的回退通道。

    /** 幂等创建 / 返回内置 Claude provider（点「登录 Claude 订阅」的第一步）。 */
    @PostMapping("/official/claude/ensure")
    public ProviderDto ensureOfficialClaude() {
        return providerService.ensureOfficialClaudeProvider();
    }

    /** 生成 Claude 授权链接：前端拿到 url 后打开浏览器。 */
    @PostMapping("/{id}/oauth/claude/authorize")
    public Map<String, Object> claudeAuthorize(@PathVariable String id) {
        return Map.of("url", claudeOAuthService.buildAuthorizeUrl(id));
    }

    /** 用回调 URL / 授权码完成 Claude 登录（53692 被占用时的手动回退）。 */
    @PostMapping("/{id}/oauth/claude/complete")
    public ResponseEntity<Map<String, Object>> claudeComplete(@PathVariable String id,
            @RequestBody(required = false) CompleteRequest req) {
        String input = req == null ? null : req.input();
        try {
            claudeOAuthService.complete(id, input);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            log.warn("Claude 订阅登录失败 provider={}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("ok", false, "message", readable(e.getMessage())));
        } catch (OAuthTokenExchangeError e) {
            // message 里已含 HTTP status + 响应体（截断），日志留全量以便诊断地区限制等问题
            log.warn("Claude 订阅换 token 失败 provider={}: error={} detail={}",
                id, e.errorCode(), e.getMessage());
            String reason = e.errorCode() != null && !e.errorCode().isBlank()
                ? "provider 返回 " + e.errorCode()
                : "token 端点拒绝了本次请求";
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                "message", "授权码兑换失败（" + reason + "），请重新点击登录获取新的授权链接"));
        }
    }

    /** 退出 Claude 订阅登录：清凭据并回落到 API key 模式。 */
    @DeleteMapping("/{id}/oauth/claude")
    public ResponseEntity<Void> claudeLogout(@PathVariable String id) {
        claudeOAuthService.logout(id);
        return ResponseEntity.noContent().build();
    }

    private static String readable(String message) {
        return message == null || message.isBlank() ? "登录失败，请重试" : message;
    }
}
