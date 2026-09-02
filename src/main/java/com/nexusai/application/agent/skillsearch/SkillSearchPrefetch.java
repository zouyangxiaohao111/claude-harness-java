package com.nexusai.application.agent.skillsearch;

import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * skill-search 子系统预取模块骨架 · 对齐 CC {@code services/skillSearch/prefetch.ts}
 * （<b>CC 真源已确认存在</b>：Open-ClaudeCode/src/services/skillSearch/prefetch.ts，本 checkout 已 ls 复验）。
 *
 * <p><b>Java 未接线 CC 既有实现（prompt-align TOOLS-07 更新）</b>: 本类只定义消费点契约接口 + 占位
 * Default 实现，<b>不伪造任何发现 / 索引行为</b>（feature-gated，EXPERIMENTAL_SKILL_SEARCH 默认关；
 * Java 亦无真实技能搜索索引 → 恒空集）。CC 真源 prefetch.ts 已存在，Java 未接线其真实行为。
 *
 * <p>消费点（CC 真源已核实，不依赖注释）:
 * <ul>
 *   <li>{@code query.ts:66} {@code const skillPrefetch = feature('EXPERIMENTAL_SKILL_SEARCH')
 *       ? require('../services/skillSearch/prefetch.js')... : null} —— flag-off 时 undefined</li>
 *   <li>{@code query.ts:331-335} {@code skillPrefetch?.startSkillDiscoveryPrefetch(null, messages, toolUseContext)}
 *       —— per-iteration turn 起（模型流式前）启动发现；usesFindWritePivot 守卫（写迭代才真正发现）</li>
 *   <li>{@code query.ts:1620-1628} {@code if (skillPrefetch && pendingSkillPrefetch) {
 *       collectSkillDiscoveryPrefetch(pendingSkillPrefetch) → skillAttachments → createAttachmentMessage
 *       → yield + toolResults.push}}</li>
 *   <li>{@code attachments.ts:806-810} {@code skillSearchModules.prefetch.getTurnZeroSkillDiscovery(
 *       input, messages ?? [], context)} —— turn-0 user input 阻塞发现（userInputAttachments 内）</li>
 * </ul>
 *
 * <p><b>feature-off 默认短路</b>: Default 实现 start 恒返回 null / collect &amp; getTurnZero 恒空集
 * → 生产行为零变化（对齐 CC flag-off 时 {@code skillPrefetch===null}，{@code ?.()} 短路）。
 */
public interface SkillSearchPrefetch {

    /**
     * 启动一轮技能发现预取 · CC original: {@code startSkillDiscoveryPrefetch}
     * （query.ts:331-335，per-iteration 模型流式前调用）。
     *
     * @param input          user prompt 信号（CC 调用点传 {@code null} —— 该参数真实语义待上游源码补充）
     * @param messages       当前消息快照（CC original: messages）
     * @param toolUseContext 工具上下文（CC original: toolUseContext）
     * @return 预取句柄；feature-off / 无真实实现 → null（对齐 CC {@code skillPrefetch?.()} 短路）
     */
    PrefetchHandle startSkillDiscoveryPrefetch(String input,
                                               List<ChatMessageDto> messages,
                                               ToolUseContext toolUseContext);

    /**
     * 收集本轮已完成的技能发现 · CC original: {@code collectSkillDiscoveryPrefetch}
     * （query.ts:1620-1628，工具循环后收集 → skill_discovery attachment）。
     *
     * @param handle {@link #startSkillDiscoveryPrefetch} 返回的句柄
     * @return 发现技能列表；Default 恒空集 → 无附件注入
     */
    List<SkillSearchSignals.SkillDiscovery> collectSkillDiscoveryPrefetch(PrefetchHandle handle);

    /**
     * turn-0 user input 技能发现 · CC original: {@code getTurnZeroSkillDiscovery}
     * （attachments.ts:806-810，userInputAttachments 内 {@code maybe('skill_discovery', ...)}）。
     *
     * <p>TODO: CC 第三参 {@code context} 为完整 agent context（shape 未知，Java 侧对应类型不明确），
     * 当前以 {@link ToolUseContext} 近似承载，待上游源码补充后对齐。
     *
     * @param input          user 输入（CC original: input）
     * @param messages       消息数组（CC original: messages ?? []）
     * @param toolUseContext 工具上下文（CC original: context，近似映射）
     * @return 发现技能列表；Default 恒空集
     */
    List<SkillSearchSignals.SkillDiscovery> getTurnZeroSkillDiscovery(
        String input, List<ChatMessageDto> messages, ToolUseContext toolUseContext);

    /**
     * 预取句柄占位 · CC original: {@code pendingSkillPrefetch}（query.ts:331）。
     * 具体句柄类型随 prefetch.ts 真实实现，当前仅占位（record 字段不假定 CC 契约）。
     */
    record PrefetchHandle(String turnId) {
    }

    /**
     * 占位实现 · Java 未接线 CC 既有实现 + feature-gated no-op，不伪造发现行为。
     *
     * <p>{@code enabled} 镜像 CC {@code feature('EXPERIMENTAL_SKILL_SEARCH') ? require(...) : null}
     * （query.ts:66）—— enabled=false（默认）时 start 恒 null（{@code ?.()} 短路）、collect 恒空集；
     * enabled=true（测试构造注入）时 start 返回占位句柄使 wiring 可观测，但 collect 仍恒空集
     * （无真实技能搜索索引，不伪造发现结果，参考 CS-DEL-1 stripReinjectedAttachments 前科）。
     */
    final class Default implements SkillSearchPrefetch {
        private static final Logger log = LoggerFactory.getLogger(Default.class);

        private final boolean enabled;
        /** [prompt-align TOOLS-05] 查询意图归一化模块 · 对齐 CC prefetch.ts:326
         *  {@code normalizeQueryIntent(input)} 搜索前置步；null → 跳过（零变化）。 */
        private final SkillSearchIntentNormalize intentNormalize;

        /** feature-off 默认 · 对齐 CC EXPERIMENTAL_SKILL_SEARCH flag 关闭。 */
        public Default() {
            this(false);
        }

        /** @param enabled feature-on 构造注入（测试可观测 wiring） */
        public Default(boolean enabled) {
            this(enabled, null);
        }

        /** [prompt-align TOOLS-05] 完整构造 · normalize 可注入（null → 跳过）。 */
        public Default(boolean enabled, SkillSearchIntentNormalize intentNormalize) {
            this.enabled = enabled;
            this.intentNormalize = intentNormalize;
            if (log.isDebugEnabled()) {
                log.debug("[SkillSearchPrefetch] 占位实现 enabled={} normalize={} · CC 真源 services/skillSearch/prefetch.ts 已存在（Java 未接线），feature-gated · query.ts:66",
                    enabled, intentNormalize != null);
            }
        }

        @Override
        public PrefetchHandle startSkillDiscoveryPrefetch(String input,
                                                          List<ChatMessageDto> messages,
                                                          ToolUseContext toolUseContext) {
            if (!enabled) {
                if (log.isDebugEnabled()) {
                    log.debug("[SkillSearchPrefetch] startSkillDiscoveryPrefetch 占位返回 null · flag-off 短路（CC query.ts:66 skillPrefetch===null ?.()）· query.ts:331-335");
                }
                return null;
            }
            // enabled=true：返回占位句柄使 wiring 可观测（真实发现逻辑待上游 prefetch.ts 补充后对齐）
            if (log.isDebugEnabled()) {
                log.debug("[SkillSearchPrefetch] startSkillDiscoveryPrefetch 占位句柄（真实发现待上游源码补充）· query.ts:331-335");
            }
            return new PrefetchHandle(String.valueOf(System.currentTimeMillis()));
        }

        @Override
        public List<SkillSearchSignals.SkillDiscovery> collectSkillDiscoveryPrefetch(PrefetchHandle handle) {
            // 恒空集：无真实技能搜索索引，不伪造发现结果（参考 CS-DEL-1 前科）
            if (log.isDebugEnabled()) {
                log.debug("[SkillSearchPrefetch] collectSkillDiscoveryPrefetch 占位恒空 · CC 真源已存在（services/skillSearch/prefetch.ts），Java 未接线无真实索引 · query.ts:1620-1628");
            }
            return List.of();
        }

        @Override
        public List<SkillSearchSignals.SkillDiscovery> getTurnZeroSkillDiscovery(
            String input, List<ChatMessageDto> messages, ToolUseContext toolUseContext) {
            // [prompt-align TOOLS-05] 查询意图归一化前置步 · 对齐 CC prefetch.ts:326
            //   const searchQuery = await normalizeQueryIntent(input)
            //   —— 门控 + CJK + 非空已在 normalize 内短路，不浪费 Haiku 调用。Java 无真实
            //   技能搜索索引（本骨架恒空集）→ 归一化结果当前无生产消费方（调用点登记 +
            //   模块由 SkillSearchIntentNormalizeTest 单测覆盖）。
            if (intentNormalize != null && input != null && !input.isBlank()) {
                String searchQuery = intentNormalize.normalizeQueryIntent(input);
                if (log.isDebugEnabled()) {
                    log.debug("[SkillSearchPrefetch] getTurnZeroSkillDiscovery 归一化前置步 searchQuery={} · CC prefetch.ts:326",
                        searchQuery != null && searchQuery.length() > 60 ? searchQuery.substring(0, 60) + "…" : searchQuery);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[SkillSearchPrefetch] getTurnZeroSkillDiscovery 占位恒空 · CC 真源已存在（services/skillSearch/prefetch.ts），Java 未接线 · attachments.ts:806-810");
            }
            return List.of();
        }
    }
}
