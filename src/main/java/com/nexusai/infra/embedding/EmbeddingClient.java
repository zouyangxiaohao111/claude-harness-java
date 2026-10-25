package com.nexusai.infra.embedding;

import com.nexusai.infra.llm.ProviderConfig;
import java.util.List;

/**
 * 向量嵌入客户端 · 本仓通用能力（非对话）。
 *
 * <p><b>WHY 独立于 {@link LlmProvider}</b>：{@code LlmProvider.stream} 的语义是「对话流」
 * （messages/tools/usage/流式块），embedding 是一次性请求-响应、无消息无工具。塞进同一接口
 * 会让所有 provider 实现被迫实现一个永不使用的方法，也会让对话路径的守卫（模型类型校验）
 * 与向量路径互相污染。
 *
 * <p><b>协议</b>：目前只实现 OpenAI 兼容的 {@code POST {baseUrl}/embeddings}
 * （百炼 compatible-mode 同一协议：{@code https://dashscope.aliyuncs.com/compatible-mode/v1}）。
 * 请求字段：{@code model} / {@code input[]} / {@code dimensions?} / {@code encoding_format=float}。
 *
 * <p><b>不做的事</b>：不负责 provider/model 解析、不解密 key、不校验模型类型 —— 那些在
 * {@code EmbeddingService}（应用层）。本接口只做「给配置 + 文本 → 向量」。
 */
public interface EmbeddingClient {

    /**
     * 向量化结果。
     *
     * @param vectors      与入参 inputs <b>顺序一一对应</b>；每条是 float 向量
     * @param model        实际使用的模型名（回显请求值）
     * @param dimensions   实际维度（取首条向量长度；空输入 → 0）
     * @param promptTokens 服务端上报的 prompt token 合计（多批次累加；未上报 → 0）
     * @param batches      实际发起的批次数
     */
    record Result(List<float[]> vectors, String model, int dimensions, long promptTokens, int batches) {
    }

    /**
     * 向量化一批文本。
     *
     * @param config    provider 运行时配置（baseUrl + 明文 key + 自定义头）
     * @param modelName 模型名（原样发给服务端，例如 {@code text-embedding-v4}）
     * @param inputs    文本列表（非空；调用方负责空值/长度校验）
     * @param dimensions 目标维度（null/≤0 → 不发送该字段，用服务端默认）
     * @param batchSize 单请求最大条数（≤0 → 10；实现方按上限裁剪）
     * @return 向量结果（顺序与 inputs 一致）
     */
    Result embed(ProviderConfig config, String modelName, List<String> inputs,
                 Integer dimensions, int batchSize);
}
