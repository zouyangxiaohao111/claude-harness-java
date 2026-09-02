package com.nexusai.application.agent.context;

import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * claudemd 内部块级 Markdown lexer（自实现 · 对齐 CC marked {@code Lexer} gfm:false 行为子集）。
 *
 * <p>Java 无 marked/yaml/glob 库（规则三：CC 复杂则 Java 同步复杂），这里实现 CC 用到的三个能力：
 * <ol>
 *   <li><b>stripHtmlComments</b>（claudemd.ts:292-334）：仅剥离<b>块级</b> HTML 注释
 *       {@code <!-- ... -->}；行内 code span / fenced code block 内的注释保留；段落内
 *       行内 HTML 注释不动。未闭合注释（无 {@code -->}）保留。</li>
 *   <li><b>extractIncludePaths</b>（claudemd.ts:451-535）：从预 lex 的 token 中提取
 *       {@code @path} 引用并解析为绝对路径；跳过 code/codespan；html 注释 token 只检查
 *       residue；text 节点递归。</li>
 *   <li><b>parseFrontmatter</b>（frontmatterParser.ts:123-175）：提取 {@code ---} frontmatter
 *       的 {@code paths} 字段（逗号分割尊重 brace + brace 展开）。C-15 收敛（OPD-CM5-C-15）：
 *       委托共享 {@link ParseSkillFrontmatter}（对齐 CC 单一 frontmatterParser.ts），删除本类
 *       私有 parseSimpleYaml/expandBraces 复制。</li>
 * </ol>
 *
 * <p><b>为什么自实现而非现成库</b>：CC 用 marked gfm:false + ignore（gitignore glob）+ picomatch。
 * Java 生态无语义等价的轻量库 —— 按 CC 行为自实现子集，保证加载序/@include/条件规则不漂移。
 */
final class ClaudemdLexer {

    private static final Logger log = LoggerFactory.getLogger(ClaudemdLexer.class);

    /** 块类型 · 对齐 marked Lexer token type 相关子集。 */
    enum BlockType { TEXT, CODE, HTML, HTML_COMMENT }

    /** 块级 token。raw = 原始文本。 */
    record Block(BlockType type, String raw) {}

    /** stripHtmlComments 结果 · 对齐 CC claudemd.ts:292-301 {@code {content, stripped}}。 */
    record StripResult(String content, boolean stripped) {}

    /** parseFrontmatter 结果 · 对齐 CC frontmatterParser.ts:61-64 {@code {frontmatter, content}}。 */
    record FrontmatterResult(java.util.Map<String, Object> frontmatter, String content) {}

    private ClaudemdLexer() {
    }

    // ════════════════════════════════════════════════════════════════
    // lex · 行级块扫描（marked gfm:false 块行为子集）
    // ════════════════════════════════════════════════════════════════

    /**
     * 把 markdown 切分为块级 token · 对齐 marked {@code Lexer} gfm:false 的块分类子集。
     *
     * <p>行级规则（CommonMark 子集）：
     * <ul>
     *   <li>fenced code block（{@code ```} / {@code ~~~}）→ CODE（跳过 @include 提取）</li>
     *   <li>4+ 空格缩进段 → CODE（marked 视为 indented code）</li>
     *   <li>{@code <!--} 开头的行 → HTML_COMMENT（CC strip 只处理这种；未闭合则保留）</li>
     *   <li>其他 {@code <...} 开头的行 → HTML（extract 跳过；strip 原样保留）</li>
     *   <li>其余 → TEXT（相邻行合并为一段）</li>
     * </ul>
     *
     * <p>注：marked 在 lex 时会把 CRLF normalize 为 LF（claudemd.ts:369-370 注释）——
     * 本实现<b>不</b>normalize，只在确定需要 strip 时才 lex（与 CC "只有存在注释才
     * 经 token 重建，避免 CRLF 文件误翻转 contentDiffersFromDisk" 语义一致）。
     */
    static List<Block> lex(String content) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = content.split("\\n", -1);
        int i = 0;
        while (i < lines.length) {
            // 尾部空段（content 以 \n 结尾时 split 产生的 "" 元素）不产生幻影 TEXT 块
            // （marked 对文件末尾空行不产 token；否则 stripHtmlComments 重建内容多一个 \n）
            if (i == lines.length - 1 && lines[i].isEmpty()) {
                break;
            }
            String line = lines[i];
            String trimmedStart = leadingTrim(lines[i]);
            // fenced code block
            if (isFence(trimmedStart)) {
                String fence = trimmedStart.substring(0, 3);
                StringBuilder code = new StringBuilder(lines[i]).append("\n");
                i++;
                boolean closed = false;
                while (i < lines.length) {
                    String l = lines[i];
                    code.append(l).append("\n");
                    if (l.trim().startsWith(fence)) {
                        closed = true;
                        i++;
                        break;
                    }
                    i++;
                }
                blocks.add(new Block(BlockType.CODE, code.toString()));
                if (i >= lines.length) {
                    break;
                }
                continue;
            }
            // indented code block: 4+ leading spaces/tab (non-empty)
            if (isIndentedCodeLine(line)) {
                StringBuilder code = new StringBuilder(lines[i]).append("\n");
                i++;
                while (i < lines.length && isIndentedCodeLine(lines[i])) {
                    code.append(lines[i]).append("\n");
                    i++;
                }
                blocks.add(new Block(BlockType.CODE, code.toString()));
                continue;
            }
            // HTML comment block
            if (trimmedStart.startsWith("<!--")) {
                StringBuilder html = new StringBuilder(lines[i]).append("\n");
                boolean closed = trimmedStart.contains("-->");
                i++;
                while (i < lines.length && !closed) {
                    html.append(lines[i]).append("\n");
                    if (lines[i].contains("-->")) {
                        closed = true;
                    }
                    i++;
                }
                blocks.add(new Block(BlockType.HTML_COMMENT, html.toString()));
                continue;
            }
            // other HTML block
            if (trimmedStart.startsWith("<")) {
                StringBuilder html = new StringBuilder(lines[i]).append("\n");
                i++;
                while (i < lines.length) {
                    String l = lines[i];
                    String lt = l.trim();
                    // CommonMark type-6/7 HTML 块持续到空行（CLD-05①）—— 块内独立
                    // `<!-- note -->` 行属 html token 内容，不剥离（CC marked gfm:false 同；
                    // 旧实现遇 isSpecialBlockStart（含 `<!--`）断开 → 注释被误剥）。
                    if (lt.isEmpty()) {
                        break;
                    }
                    html.append(l).append("\n");
                    i++;
                }
                blocks.add(new Block(BlockType.HTML, html.toString()));
                continue;
            }
            // text run
            StringBuilder text = new StringBuilder(lines[i]).append("\n");
            i++;
            while (i < lines.length) {
                String l = lines[i];
                String lt = leadingTrim(l);
                if (lt.isEmpty()
                        || isFence(lt)
                        || isIndentedCodeLine(l)
                        || lt.startsWith("<!--")
                        || lt.startsWith("<")
                        || lt.startsWith("---")) {
                    break;
                }
                text.append(l).append("\n");
                i++;
            }
            blocks.add(new Block(BlockType.TEXT, text.toString()));
        }
        return blocks;
    }

    private static String leadingTrim(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(i);
    }

    private static boolean isFence(String trimmed) {
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private static boolean isIndentedCodeLine(String line) {
        if (line.isEmpty()) {
            return false;
        }
        int spaces = 0;
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            spaces++;
            i++;
        }
        if (i < line.length() && line.charAt(i) == '\t') {
            return true;
        }
        return spaces >= 4;
    }

    private static boolean isSpecialBlockStart(String trimmed) {
        return isFence(trimmed) || trimmed.startsWith("<!--") || trimmed.startsWith("---");
    }

    // ════════════════════════════════════════════════════════════════
    // stripHtmlComments · claudemd.ts:292-334
    // ════════════════════════════════════════════════════════════════

    /**
     * 剥离块级 HTML 注释 · CC original: {@code stripHtmlComments}（claudemd.ts:292-334）。
     *
     * <p>若内容不含 {@code <!--} → 原样返回 {@code {content, stripped:false}}（不触发
     * lex，避免 CRLF 文件被 normalize —— claudemd.ts:296-301）。含注释时经块 lex 重建，
     * 仅剥离 HTML_COMMENT 块中的注释 span，保留 span 外 residue（如
     * {@code <!-- note --> Use bun} → {@code  Use bun}）。未闭合注释（无 {@code -->}）
     * 整块保留（CC 注释："a typo doesn't silently swallow the rest of the file"）。
     */
    static StripResult stripHtmlComments(String content) {
        if (content == null || !content.contains("<!--")) {
            return new StripResult(content, false);
        }
        List<Block> blocks = lex(content);
        StringBuilder result = new StringBuilder(content.length());
        boolean stripped = false;
        for (Block b : blocks) {
            if (b.type() == BlockType.HTML_COMMENT) {
                String residue = stripCommentSpans(b.raw());
                stripped = true;
                if (residue.trim().length() > 0) {
                    // CC claudemd.ts:323-326: 存在 residue（如 "<!-- note --> Use bun"）→ 保留
                    result.append(residue);
                }
            } else {
                result.append(b.raw());
            }
        }
        return new StripResult(result.toString(), stripped);
    }

    /** 剥离注释 span（{@code <!-- ... -->} 非贪婪跨行）并返回 residue · CC claudemd.ts:312-321。 */
    static String stripCommentSpans(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            int start = raw.indexOf("<!--", i);
            if (start < 0) {
                out.append(raw, i, raw.length());
                break;
            }
            int end = raw.indexOf("-->", start + 4);
            if (end < 0) {
                // 未闭合 → 保留剩余（CC 注释：typo 不静默吞掉剩余文件）
                out.append(raw, i, raw.length());
                break;
            }
            out.append(raw, i, start);
            i = end + 3;
        }
        return out.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // extractIncludePaths · claudemd.ts:451-535
    // ════════════════════════════════════════════════════════════════

    /**
     * 从内容中提取 {@code @path} include 引用并解析为绝对路径 · CC original:
     * {@code extractIncludePathsFromTokens}（claudemd.ts:451-535）。
     *
     * <p>语义（逐字对齐 CC）：
     * <ul>
     *   <li>仅 leaf text 节点（code block / codespan 内忽略）</li>
     *   <li>{@code @path} / {@code @./path} / {@code @~/path} / {@code @/path} 均接受</li>
     *   <li>strip fragment（{@code #heading}）后未空才接受</li>
     *   <li>反斜杠转义空格（{@code \ }）unescape</li>
     *   <li>html 注释 token：strip comment span 后检查 residue</li>
     *   <li>相对路径相对 {@code dirname(basePath)} 解析；{@code ~} 相对 user.home</li>
     * </ul>
     *
     * @param content  markdown 内容（可含注释；本方法先 strip 注释 span 再扫描）
     * @param basePath 包含文件路径（@include 解析基准目录 = 其 dirname）
     * @return 去重后的绝对路径列表（LinkedHashSet 保序）
     */
    static List<String> extractIncludePaths(String content, String basePath) {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        List<Block> blocks = lex(content);
        for (Block b : blocks) {
            switch (b.type()) {
                case CODE -> { /* 跳过 */ }
                case HTML_COMMENT -> {
                    String residue = stripCommentSpans(b.raw());
                    if (residue.trim().length() > 0) {
                        extractPathsFromText(residue, basePath, paths);
                    }
                }
                case HTML -> { /* 非注释 html token 整体跳过（CC claudemd.ts:503-514） */ }
                case TEXT -> {
                    // REQ-14（OPD-CM3-19/D05）：段落内联注释（TEXT 块内 <!-- ... -->）先剥注释
                    //   span 再扫描 @path，对齐 CC 内联 html token 仅查 residue（claudemd.ts:503-514）。
                    //   Java 块级 lexer 把段落内行内注释保留在 TEXT 块内，marked 则将其拆为独立
                    //   html inline token（probe 实测 `text <!-- @/secret/path --> more` → text/html/text
                    //   三 token，comment token 仅查 residue=空 → 注释内 @path 忽略）。否则注释内
                    //   @path 被误提取进上下文（REQ-14，ClaudemdEngineTest 段内联注释用例锁定）。
                    extractPathsFromText(stripCommentSpans(b.raw()), basePath, paths);
                }
                default -> { }
            }
        }
        return new ArrayList<>(paths);
    }

    /**
     * 从文本扫描 {@code @path} · CC {@code extractPathsFromText}（claudemd.ts:458-491）。
     * 手写扫描避免 regex 转义依赖：{@code @} 前必须是行首或空白；读取到空白或行尾；
     * 反斜杠视为下一字符转义（{@code \ } → 空格）。
     */
    private static void extractPathsFromText(String text, String basePath, java.util.Set<String> out) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '`') {
                // 跳过 codespan（CC claudemd.ts:496-497 跳过 code/codespan）：marked 内联
                // code 规则 `\1` backreference —— opener 为连续反引号 run，closing 必须为
                // **同长** run；单反引号/多反引号 span 均跳过。旧实现「找下一个 ` 」单对配对：
                // 三反引号 opener 的第二个 ` 被当作 close → span 内容被扫描，其中 @path
                // （如 ```js @/secret```）被误提取（△-2，OPD-CM5-F-08）。span 内容里其他
                // 长度的反引号 run 属代码内容，继续向后找同长 closer。
                int runLen = 1;
                while (i + runLen < n && text.charAt(i + runLen) == '`') {
                    runLen++;
                }
                int closeRunEnd = -1;
                int searchFrom = i + runLen;
                while (searchFrom < n) {
                    int bt = text.indexOf('`', searchFrom);
                    if (bt < 0) {
                        break;
                    }
                    int len = 1;
                    while (bt + len < n && text.charAt(bt + len) == '`') {
                        len++;
                    }
                    if (len == runLen) {
                        closeRunEnd = bt + len;
                        break;
                    }
                    searchFrom = bt + len;
                }
                if (closeRunEnd < 0) {
                    // CLD-05②：未配对反引号 → marked 行内解析容忍（不构成 codespan），
                    // 按普通字符继续扫描后续 @path（旧实现 break 停止整段 → 丢失后续 include）
                    i++;
                    continue;
                }
                i = closeRunEnd;
                continue;
            }
            boolean atStartOrSpace = (i == 0) || Character.isWhitespace(text.charAt(i - 1));
            if (c == '@' && atStartOrSpace) {
                StringBuilder cand = new StringBuilder();
                int j = i + 1;
                while (j < n) {
                    char cc = text.charAt(j);
                    if (Character.isWhitespace(cc)) {
                        break;
                    }
                    if (cc == '\\' && j + 1 < n) {
                        // 转义下一字符（如 \ 空格）
                        cand.append(text.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    cand.append(cc);
                    j++;
                }
                String rawPath = cand.toString();
                if (!rawPath.isEmpty()) {
                    resolveIncludePath(rawPath, basePath, out);
                }
                i = j;
                continue;
            }
            i++;
        }
    }

    /**
     * 校验并解析单个 include 路径 · CC claudemd.ts:465-489。fragment strip +
     * 有效性校验（./ ~/ / 或字母数字开头）+ {@code expandPath} 解析。
     */
    private static void resolveIncludePath(String rawPath, String basePath, java.util.Set<String> out) {
        String path = rawPath;
        int hashIndex = path.indexOf('#');
        if (hashIndex != -1) {
            path = path.substring(0, hashIndex);
        }
        if (path.isEmpty()) {
            return;
        }
        // 有效性校验（CC claudemd.ts:477-484 · JS /^[a-zA-Z0-9._-]/ 是前缀匹配，非全串
        // matches —— Java String.matches 双端锚定会误拒 "b.md" 这类多字符路径，需手写前缀判定）
        boolean valid = path.startsWith("./")
                || path.startsWith("~/")
                || (path.startsWith("/") && !path.equals("/"))
                || (!path.startsWith("@")
                    && !startsWithSpecial(path)
                    && startsWithPathChar(path));
        if (!valid) {
            return;
        }
        String resolved = expandPath(path, basePath);
        if (resolved != null) {
            out.add(resolved);
        }
    }

    /** CC claudemd.ts:482-483 {@code /^[#%^&*()]+/} 前缀判定：首字符是否特殊符号集。 */
    private static boolean startsWithSpecial(String p) {
        return !p.isEmpty() && "#%^&*()".indexOf(p.charAt(0)) >= 0;
    }

    /** CC claudemd.ts:483 {@code /^[a-zA-Z0-9._-]/} 前缀判定：首字符字母数字或 . _ -。 */
    private static boolean startsWithPathChar(String p) {
        if (p.isEmpty()) {
            return false;
        }
        char c = p.charAt(0);
        return Character.isLetterOrDigit(c) || "._-".indexOf(c) >= 0;
    }

    /** Windows 平台判定 · CC getPlatform() === 'windows'（path.ts:69）。 */
    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    /**
     * 相对 base 目录解析 + ~ 展开 · CC {@code expandPath}（utils/path.ts:32-85）。
     * {@code ~} → user.home；Windows 上 {@code /c/...} MinGW 形态 → native（CLD-04）；
     * 相对路径 → {@code dirname(basePath)} resolve + normalize。
     */
    static String expandPath(String path, String basePath) {
        String p = path;
        if (p.equals("~") || p.startsWith("~/")) {
            String home = System.getProperty("user.home", "");
            p = p.equals("~") ? home : java.nio.file.Paths.get(home, p.substring(2)).toString();
            return java.nio.file.Paths.get(p).normalize().toString();
        }
        // CLD-04（CC path.ts:67-76）：Windows 上 POSIX 形态 `/c/...` → posixPathToWindowsPath
        // 转 native（否则 `@/c/Users/x` 解析为 `\c\Users\x` —— 当前盘根相对，≠ CC `C:\Users\x`）
        if (IS_WINDOWS && isMinGwPosix(p)) {
            p = posixPathToWindowsPath(p);
        }
        java.nio.file.Path pp;
        try {
            pp = java.nio.file.Paths.get(p);
        } catch (Exception e) {
            return null;
        }
        if (pp.isAbsolute()) {
            return pp.normalize().toString();
        }
        if (basePath == null) {
            return pp.normalize().toString();
        }
        try {
            return java.nio.file.Paths.get(basePath).getParent().resolve(pp).normalize().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** CC path.ts:69 {@code /^\/[a-z]\//i} 守卫：`/c/...` 形态（3 字符起：/ + 字母 + /）。 */
    private static boolean isMinGwPosix(String p) {
        return p.length() >= 3
            && p.charAt(0) == '/'
            && Character.isLetter(p.charAt(1))
            && p.charAt(2) == '/';
    }

    /**
     * POSIX → Windows 路径转换 · CC original: {@code posixPathToWindowsPath}
     * （utils/windowsPaths.ts:148-173）—— UNC `//server/share` → `\\server\share`；
     * `/cygdrive/c/...` → `C:\...`；`/c/...` → `C:\...`（MSYS2/Git Bash）；其余翻斜杠。
     */
    private static String posixPathToWindowsPath(String posix) {
        if (posix.startsWith("//")) {
            return posix.replace('/', '\\');
        }
        if (posix.startsWith("/cygdrive/") && posix.length() >= 11) {
            // "/cygdrive/" 10 字符，盘符在 index 10（CC windowsPaths.ts:155-160）
            char drive = posix.charAt(10);
            if (Character.isLetter(drive) && (posix.length() == 11 || posix.charAt(11) == '/')) {
                String rest = posix.substring(11);
                return Character.toUpperCase(drive) + ":"
                    + (rest.isEmpty() ? "\\" : rest.replace('/', '\\'));
            }
        }
        if (posix.length() >= 2 && Character.isLetter(posix.charAt(1))
            && (posix.length() == 2 || posix.charAt(2) == '/')) {
            String rest = posix.substring(2);
            return Character.toUpperCase(posix.charAt(1)) + ":"
                + (rest.isEmpty() ? "\\" : rest.replace('/', '\\'));
        }
        return posix.replace('/', '\\');
    }

    // ════════════════════════════════════════════════════════════════
    // parseFrontmatter / splitPathInFrontmatter · frontmatterParser.ts:123-266
    // ════════════════════════════════════════════════════════════════

    /**
     * 解析 frontmatter · CC original: {@code parseFrontmatter}（frontmatterParser.ts:123-175）。
     *
     * <p>C-15 收敛（OPD-CM5-C-15）：委托共享 {@link ParseSkillFrontmatter}（对齐 CC 单一
     * frontmatterParser.ts），删除本类私有 parseSimpleYaml/expandBraces 复制。语义对照：
     * <ul>
     *   <li>开闭符：共享类 FRONTMATTER_REGEX {@code ^---\s*\n([\s\S]*?)---\s*\n?} —— 开符须
     *       {@code ---} + 仅空白 + 必选换行；闭符 = 首个后续 {@code ---}（`--- ` / `---\t` /
     *       `---foo` / `----` 均闭合；行中 `---` 同样闭合，逐字节对齐 CC）。</li>
     *   <li>值类型：旧 parseSimpleYaml 手写子集把多行列表（{@code paths:\n  - a.md}）拼为逗号串
     *       String；共享类用真实 YAML（Jackson YAMLMapper，CC Bun.YAML 等价）→ YAML List。
     *       消费方 {@code ClaudemdEngine.parseFrontmatterPaths} 对 String|List 双型处理，
     *       最终 globs 一致（CLD-03 语义保持）。</li>
     *   <li>content：CC frontmatterParser.ts:145 {@code markdown.slice(match[0].length)}（不 trim）；
     *       闭符后空白被 {@code \s*\n?} 贪婪消费，与旧"手动跳过全部空白"对常见输入等价。</li>
     * </ul>
     */
    static FrontmatterResult parseFrontmatter(String markdown) {
        ParseSkillFrontmatter.ParsedMarkdown pm = ParseSkillFrontmatter.parseFrontmatterStatic(markdown, null);
        return new FrontmatterResult(pm.frontmatter(), pm.content());
    }

    /**
     * 拆分 frontmatter paths · CC original: {@code splitPathInFrontmatter}
     * （frontmatterParser.ts:189-266）。C-15 收敛（OPD-CM5-C-15）：委托共享
     * {@link ParseSkillFrontmatter}（逗号分割尊重 brace + brace 展开，语义一致）。
     */
    static List<String> splitPathInFrontmatter(String input) {
        return ParseSkillFrontmatter.splitPathInFrontmatter(input);
    }

    /**
     * Object 重载 · CC splitPathInFrontmatter 接受 {@code string | string[]}
     * （frontmatterParser.ts:189-192）。C-15 收敛：委托共享 {@link ParseSkillFrontmatter}。
     */
    static List<String> splitPathInFrontmatter(Object input) {
        return ParseSkillFrontmatter.splitPathInFrontmatter(input);
    }
}
