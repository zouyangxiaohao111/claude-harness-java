package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工具调用进度事件 · CC original: {@code bash_progress}（BashTool.tsx:663-677
 * {@code onProgress({toolUseID: 'bash-progress-N', data: {type:'bash_progress', output, fullOutput,
 * elapsedTimeSeconds, totalLines, totalBytes, taskId, timeoutMs}})}）。
 *
 * <p>生产接线（G5-4 遗留）：BashTool 前台命令运行超 {@code PROGRESS_THRESHOLD_MS}=2s 阈值后，
 * 每秒 tick 发射一条进度事件到会话级流 topic {@code /topic/sessions/{sessionId}/stream}
 * （与 {@link MessageToolCallEvent} / {@link MessageToolResultEvent} 同一会话单 topic）。
 *
 * <p>type = {@code tool_call_progress}（项目约定：Java STOMP 事件类型用 snake 段；CC 内层
 * {@code bash_progress} 语义对齐到跨工具统一命名，前端按 toolCallId 归组到工具卡片）。
 *
 * <p>字段对齐 CC payload：output / fullOutput / elapsedTimeSeconds / totalLines / totalBytes；
 * taskId/timeoutMs 缺省登记已知差异（前端经持久化消息承载 taskId）。
 *
 * <p>{@code userMessageId} 可为 null（瞬时进度事件，非消息落库载体；{@code @JsonInclude(NON_NULL)}
 * 序列化时省略）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolCallProgressEvent extends StreamEvent {

    private final String toolCallId;
    private final String output;
    private final String fullOutput;
    private final long elapsedTimeSeconds;
    private final long totalLines;
    private final long totalBytes;

    public ToolCallProgressEvent(String sessionId, String userMessageId, String toolCallId,
                                 String output, String fullOutput, long elapsedTimeSeconds,
                                 long totalLines, long totalBytes) {
        super("tool_call_progress", sessionId, userMessageId);
        this.toolCallId = toolCallId;
        this.output = output;
        this.fullOutput = fullOutput;
        this.elapsedTimeSeconds = elapsedTimeSeconds;
        this.totalLines = totalLines;
        this.totalBytes = totalBytes;
    }

    public String getToolCallId() { return toolCallId; }
    public String getOutput() { return output; }
    public String getFullOutput() { return fullOutput; }
    public long getElapsedTimeSeconds() { return elapsedTimeSeconds; }
    public long getTotalLines() { return totalLines; }
    public long getTotalBytes() { return totalBytes; }
}
