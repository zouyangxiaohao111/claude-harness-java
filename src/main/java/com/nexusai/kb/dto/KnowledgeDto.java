package com.nexusai.kb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 知识条目响应 DTO · 出站 JSON 字段名对齐 WeKnora {@code types.Knowledge}（33 字段）。
 *
 * <p><b>契约真源</b>：{@code docs/swagger.json} 的
 * {@code github_com_Tencent_WeKnora_internal_types.Knowledge}。
 *
 * <p><b>哪些字段不返回、为什么</b>（⛔ 都不返回假值）：
 * <table>
 *   <tr><th>源字段</th><th>本批状态</th><th>原因</th></tr>
 *   <tr><td>{@code tags}</td><td>不返回</td><td>依赖 B9 的标签表</td></tr>
 *   <tr><td>{@code profile}</td><td>不返回</td><td>依赖 B6 画像生成</td></tr>
 *   <tr><td>{@code last_faq_import_result}</td><td>不返回</td><td>依赖 B9 FAQ 导入</td></tr>
 *   <tr><td>{@code stall_state} / {@code last_activity_at}</td><td>不返回</td>
 *       <td>依赖 B6 的 span 表（活动流）</td></tr>
 *   <tr><td>{@code knowledge_base_name}</td><td>不返回</td>
 *       <td>源侧也是「不入库、按需 JOIN 填充」；单条查询时如需再补</td></tr>
 *   <tr><td>{@code deleted_at}</td><td>不返回</td>
 *       <td><b>D-13 直接后果</b>：zjky 体系只有 {@code deleted} 布尔，删除时刻不可知</td></tr>
 * </table>
 *
 * <p><b>时间字段</b>：{@code created_at} / {@code updated_at} / {@code processed_at}
 * 一律 {@code yyyy-MM-dd HH:mm:ss}（见 O-7）。源侧是 RFC3339，此处形态不同但语义相同
 * —— 本仓既有 19 张表就是这个形态，不为此单独造一套。
 *
 * @param id                   知识条目 ID
 * @param knowledgeBaseId      所属知识库 ID
 * @param type                 类型：file | url | manual | faq ...
 * @param channel              来源渠道（默认 web）
 * @param title                标题
 * @param description          描述
 * @param source               来源（URL / 文件路径 / 'manual'）
 * @param parseStatus          解析状态机当前值
 * @param enableStatus         enabled | disabled（检索可见性开关）
 * @param summaryStatus        摘要状态
 * @param pendingSubtasksCount 待完成后处理子任务数
 * @param embeddingModelId     嵌入模型 ID
 * @param fileName             文件名
 * @param fileType             文件类型（扩展名，不含点）
 * @param fileSize             文件字节数
 * @param filePath             文件路径
 * @param fileHash             文件内容哈希
 * @param storageSize          存储占用估算（字节）
 * @param folderPath           文件夹路径
 * @param metadata             元数据 JSON
 * @param customMetadata       用户自定义元数据 JSON
 * @param errorMessage         失败原因
 * @param processedAt          处理完成时间
 * @param createdAt            创建时间
 * @param updatedAt            更新时间
 */
public record KnowledgeDto(
    @JsonProperty("id") String id,
    @JsonProperty("knowledge_base_id") String knowledgeBaseId,
    @JsonProperty("type") String type,
    @JsonProperty("channel") String channel,
    @JsonProperty("title") String title,
    @JsonProperty("description") String description,
    @JsonProperty("source") String source,
    @JsonProperty("parse_status") String parseStatus,
    @JsonProperty("enable_status") String enableStatus,
    @JsonProperty("summary_status") String summaryStatus,
    @JsonProperty("pending_subtasks_count") int pendingSubtasksCount,
    @JsonProperty("embedding_model_id") String embeddingModelId,
    @JsonProperty("file_name") String fileName,
    @JsonProperty("file_type") String fileType,
    @JsonProperty("file_size") Long fileSize,
    @JsonProperty("file_path") String filePath,
    @JsonProperty("file_hash") String fileHash,
    @JsonProperty("storage_size") long storageSize,
    @JsonProperty("folder_path") String folderPath,
    @JsonProperty("metadata") String metadata,
    @JsonProperty("custom_metadata") String customMetadata,
    @JsonProperty("error_message") String errorMessage,
    @JsonProperty("processed_at") String processedAt,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt,
    /**
     * 该文档身上的标签（{@code kb_knowledge_tag_relation} ⋈ {@code kb_knowledge_tag}）。
     *
     * <p><b>⭐ 2026-11-06 补上</b>：此前 javadoc 写「不返回，依赖 B9 的标签表」，
     * 但 B9 的表（V269 `kb_knowledge_tag` / `kb_knowledge_tag_relation`）早已存在，
     * 写入端点也有了 ⇒ 只是列表/详情没 JOIN。缺这一项的后果是：
     * 前端文档列表的「标签」列永远是空的，而用户明明能打上标签（打上了看不见）。
     *
     * <p>⚠️ 空数组（不是 null）：前端按数组渲染，null 会多一条判空分支。
     */
    @JsonProperty("tags") java.util.List<KnowledgeTagView> tags
) {

    /** 文档上的一个标签（只出前端渲染需要的字段）。 */
    public record KnowledgeTagView(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("color") String color
    ) {
    }
}
