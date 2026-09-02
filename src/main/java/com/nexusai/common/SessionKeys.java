package com.nexusai.common;

import java.util.UUID;

/**
 * 会话标识双形态归一化 · <b>[session-id-short] 已降级为 @Deprecated 旧数据兼容层（阶段 1）</b>。
 *
 * <p><b>历史背景（双形态产生原因）</b>：Web 后端早期同一会话存在两种标识，各层以不同形态作 Map 键：
 * <ul>
 *   <li><b>原始会话键 {@code "sess-xxxxxxxx"}（8 位 hex）</b>——前端/HTTP 侧会话主键
 *       （{@code SessionService.generateId("sess")}）。</li>
 *   <li><b>派生 UUID {@code 00000000-0000-0000-0000-xxxxxxxx0000}</b>——{@code "sess-xxx"} 经
 *       {@code ChatService.parseSessionUuid} 归一化的稳定 UUID（旧 {@code AgentState.sessionId} /
 *       {@code RunRequest.sessionId} / {@code ToolUseContext.sessionId} / RUNNING_SESSIONS /
 *       SessionCwdHolder 层以此为键）。</li>
 * </ul>
 *
 * <p><b>[session-id-short] 现状</b>：会话唯一 id 已统一为 short（sess-xxx，String），全仓热路径
 * 消费方（NotificationQueue / CronIdleExecutor / TeamHelpers / ImageAttachmentStore /
 * PostCompactionState / PartialCompactService / WebSocketPermissionPrompter / ToolRegistrationConfig /
 * SessionService / EffortCommand / MemoryBareModeConfig / SessionToolDisableConfig / TeamController /
 * SessionToolsController / SkillImprovementController 等）改走 short 直键，不再调用本类。
 *
 * <p><b>阶段 1 用途收窄为「仅存量读取兼容」</b>，仅服务三类存量数据：
 * <ol>
 *   <li>存量 DB cron 任务 sessionId 列派生 UUID 串（{@link #originalKey(String)} 反解）；</li>
 *   <li>存量 transcript 文件名派生 UUID（{@link #originalKey(String)} 反解）；</li>
 *   <li>QueueItem 历史项（{@link #canonicalUuid(String)} 正向派生供历史测试/审计比较）。</li>
 * </ol>
 *
 * <p><b>阶段 2</b>（DB 迁移完成 + transcript 文件改名完成）后删除整个本类（或降级为私有
 * LegacySessionKeyReader 仅供 DB/transcript 存量读取）。canonicalUuid 的 null→UUID(0,0) 占位与
 * hash 兜底语义被『null sessionId 原样透传』取代；originalKey 的『不可逆→null 诚实降级』语义
 * 仅在兼容层保留。
 */
public final class SessionKeys {

    private SessionKeys() {}

    /**
     * 归一化会话 UUID（正向）· <b>@Deprecated 兼容层：仅存量读取</b>。
     *
     * <p>[session-id-short] 新代码一律 short 直键，不调用本方法。存量场景：QueueItem 历史项
     * 派生 UUID 串 / 历史测试与审计的键派生比较。
     *
     * <p>解析顺序（与旧 ChatService.parseSessionUuid 逐字节同构）：
     * <ol>
     *   <li>null/空白 → {@code new UUID(0L, 0L)}（占位，绝不抛）</li>
     *   <li>合规 UUID 字符串 → 直接返回</li>
     *   <li>{@code "sess-xxxxxxxx"}（8 位）→ 拼成 {@code 00000000-0000-0000-0000-xxxxxxxx0000}</li>
     *   <li>解析失败 → 用 string hashCode 生成稳定 UUID（同一 sessionId 多次调用映射到同一 UUID，
     *       保证权限系统 requestId 关联不失效）</li>
     * </ol>
     *
     * @param sessionId 会话 ID（{@code "sess-xxxxxxxx"} / 合规 UUID / 任意串）
     * @return UUID（永不返回 null）
     * @deprecated 仅存量读取兼容（阶段 2 删除），新代码用 short 直键。
     */
    @Deprecated
    public static UUID canonicalUuid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new UUID(0L, 0L);
        }
        // 1) 尝试直接 parse（合规 UUID 字符串）
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException ignore) {
            // 不是合规 UUID → 继续
        }
        // 2) "sess-xxxxxxxx" → 拼成 00000000-0000-0000-0000-xxxxxxxx0000
        String stripped = sessionId.startsWith("sess-") ? sessionId.substring(5) : sessionId;
        if (stripped.length() == 8) {
            try {
                return UUID.fromString("00000000-0000-0000-0000-" + stripped + "0000");
            } catch (IllegalArgumentException ignore) {
                // 继续 fallback
            }
        }
        // 3) Fallback: 稳定 hash → UUID (mostSignificantBits 64 位)
        long h1 = sessionId.hashCode();
        long h2 = (sessionId.hashCode() * 31L) ^ (sessionId.length() * 17L);
        return new UUID(h1, h2);
    }

    /**
     * 从派生 UUID 回解原始会话键（逆向）· <b>@Deprecated 兼容层：仅存量读取</b>。
     *
     * <p>规则：仅当 UUID 精确匹配 {@code "00000000-0000-0000-0000-xxxxxxxx0000"}（即
     * {@code "sess-"+8hex} 的派生形态）时还原 {@code "sess-xxxxxxxx"}；否则返回 null（诚实降级：
     * 合法 UUID 直接解析 / hash 兜底的 UUID 不可逆，调用方回退原值）。存量会话全部为
     * {@code SessionService.generateId("sess")} 格式，恒可反解。
     *
     * @param sessionUuid 派生 UUID（{@link #canonicalUuid(String)} 对 {@code "sess-xxx"} 的产物）
     * @return 原始会话键 {@code "sess-xxxxxxxx"}；不可逆 → null
     * @deprecated 仅存量读取兼容（阶段 2 删除），新代码用 short 直键。
     */
    @Deprecated
    public static String originalKey(UUID sessionUuid) {
        if (sessionUuid == null) {
            return null;
        }
        String s = sessionUuid.toString();
        if (s.startsWith("00000000-0000-0000-0000-") && s.endsWith("0000")) {
            String id8 = s.substring("00000000-0000-0000-0000-".length(), s.length() - 4);
            if (id8.length() == 8) {
                return "sess-" + id8;
            }
        }
        return null;
    }

    /**
     * 从会话标识串重建原始键（String 便捷重载）· <b>@Deprecated 兼容层：仅存量读取</b>。
     * <ul>
     *   <li>已是 {@code "sess-xxx"}（HTTP 路径落库形态）→ 原样返回</li>
     *   <li>存量派生 UUID 串（旧工具路径 {@code CronCreateTool} 落库形态）→ 反解回 {@code "sess-xxx"}</li>
     *   <li>不可反解（合规 UUID 直接解析 / hash 兜底 / null / 空白）→ null（调用方回退原值）</li>
     * </ul>
     *
     * @param sessionId 会话标识串（short 原始键或存量派生 UUID 串）
     * @return 原始会话键；不可反解 → null
     * @deprecated 仅存量读取兼容（阶段 2 删除），新代码用 short 直键。
     */
    @Deprecated
    public static String originalKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (sessionId.startsWith("sess-")) {
            return sessionId;
        }
        return originalKey(canonicalUuid(sessionId));
    }
}
