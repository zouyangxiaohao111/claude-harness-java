package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.memory.KbMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 阶段 1 · 长期记忆召回 · 对齐源侧 {@code chat_pipeline/memory_recall.go}。
 *
 * <h2>为什么单独一层</h2>
 * {@link KbMemoryService#promptBlock()} 早已实现（W9 / V272），但**零消费点** ——
 * 记忆表建了、接口有 16 个端点、常驻块能读，就是没有一轮问答用它。
 * 这是第 27 类「设计了但没接线」的又一例。
 *
 * <h2>⭐ 为什么单独成一个类而不是直接在流水线里调 service</h2>
 * <ol>
 *   <li>流水线要能在单测里跑（{@code KbMemoryService} 依赖 6 张表 + 工作区上下文）；</li>
 *   <li>源侧把它做成独立插件，语义是「一个阶段 = 一个可替换单元」；</li>
 *   <li>将来要加「兴趣/常查资料」的第二段注入时，改这里一处即可（流水线不用动）。</li>
 * </ol>
 *
 * <h2>注入位置</h2>
 * 进<b>系统提示词</b>，⛔ 不进资料上下文 —— 记忆是「关于提问者的背景」，
 * 放进 {@code [n]} 编号体系会让模型把它当证据引用。
 *
 * <h2>失败语义</h2>
 * 记忆是增强：任何异常 ⇒ 返回空串 + WARN，本轮不注入。
 * 与源侧一致（{@code recall.Prompt == ""} 是正常路径，不是错误）。
 */
@Component
public class KbMemoryRecallStage {

    private static final Logger log = LoggerFactory.getLogger(KbMemoryRecallStage.class);

    @Autowired(required = false)
    private KbMemoryService memoryService;

    /**
     * 读本轮要注入的记忆文本。
     *
     * @return 记忆文本；未装配 / 记忆关闭 / 为空 → 空串（恒非 null）
     */
    public String recallBlock() {
        if (memoryService == null) {
            log.debug("[记忆召回] 记忆服务未装配，跳过");
            return "";
        }
        try {
            String block = memoryService.promptBlock();
            if (block == null) {
                return "";
            }
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                log.debug("[记忆召回] 常驻块为空，本轮不注入");
                return "";
            }
            log.info("[记忆召回] 注入常驻块 长度={}", trimmed.length());
            return trimmed;
        } catch (RuntimeException e) {
            log.warn("[记忆召回] 读取失败，本轮不注入 原因={}", e.getMessage());
            return "";
        }
    }

    /**
     * 读本轮改写用的「提问者背景」（兴趣 + 常查资料）。
     *
     * <p>与 {@link #recallBlock()} 分开：那个进<b>答案</b>的系统提示词，
     * 这个进<b>改写</b>的提示词（见 {@code KbMemoryService.rewriteBackground} 的说明）。
     *
     * @return 背景文本；无数据 / 未装配 / 失败 → 空串（恒非 null）
     */
    public String rewriteBackground() {
        if (memoryService == null) {
            return "";
        }
        try {
            String bg = memoryService.rewriteBackground();
            if (bg == null || bg.isBlank()) {
                return "";
            }
            log.info("[记忆召回] 改写背景 长度={}", bg.strip().length());
            return bg.strip();
        } catch (RuntimeException e) {
            log.warn("[记忆召回] 读改写背景失败，本轮不加背景 原因={}", e.getMessage());
            return "";
        }
    }
}
