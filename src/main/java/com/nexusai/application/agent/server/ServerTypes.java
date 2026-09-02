package com.nexusai.application.agent.server;

import java.util.List;
import java.util.Map;

/**
 * Server types · 对齐 CC server/types.ts.
 *
 * <p>L1 语义: server Zod schemas + ServerConfig + SessionState 状态枚举 + SessionInfo + SessionIndex.
 *
 * <p>L3 (Java idiom): TS Zod lazySchema → Java record (无运行时校验; 严格 type 来自 Jackson 反序列化);
 *                    TS enum → Java enum; TS Record&lt;string, SessionIndexEntry&gt; → Map&lt;String, SessionIndexEntry&gt;.
 */
public final class ServerTypes {

    private ServerTypes() {}

    /** CC connectResponseSchema — server connect 响应 (3 字段). */
    public record ConnectResponse(
        String sessionId,    // CC session_id (snake_case → camelCase Jackson 映射)
        String wsUrl,
        String workDir       // 可选
    ) {}

    /** CC ServerConfig — 7 字段 server 配置. */
    public record ServerConfig(
        int port,
        String host,
        String authToken,
        String unix,         // 可选 Unix socket 路径
        Long idleTimeoutMs,  // 0 = never expire
        Integer maxSessions,
        String workspace
    ) {}

    /** CC SessionState — 5 状态枚举. */
    public enum SessionState {
        STARTING("starting"),
        RUNNING("running"),
        DETACHED("detached"),
        STOPPING("stopping"),
        STOPPED("stopped");

        private final String value;
        SessionState(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /** CC SessionInfo — 单个 session 运行时信息. */
    public record SessionInfo(
        String id,
        SessionState status,
        long createdAt,
        String workDir,
        Long pid,            // ChildProcess.pid (Java 用 Long 替代 CC ChildProcess 引用)
        String sessionKey
    ) {}

    /** CC SessionIndexEntry — 持久化的 session 元数据 (用于 server 重启恢复). */
    public record SessionIndexEntry(
        String sessionId,
        String transcriptSessionId,  // CC --resume session id; same as sessionId for direct sessions
        String cwd,
        String permissionMode,
        long createdAt,
        long lastActiveAt
    ) {}

    /** CC SessionIndex — session key → entry 映射. */
    public record SessionIndex(Map<String, SessionIndexEntry> entries) {}
}