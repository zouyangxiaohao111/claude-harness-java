package com.nexusai.model.session.dto;

/**
 * assistant 内容块（协议中立）· 对齐 Claude Code transcript 的「一块一行 + 索引」模型。
 *
 * <p><b>语义</b>：一条 assistant 消息在 API 响应里是 content 块数组
 * （text / thinking / tool_use）。CC transcript 把同一 message.id 拆成 N 行，每行只带一个块并附
 * apiBlockIndex（该块在 API content 数组中的下标）；渲染时相邻 tool_use 合并、连续 text 合并。
 * 本 record 是该结构在 Java 侧的中立载体。
 *
 * <p><b>为什么中立</b>：本 record 只服务「落库 / 渲染 / 顺序还原」，
 * ⛔ 绝不作为任一 provider 的请求体：
 * <ul>
 *   <li>Anthropic messages：出站仍由 AnthropicSdkProvider 组装 content 数组
 *       （thinking → text → tool_use，来自 reasoning/thinkingSignature/content/toolCalls）；</li>
 *   <li>OpenAI Chat Completions：出站仍是 content 字符串 + tool_calls 数组 + role=tool 结果；</li>
 *   <li>OpenAI Responses：出站仍是 output items（message/output_text、function_call + call_id、
 *       function_call_output、reasoning/summary）。</li>
 * </ul>
 *
 * <p><b>字段</b>（按类型惰性有值，其余为 null）：
 * <ul>
 *   <li>{@code type}：{@code text} / {@code thinking} / {@code tool_use}（扩展类型原样透传）</li>
 *   <li>{@code text}：text 块正文</li>
 *   <li>{@code thinking} / {@code signature}：thinking 块正文与上游签名（signature 可 null）</li>
 *   <li>{@code id} / {@code name} / {@code input}：tool_use 块的调用 id、工具名、入参 JSON 文本</li>
 *   <li>{@code apiBlockIndex}：该块在 API content 数组中的下标（同一条 assistant 消息内连续）</li>
 * </ul>
 *
 * <p>Jackson 反序列化走 canonical 构造器；旧数据缺键时为 null（容错）。
 */
public record ContentBlockDto(
    String type,
    String text,
    String thinking,
    String signature,
    String id,
    String name,
    String input,
    Integer apiBlockIndex
) {
    /** text 块（apiBlockIndex 由装配器按到场顺序分配）。 */
    public static ContentBlockDto text(String text, int apiBlockIndex) {
        return new ContentBlockDto("text", text, null, null, null, null, null, apiBlockIndex);
    }

    /** thinking 块（signature 可 null）。 */
    public static ContentBlockDto thinking(String thinking, String signature, int apiBlockIndex) {
        return new ContentBlockDto("thinking", null, thinking, signature, null, null, null, apiBlockIndex);
    }

    /** tool_use 块（input 为入参 JSON 文本，与 ToolCallDto.arguments 同形）。 */
    public static ContentBlockDto toolUse(String id, String name, String input, int apiBlockIndex) {
        return new ContentBlockDto("tool_use", null, null, null, id, name, input, apiBlockIndex);
    }
}
