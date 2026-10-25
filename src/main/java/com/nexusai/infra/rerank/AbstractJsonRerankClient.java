package com.nexusai.infra.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.ProviderHeaderInjector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 重排客户端<b>模板基类</b> · 泛型 {@code <REQ, RESP>} 承载厂商各自的 wire schema。
 *
 * <p><b>模板方法</b>（{@link #rerank}，final）固定六步：
 * 配置校验 → {@link #buildRequest} → {@link #endpoint} → {@link #toJson} → HTTP POST →
 * {@link #fromJson} → {@link #parse}。子类只实现这 5 个映射点，<b>不碰</b> HTTP/鉴权/超时/错误包装。
 *
 * <p><b>为什么用泛型</b>：各厂商请求/响应结构不同（扁平 vs 嵌套、字段名不同）。把 wire 类型
 * 作为泛型参数，子类可以用自己的 record/DTO 建模，而调用方始终只看到
 * {@link RerankCommand} → {@link RerankResult}。
 *
 * <p><b>日志纪律</b>：只打印协议 / 模型 / 候选数 / topN / 返回数 / token / 耗时；
 * ⛔ 不打印查询与文档正文、不打印 API key。
 */
public abstract class AbstractJsonRerankClient<REQ, RESP> implements RerankClient {

    private static final Logger log = LoggerFactory.getLogger(AbstractJsonRerankClient.class);
    protected static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build();

    // ── 厂商实现点 ──────────────────────────────────────────────────────

    /** 规范请求 → 厂商请求对象。 */
    protected abstract REQ buildRequest(RerankCommand command);

    /** 厂商端点（由子类按 provider 配置推导）。 */
    protected abstract String endpoint(ProviderConfig config);

    /** 厂商请求对象 → JSON body。 */
    protected abstract ObjectNode toJson(REQ request);

    /** 响应 JSON → 厂商响应对象（无独立建模时可原样返回 JsonNode）。 */
    protected abstract RESP fromJson(JsonNode node);

    /** 厂商响应对象 → 规范结果。 */
    protected abstract RerankResult parse(RESP response, RerankCommand command);

    // ── 模板方法（final：子类不得改写 HTTP 生命周期）───────────────────

    @Override
    public final RerankResult rerank(ProviderConfig config, RerankCommand command) {
        if (config == null || !config.isUsable()) {
            throw new IllegalArgumentException(protocol() + " provider 未配置可用的 API key");
        }
        String url = endpoint(config);
        String body = toJson(buildRequest(command)).toString();

        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");
        // 自定义 header 先应用；Authorization 最后 setHeader（同名覆盖，避免追加出两个值）
        ProviderHeaderInjector.apply(request::header, config.extraHeaders(), null);
        request.setHeader("Authorization", "Bearer " + config.apiKey());
        request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                String detail = truncate(response.body());
                log.error("[{}] 重排调用失败: model={} HTTP={} url={} body={}",
                    protocol(), command.model(), response.statusCode(), url, detail);
                throw new IllegalStateException(
                    protocol() + " 重排调用失败 HTTP " + response.statusCode() + ": " + detail);
            }
            JsonNode node = JSON.readTree(response.body());
            RerankResult result = parse(fromJson(node), command);
            if (log.isInfoEnabled()) {
                log.info("[{}] 重排完成: model={} 候选={} topN={} 返回={} totalTokens={} 耗时={}ms",
                    protocol(), command.model(), command.documents().size(), command.topN(),
                    result.scores().size(), result.totalTokens(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            }
            return result;
        } catch (IllegalStateException e) {
            throw e; // 已带 HTTP/协议详情，直接上抛
        } catch (Exception e) {
            log.error("[{}] 重排调用异常: model={} url={} err={}",
                protocol(), command.model(), url, e.toString());
            throw new IllegalStateException(protocol() + " 重排调用失败: " + e.getMessage(), e);
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }
}
