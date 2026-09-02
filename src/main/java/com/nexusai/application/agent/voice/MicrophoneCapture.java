package com.nexusai.application.agent.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 麦克风音频采集 · 对齐 CC services/voice.ts 麦克风硬件抽象.
 *
 * <p>L1 语义: 按住说话模式, push-to-talk. start() 开 TargetDataLine + 后台 daemon 线程
 * 读 PCM, 每读一帧调用 listener.onPcmChunk(byte[]). stop() 关闭 line.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 16kHz mono 16-bit signed little-endian PCM (CC voice_stream default)</li>
 *   <li><b>A2 Golden Trace</b>: start → onChunk (多次) → stop (单帧一次性)</li>
 *   <li><b>A3</b>: STOPPED → RECORDING → STOPPED, 严格单向</li>
 *   <li><b>A4</b>: listener.onChunk 顺序调用, 不并发 (单线程读 line)</li>
 *   <li><b>A5</b>: 单帧大小固定 (3200 bytes = 100ms @ 16kHz mono 16-bit)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): javax.sound.sampled (JDK) + daemon thread + CopyOnWriteArrayList (listener).
 */
@Component
public class MicrophoneCapture {

    private static final Logger log = LoggerFactory.getLogger(MicrophoneCapture.class);

    /** 麦克风录制状态 (A3 Gate). */
    public enum State { STOPPED, RECORDING }

    /** 默认 PCM 格式: 16kHz mono 16-bit signed (CC voice_stream 参数). */
    public static final AudioFormat DEFAULT_FORMAT = new AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        16000f,  // sample rate
        16,      // sample size in bits
        1,       // channels (mono)
        2,       // frame size (bytes per sample * channels)
        16000f,  // frame rate
        false    // little endian
    );

    /** 单帧字节数: 100ms @ 16kHz mono 16-bit = 16000 * 0.1 * 2 = 3200 bytes. */
    public static final int CHUNK_BYTES_100MS = 3200;

    private final List<Consumer<byte[]>> listeners = new ArrayList<>();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private TargetDataLine line;
    private Thread captureThread;

    /** 启动采集 (用默认 16kHz mono PCM 格式). */
    public void start() {
        start(DEFAULT_FORMAT);
    }

    /**
     * 启动采集 (用指定 PCM 格式).
     *
     * @param format 麦克风采集 PCM 格式
     */
    public synchronized void start(AudioFormat format) {
        if (!recording.compareAndSet(false, true)) {
            log.debug("[MicrophoneCapture] already recording");
            return;
        }
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                throw new IllegalStateException("microphone line not supported: " + format);
            }
            this.line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format, CHUNK_BYTES_100MS * 4);
            line.start();
            log.info("[MicrophoneCapture] started format={}", format);

            captureThread = new Thread(this::readLoop, "voice-mic-capture");
            captureThread.setDaemon(true);
            captureThread.start();
        } catch (Exception e) {
            // 任何异常 (LineUnavailableException + IllegalStateException + RuntimeException)
            // 都重置 state, 避免半启动状态
            recording.set(false);
            throw new IllegalStateException("microphone start failed: " + e.getMessage(), e);
        }
    }

    /** 停止采集 (幂等). */
    public synchronized void stop() {
        if (!recording.compareAndSet(true, false)) {
            return;
        }
        if (line != null) {
            try {
                line.stop();
                line.close();
                log.info("[MicrophoneCapture] stopped");
            } catch (Exception e) {
                log.warn("[MicrophoneCapture] close error: {}", e.getMessage());
            }
        }
        if (captureThread != null) {
            try {
                captureThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 注册 chunk 回调 (consumer 在 capture 线程上被调, 不应阻塞). */
    public void onChunk(Consumer<byte[]> listener) {
        listeners.add(listener);
    }

    /** 当前 listener 数量. */
    public int listenerCount() {
        return listeners.size();
    }

    /** 测试用: 当前所有 listeners (只读). */
    public List<Consumer<byte[]>> listenersForTest() {
        return java.util.Collections.unmodifiableList(listeners);
    }

    /** 当前状态. */
    public State getState() {
        return recording.get() ? State.RECORDING : State.STOPPED;
    }

    // ────────────── 内部 ──────────────

    private void readLoop() {
        byte[] buf = new byte[CHUNK_BYTES_100MS];
        while (recording.get() && line != null) {
            int n = line.read(buf, 0, buf.length);
            if (n > 0) {
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                for (var l : listeners) {
                    try {
                        l.accept(chunk);
                    } catch (Exception e) {
                        log.warn("[MicrophoneCapture] listener error: {}", e.getMessage());
                    }
                }
            }
        }
    }
}