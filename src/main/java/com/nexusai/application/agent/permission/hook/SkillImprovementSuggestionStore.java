package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.hook.SkillImprovementHook.SkillUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill Improvement Suggestion Store · Java 等价 CC {@code AppState.skillImprovement.suggestion}
 * (skillImprovement.ts:160-165 / useSkillImprovementSurvey.ts:14-17).
 *
 * <p><b>[P1-13] apply 半环接线</b> (WHY 规则一 · 先思后码): CC 的 suggestion 经
 * {@code context.toolUseContext.setAppState} 写入 {@code AppState.skillImprovement.suggestion},
 * 前端 {@code useSkillImprovementSurvey.ts:26} 读 AppState 显示 survey, 用户显式
 * applied/dismissed 决策 (handleSelect :53-98) 后触发 {@code applySkillImprovement}.
 * Java 端 {@code appStateRef} 是 {@code LlmAgentLoop} 实例字段 (REST 层不可达),
 * 故新增本 <b>session-keyed</b> store:
 * <ol>
 *   <li>检测器 {@link SkillImprovementHook#writeSuggestion} 写入 (CC skillImprovement.ts:160-165)</li>
 *   <li>REST decision endpoint 读取/决策 (CC useSkillImprovementSurvey.ts handleSelect)</li>
 *   <li>决策 applied → 触发 {@link SkillImprovementHook#applySkillImprovement} 生产消费方改写 SKILL.md</li>
 * </ol>
 *
 * <p><b>[DEL-SH-01] 删除 TTL/sweeper, 新增会话清理钩子</b> (用户拍板 · 对齐 CC 随会话消亡):
 * CC 中 suggestion 是<b>前端响应式状态</b> — {@code useSkillImprovementSurvey.ts:26} 直接读 AppState,
 * 用户 responded 后 :89-95 清空, 会话销毁时随 AppState 一起消失 (CC <b>无 TTL, 无 sweeper</b>).
 * 旧 Java 实现用 30 分钟 TTL + 后台 daemon 清扫线程近似 "session 结束/超时移除条目"
 * (R2I-DEC-15), 该机制 CC 无对应物 — 属 {@code ⊕} 目标端独有, 已按用户拍板删除.
 * 替代语义: {@link #removeBySession(UUID)} 在会话生命周期结束时显式清理该 session 条目
 * (对齐 CC AppState 随会话消亡), 由会话关闭点调用 (接线点见该方法 Javadoc).
 *
 * <p><b>local-only 约束 (对齐 CLAUDE.md BudgetTracker 红线)</b>: 本 store 是独立 Spring bean,
 * 不进入 AgentState / EventPublisher / STOMP / LLM payload, 不序列化.
 *
 * @param sessionId CC original: sessionId (ToolUseContext.sessionId) — suggestion 归属的会话
 * @see SkillImprovementHook
 * @see com.nexusai.apis.skillimprovement.SkillImprovementController
 * @since Session P1-13
 */
@Component
public class SkillImprovementSuggestionStore {

    private static final Logger log = LoggerFactory.getLogger(SkillImprovementSuggestionStore.class);

    /**
     * PendingSuggestion · CC original: {@code AppState.skillImprovement.suggestion =
     * { skillName, updates }} (skillImprovement.ts:160-165 / useSkillImprovementSurvey.ts:14-17).
     *
     * @param skillName CC original: skillName — 待改进的 skill 名
     * @param updates   CC original: updates — SkillUpdate[] (LLM 检测器产出的改进项)
     */
    public record PendingSuggestion(String skillName, List<SkillUpdate> updates) {}

    /** session 作用域 suggestion 存储 · 对齐 CC AppState.skillImprovement.suggestion 每会话一份语义. */
    // [session-id-short] 键空间 UUID→String（short 形态 sess-xxx），写侧 SkillImprovementHook / 读侧 Controller 直键
    private final ConcurrentHashMap<String, PendingSuggestion> store = new ConcurrentHashMap<>();

    /**
     * 写入 suggestion · CC original: {@code setAppState({skillImprovement:{suggestion}})}
     * (skillImprovement.ts:160-165). 同 session 重复写入覆盖 (对齐 CC setAppState 覆盖语义).
     *
     * @param sessionId  归属会话
     * @param suggestion 待定 suggestion
     */
    public void put(String sessionId, PendingSuggestion suggestion) {
        if (sessionId == null || suggestion == null) {
            return;
        }
        store.put(sessionId, suggestion);
        if (log.isDebugEnabled()) {
            log.debug("Skill improvement suggestion 写入 store: session={} skill={}",
                    sessionId, suggestion.skillName());
        }
    }

    /**
     * 读取 suggestion (不移除) · 供 REST GET /suggestion 轮询决策闸门.
     *
     * @param sessionId 归属会话
     * @return 待定 suggestion; 无 → null
     */
    public PendingSuggestion peek(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return store.get(sessionId);
    }

    /**
     * 读取并移除 suggestion · 供 REST POST /decision 决策后清空
     * (CC original: {@code setAppState({...prev, skillImprovement:{suggestion:null}})},
     * useSkillImprovementSurvey.ts:89-95).
     *
     * @param sessionId 归属会话
     * @return 已移除的 suggestion; 无 → null
     */
    public PendingSuggestion remove(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        PendingSuggestion removed = store.remove(sessionId);
        if (log.isDebugEnabled() && removed != null) {
            log.debug("Skill improvement suggestion 移出 store: session={} removed=true",
                    sessionId);
        }
        return removed;
    }

    /**
     * 当前待定 suggestion 数量 · 测试断言 "store 清空" 用.
     */
    public int size() {
        return store.size();
    }

    /**
     * [DEL-SH-01] 会话清理钩子 · CC original: {@code AppState.skillImprovement.suggestion}
     * 随会话销毁自动消失 (useSkillImprovementSurvey.ts:26 响应式读 / :89-95 决策后清空;
     * CC <b>无显式 TTL</b>) — 替换旧 30 分钟 TTL 近似机制, 改为会话生命周期结束时的显式清理.
     *
     * <p><b>接线点 (受控残留 · 待 owner 接线)</b>: 会话删除入口
     * {@code com.nexusai.domain.session.SessionService#delete(String)} 在删 DB 前调
     * {@code com.nexusai.application.chat.ChatService#closeSession(String)} (Phase 4) —
     * 该点是 "会话消亡" 的权威接入点. 接线 recipe:
     * {@code store.removeBySession(ChatService.parseSessionUuid(sessionId))}
     * (sessionId 为 {@code "sess-xxxxxxxx"} 格式, 经 {@code parseSessionUuid} 稳定映射到本 store 的
     * UUID 键; ChatService.java:719). <b>不得</b>在 LlmAgentLoop.run 退出点接线 — 那会在线程
     * 每轮结束即清空 suggestion, 破坏 apply 半环 (REST 决策端点须在 loop 退出后仍可读 store).
     *
     * @param sessionId 归属会话 (short；与 {@link #put}/{@link #peek}/{@link #remove} 同一 String 键空间)
     */
    public void removeBySession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        store.remove(sessionId);
        if (log.isDebugEnabled()) {
            log.debug("Skill improvement suggestion 会话结束清理: session={}", sessionId);
        }
    }
}
