package com.nexusai.kb.api;

import com.nexusai.kb.domain.KnowledgeBaseService;
import com.nexusai.kb.dto.CreateKnowledgeBaseRequest;
import com.nexusai.kb.dto.KnowledgeBaseDto;
import com.nexusai.kb.dto.UpdateKnowledgeBaseRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库 REST 端点 · 对齐 WeKnora {@code /knowledge-bases} 路径族（批次 B1a）。
 *
 * <p><b>路径前缀为 {@code /api/v1/knowledge-bases}</b>：
 * NexusAI 现有控制器统一 {@code /api/v1/xxx}；源侧是裸 {@code /knowledge-bases}。
 * 前缀差异是 NexusAI 既有约定，<b>不改</b> —— 客户端迁移时改 base URL 即可。
 *
 * <p><b>本批实现的 5 个端点</b>（源侧「知识库」tag 共 17 个）：
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>源侧</th></tr>
 *   <tr><td>GET</td><td>/api/v1/knowledge-bases</td><td>GET /knowledge-bases</td></tr>
 *   <tr><td>POST</td><td>/api/v1/knowledge-bases</td><td>POST /knowledge-bases</td></tr>
 *   <tr><td>GET</td><td>/api/v1/knowledge-bases/{id}</td><td>GET /knowledge-bases/{id}</td></tr>
 *   <tr><td>PUT</td><td>/api/v1/knowledge-bases/{id}</td><td>PUT /knowledge-bases/{id}</td></tr>
 *   <tr><td>DELETE</td><td>/api/v1/knowledge-bases/{id}</td><td>DELETE /knowledge-bases/{id}</td></tr>
 * </table>
 *
 * <p><b>其余 12 个端点未实现</b>（依赖 B2/B3/B6 的表），清单与依赖见
 * {@code docs/zjkycode/specs/weknora-replica/BATCH_PLAN.md} B1。
 *
 * <p><b>错误契约</b>：{@code GlobalExceptionHandler} 转 RFC 7807 Problem。
 * {@code ValidationException} → 400、{@code NotFoundException} → 404、
 * Bean Validation 失败 → 400。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    /** 列表 · 置顶优先 + 创建时间倒序。 */
    @GetMapping
    public List<KnowledgeBaseDto> list() {
        List<KnowledgeBaseDto> dtos = knowledgeBaseService.list();
        if (log.isDebugEnabled()) {
            log.debug("[知识库API] GET /api/v1/knowledge-bases → {} 条", dtos.size());
        }
        return dtos;
    }

    /** 单条查询 · 不存在返回 404。 */
    @GetMapping("/{id}")
    public KnowledgeBaseDto get(@PathVariable String id) {
        KnowledgeBaseDto dto = knowledgeBaseService.getById(id);
        if (log.isDebugEnabled()) {
            log.debug("[知识库API] GET /api/v1/knowledge-bases/{} → name={}", id, dto.name());
        }
        return dto;
    }

    /** 创建 · 成功返回 201（对齐源侧 201）。 */
    @PostMapping
    public ResponseEntity<KnowledgeBaseDto> create(@Valid @RequestBody CreateKnowledgeBaseRequest req) {
        KnowledgeBaseDto dto = knowledgeBaseService.create(req);
        log.info("[知识库API] POST /api/v1/knowledge-bases → 已创建 id={}", dto.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    /**
     * 局部更新 · 批次 W5。
     *
     * <p>⭐⭐ 与 PUT 的差别是<b>「没传」不等于「传 null」</b>：
     * 这里收原始 JSON，只有请求体里<b>出现过</b>的字段才会被改。
     * 随手 PATCH 一个 name 不会把 {@code faq_config} 清空。
     */
    @PatchMapping("/{id}")
    public KnowledgeBaseDto patch(@PathVariable String id,
                                  @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        KnowledgeBaseDto dto = knowledgeBaseService.patch(id, body);
        log.info("[知识库API] PATCH /api/v1/knowledge-bases/{} → 已局部更新", id);
        return dto;
    }

    /** 局部更新 · null 字段表示不改。 */
    @PutMapping("/{id}")
    public KnowledgeBaseDto update(@PathVariable String id,
                                   @Valid @RequestBody UpdateKnowledgeBaseRequest req) {
        KnowledgeBaseDto dto = knowledgeBaseService.update(id, req);
        log.info("[知识库API] PUT /api/v1/knowledge-bases/{} → 已更新", id);
        return dto;
    }

    /** 逻辑删除 · 成功返回 204（源侧无 204 声明，此处取 REST 惯例；见下方说明）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        knowledgeBaseService.delete(id);
        log.info("[知识库API] DELETE /api/v1/knowledge-bases/{} → 已逻辑删除", id);
        return ResponseEntity.noContent().build();
    }
}
