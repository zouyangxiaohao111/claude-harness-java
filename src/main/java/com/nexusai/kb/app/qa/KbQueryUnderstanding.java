package com.nexusai.kb.app.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.UsageCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 查询改写 + 意图判定 · 复刻 WeKnora {@code PluginQueryUnderstand}
 * （{@code chat_pipeline/query_understand.go}，563 行）。
 *
 * <h2>编排方式：⭐ 不是 Graph</h2>
 * 源侧用的是「{@code Plugin} 接口 + {@code next()} 回调」的手写中间件链；
 * 本类把它压成<b>一个方法内的顺序调用</b> —— 因为这一阶段本来就只有一次 LLM 调用，
 * 拆成可插拔插件只会增加理解成本，不带来任何灵活性。
 *
 * <h2>一次调用做三件事（源侧设计，照抄）</h2>
 * <ol>
 *   <li><b>改写</b>：消解指代（「它」「这个」）+ 补全省略，让问题可独立理解</li>
 *   <li><b>意图判定</b>：9 类之一，决定下游<b>是否检索</b></li>
 *   <li><b>图片描述</b>：有图时产出 OCR + 场景描述</li>
 * </ol>
 *
 * <h2>⭐⭐ 两个必须照抄的安全设计</h2>
 * <ul>
 *   <li><b>意图未知 ⇒ 按需要检索</b>（{@link KbQueryIntent#normalize}）：
 *       源侧事故是「未知标签 ⇒ 不检索 ⇒ 答案凭空生成且不报错」。</li>
 *   <li><b>改写失败/解析失败 ⇒ 回落到原问题</b>，而不是返回空查询。
 *       空查询会让检索阶段返回空结果，用户看到「找不到相关内容」，
 *       但真实原因是改写链路挂了 —— 这是最难查的一类故障。</li>
 * </ul>
 *
 * <h2>参数（逐字对齐源侧）</h2>
 * {@code Temperature 0.3}；{@code MaxCompletionTokens 150}（有图时 2048，源侧注释说明
 * 500 会让文本多的截图 JSON 被截断，那一轮就丢了 rewrite + intent + description）；
 * {@code Thinking = false}（改写是机械任务，不需要推理）。
 */
@Component
public class KbQueryUnderstanding {

    private static final Logger log = LoggerFactory.getLogger(KbQueryUnderstanding.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    // ⚠️ 2026-11-12 删除 MAX_TOKENS_TEXT(150) / MAX_TOKENS_WITH_IMAGE(2048)：
    //   输出上限唯一入口 = models.max_tokens，调用点不再填值
    //   （非流式由 provider 侧压到 MAX_NON_STREAMING_TOKENS=64000）。

    /** 改写温度（源侧写死 0.3）。 */
    private static final double TEMPERATURE = 0.3;

    /** 记账标签（源侧 LLM 调用 metadata 同名）。 */
    private static final String USAGE_LABEL = "query_rewrite";

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static final String[] WEEKDAYS =
        {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    private final LlmSideCallClient sideCallClient;

    public KbQueryUnderstanding(LlmSideCallClient sideCallClient) {
        this.sideCallClient = sideCallClient;
    }

    /**
     * 改写结果。
     *
     * @param rewriteQuery     改写后的问题（⚠️ 恒非空：失败时回落原问题）
     * @param intent           意图（恒非 null；未知值已 fail-open 到 {@link KbQueryIntent#KB_SEARCH}）
     * @param imageDescription 图片描述（无图时为空串）
     * @param degraded         true = 改写链路降级（模型调用失败 / 解析失败），此时
     *                         {@code rewriteQuery} 是原问题、{@code intent} 是 KB_SEARCH
     */
    public record Result(
        String rewriteQuery,
        KbQueryIntent intent,
        String imageDescription,
        boolean degraded
    ) {
        /**
         * 是否需要走检索阶段。
         *
         * @param webSearchEnabled 联网检索是否开启
         * @return true = 需要检索
         */
        public boolean needsRetrieval(boolean webSearchEnabled) {
            return intent.needsRetrieval(webSearchEnabled);
        }

        /**
         * 送进检索的查询 —— <b>多个</b>（源侧只有一个，这里保留一个是为了将来支持
         * {@code query_expansion} 的多查询展开）。
         *
         * @return 查询列表（恒非空、恒非空白）
         */
        public List<String> retrievalQueries() {
            String q = rewriteQuery == null ? "" : rewriteQuery.trim();
            return q.isEmpty() ? List.of() : List.of(q);
        }
    }

    /**
     * 执行改写 + 意图判定。
     *
     * @param config        provider 配置（已解密）
     * @param modelName     模型名
     * @param query         用户原始问题
     * @param history       最近若干轮对话（每条如 {@code 用户: ...} / {@code 助手: ...}），可空
     * @param hasImages     是否附带图片
     * @param hasDocuments  是否附带文档
     * @param language      语言提示（空 = 跟随原问题语言）
     * @param usage         记账归属
     * @return 改写结果（永不抛异常、永不返回 null）
     */
    public Result understand(
        ProviderConfig config, String modelName, String query,
        List<String> history, boolean hasImages, boolean hasDocuments,
        String language, LlmSideCallClient.SideCallUsage usage
    ) {
        return understand(config, modelName, query, history, hasImages, hasDocuments,
            language, usage, null);
    }

    /**
     * 执行改写 + 意图判定（带「提问者背景」）。
     *
     * <p><b>⭐⭐ 为什么要带背景</b>（对齐源侧 {@code query_understand.go:memoryBackground}）：
     * 「怎么调分段参数」对做医疗影像的人和做自动驾驶的人是<b>两个不同的检索</b>，
     * 而这个差异只能在检索<b>之前</b>施加。记忆在这里不是答案里的装饰段，
     * 而是改变「改写出什么检索词」的输入。
     *
     * <p>⚠️ 源侧对此的定性必须照抄：背景是<b>建议</b>不是过滤器 ——
     * 它不缩小范围、不排除任何知识库。一条过时的项目记忆不该让本季度的文档变得不可达。
     * 因此它只进提示词，⛔ 不参与任何命中判断。
     *
     * <p>⚠️ 背景也<b>不计入</b>「本轮用到的记忆」：这里读的是整块常驻背景（未按相关性筛），
     * 把它报成「本轮召回了这些记忆」是虚报。真正按相关性选中的那一批由阶段 1 负责。
     *
     * @param memoryBackground 提问者背景（可为空；空 ⇒ 与 8 参重载完全一致）
     */
    public Result understand(
        ProviderConfig config, String modelName, String query,
        List<String> history, boolean hasImages, boolean hasDocuments,
        String language, LlmSideCallClient.SideCallUsage usage,
        String memoryBackground
    ) {
        String original = query == null ? "" : query.trim();

        if (config == null || modelName == null || modelName.isBlank()) {
            // 没有可用模型 ⇒ 降级（不是「不需要改写」，而是「没法改」）
            log.warn("[查询改写] 缺少模型配置，回落到原问题（意图按 kb_search 处理）");
            return degradedResult(original, "");
        }
        if (original.isEmpty() && !hasImages) {
            // 空问题且无图：交给下游处理（这里不当作改写失败）
            return new Result("", KbQueryIntent.KB_SEARCH, "", false);
        }

        String systemPrompt = KbQueryRewritePrompts.renderSystem(language);
        String queryBlock = buildQueryBlock(original, history, hasImages, hasDocuments);
        if (memoryBackground != null && !memoryBackground.isBlank()) {
            // 源侧标记名照抄：note 里明说「仅用于消解指代和补全检索词」，否则模型会把它当问题的一部分
            queryBlock += "\n\n<asker_background note=\"背景仅用于消解指代和补全检索词，不要当作问题的一部分\">\n"
                + memoryBackground.strip() + "\n</asker_background>";
        }
        String userPrompt = KbQueryRewritePrompts.renderUser(
            LocalDateTime.now().format(TIME_FMT),
            WEEKDAYS[LocalDateTime.now().getDayOfWeek().getValue() - 1],
            queryBlock);

        String raw;
        try {
            if (log.isDebugEnabled()) {
                log.debug("[查询改写] 调用模型 model={} max_tokens=按模型配置 有图={} 原问题={}",
                    modelName, hasImages, original);
            }
            raw = sideCallClient.chat(
                config, modelName, systemPrompt, userPrompt,
                // 字段顺序（照 LlmProvider.ChatRequestOptions 定义）：
                //   history / tools / outputFormat / thinkingConfig / temperature / querySource
                //   / abortController / maxTokens / skipCacheWrite / enablePromptCaching
                //   / agents / hasAppendSystemPrompt / mcpTools / isNonInteractiveSession
                //   / toolChoice / agentContext
                new LlmProvider.ChatRequestOptions(
                    null, null, null,
                    // 改写是机械任务，关掉思考（源侧同样写死 false）
                    LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
                    TEMPERATURE,
                    USAGE_LABEL,
                    null, null, null, null,
                    null, null, null, null,
                    null, null),
                usage);
        } catch (RuntimeException e) {
            // ⭐ 降级而不是抛：一次模型抖动不该让整轮对话失败
            log.warn("[查询改写] 模型调用失败，回落到原问题 原问题={} 原因={}", original, e.getMessage());
            return degradedResult(original, "");
        }

        return parse(original, raw, hasImages);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 拼用户提问块（对齐源侧 {@code buildPrompts} 的 queryContent 构造）。
     *
     * <p>⚠️ 那两个 XML 标记是<b>提示词的一部分</b>，不是装饰：意图判定规则里明确写了
     * 「{@code <未附图 />} 出现时禁止判成 image_only」—— 模型靠这两个标记判断
     * 「本轮到底有没有附件」。
     *
     * <p>⭐⭐ <b>标记必须与 {@link KbQueryRewritePrompts#SYSTEM} 里写的一字不差</b>。
     * 提示词已按用户要求转成中文 ⇒ 这里也必须用中文标记；两边不一致会让模型的判定依据失效
     * （且不会报错，只是判定悄悄变差）。
     */
    private static String buildQueryBlock(
        String query, List<String> history, boolean hasImages, boolean hasDocuments
    ) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            sb.append("## 对话历史\n");
            for (String line : history) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
        }
        sb.append(query);
        sb.append("\n\n");
        sb.append(hasImages ? "<附图 />" : "<未附图 />");
        sb.append('\n');
        sb.append(hasDocuments ? "<附文档 />" : "<未附文档 />");
        return sb.toString();
    }

    /**
     * 解析模型输出。
     *
     * <p><b>容错</b>（真实模型经常不走严格 JSON）：
     * 先去 markdown 代码围栏，再找第一个 {@code &#123;} 到最后一个 {@code &#125;}。
     * 都失败 ⇒ 降级到原问题，⛔ 不猜、不返回空。
     */
    private Result parse(String original, String raw, boolean hasImages) {
        String text = stripFences(raw == null ? "" : raw);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("[查询改写] 输出里找不到 JSON，回落到原问题 原文前 120 字={}",
                text.length() > 120 ? text.substring(0, 120) : text);
            return degradedResult(original, "");
        }
        JsonNode node;
        try {
            node = JSON.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            // ⭐ 抢救（对齐源侧 salvageStructuredQueryOutput）：被 token 上限截断的回复不是合法
            //   JSON，但模型在被截之前写好的字段仍然可用。⛔ 不能直接降级到原问题 ——
            //   那样「改写成功了一半」的轮次会被当成完全失败。
            node = salvage(text);
            if (node == null) {
                log.warn("[查询改写] JSON 解析失败且无法抢救，回落到原问题 原因={}", e.getMessage());
                return degradedResult(original, "");
            }
            log.info("[查询改写] JSON 不完整，已抢救出字段: {}", fieldNames(node));
        }

        String rewritten = textOf(node, "rewrite_query");
        String intentRaw = textOf(node, "intent");
        String imageDesc = textOf(node, "image_description");
        KbQueryIntent intent = KbQueryIntent.normalize(intentRaw);

        // 改写结果为空 ⇒ 用原问题（⛔ 空查询会让检索返回空，看起来像「没找到」）
        if (rewritten == null || rewritten.isBlank()) {
            log.warn("[查询改写] rewrite_query 为空，用原问题代替 原问题={}", original);
            rewritten = original;
        }

        if (intentRaw != null && !intentRaw.isBlank()
            && !intentRaw.trim().toLowerCase().replace('-', '_').equals(intent.wire())) {
            log.warn("[查询改写] 未知意图标签 '{}' ⇒ 按 {} 处理（fail-open 到检索，宁可多查不可漏查）",
                intentRaw, intent.wire());
        }

        if (log.isInfoEnabled()) {
            log.info("[查询改写] 完成 原问题={} 改写={} 意图={}({}) 有图={} 有图描述={}",
                original, rewritten, intent.wire(), intent.label(), hasImages,
                imageDesc != null && !imageDesc.isEmpty());
        }
        return new Result(rewritten, intent, imageDesc == null ? "" : imageDesc, false);
    }

    /** 截断回复里单个字段的匹配（值允许转义、允许未闭合）。 */
    private static final java.util.regex.Pattern SALVAGE_FIELD =
        java.util.regex.Pattern.compile("\"([a-z_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)(\"?)");

    /**
     * 从被截断的回复里抢救字段（对齐源侧 {@code salvageStructuredQueryOutput}）。
     *
     * <p><b>规则</b>（与源侧逐条一致）：
     * <ul>
     *   <li><b>已闭合</b>的字符串字段 ⇒ 照用；</li>
     *   <li><b>被截断</b>的字段：只有 {@code image_*} / {@code description} 保留
     *       —— 半截 OCR 仍有用；而半截的 {@code rewrite_query} / {@code intent}
     *       必须丢弃 —— 用半句话替换用户完整的问题，比不替换更糟。</li>
     * </ul>
     *
     * @param raw 模型原始输出
     * @return 抢救出的对象；一个字段都没抢到 → null
     */
    private static JsonNode salvage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        com.fasterxml.jackson.databind.node.ObjectNode out = JSON.createObjectNode();
        java.util.regex.Matcher m = SALVAGE_FIELD.matcher(raw);
        while (m.find()) {
            String key = m.group(1);
            boolean closed = m.group(3) != null && !m.group(3).isEmpty();
            boolean imageField = key.startsWith("image_") || "description".equals(key);
            if (!closed && !imageField) {
                continue;
            }
            String value = m.group(2);
            if (value.endsWith("\\")) {
                value = value.substring(0, value.length() - 1);
            }
            out.put(key, unescape(value));
        }
        return out.isEmpty() ? null : out;
    }

    /** 反转义 JSON 字符串（{@code \"} / {@code \\} / {@code \n} 等）。 */
    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        try {
            return JSON.readTree("\"" + value + "\"").asText();
        } catch (Exception e) {
            return value;
        }
    }

    /** 节点里的字段名（日志用）。 */
    private static String fieldNames(JsonNode node) {
        if (node == null) {
            return "[]";
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    /** 去掉 markdown 代码围栏（```json ... ```）。 */
    private static String stripFences(String s) {
        String t = s.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNewline = t.indexOf('\n');
        if (firstNewline < 0) {
            return t;
        }
        String body = t.substring(firstNewline + 1);
        int fence = body.lastIndexOf("```");
        return fence >= 0 ? body.substring(0, fence).trim() : body.trim();
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText(null);
    }

    private static Result degradedResult(String original, String imageDescription) {
        return new Result(original, KbQueryIntent.KB_SEARCH, imageDescription, true);
    }

    /** 记账分类（本类调用统一归 SIDE_CALL，由调用方传更精确的 usage 时以调用方为准）。 */
    static LlmSideCallClient.SideCallUsage usageFor(String sessionId, String agentId) {
        return sessionId == null || sessionId.isBlank()
            ? LlmSideCallClient.SideCallUsage.standalone(UsageCategory.SIDE_CALL, USAGE_LABEL)
            : LlmSideCallClient.SideCallUsage.inSession(
                sessionId, agentId, UsageCategory.SIDE_CALL, USAGE_LABEL);
    }
}
