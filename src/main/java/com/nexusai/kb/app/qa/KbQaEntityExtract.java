package com.nexusai.kb.app.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.kb.app.ingest.KbPostProcessConfig;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 阶段 3 · EXTRACT_ENTITY（对齐源侧 {@code chat_pipeline/extract_entity.go}）。
 *
 * <h2>这一步在干什么</h2>
 * 把「用户这个问题里提到了哪些实体」问一次模型，抽出实体名，交给下一阶段去图谱里
 * 找这些实体关联的块。它<b>不写库</b>、<b>不改图</b> —— 只是一次查询规划。
 *
 * <h2>⭐ 三条硬约束（每一条都是源侧的语义，改错就会悄悄浪费钱或悄悄答错）</h2>
 * <ol>
 *   <li><b>只对开启图谱的库做</b>：判据复用 {@link KbPostProcessConfig#graphEnabled}
 *       （{@code indexing_strategy.graph_enabled} ∧ {@code extract_config.enabled}）。
 *       全部库都关 ⇒ <b>一次模型都不调</b>直接返回空。⛔ 不能「先调再判断有无结果」。</li>
 *   <li><b>用重写后的问题</b>：改写已经把指代消解、把口语补全，用原问题抽实体等于白抽。</li>
 *   <li><b>失败不阻断</b>：抽取是增强，模型抖动/额度不足只该让本轮少一路检索，
 *       ⛔ 不该让整个问答失败（源侧 {@code next()} 同款）。</li>
 * </ol>
 *
 * <h2>为什么按库分组返回</h2>
 * 实体属于某个库的图谱，<b>跨库同名实体不是同一个东西</b>（同一个「水泵」在两个库里
 * 指的可能不是同一批文档）。⇒ 返回 {@code Map<kbId, List<实体名>>}，下游按库查，
 * ⛔ 不能拍平成一个名字列表再去各库碰。
 */
@Component
public class KbQaEntityExtract {

    private static final Logger log = LoggerFactory.getLogger(KbQaEntityExtract.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 送去抽取的问题长度上限（问题不是文档，超长没意义还烧 token）。 */
    static final int MAX_QUERY_CHARS = 1000;

    /** 抽取温度：要稳定（沿用 W7 图谱抽取的口径）。 */
    private static final double TEMPERATURE = 0.1;

    /** 输出 token 上限（源侧 {@code entityExtractionMaxTokens = 8192}）。 */
    private static final int MAX_TOKENS = 8192;

    /**
     * 查询期实体抽取的系统提示词（逐字对齐源侧 {@code config/config.yaml:94-105}
     * 的 {@code extract_entity.description} + 示例）。
     *
     * <p><b>⭐ 为什么不复用 W7 的 {@code KbGraphPrompts.EXTRACT_ENTITIES}</b>：
     * 那套是<b>文档入库</b>用的 —— 喂的是整篇正文，要按 11 种实体类型分类、
     * 输出 {@code {"title","type","description"}}。查询期问的是「用户这句问话里
     * 提到了哪些东西」，不分类型、只要名字，输出 {@code {"entity":"..."}}。
     * 两者混用会让模型按文档抽取的架子回答一个问题 ⇒ 实测返回 {@code []}（2026-11-06 真机）。
     */
    static final String SYSTEM_PROMPT = """
        Based on the user's question, process the key information extraction task following these steps:
        1. Analyze logical connections: First, fully analyze the text content, identify its core logical relationships, and briefly annotate the core logic type;
        2. Extract key entities: Based on the identified logical relationships, precisely extract key information from the text and classify it into clear entities, ensuring no core information is omitted and no redundant content is added;
        3. Prioritize entities: Sort by the closeness of each entity's association with the core topic of the text, presenting the most important entities for understanding the main idea first;

        # Examples

        # Question
        Q: 'Romeo and Juliet' is a tragedy written by William Shakespeare early in his career, and is one of the most frequently performed plays in world literature.

        # Answer
        A: ```json
        [
          {
            "entity": "Romeo and Juliet"
          },
          {
            "entity": "William Shakespeare"
          },
          {
            "entity": "world literature"
          }
        ]
        ```
        """;

    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final LlmSideCallClient sideCallClient;

    @Autowired(required = false) private ModelConfigResolver modelConfigResolver;

    public KbQaEntityExtract(
        KbKnowledgeBaseMapper knowledgeBaseMapper,
        LlmSideCallClient sideCallClient
    ) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.sideCallClient = sideCallClient;
    }

    /**
     * 抽取结果。
     *
     * @param entitiesByKb 库 → 实体名（按抽取顺序去重）；未开启图谱的库不会出现在这个 map 里
     * @param enabledKbs   本次真正「开启了图谱」的库数（= 若 &gt;0 则调过一次模型）
     */
    public record Outcome(Map<String, List<String>> entitiesByKb, int enabledKbs) {

        /** 空结果（没开图谱 / 抽取失败 / 没抽到）。 */
        public static Outcome empty() {
            return new Outcome(Map.of(), 0);
        }

        /** 是否有可用的实体。 */
        public boolean isEmpty() {
            return entitiesByKb.isEmpty();
        }
    }

    /**
     * 用重写后的问题抽取实体。
     *
     * @param question   重写后的问题（空 ⇒ 退化为原问题由调用方保证；这里再兜一次）
     * @param kbIds      本轮检索范围
     * @param modelName  抽取模型（空 ⇒ 不抽，返回空）
     * @return 结果（永不 null、永不抛）
     */
    public Outcome extract(String question, List<String> kbIds, String modelName) {
        if (question == null || question.isBlank() || kbIds == null || kbIds.isEmpty()) {
            return Outcome.empty();
        }
        if (modelName == null || modelName.isBlank()) {
            log.info("[问答链] ③ 未配置实体抽取模型，跳过");
            return Outcome.empty();
        }

        // ── ① 只留开启图谱的库（⭐ 未开启的一个字都不发）──────────────
        // ⚠️ 顺序不能换：先判开关再解析 provider。反过来的话「全关」时也要白解析一次配置，
        //    更要命的是会让人误以为「没解析成功」和「没开图谱」是同一条路径。
        List<String> enabled = enabledGraphKbs(kbIds);
        if (enabled.isEmpty()) {
            log.info("[问答链] ③ 范围内 {} 个库均未开启图谱，跳过抽取（未调模型）", kbIds.size());
            return Outcome.empty();
        }

        ProviderConfig provider = resolveConfig(modelName);
        if (provider == null) {
            log.info("[问答链] ③ 实体抽取模型不可用，跳过 model={}", modelName);
            return Outcome.empty();
        }

        // ── ② 一次模型调用（跨库共用一次：抽的是「问题里有什么实体」，与库无关）──
        String clipped = clip(question.strip(), MAX_QUERY_CHARS);
        List<String> names;
        try {
            String raw = sideCallClient.chat(
                provider, modelName, SYSTEM_PROMPT, userMessage(clipped),
                options("qa_extract_entities"),
                LlmSideCallClient.SideCallUsage.standalone(
                    UsageCategory.SIDE_CALL, "qa_extract_entities"));
            names = parseEntityNames(raw);
        } catch (RuntimeException e) {
            // ⭐ 不阻断：整轮继续，只是少一路「实体检索」
            log.warn("[问答链] ③ 实体抽取失败，本轮跳过实体检索 原因={}", e.getMessage());
            return new Outcome(Map.of(), enabled.size());
        }
        if (names.isEmpty()) {
            log.info("[问答链] ③ 未抽出实体（开启图谱的库={}）", enabled.size());
            return new Outcome(Map.of(), enabled.size());
        }

        // ── ③ 按库展开（实体名去重，保持抽取顺序）────────────────────
        Map<String, List<String>> byKb = groupByKb(names, enabled);
        if (byKb.isEmpty()) {
            log.info("[问答链] ③ 实体名全部为空，跳过");
            return new Outcome(Map.of(), enabled.size());
        }
        log.info("[问答链] ③ 实体抽取 实体数={} 开启图谱的库={}/{} 名字={}",
            byKb.values().iterator().next().size(), enabled.size(), kbIds.size(),
            byKb.values().iterator().next());
        return new Outcome(byKb, enabled.size());
    }

    /**
     * 筛出开启了图谱的库。
     *
     * <p>⭐ 判据复用 {@link KbPostProcessConfig#graphEnabled} —— 单一真源。
     * 若在这里另写一份 JSON 解析，两处口径迟早分叉（一边判了 graph_enabled 一边没判）。
     */
    private List<String> enabledGraphKbs(List<String> kbIds) {        List<String> out = new ArrayList<>();
        for (String kbId : kbIds) {
            if (kbId == null || kbId.isBlank()) {
                continue;
            }
            KbKnowledgeBaseRecord kb;
            try {
                kb = knowledgeBaseMapper.selectOneById(kbId);
            } catch (RuntimeException e) {
                log.warn("[问答链] ③ 读知识库失败，该库不做实体抽取 kb={} 原因={}", kbId, e.getMessage());
                continue;
            }
            // ⚠️ 行不存在也跳过 —— 白名单里可能残留已删库
            if (kb != null && KbPostProcessConfig.graphEnabled(kb)) {
                out.add(kb.getId());
            }
        }
        return out;
    }

    private ProviderConfig resolveConfig(String modelName) {
        if (modelConfigResolver == null) {
            return null;
        }
        try {
            ModelConfigResolver.ResolvedModel r = modelConfigResolver.resolve(modelName);
            return r == null ? null : r.config();
        } catch (RuntimeException e) {
            log.warn("[问答链] ③ provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
            return null;
        }
    }

    private static LlmProvider.ChatRequestOptions options(String label) {
        return new LlmProvider.ChatRequestOptions(
            null, null, null,
            LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
            TEMPERATURE, label, null, MAX_TOKENS, null, null,
            null, null, null, null, null, null);
    }

    /** 按码点截断（Go 的 rune 语义）。 */
    private static String clip(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        int len = s.codePointCount(0, s.length());
        if (len <= maxChars) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxChars));
    }

    /** 供测试核对 JSON 形状用（避免测试里再写一个 ObjectMapper 常量）。 */
    static JsonNode readTree(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** 查询期的 user 消息（对齐源侧 {@code QAPromptGenerator.User}）。 */
    static String userMessage(String question) {
        return "# Question\nQ: " + (question == null ? "" : question) + "\n\n# Answer\nA: ";
    }

    /**
     * 从模型输出里解析实体名（对齐源侧 {@code Formater.parseOutput} + {@code {"entity":...}}）。
     *
     * <p><b>⭐ 为什么不复用 {@code KbGraphParser.parseEntities}</b>：那一个解析的是
     * {@code {"title","type","description"}}；查询期模型按本类自己的提示词输出
     * {@code {"entity":"..."}}。键名不同，复用等于恒返回空。
     *
     * <p><b>容错</b>（源侧 {@code extractContent} 的等价物）：模型经常加 ```json 围栏、
     * 或前后带一句废话 ⇒ 先抠出 JSON 数组片段再解析。实在解析不出来 ⇒ 空列表，
     * ⛔ 不抛（抽取失败不该拖垮问答）。
     *
     * @param raw 模型原文
     * @return 实体名（保序、已去空）；永不为 null
     */
    static List<String> parseEntityNames(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        JsonNode arr = readJsonArray(raw);
        if (arr == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode item : arr) {
            if (item == null) {
                continue;
            }
            // 主键是 entity；同时容忍 name/title（不同模型对示例的模仿程度不同）
            String name = textOf(item, "entity");
            if (name.isEmpty()) {
                name = textOf(item, "name");
            }
            if (name.isEmpty()) {
                name = textOf(item, "title");
            }
            if (!name.isEmpty() && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** 从可能带围栏/废话的文本里抠出第一个 JSON 数组。 */
    private static JsonNode readJsonArray(String raw) {
        JsonNode direct = readTree(raw.strip());
        if (direct != null && direct.isArray()) {
            return direct;
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            JsonNode inner = readTree(raw.substring(start, end + 1));
            if (inner != null && inner.isArray()) {
                return inner;
            }
        }
        return null;
    }

    private static String textOf(JsonNode item, String field) {
        JsonNode v = item.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            return "";
        }
        return v.asText().strip();
    }

    /**
     * 抽取结果的「按库展开」规则（纯函数，供测试直接打）。
     *
     * <p>⭐ 抽出来是因为这条规则有真实的坑：实体名必须<b>去重且保序</b>，
     * 并且<b>每个开启的库都要拿到完整列表</b>（实体名本身不带库归属，
     * 归属由「查哪个库的图谱」决定）。写成内联 lambda 的话测试只能靠 mock 整条链路。
     *
     * @param names      模型抽出的实体名（可含 null/空/重复）
     * @param enabledKbs 开启了图谱的库 id
     * @return 库 → 实体名；enabledKbs 为空 ⇒ 空 map
     */
    static Map<String, List<String>> groupByKb(List<String> names, List<String> enabledKbs) {
        if (enabledKbs == null || enabledKbs.isEmpty()) {
            return Map.of();
        }
        List<String> cleaned = new ArrayList<>(new LinkedHashSet<>(
            names == null ? List.of() : names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::strip)
                .toList()));
        if (cleaned.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> byKb = new LinkedHashMap<>();
        for (String kbId : enabledKbs) {
            if (kbId != null && !kbId.isBlank()) {
                byKb.put(kbId, List.copyOf(cleaned));
            }
        }
        return byKb;
    }
}
