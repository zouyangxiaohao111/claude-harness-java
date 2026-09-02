package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * [X3 部分删除] skill-search 索引清除契约宿主 · P3-5 对齐 CC {@code clearSkillIndexCache}。
 *
 * <p><b>X3 处置（2026-08-04 收尾，全局反思 required_rework 5）</b>：原零行为发现骨架
 * {@code startSkillDiscoveryPrefetch / collectSkillDiscoveryPrefetch}（CC query.ts:331-335/:1620-1628
 * 契约，Java 无真实技能搜索服务 → collect 恒返回 {@code List.of()}）已整段删除，LlmAgentLoop 3 处
 * 调用点同步清理（X3 删除时点 grep {@code startSkillDiscoveryPrefetch|collectSkillDiscoveryPrefetch|SkillDiscoveryPending} 0 命中）。
 * <p><b>C-30 更新（2026-08-05）</b>：发现骨架消费点已重挂至 {@code skillsearch.SkillSearchPrefetch}
 * （LlmAgentLoop:2333/:3349，feature-gated 占位恒 null/空）——「0 命中」断言仅指 X3 删除时点，非当前状态。
 *
 * <p><b>为什么保留本类</b>：P3-5 已把本类接入为 skill-search <b>索引清除</b>宿主 ——
 * {@link #clearSkillIndexCache()} 在 flag-on 时被 {@code SkillRegistry.refresh()}（commands.ts:531）/
 * {@code McpToolPool} list_changed 处理器（useManageMCPConnections.ts:694/:738）经 Runnable 委托调用，
 * {@code isEnabled()} 被 LlmAgentLoop A8 的 filterToBundledAndMcp 门控消费（attachments.ts:2692-2697
 * EXPERIMENTAL_SKILL_SEARCH 映射）。整类删除会破坏 P3-5 接线（SkillRegistry/McpToolPool/McpServerService/
 * ToolRegistrationConfig 均引用），故按任务逃生口「P3-5 已引入 skill-search 索引联动依赖该骨架则重新评估
 * 并登记」裁决为<b>部分删除</b>：只删零行为发现骨架，保留索引清除宿主。登记见
 * 09-open-decisions.md §4 X3 行（已决策-部分删除）。
 *
 * <p><b>flag 门控</b>: {@code enabled=false}（默认，对齐 CC EXPERIMENTAL_SKILL_SEARCH flag 关闭）时
 * {@link #clearSkillIndexCache()} no-op + debug 日志（对齐 CC flag-off 时 {@code clearSkillIndexCache===undefined}，
 * {@code ?.()} 短路）；enabled=true 时<b>当前无真实技能搜索索引仍 no-op</b>（concern #30 子系统范围外，
 * 结构就位待填充），但递增 {@link #clearCount()} 使调用点可观测。不造'假索引'（参考 CS-DEL-1
 * stripReinjectedAttachments 无条件 no-op 前科）。
 */
public class SkillDiscoveryPrefetch {

    private static final Logger log = LoggerFactory.getLogger(SkillDiscoveryPrefetch.class);

    /** 是否启用 · 默认 false（对齐 CC EXPERIMENTAL_SKILL_SEARCH flag 关闭） */
    private final boolean enabled;

    /**
     * P3-5: clearSkillIndexCache 被调次数（仅 enabled 时递增）· 测试可观测 wiring。
     */
    private final AtomicInteger clearCount = new AtomicInteger();

    public SkillDiscoveryPrefetch(boolean enabled) {
        this.enabled = enabled;
        if (log.isDebugEnabled()) {
            log.debug("[SkillDiscoveryPrefetch] 技能发现预取: {}", enabled ? "启用" : "禁用");
        }
    }

    /**
     * 是否启用 · 对齐 CC query.ts:66 {@code EXPERIMENTAL_SKILL_SEARCH}。
     * 消费方：LlmAgentLoop A8 filterToBundledAndMcp 门控（P3-5 concern #2 isSkillSearchEnabled 映射）。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * P3-5: 清除 skill-search 索引缓存 · CC original: {@code clearSkillIndexCache}
     * （useManageMCPConnections.ts:27-30 / commands.ts:96-99 require-based 定义，
     * 调用点 :694/:738/:531 {@code clearSkillIndexCache?.()}）。
     *
     * <p><b>flag 门控</b>: 镜像 CC {@code feature('EXPERIMENTAL_SKILL_SEARCH') ? require(...).clearSkillIndexCache : undefined}
     * —— enabled=false 时本方法 no-op + debug 日志（对齐 CC flag-off 时 clearSkillIndexCache===undefined，
     * {@code ?.()} 短路）；enabled=true 时<b>当前无真实技能搜索索引仍 no-op</b>（concern #30
     * skill-search 子系统范围外，结构就位待填充），但递增 {@link #clearCount()} 使调用点可观测。
     */
    public void clearSkillIndexCache() {
        if (!enabled) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillDiscoveryPrefetch] EXPERIMENTAL_SKILL_SEARCH flag 关闭，clearSkillIndexCache no-op · CC useManageMCPConnections.ts:27-30");
            }
            return;
        }
        clearCount.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("[SkillDiscoveryPrefetch] clearSkillIndexCache 触发 (count={}) · 当前无真实技能搜索索引，结构就位待填充 · CC commands.ts:531/useManageMCPConnections.ts:694/:738",
                clearCount.get());
        }
    }

    /** P3-5: clearSkillIndexCache 被调次数（enabled 时递增）· 测试可观测 */
    public int clearCount() {
        return clearCount.get();
    }
}
