package com.nexusai.kb.app.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 知识库画像自动生成触发器 · 2026-11-11。
 *
 * <p><b>源站对照</b>：WeKnora 在四个地方调 {@code requestKnowledgeBaseProfileRefresh}
 * —— 开关打开（{@code knowledgebase.go:757}）、文档删除（{@code knowledge_delete.go:657}）、
 * 摘要后处理完成（{@code knowledge_post_process.go:529}）、上传/重新解析
 * （{@code knowledge_process.go:1502/2799/2875}）。源站走 asynq 队列 + 30 秒去抖。
 *
 * <h2>⭐ 本仓与源站的差异（用户已拍板）</h2>
 * <ul>
 *   <li><b>不去抖</b>：本仓 kb 模块**没有异步任务设施**（源站的 asynq 不可照搬），
 *       且本仓文档解析是同步跑的 ⇒ 直接同步触发。
 *       代价：批量导入会多次触发 ⇒ 靠<b>指纹短路</b>兜底（聚合没变就不调模型）。</li>
 *   <li><b>同步执行</b>：失败不阻断主流程（文档上传/删除照常成功）。</li>
 * </ul>
 *
 * <h2>⭐ 统一判据（各触发点只管调 {@link #trigger}）</h2>
 * <ol>
 *   <li>{@code profile_config.enabled == true}</li>
 *   <li>知识库是 <b>document</b> 类型且<b>非临时</b>（源站 {@code knowledgeBaseProfileEligible}）</li>
 * </ol>
 */
@Service
public class KbProfileTrigger {

    private static final Logger log = LoggerFactory.getLogger(KbProfileTrigger.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KbKnowledgeBaseMapper baseMapper;

    @Autowired(required = false)
    private KbProfileService profileService;

    @Autowired
    public KbProfileTrigger(KbKnowledgeBaseMapper baseMapper) {
        this.baseMapper = baseMapper;
    }

    /**
     * 触发一次自动生成。
     *
     * <p>⚠️ **不抛异常**：画像生成失败不该让「上传文档」「删除文档」「保存设置」失败。
     * 失败信息已经落进 {@code generated_profile.status=failed}，UI 能看到。
     *
     * @param kbId  知识库 ID
     * @param force true ⇒ 强制重新生成（跳过指纹短路）
     */
    public void trigger(String kbId, boolean force) {
        trigger(kbId, force, null);
    }

    /** 同上，但可指定模型。 */
    public void trigger(String kbId, boolean force, String modelName) {
        if (profileService == null) {
            return;
        }
        if (kbId == null || kbId.isBlank()) {
            return;
        }
        KbKnowledgeBaseRecord kb = baseMapper.selectOneById(kbId);
        if (kb == null) {
            return;
        }
        // ── 判据 1：开关是否打开 ────────────────────────────────────
        if (!isEnabled(kb)) {
            return;
        }
        // ── 判据 2：库类型是否适用（源站 knowledgeBaseProfileEligible）──
        if (!KbProfileService.isEligible(kb)) {
            return;
        }
        try {
            KbProfileService.Result r = profileService.generate(kbId, modelName, force);
            log.info("[画像] 自动生成完成 kb={} status={} 文档数={}", kbId, r.status(), r.documentCount());
        } catch (RuntimeException e) {
            // ⚠️ 吞掉：画像是旁路能力，失败不该影响主流程
            log.warn("[画像] 自动生成失败 kb={} 原因={}", kbId, e.getMessage());
        }
    }

    /**
     * 开关是否打开。
     *
     * <p>⚠️ 读的是 {@code profile_config.enabled}（本仓 UI 落的就是这个键）。
     * 源站用的是 {@code ProfileConfig.Enabled}（Go 结构体），语义一致。
     */
    public boolean isEnabled(KbKnowledgeBaseRecord kb) {
        if (kb == null) {
            return false;
        }
        JsonNode cfg = readConfig(kb.getProfileConfig());
        return cfg.path("enabled").asBoolean(false);
    }

    private JsonNode readConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode n = JSON.readTree(raw);
            return n == null || !n.isObject() ? MissingNode.getInstance() : n;
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }
}
