package com.nexusai.infra.rerank;

import java.util.List;

/**
 * 重排请求的<b>厂商无关</b>规范形态 · 模板入口。
 *
 * <p>厂商实现负责把它翻译成自己的 wire schema（例如 OpenAI 兼容的扁平
 * {@code model/query/documents/top_n/instruct}，或 DashScope 原生的嵌套
 * {@code input{query,documents}} + {@code parameters}）。
 *
 * @param model     模型名
 * @param query     查询
 * @param documents 候选文档
 * @param topN      返回前 N 条（null → 不发送，服务端默认）
 * @param instruct  指令（可选，部分厂商支持）
 */
public record RerankCommand(String model, String query, List<String> documents,
                            Integer topN, String instruct) {
}
