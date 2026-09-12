package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 边界读侧（BoundaryReader）· 对齐 CC utils/messages.ts:4608-4656 + services/compact/snipProjection.ts。
 *
 * <p><b>WHY 存在（IMP-05）</b>: D-22 删除 {@code CompactBoundaryMessage.isCompactBoundary/extractSource}
 * 文本前缀读侧死代码后，由本组件按 CC 结构化 subtype 判别边界。boundary 在消息流中为
 * ChatMessageDto（role=system + subtype='compact_boundary'），读侧以 subtype 判别（INV-4 单一表示）。
 *
 * <h2>CC 对齐（CC 原名 + 行号，grep -n 自验 2026-08-04 / 2026-08-18）</h2>
 * <table>
 *   <tr><th>本方法</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>isCompactBoundaryMessage</td><td>isCompactBoundaryMessage(message)</td><td>messages.ts:4608-4612</td></tr>
 *   <tr><td>findLastCompactBoundaryIndex</td><td>findLastCompactBoundaryIndex(messages)</td><td>messages.ts:4618-4628</td></tr>
 *   <tr><td>getMessagesAfterCompactBoundary(messages, includeSnipped)</td><td>getMessagesAfterCompactBoundary(messages, options?)</td><td>messages.ts:5083-5096</td></tr>
 *   <tr><td>isSnipBoundaryMessage</td><td>isSnipBoundaryMessage(message)</td><td>snipProjection.ts:15-18</td></tr>
 *   <tr><td>projectSnippedView</td><td>projectSnippedView(messages)</td><td>snipProjection.ts:35-60</td></tr>
 *   <tr><td>applyPreservedSegmentRelink</td><td>applyPreservedSegmentRelinks(messages)</td><td>sessionStorage.ts:1876-1992</td></tr>
 * </table>
 *
 * <p><b>[D4 解法1-精简 · 2026-09-12 用户裁定] 保留段读侧重挂</b>：kept 段写侧<b>零改写</b>（对齐 CC
 * {@code recordTranscript} 按 uuid dedup 跳过、磁盘上原地不动），故它物理排在 boundary <b>之前</b>；
 * 本组件在切片后按 boundary 上的 {@code preservedSegment} 把它按原 seq 序接回 {@code anchorUuid} 之后
 * （对齐 CC 在读侧打 {@code parentUuid} 补丁的形态）。<b>只读 seq、不改写</b> —— 排序权威仍只有 seq 一个。
 * 详见 {@link #applyPreservedSegmentRelink(List, int, List)}。
 *
 * <p><b>snip 投影（2026-08-18 真源对齐）</b>: CC getMessagesAfterCompactBoundary 在
 * {@code !options?.includeSnipped} 时会对切片应用 {@code projectSnippedView}（messages.ts:5088-5093），
 * 剔除被 snip 删除的消息（removedUuids），使模型面数组不含陈旧历史。
 * 本组件按已入库真源 {@code Open-ClaudeCode/src/services/compact/snipProjection.ts} 完整实现
 * isSnipBoundaryMessage + projectSnippedView（此前 TODO[OD-01] 悬空，vendored snapshot 缺
 * snipProjection.js —— 2026-08-18 真源已取回，投影从「引用方语义」升级为「真源实现」）。
 * <b>[N2 2026-09-11]</b> 投影**不再受 HISTORY_SNIP 运行时开关门控**（回放门，见下方 [N2] 段）；
 * 无 boundary / 无 removedUuids 时投影原样返回，故既有单参调用方在「无 snip 历史」时零行为变化。
 *
 * <p><b>[D4 双门源合并] 门公式单一来源</b>: {@link #isHistorySnipEnabled(CompactSettingsResolver,
 * FeatureFlags)} 为唯一纯函数实现；{@code LlmAgentLoop} snip 步骤（门①）委托它。
 * <b>[N2 2026-09-11]</b> 该静态槽读侧（原「门②」，即本组件 {@link #setFeatureFlags} /
 * {@link #setSettingsResolver} 支撑的 {@code isHistorySnipEnabled()} 无参重载）**已无消费方**
 * （投影去门控后成为死接线）——保留待用户裁定是否清理，详见下方 [N2] 段。
 *
 * <p><b>[snip-state-fix 2026-09-10 语义澄清]</b> {@code includeSnipped} 的两种取值在本仓的用途：
 * <ul>
 *   <li>{@code false}（单参重载缺省，CC 的 model-facing 用法）：用于**模型面**——本仓
 *       {@code LlmAgentLoop} 循环入口用它生成 {@code state.rawMessages()} 的内容（state 在本仓循环里
 *       扮演 CC {@code messagesForQuery} 的角色）。</li>
 *   <li>{@code true}：CC 在 REPL/UI 面用（{@code REPL.tsx:3167-3169}，保留滚动回看）。本仓**当前
 *       无可用的调用方**——因为「REPL 全量历史」在本仓由 **DB** 承担（前端/轨迹读 DB，DB
 *       append-only 不删行），循环内 state 不需要第二份全量。</li>
 * </ul>
 *
 * <p><b>[N2 2026-09-11 · 回放门 vs 执行门拆分 · 用户拍板 (B)「历史 snip 不复活」]</b>
 * 关掉开关只应禁止「产生新 snip」，不应让**已执行过**的 snip 失效。故：
 * <ol>
 *   <li><b>回放门（本组件 {@code getMessagesAfterCompactBoundary}）</b>：去门控 —— 只要
 *       {@code !includeSnipped} 就应用 {@link #projectSnippedView}，与 compact boundary 剥离
 *       （恒定、本就无门）对称。</li>
 *   <li><b>执行门（{@code LlmAgentLoop} snip 步骤）</b>：仍读开关
 *       （{@code if (historySnipEnabled)}，公式 = {@link #isHistorySnipEnabled(CompactSettingsResolver,
 *       FeatureFlags)}）—— 管「本轮是否执行**新** snip」，语义不变。</li>
 * </ol>
 * <b>⛔ 5 处静态调用面逐一审计结论（实施时 grep 复核，行号为当日实测）：全部为「回放」语义</b>
 * <table>
 *   <tr><th>调用点</th><th>用途</th><th>回放/执行</th></tr>
 *   <tr><td>{@code LlmAgentLoop.java:5085}</td><td>循环入口 boundary 剥离 + snip 投影写回
 *       {@code state.rawMessages()}（state 扮演 CC {@code messagesForQuery} 角色）→ 模型面</td>
 *       <td><b>回放</b></td></tr>
 *   <tr><td>{@code CompactCommand.java:217}</td><td>手动 {@code /compact}：剥离结果即压缩输入
 *       （喂给摘要模型）→ 模型面</td><td><b>回放</b></td></tr>
 *   <tr><td>{@code PartialCompactService.java:318}</td><td>partial compact：剥离后按
 *       {@code messageId} 定 pivot；**pivot == -1 即「已 snipped/pre-compact」的判据**
 *       （:322-330 抛 404 提示）——去门控才能保住该判据正确</td><td><b>回放</b></td></tr>
 *   <tr><td>{@code StreamCompactSummary.java:744}</td><td>流式 fallback 的 apiMessages
 *       （{@code [getMessagesAfterCompactBoundary(messages), summaryRequest]}）→ 模型面</td>
 *       <td><b>回放</b></td></tr>
 *   <tr><td>{@code SkillifySkillRegistrar.java:301}</td><td>{@code extractUserMessages(
 *       getMessagesAfterCompactBoundary(context.messages))} → 喂 skillify 提示词 → 模型面</td>
 *       <td><b>回放</b></td></tr>
 * </table>
 * 无一处需要「执行」语义 —— 执行门只存在于 {@code LlmAgentLoop} 的 snip 步骤（不经过本方法）。
 *
 * <p><b>[N2 遗留 · 待用户裁定清理]</b> 去门控后，本组件的静态槽 {@link #setFeatureFlags} /
 * {@link #setSettingsResolver}（生产接线 {@code ToolRegistrationConfig.java:995/:1002}）及其唯一的
 * 消费方 {@code isHistorySnipEnabled()} 无参重载**已无任何消费方**（死接线）。本次**未删除**
 * （用户拍板项，非本项范围）；如需清理须同步移除 {@code ToolRegistrationConfig} 两行接线与
 * {@code BoundaryReaderHistorySnipSingleSourceTest} 的对应断言。
 */
public final class BoundaryReader {

    private static final Logger log = LoggerFactory.getLogger(BoundaryReader.class);

    /** CC original: subtype 'snip_boundary'（snipProjection.ts:17 / snipCompact.ts:99）· snip 边界消息 subtype */
    private static final String SUBTYPE_SNIP_BOUNDARY = "snip_boundary";

    /** CC original: snipMetadata.removedUuids（snipProjection.ts:31 / snipCompact.ts:99-103）· snip 删除消息 uuid 数组 */
    private static final String SNIP_METADATA_REMOVED_UUIDS = "removedUuids";

    /** CC original: compactMetadata.preservedSegment（message.ts:85-93）· 保留段接线图（写方 = CompactBoundaryMessage.annotateBoundaryWithPreservedSegment；读方 = 本组件的重挂投影）。 */
    private static final String COMPACT_METADATA_PRESERVED_SEGMENT = "preservedSegment";

    /** CC original: preservedSegment.headUuid（compact.ts:384）· 保留段首条消息 uuid。 */
    private static final String SEG_HEAD_UUID = "headUuid";

    /** CC original: preservedSegment.anchorUuid（compact.ts:371-380 / :1117）· 保留段在新链中紧邻的前一条消息 uuid。 */
    private static final String SEG_ANCHOR_UUID = "anchorUuid";

    /** CC original: preservedSegment.tailUuid（compact.ts:385）· 保留段末条消息 uuid。 */
    private static final String SEG_TAIL_UUID = "tailUuid";

    /**
     * [2026-08-18] HISTORY_SNIP 门静态槽 · 默认全关。
     *
     * <p>与 {@link MicroCompactor} / {@link StreamCompactSummary} 同模式（static volatile + 测试
     * setter，IMP2-01 先例）：getMessagesAfterCompactBoundary 为静态纯函数，生产 bean 无
     * FeatureFlags 注入面，以静态槽位承载门控。
     *
     * <p><b>⚠️ [N2 2026-09-11] 本槽已无消费方（死接线，待用户裁定是否清理）</b>：snip 投影去门控
     * 后，唯一读者 {@code isHistorySnipEnabled()} 无参重载已无调用点。生产写入点仍为
     * {@code ToolRegistrationConfig.microCompactor} @Bean（ToolRegistrationConfig.java:1002），
     * 写入后不会被读取 —— 保留仅为避免在未获授权时擅自删除。详见类注释 [N2 遗留] 段。
     */
    private static volatile com.nexusai.application.agent.loop.FeatureFlags featureFlags =
        com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;

    /**
     * [V52 X1-3] 压缩配置 DB 实时读源静态槽位。
     *
     * <p>同 {@link #setFeatureFlags} 静态槽位模式（BoundaryReader 为纯静态工具类，无实例注入面）；
     * 生产在 {@code ToolRegistrationConfig.microCompactor} @Bean 接线。
     *
     * <p><b>⚠️ [N2 2026-09-11] 本槽已无消费方（死接线，待用户裁定是否清理）</b>：同上，唯一读者
     * {@code isHistorySnipEnabled()} 无参重载已无调用点。生产写入点
     * {@code ToolRegistrationConfig.java:995}。
     */
    private static volatile com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver;

    /**
     * [2026-08-18] 注入 feature 门（对齐 {@link MicroCompactor#setFeatureFlags} 先例）。
     *
     * <p><b>⚠️ [N2 2026-09-11] 死接线</b>：生产写入点 {@code ToolRegistrationConfig.microCompactor}
     * @Bean（ToolRegistrationConfig.java:1002），但 snip 投影去门控后已无读者。保留待用户裁定。
     *
     * @param flags feature 门（null → 回退 ALL_DISABLED）
     */
    public static void setFeatureFlags(com.nexusai.application.agent.loop.FeatureFlags flags) {
        featureFlags = flags != null
            ? flags
            : com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;
        if (log.isDebugEnabled()) {
            log.debug("BoundaryReader setFeatureFlags: historySnip={}（[N2] 该槽已无消费方，"
                    + "snip 投影回放门不受其门控）", featureFlags.historySnip());
        }
    }

    /**
     * [V52 X1-3] 压缩配置 DB 实时读源静态注入（可 null）。
     *
     * <p><b>⚠️ [N2 2026-09-11] 死接线</b>：生产写入点 ToolRegistrationConfig.java:995，已无读者。
     *
     * @param resolver 压缩配置实时读源（可 null）
     */
    public static void setSettingsResolver(com.nexusai.application.agent.compact.CompactSettingsResolver resolver) {
        settingsResolver = resolver;
        if (log.isDebugEnabled()) {
            log.debug("BoundaryReader setSettingsResolver: 注入={}（[N2] 该槽已无消费方）",
                resolver != null);
        }
    }

    /**
     * HISTORY_SNIP 门公式 · <b>[D4 双门源合并] 全仓唯一纯函数实现</b>
     * （CC {@code feature('HISTORY_SNIP')}，query.ts:141/401 + messages.ts:5088 读侧判定）。
     *
     * <p><b>[N2 2026-09-11] 当前唯一消费方 = 执行门</b>：{@code LlmAgentLoop} snip 步骤
     * （{@code if (historySnipEnabled)}，管「本轮是否产生**新** snip」）。原先的「门②」（本组件
     * 静态槽读侧的 snip 投影门控）已随回放门去门控而消失。
     *
     * <p>DB {@code settings.history_snip_enabled} 有值（非 null）即覆盖并返回；null → 回落
     * {@code flags.historySnip()}（零行为变化）。resolver <b>每次调用实时读 DB</b>，绝不缓存进静态槽
     * （前端 PUT /api/v1/settings 后下一轮即生效）。
     *
     * @param resolver 压缩配置实时读源（可 null = 未接线 → 直接回落 flags）
     * @param flags    feature 门（可 null → 视作全关，对齐 flag-off）
     * @return true = HISTORY_SNIP 开启（含 DB 覆盖）
     */
    public static boolean isHistorySnipEnabled(CompactSettingsResolver resolver,
                                               com.nexusai.application.agent.loop.FeatureFlags flags) {
        Boolean dbSnip = resolver != null ? resolver.historySnipEnabled() : null;
        if (dbSnip != null) {
            return dbSnip;
        }
        return flags != null && flags.historySnip();
    }

    /**
     * HISTORY_SNIP 门 DB-aware 解析（静态槽输入）· [V52 X1-3]。
     *
     * <p><b>⚠️ [N2 2026-09-11] 已无消费方（死代码，待用户裁定是否清理）</b>：原供读侧 snip 投影
     * 门控；投影改「回放门」（不受开关门控）后，本重载连同其两个静态输入
     * （{@link #settingsResolver} / {@link #featureFlags}）一并成为死接线。**本次未删除**——
     * 属用户拍板项，不在本项范围。详见类注释 [N2 遗留] 段。
     *
     * <p>[D4 双门源合并] 无参重载委托 {@link #isHistorySnipEnabled(CompactSettingsResolver,
     * com.nexusai.application.agent.loop.FeatureFlags)}。
     *
     * @return true = HISTORY_SNIP 开启（含 DB 覆盖）
     */
    @SuppressWarnings("unused")
    private static boolean isHistorySnipEnabled() {
        return isHistorySnipEnabled(settingsResolver, featureFlags);
    }

    private BoundaryReader() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 判别一个消息是否为 compact boundary · 对齐 CC {@code isCompactBoundaryMessage}
     * （messages.ts:4608-4612）：{@code type==='system' && subtype==='compact_boundary'}。
     *
     * <p>Java 侧等价：role==system && subtype=='compact_boundary'。microcompact_boundary
     * 不在判别范围（CC 仅匹配 compact_boundary，microCompact 返回 {messages, compactionInfo?}
     * 不产 boundary，D-19）。
     *
     * @param message 待检查消息
     * @return true 表示是 compact boundary
     */
    public static boolean isCompactBoundaryMessage(ChatMessageDto message) {
        return message != null
            && message.role() == Role.system
            && CompactBoundaryMessage.SUBTYPE_COMPACT_BOUNDARY.equals(message.subtype());
    }

    /**
     * 找出消息数组中最后一个 compact boundary 的下标 · 对齐 CC {@code findLastCompactBoundaryIndex}
     * （messages.ts:4618-4628）：向后扫描，返回最后一个边界下标；无边界返回 -1。
     *
     * @param messages 消息列表
     * @return 最后一个 compact boundary 下标，无边界时 -1
     */
    public static int findLastCompactBoundaryIndex(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return -1;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isCompactBoundaryMessage(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从最后一个 compact boundary（含）向后切片 · 对齐 CC {@code getMessagesAfterCompactBoundary}
     * （messages.ts:5083-5096）：无边界返回全量；有边界返回从最后一个边界（含）到末尾的新列表。
     * 默认 {@code includeSnipped=false}（CC 缺省）：对切片应用 {@link #projectSnippedView}
     * （messages.ts:5088-5093）。<b>[N2 2026-09-11]</b> 投影**不受** HISTORY_SNIP 运行时开关门控
     * （回放门·历史 snip 不复活）；无 boundary / 无 removedUuids 时投影原样返回，故无 snip 历史时
     * 行为与改前一致。
     *
     * <p><b>WHY 返回新列表</b>: CC {@code messages.slice(boundaryIndex)} 生成新数组；下游
     * （budget/snip/autocompact）可能改写切片，返回新列表避免 subList 视图把改动回灌原消息链
     * （05 DRIFT-17 边界剥离语义）。
     *
     * @param messages 消息列表
     * @return 从最后一个 boundary（含）向后的切片；无边界时全量（不可变快照）
     */
    public static List<ChatMessageDto> getMessagesAfterCompactBoundary(List<ChatMessageDto> messages) {
        return getMessagesAfterCompactBoundary(messages, false);
    }

    /**
     * 从最后一个 compact boundary（含）向后切片 + 可选 snip 投影 · 对齐 CC
     * {@code getMessagesAfterCompactBoundary(messages, options?)}（messages.ts:5083-5096）：
     * 切片语义与单参版相同；随后在 {@code !includeSnipped} 时对切片应用
     * {@link #projectSnippedView}，剔除被 snip 删除的陈旧消息。
     *
     * <p><b>[N2 2026-09-11] 回放门 ≠ 执行门（有意偏离 CC 字面、保持 CC 语义）</b>:
     * CC 的字面门是 {@code !options?.includeSnipped && feature('HISTORY_SNIP')}（messages.ts:5088）。
     * 但 CC 的 {@code feature()} 是**构建期常量**（模块级三元，见 {@code tools.ts:141} /
     * {@code query.ts:141}）→ CC 侧「回放已执行的 snip」与「本轮执行新 snip」不可能分叉。本仓把该
     * 开关实现成**运行时 DB 开关**（{@code settings.history_snip_enabled}），因此必须拆成两个门：
     * <ul>
     *   <li><b>回放门（本方法）</b>：**不受开关门控** —— 只要历史里有 snip_boundary，就剔除其
     *       {@code removedUuids}。理由：关掉开关只应禁止「产生新 snip」，已执行过的 snip 是既成事实；
     *       且必须与 compact boundary 对称（后者的剥离在本方法上方恒定执行、同样无门）。</li>
     *   <li><b>执行门</b>：{@code LlmAgentLoop} snip 步骤（{@code if (historySnipEnabled)}）仍读开关，
     *       管「本轮是否产生**新** snip」——语义不变。</li>
     * </ul>
     *
     * <p><b>includeSnipped 语义（不变）</b>: true = 保留被 snip 删除的消息（CC 在 REPL/UI 面这么用：
     * {@code REPL.tsx:3167-3169} 全屏 compact 处理器，保留滚动回看）；false = 默认（CC 的 model-facing
     * 用法），应用投影。
     * ⚠️ 本仓**当前只有 false 的调用方**（5 处静态调用面，见类注释的 [N2] 段——经逐一审计全部为
     * 「回放」语义）——「REPL 全量历史」由 DB 承担，循环内 state 不需第二份全量；
     * 若将来要新增 true 的调用方，必须同时审计所有吃 {@code state.rawMessages()} 的模型相邻消费点。
     *
     * @param messages      消息列表
     * @param includeSnipped 是否保留被 snip 删除的消息（CC options.includeSnipped，messages.ts:4648）
     * @return 切片 + 可选 snip 投影后的列表；无边界时全量
     */
    public static List<ChatMessageDto> getMessagesAfterCompactBoundary(
            List<ChatMessageDto> messages, boolean includeSnipped) {
        if (messages == null || messages.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("读侧切片: 消息列表为空，返回空列表");
            }
            return messages == null ? List.of() : messages;
        }
        int boundaryIndex = findLastCompactBoundaryIndex(messages);
        List<ChatMessageDto> sliced;
        if (boundaryIndex == -1) {
            if (log.isDebugEnabled()) {
                log.debug("读侧切片: 无 compact boundary，返回全量 {} 条消息", messages.size());
            }
            sliced = List.copyOf(messages);
        } else {
            sliced = new ArrayList<>(messages.subList(boundaryIndex, messages.size()));
            if (log.isDebugEnabled()) {
                log.debug("读侧切片: 最后一个 compact boundary 下标={}，切片 {} 条消息（含 boundary）",
                    boundaryIndex, sliced.size());
            }
            // ── [D4 解法1-精简 · 读侧重挂] 保留段（kept）在写侧原地不动 → 物理排在 boundary 之前 →
            //    切片天然丢段。此处按 boundary 上贴的 preservedSegment 接线图，把它按原 seq 序接回
            //    anchorUuid 之后（对齐 CC sessionStorage.ts:1876-1992 applyPreservedSegmentRelinks 的
            //    head.parentUuid = anchorUuid 读侧内存修补语义）。⚠️ 只读不写，seq 仍是唯一排序输入。
            sliced = applyPreservedSegmentRelink(messages, boundaryIndex, sliced);
        }
        // ── [N2 2026-09-11 · 回放门 vs 执行门拆分] snip 投影**不再受** isHistorySnipEnabled 门控 ──
        // 决策 (B)「历史 snip 不复活」：关掉开关只应禁止「产生**新** snip」，绝不能让**已执行过**的
        // snip 失效 —— 历史 snip_boundary 是既成事实，与 compact boundary 同类（后者的剥离在本方法
        // 上方恒定执行、同样无门），二者必须对称。
        //
        // ⚠️ 这是**有意偏离 CC 字面、但保持 CC 语义**的改造（CC 对照见类注释）：
        //   · CC 门 = {@code !options?.includeSnipped && feature('HISTORY_SNIP')}（messages.ts:5088，
        //     已读实际 TS 源码复核），而 {@code feature()} 是**构建期常量** —— 它在 CC 里出现于
        //     模块级三元（tools.ts:141 {@code const SnipTool = feature('HISTORY_SNIP') ? ... : null}、
        //     query.ts:141 {@code const snipModule = feature('HISTORY_SNIP') ? ... : null}）→ 一旦构建
        //     完成即不可能翻转 ⇒ 「回放」与「执行」在 CC **天然同源、不可能分叉**。
        //   · 本仓把它实现成了**运行时 DB 开关**（settings.history_snip_enabled）→ 才必须把两个语义
        //     拆成两个门：本处 = **回放门**（无门控，只要历史有 boundary 就回放既成事实）；门① =
        //     {@code LlmAgentLoop} 的 snip 步骤（仍读开关，管「本轮是否执行**新** snip」）。
        //   · 偏离的只是字面（少一次 isHistorySnipEnabled 判据），保持的是语义：「有 boundary」在 CC
        //     中等价于「flag 曾开启」（flag 关时 CC 侧 SnipTool 根本注册不出、绝不会产生 boundary）。
        //     故「!includeSnipped && flag」≡「!includeSnipped && 存在 boundary」。
        //   · 不误伤保证：无 boundary 或 removedUuids 为空时 {@link #projectSnippedView} 原样返回
        //     （:325-331），故「门关 + 历史无 snip」行为与改前逐字节一致。
        if (!includeSnipped) {
            if (log.isDebugEnabled()) {
                log.debug("读侧切片: includeSnipped=false → 应用 snip 投影"
                        + "（回放门·不受 HISTORY_SNIP 运行时开关门控，N2 决策 B：历史 snip 不复活；"
                        + "CC messages.ts:5088 构建期常量的等价语义）");
            }
            return projectSnippedView(sliced);
        }
        if (log.isDebugEnabled()) {
            log.debug("读侧切片: 跳过 snip 投影（includeSnipped=true，UI/REPL 全量面，"
                    + "CC REPL.tsx:3167-3169 用法；CC messages.ts:5088 options.includeSnipped 分支）");
        }
        return sliced;
    }

    /**
     * 保留段读侧重挂 · 对齐 CC {@code applyPreservedSegmentRelinks}
     * （sessionStorage.ts:1876-1992，唯一调用点 :3808 = {@code loadTranscriptFile} 内）的
     * <b>等价实现</b>。
     *
     * <h2>WHY（CC 对照，只读实际 TS 行为）</h2>
     * CC 的 kept 行<b>在磁盘上原地不动</b>：{@code recordTranscript} 按 uuid dedup 跳过
     * （sessionStorage.ts:1457-1468），JSONL append-only <b>不能改</b> ⇒ 它们的 {@code parentUuid}
     * 仍指着被压缩掉的老消息。CC 在<b>读侧</b>（加载 transcript 后）打补丁：
     * <pre>
     *   head.parentUuid = anchorUuid            // 保留段接到锚点之后（:1935-1940）
     *   anchor 的其它 children → parentUuid = tailUuid   // 锚点原有的后续内容挪到保留段之后（:1942-1948）
     * </pre>
     * <b>不是落盘前、不是复制、不是移动</b>。本仓无 {@code parentUuid} 链结构，顺序权威是 {@code seq}
     * —— 故等价实现 = 在<b>读侧切片后</b>把「boundary 之前、且在 {@code [headUuid..tailUuid]} 区间内」
     * 的行<b>按原 seq 序</b>拼接到 {@code anchorUuid} 所指位置之后。
     *
     * <h2>⛔ 红线：不引入第二套排序权威</h2>
     * 本方法<b>只读</b> {@code seq}（入参列表序 = seq 序）与 {@code anchorUuid} 的位置，
     * <b>不改写任何 seq</b>，也不产生新的排序键。写侧（{@code MessageService.appendPostCompactMessages}）
     * 因此可以做到零改写（真 append-only）。任何「据此重排 seq」的改动都是被禁止的。
     *
     * <h2>为什么 anchor 规则天然给出方向正确的顺序</h2>
     * CC 的 {@code anchorUuid} = 「新链里紧邻 {@code keep[0]} 之前那条」（compact.ts:371-380）：
     * <ul>
     *   <li><b>up_to / SM（后缀保留）</b>：anchor = 最后一条 summary → 拼接后
     *       {@code [boundary, summary, kept...]}（= {@code CompactionResult.buildPostCompactMessages} 顺序）；</li>
     *   <li><b>from（前缀保留）</b>：anchor = boundary 本身（compact.ts:1112-1115）→ 拼接后
     *       {@code [boundary, kept..., summary]}（= {@code CompactionResult.buildPartialPostCompactMessages}
     *       的 from 分支顺序 —— REPL.tsx:4950-4951 direction-aware 重组）。</li>
     * </ul>
     * 即：本重挂产出的顺序与压缩当时的内存数组顺序<b>逐项一致</b>，故「本轮内存视图 == 下轮 DB 派生视图」。
     *
     * <h2>退化行为（数据异常时不硬拼）</h2>
     * <ol>
     *   <li><b>无 seg</b>（boundary 无 {@code compactMetadata.preservedSegment}）：原样返回切片
     *       —— 与改造前逐字节一致（全量压缩不写 seg；旧数据若写侧已把 kept 搬到 boundary 之后，
     *       其 seg 虽在但下条判据会命中）。</li>
     *   <li><b>seg 存在但 head/tail 在列表里解析不到</b>（行被删 / id 损坏）：ERROR 留痕 + 原样返回切片。</li>
     *   <li><b>{@code tailUuid} 落在 boundary 及其后</b>（= 改造前的旧数据形态：写侧已把 kept 之 seq
     *       重挂到 boundary 之后）：WARN 留痕 + 原样返回 —— 此时 kept 已在切片内，
     *       <b>不重复插入</b>，故新旧数据都幂等。</li>
     *   <li><b>{@code anchorUuid} 不在切片内</b>：ERROR 留痕 + 原样返回切片。</li>
     * </ol>
     * 与 CC 的差异如实登记：CC 走链校验失败时 {@code return} 会<b>连剪枝一起跳过</b>
     * （宁可多加载整段历史也不剪错，sessionStorage.ts:1921-1932）；本仓的 boundary 剥离是
     * 切片语义（恒定执行、无独立剪枝步骤），无法"撤销切片"，故退化为<b>不重挂</b>并显式留痕。
     *
     * @param full          全量消息（seq 序；未切片）
     * @param boundaryIndex 最后一个 compact boundary 的下标（切片起点）
     * @param sliced        已从 boundaryIndex 切出的列表（本方法不修改它）
     * @return 重挂后的新列表；无 seg / 数据异常 → 原样返回 {@code sliced}
     */
    static List<ChatMessageDto> applyPreservedSegmentRelink(
            List<ChatMessageDto> full, int boundaryIndex, List<ChatMessageDto> sliced) {
        if (full == null || sliced == null || boundaryIndex < 0 || boundaryIndex >= full.size()) {
            return sliced;
        }
        ChatMessageDto boundary = full.get(boundaryIndex);
        Map<String, Object> meta = boundary.compactMetadata();
        Object segObj = meta == null ? null : meta.get(COMPACT_METADATA_PRESERVED_SEGMENT);
        if (!(segObj instanceof Map<?, ?> seg)) {
            if (log.isDebugEnabled()) {
                log.debug("保留段重挂: 最后一条 compact boundary 无 preservedSegment（全量压缩 / 老数据）→"
                        + "不重挂，切片 {} 条（行为与改前一致）", sliced.size());
            }
            return sliced;
        }
        String headUuid = asString(seg.get(SEG_HEAD_UUID));
        String anchorUuid = asString(seg.get(SEG_ANCHOR_UUID));
        String tailUuid = asString(seg.get(SEG_TAIL_UUID));
        if (isBlank(headUuid) || isBlank(anchorUuid) || isBlank(tailUuid)) {
            log.error("保留段重挂: preservedSegment 字段残缺，放弃重挂（不硬拼）: boundaryId={} "
                    + "headUuid={} anchorUuid={} tailUuid={} —— 顺序兜底仍只有 seq",
                boundary.id(), headUuid, anchorUuid, tailUuid);
            return sliced;
        }
        int headIdx = indexOfId(full, headUuid);
        int tailIdx = indexOfId(full, tailUuid);
        if (headIdx < 0 || tailIdx < 0 || headIdx > tailIdx) {
            // CC 对照：applyPreservedSegmentRelinks 的 tail→head 走链校验失败 → logEvent
            //   ('tengu_relink_walk_broken') 并直接 return（:1921-1932）。本仓等价判据 =
            //   「head/tail 都在列表里，且 head 不晚于 tail」（无链，故只能校验区间合法）。
            log.error("保留段重挂: head/tail 无法在消息列表内解析成合法区间，放弃重挂（不硬拼）: "
                    + "boundaryId={} headUuid={}(idx={}) tailUuid={}(idx={}) 列表={} 条"
                    + "（CC sessionStorage.ts:1921-1932 走链失败同义：宁可少加载也不剪错）",
                boundary.id(), headUuid, headIdx, tailUuid, tailIdx, full.size());
            return sliced;
        }
        if (tailIdx >= boundaryIndex) {
            // 改造前的旧数据形态：写侧曾把 kept 之 seq 重挂到 boundary 之后 → kept 物理已在切片内。
            // 此处必须 no-op，否则重复插入同一批行（同一行出现两次）。
            if (log.isDebugEnabled()) {
                log.debug("保留段重挂: kept 区间 [{}(idx={})..{}(idx={})] 不在 boundary(idx={}) 之前"
                        + "（旧数据形态：kept 已由写侧重挂进切片）→ 不重挂（幂等，避免重复插入）",
                    headUuid, headIdx, tailUuid, tailIdx, boundaryIndex);
            }
            return sliced;
        }
        int anchorIdx = indexOfId(sliced, anchorUuid);
        if (anchorIdx < 0) {
            log.error("保留段重挂: anchorUuid 不在切片内，放弃重挂（不硬拼）: boundaryId={} anchorUuid={}"
                    + "（from 方向应为 boundary 自身 / up_to·SM 方向应为最后一条 summary）",
                boundary.id(), anchorUuid);
            return sliced;
        }
        // 区间是 seq 序上的连续子序列（full 已按 seq 排）→ 直接 subList，不重新排序（红线：唯一权威仍是 seq）。
        List<ChatMessageDto> kept = new ArrayList<>(full.subList(headIdx, tailIdx + 1));
        List<ChatMessageDto> out = new ArrayList<>(sliced.size() + kept.size());
        out.addAll(sliced.subList(0, anchorIdx + 1));
        out.addAll(kept);
        out.addAll(sliced.subList(anchorIdx + 1, sliced.size()));
        if (log.isInfoEnabled()) {
            log.info("保留段重挂: boundaryId={} 把 kept {} 条（[{}(idx={})..{}(idx={})]，原 seq 未改写）"
                    + "拼接到 anchor={}(切片下标={}) 之后 → 视图 {} → {} 条（CC sessionStorage.ts:1876-1992 读侧语义）",
                boundary.id(), kept.size(), headUuid, headIdx, tailUuid, tailIdx,
                anchorUuid, anchorIdx, sliced.size(), out.size());
        }
        return out;
    }

    /** 在列表中按 id 定位下标（无匹配 → -1）。仅用于保留段读数，不参与排序。 */
    private static int indexOfId(List<ChatMessageDto> messages, String id) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessageDto m = messages.get(i);
            if (m != null && id.equals(m.id())) {
                return i;
            }
        }
        return -1;
    }

    /** JSON 值 → String（Jackson 反序列化自 DB compact_metadata 列；非字符串 / 缺失 → null）。 */
    private static String asString(Object v) {
        return v instanceof String s ? s : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 判别一个消息是否为 snip boundary 标记 · 对齐 CC {@code isSnipBoundaryMessage}
     * （snipProjection.ts:15-18）：{@code message.type==='system' && subtype==='snip_boundary'}。
     *
     * <p>Java 侧等价：role==system && subtype=='snip_boundary'（含 null 防护）。
     * snip boundary 是 subtype='snip_boundary' 的 system 消息，可携带
     * {@code snipMetadata.removedUuids} 记录 snip 操作删除的消息 uuid（snipProjection.ts:5-9）。
     *
     * @param message 待检查消息
     * @return true 表示是 snip boundary 标记
     */
    public static boolean isSnipBoundaryMessage(ChatMessageDto message) {
        return message != null
            && message.role() == Role.system
            && SUBTYPE_SNIP_BOUNDARY.equals(message.subtype());
    }

    /**
     * 投影「snipped 视图」· 对齐 CC {@code projectSnippedView}（snipProjection.ts:35-60）：
     * 遍历收集所有 snip boundary 的 {@code snipMetadata.removedUuids} → removedSet；set 非空时
     * 过滤掉 uuid 在 removedSet 中的消息（boundary 自身保留），set 为空返回原数组。
     *
     * <p><b>模型面语义</b>: getMessagesAfterCompactBoundary 在 compact boundary 切片后经本方法
     * 进一步过滤被 snip 删除的消息，使模型面数组不含陈旧历史（snipProjection.ts:20-30）；
     * REPL 保留全量历史用于 UI 滚动回看，故模型面路径需同时应用 compact 切片 + snip 过滤。
     *
     * <p><b>泛型擦除防护</b>: {@code snipMetadata} 为 {@code Map<String,Object>}，
     * {@code removedUuids} 值经 {@code instanceof List<?>} 判定，元素经 {@code instanceof String}
     * 判定（值可为 List&lt;String&gt; 或 List&lt;?&gt;，需 null/类型防护）。
     *
     * @param messages 消息数组（可能含 0..N 个 snip boundary）
     * @return 剔除被删除消息后的新数组；无 removedUuids 时原数组（同引用）
     */
    public static List<ChatMessageDto> projectSnippedView(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        // 收集所有 snip boundary 删除的 uuid（snipProjection.ts:36-53）
        Set<String> removedSet = new HashSet<>();
        for (ChatMessageDto msg : messages) {
            if (isSnipBoundaryMessage(msg)) {
                Map<String, Object> meta = msg.snipMetadata();
                if (meta != null) {
                    Object removedUuidsObj = meta.get(SNIP_METADATA_REMOVED_UUIDS);
                    if (removedUuidsObj instanceof List<?> removedUuids) {
                        for (Object uuidObj : removedUuids) {
                            if (uuidObj instanceof String uuid) {
                                removedSet.add(uuid);
                            }
                        }
                    }
                }
            }
        }
        if (removedSet.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("snip 投影: 无 removedUuids，返回原数组 {} 条消息（snipProjection.ts:55-57）",
                    messages.size());
            }
            return messages;
        }
        // 过滤 uuid 在 removedSet 中的消息（boundary 自身保留，snipProjection.ts:59）
        List<ChatMessageDto> projected = messages.stream()
            .filter(m -> !removedSet.contains(m.id()))
            .toList();
        if (log.isDebugEnabled()) {
            log.debug("snip 投影: removedSet={} 条 uuid，过滤 {} → {} 条消息（snipProjection.ts:59）",
                removedSet.size(), messages.size(), projected.size());
        }
        return projected;
    }
}
