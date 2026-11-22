package com.nexusai.domain.im_channel.inbound;

import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.model.im_channel.ImInstanceEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 入站监听的生命周期管理（按渠道实例启停）。
 *
 * <h2>为什么需要这一层</h2>
 * {@link ImInboundListener} 的实现持有**长连接/轮询线程**。它不能是 Spring 单例：
 * 同一平台可能有多个实例（两个企微机器人），一个 Bean 实例撑不住两条连接。
 * 所以：实现类标 {@code @Scope("prototype")}，本类按 instanceId **每个实例拿一个新对象**，
 * 自己维护「实例 → 监听器」的映射。
 *
 * <h2>为什么不自动启动</h2>
 * 后端启动时就把所有渠道连上去，会有三个问题：
 * <ol>
 *   <li>测试/开发环境会去连真实平台（不可接受）；</li>
 *   <li>用户可能只是打开 App 看看，不想让 bot 开始收消息；</li>
 *   <li>连上之后消息会真的入队触发 agent —— 有实际副作用。</li>
 * </ol>
 * 所以**显式启停**（REST），不做 {@code @PostConstruct} 自动连。
 *
 * <h2>状态一致性</h2>
 * {@code start} 对同一实例幂等（已在跑就直接返回）；{@code stop} 同理。
 * 实例被删除时监听器要一起停 —— 由调用方（控制器/定时对账）负责，本类不猜。
 */
@Component
public class ImInboundManager {

    private static final Logger log = LoggerFactory.getLogger(ImInboundManager.class);

    @Autowired private ImChannelService channelService;
    @Autowired private ImChatService chatService;
    @Autowired private ImInboundRouter router;

    /**
     * 平台 → Listener 工厂。
     *
     * <p>用 {@link ObjectProvider} 而不是直接注入 List：Listener 实现是 prototype，
     * 每次 {@code getIfAvailable} 拿到的是**新对象**（一个实例一条连接）。
     * ⚠️ 实现类必须标 {@code @Scope("prototype")}，否则这里拿到的是同一个单例 ——
     * 第二个实例启动时会把第一个的连接状态覆盖掉。
     */
    @Autowired private ObjectProvider<ImInboundListener> listeners;

    /** instanceId → 正在运行的监听器。 */
    private final Map<String, ImInboundListener> running = new ConcurrentHashMap<>();

    /**
     * 启动时自动恢复：**有接线的实例**自动开始收消息。
     *
     * <h2>判据为什么是「接线」而不是「上次谁在跑」</h2>
     * 进程重启后内存里的「谁在跑」全没了（那个标志本来也不该是权威）。
     * 而 {@code im_chat.bound_session_id} 是**持久化**的产品事实：用户把某个聊天接到了任务，
     * 就说明他希望这个渠道的消息被处理。重启不该改变这个意图。
     *
     * <h2>为什么用 ApplicationReadyEvent 而不是 @PostConstruct</h2>
     * {@code @PostConstruct} 时 Flyway/MyBatis 可能还没就绪；Ready 事件保证「可以查库了」。
     * 而且此时 Web 端口已监听，自动恢复失败也不会拖慢启动（异常只记日志）。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void restoreOnStartup() {
        java.util.Set<String> instanceIds = new java.util.LinkedHashSet<>();
        try {
            // 只取「已接线」的聊天 ⇒ 它们的实例才需要收消息
            chatService.list().stream()
                    .filter(c -> c.boundSessionId() != null && !c.boundSessionId().isBlank())
                    .forEach(c -> {
                        if (c.instanceId() != null && !c.instanceId().isBlank()) {
                            instanceIds.add(c.instanceId());
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("[ImInbound] 启动恢复时查接线失败（跳过自动恢复）: {}", e.toString());
            return;
        }
        if (instanceIds.isEmpty()) {
            log.info("[ImInbound] 启动恢复：没有已接线的聊天，不自动连接任何渠道");
            return;
        }
        for (String id : instanceIds) {
            try {
                boolean ok = start(id);
                log.info("[ImInbound] 启动恢复：实例 {} {}", id, ok ? "已开始收消息" : "未启动（见上方原因）");
            } catch (RuntimeException e) {
                // 单个实例失败不影响其它实例
                log.warn("[ImInbound] 启动恢复失败 instance={} err={}", id, e.toString());
            }
        }
    }

    /**
     * 新建实例后**自动启动**长连接（用户 2026-11-22 定：连接后默认启动，别让人多点一步）。
     *
     * <p>创建路径（手填凭证 / 扫码 / 一键创建）都经 {@code ImChannelService.create}
     * 发这个事件 ⇒ 一处接线覆盖所有渠道。
     * <p>失败只记日志：实例已经建好，用户仍可在卡片上手动「启动连接」。
     */
    @org.springframework.context.event.EventListener
    public void onInstanceCreated(com.nexusai.domain.im_channel.ImInstanceCreatedEvent e) {
        if (e == null || e.instanceId() == null || e.instanceId().isBlank()) {
            return;
        }
        try {
            boolean ok = start(e.instanceId());
            log.info("[ImInbound] 新建实例自动启动 instance={} {}", e.instanceId(),
                    ok ? "已开始收消息" : "未启动（见上方原因；可手动启动）");
        } catch (RuntimeException ex) {
            log.warn("[ImInbound] 新建实例自动启动失败 instance={} err={}", e.instanceId(), ex.toString());
        }
    }

    /**
     * 启动某实例的入站监听。
     *
     * @return 是否启动成功（已运行也算成功）
     */
    public boolean start(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return false;
        }
        ImInboundListener existing = running.get(instanceId);
        if (existing != null && existing.isRunning()) {
            return true;
        }
        Optional<ImInstanceEntry> instOpt = findInstance(instanceId);
        if (instOpt.isEmpty()) {
            log.warn("[ImInbound] 实例不存在，无法启动: {}", instanceId);
            return false;
        }
        ImInstanceEntry inst = instOpt.get();
        ImInboundListener listener = pickListener(inst.platform());
        if (listener == null) {
            log.warn("[ImInbound] 平台 {} 暂无入站实现，无法启动实例 {}", inst.platform(), instanceId);
            return false;
        }
        try {
            listener.start(instanceId, router::route);
            running.put(instanceId, listener);
            log.info("[ImInbound] 已启动 platform={} instance={} name={}",
                    inst.platform(), instanceId, inst.displayName());
            return true;
        } catch (RuntimeException e) {
            log.error("[ImInbound] 启动失败 instance={} platform={}", instanceId, inst.platform(), e);
            running.remove(instanceId);
            // 启动就在门口失败 ⇒ 监听器没机会自己写状态；这里补一条，卡片才能显示「异常」
            markStatus(instanceId, "error", "入站启动失败（见后端日志）");
            return false;
        }
    }

    /** 回写实例状态（DB 失败不影响启动/停止本身）。 */
    private void markStatus(String id, String status, String detail) {
        if (id == null || channelService == null) {
            return;
        }
        try {
            channelService.markStatus(id, status, detail);
        } catch (RuntimeException e) {
            log.warn("[ImInbound] 回写实例状态失败 instance={} status={} err={}", id, status, e.toString());
        }
    }

    /**
     * 收敛某实例的监听状态：**有接线就开、没接线就关**。
     *
     * <h2>为什么需要它</h2>
     * 监听的生命周期 = 「这个渠道还有没有聊天被接到任务」。
     * 用户解绑了最后一个聊天后，继续连着 WS 就是**白收**：消息进来也没处去（Router 会
     * 判定"未接线"而丢弃），却仍在消耗连接与重连开销。
     *
     * <h2>调用点</h2>
     * {@code ImChatController} 的 bind / unbind 之后各调一次 —— 接线变了，监听跟着变。
     * ⛔ 不要只在 unbind 里调：改接（从 A 实例换到 B）时也要让 B 起来。
     *
     * @param instanceId 目标实例；null/空白 ⇒ 不动作
     */
    public void reconcile(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        boolean stillBound;
        try {
            stillBound = chatService.list().stream()
                    .anyMatch(c -> instanceId.equals(c.instanceId())
                            && c.boundSessionId() != null && !c.boundSessionId().isBlank());
        } catch (RuntimeException e) {
            // 查不到就不动现状（fail-safe：宁可多连，也不要因为一次查询失败把用户的收消息停掉）
            log.warn("[ImInbound] 收敛时查接线失败，保持现状 instance={} err={}",
                    instanceId, e.toString());
            return;
        }
        if (stillBound) {
            boolean ok = start(instanceId);
            log.info("[ImInbound] 收敛：实例 {} 仍有接线 ⇒ {}", instanceId, ok ? "保持/开始收消息" : "未能启动");
        } else {
            boolean stopped = stop(instanceId);
            log.info("[ImInbound] 收敛：实例 {} 已无接线 ⇒ {}", instanceId,
                    stopped ? "已断开连接" : "本来就没在跑");
        }
    }

    /** 停止某实例的入站监听（幂等）。 */
    public boolean stop(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return false;
        }
        ImInboundListener listener = running.remove(instanceId);
        if (listener == null) {
            return false;
        }
        try {
            listener.stop();
            log.info("[ImInbound] 已停止 instance={}", instanceId);
            return true;
        } catch (RuntimeException e) {
            log.warn("[ImInbound] 停止时异常（忽略）instance={} err={}", instanceId, e.toString());
            return false;
        }
    }

    /**
     * 应用关闭时优雅关闭所有入站长连接。
     *
     * <p><b>为什么必须有</b>：飞书/企微的长连接在平台侧是按「设备」登记的。进程退出时若不
     * 主动 {@code close()}，平台仍可能把事件路由到这条已死的连接（推送照样回 200），本机表现
     * 就是「重启后一段时间一条事件都收不到，手动断开→启动一次才恢复」。关闭时主动 close，
     * 让平台立刻注销本设备。
     */
    @jakarta.annotation.PreDestroy
    public void stopAllOnShutdown() {
        for (String id : new java.util.ArrayList<>(running.keySet())) {
            try {
                stop(id);
            } catch (RuntimeException e) {
                log.warn("[ImInbound] 关闭时停止实例异常（忽略）instance={} err={}", id, e.toString());
            }
        }
    }

    /** 当前正在监听的实例（给状态面板）。 */
    public List<Map<String, Object>> status() {
        return running.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instanceId", e.getKey());
            m.put("platform", e.getValue().platform());
            m.put("running", e.getValue().isRunning());
            return m;
        }).toList();
    }

    private Optional<ImInstanceEntry> findInstance(String instanceId) {
        try {
            return channelService.list().stream()
                    .filter(i -> instanceId.equals(i.id()))
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("[ImInbound] 查询实例失败: {}", e.toString());
            return Optional.empty();
        }
    }

    /** 按平台取一个**新的**监听器实例（prototype）。 */
    private ImInboundListener pickListener(String platform) {
        if (platform == null) {
            return null;
        }
        return listeners.orderedStream()
                .filter(l -> l != null && platform.equals(l.platform()))
                .findFirst()
                .orElse(null);
    }
}
