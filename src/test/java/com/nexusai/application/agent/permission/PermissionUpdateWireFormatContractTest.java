package com.nexusai.application.agent.permission;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 A1] PermissionUpdate 线格式契约 · 出站 CC 形状 ⇄ 入站解析 round-trip。
 *
 * <h2>WHY</h2>
 * <p>探查实证（本类逐条钉住）：「前端原样回传建议规则」这条链路此前<b>结构上不可能通</b> ——
 * <ul>
 *   <li>出站 {@link PermissionUpdate} 是裸 record，Jackson 默认序列化给出
 *       {@code {"destination":"LOCAL_SETTINGS","behavior":"ALLOW",
 *       "rules":[{"source":…,"ruleBehavior":…,"ruleValue":{"toolName":…}}]}} ——
 *       <b>无 {@code type} 判别字段</b>、枚举是大写名、{@code toolName} 嵌在 {@code ruleValue} 下；</li>
 *   <li>入站 {@code WebSocketPermissionPrompter.parseRules} 按 CC 形状读<b>扁平</b>
 *       {@code toolName} ⇒ 取不到 ⇒ {@code continue} ⇒ <b>静默丢弃</b>（零日志）；
 *       回传的 {@code updatedPermissions} 于是恒为空列表，"总是允许"从不生效且不可归因。</li>
 *   <li>{@link PermissionUpdate.AddDirectories} / {@link PermissionUpdate.RemoveDirectories}
 *       早先字段名+类型完全相同（都是 {@code List<String> paths}）⇒ 入站靠
 *       {@code has("paths")} 推断 add/remove ⇒ <b>新增目录被反向执行成删除目录</b>。</li>
 * </ul>
 *
 * <h2>口径</h2>
 * <p>「反序列化」方向刻意走<b>生产入站链</b>（{@code JsonNode → Map →
 * PermissionUpdateSchema.safeParse → WebSocketPermissionPrompter.parsePermissionUpdate}），
 * 而不是 Jackson {@code readValue}：
 * <ul>
 *   <li>CC 的 {@code PermissionRuleValue} 只有 {@code toolName}/{@code ruleContent}，而 Java
 *       {@link PermissionRule} 多出 {@code source}/{@code ruleBehavior}（本地扩展，见
 *       {@link PermissionUpdateApplier} 的 [DEL-WF1-04] 说明）——这两项在 CC 线格式里
 *       <b>不存在</b>，只能由 {@code destination}+{@code behavior} 派生。因此「JSON → 等价对象」
 *       必须经过生产解析器，用 Jackson 反序列化反而会引入第二个（且必然错的）推导点。</li>
 *   <li>这样 round-trip 覆盖的是真实链路：出站 serializer + 严格 schema 门 + 字段解析器。</li>
 * </ul>
 *
 * <h2>反向实验配方</h2>
 * <p>把 {@code WireSerializer} 里任一处的 {@code "type"} 字面量改错、把 {@code directories}
 * 改回 {@code paths}、或删掉 {@code parseRules} 的 {@code log.warn} ⇒ 对应用例立刻变红。
 */
@DisplayName("[批 A1] PermissionUpdate 线格式（CC 形状 + round-trip + fail-loud）")
class PermissionUpdateWireFormatContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ch.qos.logback.classic.Logger prompterLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachWarnOnlyAppender() {
        prompterLogger = (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(WebSocketPermissionPrompter.class);
        originalLevel = prompterLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        prompterLogger.addAppender(appender);
        // 只放行 WARN+ ⇒ 生产 root=INFO 下不可见的 debug 不算留痕（探针口径 = 生产口径）。
        prompterLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachAppender() {
        prompterLogger.detachAppender(appender);
        appender.stop();
        prompterLogger.setLevel(originalLevel);
    }

    /** 已捕获的 WARN+ 消息（格式化后）。 */
    private List<String> warns() {
        return appender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ── 构造 helper ──────────────────────────────────────────────────────────

    private static PermissionRule rule(PermissionRuleSource source, PermissionBehavior behavior,
                                       String toolName, String ruleContent) {
        return new PermissionRule(source, behavior, new PermissionRuleValue(toolName, ruleContent));
    }

    private static JsonNode toJson(PermissionUpdate update) throws Exception {
        return JSON.readTree(JSON.writeValueAsString(update));
    }

    /** 生产入站链：JSON 节点 → Map → 严格 schema → 解析器。 */
    @SuppressWarnings("unchecked")
    private static Optional<PermissionUpdate> parseBack(JsonNode node) {
        Map<String, Object> asMap = JSON.convertValue(node, Map.class);
        return PermissionUpdateSchema.safeParse(asMap);
    }

    /** 断言「序列化 → CC 形状 → 生产解析器 → 与原对象等价」，并返回解析结果。 */
    private static PermissionUpdate assertRoundTrip(PermissionUpdate original) throws Exception {
        JsonNode node = toJson(original);
        Optional<PermissionUpdate> parsed = parseBack(node);
        assertThat(parsed)
            .as("round-trip 必须成功解析（线格式 %s）", node)
            .isPresent();
        assertThat(parsed.get())
            .as("round-trip 必须等价（JSON=%s）", node)
            .isEqualTo(original);
        return parsed.get();
    }

    // ── 验收 1：addRules 出站形状 ────────────────────────────────────────────

    @Test
    @DisplayName("AddRules 序列化 = CC 形状（type/destination/behavior 小驼峰 + rules 扁平）")
    void addRules_serializesToCcShape() throws Exception {
        PermissionUpdate.AddRules update = new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.LOCAL_SETTINGS,
            List.of(rule(PermissionRuleSource.LOCAL_SETTINGS, PermissionBehavior.ALLOW,
                "Bash", "git status:*")),
            PermissionBehavior.ALLOW);

        JsonNode node = toJson(update);

        assertThat(node.path("type").asText()).isEqualTo("addRules");
        assertThat(node.path("destination").asText()).isEqualTo("localSettings");
        assertThat(node.path("behavior").asText()).isEqualTo("allow");
        assertThat(node.path("rules")).hasSize(1);
        assertThat(node.path("rules").get(0).path("toolName").asText()).isEqualTo("Bash");
        assertThat(node.path("rules").get(0).path("ruleContent").asText()).isEqualTo("git status:*");

        // 反向断言：Java 侧富字段与旧字段名<b>不得</b>出现在线格式里
        assertThat(node.has("paths")).as("旧字段名 paths 不得出现").isFalse();
        JsonNode ruleJson = node.path("rules").get(0);
        assertThat(ruleJson.has("source")).as("规则元素不得含 source（CC PermissionRuleValue 无此字段）").isFalse();
        assertThat(ruleJson.has("ruleBehavior")).as("规则元素不得含 ruleBehavior").isFalse();
        assertThat(ruleJson.has("ruleValue")).as("规则元素不得嵌套 ruleValue").isFalse();
    }

    // ── 验收 2：6 型 round-trip ─────────────────────────────────────────────

    @Test
    @DisplayName("6 型逐一 构造 → 序列化 → 断言 JSON 形状 → 反序列化 → 断言等价")
    void allSixTypes_roundTripThroughCcShape() throws Exception {
        PermissionUpdate[] originals = {
            new PermissionUpdate.AddRules(
                PermissionUpdate.Destination.LOCAL_SETTINGS,
                List.of(rule(PermissionRuleSource.LOCAL_SETTINGS, PermissionBehavior.ALLOW,
                    "Bash", "git status:*")),
                PermissionBehavior.ALLOW),
            new PermissionUpdate.ReplaceRules(
                PermissionUpdate.Destination.PROJECT_SETTINGS,
                List.of(rule(PermissionRuleSource.PROJECT_SETTINGS, PermissionBehavior.ASK,
                    "Edit", "/tmp/**")),
                PermissionBehavior.ASK),
            // replaceRules 允许空 rules（"清空该桶"）—— 空数组也必须能 round-trip
            new PermissionUpdate.ReplaceRules(
                PermissionUpdate.Destination.USER_SETTINGS, List.of(), PermissionBehavior.DENY),
            new PermissionUpdate.RemoveRules(
                PermissionUpdate.Destination.USER_SETTINGS,
                List.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.DENY,
                    "WebFetch", null)),
                PermissionBehavior.DENY),
            new PermissionUpdate.SetMode(
                PermissionUpdate.Destination.PROJECT_SETTINGS, PermissionMode.PLAN),
            new PermissionUpdate.AddDirectories(
                PermissionUpdate.Destination.SESSION, List.of("/tmp", "/work")),
            new PermissionUpdate.RemoveDirectories(
                PermissionUpdate.Destination.CLI_ARG, List.of("/legacy")),
        };
        String[] expectedTypes = {
            "addRules", "replaceRules", "replaceRules", "removeRules",
            "setMode", "addDirectories", "removeDirectories",
        };

        for (int i = 0; i < originals.length; i++) {
            PermissionUpdate original = originals[i];
            JsonNode node = toJson(original);
            assertThat(node.path("type").asText())
                .as("第 %d 型 type 判别字段（JSON=%s）", i, node)
                .isEqualTo(expectedTypes[i]);
            assertThat(node.has("destination"))
                .as("第 %d 型 destination 必填（CC 6 变体全强制，JSON=%s）", i, node)
                .isTrue();
            assertRoundTrip(original);
        }
    }

    @Test
    @DisplayName("SetMode 出站 mode 是小驼峰 CC 字面量（不是枚举名 PLAN）")
    void setMode_serializesCcModeLiteral() throws Exception {
        JsonNode node = toJson(new PermissionUpdate.SetMode(
            PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS));
        assertThat(node.path("type").asText()).isEqualTo("setMode");
        assertThat(node.path("mode").asText()).isEqualTo("acceptEdits");
        assertThat(node.path("destination").asText()).isEqualTo("session");
    }

    // ── 验收 3：add/remove directories 可区分 ───────────────────────────────

    @Test
    @DisplayName("AddDirectories 与 RemoveDirectories 可区分：type 不同且回传不再互相误判")
    void directories_addAndRemoveAreDistinguishable() throws Exception {
        PermissionUpdate.AddDirectories add = new PermissionUpdate.AddDirectories(
            PermissionUpdate.Destination.SESSION, List.of("/workspace"));
        PermissionUpdate.RemoveDirectories remove = new PermissionUpdate.RemoveDirectories(
            PermissionUpdate.Destination.SESSION, List.of("/workspace"));

        JsonNode addJson = toJson(add);
        JsonNode removeJson = toJson(remove);

        assertThat(addJson.path("type").asText()).isEqualTo("addDirectories");
        assertThat(removeJson.path("type").asText()).isEqualTo("removeDirectories");
        assertThat(addJson).as("仅 type 不同即可区分").isNotEqualTo(removeJson);
        // 两者字段名恒为 CC 的 directories（旧 paths 会让二者在无 type 时不可区分）
        assertThat(addJson.path("directories")).hasSize(1);
        assertThat(removeJson.path("directories")).hasSize(1);
        assertThat(addJson.has("paths")).isFalse();
        assertThat(removeJson.has("paths")).isFalse();

        // 决定性断言：各自回传后必须解析回<b>同型</b>，而不是对方
        assertThat(parseBack(addJson).orElseThrow())
            .isInstanceOf(PermissionUpdate.AddDirectories.class)
            .isEqualTo(add);
        assertThat(parseBack(removeJson).orElseThrow())
            .isInstanceOf(PermissionUpdate.RemoveDirectories.class)
            .isEqualTo(remove);
    }

    @Test
    @DisplayName("旧 Java 形状（paths + 无 type）不再被推断成 removeDirectories，而是 WARN 丢弃")
    void legacyPathsWithoutType_isRejectedLoudly() {
        // 批 A1 前：{"destination":"SESSION","paths":["/workspace"]} 无 type ⇒ has("paths")
        //   ⇒ parseDirectories(node, false) ⇒ 新增目录被读成删除目录。
        JsonNode legacy = JSON.createObjectNode()
            .put("destination", "SESSION")
            .set("paths", JSON.createArrayNode().add("/workspace"));

        assertThat(WebSocketPermissionPrompter.parsePermissionUpdate(legacy)).isNull();
        assertThat(warns())
            .as("无 type 的旧形状必须留痕（禁止静默失效）")
            .anyMatch(w -> w.contains("缺 type"));
    }

    // ── 验收 4：静默丢弃 → ≥WARN（含反向实验配方）──────────────────────────

    @Test
    @DisplayName("rules 元素缺扁平的 toolName ⇒ WARN 留痕，不再静默丢弃")
    void rulesMissingFlatToolName_warnsInsteadOfSilentDrop() {
        // 这正是批 A1 前出站 JSON 的回传形状：toolName 嵌在 ruleValue 下。
        ObjectNode nested = JSON.createObjectNode()
            .put("type", "addRules")
            .put("destination", "localSettings")
            .put("behavior", "allow");
        nested.set("rules", JSON.createArrayNode().add(JSON.createObjectNode()
            .put("source", "LOCAL_SETTINGS")
            .put("ruleBehavior", "ALLOW")
            .set("ruleValue", JSON.createObjectNode()
                .put("toolName", "Bash")
                .put("ruleContent", "git status:*"))));

        assertThat(WebSocketPermissionPrompter.parsePermissionUpdate(nested))
            .as("嵌套 Java 形状不是合法 CC 线格式 ⇒ 解析结果为空")
            .isNull();
        assertThat(warns())
            .as("被丢弃的规则元素必须 ≥WARN 留痕（production root=INFO，debug 不算留痕）")
            .anyMatch(w -> w.contains("rules 元素缺 toolName"));

        // 对照组：同一负载换成扁平 CC 形状必须解析成功 —— 保证上面的 WARN 断言不是恒真
        ObjectNode flat = JSON.createObjectNode()
            .put("type", "addRules")
            .put("destination", "localSettings")
            .put("behavior", "allow");
        flat.set("rules", JSON.createArrayNode().add(JSON.createObjectNode()
            .put("toolName", "Bash")
            .put("ruleContent", "git status:*")));
        assertThat(WebSocketPermissionPrompter.parsePermissionUpdate(flat))
            .as("扁平 CC 形状必须解析成功")
            .isNotNull();
    }

    @Test
    @DisplayName("未知 type / 缺字段的 addRules ⇒ WARN 留痕")
    void unknownTypeAndMissingField_warnInsteadOfSilentDrop() {
        JsonNode unknownType = JSON.createObjectNode().put("type", "grantEverything");
        assertThat(WebSocketPermissionPrompter.parsePermissionUpdate(unknownType)).isNull();
        assertThat(warns()).anyMatch(w -> w.contains("字段缺失或非法"));

        appender.list.clear();

        // addRules 缺 behavior（CC schema 必填）
        ObjectNode missingBehavior = JSON.createObjectNode()
            .put("type", "addRules")
            .put("destination", "session");
        missingBehavior.set("rules", JSON.createArrayNode().add(
            JSON.createObjectNode().put("toolName", "Bash")));
        assertThat(WebSocketPermissionPrompter.parsePermissionUpdate(missingBehavior)).isNull();
        assertThat(warns())
            .as("判别通过但字段非法也必须留痕")
            .anyMatch(w -> w.contains("字段缺失或非法"));
    }

    @Test
    @DisplayName("round-trip 全链：出站 JSON 直接喂生产入站（parseUpdatedPermissions）")
    void outboundJsonFeedsProductionInboundParser() throws Exception {
        List<PermissionUpdate> originals = List.of(
            new PermissionUpdate.AddRules(
                PermissionUpdate.Destination.LOCAL_SETTINGS,
                List.of(rule(PermissionRuleSource.LOCAL_SETTINGS, PermissionBehavior.ALLOW,
                    "Bash", "git status:*")),
                PermissionBehavior.ALLOW),
            new PermissionUpdate.AddDirectories(
                PermissionUpdate.Destination.SESSION, List.of("/workspace")));

        // 模拟前端「原样回传」：把出站 JSON 原封不动交给入站解析入口
        List<JsonNode> echoed = originals.stream()
            .map(u -> {
                try {
                    return toJson(u);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            })
            .toList();

        assertThat(WebSocketPermissionPrompter.parseUpdatedPermissions(echoed))
            .as("前端原样回传必须被完整解析（此前恒为空列表）")
            .isEqualTo(originals);
    }
}
