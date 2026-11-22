package com.nexusai.apis.im;

import com.nexusai.domain.im_channel.ImSendService;
import com.nexusai.domain.im_channel.send.ImSendAttachment;
import com.nexusai.domain.im_channel.send.ImSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主动投递的手动入口（NXS-UC-CHN-020 · API {@code NXS-API-CHN-018}）。
 *
 * <h2>为什么 agent 用工具、还要这个 REST</h2>
 * agent 走 {@code SendUserMessage}（工具），但那条链路必须跑完一整轮对话才能验证。
 * 这个端点让「查聊天 → 发消息 → 看逐目标结果」可以单独 curl 验证，
 * 也供前端做「测试发送」。两者最终都调同一个 {@link ImSendService}。
 *
 * <h2>状态码约定</h2>
 * <ul>
 *   <li>{@code 200} —— 全部目标都是 SENT 或 SKIPPED（跳过=能力边界，不是错误）；</li>
 *   <li>{@code 207} —— 至少一个目标 FAILED（部分失败，但其余照发）；</li>
 *   <li>{@code 400} —— 入参不合法（chatIds 空 / text 空 / 超上限）。</li>
 * </ul>
 * ⛔ 不要因为「某平台不支持」就返回 4xx —— 那会让调用方以为整体失败而重试。
 */
@RestController
@RequestMapping("/api/v1/im")
public class ImSendController {

    private static final Logger log = LoggerFactory.getLogger(ImSendController.class);

    @Autowired private ImSendService sendService;
    /** 审计（与 agent 工具走同一个 service；⛔ REST 路径不能漏 —— 否则「发出去但查不到记录」）。 */
    @Autowired(required = false) private com.nexusai.domain.im_channel.ImSendAuditService auditService;
    @Autowired(required = false) private com.nexusai.domain.im_channel.ImChatService chatService;

    /**
     * 请求体：chatIds 必填；text 与 attachments 至少一个。
     *
     * <p>{@code attachments} 是**本地绝对路径**列表（相对路径按 JVM 的 user.dir 解析）。
     * 提供它主要是为了 e2e / 手动验证「发文件」—— agent 侧走 {@code SendUserMessage.attachments}。
     */
    public record SendRequest(List<String> chatIds, String text, List<String> attachments,
                              String idempotencyKey,
                              /**
                               * 以谁的名义发：{@code BOT}（默认）| {@code USER}。
                               *
                               * <p>为什么 REST 也要这个：前端「测试发送」和排查脚本都需要能
                               * 分别验两条通道；只有 agent 工具能选身份的话，真机排障还得跑一整轮对话。
                               * <p>⚠️ {@code USER} 只在实例做过 OAuth 授权后有效，
                               * 否则由策略层降级为 BOT 并在结果的 {@code identityNote} 里说明。
                               */
                              String identity) {}

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody(required = false) SendRequest req) {
        if (req == null) {
            return badRequest("请求体为空");
        }
        if (req.chatIds() == null || req.chatIds().isEmpty()) {
            return badRequest("chatIds 不能为空");
        }
        boolean hasText = req.text() != null && !req.text().isBlank();
        boolean hasAttach = req.attachments() != null && !req.attachments().isEmpty();
        if (!hasText && !hasAttach) {
            return badRequest("text 与 attachments 至少提供一个");
        }
        if (req.chatIds().size() > ImSendService.MAX_TARGETS) {
            return badRequest("一次最多投递 " + ImSendService.MAX_TARGETS + " 个目标");
        }

        List<ImSendAttachment> attachments = toAttachments(req.attachments());
        // 缺省/未知值一律 BOT —— 默认必须是机器人身份（用户身份要显式写出来）
        com.nexusai.domain.im_channel.send.SendIdentity who =
                "USER".equalsIgnoreCase(req.identity())
                        ? com.nexusai.domain.im_channel.send.SendIdentity.USER
                        : com.nexusai.domain.im_channel.send.SendIdentity.BOT;
        List<ImSendResult> results = sendService.send(req.chatIds(), req.text(), attachments, who);

        // 审计：逐目标一条（与 SendImAsUserTool 同一套 service）
        // `results` 与 `chatIds` 同序（ImSendService 按输入顺序逐个目标返回）
        if (auditService != null && chatService != null) {
            for (int i = 0; i < results.size() && i < req.chatIds().size(); i++) {
                String chatId = req.chatIds().get(i);
                // lambda 只能捕获 effectively-final ⇒ 循环变量另存一份
                final ImSendResult one = results.get(i);
                try {
                    chatService.findById(chatId).ifPresent(chat ->
                            auditService.record(chat, who, one.identity(),
                                    req.text(), attachments.size(), one));
                } catch (RuntimeException e) {
                    // 审计失败不能改变「消息已经发出去了」这个事实
                    log.warn("[ImSendController] 写审计失败 chatId={} err={}", chatId, e.toString());
                }
            }
        }

        List<Map<String, Object>> payload = new ArrayList<>(results.size());
        boolean anyFailed = false;
        for (ImSendResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", r.status().name());
            m.put("ok", r.ok());
            // 🔴 实际身份必须回传：请求 USER 但实例没票时会降级成 BOT，
            //    不回的话调用方只能去查审计表或问对端「你看到的是谁发的」。
            //    与 identityNote 配对出现 —— 降级原因要能让调用方看到。
            if (r.identity() != null) m.put("identity", r.identity().name());
            if (r.identityNote() != null) m.put("identityNote", r.identityNote());
            if (r.messageId() != null) m.put("messageId", r.messageId());
            if (r.errorCode() != null) m.put("errorCode", r.errorCode());
            if (r.reason() != null) m.put("reason", r.reason());
            payload.add(m);
            if (!r.acceptable()) {
                anyFailed = true;
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[ImSendController] 手动投递完成 targets={} failed={} idempotencyKey={}",
                    results.size(), anyFailed, req.idempotencyKey());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestedIdentity", who.name());
        body.put("results", payload);
        // 207 = 部分失败；全成功或全跳过都算 200（跳过是能力边界，不是错误）
        HttpStatus status = anyFailed ? HttpStatus.MULTI_STATUS : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }

    /** 附件级失败也要在结果里带出来（正文发出去了但文件没发成功，用户要知道）。 */
    private static List<ImSendAttachment> toAttachments(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ImSendAttachment> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                out.add(ImSendAttachment.of(java.nio.file.Path.of(s.trim())));
            } catch (RuntimeException e) {
                log.warn("[ImSendController] 附件路径非法，跳过: {} err={}", s, e.toString());
            }
        }
        return out;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "IM_PARAM_INVALID");
        body.put("message", message);
        return ResponseEntity.badRequest().body(body);
    }
}
