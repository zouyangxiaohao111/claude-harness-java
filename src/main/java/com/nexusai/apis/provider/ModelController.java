package com.nexusai.apis.provider;

import com.nexusai.model.provider.dto.*;
import com.nexusai.domain.provider.ModelService;
import com.nexusai.application.embedding.EmbeddingService;
import com.nexusai.application.rerank.RerankService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Model REST 端点（嵌套在 Provider 下） */
@RestController
@RequestMapping("/api/v1/providers/{providerId}/models")
public class ModelController {

    @Autowired private ModelService modelService;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private RerankService rerankService;

    @GetMapping
    public List<ModelDto> list(@PathVariable String providerId) { return modelService.listByProvider(providerId); }

    @GetMapping("/{id}")
    public ModelDto get(@PathVariable String providerId, @PathVariable String id) { return modelService.getById(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelDto create(@PathVariable String providerId, @RequestBody ModelCreateRequest req) {
        return modelService.create(providerId, req);
    }

    @PatchMapping("/{id}")
    public ModelDto update(@PathVariable String providerId, @PathVariable String id, @RequestBody ModelUpdateRequest req) {
        return modelService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String providerId, @PathVariable String id) { modelService.delete(id); }

    /**
     * 向量嵌入（通用能力 · 知识库/RAG 之前先提供可验证入口）。
     *
     * <p>只接受 {@code models.type = embedding} 的模型；对话/重排模型 → 400。
     * 请求/响应体见 {@link EmbeddingRequest} / {@link EmbeddingResponse}。
     * {@code providerId} 仅用于路径一致性（模型 id 是权威；与 update/delete 同口径）。
     *
     * @param providerId 路径上的 provider id（不参与解析）
     * @param id         模型行 id
     * @param req        文本列表 + 可选维度覆盖
     * @return 向量列表（与 inputs 顺序一致）+ 实际维度 + token
     */
    @PostMapping("/{id}/embed")
    public EmbeddingResponse embed(@PathVariable String providerId, @PathVariable String id,
                                   @RequestBody EmbeddingRequest req) {
        return embeddingService.embed(id, req.inputs(), req.dimensions());
    }

    /**
     * 重排序（通用能力 · 与向量同一 provider 配置，无需新增 provider 类型）。
     *
     * <p>只接受 {@code models.type = rerank} 的模型。百炼 provider 的 baseUrl 填
     * {@code .../compatible-mode/v1} 即可，端点自动推导为 {@code .../compatible-api/v1/reranks}。
     *
     * @param providerId 路径上的 provider id（不参与解析）
     * @param id         模型行 id
     * @param req        query + documents + 可选 topN/instruct
     * @return 排序结果
     */
    @PostMapping("/{id}/rerank")
    public RerankResponse rerank(@PathVariable String providerId, @PathVariable String id,
                                 @RequestBody RerankRequest req) {
        return rerankService.rerank(id, req.query(), req.documents(), req.topN(), req.instruct());
    }
}
