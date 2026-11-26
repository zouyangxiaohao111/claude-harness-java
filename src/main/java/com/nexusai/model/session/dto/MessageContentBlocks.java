package com.nexusai.model.session.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * assistant 内容块装配器（协议中立）· content_blocks 列的唯一序列化/装配点。
 *
 * <p><b>职责</b>
 * <ol>
 *   <li>{@link #resolve(ChatMessageDto)}：写库前拿到「该消息的块顺序」——
 *       优先用上游已按到达顺序采集好的 {@link ChatMessageDto#blocks()}（Anthropic 流式的
 *       content_block_start 下标即真实顺序）；为空时按协议无关的规范顺序派生
 *       （thinking → text → tool_use，与 Anthropic 发送侧重建顺序一致）。</li>
 *   <li>{@link #ordered(List)}：按入参顺序重排 apiBlockIndex（0..n-1 连续），
 *       保证「到达顺序」是唯一真源、索引只做冗余校验。</li>
 *   <li>{@link #toJson(List)} / {@link #fromJson(String)}：content_blocks 列（JSON 数组）。</li>
 *   <li>{@link #toMap(ContentBlockDto)}：子代理 transcript 的「一块一行」载荷。</li>
 * </ol>
 *
 * <p><b>⛔ 边界</b>：本类只服务落库/渲染/顺序还原。任何 provider 的出站请求组装器
 * （Anthropic content 数组 / OpenAI Chat tool_calls / OpenAI Responses output items）
 * 都不读本类产物 —— 见 {@link ContentBlockDto} 类注释。
 *
 * <p><b>唯一例外（方案 A，主会话 2026-11-25 裁决）</b>：{@code GoogleGeminiProvider} 经
 * {@link ChatMessageDto#blocks()}（本类 {@link #fromJson} 读回产物）取 Gemini thoughtSignature
 * 元数据与 part 边界，须先与 canonical 内容核对等价；仍⛔不整份序列化 blocks 进请求体。
 */
public final class MessageContentBlocks {

    /** JSON 通道：非空字段才落键（与既有结构化列同风格）；未知字段容错（前向兼容）。 */
    private static final ObjectMapper JSON = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private MessageContentBlocks() {
    }

    /**
     * 取该消息写入 content_blocks 的块列表。
     *
     * <p>返回 null 表示「本消息不落块」（非 assistant / 无任何块内容）——
     * 历史行与未采集块顺序的路径保持 NULL，前端走旧渲染。
     *
     * @param msg 出站/待落库消息
     * @return 按到达顺序排列的块（apiBlockIndex 连续）；null = 不落块
     */
    public static List<ContentBlockDto> resolve(ChatMessageDto msg) {
        if (msg == null) {
            return null;
        }
        List<ContentBlockDto> explicit = msg.blocks();
        if (explicit != null && !explicit.isEmpty()) {
            return ordered(explicit);
        }
        if (msg.role() != Role.assistant) {
            return null;
        }
        List<ContentBlockDto> canonical = canonical(
            msg.reasoning(), msg.thinkingSignature(), msg.content(), msg.toolCalls());
        return canonical.isEmpty() ? null : canonical;
    }

    /**
     * 规范顺序派生：thinking（有 reasoning）→ text（有正文）→ 每个 tool_use。
     *
     * <p>这是 Anthropic 发送侧重建 assistant content 数组的顺序
     * （{@code [thinking?, text?, ...tool_use]}，见 AnthropicSdkProvider 的 thinking 透传分支），
     * 也是 OpenAI 侧无法提供块下标时的确定回退。apiBlockIndex 按最终列表序连续分配。
     *
     * @param reasoning   thinking 正文（null/空 = 无 thinking 块）
     * @param signature   thinking 签名（可 null，随 thinking 块落库）
     * @param content     text 正文（null/空 = 无 text 块）
     * @param toolCalls   tool_use 列表（null/空 = 无工具块）
     * @return 按规范顺序排列的块（可能为空列表）
     */
    public static List<ContentBlockDto> canonical(String reasoning, String signature,
                                                  String content, List<ToolCallDto> toolCalls) {
        List<ContentBlockDto> raw = new ArrayList<>();
        if (reasoning != null && !reasoning.isEmpty()) {
            raw.add(ContentBlockDto.thinking(reasoning, signature, raw.size()));
        }
        if (content != null && !content.isEmpty()) {
            raw.add(ContentBlockDto.text(content, raw.size()));
        }
        if (toolCalls != null) {
            for (ToolCallDto tc : toolCalls) {
                if (tc == null) {
                    continue;
                }
                raw.add(ContentBlockDto.toolUse(tc.id(), tc.name(), tc.arguments(), raw.size()));
            }
        }
        return raw;
    }

    /**
     * 按列表顺序重排 apiBlockIndex（0..n-1）。
     *
     * <p>WHY：索引只是「在 API content 数组中的位置」的冗余表达，真实顺序由追加顺序决定。
     * 重排可保证落库数据自洽（索引连续），并让「聚合顺序」成为唯一真源。
     *
     * @param blocks 块列表（顺序 = 到达顺序）
     * @return 新列表（apiBlockIndex 连续）；入参 null/空 → 空列表
     */
    public static List<ContentBlockDto> ordered(List<ContentBlockDto> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<ContentBlockDto> out = new ArrayList<>(blocks.size());
        int i = 0;
        for (ContentBlockDto b : blocks) {
            if (b == null) {
                continue;
            }
            out.add(new ContentBlockDto(b.type(), b.text(), b.thinking(), b.signature(),
                b.id(), b.name(), b.input(), i));
            i++;
        }
        return out;
    }

    /** 块列表 → content_blocks 列 JSON；null/空 → null（列 NULL，与历史行同形）。 */
    public static String toJson(List<ContentBlockDto> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(ordered(blocks));
        } catch (Exception e) {
            return null;
        }
    }

    /** content_blocks 列 JSON → 块列表；null/空/解析失败 → null（走旧渲染）。 */
    public static List<ContentBlockDto> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<ContentBlockDto> list = JSON.readValue(json, new TypeReference<List<ContentBlockDto>>() { });
            if (list == null || list.isEmpty()) {
                return null;
            }
            return ordered(list);
        } catch (Exception e) {
            return null;
        }
    }

    /** 单块 → transcript 行载荷（子代理 jsonl「一块一行」）。 */
    public static Map<String, Object> toMap(ContentBlockDto block) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", block.type());
        if (block.text() != null) {
            map.put("text", block.text());
        }
        if (block.thinking() != null) {
            map.put("thinking", block.thinking());
        }
        if (block.signature() != null) {
            map.put("signature", block.signature());
        }
        if (block.id() != null) {
            map.put("id", block.id());
        }
        if (block.name() != null) {
            map.put("name", block.name());
        }
        if (block.input() != null) {
            map.put("input", block.input());
        }
        map.put("apiBlockIndex", block.apiBlockIndex());
        return map;
    }

    /**
     * transcript 单块行 → 块对象。
     *
     * @param node 单行 JSON 节点（含 type + 类型相关字段 + apiBlockIndex）
     * @return 块；type 缺失 → null（非块行）
     */
    public static ContentBlockDto fromNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String type = node.hasNonNull("type") ? node.path("type").asText() : null;
        if (type == null || type.isBlank()) {
            return null;
        }
        Integer index = node.hasNonNull("apiBlockIndex") ? node.path("apiBlockIndex").asInt() : null;
        return new ContentBlockDto(
            type,
            node.hasNonNull("text") ? node.path("text").asText() : null,
            node.hasNonNull("thinking") ? node.path("thinking").asText() : null,
            node.hasNonNull("signature") ? node.path("signature").asText() : null,
            node.hasNonNull("id") ? node.path("id").asText() : null,
            node.hasNonNull("name") ? node.path("name").asText() : null,
            node.hasNonNull("input") ? node.path("input").asText() : null,
            index);
    }
}
