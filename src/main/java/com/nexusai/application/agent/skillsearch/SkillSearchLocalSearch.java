package com.nexusai.application.agent.skillsearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * skill-search 子系统本地检索模块骨架 · 对齐 CC {@code services/skillSearch/localSearch.ts}
 * （<b>CC 真源已确认存在</b>：Open-ClaudeCode/src/services/skillSearch/localSearch.ts，本 checkout 已 ls 复验）。
 *
 * <p><b>Java 未接线 CC 既有实现（prompt-align TOOLS-07 更新）</b>: 本类为骨架占位，Java 侧未接线
 * CC 既有 localSearch.ts（feature-gated，EXPERIMENTAL_SKILL_SEARCH 默认关）。
 *
 * <p><b>live 宿主标注（避免双实现漂移）</b>: {@link #clearSkillIndexCache()} 的 Java 端当前
 * <b>live 宿主 = {@code com.nexusai.application.agent.skill.SkillDiscoveryPrefetch#clearSkillIndexCache}</b>
 * （P3-5）—— SkillRegistry.refresh()（commands.ts:531 {@code clearSkillIndexCache?.()}）/ McpServerService
 * list_changed 处理器（useManageMCPConnections.ts:694/:738）经 Runnable 委托调用已接线。
 * 本类为未来结构归宿，在 CC 上游源码补充并迁移接线前<b>不重复实现</b>，避免双实现漂移。
 */
public interface SkillSearchLocalSearch {

    /**
     * 清除 skill-search 索引缓存 · CC original: {@code clearSkillIndexCache}
     * （commands.ts:96-99 / useManageMCPConnections.ts:27-30 require-based 定义，
     * 调用点 :531/:694/:738 {@code clearSkillIndexCache?.()}）。
     *
     * <p><b>TODO</b>: 当前 live 宿主 = SkillDiscoveryPrefetch.clearSkillIndexCache（P3-5 已接线）；
     * CC 真源 localSearch.ts 已存在（Java 未接线）——待 Java 按真实实现接线后决定是否迁移
     * 至此（届时同步改 SkillRegistry / McpServerService / ToolRegistrationConfig 引用，独立登记）。
     * 本接口仅为未来结构归宿，不重复实现。
     */
    void clearSkillIndexCache();

    /**
     * 占位实现 · 无行为（当前 live 宿主 = SkillDiscoveryPrefetch，见类 JavaDoc，避免双实现漂移）。
     */
    final class Default implements SkillSearchLocalSearch {
        private static final Logger log = LoggerFactory.getLogger(Default.class);

        @Override
        public void clearSkillIndexCache() {
            if (log.isDebugEnabled()) {
                log.debug("[SkillSearchLocalSearch] clearSkillIndexCache 占位 no-op · live 宿主 = SkillDiscoveryPrefetch（P3-5 已接线），本类为未来结构归宿 · CC commands.ts:531");
            }
        }
    }
}
