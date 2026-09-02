package com.nexusai.application.agent.voice;

import java.util.List;

/**
 * VoiceContextState · 对齐 CC context/voice.tsx:4-19 VoiceState + DEFAULT_STATE。
 *
 * <p>L1 语义: 语音输入的 UI 状态值对象。5 个字段: voiceState(idle/recording/processing)、
 * voiceError、voiceInterimTranscript、voiceAudioLevels、voiceWarmingUp。默认为全空闲状态。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: record 5 字段 + VoiceStatus 枚举 3 值 + DEFAULT 常量, 字段名/默认值与 CC 1:1</li>
 *   <li><b>A2 Golden Trace</b>: DEFAULT = idle/null/""/[]/false</li>
 *   <li><b>A3 不可变值对象</b>: record 自动 equals/hashCode; withXxx 返回新实例</li>
 *   <li><b>A4 边界</b>: voiceAudioLevels 默认空 list; voiceError 可 null</li>
 *   <li><b>A5 业务场景</b>: 录音开始 → recording; 出错 → idle + voiceError 文本</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS type + createStore(DEFAULT_STATE) React store → Java record 值对象 +
 * DEFAULT 常量; 状态订阅/setState 的 React store 由 Java 端 VoiceStreamClient 事件承接。
 */
public record VoiceContextState(
    VoiceStatus voiceState,
    String voiceError,
    String voiceInterimTranscript,
    List<Integer> voiceAudioLevels,
    boolean voiceWarmingUp) {

    /** CC voice.tsx:4-9 voiceState 三态 */
    public enum VoiceStatus { idle, recording, processing }

    /** CC voice.tsx:11-17 DEFAULT_STATE */
    public static final VoiceContextState DEFAULT =
        new VoiceContextState(VoiceStatus.idle, null, "", List.of(), false);

    public VoiceContextState {
        if (voiceState == null) voiceState = VoiceStatus.idle;
        if (voiceInterimTranscript == null) voiceInterimTranscript = "";
        if (voiceAudioLevels == null) voiceAudioLevels = List.of();
    }
}
