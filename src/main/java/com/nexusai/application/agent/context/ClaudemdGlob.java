package com.nexusai.application.agent.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * gitignore 风格 glob 匹配器 · 对齐 CC {@code ignore} 库（claudemd.ts:29 import ignore from
 * 'ignore'）在 {@code processConditionedMdRules} 中的 {@code ignore().add(globs).ignores(path)}
 * 语义（claudemd.ts:1395）。
 *
 * <p><b>为什么自实现</b>：Java 无 gitignore 语义库（{@code FileSystem.getPathMatcher("glob:")}
 * 与 gitignore 的 {@code **} 跨段匹配/无斜杠模式任意深度匹配语义不同）。本项目依赖仅
 * Maven central 可用的轻量库 —— 按 {@code ignore} 库核心语义自实现。
 *
 * <p>支持的 pattern 语义（gitignore 规范子集）：
 * <ul>
 *   <li>{@code *} 匹配单段内任意字符（不跨 /）</li>
 *   <li>{@code **} 匹配任意深度（可跨 /）</li>
 *   <li>{@code ?} 匹配单个字符</li>
 *   <li>{@code [abc]} / {@code [a-z]} 字符类</li>
 *   <li>leading {@code /} 锚定到基目录（其余 pattern 匹配任意深度）</li>
 *   <li>trailing {@code /} 仅匹配目录（条件规则场景忽略，因目标是文件路径）</li>
 *   <li>最后一条匹配 pattern 生效（negation 用 {@code !} 前缀）</li>
 * </ul>
 *
 * <p>简化（偏离 CC ignore 库的边界，记 concerns）：无 basename/negation 文件集扩展等
 * ignore 库完整特性 —— 仅实现条件规则实际用到的子集；父目录忽略传播与通配转义已由
 * CLD-05③ 对齐（见下）。中段 {@code a**b} 保持 {@code .*} 跨段语义（npm ignore
 * globstar 任意位置），与计划目标 {@code [^/]*} 的差异已登记待 owner 裁决（见进度文件）。
 *
 * <p><b>CLD-05③ 对齐增量</b>（OPD-R2-CLD-05③，探查 v4.0 复验三缺口）：
 * <ul>
 *   <li>字符类否定 {@code [!a]} → Java regex {@code [^a]}（gitignore 语义）</li>
 *   <li>通配转义 {@code \*}/{\@code \?} → 字面字符（gitignore 规范）</li>
 *   <li>父目录忽略传播：目录被忽略 → 其下全部忽略（含 trailing {@code /} 目录模式匹配祖先目录）</li>
 * </ul>
 * 中段 {@code a**b} 按 {@code .*}（跨段）保持 —— npm ignore globstar 任意位置语义，既有
 * 测试（{@code src/**.java} 匹配 {@code src/main/App.java}）编码此行为，v4.0 复验未列差异。
 */
final class ClaudemdGlob {

    private static final Logger log = LoggerFactory.getLogger(ClaudemdGlob.class);

    private final List<Entry> entries = new ArrayList<>();

    private record Entry(boolean negate, boolean anchored, boolean dirOnly, String regex) {}

    private ClaudemdGlob() {
    }

    /** 空匹配器（空 patterns → 永不 ignore）· CC ignore().add([]) 等价。 */
    static ClaudemdGlob empty() {
        return new ClaudemdGlob();
    }

    /** 添加 glob patterns · CC {@code ignore().add(patterns)}。 */
    ClaudemdGlob add(List<String> patterns) {
        if (patterns == null) {
            return this;
        }
        for (String p : patterns) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            boolean negate = p.startsWith("!");
            String body = negate ? p.substring(1) : p;
            boolean anchored = body.startsWith("/");
            if (anchored) {
                body = body.substring(1);
            }
            // trailing / → 目录模式（gitignore：仅匹配目录；对文件路径按「祖先目录匹配」判定）
            boolean dirOnly = body.endsWith("/");
            if (dirOnly) {
                body = body.substring(0, body.length() - 1);
            }
            entries.add(new Entry(negate, anchored, dirOnly, globToRegex(body)));
        }
        return this;
    }

    /**
     * 路径是否被忽略 · CC {@code ignore().ignores(path)}。
     * 空 entries → false（CC ignore() 对空集合 ignores 返回 false）。逐条匹配，最后命中者生效。
     */
    boolean ignores(String relativePath) {
        if (entries.isEmpty() || relativePath == null) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        // 去掉 ./ 前缀（glob 通常以 base 相对段开始）
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        boolean ignored = false;
        for (Entry e : entries) {
            if (matchesPathOrAncestor(e, normalized)) {
                ignored = !e.negate();
            }
        }
        return ignored;
    }

    /**
     * 路径或任一祖先目录命中判定 · CLD-05③ 父目录忽略传播（gitignore：目录被忽略 → 其下
     * 全部忽略；npm ignore 对 file path 同样逐祖先检查）。目录模式（trailing /）仅经祖先
     * 目录匹配（文件路径本身不是目录）。
     */
    private static boolean matchesPathOrAncestor(Entry e, String normalizedPath) {
        if (!e.dirOnly() && matches(e, normalizedPath)) {
            return true;
        }
        int idx = normalizedPath.length();
        while (true) {
            idx = normalizedPath.lastIndexOf('/', idx - 1);
            if (idx <= 0) {
                break;
            }
            if (matches(e, normalizedPath.substring(0, idx))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Entry e, String normalizedPath) {
        String target = e.anchored() ? normalizedPath : basenameOnly(normalizedPath);
        // anchored pattern 只匹配完整相对路径；非 anchored 允许匹配任意深度（gitignore 规则）
        // 无斜杠 pattern 匹配任意层 basename；含斜杠 pattern 匹配完整相对路径任意段起点。
        if (e.anchored()) {
            return target.matches(e.regex());
        }
        // 非锚定：整个路径（** 已在 regex 中表达跨段）或任一 basename
        if (normalizedPath.matches(e.regex())) {
            return true;
        }
        // gitignore 无斜杠 pattern 匹配任意层级的 basename
        if (!e.regex().contains("/")) {
            String base = basenameOnly(normalizedPath);
            return base.matches(e.regex());
        }
        // 含斜杠非锚定 pattern：从任一目录起点匹配
        for (int i = 0; i < normalizedPath.length(); i++) {
            if (normalizedPath.charAt(i) == '/') {
                String segment = normalizedPath.substring(i + 1);
                if (segment.matches(e.regex())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String basenameOnly(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx < 0 ? path : path.substring(idx + 1);
    }

    /** glob → regex · 对齐 ignore 库的 glob 转义语义子集（CLD-05③ 补否定类/转义）。 */
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '\\' -> {
                    // 通配转义（gitignore）：\x → 字面 x（CLD-05③）
                    if (i + 1 < glob.length()) {
                        sb.append(java.util.regex.Pattern.quote(String.valueOf(glob.charAt(i + 1))));
                        i += 2;
                    } else {
                        sb.append("\\\\");
                        i++;
                    }
                }
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        // ** : 跨段任意（含零段）· npm ignore globstar 任意位置语义（含中段 a**b）
                        sb.append(".*");
                        i += 2;
                        // 吞掉后续 /
                        if (i < glob.length() && glob.charAt(i) == '/') {
                            i++;
                        }
                    } else {
                        sb.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> {
                    sb.append("[^/]");
                    i++;
                }
                case '[' -> {
                    int close = glob.indexOf(']', i + 1);
                    if (close < 0) {
                        sb.append("\\[");
                        i++;
                    } else {
                        // 字符类：gitignore 用 [!...] 表示否定（CLD-05③）；Java regex 用 [^...]
                        String cls = glob.substring(i + 1, close);
                        if (cls.startsWith("!")) {
                            sb.append("[^").append(cls.substring(1)).append("]");
                        } else {
                            sb.append('[').append(cls).append(']');
                        }
                        i = close + 1;
                    }
                }
                case '.', '(', ')', '+', '|', '^', '$', '{', '}' -> {
                    sb.append('\\').append(c);
                    i++;
                }
                default -> {
                    sb.append(c);
                    i++;
                }
            }
        }
        return sb.toString();
    }
}
