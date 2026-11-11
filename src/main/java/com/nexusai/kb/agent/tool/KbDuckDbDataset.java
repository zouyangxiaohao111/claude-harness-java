package com.nexusai.kb.agent.tool;

import com.nexusai.kb.app.qa.KbSqlGuard;
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
import java.util.List;
import java.util.Locale;

/**
 * DuckDB 数据文件装载与查询 · 供 {@code data_schema} / {@code data_analysis} 两个工具复用。
 *
 * <h2>⭐⭐ 为什么要抽出来，而不是直接调 {@code KbDataAnalysisStage}</h2>
 * 两者<b>层次不同</b>：
 * <ul>
 *   <li>{@link com.nexusai.kb.app.qa.KbDataAnalysisStage} 是 RAG 流水线的一个阶段 ——
 *       它自己调模型「判断要不要分析 + 写 SQL」。输入是自然语言问题。</li>
 *   <li>{@code data_analysis} 工具 —— 输入是<b>模型已经写好的 SQL</b>，
 *       只负责装表、校验、执行、格式化。</li>
 * </ul>
 * 所以工具层需要的正是 Stage 里那几个 private 方法（装载 / DESCRIBE / 执行），
 * 而不是整个 {@code analyze()}。把它们抽到这里，两边共用同一套装载与执行逻辑。
 *
 * <h2>⚠️ 安全边界只有一处</h2>
 * SQL 校验<b>必须</b>走 {@link KbSqlGuard#check(String)} ——
 * 那是唯一的安全边界。本类<b>不</b>自己做 SQL 校验，避免出现第二套规则。
 *
 * <h2>⚠️ 为什么用内存库</h2>
 * {@code jdbc:duckdb:}（无路径）= 纯内存。用完即关，不落盘、不共享状态。
 */
@Component
public class KbDuckDbDataset {

    private static final Logger log = LoggerFactory.getLogger(KbDuckDbDataset.class);

    /** 可分析的文件扩展名。 */
    static final List<String> DATA_EXTENSIONS = List.of("csv", "tsv", "xlsx", "xls");

    /** 结果行数上限（防一次查询把上万行塞进上下文）。 */
    static final int MAX_RESULT_ROWS = 100;

    private final KbKnowledgeMapper knowledgeMapper;

    public KbDuckDbDataset(KbKnowledgeMapper knowledgeMapper) {
        this.knowledgeMapper = knowledgeMapper;
    }

    /**
     * 一次查询的结果。
     *
     * @param markdown markdown 表格
     * @param columns  列名列表
     * @param rows     实际返回行数
     * @param truncated 是否因行数上限被截断
     */
    public record Result(String markdown, List<String> columns, int rows, boolean truncated) {
    }

    /**
     * 打开一个内存 DuckDB 连接。调用方负责关闭。
     *
     * @return 连接
     * @throws IllegalStateException 驱动不可用
     */
    public Connection open() {
        try {
            return DriverManager.getConnection("jdbc:duckdb:");
        } catch (Exception e) {
            throw new IllegalStateException("无法打开 DuckDB 内存库：" + e.getMessage(), e);
        }
    }

    /**
     * 把数据文件装成 {@code dataset} 表。
     *
     * @param conn DuckDB 连接
     * @param file 数据文件
     * @return 列结构描述（给模型看）；失败 ⇒ {@code null}
     */
    public String loadDataset(Connection conn, Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try (Statement st = conn.createStatement()) {
            if (name.endsWith(".csv") || name.endsWith(".tsv")) {
                // 路径由我们拼接（来自知识条目记录，不是模型给的）⇒ 无注入面
                String sql = "CREATE TABLE " + KbSqlGuard.DATASET_TABLE
                    + " AS SELECT * FROM read_csv_auto('" + escapePath(file) + "',"
                    + " header=true, ignore_errors=true)";
                st.execute(sql);
            } else {
                // ⚠️ Excel 走「转 CSV 再装」：DuckDB 的 excel 扩展需要联网 INSTALL，
                //    本仓不做联网装扩展（与 KbDataAnalysisStage 同一偏离登记）
                return null;
            }
            return describe(conn);
        } catch (Exception e) {
            log.warn("[DataTool] 装载数据集失败 file={} 原因={}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * 读列结构（{@code DESCRIBE dataset}）。
     *
     * @param conn DuckDB 连接
     * @return 列结构文本（每行 {@code - 列名 (类型)}）；失败 ⇒ {@code null}
     */
    public String describe(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("DESCRIBE " + KbSqlGuard.DATASET_TABLE)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append("- ").append(rs.getString("column_name"))
                    .append(" (").append(rs.getString("column_type")).append(")\n");
            }
            return sb.toString().strip();
        } catch (Exception e) {
            log.warn("[DataTool] 读列结构失败 原因={}", e.getMessage());
            return null;
        }
    }

    /**
     * 执行<b>已通过校验</b>的 SQL，结果转 markdown 表。
     *
     * <p>⚠️ 调用方必须先跑 {@link KbSqlGuard#check(String)}。本方法不做校验。
     *
     * @param conn DuckDB 连接
     * @param sql  只读查询
     * @return 结果
     * @throws IllegalStateException 执行失败
     */
    public Result run(Connection conn, String sql) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            List<String> columnNames = new java.util.ArrayList<>(cols);
            StringBuilder sb = new StringBuilder();
            sb.append('|');
            for (int i = 1; i <= cols; i++) {
                String label = md.getColumnLabel(i);
                columnNames.add(label);
                sb.append(' ').append(escapeCell(label)).append(" |");
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
            if (truncated) {
                sb.append("\n（结果超过 ").append(MAX_RESULT_ROWS).append(" 行，已截断）");
            }
            return new Result(sb.toString(), columnNames, rows, truncated);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 按 id 找知识条目并解析出可读的数据文件路径。
     *
     * <p>⚠️ 路径不存在时返回 {@code null} —— 调用方要如实告诉模型「文件不在本机」，
     * ⛔ 不要退化成「没有数据」。
     *
     * @param knowledgeId 文档 id
     * @return 文件路径；找不到 / 不可读 ⇒ {@code null}
     */
    public Path resolveDataFile(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return null;
        }
        KbKnowledgeRecord row;
        try {
            row = knowledgeMapper.selectOneById(knowledgeId.trim());
        } catch (RuntimeException e) {
            log.warn("[DataTool] 读知识条目失败 id={} 原因={}", knowledgeId, e.getMessage());
            return null;
        }
        if (row == null) {
            return null;
        }
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

    /** 该知识条目是否是 CSV / Excel 数据文件。 */
    public boolean isDataFile(KbKnowledgeRecord row) {
        if (row == null || row.getFileName() == null) {
            return false;
        }
        String name = row.getFileName().toLowerCase(Locale.ROOT);
        for (String ext : DATA_EXTENSIONS) {
            if (name.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }

    /** 路径里的单引号要转义（SQL 字符串字面量）。 */
    private static String escapePath(Path p) {
        return p.toAbsolutePath().toString().replace("'", "''");
    }

    /** markdown 单元格转义（竖线与换行会破坏表格）。 */
    static String escapeCell(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
