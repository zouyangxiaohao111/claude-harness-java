package com.nexusai.kb.agent.share;

import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.agent.share.mapper.KbShareMapper;
import com.nexusai.kb.domain.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 资源分享（知识库 + 智能体）· 批次 W11 · 用户裁定「C 建表但无接收方」。
 *
 * <p><b>⚠️⚠️ 先说清楚这条能力的边界</b>：
 * 本仓是**单机单用户**（W9 已裁「单机单主体」）。这里写进去的共享记录
 * <b>不会让任何其他人看到这个资源</b> —— 因为没有「其他人」。
 *
 * <p>那为什么还做？用户明确选了 C：
 * <ul>
 *   <li>端点与表结构与源侧对齐，将来接多用户时不用改表；</li>
 *   <li>{@code GET /shared-knowledge-bases} 会如实返回空（本机没有「别人共享给我的」）；</li>
 *   <li>⛔ **不假装成功**：接口响应里带 {@code effective: false} 与原因，
 *       避免前端或用户以为共享真的生效了。</li>
 * </ul>
 *
 * <p>源侧有两张同构表 {@code kb_shares} / {@code agent_shares}，
 * 本仓合并成 {@code kb_share} 一张，用 {@code resource_type} 区分。
 */
@Service
public class KbShareService {

    private static final Logger log = LoggerFactory.getLogger(KbShareService.class);

    /** 本仓唯一的组织 id（单机单用户）。 */
    public static final String LOCAL_ORG = "local";

    /** 本仓唯一的用户 id（对齐 W9 的 {@code local_user:default}）。 */
    public static final String LOCAL_USER = "local_user";

    /** 合法权限值（对齐源侧 admin|editor|viewer）。 */
    public static final List<String> PERMISSIONS = List.of("admin", "editor", "viewer");

    public static final String TYPE_KNOWLEDGE_BASE = "knowledge_base";
    public static final String TYPE_AGENT = "agent";

    @Autowired private KbShareMapper mapper;
    @Autowired private KnowledgeBaseService knowledgeBaseService;

    // ── 知识库分享 ─────────────────────────────────────────

    /** 共享知识库（源侧 {@code POST /knowledge-bases/{id}/shares}）。 */
    public Map<String, Object> shareKnowledgeBase(String knowledgeBaseId, String permission) {
        String kbId = requireKb(knowledgeBaseId);
        return doShare(TYPE_KNOWLEDGE_BASE, kbId, permission);
    }

    /** 知识库的分享列表（源侧 {@code GET /knowledge-bases/{id}/shares}）。 */
    public List<Map<String, Object>> listKnowledgeBaseShares(String knowledgeBaseId) {
        return doList(TYPE_KNOWLEDGE_BASE, requireKb(knowledgeBaseId));
    }

    /** 修改知识库分享权限（源侧 {@code PUT /knowledge-bases/{id}/shares/{share_id}}）。 */
    public Map<String, Object> updateKnowledgeBaseShare(String knowledgeBaseId, String shareId,
                                                        String permission) {
        return doUpdate(TYPE_KNOWLEDGE_BASE, requireKb(knowledgeBaseId), shareId, permission);
    }

    /** 取消知识库分享（源侧 {@code DELETE /knowledge-bases/{id}/shares/{share_id}}）。 */
    public void deleteKnowledgeBaseShare(String knowledgeBaseId, String shareId) {
        doDelete(TYPE_KNOWLEDGE_BASE, requireKb(knowledgeBaseId), shareId);
    }

    // ── 智能体分享（源侧 /agents/{id}/shares，3 条）─────────

    /**
     * 共享智能体（源侧 {@code POST /agents/{id}/shares}）。
     *
     * <p>⚠️ 智能体本体的校验在 controller 层做（必须存在），
     * 这里只校验非空 —— 避免 kb 包反向依赖 agent 包。
     */
    public Map<String, Object> shareAgent(String agentId, String permission) {
        return doShare(TYPE_AGENT, requireId(agentId, "agent id"), permission);
    }

    /** 智能体的分享列表（源侧 {@code GET /agents/{id}/shares}）。 */
    public List<Map<String, Object>> listAgentShares(String agentId) {
        return doList(TYPE_AGENT, requireId(agentId, "agent id"));
    }

    /** 取消智能体分享（源侧 {@code DELETE /agents/{id}/shares/{share_id}}）。 */
    public void deleteAgentShare(String agentId, String shareId) {
        doDelete(TYPE_AGENT, requireId(agentId, "agent id"), shareId);
    }

    // ── 共享给我的 ─────────────────────────────────────────

    /**
     * 共享给我的知识库（源侧 {@code GET /shared-knowledge-bases}）。
     *
     * <p>⚠️ <b>本仓恒为空</b>：没有「别人」，所以没有「别人共享给我的」。
     * ⛔ 不要为了让前端有数据就返回自己的库 —— 那会让用户以为共享功能生效了。
     */
    public List<Map<String, Object>> sharedKnowledgeBasesToMe() {
        log.info("[共享] shared-knowledge-bases 请求 —— 本仓单机单用户，如实返回空");
        return List.of();
    }

    /** 共享给我的智能体（源侧 {@code GET /shared-agents}）· 同上，恒空。 */
    public List<Map<String, Object>> sharedAgentsToMe() {
        log.info("[共享] shared-agents 请求 —— 本仓单机单用户，如实返回空");
        return List.of();
    }

    // ── 内部 ───────────────────────────────────────────────

    private Map<String, Object> doShare(String type, String resourceId, String permission) {
        String perm = normalizePermission(permission);
        for (KbShareRecord r : mapper.selectAll()) {
            if (type.equals(r.getResourceType())
                && resourceId.equals(r.getResourceId())
                && LOCAL_ORG.equals(r.getOrganizationId())) {
                throw new ConflictException("该资源已共享过，请用 PUT 修改权限");
            }
        }
        KbShareRecord row = new KbShareRecord();
        row.setId("share-" + UUID.randomUUID().toString().substring(0, 8));
        row.setResourceType(type);
        row.setResourceId(resourceId);
        row.setOrganizationId(LOCAL_ORG);
        row.setSharedByUserId(LOCAL_USER);
        row.setSourceTenantId(0);
        row.setPermission(perm);
        mapper.insert(row);
        log.info("[共享] 已写入 type={} resource={} perm={}（⚠️ 本仓无接收方，不产生跨用户效果）",
            type, resourceId, perm);
        return toDto(row);
    }

    private List<Map<String, Object>> doList(String type, String resourceId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbShareRecord r : mapper.selectAll()) {
            if (type.equals(r.getResourceType()) && resourceId.equals(r.getResourceId())) {
                out.add(toDto(r));
            }
        }
        return out;
    }

    private Map<String, Object> doUpdate(String type, String resourceId, String shareId,
                                         String permission) {
        KbShareRecord row = requireShare(type, resourceId, shareId);
        row.setPermission(normalizePermission(permission));
        mapper.update(row);
        log.info("[共享] 权限已更新 type={} resource={} share={} perm={}",
            type, resourceId, shareId, row.getPermission());
        return toDto(row);
    }

    private void doDelete(String type, String resourceId, String shareId) {
        requireShare(type, resourceId, shareId);
        mapper.deleteById(shareId);
        log.info("[共享] 已取消 type={} resource={} share={}", type, resourceId, shareId);
    }

    private KbShareRecord requireShare(String type, String resourceId, String shareId) {
        if (shareId == null || shareId.isBlank()) {
            throw new ValidationException("share_id 不能为空");
        }
        KbShareRecord row = mapper.selectOneById(shareId.trim());
        if (row == null || !type.equals(row.getResourceType())
            || !resourceId.equals(row.getResourceId())) {
            throw new NotFoundException("共享记录不存在: " + shareId);
        }
        return row;
    }

    private String requireKb(String knowledgeBaseId) {
        String id = requireId(knowledgeBaseId, "knowledge_base_id");
        // ⚠️ 库必须存在：否则会存下一条永远对不上的共享记录
        knowledgeBaseService.getById(id);
        return id;
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " 不能为空");
        }
        return value.trim();
    }

    static String normalizePermission(String permission) {
        String p = permission == null || permission.isBlank() ? "viewer" : permission.trim().toLowerCase();
        if (!PERMISSIONS.contains(p)) {
            throw new ValidationException("permission 只能是 " + PERMISSIONS + "，收到: " + permission);
        }
        return p;
    }

    private static Map<String, Object> toDto(KbShareRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("resource_type", r.getResourceType());
        m.put("resource_id", r.getResourceId());
        m.put("organization_id", r.getOrganizationId());
        m.put("shared_by_user_id", r.getSharedByUserId());
        m.put("permission", r.getPermission());
        m.put("created_at", r.getCreateTime() == null ? null : r.getCreateTime().toString());
        // ⚠️ 如实标注：本仓没有接收方，这条记录不产生跨用户效果
        m.put("effective", false);
        m.put("note", "本仓是单机单用户，共享记录不会让其他人看到该资源");
        return m;
    }
}
