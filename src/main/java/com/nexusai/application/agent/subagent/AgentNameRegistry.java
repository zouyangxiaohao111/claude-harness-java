package com.nexusai.application.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 子 Agent name→agentId 注册表 · 对齐 CC {@code appState.agentNameRegistry}
 * （Open-ClaudeCode/src/state/AppStateStore.ts，写点 AgentTool.tsx:703-712，读点
 * SendMessageTool.ts:804 {@code appState.agentNameRegistry.get(input.to)}）。
 *
 * <p>IMP-G4（组 11-1）TR-G1 C7 修复：探查证实 Java 全仓 {@code agentNameRegistry}
 * 无写入点（仅 AppState.java:17 文档提及）→ SendMessage 按名路由断。本组件提供
 * 会话级 name→agentId 注册 + CC queuePendingMessage 等价待投递队列。
 *
 * <p><b>写点</b>：{@link SubagentTool} async spawn（CC AgentTool.tsx:703-712
 * {@code if (name) rootSetAppState(... agentNameRegistry: next)}）——post-registerAsyncAgent，
 * spawn 失败不残留；agent 终态 unregister。
 *
 * <p><b>读点</b>：{@link com.nexusai.application.agent.tool.impl.SendMessageTool}
 * 按名路由（CC SendMessageTool.ts:800-813 {@code registered ?? toAgentId(input.to)}）——
 * 已注册且任务存活 → queue 到待投递队列（CC queuePendingMessage）；子 agent 下一轮
 * {@link SubagentExecutor#runSubagentQueryLoop} drain 为 user 消息（CC LocalAgentTask
 * 每轮消费 pendingMessages）。
 *
 * <p><b>WHY 独立 @Component</b>（Pattern #14 static seam 反转）：CC 注册表是 React
 * appState 的一部分（会话作用域）；Java 无 appState 字段化承载 → 独立会话级组件，
 * SubagentTool（写）/SendMessageTool（读）/SubagentExecutor（drain）三方 DI 共享
 * 同一实例（单例 Spring bean），与 CC 会话级 appState 语义等价（多实例共享一致性
 * 登记：单机部署下同进程即会话隔离边界）。
 */
@Component
public class AgentNameRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentNameRegistry.class);

    /** name → agentId · 对齐 CC {@code appState.agentNameRegistry}（Map&lt;string, agentId&gt;） */
    private final Map<String, String> nameToAgentId = new ConcurrentHashMap<>();

    /** agentId → 待投递消息队列（FIFO）· 对齐 CC {@code queuePendingMessage} 待办队列 */
    private final Map<String, ArrayDeque<PendingAgentMessage>> pendingMessages = new ConcurrentHashMap<>();

    /**
     * 已结束 agent 残留消息的补投通道（对齐 CC「a send resumes it from its transcript」）。
     * 可选注入：无 Spring（单测/POJO）→ null，unregister 只 WARN，不改变清理行为。
     */
    @Autowired(required = false)
    private ObjectProvider<ResumeService> resumeServiceProvider;

    /**
     * 一条待投递消息 · 对齐 CC {@code queuePendingMessage} 队列项（exe 2.1.328：
     * {@code {text, origin, isMeta, toolsUsed, userInitiatedAt, forwarded, shownAs}}）。
     *
     * <p>本仓额外携带 {@code id}/{@code from}/{@code summary}：CC 入队时无稳定 ID（drain 时才生成
     * {@code source_uuid}），本仓在入队时生成，使「实时推送」与「转录重拉」可按同一 uuid 去重，
     * 且 SendMessage 的 summary（UI 预览）不再在路由层丢失。
     *
     * @param id        稳定消息 ID（= 消费行 ChatMessageDto.id / transcript uuid）
     * @param agentId   目标子代理 UUID
     * @param sessionId 父会话 ID（残留补投时定位 transcript 目录）
     * @param from      发送方 agent 名（null → 前端按「代理」兜底）
     * @param summary   SendMessage 的 5-10 词 UI 摘要（可空）
     * @param text      消息正文
     * @param origin    {@code coordinator} / {@code peer}（对齐 CC origin.kind）
     * @param queuedAt  入队时间（ISO-8601）
     */
    public record PendingAgentMessage(String id, String agentId, String sessionId,
                                      String from, String summary, String text,
                                      String origin, String queuedAt) {}

    /**
     * 注册 name→agentId · 对齐 CC AgentTool.tsx:703-712
     * {@code next.set(name, asAgentId(asyncAgentId))}（post-registerAsyncAgent）。
     * 重复注册覆盖（CC Map.set 语义）。
     *
     * @param name    子 agent 名（工具输入 {@code name} 字段，可为 null 表示无名）
     * @param agentId 子 agent UUID（async 路径 agentId===taskId）
     */
    public void register(String name, String agentId) {
        if (name == null || name.isBlank() || agentId == null) {
            return;
        }
        String prev = nameToAgentId.put(name, agentId);
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] 注册 name→agentId: name='{}' agentId={} (CC AgentTool.tsx:703-712, 覆盖前={})",
                name, agentId, prev);
        }
    }

    /**
     * 注销 name→agentId · 对齐 CC 无显式注销（React 状态随会话结束 GC）；
     * Java 在子 agent 终态清理时调用，避免 name 映射残留指向已终止 agentId。
     *
     * <p><b>[终态清理 → 对齐 CC 不静默丢]</b>：agent 终态后不再有消费点，残留队列若直接丢弃，
     * 调用方已拿到 success:true 而消息永不到达。CC 的语义是「名字在 agent 完成后仍可寻址，
     * 发送会从 transcript 热启动它」⇒ 本方法对非空残留做 resume 补投（{@link #redeliverOrWarn}），
     * 不可用时至少 WARN 写明丢弃条数。
     *
     * @param name 子 agent 名
     */
    public void unregister(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        // Map.remove 返回被移除的值 = 本 name 映射到的 agentId（清理目标）
        String removed = nameToAgentId.remove(name);
        if (removed != null) {
            ArrayDeque<PendingAgentMessage> dropped = pendingMessages.remove(removed);
            if (dropped != null && !dropped.isEmpty()) {
                // 对齐 CC：名字在 agent 完成后仍可寻址，发送会热启动它 —— 残留消息不静默丢。
                redeliverOrWarn(removed, dropped);
            }
            if (log.isDebugEnabled()) {
                log.debug("[AgentNameRegistry] 注销 name→agentId: name='{}' 移除={} 清空待投递队列条数={}",
                    name, removed, dropped != null ? dropped.size() : 0);
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] 注销 name→agentId: name='{}' 移除=null（未注册，无队列可清）", name);
        }
    }

    /**
     * 已结束 agent 的残留消息补投 · 对齐 CC「names keep working after an agent completes
     * (a send resumes it from its transcript)」。
     *
     * <p>无 ResumeService（单测/POJO）或补投失败 → WARN 写明丢弃条数，不静默。
     */
    private void redeliverOrWarn(String agentId, Collection<PendingAgentMessage> dropped) {
        ResumeService resume = resumeServiceProvider != null
            ? resumeServiceProvider.getIfAvailable() : null;
        String sessionId = dropped.stream()
            .map(PendingAgentMessage::sessionId)
            .filter(s -> s != null && !s.isBlank())
            .findFirst().orElse(null);
        String prompt = dropped.stream()
            .map(PendingAgentMessage::text)
            .filter(t -> t != null && !t.isBlank())
            .collect(Collectors.joining("\n\n"));
        UUID uuid = ResumeService.parseAgentIdString(agentId);
        if (resume != null && sessionId != null && uuid != null && !prompt.isBlank()) {
            try {
                resume.resumeAgentBackground(uuid, prompt,
                    ResumeService.resolveSessionDir(sessionId), sessionId, null, false);
                log.warn("[AgentNameRegistry] agent 已结束但队列有 {} 条未消费消息，已热启动补投 "
                    + "agentId={} session={}（CC a-send-resumes-it 语义）",
                    dropped.size(), agentId, sessionId);
                return;
            } catch (Exception e) {
                log.warn("[AgentNameRegistry] 残留消息补投失败（丢弃 {} 条）agentId={}: {}",
                    dropped.size(), agentId, e.toString());
                return;
            }
        }
        log.warn("[AgentNameRegistry] agent 已结束且无法补投，丢弃未消费消息 {} 条 agentId={}",
            dropped.size(), agentId);
    }

    /**
     * 按名解析 agentId · 对齐 CC SendMessageTool.ts:804
     * {@code appState.agentNameRegistry.get(input.to)}。
     *
     * @param name 子 agent 名
     * @return agentId 或 null（未注册）
     */
    public String resolve(String name) {
        return name == null ? null : nameToAgentId.get(name);
    }

    /**
     * 是否存在该 name 注册（路由可用性判定）。
     */
    public boolean contains(String name) {
        return name != null && nameToAgentId.containsKey(name);
    }

    /**
     * 投递待办消息到子 agent · 对齐 CC {@code queuePendingMessage(agentId, message, setAppState)}
     * （SendMessageTool.ts:810-813，子 agent 存活时按名路由的落点）。子 agent 下一轮
     * {@link SubagentExecutor#runSubagentQueryLoop} 经 {@link #drain} 消费。
     *
     * @param agentId 目标子 agent UUID
     * @param message 待投递消息文本
     */
    public void queue(String agentId, String message) {
        queue(agentId, null, null, message, null, null);
    }

    /**
     * 投递带来源/摘要的待办消息（SendMessage 按名路由 · 对齐 CC {@code queuePendingMessage}）。
     *
     * @param agentId   目标子 agent UUID
     * @param sessionId 父会话 ID（残留补投用；可空）
     * @param from      发送方 agent 名（可空）
     * @param message   消息正文
     * @param summary   SendMessage 的 UI 摘要（可空）
     * @param origin    {@code coordinator} / {@code peer}（可空）
     */
    public void queue(String agentId, String sessionId, String from, String message,
                      String summary, String origin) {
        if (agentId == null || message == null) {
            return;
        }
        PendingAgentMessage rec = new PendingAgentMessage(
            UUID.randomUUID().toString(), agentId, sessionId, from, summary, message,
            origin, java.time.Instant.now().toString());
        pendingMessages.computeIfAbsent(agentId, k -> new ArrayDeque<>()).addLast(rec);
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] 投递待办消息: agentId={} id={} from={} 消息长度={} "
                    + "(CC queuePendingMessage)",
                agentId, rec.id(), from, message.length());
        }
    }

    /**
     * 取出并清空该 agent 的待投递消息（FIFO）· 对齐 CC LocalAgentTask 每轮消费
     * {@code pendingMessages}。无待办 → 空列表。
     *
     * @param agentId 子 agent UUID
     * @return 待投递消息列表（FIFO 序，消费后清除）
     */
    public List<String> drain(String agentId) {
        List<PendingAgentMessage> recs = drainPending(agentId);
        if (recs.isEmpty()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>(recs.size());
        for (PendingAgentMessage r : recs) {
            out.add(r.text());
        }
        return out;
    }

    /**
     * 取出并清空该 agent 的待投递记录（FIFO）· 生产消费入口（LlmAgentLoop / SubagentExecutor）。
     *
     * @param agentId 子 agent UUID
     * @return 待投递记录列表（FIFO 序，消费后清除）
     */
    public List<PendingAgentMessage> drainPending(String agentId) {
        if (agentId == null) {
            return List.of();
        }
        ArrayDeque<PendingAgentMessage> q = pendingMessages.remove(agentId);
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        return List.copyOf(q);
    }

    /**
     * 是否有待投递消息（路由层判定子 agent 是否被阻塞等待）。
     */
    public boolean hasPending(String agentId) {
        ArrayDeque<PendingAgentMessage> q = agentId == null ? null : pendingMessages.get(agentId);
        return q != null && !q.isEmpty();
    }

    /** 当前注册条目数（测试/观测）。 */
    public int size() {
        return nameToAgentId.size();
    }
}
