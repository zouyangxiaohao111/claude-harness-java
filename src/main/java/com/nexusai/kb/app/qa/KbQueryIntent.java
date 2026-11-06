package com.nexusai.kb.app.qa;

/**
 * 查询意图 · 逐字对齐 WeKnora {@code internal/types/chat_manage.go:119-129}。
 *
 * <p><b>⭐⭐ 为什么未知值必须归一成「空意图（= 要检索）」</b>
 * （源侧 {@code NormalizeQueryIntent:262} 的注释记录了真实事故）：
 * 早期实现把模型返回的标签<b>原样使用</b>，于是任何意外取值（大小写不同、模型自创标签、
 * 多一个空格）都会落到「不是 kb_search」⇒ <b>不检索</b> ⇒
 * 答案凭空生成、<b>既没查知识库也不报错</b>。用户看到的是一段看似正常但毫无依据的回答。
 *
 * <p>⇒ 本枚举的归一化规则必须保持 <b>fail-open 到检索</b>：
 * 宁可多查一次知识库，也绝不能静默跳过。
 */
public enum KbQueryIntent {

    /** 需要检索知识库（最常见的默认）。 */
    KB_SEARCH("kb_search"),
    /** 需要联网检索（源侧还要看 WebSearchEnabled 开关）。 */
    WEB_SEARCH("web_search"),
    /** 问候语（你好 / 早上好）。 */
    GREETING("greeting"),
    /** 闲聊。 */
    CHITCHAT("chitchat"),
    /** 追问（针对上一轮回答继续问）。 */
    FOLLOW_UP("follow_up"),
    /** 只有图片、没有文字问题。 */
    IMAGE_ONLY("image_only"),
    /** 只有文档、没有具体问题（如「看看这个文件」）。 */
    DOC_ONLY("doc_only"),
    /** 要求总结当前材料。 */
    SUMMARIZE("summarize"),
    /** 问题不清晰，需要向用户澄清。 */
    CLARIFICATION("clarification");

    private final String wire;

    KbQueryIntent(String wire) {
        this.wire = wire;
    }

    /** 线格式（与源侧 JSON 字段值逐字一致）。 */
    public String wire() {
        return wire;
    }

    /**
     * 是否需要查知识库（对齐源侧 {@code QueryIntent.NeedsKBRetrieval}）。
     *
     * <p>⚠️ {@link #WEB_SEARCH} 不在这里判 —— 它还要看联网开关，
     * 由调用方通过 {@link #needsRetrieval(boolean)} 统一判断。
     */
    public boolean needsKbRetrieval() {
        return this == KB_SEARCH || this == CLARIFICATION;
    }

    /**
     * 是否需要检索（知识库或联网）· 对齐源侧 {@code ChatManage.NeedsRetrieval}。
     *
     * @param webSearchEnabled 联网检索是否开启
     * @return true = 需要走检索阶段
     */
    public boolean needsRetrieval(boolean webSearchEnabled) {
        if (this == WEB_SEARCH) {
            return webSearchEnabled;
        }
        return needsKbRetrieval();
    }

    /**
     * 归一化：把模型返回的原始标签映射到已知意图。
     *
     * <p><b>⭐ 未知值 → {@link #KB_SEARCH}</b>（不是「空意图」这个中间态 —— 本仓用
     * {@code KB_SEARCH} 直接表达「要检索」，效果与源侧「空串也检索」一致，
     * 但省掉一个「空枚举值」的歧义）。
     *
     * <p>宽恕规则（对齐源侧）：大小写不敏感、分隔符视为等价
     * （{@code KB-Search} / {@code kb search} / {@code Kb_Search} 都 → {@link #KB_SEARCH}）。
     *
     * @param raw 模型返回的原始标签（可 null / 空白 / 未知）
     * @return 归一化后的意图（恒非 null）
     */
    public static KbQueryIntent normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return KB_SEARCH;  // fail-open：没给意图 ⇒ 按需要检索处理
        }
        String key = raw.trim().toLowerCase()
            .replace('-', '_')
            .replace(' ', '_');
        for (KbQueryIntent v : values()) {
            if (v.wire.equals(key)) {
                return v;
            }
        }
        return KB_SEARCH;  // ⭐ 未知值同样 fail-open 到检索（源侧事故的直接教训）
    }

    /**
     * 中文展示名（供日志与前端）。
     *
     * @return 中文名
     */
    public String label() {
        return switch (this) {
            case KB_SEARCH -> "查知识库";
            case WEB_SEARCH -> "联网检索";
            case GREETING -> "问候";
            case CHITCHAT -> "闲聊";
            case FOLLOW_UP -> "追问";
            case IMAGE_ONLY -> "仅图片";
            case DOC_ONLY -> "仅文档";
            case SUMMARIZE -> "总结";
            case CLARIFICATION -> "需澄清";
        };
    }
}
