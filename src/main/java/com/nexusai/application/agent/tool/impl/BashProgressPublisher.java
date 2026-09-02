package com.nexusai.application.agent.tool.impl;

import com.nexusai.eventbus.ws.ToolCallProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Bash 进度 STOMP 发布器 · 生产接线 {@link BashTool#bashProgressSink}
 * （G5-4 遗留项：此前无 bean 注入点，生产路径 never 发射）。
 *
 * <p>CC original: {@code onProgress}（BashTool.tsx:663-677）—— BashTool 前台命令运行超
 * {@code PROGRESS_THRESHOLD_MS}=2s 阈值后，每秒 tick 的增量进度（{@link BashTool.BashProgress}）
 * 经本 bean 发射为 {@link ToolCallProgressEvent}（type={@code tool_call_progress}）到会话级流 topic
 * {@code /topic/sessions/{sessionId}/stream}（与 {@code MessageToolCallEvent}/{@code MessageToolResultEvent}
 * 同一会话单 topic）。
 *
 * <p><b>fail-soft（不破坏 BashTool 主结果路径）</b>：{@code SimpMessagingTemplate} 未注入（无 WebSocket
 * 场景 / 单测直构）或 {@code sessionId} 空 → 跳过推送，仅 log.debug 登记；推送异常 catch + log.warn
 * （best-effort，对齐 StreamingToolExecutor.pushToolCallRealtime 的 fail-loud 模式）。
 *
 * <p>注册方式：{@code @Component} + {@code implements Consumer<BashTool.BashProgress>}，
 * 被 {@code BashTool.bashProgressSink} 的 {@code @Autowired(required=false)} 字段按泛型类型自动注入
 * （全仓唯一 {@code Consumer<BashProgress>} bean，无歧义）。
 */
@Component
public class BashProgressPublisher implements Consumer<BashTool.BashProgress> {

    private static final Logger log = LoggerFactory.getLogger(BashProgressPublisher.class);

    /** STOMP 模板 · required=false：无 WebSocket 场景 / 单测直构 → null → 跳过推送（fail-soft）。 */
    @Autowired(required = false)
    private SimpMessagingTemplate wsTemplate;

    /** 测试/非 Spring 场景注入（对齐 setBashProgressSink 短路语义）。 */
    public void setWsTemplate(SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
    }

    @Override
    public void accept(BashTool.BashProgress p) {
        if (wsTemplate == null) {
            if (log.isDebugEnabled()) {
                log.debug("BashProgressPublisher: wsTemplate 未注入，跳过 tool_call_progress 推送");
            }
            return;
        }
        if (p == null || p.sessionId() == null || p.sessionId().isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("BashProgressPublisher: sessionId 空/进度为空，跳过 tool_call_progress 推送");
            }
            return;
        }
        String topic = "/topic/sessions/" + p.sessionId() + "/stream";
        try {
            wsTemplate.convertAndSend(topic, new ToolCallProgressEvent(
                p.sessionId(), null, p.toolCallId(), p.output(), p.fullOutput(),
                p.elapsedTimeSeconds(), p.totalLines(), p.totalBytes()));
            if (log.isDebugEnabled()) {
                log.debug("BashProgressPublisher: 已推送 tool_call_progress session={} toolCallId={} "
                        + "elapsed={}s totalLines={} totalBytes={} lastLines={}",
                    p.sessionId(), abbreviate(p.toolCallId(), 24), p.elapsedTimeSeconds(),
                    p.totalLines(), p.totalBytes(), countLines(p.output()));
            }
        } catch (Throwable th) {
            // 推送失败不阻断工具执行（best-effort, fail loud 已记日志）
            log.warn("BashProgressPublisher: tool_call_progress 推送失败 session={} err={}",
                p.sessionId(), th.toString());
        }
    }

    /** 统计增量输出行数（供 debug 日志；不参与 payload 计算）。 */
    private static long countLines(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** 日志缩写工具 · 超长 ID 截断（对齐 StreamingToolExecutor.abbreviate 语义）。 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
