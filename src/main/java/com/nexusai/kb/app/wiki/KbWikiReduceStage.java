package com.nexusai.kb.app.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.UsageCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wiki Reduce 阶段（每 slug 一次）· W2-5。
 *
 * <p><b>源侧对照</b>：WeKnora {@code wiki_ingest_batch.go:1910 reduceSlugUpdates}
 * + {@code wiki_ingest.go:2696 generateWithTemplateResult}（续写）。
 *
 * <h2>两件事，各有一个致命坑</h2>
 * <ol>
 *   <li><b>页面合并</b>：把多个文档对同一 slug 的贡献合成一页。
 *       坑 = <b>冲突</b>：新信息讲的是另一个事物（页面是「混元模型」，新信息是「Qwen3」）
 *       ⇒ 必须<b>拒绝</b>，⛔ 不许硬并。提示词里已写死，但这里还要做<b>结构上</b>的兜底：
 *       合并失败时<b>保留旧页面</b>，⛔ 不许写入半成品。</li>
 *   <li><b>截断续写</b>：长枚举（一百多行证书台账）会被 {@code finish_reason=length} 打断，
 *       一次存下来<b>少三分之一</b>且不报错。修法 = 回放已写部分 + 续写提示词，
 *       最多 3 轮；3 轮后仍截断 ⇒ <b>拒绝这份残页</b>（保留旧页），而不是存半个。</li>
 * </ol>
 *
 * <h2>⚠️ 「拒绝」比「存half page」重要</h2>
 * 半页是<b>不可恢复</b>的：用户看到的是残缺事实，而且系统以为已完成。
 * 保留旧页 + 标记 additionFailed 则是可恢复的（下一批会重试）。
 */
@Component
public class KbWikiReduceStage {

    private static final Logger log = LoggerFactory.getLogger(KbWikiReduceStage.class);

    /** 源侧 {@code wikiPageModifyMaxContinuations = 3}。 */
    public static final int MAX_CONTINUATIONS = 3;

    private static final double TEMP_MODIFY = 0.3;

    // ⚠️ 2026-11-12 删除 MAX_TOKENS_MODIFY(8000) / MAX_TOKENS_CONTINUE(6000)：
    //   输出上限唯一入口 = 模型配置页「生成参数 · max tokens」（models.max_tokens）。
    //   截断续写机制（isLengthFinish → 续写）保持不变，只是预算改由模型配置决定。

    /** 匹配历史遗留的 chunk 内联引用 {@code [c003]} / {@code [c12]}。 */
    private static final Pattern INLINE_CHUNK_CITATION =
        Pattern.compile("\\[(c\\d{1,6})]");

    /** 首行摘要：{@code SUMMARY: {...}}。 */
    private static final Pattern SUMMARY_LINE =
        Pattern.compile("^\\s*SUMMARY:\\s*(.+?)\\s*$", Pattern.MULTILINE);

    private final LlmSideCallClient sideCallClient;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    public KbWikiReduceStage(LlmSideCallClient sideCallClient) {
        this.sideCallClient = sideCallClient;
    }

    // ══════════════════════════════════════════════════════════
    // 数据载体
    // ══════════════════════════════════════════════════════════

    /**
     * 一个文档对某个 slug 的贡献。
     *
     * @param knowledgeId 来源文档 ID（撤稿按它匹配）
     * @param sourceRef   来源引用串（只用 knowledgeId，⛔ 不含文件名）
     * @param docSummary  文档级摘要（共享上下文，用于对齐范围与语气）
     * @param itemName    条目显示名
     * @param description 15-40 词描述
     * @param details     ≤300 字符兜底
     * @param aliases     别名
     * @param chunkIds    证据 chunk ID
     * @param chunkTexts  chunk 正文（与 chunkIds 对应；可空 ⇒ 用 details 兜底）
     */
    public record SlugUpdate(
        String knowledgeId, String sourceRef, String docSummary,
        String itemName, String description, String details, List<String> aliases,
        List<String> chunkIds, Map<String, String> chunkTexts
    ) {
    }

    /**
     * 现有页面快照（用于合并基线）。
     *
     * @param title    标题
     * @param content  正文（⛔ 可能含历史遗留 chunk 引用，合并前会剔除）
     * @param summary  一行摘要
     * @param aliases  别名
     * @param pageType 类型
     * @param sourceRefs 现有来源引用
     * @param chunkRefs  现有证据 chunk
     */
    public record ExistingWikiPage(
        String title, String content, String summary, List<String> aliases,
        String pageType, List<String> sourceRefs, List<String> chunkRefs
    ) {
        public boolean isEmpty() {
            return (content == null || content.isBlank())
                && (title == null || title.isBlank());
        }
    }

    /**
     * 合并结果。
     *
     * @param action      created / updated / unchanged / rejected
     * @param title       新标题
     * @param content     新正文（已剔除 chunk 引用）
     * @param summary     一行摘要
     * @param aliases     合并后的别名
     * @param pageType    类型
     * @param sourceRefs  合并后的来源
     * @param chunkRefs   合并后的证据 chunk
     * @param rejectReason 拒绝原因（action=rejected 时有值）
     */
    public record MergeResult(
        String action, String title, String content, String summary, List<String> aliases,
        String pageType, List<String> sourceRefs, List<String> chunkRefs, String rejectReason
    ) {
        public boolean changed() {
            return "created".equals(action) || "updated".equals(action);
        }
    }

    // ══════════════════════════════════════════════════════════
    // 主流程
    // ══════════════════════════════════════════════════════════

    /**
     * 合并一个 slug 的更新。
     *
     * @param slug         页面 slug
     * @param existing     现有页面（null / isEmpty ⇒ 新建）
     * @param additions    新增贡献（可空）
     * @param retractions  撤稿的文档 ID（可空）
     * @param validLinks   可用 wiki 链接（slug → 标题）
     * @param modelName    模型名
     * @param contentInstructions 文风说明（可空）
     * @return 合并结果（永不 null；失败时 action=rejected 且内容取自 existing）
     */
    public MergeResult reduceSlug(
        String slug, ExistingWikiPage existing,
        List<SlugUpdate> additions, List<String> retractions,
        Map<String, String> validLinks, String modelName, String contentInstructions
    ) {
        List<SlugUpdate> adds = additions == null ? List.of() : additions;
        List<String> retracts = retractions == null ? List.of() : retractions;

        if (adds.isEmpty() && retracts.isEmpty()) {
            return unchanged(slug, existing);
        }
        if (modelName == null || modelName.isBlank()) {
            log.warn("[Wiki] Reduce 跳过（未配置模型） slug={}", slug);
            return rejected(slug, existing, "未配置 Wiki 生成模型");
        }
        ModelConfigResolver.ResolvedModel _r = resolveModel(modelName);
        ProviderConfig config = _r == null ? null : _r.config();
        // ⚠️ 往下传的必须是 **models.name**：入参 modelName 往往只是 models.id，
        //    直接发出去会被对方 API 400（实测 `but you passed model-0823a98d`）。
        if (_r != null && _r.apiModelName() != null && !_r.apiModelName().isBlank()) {
            modelName = _r.apiModelName();
        }
        if (config == null) {
            return rejected(slug, existing, "模型 provider 配置无法解析");
        }

        boolean exists = existing != null && !existing.isEmpty();

        // ── 撤稿：过滤掉被删文档的 sourceRef ────────────────────────
        List<String> keptSourceRefs = new ArrayList<>();
        if (exists && existing.sourceRefs() != null) {
            for (String ref : existing.sourceRefs()) {
                if (!retracts.contains(knowledgeIdOfRef(ref))) {
                    keptSourceRefs.add(ref);
                }
            }
        }

        // ── 拼提示词 ─────────────────────────────────────────────────
        String newInformation = renderNewInformation(adds);
        String sharedContexts = renderSharedContexts(adds);
        String deletionBlock = renderDeletionBlock(retracts, existing, adds);
        String validLinkBlock = renderValidLinks(validLinks);
        if (contentInstructions != null && !contentInstructions.isBlank()) {
            validLinkBlock = validLinkBlock + "\n<content_instructions>\n"
                + contentInstructions.trim() + "\n</content_instructions>\n";
        }

        // 基础页面身份：已有页面的 title / type 优先；新建页从第一个 addition 取
        String pageTitle = exists && existing.title() != null && !existing.title().isBlank()
            ? existing.title()
            : (adds.isEmpty() ? slug : adds.get(0).itemName());
        String pageType = exists && existing.pageType() != null && !existing.pageType().isBlank()
            ? existing.pageType()
            : inferPageType(slug);
        List<String> mergedAliases = mergeAliases(
            exists ? existing.aliases() : List.of(), adds);

        // ⭐ 剔除历史遗留 chunk 引用（否则模型可能原样抄回去）
        String existingContent = exists ? stripInlineChunkCitations(existing.content()) : "";
        if (existingContent.isBlank()) {
            existingContent = "(New page)";
        }

        String user = KbWikiPrompts.PAGE_MODIFY_USER
            .replace("{{SHARED_SOURCE_CONTEXTS}}", sharedContexts)
            .replace("{{PAGE_SLUG}}", slug)
            .replace("{{PAGE_TITLE}}", pageTitle == null ? slug : pageTitle)
            .replace("{{PAGE_TYPE}}", pageType)
            .replace("{{PAGE_ALIASES}}", String.join(", ", mergedAliases))
            .replace("{{EXISTING_CONTENT}}", existingContent)
            .replace("{{NEW_INFORMATION}}", newInformation)
            .replace("{{DELETION_BLOCK}}", deletionBlock)
            .replace("{{VALID_WIKI_LINKS}}", validLinkBlock);

        // ── 调模型（带续写）──────────────────────────────────────────
        RewriteOutcome out = generateWithContinuation(
            config, modelName, KbWikiPrompts.PAGE_MODIFY_SYSTEM, user, slug);
        if (out.truncated()) {
            // ⭐⭐ 3 轮续写后仍截断 ⇒ 拒绝残页（保留旧页）
            log.warn("[Wiki] Reduce 拒绝残页 slug={}（续写 {} 轮后仍截断）",
                slug, MAX_CONTINUATIONS);
            return rejected(slug, existing, "输出过长，续写 " + MAX_CONTINUATIONS + " 轮后仍被截断");
        }
        if (out.content() == null || out.content().isBlank()) {
            log.warn("[Wiki] Reduce 模型返回空内容 slug={}", slug);
            return rejected(slug, existing, "模型返回空内容");
        }

        // ── 解析 SUMMARY 行 + 正文 ──────────────────────────────────
        SplitContent split = splitSummaryLine(out.content());

        // ⭐ 再剔除一次：模型就算被要求了也可能写出 chunk 引用
        String body = stripInlineChunkCitations(split.body());
        if (body.isBlank()) {
            return rejected(slug, existing, "模型输出的正文为空");
        }

        // ── 合并来源与证据 ──────────────────────────────────────────
        Set<String> sourceRefs = new LinkedHashSet<>(keptSourceRefs);
        for (SlugUpdate u : adds) {
            if (u.sourceRef() != null && !u.sourceRef().isBlank()) {
                sourceRefs.add(u.sourceRef());
            }
        }
        Set<String> chunkRefs = new LinkedHashSet<>();
        if (exists && existing.chunkRefs() != null) {
            chunkRefs.addAll(existing.chunkRefs());
        }
        for (SlugUpdate u : adds) {
            if (u.chunkIds() != null) {
                for (String c : u.chunkIds()) {
                    if (c != null && !c.isBlank()) {
                        chunkRefs.add(c);
                    }
                }
            }
        }

        String summary = split.summary();
        if (summary == null || summary.isBlank()) {
            summary = exists ? existing.summary() : null;
        }

        return new MergeResult(
            exists ? "updated" : "created",
            pageTitle == null ? slug : pageTitle.trim(),
            body,
            summary,
            mergedAliases,
            pageType,
            new ArrayList<>(sourceRefs),
            new ArrayList<>(chunkRefs),
            null);
    }

    // ══════════════════════════════════════════════════════════
    // 目录规划（一批一次）
    // ══════════════════════════════════════════════════════════

    /**
     * 一批页面统一分目录。
     *
     * <p>⭐⭐ <b>一批只调一次</b>：若每页各自规划，同一主题会被分到不同目录，树会分裂。
     *
     * @param pages        本批页面（slug → 标题）
     * @param existingFolders 已有目录路径（如 "地点/地址"）
     * @param modelName    模型名
     * @return slug → 目录路径（≤2 级）；失败返回空 map（调用方保留原归属）
     */
    public Map<String, List<String>> planTaxonomy(
        Map<String, String> pages, List<String> existingFolders, String modelName
    ) {
        if (pages == null || pages.isEmpty() || modelName == null || modelName.isBlank()) {
            return Map.of();
        }
        ModelConfigResolver.ResolvedModel _r2 = resolveModel(modelName);
        ProviderConfig config = _r2 == null ? null : _r2.config();
        if (config == null) {
            return Map.of();
        }
        // ⚠️ 同 resolveModel 的说明：往下传真实模型名而不是 ID
        if (_r2.apiModelName() != null && !_r2.apiModelName().isBlank()) {
            modelName = _r2.apiModelName();
        }
        String user = KbWikiPrompts.TAXONOMY_PLAN_USER
            .replace("{{EXISTING_FOLDERS}}", existingFolders == null || existingFolders.isEmpty()
                ? "（暂无已有目录）" : String.join("\n", existingFolders))
            .replace("{{PAGES}}", renderPagesForTaxonomy(pages));

        LlmProvider.LlmInvocationResult r = invoke(
            config, modelName, KbWikiPrompts.TAXONOMY_PLAN_SYSTEM, user,
            0.1, "wiki_taxonomy");
        if (r == null || r.content() == null) {
            log.warn("[Wiki] 目录规划调用失败，保留原有归属");
            return Map.of();
        }
        return parseAssignments(r.content(), pages.keySet());
    }

    /**
     * 解析目录规划结果。
     *
     * <p>⭐ 严格校验：路径 ≤2 级、不含斜杠、不许用 entity/concept 当目录名。
     * 不合格的分配<b>丢弃该条</b>（保留原归属），⛔ 不猜。
     */
    static Map<String, List<String>> parseAssignments(String raw, Set<String> allowedSlugs) {
        JsonNode root = KbWikiMapStage.parseJsonLoose(raw);
        if (root == null) {
            return Map.of();
        }
        JsonNode arr = root.get("assignments");
        if (arr == null || !arr.isArray()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (JsonNode n : arr) {
            String rawSlug = n.path("slug").asText(null);
            String slug = KbWikiSlug.normalize(rawSlug);
            if (slug == null || (allowedSlugs != null && !allowedSlugs.contains(slug))) {
                // ⭐ 只接受本批页面（模型偶尔会返回上一批的 slug）
                continue;
            }
            JsonNode pathNode = n.get("path");
            if (pathNode == null || !pathNode.isArray()) {
                continue;
            }
            List<String> path = new ArrayList<>();
            boolean bad = false;
            for (JsonNode p : pathNode) {
                String seg = p.asText("").trim();
                if (seg.isEmpty() || seg.contains("/")) {
                    bad = true;
                    break;
                }
                String lower = seg.toLowerCase(Locale.ROOT);
                if ("entity".equals(lower) || "concept".equals(lower)) {
                    bad = true;    // 源侧明令禁止
                    break;
                }
                path.add(seg);
            }
            if (bad) {
                continue;
            }
            if (path.size() > 2) {
                // ⭐ 超过 2 级 ⇒ 截断而不是丢弃（截断比丢失归属好）
                path = new ArrayList<>(path.subList(0, 2));
            }
            out.put(slug, path);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════
    // 续写
    // ══════════════════════════════════════════════════════════

    private record RewriteOutcome(String content, boolean truncated) {
    }

    /**
     * 调模型生成页面；被长度截断时续写，最多 {@link #MAX_CONTINUATIONS} 轮。
     *
     * <p>⚠️ 每一轮都要检查 <b>是否还在截断</b>：
     * <ul>
     *   <li>续写返回 {@code (complete)} ⇒ 已写完，停止；</li>
     *   <li>返回空 ⇒ 停止（⛔ 别把空当成写完 —— 那是失败）；</li>
     *   <li>仍然 length ⇒ 继续下一轮；轮次耗尽 ⇒ truncated=true。</li>
     * </ul>
     */
    private RewriteOutcome generateWithContinuation(
        ProviderConfig config, String modelName, String system, String user, String slug
    ) {
        LlmProvider.LlmInvocationResult first = invoke(
            config, modelName, system, user, TEMP_MODIFY, "wiki_page_modify");
        if (first == null) {
            return new RewriteOutcome(null, false);
        }
        StringBuilder acc = new StringBuilder(first.content() == null ? "" : first.content());
        String finish = first.finishReason();
        int round = 0;

        while (isLengthFinish(finish) && round < MAX_CONTINUATIONS) {
            round++;
            log.warn("[Wiki] 页面输出被截断 slug={} finish={} 已写={}字符，请求续写 {}/{}",
                slug, finish, acc.length(), round, MAX_CONTINUATIONS);
            String continueUser = KbWikiPrompts.PAGE_MODIFY_CONTINUATION_USER
                .replace("{{WRITTEN_SO_FAR}}", acc.toString());
            LlmProvider.LlmInvocationResult r = invoke(
                config, modelName,
                KbWikiPrompts.PAGE_MODIFY_CONTINUATION_SYSTEM, continueUser,
                TEMP_MODIFY, "wiki_page_continue");
            if (r == null || r.content() == null || r.content().isBlank()) {
                log.warn("[Wiki] 续写返回空，停止 slug={} 轮次={}", slug, round);
                break;
            }
            String part = r.content();
            if ("(complete)".equals(part.trim())) {
                log.info("[Wiki] 模型声明已写完 slug={} 轮次={}", slug, round);
                finish = "stop";
                break;
            }
            // ⭐ 逐字追加（源侧契约：模型首字符必须紧接着上一个字符）
            acc.append(part);
            finish = r.finishReason();
        }

        boolean truncated = isLengthFinish(finish);
        if (truncated) {
            log.warn("[Wiki] 续写耗尽仍截断 slug={} 轮次={}", slug, round);
        }
        return new RewriteOutcome(acc.toString(), truncated);
    }

    /**
     * 判断是否被长度截断。
     *
     * <p>⭐ 词汇表与源侧 {@code isLengthStopFinishReason} 一致：
     * {@code length} / {@code max_tokens} / {@code max_output_tokens}。
     * ⛔ 不要只判 {@code "length"}：不同 provider 用不同词。
     */
    static boolean isLengthFinish(String reason) {
        if (reason == null) {
            return false;
        }
        return switch (reason.trim().toLowerCase(Locale.ROOT)) {
            case "length", "max_tokens", "max_output_tokens" -> true;
            default -> false;
        };
    }

    // ══════════════════════════════════════════════════════════
    // 文本处理
    // ══════════════════════════════════════════════════════════

    /**
     * 拆分结果。
     *
     * <p>⚠️ 包级可见（不是 private）：这是<b>可单独验证的纯函数输出</b>。
     * 若做成 private，测试只能通过「整页生成」间接断言 SUMMARY 解析 ——
     * 那样一旦解析错，测试看到的是「正文里多了个 SUMMARY 行」这种间接症状，
     * 定位成本高得多。
     */
    record SplitContent(String summary, String body) {
    }

    /**
     * 拆出首行 {@code SUMMARY: ...} 与正文。
     *
     * <p>⚠️ 只认<b>行首</b>的 SUMMARY（{@code MULTILINE}），
     * 否则正文里提到 "SUMMARY:" 会被误当摘要行。
     */
    static SplitContent splitSummaryLine(String content) {
        if (content == null) {
            return new SplitContent(null, "");
        }
        Matcher m = SUMMARY_LINE.matcher(content);
        if (!m.find()) {
            return new SplitContent(null, content.trim());
        }
        String summary = m.group(1) == null ? null : m.group(1).trim();
        String body = content.substring(m.end()).trim();
        return new SplitContent(summary, body);
    }

    /**
     * 剔除内联 chunk 引用 {@code [c003]}。
     *
     * <p>⭐⭐ 为什么必须在<b>两侧</b>都做（读时 + 写后）：
     * 读时剔 ⇒ 模型看不到历史遗留，不会学着写；
     * 写后剔 ⇒ 模型就算被要求了也可能违规，必须兜住。
     * <p>只删「[c+数字]」这一种形态 —— 正文里正常的 {@code [1]} 引用编号<b>保留</b>。
     */
    static String stripInlineChunkCitations(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return INLINE_CHUNK_CITATION.matcher(content).replaceAll("");
    }

    // ══════════════════════════════════════════════════════════
    // 渲染
    // ══════════════════════════════════════════════════════════

    private static String renderNewInformation(List<SlugUpdate> adds) {
        if (adds.isEmpty()) {
            return "（无新增信息）";
        }
        StringBuilder sb = new StringBuilder();
        for (SlugUpdate u : adds) {
            sb.append("<document>\n");
            // ⭐ sourceRef 只含 knowledgeId，不含文件名
            sb.append("<source_ref>").append(u.sourceRef() == null ? "" : u.sourceRef())
                .append("</source_ref>\n");
            sb.append("<content>\n**").append(u.itemName()).append("**: ")
                .append(u.description() == null ? "" : u.description()).append("\n\n");
            String cited = renderCitedChunks(u);
            sb.append(cited.isEmpty() ? (u.details() == null ? "" : u.details()) : cited);
            sb.append("\n</content>\n</document>\n\n");
        }
        return sb.toString();
    }

    /**
     * 渲染被引用的 chunk 正文。
     *
     * <p>⚠️ chunk id <b>不写进</b> <code>&lt;content&gt;</code> 的可读部分 ——
     * 只作为 XML 属性，模型抄进正文的概率低一个数量级（且写后还会再剔一次）。
     */
    private static String renderCitedChunks(SlugUpdate u) {
        if (u.chunkIds() == null || u.chunkIds().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : u.chunkIds()) {
            String text = u.chunkTexts() == null ? null : u.chunkTexts().get(id);
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append("```text\n").append(text.trim()).append("\n```\n");
        }
        return sb.toString();
    }

    private static String renderSharedContexts(List<SlugUpdate> adds) {
        // 同一文档的 context 只写一次（按 sourceRef 去重），且顺序稳定（利于前缀缓存）
        Map<String, String> byRef = new LinkedHashMap<>();
        for (SlugUpdate u : adds) {
            String ref = u.sourceRef() == null ? u.knowledgeId() : u.sourceRef();
            String ctx = u.docSummary();
            if (ref != null && ctx != null && !ctx.isBlank()) {
                byRef.putIfAbsent(ref, ctx);
            }
        }
        if (byRef.isEmpty()) {
            return "（无共享上下文）";
        }
        StringBuilder sb = new StringBuilder();
        byRef.forEach((ref, ctx) -> sb.append("<document>\n<source_ref>").append(ref)
            .append("</source_ref>\n<context>\n").append(ctx.trim())
            .append("\n</context>\n</document>\n"));
        return sb.toString();
    }

    private static String renderDeletionBlock(
        List<String> retracts, ExistingWikiPage existing, List<SlugUpdate> adds
    ) {
        if (retracts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<deleted_documents>\n");
        for (String id : retracts) {
            sb.append("- ").append(id).append('\n');
        }
        sb.append("</deleted_documents>\n");

        sb.append("<remaining_source_documents>\n");
        if (existing != null && existing.sourceRefs() != null) {
            boolean any = false;
            for (String ref : existing.sourceRefs()) {
                if (!retracts.contains(knowledgeIdOfRef(ref))) {
                    sb.append("- ").append(ref).append('\n');
                    any = true;
                }
            }
            if (!any) {
                sb.append("（无剩余来源）\n");
            }
        } else {
            sb.append("（无剩余来源）\n");
        }
        sb.append("</remaining_source_documents>\n");
        return sb.toString();
    }

    private static String renderValidLinks(Map<String, String> links) {
        if (links == null || links.isEmpty()) {
            return "（暂无可链接页面）";
        }
        StringBuilder sb = new StringBuilder();
        links.forEach((slug, title) ->
            sb.append("- [[").append(slug).append("|").append(title).append("]]\n"));
        return sb.toString();
    }

    private static String renderPagesForTaxonomy(Map<String, String> pages) {
        StringBuilder sb = new StringBuilder();
        pages.forEach((slug, title) -> sb.append("- ").append(slug).append(" = ").append(title)
            .append('\n'));
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════
    // 兜底与基础设施
    // ══════════════════════════════════════════════════════════

    private static MergeResult unchanged(String slug, ExistingWikiPage existing) {
        return new MergeResult(
            "unchanged",
            existing == null ? slug : existing.title(),
            existing == null ? "" : existing.content(),
            existing == null ? null : existing.summary(),
            existing == null ? List.of() : existing.aliases(),
            existing == null ? inferPageType(slug) : existing.pageType(),
            existing == null ? List.of() : existing.sourceRefs(),
            existing == null ? List.of() : existing.chunkRefs(),
            null);
    }

    /**
     * 拒绝：<b>页面内容原样保留</b>，只给出原因。
     *
     * <p>⭐⭐ 这是「宁可不更新，也不存半个」的落地 —— 调用方据此标记 additionFailed，
     * 让这批文档留到下一批重试。
     */
    private static MergeResult rejected(String slug, ExistingWikiPage existing, String reason) {
        MergeResult base = unchanged(slug, existing);
        return new MergeResult("rejected", base.title(), base.content(), base.summary(),
            base.aliases(), base.pageType(), base.sourceRefs(), base.chunkRefs(), reason);
    }

    /**
     * 从 sourceRef 里取 knowledgeId。
     *
     * <p>兼容三种形态：纯 ID / {@code id} / {@code id#title}（历史遗留）。
     */
    static String knowledgeIdOfRef(String ref) {
        if (ref == null) {
            return "";
        }
        String s = ref.trim();
        int hash = s.indexOf('#');
        if (hash > 0) {
            return s.substring(0, hash).trim();
        }
        return s;
    }

    private static String inferPageType(String slug) {
        String type = KbWikiSlug.typeOf(slug);
        if (type == null) {
            return "entity";
        }
        return switch (type) {
            case KbWikiSlug.SUMMARY -> "summary";
            case KbWikiSlug.INDEX -> "index";
            default -> type;
        };
    }

    /** 合并别名（去重、保序、忽略空白）。 */
    static List<String> mergeAliases(List<String> existing, List<SlugUpdate> adds) {
        Set<String> out = new LinkedHashSet<>();
        if (existing != null) {
            for (String a : existing) {
                if (a != null && !a.isBlank()) {
                    out.add(a.trim());
                }
            }
        }
        for (SlugUpdate u : adds) {
            if (u.aliases() == null) {
                continue;
            }
            for (String a : u.aliases()) {
                if (a != null && !a.isBlank()) {
                    out.add(a.trim());
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 解析「模型 ID / 名」→ 配置 + 真实模型名。
     *
     * <p>⚠️ 必须整份返回 {@link ModelConfigResolver.ResolvedModel}：调用点要的是
     * {@code apiModelName}（= {@code models.name}），而库里 {@code *_model_id} 存的是
     * {@code models.id}。只带 config 出去，调用点就只好把 ID 当名字发 ——
     * 实测被对方 400 拒绝（{@code but you passed model-0823a98d}）。
     */
    private ModelConfigResolver.ResolvedModel resolveModel(String modelName) {
        if (modelConfigResolver == null) {
            return null;
        }
        try {
            return modelConfigResolver.resolve(modelName);
        } catch (RuntimeException e) {
            log.warn("[Wiki] provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
            return null;
        }
    }

    /**
     * 单发调用（返回完整结果，含 finishReason）。
     *
     * <p>⚠️ 这里必须用 {@code message()} 而不是 {@code chat()} ——
     * {@code chat()} 只返回文本，<b>拿不到 finishReason</b>，
     * 于是「被截断」和「正常结束」无法区分，续写永远不会触发。
     */
    private LlmProvider.LlmInvocationResult invoke(
        ProviderConfig config, String modelName, String system, String user,
        double temperature, String label
    ) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("[Wiki] 调用模型 label={} model={} max_tokens=按模型配置", label, modelName);
            }
            return sideCallClient.message(
                config, modelName, system, user,
                new LlmProvider.ChatRequestOptions(
                    null, null, null,
                    LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
                    temperature, label,
                    null, null, null, null,
                    null, null, null, null,
                    null, null),
                LlmSideCallClient.SideCallUsage.standalone(UsageCategory.SIDE_CALL, label));
        } catch (RuntimeException e) {
            log.warn("[Wiki] 模型调用失败 label={} model={} 原因={}", label, modelName, e.getMessage());
            return null;
        }
    }
}
