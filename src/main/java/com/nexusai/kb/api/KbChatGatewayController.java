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
    /** 会话临时附件（上传即解析）· 本条链路的正文内联用它。 */
    @Autowired private com.nexusai.kb.app.attachment.KbChatAttachmentService chatAttachmentService;

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
        return viaReact(sessionId, req, agentId);
    }

    // ── 两条链的转发 ──────────────────────────────────────────────────────────

    /** RAG 链：与 {@code KnowledgeChatController.ask} 完全同一个调用。 */
    private GatewayAccepted viaRag(String sessionId, GatewayRequest req) {
        // RAG 轮：正文内联进 user content（与历史投影里的 <attachments> 清单并存 ——
        //   用户 2026-11-14 明确裁定：就拼两份，不改 MessageService。
        String content = inlineAttachments(sessionId, req) + req.content();
        KbQaPipeline.Request pipelineReq = new KbQaPipeline.Request(
            sessionId, null, null, content,
            req.knowledgeBaseIds(), req.knowledgeIds(), req.modelName(),
            Boolean.TRUE.equals(req.webSearchEnabled()), new AtomicBoolean(false));
        KbQaTurnService.Accepted accepted = turnService.accept(sessionId, content, pipelineReq, req.attachments());
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
    private GatewayAccepted viaReact(String sessionId, GatewayRequest req, String agentId) {
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
        //
        //   ⭐⭐ 2026-11-14 对齐源站选库语义：不 @ 时**不再**是空范围，而是「当前人格可用的
        //     全部库」（源站 KBSelectionMode=all 的等价物，见 resolveTurnKbScope）。
        //     同一份 id 同时喂给下面两处，避免「给模型看的」与「工具硬约束」分叉。
        List<String> scopeKbIds = resolveTurnKbScope(req, agentId);
        // 会话临时附件：正文内联进 user content（源站 TemporaryDocument 预算）
        // ⭐ Agent 轮只给**清单 + 解析后 .md 路径**，正文由模型用 Read 按需读
        //   （对齐源站 describeTemporaryAttachmentsForAgent；RAG 轮仍内联正文）。
        String llmContent = buildRuntimeContextBlock(req, scopeKbIds) + "\n\n"
            + attachmentManifest(sessionId, req) + req.content();
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
            // ⭐ 请求级检索范围（@ 的库/文件 + 本轮完整读范围）。批 1 已把 requestExtras 打通到
            //   ToolUseContext，KbAgentToolScope 用它把工具作用域收窄到本轮范围。
            //   ⚠️ 与上面那段 XML 用同一份 scopeKbIds —— 两处各算一遍就会分叉。
            kbScopeExtras(req, scopeKbIds, agentId));
        chatService.validateFallbackModelDistinct(sessionId, sendReq);
        MessageCreatedResponse resp = messageService.createUserMessage(sessionId, sendReq);
        chatService.processUserMessage(sessionId, resp.userMessageId(), sendReq, wsTemplate);
        return new GatewayAccepted(resp.userMessageId(), resp.assistantMessageId(),
            resp.streamTopic(), resp.queued(), Route.REACT.name());
    }

    /**
     * RAG 轮的附件正文内联块（`<attachments>`，含正文）。
     *
     * <p>⚠️ 与 {@code MessageService.appendHistoryAttachments} 拼的清单**并存**（两份）——
     * 用户 2026-11-14 明确裁定不改 MessageService，接受重复；本块只补正文。
     */
    private String inlineAttachments(String sessionId, GatewayRequest req) {
        java.util.List<String> ids = req.attachmentIds();
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        java.util.List<com.nexusai.kb.app.attachment.KbChatAttachmentService.InlineDoc> docs =
            chatAttachmentService.resolveForPrompt(sessionId, ids);
        if (docs.isEmpty()) {
            log.info("[KB对话入口] 附件未就绪，跳过内联 session={} 请求数={}", sessionId, ids.size());
            return "";
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<attachments>\n");
        for (var d : docs) {
            sb.append("<attachment name=\"").append(escapeXml(d.fileName()))
              .append("\" type=\"").append(escapeXml(d.fileType())).append("\">\n")
              .append(d.text()).append("\n</attachment>\n");
        }
        sb.append("</attachments>\n");
        log.info("[KB对话入口] 已内联会话附件 session={} 个数={} 总字符={}",
            sessionId, docs.size(), sb.length());
        return sb.toString();
    }

    /**
     * Agent 轮的附件清单（不内联正文）· 对齐源站 {@code describeTemporaryAttachmentsForAgent}。
     *
     * <p>给模型文件名 / 类型 / 解析后 .md 的相对路径，需要时用 {@code Read} 读。
     * 路径相对会话 cwd（= {@code chatRoot()}），不需要放宽沙箱。
     */
    private String attachmentManifest(String sessionId, GatewayRequest req) {
        java.util.List<String> ids = req.attachmentIds();
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        java.util.List<com.nexusai.kb.app.attachment.KbChatAttachmentService.ManifestDoc> docs =
            chatAttachmentService.manifestForPrompt(sessionId, ids);
        if (docs.isEmpty()) {
            log.info("[KB对话入口] 附件未就绪，跳过清单 session={} 请求数={}", sessionId, ids.size());
            return "";
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("<attachments>\n");
        for (var d : docs) {
            sb.append("<attachment name=\"").append(escapeXml(d.fileName()))
              .append("\" type=\"").append(escapeXml(d.fileType()))
              .append("\" path=\"").append(escapeXml(d.path())).append("\" />\n");
        }
        sb.append("</attachments>\n");
        sb.append("<note>附件已解析为 Markdown；需要内容时用 Read 工具读取上面的 path")
          .append("（相对会话工作目录）。</note>\n");
        log.info("[KB对话入口] 已输出附件清单 session={} 个数={}", sessionId, docs.size());
        return sb.toString();
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
    private java.util.Map<String, Object> kbScopeExtras(GatewayRequest req, java.util.List<String> scopeKbIds,
                                                       String agentId) {
        java.util.List<String> kbIds = req.knowledgeBaseIds() == null ? java.util.List.of() : req.knowledgeBaseIds();
        java.util.List<String> docIds = req.knowledgeIds() == null ? java.util.List.of() : req.knowledgeIds();
        if (kbIds.isEmpty() && docIds.isEmpty() && scopeKbIds.isEmpty()) {
            return null;
        }
        java.util.List<String> explicit = explicitKbIds(req);
        // ⭐ 写范围（对齐源站 writableWikiKBIDs）：wiki-fixer 在源站是 kb_selection_mode=selected，
        //   等价物 = 只允许用户 @ 的库；其他人格沿用读范围（all 模式下有写权限即可写）。
        //   ⛔ 这里必须由网关算好，工具层与装配层都只读这一个字段 —— 三处各算一遍必然分叉。
        java.util.List<String> writable = isWikiFixer(agentId) ? explicit : scopeKbIds;

        java.util.Map<String, Object> scope = new java.util.LinkedHashMap<>();
        // 用户真正 @ 的（保留原始语义）
        scope.put("knowledgeBaseIds", java.util.List.copyOf(kbIds));
        scope.put("knowledgeIds", java.util.List.copyOf(docIds));
        // 本轮完整读范围（不 @ 时 = 当前人格可用的全部库）
        scope.put("effectiveKbIds", java.util.List.copyOf(scopeKbIds));
        // 本轮可写范围（空 = 一律不许写）
        scope.put("writableKbIds", java.util.List.copyOf(writable));
        return java.util.Map.of("kbScope", scope);
    }

    /** 是否「维基修订」人格（源站 kb_selection_mode=selected 的那个）。 */
    private static boolean isWikiFixer(String agentId) {
        return com.nexusai.kb.agent.KbBuiltInAgentDefs.WIKI_FIXER.equals(agentId);
    }

    /**
     * 本轮<b>读范围</b>（唯一真源）· 对齐源站 {@code resolveKnowledgeBases} + {@code KBSelectionMode=all}。
     *
     * <p>规则（源站 {@code session_qa_helpers.go:36}）：
     * <ol>
     *   <li>用户 @ 了库/文件 ⇒ 用 @ 的（文件解析成父库）</li>
     *   <li>没 @ ⇒ 用<b>当前人格可用的全部库</b>（源站 {@code KBSelectionMode="all"} 展开的等价物）。
     *       本仓没有「租户全部库」的概念，等价物就是 {@code knowledgeBaseService.list()}</li>
     *   <li>两种情况都按人格能力过滤（源站 {@code DeriveKBFilterForAgent}）：
     *       wiki 人格只给带 wiki 的库；智能推理给 chunks/graph；数据分析/快速问答只给 chunks</li>
     * </ol>
     *
     * <p>⛔ 为什么不再返回「空」：用户报的现象（2026-11-14）就是空范围 → 模型回
     * 「本轮没有绑定知识库，请补充」。源站模型从来看不到空范围，因为 all 模式总会展开成具体库。
     *
     * @param req     网关请求
     * @param agentId 会话绑定的 agent（决定能力过滤规则；null = 不过滤）
     * @return 读范围 id 列表（可能为空：库全被能力过滤掉 / 一个库都没有）
     */
    private java.util.List<String> resolveTurnKbScope(GatewayRequest req, String agentId) {
        // ① 用户显式范围（库 + 文件所属库）
        java.util.LinkedHashSet<String> explicit = new java.util.LinkedHashSet<>(explicitKbIds(req));

        // ② 全库索引（一次查询；显式分支也要用它做能力判定）
        java.util.Map<String, com.nexusai.kb.dto.KnowledgeBaseDto> byId = new java.util.LinkedHashMap<>();
        try {
            for (var kb : knowledgeBaseService.list()) {
                if (kb != null && kb.id() != null) {
                    byId.put(kb.id(), kb);
                }
            }
        } catch (RuntimeException e) {
            // 列库失败不阻断对话：退回「不过滤」。⛔ 不要因此判成「没有库」——那是假红。
            log.warn("[KB对话入口] 列知识库失败，本轮跳过能力过滤: {}", e.toString());
            return explicit.isEmpty() ? java.util.List.of() : new java.util.ArrayList<>(explicit);
        }

        // ③ ⭐ 用户显式 @ 的库直接采用，**不做能力过滤** —— 对齐源站
        //    `session_qa_helpers.go` 的 `hasExplicitMention` 分支（它直接用请求里的 id）。
        //    @ 面板本身已按能力筛过（源站 frontend/src/utils/tool-capabilities.ts 同源）。
        if (!explicit.isEmpty()) {
            return new java.util.ArrayList<>(explicit);
        }

        // ④ 不 @ ⇒ 「全部库」按人格过滤（源站 resolveKnowledgeBasesFromAgent 的 all 分支）
        java.util.List<String> out = new java.util.ArrayList<>(byId.size());
        for (String id : byId.keySet()) {
            if (capabilityAllowed(byId.get(id), agentId)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * 用户<b>真正 @ 的</b>知识库（库 + @ 文件所属库）· 写范围的唯一来源。
     *
     * <p>⛔ 与 {@link #resolveTurnKbScope} 的区别：那个是「本轮可读的全部库」（不 @ 时展开成全库），
     * 这个是「用户亲手选的」。写操作只认后者 —— 否则不 @ 就等于拿到全库写权限。
     */
    private java.util.List<String> explicitKbIds(GatewayRequest req) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (req.knowledgeBaseIds() != null) {
            for (String id : req.knowledgeBaseIds()) {
                if (id != null && !id.isBlank()) {
                    out.add(id);
                }
            }
        }
        if (req.knowledgeIds() != null) {
            for (String kid : req.knowledgeIds()) {
                if (kid == null || kid.isBlank()) {
                    continue;
                }
                try {
                    var dto = knowledgeService.getById(kid);
                    if (dto != null && dto.knowledgeBaseId() != null && !dto.knowledgeBaseId().isBlank()) {
                        out.add(dto.knowledgeBaseId());
                    }
                } catch (RuntimeException e) {
                    log.debug("[KB对话入口] 文档 {} 解析父库失败: {}", kid, e.toString());
                }
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * 该库是否满足人格的能力要求 · 对齐源站 {@code KBSatisfiesAgentRequirements}（AnyOf 语义）。
     *
     * <p>⚠️ 查不到 dto / 未绑定 agent 时**放行**：失败方向要「多看一眼」而不是「静默少一个库」。
     */
    private boolean capabilityAllowed(com.nexusai.kb.dto.KnowledgeBaseDto dto, String agentId) {
        if (agentId == null || agentId.isBlank() || dto == null) {
            return true;
        }
        String caps = capabilitiesOf(dto);
        return switch (agentId) {
            case com.nexusai.kb.agent.KbBuiltInAgentDefs.WIKI_RESEARCHER,
                 com.nexusai.kb.agent.KbBuiltInAgentDefs.WIKI_FIXER -> caps.contains("wiki");
            case com.nexusai.kb.agent.KbBuiltInAgentDefs.SMART_REASONING ->
                caps.contains("chunks") || caps.contains("graph");
            case com.nexusai.kb.agent.KbBuiltInAgentDefs.DATA_ANALYST,
                 com.nexusai.kb.agent.KbBuiltInAgentDefs.QUICK_ANSWER -> caps.contains("chunks");
            default -> true;
        };
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
    private String buildRuntimeContextBlock(GatewayRequest req, List<String> scopeKbIds) {
        List<String> kbIds = scopeKbIds == null ? List.of() : scopeKbIds;
        List<String> docIds = req.knowledgeIds() == null ? List.of() : req.knowledgeIds();
        StringBuilder sb = new StringBuilder(512);
        sb.append("<runtime_context scope=\"this_turn\">\n");
        if (kbIds.isEmpty() && docIds.isEmpty()) {
            // 对齐源站 observe.go:630-664：两个集合都空 ⇒ 标签不输出，也**不写任何 note**。
            // ⛔ 以前这里写「本轮用户未指定知识库/文件范围」，模型据此反问「要查哪个库」——
            //    那句源站没有，且与 all 语义冲突（详见 resolveTurnKbScope 的说明）。
            sb.append("</runtime_context>");
            return sb.toString();
        }
        if (!kbIds.isEmpty()) {
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
        }
        if (!docIds.isEmpty()) {
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
        }
        sb.append("  <note>工具参数里的知识库/文档请使用上面的 kb:// / doc:// 句柄；")
          .append("也可直接使用完整 id。</note>\n");
        sb.append("</runtime_context>");
        return sb.toString();
    }

    /**
     * 库的检索能力（`chunks` / `wiki` / `graph`）—— 让模型据此选工具，而不是盲搜。
     *
     * <p>来源：`indexing_strategy` JSON 的 `vector_enabled` / `keyword_enabled` /
     * `wiki_enabled` / `graph_enabled`。解析失败返回空串（模型看不到能力，退回通用策略，
     * 不影响正确性）。
     *
     * <p>⭐ 对齐源站 5 个能力（vector / keyword / wiki / graph / faq）：这里把 vector+keyword
     * 合并成 `chunks`。源站的能力过滤是 AnyOf 语义（满足任一即可）⇒ 合并是**等价**的
     * （{@code capabilities.go:103-117}）。
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
            java.util.List<String> caps = new java.util.ArrayList<>(3);
            if (node.path("vector_enabled").asBoolean(false) || node.path("keyword_enabled").asBoolean(false)) {
                caps.add("chunks");
            }
            if (node.path("wiki_enabled").asBoolean(false)) {
                caps.add("wiki");
            }
            if (node.path("graph_enabled").asBoolean(false)) {
                caps.add("graph");
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
        /**
         * 本轮引用的**会话临时附件** id（由 {@code POST /api/v1/kb/chat/{sid}/attachments} 上传得到）。
         *
         * <p>与 {@link #attachments} 不是一回事：那是「@ 选的库/文件」快照（只在气泡上显示、
         * 给检索范围用）；本字段是**用户上传的文件**，正文会被内联进 user content
         * （对齐源站 TemporaryDocument 的 12000 / 6000 token 预算）。
         */
        List<String> attachmentIds,
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
