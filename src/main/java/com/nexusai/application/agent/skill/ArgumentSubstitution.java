package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 参数解析 + 占位符替换 · 镜像 CC argumentSubstitution.ts 单模块
 * （CC original: Open-ClaudeCode/src/utils/argumentSubstitution.ts）
 *
 * <p>该模块被 CC 三方 import（loadSkillsDir.ts:24 / hookHelpers.ts:7 /
 * loadPluginCommands.ts:8），Java 端对应单一公共类 {@code ArgumentSubstitution}，
 * 供 skill 与 hook 两端共用（消除 ExecPromptHook/ExecAgentHook 的私有重复实现——见
 * {@code 05-task-register.csv} P0-4 concern #PLAN-P04-2 follow-up）。
 *
 * <h2>能力（CC 真源行为，逐条 Read + shell-quote@1.8.2 实跑核验）</h2>
 * <ol>
 *   <li>{@link #parseArguments} —— shell-quote 语义 tokenizer（CC :24-40）：
 *       空/空白→[]；引号内空格合并；单引号字面；双引号内 {@code \$/\\/\$VAR} 转义、
 *       {@code $VAR} 保留字面（env {@code key => '$'+key} 不展开）；运算符
 *       {@code | & ; ( ) < >} 与 glob（引号外 {@code * ?}）过滤为非 string token；
 *       坏替换（{@code ${}} / 未闭合 {@code ${}）抛错 → 回退空白分割（CC :31-34）。</li>
 *   <li>{@link #parseArgumentNames} —— frontmatter {@code arguments} 字段解析（CC :50-68）：
 *       数组或空格串；过滤 trim 空与纯数字（防与 {@code $N} 简写冲突）。</li>
 *   <li>{@link #substituteArguments} —— 5 替换 + append（CC :94-145），顺序：
 *       {@code $name}（:111-121）→ {@code $ARGUMENTS[N]}（:124-127）→ {@code $N}（:130-133）
 *       → {@code $ARGUMENTS} 全串（:136）→ 无占位符时追加 {@code ARGUMENTS: }（:140-141）。
 *       null args 原样返回 content（:100-104）；空串 args 是合法输入会替换为空值。</li>
 * </ol>
 *
 * <p><b>已知残余偏差（记录）</b>：
 * <ul>
 *   <li>CC {@code $name} 用 JS {@code String.replace(regexp, string)} 字符串替换，对替换值做
 *       {@code $&/$1/$$} 展开；Java 端用 {@link Matcher#quoteReplacement} 按字面处理（参数值含
 *       {@code $} 时字面保留——意图正确，且 Java 的 {@code $} 展开语法与 JS 不同，盲目展开反而不忠实）。</li>
 *   <li>shell-quote 内部 JS {@code \s} 覆盖更多 Unicode 空白；Java {@code \s} 为 ASCII 子集，
 *       对技能参数（ASCII）无实际差异。</li>
 *   <li>CC {@code parseIndex} 用 JS {@code parseInt}（超大数字串→NaN→空串）；Java 用
 *       {@link Integer#parseInt} 捕获溢出→越界→空串，语义等价。</li>
 * </ul>
 *
 * <p><b>CC 默认值</b>：{@code appendIfNoPlaceholder} CC 技能路径显式 true
 * （loadSkillsDir.ts:351）；hook 路径经 hookHelpers.ts:34 用默认值 true。Java 端唯一调用方
 * 也传 true——未来复用方误传 false 会行为偏移，调用方务必显式传参。
 */
public final class ArgumentSubstitution {

    private static final Logger log = LoggerFactory.getLogger(ArgumentSubstitution.class);

    /**
     * shell-quote CONTROL 运算符集合 · CC original: shell-quote parse.js CONTROL
     * （node_modules/shell-quote/parse.js:9-21，最长匹配在前）。
     */
    private static final String CONTROL_SRC =
        "\\|\\||&&|;;|\\|&|\\(<|<<<|>>|>&|<&|[&;()|<>]";

    /**
     * chunker 正则等价物 · CC original: shell-quote parse.js chunker（parse.js:52-56）。
     * group(1)=control 运算符；group(2)=word-run chunk（BAREWORD 或引号串）。
     */
    private static final Pattern CHUNKER = Pattern.compile(
        "(" + CONTROL_SRC + ")"
        + "|((?:(?:\\\\[\"'|&;()<>\\s]|[^\\s'\"|&;()<>])+"
        + "|\"((?:\\\\.|[^\"])*?)\"|'((?:\\\\.|[^'])*?)')+)");

    /** CC original: parseEnvVar /[*@#?$!_-]/（parse.js:136，特殊变量名单字符）。 */
    private static final Pattern SPECIAL_VAR = Pattern.compile("[*@#?$!_-]");
    /** CC original: parseEnvVar /[^\w\d_]/ 的 Java 等价 [^\w]（parse.js:143）。 */
    private static final Pattern NON_WORD = Pattern.compile("[^\\w]");

    /** CC original: substituteArguments /\\$ARGUMENTS\\[(\\d+)\\]/（argumentSubstitution.ts:124）。 */
    private static final Pattern ARGUMENTS_INDEXED = Pattern.compile("\\$ARGUMENTS\\[(\\d+)\\]");
    /** CC original: substituteArguments /\\$(\\d+)(?!\\w)/（argumentSubstitution.ts:130）。 */
    private static final Pattern SHORT_INDEXED = Pattern.compile("\\$(\\d+)(?!\\w)");

    private ArgumentSubstitution() {
    }

    /**
     * 解析参数字符串为参数数组 · CC original: parseArguments（argumentSubstitution.ts:24-40）。
     *
     * <p>空串/纯空白 → 空数组；shell-quote 解析失败（坏替换 {@code ${}} 等）→ 回退空白分割。
     * 期望值经 shell-quote@1.8.2 实跑 + CC 注释三例（:19-22）双重核验。
     *
     * @param args 原始参数字符串（可为 null/空）
     * @return 参数列表（仅 string token；运算符/glob 被过滤）
     */
    public static List<String> parseArguments(String args) {
        if (args == null || args.trim().isEmpty()) {
            return List.of();
        }
        try {
            return tokenizeShellQuote(args);
        } catch (RuntimeException e) {
            // CC tryParseShellCommand 失败 → 回退空白分割（argumentSubstitution.ts:31-34）
            if (log.isDebugEnabled()) {
                log.debug("[ArgumentSubstitution] parseArguments 解析失败，回退空白分割: {}",
                    e.getMessage());
            }
            List<String> out = new ArrayList<>();
            for (String s : args.split("\\s+")) {
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    /**
     * 解析 frontmatter {@code arguments} 字段为命名参数数组 ·
     * CC original: parseArgumentNames（argumentSubstitution.ts:50-68）。
     *
     * <p>接受数组或空格串；过滤 trim 空与纯数字（纯数字与 {@code $N} 简写冲突，:57-59）。
     *
     * @param value frontmatter 值（List / String / 其它 → 空数组）
     * @return 命名参数列表
     */
    public static List<String> parseArgumentNames(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && isValidName(s)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (value instanceof String str) {
            List<String> out = new ArrayList<>();
            for (String s : str.split("\\s+")) {
                if (isValidName(s)) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }

    /**
     * 生成渐进参数提示（剩余未填参数）· CC original: generateProgressiveArgumentHint
     * （argumentSubstitution.ts:76-83）。
     *
     * <p>CC 真源（✗-1 补齐，用户决策组3「后端补齐」）：
     * <pre>{@code
     * export function generateProgressiveArgumentHint(argNames, typedArgs) {
     *   const remaining = argNames.slice(typedArgs.length)
     *   if (remaining.length === 0) return undefined
     *   return remaining.map(name => `[${name}]`).join(' ')
     * }
     * }</pre>
     * 真实 UI 消费方 useTypeahead.tsx:21/:759（斜杠命令渐进参数提示）。
     *
     * <p>语义：从 {@code argNames} 中截取 {@code typedArgs.length} 之后的剩余参数名，逐名包裹
     * {@code [name]} 后空格 join；无剩余 → 返回 {@code null}（CC {@code undefined}）。
     *
     * @param argNames   frontmatter {@code arguments} 解析出的命名参数（可为 null → 空）
     * @param typedArgs  用户已键入的参数列表（可为 null → 空）
     * @return 形如 {@code "[arg2] [arg3]"} 的提示串；全部已填 → {@code null}
     */
    public static String generateProgressiveArgumentHint(List<String> argNames, List<String> typedArgs) {
        List<String> names = argNames != null ? argNames : List.of();
        int typed = typedArgs != null ? typedArgs.size() : 0;
        // CC argNames.slice(typedArgs.length)：typed 超出 names 长度 → 空剩余
        List<String> remaining = names.subList(Math.min(typed, names.size()), names.size());
        if (remaining.isEmpty()) {
            return null; // CC return undefined
        }
        return remaining.stream().map(name -> "[" + name + "]").collect(Collectors.joining(" "));
    }

    /**
     * 替换占位符 · CC original: substituteArguments（argumentSubstitution.ts:94-145）。
     *
     * <p>顺序严格对齐 CC：{@code $name} → {@code $ARGUMENTS[N]} → {@code $N} →
     * {@code $ARGUMENTS} → 无占位符追加。null args 原样返回 content（:100-104）；
     * 空串 args 是合法输入（占位符替换为空值，:101-102）。
     *
     * @param content              含占位符的内容
     * @param args                 原始参数字符串（可为 null；null 时原样返回）
     * @param appendIfNoPlaceholder true 且无占位符替换且 args 非空 → 追加 {@code \n\nARGUMENTS: }（CC 默认 true）
     * @param argumentNames        命名参数数组（可为 null 等价空数组；null 时 {@code $name} 恒 inert）
     * @return 替换后的内容
     */
    public static String substituteArguments(String content, String args,
                                             boolean appendIfNoPlaceholder, List<String> argumentNames) {
        if (content == null) {
            return null;
        }
        if (args == null) {
            return content;
        }
        List<String> parsedArgs = parseArguments(args);
        List<String> names = argumentNames != null ? argumentNames : List.of();
        String originalContent = content;

        // ① $name 命名替换（最先执行）· CC :111-121 —— name 未转义（镜照 CC 原样），
        //   lookahead (?![\[\w]) 不匹配 $name[...] 与 $nameXxx
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name == null || name.isEmpty()) {
                continue;
            }
            String value = i < parsedArgs.size() ? parsedArgs.get(i) : "";
            content = content.replaceAll("\\$" + name + "(?![\\[\\w])",
                Matcher.quoteReplacement(value));
        }

        // ② $ARGUMENTS[N] 索引替换 · CC :124-127
        content = replaceIndexed(content, ARGUMENTS_INDEXED, parsedArgs);

        // ③ $N 简写替换 · CC :130-133
        content = replaceIndexed(content, SHORT_INDEXED, parsedArgs);

        // ④ $ARGUMENTS 全串替换（字面全量替换，最后执行）· CC :136
        content = content.replace("$ARGUMENTS", args);

        // ⑤ 无占位符且 appendIfNoPlaceholder 且 args 非空 → 追加 · CC :140-141
        if (content.equals(originalContent) && appendIfNoPlaceholder && !args.isEmpty()) {
            content = content + "\n\nARGUMENTS: " + args;
        }

        if (log.isDebugEnabled()) {
            log.debug("[ArgumentSubstitution] substituteArguments: argsLen={} 替换前长度={} 替换后长度={} "
                    + "(CC argumentSubstitution.ts:94-145)",
                args.length(), originalContent.length(), content.length());
        }
        return content;
    }

    /**
     * 索引替换共用 · 镜像 CC {@code replace(regex, (_, indexStr) => parsedArgs[index] ?? '')}
     * 的 function-replacement（返回值字面插入，无 {@code $} 展开）。
     */
    private static String replaceIndexed(String content, Pattern pattern, List<String> parsedArgs) {
        Matcher m = pattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int idx = parseIndex(m.group(1));
            String val = idx >= 0 && idx < parsedArgs.size() ? parsedArgs.get(idx) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解析索引 · 镜像 JS parseInt（超大数字串→NaN→空串）；Java 捕获溢出→越界→空串。
     */
    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isValidName(String name) {
        return !name.trim().isEmpty() && !name.matches("\\d+");
    }

    /**
     * shell-quote tokenizer 等价物 · 逐条镜像 shell-quote parse.js parseInternal
     * （chunker 匹配 → 每 chunk 扫描）。返回仅 string token（运算符/glob/注释被过滤）。
     */
    private static List<String> tokenizeShellQuote(String s) {
        List<String> tokens = new ArrayList<>();
        Matcher m = CHUNKER.matcher(s);
        while (m.find()) {
            if (m.group(1) != null) {
                // control 运算符 → 非 string token，parseArguments 过滤（CC :37-39）
                continue;
            }
            ScanResult r = scanChunk(m.group(2));
            if (r.text != null) {
                tokens.add(r.text);
            }
            if (r.commented) {
                // '#' 注释：后续 chunk 全部丢弃（shell-quote commented 标志）
                break;
            }
        }
        return tokens;
    }

    /**
     * 单个 chunk 扫描 · 镜像 shell-quote parse.js parseInternal 的 per-chunk for-loop
     * （:89-184）：单引号字面 / 双引号转义+$VAR 保留 / 反斜杠转义 / glob 标记 / 注释。
     */
    private static ScanResult scanChunk(String chunk) {
        int n = chunk.length();
        boolean quote = false;
        char quoteChar = 0;
        boolean esc = false;
        boolean isGlob = false;
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < n; i++) {
            char c = chunk.charAt(i);
            // shell-quote: isGlob = isGlob || (!quote && (c === '*' || c === '?'))
            isGlob = isGlob || (!quote && (c == '*' || c == '?'));
            if (esc) {
                out.append(c);
                esc = false;
            } else if (quote) {
                if (c == quoteChar) {
                    quote = false;
                } else if (quoteChar == '\'') {
                    // 单引号内全字面（shell-quote: out += c）
                    out.append(c);
                } else {
                    // 双引号内：\ 转义 (" \ $) / $VAR 保留字面（env key=>'$'+key）
                    if (c == '\\') {
                        i++;
                        if (i < n) {
                            char nc = chunk.charAt(i);
                            if (nc == '"' || nc == '\\' || nc == '$') {
                                out.append(nc);
                            } else {
                                out.append('\\').append(nc);
                            }
                        } else {
                            // 尾部孤立反斜杠：shell-quote out += BS + '' = '\'
                            out.append('\\');
                        }
                    } else if (c == '$') {
                        i = parseEnvVar(chunk, i, out);
                    } else {
                        out.append(c);
                    }
                }
            } else if (c == '"' || c == '\'') {
                quote = true;
                quoteChar = c;
            } else if (isControlChar(c)) {
                // 整 chunk 视作 operator 对象 → 过滤（shell-quote: return { op: s }）
                return new ScanResult(null, false);
            } else if (c == '#') {
                // 注释：返回 '#' 前已累积文本，后续全部丢弃
                return new ScanResult(out.length() == 0 ? null : out.toString(), true);
            } else if (c == '\\') {
                esc = true;
            } else if (c == '$') {
                i = parseEnvVar(chunk, i, out);
            } else {
                out.append(c);
            }
        }

        if (isGlob) {
            // glob（引号外 * ?）→ 对象 token → 过滤（shell-quote: { op:'glob', pattern }）
            return new ScanResult(null, false);
        }
        // 空串 token（如 '' ）合法保留（shell-quote: typeof '' === 'string'）
        return new ScanResult(out.toString(), false);
    }

    /**
     * 环境变量解析 · 镜像 shell-quote parse.js parseEnvVar（:120-164）。
     * env 恒为 {@code key => '$'+key} → 变量以字面 {@code $name} 保留。
     * 坏替换（{@code ${}} / 未闭合 {@code ${}）抛 {@link IllegalArgumentException}。
     *
     * @param s    chunk 字符串
     * @param i    当前索引（指向 {@code $}）
     * @param out  输出缓冲
     * @return 消费后的新索引
     */
    private static int parseEnvVar(String s, int i, StringBuilder out) {
        i += 1; // 消费 '$'
        if (i >= s.length()) {
            // 尾部孤立 '$'：shell-quote varname='' → env('') → '$'
            out.append('$');
            return s.length();
        }
        char c = s.charAt(i);
        if (c == '{') {
            i += 1;
            if (i < s.length() && s.charAt(i) == '}') {
                throw new IllegalArgumentException("Bad substitution: ${}");
            }
            int varend = s.indexOf('}', i);
            if (varend < 0) {
                throw new IllegalArgumentException("Bad substitution: " + s.substring(i));
            }
            String varname = s.substring(i, varend);
            out.append('$').append(varname);
            // 返回 '}' 位置；调用方 for 循环 i++ 后落在 '}' 之后第一个字符
            // （镜像 shell-quote: i = varend; 循环 i++ → varend+1）
            return varend;
        }
        if (SPECIAL_VAR.matcher(String.valueOf(c)).matches()) {
            out.append('$').append(c);
            // i 指向特殊字符；调用方循环 i++ 后落在其后第二个字符（镜像 shell-quote: i += 1; 循环 i++）
            return i + 1;
        }
        String rest = s.substring(i);
        Matcher m = NON_WORD.matcher(rest);
        if (!m.find()) {
            out.append('$').append(rest);
            return s.length();
        }
        String varname = rest.substring(0, m.start());
        out.append('$').append(varname);
        // 返回分隔符前一位；调用方循环 i++ 后落在分隔符上
        // （镜像 shell-quote: i += varend.index - 1; 循环 i++ → 分隔符位置）
        return i + m.start() - 1;
    }

    private static boolean isControlChar(char c) {
        return c == '&' || c == ';' || c == '(' || c == ')' || c == '|' || c == '<' || c == '>';
    }

    /** chunk 扫描结果：text 为 null 表示该 chunk 生成非 string token（过滤）；commented 表示遇 '#'。 */
    private record ScanResult(String text, boolean commented) {
    }
}
