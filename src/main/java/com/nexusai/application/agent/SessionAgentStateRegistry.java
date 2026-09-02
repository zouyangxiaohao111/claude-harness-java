package com.nexusai.application.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [P1-6] 会话级主 AgentState 注册表 · SkillTool 写入侧（addInvokedSkill）经 sessionId
 * 解析主会话 AgentState。
 *
 * <p><b>用途</b>：CC {@code STATE}（bootstrap/state.ts:178-187）在 Java 端是按会话分散的
 * AgentState 实例，且 CC 单进程全局 Map 的写入侧（processSlashCommand.tsx:885
 * {@code addInvokedSkill}）需要 sessionId → 主会话 AgentState 的寻址通道。本注册表提供
 * 该通道：{@link LlmAgentLoop#run} 主会话（agentId==null）入口注册，SkillToolImpl 内联路径
 * 经 {@link #get} 解析后写入 invokedSkills。
 *
 * <p><b>[session-id-short] 键空间拆双 map</b>：
 * <ul>
 *   <li>{@link #sessions} —— {@code String}（short 形态 sess-xxx）→ 主会话 AgentState
 *       （LlmAgentLoop 主线程 agentId==null 注册，ChatService/ToolRegistrationConfig/
 *       PartialCompactService/PostCompactionState/CommandController/WebSocketPermissionPrompter
 *       全部经此直键查询）。</li>
 *   <li>{@link #agents} —— {@code UUID}（agentId=agentUuid）→ 后台化主会话 AgentState
 *       （MainSessionBackgroundService 后台任务 LlmAgentLoop.run 唯一非 null agentId 调用方按
 *       agentId 注册；SkillTool 写侧经 {@code ctx.agentId()}（工具线程上恒为该后台 loop 的
 *       agentUuid）解析命中 —— agentId 通道与会话无关保持 UUID）。</li>
 * </ul>
 *
 * <p>主/后台不同 key（sessionId short vs agentUuid UUID），互不覆盖，registry 查询不冲突。
 *
 * <p><b>local-only 红线（CLAUDE.md BudgetTracker 架构）</b>：纯内存进程内注册表，
 * 绝不序列化 / 绝不经 STOMP / WebSocket / EventPublisher / outbound DTO 外发。
 * 其中持有的 AgentState 含 {@code invokedSkills} skill 全文（可能含敏感项目内容）与
 * {@code budgetTracker}（token 使用隐私数据），任何新增 external 通道必须先审计本类。
 *
 * <p><b>注册守卫（对齐 CC 多 agent 语义）</b>：仅主会话（agentId==null）注册。
 * fork 子 agent 有独立隔离 AgentState（SubagentExecutor.java:2015）且共享父 sessionId，
 * 若也按 sessionId 注册会覆盖主状态；fork 子 agent 的 invokedSkills 写入经
 * SkillToolImpl 写入侧 resolver 仍落主 map（key=代理 agentId），fork 退出由
 * {@code clearInvokedSkillsForAgent}（CC SkillTool.ts:287 finally 语义）清理。
 *
 * <p><b>并发</b>：ConcurrentHashMap —— 流式 tool_use 回调（写入侧）与 LlmAgentLoop
 * 主循环跨线程读写安全。
 *
 * <p><b>防泄漏</b>：{@link #remove} 供 session 终止路径接线（当前登记为后续清理项，
 * 见 P1-6 实施登记 concerns）；每次 run() 对同一 sessionId 的 register 覆盖旧状态，
 * 天然避免同 session 累积。
 */
@Component
public class SessionAgentStateRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionAgentStateRegistry.class);

    /** 主会话映射 · key = short sessionId（sess-xxx）。 */
    private final ConcurrentHashMap<String, AgentState> sessions = new ConcurrentHashMap<>();
    /** 后台化主会话映射 · key = agentId（agentUuid，与会话无关保持 UUID）。 */
    private final ConcurrentHashMap<UUID, AgentState> agents = new ConcurrentHashMap<>();

    /**
     * 注册（或覆盖）short sessionId → 主 AgentState 映射（主线程 agentId==null 入口）。
     *
     * <p>每次 {@link LlmAgentLoop#run} 主会话入口注册当前 AgentState；同 session 多轮
     * run 后写覆盖前一轮（每轮 run 新建 AgentState，旧实例 GC）。写入侧与压缩侧
     * （CompactConversation step 10 invoked_skills 重注入，CC compact.ts:558-560
     * createSkillAttachmentIfNeeded → 经本 registry 静态 holder 解析主 AgentState）
     * 读取同一 state 引用，闭环成立。
     *
     * @param sessionId 会话 ID（short 形态 sess-xxx；非 null）
     * @param state     该会话当前主 AgentState（非 null）
     */
    public void register(String sessionId, AgentState state) {
        if (sessionId == null || state == null) {
            return;
        }
        sessions.put(sessionId, state);
        if (log.isDebugEnabled()) {
            log.debug("[P1-6] 注册会话主 AgentState（sessionId short）: sessionId={} agentId={}",
                sessionId, state.agentId());
        }
    }

    /**
     * 注册（或覆盖）agentId → 后台化主 AgentState 映射（后台任务 agentId=agentUuid 入口）。
     *
     * <p>后台化主会话任务（MainSessionBackgroundService 唯一非 null agentId 的
     * LlmAgentLoop.run 调用方）按 agentId 注册：SkillTool 写入侧先经 ctx.agentId()
     * （工具线程上恒为 agentUuid）解析到后台 AgentState，落 agentId=agentUuid 条目，
     * 使 /clear preservedAgentIds={task.agentId()}（CommandController:370-373 /
     * CC conversation.ts:93-106）能匹配保留（EVD-B 归因链）。
     *
     * @param agentId agent 实例 ID（agentUuid；非 null）
     * @param state   后台化主会话当前 AgentState（非 null）
     */
    public void register(UUID agentId, AgentState state) {
        if (agentId == null || state == null) {
            return;
        }
        agents.put(agentId, state);
        if (log.isDebugEnabled()) {
            log.debug("[P1-6] 注册后台化主 AgentState（agentUuid）: agentId={} sessionId={}",
                agentId, state.sessionId());
        }
    }

    /**
     * 按键取 AgentState · 按参类型路由（String short sessionId → sessions map；
     * UUID agentId → agents map）。未命中返回 null（写入侧 null-safe 降级 skip）。
     */
    public AgentState get(Object key) {
        if (key == null) {
            return null;
        }
        if (key instanceof UUID agentId) {
            return agents.get(agentId);
        }
        if (key instanceof String sessionId) {
            return sessions.get(sessionId);
        }
        return null;
    }

    /** 按 short sessionId 取主会话 AgentState（sessions map）。 */
    public AgentState get(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessions.get(sessionId);
    }

    /** 按 agentId 取后台化主会话 AgentState（agents map）。 */
    public AgentState getByAgentId(UUID agentId) {
        if (agentId == null) {
            return null;
        }
        return agents.get(agentId);
    }

    /** 移除 short sessionId 主会话映射（session 终止路径接线，防 per-session 内存累积）。 */
    public void remove(String sessionId) {
        if (sessionId == null) {
            return;
        }
        AgentState removed = sessions.remove(sessionId);
        if (removed != null && log.isDebugEnabled()) {
            log.debug("[P1-6] 移除会话主 AgentState: sessionId={}", sessionId);
        }
    }

    /** 移除 agentId 后台化主会话映射。 */
    public void remove(UUID agentId) {
        if (agentId == null) {
            return;
        }
        AgentState removed = agents.remove(agentId);
        if (removed != null && log.isDebugEnabled()) {
            log.debug("[P1-6] 移除后台化主 AgentState: agentId={}", agentId);
        }
    }

    /** 当前注册会话数（测试 / 审计用）。 */
    public int size() {
        return sessions.size() + agents.size();
    }
}
