package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.ImConfigService;
import com.nexusai.domain.im_channel.ImSendAuditService;
import com.nexusai.domain.im_channel.ImSendService;
import com.nexusai.domain.im_channel.send.ImSendAttachment;
import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.send.SendIdentity;
import com.nexusai.model.im_channel.ImChatEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code SendImAsUser} 工具 · <b>以用户本人的名义</b>在 IM 里发消息。
 *
 * <h2>与 {@code SendUserMessage} 的区别（必须让模型一眼分清）</h2>
 * <table border="1">
 *   <caption>两个工具的语义差异</caption>
 *   <tr><th></th><th>SendUserMessage</th><th>SendImAsUser（本工具）</th></tr>
 *   <tr><td>谁在说话</td><td><b>机器人</b></td><td><b>用户本人</b></td></tr>
 *   <tr><td>说给谁</td><td>通知用户自己</td><td>可以是<b>别人</b>（同事 / 客户 / 群）</td></tr>
 *   <tr><td>对方看到</td><td>「机器人发的」</td><td>「<b>你本人</b>发的」</td></tr>
 * </table>
 *
 * <h2>为什么必须做成独立工具而不是给 SendUserMessage 加个参数</h2>
 * 两者的<b>社会后果完全不同</b>：一个是信息通知，另一个是「替本人对外发言」。
 * 若塞进同一个工具，模型一次参数错就会把「通知你」变成「以你名义跟别人说话」，
 * 而工具描述里的区分度远不如两个独立工具。
 *
 * <h2>可见性</h2>
 * {@link #isEnabled()} 读 {@code im_config.allow_user_identity}（默认关闭）：
 * <ul>
 *   <li>关闭 ⇒ 本工具<b>不进 LLM 的 schema</b>（{@code ToolRegistry.toOpenAiToolsArray}
 *       在 {@code ToolRegistry.java:651-653} 按 {@code isEnabled()} 跳过），模型看不到也调不到；</li>
 *   <li>开启 ⇒ 工具出现在 schema 里，模型可以调用。</li>
 * </ul>
 * ⛔ 读不到配置时按<b>关闭</b>（fail-closed）：宁可 AI 不能冒充，也不要在配置库异常时
 * 让它突然获得「替用户对外发言」的能力。
 *
 * <h2>降级必须告知（不能静默）</h2>
 * 请求的是「以用户名义」，但平台可能不支持（飞书 / 微信 / 钉钉只有机器人身份端点）
 * ⇒ 实际以机器人身份发出。此时本工具**仍然返回成功**（消息确实发出去了），
 * 但输出里必须写明 {@code identity=BOT} + 降级说明 ⇒ 模型能告诉用户
 * 「这条是以机器人身份发的，不是你本人」。
 */
@Component
public class SendImAsUserTool implements Tool {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(SendImAsUserTool.class);

    public static final String NAME = "SendImAsUser";

    public static final String DESCRIPTION =
        "以**用户本人（你自己）**的名义在 IM 里发一条消息给对方（同事 / 客户 / 群）。"
            + "对方在 IM 里看到的是**你本人**说的，不是机器人说的。"
            + "与 SendUserMessage 的区别：那个是**机器人通知你**，这个是**替你对别人说话**。"
            + "先调用 ListImChats 拿到目标聊天的 id（chatId）再调用本工具。";

    public static final String PROMPT =
        "Send a message **as the user themselves** over IM (WeCom / Feishu / WeChat).\n\n"
            + "This is NOT the same as `SendUserMessage`: that one notifies the **user** with the "
            + "bot's voice; this one speaks **as the user** to somebody else. The recipient sees "
            + "the user's own name and avatar.\n\n"
            + "`chatId` — the target chat id from `ListImChats` (looks like `imch-xxxx`). Never "
            + "guess it.\n"
            + "`text` — the message body (markdown).\n"
            + "`attachments` — optional local file paths.\n\n"
            + "Use this only when the user explicitly asks you to contact someone on their behalf. "
            + "If the platform cannot send with the user's identity, the message still goes out "
            + "as the **bot** — the result tells you `identity` and `identityNote`; relay that "
            + "to the user instead of implying you spoke as them.";

    @Autowired(required = false)
    private ImSendService sendService;

    @Autowired(required = false)
    private ImChatService chatService;

    @Autowired(required = false)
    private ImConfigService configService;

    @Autowired(required = false)
    private ImSendAuditService auditService;

    /** [测试注入] IM 投递服务。 */
    public void setSendService(ImSendService sendService) {
        this.sendService = sendService;
    }

    /** [测试注入] 聊天目录。 */
    public void setChatService(ImChatService chatService) {
        this.chatService = chatService;
    }

    /** [测试注入] 配置服务（可见性开关）。 */
    public void setConfigService(ImConfigService configService) {
        this.configService = configService;
    }

    /** [测试注入] 审计服务。 */
    public void setAuditService(ImSendAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String prompt() {
        return PROMPT;
    }

    /**
     * 可见性闸门：全局开关关闭 ⇒ 本工具不进 schema。
     *
     * <p>对齐 CC {@code Tool.ts:403 isEnabled()}（{@code ToolRegistry.java:651-653}
     * 在生成 LLM 工具数组时按它跳过）。
     */
    @Override
    public boolean isEnabled() {
        if (configService == null) {
            // 未注入（单测 / 孤立运行）⇒ 保守关闭
            return false;
        }
        return configService.isAllowUserIdentity();
    }

    /** 发消息不可并发（同一目标并发发会乱序）。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /** 不是只读操作（对外发言）。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("chatId")
            .put("type", "string")
            .put("description", "Target chat id from ListImChats (e.g. `imch-1a2b3c4d`). "
                + "Never invent one — list the chats first.");

        props.putObject("text")
            .put("type", "string")
            .put("description", "The message to send, written as if the user themselves said it. "
                + "Markdown supported. Required unless attachments are given.");

        ObjectNode attachments = props.putObject("attachments");
        attachments.put("type", "array");
        ObjectNode items = JsonNodeFactory.instance.objectNode();
        items.put("type", "string");
        attachments.set("items", items);
        attachments.put("description", "Optional local file paths (absolute or cwd-relative) to send "
            + "alongside the message. Note: some platforms cannot send media under the user's "
            + "identity — in that case the text still goes as the user and the attachment goes as "
            + "the bot; the result says so.");

        schema.putArray("required").add("chatId");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String chatId = input.path("chatId").asText("").trim();
        String text = input.path("text").asText("");
        List<String> rawPaths = readPaths(input.path("attachments"));

        if (chatId.isEmpty()) {
            return ToolResult.error(call.id(), "Error: chatId 不能为空（先调用 ListImChats 拿到目标会话 id）");
        }
        if (text.isBlank() && rawPaths.isEmpty()) {
            return ToolResult.error(call.id(), "Error: 消息内容为空（text 与 attachments 至少给一个）");
        }
        if (sendService == null) {
            return ToolResult.error(call.id(), "Error: IM 投递服务未初始化，无法发送");
        }

        // 目标聊天（用于审计；查不到不阻断投递 —— 服务层会给出明确的失败码）
        ImChatEntry chat = null;
        if (chatService != null) {
            try {
                chat = chatService.findById(chatId).orElse(null);
            } catch (RuntimeException e) {
                log.warn("[SendImAsUser] 查聊天目录失败（不影响投递）: chatId={} err={}", chatId, e.toString());
            }
        }

        List<ImSendAttachment> attachments = toAttachments(rawPaths, ctx);
        List<ImSendResult> results;
        try {
            results = sendService.send(List.of(chatId), text, attachments, SendIdentity.USER);
        } catch (RuntimeException e) {
            log.error("[SendImAsUser] 投递异常 chatId={}", chatId, e);
            return ToolResult.error(call.id(),
                "Error: 投递过程出现内部错误: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }

        if (results == null || results.isEmpty()) {
            return ToolResult.error(call.id(), "Error: 投递没有返回结果");
        }
        ImSendResult r = results.get(0);

        // 审计（失败不影响投递结果）
        if (auditService != null) {
            try {
                auditService.record(chat, SendIdentity.USER, r.identity(), text, attachments.size(), r);
            } catch (RuntimeException e) {
                log.warn("[SendImAsUser] 写审计失败（不影响投递结果）: {}", e.toString());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", r.status().name());
        data.put("requestedIdentity", SendIdentity.USER.name());
        data.put("identity", r.identity() == null ? SendIdentity.BOT.name() : r.identity().name());
        if (r.messageId() != null) {
            data.put("messageId", r.messageId());
        }
        if (r.errorCode() != null) {
            data.put("errorCode", r.errorCode());
        }
        if (r.reason() != null) {
            data.put("reason", r.reason());
        }
        if (r.identityNote() != null) {
            data.put("identityNote", r.identityNote());
        }
        if (r.attachmentErrors() != null && !r.attachmentErrors().isEmpty()) {
            data.put("attachmentErrors", r.attachmentErrors());
        }

        if (r.status() == ImSendResult.Status.FAILED) {
            // 失败要如实报失败（否则模型会以为发出去了）
            return ToolResult.error(call.id(),
                "Error: 投递失败（" + (r.errorCode() == null ? "UNKNOWN" : r.errorCode()) + "）："
                    + (r.reason() == null ? "平台拒绝发送" : r.reason()));
        }

        if (log.isInfoEnabled()) {
            log.info("[SendImAsUser] 投递完成 chatId={} status={} identity={} attachments={}",
                chatId, r.status(), r.identity(), attachments.size());
        }
        // ⛔ SKIPPED（能力边界，如缺回复令牌）也算「没发出去」⇒ 明确告知模型
        if (r.status() == ImSendResult.Status.SKIPPED) {
            return ToolResult.error(call.id(),
                "Error: 未发送（" + (r.errorCode() == null ? "SKIPPED" : r.errorCode()) + "）："
                    + (r.reason() == null ? "该平台当前无法发送" : r.reason()));
        }
        // 🔴 必须序列化成 String：落库链路只对 String data 生成 content，
        //   Map 会落成空 content（2026-11-18 实测：ListImChats 同款问题，DB 里 length=0）。
        try {
            return ToolResult.success(call.id(), JSON.writeValueAsString(data));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[SendImAsUser] 序列化失败: {}", e.toString());
            return ToolResult.success(call.id(),
                "{\"status\":\"" + r.status().name() + "\"}");
        }
    }

    /**
     * 当前工作目录（与 {@code BriefTool.cwd} 同口径：优先 per-turn effectiveCwd，
     * 回落会话解析，最后回落进程目录）。
     */
    private static String cwd(ToolUseContext ctx) {
        if (ctx != null && ctx.effectiveCwd() != null) {
            return ctx.effectiveCwd().toString();
        }
        String c = com.nexusai.application.agent.agent.CwdResolution.getCwd(ctx != null ? ctx.sessionId() : null);
        return Path.of(c != null && !c.isBlank() ? c : System.getProperty("user.dir", ".")).toString();
    }

    /** 读 attachments 数组（string[]）。 */
    private static List<String> readPaths(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(node.size());
        node.forEach(n -> {
            String s = n.asText("").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        });
        return out;
    }

    /**
     * 附件路径 → 投递用附件对象（相对路径按 cwd 解析）。
     *
     * <p>与 {@code BriefTool.toImAttachments} 同口径：Service 只收绝对路径，保持与 cwd 无关。
     */
    private static List<ImSendAttachment> toAttachments(List<String> rawPaths, ToolUseContext ctx) {
        if (rawPaths == null || rawPaths.isEmpty()) {
            return List.of();
        }
        Path base;
        try {
            base = Path.of(cwd(ctx));
        } catch (RuntimeException e) {
            base = Path.of(System.getProperty("user.dir", "."));
        }
        List<ImSendAttachment> out = new ArrayList<>(rawPaths.size());
        for (String raw : rawPaths) {
            try {
                Path p = Path.of(raw);
                out.add(ImSendAttachment.of(p.isAbsolute() ? p : base.resolve(p).normalize()));
            } catch (RuntimeException e) {
                log.warn("[SendImAsUser] 附件路径无法解析，跳过: raw={} err={}", raw, e.toString());
            }
        }
        return out;
    }
}
