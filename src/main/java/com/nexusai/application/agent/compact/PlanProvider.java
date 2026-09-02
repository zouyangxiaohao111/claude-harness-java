package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;

import java.util.UUID;

/**
 * plan 文件提供者 · 对齐 CC {@code plans.ts} 磁盘 plan 机制
 * （Open-ClaudeCode/src/utils/plans.ts）与 compact 重建链的
 * {@code createPlanAttachmentIfNeeded}（services/compact/compact.ts:1470-1486）。
 *
 * <p><b>WHY 存在</b>: CC 端 plan_file_reference / plan_mode 附件重建依赖读磁盘 plan 文件
 * （{@code getPlan(agentId)} 读 {@code {planSlug}.md} / {@code {planSlug}-agent-{agentId}.md}，
 * plans.ts:119-145），并据此在压缩后把 plan 全文 / plan 模式指引重注入模型
 * （compact.ts:545-555 / 939-947）。本接口把 CC 的读盘 + 写盘 + 附件注入契约参数化，
 * 由 {@link PlanProviderImpl} 提供磁盘生产实现，供 compact 重建链 + ExitPlanModeTool 接线。
 *
 * <p><b>slug 约定（concern A 拍板 sessionId-as-slug）</b>: CC 每会话随机 word slug
 * （plans.ts:32-49 generateWordSlug）；Java 端以 {@code sessionId} 作 slug（稳定唯一、零新增
 * 依赖、读/写/注入契约等价）。文件名语义偏离 CC「人类可读词对」，但路径拼接与
 * 注入契约不变。实现见 {@link PlanProviderImpl}。
 */
public interface PlanProvider {

    /**
     * 取指定 agent 的 plan 文件路径 · CC original: getPlanFilePath(agentId)（plans.ts:119-129）。
     *
     * <p>主会话（agentId null）→ {@code {plansDir}/{slug}.md}；子代理（agentId 非 null）→
     * {@code {plansDir}/{slug}-agent-{agentId}.md}（CC plans.ts:122-128）。
     *
     * @param agentId 当前 agent ID（null = 主会话；CC 默认参，plans.ts:119）
     * @return plan 文件绝对路径（不保证文件存在）
     */
    String getPlanFilePath(UUID agentId);

    /**
     * 读指定 agent 的 plan 文件全文 · CC original: getPlan(agentId)（plans.ts:135-144）。
     *
     * <p>readFileSync utf-8；ENOENT → null；其它 error → logError + null（不抛，plans.ts:137-142）。
     *
     * @param agentId 当前 agent ID（null = 主会话）
     * @return plan 全文；无文件 / 读失败 → null
     */
    String getPlan(UUID agentId);

    /**
     * 恢复 plan 文件（resume）· CC original: copyPlanForResume（plans.ts:164-231，简化本地形式）。
     *
     * <p><b>concern D 简化</b>: CC 在 ENOENT 且 CCR 环境（getEnvironmentKind()!==null）时走
     * recoverPlanFromMessages / findFileSnapshotEntry / persistFileSnapshotIfRemote 恢复链
     * （plans.ts:189-229）。Java 后端无 LogOption 转录 / file_snapshot 类型等价物，本期只实现
     * 「读源 slug 文件成功 → 复制到目标 session 文件 → true；ENOENT → false」本地简化，
     * 恢复链留待 resume/fork 模块 worktree（concern E）。
     *
     * <p>[session-id-short] targetSessionId 为 short（sess-xxx），sessionId-as-slug 语义不变。
     *
     * @param targetSessionId 目标会话 ID（short，resume 后的会话）
     * @param sourceSlug      源会话 slug（原会话 plan 文件名）
     * @return true=源文件存在且复制成功；false=无源 slug / ENOENT / 写失败
     */
    boolean copyPlanForResume(String targetSessionId, String sourceSlug);

    /**
     * 复制 plan 文件（fork）· CC original: copyPlanForFork（plans.ts:239-264，简化本地形式）。
     *
     * <p>CC 原 slug → {@code getPlanSlug(targetSessionId)} 生成新 slug（不复用原 slug）→ copyFile。
     * Java sessionId-as-slug 下目标 session 文件路径即 {@code {targetSessionId}.md}（新 slug
     * 天然由目标 sessionId 决定），故与 {@link #copyPlanForResume} 同一复制语义（copyFile
     * 源文件到目标 session 文件，防止原/分叉会话互相覆盖）。无 Java 调用方（resume/fork
     * 流程未接线，concern E）。
     *
     * <p>[session-id-short] targetSessionId 为 short（sess-xxx），sessionId-as-slug 语义不变。
     *
     * @param targetSessionId 分叉会话 ID（short）
     * @param sourceSlug      原会话 slug
     * @return true=复制成功；false=无源 slug / ENOENT / 写失败
     */
    boolean copyPlanForFork(String targetSessionId, String sourceSlug);

    /**
     * 构建 plan_file_reference 附件引用（若 plan 文件存在）· CC original:
     * createPlanAttachmentIfNeeded(agentId)（compact.ts:1470-1486）。
     *
     * <p>{@code getPlan(agentId)} 为 null → 返回 null；否则
     * {@code {planFilePath: getPlanFilePath(agentId), planContent}}（compact.ts:1473-1485）。
     *
     * @param agentId 当前 agent ID（null = 主会话）
     * @return PlanRef(planFilePath, planContent)；无 plan 文件 → null
     */
    AttachmentMessageDto.PlanRef createPlanAttachmentIfNeeded(UUID agentId);
}
