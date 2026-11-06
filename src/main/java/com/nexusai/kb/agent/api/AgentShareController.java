package com.nexusai.kb.agent.api;

import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.agent.KbAgentMdService;
import com.nexusai.kb.agent.share.KbShareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 智能体共享端点 · 批次 W11 · 3 条（源侧 {@code /agents/{id}/shares}）
 * + 1 条 {@code GET /shared-agents}（源侧存在，同为「共享给我的」）。
 *
 * <p>⚠️⚠️ 与知识库共享同样的边界：<b>本仓单机单用户，没有接收方</b>。
 * 记录会写入 {@code kb_share}（{@code resource_type='agent'}），但不会产生跨用户效果；
 * 响应带 {@code effective:false} 与原因，⛔ 不假装共享成功。
 *
 * <p>⚠️ 路径前缀与 {@link AgentController} 相同（{@code /api/v1/agents}）但路径不重叠
 * （这里是 {@code /{id}/shares}），Spring 按最长匹配路由，不会冲突。
 */
@RestController
public class AgentShareController {

    private static final Logger log = LoggerFactory.getLogger(AgentShareController.class);

    @Autowired private KbShareService shareService;
    @Autowired private KbAgentMdService mdService;

    /**
     * 共享智能体（源侧 {@code POST /agents/:id/shares}）。
     *
     * @param id  智能体 id
     * @param req 权限
     * @return 共享记录（含 {@code effective:false}）
     */
    @PostMapping("/api/v1/agents/{id}/shares")
    public Map<String, Object> share(@PathVariable String id,
                                     @RequestBody(required = false) ShareRequest req) {
        requireAgent(id);
        String permission = req == null ? null : req.permission();
        log.info("[共享] POST agent={} perm={}", id, permission);
        return shareService.shareAgent(id, permission);
    }

    /**
     * 智能体的共享列表（源侧 {@code GET /agents/:id/shares}）。
     *
     * @param id 智能体 id
     * @return 列表
     */
    @GetMapping("/api/v1/agents/{id}/shares")
    public List<Map<String, Object>> list(@PathVariable String id) {
        requireAgent(id);
        return shareService.listAgentShares(id);
    }

    /**
     * 取消共享（源侧 {@code DELETE /agents/:id/shares/:share_id}）。
     *
     * @param id      智能体 id
     * @param shareId 共享记录
     * @return 删除结果
     */
    @DeleteMapping("/api/v1/agents/{id}/shares/{shareId}")
    public Map<String, Object> delete(@PathVariable String id, @PathVariable String shareId) {
        requireAgent(id);
        shareService.deleteAgentShare(id, shareId);
        return Map.of("id", shareId, "deleted", true);
    }

    /**
     * 共享给我的智能体（源侧 {@code GET /shared-agents}）。
     *
     * <p>⚠️ 本仓恒空：没有「别人」，所以没有「别人共享给我的」。
     *
     * @return 恒空列表
     */
    @GetMapping("/api/v1/shared-agents")
    public List<Map<String, Object>> sharedToMe() {
        return shareService.sharedAgentsToMe();
    }

    // ── 内部 ───────────────────────────────────────────────

    /** ⚠️ 必须校验智能体存在：否则会给一个不存在的 id 存下共享记录。 */
    private void requireAgent(String id) {
        if (id == null || id.isBlank()) {
            throw new ValidationException("agent id 不能为空");
        }
        if (!mdService.exists(id.trim())) {
            throw new NotFoundException("智能体不存在: " + id);
        }
    }

    /** 共享请求体。 */
    public record ShareRequest(String permission) {
    }
}
