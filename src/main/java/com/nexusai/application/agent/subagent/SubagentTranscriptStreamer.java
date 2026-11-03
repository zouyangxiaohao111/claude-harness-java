package com.nexusai.application.agent.subagent;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 子代理 transcript 实时推送 · 把 {@link AgentTranscript} 落盘的每条增量推到
 * {@code /topic/sessions/{sessionId}/subagents/{agentId}}。
 *
 * <p><b>WHY 需要</b>：右栏点开子代理只做一次 REST 拉取（{@code SubagentController.getTranscript}）
 * ⇒ 运行中的子代理在右栏里是「静止」的，用户必须关掉再点开才能看到新进展。主对话靠
 * {@code /topic/sessions/{sid}/stream} 实时出字，子代理视图此前没有任何增量通道。
 *
 * <p><b>与主对话流的边界</b>：本通道只承载子代理 sidechain transcript（{@code subagents/agent-*.jsonl}），
 * 不参与主对话渲染、不写 store 的 messages —— 纯展示侧订阅。
 *
 * <p><b>载荷 = 与落盘逐位一致的 map 列表</b>：前端按 {@code uuid} 幂等去重，因此「订阅后补拉一次」
 * 与「实时增量」可以安全重叠（闭合首拉与订阅之间的窗口）。
 */
@Component
public class SubagentTranscriptStreamer {

    private static final Logger log = LoggerFactory.getLogger(SubagentTranscriptStreamer.class);

    /** 可选注入：POJO 测试 / 无 WebSocket 上下文时为 null → 只记日志，不推送。 */
    @Autowired(required = false)
    private SimpMessagingTemplate wsTemplate;

    /**
     * 已推过增量的 agent（"sessionId/agentId"）· 只用于「首次推送打 INFO」。
     *
     * <p>WHY 不全打 INFO：一次子代理运行逐条落盘，实测 20 秒 17 帧；长任务可达数百帧，
     * 每帧一行 INFO 会把正常日志淹掉。首次一行 INFO（数据流起点可定位），其后逐帧 DEBUG。
     * 集合有界（超过 1024 条整表清空）：只是日志降噪开关，清空最坏是多打一行 INFO。
     */
    private final Map<String, Boolean> announced = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    void install() {
        AgentTranscript.setIncrementSink(this::publish);
        log.info("[SubagentTranscriptStreamer] transcript 增量推送已接线 wsTemplate={}", wsTemplate != null);
    }

    /**
     * 推一条增量。
     *
     * @param sessionId 会话 id（topic 段）
     * @param agentId   transcript 文件名里的 agent id（a+16hex；与 REST 端点同源）
     * @param messages  本次落盘的消息 map（已含 agentId/isSidechain/uuid/parentUuid）
     */
    private void publish(String sessionId, String agentId, List<Map<String, Object>> messages) {
        if (wsTemplate == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agentId);
        payload.put("messages", messages);
        String topic = "/topic/sessions/" + sessionId + "/subagents/" + agentId;
        wsTemplate.convertAndSend(topic, payload);
        // ---- 数据流日志（规则：编码后必须留痕；首帧 INFO / 逐帧 DEBUG）----
        String key = sessionId + "/" + agentId;
        if (announced.putIfAbsent(key, Boolean.TRUE) == null) {
            if (announced.size() > 1024) announced.clear();
            log.info("[SubagentTranscriptStreamer] 开始实时推送 topic={} 首帧条数={}", topic, messages.size());
        } else if (log.isDebugEnabled()) {
            StringBuilder roles = new StringBuilder();
            for (Map<String, Object> m : messages) {
                if (roles.length() > 0) roles.append(',');
                roles.append(m.get("role")).append(':').append(m.get("uuid"));
            }
            log.debug("[SubagentTranscriptStreamer] 推送 topic={} 条数={} [{roles}]",
                topic, messages.size(), roles);
        }
    }
}
