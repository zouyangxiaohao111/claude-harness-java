package com.nexusai.application.agent.permission.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * YoloClassifier 接口 · 对齐 CC yoloClassifier.ts:1012 (classifyYoloAction)
 *
 * <p>[S06 重构] 分类器主路径对齐 CC 2-stage XML + classify_result 协议
 * （OPD-WF6-01）：stage1（fast）→ 若 block 触发 stage2（thinking），结果契约为
 * CC {@code YoloClassifierResult}（布尔 {@code shouldBlock}）。
 *
 * <p>[S06 重构] {@code getModelConfig(int)} 已删除（⊕-08）：CC 无双 config /
 * getModelConfig，单模型 getClassifierModel（yoloClassifier.ts:1334-1347
 * env→GB→mainLoop）由实现内部 {@code resolveClassifierModel} 承载。
 *
 * @see YoloClassifierResult
 */
public interface YoloClassifier {

    /**
     * 主入口：评估工具调用安全性 · 对齐 CC {@code classifyYoloAction}（yoloClassifier.ts:1012）。
     *
     * @param toolName   工具名
     * @param input      工具输入
     * @param transcript 对话历史（用于上下文）
     * @param ctx        工具调用上下文（abort 判定，CC abortSignal）
     * @return 分类结果（shouldBlock 布尔 + reason + model；失败 fail-closed block + unavailable）
     */
    CompletableFuture<YoloClassifierResult> classify(
            String toolName,
            JsonNode input,
            List<ChatMessageDto> transcript,
            ToolUseContext ctx
    );

    /**
     * 用户文本 action 分类 · 对齐 CC {@code classifyYoloAction}（yoloClassifier.ts:1012）
     * 的 user-text action 变体（toCompactBlock :418-421 {@code "User: {text}\n"}）。
     *
     * <p>CC handoff 复核（agentToolUtils.ts:410-424 classifyHandoffIfNeeded）把 synthetic
     * user 消息作 action 传入 classifyYoloAction —— action 是 user 文本块，恒非空 →
     * 不走 '' 短路，真实 2-stage XML 分类。
     *
     * @param userText   用户文本 action 内容（CC reviewPrompt / block.text）
     * @param transcript 对话历史（用于上下文，与 {@link #classify} 同构）
     * @param ctx        工具调用上下文（abort 判定，CC abortSignal）
     * @return 分类结果（shouldBlock 布尔；失败 fail-closed block + unavailable）
     */
    CompletableFuture<YoloClassifierResult> classifyTextAction(
            String userText,
            List<ChatMessageDto> transcript,
            ToolUseContext ctx
    );

    /**
     * 是否可用（单模型配置解析可用 / provider 已接线）。
     */
    boolean isAvailable();
}
