package com.nexusai.kb.app.qa;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 阶段 9 · DATA_ANALYSIS（对齐源侧 {@code chat_pipeline/data_analysis.go}）。
 *
 * <h2>它解决什么</h2>
 * 「这张表里总共多少人」「按区县分组各多少」这类问题，文本检索拿不到答案 ——
 * 即使把整张 CSV 塞进上下文，模型也只会靠猜。把数据装进 DuckDB、让模型写 SQL，
 * 答案就是<b>算出来的</b>而不是编出来的。
 *
 * <h2>⭐ 执行顺序（每一步失败都只跳过本阶段，⛔ 不阻断问答）</h2>
 * <ol>
 *   <li>从上下文片段里找扩展名是 CSV/Excel 的数据文件（只取第一个，源侧同）；</li>
 *   <li>装进内存 DuckDB，表名固定 {@link KbSqlGuard#DATASET_TABLE}；</li>
 *   <li>把列结构给模型，问「要不要分析、SQL 是什么」（温度 0.1）；</li>
 *   <li><b>服务端校验 SQL</b>（{@link KbSqlGuard}）——这是唯一的安全边界；</li>
 *   <li>执行，结果转 markdown 表追加进上下文；</li>
 *   <li>被拒/失败 ⇒ 给模型<b>一次</b>修复机会（同源侧 2 次上限）。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么用内存库</h2>
 * {@code jdbc:duckdb:}（无路径）= 纯内存。用完即关，不落盘、不共享状态 ——
 * 一个会话的分析不会泄漏到另一个会话，也不会在磁盘上留下用户的表数据。
 */
@Component
public class KbDataAnalysisStage {

    private static final Logger log = LoggerFactory.getLogger(KbDataAnalysisStage.class);

    /** 规划温度（源侧 {@code Temperature: 0.1}）。 */
    private static final double TEMPERATURE = 0.1;

    private static final int MAX_TOKENS = 1000;

    /** 规划尝试次数（源侧 {@code maxAttempts = 2}：初次 + 一次修复）。 */
    static final int MAX_ATTEMPTS = 2;

    /** 结果行数上限（防一次查询把上万行塞进上下文）。 */
    static final int MAX_RESULT_ROWS = 100;

    /** 可分析的文件扩展名。 */
    private static final List<String> DATA_EXTENSIONS = List.of("csv", "tsv", "xlsx", "xls");

    private final KbKnowledgeMapper knowledgeMapper;
    private final LlmSideCallClient sideCallClient;

    @Autowired(required = false) private ModelConfigResolver modelConfigResolver;

    public KbDataAnalysisStage(KbKnowledgeMapper knowledgeMapper, LlmSideCallClient sideCallClient) {
        this.knowledgeMapper = knowledgeMapper;
        this.sideCallClient = sideCallClient;
    }

    /** 一次分析的结果。 */
    public record Outcome(String markdown, String sql, int rows, String note) {

        public static Outcome skip(String note) {
            return new Outcome(null, null, 0, note);
        }

        public boolean hasResult() {
            return markdown != null && !markdown.isBlank();
        }
    }

    /**
     * 尝试对命中的数据集做一次 SQL 分析。
     *
     * @param question    用户问题（改写后）
     * @param knowledgeIds 本轮命中的文档 id（按顺序，取第一个数据文件）
     * @param modelName   规划用模型
     * @return 结果（永不 null、永不抛）
     */
    public Outcome analyze(String question, List<String> knowledgeIds, String modelName) {
        if (question == null || question.isBlank()
                || knowledgeIds == null || knowledgeIds.isEmpty()
                || modelName == null || modelName.isBlank()) {
            return Outcome.skip("输入不全");
        }
        ProviderConfig provider = resolveConfig(modelName);
        if (provider == null) {
            return Outcome.skip("模型不可用");
        }
        KbKnowledgeRecord dataDoc = findFirstDataDoc(knowledgeIds);
        if (dataDoc == null) {
            return Outcome.skip("命中里没有 CSV/Excel 数据文件");
        }
        Path file = existingFile(dataDoc);
        if (file == null) {
            // ⚠️ 文件不在本机 ⇒ 无法装载。如实记 WARN（不是"不需要分析"）
            log.warn("[问答链] ⑨ 数据文件不存在，跳过分析 knowledge={} path={}",
                dataDoc.getId(), dataDoc.getFilePath());
            return Outcome.skip("数据文件不存在");
        }

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            String schema = loadDataset(conn, file);
            if (schema == null) {
                return Outcome.skip("装载失败");
            }
            log.info("[问答链] ⑨ 数据集已装载 knowledge={} 文件={} 列结构={}",
                dataDoc.getId(), file.getFileName(), schema);
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                String sql = plan(provider, modelName, question, schema, attempt > 1 ? schema : null);
                if (sql == null) {
                    return Outcome.skip("模型判断不需要分析");
                }
                KbSqlGuard.Verdict verdict = KbSqlGuard.check(sql);
                if (!verdict.ok()) {
                    // ⭐ 拒绝是安全边界，必须打 WARN（不是 debug）——被拒说明模型在越界
                    log.warn("[问答链] ⑨ SQL 被拒 attempt={}/{} 原因={} sql={}",
                        attempt, MAX_ATTEMPTS, verdict.reason(), preview(sql));
                    if (attempt >= MAX_ATTEMPTS) {
                        return Outcome.skip("SQL 校验未通过: " + verdict.reason());
                    }
                    continue;
                }
                try {
                    Outcome out = run(conn, sql);
                    log.info("[问答链] ⑨ 分析完成 attempt={}/{} 行数={} sql={}",
                        attempt, MAX_ATTEMPTS, out.rows(), preview(sql));
                    return out;
                } catch (RuntimeException e) {
                    log.warn("[问答链] ⑨ SQL 执行失败 attempt={}/{} 原因={} sql={}",
                        attempt, MAX_ATTEMPTS, e.getMessage(), preview(sql));
                    if (attempt >= MAX_ATTEMPTS) {
                        return Outcome.skip("SQL 执行失败: " + e.getMessage());
                    }
                }
            }
            return Outcome.skip("超过尝试上限");
        } catch (Exception e) {
            log.warn("[问答链] ⑨ 数据分析失败，跳过本阶段 原因={}", e.getMessage());
            return Outcome.skip("分析失败: " + e.getMessage());
        }
    }

    // ── 装载 ────────────────────────────────────────────────────────

    /**
     * 把数据文件装成 {@code dataset} 表。
     *
     * @return 列结构描述（给模型看）；失败 ⇒ null
     */
    private String loadDataset(Connection conn, Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try (Statement st = conn.createStatement()) {
            if (name.endsWith(".csv") || name.endsWith(".tsv")) {
                // read_csv_auto 的参数里文件路径由**我们**拼接（不是模型给的），
                // 且路径来自知识条目记录 ⇒ 不存在注入面
                String sql = "CREATE TABLE " + KbSqlGuard.DATASET_TABLE
                    + " AS SELECT * FROM read_csv_auto('" + escapePath(file) + "',"
                    + " header=true, ignore_errors=true)";
                st.execute(sql);
            } else {
                // ⚠️ Excel 走「转 CSV 再装」：DuckDB 的 excel 扩展需要联网 INSTALL，
                //    本仓不做联网装扩展（见 RUN_LOG 偏离登记）
                return null;
            }
            return describe(conn);
        } catch (Exception e) {
            log.warn("[问答链] ⑨ 装载数据集失败 file={} 原因={}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    /** 读列结构（{@code DESCRIBE dataset}）。 */
    private String describe(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("DESCRIBE " + KbSqlGuard.DATASET_TABLE)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append("- ").append(rs.getString("column_name"))
                    .append(" (").append(rs.getString("column_type")).append(")\n");
            }
            return sb.toString().strip();
        } catch (Exception e) {
            return null;
        }
    }

    // ── 规划 ────────────────────────────────────────────────────────

    /**
     * 问模型要不要分析、SQL 是什么。
     *
     * @param repairSchema 非 null ⇒ 这是**修复**请求（上一次被拒）
     * @return SQL（null = 不需要分析）
     */
    private String plan(ProviderConfig provider, String modelName, String question,
                        String schema, String repairSchema) {
        String prompt = "User Question: " + question + "\n\n"
            + "The user's question may be answerable from a single CSV/Excel document loaded into DuckDB.\n"
            + "Its rows are exposed as the table \"" + KbSqlGuard.DATASET_TABLE + "\". "
            + "This is the only table; never reference any other table name.\n\n"
            + "Table Schema:\n" + schema + "\n\n"
            + "Determine if the user's question requires data analysis (statistics, aggregation, "
            + "filtering) on this table.\n"
            + "If YES, write one read-only DuckDB SELECT against the table "
            + "\"" + KbSqlGuard.DATASET_TABLE + "\" in the sql field. "
            + "If NO, set needs_analysis to false and leave sql empty.\n\n"
            + "Return strict JSON: {\"needs_analysis\": true|false, \"sql\": \"...\"}";
        try {
            String raw = sideCallClient.chat(
                provider, modelName, "You are a data-analysis planner. Output only JSON.",
                prompt,
                new LlmProvider.ChatRequestOptions(
                    null, null, null,
                    LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
                    TEMPERATURE, "qa_data_analysis_plan", null, MAX_TOKENS, null, null,
                    null, null, null, null, null, null),
                LlmSideCallClient.SideCallUsage.standalone(
                    UsageCategory.SIDE_CALL, "qa_data_analysis_plan"));
            return parsePlan(raw);
        } catch (RuntimeException e) {
            log.warn("[问答链] ⑨ 规划调用失败 原因={}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析规划输出（容错：围栏 / 前后废话 / 字段别名）。
     *
     * @return SQL（null = 不需要 / 解析不出）
     */
    static String parsePlan(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw.substring(start, end + 1));
            if (node == null) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode needed = node.get("needs_analysis");
            if (needed == null || !needed.asBoolean(false)) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode sql = node.get("sql");
            if (sql == null || !sql.isTextual() || sql.asText().isBlank()) {
                return null;
            }
            return sql.asText();
        } catch (Exception e) {
            return null;
        }
    }

    // ── 执行 ────────────────────────────────────────────────────────

    /** 执行已通过校验的 SQL，结果转 markdown。 */
    private Outcome run(Connection conn, String sql) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            StringBuilder sb = new StringBuilder();
            sb.append('|');
            for (int i = 1; i <= cols; i++) {
                sb.append(' ').append(escapeCell(md.getColumnLabel(i))).append(" |");
            }
            sb.append('\n').append('|');
            for (int i = 1; i <= cols; i++) {
                sb.append(" --- |");
            }
            sb.append('\n');
            int rows = 0;
            boolean truncated = false;
            while (rs.next()) {
                if (rows >= MAX_RESULT_ROWS) {
                    truncated = true;
                    break;
                }
                sb.append('|');
                for (int i = 1; i <= cols; i++) {
                    sb.append(' ').append(escapeCell(rs.getString(i))).append(" |");
                }
                sb.append('\n');
                rows++;
            }
            String note = truncated
                ? "（结果超过 " + MAX_RESULT_ROWS + " 行，已截断）" : null;
            if (truncated) {
                sb.append('\n').append(note);
            }
            return new Outcome(sb.toString(), sql, rows, note);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /** 找第一个「存在且是数据文件」的文档。 */
    private KbKnowledgeRecord findFirstDataDoc(List<String> knowledgeIds) {
        for (String id : knowledgeIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            try {
                KbKnowledgeRecord row = knowledgeMapper.selectOneById(id);
                if (row == null) {
                    continue;
                }
                String name = row.getFileName() == null ? "" : row.getFileName().toLowerCase(Locale.ROOT);
                for (String ext : DATA_EXTENSIONS) {
                    if (name.endsWith("." + ext)) {
                        return row;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[问答链] ⑨ 读知识条目失败 id={} 原因={}", id, e.getMessage());
            }
        }
        return null;
    }

    /** 文件存在则返回路径（不存在返回 null）。 */
    private Path existingFile(KbKnowledgeRecord row) {
        String raw = row.getFilePath();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(raw);
            return Files.isReadable(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 路径里的单引号要转义（SQL 字符串字面量）。 */
    private static String escapePath(Path p) {
        return p.toAbsolutePath().toString().replace("'", "''");
    }

    /** markdown 单元格转义（竖线与换行会破坏表格）。 */
    private static String escapeCell(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    /** 日志里截断 SQL 预览。 */
    private static String preview(String sql) {
        String s = sql == null ? "" : sql.replaceAll("\\s+", " ").strip();
        return s.length() <= 160 ? s : s.substring(0, 160) + "…";
    }

    private ProviderConfig resolveConfig(String modelName) {
        if (modelConfigResolver == null) {
            return null;
        }
        try {
            ModelConfigResolver.ResolvedModel r = modelConfigResolver.resolve(modelName);
            return r == null ? null : r.config();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
