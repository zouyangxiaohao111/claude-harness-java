package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * countTokens 客户端策略接口 · 对齐 CC {@code countTokensWithFallback}
 * （Open-ClaudeCode/src/utils/analyzeContext.ts:77-109）。
 *
 * <p>两条计数路径（对齐 CC countTokensWithFallback 的两个调用场景）：
 * <ol>
 *   <li><b>逐 section 计数</b>（{@link #countTokens(String)}）：入参为单 user 消息、无 tools
 *       （analyzeContext.ts:301 {@code countTokensWithFallback([{role:'user',content}], [])}），
 *       用于 system prompt / memory 文件段；</li>
 *   <li><b>工具定义计数</b>（{@link #countTokensForTools(List)}）：入参为 tools 数组、无真实消息
 *       （analyzeContext.ts:250 {@code countTokensWithFallback([], toolSchemas)}），
 *       用于 built-in / MCP 工具段（CC countToolDefinitionTokens analyzeContext.ts:234-258）。</li>
 * </ol>
 *
 * <p>返回语义（CC number | null）：
 * <ul>
 *   <li>空内容/空工具 → {@code 0}（tokenEstimation.ts:127-130 countTokensWithAPI 短路）；</li>
 *   <li>API 成功 → {@code input_tokens}（tokenEstimation.ts:195）；</li>
 *   <li>API 失败 / {@code input_tokens} 非 number / model 或 config 不可得 → {@code null}
 *       （tokenEstimation.ts:189-199）。</li>
 * </ul>
 *
 * <p><b>[CC 对照 · 已核 2.1.284 发行产物]</b> 对齐目标 = <b>CC 2.1.284</b>（本机
 * {@code C:/Users/WIN/AppData/Roaming/npm/node_modules/@anthropic-ai/claude-code/bin/claude.exe}，
 * 246,480,032 字节，exe 内嵌 {@code // Version: 2.1.284} 且与同包 package.json 一致）。
 * 关于「API 计数失败之后会怎样」，核过的事实（本类旧注释称 CC 靠 <b>Haiku</b> 兜底，已失真）：
 * <ul>
 *   <li>CC 在 {@code analyzeContext} 侧对主调用包了一层 {@code ebt(主调用, 本地估算器)}：
 *       主调用返回 {@code null} 或抛错时，记日志
 *       {@code "analyzeContext: count unavailable, estimating locally"}，然后改走
 *       <b>本地估算器</b>（{@code eZn} 内联的 {@code h}：逐消息 {@code Xte(content, tokenizer)}
 *       求和）。⇒ <b>通用兜底是本地估算，不是 Haiku</b>。</li>
 *   <li>唯一沾模型的是一条<b>很窄的特例</b>：仅当确实在用网关（{@code gatewayAuth()} 非空）
 *       且 count_tokens 回 <b>HTTP 501</b> 时，才走 {@code pAn}（"gateway sample count"）——
 *       以 small/fast 模型（{@code ANTHROPIC_SMALL_FAST_MODEL}，缺省回落到
 *       {@code ANTHROPIC_DEFAULT_HAIKU_MODEL} 即 Haiku；Vertex/Bedrock 分支换 Sonnet）发一次
 *       {@code messages.create}，读 {@code usage.input_tokens}。它<b>不</b>是通用兜底路径。</li>
 *   <li><b>[有意偏离 CC]</b> 本仓 Java 端不复制这层兜底，两个理由：① Java 的 anthropic 客户端
 *       没有 Haiku 兜底通道；② 更要紧的是，CC 那种「API 失败后静默换成估算值」会让调用方
 *       分不清「精确」与「估算」—— 正是本批次要消灭的。本仓把估算做成<b>另一条显式路径</b>
 *       （{@link OpenAICountTokensClient} 的 tiktoken，来源标 {@link TokenSource#ESTIMATE}），
 *       API 路径失败则如实返回 {@code null}（来源标 {@link TokenSource#UNAVAILABLE}），
 *       由 {@link TokenSource} 四态把两者区分开。</li>
 * </ul>
 * ⚠️ minify 名（{@code eZn} / {@code ebt} / {@code pAn}）随 CC 构建漂动，换版本后须按语义重核。
 *
 * <p><b>⛔ 调用方不得再把 {@code null} 抹成 0</b>：CC 在消费侧写的是 {@code tokens||0}
 * （analyzeContext.ts:308），那会把「算不出来」与「真 0」在 wire 上塌成同一个字节，前端无从标注。
 * Java 端由 {@link TokenSource#of(Integer, TokenSource)} 统一把 {@code null} 记成
 * {@link TokenSource#UNAVAILABLE} 并<b>保留 null 数值</b>（三处 collapse 点已改）。</p>
 *
 * <p><b>RES-C9 变更</b>：原 @FunctionalInterface 升级为普通接口（新增 countTokensForTools 默认方法），
 * 既有 lambda 消费方（如 SystemPromptTokenCounter 测试的 {@code content -> 5}）不受影响
 * （单抽象方法 + 默认方法仍兼容 lambda）。
 */
public interface CountTokensClient {

    /**
     * 单 section 内容 token 计数 · CC original: countTokensWithFallback([{role:'user',content}], [])
     * （analyzeContext.ts:301）。
     *
     * @param content section 内容（非空；空 → 0 短路）
     * @return token 数或 null（<b>null 必须原样向上带出</b>，语义 = 算不出来 / 不可用；
     *         调用方不得按 0 处理，见 {@link TokenSource#of(Integer, TokenSource)}）
     */
    Integer countTokens(String content);

    /**
     * 本客户端产生数值的<b>来源类别</b>（{@link TokenSource#API} 真实端点 /
     * {@link TokenSource#ESTIMATE} 本地估算）。
     *
     * <p>默认 {@link TokenSource#ESTIMATE}：未知实现一律按「估算」标注 —— 保守方向 = 宁可少标
     * 精度、绝不虚报精度（把估算标成精确是「自信的错标」，把精确标成估算是无害的保守）。
     * {@link AnthropicCountTokensClient} 覆写为 {@link TokenSource#API}，
     * {@link OpenAICountTokensClient} 显式覆写为 {@link TokenSource#ESTIMATE}（自证意向）。
     *
     * <p>⛔ 不得由前端按 provider 类型推测（bean 构造期求值一次，切 provider 后不重建；
     * 且 anthropic 兼容第三方恰恰是没有该端点那批）。
     *
     * @return 该客户端数值的来源类别
     */
    default TokenSource sourceKind() {
        return TokenSource.ESTIMATE;
    }

    /**
     * 工具定义 token 计数 · CC original: countTokensWithFallback([], toolSchemas)
     * （analyzeContext.ts:250 countToolDefinitionTokens :234-258）。
     *
     * <p>tools 数组随请求发送（tokenEstimation.ts:172-187 请求体 {model, messages:[dummy], tools:[...]}），
     * 非把 schema 序列化为消息文本。默认实现返回 0（未知工具或无 tools 通道时调用方记 0，
     * analyzeContext.ts:257 {@code result ?? 0}）。
     *
     * <p><b>TOOL_TOKEN_COUNT_OVERHEAD 补偿</b>：API 返回的 input_tokens 包含约 500 token 的工具前缀
     * 开销（analyzeContext.ts:68-75），补偿逻辑由<b>调用方</b>（ContextAnalyzeService.countToolDefinitionTokens）
     * 按 {@code Math.max(0, raw - 500)} 扣减（analyzeContext.ts:479/:638-641），本方法返回原始值。
     *
     * @param tools 工具 schema 列表（CC toolToAPISchema 产物；空 → 0）
     * @return token 数（含 overhead）或 0（默认实现，无 tools 通道）；<b>null = 算不出来</b>，
     *         调用方必须原样带出为不可用（不得扣成 {@code Math.max(0, 0-500)=0} 的假 0）
     */
    default Integer countTokensForTools(List<ToolSchema> tools) {
        return 0;
    }

    /**
     * 工具 schema · CC original: toolToAPISchema 产物（name/description/input_schema，
     * 用于 countTokensWithFallback([], toolSchemas) analyzeContext.ts:250 请求体 tools 数组元素）。
     *
     * @param name        工具名（CC original: tool.name → toolToAPISchema name）
     * @param description 工具描述（CC original: toolToAPISchema description，允许 null/空）
     * @param inputSchema 输入 schema（CC original: toolToAPISchema input_schema；null → 空 object）
     */
    record ToolSchema(String name, String description, JsonNode inputSchema) {
    }
}
