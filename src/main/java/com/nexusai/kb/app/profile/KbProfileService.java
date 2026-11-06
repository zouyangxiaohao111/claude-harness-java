package com.nexusai.kb.app.profile;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.kb.domain.KbActivityService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库画像生成 · 批次 W4（{@code POST /knowledge-bases/{id}/profile/generate}）。
 *
 * <h2>⭐⭐ 空库不调模型</h2>
 * 见 {@link KbProfilePrompts}：空库调模型必然编造内容。这里在入口就短路，
 * 写入固定文案 {@link KbProfilePrompts#EMPTY_PROFILE}。
 *
 * <h2>⭐ 生成失败保留旧画像</h2>
 * 不能让一次网络抖动把用户已有的画像清空。失败时：不写库、把旧值原样返回、HTTP 200
 * （不是 500 —— 「没生成新的」不等于「请求失败」；前端据此提示「保留原描述」）。
 */
@Service
public class KbProfileService {

    private static final Logger log = LoggerFactory.getLogger(KbProfileService.class);

    /** 画像长度上限（与提示词的「不超过 200 字」配套，防模型超写）。 */
    private static final int MAX_PROFILE_CHARS = 1000;

    /** 温度：描述类文本要稳定，不要发散。 */
    private static final double TEMPERATURE = 0.3;

    /** 记账标签（与其它旁路调用的命名口径一致）。 */
    private static final String USAGE_LABEL = "kb_profile_generate";

    private final KbKnowledgeBaseMapper baseMapper;
    private final KbKnowledgeMapper knowledgeMapper;
    private final KbActivityService activityService;

    @Autowired(required = false)
    private LlmSideCallClient sideCallClient;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    @Autowired
    public KbProfileService(KbKnowledgeBaseMapper baseMapper,
                            KbKnowledgeMapper knowledgeMapper,
                            KbActivityService activityService) {
        this.baseMapper = baseMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.activityService = activityService;
    }

    /**
     * 生成画像。
     *
     * @param kbId      知识库
     * @param modelName 指定模型（可空 ⇒ 用库的 summary_model_id ⇒ 再空则失败）
     * @return 生成结果
     */
    public Result generate(String kbId, String modelName) {
        KbKnowledgeBaseRecord kb = requireBase(kbId);
        List<KbKnowledgeRecord> docs = knowledgeMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .orderBy("create_time", true));

        if (docs.isEmpty()) {
            // ⚠️ 不调模型：空标题列表调模型 = 必然编造
            String text = KbProfilePrompts.EMPTY_PROFILE;
            saveProfile(kbId, text);
            activityService.record(kbId, KbActivityService.A_PROFILE_GENERATE, "success",
                "knowledge_base", kbId, "{\"documents\":0,\"model_calls\":0}");
            log.info("[画像] 知识库 {} 无文档，写入固定文案（未调模型）", kbId);
            return new Result(text, "empty", 0, "");
        }

        String model = firstNonBlank(modelName, kb.getSummaryModelId());
        if (model == null) {
            throw new ValidationException(
                "该知识库未配置摘要模型（summary_model_id），请在知识库设置里指定，或请求里传 model");
        }
        ProviderConfig config = resolveConfig(model);
        if (config == null || sideCallClient == null) {
            // ⚠️ 不写库：保留旧画像
            log.warn("[画像] 模型不可用 kb={} model={}，保留原描述", kbId, model);
            return new Result(kb.getGeneratedProfile() == null ? "" : kb.getGeneratedProfile(),
                "unavailable", 0, model);
        }

        List<String> titles = new ArrayList<>(docs.size());
        for (KbKnowledgeRecord d : docs) {
            String t = firstNonBlank(d.getTitle(), d.getFileName());
            if (t != null) {
                titles.add(t);
            }
        }
        if (titles.isEmpty()) {
            String text = KbProfilePrompts.EMPTY_PROFILE;
            saveProfile(kbId, text);
            return new Result(text, "empty", 0, model);
        }

        String generated;
        try {
            generated = sideCallClient.chat(
                config, model,
                KbProfilePrompts.SYSTEM,
                KbProfilePrompts.userMessage(kb.getName(), titles),
                // 字段顺序照 LlmProvider.ChatRequestOptions 定义（17 参数）：
                //   history / tools / outputFormat / thinkingConfig / temperature / querySource
                //   / abortController / maxTokens / skipCacheWrite / enablePromptCaching
                //   / agents / hasAppendSystemPrompt / mcpTools / isNonInteractiveSession
                //   / toolChoice / agentContext
                new LlmProvider.ChatRequestOptions(
                    null, null, null,
                    // 描述撰写是机械提炼，关掉思考（省时省钱，输出也更稳）
                    LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
                    TEMPERATURE, USAGE_LABEL,
                    null, 500, null, null,
                    null, null, null, null,
                    null, null),
                LlmSideCallClient.SideCallUsage.standalone(
                    com.nexusai.infra.llm.UsageCategory.SIDE_CALL, "kb-profile"));
        } catch (RuntimeException e) {
            log.warn("[画像] 调用失败 kb={} 原因={}，保留原描述", kbId, e.getMessage());
            return new Result(kb.getGeneratedProfile() == null ? "" : kb.getGeneratedProfile(),
                "failed", 0, model);
        }

        String cleaned = sanitize(generated);
        if (cleaned.isEmpty()) {
            log.warn("[画像] 模型返回空文本 kb={}，保留原描述", kbId);
            return new Result(kb.getGeneratedProfile() == null ? "" : kb.getGeneratedProfile(),
                "empty_response", 0, model);
        }
        saveProfile(kbId, cleaned);
        activityService.record(kbId, KbActivityService.A_PROFILE_GENERATE, "success",
            "knowledge_base", kbId,
            "{\"documents\":" + titles.size() + ",\"model_calls\":1}");
        log.info("[画像] 已生成 kb={} 文档数={} 字数={}", kbId, titles.size(), cleaned.length());
        return new Result(cleaned, "generated", titles.size(), model);
    }

    /** 只读画像（不在端点列表里，供其它服务拼提示词）。 */
    public String current(String kbId) {
        KbKnowledgeBaseRecord kb = requireBase(kbId);
        return kb.getGeneratedProfile() == null ? "" : kb.getGeneratedProfile();
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private void saveProfile(String kbId, String text) {
        KbKnowledgeBaseRecord row = baseMapper.selectOneById(kbId);
        if (row == null) {
            throw new NotFoundException("知识库 " + kbId + " 不存在");
        }
        row.setGeneratedProfile(text);
        row.setUpdateTime(LocalDateTime.now());
        // `update(row, false)`：显式写 NULL。这里 text 恒非 null，但与其它写点保持同款更安全。
        baseMapper.update(row, false);
    }

    private KbKnowledgeBaseRecord requireBase(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
        KbKnowledgeBaseRecord row = baseMapper.selectOneById(kbId);
        if (row == null) {
            throw new NotFoundException("知识库 " + kbId + " 不存在");
        }
        return row;
    }

    private ProviderConfig resolveConfig(String model) {
        if (modelConfigResolver == null) {
            return null;
        }
        try {
            ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(model);
            return resolved == null ? null : resolved.config();
        } catch (RuntimeException e) {
            log.warn("[画像] 模型配置解析失败 model={} 原因={}", model, e.getMessage());
            return null;
        }
    }

    /**
     * 清洗模型输出：去代码围栏、去首尾引号、去「描述：」前缀、截断超长。
     *
     * <p>⚠️ 模型很爱把纯文本也包进 ``` 里 —— 不清掉的话画像会带着围栏入库，
     * 拼进提示词时看起来像「代码块」，影响后续判定。
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            }
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) {
                s = s.substring(0, lastFence);
            }
            s = s.trim();
        }
        // 去掉「描述：」这类前缀（提示词要求不要，但模型偶尔还是加）
        for (String prefix : new String[]{"描述：", "描述:", "知识库描述：", "知识库描述:"}) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).trim();
                break;
            }
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.length() > MAX_PROFILE_CHARS) {
            s = s.substring(0, MAX_PROFILE_CHARS);
        }
        return s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * 生成结果。
     *
     * @param profile     画像文本
     * @param status      generated | empty | unavailable | failed | empty_response
     * @param documentCount 参与生成的文档数
     * @param model       实际使用的模型（可为空）
     */
    public record Result(String profile, String status, int documentCount, String model) {
        /** 是否真的调用了模型。 */
        public boolean usedModel() {
            return "generated".equals(status);
        }
    }

    /** 时间格式化（出站）。 */
    public static String formatTime(LocalDateTime t) {
        return KbTimeFormat.format(t);
    }
}
