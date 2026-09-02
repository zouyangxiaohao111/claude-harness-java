package com.nexusai.application.agent.team;

import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 消息折叠链 · 对齐 CC {@code components/Messages.tsx:520} 渲染链
 * {@code collapseBackgroundBashNotifications(collapseHookSummaries(
 *   collapseTeammateShutdowns(collapseReadSearchGroups(groupedMessages, tools))), verbose)}。
 *
 * <p><b>OPD-TP-07 / W8-04 接线</b>: 本类是 Java 侧出站 SDK/STOMP 消息组装点的折叠链唯一实现，
 * 在把消息列表推给前端前应用 collapseTeammateShutdowns 步骤 —— 连续 in-process teammate
 * shutdown task_status attachment 折叠为单条 {@code teammate_shutdown_batch}。CC 在
 * Messages.tsx:520 渲染链调用 {@code collapseTeammateShutdowns(...)}，Java 侧出站消息组装点
 * （ChatController GET /messages）经本链应用折叠步骤，消费侧（前端）不再收到连续 teammate
 * shutdown task_status attachment 洪泛。
 *
 * <p><b>R3 合一（WF-8 双实现漂移收口，规则七）</b>: 折叠算法本体原在 {@code CollapseTeammateShutdowns}
 * （已删除，MESSAGE 载体类同名实现被移除），本类为<b>唯一折叠实现</b> —— 折叠算法内联为本类私有
 * 方法，逐行对齐 CC {@code utils/collapseTeammateShutdowns.ts:3-55}；task_status 附件工厂
 * {@link #teammateTaskStatusAttachment} 对齐 CC {@code services/compact/compact.ts:1584-1596}
 * task_status attachment schema（{@code {type:'task_status', taskId, taskType, description,
 * status, deltaSummary, outputFilePath}}）。实际消费方（ChatController/AutonomousAgentLoop/测试）
 * 均消费本类，故保留本类、删除原算法宿主类。
 *
 * <p>CC 实际 TS 源码行为（utils/collapseTeammateShutdowns.ts:3-55，Messages.tsx:520）：
 * <ul>
 *   <li>isTeammateShutdownAttachment = type='attachment' && attachment.type='task_status'
 *       && taskType='in_process_teammate' && status='completed'</li>
 *   <li>连续命中 → 计数;单条保留原 attachment;多条 → {@code {type:'attachment',
 *       uuid, timestamp, attachment:{type:'teammate_shutdown_batch', count}}}</li>
 *   <li>非命中消息原样透传</li>
 * </ul>
 */
public final class TeammateMessageFoldingChain {

    private static final Logger log = LoggerFactory.getLogger(TeammateMessageFoldingChain.class);

    /** CC AttachmentMessage.type 常量（collapseTeammateShutdowns.ts:8） */
    private static final String TYPE_ATTACHMENT = "attachment";
    /** CC attachment.type 'task_status' */
    private static final String ATTACHMENT_TASK_STATUS = "task_status";
    /** CC taskType 'in_process_teammate' */
    private static final String TASK_TYPE_IN_PROCESS_TEAMMATE = "in_process_teammate";
    /** CC status 'completed'（collapseTeammateShutdowns.ts:10） */
    private static final String STATUS_COMPLETED = "completed";
    /** CC 折叠产物 attachment.type（collapseTeammateShutdowns.ts:43） */
    private static final String ATTACHMENT_TEAMMATE_SHUTDOWN_BATCH = "teammate_shutdown_batch";

    private TeammateMessageFoldingChain() {
    }

    /**
     * 折叠消息列表 — 对齐 CC Messages.tsx:520 链中 collapseTeammateShutdowns 步骤
     * （collapseTeammateShutdowns.ts:24-51 折叠算法本体，内联自原 CollapseTeammateShutdowns）。
     *
     * <p>输入为 Java 出站消息 DTO（ChatMessageDto，author='attachment' 且 subtype='task_status'
     * 的即为 CC task_status attachment 对应物）。逐条判定 isTeammateShutdown，连续命中按
     * CC 计数折叠为 teammate_shutdown_batch；单条保留原消息；其余透传保序。
     *
     * @param outbound 出站消息列表（可空 → 空列表）
     * @return 折叠后的消息列表（纯函数，不修改入参）
     */
    public static List<ChatMessageDto> collapse(List<ChatMessageDto> outbound) {
        if (outbound == null || outbound.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessageDto> result = new ArrayList<>(outbound.size());
        int i = 0;
        while (i < outbound.size()) {
            ChatMessageDto msg = outbound.get(i);
            if (isTeammateShutdownAttachment(msg)) {
                int count = 0;
                while (i < outbound.size() && isTeammateShutdownAttachment(outbound.get(i))) {
                    count++;
                    i++;
                }
                if (count == 1) {
                    result.add(msg);
                } else {
                    // CC :38-46 折叠产物：attachment/uuid/timestamp + attachment{type:'teammate_shutdown_batch',count}
                    result.add(buildBatchMessage(msg, count));
                }
            } else {
                result.add(msg);
                i++;
            }
        }
        if (log.isDebugEnabled() && result.size() != outbound.size()) {
            log.debug("TeammateMessageFoldingChain: 折叠完成 {} → {} 条（连续 teammate shutdown 合并）",
                outbound.size(), result.size());
        }
        return result;
    }

    /**
     * 判定单条消息是否为 CC isTeammateShutdownAttachment
     * （collapseTeammateShutdowns.ts:3-12: type='attachment' && attachment.type='task_status'
     * && taskType='in_process_teammate' && status='completed'）。
     *
     * <p>Java 载体：ChatMessageDto author='attachment' 且 subtype='task_status' 的附件消息；
     * taskType/status 从 content 内联 JSON payload 解析（TaskStatusView）。解析失败保守返回
     * false（不误折叠）。
     */
    private static boolean isTeammateShutdownAttachment(ChatMessageDto msg) {
        if (msg == null || msg.author() == null) {
            return false;
        }
        if (!TYPE_ATTACHMENT.equals(msg.author())) {
            return false;
        }
        // ChatMessageDto 的 subtype 即 CC attachment.type 判别位（IMP-05 契约，见 ChatMessageDto Javadoc）
        if (!ATTACHMENT_TASK_STATUS.equals(msg.subtype())) {
            return false;
        }
        TaskStatusView view = TaskStatusView.parse(msg);
        return view != null
            && TASK_TYPE_IN_PROCESS_TEAMMATE.equals(view.taskType)
            && STATUS_COMPLETED.equals(view.status);
    }

    /** 折叠产物消息：保留原 attachment 消息的 id/sessionId/timestamp，attachment 换为 batch。 */
    private static ChatMessageDto buildBatchMessage(ChatMessageDto first, int count) {
        String payload = "{\"type\":\"teammate_shutdown_batch\",\"count\":" + count + "}";
        return new ChatMessageDto(
            first.id(),
            first.sessionId(),
            first.role(),
            TYPE_ATTACHMENT,
            payload,
            first.reasoning(),
            first.toolCalls(),
            first.finishReason(),
            first.inputTokens(),
            first.outputTokens(),
            first.time(),
            first.createdAt(),
            first.toolCallId(),
            first.assistantMessageId(),
            first.acceptFeedback(),
            first.contentBlocks(),
            first.imagePasteIds(),
            first.structuredOutput(),
            first.isMeta(),
            first.isError(),
            first.sourceToolUseID(),
            ATTACHMENT_TEAMMATE_SHUTDOWN_BATCH,
            first.isApiErrorMessage(),
            first.apiError(),
            first.error(),
            first.errorDetails(),
            first.usage()); // DEC-04 usage 透传（attachment 消息保留源消息 usage）
    }

    /**
     * 生成 teammate 完成 task_status attachment 消息 · 对齐 CC compact.ts:1584-1596
     * {@code {type:'task_status', taskId, taskType, description, status, deltaSummary, outputFilePath}}。
     *
     * <p><b>W8-04 完成通知链</b>: teammate 终端转换（completed/failed/killed）产出本 attachment，
     * 进入出站消息列表 → 折叠链 {@link #collapse} 判定（taskType='in_process_teammate' &&
     * status='completed'）→ 连续折叠。CC 渲染层 TeammateTaskStatus 依赖 task_status attachment
     * 展示「Teammate @agentName shut down gracefully」。
     *
     * @param taskId     teammate 任务 id（CC original: attachment.taskId, compact.ts:1587）
     * @param agentName  teammate agent 名（description 语义, compact.ts:1589）
     * @param status     终态 'completed' | 'failed' | 'killed'
     * @param sessionId  会话 id（透传定位 transcript）
     * @return author='attachment' + subtype='task_status' 的 ChatMessageDto
     */
    public static ChatMessageDto teammateTaskStatusAttachment(String taskId, String agentName,
                                                              String status, String sessionId) {
        String payload = "{\"type\":\"task_status\",\"taskId\":\"" + taskId
            + "\",\"taskType\":\"in_process_teammate\",\"description\":\"" + agentName
            + "\",\"status\":\"" + status + "\",\"deltaSummary\":null,\"outputFilePath\":null}";
        return new ChatMessageDto(
            null, sessionId, null, TYPE_ATTACHMENT, payload, null,
            null, null, null, null, "刚刚", null,
            null, null, null, null, null, null, false, false,
            null, ATTACHMENT_TASK_STATUS, false, null, null, null,
            null); // DEC-04 usage（attachment 消息无 usage）
    }

    /**
     * task_status attachment 载荷视图 — 从 ChatMessageDto 附件消息解析 taskType/status。
     *
     * <p>Java 端 task_status attachment 两种载体：
     * <ol>
     *   <li>{@link com.nexusai.application.agent.attachment.AttachmentMessageDto.TaskStatusRef}
     *       （强类型，compact 恢复路径）——若 content 为结构化 JSON 可尝试解析</li>
     *   <li>内联 JSON payload（PostCompactAttachmentRestorer.asyncAgentAttachments /
     *       {@link #teammateTaskStatusAttachment} 产物：{@code {"type":"task_status","taskId":...,
     *       "taskType":"in_process_teammate","description":...,"status":"completed",...}}）</li>
     * </ol>
     * 解析失败返回 null。
     */
    private static final class TaskStatusView {
        final String taskType;
        final String status;

        TaskStatusView(String taskType, String status) {
            this.taskType = taskType;
            this.status = status;
        }

        static TaskStatusView parse(ChatMessageDto msg) {
            String content = msg.content();
            if (content == null) {
                return null;
            }
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(content);
                if (node == null || !node.isObject()) {
                    return null;
                }
                String tt = node.path("taskType").asText(null);
                String st = node.path("status").asText(null);
                if (tt == null || st == null) {
                    return null;
                }
                return new TaskStatusView(tt, st);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
