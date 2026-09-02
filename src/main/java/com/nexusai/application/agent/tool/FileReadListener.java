package com.nexusai.application.agent.tool;

/**
 * 文件读取监听器 · 对齐 CC {@code tools/FileReadTool/FileReadTool.ts:162-173 FileReadListener}.
 *
 * <p>对齐契约（L1 行为）：
 * <pre>
 * type FileReadListener = (filePath: string, content: string) => void
 * </pre>
 *
 * <p><b>[IMP-C5] FileReadEvent 退役</b>：Java 原把 {@code (filePath, content)} 扩成
 * {@link FileReadEvent}（带 mtime / callId 追踪字段）——CC 无此事件对象，删除扩展字段并
 * 直接对齐 CC 二元签名 {@code (filePath, content)}（TR-D1-⊕-2 拍板）。listener 收到的
 * {@code content} 为 <b>本次读取的 range 内容</b>（对齐 CC FileReadTool.ts:1040-1044
 * {@code listener(resolvedFilePath, content)}，content 为 readFileInRange 返回的窗口内容，
 * 非全文件；W6/R2 修复）。
 *
 * @see FileReadListenerRegistry
 */
@FunctionalInterface
public interface FileReadListener {

    /**
     * 文件被成功读取后调用。
     *
     * <p>CC 仅在 text 分支成功后通知（{@code FileReadTool.ts:1040-1044}）——
     * image / notebook / PDF / file_unchanged 分支不通知。
     *
     * <p><b>[L+ R2] 实现方自 catch 业务异常</b>：CC FileReadTool.ts:1042 是裸调用
     * （无 try/catch 隔离），一 listener 抛异常会中断后续 listener 投递并向上传播
     * 给 ReadFileTool 主流程。对齐 CC fail-fast 哲学，{@link FileReadListenerRegistry}
     * 不再 catch 隔离 listener 异常。实现方应：
     * <ul>
     *   <li><b>必须</b>在 onFileRead 内部 try/catch 自己的业务异常，避免炸主流程
     *       （ReadFileTool.execute 会因 listener 异常而失败 → 整个 tool call 中断）</li>
     *   <li>避免同步阻塞——listener 跑长任务应 fork 异步线程</li>
     *   <li>异常隔离粒度由实现方控制（更细粒度 vs 主流程粒度）</li>
     * </ul>
     *
     * @param filePath 被读取文件的路径（与 LLM 输入一致；CC original: filePath）
     * @param content  读取到的 range 文本内容（仅 text 分支非空；其它分支不会发出此事件。
     *                 CC original: content，FileReadTool.ts:1040-1044 传 readFileInRange 窗口内容）
     */
    void onFileRead(String filePath, String content);
}
