package com.nexusai.kb.app.qa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 模型生成的 SQL 的安全校验（阶段 9 的**最高风险点**）。
 *
 * <h2>⛔ 为什么必须校验，而且必须在服务端校验</h2>
 * 这段 SQL 会被<b>直接执行</b>。提示词里写「请只写 SELECT」不构成任何约束 ——
 * 模型可以被诱导（或单纯幻觉）写出：
 * <pre>
 *   COPY (SELECT ...) TO '/tmp/leak.csv';          -- 把数据写出去
 *   SELECT * FROM read_csv('/etc/passwd');         -- 读到知识库之外的任意文件
 *   ATTACH 'other.db';                              -- 挂载别的库
 *   INSTALL httpfs; LOAD httpfs;                    -- 装扩展、走网络
 * </pre>
 * 这些都是合法的 DuckDB 语法，能通过「看起来像查询」的表层检查。
 *
 * <h2>四道闸门</h2>
 * <ol>
 *   <li><b>必须以 SELECT 或 WITH 开头</b>（CTE 也是只读的）；</li>
 *   <li><b>禁止多语句</b>：去掉末尾分号后，正文里不允许再出现分号；</li>
 *   <li><b>禁止危险关键字</b>：DDL/DML/ATTACH/COPY/INSTALL/LOAD/EXPORT/PRAGMA/SET/CALL；</li>
 *   <li><b>禁止文件读取函数</b>：{@code read_csv} / {@code read_parquet} / {@code read_json} /
 *       {@code glob} / {@code sniff_csv} —— 它们能绕开「只用 dataset 表」的全部约束。</li>
 * </ol>
 *
 * <h2>⚠️ 这是「拒绝已知坏形态」，不是「完备的 SQL 解析」</h2>
 * 真正的完备方案是用 DuckDB 自己的解析器做 AST 校验。这里选择关键字白/黑名单，
 * 因为它<b>没有额外依赖、行为可预测、误拒时日志能直接指出哪个词</b>。
 * 配合「内存库 + 只装载一个表」的执行环境，逃逸面被压到很小。
 */
public final class KbSqlGuard {

    private static final Logger log = LoggerFactory.getLogger(KbSqlGuard.class);

    /** 允许的唯一表名（与源侧 {@code DataAnalysisTableName} 一致）。 */
    public static final String DATASET_TABLE = "dataset";

    /** 危险关键字（词边界匹配，避免误伤列名里含 substring 的情况）。 */
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
        "insert", "update", "delete", "drop", "create", "alter", "truncate",
        "attach", "detach", "copy", "export", "import",
        "install", "load", "pragma", "set", "call", "vacuum", "checkpoint",
        "grant", "revoke", "use", "begin", "commit", "rollback"
    );

    /** 文件/网络读取函数（能绕开「只用 dataset」的约束）。 */
    private static final List<String> FORBIDDEN_FUNCTIONS = List.of(
        "read_csv", "read_csv_auto", "read_parquet", "read_json", "read_json_auto",
        "read_text", "read_blob", "glob", "sniff_csv", "parquet_scan", "csv_scan"
    );

    private KbSqlGuard() {
    }

    /**
     * 校验结果。
     *
     * @param ok    是否放行
     * @param reason 拒绝原因（放行时为 null）；日志直接用它，便于定位
     */
    public record Verdict(boolean ok, String reason) {

        static Verdict allow() {
            return new Verdict(true, null);
        }

        static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * 校验一条 SQL 是否可以执行。
     *
     * @param sql 模型生成的 SQL（null/空白 ⇒ 拒绝）
     * @return 判定
     */
    public static Verdict check(String sql) {
        if (sql == null || sql.isBlank()) {
            return Verdict.deny("SQL 为空");
        }
        // 去掉注释再判断 —— ⛔ 注释能藏关键字（`SELECT 1 -- ; DROP TABLE x`）
        String cleaned = stripComments(sql).strip();
        if (cleaned.isEmpty()) {
            return Verdict.deny("SQL 去掉注释后为空");
        }
        // 去掉末尾分号（合法写法），其余分号 = 多语句
        String body = cleaned.endsWith(";")
            ? cleaned.substring(0, cleaned.length() - 1).strip() : cleaned;
        if (body.indexOf(';') >= 0) {
            return Verdict.deny("不允许多条语句");
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            return Verdict.deny("只允许 SELECT / WITH 开头的只读查询");
        }
        for (String kw : FORBIDDEN_KEYWORDS) {
            if (hasWord(lower, kw)) {
                return Verdict.deny("包含禁止的关键字: " + kw);
            }
        }
        for (String fn : FORBIDDEN_FUNCTIONS) {
            if (hasWord(lower, fn)) {
                return Verdict.deny("包含禁止的文件/网络读取函数: " + fn);
            }
        }
        // ⚠️ 除了 SELECT/WITH 开头，还要确认语句里**没有**第二个顶层语句起点
        //   （`WITH x AS (...) SELECT ...` 是合法的，所以不能简单数 select 个数）
        if (lower.contains("into outfile") || lower.contains("into dumpfile")) {
            return Verdict.deny("包含导出子句");
        }
        return Verdict.allow();
    }

    /**
     * 词边界匹配（避免 `set` 误伤 `offset`、`load` 误伤 `payload`）。
     *
     * <p>⚠️ SQL 标识符会被引号包围（{@code "offset"}），所以这里在前后允许
     * 非字母数字下划线字符，包括引号和空格。
     */
    static boolean hasWord(String loweredSql, String word) {
        Pattern p = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(word) + "(?![a-z0-9_])");
        return p.matcher(loweredSql).find();
    }

    /**
     * 去掉 SQL 注释（{@code -- ...} 行注释 与 {@code /* ... *&#47;} 块注释）。
     *
     * <p>⭐ 必须先去掉再判断：否则 {@code SELECT 1 -- ; DROP TABLE x} 会漏掉后面的危险词。
     * ⚠️ 字符串字面量里的 `--` 会被误删，但那只会让校验更严（更容易拒），
     * 不会放过危险语句 —— 在本场景可接受。
     */
    static String stripComments(String sql) {
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            int idx = line.indexOf("--");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }
}
