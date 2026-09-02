package com.nexusai.application.agent.skillsearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * skill-search 查询意图归一化模块 · 对齐 CC {@code services/skillSearch/intentNormalize.ts}
 * （真源存在，本 checkout 已核实）。
 *
 * <p><b>为什么（意图）</b>: TF-IDF bag-of-words 在用户查询为中文、技能描述多为英文时丢失语义
 * （CJK 双字 DF=1 → 虚假 IDF 提升，产生 spurious 匹配）。在交给 {@code searchSkills()} 前
 * 用 Haiku 把查询归一化为 3-6 个英文任务/对象关键词，与原文拼接让 TF-IDF 同时看到二者。
 *
 * <p>CC 设计（intentNormalize.ts）：
 * <ul>
 *   <li>仅 turn-0 阻塞（session-unique 查询一次 Haiku 调用）</li>
 *   <li>进程级 LRU 缓存（同查询复用；cap 200 / trim 150）</li>
 *   <li>优雅回落：Haiku 失败/超时/空 → 返回原查询</li>
 *   <li>ASCII-only 快路径：无 CJK 字符直接跳过 LLM</li>
 *   <li>feature 门控：{@code SKILL_SEARCH_INTENT_ENABLED === '1'}（Java 兼读 DB
 *       settings.skill_search_intent_enabled，PromptAlignSettingsResolver 只读引用）</li>
 * </ul>
 *
 * <p><b>调用点登记</b>: {@link SkillSearchPrefetch.Default#getTurnZeroSkillDiscovery} 已接
 * normalize 为搜索前置步（对齐 CC prefetch.ts:326）。Java skill-search 子系统无真实搜索索引
 * （骨架恒空集）→ normalize 输出当前无生产消费方（门控 + CJK + 非空已在 normalize 内短路，
 * 不浪费 Haiku 调用）。模块由单测覆盖。
 */
public interface SkillSearchIntentNormalize {

    /**
     * 归一化用户查询 · CC original: {@code normalizeQueryIntent}
     * （intentNormalize.ts:89-111）。
     *
     * <p>返回 {@code <original> <keywords>}（成功）或原串（任何失败路径）。绝不抛异常。
     *
     * @param query 用户查询（null → null）
     * @return 归一化查询串
     */
    String normalizeQueryIntent(String query);

    /**
     * 是否启用意图归一化 · CC original: {@code isIntentNormalizeEnabled}
     * （intentNormalize.ts:80 env {@code SKILL_SEARCH_INTENT_ENABLED === '1'}）。
     *
     * @return true = 启用（DB settings 优先，回落 env）
     */
    boolean isIntentNormalizeEnabled();

    /** 清空进程级缓存 · CC original: {@code clearIntentNormalizeCache}（仅测试间重置）。 */
    void clearIntentNormalizeCache();

    /**
     * 默认实现 · 逐字节对齐 CC intentNormalize.ts。
     *
     * <p>构造注入：
     * <ul>
     *   <li>{@code enabledSupplier}：门控求值（DB resolver 非 null → 用之；null → 回落
     *       env {@code SKILL_SEARCH_INTENT_ENABLED === '1'}，CC :80）；null → 恒 env</li>
     *   <li>{@code haikuInvoker}：Haiku 调用器（(systemPrompt, userPrompt) → 文本响应）；
     *       null → 跳过 LLM 返回原串（避免占位路径浪费 Haiku 调用）</li>
     * </ul>
     */
    final class Default implements SkillSearchIntentNormalize {

        private static final Logger log = LoggerFactory.getLogger(Default.class);

        /** CC INTENT_SYSTEM_PROMPT（intentNormalize.ts:27-44 原文，逐字节）。 */
        static final String INTENT_SYSTEM_PROMPT =
            "You are a query normalizer for a skill-search index.\n\n"
            + "Given a user's natural-language request (often Chinese, possibly long), extract 3-6 English keywords that capture:\n"
            + "1. TASK VERB (optimize, review, debug, refactor, test, deploy, analyze, write, audit, design, research, cleanup, implement)\n"
            + "2. OBJECT (code, prompt, test, UI, API, database, documentation, performance, security, architecture)\n"
            + "3. CONTEXT/DOMAIN when clear (frontend, backend, mobile, python, go, rust, typescript)\n\n"
            + "Output ONLY space-separated lowercase English keywords. No prose, no JSON, no punctuation, no code fences.\n\n"
            + "Examples:\n"
            + "- \"帮我优化代码的性能\" -> optimize code performance refactor\n"
            + "- \"研究当前代码的实现然后分析优化思路\" -> analyze code research refactor architecture\n"
            + "- \"优化 prompt 的表达\" -> optimize prompt refine writing\n"
            + "- \"帮我做 code review\" -> code review audit\n"
            + "- \"清理代码里的 TODO\" -> cleanup refactor dead-code\n"
            + "- \"重构这个模块的代码\" -> refactor code modularize\n"
            + "- \"帮我写个 Go 单元测试\" -> write test golang unit\n\n"
            + "Output ONLY keywords. Nothing else.";

        /** CC DEFAULT_TIMEOUT_MS（intentNormalize.ts:46）= 6000ms（超时由调用器承载）。 */
        static final int DEFAULT_TIMEOUT_MS = 6_000;
        /** CC MAX_QUERY_CHARS（:47）= 500。 */
        static final int MAX_QUERY_CHARS = 500;
        /** CC MAX_KEYWORDS_CHARS（:48）= 120。 */
        static final int MAX_KEYWORDS_CHARS = 120;
        /** CC CACHE_MAX_ENTRIES（:56）= 200。 */
        static final int CACHE_MAX_ENTRIES = 200;
        /** CC CACHE_TRIM_TO（:57）= 150。 */
        static final int CACHE_TRIM_TO = 150;

        /** CC CJK 判定正则 {@code /[一-鿿]/}（:96）—— 非 CJK 快路径跳过 LLM。 */
        private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");
        /** CC sanitize 正则 {@code /[^a-z0-9\- ]+/g}（:143）。 */
        private static final Pattern NON_KEYWORD_PATTERN = Pattern.compile("[^a-z0-9\\- ]+");
        /** CC 空白折叠 {@code /\s+/g}（:144）。 */
        private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

        /** CC env 门控键（intentNormalize.ts:80）。 */
        static final String SKILL_SEARCH_INTENT_ENABLED_ENV = "SKILL_SEARCH_INTENT_ENABLED";

        /** Haiku 调用器 · 镜像 CC queryHaiku（intentNormalize.ts:115-128）。 */
        @FunctionalInterface
        public interface HaikuInvoker {
            /**
             * @param systemPrompt 系统提示（INTENT_SYSTEM_PROMPT）
             * @param userPrompt   截断后的查询（max 500 chars）
             * @return 文本响应（sanitize 前原始文本）；异常由调用方处理
             */
            String invoke(String systemPrompt, String userPrompt);
        }

        private final BooleanSupplier enabledSupplier;
        private final HaikuInvoker haikuInvoker;
        /** 进程级查询→关键词缓存 · 插入序 LRU（CC intentNormalize.ts:59 Map）。 */
        private final Map<String, String> cache = new LinkedHashMap<>();

        /**
         * 默认构造：门控仅 env（CC :80 原生），无 LLM 通道（null → 返回原串）。
         */
        public Default() {
            this(null, null);
        }

        /**
         * 完整构造。
         *
         * @param enabledSupplier 门控求值（null → 恒 env {@code SKILL_SEARCH_INTENT_ENABLED === '1'}）
         * @param haikuInvoker    Haiku 调用器（null → 跳过 LLM 返回原串）
         */
        public Default(BooleanSupplier enabledSupplier, HaikuInvoker haikuInvoker) {
            this.enabledSupplier = enabledSupplier;
            this.haikuInvoker = haikuInvoker;
        }

        @Override
        public boolean isIntentNormalizeEnabled() {
            if (enabledSupplier != null) {
                Boolean v = enabledSupplier.getAsBoolean();
                if (v != null) {
                    return v;
                }
            }
            return "1".equals(System.getenv(SKILL_SEARCH_INTENT_ENABLED_ENV));
        }

        @Override
        public void clearIntentNormalizeCache() {
            cache.clear();
        }

        @Override
        public String normalizeQueryIntent(String query) {
            if (query == null) {
                return null;
            }
            String trimmed = query.trim();
            if (trimmed.isEmpty()) {
                return trimmed;
            }
            if (!isIntentNormalizeEnabled()) {
                return trimmed;
            }
            // ASCII-only 快路径：无 CJK 字符 → 索引侧已是正确形态（intentNormalize.ts:96-97）
            if (!CJK_PATTERN.matcher(trimmed).find()) {
                return trimmed;
            }
            String cached = getCached(trimmed);
            if (cached != null) {
                return cached;
            }
            if (haikuInvoker == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[SkillSearchIntentNormalize] 无 Haiku 通道，返回原串（避免占位路径浪费 LLM 调用）· intentNormalize.ts:115-128");
                }
                return trimmed;
            }
            String capped = trimmed.length() > MAX_QUERY_CHARS
                ? trimmed.substring(0, MAX_QUERY_CHARS) : trimmed;
            String keywords;
            try {
                keywords = sanitizeKeywords(haikuInvoker.invoke(INTENT_SYSTEM_PROMPT, capped));
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[SkillSearchIntentNormalize] Haiku 调用失败，回落原串: {} · intentNormalize.ts:124-127",
                        e.getMessage());
                }
                keywords = "";
            }
            String result = (keywords != null && !keywords.isEmpty())
                ? trimmed + " " + keywords : trimmed;
            putCached(trimmed, result);
            if (log.isDebugEnabled()) {
                log.debug("[SkillSearchIntentNormalize] intent normalized: \"{}\" -> \"{}\" · intentNormalize.ts:107-109",
                    trimmed.length() > 40 ? trimmed.substring(0, 40) + "…" : trimmed,
                    keywords);
            }
            return result;
        }

        /** 命中刷新插入序（LRU 存活）· CC :99-102（cache.delete + cache.set）。 */
        private String getCached(String key) {
            String v = cache.remove(key);
            if (v != null) {
                cache.put(key, v);
            }
            return v;
        }

        /** 写入 + 超容驱逐（front 至 CACHE_TRIM_TO）· CC :66-75 setCachedQueryIntent。 */
        private void putCached(String key, String value) {
            if (cache.containsKey(key)) {
                cache.remove(key);
            }
            cache.put(key, value);
            if (cache.size() > CACHE_MAX_ENTRIES) {
                int toDrop = cache.size() - CACHE_TRIM_TO;
                var it = cache.keySet().iterator();
                for (int i = 0; i < toDrop && it.hasNext(); i++) {
                    it.next();
                    it.remove();
                }
            }
        }

        /** CC sanitizeKeywords（intentNormalize.ts:141-149）：小写 → 去非关键词字符 → 折叠空白 → cap 120。 */
        private static String sanitizeKeywords(String raw) {
            if (raw == null || raw.isEmpty()) {
                return "";
            }
            String cleaned = NON_KEYWORD_PATTERN.matcher(raw.toLowerCase()).replaceAll(" ");
            cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ").trim();
            if (cleaned.isEmpty()) {
                return "";
            }
            return cleaned.length() > MAX_KEYWORDS_CHARS
                ? cleaned.substring(0, MAX_KEYWORDS_CHARS) : cleaned;
        }
    }
}
