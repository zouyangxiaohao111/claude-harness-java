package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.RequestContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * claude-api bundled skill 注册器 · 对齐 CC skills/bundled/claudeApi.ts.
 *
 * <p>L1 语义: `/claude-api` 斜杠命令 — 构建 Claude API/Anthropic SDK 集成的 prompt.
 *            检测项目语言 (8 种:python/typescript/java/go/ruby/csharp/php/curl),
 *            加载对应语言文档 + shared 文档,拼装 prompt.
 *            prompt = SKILL_PROMPT (到 Reading Guide 之前) + reading guide + inline docs + (WebFetch section) + user args.
 *            SKILL_MODEL_VARS 替换 {{var}} 占位符;HTML 注释剥离.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: register() → BundledSkillDefinition;8 个 LANGUAGE_INDICATORS;
 *       processContent (strip HTML comments + {{var}} 替换);
 *       buildInlineReference 按 path 排序输出 &lt;doc&gt; 块;
 *       buildPrompt 主链 (read SKILL_PROMPT + detectLanguage + reading guide + inline).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — detectLanguage → getFilesForLanguage (lang/* + shared/*) →
 *       buildInlineReference → buildPrompt (basePrompt = SKILL_PROMPT to Reading Guide) +
 *       readingGuide (replace {lang}) + docs + WebFetch section + args.</li>
 *   <li><b>A3</b>: 状态: detectLanguage (null → include all docs + ask user) vs (lang → filtered);
 *       processContent 循环剥离 nested HTML comments.</li>
 *   <li><b>A4</b>: readdir 失败 (NoSuchFile) → null language → include all docs;
 *       缺 Reading Guide section → 用整个 SKILL_PROMPT;
 *       缺 WebFetch section → 不附加 WebFetch section;
 *       {{var}} 缺 key → 保留原 {{var}} (不抛).</li>
 *   <li><b>A5</b>: 真实场景 — Java Spring Boot 项目 → java detected → 加载 java/claude-api/* + shared/* + reading guide 替换 {lang}=java.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async readdir → 注入式 Supplier&lt;List&lt;String&gt;&gt; (entries, 测试确定性)
 *                    + {@link #detectLanguage(String)} 运行时 cwd 真实 readdir（生产主路径,
 *                    promptFn cwd = ctx.effectiveCwd = CC getCwd 等价；ALIGN-BUNDLED-2）；
 *                    TS dynamic import('./claudeApiContent.js') → 注入式 SkillContentSupplier (懒加载);
 *                    TS `as const` LANGUAGE_INDICATORS → Java Map&lt;DetectedLanguage, List&lt;String&gt;&gt;.
 */
public final class ClaudeApiSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ClaudeApiSkillRegistrar.class);

    /** CC claudeApi.ts:77 — `/\{\{(\w+)\}\}/g`（key 限 [A-Za-z0-9_]，未知 key 保留原样）. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    /** CC DetectedLanguage 8 枚举. */
    public enum DetectedLanguage {
        PYTHON("python"), TYPESCRIPT("typescript"), JAVA("java"), GO("go"),
        RUBY("ruby"), CSHARP("csharp"), PHP("php"), CURL("curl");

        private final String value;
        DetectedLanguage(String value) { this.value = value; }
        public String getValue() { return value; }

        public static DetectedLanguage fromValue(String v) {
            for (DetectedLanguage l : values()) if (l.value.equals(v)) return l;
            return null;
        }
    }

    /** CC LANGUAGE_INDICATORS — 文件后缀/文件名. */
    public static final Map<DetectedLanguage, List<String>> LANGUAGE_INDICATORS = new LinkedHashMap<>();
    static {
        LANGUAGE_INDICATORS.put(DetectedLanguage.PYTHON,
            List.of(".py", "requirements.txt", "pyproject.toml", "setup.py", "Pipfile"));
        LANGUAGE_INDICATORS.put(DetectedLanguage.TYPESCRIPT,
            List.of(".ts", ".tsx", "tsconfig.json", "package.json"));
        LANGUAGE_INDICATORS.put(DetectedLanguage.JAVA,
            List.of(".java", "pom.xml", "build.gradle"));
        LANGUAGE_INDICATORS.put(DetectedLanguage.GO,
            List.of(".go", "go.mod"));
        LANGUAGE_INDICATORS.put(DetectedLanguage.RUBY,
            List.of(".rb", "Gemfile"));
        LANGUAGE_INDICATORS.put(DetectedLanguage.CSHARP,
            List.of(".cs", ".csproj"));
        LANGUAGE_INDICATORS.put(DetectedLanguage.PHP,
            List.of(".php", "composer.json"));
        LANGUAGE_INDICATORS.put(DetectedLanguage.CURL, List.of());
    }

    private static final String INLINE_READING_GUIDE = """
        ## Reference Documentation

        The relevant documentation for your detected language is included below in `<doc>` tags. Each tag has a `path` attribute showing its original file path. Use this to find the right section:

        ### Quick Task Reference

        **Single text classification/summarization/extraction/Q&A:**
        → Refer to `{lang}/claude-api/README.md`

        **Chat UI or real-time response display:**
        → Refer to `{lang}/claude-api/README.md` + `{lang}/claude-api/streaming.md`

        **Long-running conversations (may exceed context window):**
        → Refer to `{lang}/claude-api/README.md` — see Compaction section

        **Prompt caching / optimize caching / "why is my cache hit rate low":**
        → Refer to `shared/prompt-caching.md` + `{lang}/claude-api/README.md` (Prompt Caching section)

        **Function calling / tool use / agents:**
        → Refer to `{lang}/claude-api/README.md` + `shared/tool-use-concepts.md` + `{lang}/claude-api/tool-use.md`

        **Batch processing (non-latency-sensitive):**
        → Refer to `{lang}/claude-api/README.md` + `{lang}/claude-api/batches.md`

        **File uploads across multiple requests:**
        → Refer to `{lang}/claude-api/README.md` + `{lang}/claude-api/files-api.md`

        **Agent with built-in tools (file/web/terminal) (Python & TypeScript only):**
        → Refer to `{lang}/agent-sdk/README.md` + `{lang}/agent-sdk/patterns.md`

        **Error handling:**
        → Refer to `shared/error-codes.md`

        **Latest docs via WebFetch:**
        → Refer to `shared/live-sources.md` for URLs""";

    private final Supplier<List<String>> cwdEntriesSupplier;       // 注入式 readdir
    private final SkillContentSupplier contentSupplier;             // 注入式 dynamic import

    public ClaudeApiSkillRegistrar(Supplier<List<String>> cwdEntriesSupplier,
                                    SkillContentSupplier contentSupplier) {
        this.cwdEntriesSupplier = Objects.requireNonNull(cwdEntriesSupplier);
        this.contentSupplier = Objects.requireNonNull(contentSupplier);
    }

    /** Skill content (CC claudeApiContent.js SKILL_FILES + SKILL_PROMPT + SKILL_MODEL_VARS). */
    public interface SkillContent {
        Map<String, String> SKILL_FILES();
        String SKILL_PROMPT();
        Map<String, String> SKILL_MODEL_VARS();
    }

    /** Skill content supplier (注入 — 懒加载). */
    @FunctionalInterface
    public interface SkillContentSupplier {
        SkillContent load();
    }

    /**
     * CC detectLanguage（claudeApi.ts:35-53）· 无 cwd（测试注入）→ 用注入 supplier 的 entries。
     */
    public DetectedLanguage detectLanguage() {
        return detectLanguage(null);
    }

    /**
     * CC detectLanguage（claudeApi.ts:35-53）— readdir(cwd) entries + indicator 匹配。
     *
     * <p>cwd != null（生产：promptFn 运行时 cwd = ctx.effectiveCwd，CC getCwd 等价）→ 真实
     * readdir（claudeApi.ts:37-39）；readdir 失败 → null（CC :38-40 catch → null → 全文档分支）。
     * cwd == null → 注入 supplier（测试确定性 / 无会话 cwd 时回落进程 cwd 由 Bootstrapper 供应）。
     */
    public DetectedLanguage detectLanguage(String cwd) {
        List<String> entries;
        if (cwd != null) {
            try (java.util.stream.Stream<Path> stream = Files.list(Paths.get(cwd))) {
                entries = stream.map(p -> p.getFileName().toString())
                    .collect(java.util.stream.Collectors.toList());
            } catch (IOException e) {
                log.debug("[ClaudeApiSkill] cwd readdir failed: {}", e.getMessage());
                return null;
            }
        } else {
            try {
                entries = cwdEntriesSupplier.get();
            } catch (Exception e) {
                log.debug("[ClaudeApiSkill] cwd readdir failed: {}", e.getMessage());
                return null;
            }
            if (entries == null) return null;
        }

        for (Map.Entry<DetectedLanguage, List<String>> e : LANGUAGE_INDICATORS.entrySet()) {
            List<String> indicators = e.getValue();
            if (indicators.isEmpty()) continue;
            for (String indicator : indicators) {
                if (indicator.startsWith(".")) {
                    if (entries.stream().anyMatch(en -> en.endsWith(indicator))) {
                        return e.getKey();
                    }
                } else {
                    if (entries.contains(indicator)) {
                        return e.getKey();
                    }
                }
            }
        }
        return null;
    }

    /** CC getFilesForLanguage — lang/* + shared/*. */
    public List<String> getFilesForLanguage(DetectedLanguage lang, SkillContent content) {
        List<String> result = new ArrayList<>();
        for (String path : content.SKILL_FILES().keySet()) {
            if (path.startsWith(lang.getValue() + "/") || path.startsWith("shared/")) {
                result.add(path);
            }
        }
        return result;
    }

    /** CC processContent — strip HTML comments + {{var}} 替换. */
    public String processContent(String md, SkillContent content) {
        // Strip HTML comments (loop for nested)
        String out = md;
        String prev;
        do {
            prev = out;
            out = out.replaceAll("(?s)<!--.*?-->\\n?", "");
        } while (!out.equals(prev));

        // Replace {{var}} placeholders — CC claudeApi.ts:77-82 `/\{\{(\w+)\}\}/g` with
        // `SKILL_MODEL_VARS[key] ?? match`. 用正则（非手工扫描）复现 JS 正则语义：
        // key 限 \w = [A-Za-z0-9_]，未知 key 保留原 {{...}}；嵌套花括号（如 {{a{{b}}、
        // {{{{OPUS_ID}}}}）与 JS 引擎逐位一致（手工扫描器在这些边界会保留整个串，行为不同）.
        Map<String, String> vars = content.SKILL_MODEL_VARS();
        Matcher m = PLACEHOLDER_PATTERN.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** CC buildInlineReference — 按 path 排序,生成 <doc> 块. */
    public String buildInlineReference(List<String> filePaths, SkillContent content) {
        List<String> sorted = new ArrayList<>(filePaths);
        java.util.Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String filePath : sorted) {
            String md = content.SKILL_FILES().get(filePath);
            if (md == null) continue;
            sb.append("<doc path=\"").append(filePath).append("\">\n");
            sb.append(processContent(md, content).trim());
            sb.append("\n</doc>\n\n");
        }
        return sb.toString().trim();
    }

    /** CC buildPrompt — 主链. */
    public String buildPrompt(DetectedLanguage lang, String args) {
        SkillContent content = contentSupplier.load();

        String cleanPrompt = processContent(content.SKILL_PROMPT(), content);
        int readingGuideIdx = cleanPrompt.indexOf("## Reading Guide");
        // CC claudeApi.ts:141-143 — slice(0, idx).trimEnd() vs 整段原样（无 Reading Guide 不 trim）.
        String basePrompt = readingGuideIdx != -1
            ? cleanPrompt.substring(0, readingGuideIdx).stripTrailing()
            : cleanPrompt;

        StringBuilder parts = new StringBuilder();
        parts.append(basePrompt);

        if (lang != null) {
            List<String> filePaths = getFilesForLanguage(lang, content);
            String readingGuide = INLINE_READING_GUIDE.replace("{lang}", lang.getValue());
            parts.append("\n\n").append(readingGuide);
            parts.append("\n\n---\n\n## Included Documentation\n\n");
            parts.append(buildInlineReference(filePaths, content));
        } else {
            String readingGuide = INLINE_READING_GUIDE.replace("{lang}", "unknown");
            parts.append("\n\n").append(readingGuide);
            parts.append("\n\nNo project language was auto-detected. Ask the user which language they are using, then refer to the matching docs below.");
            parts.append("\n\n---\n\n## Included Documentation\n\n");
            parts.append(buildInlineReference(new ArrayList<>(content.SKILL_FILES().keySet()), content));
        }

        int webFetchIdx = cleanPrompt.indexOf("## When to Use WebFetch");
        if (webFetchIdx != -1) {
            // CC claudeApi.ts:169-170 — slice(webFetchIdx).trimEnd()
            parts.append("\n\n").append(cleanPrompt.substring(webFetchIdx).stripTrailing());
        }

        if (args != null && !args.isEmpty()) {
            parts.append("\n\n## User Request\n\n").append(args);
        }
        return parts.toString();
    }

    /** CC registerClaudeApiSkill — 统一产出 BundledSkillDefinition（P1-4）. */
    public BundledSkillDefinition register() {
        return new BundledSkillDefinition(
            "claude-api",
            "Build apps with the Claude API or Anthropic SDK.\n"
                + "TRIGGER when: code imports `anthropic`/`@anthropic-ai/sdk`/`claude_agent_sdk`, or user asks to use Claude API, Anthropic SDKs, or Agent SDK.\n"
                + "DO NOT TRIGGER when: code imports `openai`/other AI SDK, general programming, or ML/data-science tasks.",
            null,   // aliases
            null,   // whenToUse
            null,   // argumentHint
            List.of("Read", "Grep", "Glob", "WebFetch"),   // allowedTools (CC claudeApi.ts:187)
            null,   // model
            null,   // disableModelInvocation (CC undefined → default false)
            true,   // userInvocable (CC claudeApi.ts:188 显式 true 透传，替代 Command 构造默认兜底)
            null,   // isEnabled
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, context) -> {
                // ALIGN-BUNDLED-2：生产检测用运行时 cwd（ctx.effectiveCwd，CC getCwd 等价），
                // 旧实现恒用构造期 supplier（Bootstrapper 传 List::of → detectLanguage 恒 null → 恒全文档分支）。
                // [拍板#9 part2] 第二参升级 PromptFnContext：cwd 取 context.cwd()（会话通道，见 PromptFnContext）。
                DetectedLanguage lang = detectLanguage(context.cwd());
                String prompt = buildPrompt(lang, args);
                return List.of(PromptBlock.text(prompt));
            }
        );
    }

    /**
     * 进程 cwd entries · CC getCwd()（utils/cwd.js = process.cwd()）→ readdir（claudeApi.ts:37-39）。
     *
     * <p>无会话 cwd（promptFn cwd == null）时的回落供应；IO 失败 → 空列表（与 CC readdir catch
     * → null → 全文档分支同可观测结果）。生产主路径经 {@link #detectLanguage(String)} 用运行时 cwd。
     *
     * <p>cwd-align-ext：兜底通道改走会话 cwd（CC claudeApi.ts:31 {@code const cwd = getCwd()} →
     * :34 {@code readdir(cwd)}）；无 sessionId 回落 user.dir（方案 1，零行为变化）。
     */
    public static List<String> listCwdEntries() {
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        Path base = Path.of(cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", "."));
        try (java.util.stream.Stream<Path> stream = Files.list(base)) {
            return stream.map(p -> p.getFileName().toString())
                .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }
}
