package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 权限决策结果 · 对齐 CC {@code types/permissions.ts:174-266}
 *
 * <h2>4 种 behavior</h2>
 * <ul>
 *   <li>{@link Allow} — 显式允许，工具立即执行</li>
 *   <li>{@link Deny} — 显式拒绝，工具不执行（含原因）</li>
 *   <li>{@link Ask} — 必须问用户，弹窗后决定</li>
 *   <li>{@link Passthrough} — 工具不表态，交给通用管线决定（10 层规则第 3 层转 ask）</li>
 * </ul>
 *
 * <h2>CC 教学版 vs 实际</h2>
 * 教学版只有 allow/deny/ask 3 种。CC 实际有 4 种，{@link Passthrough} 是工具的"中立票"——
 * 工具说"我没意见"，上层 {@code hasPermissionsToUseToolInner} 根据通用规则决定。
 *
 * <h2>Forward reference 说明</h2>
 * {@link Ask#suggestions} 字段类型 {@link PermissionUpdate} 在 PR 3 实现。
 * 本 PR 创建一个最小占位 interface（见 {@code PermissionUpdate.java}）以通过编译。
 *
 * <h2>[Session H P2-7] PermissionMetadata 对齐</h2>
 * {@link Ask#metadata} 由 {@code Map<String, Object>} 升级为
 * {@link PermissionMetadata} sealed interface（对齐 CC
 * {@code types/permissions.ts:167-169} {@code {command: PermissionCommandMetadata} | undefined}），
 * 唯一分支 {@link PermissionMetadata.CommandMetadata}（CC 157-162）。null 合法
 * （CC {@code | undefined}）；全工程构造点均传 null，无生产者（Pattern #10 审计见
 * {@link PermissionMetadata} javadoc）。
 *
 * @see PermissionDecisionReason
 * @see PermissionMode
 */
public sealed interface PermissionResult
        permits PermissionResult.Allow,
                PermissionResult.Deny,
                PermissionResult.Ask,
                PermissionResult.Passthrough {

    /**
     * [Session H14] 待分类器检查上下文结构体 · 对齐 CC {@code types/permissions.ts:190-194}.
     *
     * <p>CC 定义 PendingClassifierCheck 为结构体 {@code {command, cwd, descriptions}},
     * 支撑异步分类器 (bash classifier) 先行评估 — 分类器需要 command/cwd/descriptions
     * 才能判断 bash 命令是否安全. Java 端 {@link Ask#pendingClassifierCheck} 为
     * <b>非空即该结构体</b> (null = 无分类器检查), 取代 H14 前的 boolean 占位.
     */
    record PendingClassifierCheck(
            String command,
            String cwd,
            List<String> descriptions
    ) {
        public PendingClassifierCheck {
            command = command == null ? "" : command;
            cwd = cwd == null ? "" : cwd;
            descriptions = descriptions == null ? List.of() : List.copyOf(descriptions);
        }
    }

    /**
     * 权限决策元数据 · 对齐 CC {@code types/permissions.ts:164-169} {@code PermissionMetadata}
     * union（{@code {command: PermissionCommandMetadata} | undefined}）。
     *
     * <p>[Session H P2-7] 替代 {@code Map<String, Object>} 死字段：全工程构造点均传 null
     * （Pattern #10 字段类型审计：ReadPermissionChecker / CheckLayer1b/1e/1f/1g/3 /
     * HookPermissionResolver / ConfigToolImpl + 测试桩），无生产者。CC 侧同样只有消费者
     * （SkillPermissionRequest.tsx:36）且 Java 无 UI 消费层 → {@link CommandMetadata}
     * 两字段即为当前语义全集。
     *
     * <p>sealed 约束对齐 CC union 唯一分支；null 合法（CC {@code | undefined}）。
     *
     * @see #Ask
     */
    sealed interface PermissionMetadata permits PermissionMetadata.CommandMetadata {

        /**
         * 命令元数据访问器 · 对齐 CC union 成员形状
         * {@code {command: PermissionCommandMetadata}}（{@code metadata.command.name}）。
         * 唯一 permitted 分支 {@link CommandMetadata} 返回自身。
         *
         * @return 命令元数据（当前恒为 {@code this}）
         */
        CommandMetadata command();

        /**
         * 最小命令形状 · 对齐 CC {@code types/permissions.ts:157-162}
         * {@code PermissionCommandMetadata}（{@code {name, description?}}）。
         *
         * <p>CC 的 index signature {@code [key: string]: unknown}（前向兼容）Java record
         * 无法表达 → 只保留 name + description 两字段（当前语义全集，见
         * {@link PermissionMetadata} javadoc）。CC 原名 + 行号：PermissionCommandMetadata
         * @ types/permissions.ts:157-162。
         *
         * @param name        命令名（必填，CC {@code name: string}）
         * @param description 命令描述（可选，CC {@code description?: string}）
         */
        record CommandMetadata(String name, String description) implements PermissionMetadata {
            public CommandMetadata {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("CommandMetadata.name is blank");
                }
                // description 可以为 null (CC description?: string)
            }

            @Override
            public CommandMetadata command() {
                return this;
            }
        }
    }

    /**
     * 显式允许。来源：allow rule / 用户批准 / hook allow / bypass mode / 分类器放行。
     *
     * @param updatedInput    工具输入的更新版本（hook 可修改）
     * @param reason          决策原因（用于审计/调试，可为 {@code null}）
     * @param toolUseID       工具调用 ID（用于关联，可为 {@code null}）
     * @param userModified    用户是否修改了 input（默认 {@code false}）
     * @param acceptFeedback  用户接受反馈（可为 {@code null}）
     * @param contentBlocks   内容块列表（默认空，null 安全）
     */
    record Allow(
            JsonNode updatedInput,
            PermissionDecisionReason reason,
            String toolUseID,
            boolean userModified,
            String acceptFeedback,
            List<JsonNode> contentBlocks
    ) implements PermissionResult {
        public Allow {
            if (updatedInput == null) {
                throw new IllegalArgumentException("Allow.updatedInput is null");
            }
            // reason 可以为 null —— 非必填
            // toolUseID 可以为 null
            // userModified 默认 false
            // acceptFeedback 可以为 null
            contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks);
        }
    }

    /**
     * 显式拒绝。来源：deny rule / 用户拒绝 / hook deny / 工具自决 deny。
     *
     * <p>命中后工具不执行，{@code ToolResult.isError=true}，{@code content=message} 注入
     * LLM 让模型自纠。
     *
     * @param message    拒绝原因（必填，给 LLM 看）
     * @param reason     决策归因（必填，{@link PermissionDecisionReason} 11 种之一）
     * @param toolUseID  工具调用 ID（用于关联，可为 {@code null}）
     */
    record Deny(String message, PermissionDecisionReason reason, String toolUseID) implements PermissionResult {
        public Deny {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("Deny.message is blank");
            }
            if (reason == null) {
                throw new IllegalArgumentException("Deny.reason is null");
            }
            // toolUseID 可以为 null
        }
    }

    /**
     * 必须问用户。来源：ask rule / 默认 fallback / 内容特定 rule / safetyCheck。
     *
     * <p>弹窗后用户决定 → 转 {@link Allow} 或 {@link Deny}。
     *
     * @param message                          弹窗消息（必填）
     * @param reason                           决策归因（可为 {@code null}，对齐 CC
     *                                         {@code decisionReason?: PermissionDecisionReason}
     *                                         可选字段 types/permissions.ts:206）
     * @param suggestions                      建议的 {@link PermissionUpdate}（如
     *                                         "Add allow rule for this command"）
     * @param blockedPath                      被阻断的路径（如果涉及文件路径；可为 {@code null}）
     * @param updatedInput                     用户弹窗后可修改的 input（可为 {@code null}）
     * @param metadata                         权限决策元数据（可为 {@code null}，CC
     *                                         {@code metadata?: PermissionMetadata}
     *                                         types/permissions.ts:208；[Session H P2-7]
     *                                         由 {@code Map<String,Object>} 升级为
     *                                         {@link PermissionMetadata} sealed interface）
     * @param isBashSecurityCheckForMisparsing 是否为 bash 安全检查误解析（默认 {@code false}）
     * @param pendingClassifierCheck           待分类器检查结构体（可为 {@code null}，默认无检查）·
     *                                         非 null 即触发 async classifier auto-approval
     *                                         （CC types/permissions.ts:220）
     * @param contentBlocks                    内容块列表（默认空，null 安全）
     */
    record Ask(
            String message,
            PermissionDecisionReason reason,
            List<PermissionUpdate> suggestions,
            String blockedPath,
            JsonNode updatedInput,
            PermissionMetadata metadata,
            boolean isBashSecurityCheckForMisparsing,
            PermissionResult.PendingClassifierCheck pendingClassifierCheck,
            List<JsonNode> contentBlocks
    ) implements PermissionResult {
        public Ask {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("Ask.message is blank");
            }
            // reason 可以为 null —— 对齐 CC decisionReason?: PermissionDecisionReason 可选
            //   (types/permissions.ts:206)；第 3 层兜底 passthrough 无 reason 时落 null，
            //   消息由 PermissionMessageGenerator 落通用默认句 (permissions.ts:207-209)。
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            // blockedPath 可以为 null
            // updatedInput 可以为 null（用户弹窗后可修改 input）
            // metadata 可以为 null
            // isBashSecurityCheckForMisparsing 默认 false
            // pendingClassifierCheck 默认 null（无分类器检查）
            contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks);
        }
    }

    /**
     * 工具不表态。{@code tool.checkPermissions()} 的默认返回值。
     *
     * <p>由 {@code hasPermissionsToUseToolInner} 第 3 层通用规则决定 →
     * 转为 {@link Ask}（默认）或 {@link Allow}。
     *
     * @param message                描述（必填）
     * @param reason                 决策归因（可为 {@code null}，工具可以提供）
     * @param suggestions            建议的 {@link PermissionUpdate} 列表（默认空）
     * @param blockedPath            被阻断的路径（可为 {@code null}）
     * @param pendingClassifierCheck 待分类器检查结构体（可为 {@code null}）·
     *                                CC PermissionResult passthrough 变体同样声明
     *                                {@code pendingClassifierCheck?: PendingClassifierCheck}
     *                                (types/permissions.ts:265)
     */
    record Passthrough(
            String message,
            PermissionDecisionReason reason,
            List<PermissionUpdate> suggestions,
            String blockedPath,
            PermissionResult.PendingClassifierCheck pendingClassifierCheck
    ) implements PermissionResult {
        public Passthrough {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("Passthrough.message is blank");
            }
            // reason 可以为 null —— Passthrough 本身是"无意见"，可不必提供归因
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            // blockedPath 可以为 null
            // pendingClassifierCheck 默认 null（无分类器检查）
        }
    }
}
