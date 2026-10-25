package com.nexusai.infra.rerank;

import com.nexusai.infra.llm.ProviderConfig;

/**
 * 重排客户端 · <b>厂商无关的门面接口</b>。
 *
 * <p><b>为什么需要模板/泛型</b>（用户 2026-10-26 裁定）：各厂商的重排 schema 并不统一——
 * OpenAI/Cohere 兼容是扁平 {@code {model,query,documents,top_n}}，DashScope 原生是嵌套
 * {@code {input:{query,documents},parameters:{top_n}}}。调用方（{@code RerankService}）不应该
 * 感知这些差异，所以：
 * <ul>
 *   <li>本接口只暴露<b>规范形态</b>（{@link RerankCommand} → {@link RerankResult}）；</li>
 *   <li>具体厂商继承 {@link AbstractJsonRerankClient}（泛型 {@code <REQ, RESP>}），
 *       只实现「请求对象 → JSON」「JSON → 响应对象」「响应对象 → 规范结果」三段映射；</li>
 *   <li>HTTP、鉴权头、超时、错误包装、日志全部由基类统一处理。</li>
 * </ul>
 *
 * <p><b>路由</b>：一个 provider 只应命中一个客户端。{@link #supports(ProviderConfig)} 由各实现
 * 声明自己的适用范围；调用方命中 0 个或多个时 fail loud（⛔ 不猜）。
 */
public interface RerankClient {

    /** 协议标识（日志/诊断用，如 {@code openai-compatible-rerank}）。 */
    String protocol();

    /**
     * 是否能处理该 provider 配置。
     *
     * <p>默认实现返回 true（单实现时的通用兼容客户端）；未来厂商专用实现可收窄
     * （例如按 baseUrl 域名判定），调用方负责保证互斥。
     *
     * @param config provider 运行时配置
     * @return true = 可处理
     */
    default boolean supports(ProviderConfig config) {
        return true;
    }

    /**
     * 执行重排（模板入口 · 厂商无关）。
     *
     * @param config  provider 运行时配置
     * @param command 规范请求
     * @return 规范结果
     */
    RerankResult rerank(ProviderConfig config, RerankCommand command);
}
