package com.nexusai.application.rerank;

import com.nexusai.application.provider.ModelEndpointResolver;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.rerank.RerankClient;
import com.nexusai.infra.rerank.RerankCommand;
import com.nexusai.infra.rerank.RerankResult;
import com.nexusai.infra.rerank.RerankScore;
import com.nexusai.model.provider.dto.RerankItem;
import com.nexusai.model.provider.dto.RerankResponse;
import com.nexusai.repository.provider.entity.ModelRecord;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 重排序应用服务 · 把「DB 里的模型配置」翻译成「一次 rerank 调用」。
 *
 * <p><b>职责链</b>：模型/provider 解析与类型门（{@link ModelEndpointResolver}）→ 输入校验 →
 * 调 {@link RerankClient}（OpenAI/Cohere 兼容 {@code /reranks}）。不写库、不落结果。
 *
 * <p><b>与 embedding 的关系</b>：同一 provider 配置（baseUrl + key）即可；百炼 baseUrl 仍是
 * {@code .../compatible-mode/v1}，重排端点由 client 推导为 {@code .../compatible-api/v1/reranks}
 * —— ⛔ 不需要新增 provider 类型。
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    /** 单次候选文档上限（百炼上限 500）。 */
    static final int MAX_DOCUMENTS = 500;
    /** 单条文档字符上限（粗粒度防滥用；token 上限由服务端判定）。 */
    static final int MAX_CHARS_PER_DOCUMENT = 32_768;
    /** 单次总字符上限。 */
    static final long MAX_TOTAL_CHARS = 500_000;

    private final ModelEndpointResolver resolver;
    private final List<RerankClient> rerankClients;

    @Autowired
    public RerankService(ModelEndpointResolver resolver, List<RerankClient> rerankClients) {
        this.resolver = resolver;
        this.rerankClients = rerankClients;
    }

    /**
     * 重排序。
     *
     * @param modelId   模型行 id（{@code models.type} 必须是 {@code rerank}）
     * @param query     查询
     * @param documents 候选文档
     * @param topN      返回前 N 条（null → 不发送；超过候选数 → 收敛到候选数）
     * @param instruct  指令（可选）
     * @return 排序结果
     */
    public RerankResponse rerank(String modelId, String query, List<String> documents,
                                 Integer topN, String instruct) {
        ModelEndpointResolver.Resolved resolved = resolver.resolve(modelId, "rerank");
        ModelRecord model = resolved.model();

        validate(query, documents);
        // 优先级：请求显式值 > 模型级默认（models.rerank_top_n / rerank_instruct）
        Integer configuredTopN = topN != null ? topN : model.getRerankTopN();
        Integer effectiveTopN = normalizeTopN(configuredTopN, documents.size());
        if (effectiveTopN != null && configuredTopN != null && effectiveTopN < configuredTopN) {
            log.warn("[RerankService] topN={} 超过候选数 {}，已收敛为候选数",
                configuredTopN, documents.size());
        }
        String effectiveInstruct = resolveInstruct(instruct, model.getRerankInstruct());

        RerankClient client = selectClient(rerankClients, resolved.config(), resolved.provider().getId());
        RerankCommand command = new RerankCommand(model.getName(), query.trim(), documents,
            effectiveTopN, effectiveInstruct);
        RerankResult result = client.rerank(resolved.config(), command);

        List<RerankItem> items = new ArrayList<>(result.scores().size());
        for (RerankScore score : result.scores()) {
            items.add(new RerankItem(score.index(), score.score(), score.document()));
        }
        if (log.isInfoEnabled()) {
            log.info("[RerankService] 重排序完成: model={} 候选={} topN={} 返回={} totalTokens={}",
                model.getName(), documents.size(), effectiveTopN, items.size(), result.totalTokens());
        }
        return new RerankResponse(model.getName(), items.size(), List.copyOf(items), result.totalTokens());
    }

    /**
     * 选择重排客户端（厂商无关路由）。
     *
     * <p>命中必须<b>恰好 1 个</b>：0 个（没有实现支持该 provider）或多个（协议声明重叠）
     * 都 fail loud —— ⛔ 不猜、不按 bean 顺序取第一个（否则厂商实现一多就会静默选错 schema）。
     *
     * @param clients    容器内全部重排客户端
     * @param config     provider 运行时配置（供 {@link RerankClient#supports} 判定）
     * @param providerId provider id（仅用于错误文案）
     * @return 唯一命中的客户端
     */
    static RerankClient selectClient(List<RerankClient> clients, ProviderConfig config, String providerId) {
        if (clients == null || clients.isEmpty()) {
            throw new ValidationException("没有可用的重排客户端");
        }
        List<RerankClient> hits = clients.stream().filter(client -> client.supports(config)).toList();
        if (hits.isEmpty()) {
            throw new ValidationException("没有可用的重排客户端（provider=" + providerId + "）");
        }
        if (hits.size() > 1) {
            List<String> protocols = hits.stream().map(RerankClient::protocol).toList();
            throw new ValidationException("多个重排客户端同时声明支持 provider "
                + providerId + ": " + protocols);
        }
        return hits.get(0);
    }

    /** 输入校验：query 非空、候选非空、条数/单条长度/总长度上限。 */
    static void validate(String query, List<String> documents) {
        if (query == null || query.isBlank()) {
            throw new ValidationException("query 不能为空");
        }
        if (documents == null || documents.isEmpty()) {
            throw new ValidationException("documents 不能为空");
        }
        if (documents.size() > MAX_DOCUMENTS) {
            throw new ValidationException("documents 最多 " + MAX_DOCUMENTS + " 条");
        }
        long total = 0;
        for (int i = 0; i < documents.size(); i++) {
            String document = documents.get(i);
            if (document == null || document.isBlank()) {
                throw new ValidationException("documents[" + i + "] 不能为空");
            }
            if (document.length() > MAX_CHARS_PER_DOCUMENT) {
                throw new ValidationException("documents[" + i + "] 超过 "
                    + MAX_CHARS_PER_DOCUMENT + " 字符");
            }
            total += document.length();
        }
        if (total > MAX_TOTAL_CHARS) {
            throw new ValidationException("documents 总字符数超过 " + MAX_TOTAL_CHARS);
        }
    }

    /** 指令优先级：请求显式值 > 模型级默认；空/空白 → null（不发送）。 */
    static String resolveInstruct(String requestInstruct, String modelInstruct) {
        if (requestInstruct != null && !requestInstruct.isBlank()) {
            return requestInstruct.trim();
        }
        if (modelInstruct != null && !modelInstruct.isBlank()) {
            return modelInstruct.trim();
        }
        return null;
    }

    /** topN 归一：null → null（服务端默认）；≤0 → 400；超过候选数 → 候选数。 */
    static Integer normalizeTopN(Integer topN, int documentCount) {
        if (topN == null) {
            return null;
        }
        if (topN <= 0) {
            throw new ValidationException("topN 必须为正整数");
        }
        return Math.min(topN, documentCount);
    }
}
