package com.nexusai.kb.app.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图谱抽取结果的解析与归一 · 对齐 WeKnora
 * {@code graph.go extractEntities/extractRelationships} 的解析段 +
 * {@code chat_pipeline/extract_entity.go ParseGraph/rebuildGraph}。
 *
 * <p><b>⭐⭐ 为什么把解析抽成纯函数</b>：模型输出是<b>外部输入</b>。
 * 「JSON 外面套 ```json 围栏」「多给了一个不存在的类型」「关系引用了一个没抽到的实体」
 * 「source 与 target 是同一句话」—— 这些都要在不调模型的情况下逐条测。
 *
 * <p><b>⭐ 三条静默失败防线</b>：
 * <ol>
 *   <li><b>类型不在清单里 ⇒ 回落 {@code Concept}</b>（⛔ 不丢弃实体）。
 *       丢弃会让「模型写了个 Widget 类型」变成图上少一个节点，且没有任何迹象。</li>
 *   <li><b>title 或 description 为空 ⇒ 丢弃该实体</b>（源侧同）。
 *       没有名字的实体无法被关系引用，也无法显示。</li>
 *   <li><b>自环关系 ⇒ 丢弃</b>（源侧 {@code node1 == node2} 直接 continue）。
 *       自环在力导向布局里会把节点拽死在一个点上。</li>
 * </ol>
 */
public final class KbGraphParser {

    private static final Logger log = LoggerFactory.getLogger(KbGraphParser.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private KbGraphParser() {
    }

    /**
     * 一个抽出来的实体。
     *
     * @param name        实体名（源侧 title）
     * @param entityType  实体类型（已归一，必定在 {@link KbGraphPrompts#ENTITY_TYPES} 内）
     * @param description 描述（可能为空串）
     * @param attributes  属性列表（可能为空）
     */
    public record ExtractedEntity(
        String name, String entityType, String description, List<String> attributes
    ) {
    }

    /**
     * 一条抽出来的关系。
     *
     * @param source      起点实体名
     * @param target      终点实体名
     * @param type        关系类型（可空）
     * @param description 描述（可空）
     * @param strength    强度（已夹到 5..10）
     */
    public record ExtractedRelation(
        String source, String target, String type, String description, int strength
    ) {
    }

    /**
     * 解析实体抽取输出。
     *
     * @param raw 模型原文
     * @return 实体列表（永不为 null；无法解析 ⇒ 空列表）
     */
    public static List<ExtractedEntity> parseEntities(String raw) {
        JsonNode arr = readArray(raw, "实体");
        if (arr == null) {
            return List.of();
        }
        // 同名实体合并（源侧 entityMapByTitle 语义）：保留首个描述，属性取并集
        Map<String, ExtractedEntity> byName = new LinkedHashMap<>();
        for (JsonNode n : arr) {
            String title = text(n, "title");
            if (title.isEmpty()) {
                // 也接受 name（模型偶尔用错键）
                title = text(n, "name");
            }
            String desc = text(n, "description");
            if (title.isEmpty() || desc.isEmpty()) {
                log.debug("[图谱] 丢弃缺名字或缺描述的实体 title={} desc={}", title, desc);
                continue;
            }
            String type = normalizeType(text(n, "type"));
            List<String> attrs = stringList(n.get("attributes"));
            ExtractedEntity prev = byName.get(title);
            if (prev == null) {
                byName.put(title, new ExtractedEntity(title, type, desc, attrs));
            } else {
                // 合并属性，保留先出现的描述
                List<String> merged = new ArrayList<>(prev.attributes());
                for (String a : attrs) {
                    if (!merged.contains(a)) {
                        merged.add(a);
                    }
                }
                byName.put(title, new ExtractedEntity(
                    title, prev.entityType(), prev.description(), List.copyOf(merged)));
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * 解析关系抽取输出。
     *
     * <p>⭐ 只保留两端都在 {@code knownNames} 里的关系。
     * 模型常引用一个它自己上一轮编出来、但实体抽取没抽到的名字 —— 留着会变成悬空边。
     *
     * @param raw        模型原文
     * @param knownNames 本批已知实体名
     * @return 关系列表（永不为 null）
     */
    public static List<ExtractedRelation> parseRelations(String raw, java.util.Set<String> knownNames) {
        JsonNode arr = readArray(raw, "关系");
        if (arr == null) {
            return List.of();
        }
        List<ExtractedRelation> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String source = text(n, "source");
            String target = text(n, "target");
            if (source.isEmpty() || target.isEmpty()) {
                continue;
            }
            // ⭐ 自环丢弃：力导向布局下会把节点拽死
            if (source.equals(target)) {
                log.debug("[图谱] 丢弃自环关系 node={}", source);
                continue;
            }
            // ⭐ 两端必须都在已知实体里
            if (knownNames != null && (!knownNames.contains(source) || !knownNames.contains(target))) {
                log.debug("[图谱] 丢弃悬空关系 {}→{}（端点不在实体列表）", source, target);
                continue;
            }
            out.add(new ExtractedRelation(
                source, target,
                text(n, "type"),
                text(n, "description"),
                clampStrength(n.path("strength").asInt(KbGraphPrompts.MIN_STRENGTH))));
        }
        return List.copyOf(out);
    }

    /**
     * 实体类型归一。
     *
     * <p>大小写不敏感；不在清单里的回落 {@link KbGraphPrompts#DEFAULT_ENTITY_TYPE}。
     * ⚠️ <b>不丢弃实体</b> —— 类型错只是着色不对，丢实体是少一个节点。
     *
     * @param raw 模型给的类型
     * @return 合法类型
     */
    public static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return KbGraphPrompts.DEFAULT_ENTITY_TYPE;
        }
        String t = raw.trim();
        for (String known : KbGraphPrompts.ENTITY_TYPES) {
            if (known.equalsIgnoreCase(t)) {
                return known;
            }
        }
        // 常见变体：单复数 / 空格
        String compact = t.replace(" ", "").replace("_", "");
        for (String known : KbGraphPrompts.ENTITY_TYPES) {
            if (known.equalsIgnoreCase(compact)) {
                return known;
            }
            if ((known + "s").equalsIgnoreCase(compact)) {
                return known;
            }
        }
        log.debug("[图谱] 未知实体类型 {} ⇒ 回落 {}", raw, KbGraphPrompts.DEFAULT_ENTITY_TYPE);
        return KbGraphPrompts.DEFAULT_ENTITY_TYPE;
    }

    /**
     * 强度夹到 5..10。
     *
     * @param raw 模型给的强度
     * @return 合法强度
     */
    public static int clampStrength(int raw) {
        return Math.max(KbGraphPrompts.MIN_STRENGTH, Math.min(KbGraphPrompts.MAX_STRENGTH, raw));
    }

    /**
     * 宽松读 JSON 数组：容忍 ```json 围栏、前后废话、以及「对象包着数组」两种形态。
     *
     * @param raw  模型原文
     * @param what 日志用的名称
     * @return 数组节点；读不到 ⇒ null
     */
    static JsonNode readArray(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("[图谱] {} 抽取输出不含 JSON 数组，前 200 字：{}", what,
                s.length() > 200 ? s.substring(0, 200) : s);
            return null;
        }
        try {
            JsonNode n = JSON.readTree(s.substring(start, end + 1));
            return n != null && n.isArray() ? n : null;
        } catch (Exception e) {
            log.warn("[图谱] {} 抽取输出解析失败：{}", what, e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static List<String> stringList(JsonNode n) {
        if (n == null || !n.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode v : n) {
            String s = v.isTextual() ? v.asText() : v.toString();
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return List.copyOf(out);
    }

    /** 小写化工具（便于日志比对；不参与业务判断）。 */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
