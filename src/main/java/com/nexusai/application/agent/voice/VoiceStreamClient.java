package com.nexusai.application.agent.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Voice Stream STT 客户端 · 对齐 CC services/voiceStreamSTT.ts + services/voice.ts.
 *
 * <p>L1 语义: 推送音频 → 服务端转录 → 回调流式返回. 真实 WebSocket 留后续 (需 OAuth + WS),
 * 本 commit 提供可单测的 in-memory 实现 + L1/L2 状态机.
 *
 * <p>L2 契约:
 * <ul>
 *   <li>create() → NEW 连接, isConnected=true</li>
 *   <li>send 后所有数据累计; finalize → 拼接并 complete future</li>
 *   <li>close → isConnected=false, 后续 send 抛 IllegalStateException</li>
 *   <li>finalize 在 close 后返回 empty string (不抛)</li>
 * </ul>
 */
@Component
public class VoiceStreamClient {

    private static final Logger log = LoggerFactory.getLogger(VoiceStreamClient.class);

    /** 创建新连接 (按 push-to-talk 一次会话). */
    public VoiceStreamConnection create() {
        return new InMemoryConnection();
    }

    /** 内存实现 · 测试 + 无 WS 环境兜底. */
    static final class InMemoryConnection implements VoiceStreamConnection {
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final List<byte[]> chunks = new ArrayList<>();
        // 默认 stub 转录: 拼接字节数 + "transcribed"
        private volatile String stubTranscript = "";

        @Override
        public void send(byte[] audioChunk) {
            if (!connected.get()) {
                throw new IllegalStateException("connection not connected");
            }
            chunks.add(audioChunk);
            log.debug("[VoiceStreamConnection] received chunk size={}", audioChunk == null ? 0 : audioChunk.length);
        }

        @Override
        public CompletableFuture<String> transcribe() {
            CompletableFuture<String> fut = new CompletableFuture<>();
            if (closed.get()) {
                fut.complete("");
                return fut;
            }
            // L1 stub: 总字节数作转录长度指示, 真实实现调 WS API
            int totalBytes = chunks.stream().mapToInt(c -> c == null ? 0 : c.length).sum();
            stubTranscript = "[stub STT, " + totalBytes + " bytes audio]";
            log.info("[VoiceStreamConnection] finalized chunks={} totalBytes={}", chunks.size(), totalBytes);
            fut.complete(stubTranscript);
            return fut;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                connected.set(false);
                log.info("[VoiceStreamConnection] closed");
            }
        }

        @Override
        public boolean isConnected() {
            return connected.get();
        }
    }
}