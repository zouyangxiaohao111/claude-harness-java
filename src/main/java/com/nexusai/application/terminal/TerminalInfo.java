package com.nexusai.application.terminal;

/**
 * 终端出站描述（REST {@code GET/POST /api/v1/sessions/{sessionId}/terminals} 的响应元素）。
 *
 * @param id        终端 ID（{@code term-xxxxxxxx}）
 * @param shell     实际启动的 shell（可执行文件路径或 PATH 名，白名单内的一个）
 * @param cwd       启动工作目录（会话项目目录；解析失败时回落 {@code user.home}，见 TerminalService）
 * @param cols      当前列数
 * @param rows      当前行数
 * @param alive     进程是否存活（{@code false} = 已退出/已关闭；缓冲可能仍可读，见 TerminalService 口径）
 * @param startedAt 启动时刻（ISO-8601，{@link java.time.Instant#toString()}）
 */
public record TerminalInfo(String id, String shell, String cwd, int cols, int rows, boolean alive,
                           String startedAt) {
}
