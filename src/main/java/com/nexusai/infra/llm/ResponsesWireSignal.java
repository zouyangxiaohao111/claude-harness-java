package com.nexusai.infra.llm;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponsesServerEvent;

/**
 * Responses 流事件的传输无关形态（SSE {@link ResponseStreamEvent} 与 WebSocket
 * {@link ResponsesServerEvent} 共用一份消费逻辑）。
 *
 * <p><b>WHY 单源</b>：两条传输的事件字段几乎一一对应；若各写一套映射，新增/修正事件
 * （如 refusal / reasoning_text / output_text.done 兜底）必然只修一边 —— 本仓有过
 * 「同一能力两套判据」漂移的前科。
 */
final class ResponsesWireSignal {

    enum Kind {
        /** 正文增量。 */
        TEXT_DELTA,
        /** 思考摘要增量（reasoning_summary_text.delta）。 */
        REASONING_SUMMARY_DELTA,
        /** 原始思考增量（reasoning_text.delta）。 */
        REASONING_TEXT_DELTA,
        /** 拒答增量（refusal.delta）。 */
        REFUSAL_DELTA,
        /** 输出 item 开始（function_call 的 name/call_id 在此给出）。 */
        ITEM_ADDED,
        /** 工具参数增量。 */
        ARGS_DELTA,
        /** 工具参数完成。 */
        ARGS_DONE,
        /** 输出 item 完成。 */
        ITEM_DONE,
        /** 正文完成（兼容不发 delta 的端点：可整段补发）。 */
        TEXT_DONE,
        /** 正常完成。 */
        COMPLETED,
        /** 被截断（max_output_tokens 等）。 */
        INCOMPLETE,
        /** 失败。 */
        FAILED,
        /** 错误事件。 */
        ERROR,
        /** 未映射/未建模事件。 */
        IGNORED
    }

    final Kind kind;
    final String delta;
    final String itemId;
    final ResponseOutputItem item;
    final String arguments;
    final String text;
    final Response response;
    final String message;

    private ResponsesWireSignal(Kind kind, String delta, String itemId, ResponseOutputItem item,
                               String arguments, String text, Response response, String message) {
        this.kind = kind;
        this.delta = delta;
        this.itemId = itemId;
        this.item = item;
        this.arguments = arguments;
        this.text = text;
        this.response = response;
        this.message = message;
    }

    static ResponsesWireSignal ignored() {
        return new ResponsesWireSignal(Kind.IGNORED, null, null, null, null, null, null, null);
    }

    /** SSE 事件映射。 */
    static ResponsesWireSignal of(ResponseStreamEvent ev) {
        if (ev == null) {
            return ignored();
        }
        if (ev.isOutputTextDelta()) {
            var e = ev.asOutputTextDelta();
            return new ResponsesWireSignal(Kind.TEXT_DELTA, e.delta(), e.itemId(), null, null, null, null, null);
        }
        if (ev.isReasoningSummaryTextDelta()) {
            var e = ev.asReasoningSummaryTextDelta();
            return new ResponsesWireSignal(Kind.REASONING_SUMMARY_DELTA, e.delta(), e.itemId(), null, null, null, null, null);
        }
        if (ev.isReasoningTextDelta()) {
            var e = ev.asReasoningTextDelta();
            return new ResponsesWireSignal(Kind.REASONING_TEXT_DELTA, e.delta(), e.itemId(), null, null, null, null, null);
        }
        if (ev.isRefusalDelta()) {
            var e = ev.asRefusalDelta();
            return new ResponsesWireSignal(Kind.REFUSAL_DELTA, e.delta(), e.itemId(), null, null, null, null, null);
        }
        if (ev.isOutputItemAdded()) {
            return new ResponsesWireSignal(Kind.ITEM_ADDED, null, null, ev.asOutputItemAdded().item(), null, null, null, null);
        }
        if (ev.isFunctionCallArgumentsDelta()) {
            var e = ev.asFunctionCallArgumentsDelta();
            return new ResponsesWireSignal(Kind.ARGS_DELTA, e.delta(), e.itemId(), null, null, null, null, null);
        }
        if (ev.isFunctionCallArgumentsDone()) {
            var e = ev.asFunctionCallArgumentsDone();
            return new ResponsesWireSignal(Kind.ARGS_DONE, null, e.itemId(), null, e.arguments(), null, null, null);
        }
        if (ev.isOutputItemDone()) {
            return new ResponsesWireSignal(Kind.ITEM_DONE, null, null, ev.asOutputItemDone().item(), null, null, null, null);
        }
        if (ev.isOutputTextDone()) {
            var e = ev.asOutputTextDone();
            return new ResponsesWireSignal(Kind.TEXT_DONE, null, e.itemId(), null, null, e.text(), null, null);
        }
        if (ev.isCompleted()) {
            return new ResponsesWireSignal(Kind.COMPLETED, null, null, null, null, null,
                ev.asCompleted().response(), null);
        }
        if (ev.isIncomplete()) {
            return new ResponsesWireSignal(Kind.INCOMPLETE, null, null, null, null, null,
                ev.asIncomplete().response(), null);
        }
        if (ev.isFailed()) {
            return new ResponsesWireSignal(Kind.FAILED, null, null, null, null, null,
                ev.asFailed().response(), null);
        }
        if (ev.isError()) {
            return new ResponsesWireSignal(Kind.ERROR, null, null, null, null, null, null,
                ev.asError().message());
        }
        return ignored();
    }

    /** WebSocket 服务端事件映射（codex 未建模事件 → IGNORED）。 */
    static ResponsesWireSignal of(ResponsesServerEvent ev) {
        if (ev == null) {
            return ignored();
        }
        if (ev.responseOutputTextDelta().isPresent()) {
            var e = ev.responseOutputTextDelta().get();
            return new ResponsesWireSignal(Kind.TEXT_DELTA, e.delta(), e.itemId(), null, null, null, null, null);
        }
        if (ev.responseReasoningSummaryTextDelta().isPresent()) {
            var e = ev.responseReasoningSummaryTextDelta().get();
            return new ResponsesWireSignal(Kind.REASONING_SUMMARY_DELTA, e.delta(), e.itemId(), null, null, null, null, null);
        }
        if (ev.responseReasoningTextDelta().isPresent()) {
            var e = ev.responseReasoningTextDelta().get();
            return new ResponsesWireSignal(Kind.REASONING_TEXT_DELTA, e.delta(), e.itemId(), null, null, null, null, null);
        }
        if (ev.responseRefusalDelta().isPresent()) {
            var e = ev.responseRefusalDelta().get();
            return new ResponsesWireSignal(Kind.REFUSAL_DELTA, e.delta(), e.itemId(), null, null, null, null, null);
        }
        if (ev.responseOutputItemAdded().isPresent()) {
            return new ResponsesWireSignal(Kind.ITEM_ADDED, null, null,
                ev.responseOutputItemAdded().get().item(), null, null, null, null);
        }
        if (ev.responseFunctionCallArgumentsDelta().isPresent()) {
            var e = ev.responseFunctionCallArgumentsDelta().get();
            return new ResponsesWireSignal(Kind.ARGS_DELTA, e.delta(), e.itemId(), null, null, null, null, null);
        }
        if (ev.responseFunctionCallArgumentsDone().isPresent()) {
            var e = ev.responseFunctionCallArgumentsDone().get();
            return new ResponsesWireSignal(Kind.ARGS_DONE, null, e.itemId(), null, e.arguments(), null, null, null);
        }
        if (ev.responseOutputItemDone().isPresent()) {
            return new ResponsesWireSignal(Kind.ITEM_DONE, null, null,
                ev.responseOutputItemDone().get().item(), null, null, null, null);
        }
        if (ev.responseOutputTextDone().isPresent()) {
            var e = ev.responseOutputTextDone().get();
            return new ResponsesWireSignal(Kind.TEXT_DONE, null, e.itemId(), null, null, e.text(), null, null);
        }
        if (ev.responseCompleted().isPresent()) {
            return new ResponsesWireSignal(Kind.COMPLETED, null, null, null, null, null,
                ev.responseCompleted().get().response(), null);
        }
        if (ev.responseIncomplete().isPresent()) {
            return new ResponsesWireSignal(Kind.INCOMPLETE, null, null, null, null, null,
                ev.responseIncomplete().get().response(), null);
        }
        if (ev.responseFailed().isPresent()) {
            return new ResponsesWireSignal(Kind.FAILED, null, null, null, null, null,
                ev.responseFailed().get().response(), null);
        }
        if (ev.error().isPresent()) {
            var err = ev.error().get().error();
            return new ResponsesWireSignal(Kind.ERROR, null, null, null, null, null, null,
                err == null ? "responses websocket error" : err.message());
        }
        return ignored();
    }
}
