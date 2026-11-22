package com.nexusai.domain.im_channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.send.ImSendContext;
import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.send.ImSendStrategy;
import com.nexusai.domain.im_channel.send.ImSendStrategyFactory;
import com.nexusai.model.im_channel.ImChatEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 主动投递应用服务（NXS-UC-CHN-020）：把一条文本发到一组 IM 聊天。
 *
 * <h2>为什么是应用服务而不是策略</h2>
 * 策略只管协议（怎么发），这里管**编排**：目标解析、凭证解密、上下文组装、结果汇总、状态回写。
 * 把它放这里而不是塞进策略，是为了让三个平台策略都退化成纯协议实现（好测、好加平台）。
 *
 * <h2>「逐目标独立」是核心规则</h2>
 * 一个目标失败（聊天被删 / 平台拒绝 / 网络挂）**不能**影响其余目标。
 * 所以每个目标都包在 {@link #sendOne} 里，异常在那一层被吃掉，方法返回结果对象而非抛错。
 *
 * <h2>为什么不写 bound_session_id</h2>
 * 「投递到某个聊天」与「把聊天接到某个任务」是两件事（后者是 NXS-UC-CHN-011）。
 * 本服务只发消息 + 更新最近消息时间，<b>不动接线</b>。
 *
 * <h2>🔴 为什么发成功后还要往本地表补一行</h2>
 * 企微的 {@code chat/messages/list} 返回的是「<b>用户视角的消息流</b>」，
 * 我们用 {@code /cli/message/aibot/send} 发出去的机器人消息<b>不在这个流里</b>（实测结论）。
 * ⇒ 不自己留一份，对话就是<b>断的</b>：用户看到别人的问题，看不到我们自己的回答。
 * <p>⇒ 发送成功后由 {@link ImMessageOutboundRecorder} 往 V315 的 {@code im_message}
 * 补一行 {@code direction='out'} / {@code origin='local'}；
 * <b>记账失败绝不影响发送结果</b>（消息已经发出去了，不能回滚，也不能让用户看到「发送失败」）。
 */
@Service
public class ImSendService {

    private static final Logger log = LoggerFactory.getLogger(ImSendService.class);

    /** 一次请求最多投递多少个目标（防止误传上千个 id 打爆平台限流）。 */
    public static final int MAX_TARGETS = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ImChatService chatService;
    @Autowired private ImChannelService channelService;
    @Autowired private ImSendStrategyFactory strategyFactory;

    /**
     * 出站消息的本地补写器（V315 {@code im_message} · {@code direction='out'}）。
     *
     * <p>⚠️ <b>为什么它不能是「可有可无」</b>：企微的 {@code chat/messages/list} 返回的是
     * 「用户视角的消息流」，我们发出去的机器人消息<b>不在里面</b>
     * ⇒ 没有这一行，对话就是断的（用户看到别人的问题，看不到我们的回答）。
     *
     * <p>⚠️ <b>为什么还留了 null 判断</b>：本类被大量单测直接 {@code new} 出来用（无 Spring 上下文），
     * 那时这个字段必然是 null ⇒ 用 null 判断让「没装配」退化成「不补写」，
     * 而不是让整个投递抛 NPE 被当成发送失败。生产环境里它是<b>必装配</b>的
     * （{@code @Autowired} 默认 required=true ⇒ 缺 bean 直接启动失败，不会静默少记）。
     */
    @Autowired private ImMessageOutboundRecorder outboundRecorder;

    /**
     * 批量投递（纯文本）。
     *
     * @param chatIds 聊天目录 id 列表（可 null / 含 null / 重复 —— 见下方处理）
     * @param text    正文（null/空白 ⇒ 每个目标都返回 FAILED，而不是抛错）
     * @return 与**去重后**的 chatIds 一一对应，顺序保持入参顺序；永不返回 null
     */
    public List<ImSendResult> send(List<String> chatIds, String text) {
        return send(chatIds, text, null, com.nexusai.domain.im_channel.send.SendIdentity.BOT);
    }

    /**
     * 批量投递（文本 + 附件）。
     *
     * <p><b>附件在「每个目标」上分别上传</b>：飞书的 {@code file_key} 与企微的
     * {@code media_id} 互不通用，同一个文件发给两个平台要传两次（平台限制，见
     * {@link com.nexusai.domain.im_channel.send.ImMediaUploader} 类注释）。
     *
     * <p>单目标失败不影响其它目标；单附件失败不影响该目标的正文（见
     * {@link ImSendResult#attachmentErrors()}）。
     *
     * @param attachments 本地文件列表（可 null/空）；路径不合法时该目标返回 FAILED
     */
    public List<ImSendResult> send(List<String> chatIds, String text,
                                   List<com.nexusai.domain.im_channel.send.ImSendAttachment> attachments) {
        return send(chatIds, text, attachments, com.nexusai.domain.im_channel.send.SendIdentity.BOT);
    }

    /**
     * 批量投递（文本 + 附件 + <b>发送身份</b>）。
     *
     * <h2>identity 是干什么的</h2>
     * {@code SendIdentity.USER} = <b>以授权真人名义</b>发（对方看到的是"你本人"说的）；
     * {@code BOT} = 机器人名义。企微两个端点身份不同，其余平台只有 BOT。
     * 平台不支持 USER 时由策略层降级为 BOT，并把**实际身份**写进
     * {@link ImSendResult#identity()} —— ⛔ 不允许静默降级（AI 会以为自己冒充成功）。
     *
     * @param identity 发送身份；null ⇒ 按 BOT
     */
    public List<ImSendResult> send(List<String> chatIds, String text,
                                   List<com.nexusai.domain.im_channel.send.ImSendAttachment> attachments,
                                   com.nexusai.domain.im_channel.send.SendIdentity identity) {
        com.nexusai.domain.im_channel.send.SendIdentity who =
                identity == null ? com.nexusai.domain.im_channel.send.SendIdentity.BOT : identity;
        if (chatIds == null || chatIds.isEmpty()) {
            return List.of();
        }
        // 去重（保持顺序）：同一个 chat 重复出现在 notifyChats 里是配置错误，
        // 但重复发送会让用户收到两条一样的消息 ⇒ 这里静默去重比发两遍更合理。
        Set<String> unique = new LinkedHashSet<>();
        for (String id : chatIds) {
            if (id != null && !id.isBlank()) {
                unique.add(id.trim());
            }
        }
        if (unique.size() > MAX_TARGETS) {
            log.warn("[ImSend] 目标数 {} 超过上限 {}，只处理前 {} 个",
                    unique.size(), MAX_TARGETS, MAX_TARGETS);
        }

        // 截到上限（保序）
        List<String> targets = new ArrayList<>(unique.size());
        for (String id : unique) {
            if (targets.size() >= MAX_TARGETS) break;
            targets.add(id);
        }
        if (targets.isEmpty()) {
            return List.of();
        }
        // 单目标没有并行收益（建池/调度反而是开销）⇒ 直接走同步路径
        if (targets.size() == 1) {
            return List.of(sendOne(targets.get(0), text, attachments, who));
        }

        // 🔴 并行投递：每个目标都是「查库 → 解密凭证 → 换票 → 上传 → 发送」一条独立 HTTP 链，
        //    串行时总耗时 = 各目标之和（企微换票+上传能到秒级，5 个目标就是数秒）。
        //    这些目标之间**没有共享状态**（sendOne 是纯函数式的：只读 chat/channel 再发请求），
        //    所以并行只有收益。
        //
        // ⚠️ 但 results **必须与 targets 同序**：调用方（BriefTool / ImSendController）
        //    靠下标把结果配回 chatIds，顺序错了会把 A 的成功算到 B 头上。
        //    ⇒ supplyAsync 之后按 futures 的顺序 join，不用 allOf+无序收集。
        List<java.util.concurrent.CompletableFuture<ImSendResult>> futures =
                new ArrayList<>(targets.size());
        for (String chatId : targets) {
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return sendOne(chatId, text, attachments, who);
                        } catch (RuntimeException e) {
                            // sendOne 已保证不抛，这里只是兜底：绝不让一个目标炸掉整批
                            log.warn("[ImSend] 并行投递单目标异常 chatId={} err={}", chatId, e.toString());
                            return ImSendResult.failed("IM_SEND_INTERNAL", "投递异常：" + e.getClass().getSimpleName());
                        }
                    }, SEND_POOL));
        }
        List<ImSendResult> out = new ArrayList<>(targets.size());
        for (java.util.concurrent.CompletableFuture<ImSendResult> f : futures) {
            try {
                out.add(f.join());
            } catch (RuntimeException e) {
                // join 抛（理论上不会，因为上面已兜底）⇒ 仍然要保持「results 与 targets 同长」
                out.add(ImSendResult.failed("IM_SEND_INTERNAL", "投递异常：" + e.getClass().getSimpleName()));
            }
        }
        return out;
    }

    /**
     * 投递线程池。
     *
     * <p><b>为什么固定 8 个线程 + daemon</b>：
     * <ul>
     *   <li>目标是**网络 IO 密集**，8 个足够把 20 个上限的目标摊平，再多只是打平台限流；</li>
     *   <li>daemon ⇒ 不会因为残留任务阻止 JVM 退出（这是投递，不是必须完成的后台作业）；</li>
     *   <li>有界队列 + CallerRuns 拒绝策略：池满时由调用线程自己跑，
     *       宁可慢一点，也不要把消息丢掉。</li>
     * </ul>
     * ⚠️ 共享一个池（不是每次 new）：`send` 是高频道调用，每次建池会不断产生/销毁线程。
     */
    private static final java.util.concurrent.ExecutorService SEND_POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    8, 8, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "im-send-" + System.nanoTime() % 10_000);
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * 投递到单个目标。
     *
     * <p>⚠️ 本方法**不抛异常**：所有失败都变成 {@link ImSendResult}。
     * 因为它在循环里被调用，抛出去就是一个目标炸掉整批。
     */
    private ImSendResult sendOne(String chatId, String text,
                                 List<com.nexusai.domain.im_channel.send.ImSendAttachment> attachments,
                                 com.nexusai.domain.im_channel.send.SendIdentity identity) {
        boolean hasText = text != null && !text.isBlank();
        boolean hasAttach = attachments != null && !attachments.isEmpty();
        if (!hasText && !hasAttach) {
            return ImSendResult.failed("IM_PARAM_INVALID", "消息内容为空");
        }
        try {
            // ① 查聊天目录
            Optional<ImChatEntry> chatOpt = chatService.findById(chatId);
            if (chatOpt.isEmpty()) {
                return ImSendResult.failed("IM_CHAT_NOT_FOUND", "目标聊天不存在：" + chatId);
            }
            ImChatEntry chat = chatOpt.get();

            // ② 取实例凭证（解密后的根凭证）
            Optional<String> credOpt = channelService.credentialOf(chat.instanceId());
            if (credOpt.isEmpty()) {
                return ImSendResult.failed("IM_INSTANCE_NOT_FOUND",
                        "渠道实例不存在或未配置凭证：" + chat.instanceId());
            }

            // ③ 组上下文（replyToken 只有微信需要；其余平台拿了也不用，不额外开销）
            ImSendContext ctx = new ImSendContext(
                    chat.instanceId(),
                    chat.platform(),
                    chat.peerId(),
                    chat.chatKind(),
                    credOpt.get(),
                    chatService.replyTokenOf(chatId).orElse(null),
                    identity);

            // ④ 工厂取策略 → 模板方法发送
            ImSendStrategy strategy = strategyFactory.of(chat.platform());
            // send() 定义在接口上：工厂返回的任何策略（含兜底 Unsupported）都能直接调，
            // ⛔ 不要强转成 AbstractImSendStrategy —— 那会把模板细节泄漏到调用方。
            ImSendResult result = strategy.send(ctx, text, attachments);
            if (result == null) {
                // 防御：实现违反了「不返回 null」的约定，也不能让批量投递崩
                result = ImSendResult.failed("IM_SEND_PLATFORM_ERROR", "策略未返回结果：" + chat.platform());
            }

            // ⑤ 成功则更新「最近消息」信息（失败不写，避免把失败当成交互）
            if (result.ok()) {
                String preview = hasText ? text : ("[附件] " + attachments.get(0).name());
                chatService.markOutbound(chatId, preview, now());
            }

            // ⑥ 成功 ⇒ 本地补写一行 direction='out'（补「企微读不到 bot 自己消息」的缺口）
            recordOutbound(chat, result, text, attachments);

            return result;
        } catch (RuntimeException e) {
            // 兜底：单目标绝不外溢
            log.error("[ImSend] 单目标投递异常 chatId={}", chatId, e);
            return ImSendResult.failed("IM_SEND_PLATFORM_ERROR", "投递过程出现内部错误");
        }
    }

    /**
     * 补写本地消息行（{@code direction='out'} / {@code origin='local'}）。
     *
     * <h2>⛔ 为什么这一步绝不能影响发送结果</h2>
     * 消息<b>已经发出去了</b>（外部副作用已发生且不可回滚）。
     * 本地记账失败只能是「我们少了一行历史」，
     * 绝不能变成「用户看到发送失败」——更不能让人以为没发出去而再发一遍（对方收到两条）。
     * ⇒ 这里与 {@link ImMessageOutboundRecorder} 内部<b>各有一层</b> try/catch：
     * 记录器自己兜住一切，本方法再兜一层（防止将来换实现时漏掉）。
     *
     * <h2>为什么只在「真的发出去」时写</h2>
     * {@code SKIPPED}（我们主动没发）/ {@code FAILED}（平台拒绝）都没发出去 ⇒ 写进去就是假数据。
     * 判定由记录器统一负责（{@link ImSendResult#ok()}），本方法不重复判定（避免两处口径不一致）。
     */
    private void recordOutbound(ImChatEntry chat, ImSendResult result, String text,
                                List<com.nexusai.domain.im_channel.send.ImSendAttachment> attachments) {
        if (outboundRecorder == null) {
            // 只可能出现在「无 Spring 上下文直接 new 本服务」的单测里；生产环境必装配
            log.warn("[ImSend] 未装配 ImMessageOutboundRecorder，本条不补写本地消息 instance={}", chat.instanceId());
            return;
        }
        try {
            outboundRecorder.record(chat, result, text, attachments);
        } catch (RuntimeException e) {
            log.warn("[ImSend] 补写本地消息失败（发送结果不受影响）chatId={} err={}", chat.id(), e.toString());
        }
    }

    /** 解析 {@code notifyChats}（JSON 数组字符串）→ chatId 列表；格式不对返回空列表。 */
    public static List<String> parseChatIds(String notifyChatsJson) {
        if (notifyChatsJson == null || notifyChatsJson.isBlank()) {
            return List.of();
        }
        String raw = notifyChatsJson.trim();
        if (raw.startsWith("[")) {
            try {
                var node = JSON.readTree(raw);
                List<String> out = new ArrayList<>();
                if (node.isArray()) {
                    for (var n : node) {
                        if (n != null && n.isTextual() && !n.asText().isBlank()) {
                            out.add(n.asText().trim());
                        }
                    }
                }
                return out;
            } catch (Exception e) {
                log.warn("[ImSend] notifyChats 不是合法 JSON 数组，按空处理: {}", e.toString());
                return List.of();
            }
        }
        // 兼容逗号分隔的**裸 id** 形态（历史配置，可能带空格）。
        // ⛔ 只排除 JSON 元字符：出现 [ ] { } " \ 说明这是别的格式的脏数据，
        //    当成 chatId 只会让它一路走到 IM_CHAT_NOT_FOUND，不如直接判空 —— 早失败、日志清楚。
        if (JSON_META.matcher(raw).find()) {
            log.warn("[ImSend] notifyChats 既不是 JSON 数组也不是裸 id 列表，按空处理");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (part != null && !part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /** JSON 结构元字符 —— 出现即说明不是「裸 id 逗号列表」。 */
    private static final java.util.regex.Pattern JSON_META =
            java.util.regex.Pattern.compile("[\\[\\]{}\"\\\\]");

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
