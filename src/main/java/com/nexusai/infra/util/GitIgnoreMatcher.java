package com.nexusai.infra.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * gitignore-style 路径匹配器 · 对齐 CC {@code ignore} npm 包（{@code loadSkillsDir.ts:1012}
 * {@code ignore().add(skill.paths)}）。
 *
 * <p><b>用途</b>：{@link com.nexusai.application.agent.skill.DynamicSkillsManager}
 * {@code activateConditionalSkillsForPaths} 用 gitignore 语义匹配 skill frontmatter {@code paths}
 * 与文件相对路径（CC loadSkillsDir.ts:1012-1038）。
 *
 * <p><b>支持的语义</b>（对齐 gitignore / node-ignore 子集，覆盖 skill paths 常见形态）：
 * <ul>
 *   <li><b>basename 任意层级匹配</b>：{@code foo} 匹配任意深度名为 {@code foo} 的文件/目录及其内容</li>
 *   <li><b>{@code /} 锚定</b>：前导 {@code /} 或内含 {@code /} 的 pattern 相对 ignore 根锚定
 *       （{@code /foo} 只匹配根下 foo；{@code src/*.ts} 只匹配 src 直属）</li>
 *   <li><b>trailing {@code /} 目录</b>：{@code docs/} 匹配目录 docs 及其下所有内容</li>
 *   <li><b>{@code !} negation</b>：反选（last-match-wins 时取消忽略）</li>
 *   <li><b>{@code **} 通配</b>：{@code **} 跨目录；{@code **} 后接斜杠匹配零或多个目录</li>
 *   <li><b>last-match-wins</b>：多条 pattern 顺序应用，最后命中的 pattern 决定该候选路径状态</li>
 * </ul>
 *
 * <p><b>祖先目录检查</b>：{@link #ignores(String)} 除测试相对路径本身外，还逐级上溯祖先目录
 * （gitignore 语义：匹配目录即忽略其内容，CC node-ignore 同样做 parent traversal）。
 *
 * <p><b>简化说明</b>：不实现 pattern 尾部空格转义 / 字符类内转义等罕见形态（CC skill paths
 * 不依赖）；negation 反选祖先排除目录时行为对齐 node-ignore（ancestor 逐级 last-match-wins）。
 */
public final class GitIgnoreMatcher {

    private final List<MatchedPattern> patterns;

    /** 构造：编译 pattern 列表（保留顺序 · last-match-wins）。 */
    public GitIgnoreMatcher(List<String> patterns) {
        this.patterns = new ArrayList<>();
        if (patterns != null) {
            for (String p : patterns) {
                MatchedPattern mp = MatchedPattern.compile(p);
                if (mp != null) {
                    this.patterns.add(mp);
                }
            }
        }
    }

    /**
     * 判断相对路径是否被任一 pattern 忽略（含祖先目录）。
     *
     * @param relativePath 相对路径（{@code /} 或 {@code \} 分隔均可；{@code ./} 前缀自动剥离）
     * @return true 当路径或其祖先目录被匹配（空路径 / {@code ..} 开头 / 绝对路径 → false）
     */
    public boolean ignores(String relativePath) {
        String path = normalize(relativePath);
        if (path == null || path.isEmpty()) {
            return false;
        }
        // 逐级上溯祖先目录（CC node-ignore parent traversal · gitignore 目录匹配语义）
        String candidate = path;
        while (candidate != null && !candidate.isEmpty()) {
            if (finalState(candidate)) {
                return true;
            }
            int slash = candidate.lastIndexOf('/');
            candidate = slash >= 0 ? candidate.substring(0, slash) : null;
        }
        return false;
    }

    /** 对单个候选路径应用全部 pattern · last-match-wins。 */
    private boolean finalState(String candidate) {
        boolean ignored = false;
        for (MatchedPattern mp : patterns) {
            if (mp.pattern.matcher(candidate).matches()) {
                ignored = !mp.negated;
            }
        }
        return ignored;
    }

    /** 归一化：反斜杠 → 正斜杠、剥离 {@code ./} 前缀、剥离首尾空白。 */
    static String normalize(String path) {
        if (path == null) {
            return null;
        }
        String s = path.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        return s;
    }

    /**
     * 单条 pattern · CC original: {@code ignore().add(skill.paths)} 内部 pattern（loadSkillsDir.ts:1012）。
     *
     * @param negated 是否 negation（前导 {@code !}）
     * @param pattern 编译后的正则
     */
    record MatchedPattern(boolean negated, Pattern pattern) {

        /** 编译单条 gitignore pattern → 正则；无法解析 → null（跳过该条）。 */
        static MatchedPattern compile(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String p = normalize(raw);
            if (p == null || p.isEmpty()) {
                return null;
            }
            boolean negated = false;
            if (p.startsWith("!")) {
                negated = true;
                p = p.substring(1);
                if (p.isEmpty()) {
                    return null;
                }
            }
            boolean directoryOnly = p.endsWith("/");
            if (directoryOnly) {
                p = p.substring(0, p.length() - 1);
            }
            boolean anchored = p.startsWith("/") || p.indexOf('/') >= 0;
            if (p.startsWith("/")) {
                p = p.substring(1);
            }
            StringBuilder sb = new StringBuilder();
            int i = 0;
            int n = p.length();
            while (i < n) {
                char c = p.charAt(i);
                if (c == '*') {
                    if (i + 1 < n && p.charAt(i + 1) == '*') {
                        if (i + 2 < n && p.charAt(i + 2) == '/') {
                            // **/ → 零或多个目录（CC src/**/*.ts 匹配 src/a.ts 与 src/a/b.ts）
                            sb.append("(?:.*/)?");
                            i += 3;
                        } else {
                            // ** → 跨目录通配
                            sb.append(".*");
                            i += 2;
                        }
                    } else {
                        // * → 单段内通配（不跨 /）
                        sb.append("[^/]*");
                        i++;
                    }
                } else if (c == '?') {
                    sb.append("[^/]");
                    i++;
                } else if (c == '[') {
                    int end = p.indexOf(']', i + 1);
                    if (end < 0) {
                        sb.append("\\[");
                        i++;
                    } else {
                        sb.append(p, i, end + 1);
                        i = end + 1;
                    }
                } else if (c == '.') {
                    sb.append("\\.");
                    i++;
                } else if (c == '/') {
                    sb.append('/');
                    i++;
                } else if ("\\[]{}()+-^$|".indexOf(c) >= 0) {
                    sb.append('\\').append(c);
                    i++;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            StringBuilder out = new StringBuilder();
            if (anchored) {
                out.append('^');
            } else {
                // basename 任意层级：匹配任意前缀（含零）+ 段。注：不能用 (?:^|/) ——
                // Pattern.matches() 全串锚定，无法跳过前导段；(?:.*/)? 才能匹配 a/foo 形态
                out.append("(?:.*/)?");
            }
            out.append('(').append(sb).append(')');
            if (directoryOnly) {
                // dir/ → 匹配目录本身或目录下所有内容
                out.append("(?:/.*)?");
            }
            out.append('$');
            return new MatchedPattern(negated, Pattern.compile(out.toString()));
        }
    }
}
