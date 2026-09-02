package com.nexusai.application.agent.voice;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voice STT keyterms 构造器 · 对齐 CC services/voiceKeyterms.ts.
 *
 * <p>L1 语义: 为 voice_stream STT 端点构造 Deepgram "keywords" 列表,
 *            提升对 coding 术语/项目名/分支名的识别率. 组合:
 *            (1) 硬编码 GLOBAL_KEYTERMS (14 个)
 *            (2) 项目根 basename (整体作为一个 term,避免分隔符拆分)
 *            (3) git branch 分词
 *            (4) recent file names 分词,达到 MAX_KEYTERMS=50 即停止.
 *            splitIdentifier 支持 camelCase/PascalCase/kebab/snake/path 拆分,
 *            仅保留 3-20 字符的词 (避免噪声).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: GLOBAL_KEYTERMS 14 字段 + MAX_KEYTERMS=50 + splitIdentifier (字符 3-20);
 *       getVoiceKeyterms(recentFiles?) → List&lt;String&gt;; 不可重复 (Set dedup).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — Set&lt;GLOBAL&gt; + add(projectRoot basename, 长度 3-50)
 *       + for each branch word (3-20 字符) add + for each filePath add words 直到 size≥50.</li>
 *   <li><b>A3</b>: 状态机: EMPTY → GLOBAL_ADDED → PROJECT_ADDED → BRANCH_ADDED → FILES_ADDED;
 *       projectRoot/branch throws → catch + ignore (静默).</li>
 *   <li><b>A4</b>: projectRoot 空/throw → 跳过;branch 空/throw → 跳过;recentFiles 空 → 跳过;
 *       filename 词 ≤2 字符或 >20 字符 → 过滤;projectRoot basename ≤2 或 >50 字符 → 跳过.</li>
 *   <li><b>A5</b>: 真实场景 — 用户在 claude-code 项目 feat/voice-keyterms 分支编辑 MCPClient.java → keyterms 含 MCP/git/voice/keyterms/MCPClient (camelCase 拆词).</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async/await → 同步 Supplier (项目根/分支在启动时一次性获取);
 *                    TS regex split → Java Pattern;
 *                    TS Set/Array → Java LinkedHashSet (保插入顺序) → List.
 */
public final class VoiceKeytermsBuilder {

    private static final Logger log = LoggerFactory.getLogger(VoiceKeytermsBuilder.class);

    /** CC GLOBAL_KEYTERMS — 14 hardcoded terms. */
    public static final java.util.List<String> GLOBAL_KEYTERMS = java.util.List.of(
        "MCP", "symlink", "grep", "regex", "localhost", "codebase",
        "TypeScript", "JSON", "OAuth", "webhook", "gRPC", "dotfiles",
        "subagent", "worktree"
    );

    public static final int MAX_KEYTERMS = 50;
    private static final int MIN_WORD_LEN = 3;
    private static final int MAX_WORD_LEN = 20;
    private static final int MIN_BASENAME_LEN = 3;
    private static final int MAX_BASENAME_LEN = 50;

    private static final Pattern CAMEL_SPLIT = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern IDENTIFIER_SPLIT = Pattern.compile("[-_./\\s]+");
    private static final Pattern EXT_STRIP = Pattern.compile("\\.[^.]+$");

    private final Supplier<Path> projectRootSupplier;
    private final Supplier<String> branchSupplier;

    public VoiceKeytermsBuilder(Supplier<Path> projectRootSupplier,
                                 Supplier<String> branchSupplier) {
        this.projectRootSupplier = Objects.requireNonNull(projectRootSupplier);
        this.branchSupplier = Objects.requireNonNull(branchSupplier);
    }

    /** CC splitIdentifier — camelCase + kebab/snake/path 拆分,长度 3-20. */
    public static java.util.List<String> splitIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return java.util.List.of();
        }
        String spaced = CAMEL_SPLIT.matcher(name).replaceAll("$1 $2");
        String[] parts = IDENTIFIER_SPLIT.split(spaced);
        java.util.List<String> out = new java.util.ArrayList<>(parts.length);
        for (String p : parts) {
            String trimmed = p.trim();
            if (trimmed.length() >= MIN_WORD_LEN && trimmed.length() <= MAX_WORD_LEN) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** CC fileNameWords — basename 去扩展名 + splitIdentifier. */
    public static java.util.List<String> fileNameWords(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return java.util.List.of();
        }
        Path p = Path.of(filePath);
        String stem = p.getFileName().toString();
        stem = EXT_STRIP.matcher(stem).replaceFirst("");
        return splitIdentifier(stem);
    }

    /**
     * CC getVoiceKeyterms — 主链.
     * 合并 GLOBAL + project root basename + branch words + recent file words,上限 MAX_KEYTERMS.
     */
    public java.util.List<String> getVoiceKeyterms(java.util.Set<String> recentFiles) {
        Set<String> terms = new LinkedHashSet<>(GLOBAL_KEYTERMS);

        // Project root basename (整体作为一个 term)
        try {
            Path projectRoot = projectRootSupplier.get();
            if (projectRoot != null) {
                Path fileName = projectRoot.getFileName();
                if (fileName != null) {
                    String name = fileName.toString();
                    if (name.length() > MIN_BASENAME_LEN && name.length() <= MAX_BASENAME_LEN) {
                        terms.add(name);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[VoiceKeyterms] projectRoot unavailable: {}", e.getMessage());
        }

        // Git branch words
        try {
            String branch = branchSupplier.get();
            if (branch != null && !branch.isEmpty()) {
                for (String word : splitIdentifier(branch)) {
                    terms.add(word);
                }
            }
        } catch (Exception e) {
            log.debug("[VoiceKeyterms] branch unavailable: {}", e.getMessage());
        }

        // Recent file names — 直到达到 MAX_KEYTERMS
        if (recentFiles != null) {
            for (String filePath : recentFiles) {
                if (terms.size() >= MAX_KEYTERMS) break;
                for (String word : fileNameWords(filePath)) {
                    terms.add(word);
                    if (terms.size() >= MAX_KEYTERMS) break;
                }
            }
        }

        // 截断到 MAX_KEYTERMS (保插入顺序)
        java.util.List<String> out = new java.util.ArrayList<>(Math.min(terms.size(), MAX_KEYTERMS));
        int i = 0;
        for (String t : terms) {
            if (i >= MAX_KEYTERMS) break;
            out.add(t);
            i++;
        }
        return out;
    }
}
