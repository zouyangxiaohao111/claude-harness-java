package com.nexusai.application.agent.skillsearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * skill-search 子系统信号模块骨架 · 对齐 CC {@code services/skillSearch/signals.ts}
 * （<b>CC 真源已确认存在</b>：Open-ClaudeCode/src/services/skillSearch/signals.ts，本 checkout 已 ls 复验）。
 *
 * <p><b>Java 未接线 CC 既有实现（prompt-align TOOLS-07 更新）</b>: CC 端仅 {@code utils/attachments.ts:88}
 * type-only import {@code DiscoverySignal}（shape 未知），故本类只做占位 record，
 * <b>不伪造任何信号类型语义</b>。CC 真源 signals.ts 已存在，Java 未接线其真实 shape。
 *
 * <p>消费点：{@code skill_discovery} attachment 的 {@code signal} 字段
 * （attachments.ts:538-540 {@code {type:'skill_discovery', skills, signal: DiscoverySignal, source}}）。
 * Java 端 {@code AttachmentMessageDto} 以 {@code String discoverySignal} 承载
 * （shape 未知 → 不透传强类型），本 record 为未来结构归宿。
 */
public interface SkillSearchSignals {

    /**
     * 技能发现信号占位 record · CC original: {@code DiscoverySignal}
     * （attachments.ts:88 type-only import，shape 未知）。
     *
     * <p>TODO: CC 真源 signals.ts 已存在（Java 未接线）——待 Java 按真实 shape 接线后对齐；
     * 当前字段 {@code name}/{@code reason} 为占位，不假定 CC 契约。
     */
    record DiscoverySignal(String name, String reason) {
    }

    /**
     * 单条发现技能 · CC original: {@code skill_discovery} attachment skills 元素
     * （attachments.ts:537-540 {@code {name, description, shortId?}}）。
     *
     * @param name        技能名（CC original: s.name）
     * @param description 技能描述（CC original: s.description）
     * @param shortId     短 ID，可选（CC original: s.shortId）
     */
    record SkillDiscovery(String name, String description, String shortId) {
    }

    /**
     * 占位实现 · Java 未接线 CC 既有实现 → 纯类型模块无行为，不伪造发现行为（参考 CS-DEL-1 无条件 no-op 前科）。
     */
    final class Default implements SkillSearchSignals {
        private static final Logger log = LoggerFactory.getLogger(Default.class);

        /** 空构造 · 模块为纯类型容器（DiscoverySignal / SkillDiscovery record），无运行时行为。 */
        public Default() {
            if (log.isDebugEnabled()) {
                log.debug("[SkillSearchSignals] 占位模块初始化 · CC 真源 services/skillSearch/signals.ts 已存在（Java 未接线），仅 type-only import（attachments.ts:88）");
            }
        }
    }
}
