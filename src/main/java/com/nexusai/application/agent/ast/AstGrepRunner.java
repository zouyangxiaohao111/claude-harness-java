package com.nexusai.application.agent.ast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ast-grep 参数组装 + JSONL 解析 + 排序分页 · 两个工具（AstGrep/AstEdit）的唯一执行内核。
 *
 * <h2>参数管线（顺序固定，改动需同步 AstGrepRunnerTest）</h2>
 * <pre>
 * run -p &lt;pattern&gt; --json=stream --color never --inspect summary --no-ignore hidden
 *     --globs !&#42;&#42;/.git ... （6 个 VCS 目录排除，与 GrepTool.VCS_DIRS 同集合）
 *     [-l &lt;lang&gt;] [-r &lt;rewrite&gt;] &lt;targets...&gt;
 * </pre>
 * <ul>
 *   <li>{@code --json=stream} 必须带 {@code =}（ast-grep 的 clap 定义是 {@code --json[=STYLE]}）。</li>
 *   <li>{@code --inspect summary} 把「扫描文件数」写到 stderr（{@code scannedFileCount=N}），
 *       用于区分「真的没有匹配」与「文件类型不支持/全被忽略」。</li>
 *   <li>{@code --no-ignore hidden} 才会进入隐藏目录；6 条 {@code --globs} 排除 VCS 元数据目录
 *       （与 {@code GrepTool.VCS_DIRS} 同一集合，避免扫 .svn/.git 内部）。</li>
 *   <li>⛔ 不用 {@code --update-all}：写盘由 {@link AstEditPlan} 按 UTF-8 字节区间自行合并执行，
 *       保证多 op 对「同一份原文」一次性改写；也不传 {@code glob} 参数（作用域只由 path 表达）。</li>
 * </ul>
 *
 * <h2>退出码语义</h2>
 * <ul>
 *   <li>0 = 有匹配；1 = 无匹配（<b>不是错误</b>，返回空页）。</li>
 *   <li>其余 = 失败（消息来自 stderr，单行化后 ≤500 字符）。</li>
 *   <li>本端因 stdout 超上限 kill 掉进程时（{@link AstGrepCli.Result#outputTruncated()}）忽略退出码，
 *       按「有匹配但被截断」处理（fail-closed：调用方会因截断标记拒绝写盘）。</li>
 * </ul>
 *
 * <h2>顺序</h2>
 * 结果按 {@code (规范化绝对路径, startByte, endByte)} 升序 —— 路径先 {@code Path.toAbsolutePath()
 * .normalize()} 再比较，避免 Windows 混合分隔符（{@code \} / {@code /}）导致同目录文件排序抖动。
 */
public final class AstGrepRunner {

    private static final Logger log = LoggerFactory.getLogger(AstGrepRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** stderr 里的扫描文件数（{@code --inspect summary} 输出）。 */
    private static final Pattern SCANNED = Pattern.compile("scannedFileCount=(\\d+)");

    /** summary 行前缀（不算 warnings）。 */
    private static final String SUMMARY_PREFIX = "sg: summary|";

    /** warnings 最多保留条数（工具面再截到 5 条）。 */
    private static final int MAX_WARNINGS = 20;

    /** 与 GrepTool.VCS_DIRS 同一集合（--globs 排除，防止扫版本库元数据）。 */
    private static final List<String> VCS_GLOBS = List.of(
        "!**/.git", "!**/.svn", "!**/.hg", "!**/.bzr", "!**/.jj", "!**/.sl");

    /** 结果排序键（路径规范化后比较）。 */
    static final Comparator<AstMatch> ORDER = Comparator
        .comparing((AstMatch m) -> normalizePath(m.file()))
        .thenComparingInt(AstMatch::startByte)
        .thenComparingInt(AstMatch::endByte);

    private final AstGrepCli cli;

    public AstGrepRunner() {
        this(new ProcessAstGrepCli());
    }

    public AstGrepRunner(AstGrepCli cli) {
        if (cli == null) {
            throw new IllegalArgumentException("cli is null");
        }
        this.cli = cli;
    }

    /**
     * 一次搜索请求。
     *
     * @param pattern    AST 模式（原样传给 CLI）
     * @param targets    目标（绝对路径，文件或目录）
     * @param lang       语言覆盖（null/空白 = 按扩展名逐文件推断）
     * @param workdir    子进程 cwd（sgconfig.yml 发现基准）
     * @param timeoutMs  本次调用超时
     */
    public record Request(String pattern, List<String> targets, String lang, Path workdir, long timeoutMs) {}

    /**
     * 分页结果。
     *
     * @param matches         本页匹配（已排序）
     * @param totalMatches    全部匹配数（不受 skip/limit 影响）
     * @param limitReached    true = 还有更多匹配未展示
     * @param filesSearched   CLI 报告的扫描文件数（解析失败 = 0）
     * @param warnings        stderr 非 summary 行（去重保序，≤20）
     * @param outputTruncated true = CLI 输出超上限被截断（结果不完整）
     */
    public record Page(List<AstMatch> matches, int totalMatches, boolean limitReached,
                       int filesSearched, List<String> warnings, boolean outputTruncated) {}

    /**
     * 全量结果（AstEdit 用）。
     *
     * @param matches         已解析匹配（按 {@link #ORDER} 排序；{@code exceededCap} 时只到上限附近）
     * @param totalMatches    已解析条数
     * @param exceededCap     true = 超过调用方 cap 或 CLI 输出被截断（调用方必须拒绝继续）
     * @param filesSearched   扫描文件数
     * @param warnings        stderr 非 summary 行
     * @param outputTruncated true = CLI stdout 超上限
     */
    public record All(List<AstMatch> matches, int totalMatches, boolean exceededCap,
                      int filesSearched, List<String> warnings, boolean outputTruncated) {}

    /** 运行时失败 · {@link Kind} 决定工具层文案（消息不含 {@code Error: } 前缀，由工具层统一加）。 */
    public static final class AstGrepException extends RuntimeException {

        /** 失败类别。 */
        public enum Kind { ABORTED, TIMEOUT, SPAWN, FAILED }

        private final Kind kind;

        public AstGrepException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    /**
     * 分页搜索。
     *
     * @param req   请求
     * @param skip  跳过前 N 条（≥0）
     * @param limit 本页条数（≥1）
     * @param abort 取消信号（null → NOOP）
     * @return 本页结果
     */
    public Page find(Request req, int skip, int limit, AbortController abort) {
        AstGrepCli.Result res = call(req, null, abort);
        List<String> warnings = collectWarnings(res.stderrLines());
        int cap = skip + limit;
        PriorityQueue<AstMatch> keep = new PriorityQueue<>(Math.max(1, cap), ORDER.reversed());
        int total = 0;
        int dirty = 0;
        for (String line : res.stdoutLines()) {
            AstMatch m = parseLine(line, req);
            if (m == null) {
                if (!line.isBlank()) {
                    dirty++;
                }
                continue;
            }
            total++;
            if (keep.size() < cap) {
                keep.add(m);
            } else if (ORDER.compare(m, keep.peek()) < 0) {
                keep.poll();
                keep.add(m);
            }
        }
        if (dirty > 0) {
            warnings = appendWarning(warnings, "skipped " + dirty + " unparsable output line(s)");
        }
        List<AstMatch> retained = new ArrayList<>(keep);
        retained.sort(ORDER);
        int from = Math.min(skip, retained.size());
        int to = Math.min(skip + limit, retained.size());
        List<AstMatch> page = new ArrayList<>(retained.subList(from, to));
        boolean limitReached = total > cap || res.outputTruncated();
        int filesSearched = filesSearched(res.stderrLines());
        if (log.isDebugEnabled()) {
            log.debug("AstGrepRunner: find pattern='{}' total={} page={} scanned={} truncated={}",
                req.pattern(), total, page.size(), filesSearched, res.outputTruncated());
        }
        return new Page(page, total, limitReached, filesSearched, warnings, res.outputTruncated());
    }

    /**
     * 全量搜索（用于 codemod 合并）· 累计超过 {@code cap} 即停止解析。
     *
     * @param req     请求
     * @param cap     匹配数上限（超过 → {@code exceededCap=true}）
     * @param rewrite 替换模板（null = 不生成 {@code replacement}）
     * @param abort   取消信号
     * @return 全量结果
     */
    public All findAll(Request req, int cap, String rewrite, AbortController abort) {
        AstGrepCli.Result res = call(req, rewrite, abort);
        List<String> warnings = collectWarnings(res.stderrLines());
        List<AstMatch> matches = new ArrayList<>();
        boolean exceeded = res.outputTruncated();
        int dirty = 0;
        for (String line : res.stdoutLines()) {
            if (matches.size() >= cap) {
                exceeded = true;
                break;
            }
            AstMatch m = parseLine(line, req);
            if (m == null) {
                if (!line.isBlank()) {
                    dirty++;
                }
                continue;
            }
            matches.add(m);
        }
        if (dirty > 0) {
            warnings = appendWarning(warnings, "skipped " + dirty + " unparsable output line(s)");
        }
        matches.sort(ORDER);
        int filesSearched = filesSearched(res.stderrLines());
        if (log.isDebugEnabled()) {
            log.debug("AstGrepRunner: findAll pattern='{}' matches={} exceeded={} scanned={} truncated={}",
                req.pattern(), matches.size(), exceeded, filesSearched, res.outputTruncated());
        }
        return new All(List.copyOf(matches), matches.size(), exceeded, filesSearched, warnings,
            res.outputTruncated());
    }

    // ───────────────────────── CLI 调用与参数 ─────────────────────────

    private AstGrepCli.Result call(Request req, String rewrite, AbortController abort) {
        List<String> args = args(req, rewrite);
        try {
            AstGrepCli.Result res = cli.run(args, req.workdir(), abort, req.timeoutMs());
            if (res.outputTruncated() || res.exitCode() == 0 || res.exitCode() == 1) {
                return res;
            }
            throw new AstGrepException(AstGrepException.Kind.FAILED,
                "ast-grep failed: " + singleLine(res.stderrLines(), 500));
        } catch (AstGrepCli.AstCliException e) {
            throw switch (e.kind()) {
                case ABORTED -> new AstGrepException(AstGrepException.Kind.ABORTED, e.getMessage());
                case TIMEOUT -> new AstGrepException(AstGrepException.Kind.TIMEOUT, e.getMessage());
                case SPAWN -> new AstGrepException(AstGrepException.Kind.SPAWN,
                    "ast-grep failed: " + e.getMessage());
            };
        }
    }

    /** 参数组装（顺序即契约 · 见类注释）。 */
    static List<String> args(Request req, String rewrite) {
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("-p");
        args.add(req.pattern());
        args.add("--json=stream");
        args.add("--color");
        args.add("never");
        args.add("--inspect");
        args.add("summary");
        args.add("--no-ignore");
        args.add("hidden");
        for (String glob : VCS_GLOBS) {
            args.add("--globs");
            args.add(glob);
        }
        String lang = req.lang();
        if (lang != null && !lang.isBlank()) {
            args.add("-l");
            args.add(lang.trim());
        }
        if (rewrite != null) {
            args.add("-r");
            args.add(rewrite);
        }
        args.addAll(req.targets());
        return args;
    }

    // ───────────────────────── JSONL 解析 ─────────────────────────

    /** 单行 JSON → {@link AstMatch}；脏行返回 null（计 warning，不整体失败）。 */
    static AstMatch parseLine(String line, Request req) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(line);
            if (node == null || !node.isObject() || !node.hasNonNull("text")) {
                return null;
            }
            JsonNode range = node.path("range");
            JsonNode start = range.path("start");
            JsonNode end = range.path("end");
            JsonNode byteOffset = range.path("byteOffset");
            String file = node.path("file").asText(null);
            if (file == null || file.isEmpty()) {
                return null;
            }
            Path p = Path.of(file);
            if (!p.isAbsolute() && req.workdir() != null) {
                p = req.workdir().resolve(p);
            }
            Map<String, String> meta = new LinkedHashMap<>();
            JsonNode single = node.path("metaVariables").path("single");
            if (single.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = single.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    meta.put(e.getKey(), e.getValue().path("text").asText(""));
                }
            }
            String replacement = node.has("replacement") ? node.get("replacement").asText("") : null;
            return new AstMatch(
                p.toString(),
                node.get("text").asText(""),
                node.path("language").asText(""),
                start.path("line").asInt(0) + 1,
                start.path("column").asInt(0) + 1,
                end.path("line").asInt(0) + 1,
                end.path("column").asInt(0) + 1,
                byteOffset.path("start").asInt(0),
                byteOffset.path("end").asInt(0),
                meta,
                replacement);
        } catch (Exception e) {
            return null;
        }
    }

    /** stderr 里的扫描文件数（多个 summary 取最后一个；缺失 = 0）。 */
    static int filesSearched(List<String> stderrLines) {
        int count = 0;
        for (String line : stderrLines) {
            Matcher m = SCANNED.matcher(line);
            while (m.find()) {
                try {
                    count = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    // 超大数字当未报告处理
                }
            }
        }
        return count;
    }

    /** stderr 非 summary 行 → warnings（去重保序，≤20）。 */
    static List<String> collectWarnings(List<String> stderrLines) {
        List<String> warnings = new ArrayList<>();
        if (stderrLines == null) {
            return warnings;
        }
        for (String line : stderrLines) {
            if (line == null || line.isBlank() || line.startsWith(SUMMARY_PREFIX)) {
                continue;
            }
            String w = line.trim();
            if (!warnings.contains(w)) {
                warnings.add(w);
            }
            if (warnings.size() >= MAX_WARNINGS) {
                break;
            }
        }
        return warnings;
    }

    private static List<String> appendWarning(List<String> warnings, String warning) {
        List<String> out = new ArrayList<>(warnings);
        if (!out.contains(warning)) {
            out.add(warning);
        }
        return out;
    }

    /** 多行 stderr → 单行（供错误文案），最多 max 字符。 */
    static String singleLine(List<String> lines, int max) {
        String joined = String.join(" ", lines == null ? List.of() : lines).trim().replaceAll("\\s+", " ");
        return joined.length() <= max ? joined : joined.substring(0, max);
    }

    /** 规范化路径（排序键用；解析失败原样返回）。 */
    static String normalizePath(String file) {
        if (file == null) {
            return "";
        }
        try {
            return Path.of(file).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return file;
        }
    }
}
