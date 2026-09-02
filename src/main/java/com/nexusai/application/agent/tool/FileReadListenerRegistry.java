package com.nexusai.application.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 文件读取监听器注册表 · 对齐 CC {@code tools/FileReadTool/FileReadTool.ts:163-173}
 * {@code fileReadListeners} 数组 + {@code registerFileReadListener}.
 *
 * <p>L1 行为：
 * <ul>
 *   <li>{@link #register} → 返回 unsubscribe {@link Runnable}（CC 返回闭包；Java
 *       用 {@link Runnable} 替代），调它即可注销。</li>
 *   <li>{@link #notifyRead} → 同步遍历 <b>snapshot</b>（避免 listener 在回调中注销导致
 *       splice 跳过下一个 listener，CC :1040-1044 注释明确）</li>
 *   <li><b>[L+ R2] 异常哲学对齐 CC</b>：CC {@code FileReadTool.ts:1042} 是裸调用
 *       <pre>for (const listener of fileReadListeners.slice()) { listener(...) }</pre>
 *       一 listener 抛异常 → 中断后续 listener 投递, 异常向上传播给主流程（fail-fast）。
 *       L session 决策保留 Java fail-soft（catch RuntimeException 隔离），L+ R2 改回
 *       CC fail-fast 哲学：listener 异常不再吞, 暴露给 ReadFileTool 主流程（规则十二
 *       显式失败）。Listener 实现方需在 {@link FileReadListener#onFileRead} 内部自 catch
 *       业务异常（与 registry 行为解耦）。</li>
 * </ul>
 *
 * <p>WHY {@code CopyOnWriteArrayList}：listener 通常在启动期注册、稳态运行；写少
 * 读多。CoW 让 register / unregister 线程安全且遍历无需额外加锁。
 */
@Component
public class FileReadListenerRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileReadListenerRegistry.class);

    private final CopyOnWriteArrayList<FileReadListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 注册监听器，返回取消订阅句柄。
     *
     * <p>调返回的 {@link Runnable} 即可注销（多次调用是 idempotent 的）。
     *
     * @param listener 监听器（不可为 null）
     * @return unsubscribe handle
     */
    public Runnable register(FileReadListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is null");
        }
        listeners.addIfAbsent(listener);
        return () -> {
            listeners.remove(listener);
            if (log.isDebugEnabled()) {
                log.debug("[FileReadListenerRegistry] listener 已注销: {}",
                    listener.getClass().getSimpleName());
            }
        };
    }

    /**
     * 通知所有 listener · 对齐 CC {@code for (const listener of fileReadListeners.slice())}.
     *
     * <p>遍历前做 snapshot，避免 listener 在回调中 unregister 导致遍历跳过元素。
     *
     * <p><b>[IMP-C5] FileReadEvent 退役</b>：签名改为 CC 二元 {@code (filePath, content)}
     * （FileReadTool.ts:162），删除 mtime/callId 扩展字段（TR-D1-⊕-2）。
     *
     * <p><b>[L+ R2] 异常哲学对齐 CC fail-fast</b>：CC FileReadTool.ts:1042 是裸调用,
     * 一 listener 抛异常 → 中断后续 listener + 异常向上传播. 本类删除 try/catch 隔离.
     * 实现方应在 {@link FileReadListener#onFileRead} 内自 catch 业务异常, 避免炸主流程.
     * 这是 CC 与 Java fail-soft 的哲学调整 (L+.7-3 决策点) — 选择 CC fail-fast 暴露
     * 真实问题 (CLAUDE.md 规则十二·显式失败).
     *
     * @param filePath 被读取文件路径（CC original: filePath）
     * @param content  读取到的 range 文本内容（CC original: content；对齐 W6 range vs 全文件）
     */
    public void notifyRead(String filePath, String content) {
        if (filePath == null) return;
        List<FileReadListener> snapshot = List.copyOf(listeners);
        // 裸调用 listener.onFileRead — 异常向上传播 (CC FileReadTool.ts:1042 语义).
        for (FileReadListener listener : snapshot) {
            listener.onFileRead(filePath, content);
        }
    }

    /**
     * 当前已注册 listener 数量（测试用）。
     */
    public int listenerCount() {
        return listeners.size();
    }
}