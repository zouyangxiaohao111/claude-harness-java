package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行期 live endpoint 注册表（跨会话 SendMessage 的唯一寻址真源）。
 *
 * <p>不复用 DB 会话历史（含已关闭会话 = 幽灵目标），也不复用
 * {@code LlmAgentLoop.activeSessionIds()}（没有代次 / 权限模式 / 收件名）。
 * 只登记运行期 endpoint：B 批在会话成功初始化时 register，关闭时 beginClosing + unregister。
 *
 * <p>代次语义：register 每次生成新 epoch，同 session 重复 register 使旧 epoch 失效；
 * unregister 只在 epoch 匹配时删除（旧轮 finally 不会误删新 endpoint）；snapshot 不含 token。
 *
 * <p><b>endpoint token 已停用（2026-11-20 用户裁定 · 对齐清单 T01）</b>：
 * <ul>
 *   <li><b>CC 的做法</b>（2.1.328 {@code bin/claude.exe}：{@code lnl/$ds/dnl/cnl/pnl}，偏移
 *       ~317878947 / ~317882292 区；注册表见 TS 源 {@code src/utils/concurrentSessions.ts:22,68-71}）：
 *       注册表落 {@code <CLAUDE_CONFIG_HOME>/sessions/<pid>.json}；token <b>单独落盘</b>
 *       {@code <pid>.<sha256(小写规范化管道路径)>.key}（目录 0700、文件 0600），内容
 *       {@code {peerToken, childToken, ...}}；SendMessage 侧按 token + 命名管道归属校验发送方，
 *       防止别的进程冒用该会话地址。</li>
 *   <li><b>本仓现状</b>：单进程运行，来源身份由服务端上下文（{@link PeerEnvelope#fromSessionId()}）
 *       生成，token 从未被任何生产路径校验（{@code register} 的返回值只用于取 epoch，
 *       见 {@code ChatService#ensurePeerEndpointRegistered}）；因此不再生成随机 token。</li>
 *   <li><b>为何保留字段</b>：{@link EndpointHandle#endpointToken()} 与内部 {@code Endpoint.endpointToken}
 *       仍保留（恒为空串），避免改构造器签名破坏既有调用方与测试装配。</li>
 *   <li><b>何时恢复</b>：将来做多进程 / 多实例（跨进程投递、命名管道寻址）时再接：
 *       恢复 {@code UUID.randomUUID()} 生成，并在投递入口按 token + 通道归属校验来源。</li>
 * </ul>
 */
@Component
public class PeerSessionRegistry {

    /** endpoint 生命周期状态（只读展示用）。 */
    public enum Status { RUNNING, IDLE, UNKNOWN }

    /** 运行态来源：B 注入，默认 UNKNOWN（不复制 running 真源）。 */
    public interface StatusSource {
        Status status(String sessionId);
    }

    /** 注册入参。 */
    public record Registration(String sessionId, String title, String projectRoot,
                               PermissionMode permissionMode, long processGeneration) {}

    /**
     * 注册结果：token 字段保留但<b>已停用</b>（恒为空串，见类注释 T01）。
     *
     * <p>历史上 token 只回给注册者、不得外泄；本仓单进程 + 服务端上下文给出来源身份，
     * 无生产路径校验它 ⇒ 不再生成。
     */
    public record EndpointHandle(String sessionId, long endpointEpoch, String endpointToken) {}

    /** 对外只读视图（不含 token）。 */
    public record LiveSession(String sessionId, long processGeneration, long endpointEpoch,
                              String title, String projectRoot, PermissionMode permissionMode,
                              long modeVersion, Instant registeredAt, Instant lastActivityAt,
                              boolean accepting, boolean closing, String addressName, String ref) {}

    /** 内部存储：token 字段保留但已停用（恒为空串）。 */
    private record Endpoint(String sessionId, long processGeneration, long endpointEpoch,
                            String endpointToken, String title, String projectRoot,
                            PermissionMode permissionMode, long modeVersion, Instant registeredAt,
                            Instant lastActivityAt, boolean accepting, boolean closing) {

        Endpoint withActivity(Instant now) {
            return new Endpoint(sessionId, processGeneration, endpointEpoch, endpointToken, title,
                projectRoot, permissionMode, modeVersion, registeredAt, now, accepting, closing);
        }

        Endpoint withMode(PermissionMode mode, long version, Instant now) {
            return new Endpoint(sessionId, processGeneration, endpointEpoch, endpointToken, title,
                projectRoot, mode, version, registeredAt, now, accepting, closing);
        }

        Endpoint withTitle(String newTitle, Instant now) {
            return new Endpoint(sessionId, processGeneration, endpointEpoch, endpointToken, newTitle,
                projectRoot, permissionMode, modeVersion, registeredAt, now, accepting, closing);
        }

        Endpoint closing(Instant now) {
            return new Endpoint(sessionId, processGeneration, endpointEpoch, endpointToken, title,
                projectRoot, permissionMode, modeVersion, registeredAt, now, false, true);
        }

        LiveSession view(String addressName, String ref) {
            return new LiveSession(sessionId, processGeneration, endpointEpoch, title, projectRoot,
                permissionMode, modeVersion, registeredAt, lastActivityAt, accepting, closing,
                addressName, ref);
        }
    }

    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();

    /** 单调递增代次（进程内）。 */
    private final AtomicLong epochCounter = new AtomicLong();

    private volatile StatusSource statusSource;

    /** 部署门值（application.yml）；空 = 未显式配置。 */
    @Value("${nexusai.feature.messaging-beyond-own-agents:}")
    private String messagingBeyondOwnAgentsValue;

    /** 启动时把 yml 部署门安装为 {@link TaskSystemConfig} 动态 source；未配置则不安装。 */
    @PostConstruct
    void installFeatureGate() {
        Boolean configured = parseBooleanOrNull(messagingBeyondOwnAgentsValue);
        if (configured == null) {
            return;
        }
        TaskSystemConfig.installMessagingBeyondOwnAgentsSource(() -> configured);
    }

    private static Boolean parseBooleanOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v)) {
            return Boolean.TRUE;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 登记 endpoint（重复登记 = 新代次，旧代次立即失效）。
     *
     * <p>token <b>不再生成</b>（T01）：CC 用 {@code <pid>.<sha256(管道路径)>.key} 落盘 token 防跨进程
     * 冒用地址；本仓单进程、来源身份由服务端上下文给出，token 无生产校验点 ⇒ 恒为空串。
     * 恢复条件见类注释（将来做多进程 / 多实例时）。
     *
     * @param registration 注册入参
     * @return 新代次句柄（endpointToken 恒为空串）
     */
    public EndpointHandle register(Registration registration) {
        if (registration == null || registration.sessionId() == null
            || registration.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        long epoch = epochCounter.incrementAndGet();
        Instant now = Instant.now();
        Endpoint endpoint = new Endpoint(
            registration.sessionId(), registration.processGeneration(), epoch,
            // token 已停用：原为 UUID.randomUUID().toString()（CC 对应 <pid>.<sha256(pipe)>.key）。
            // 恢复多进程 / 多实例时还原生成并补投递侧校验（见类注释 T01）。
            "",
            registration.title(), registration.projectRoot(),
            registration.permissionMode(), 0L, now, now, true, false);
        endpoints.put(registration.sessionId(), endpoint);
        return new EndpointHandle(registration.sessionId(), epoch, endpoint.endpointToken());
    }

    /** 心跳（只更新 lastActivityAt；代次不符 = no-op）。 */
    public boolean heartbeat(String sessionId, long endpointEpoch) {
        Instant now = Instant.now();
        boolean[] ok = {false};
        endpoints.computeIfPresent(sessionId, (k, e) -> {
            if (e.endpointEpoch() != endpointEpoch) {
                return e;
            }
            ok[0] = true;
            return e.withActivity(now);
        });
        return ok[0];
    }

    /** 更新权限模式（只在 modeVersion 前进时生效，旧版本不覆盖新值）。 */
    public boolean updateMode(String sessionId, long endpointEpoch, PermissionMode mode, long modeVersion) {
        if (mode == null) {
            return false;
        }
        Instant now = Instant.now();
        boolean[] ok = {false};
        endpoints.computeIfPresent(sessionId, (k, e) -> {
            if (e.endpointEpoch() != endpointEpoch || modeVersion <= e.modeVersion()) {
                return e;
            }
            ok[0] = true;
            return e.withMode(mode, modeVersion, now);
        });
        return ok[0];
    }

    /**
     * 更新标题快照（代次匹配才生效）。
     *
     * <p><b>WHY</b>：注册时只存标题快照，而会话标题会在首轮后自动生成、或被用户 /rename 改掉。
     * 不同步的话 {@link #snapshot()} / {@link #findByAddress} 永远显示旧标题 —— ListAgents 与
     * SendMessage 的「按标题寻址」都会落后于 DB。
     *
     * @param sessionId     会话 id
     * @param endpointEpoch 注册代次
     * @param title         新标题（null/空白 → 归一为空标题，地址名回落到 sessionId）
     * @return true = 已更新；false = 会话未登记或代次不符
     */
    public boolean updateTitle(String sessionId, long endpointEpoch, String title) {
        Instant now = Instant.now();
        boolean[] ok = {false};
        endpoints.computeIfPresent(sessionId, (k, e) -> {
            if (e.endpointEpoch() != endpointEpoch) {
                return e;
            }
            ok[0] = true;
            return e.withTitle(title, now);
        });
        return ok[0];
    }

    /** 开始关闭：accepting=false、closing=true（仍可只读展示，直到 unregister）。 */
    public boolean beginClosing(String sessionId, long endpointEpoch) {
        Instant now = Instant.now();
        boolean[] ok = {false};
        endpoints.computeIfPresent(sessionId, (k, e) -> {
            if (e.endpointEpoch() != endpointEpoch) {
                return e;
            }
            ok[0] = true;
            return e.closing(now);
        });
        return ok[0];
    }

    /** 注销（代次不符 = no-op）。 */
    public boolean unregister(String sessionId, long expectedEpoch) {
        if (sessionId == null) {
            return false;
        }
        boolean[] removed = {false};
        endpoints.computeIfPresent(sessionId, (k, e) -> {
            if (e.endpointEpoch() != expectedEpoch) {
                return e;
            }
            removed[0] = true;
            return null;
        });
        return removed[0];
    }

    /** 按 sessionId 取只读视图（含 closing 的 endpoint；不判可投递）。 */
    public Optional<LiveSession> resolve(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        Endpoint e = endpoints.get(sessionId);
        return e == null ? Optional.empty() : Optional.of(toView(e));
    }

    /**
     * 按收件名 + 可选 ref 查找（0/1/n）。同名跨会话不串：多条命中原样返回，由调用方判 AMBIGUOUS。
     */
    public List<LiveSession> findByAddress(String name, String ref) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String normalized = PeerAddressing.normalizeName(name);
        List<LiveSession> out = new ArrayList<>();
        for (Endpoint e : endpoints.values()) {
            LiveSession view = toView(e);
            if (!normalized.equals(baseName(e))) {
                continue;
            }
            // 对齐 CC `t3()`：写入 ref 是 6..12 位 hex 时按哈希前缀比较（列表可能打印更长的
            // 最短唯一前缀）；pinned 形态（session_/cse_/local_）要求与身份逐字相等。
            if (ref != null && !ref.isBlank()
                && !PeerAddressing.refMatches("session", e.sessionId(), ref)) {
                continue;
            }
            out.add(view);
        }
        out.sort(Comparator.comparing(LiveSession::sessionId));
        return List.copyOf(out);
    }

    /** 全量只读快照（不含 token）。 */
    public List<LiveSession> snapshot() {
        List<LiveSession> out = new ArrayList<>(endpoints.size());
        for (Endpoint e : endpoints.values()) {
            out.add(toView(e));
        }
        out.sort(Comparator.comparing(LiveSession::addressName,
                Comparator.nullsLast(String::compareTo)).thenComparing(LiveSession::sessionId));
        return List.copyOf(out);
    }

    /** 是否可投递（代次匹配 + accepting + 未 closing）。 */
    public boolean isLive(String sessionId, long endpointEpoch) {
        Endpoint e = endpoints.get(sessionId);
        return e != null && e.endpointEpoch() == endpointEpoch && e.accepting() && !e.closing();
    }

    /** 运行态状态（来自 StatusSource；未注入 → UNKNOWN）。 */
    public Status status(String sessionId) {
        StatusSource source = statusSource;
        if (source == null) {
            return Status.UNKNOWN;
        }
        Status s = source.status(sessionId);
        return s == null ? Status.UNKNOWN : s;
    }

    /** 安装运行态来源（B 注入）。 */
    public void setStatusSource(StatusSource source) {
        this.statusSource = source;
    }

    /** 当前登记数（测试/观测）。 */
    public int size() {
        return endpoints.size();
    }

    /** 清空（测试辅助）。 */
    public void clear() {
        endpoints.clear();
        statusSource = null;
    }

    private LiveSession toView(Endpoint e) {
        return e.view(addressNameOf(e.sessionId()), PeerAddressing.refFor("session", e.sessionId()));
    }

    /**
     * 收件名：标题归一化后作地址名；空白 → sessionId。重名时追加 {@code -ref}（ref 6 hex），
     * 保证同一时刻地址名唯一（无 ref 发送不歧义）。
     */
    private String addressNameOf(String sessionId) {
        Endpoint self = endpoints.get(sessionId);
        if (self == null) {
            return sessionId;
        }
        String base = baseName(self);
        String ref = PeerAddressing.refFor("session", sessionId);
        boolean conflict = false;
        for (Endpoint other : endpoints.values()) {
            if (other.sessionId().equals(sessionId)) {
                continue;
            }
            if (baseName(other).equals(base)) {
                conflict = true;
                break;
            }
        }
        return conflict ? base + "-" + ref : base;
    }

    /** 收件名（不含冲突后缀）：标题归一化；空白 → sessionId。 */
    private static String baseName(Endpoint e) {
        String base = PeerAddressing.normalizeName(e.title());
        return base.isBlank() ? e.sessionId() : base;
    }
}
