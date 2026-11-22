package com.nexusai.infra.llm;

import com.nexusai.repository.provider.entity.ModelRecord;

import java.util.Locale;
import java.util.Set;

/**
 * 会话模型类型判定 · 唯一口径。
 *
 * <p><b>WHY 需要这个类</b>：会话主模型、{@code SessionCreate.model} 与模型清单工具
 * （{@code ListModels}）必须用同一套「哪些模型能跑会话」的判定。前端已有一份同义实现
 * （{@code front/src/App.tsx:136-139} 的 {@code CHAT_MODEL_TYPES}），本类与之逐条同义：
 * <ul>
 *   <li>可用于会话：{@code chat} / {@code text} / {@code vision} / {@code multimodal}；</li>
 *   <li>不可用于会话：{@code embedding} / {@code rerank} / {@code image} /
 *       {@code image_generation} / {@code audio} / {@code moderation}；</li>
 *   <li>空 type 按历史对话模型兼容（老数据没有 type 值）。</li>
 * </ul>
 *
 * <p><b>为什么不在聊天路径里判</b>：现有 {@code LlmProviderFactory} / {@code ModelConfigResolver}
 * 只按 provider.type 建客户端，不校验 model.type；把 embedding 写进 {@code sessions.model_name}
 * 时不会在这里报错，而是拖到第一次模型调用才以 400/解码错误暴露。故选择面（工具参数、清单展示）
 * 必须前置拦住。
 */
public final class ChatModelTypes {

    /** 允许作为会话模型的 {@code models.type} 值（小写，与 {@code ModelType} 枚举名一致）。 */
    private static final Set<String> CONVERSATION_TYPES =
        Set.of("chat", "text", "vision", "multimodal");

    private ChatModelTypes() {}

    /**
     * 该 type 是否能跑会话。
     *
     * @param type {@code models.type} 原始值（可为 null / 空 / 带空格）
     * @return true = 可用于会话；null / blank → true（历史数据兼容，与前端同义）
     */
    public static boolean isConversationModel(String type) {
        if (type == null || type.isBlank()) {
            return true;
        }
        return CONVERSATION_TYPES.contains(type.trim().toLowerCase(Locale.ROOT));
    }

    /** 模型行是否能跑会话（null → false）。 */
    public static boolean isConversationModel(ModelRecord model) {
        return model != null && isConversationModel(model.getType());
    }

    /**
     * 允许的 type 名集合（只读）。
     *
     * <p>给错误文案 / 工具输出用，避免各处手写「chat, text, vision, multimodal」而漂移。
     */
    public static Set<String> conversationTypes() {
        return CONVERSATION_TYPES;
    }
}
