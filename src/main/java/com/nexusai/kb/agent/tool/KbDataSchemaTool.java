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
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Connection;

/**
 * 查看数据文件结构（{@code data_schema}）· 入参对齐源侧
 * {@code internal/agent/tools/data_schema.go}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { knowledge_id*: string }   // doc://N 文档句柄
 * </pre>
 * 源站描述：「Use this tool to get the schema information of a CSV or Excel file loaded into DuckDB.
 * It returns the columns and row count. When querying the document with data_analysis,
 * reference it as the table {@code "dataset"}」。
 *
 * <h2>⭐ 为什么需要单独一个工具</h2>
 * 模型要先知道列名和类型，才能写出正确的 SQL。不给 schema 直接让它写 SQL，
 * 它会猜列名，然后每次执行都失败——这是源站把 schema 查询独立成工具的原因。
 */
@Component
@ExclusiveTool
public class KbDataSchemaTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbDataSchemaTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Autowired private KbDuckDbDataset dataset;

    @Autowired private KbKnowledgeMapper knowledgeMapper;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "data_schema";
    }

    @Override
    public String description() {
        return "查看一个 CSV / Excel 文件的列结构与行数（把它装进 DuckDB 后 DESCRIBE）。"
            + " 在 data_analysis 里引用该文件时，表名固定是 \"dataset\"。"
            + " 写 SQL 前如果不知道列名，先用本工具查。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        ObjectNode kid = JSON.objectNode();
        kid.put("type", "string");
        kid.put("description", "文档 id（doc://N 的 N，来自 search_knowledge / list_documents）");
        props.set("knowledge_id", kid);

        ArrayNode required = JSON.arrayNode();
        required.add("knowledge_id");
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
            String knowledgeId = call.input().path("knowledge_id").asText("").trim();
            if (knowledgeId.isEmpty()) {
                return ToolResult.error(call.id(), "knowledge_id 不能为空");
            }

            KbKnowledgeRecord row = lookup(knowledgeId);
            if (row == null) {
                return ToolResult.error(call.id(), "找不到该文档：" + knowledgeId);
            }
            String denied = checkScope(row.getKnowledgeBaseId(), ctx);
            if (denied != null) {
                log.warn("[data_schema] 拒绝越权读取 knowledge={} kb={}",
                    knowledgeId, row.getKnowledgeBaseId());
                return ToolResult.error(call.id(), denied);
            }
            if (!dataset.isDataFile(row)) {
                return ToolResult.error(call.id(),
                    "该文档不是 CSV / Excel 数据文件（当前只支持 .csv / .tsv / .xlsx / .xls）："
                        + row.getFileName());
            }

            Path file = dataset.resolveDataFile(knowledgeId);
            if (file == null) {
                // ⚠️ 如实说明「文件不在本机」，⛔ 不说「没有数据」
                return ToolResult.error(call.id(),
                    "数据文件不在本机可读路径上（可能尚未下载或已被清理），无法查看结构");
            }

            try (Connection conn = dataset.open()) {
                String schema = dataset.loadDataset(conn, file);
                if (schema == null) {
                    return ToolResult.error(call.id(),
                        "装载失败：只支持 CSV / TSV 直接装入 DuckDB；"
                            + "Excel 需要先转成 CSV（本仓不联网安装 DuckDB 的 excel 扩展）");
                }
                long rowCount = countRows(conn);
                log.info("[data_schema] 结构查询完成 knowledge={} 文件={} 列数={} 行数={}",
                    knowledgeId, file.getFileName(),
                    schema.isBlank() ? 0 : schema.split("\n").length, rowCount);

                return ToolResult.success(call.id(),
                    "{\"table\":\"dataset\",\"columns\":\""
                        + KbWikiToolSupport.esc(schema)
                        + "\",\"row_count\":" + rowCount
                        + ",\"note\":\"在 data_analysis 的 SQL 里用表名 dataset，列名照上面的用\"}");
            }
        } catch (RuntimeException e) {
            log.error("[data_schema] 执行失败", e);
            return ToolResult.error(call.id(), "查看数据文件结构失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("[data_schema] 连接/关闭失败", e);
            return ToolResult.error(call.id(), "查看数据文件结构失败：" + e.getMessage());
        }
    }

    /** 数总行数（失败返回 -1，不阻断 schema 返回）。 */
    private long countRows(Connection conn) {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM " + com.nexusai.kb.app.qa.KbSqlGuard.DATASET_TABLE)) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (Exception e) {
            log.warn("[data_schema] 统计行数失败 原因={}", e.getMessage());
            return -1;
        }
    }

    private KbKnowledgeRecord lookup(String knowledgeId) {
        try {
            return knowledgeMapper.selectOneById(knowledgeId);
        } catch (RuntimeException e) {
            log.warn("[data_schema] 读知识条目失败 id={} 原因={}", knowledgeId, e.getMessage());
            return null;
        }
    }

    /** 作用域校验。 */
    private String checkScope(String kbId, ToolUseContext ctx) {
        return KbWikiToolSupport.checkKbAllowed(scope, ctx, kbId);
    }
}
