package com.nexusai.kb.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ExclusiveTool;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.kb.app.qa.KbSqlGuard;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Connection;

/**
 * 对 CSV / Excel 数据执行只读 SQL（{@code data_analysis}）· 入参对齐源侧
 * {@code internal/agent/tools/data_analysis.go}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { knowledge_id*: string,   // doc://N 文档句柄
 *   sql*: string }           // 只读 SELECT，引用文档为表 "dataset"
 * </pre>
 *
 * <h2>⭐⭐ 安全边界在 {@link KbSqlGuard}</h2>
 * SQL 由<b>模型</b>生成，所以必须服务端校验。「只读」不能靠提示词保证 ——
 * 提示词是建议，校验才是边界。本工具<b>只</b>调 {@link KbSqlGuard#check}，
 * ⛔ 不自己再写一套规则（两套规则迟早不一致，而且不一致的那套会静默放行）。
 *
 * <h2>⚠️ 列名用双引号</h2>
 * DuckDB 对含特殊字符的标识符要求双引号；这属于 SQL 写法，由模型负责，
 * 校验器不拦（它只拦写操作与危险函数）。
 */
@Component
@ExclusiveTool
public class KbDataAnalysisTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbDataAnalysisTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Autowired private KbDuckDbDataset dataset;

    @Autowired private KbKnowledgeMapper knowledgeMapper;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "data_analysis";
    }

    @Override
    public String description() {
        return "把 CSV / Excel 文件装进 DuckDB 并执行一条只读 SQL。"
            + " 该文件在 SQL 里固定是表 \"dataset\"；不要把文档 id 写进 SQL。"
            + " 写 SQL 前若不知道列名，先用 data_schema 查。"
            + " 只允许只读查询（写操作会被服务端拒绝）。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        ObjectNode kid = JSON.objectNode();
        kid.put("type", "string");
        kid.put("description", "文档 id（doc://N 的 N），必须是 CSV / Excel 文件");
        props.set("knowledge_id", kid);

        ObjectNode sql = JSON.objectNode();
        sql.put("type", "string");
        sql.put("description",
            "只读 SELECT，引用文档为表 \"dataset\"（例如 SELECT COUNT(*) FROM dataset）。"
                + " 这是唯一可用的表名；文档 id 不要写进 SQL。");
        props.set("sql", sql);

        ArrayNode required = JSON.arrayNode();
        required.add("knowledge_id");
        required.add("sql");
        root.set("required", required);
        return root;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        try {
            JsonNode in = call.input();
            String knowledgeId = in.path("knowledge_id").asText("").trim();
            String sql = in.path("sql").asText("").trim();

            if (knowledgeId.isEmpty()) {
                return ToolResult.error(call.id(), "knowledge_id 不能为空");
            }
            if (sql.isEmpty()) {
                return ToolResult.error(call.id(), "sql 不能为空");
            }

            // ⭐ 唯一安全边界：先校验再执行
            KbSqlGuard.Verdict verdict = KbSqlGuard.check(sql);
            if (!verdict.ok()) {
                // 拒绝是安全边界，必须 WARN（不是 debug）：说明模型在越界
                log.warn("[data_analysis] SQL 被拒 knowledge={} 原因={} sql={}",
                    knowledgeId, verdict.reason(), preview(sql));
                return ToolResult.error(call.id(),
                    "SQL 未通过安全校验：" + verdict.reason()
                        + "。只允许只读 SELECT，且只能引用表 dataset。");
            }

            KbKnowledgeRecord row;
            try {
                row = knowledgeMapper.selectOneById(knowledgeId);
            } catch (RuntimeException e) {
                log.warn("[data_analysis] 读知识条目失败 id={} 原因={}", knowledgeId, e.getMessage());
                return ToolResult.error(call.id(), "找不到该文档：" + knowledgeId);
            }
            if (row == null) {
                return ToolResult.error(call.id(), "找不到该文档：" + knowledgeId);
            }
            String denied = KbWikiToolSupport.checkKbAllowed(scope, ctx, row.getKnowledgeBaseId());
            if (denied != null) {
                log.warn("[data_analysis] 拒绝越权读取 knowledge={} kb={}",
                    knowledgeId, row.getKnowledgeBaseId());
                return ToolResult.error(call.id(), denied);
            }
            if (!dataset.isDataFile(row)) {
                return ToolResult.error(call.id(),
                    "该文档不是 CSV / Excel 数据文件（当前支持 .csv / .tsv / .xlsx / .xls）："
                        + row.getFileName());
            }

            Path file = dataset.resolveDataFile(knowledgeId);
            if (file == null) {
                return ToolResult.error(call.id(),
                    "数据文件不在本机可读路径上（可能尚未下载或已被清理），无法分析");
            }

            try (Connection conn = dataset.open()) {
                String schema = dataset.loadDataset(conn, file);
                if (schema == null) {
                    return ToolResult.error(call.id(),
                        "装载失败：只支持 CSV / TSV 直接装入 DuckDB；"
                            + "Excel 需要先转成 CSV（本仓不联网安装 DuckDB 的 excel 扩展）");
                }
                KbDuckDbDataset.Result result = dataset.run(conn, sql);

                log.info("[data_analysis] 执行完成 knowledge={} 列数={} 行数={} 截断={} sql={}",
                    knowledgeId, result.columns().size(), result.rows(), result.truncated(),
                    preview(sql));

                StringBuilder sb = new StringBuilder("{\"markdown\":\"")
                    .append(KbWikiToolSupport.esc(result.markdown()))
                    .append("\",\"columns\":[");
                for (int i = 0; i < result.columns().size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(KbWikiToolSupport.esc(result.columns().get(i))).append('"');
                }
                sb.append("],\"rows\":").append(result.rows())
                  .append(",\"truncated\":").append(result.truncated())
                  .append('}');

                return ToolResult.success(call.id(), sb.toString());
            }
        } catch (IllegalStateException e) {
            // 执行失败（SQL 语法 / 列名不存在等）—— 把真实原因给模型，让它自己改
            log.warn("[data_analysis] SQL 执行失败 原因={}", e.getMessage());
            return ToolResult.error(call.id(), "SQL 执行失败：" + e.getMessage()
                + "。可先用 data_schema 确认列名。");
        } catch (RuntimeException e) {
            log.error("[data_analysis] 执行失败", e);
            return ToolResult.error(call.id(), "数据分析失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("[data_analysis] 连接/关闭失败", e);
            return ToolResult.error(call.id(), "数据分析失败：" + e.getMessage());
        }
    }

    /** 日志里截断 SQL 预览。 */
    private static String preview(String sql) {
        String s = sql == null ? "" : sql.replaceAll("\\s+", " ").strip();
        return s.length() <= 160 ? s : s.substring(0, 160) + "…";
    }
}
