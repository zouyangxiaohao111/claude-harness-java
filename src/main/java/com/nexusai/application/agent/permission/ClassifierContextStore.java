package com.nexusai.application.agent.permission;

import java.util.concurrent.ConcurrentHashMap;

/**
 * [Hook v2.1.325] PostToolUse {@code classifierContext} 暂存 · 供下一次 auto-mode 权限分类器
 * 在 transcript 中看到"宿主断言"。
 *
 * <p>CC 语义：context 与本调用的工具结果一起给分类器看；仅同步 hook 响应生效，最多 2000
 * UTF-16（折叠层已截断）。Java 分类器在**下一次**权限决策前读 transcript，故这里按
 * {@code toolUseId} 暂存，{@link PermissionPipeline#fullTranscript} 读时附加到对应 tool 消息。
 *
 * <p>键是工具调用 id（全局唯一）；条目在下一次分类读取后仍保留，供竞速/重试复用。
 * 内存有界（每个工具调用一条），不做持久化。
 */
public final class ClassifierContextStore {

    private static final ConcurrentHashMap<String, String> CONTEXTS = new ConcurrentHashMap<>();

    private ClassifierContextStore() {}

    public static void put(String toolUseId, String context) {
        if (toolUseId == null || toolUseId.isBlank() || context == null || context.isBlank()) {
            return;
        }
        CONTEXTS.put(toolUseId, context);
    }

    public static String get(String toolUseId) {
        return toolUseId == null ? null : CONTEXTS.get(toolUseId);
    }

    /** 会话/测试清理。 */
    public static void clear() {
        CONTEXTS.clear();
    }
}
