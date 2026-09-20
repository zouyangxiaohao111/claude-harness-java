package com.nexusai.model.session.dto;

/**
 * POST /api/v1/sessions/{sessionId}/queue/pop 请求体 · 对齐 CC
 * {@code popAllEditable(currentInput, currentCursorOffset)}
 * （utils/messageQueueManager.ts:428-429）的 <b>currentInput</b> 实参。
 *
 * <p><b>WHY（为什么必须由前端传入，而不是后端用空串）</b>：CC 的回填文本是
 * {@code [...queuedTexts, currentInput].filter(Boolean).join('\n')}
 * （messageQueueManager.ts:455-456）—— 用户已经敲在输入框里但还没发的草稿<b>也是回填的一部分</b>
 * （CC 的 pop 是「把排队项接在草稿前面」，不是「用排队项覆盖草稿」）。若后端用空串代替，
 * 用户按 Esc 拉回排队消息时，<b>输入框里原有的草稿会被静默清掉</b> —— 即把「N-1 条排队消息静默消失」
 * 换成「草稿静默消失」，缺陷只是换了个位置。故本字段由前端（{@code App.tsx} 的 {@code composerText}）
 * 显式携带。
 *
 * <p><b>可空语义</b>：请求体整体可缺省（{@code @RequestBody(required=false)}，旧客户端/裸 POST 仍可用）
 * ⇒ {@code currentInput=null} 视同空串（不参与 join，与 CC {@code filter(Boolean)} 同款：
 * 空串/无草稿在结果文本里不留空行）。{@code cursorOffset} 本仓不承载（web 输入框无 CC 终端的光标
 * 偏移还原语义，前端 setComposerText 后由 textarea 自行定位），故不设该字段。
 *
 * @param currentInput 用户当前未发送的输入框草稿（null/空 = 无草稿）
 */
public record QueuePopRequest(
    String currentInput
) {}
