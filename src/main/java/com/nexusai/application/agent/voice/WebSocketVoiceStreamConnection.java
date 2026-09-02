package com.nexusai.application.agent.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Voice Stream WebSocket 连接 · 对齐 CC voiceStreamSTT.ts:111-380 connectVoiceStream.
 *
 * <p>L1 语义: 通过 java.net.http WebSocket 客户端连接 voice_stream endpoint (e.g. wss://api.anthropic.com/api/ws/speech_to_text/voice_stream),
 * 推音频 → 收 TranscriptText events → CloseStream → 等 TranscriptEndpoint → 返回最终转录.
 *
 * <p>L2 契约 (A3 状态机严格单向):
 * <ul>
 *   <li>CREATED → OPEN (连接成功) → FINALIZING (CloseStream 已发) → CLOSED (finalize 完成)</li>
 *   <li>未 OPEN 时 send 抛 IllegalStateException</li>
 *   <li>FINALIZING 后 send 不抛, 但静默丢弃 (CC voiceStreamSTT.ts:218-227)</li>
 *   <li>已 CLOSED 后 transcribe 返回 empty, 不抛 (CC ws_already_closed 分支)</li>
 * </ul>
 *
 * <p>OAuth/Bearer token 由调用方注入 (经 constructor). CC Anthropic OAuth 跳过 (用户要求).
 *
 * <p>L3 (Java idiom): java.net.http.HttpClient 新 WebSocket API, sealed 状态枚举.
 */
public class WebSocketVoiceStreamConnection implements VoiceStreamConnection {

    private static final Logger log = LoggerFactory.getLogger(WebSocketVoiceStreamConnection.class);

    /** 状态机 (A3 Gate). */
    public enum State {
        CREATED, OPEN, FINALIZING, CLOSED
    }

    /** 控制消息回调 (供测试注入, 真实场景下连 WebSocket). */
    public interface ControlSender {
        /** 发送字符串帧 (control message). */
        void sendText(String text);
        /** 发送二进制帧 (audio chunk). */
        void sendBinary(byte[] bytes);
        /** 关闭 WS, 幂等. */
        void close();
        /** 当前是否可写 (OPEN + 未 FINALIZING). */
        boolean isOpen();
    }

    private final ControlSender sender;
    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);
    private final AtomicBoolean closeStreamSent = new AtomicBoolean(false);
    private final StringBuilder transcriptBuffer = new StringBuilder();
    private final AtomicBoolean endpointReceived = new AtomicBoolean(false);
    private final CompletableFuture<VoiceStreamProtocol.FinalizeSource> finalizeFuture = new CompletableFuture<>();

    public WebSocketVoiceStreamConnection(ControlSender sender) {
        this.sender = sender;
    }

    /** 工厂: 真实 WebSocket 连接到 voice_stream endpoint. OAuth header 跳过 (用户要求). */
    public static WebSocketVoiceStreamConnection connect(URI endpoint) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        CompletableFuture<WebSocketVoiceStreamConnection> holder = new CompletableFuture<>();
        WebSocketVoiceStreamConnection[] connRef = new WebSocketVoiceStreamConnection[1];
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                VoiceStreamProtocol.VoiceStreamEvent event = VoiceStreamProtocol.decode(data.toString());
                if (event != null) {
                    connRef[0].handleServerEvent(event);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                connRef[0].state.set(State.CLOSED);
                connRef[0].finalizeFuture.complete(VoiceStreamProtocol.FinalizeSource.WS_CLOSE);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                connRef[0].finalizeFuture.completeExceptionally(error);
            }
        };

        client.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(endpoint, listener)
            .whenComplete((ws, err) -> {
                if (err != null) {
                    holder.completeExceptionally(err);
                } else {
                    WebSocketVoiceStreamConnection conn = new WebSocketVoiceStreamConnection(
                        new ControlSender() {
                            @Override public void sendText(String text) {
                                ws.sendText(text, true);
                            }
                            @Override public void sendBinary(byte[] bytes) {
                                ws.sendBinary(ByteBuffer.wrap(bytes), true);
                            }
                            @Override public void close() {
                                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client close");
                            }
                            @Override public boolean isOpen() {
                                return connRef[0] != null && connRef[0].state.get() == State.OPEN;
                            }
                        });
                    conn.state.set(State.OPEN);
                    connRef[0] = conn;
                    holder.complete(conn);
                }
            });

        try {
            return holder.join();
        } catch (Exception e) {
            throw new IllegalStateException("WebSocket connect failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void send(byte[] audioChunk) {
        State s = state.get();
        if (s == State.CREATED || s == State.CLOSED) {
            throw new IllegalStateException("voice stream not open (state=" + s + ")");
        }
        if (s == State.FINALIZING || closeStreamSent.get()) {
            // CC voiceStreamSTT.ts:220-227: finalize 后静默丢弃, 避免协议错误
            log.debug("[VoiceStream] dropping audio chunk after CloseStream: {} bytes",
                audioChunk == null ? 0 : audioChunk.length);
            return;
        }
        sender.sendBinary(audioChunk);
    }

    @Override
    public CompletableFuture<String> transcribe() {
        if (state.get() == State.CLOSED) {
            // CC ws_already_closed 分支: 直接返回 empty, 不抛
            return CompletableFuture.completedFuture("");
        }
        if (closeStreamSent.compareAndSet(false, true)) {
            // A4 Tool Sequence: CloseStream 是 finalize 的唯一入口
            state.set(State.FINALIZING);
            sender.sendText(VoiceStreamProtocol.encodeCloseStream());
        }
        return finalizeFuture.thenApply(source -> {
            state.set(State.CLOSED);
            sender.close();
            String text = transcriptBuffer.toString();
            log.info("[VoiceStream] finalized via={} textLen={}", source, text.length());
            return text;
        });
    }

    @Override
    public void close() {
        if (state.get() == State.CLOSED) {
            return;
        }
        state.set(State.CLOSED);
        sender.close();
        finalizeFuture.complete(VoiceStreamProtocol.FinalizeSource.WS_ALREADY_CLOSED);
    }

    @Override
    public boolean isConnected() {
        State s = state.get();
        return s == State.OPEN || s == State.FINALIZING;
    }

    // ────────────── 测试 / Listener hook ──────────────

    /** 处理服务端事件 (测试可调, WebSocket listener 也调). */
    public void handleServerEvent(VoiceStreamProtocol.VoiceStreamEvent event) {
        switch (event) {
            case VoiceStreamProtocol.VoiceStreamEvent.TranscriptText t ->
                transcriptBuffer.append(t.text());
            case VoiceStreamProtocol.VoiceStreamEvent.TranscriptEndpoint ignored -> {
                if (endpointReceived.compareAndSet(false, true)) {
                    finalizeFuture.complete(VoiceStreamProtocol.FinalizeSource.POST_CLOSESTREAM_ENDPOINT);
                }
            }
            case VoiceStreamProtocol.VoiceStreamEvent.TranscriptError err ->
                finalizeFuture.completeExceptionally(new IllegalStateException(
                    "voice stream error: " + err.errorCode() + " " + err.description()));
            case VoiceStreamProtocol.VoiceStreamEvent.Error err ->
                finalizeFuture.completeExceptionally(new IllegalStateException(
                    "voice stream error: " + err.message()));
        }
    }

    /** 测试用: 当前状态. */
    public State getState() {
        return state.get();
    }

    /** 测试用: 当前转录 buffer 内容. */
    public String transcriptSoFar() {
        return transcriptBuffer.toString();
    }

    /** 测试用: sender 访问器. */
    public ControlSender senderForTest() {
        return sender;
    }

    /** 测试用: 强制设状态 (模拟 WS open/close 后的状态机迁移). */
    public void setStateForTest(State s) {
        state.set(s);
    }
}