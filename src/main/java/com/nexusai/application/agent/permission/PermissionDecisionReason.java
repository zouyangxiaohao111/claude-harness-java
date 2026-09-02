package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.PermissionResult;

import java.util.Map;

/**
 * 决策归因 · 对齐 CC {@code types/permissions.ts:271-324}
 *
 * <h2>11 种 type</h2>
 * 教学版只识别 3 种（rule / mode / 其他）。CC 实际有 13 种 fine-grained 归因——
 * 日志、审计、用户回看"为什么被拒绝"、A/B 评估分类器效果都依赖此。本骨架保留 11 种，
 * 其中 {@code workingDir} / {@code permissionPromptTool} 在 CC 是细分版本，按
 * 用户文档（HTML §1.2）合并。
 *
 * <h2>关键设计</h2>
 * 第 1f / 1g 层（内容特定 rule / safetyCheck）的归因是 bypass-immune：
 * 即使在 {@code bypassPermissions} 模式也必须 ask。
 *
 * <h2>自引用</h2>
 * {@link SubcommandResults#reasons()} 是 {@code Map<String, PermissionResult>}，
 * 与 {@link PermissionResult} 互引用。同包内编译可解，Java 编译器无问题。
 *
 * <h2>[R32-b12 D-3] OTel source 映射</h2>
 * <p>{@link #decisionReasonToOTelSource(PermissionDecisionReason, PermissionResult.Behavior)}
 * 把 11 种 reason 映射为 CC Open-ClaudeCode/src/services/tools/toolExecution.ts:207-250
 * 同名 OTel {@code source} 字段（{@code "rule"} / {@code "hook"} / {@code "config"} /
 * {@code "user_temporary"} / {@code "user_permanent"} / {@code "user_reject"}）.
 * 由 {@link com.nexusai.application.agent.tool.ToolDecisionInfo} 写入
 * {@code ToolUseContext.toolDecisions} map.
 */
public sealed interface PermissionDecisionReason
        permits PermissionDecisionReason.Rule,
                PermissionDecisionReason.Mode,
                PermissionDecisionReason.SubcommandResults,
                PermissionDecisionReason.PermissionPromptTool,
                PermissionDecisionReason.Hook,
                PermissionDecisionReason.AsyncAgent,
                PermissionDecisionReason.SandboxOverride,
                PermissionDecisionReason.Classifier,
                PermissionDecisionReason.WorkingDir,
                PermissionDecisionReason.SafetyCheck,
                PermissionDecisionReason.Other {

    /**
     * 规则匹配命中。来源：deny/ask/allow rule from 8 sources
     * （{@link PermissionRuleSource}）。
     */
    record Rule(PermissionRule rule) implements PermissionDecisionReason {
        public Rule {
            if (rule == null) {
                throw new IllegalArgumentException("Rule.rule is null");
            }
        }
    }

    /**
     * {@link PermissionMode} 决定（bypass / plan / acceptEdits 等）。
     */
    record Mode(PermissionMode mode) implements PermissionDecisionReason {
        public Mode {
            if (mode == null) {
                throw new IllegalArgumentException("Mode.mode is null");
            }
        }
    }

    /**
     * bash 复合子命令的 Map 决策。{@code key=子命令}，{@code value=PermissionResult}。
     */
    record SubcommandResults(Map<String, PermissionResult> reasons) implements PermissionDecisionReason {
        public SubcommandResults {
            reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
        }
    }

    /**
     * SDK 外部权限工具结果（{@code permission_prompt_tool}）。
     *
     * @param toolName   外部工具名
     * @param toolResult 外部工具的返回结果（任意类型 —— 强类型化在 PR 3 视 SDK 决定）
     */
    record PermissionPromptTool(String toolName, Object toolResult) implements PermissionDecisionReason {
        public PermissionPromptTool {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("PermissionPromptTool.toolName is blank");
            }
            // toolResult 可以为 null
        }
    }

    /**
     * Hook 决策（PreToolUse / PostToolUse 等）。
     *
     * @param hookName   hook 名称（如 {@code "PreToolUse:Read"})
     * @param hookSource hook 来源（用户 / 项目 / 内置）
     * @param reason     hook 提供的拒绝/允许原因（可为 {@code null}）
     */
    record Hook(String hookName, String hookSource, String reason) implements PermissionDecisionReason {
        public Hook {
            if (hookName == null || hookName.isBlank()) {
                throw new IllegalArgumentException("Hook.hookName is blank");
            }
            // hookSource / reason 可以为 null
        }
    }

    /**
     * 异步 agent 拒绝。
     */
    record AsyncAgent(String reason) implements PermissionDecisionReason {
        public AsyncAgent {
            if (reason == null) {
                throw new IllegalArgumentException("AsyncAgent.reason is null");
            }
        }
    }

    /**
     * 沙箱覆盖（{@code excludedCommand} / {@code dangerouslyDisableSandbox}）。
     *
     * <p>严格对齐 CC {@code Open-ClaudeCode/src/types/permissions.ts:299-302}:
     * <pre>{@code
     * | {
     *     type: 'sandboxOverride'
     *     reason: 'excludedCommand' | 'dangerouslyDisableSandbox'
     *   }
     * }</pre>
     *
     * <p><b>WHY enum 化</b>: CC 是字面量联合 (closed set, 2 值), 用 {@code String}
     * 会让任何拼写错误（如 {@code "ExcludeCommand"}）静默通过编译, 在
     * {@link #decisionReasonToOTelSource} 里悄无声息地落到 default case 返回 "config"
     * —— 既不报错也不正确, 是教科书级契约漂移. 改 enum 把封闭集合编译期钉死,
     * 拼写错误立即 {@code ClassNotFoundException}.
     *
     * <p><b>WHY 嵌套 enum (非顶层)</b>: 枚举仅用于 {@link SandboxOverride#reason} 字段
     * 含义标注, 无跨类引用需求, 嵌套表达"这是 SandboxOverride 子分类"的语义;
     * 若未来 CC 增加第 3 个字面量 (例如 {@code 'adminOverride'}), 改本 enum
     * + SandboxOverride record 字段即可, 不会污染顶层命名空间.
     */
    record SandboxOverride(SandboxOverrideReason reason) implements PermissionDecisionReason {
        public SandboxOverride {
            if (reason == null) {
                throw new IllegalArgumentException("SandboxOverride.reason is null");
            }
        }

        /**
         * Sandbox 覆盖原因枚举 · 严格对齐 CC {@code types/permissions.ts:299-302} 字面量联合.
         *
         * <p>每个枚举值对应一个 CC 字符串字面量 ({@link #ccLiteral()}),
         * 用于 CC 真源字符串比较 / telemetry 序列化 / 调试日志.
         *
         * <p><b>WHY 加 {@code ccLiteral()}</b>: 序列化/审计需要 CC 原始字符串,
         * 不能用 {@code name()} (CC 是 camelCase, Java 是 SCREAMING_SNAKE).
         * 显式映射避免{@code name().toLowerCase()} 等猜测性变换.
         */
        public enum SandboxOverrideReason {
            /**
             * 命令在沙箱 exclude 列表中 ({@code excludedCommand}) ·
             * CC {@code types/permissions.ts:301} 字面量.
             */
            EXCLUDED_COMMAND("excludedCommand"),

            /**
             * 用户用 {@code --dangerously-disable-sandbox} 主动禁用沙箱
             * ({@code dangerouslyDisableSandbox}) ·
             * CC {@code types/permissions.ts:301} 字面量.
             */
            DANGEROUSLY_DISABLE_SANDBOX("dangerouslyDisableSandbox");

            private final String ccLiteral;

            SandboxOverrideReason(String ccLiteral) {
                this.ccLiteral = ccLiteral;
            }

            /**
             * CC 原始字面量 · 严格对齐 {@code types/permissions.ts:301}.
             *
             * @return CC 字面量 ({@code 'excludedCommand'} 或 {@code 'dangerouslyDisableSandbox'})
             */
            public String ccLiteral() {
                return ccLiteral;
            }

            /**
             * 解析 CC 字面量 → 枚举 · 严格对齐 CC {@code types/permissions.ts:301}
             * 字面量联合 {@code 'excludedCommand' | 'dangerouslyDisableSandbox'}.
             *
             * <p><b>WHY</b>: Java 无 TS 字面量联合, JSON 反序列化 / 透传需要显式解析入口.
             * 严格语义 (仿 {@code Task.TaskStatus.fromString}, CC tasks.ts:333-339
             * safeParse→null): <b>null / 未知字符串一律返回 null</b>, 不做大小写折叠 /
             * 蛇形别名 / 未来字面量猜测 —— 拼写错误立即暴露, 不会静默映射成错误语义.
             *
             * @param literal CC 原始字面量 ({@code 'excludedCommand'} 或
             *                {@code 'dangerouslyDisableSandbox'})
             * @return 命中枚举; null / 未知字符串 → {@code null}
             */
            public static SandboxOverrideReason fromString(String literal) {
                if (literal == null) {
                    return null;
                }
                return switch (literal) {
                    case "excludedCommand" -> EXCLUDED_COMMAND;
                    case "dangerouslyDisableSandbox" -> DANGEROUSLY_DISABLE_SANDBOX;
                    default -> null;
                };
            }
        }
    }

    /**
     * AI 分类器决策。
     *
     * <p>[WF-1 · DEL-WF1-01] 删除 {@code mode} 字段，对齐 CC {@code types/permissions.ts:303-307}
     * 分类器变体仅 {@code {type:'classifier', classifier, reason}} 无 mode 字段，
     * auto-mode 语义由 {@code classifier} 字段值 {@code 'auto-mode'} 承载：
     * <ul>
     *   <li>{@code classifier = "auto-mode"} → 触发 PermissionDenied retry hook
     *       （与 CC {@code feature('TRANSCRIPT_CLASSIFIER') && decisionReason.classifier === 'auto-mode'}
     *       toolExecution.ts:1078 行为对齐）</li>
     *   <li>{@code classifier} 其他值（如 {@code "bash_allow"}）→ 不触发 retry hook</li>
     * </ul>
     *
     * <p>CC 构造侧自证：permissions.ts:907/923 auto-mode 分类器决策写
     * {@code classifier: 'auto-mode'}；permissions.ts:1045-1057 handleDenialLimitExceeded
     * 保留 originalClassifier（缺省 'auto-mode'）。
     *
     * @param classifier 分类器名；auto-mode 分类器决策时承载 {@code "auto-mode"}
     *                   （对齐 CC permissions.ts:907/923 构造侧）
     * @param reason     分类器输出的理由
     */
    record Classifier(String classifier, String reason) implements PermissionDecisionReason {
        public Classifier {
            if (classifier == null || classifier.isBlank()) {
                throw new IllegalArgumentException("Classifier.classifier is blank");
            }
            if (reason == null) {
                throw new IllegalArgumentException("Classifier.reason is null");
            }
        }
    }

    /**
     * 工作目录外（path 越狱）。
     */
    record WorkingDir(String reason) implements PermissionDecisionReason {
        public WorkingDir {
            if (reason == null) {
                throw new IllegalArgumentException("WorkingDir.reason is null");
            }
        }
    }

    /**
     * 路径安全检查（{@code .git/} / {@code .claude/} / {@code .vscode/} / shell configs）。
     * {@code classifierApprovable=true} 时 auto mode 让分类器评估。
     */
    record SafetyCheck(String reason, boolean classifierApprovable) implements PermissionDecisionReason {
        public SafetyCheck {
            if (reason == null) {
                throw new IllegalArgumentException("SafetyCheck.reason is null");
            }
        }
    }

    /**
     * 兜底归因。
     */
    record Other(String reason) implements PermissionDecisionReason {
        public Other {
            if (reason == null) {
                throw new IllegalArgumentException("Other.reason is null");
            }
        }
    }

    // ─────────────────── [R32-b12 D-3] OTel source 映射 ───────────────────

    /**
     * 映射 {@link PermissionDecisionReason} 为 OTel {@code source} 字段 · 严格对齐
     * CC Open-ClaudeCode/src/services/tools/toolExecution.ts:207-250
     * {@code decisionReasonToOTelSource}.
     *
     * <p>CC 真源映射规则（10 case switch）:
     * <ul>
     *   <li>{@code permissionPromptTool}: toolResult.decisionClassification ∈
     *       {user_temporary, user_permanent, user_reject} → 原值返回；
     *       否则 allow → user_temporary / deny → user_reject</li>
     *   <li>{@code rule} → {@link PermissionRuleSource} 经 ruleSourceToOTelSource 映射</li>
     *   <li>{@code hook} → "hook"</li>
     *   <li>{@code mode / classifier / subcommandResults / asyncAgent /
     *       sandboxOverride / workingDir / safetyCheck / other} → "config"</li>
     *   <li>{@code null} → "config"（CC 真源: if (!reason) return 'config'）</li>
     * </ul>
     *
     * <p>注：{@code PermissionDecisionReason.PermissionPromptTool} 已存在（P2 等价 CC
     * {@code permissionPromptTool} case），Java 端无需新增类.
     *
     * @param reason    决策归因（可为 null → "config"）
     * @param behavior  决策行为（{@code ALLOW} / {@code DENY}），仅 permissionPromptTool fallback 用
     * @return OTel source 字符串
     */
    static String decisionReasonToOTelSource(PermissionDecisionReason reason,
                                              PermissionBehavior behavior) {
        if (reason == null) {
            return "config";
        }
        if (reason instanceof PermissionPromptTool promptReason) {
            Object toolResult = promptReason.toolResult();
            String classified = null;
            if (toolResult instanceof Map<?, ?> map) {
                Object dc = map.get("decisionClassification");
                if (dc instanceof String s) {
                    classified = s;
                }
            }
            if ("user_temporary".equals(classified)
                || "user_permanent".equals(classified)
                || "user_reject".equals(classified)) {
                return classified;
            }
            return behavior == PermissionBehavior.ALLOW
                ? "user_temporary" : "user_reject";
        }
        if (reason instanceof Rule ruleReason) {
            // Rule: 委托给 PermissionRuleSource 经 ruleSourceToOTelSource (CC 真源).
            // CC Open-ClaudeCode/src/services/tools/toolExecution.ts:181-194:
            //   session         → user_temporary (allow) | user_reject (deny)
            //   userSettings    → user_permanent (allow) | user_reject (deny)
            //   localSettings   → user_permanent (allow) | user_reject (deny)
            //   default         → config
            // Java 端: PermissionRule.source 是枚举, 按 behavior 分流词汇.
            PermissionRule rule = ruleReason.rule();
            if (rule != null && rule.source() != null) {
                boolean allow = behavior == PermissionBehavior.ALLOW;
                return switch (rule.source()) {
                    case SESSION -> allow ? "user_temporary" : "user_reject";
                    case USER_SETTINGS, LOCAL_SETTINGS -> allow ? "user_permanent" : "user_reject";
                    case PROJECT_SETTINGS, FLAG_SETTINGS,
                         POLICY_SETTINGS, CLI_ARG, COMMAND -> "config";
                };
            }
            return "config";
        }
        if (reason instanceof Hook) {
            return "hook";
        }
        // [F Session P1-5] SandboxOverride 显式 case · 对齐 CC 真源 default case.
        // CC Open-ClaudeCode/src/services/tools/toolExecution.ts:240-244 sandboxOverride
        // 与 mode / classifier / subcommandResults / asyncAgent / workingDir / safetyCheck /
        // other 一并落入 default case 返回 'config'. Java 端保持显式 switch 写出,
        // 与 sealed interface 11 种 reason 一一对应 (CLAUDE.md 经验教训 #6 sealed interface
        // 字段不要塞硬编码 null, 但 switch case 显式列出是穷尽性 + 可读性的体现).
        if (reason instanceof SandboxOverride sandboxOverride) {
            // CC 真源: sandboxOverride.reason 取值不影响 OTel source (excludedCommand /
            // dangerouslyDisableSandbox 都归 config). 保留 reason 字段用于审计/调试,
            // 这里不读取.
            return "config";
        }
        // 兜底: mode / classifier / subcommandResults / asyncAgent / workingDir /
        // safetyCheck / other → "config" (CC 真源 default case).
        return "config";
    }
}
