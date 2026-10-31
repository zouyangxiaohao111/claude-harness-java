package com.nexusai.application.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 产物/来源抽取结果的<b>纯解析器</b>（无 Spring 上下文、无 IO、无副作用）。
 *
 * <p><b>WHY 单独成类</b>：模型输出的形态不可控（```json 围栏、前后带解释性废话、少字段）——
 * 这段「剥壳 + 容错」是最容易写错、也最需要单测钉住的部分。抽成纯静态方法后
 * {@code SessionArtifactParserTest} 可以零上下文直测，不必启动 Spring（铁律：⛔ 不跑
 * @SpringBootTest，它会迁移用户真库）。
 *
 * <p><b>容错口径（规则十二：不猜）</b>：
 * <ul>
 *   <li>非 JSON / 顶层不是对象 ⇒ 返回空 + WARN（⛔ 不抛异常，也不做「猜一个出来」的补救）</li>
 *   <li>条目缺 title 或 title 空白 ⇒ <b>丢弃该条</b>（无标题的行前端无法展示，也无法做 upsert 键）</li>
 *   <li>其它字段缺失 ⇒ null（路径/链接的业务校验由 {@code SessionArtifactService} 做，
 *       那里才拿得到会话工作目录与文件系统）</li>
 * </ul>
 */
public final class SessionArtifactParser {

    private static final Logger log = LoggerFactory.getLogger(SessionArtifactParser.class);

    /** 项目惯例：各服务静态 ObjectMapper（本类只读不写，线程安全）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单条抽取结果（deliverable 用 path，source 用 url；另一侧为 null）。 */
    public record ParsedArtifact(String title, String path, String url, String summary) {}

    /** 一次抽取的全部结果。 */
    public record ParsedExtraction(List<ParsedArtifact> deliverables, List<ParsedArtifact> sources) {
        public static final ParsedExtraction EMPTY =
                new ParsedExtraction(List.of(), List.of());
    }

    private SessionArtifactParser() {}

    /**
     * 解析模型原始输出。
     *
     * @param raw 模型返回文本（可含 ```json 围栏 / 前后废话 / 可空）
     * @return 非 null；解析失败 → {@link ParsedExtraction#EMPTY}（调用方按「本轮无结果」处理）
     */
    public static ParsedExtraction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedExtraction.EMPTY;
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            log.warn("[artifact-extract] 模型输出里找不到 JSON 对象，本轮按无结果处理（不猜测）");
            return ParsedExtraction.EMPTY;
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            log.warn("[artifact-extract] 模型输出 JSON 解析失败，本轮按无结果处理（不猜测）: {}", e.toString());
            return ParsedExtraction.EMPTY;
        }
        if (root == null || !root.isObject()) {
            log.warn("[artifact-extract] 模型输出顶层不是 JSON 对象，本轮按无结果处理");
            return ParsedExtraction.EMPTY;
        }
        return new ParsedExtraction(
                parseArray(root.get("deliverables")),
                parseArray(root.get("sources")));
    }

    /**
     * 去掉 Markdown 代码围栏，再取<b>第一个左大括号到最后一个右大括号</b>之间的内容。
     *
     * @return 形如 {...} 的串；找不到成对花括号 → null
     */
    static String extractJsonObject(String raw) {
        String text = stripCodeFence(raw).trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** 剥掉 ``` 围栏行（```json / ```JSON / ``` 三种都算）；无围栏原样返回。 */
    static String stripCodeFence(String raw) {
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            // 单行形态：```{...}``` —— 直接把反引号去掉，交给后面的花括号切片
            return text.replace("```", " ");
        }
        String body = text.substring(firstNewline + 1);
        int lastFence = body.lastIndexOf("```");
        if (lastFence >= 0) {
            body = body.substring(0, lastFence);
        }
        return body;
    }

    private static List<ParsedArtifact> parseArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<ParsedArtifact> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            ParsedArtifact parsed = parseItem(item);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return List.copyOf(out);
    }

    /** 条目 → 值对象；缺 title（或空白）⇒ null（= 丢弃）。 */
    private static ParsedArtifact parseItem(JsonNode item) {
        if (item == null || !item.isObject()) {
            return null;
        }
        String title = textOrNull(item.get("title"));
        if (title == null) {
            if (log.isDebugEnabled()) {
                log.debug("[artifact-extract] 丢弃无标题条目: {}", item);
            }
            return null;
        }
        return new ParsedArtifact(
                title,
                textOrNull(item.get("path")),
                textOrNull(item.get("url")),
                textOrNull(item.get("summary")));
    }

    /** 取文本并 trim；非文本 / 空白 → null。 */
    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
