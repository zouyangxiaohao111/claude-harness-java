package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;

/**
 * Token 计数接口 · 由调用方提供实际实现（GR-3 自旧管线嵌套 TokenCounter 提为独立类型）。
 *
 * <p><b>WHY 独立文件</b>: 旧压缩管线（D-02）随 GR-3 删除，其嵌套 {@code TokenCounter} 不再
 * 有宿主；Token 计数是 AutoCompactor（autoCompactIfNeeded 阈值比较）与 ReactiveCompactor
 * （reactive 恢复）的公共依赖，独立为顶层功能接口。
 *
 * <p>实现应使用实际 tokenizer 或估算方法（对齐 CC {@code tokenCountWithEstimation}）。
 */
@FunctionalInterface
public interface TokenCounter {

    /**
     * 计算消息列表的 token 数
     *
     * @param messages 消息列表
     * @return token 估算值
     */
    int count(List<ChatMessageDto> messages);
}
