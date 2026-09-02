package com.nexusai.application.agent.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Team Memory 内容 hash 工具 · 对齐 CC {@code Open-ClaudeCode/src/services/teamMemorySync/index.ts}
 * {@code hashContent}。
 *
 * <p>CC 真源（2026-08-06 grep -n 自验）：{@code hashContent} index.ts:134-136
 * = {@code 'sha256:' + sha256(content)}（与服务端 entryChecksums 直接字符串相等比较）。
 *
 * <p>DEL-M-21：旧 delta 计算实例方法（remoteEntries 签名）删除 —— 0 生产调用方，且 CC
 * pushTeamMemory :966-972 的 delta 是纯 hash 比较（{@code serverChecksums.get(key) !== localHash}，
 * 无 remoteEntries 参数），旧签名与 CC 语义不符。delta 计算由 TeamMemorySyncService 内联实现，
 * 本类只保留静态 {@link #hashContent}。
 */
public final class TeamMemoryDelta {

    private TeamMemoryDelta() {
        // 工具类：禁止实例化
    }

    /**
     * 计算 {@code sha256:<hex>} · CC original: {@code hashContent}（index.ts:134-136）。
     * 格式与服务端 entryChecksums 一致，本地-服务端比较用直接字符串相等。
     */
    public static String hashContent(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2 + 7);
            sb.append("sha256:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
