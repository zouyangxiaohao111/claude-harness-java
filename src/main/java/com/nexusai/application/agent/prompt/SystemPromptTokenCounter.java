package com.nexusai.application.agent.prompt;

import com.nexusai.infra.llm.CountTokensClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系统提示 token 计数 · 重建对齐 CC {@code analyzeContext.ts:272-318 countSystemTokens}。
 *
 * <p>纯能力落地（无命令消费方）：输入系统提示分段数组（{@code effectiveSystemPrompt}）+ 系统上下文
 * （{@code systemContext}，Java 端由调用方注入），产出总 token + 逐 section 明细。
 *
 * <p>对齐要点：
 * <ol>
 *   <li><b>过滤</b>（analyzeContext.ts:283-293）：跳过空串与 {@code SYSTEM_PROMPT_DYNAMIC_BOUNDARY}
 *       标记；systemContext 仅并入非空条目（key 作 name）。</li>
 *   <li><b>名称提取</b>（analyzeContext.ts:261-270 extractSectionName）：首个 markdown heading
 *       {@code ^#+\s+(.+)$}（m flag）取 group(1).trim()；否则首非空行，{@code >40} 字符截 40 + '…'
 *       （=40 不截）。</li>
 *   <li><b>计数口径</b>（tokenEstimation.ts:124-201 + analyzeContext.ts:77-109/299-317）：逐 section
 *       委托 {@link CountTokensClient}（真实 LLM countTokens API，POST /v1/messages/count_tokens），
 *       API 失败/null → 该 section {@code tokens=0}（analyzeContext.ts:308 {@code tokens||0}）；全失败 →
 *       {@code systemPromptTokens=0}。<b>已接入真实 API</b>，本地 rough（round(len/4)）不再作为主计数路径。</li>
 *   <li><b>短路</b>（analyzeContext.ts:295-297）：namedEntries 为空 → {@code {0, []}}。</li>
 * </ol>
 */
public final class SystemPromptTokenCounter {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptTokenCounter.class);

    /** 首个 markdown heading 提取 · CC original: extractSectionName (analyzeContext.ts:263)，m flag 多行锚定 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("(?m)^#+\\s+(.+)$");

    /** 名称兜底截断上限（字符）· CC analyzeContext.ts:269 firstLine.length > 40 → slice(0,40) + '…' */
    private static final int NAME_PREVIEW_MAX = 40;

    /** 截断省略号 · CC analyzeContext.ts:269 '…' */
    private static final String NAME_PREVIEW_ELLIPSIS = "…";

    /** 逐 section 计数明细 · CC original: SystemPromptSectionDetail (analyzeContext.ts:152-155) */
    public record SystemPromptSectionDetail(String name, int tokens) {
    }

    /** 系统提示总计数 · CC original: countSystemTokens 返回结构 (analyzeContext.ts:274-276) */
    public record SystemTokenCounts(int systemPromptTokens, List<SystemPromptSectionDetail> systemPromptSections) {
    }

    /** 内部命名条目：[name, content] · CC namedEntries 元素（analyzeContext.ts:283-293） */
    private record NamedEntry(String name, String content) {
    }

    private SystemPromptTokenCounter() {
    }

    /**
     * 系统提示 token 计数 · CC original: countSystemTokens (analyzeContext.ts:272-318)。
     *
     * <p>逐 section 委托 {@code counter}（真实 countTokens API），API 失败/null → 该 section
     * {@code tokens=0}（analyzeContext.ts:308 {@code tokens||0}），不再回退 rough 估算主路径。
     *
     * @param effectiveSystemPrompt 系统提示分段数组（effectiveSystemPrompt，CC 入参）
     * @param systemContext         系统上下文（CC getSystemContext 产物，Java 端由调用方注入，允许 null）
     * @param counter               countTokens 客户端（CC countTokensWithFallback 等价，analyzeContext.ts:301）
     * @return 总 token + 逐 section 明细；无可计数 section → {@code {0, []}}
     */
    public static SystemTokenCounts count(List<String> effectiveSystemPrompt, Map<String, String> systemContext,
                                          CountTokensClient counter) {
        // 1. namedEntries：系统提示分段（滤空串 + boundary）+ systemContext 非空条目（analyzeContext.ts:283-293）
        List<NamedEntry> namedEntries = new ArrayList<>();
        if (effectiveSystemPrompt != null) {
            for (String content : effectiveSystemPrompt) {
                if (content != null && content.length() > 0
                        && !SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY.equals(content)) {
                    namedEntries.add(new NamedEntry(extractSectionName(content), content));
                }
            }
        }
        if (systemContext != null) {
            for (Map.Entry<String, String> entry : systemContext.entrySet()) {
                String content = entry.getValue();
                if (content != null && content.length() > 0) {
                    namedEntries.add(new NamedEntry(entry.getKey(), content));
                }
            }
        }

        // 2. 空数组短路（analyzeContext.ts:295-297）
        if (namedEntries.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptTokenCounter] 无可计数 section（空数组/全 boundary/空 context）→ systemPromptTokens=0, sections=[]");
            }
            return new SystemTokenCounts(0, List.of());
        }

        // 3. 逐 section API 计数求和（analyzeContext.ts:299-317：countTokensWithFallback([{role:'user',
        //    content}], []) → tokens||0）
        List<SystemPromptSectionDetail> sections = new ArrayList<>(namedEntries.size());
        int total = 0;
        for (NamedEntry entry : namedEntries) {
            Integer raw = counter.countTokens(entry.content());
            int tokens = raw == null ? 0 : raw; // analyzeContext.ts:308 tokens || 0
            total += tokens;
            sections.add(new SystemPromptSectionDetail(entry.name(), tokens));
        }
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptTokenCounter] 计数完成：{} 个 section，systemPromptTokens={}", sections.size(), total);
        }
        return new SystemTokenCounts(total, sections);
    }

    /**
     * 从 section 内容提取展示名 · CC original: extractSectionName (analyzeContext.ts:261-270)。
     *
     * <p>首个 markdown heading（{@code ^#+\s+(.+)$} m flag）→ group(1).trim()；否则首非空行，
     * {@code >40} 字符截断加 '…'（=40 保持原样）。
     *
     * @param content section 内容（调用方保证非空非 boundary，null 防御返回空串）
     * @return 展示名
     */
    static String extractSectionName(String content) {
        if (content == null) {
            return "";
        }
        Matcher heading = HEADING_PATTERN.matcher(content);
        if (heading.find()) {
            return heading.group(1).trim();
        }
        String firstLine = "";
        for (String line : content.split("\n", -1)) {
            if (!line.trim().isEmpty()) {
                firstLine = line;
                break;
            }
        }
        return firstLine.length() > NAME_PREVIEW_MAX
                ? firstLine.substring(0, NAME_PREVIEW_MAX) + NAME_PREVIEW_ELLIPSIS
                : firstLine;
    }
}
