package com.nexusai.kb.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ExclusiveTool;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.kb.app.wiki.KbWikiPageService;
import com.nexusai.kb.repo.entity.KbWikiPageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 检索 Wiki 页面（{@code wiki_search}）· 入参对齐源侧
 * {@code internal/agent/tools/wiki_tools.go} 的 {@code NewWikiSearchTool}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { query*: string (minLength 1, POSIX 正则 / 非法时按字面量),
 *   regex?: boolean (false 强制字面量；true 要求合法正则；缺省自动),
 *   knowledge_base_ids?: string[],
 *   limit?: integer (1..50，默认 10，每个知识库) }
 * </pre>
 *
 * <h2>⭐⭐ 检索面：标题 / slug / 别名 / 摘要 / 正文</h2>
 * 源站逐字：「Search the wiki pages ... by title, slug, alias, summary and content」。
 * 本仓的 {@code KbWikiPageService.listPages} 一次返回整个库的页面，在内存里过滤即可
 * （单库 Wiki 页数量级是几十到几百，不值得为它加 FTS）。
 */
@Component
@ExclusiveTool
public class KbWikiSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbWikiSearchTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 每个知识库最多返回的页数 · 源站 {@code maximum: 50}。 */
    static final int MAX_LIMIT = 50;
    /** 默认页数 · 源站 {@code default 10}。 */
    static final int DEFAULT_LIMIT = 10;
    /** 可指定的知识库上限。 */
    static final int MAX_KB_IDS = 10;

    @Autowired private KbWikiPageService wikiPageService;

    @Autowired(required = false)
    private com.nexusai.kb.domain.KnowledgeBaseService knowledgeBaseService;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "wiki_search";
    }

    @Override
    public String description() {
        return "在授权范围内的知识库的 Wiki 页面中检索（匹配标题、slug、别名、摘要与正文）。"
            + " 返回页面 slug 与摘要；读整页用 wiki_read_page。"
            + " query 默认按正则处理（大小写不敏感），非法正则自动退化为字面量匹配；"
            + " regex=false 可强制字面量。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        ObjectNode q = JSON.objectNode();
        q.put("type", "string");
        q.put("description", "检索文本。默认按大小写不敏感的正则处理，非法正则按字面量匹配。");
        q.put("minLength", 1);
        props.set("query", q);

        ObjectNode regex = JSON.objectNode();
        regex.put("type", "boolean");
        regex.put("description", "false 强制按字面量匹配；true 要求是合法正则；不传则自动判断。");
        props.set("regex", regex);

        ObjectNode kbIds = JSON.objectNode();
        kbIds.put("type", "array");
        kbIds.put("description", "可选：限定检索的知识库 id 列表。");
        ObjectNode items = JSON.objectNode();
        items.put("type", "string");
        kbIds.set("items", items);
        props.set("knowledge_base_ids", kbIds);

        ObjectNode limit = JSON.objectNode();
        limit.put("type", "integer");
        limit.put("description",
            "每个知识库最多返回多少页（默认 " + DEFAULT_LIMIT + "，范围 1.." + MAX_LIMIT + "）");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        props.set("limit", limit);

        ArrayNode required = JSON.arrayNode();
        required.add("query");
        root.set("required", required);
        return root;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        try {
            JsonNode in = call.input();
            String query = in.path("query").asText("").trim();
            if (query.isEmpty()) {
                return ToolResult.error(call.id(), "query 不能为空");
            }
            int limit = clampLimit(in.path("limit").asInt(DEFAULT_LIMIT));
            Boolean regexFlag = in.has("regex") ? in.path("regex").asBoolean(true) : null;

            List<String> targets = resolveTargets(in, ctx);
            if (targets.isEmpty()) {
                // 如实区分「没有库可查」与「库不在授权范围」
                String reason = scope != null && isScopeRestricting(ctx)
                    ? "该智能体未授权任何知识库"
                    : "当前没有任何知识库";
                log.warn("[wiki_search] 无可检索的库 原因={}", reason);
                return ToolResult.success(call.id(),
                    "{\"pages\":[],\"note\":\"" + reason + "\"}");
            }

            Matcher matcher = buildMatcher(query, regexFlag);
            StringBuilder sb = new StringBuilder("{\"pages\":[");
            int total = 0;
            for (String kbId : targets) {
                List<KbWikiPageRecord> pages;
                try {
                    pages = wikiPageService.listPages(kbId);
                } catch (RuntimeException e) {
                    log.warn("[wiki_search] 读页面失败 kb={} 原因={}", kbId, e.toString());
                    continue;
                }
                if (pages == null) {
                    continue;
                }
                int perKb = 0;
                for (KbWikiPageRecord p : pages) {
                    if (perKb >= limit) {
                        break;
                    }
                    if (!matcher.test(p)) {
                        continue;
                    }
                    if (total > 0) {
                        sb.append(',');
                    }
                    sb.append(KbWikiToolSupport.pageBrief(p));
                    total++;
                    perKb++;
                }
            }
            sb.append("],\"total\":").append(total)
              .append(",\"mode\":\"").append(matcher.mode()).append("\"}");

            log.info("[wiki_search] 检索完成 库数={} 命中={} mode={} query={}",
                targets.size(), total, matcher.mode(), KbWikiToolSupport.truncate(query, 40));
            return ToolResult.success(call.id(), sb.toString());
        } catch (RuntimeException e) {
            log.error("[wiki_search] 执行失败", e);
            return ToolResult.error(call.id(), "检索 Wiki 失败：" + e.getMessage());
        }
    }

    /**
     * 解析检索目标库。
     *
     * <h2>⭐⭐ 三态必须分清（真机踩过）</h2>
     * <ul>
     *   <li>{@code allowed == null} = <b>不限制</b>（会话未绑定知识库范围）⇒ 应检索<b>全部</b>库</li>
     *   <li>{@code allowed.isEmpty()} = <b>明确禁止</b>（绑定了但一个都没授权）⇒ 应返回空</li>
     *   <li>{@code allowed} 非空 = 白名单</li>
     * </ul>
     * ⚠️ 我第一版把 {@code allowed == null} 也返回了空列表，与「明确禁止」混为一谈 ⇒
     * 真机上绑了 agent 后每条 wiki_search 都返回
     * 「请求的知识库均不在本智能体的授权范围内」，而会话其实没有设置任何限制。
     * 这个错误的隐蔽之处是<b>不报错</b>，只是永远搜不到东西。
     *
     * @return 目标库列表；空列表 = 未授权任何库（调用方应如实返回空结果）
     */
    private List<String> resolveTargets(JsonNode in, ToolUseContext ctx) {
        List<String> allowed = scope == null ? null : scope.allowedKnowledgeBaseIds(ctx);
        List<String> requested = KbSearchKnowledgeTool.readStringArray(in, "knowledge_base_ids", MAX_KB_IDS);

        List<String> effective = allowed != null ? allowed : allKbIds();
        if (requested.isEmpty()) {
            return effective;
        }
        List<String> out = new ArrayList<>();
        for (String want : requested) {
            if (allowed == null || allowed.contains(want)) {
                out.add(want);
            }
        }
        return out;
    }

    /**
     * 该会话是否真的被限制了知识库范围（用于把「没库」和「没授权」区分开）。
     *
     * <p>{@code allowed == null} = 不限制 ⇒ 返回 false。
     * {@code allowed} 非 null（含空列表）= 有限制 ⇒ 返回 true。
     */
    private boolean isScopeRestricting(ToolUseContext ctx) {
        if (scope == null) {
            return false;
        }
        return scope.allowedKnowledgeBaseIds(ctx) != null;
    }

    /**
     * 列出全部知识库 id（未绑定范围时的「不限制」实现）。
     *
     * <p>失败只 WARN 并返回空 —— 检索工具不该因为列库失败就整体报错，
     * 但也要留痕（否则又是「静默空结果」）。
     */
    private List<String> allKbIds() {
        if (knowledgeBaseService == null) {
            return List.of();
        }
        try {
            List<String> ids = new ArrayList<>();
            knowledgeBaseService.list().forEach(kb -> {
                if (kb != null && kb.id() != null) {
                    ids.add(kb.id());
                }
            });
            return ids;
        } catch (RuntimeException e) {
            log.warn("[wiki_search] 知识库列表查询失败: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 按源站语义构造匹配器。
     *
     * <h2>⭐⭐ 源站的「自动」语义，以及 Java 与 Go 的差异</h2>
     * 源站描述：「{@code regex} 缺省 = auto —— 非法正则按字面量匹配」，
     * 并举了 {@code "C++"} 作为「不是合法正则」的例子。
     * 这是因为 <b>Go 的 {@code regexp}</b>（RE2）里裸量词 {@code +} 紧跟另一个量词时编译失败，
     * 所以 {@code C++} 在 Go 下非法。
     *
     * <p>⚠️ <b>但 {@code java.util.regex} 认为 {@code C++} 合法</b>
     * （{@code +} + {@code +} 是「一个或多个、尽可能多」的组合，能编译且能匹配 {@code C}）。
     * 直接「先试编译」会让 Java 走正则分支，而源站走字面量分支 ——
     * 行为差异体现在：搜 {@code C++} 时源站找的是字面串 {@code "c++"}，
     * Java 若走正则会把它当成「一个或多个 C」，从而匹配到所有含 {@code C} 的页面。
     *
     * <p>⇒ 修正：<b>先做「是否像一个有意的正则」的判定</b>，
     * 含正则元字符（{@code | ( ) [ ] { } ^ $ * ?}）才当作正则，
     * 否则按字面量。这样 {@code C++}、{@code a+b}、{@code foo.bar} 都走字面量（更符合直觉），
     * 而 {@code stardust|skyvault}、{@code ^entity/} 走正则。
     */
    static Matcher buildMatcher(String query, Boolean regexFlag) {
        if (Boolean.FALSE.equals(regexFlag)) {
            return new Matcher(null, query.toLowerCase(Locale.ROOT), "literal");
        }
        boolean explicit = Boolean.TRUE.equals(regexFlag);
        if (!explicit && !looksLikeRegex(query)) {
            // 源站 auto 分支：没有明确的正则特征 ⇒ 按字面量（对齐 "C++" 的处理）
            return new Matcher(null, query.toLowerCase(Locale.ROOT), "literal(auto)");
        }
        try {
            Pattern p = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
            return new Matcher(p, null, explicit ? "regex" : "regex(auto)");
        } catch (PatternSyntaxException e) {
            if (explicit) {
                // 显式要求正则却给了非法正则 ⇒ 仍按字面量处理，但记录原因
                return new Matcher(null, query.toLowerCase(Locale.ROOT),
                    "literal(invalid regex: " + e.getDescription() + ")");
            }
            return new Matcher(null, query.toLowerCase(Locale.ROOT), "literal(auto)");
        }
    }

    /** 是否含「有意的正则」特征字符（不含 {@code +} 与 {@code .}：它们在普通文本里太常见）。 */
    static boolean looksLikeRegex(String query) {
        if (query == null) {
            return false;
        }
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '|' || c == '(' || c == ')' || c == '[' || c == ']'
                || c == '{' || c == '}' || c == '^' || c == '$' || c == '*' || c == '?') {
                return true;
            }
        }
        return false;
    }

    /** 页面匹配器。 */
    static final class Matcher {
        private final Pattern pattern;
        private final String literal;
        private final String mode;

        Matcher(Pattern pattern, String literal, String mode) {
            this.pattern = pattern;
            this.literal = literal;
            this.mode = mode;
        }

        String mode() {
            return mode;
        }

        /**
         * 在标题 / slug / 别名 / 摘要 / 正文里找匹配。
         *
         * <p>⚠️ 正文也参与匹配（源站明写 {@code and content}），
         * 但正文很长时每条都正则匹配有开销 —— 库里页数在几百量级，可接受。
         */
        boolean test(KbWikiPageRecord p) {
            if (p == null) {
                return false;
            }
            String hay = KbWikiToolSupport.safe(p.getTitle()) + '\n'
                + KbWikiToolSupport.safe(p.getSlug()) + '\n'
                + KbWikiToolSupport.safe(p.getAliases()) + '\n'
                + KbWikiToolSupport.safe(p.getSummary()) + '\n'
                + KbWikiToolSupport.safe(p.getContent());
            if (pattern != null) {
                return pattern.matcher(hay).find();
            }
            return KbWikiToolSupport.lower(hay).contains(literal);
        }
    }

    private static int clampLimit(int v) {
        if (v <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(v, MAX_LIMIT);
    }
}
