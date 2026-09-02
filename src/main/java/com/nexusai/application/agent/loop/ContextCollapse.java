package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.compact.CompactSettingsResolver;
import com.nexusai.application.agent.compact.SnipCompactor;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * [H7-arch Phase 5 P4 C5] contextCollapse 薄门面 · 对齐 CC query.ts 调用点契约。
 *
 * <p><b>WHY 存在</b>: CC {@code contextCollapse} 模块（CONTEXT_COLLAPSE flag 控制）内部实现
 * 文件在 CC 快照中不存在，只能读 query.ts 调用点契约。Java 侧正确做法 = 对齐调用点契约 +
 * 空值保护（默认禁用）。本类实现 CC 4 方法契约：collapse drain（L2 Snip 组合）内联在本类
 * （GR-3 自旧编排器迁入，编排 API 已删除）。
 *
 * <p><b>TODO[OD-01] contextCollapse 内部算法待考证</b>: CC 快照（2026-08-21）中 contextCollapse/index.ts
 * 存在，但为 Auto-generated stub（文件头 "Auto-generated stub — replace with real implementation"，
 * applyCollapsesIfNeeded/recoverFromOverflow 均为恒等/零值返回，无真实 spawn 折叠行为）。本类实现仅为
 * 引用方语义：对齐 CC query.ts:18-19/440-447/618/800-810/1089-1117 + postCompactCleanup.ts:47 调用点契约。
 * Java 侧骨架实现 = L2 Snip 读时投影（recoverFromOverflow 内联 SnipCompactor）。若后续 CC 桩被替换为
 * 真源后与 Java 读时投影（L2 Snip）实现不一致，以 CC 真源为准对齐改造，勿将本类误当 CC 真源实现。
 *
 * <p><b>默认禁用</b>: {@link #isContextCollapseEnabled()} 返回 {@link FeatureFlags#contextCollapse()}。
 * flag 关闭时所有方法走空值保护路径（0 命中），对齐 CC flag 关闭时模块为 null。
 *
 * <p><b>CC 调用点对照</b>:
 * <ol>
 *   <li>{@code applyCollapsesIfNeeded(messages, toolUseContext, querySource)} — CC query.ts:440-447
 *       autocompact 前投影（Java: 每轮压缩管线前调用，collapse 成功后压缩管线可能 no-op）</li>
 *   <li>{@code isContextCollapseEnabled()} — CC query.ts:618 blocking-limit 跳过条件</li>
 *   <li>{@code isWithheldPromptTooLong(message, isPromptTooLongMessage, querySource)} — CC query.ts:800-810
 *       withhold 判定（Java: 消息级 PTL 判定）</li>
 *   <li>{@code recoverFromOverflow(messages, querySource)} — CC query.ts:1089-1117 PTL drain，
 *       返回 committed &gt; 0 时 continue collapse_drain_retry</li>
 *   <li>{@code resetContextCollapse()} — CC postCompactCleanup.ts:47 resetContextCollapse（压缩后清
 *       collapse 模块态，IMP-19 固定操作序列 CONTEXT_COLLAPSE 分支，feature + main-thread gate 由
 *       {@link com.nexusai.application.agent.compact.PostCompactCleanup} 控制）</li>
 * </ol>
 */
public class ContextCollapse {

    private static final Logger log = LoggerFactory.getLogger(ContextCollapse.class);

    private final FeatureFlags featureFlags;

    private final SnipCompactor snipCompactor;

    /**
     * 压缩配置 DB 实时读源 · [V52 B1-6] 可 null：null = 未接线 → 回落 FeatureFlags。
     */
    private CompactSettingsResolver settingsResolver;

    public ContextCollapse(FeatureFlags featureFlags) {
        this(featureFlags, new SnipCompactor());
    }

    /** 测试/内部注入 SnipCompactor（保持构造纯函数，便于测试显式控制）。 */
    ContextCollapse(FeatureFlags featureFlags, SnipCompactor snipCompactor) {
        this.featureFlags = featureFlags;
        this.snipCompactor = snipCompactor != null ? snipCompactor : new SnipCompactor();
    }

    /**
     * 注入压缩配置 DB 实时读源 · [V52 B1-6] @Autowired(required=false)，同
     * {@link CompactThresholdSystem#setSettingsMapper(SettingsMapper)} 回落语义（可 null）。
     *
     * @param settingsResolver 压缩配置实时读源（可 null）
     */
    public void setSettingsResolver(CompactSettingsResolver settingsResolver) {
        this.settingsResolver = settingsResolver;
    }

    /**
     * contextCollapse 是否启用 · 对齐 CC query.ts:618 {@code isContextCollapseEnabled()}。
     *
     * <p>默认返回 {@code featureFlags.contextCollapse()}（默认 false）· 对齐 CC flag 关闭时
     * {@code contextCollapse == null} → {@code ?? false}。
     * [V52 B1-6] DB {@code settings.context_collapse_enabled} 有值覆盖 FeatureFlags（null 回落）。
     */
    public boolean isContextCollapseEnabled() {
        Boolean dbEnabled = settingsResolver != null ? settingsResolver.contextCollapseEnabled() : null;
        if (dbEnabled != null) {
            if (log.isDebugEnabled()) {
                log.debug("[ContextCollapse] DB settings.context_collapse_enabled={} 覆盖 FeatureFlags",
                    dbEnabled);
            }
            return dbEnabled;
        }
        return featureFlags.contextCollapse();
    }

    /**
     * autocompact 前投影 · 对齐 CC query.ts:440-447 {@code applyCollapsesIfNeeded}。
     *
     * <p><b>WHY</b>: CC 在 autocompact 前先投影 collapsed context view——若 collapse 已把
     * 上下文降到阈值下，autocompact 变 no-op，保留 granular context 而非单一 summary。
     * Java 端没有 collapse store（读时投影），语义对齐为"先跑 collapse（L2 Snip），
     * 若有效则返回 collapse 后消息，后续压缩管线对 collapse 结果再评估"。
     *
     * @param messages       当前消息列表
     * @param toolUseContext per-call 工具上下文（CC 透传，Java 保留签名占位）
     * @param querySource    查询来源
     * @return 投影后的消息列表（默认禁用时原样返回）
     */
    public List<ChatMessageDto> applyCollapsesIfNeeded(List<ChatMessageDto> messages,
                                                       ToolUseContext toolUseContext,
                                                       String querySource) {
        if (!isContextCollapseEnabled()) {
            return messages;
        }
        DrainResult r = recoverFromOverflow(messages, querySource);
        if (r.hasEffect()) {
            log.info("[ContextCollapse] applyCollapsesIfNeeded: committed={} steps, freed={} tokens, msgs {} → {} · CC query.ts:440-447",
                r.committed(), r.tokensFreed(), messages.size(), r.messages().size());
            return r.messages();
        }
        if (log.isDebugEnabled()) {
            log.debug("[ContextCollapse] applyCollapsesIfNeeded 无暂存压缩可投影（committed=0）· CC query.ts:440-447");
        }
        return messages;
    }

    /**
     * PTL withhold 判定 · 对齐 CC query.ts:800-810 {@code isWithheldPromptTooLong}。
     *
     * <p><b>WHY</b>: CC 在流内对 recoverable 错误（PTL / max-output-tokens）先 withhold
     * （不立即 surface），等恢复路径（collapse drain / reactive compact）尝试后再决定。
     * Java 端循环是异常驱动的，本方法用于消息级判定"这条 assistant API 错误消息是否应
     * 被 collapse 子系统 withhold"。
     *
     * <p><b>[IMP2-20 C4] 生产调用点</b>: {@code LlmAgentLoop} PTL 恢复分支传实际消息
     * （{@code lastAssistantMsg}）+ 消息级谓词 {@code ErrorClassifier::isPromptTooLongMessage}
     * （CC query.ts:802-805 同形）——不再传 null + 异常闭包谓词；drain 仅在最后 assistant
     * 消息本身命中消息级 PTL 时尝试（对齐 CC {@code isWithheld413} query.ts:1070-1073）。
     *
     * @param message           待判定的消息（assistant API 错误消息）
     * @param isPromptTooLong   是否 prompt-too-long 的判定谓词（CC 传 isPromptTooLongMessage）
     * @param querySource       查询来源
     * @return true = 应 withhold（等待恢复）；默认禁用时恒 false（不 withhold）
     */
    public boolean isWithheldPromptTooLong(ChatMessageDto message,
                                           Predicate<ChatMessageDto> isPromptTooLong,
                                           String querySource) {
        if (!isContextCollapseEnabled()) {
            return false;
        }
        return isPromptTooLong.test(message);
    }

    /**
     * PTL drain · 对齐 CC query.ts:1089-1117 {@code recoverFromOverflow}。
     *
     * <p><b>WHY</b>: CC 在 PTL 时先排空暂存 collapse（轻量 L2 Snip），成功则
     * {@code continue collapse_drain_retry}，失败才落到 reactive compact。
     * Java 端无暂存队列，drain 语义对齐为"重新运行 L2 Snip"（GR-3 自旧编排器迁入）。
     *
     * @param messages    当前消息列表（可能含超大结果）
     * @param querySource 查询来源
     * @return drain 结果（committed &gt; 0 = 可 continue collapse_drain_retry）
     */
    public DrainResult recoverFromOverflow(List<ChatMessageDto> messages,
                                           String querySource) {
        if (!isContextCollapseEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[ContextCollapse] CONTEXT_COLLAPSE flag 关闭，recoverFromOverflow 0 命中 · CC query.ts:1089-1117");
            }
            return new DrainResult(messages != null ? messages : List.of(), 0, 0);
        }
        if (messages == null || messages.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[ContextCollapse] collapse drain: 消息列表为空，跳过");
            }
            return new DrainResult(messages != null ? messages : List.of(), 0, 0);
        }

        // L2: Snip 压缩 — snip_boundary + removedUuids 剔除（CC snipCompact.ts:83-147 真源语义；CC query.ts:1094-1097 recoverFromOverflow 消费面）
        //   [IMP2-23 D-19] 消费面迁移：旧宽形状 compact(List)→CompactResult 已删除，
        //   SnipResult 窄形状直用（isEffective() 等价 = tokensFreed > 0）
        int committed = 0;
        int tokensFreed = 0;
        List<ChatMessageDto> current = new ArrayList<>(messages);

        if (SnipCompactor.shouldSnip(current)) {
            SnipCompactor.SnipResult snipResult = snipCompactor.snipCompactIfNeeded(current);
            if (snipResult.tokensFreed() > 0) {
                current = snipResult.messages();
                tokensFreed += snipResult.tokensFreed();
                committed++;
                log.info("[ContextCollapse] collapse drain L2 Snip: freed={} tokens", snipResult.tokensFreed());
            }
        }

        if (committed > 0) {
            log.info("[ContextCollapse] collapse drain 完成: committed={} steps, freed={} tokens, msgs: {} → {}",
                committed, tokensFreed, messages.size(), current.size());
        } else {
            log.debug("[ContextCollapse] collapse drain 无效果: 没有可排空的压缩（messages={}）", messages.size());
        }

        return new DrainResult(current, committed, tokensFreed);
    }

    /**
     * 重置 context-collapse 模块态 · 对齐 CC postCompactCleanup.ts:47 {@code resetContextCollapse()}
     * （压缩后清空 collapse 暂存 store，随固定操作序列在 main-thread 压缩后调用）。
     *
     * <p><b>调用方</b>: {@link com.nexusai.application.agent.compact.PostCompactCleanup#runPostCompactCleanup(String)}
     * 在 {@code feature('CONTEXT_COLLAPSE')} 且 {@code isMainThreadCompact(querySource)} 时调用
     * （postCompactCleanup.ts:42-49）。subagent 压缩不得触发 —— 与 main-thread 同进程共享模块级
     * state，reset 会破坏 main-thread 的 collapse store。
     *
     * <p><b>Java 侧实现深度</b>: CC contextCollapse/index.ts 为自动生成桩（stub，无真实折叠行为，
     * OD-01），collapse 内部算法保持引用方语义 —— Java {@link #applyCollapsesIfNeeded} /
     * {@link #recoverFromOverflow}
     * 均为<b>读时投影</b>（无暂存 store，每次从消息列表重新评估）。故本方法为对齐序列的
     * 显式复位点：无持久状态需重置，仅记录日志（若后续引入 collapse 暂存 store，复位逻辑落于此）。
     */
    public void resetContextCollapse() {
        if (log.isDebugEnabled()) {
            log.debug("[ContextCollapse] resetContextCollapse：Java 侧 collapse 为读时投影（无暂存 store），无状态需重置 · CC postCompactCleanup.ts:47");
        }
    }

    /**
     * s11.x collapse drain 结果 · 对齐 CC query.ts:1098 committed 字段。
     *
     * @param messages    drain 后的消息列表（可能未变）
     * @param committed   实际应用的压缩步骤数（0 = 无暂存可排空，1 = 应用了 L2 Snip）
     * @param tokensFreed 释放的 token 数
     */
    public record DrainResult(
        List<ChatMessageDto> messages,
        int committed,
        int tokensFreed
    ) {
        public DrainResult {
            if (messages == null) {
                throw new IllegalArgumentException("DrainResult.messages is null");
            }
            if (committed < 0) {
                throw new IllegalArgumentException("DrainResult.committed must be >= 0");
            }
            if (tokensFreed < 0) {
                throw new IllegalArgumentException("DrainResult.tokensFreed must be >= 0");
            }
        }

        /** drain 是否有实际效果（至少应用了 1 个压缩步骤） */
        public boolean hasEffect() {
            return committed > 0;
        }
    }
}
