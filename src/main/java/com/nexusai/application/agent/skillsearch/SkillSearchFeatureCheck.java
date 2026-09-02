package com.nexusai.application.agent.skillsearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * skill-search 子系统 feature 检查模块骨架 · 对齐 CC {@code services/skillSearch/featureCheck.ts}
 * （<b>CC 真源已确认存在</b>：Open-ClaudeCode/src/services/skillSearch/featureCheck.ts，本 checkout 已 ls 复验）。
 *
 * <p><b>Java 未接线 CC 既有实现（prompt-align TOOLS-07 更新）</b>: 本类为骨架占位，Java 侧未接线
 * CC 既有 featureCheck.ts（feature-gated，EXPERIMENTAL_SKILL_SEARCH 默认关）。CC 消费点已核实：
 * {@code constants/prompts.ts:95-99} require 定义、{@code utils/attachments.ts:2694-2697}
 * {@code feature('EXPERIMENTAL_SKILL_SEARCH') && skillSearchModules?.featureCheck.isSkillSearchEnabled()}
 * → {@code filterToBundledAndMcp}。
 *
 * <p><b>isSkillSearchEnabled 映射（避免双实现漂移）</b>: A8 filterToBundledAndMcp 门控
 * （attachments.ts:2692-2697）当前沿用现有 {@code SkillDiscoveryPrefetch.isEnabled()} wiring
 * （P3-5 concern #2 映射，LlmAgentLoop A8 双条件：featureFlags().skillPrefetch() && skillDiscoveryPrefetch
 * 启用），<b>不重指向</b>本占位 —— 避免双实现漂移 + SkillDiscoveryPrefetchTest churn。本类为未来结构归宿。
 */
public interface SkillSearchFeatureCheck {

    /**
     * 是否启用技能搜索 · CC original: {@code isSkillSearchEnabled}
     * （featureCheck.ts，prompts.ts:95-99 捕获模块后调用）。
     *
     * <p><b>feature-off 默认 false</b>: 对齐 CC flag-off 时 {@code skillSearchFeatureCheck===null}
     * 短路（attachments.ts:2694 {@code skillSearchModules?.featureCheck.isSkillSearchEnabled()}）。
     * CC 真源 featureCheck.ts 已存在（Java 未接线），待 Java 按真实 feature 语义接线后对齐。
     */
    boolean isSkillSearchEnabled();

    /**
     * 占位实现 · 默认 false（对齐 CC feature-off）。
     */
    final class Default implements SkillSearchFeatureCheck {
        private static final Logger log = LoggerFactory.getLogger(Default.class);

        @Override
        public boolean isSkillSearchEnabled() {
            if (log.isDebugEnabled()) {
                log.debug("[SkillSearchFeatureCheck] isSkillSearchEnabled 占位 false · CC 真源已存在（services/skillSearch/featureCheck.ts），Java 未接线，feature-off · prompts.ts:95-99/attachments.ts:2694-2697");
            }
            return false;
        }
    }
}
