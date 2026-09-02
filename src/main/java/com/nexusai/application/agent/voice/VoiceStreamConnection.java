package com.nexusai.application.agent.voice;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Voice 流式 STT 连接抽象 · 对齐 CC services/voiceStreamSTT.ts:67-72.
 *
 * <p>L1 语义: 按住说话 (push-to-talk) 模式. hold 录音 → release 触发 finalize → 等服务端
 * 转录完成. OAuth 不在本模块 (用户要求跳过).
 *
 * <p>L2 契约:
 * <ul>
 *   <li>{@link #send} 仅在 connected 时接受音频, 否则抛 IllegalStateException</li>
 *   <li>{@link #finalize} 返回 final 完整转录文本 (调用方阻塞等)</li>
 *   <li>{@link #close} 幂等, 关后 isConnected=false</li>
 *   <li>{@link #isConnected} 反映当前真实状态</li>
 * </ul>
 */
public interface VoiceStreamConnection {

    /**
     * 发送一段音频数据 (PCM 16kHz mono 推荐, 由调用方保证格式).
     */
    void send(byte[] audioChunk);

    /**
     * 触发服务端 finalize, 返回最终完整转录. 改名避免与 {@link Object#finalize()} 冲突.
     *
     * <p>对齐 CC {@code finalize(): Promise<FinalizeSource>}, Java 返回 future
     * 在超时或服务端主动 close 时也一定完成.
     */
    CompletableFuture<String> transcribe();

    /** 关闭连接 (幂等). */
    void close();

    /** 当前是否 connected. */
    boolean isConnected();

    /** 一段转录 chunk (用于回调流式输出). */
    record TranscriptChunk(String text, boolean isFinal) {}
}