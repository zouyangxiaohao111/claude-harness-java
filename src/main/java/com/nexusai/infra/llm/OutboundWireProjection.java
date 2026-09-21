package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>出站请求的「线级（wire）投影」唯一实现</b> —— 把 {@link ChatMessageDto} / tools 源 JSON
 * 投影成<b>真正会写进 OpenAI 兼容请求体的那些字段</b>组成的规范化字符串。
 *
 * <p><b>为什么必须存在这个类（= 本类存在的全部理由）</b>：
 * <ol>
 *   <li><b>出站头部探针</b>（{@code OpenAiSdkProvider.headProbeField / fingerprintHead}，
 *       批次「前缀缓存头部冻结」步骤 1）要判「跨 run 头部哪个字节在漂」。若它 hash 的是
 *       {@link ChatMessageDto#toString()}（Java record 自动生成、含 {@code id} / {@code createdAt} /
 *       {@code time} 等<b>不进 wire</b> 的客户端字段），则 {@code messages[0]} 这个
 *       <b>每轮用 {@code UUID.randomUUID()} + {@code OffsetDateTime.now()} 新造</b>的 userContext
 *       元消息（{@code AgentLoopContext.metaUserMessage} / {@code prependUserContext}）会
 *       <b>每次都变 hash</b> —— 即使 wire 字节完全不变。⇒ 探针会把判据指向最关心的那一格、
 *       且方向是错的（假阳性）。</li>
 *   <li>测试基建（{@code test.support.OutboundRequest} 的 {@code wireMessages()} /
 *       {@code toolsText()}）要表达「DeepSeek 前缀缓存看到的字节」这一事实，
 *       否则 DTO 深比较会因每轮新 id 而恒假。</li>
 * </ol>
 * <p>两处诉求同一口径、且方向相反的错误代价都很高（探针假阳性会误导归因；测试假红会掩盖真实漂移）。
 * ⇒ <b>收口成这一个生产可复用的单点</b>，⛔ 任何一处都不得另造第二套投影（同语义禁止双实现）。
 * 依赖方向 = 测试 → 生产（{@code test.support.OutboundRequest} 调本类），
 * ⛔ 生产代码不依赖测试类。
 *
 * <h2>口径一：消息（{@code projectMessage} / {@code projectMessages}）</h2>
 * <p>字段集 = {@code OpenAiSdkProvider.toSdkMessage(ChatMessageDto)} 读取的字段：
 * {@code role} / {@code content} / {@code contentBlocks} / {@code toolCalls(id,name,arguments)} /
 * {@code toolCallId} / {@code acceptFeedback}。
 * <ul>
 *   <li>⚠ 本投影是 wire 的<b>超集</b>（例如 role=user 且 contentBlocks 非空时 wire 弃 {@code content}，
 *       本投影仍带上）。取超集是<b>故意</b>的：只会更严，不会漏判。</li>
 *   <li>⚠ {@code role=system} 出站被 {@code toSdkMessage} 过滤（返回 null ⇒ provider 侧 skip）⇒
 *       投影为占位 {@link #FILTERED_SYSTEM}，保住「条数对齐」。</li>
 *   <li>{@code ToolCallDto} 的 wire 字段只有 {@code id} / {@code name} / {@code arguments}
 *       （{@code ToolCallDto} 若还有别的字段，一律不进本投影）。</li>
 *   <li>⚠ <b>同构纪律（H2）</b>：wire 对字段做的<b>折叠</b>必须逐一照搬，⛔ 不得合并两个不同的
 *       wire 状态。已钉住的一处 = {@code arguments} 的 {@code null → "{}"}（{@link #WIRE_NULL_ARGUMENTS}）：
 *       把 {@code null} 与 {@code ""} 都投成 {@code ""} 会让 {@code null↔""} 的真实漂移<b>判绿</b>
 *       （假阴性）。判据见 {@code OutboundWireProjectionWireFidelityTest}。</li>
 * </ul>
 *
 * <h2>口径二：工具（{@code projectToolNode} / {@code projectTools}）</h2>
 * <p>字段集 = {@code OpenAiSdkProvider.toOpenAiSdkTool(JsonNode, boolean)} 读取并透传的字段：
 * wrapper 顶层 {@code type} + {@code function.{name, description, parameters, strict}}。
 * <p>⭐ <b>与「源 JSON 直接 toString」的关键差异</b>：源数组是 {@code ToolRegistry.toOpenAiToolsArray}
 * 的产物，其中 wrapper 顶层的 {@code defer_loading} <b>从不被 {@code toOpenAiSdkTool} 读取</b>
 * ⇒ 它<b>不进 wire</b>。若把源 JSON 整体纳入判据，则 {@code defer_loading} 翻转（由 tool-search
 * discovered-set 决定，可跨轮变化）会把静态 wire 判成漂移 ⇒ <b>又一处恒假阳性</b>。
 * 另：{@code strict} 只在模型层门控通过时才上 wire，故须把门控值一并传入。
 *
 * <h2>⛔ 不是做什么</h2>
 * <p>不做任何缓存/状态：纯函数，同输入恒同输出。⛔ 不打印正文 —— debug 日志只记「哪一格被折叠/原样透传」
 * （如 {@code arguments=null → "{}"}、tool 顶层 {@code type} 非文本），<b>不含字段正文本身</b>。
 */
public final class OutboundWireProjection {

    private static final Logger log = LoggerFactory.getLogger(OutboundWireProjection.class);

    private OutboundWireProjection() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code role=system} 出站过滤占位（见类 javadoc「口径一」）· ⛔ 与 {@code "<none>"} 不同义。 */
    public static final String FILTERED_SYSTEM = "<filtered:system>";

    /** 消息为 null / role 为 null 的占位。 */
    public static final String NULL_MESSAGE = "<null>";

    /**
     * {@code ToolCallDto.arguments == null} 在 wire 上的折叠值 ·
     * {@code OpenAiSdkProvider.toSdkMessage}（assistant 分支）
     * = {@code arguments(tc.arguments() == null ? "{}" : tc.arguments())}。
     * <p>⛔ 与 {@code ""} <b>不同义</b>：{@code null → "{}"}、{@code "" → ""} 是两个<b>不同的 wire 字节</b>
     * （把两者折叠即假阴性，见 {@code OutboundWireProjectionWireFidelityTest}）。
     */
    public static final String WIRE_NULL_ARGUMENTS = "{}";

    /** 工具被 {@code toOpenAiSdkTool} 丢弃（缺 function / 缺 name / 非对象）的占位。 */
    public static final String DROPPED_TOOL = "<dropped:tool>";

    // ════════════════════════════════ 消息 ════════════════════════════════

    /**
     * 单条消息的线级投影 · 字段集 = {@code OpenAiSdkProvider.toSdkMessage}（见类 javadoc「口径一」）。
     *
     * @param m 出站消息（可 null）
     * @return 规范化 JSON 串；null/role 为 null → {@link #NULL_MESSAGE}；
     *         role=system → {@link #FILTERED_SYSTEM}（出站被过滤，占位保条数对齐）
     */
    public static String projectMessage(ChatMessageDto m) {
        if (m == null || m.role() == null) {
            return NULL_MESSAGE;
        }
        String role = m.role().name();
        if ("system".equals(role)) {
            return FILTERED_SYSTEM;
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("role", role);
        node.put("content", m.content() == null ? "" : m.content());
        node.put("toolCallId", m.toolCallId() == null ? "" : m.toolCallId());
        node.put("acceptFeedback", m.acceptFeedback() == null ? "" : m.acceptFeedback());
        ArrayNode blocks = node.putArray("contentBlocks");
        if (m.contentBlocks() != null) {
            for (Object b : m.contentBlocks()) {
                blocks.add(b == null ? "null" : b.toString());
            }
        }
        ArrayNode tcs = node.putArray("toolCalls");
        if (m.toolCalls() != null) {
            for (ToolCallDto tc : m.toolCalls()) {
                if (tc == null) {
                    tcs.add("null");
                    continue;
                }
                ObjectNode t = tcs.addObject();
                t.put("id", tc.id() == null ? "" : tc.id());
                t.put("name", tc.name() == null ? "" : tc.name());
                // [H2 修复] arguments 必须与 wire【逐字同构】：toSdkMessage（assistant 分支）写的是
                //   `arguments(tc.arguments() == null ? "{}" : tc.arguments())` —— null 落 "{}"，
                //   空串原样落 ""，两者 wire 字节【不同】。旧实现把 null 与 "" 都折叠成 "" ⇒
                //   ① null↔"" 变化时 wire 变了而指纹不变（假阴性，真实漂移被判绿）；
                //   ② null↔"{}" 变化时 wire 没变而指纹变了（假阳性，漂移误报）。
                //   ⇒ 这里照搬 wire 的同一分支，⛔ 不得再折叠（判据见 OutboundWireProjectionWireFidelityTest）。
                if (tc.arguments() == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("线级投影：tool call name={} 的 arguments=null ⇒ 投影为 {}（对齐 wire 的 null 折叠）",
                            tc.name(), WIRE_NULL_ARGUMENTS);
                    }
                    t.put("arguments", WIRE_NULL_ARGUMENTS);
                } else {
                    t.put("arguments", tc.arguments());
                }
            }
        }
        return node.toString();
    }

    /**
     * 整串消息的线级投影 · <b>与 wire 同序</b>：先过
     * {@link ToolResultPairingRepair#ensureToolResultPairing(List)}（{@code OpenAiSdkProvider}
     * 发送边界的同一次修复），否则「悬挂 tool_use / 孤儿 tool_result」会在两侧被不同地折叠，
     * 产生与 wire 无关的假差异。
     *
     * @param messages 出站消息列表（可 null）
     * @return 逐条投影（保持顺序）；null → 空列表
     */
    public static List<String> projectMessages(List<ChatMessageDto> messages) {
        if (messages == null) {
            return List.of();
        }
        List<ChatMessageDto> repaired = ToolResultPairingRepair.ensureToolResultPairing(messages);
        List<String> out = new ArrayList<>(repaired.size());
        for (ChatMessageDto m : repaired) {
            out.add(projectMessage(m));
        }
        return out;
    }

    // ════════════════════════════════ 工具 ════════════════════════════════

    /**
     * 单个工具条目的线级投影 · 字段集 = {@code OpenAiSdkProvider.toOpenAiSdkTool}（见类 javadoc「口径二」）。
     *
     * @param toolNode       {@code ToolRegistry.toOpenAiToolsArray} 产出的 wrapper 节点
     * @param strictModelGate 模型层 strict 门控（{@code StructuredOutputsSupport.shouldTransmitStrictOpenAi}）
     *                        —— {@code false} 时 {@code strict} 不上 wire，投影同步省略；
     *                        测试侧无模型上下文时传 {@code true}（wire 超集，只会更严）
     * @return 规范化 JSON 串；被 {@code toOpenAiSdkTool} 丢弃（非对象 / 缺 function / 缺 name）→
     *         {@link #DROPPED_TOOL}（占位保序，对齐 {@link #FILTERED_SYSTEM} 的思路）
     */
    public static String projectToolNode(JsonNode toolNode, boolean strictModelGate) {
        if (toolNode == null || !toolNode.isObject()) {
            return DROPPED_TOOL;
        }
        JsonNode fnNode = toolNode.get("function");
        if (fnNode == null || !fnNode.isObject()) {
            return DROPPED_TOOL;
        }
        JsonNode nameNode = fnNode.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            return DROPPED_TOOL;
        }
        ObjectNode node = JSON.createObjectNode();
        JsonNode typeNode = toolNode.get("type");
        // [H2 修复] type 与 wire【逐字同构】：toOpenAiSdkTool 写的是
        //   `has("type") && get("type") != null ? JsonValue.fromJsonNode(get("type")) : JsonValue.from("function")`
        //   ⇒ 字段【存在】时原样透传该节点（显式 null / 非文本都照上 wire），只有字段【缺失】才落 "function"。
        //   旧实现用 `isNull() ? "function" : asText()` 把「显式 null」与「字段缺失」折叠成同一个
        //   "function" ⇒ 两个不同的 wire 状态投影相同（同类假阴性）。
        //   ToolRegistry.toOpenAiToolsArray 恒写文本 "function" ⇒ 该形态生产不可达，此处仍按 wire 同构收口。
        if (toolNode.has("type") && typeNode != null) {
            if (log.isDebugEnabled() && !typeNode.isTextual()) {
                log.debug("线级投影：tool '{}' 的顶层 type 非文本（{}）⇒ 原样透传（对齐 wire 的 fromJsonNode）",
                    nameNode.asText(), typeNode);
            }
            node.set("type", typeNode);
        } else {
            node.put("type", "function");
        }
        ObjectNode fn = node.putObject("function");
        fn.put("name", nameNode.asText());
        JsonNode descNode = fnNode.get("description");
        if (descNode != null && descNode.isTextual()) {
            fn.put("description", descNode.asText());
        }
        JsonNode paramsNode = fnNode.get("parameters");
        if (paramsNode != null && paramsNode.isObject()) {
            fn.set("parameters", paramsNode);
        }
        JsonNode strictNode = fnNode.get("strict");
        boolean strictMarked = strictNode != null && strictNode.isBoolean() && strictNode.asBoolean(false);
        if (strictMarked && strictModelGate) {
            fn.put("strict", true);
        }
        return node.toString();
    }

    /**
     * 整串工具的线级投影（顺序敏感 · ⛔ 不排序：wire 按数组序，重排即真漂移）。
     *
     * @param tools          {@code ToolRegistry.toOpenAiToolsArray} 产物（可 null = 不发送工具）
     * @param strictModelGate 见 {@link #projectToolNode(JsonNode, boolean)}
     * @return 规范化串；null / 空 → {@code "[]"}
     */
    public static String projectTools(ArrayNode tools, boolean strictModelGate) {
        if (tools == null || tools.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (JsonNode t : tools) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(projectToolNode(t, strictModelGate));
        }
        return sb.append(']').toString();
    }
}
