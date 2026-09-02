package com.nexusai.application.agent.permission;

import java.util.List;

/**
 * 权限更新操作 · 对齐 CC {@code types/permissions.ts:43-...} (PermissionUpdate)
 *
 * <h2>PR 3 升级</h2>
 * <p>从 PR 1 的 interface stub 升级为 sealed interface + 6 record。
 * 这 6 个 record 对应权限系统运行时可执行的所有更新操作。
 *
 * <h2>6 种 type</h2>
 * <ol>
 *   <li>{@link AddRules} — 添加规则（按 destination + behavior 分组）</li>
 *   <li>{@link RemoveRules} — 删除指定规则</li>
 *   <li>{@link ReplaceRules} — 替换该 source+behavior 的全部规则</li>
 *   <li>{@link SetMode} — 切换 PermissionMode</li>
 *   <li>{@link AddDirectories} — 添加工作目录（path sandbox 扩展）</li>
 *   <li>{@link RemoveDirectories} — 收缩工作目录</li>
 * </ol>
 *
 * <h2>CC 源码</h2>
 * <p>{@code types/permissions.ts:43-...} 定义 6 种 PermissionUpdate。
 * 对应 {@code utils/permissions/PermissionUpdate.ts:55-188} 的 {@code applyPermissionUpdate()} 6 case 切换。
 *
 * <h2>PR 1 → PR 3 升级</h2>
 * <p>PR 1 时本类型是普通 interface stub，{@link PermissionResult.Ask} 的
 * {@code suggestions} 字段 forward reference。
 * PR 3 升级为 sealed interface + 6 record。
 * 现有 {@code List<PermissionUpdate>} 调用方零改动（pattern matching 受益）。
 *
 * <h2>为什么是 sealed interface</h2>
 * <p>sealed + permits 让 PR 4+ 的 {@code switch (update)} 模式匹配在编译期
 * 保证 exhaustive——6 个 case 缺一不可。下游 IDE / javac -encoding UTF-8 会强制实现，避免
 * 漏掉某 case 导致 silent skip（CLAUDE.md 规则十二）。
 */
public sealed interface PermissionUpdate
        permits PermissionUpdate.AddRules,
                PermissionUpdate.RemoveRules,
                PermissionUpdate.ReplaceRules,
                PermissionUpdate.SetMode,
                PermissionUpdate.AddDirectories,
                PermissionUpdate.RemoveDirectories {

    /**
     * 规则的"目的地"——哪个 source。
     *
     * <p>对齐 CC {@code PermissionUpdateDestination}（{@code types/permissions.ts:147-153}）。
     * 用户弹窗"Add rule for this command"时建议写到哪里。
     *
     * <h2>5 种 destination</h2>
     * <ul>
     *   <li>{@link #USER_SETTINGS} — 写到 {@code ~/.nexusai/settings.json}（跨项目）</li>
     *   <li>{@link #PROJECT_SETTINGS} — 写到 {@code <project>/.nexusai/settings.json}（项目内）</li>
     *   <li>{@link #LOCAL_SETTINGS} — 写到 {@code .nexusai/settings.local.json}（gitignored 个人）</li>
     *   <li>{@link #CLI_ARG} — 只在本次会话内存生效（不写盘）</li>
     *   <li>{@link #SESSION} — 子 agent 临时授权（不写盘）</li>
     * </ul>
     */
    enum Destination {
        USER_SETTINGS,
        PROJECT_SETTINGS,
        LOCAL_SETTINGS,
        CLI_ARG,
        SESSION
    }

    /**
     * 添加规则到指定 destination + behavior 桶。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'addRules'（{@code PermissionUpdate.ts:55-80}）。
     *
     * <h2>语义</h2>
     * <p>把 {@code rules} 追加到 {@code destination} 的 {@code behavior} 桶里。
     * 如果规则已存在（按 toolName + ruleContent 匹配），去重跳过。
     *
     * @param destination 目标 source（必填）
     * @param rules       要添加的规则列表（非空）
     * @param behavior    allow / deny / ask（必填）
     */
    record AddRules(
            Destination destination,
            List<PermissionRule> rules,
            PermissionBehavior behavior
    ) implements PermissionUpdate {

        /**
         * compact constructor：不变量保护 + 防御性 copy。
         *
         * <p>WHY: rules 为 null/empty 会让 addRules 无操作；destination / behavior 为 null
         * 会让 PR 4+ 的 {@code applyPermissionUpdate} 无法 dispatch。
         * {@code List.copyOf} 防止外部 mutate 后续添加的规则。
         */
        public AddRules {
            if (destination == null) {
                throw new IllegalArgumentException("AddRules.destination is required");
            }
            if (rules == null || rules.isEmpty()) {
                throw new IllegalArgumentException(
                    "AddRules.rules is required and must be non-empty");
            }
            if (behavior == null) {
                throw new IllegalArgumentException("AddRules.behavior is required");
            }
            // 防御性 copy —— 防止外部 mutate 后续添加的规则污染规则集
            rules = List.copyOf(rules);
        }
    }

    /**
     * 删除指定 rules（按 destination + behavior 桶 + 规则值匹配）。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'removeRules'（{@code PermissionUpdate.ts:139-169}）。
     *
     * <h2>语义</h2>
     * <p>从 {@code destination} 的 <strong>单个</strong> {@code behavior} 桶中查找匹配
     * {@code rules} 的项并移除（CC 单桶语义：{@code ruleKind} 按 {@code update.behavior}
     * 选 allow/deny/ask 桶，{@code filter} 仅作用于 {@code context[ruleKind][destination]}）。
     * 匹配键 = {@code toolName + ruleContent}（{@link PermissionRuleValue}）。
     * 未找到的规则 silent skip（CC 行为：best-effort，不抛错）。
     *
     * @param destination 目标 source（必填） CC original: destination (types/permissions.ts:113)
     * @param rules       要删除的规则列表（非空，未找到的项 skip） CC original: rules (types/permissions.ts:114)
     * @param behavior    目标桶 allow / deny / ask（必填） CC original: behavior (types/permissions.ts:115)
     */
    record RemoveRules(
            Destination destination,
            List<PermissionRule> rules,
            PermissionBehavior behavior
    ) implements PermissionUpdate {

        /**
         * compact constructor：不变量保护 + 防御性 copy。
         *
         * <p>WHY: rules 为空意味着"删除所有规则"——意图模糊且危险，故强制非空。
         * 如果用户想清空 rules 用 {@link ReplaceRules} 替代。
         * behavior 必填（CC schema 必填，缺 behavior 拒收而非默认——未上线可破约）。
         */
        public RemoveRules {
            if (destination == null) {
                throw new IllegalArgumentException("RemoveRules.destination is required");
            }
            if (rules == null || rules.isEmpty()) {
                throw new IllegalArgumentException(
                    "RemoveRules.rules is required and must be non-empty");
            }
            if (behavior == null) {
                throw new IllegalArgumentException("RemoveRules.behavior is required");
            }
            rules = List.copyOf(rules);
        }
    }

    /**
     * 替换该 destination + behavior 桶的全部规则。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'replaceRules'（{@code PermissionUpdate.ts:99-130}）。
     *
     * <h2>语义</h2>
     * <p>把 {@code destination} 的 {@code behavior} 桶整体替换为 {@code rules}。
     * 与 {@link RemoveRules} + {@link AddRules} 区别：replace 是原子操作，
     * 不会先 remove 再 add 导致中间窗口期规则缺失。
     *
     * <p>{@code rules} 允许为空——表示"清空该桶"。
     *
     * @param destination 目标 source（必填）
     * @param rules       新规则列表（可空——表示清空）
     * @param behavior    allow / deny / ask（必填）
     */
    record ReplaceRules(
            Destination destination,
            List<PermissionRule> rules,
            PermissionBehavior behavior
    ) implements PermissionUpdate {

        /**
         * compact constructor：允许 rules 为空（清空场景）。
         *
         * <p>WHY: 与 {@link AddRules} 不同，{@code ReplaceRules(rules=[], behavior=DENY)}
         * 合法——表示"该 source 的 deny 桶清空"。故 rules=null 报，rules=[] 放行。
         */
        public ReplaceRules {
            if (destination == null) {
                throw new IllegalArgumentException("ReplaceRules.destination is required");
            }
            if (rules == null) {
                throw new IllegalArgumentException(
                    "ReplaceRules.rules is required (can be empty list to clear)");
            }
            if (behavior == null) {
                throw new IllegalArgumentException("ReplaceRules.behavior is required");
            }
            rules = List.copyOf(rules);
        }
    }

    /**
     * 切换 {@link PermissionMode}。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'setMode'（{@code PermissionUpdate.ts:60-67}）。
     *
     * <h2>语义</h2>
     * <p>立即把当前 session 的 mode 切换为 {@code mode}。影响后续所有 10 层规则检查。
     * mode=BYPASS_PERMISSIONS 时 10 层第 2a 层强制 allow（除 4 个 bypass-immune）。
     * {@code destination} 决定该 mode 更新落盘到哪个 settings source（CC schema 必填）。
     *
     * @param destination 目标 source（必填） CC original: destination (types/permissions.ts:119)
     * @param mode        目标 mode（必填） CC original: mode (types/permissions.ts:120)
     */
    record SetMode(Destination destination, PermissionMode mode) implements PermissionUpdate {

        /**
         * compact constructor：destination + mode 必填。
         *
         * <p>WHY: destination=null 无法落盘（CC persistPermissionUpdate setMode case）；
         * mode=null 让 bypass/plan/acceptEdits 等行为无法决定，调度崩溃。
         */
        public SetMode {
            if (destination == null) {
                throw new IllegalArgumentException("SetMode.destination is required");
            }
            if (mode == null) {
                throw new IllegalArgumentException("SetMode.mode is required");
            }
        }
    }

    /**
     * 添加工作目录（path sandbox 扩展）。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'addDirectories'（{@code PermissionUpdate.ts:122-137}）。
     *
     * <h2>语义</h2>
     * <p>把 {@code paths} 加入当前会话的"额外工作目录"集合。
     * 影响 {@code pathGuard} 校验——读 / 写这些路径不再被阻断。
     * 仅扩展不收缩（要收缩用 {@link RemoveDirectories}）。
     * {@code destination} 决定目录归属（CC 中映射为 {@code AdditionalWorkingDirectory.source}）。
     *
     * @param destination 目标 source（必填） CC original: destination (types/permissions.ts:124)
     * @param paths       要添加的绝对路径列表（非空） CC original: directories (types/permissions.ts:125)
     */
    record AddDirectories(Destination destination, List<String> paths) implements PermissionUpdate {

        /**
         * compact constructor：destination 必填 + paths 必填非空 + 防御性 copy。
         *
         * <p>WHY: 空 list 是 no-op；路径必须存在（runtime 校验，本构造只校验非空）。
         */
        public AddDirectories {
            if (destination == null) {
                throw new IllegalArgumentException("AddDirectories.destination is required");
            }
            if (paths == null || paths.isEmpty()) {
                throw new IllegalArgumentException(
                    "AddDirectories.paths is required and must be non-empty");
            }
            paths = List.copyOf(paths);
        }
    }

    /**
     * 删除工作目录（path sandbox 收缩）。
     *
     * <p>对齐 CC {@code applyPermissionUpdate()} case 'removeDirectories'（{@code PermissionUpdate.ts:171-183}）。
     *
     * <h2>语义</h2>
     * <p>从"额外工作目录"集合中移除 {@code paths}。
     * 移除后访问这些路径会重新被 pathGuard 阻断（除非其他 rule 允许）。
     * {@code destination} 决定目录归属（CC schema 必填）。
     *
     * @param destination 目标 source（必填） CC original: destination (types/permissions.ts:129)
     * @param paths       要移除的绝对路径列表（非空） CC original: directories (types/permissions.ts:130)
     */
    record RemoveDirectories(Destination destination, List<String> paths) implements PermissionUpdate {

        /**
         * compact constructor：destination 必填 + paths 必填非空 + 防御性 copy。
         */
        public RemoveDirectories {
            if (destination == null) {
                throw new IllegalArgumentException("RemoveDirectories.destination is required");
            }
            if (paths == null || paths.isEmpty()) {
                throw new IllegalArgumentException(
                    "RemoveDirectories.paths is required and must be non-empty");
            }
            paths = List.copyOf(paths);
        }
    }
}
