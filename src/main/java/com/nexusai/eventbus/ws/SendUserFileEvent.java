package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SendUserFile 文件送达事件 · CC {@code SendUserFileTool.ts} call() 中 "bridge upload"
 * 交付语义的 Java web 等价物。
 *
 * <p><b>WHY（G32① 真行为补全）</b>: CC SendUserFileTool 在 stat 校验后，若 repl bridge 启用则经
 * {@code uploadBriefAttachment} 上传到 {@code /api/oauth/file_upload} 拿 {@code file_uuid}，
 * web viewer 经 {@code file_uuid} 下载（SendUserFileTool.ts:101-116）。Java web 端：后端即宿主
 * 服务器，文件已在该服务器文件系统，且工具运行方（web 会话）与文件读取方是同一进程——"发送文件
 * 给用户"的等价动作 = 经 WebSocket/STOMP 推送本事件（携带 {@code file_path + size + description}），
 * 前端收到即可渲染下载入口 / 通知用户文件已就绪。
 *
 * <p><b>topic</b>: {@code /topic/sessions/{sess-xxx}}（session-level topic，前端
 * {@code useChatSocket.ts:59} 的 bare-session-topic 订阅已覆盖，与
 * {@code SkillImprovementSuggestionEvent} 同通道；订阅处理器当前只分发
 * {@code skill_improvement.suggestion}，本事件分支属待前端对接项 —— 已登记 待前端对接.md）。
 *
 * <p><b>与 CC 差异登记（Java 等价通道）</b>:
 * <ul>
 *   <li>CC 交付 = bridge 上传 → {@code file_uuid}（web viewer 经 bridge 直接下载）；
 *       Java 交付 = STOMP 事件推送文件路径 → 前端经 {@code SendUserFileController#download}
 *       （{@code GET /api/v1/sessions/{sessionId}/send-user-file/download?path=filePath}）读取实际
 *       字节（Q3 返工 2026-08-23 已接线文件服务端点；本事件携带元数据 = 下载所需参数）。</li>
 *   <li>CC output {@code {sent, file_path, size, file_uuid?, error?}}（SendUserFileTool.ts:22）
 *       由工具结果（{@code ToolResult}）承载；本事件只携带前端展示所需字段。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SendUserFileEvent extends StreamEvent {

    /** 发送文件的绝对路径 · CC original: {@code file_path}（SendUserFileTool.ts:22）. */
    private final String filePath;

    /** 文件字节数 · CC original: {@code size}（SendUserFileTool.ts:22）. */
    private final long size;

    /** 可选文件说明 · CC original: {@code description}（SendUserFileTool.ts:13-16）. */
    private final String description;

    /**
     * @param sessionId   会话 UUID 字符串（{@code ToolUseContext.sessionId}）
     * @param filePath    待发送文件绝对路径
     * @param size        文件字节数
     * @param description 可选文件说明（可为 null）
     */
    public SendUserFileEvent(String sessionId, String filePath, long size, String description) {
        super("send_user_file", sessionId, null);
        this.filePath = filePath;
        this.size = size;
        this.description = description;
    }

    public String getFilePath() { return filePath; }
    public long getSize() { return size; }
    public String getDescription() { return description; }
}
