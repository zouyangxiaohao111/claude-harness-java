package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 品牌化系统提示数组 · 对齐 CC {@code SystemPrompt}
 * （CC original: {@code SystemPrompt = readonly string[] & {readonly __brand: 'SystemPrompt'}} (utils/systemPromptType.ts:8-10)）。
 *
 * <p>CC 中 SystemPrompt 是字符串数组 + 编译期品牌（TS nominal typing），无任何运行时行为：
 * <ul>
 *   <li>数组态保留到发送边界，不中途坍缩为单个字符串（不变量 I-1）</li>
 *   <li>元素序即发送序，identity 工厂返回原数组引用（零拷贝）</li>
 * </ul>
 *
 * @param elements 系统提示数组元素（有序；boundary 是独立数组元素，非文本内嵌）
 */
public record SystemPrompt(
    List<String> elements
) {

    private static final Logger log = LoggerFactory.getLogger(SystemPrompt.class);

    /**
     * Identity 工厂 · 对齐 CC {@code asSystemPrompt}
     * （CC original: {@code asSystemPrompt(value: readonly string[]): SystemPrompt} (utils/systemPromptType.ts:12-14)）。
     *
     * <p>CC 实现为纯强转 {@code value as SystemPrompt}，零行为：
     * <ul>
     *   <li>返回<b>原数组引用</b>，不做防御性拷贝（验收测试断言引用等价）</li>
     *   <li>从不 {@code <system>} 包裹，从不拼接字符串（CC 全 src 无 {@code <system>} 包裹系统提示）</li>
     *   <li>{@code elements()} 直接返回入参 List</li>
     * </ul>
     *
     * @param value 已按发送顺序排好的系统提示数组（任意字符串数组均可品牌化）
     * @return 同一引用上的 SystemPrompt 品牌视图
     */
    public static SystemPrompt from(List<String> value) {
        if (log.isDebugEnabled()) {
            log.debug("[SystemPrompt] identity 强转品牌化: {} 个元素，返回原引用（零拷贝）", value == null ? 0 : value.size());
        }
        return new SystemPrompt(value);
    }
}
