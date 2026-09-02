package com.nexusai.application.agent.skillsearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * skill-search 子系统远端状态模块骨架 · 对齐 CC {@code services/skillSearch/remoteSkillState.ts}
 * （<b>CC 真源已确认存在</b>：Open-ClaudeCode/src/services/skillSearch/remoteSkillState.ts，本 checkout 已 ls 复验）。
 *
 * <p><b>Java 未接线 CC 既有实现（prompt-align TOOLS-07 更新）</b>: CC 消费点 =
 * {@code tools/SkillTool.ts:110-113} {@code remoteSkillModules = { remoteSkillState,
 * remoteSkillLoader, telemetry, featureCheck }}（remote 执行路径，CC 侧亦 experimental，SkillTool.ts:141-143
 * {@code was_discovered} gate）。Java <b>无 remote execution</b> → 纯骨架无消费接线，不为它伪造行为。
 *
 * <p>TODO: CC 真源 remoteSkillState.ts 已存在 + Java remote 执行路径落地后接线；
 * 届时按 CC SkillTool.ts:110-113 接线（Java 侧对应 SkillToolImpl，独立登记）。
 */
public interface SkillSearchRemoteState {
    // TODO: CC 真源 services/skillSearch/remoteSkillState.ts 已存在，Java 未接线（当前纯骨架，Java 无 remote execution）。

    /**
     * 占位实现 · 无行为（Java 无 remote execution，纯骨架）。
     */
    final class Default implements SkillSearchRemoteState {
        private static final Logger log = LoggerFactory.getLogger(Default.class);

        /** 空构造 · 纯骨架，无消费接线（SkillTool.ts:110 remoteSkillModules，Java 无 remote 路径）。 */
        public Default() {
            if (log.isDebugEnabled()) {
                log.debug("[SkillSearchRemoteState] 占位骨架初始化 · CC 真源 services/skillSearch/remoteSkillState.ts 已存在（Java 未接线），Java 无 remote execution · SkillTool.ts:110-113");
            }
        }
    }
}
