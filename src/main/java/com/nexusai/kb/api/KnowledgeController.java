package com.nexusai.kb.api;

import com.nexusai.kb.domain.KnowledgeService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.dto.HybridSearchRequest;
import com.nexusai.kb.dto.KnowledgeDto;
import com.nexusai.kb.dto.KnowledgePageResponse;
import com.nexusai.kb.dto.SearchResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * 知识管理 REST 端点 · 对齐 WeKnora 「知识管理」tag（批次 B3）。
 *
 * <p><b>本批实现的 7 个端点</b>（源侧该 tag 共 24 个）：
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>源侧</th></tr>
 *   <tr><td>POST</td><td>/api/v1/knowledge-bases/{id}/knowledge/file</td>
 *       <td>POST /knowledge-bases/{id}/knowledge/file</td></tr>
 *   <tr><td>GET</td><td>/api/v1/knowledge-bases/{id}/knowledge</td>
 *       <td>GET /knowledge-bases/{id}/knowledge</td></tr>
 *   <tr><td>GET</td><td>/api/v1/knowledge/{id}</td><td>GET /knowledge/{id}</td></tr>
 *   <tr><td>DELETE</td><td>/api/v1/knowledge/{id}</td><td>DELETE /knowledge/{id}</td></tr>
 *   <tr><td>POST</td><td>/api/v1/knowledge/{id}/reparse</td><td>POST /knowledge/{id}/reparse</td></tr>
 *   <tr><td>POST</td><td>/api/v1/knowledge/{id}/cancel-parse</td>
 *       <td>POST /knowledge/{id}/cancel-parse</td></tr>
 * </table>
 *
 * <p><b>未实现的端点及原因</b>（依赖后续批次）：
 * 手工/URL 创建（需 B6 摘要与抓取）、批量操作（需跨行事务语义确认）、
 * download/preview（需对象存储抽象）、spans（需 B6 span 表）、文件夹树（需 B9）、
 * tags（需 B9）、images（需 B6）、hybrid-search（需 B3）。
 */
@RestController
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    /**
     * 入库文件落盘根目录 · 由 Spring 注入 {@code nexusai.knowledge.storage-dir}。
     *
     * <p>⚠️ 刻意做成可配置而不是写死：单机默认落在配置根下，测试与多实例部署可覆盖。
     */
    @org.springframework.beans.factory.annotation.Value(
        "${nexusai.knowledge.storage-dir:${user.home}/.nexusai/kb-storage}")
    private String storageDir;

    @Autowired
    private KnowledgeService knowledgeService;

    /**
     * 从文件创建知识（multipart）。
     *
     * <p><b>源侧请求字段</b>（{@code knowledge.go:294-305}）：
     * {@code file}（必填）、{@code fileName}（自定义名，可带目录前缀）、
     * {@code metadata}（JSON）、{@code enable_multimodel}、{@code tag_ids}、{@code process_config}。
     * 本批只支持 {@code file} / {@code fileName} / {@code metadata}；
     * 其余三项依赖 B6/B9，⛔ 不静默忽略 —— 传了就报 400。
     *
     * @param id       知识库 ID
     * @param file     上传文件
     * @param fileName 自定义文件名（可空）
     * @param metadata 元数据 JSON（可空）
     * @return 创建的知识条目（201）
     */
    @PostMapping(
        value = "/api/v1/knowledge-bases/{id}/knowledge/file",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeDto> createFromFile(
        @PathVariable String id,
        @RequestPart("file") MultipartFile file,
        @RequestParam(value = "fileName", required = false) String fileName,
        @RequestParam(value = "metadata", required = false) String metadata,
        @RequestParam(value = "enable_multimodel", required = false) String enableMultimodel,
        @RequestParam(value = "tag_ids", required = false) String tagIds,
        @RequestParam(value = "process_config", required = false) String processConfig
    ) throws IOException {
        rejectUnsupported(enableMultimodel, "enable_multimodel", "B6（多模态处理）");
        rejectUnsupported(tagIds, "tag_ids", "B9（标签）");
        rejectUnsupported(processConfig, "process_config", "B6（处理配置覆盖）");

        if (file == null || file.isEmpty()) {
            throw new ValidationException("上传文件为空");
        }
        String name = (fileName == null || fileName.isBlank())
            ? file.getOriginalFilename() : fileName;
        try (InputStream in = file.getInputStream()) {
            KnowledgeDto dto = knowledgeService.createFromFile(
                id, name, in, Path.of(storageDir), metadata);
            log.info("[知识API] POST /knowledge-bases/{}/knowledge/file → 已创建 id={}", id, dto.id());
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        }
    }

    /**
     * 知识列表（分页 + 筛选）。
     *
     * <p>⚠️ 响应形状是源侧 {@code PageResponse}（{@code {success,data,page,page_size,total}}），
     * 不是本仓既有分页类型。
     */
    @GetMapping("/api/v1/knowledge-bases/{id}/knowledge")
    public KnowledgePageResponse<KnowledgeDto> list(
        @PathVariable String id,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "page_size", required = false) Integer pageSize,
        @RequestParam(value = "parse_status", required = false) String parseStatus,
        @RequestParam(value = "file_type", required = false) String fileType,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "folder_path", required = false) String folderPath
    ) {
        return knowledgeService.list(id, page, pageSize, parseStatus, fileType, keyword, folderPath);
    }

    /** 知识详情 · 不存在 404。 */
    @GetMapping("/api/v1/knowledge/{id}")
    public KnowledgeDto get(@PathVariable String id) {
        return knowledgeService.getById(id);
    }

    /** 删除知识（逻辑删）· 成功 204。 */
    @DeleteMapping("/api/v1/knowledge/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        knowledgeService.delete(id);
        log.info("[知识API] DELETE /api/v1/knowledge/{} → 已逻辑删除", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 重新解析 · 返回新任务 ID。
     *
     * <p>响应体形如 {@code {"task_id": 42}}（源侧返回 task id，本仓队列主键是自增 long）。
     */
    @PostMapping("/api/v1/knowledge/{id}/reparse")
    public java.util.Map<String, Object> reparse(@PathVariable String id) {
        long taskId = knowledgeService.reparse(id);
        log.info("[知识API] POST /api/v1/knowledge/{}/reparse → task={}", id, taskId);
        return java.util.Map.of("task_id", taskId);
    }

    /** 取消解析 · 成功 204。 */
    @PostMapping("/api/v1/knowledge/{id}/cancel-parse")
    public ResponseEntity<Void> cancelParse(@PathVariable String id) {
        knowledgeService.cancelParse(id);
        log.info("[知识API] POST /api/v1/knowledge/{}/cancel-parse → 已取消", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 混合检索（POST 版）· 对齐源侧 {@code POST /knowledge-bases/{id}/hybrid-search}。
     *
     * <p><b>为什么同时提供 GET 版</b>：源侧两个方法都有（GET 走 query 参数，
     * 适合浏览器直接访问 / 简单脚本；POST 走 JSON body，适合长查询和结构化参数）。
     * 只做一个会让一部分调用方无法迁移。
     *
     * @param id      知识库 ID
     * @param request 检索请求
     * @return 命中列表
     */
    @PostMapping("/api/v1/knowledge-bases/{id}/hybrid-search")
    public List<SearchResultDto> hybridSearchPost(
        @PathVariable String id,
        @RequestBody HybridSearchRequest request
    ) {
        rejectUnsupportedSearchFields(request);
        List<SearchResultDto> hits = knowledgeService.hybridSearch(
            id, request.queryText(), request.matchCount(),
            request.disableVectorMatch(), request.disableKeywordsMatch(), request.knowledgeIds(),
            request.rerankModelId(), request.rerankTopN());
        log.info("[知识API] POST /knowledge-bases/{}/hybrid-search → {} 条", id, hits.size());
        return hits;
    }

    /**
     * 混合检索（GET 版）· 对齐源侧 {@code GET /knowledge-bases/{id}/hybrid-search}。
     *
     * @param id         知识库 ID
     * @param queryText  查询文本
     * @param matchCount 返回条数
     * @return 命中列表
     */
    @GetMapping("/api/v1/knowledge-bases/{id}/hybrid-search")
    public List<SearchResultDto> hybridSearchGet(
        @PathVariable String id,
        @RequestParam(value = "query_text", required = false) String queryText,
        @RequestParam(value = "match_count", required = false) Integer matchCount,
        @RequestParam(value = "disable_vector_match", required = false) Boolean disableVector,
        @RequestParam(value = "disable_keywords_match", required = false) Boolean disableKeyword,
        @RequestParam(value = "rerank_model_id", required = false) String rerankModelId,
        @RequestParam(value = "rerank_top_n", required = false) Integer rerankTopN
    ) {
        List<SearchResultDto> hits = knowledgeService.hybridSearch(
            id, queryText, matchCount, disableVector, disableKeyword, null,
            rerankModelId, rerankTopN);
        log.info("[知识API] GET /knowledge-bases/{}/hybrid-search → {} 条", id, hits.size());
        return hits;
    }

    /**
     * 本批未实现的检索参数：传了就 400（⛔ 不静默忽略）。
     *
     * <p>阈值 / 重排 / 标签这几个字段调用方传了就是期待生效；静默忽略会返回
     * 「看起来正常但其实没按你的要求筛」的结果 —— 这比报错更难查。
     */
    private static void rejectUnsupportedSearchFields(HybridSearchRequest request) {
        // 本批 HybridSearchRequest 里只有已实现字段；新增未实现字段时在此拦截。
        // 保留方法是为了给出明确的扩展点，避免后来者直接往 record 加字段而不做校验。
    }

    /**
     * 本批不支持的参数：传了就 400，⛔ 不静默忽略。
     *
     * <p><b>为什么必须报错而不是忽略</b>：调用方传 {@code tag_ids} 是期待标签生效；
     * 静默忽略会返回「成功」但标签没打上 —— 这种假成功比报错更难排查。
     */
    private static void rejectUnsupported(String value, String field, String batch) {
        if (value != null && !value.isBlank()) {
            throw new ValidationException(
                "参数 " + field + " 本批未实现（依赖 " + batch + "），暂不支持");
        }
    }
}
