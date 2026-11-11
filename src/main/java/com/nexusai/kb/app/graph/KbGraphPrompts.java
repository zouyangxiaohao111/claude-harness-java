package com.nexusai.kb.app.graph;

import com.nexusai.kb.app.prompt.KbPromptLoader;

/**
 * 图谱抽取提示词（中文）· 对齐 WeKnora
 * {@code config/prompt_templates/graph_extraction.yaml}
 * （{@code default_extract_entities} / {@code default_extract_relationships}）。
 *
 * <p><b>⭐⭐ 三处不能改的字面量</b>：
 * <ol>
 *   <li><b>实体类型清单必须与解析端一致</b>。提示词给了 11 种（{@link #ENTITY_TYPES}），
 *       解析端只接受这 11 种（大小写不敏感），其余一律回落 {@code Concept}。
 *       两边不同步的后果：模型按 A 写、代码按 B 认 ⇒ 全部实体变成 Concept，
 *       图谱按类型着色时全是一个颜色，<b>不报错</b>。</li>
 *   <li><b>强度档位 5..10 是固定语义</b>（10 = 直接从属 / 9 = 同一实体的不同称呼 /
 *       8 = 密切相关 / 7 = 明确但间接 / 6 = 间接关联 / 5 = 松散相关）。
 *       解析端把 &lt;5 或 &gt;10 的值夹回区间，所以档位改动要两侧一起改。</li>
 *   <li><b>「只抽取文本里明确出现的关系」</b>。模型很爱"补"常识关系
 *       （比如看到两个地名就自动连「相邻」）。这类关系在检索时会把不相干的证据串起来，
 *       且语气上完全像真的一样。</li>
 * </ol>
 */
public final class KbGraphPrompts {

    private KbGraphPrompts() {
    }

    /**
     * 合法实体类型（与提示词里列举的 11 种逐字一致）。
     *
     * <p>⚠️ 顺序影响归一化的兜底选择，别随意调整。
     */
    public static final java.util.List<String> ENTITY_TYPES = java.util.List.of(
        "Person", "Organization", "Location", "Product", "Event",
        "Date", "Work", "Concept", "Resource", "Category", "Operation");

    /** 未知类型回落值。 */
    public static final String DEFAULT_ENTITY_TYPE = "Concept";

    /** 强度合法区间下界。 */
    public static final int MIN_STRENGTH = 5;
    /** 强度合法区间上界。 */
    public static final int MAX_STRENGTH = 10;

    /** 实体抽取系统提示词。 */
    public static final String EXTRACT_ENTITIES = KbPromptLoader.cached("graph/extract-entities.md");

    /** 关系抽取系统提示词。 */
    public static final String EXTRACT_RELATIONSHIPS = KbPromptLoader.cached("graph/extract-relationships.md");

    /**
     * 渲染关系抽取的 user 消息。
     *
     * <p>⚠️ 实体只传 {@code title/type/description} 三个字段：属性与 chunk 列表
     * 既占 token 又与「判关系」无关（源侧传了全量 JSON，但那是因为它序列化的是整个对象）。
     *
     * @param entitiesJson 实体 JSON 数组（调用方负责裁剪字段）
     * @param content      文本内容
     * @return user 消息
     */
    public static String relationshipUserMessage(String entitiesJson, String content) {
        return "实体：" + (entitiesJson == null ? "[]" : entitiesJson)
            + "\n\n文本：" + (content == null ? "" : content);
    }
}
