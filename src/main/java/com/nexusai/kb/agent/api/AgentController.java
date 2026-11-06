package com.nexusai.kb.agent.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.agent.KbAgentMdService;
import com.nexusai.kb.agent.KbAgentService;
import com.nexusai.kb.agent.entity.KbAgentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义智能体 REST 端点 · 批次 W10 · 路径对齐源侧 {@code /agents}（11 条）。
 *
 * <p><b>⭐ 真源分工</b>（用户 2026-11-05 裁定）：
 * <ul>
 *   <li><b>MD 文件 = 本体</b>：系统提示词 / 模型 / 工具 / 颜色 —— {@link KbAgentMdService}</li>
 *   <li><b>{@code kb_agent} 表 = 扩展</b>：知识库范围 / 检索参数 / 引用开关 —— {@link KbAgentService}</li>
 * </ul>
 * ⛔ 本类不把提示词/模型写进 DB，也不把知识库范围写进 MD —— 两边各管一半，避免两份口径。
 *
 * <p><b>创建/删除必须两边一起动</b>：只写 DB 会造出「有配置、无实体」的孤儿；
 * 只写 MD 则扩展配置丢失。删除同理（{@link #delete} 先删扩展再删文件，
 * 文件删除失败会如实抛出，不留半状态）。
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    @Autowired private KbAgentMdService md;
    @Autowired private KbAgentService extensions;

    @Autowired(required = false)
    private ToolRegistry toolRegistry;

    /**
     * 智能体类型预设（源侧 {@code GET /agents/type-presets}）。
     *
     * <p>只给**分类元数据**，不是智能体本体 —— 选预设后前端用它的默认提示词/工具预填表单，
     * 真正落盘仍是 MD。
     *
     * @return 4 种预设定元数据
     */
    @GetMapping("/type-presets")
    public List<TypePreset> typePresets() {
        return List.of(
            new TypePreset("rag-qa", "知识问答",
                "基于知识库的 RAG 问答：先检索再回答，答案要给出处",
                "search_knowledge, list_documents"),
            new TypePreset("wiki-qa", "Wiki 问答",
                "围绕知识库 Wiki 页面作答，适合结构化知识查询",
                "search_knowledge"),
            new TypePreset("hybrid-rag-wiki", "混合（RAG + Wiki）",
                "同时查文档分块与 Wiki 页面，覆盖两种知识形态",
                "search_knowledge, list_documents, read_document"),
            new TypePreset("custom", "自定义",
                "不套预设，提示词与工具完全自己定",
                null));
    }

    /**
     * 提示词占位符（源侧 {@code GET /agents/placeholders}）。
     *
     * <p>⚠️ 本仓的占位符替换在 {@code ArgumentSubstitution} 里，清单以那边为准；
     * 这里只暴露界面展示需要的常见几项，⛔ 不编造不存在的占位符。
     *
     * @return 占位符定义
     */
    @GetMapping("/placeholders")
    public List<Placeholder> placeholders() {
        return List.of(
            new Placeholder("query", "用户本次提问"),
            new Placeholder("contexts", "检索到的知识片段（带编号）"),
            new Placeholder("conversation", "历史对话"),
            new Placeholder("current_time", "当前时间"),
            new Placeholder("answer", "上一轮的答案（改写场景用）"));
    }

    /**
     * 可选工具清单（源侧 {@code GET /agents/tools}）。
     *
     * <p>⭐ 标注 {@code exclusive: true} 的工具**默认不激活**，
     * 只有在 MD 的 {@code tools:} 里点名才可见（见 {@code @ExclusiveTool}）。
     * 前端应把这类工具单独分组，并提示「需在智能体里显式启用」。
     *
     * @return 工具清单
     */
    @GetMapping("/tools")
    public List<ToolInfo> tools() {
        if (toolRegistry == null) {
            return List.of();
        }
        List<ToolInfo> out = new ArrayList<>();
        for (Tool t : toolRegistry.all()) {
            if (t == null || t.name() == null) {
                continue;
            }
            out.add(new ToolInfo(t.name(), t.description(), toolRegistry.isExclusive(t.name())));
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    /**
     * 智能体列表（合并 MD + 扩展配置）。
     *
     * <p>⭐ 只有 MD 存在才算「智能体」；{@code kb_agent} 里有行但 MD 没了 ⇒ 标
     * {@code md_missing: true} 并仍然列出 —— 用户需要看到它才能清理，
     * ⛔ 静默隐藏会让孤儿行永远留在库里。
     *
     * @return 列表
     */
    @GetMapping
    public List<AgentSummary> list() {
        List<AgentSummary> out = new ArrayList<>();
        for (String id : md.listMdIds()) {
            out.add(summaryOf(id, false));
        }
        // DB 里存在但 MD 已不在 ⇒ 孤儿（如实标出，不隐藏）
        for (String id : extensions.listExtensionIds()) {
            if (!md.exists(id)) {
                out.add(summaryOf(id, true));
            }
        }
        return out;
    }

    /**
     * 智能体详情（MD 原文 + 扩展配置）。
     *
     * @param id 智能体 id（= MD 的 agentType）
     * @return 详情
     */
    @GetMapping("/{id}")
    public AgentDetail get(@PathVariable String id) {
        String agentId = md.requireSafeId(id);
        if (!md.exists(agentId)) {
            throw new NotFoundException("智能体不存在: " + agentId);
        }
        return detailOf(agentId, false);
    }

    /**
     * 创建智能体（写 MD + 扩展配置）。
     *
     * @param req 请求体
     * @return 创建后的详情
     */
    @PostMapping
    public AgentDetail create(@RequestBody CreateRequest req) {
        if (req == null) {
            throw new ValidationException("请求体不能为空");
        }
        String agentId = md.requireSafeId(req.id());
        if (md.exists(agentId)) {
            throw new ConflictException("智能体已存在: " + agentId);
        }
        md.write(agentId, new KbAgentMdService.MdContent(
            req.name(), req.description(), req.tools(),
            req.model(), req.mainModel(), req.color(), req.systemPrompt()));
        try {
            if (req.extension() != null) {
                extensions.upsert(agentId, req.extension());
            }
        } catch (RuntimeException e) {
            // ⚠️ 扩展写失败 ⇒ 回滚 MD，避免「文件在、配置半截」
            md.delete(agentId);
            throw e;
        }
        log.info("[agents] 已创建 id={}", agentId);
        return detailOf(agentId, false);
    }

    /**
     * 更新智能体（整体替换 MD 内容；扩展配置按请求覆盖）。
     *
     * @param id  智能体 id
     * @param req 请求体
     * @return 更新后的详情
     */
    @PutMapping("/{id}")
    public AgentDetail update(@PathVariable String id, @RequestBody UpdateRequest req) {
        String agentId = md.requireSafeId(id);
        if (!md.exists(agentId)) {
            throw new NotFoundException("智能体不存在: " + agentId);
        }
        if (req == null || req.systemPrompt() == null || req.systemPrompt().isBlank()) {
            throw new ValidationException("系统提示词不能为空（PUT 是整体替换）");
        }
        md.write(agentId, new KbAgentMdService.MdContent(
            req.name(), req.description(), req.tools(),
            req.model(), req.mainModel(), req.color(), req.systemPrompt()));
        if (req.extension() != null) {
            extensions.upsert(agentId, req.extension());
        }
        log.info("[agents] 已整体更新 id={}", agentId);
        return detailOf(agentId, false);
    }

    /**
     * 部分更新智能体（只改传了的字段）。
     *
     * <p>⚠️ 三态：字段缺省 = 不改；传空串 = 清空该键（从 MD 里去掉行）。
     * 这与源侧 PATCH 语义一致。
     *
     * @param id  智能体 id
     * @param req 请求体
     * @return 更新后的详情
     */
    @PatchMapping("/{id}")
    public AgentDetail patch(@PathVariable String id, @RequestBody PatchRequest req) {
        String agentId = md.requireSafeId(id);
        if (!md.exists(agentId)) {
            throw new NotFoundException("智能体不存在: " + agentId);
        }
        KbAgentMdService.MdView cur = md.readView(agentId);
        md.write(agentId, new KbAgentMdService.MdContent(
            pick(req.name(), cur.name()),
            pick(req.description(), cur.description()),
            pick(req.tools(), cur.tools()),
            pick(req.model(), cur.model()),
            pick(req.mainModel(), cur.mainModel()),
            pick(req.color(), cur.color()),
            pick(req.systemPrompt(), cur.body())));
        if (req.extension() != null) {
            extensions.upsert(agentId, req.extension());
        }
        log.info("[agents] 已部分更新 id={}", agentId);
        return detailOf(agentId, false);
    }

    /**
     * 删除智能体（删扩展配置 + 删 MD）。
     *
     * <p>⚠️ 顺序：先删扩展、再删文件。若文件删失败会抛错 —— 那时扩展已删，
     * 结果 = 「MD 还在、扩展没了」，是可自愈的半状态（再删一次即成功），
     * 比反过来「文件没了、配置还在」更容易排查。
     *
     * @param id 智能体 id
     * @return 删除结果
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        String agentId = md.requireSafeId(id);
        boolean hadMd = md.exists(agentId);
        if (!hadMd && extensions.find(agentId) == null) {
            throw new NotFoundException("智能体不存在: " + agentId);
        }
        extensions.deleteExtension(agentId);
        boolean removed = md.delete(agentId);
        log.info("[agents] 已删除 id={} md删除={}", agentId, removed);
        return Map.of("id", agentId, "deleted", true, "mdRemoved", removed);
    }

    /**
     * 复制智能体（MD + 扩展配置一起复制）。
     *
     * @param id  源 id
     * @param req 请求体（新 id，可选的显示名）
     * @return 新智能体详情
     */
    @PostMapping("/{id}/copy")
    public AgentDetail copy(@PathVariable String id, @RequestBody CopyRequest req) {
        String sourceId = md.requireSafeId(id);
        if (!md.exists(sourceId)) {
            throw new NotFoundException("智能体不存在: " + sourceId);
        }
        if (req == null || req.newId() == null || req.newId().isBlank()) {
            throw new ValidationException("newId 不能为空");
        }
        String targetId = md.requireSafeId(req.newId());
        md.copy(sourceId, targetId, req.newName());
        KbAgentRecord src = extensions.find(sourceId);
        if (src != null) {
            extensions.upsert(targetId, toUpdateRequest(src));
        }
        log.info("[agents] 已复制 {} → {}", sourceId, targetId);
        return detailOf(targetId, false);
    }

    /**
     * 推荐问题（源侧 {@code GET /agents/{id}/suggested-questions}）。
     *
     * <p>⚠️ 本仓没有「智能体级推荐问题」的持久化存储，也没有对应的生成链路。
     * 如实返回空数组 + 原因，⛔ 不编造问题（否则前端会显示假内容）。
     *
     * @param id 智能体 id
     * @return 空列表
     */
    @GetMapping("/{id}/suggested-questions")
    public List<String> suggestedQuestions(@PathVariable String id) {
        String agentId = md.requireSafeId(id);
        if (!md.exists(agentId)) {
            throw new NotFoundException("智能体不存在: " + agentId);
        }
        log.info("[agents] 推荐问题本仓未实现，如实返回空 agent={}", agentId);
        return List.of();
    }

    // ── 内部 ───────────────────────────────────────────────

    private AgentSummary summaryOf(String agentId, boolean mdMissing) {
        KbAgentMdService.MdView v = null;
        if (!mdMissing) {
            try {
                v = md.readView(agentId);
            } catch (RuntimeException e) {
                log.warn("[agents] 读 MD 失败 id={}（列表降级为仅有 id）: {}", agentId, e.toString());
            }
        }
        KbAgentRecord ext = extensions.find(agentId);
        return new AgentSummary(
            agentId,
            v == null ? null : v.name(),
            v == null ? null : v.description(),
            v == null ? null : v.model(),
            v == null ? null : v.mainModel(),
            v == null ? null : v.color(),
            ext == null ? null : ext.getKbSelectionMode(),
            ext == null ? null : ext.getKnowledgeBases(),
            mdMissing);
    }

    private AgentDetail detailOf(String agentId, boolean mdMissing) {
        KbAgentMdService.MdView v = mdMissing ? null : md.readView(agentId);
        KbAgentRecord ext = extensions.find(agentId);
        Map<String, Object> extMap = new LinkedHashMap<>();
        if (ext != null) {
            extMap.put("kb_selection_mode", ext.getKbSelectionMode());
            extMap.put("knowledge_bases", ext.getKnowledgeBases());
            extMap.put("retrieve_kb_only_when_mentioned",
                ext.getRetrieveKbOnlyWhenMentioned() != null && ext.getRetrieveKbOnlyWhenMentioned() != 0);
            extMap.put("embedding_top_k", ext.getEmbeddingTopK());
            extMap.put("keyword_threshold", ext.getKeywordThreshold());
            extMap.put("vector_threshold", ext.getVectorThreshold());
            extMap.put("rerank_top_k", ext.getRerankTopK());
            extMap.put("rerank_threshold", ext.getRerankThreshold());
            extMap.put("citation_enabled", ext.getCitationEnabled() == null
                ? null : ext.getCitationEnabled() != 0);
        }
        return new AgentDetail(
            agentId,
            v == null ? null : v.name(),
            v == null ? null : v.description(),
            v == null ? null : v.tools(),
            v == null ? null : v.model(),
            v == null ? null : v.mainModel(),
            v == null ? null : v.color(),
            v == null ? null : v.body(),
            extMap,
            mdMissing);
    }

    private static String pick(String incoming, String current) {
        // PATCH 三态：null = 不改（传 current）；空串 = 清空（传 null，renderMd 会跳过该键）
        if (incoming == null) {
            return current;
        }
        return incoming.isBlank() ? null : incoming;
    }

    private KbAgentService.UpdateRequest toUpdateRequest(KbAgentRecord r) {
        return new KbAgentService.UpdateRequest(
            r.getKbSelectionMode(),
            // ⚠️ 必须显式带列表：目标行是**新建**的，传 null 会保留默认空数组 ⇒ 复制出来的
            //    智能体「库范围 = selected 但一个库都没选」，看起来像配置丢了。
            extensions.readKnowledgeBaseIds(r.getId()),
            r.getRetrieveKbOnlyWhenMentioned() != null && r.getRetrieveKbOnlyWhenMentioned() != 0,
            r.getEmbeddingTopK(),
            r.getKeywordThreshold(),
            r.getVectorThreshold(),
            r.getRerankTopK(),
            r.getRerankThreshold(),
            r.getCitationEnabled() == null ? null : r.getCitationEnabled() != 0);
    }

    // ── DTO ────────────────────────────────────────────────

    /** 智能体类型预设。 */
    public record TypePreset(String type, String displayName, String description, String defaultTools) {
    }

    /** 提示词占位符。 */
    public record Placeholder(String key, String description) {
    }

    /** 工具清单项（{@code exclusive} = 需在 MD 中显式点名才可见）。 */
    public record ToolInfo(String name, String description, boolean exclusive) {
    }

    /** 列表项。 */
    public record AgentSummary(
        String id, String name, String description, String model,
        @JsonAlias("main-model") String mainModel,
        String color, String kbSelectionMode, String knowledgeBases, boolean mdMissing
    ) {
    }

    /** 详情（MD 字段 + 扩展配置）。 */
    public record AgentDetail(
        String id, String name, String description, String tools,
        String model, @JsonAlias("main-model") String mainModel, String color, String systemPrompt,
        Map<String, Object> extension, boolean mdMissing
    ) {
    }

    /** 创建请求。 */
    public record CreateRequest(
        String id, String name, String description, String tools,
        String model, @JsonAlias("main-model") String mainModel, String color, String systemPrompt,
        KbAgentService.UpdateRequest extension
    ) {
    }

    /** 整体更新请求（PUT）。 */
    public record UpdateRequest(
        String name, String description, String tools,
        String model, @JsonAlias("main-model") String mainModel, String color, String systemPrompt,
        KbAgentService.UpdateRequest extension
    ) {
    }

    /** 部分更新请求（PATCH）。 */
    public record PatchRequest(
        String name, String description, String tools,
        String model, @JsonAlias("main-model") String mainModel, String color, String systemPrompt,
        KbAgentService.UpdateRequest extension
    ) {
    }

    /** 复制请求。 */
    public record CopyRequest(String newId, String newName) {
    }
}
