package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ToolUseBlock;

/**
 * 单个 tool_call 的累积上下文。
 *
 * <p>OpenAI 协议中 tool_call 是分块到达的：
 * 第一块给 id/type/name，后续块只给 arguments 的部分（JSON 字符串按字符拼）。
 *
 * <p>由 {@link OpenAiSdkProvider} 共享。
 */
public class OpenAiToolCallAccumulator {

    /**
     * [fix-toolcall-fault A] 幽灵 tool_call 槽的哨兵名 · 名字形态与 CC 的
     * {@code No such tool available: {name}} 错误串兼容（OpenAI 工具名合法字符集
     * {@code [a-zA-Z0-9_-]} 内），且不会与任何真实工具名冲突。
     */
    public static final String MALFORMED_TOOL_CALL_NAME = "__malformed_tool_call__";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** fail-loud 日志（_raw 兜底 2026-09-04：非法 arguments 记录含工具名/长度/异常）· slf4j（CLAUDE.md 规则）。 */
    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(OpenAiToolCallAccumulator.class);

    public int index;
    public String id;      // 第一块设定
    public String type;    // 通常 "function"
    public String name;    // 第一块设定
    public String args = "";   // 跨块拼字符串

    public ToolUseBlock toBlock() {
        JsonNode input;
        try {
            input = (args == null || args.isEmpty())
                ? JSON.createObjectNode()
                : JSON.readTree(args);
        } catch (Exception e) {
            // arguments 不是合法 JSON（openai 兼容超长参数漏转义，2026-09-04 事故）→ 记录 fail-loud，
            //   包 {_raw: 原文}（执行层 StreamingToolExecutor 拦截并引导模型拆小，见其 _raw 拦截块）。
            log.warn("OpenAiToolCallAccumulator: tool_call arguments 非法 JSON（readTree 失败）"
                    + "name={} len={} err={} → _raw 兜底", name,
                args == null ? 0 : args.length(), e.toString());
            ObjectNode wrapper = JSON.createObjectNode();
            wrapper.put("_raw", args == null ? "" : args);
            input = wrapper;
        }
        // [fix-toolcall-fault A] 幽灵 tool_call 槽容错（name 空）· 对齐 CC 同形：
        //   CC 对 name 原样拷贝不校验（claude.ts:1995-2000），坏 name 由工具层容错 ——
        //   StreamingToolExecutor.ts:77-102 / toolExecution.ts:369-410 查不到工具 → 产 is_error 的
        //   tool_result「No such tool available: {name}」→ 回合继续（回喂模型自纠）。
        //   ⛔ 本仓不能原样放行空 name：空 name 的 tool_call 在回放侧被丢弃
        //   （OpenAiSdkProvider:1030-1033 跳过 id/name 空的 tool_call）→ 后续 tool 消息无配对
        //   → OpenAI 400「must be followed by tool messages」。⇒ 映射哨兵名，走上同一条
        //   「No such tool available」错误通道，且回放侧 name 合法可配对。
        //   ⛔ 不在这里抛（原行为：ToolUseBlock.name.isBlank() → IllegalArgumentException →
        //   OpenAiSdkProvider:344 onAssistantMessage → :359 catch → onError → LlmAgentLoop
        //   判不可重试 → STREAM_ERROR ⇒ 整个回合死 = 用户报障「子代理没干成活」的根因）。
        String effectiveName = name;
        if (effectiveName == null || effectiveName.isBlank()) {
            log.warn("OpenAiToolCallAccumulator: 幽灵 tool_call 槽（name 空）→ 哨兵名兜底 "
                    + "index={} id={} argsLen={} → 工具层将回 No such tool available（可观测，"
                    + "上游系统性退化不得静默）",
                index, id, args == null ? 0 : args.length());
            effectiveName = MALFORMED_TOOL_CALL_NAME;
        }
        return new ToolUseBlock(id, effectiveName, input);
    }

    /**
     * 等 args JSON 能 parse 成 object 即返回 true（含空对象 {@code {}}）。
     *
     * <p>对齐 AnthropicSdkProvider:2813 宽松语义（id+name 非空即 complete）+ CC 无"参数非空"要求：
     * 模型产出无参工具调用时 arguments 为 "{}"（或 ""），是合法完整调用 → onToolCallComplete 必须
     * 触发，否则空参工具永不进执行器（OpenAI 400 "insufficient tool messages following tool_calls"
     * 根因 1.1）。原实现 {@code parsed.size() > 0} 把空参工具恒判为不完整，卡死整个混合批。
     *
     * <p>注意：arguments="" 时 readTree("") 抛异常 → 仍返回 false。这是有意的——流式 with-args 工具
     * 的 chunk1 就是 arguments:""，不能把 "" 直接视为完整（会提前把带参工具回调成空参）；该场景由
     * OpenAiSdkProvider.parseChunk 的 finish_reason 流结束补发处理（fix-toolcalls-400 A-2）。
     *
     * <p>[fix-toolcall-fault A] 身份判据口径统一：id/name 用 {@code isBlank()}，与消费端
     * {@link ToolUseBlock} 构造器（:18-23 {@code isBlank()}）逐字对齐 —— 原 {@code isEmpty()}
     * 会放行纯空白 name（如 "   "）进入 toBlock() 并触发其校验异常（口径不一致的洞）。
     */
    public boolean isComplete() {
        if (id == null || id.isBlank() || name == null || name.isBlank() || args == null) {
            return false;
        }
        try {
            JsonNode parsed = JSON.readTree(args);
            return parsed.isObject();
        } catch (Exception e) {
            return false;
        }
    }
}
