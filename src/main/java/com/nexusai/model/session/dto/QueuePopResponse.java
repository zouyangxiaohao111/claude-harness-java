package com.nexusai.model.session.dto;

import java.util.List;

/**
 * POST /api/v1/sessions/{sessionId}/queue/pop 响应 · 对齐 CC
 * {@code PopAllEditableResult}（utils/messageQueueManager.ts:415-419
 * {@code {text, cursorOffset, images}}）的 web 等价物。
 *
 * <p><b>为什么不是旧的 {@code Map<String,String> {content}} 契约</b>：旧契约只有一个
 * {@code content} 槽位，服务端「移除全部可编辑项，只回填最旧一条」时另 N-1 条
 * <b>不回填、不落库、不重投、不报错 ⇒ 静默消失</b>；且没有承载图片的位置 ⇒ 排队消息的附件
 * 也一并丢失。本 DTO 把 CC 的 {@code text + images} 两路都出站。
 *
 * <p><b>字段</b>：
 * <ul>
 *   <li>{@link #text()} —— 全部可编辑排队项文本 + 当前输入，{@code \n} join（CC
 *       messageQueueManager.ts:455-456 逐字：{@code [...queuedTexts, currentInput].filter(Boolean).join('\n')}）；
 *       无可弹出项时为空串</li>
 *   <li>{@link #attachments()} —— 全部可弹出项的附件（{@code QueueItem.attachments}，
 *       即 {@code ChatService.busyQueuedResolvedAttachments} 已通过三道门校验/解析的形态：
 *       图片含纯 base64、上传/本地路径腿含 contentId），按弹出顺序展开；无附件 = 空列表
 *       （CC messageQueueManager.ts:463-478 收集 pastedContents 里的 image + value 内嵌 image 块）</li>
 * </ul>
 *
 * <p><b>不复用 CC 的 {@code cursorOffset}</b>：web 前端是受控 textarea，{@code setComposerText}
 * 后光标位置由浏览器决定，无 CC 终端的偏移还原需求（承载它反而是死字段）。
 *
 * @param text        回填文本（恒非 null；无可弹出项 = ""）
 * @param attachments 回传附件（恒非 null；元素复用 {@link AttachmentRequest}，可原样重投）
 */
public record QueuePopResponse(
    String text,
    List<AttachmentRequest> attachments
) {
    /** 空结果（无可编辑排队项 / 队列未接线）· CC {@code popAllEditable} 返回 {@code undefined} 的等价物。 */
    public static QueuePopResponse empty() {
        return new QueuePopResponse("", List.of());
    }
}
