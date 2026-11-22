package com.nexusai.apis.im;

import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.inbound.ImInboundManager;
import com.nexusai.domain.im_channel.ImChatSyncService;
import com.nexusai.domain.im_channel.ImWorkspaceService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.project.dto.ProjectDto;
import com.nexusai.model.session.dto.SessionCreateRequest;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_channel.ImChatActivation;
import com.nexusai.model.im_channel.ImChatEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IM 聊天目录端点（V305）· 对应界面「IM 频道 › 任务」tab。
 *
 * <p><b>与 {@code /api/v1/channels} 的区别</b>：后者是 MCP 通道的渠道级绑定
 * （channelId = wechat），本组是产品层的<b>聊天</b>级接线（张三 / 产品需求群）。
 * 界面「任务」tab 用本组数据，因为用户看的是"谁在跟我说话"而不是"哪个渠道"。
 */
@RestController
@RequestMapping("/api/v1/im/chats")
public class ImChatController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ImChatController.class);

    @Autowired private ImChatService chatService;
    @Autowired private ImInboundManager inboundManager;
    @Autowired private ImChatSyncService chatSyncService;
    @Autowired private ImWorkspaceService imWorkspaceService;
    @Autowired private SessionService sessionService;
    @Autowired private com.nexusai.domain.im_channel.ImChannelService channelService;
    @Autowired private com.nexusai.domain.im_channel.ImInstanceIdentityService identityService;
    /** [V329] 渠道接线 ⇄ 会话 Brief/无人值守联动（配置渠道 ⇒ Brief+IM 工具开；移除最后一个 ⇒ 两者关）。 */
    @Autowired private com.nexusai.domain.session.SessionModeService sessionModeService;

    /** 全部聊天（按最后消息时间倒序）。 */
    @GetMapping
    public List<ImChatEntry> list() {
        return chatService.list();
    }

    /** 某任务接的聊天（左栏「IM 会话」块可复用）。 */
    @GetMapping("/by-session/{sessionId}")
    public List<ImChatEntry> listBySession(@PathVariable String sessionId) {
        return chatService.listBySession(sessionId);
    }

    /**
     * [A 预热提示] 全部聊天的「能否主动发」状态（只读、非敏感）。
     *
     * <p>微信必须有对方来信带来的 {@code context_token} 才能主动发；没有的话
     * 权限通知会 {@code SKIPPED}（{@code IM_REPLY_TOKEN_MISSING}），用户却以为「绑好了就能收」。
     * 前端在绑定渠道 / 开启无人值守 / 打开渠道选择器时用它标出「未激活」。
     *
     * <p>⛔ 不返回令牌本身，只返回布尔与一句提示。
     */
    @GetMapping("/activation")
    public List<ImChatActivation> activation() {
        return chatService.listActivations();
    }

    /**
     * 改接：把聊天接到某个任务。
     *
     * <p>请求体：{@code {sessionId, mode?}}（mode: manual 默认 / auto）。
     * 一个聊天只接一个任务 ⇒ 覆盖式，调用方负责先给用户看"从 A 改到 B"。
     */
    @PutMapping("/{id}/bind")
    public ImChatEntry bind(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String sessionId = body == null ? null : str(body, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new ValidationException("sessionId 不能为空");
        }
        String mode = body == null ? null : str(body, "mode");
        // [V330] 改接：旧会话可能因此失去最后一个渠道（R14 需对它收敛，不能只看新会话）。
        String previousSessionId = chatService.findById(id)
            .map(ImChatEntry::boundSessionId)
            .orElse(null);
        ImChatEntry bound = chatService.bind(id, sessionId, mode);
        // [V329] 配置渠道（会话级）= 聊天接到本会话 ⇒ Brief on + IM 相关工具 on；Ask 不动（R13/R19）。
        sessionModeService.onChannelBound(sessionId);
        if (previousSessionId != null && !previousSessionId.isBlank()
                && !previousSessionId.equals(sessionId)) {
            sessionModeService.onChannelClosed(previousSessionId);
        }
        // 接线变了 ⇒ 该渠道的监听跟着变（有接线就开，没接线就关）
        reconcileInbound(bound.instanceId());
        return bound;
    }

    /**
     * [快速绑定] 把该渠道的「机器人默认会话」接到某个任务。
     *
     * <p>默认会话的取法（不猜平台内部 id）：
     * <ul>
     *   <li>私聊优先，且飞书同一私聊的两行里优先 {@code oc_}（入站/可读历史那行）；</li>
     *   <li>没有私聊 ⇒ 退到该渠道最近活跃的任一会话（群）；</li>
     *   <li>企微一个会话都没有、但 {@code identity whoami} 拿得到授权人 ⇒ 落一条授权人私聊行再绑
     *       （官方允许把授权人 ID 当 aibot send 的 chat_id）。</li>
     * </ul>
     * ⛔ 找不到任何会话时不伪造：返回可执行的提示（先私聊机器人一句 / 群里 @ 它一次）。
     */
    @PostMapping("/bind-default")
    public Map<String, Object> bindDefault(@RequestBody Map<String, Object> body) {
        String instanceId = str(body, "instanceId");
        String sessionId = str(body, "sessionId");
        if (instanceId == null || instanceId.isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new ValidationException("instanceId / sessionId 不能为空");
        }
        ImChatEntry target = chooseDefaultChat(chatService.listByInstance(instanceId));
        if (target == null) {
            String platform = channelService.findById(instanceId)
                    .map(com.nexusai.model.im_channel.ImInstanceEntry::platform).orElse(null);
            if ("wecom".equals(platform)) {
                var idn = identityService.resolve(instanceId);
                if (idn.authorizerId() != null && !idn.authorizerId().isBlank()) {
                    target = chatService.upsert(instanceId, "wecom", idn.authorizerId(), "机器人授权人",
                            "p2p", null, null, null, ImChatService.SOURCE_INBOUND);
                }
            }
        }
        if (target == null) {
            throw new ValidationException(
                    "这个渠道还没有可绑定的会话：先私聊机器人发一句话（群里则 @ 它一次），再点快速绑定");
        }
        String previousSessionId = target.boundSessionId();
        ImChatEntry bound = chatService.bind(target.id(), sessionId, "manual");
        sessionModeService.onChannelBound(sessionId);
        if (previousSessionId != null && !previousSessionId.isBlank()
                && !previousSessionId.equals(sessionId)) {
            sessionModeService.onChannelClosed(previousSessionId);
        }
        reconcileInbound(bound.instanceId());
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("sessionId", sessionId);
        ok.put("chatId", bound.id());
        ok.put("chatName", bound.displayName());
        ok.put("bound", bound);
        return ok;
    }

    /**
     * [快速绑定 · 预览] 返回将要绑定的默认会话及其当前归属（不写库）。
     *
     * <p>前端据此在「该会话已绑在别的任务」时先问一句，避免静默抢走别人的接线。
     */
    @PostMapping("/bind-default/preview")
    public Map<String, Object> previewBindDefault(@RequestBody Map<String, Object> body) {
        String instanceId = str(body, "instanceId");
        if (instanceId == null || instanceId.isBlank()) {
            throw new ValidationException("instanceId 不能为空");
        }
        ImChatEntry target = chooseDefaultChat(chatService.listByInstance(instanceId));
        Map<String, Object> out = new LinkedHashMap<>();
        if (target == null) {
            out.put("found", false);
            out.put("message", "这个渠道还没有可绑定的会话：先私聊机器人发一句话（群里则 @ 它一次）");
            return out;
        }
        out.put("found", true);
        out.put("chatId", target.id());
        out.put("chatName", target.displayName());
        if (target.boundSessionId() != null && !target.boundSessionId().isBlank()) {
            out.put("boundSessionId", target.boundSessionId());
            String title = sessionTitleOf(target.boundSessionId());
            if (title != null && !title.isBlank()) {
                out.put("boundSessionTitle", title);
            }
        }
        return out;
    }

    /** 会话标题（给人看的「已被「xxx」占用」）；查不到 ⇒ null，不抛。 */
    private String sessionTitleOf(String sessionId) {
        try {
            return sessionService.getById(sessionId).title();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 默认会话：私聊优先（飞书 oc_ 行优先），否则最近活跃的任一会话。 */
    private static ImChatEntry chooseDefaultChat(List<ImChatEntry> chats) {
        if (chats == null || chats.isEmpty()) {
            return null;
        }
        java.util.Comparator<ImChatEntry> byRecent = java.util.Comparator.comparing(
                (ImChatEntry c) -> c.lastMessageAt() == null ? "" : c.lastMessageAt(),
                java.util.Comparator.reverseOrder());
        List<ImChatEntry> p2p = chats.stream()
                .filter(c -> "p2p".equals(c.chatKind()))
                .sorted(java.util.Comparator
                        .comparing((ImChatEntry c) -> c.peerId() != null && c.peerId().startsWith("oc_") ? 0 : 1)
                        .thenComparing(byRecent))
                .toList();
        if (!p2p.isEmpty()) {
            return p2p.get(0);
        }
        return chats.stream().sorted(byRecent).findFirst().orElse(null);
    }

    /** 断开接线（幂等）。 */
    @DeleteMapping("/{id}/bind")
    public ResponseEntity<Map<String, Object>> unbind(@PathVariable String id) {
        // [V329] 解绑前记住归属会话：解绑后 entry 不再带 boundSessionId，
        //   而 R14 需要判断「这是不是本会话最后一个渠道」。
        String previousSessionId = chatService.findById(id)
            .map(ImChatEntry::boundSessionId)
            .orElse(null);
        ImChatEntry after = chatService.unbind(id);
        // [V329] R14：移除本会话最后一个渠道 ⇒ 无人值守退出 ∧ Brief 同步关闭（只作用于该会话）。
        if (previousSessionId != null && !previousSessionId.isBlank()
                && chatService.listBySession(previousSessionId).isEmpty()) {
            sessionModeService.onLastChannelClosed(previousSessionId);
        }
        // 解绑后若该渠道已无任何接线 ⇒ 断开连接（别白收）
        reconcileInbound(after.instanceId());
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("unbound", !after.bound());
        return ResponseEntity.ok(ok);
    }

    /**
     * 新建一个任务并把它接到该聊天上（用户 2026-11-17 补充要求：不只绑已有，也能新建）。
     *
     * <p><b>为什么是后端组合端点而不是前端调两次</b>：新建任务要 {@code mainProjectId}，
     * 而 IM 工作区项目的 id 前端并不知道（它由 {@link ImWorkspaceService} 按 path 解析）。
     * 前端先查项目再建会话 = 多一次往返 + 可能走到用户自己的仓库里。
     *
     * <p>落到 {@code <安装目录>/data/workspace/<user>/im}（与扫描产物同一个工作区）。
     *
     * @return 新建的任务 id + 绑定后的聊天
     */
    @PostMapping("/{id}/bind-new")
    public Map<String, Object> bindNew(@PathVariable String id) {
        ImChatEntry existing = chatService.findById(id)
            .orElseThrow(() -> new ValidationException("聊天不存在: " + id));
        ProjectDto project = imWorkspaceService.resolveProject();
        String title = "IM · " + (existing.peerName() == null ? existing.peerId() : existing.peerName());
        SessionDto session = sessionService.create(
            new SessionCreateRequest(title, null, null, project.id(), null));
        ImChatEntry bound = chatService.bind(id, session.id(), "manual");
        // [V329] 新建任务并接线 = 配置渠道 ⇒ Brief on + IM 相关工具 on。
        sessionModeService.onChannelBound(session.id());
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("sessionId", session.id());
        ok.put("sessionTitle", title);
        ok.put("projectPath", project.path());
        ok.put("chat", bound);
        return ok;
    }

    /**
     * 适配器上报（内部用）：收到一条消息时 upsert 聊天目录。
     *
     * <p>也可以由「测试连接」按钮调用以生成演示行；⛔ 不要把它当公开 API 暴露给前端表单。
     */
    @PostMapping("/upsert")
    public ImChatEntry upsert(@RequestBody Map<String, Object> body) {
        String instanceId = str(body, "instanceId");
        String platform = str(body, "platform");
        String peerId = str(body, "peerId");
        String peerName = str(body, "peerName");
        String chatKind = str(body, "chatKind");
        String preview = str(body, "lastMessagePreview");
        String lastAt = str(body, "lastMessageAt");
        Integer memberCount = body != null && body.get("memberCount") instanceof Number n
            ? n.intValue() : null;
        return chatService.upsertOnInbound(instanceId, platform, peerId, peerName,
            chatKind, memberCount, preview, lastAt);
    }

    /**
     * 把本机微信的扫描会话同步进聊天目录（NXS-UC-CHN-020 的前置，按需触发）。
     *
     * <p>为什么是显式端点、而不是启动时自动跑：同步会写库（几百行 upsert），
     * 启动时跑会拖慢启动且「为什么多了这么多聊天」无法解释。界面在「任务」tab
     * 提供「同步微信会话」按钮，点了才跑。
     *
     * <p>⚠️ 同步成功 ≠ 能发消息：微信 iLink 仍需要 {@code context_token}
     * （只在对方先说话后才有）。见 {@code ImChatSyncService} 类注释。
     */
    @PostMapping("/sync/wechat")
    public ImChatSyncService.SyncResult syncWechatChats(@RequestBody(required = false) Map<String, Object> body) {
        String instanceId = body == null ? null : str(body, "instanceId");
        return chatSyncService.syncWechatChats(instanceId);
    }

    /**
     * 拉飞书「机器人所在的群」进聊天目录。
     *
     * <p>飞书没有本机扫描，机器人能看到哪些群只能问平台；不拉这一步就没有可投递的目标。
     */
    @PostMapping("/sync/feishu")
    public ImChatSyncService.SyncResult syncFeishuChats(@RequestBody(required = false) Map<String, Object> body) {
        String instanceId = body == null ? null : str(body, "instanceId");
        return chatSyncService.syncFeishuChats(instanceId);
    }

    /**
     * 拉「本人所在」的飞书群进聊天目录（用户身份，不是机器人身份）。
     *
     * <p>与 {@code /sync/feishu} 的区别：那个用 tenant token 列「机器人所在的群」；
     * 本端点用渠道卡片里「授权本人身份」拿到的 user token，列这个用户加入的全部群
     * —— 机器人不在群里的那种群，只有这条路能看到。
     *
     * <p>失败（未授权 / 令牌过期且续期失败 / scope 不足 / 平台报错）走业务异常，
     * ⛔ 不返回 {@code total=0} 假装成功。
     */
    @PostMapping("/sync/feishu-user")
    public ImChatSyncService.SyncResult syncFeishuUserChats(@RequestBody(required = false) Map<String, Object> body) {
        String instanceId = body == null ? null : str(body, "instanceId");
        return chatSyncService.syncFeishuUserChats(instanceId);
    }

    /**
     * 拉企微「最近 7 天有消息的群」进聊天目录。
     *
     * <p>⚠️ 平台只返回窗口内**有消息**的会话，很久没说话的群拉不到（端点限制）。
     */
    @PostMapping("/sync/wecom")
    public ImChatSyncService.SyncResult syncWecomChats(@RequestBody(required = false) Map<String, Object> body) {
        String instanceId = body == null ? null : str(body, "instanceId");
        return chatSyncService.syncWecomChats(instanceId);
    }

    /** 同步预览：不同步，只列出会被写进目录的会话（界面「同步前看一眼」）。 */
    @GetMapping("/sync/wechat/preview")
    public List<Map<String, Object>> previewWechatChats(
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit) {
        return chatSyncService.previewWechatChats(limit == null ? 0 : limit);
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 接线变化后收敛监听状态（有接线就开、没接线就关）。
     *
     * <p>{@code try/catch} 包住：**接线本身已经成功了**，监听启停失败不能让用户看到 500
     * —— 那是后台副作用，记日志即可（下次 bind/unbind 或重启会自动再收敛一次）。
     */
    private void reconcileInbound(String instanceId) {
        try {
            inboundManager.reconcile(instanceId);
        } catch (RuntimeException e) {
            log.warn("[ImChatController] 收敛入站监听失败（不影响接线结果）instance={} err={}",
                    instanceId, e.toString());
        }
    }
}
