package com.nexusai.kb.agent.api;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.agent.share.KbShareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库共享端点 · 批次 W11 · 5 条（用户裁定「C 建表但无接收方」）。
 *
 * <p>⚠️⚠️ <b>这些端点在本仓不会产生跨用户效果</b> —— 本仓是单机单用户，
 * 没有第二个组织。响应里每个共享记录都带 {@code effective:false} 与原因，
 * {@code GET /shared-knowledge-bases} 恒返回空。
 * <b>⛔ 不要为了让界面有数据就返回自己的库</b> —— 那会让用户以为共享生效了。
 *
 * <p>路径对齐源侧：4 条在 {@code /knowledge-bases/{id}/shares} 下，1 条是
 * {@code /shared-knowledge-bases}。
 */
@RestController
public class KnowledgeShareController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeShareController.class);

    @Autowired private KbShareService shareService;

    /**
     * 共享知识库（源侧 {@code POST /knowledge-bases/{id}/shares}）。
     *
     * @param id  知识库
     * @param req 权限
     * @return 共享记录（含 {@code effective:false}）
     */
    @PostMapping("/api/v1/knowledge-bases/{id}/shares")
    public Map<String, Object> share(@PathVariable String id, @RequestBody(required = false) ShareRequest req) {
        String permission = req == null ? null : req.permission();
        log.info("[共享] POST kb={} perm={}", id, permission);
        return shareService.shareKnowledgeBase(id, permission);
    }

    /**
     * 共享列表（源侧 {@code GET /knowledge-bases/{id}/shares}）。
     *
     * @param id 知识库
     * @return 列表
     */
    @GetMapping("/api/v1/knowledge-bases/{id}/shares")
    public List<Map<String, Object>> list(@PathVariable String id) {
        return shareService.listKnowledgeBaseShares(id);
    }

    /**
     * 修改共享权限（源侧 {@code PUT /knowledge-bases/{id}/shares/{share_id}}）。
     *
     * @param id      知识库
     * @param shareId 共享记录
     * @param req     新权限
     * @return 更新后的记录
     */
    @PutMapping("/api/v1/knowledge-bases/{id}/shares/{shareId}")
    public Map<String, Object> update(@PathVariable String id, @PathVariable String shareId,
                                      @RequestBody(required = false) ShareRequest req) {
        String permission = req == null ? null : req.permission();
        if (permission == null || permission.isBlank()) {
            throw new ValidationException("permission 不能为空");
        }
        return shareService.updateKnowledgeBaseShare(id, shareId, permission);
    }

    /**
     * 取消共享（源侧 {@code DELETE /knowledge-bases/{id}/shares/{share_id}}）。
     *
     * @param id      知识库
     * @param shareId 共享记录
     * @return 删除结果
     */
    @DeleteMapping("/api/v1/knowledge-bases/{id}/shares/{shareId}")
    public Map<String, Object> delete(@PathVariable String id, @PathVariable String shareId) {
        shareService.deleteKnowledgeBaseShare(id, shareId);
        return Map.of("id", shareId, "deleted", true);
    }

    /**
     * 共享给我的知识库（源侧 {@code GET /shared-knowledge-bases}）。
     *
     * <p>⚠️ 本仓恒空：没有「别人」，所以没有「别人共享给我的」。
     *
     * @return 恒空列表
     */
    @GetMapping("/api/v1/shared-knowledge-bases")
    public List<Map<String, Object>> sharedToMe() {
        return shareService.sharedKnowledgeBasesToMe();
    }

    /** 共享请求体。 */
    public record ShareRequest(String permission) {
    }
}
