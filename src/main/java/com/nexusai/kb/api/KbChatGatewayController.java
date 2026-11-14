package com.nexusai.kb.api;

import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.chat.ChatService;
import com.nexusai.kb.agent.KbBuiltInAgentDefs;
import com.nexusai.kb.app.qa.KbQaPipeline;
import com.nexusai.kb.app.qa.KbQaTurnService;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库对话<b>统一入口</b> · 按会话绑定 Agent 的 {@code agent_mode} 分流到两条链。
 *
 * <h2>⭐⭐ 为什么单独一个聚合入口，而不是改现有接口</h2>
 * 本仓已经有两条独立的对话链，各自的对端、状态机、流式事件、停止语义都不同：
 * <table border="1">
 *   <tr><th>链</th><th>现有入口</th><th>承载</th></tr>
 *   <tr><td>RAG 固定流水线</td><td>{@code POST /api/v1/knowledge-chat/{sid}}</td>
 *       <td>{@link KbQaTurnService}（理解→检索→生成→引用）</td></tr>
 *   <tr><td>ReAct 工具循环</td><td>{@code POST /api/v1/sessions/{sid}/messages}</td>
 *       <td>{@link ChatService} → {@code LlmAgentLoop}（调工具）</td></tr>
 * </table>
 *
 * <p>把分流塞进这两个已有入口，等于<b>改主对话链路</b> —— 所有会话（含用户日常在用的）
 * 都要过一道新判断，判错就会把正常对话喂给 RAG 链。那是最不该冒的险。
 *
 * <p>⇒ 本类只做<b>一件事</b>：读会话的 agent → 看它的 {@code agent_mode} → 转发给对应链。
 * <b>两个已有入口一行不改</b>；调用方（知识库面板）改用本入口即可，
 * 老调用方继续用老入口，行为完全不变。
 *
 * <h2>分流规则（与源站 {@code agent_mode} 语义一致）</h2>
 * <ul>
 *   <li>{@code quick-answer} ⇒ RAG 链。源站此模式下 {@code agent_type} 被忽略、也不调工具</li>
 *   <li>{@code smart-reasoning} ⇒ ReAct 链，会调工具</li>
 *   <li><b>会话未绑定 agent</b> ⇒ ReAct 链（保持本仓既有默认，⛔ 新入口不改变默认）</li>
 *   <li>绑定的不是知识库 agent（如 {@code Explore}）⇒ ReAct 链</li>
 * </ul>
 *
 * <h2>⚠️ 不做的事</h2>
 * <ul>
 *   <li>⛔ 不复制两条链的任何逻辑，只转发</li>
 *   <li>⛔ 不改 {@code ChatController} / {@code KnowledgeChatController}</li>
 *   <li>⛔ 不在本类判断「该不该检索知识库」——那是各链自己的事</li>
 *   <li>⛔ 不实现 busy 排队 / 即时命令 —— ReAct 链内部有兜底（{@code processUserMessage}
 *       自己会判 busy），本入口不重复造那套状态机</li>
 * </ul>
 */
@RestController
public class KbChatGatewayController {

    private static final Logger log = LoggerFactory.getLogger(KbChatGatewayController.class);

    /** 分流结果：走哪条链。 */
    public enum Route {
        /** RAG 固定流水线（quick-answer）。 */
        RAG,
        /** ReAct 工具循环（smart-reasoning / 未绑定 / 非 kb agent）。 */
        REACT
    }

    @Autowired private SessionMapper sessionMapper;
    @Autowired private KbQaTurnService turnService;
    @Autowired private ChatService chatService;
    @Autowired private com.nexusai.domain.session.MessageService messageService;
    @Autowired private SimpMessagingTemplate wsTemplate;
    @Autowired private com.nexusai.kb.domain.KnowledgeBaseService knowledgeBaseService;
    @Autowired private com.nexusai.kb.domain.KnowledgeService knowledgeService;

    /** 仅用于解析 `indexing_strategy` / `chunking_config` 这类 TEXT 列里的 JSON 小片段。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** 稳定句柄前缀 · 与 {@code KbHandleResolver} 一致（工具侧按同一前缀解析）。 */
    private static final String KB_PREFIX = com.nexusai.kb.agent.tool.KbHandleResolver.KB_PREFIX;
    private static final String DOC_PREFIX = com.nexusai.kb.agent.tool.KbHandleResolver.DOC_PREFIX;

    /**
     * 统一对话入口。
     *
     * @param sessionId 会话 ID
     * @param req       请求体
     * @return 202 + 受理信息（{@code route} 说明实际走了哪条链）
     */
    @PostMapping("/api/v1/kb/chat/{sessionId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GatewayAccepted chat(@PathVariable String sessionId, @RequestBody GatewayRequest req) {
        if (req == null || req.content() == null || req.content().isBlank()) {
            throw new com.nexusai.infra.exception.ValidationException("content 不能为空");
        }

        String agentId = boundAgentId(sessionId);
        Route route = resolveRoute(agentId);
        log.info("[KB对话入口] 分流 session={} agent={} mode={} → {}",
            sessionId, agentId == null ? "(未绑定)" : agentId,
            agentId == null ? "-" : BuiltInAgents.agentModeOf(agentId).orElse("-"),
            route);

        if (route == Route.RAG) {
            return viaRag(sessionId, req);
        }
        return viaReact(sessionId, req);
    }

    // ── 两条链的转发 ──────────────────────────────────────────────────────────

    /** RAG 链：与 {@code KnowledgeChatController.ask} 完全同一个调用。 */
    private GatewayAccepted viaRag(String sessionId, GatewayRequest req) {
        KbQaPipeline.Request pipelineReq = new KbQaPipeline.Request(
            sessionId, null, null, req.content(),
            req.knowledgeBaseIds(), req.knowledgeIds(), req.modelName(),
            Boolean.TRUE.equals(req.webSearchEnabled()), new AtomicBoolean(false));
        KbQaTurnService.Accepted accepted = turnService.accept(sessionId, req.content(), pipelineReq, req.attachments());
        return new GatewayAccepted(accepted.userMessageId(), accepted.assistantMessageId(),
            accepted.streamTopic(), false, Route.RAG.name());
    }

    /**
     * ReAct 链：与 {@code ChatController.send} 的空闲路径完全同一组调用
     * （{@code validateFallbackModelDistinct} → {@code createUserMessage} → {@code processUserMessage}）。
     *
     * <p>⚠️ busy 时由 {@code processUserMessage} 内部兜底排队（它自己有
     * {@code isSessionRunning} 判定），本入口不重复实现排队框那套状态机 ——
     * 那会让两条路径的排队行为出现分叉。
     */
    private GatewayAccepted viaReact(String sessionId, GatewayRequest req) {
        // ⭐ 检索范围以 `<runtime_context scope="this_turn">` 块拼进 **user content**（对齐源站语义）。
        //
        //   ⛔ 为什么不用 appendSystemPrompt：它真的追加到 system prompt 末尾
        //     （EffectiveSystemPromptBuilder:187/217/259），而范围每轮都变 ⇒ 系统提示前缀每轮不同
        //     ⇒ prompt cache 整体失效；语义上 system 是长期指令，也不该装「本轮」数据。
        //
        //   ⭐ 原样入库（2026-11-14 用户裁定）：不 strip。历史重放时 content 与当轮实际发送
        //     完全一致 ⇒ 前缀缓存连续。代价是后续轮次会在历史里看到旧的 scope 快照 ——
        //     因此**每轮都注入**（没选库时也注入空块 + 明确文案，见 buildRuntimeContextBlock）。
        //
        //   前端气泡按 `stripRuntimeContextForDisplay` 过滤后只显示用户原话。
        String llmContent = buildRuntimeContextBlock(req) + "\n\n" + req.content();
        SendMessageRequest sendReq = new SendMessageRequest(
            llmContent,
            req.modelName(),
            null,                 // useFastModel：网关不指定，走会话默认
            req.attachments() == null ? List.of() : req.attachments(),
            null,                 // appendSystemPrompt：范围已进 user content，这里保持 null
            null,                 // fallbackModel
            null,                 // permissionMode
            null,                 // dangerouslySkipPermissions
            null,                 // jsonSchema
            // ⭐ 请求级检索范围（@ 的库/文件）。批 1 已把 requestExtras 打通到 ToolUseContext，
            //   KbAgentToolScope 用它把工具作用域收窄到用户本次的选择。
            //   null = 用户没选任何库/文件 ⇒ 工具范围仍只由会话绑定的 kb_agent 配置决定（原行为）。
            kbScopeExtras(req));
        chatService.validateFallbackModelDistinct(sessionId, sendReq);
        MessageCreatedResponse resp = messageService.createUserMessage(sessionId, sendReq);
        chatService.processUserMessage(sessionId, resp.userMessageId(), sendReq, wsTemplate);
        return new GatewayAccepted(resp.userMessageId(), resp.assistantMessageId(),
            resp.streamTopic(), resp.queued(), Route.REACT.name());
    }

    /**
     * 把「用户在面板里选中的知识库 / 文件」拼成模型可见的范围说明（ReAct 链专用）。
     *
     * <p><b>为什么是系统提示而不是正文</b>：正文要落库并显示给用户，范围说明属于给模型的
     * 附属信息（与 RAG 链的 {@code <attachments>} 清单同一性质），不该出现在气泡里。
     *
     * <p>拼名称而不是只给 id：模型对 id 没有语义，报「要查哪个库」正是因为看不到名字；
     * id 同时给出是为了让它调用 {@code knowledge_search} 时能填 {@code knowledge_base_ids}。
     *
     * @return 范围说明；用户没选任何库/文件 ⇒ {@code null}（不改变任何既有行为）
     */
    /**
     * 请求级检索范围（`requestExtras["kbScope"]`）—— 工具执行期的硬约束来源。
     *
     * <p>与 {@link #reactScopePrompt} 的分工：那段文本是给**模型看**的（让它填对参数），
     * 这里是给**工具**看的（模型不填也搜不到范围外）。源站对应 `SearchTargets`。
     *
     * <p>只放原始 id，不做解析：文档 → 父库的推导在 {@code KbAgentToolScope} 里做（那里已有
     * 知识库/文档服务），网关不做数据查询。
     *
     * @return {@code {"kbScope": {"knowledgeBaseIds": [...], "knowledgeIds": [...]}}}；
     *         两个列表都空 ⇒ {@code null}（不改变任何既有行为）
     */
    private java.util.Map<String, Object> kbScopeExtras(GatewayRequest req) {
        java.util.List<String> kbIds = req.knowledgeBaseIds() == null ? java.util.List.of() : req.knowledgeBaseIds();
        java.util.List<String> docIds = req.knowledgeIds() == null ? java.util.List.of() : req.knowledgeIds();
        if (kbIds.isEmpty() && docIds.isEmpty()) {
            return null;
        }
        java.util.Map<String, Object> scope = new java.util.LinkedHashMap<>();
        scope.put("knowledgeBaseIds", java.util.List.copyOf(kbIds));
        scope.put("knowledgeIds", java.util.List.copyOf(docIds));
        return java.util.Map.of("kbScope", scope);
    }

    /**
     * 本轮检索范围的模型可见形式（`<runtime_context scope="this_turn">`）。
     *
     * <p>对齐源站 `internal/agent/observe.go:612 buildRuntimeContextBlock`：范围以结构化 XML
     * 信封给出，而不是自由散文 —— 模型更容易把它当数据而不是指令，后续也便于程序处理。
     *
     * <p><b>⭐ 每轮都生成，没选也给空块</b>：因为这块会**原样入库**并随历史重放，模型在第二轮
     * 会同时看到「上一轮的范围快照」和「本轮的范围」。若不注入空块，它会以为上一轮的范围还生效
     * （第 1 轮 @ 了 A 库、第 2 轮什么都没选 ⇒ 模型仍只查 A）。
     *
     * <p>⚠️ 本方法只做「给模型看」的部分；执行期硬约束走 `requestExtras.kbScope`
     * （见 {@link #kbScopeExtras}），两者独立，缺一个都不算对齐。
     *
     * @param req 网关请求（取用户选的库/文件 id）
     * @return XML 块（永不为 null / 空白）
     */
    private String buildRuntimeContextBlock(GatewayRequest req) {
        List<String> kbIds = req.knowledgeBaseIds() == null ? List.of() : req.knowledgeBaseIds();
        List<String> docIds = req.knowledgeIds() == null ? List.of() : req.knowledgeIds();
        StringBuilder sb = new StringBuilder(512);
        sb.append("<runtime_context scope=\"this_turn\">\n");
        if (kbIds.isEmpty() && docIds.isEmpty()) {
            sb.append("  <bound_knowledge_bases />\n");
            sb.append("  <pinned_documents />\n");
            sb.append("  <note>本轮用户未指定知识库/文件范围，可在全部可检索范围内检索。</note>\n");
            sb.append("</runtime_context>");
            return sb.toString();
        }
        sb.append("  <bound_knowledge_bases>\n");
        for (String id : kbIds) {
            com.nexusai.kb.dto.KnowledgeBaseDto dto = null;
            try {
                dto = knowledgeBaseService.getById(id);
            } catch (RuntimeException e) {
                log.debug("[KB对话入口] 查库信息失败 id={} 原因={}", id, e.toString());
            }
            String name = dto != null && dto.name() != null && !dto.name().isBlank() ? dto.name() : id;
            String type = dto != null && dto.type() != null && !dto.type().isBlank() ? dto.type() : "document";
            String caps = capabilitiesOf(dto);
            String desc = dto == null ? null : dto.description();
            // ⭐ 给模型看的是稳定句柄（kb://前8位），不是 36 字符 UUID。
            //   执行期范围仍走 kbScopeExtras 的全 id —— 两者不能混。
            sb.append("    <knowledge_base id=\"").append(escapeXml(handle(KB_PREFIX, id)))
              .append("\" name=\"").append(escapeXml(name))
              .append("\" type=\"").append(escapeXml(type))
              .append("\" capabilities=\"").append(escapeXml(caps)).append("\"");
            if (desc == null || desc.isBlank()) {
                sb.append(" />\n");
            } else {
                sb.append(">\n      <description>").append(escapeXml(desc)).append("</description>\n");
                sb.append("    </knowledge_base>\n");
            }
        }
        sb.append("  </bound_knowledge_bases>\n");
        sb.append("  <pinned_documents scope=\"authoritative_for_this_turn\">\n");
        for (String id : docIds) {
            String title = docTitleOf(id);
            String ext = fileTypeOf(id);
            sb.append("    <document knowledge_id=\"").append(escapeXml(handle(DOC_PREFIX, id)))
              .append("\" title=\"").append(escapeXml(title)).append("\"");
            if (!ext.isEmpty()) {
                sb.append(" file_type=\"").append(escapeXml(ext)).append("\"");
            }
            sb.append(" />\n");
        }
        sb.append("  </pinned_documents>\n");
        sb.append("  <note>工具参数里的知识库/文档请使用上面的 kb:// / doc:// 句柄；")
          .append("也可直接使用完整 id。</note>\n");
        sb.append("</runtime_context>");
        return sb.toString();
    }

    /**
     * 库的检索能力（`chunks` / `wiki`）—— 让模型据此选工具，而不是盲搜。
     *
     * <p>来源：`indexing_strategy` JSON 的 `vector_enabled` / `keyword_enabled` / `wiki_enabled`。
     * 解析失败返回空串（模型看不到能力，退回通用策略，不影响正确性）。
     */
    private String capabilitiesOf(com.nexusai.kb.dto.KnowledgeBaseDto dto) {
        if (dto == null) {
            return "";
        }
        String raw = dto.indexingStrategy();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            var node = JSON.readTree(raw);
            java.util.List<String> caps = new java.util.ArrayList<>(2);
            if (node.path("vector_enabled").asBoolean(false) || node.path("keyword_enabled").asBoolean(false)) {
                caps.add("chunks");
            }
            if (node.path("wiki_enabled").asBoolean(false)) {
                caps.add("wiki");
            }
            return String.join(",", caps);
        } catch (Exception e) {
            log.debug("[KB对话入口] 解析 indexing_strategy 失败: {}", e.toString());
            return "";
        }
    }

    /** 文档扩展名（用于 `file_type`）· 查不到返回空串。 */
    private String fileTypeOf(String id) {
        try {
            var dto = knowledgeService.getById(id);
            String name = dto == null ? null : (dto.fileName() != null ? dto.fileName() : dto.title());
            if (name == null) {
                return "";
            }
            int dot = name.lastIndexOf('.');
            return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 稳定句柄：前缀 + id 前 8 位（与工具侧 {@code KbHandleResolver} 的前缀匹配同一套规则）。 */
    private static String handle(String prefix, String id) {
        return com.nexusai.kb.agent.tool.KbHandleResolver.toHandle(prefix, id);
    }

    /** XML 属性/文本转义 —— 库名、文档名里可能带 `&` `<` `"`，不转义会破坏信封结构。 */
    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 库名 · 查不到时回落 id（⛔ 不编造名称）。 */
    private String kbNameOf(String id) {
        try {
            var dto = knowledgeBaseService.getById(id);
            if (dto != null && dto.name() != null && !dto.name().isBlank()) {
                return dto.name();
            }
        } catch (RuntimeException e) {
            log.debug("[KB对话入口] 查库名失败 id={} 原因={}", id, e.toString());
        }
        return id;
    }

    /** 文档名 · 查不到时回落 id。 */
    private String docTitleOf(String id) {
        try {
            var dto = knowledgeService.getById(id);
            if (dto != null) {
                String t = dto.title() != null && !dto.title().isBlank() ? dto.title() : dto.fileName();
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
        } catch (RuntimeException e) {
            log.debug("[KB对话入口] 查文档名失败 id={} 原因={}", id, e.toString());
        }
        return id;
    }

    // ── 分流判据 ──────────────────────────────────────────────────────────────

    /**
     * 读会话绑定的 agent id。
     *
     * @param sessionId 会话 ID
     * @return agent id；未绑定 / 会话不存在 ⇒ {@code null}
     */
    String boundAgentId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            var row = sessionMapper.selectOneById(sessionId);
            if (row == null) {
                return null;
            }
            String agent = row.getMainThreadAgent();
            return agent == null || agent.isBlank() ? null : agent.trim();
        } catch (RuntimeException e) {
            // 读会话失败不该让整轮失败 —— 按「未绑定」处理（走 ReAct，本仓既有默认）
            log.warn("[KB对话入口] 读会话失败，按未绑定处理 session={} 原因={}", sessionId, e.toString());
            return null;
        }
    }

    /**
     * 按 agent 的执行模式决定走哪条链。
     *
     * <p><b>判据只有一条</b>：{@code agentMode == "quick-answer"} ⇒ RAG；其余一律 ReAct。
     * ⚠️「其余」包含三种情况，共同点是<b>都不该走 RAG</b>：
     * <ol>
     *   <li>{@code smart-reasoning} ⇒ 本来就是 ReAct</li>
     *   <li>未绑定 agent ⇒ 保持本仓既有默认（ReAct），⛔ 新入口不改变默认</li>
     *   <li>绑定了非 kb agent（{@code Explore} 等）⇒ 它们没有 agentMode，走 ReAct</li>
     * </ol>
     *
     * @param agentId 会话绑定的 agent id（可 null）
     * @return 分流结果
     */
    Route resolveRoute(String agentId) {
        if (agentId == null) {
            return Route.REACT;
        }
        Optional<String> mode = BuiltInAgents.agentModeOf(agentId);
        if (mode.isPresent() && KbBuiltInAgentDefs.MODE_QUICK_ANSWER.equals(mode.get())) {
            return Route.RAG;
        }
        return Route.REACT;
    }

    // ── 请求 / 响应 ───────────────────────────────────────────────────────────

    /**
     * 统一入参。
     *
     * @param content           用户问题（必填）
     * @param knowledgeBaseIds  指定知识库（可空 = 全部授权库；仅 RAG 链消费）
     * @param modelName         模型名（可空 = 会话/配置默认）
     * @param webSearchEnabled  是否允许联网（仅 RAG 链消费）
     */
    public record GatewayRequest(
        String content,
        List<String> knowledgeBaseIds,
        /** 文档级范围（@ 选的文件）· 对齐源站 {@code knowledge_ids} */
        List<String> knowledgeIds,
        /** 本轮 @ 选中的知识库/文件快照（落 user 气泡胶囊；不参与检索范围） */
        List<com.nexusai.model.session.dto.AttachmentRequest> attachments,
        String modelName,
        Boolean webSearchEnabled
    ) {
    }

    /**
     * 统一出参 —— 两条链的返回形状不同（4 字段 vs 3 字段），这里归一成同一份。
     *
     * @param userMessageId      用户消息 id
     * @param assistantMessageId 助手消息 id（{@code msg-stub-pending} = 待生成）
     * @param streamTopic        流式 topic
     * @param queued             是否因上一轮未结束而入队
     * @param route              实际走的链：{@code RAG} / {@code REACT}
     */
    public record GatewayAccepted(
        String userMessageId,
        String assistantMessageId,
        String streamTopic,
        boolean queued,
        String route
    ) {
    }
}
