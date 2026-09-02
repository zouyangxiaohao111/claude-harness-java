package com.nexusai.application.agent.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 权限更新调度器 · 对齐 CC {@code PermissionUpdate.ts:55-206} (applyPermissionUpdate)
 *
 * <h2>职责</h2>
 * <p>把 {@link PermissionUpdate} 的 6 种 sealed record 分发到对应的操作逻辑，
 * 修改 {@link ToolPermissionContext} 并返回新实例（因为 ctx 是 immutable record）。
 *
 * <h2>6 case 分发</h2>
 * <ol>
 *   <li>{@link PermissionUpdate.AddRules} — 添加规则到指定 dest+behavior 桶，去重</li>
 *   <li>{@link PermissionUpdate.RemoveRules} — 从指定 dest + behavior 桶删除规则（CC 单桶语义）</li>
 *   <li>{@link PermissionUpdate.ReplaceRules} — 原子替换指定 dest+behavior 桶的全部规则</li>
 *   <li>{@link PermissionUpdate.SetMode} — 切换 PermissionMode</li>
 *   <li>{@link PermissionUpdate.AddDirectories} — 添加工作目录</li>
 *   <li>{@link PermissionUpdate.RemoveDirectories} — 收缩工作目录</li>
 * </ol>
 *
 * <h2>Destination → PermissionRuleSource 映射</h2>
 * <pre>
 *   USER_SETTINGS    → USER_SETTINGS
 *   PROJECT_SETTINGS → PROJECT_SETTINGS
 *   LOCAL_SETTINGS   → LOCAL_SETTINGS
 *   CLI_ARG          → CLI_ARG
 *   SESSION          → SESSION
 * </pre>
 *
 * <h2>[S16] CC spread 语义全字段保留</h2>
 * <p>CC {@code applyPermissionUpdate} 每个 case 都返回 {@code {...context, ...}}（spread），
 * <b>未修改的字段原样保留</b> —— 含 {@code strippedDangerousRules} /
 * {@code shouldAvoidPermissionPrompts} / {@code awaitAutomatedChecksBeforeDialog} /
 * {@code prePlanMode}（types/permissions.ts:427-441）。本类重建 ctx 统一走
 * {@link #newCtx}，从输入 ctx 透传这 4 个字段（S04 的 strip/restore stash 与 plan 模式
 * 退出恢复依赖该保真，见 DangerousPatternDetector.java:296-300 登记）。
 *
 * <h2>[DEL-WF1-04] source 归属由生产者负责（CC 桶 key 即归属）</h2>
 * <p>CC 的 PermissionUpdate 规则只有 ruleValue（toolName + ruleContent），无 source 字段；
 * 桶 key（destination）即规则归属（convertRulesToUpdates permissions.ts:1375-1403：
 * {@code destination: source}）。Java 端规则对象仍携带 source 字段，本类不在此归一
 * （原 {@code normalizeSource} 已随 DEL-WF1-04 删除）：生产者为 {@code AddRules}/{@code ReplaceRules}
 * 构造规则时必须以 destination 对应 source 为规则 source（WebSocketPermissionPrompter.parseRules
 * 已对齐），否则落桶后归属漂移。若出现规则 source ≠ 桶 source，applyAddRules 记 debug 日志暴露。
 *
 * <h2>不可变性</h2>
 * <p>{@link ToolPermissionContext} 的 compact constructor 使用 {@code Map.copyOf()} /
 * {@code Set.copyOf()} 做防御性 copy。因此每个 apply 操作都创建新的 mutable 副本、
 * 修改后构造新 ctx 返回。
 *
 * <h2>CC 源码对应</h2>
 * <p>{@code utils/permissions/PermissionUpdate.ts:55-188} —
 * {@code applyPermissionUpdate(update, context): ToolPermissionContext}
 * <br>{@code utils/permissions/PermissionUpdate.ts:196-206} —
 * {@code applyAllPermissionUpdates(updates, context): ToolPermissionContext}
 */
@Component
public class PermissionUpdateApplier {

    private static final Logger log = LoggerFactory.getLogger(PermissionUpdateApplier.class);

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * 应用单个 {@link PermissionUpdate}，返回新的 {@link ToolPermissionContext}。
     *
     * <p>对齐 CC {@code applyPermissionUpdate(update, context)}。
     *
     * @param update 权限更新操作（6 种 sealed record 之一）
     * @param ctx    当前工具权限上下文（不可变）
     * @return 修改后的新 ToolPermissionContext
     */
    public ToolPermissionContext apply(PermissionUpdate update, ToolPermissionContext ctx) {
        return switch (update) {
            case PermissionUpdate.AddRules a          -> applyAddRules(a, ctx);
            case PermissionUpdate.RemoveRules r       -> applyRemoveRules(r, ctx);
            case PermissionUpdate.ReplaceRules rp     -> applyReplaceRules(rp, ctx);
            case PermissionUpdate.SetMode s           -> applySetMode(s, ctx);
            case PermissionUpdate.AddDirectories ad   -> applyAddDirectories(ad, ctx);
            case PermissionUpdate.RemoveDirectories rd -> applyRemoveDirectories(rd, ctx);
        };
    }

    /**
     * 批量应用 {@link PermissionUpdate} 列表，顺序执行每条更新。
     *
     * <p>对齐 CC {@code applyPermissionUpdates}（PermissionUpdate.ts:196-206）。
     *
     * @param updates 权限更新列表（可为空，空列表返回原 ctx）
     * @param ctx     初始工具权限上下文
     * @return 全部应用后的 ToolPermissionContext
     */
    public ToolPermissionContext applyAll(List<PermissionUpdate> updates, ToolPermissionContext ctx) {
        ToolPermissionContext current = ctx;
        for (PermissionUpdate update : updates) {
            current = apply(update, current);
        }
        return current;
    }

    // ========================================================================
    // 6 case 实现
    // ========================================================================

    /**
     * 添加规则到指定 destination + behavior 桶。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'addRules'。
     * <ul>
     *   <li>将 destination 映射为 PermissionRuleSource</li>
     *   <li>根据 behavior 选择 allow/deny/ask 桶</li>
     *   <li>按 toolName + ruleContent 去重：已存在的规则跳过</li>
     *   <li>[DEL-WF1-04] 规则 source 由生产者负责（桶 key 即归属）；若规则 source ≠ 桶 source，
     *       记 debug 日志暴露归属漂移（不再归一）</li>
     * </ul>
     */
    private ToolPermissionContext applyAddRules(PermissionUpdate.AddRules update, ToolPermissionContext ctx) {
        PermissionRuleSource source = mapDestination(update.destination());
        PermissionBehavior behavior = update.behavior();

        // 选择目标 behavior 桶并深拷贝
        Map<PermissionRuleSource, Set<PermissionRule>> targetMap =
                selectBehaviorMap(ctx, behavior);
        Map<PermissionRuleSource, Set<PermissionRule>> newMap = deepCopyMap(targetMap);

        // 获取或创建该 source 的规则集
        Set<PermissionRule> bucket = newMap.computeIfAbsent(source, k -> new LinkedHashSet<>());

        // 逐个添加，按 toolName + ruleContent 去重（CC persist 层同款去重，
        // addPermissionRulesToSettings permissionsLoader.ts:263-270）
        int added = 0;
        for (PermissionRule rule : update.rules()) {
            // [DEL-WF1-04] source 归属由生产者保证（桶 key 即归属）；规则 source 与桶 source
            // 不一致时 fail-loud 以 debug 日志暴露归属漂移（不再归一）。
            if (rule.source() != source && log.isDebugEnabled()) {
                log.debug("AddRules: 规则 source={} 与桶 source={} 不一致（生产者应落桶前对齐 destination 对应 source，CC 桶 key 即归属）",
                    rule.source(), source);
            }
            if (!containsByValue(bucket, rule)) {
                bucket.add(rule);
                added++;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("AddRules: added {} rules to {}/{}", added, source, behavior);
        }

        return rebuildCtx(ctx, behavior, newMap);
    }

    /**
     * 删除指定规则（按 destination + behavior 桶 + 规则值匹配）。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'removeRules'（{@code PermissionUpdate.ts:139-169}）。
     * <ul>
     *   <li>按 {@code update.behavior} 选择单桶（{@code ruleKind}：allow→alwaysAllowRules /
     *       deny→alwaysDenyRules / ask→alwaysAskRules），仅从该桶删除匹配项
     *       （CC 单桶语义：{@code filter} 仅作用于 {@code context[ruleKind][destination]}，
     *       不跨桶删除——旧实现跨 3 桶是 CC 偏离，本轮修复）</li>
     *   <li>匹配键 = toolName + ruleContent（PermissionRuleValue 相等，行为不参与匹配）</li>
     *   <li>read-only source（policy/flag/command）拒绝删除 → 记 warn 日志但不抛异常</li>
     *   <li>未找到的规则 silent skip（best-effort）</li>
     * </ul>
     */
    private ToolPermissionContext applyRemoveRules(PermissionUpdate.RemoveRules update, ToolPermissionContext ctx) {
        PermissionRuleSource source = mapDestination(update.destination());
        PermissionBehavior behavior = update.behavior();

        // read-only source 保护：拒绝删除，记 warn 返回原 ctx
        if (source.isReadOnly()) {
            log.warn("RemoveRules: refused to remove rules from read-only source {}", source);
            return ctx;
        }

        // 深拷贝目标 behavior 桶（CC 单桶语义：仅删 behavior 指定桶，不跨桶）
        Map<PermissionRuleSource, Set<PermissionRule>> newMap =
                deepCopyMap(selectBehaviorMap(ctx, behavior));

        // 从目标桶中删除匹配的规则
        int removed = 0;
        for (PermissionRule rule : update.rules()) {
            removed += removeMatchingRules(newMap, source, rule);
        }
        if (log.isDebugEnabled()) {
            log.debug("RemoveRules: removed {} rules from {}/{}", removed, source, behavior);
        }

        // 重新构造 ctx（仅目标 behavior 桶被修改；CC spread 语义全字段保留）
        return rebuildCtx(ctx, behavior, newMap);
    }

    /**
     * 原子替换指定 destination + behavior 桶的全部规则。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'replaceRules'。
     * <ul>
     *   <li>先清空该 source+behavior 桶的全部规则</li>
     *   <li>再写入新的 rules（rules 可为空列表——表示清空）</li>
     *   <li>[DEL-WF1-04] 规则 source 由生产者负责（桶 key 即归属），不再归一</li>
     * </ul>
     */
    private ToolPermissionContext applyReplaceRules(PermissionUpdate.ReplaceRules update, ToolPermissionContext ctx) {
        PermissionRuleSource source = mapDestination(update.destination());
        PermissionBehavior behavior = update.behavior();

        // 深拷贝目标 behavior 桶
        Map<PermissionRuleSource, Set<PermissionRule>> targetMap =
                selectBehaviorMap(ctx, behavior);
        Map<PermissionRuleSource, Set<PermissionRule>> newMap = deepCopyMap(targetMap);

        // 原子替换：清空再写入（[DEL-WF1-04] 规则 source 由生产者保证 = 桶 source）
        Set<PermissionRule> newBucket = new LinkedHashSet<>();
        for (PermissionRule rule : update.rules()) {
            newBucket.add(rule);
        }
        newMap.put(source, newBucket);

        if (log.isDebugEnabled()) {
            log.debug("ReplaceRules: {} bucket {} -> {} rules",
                    source, behavior, newBucket.size());
        }

        return rebuildCtx(ctx, behavior, newMap);
    }

    /**
     * 切换 PermissionMode。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'setMode'（PermissionUpdate.ts:59-67
     * {@code {...context, mode: update.mode}} spread —— 保留 prePlanMode/strippedDangerousRules 等全部字段）。
     * <p>F1 修复：旧实现硬编码 {@code Map.of(), false, false, null} 会丢弃
     * {@code strippedDangerousRules}/{@code shouldAvoidPermissionPrompts}/{@code awaitAutomatedChecksBeforeDialog}/
     * {@code prePlanMode} —— EnterPlanMode 经 setMode 写 mode=PLAN 时若丢弃 prePlanMode，
     * ExitPlanMode 将无法恢复进入 plan 前的 mode（CC 偏离待修）。
     * 由于 ToolPermissionContext 是 immutable record，直接构造新 ctx（其余字段原样透传；
     * {@link #newCtx} 按 CC spread 语义透传 4 个可选字段，S16）。
     */
    private ToolPermissionContext applySetMode(PermissionUpdate.SetMode update, ToolPermissionContext ctx) {
        if (update.mode() == ctx.mode()) {
            return ctx; // 无变化，避免不必要的对象创建（CC spread 同 mode 等价）
        }
        if (log.isDebugEnabled()) {
            log.debug("SetMode: {} -> {}", ctx.mode(), update.mode());
        }
        return newCtx(ctx,
                update.mode(),
                ctx.alwaysAllowRules(),
                ctx.alwaysDenyRules(),
                ctx.alwaysAskRules(),
                ctx.additionalWorkingDirectories());
    }

    /**
     * 添加工作目录到 path sandbox 扩展集合。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'addDirectories'（PermissionUpdate.ts:122-137）。
     * <p>[OPD-WF1-01-Q1 拍板：补 destination→source 映射] CC 真源
     * {@code newAdditionalDirs.set(directory, { path: directory, source: update.destination })}
     * （PermissionUpdate.ts:130-131）——目录归属 source = {@code update.destination}（经
     * {@link #mapDestination} 一对一映射），旧实现恒 {@link PermissionRuleSource#SESSION} 名实错位
     * （{@code WORKING_DIRECTORY_SOURCE} 语义 types/permissions.ts:432：destination 决定目录归属）。
     */
    private ToolPermissionContext applyAddDirectories(PermissionUpdate.AddDirectories update, ToolPermissionContext ctx) {
        PermissionRuleSource source = mapDestination(update.destination());
        Map<String, AdditionalWorkingDirectory> newDirs = new LinkedHashMap<>(ctx.additionalWorkingDirectories());
        int before = newDirs.size();
        for (String path : update.paths()) {
            newDirs.putIfAbsent(path, new AdditionalWorkingDirectory(path, source));
        }
        int added = newDirs.size() - before;
        if (added == 0) {
            return ctx; // 全部已存在，无变化
        }
        if (log.isDebugEnabled()) {
            log.debug("AddDirectories: added {} dirs (total {}) source={}（CC PermissionUpdate.ts:130-131 source=update.destination）",
                    added, newDirs.size(), source);
        }
        return newCtx(ctx,
                ctx.mode(),
                ctx.alwaysAllowRules(),
                ctx.alwaysDenyRules(),
                ctx.alwaysAskRules(),
                newDirs);
    }

    /**
     * 从 path sandbox 扩展集合中删除工作目录。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'removeDirectories'。
     */
    private ToolPermissionContext applyRemoveDirectories(PermissionUpdate.RemoveDirectories update, ToolPermissionContext ctx) {
        Map<String, AdditionalWorkingDirectory> newDirs = new LinkedHashMap<>(ctx.additionalWorkingDirectories());
        int before = newDirs.size();
        update.paths().forEach(newDirs::remove);
        int removed = before - newDirs.size();
        if (removed == 0) {
            return ctx; // 无匹配项，无变化
        }
        if (log.isDebugEnabled()) {
            log.debug("RemoveDirectories: removed {} dirs (total {})", removed, newDirs.size());
        }
        return newCtx(ctx,
                ctx.mode(),
                ctx.alwaysAllowRules(),
                ctx.alwaysDenyRules(),
                ctx.alwaysAskRules(),
                newDirs);
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    /**
     * 将 Destination 枚举映射为 PermissionRuleSource。
     *
     * <p>5 种 destination → 5 种 source（一对一映射）。
     */
    static PermissionRuleSource mapDestination(PermissionUpdate.Destination dest) {
        return switch (dest) {
            case USER_SETTINGS    -> PermissionRuleSource.USER_SETTINGS;
            case PROJECT_SETTINGS -> PermissionRuleSource.PROJECT_SETTINGS;
            case LOCAL_SETTINGS   -> PermissionRuleSource.LOCAL_SETTINGS;
            case CLI_ARG          -> PermissionRuleSource.CLI_ARG;
            case SESSION          -> PermissionRuleSource.SESSION;
        };
    }

    /**
     * 深拷贝 Map&lt;PermissionRuleSource, Set&lt;PermissionRule&gt;&gt;，
     * 返回可变的 {@link EnumMap} + {@link LinkedHashSet}（保持插入顺序）。
     */
    private static Map<PermissionRuleSource, Set<PermissionRule>> deepCopyMap(
            Map<PermissionRuleSource, Set<PermissionRule>> original) {
        Map<PermissionRuleSource, Set<PermissionRule>> copy = new EnumMap<>(PermissionRuleSource.class);
        for (Map.Entry<PermissionRuleSource, Set<PermissionRule>> entry : original.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * 根据 behavior 选择 ctx 中对应的规则桶 Map。
     */
    private static Map<PermissionRuleSource, Set<PermissionRule>> selectBehaviorMap(
            ToolPermissionContext ctx, PermissionBehavior behavior) {
        return switch (behavior) {
            case ALLOW -> ctx.alwaysAllowRules();
            case DENY  -> ctx.alwaysDenyRules();
            case ASK   -> ctx.alwaysAskRules();
        };
    }

    /**
     * 检查 bucket 中是否已存在与 target 同 toolName + ruleContent 的规则。
     *
     * <p>匹配键 = PermissionRuleValue（toolName + ruleContent），忽略 source 和 ruleBehavior。
     */
    private static boolean containsByValue(Set<PermissionRule> bucket, PermissionRule target) {
        return bucket.stream().anyMatch(r ->
                r.ruleValue().toolName().equals(target.ruleValue().toolName()) &&
                Objects.equals(r.ruleValue().ruleContent(), target.ruleValue().ruleContent()));
    }

    /**
     * 从指定 source 的桶中删除与 target 同 toolName + ruleContent 的规则。
     *
     * @return 实际删除的数量（0 或 1，因为同一个桶内 ruleValue 唯一）
     */
    private static int removeMatchingRules(
            Map<PermissionRuleSource, Set<PermissionRule>> map,
            PermissionRuleSource source,
            PermissionRule target) {
        Set<PermissionRule> bucket = map.get(source);
        if (bucket == null || bucket.isEmpty()) {
            return 0;
        }
        // 用迭代器安全删除：找到首个匹配的 rule 并移除
        Iterator<PermissionRule> it = bucket.iterator();
        while (it.hasNext()) {
            PermissionRule existing = it.next();
            if (existing.ruleValue().toolName().equals(target.ruleValue().toolName()) &&
                    Objects.equals(existing.ruleValue().ruleContent(), target.ruleValue().ruleContent())) {
                it.remove();
                return 1;
            }
        }
        return 0;
    }

    /**
     * 根据修改后的 behavior 桶重建 ToolPermissionContext。
     *
     * <p>只有指定的 behavior 桶被修改，其余字段（含 strippedDangerousRules /
     * shouldAvoidPermissionPrompts / awaitAutomatedChecksBeforeDialog / prePlanMode）
     * 从输入 ctx 透传 —— CC spread 语义。
     */
    private static ToolPermissionContext rebuildCtx(
            ToolPermissionContext ctx,
            PermissionBehavior modifiedBehavior,
            Map<PermissionRuleSource, Set<PermissionRule>> newMap) {
        return newCtx(ctx,
                ctx.mode(),
                modifiedBehavior == PermissionBehavior.ALLOW ? newMap : ctx.alwaysAllowRules(),
                modifiedBehavior == PermissionBehavior.DENY  ? newMap : ctx.alwaysDenyRules(),
                modifiedBehavior == PermissionBehavior.ASK   ? newMap : ctx.alwaysAskRules(),
                ctx.additionalWorkingDirectories());
    }

    /**
     * [S16] 构造新 ToolPermissionContext · 从输入 ctx 透传 4 个可选字段
     * （strippedDangerousRules / shouldAvoidPermissionPrompts /
     * awaitAutomatedChecksBeforeDialog / prePlanMode），对齐 CC spread 语义
     * （{@code {...context, mode, alwaysAllowRules, ...}}，PermissionUpdate.ts:55-188）。
     */
    private static ToolPermissionContext newCtx(
            ToolPermissionContext ctx,
            PermissionMode mode,
            Map<PermissionRuleSource, Set<PermissionRule>> allow,
            Map<PermissionRuleSource, Set<PermissionRule>> deny,
            Map<PermissionRuleSource, Set<PermissionRule>> ask,
            Map<String, AdditionalWorkingDirectory> dirs) {
        return new ToolPermissionContext(
                mode,
                allow,
                deny,
                ask,
                dirs,
                ctx.isBypassPermissionsModeAvailable(),
                ctx.isAutoModeAvailable(),
                ctx.strippedDangerousRules(),
                ctx.shouldAvoidPermissionPrompts(),
                ctx.awaitAutomatedChecksBeforeDialog(),
                ctx.prePlanMode()
        );
    }
}
