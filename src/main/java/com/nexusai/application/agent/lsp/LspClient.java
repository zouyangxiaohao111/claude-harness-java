package com.nexusai.application.agent.lsp;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * LSP 客户端抽象 · 对齐 CC LSPClient.ts:21-41.
 *
 * <p>L1 语义: 进程外 LSP server 子进程的 JSON-RPC 双向通道. 生命周期:
 * {@link #start} → {@link #initialize} → {@link #sendRequest}/{@link #sendNotification} →
 * {@link #stop}. 一旦 stop, 客户端不可再用, 需重新 start.
 *
 * <p>L2 契约:
 * <ul>
 *   <li>capabilities 由 initialize 完成时填充, 之前返回 null</li>
 *   <li>sendRequest/sendNotification 在未 initialize 时应抛 IllegalStateException</li>
 *   <li>stop() 幂等, 重复调用不抛异常</li>
 * </ul>
 *
 * <p>L3 (Java idiom): 用 {@link CompletableFuture} 取代 CC 的 Promise, record 取代 interface.
 */
public interface LspClient {

    /** initialize 完成后填充的能力描述, 否则 null. 对齐 CC LSPClient.ts:22. */
    Map<String, Object> capabilities();

    /** 是否完成 initialize 握手. 对齐 CC LSPClient.ts:23. */
    boolean isInitialized();

    /**
     * 启动 LSP server 子进程. 失败抛 RuntimeException (CC onCrash 回调触发).
     *
     * @param command 可执行命令 (e.g. "typescript-language-server")
     * @param args    命令参数
     * @param env     合并的环境变量
     * @param cwd     工作目录, null 继承父进程
     */
    void start(String command, String[] args, Map<String, String> env, String cwd);

    /**
     * initialize 握手. 必须在 {@link #start} 之后, {@link #sendRequest} 之前.
     *
     * @param rootUri workspace root URI
     * @return initialize 响应 (capabilities + serverInfo)
     */
    LspInitializeResult initialize(String rootUri);

    /** 发送 JSON-RPC request. 未 initialize 时抛 IllegalStateException. */
    <T> T sendRequest(String method, Object params, Class<T> resultType);

    /** 发送 JSON-RPC notification (无响应). 未 initialize 时抛 IllegalStateException. */
    void sendNotification(String method, Object params);

    /** 关闭子进程, 幂等. */
    void stop();

    /** initialize 响应 · 对齐 CC vscode-languageserver-protocol InitializeResult. */
    record LspInitializeResult(
        String protocolVersion,
        Map<String, Object> capabilities,
        LspServerInfo serverInfo
    ) {}

    /** serverInfo (name + version). */
    record LspServerInfo(String name, String version) {}
}