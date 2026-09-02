package com.nexusai.application.agent.settings;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Settings 同步 delta 计算 · 对齐 CC services/settingsSync (delta 路径, OAuth 跳过).
 *
 * <p>L1 语义: 给定 local settings (path → content) + remote settings + remote checksums,
 * 计算 push 时的最小上传集 (新增 + 变化) 与 pull 时的覆盖集. 不依赖 OAuth, 仅描述算法.
 *
 * <p>L2 契约:
 * <ul>
 *   <li>{@link #computeUploadSet} 与 TeamMemoryDelta 行为一致: checksum 匹配的跳过</li>
 *   <li>{@link #computePullSet} 与 computeUploadSet 对偶: 远端有且本地无 checksum → 拉取</li>
 * </ul>
 *
 * <p>L3 (Java idiom): 与 TeamMemoryDelta 共享 SHA-256 计算, 但不复用 (避免跨包耦合).
 */
@Component
public class SettingsSyncDelta {

    /** 计算 push 上传集 (local 主导, 与 TeamMemory 行为一致). */
    public Map<String, String> computeUploadSet(
        Map<String, String> local,
        Map<String, String> remote,
        Map<String, String> remoteChecksums
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : local.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (!remote.containsKey(path)) {
                result.put(path, content);
                continue;
            }
            String remoteChecksum = remoteChecksums == null ? null : remoteChecksums.get(path);
            String localChecksum = "sha256:" + sha256Hex(content);
            if (!localChecksum.equals(remoteChecksum)) {
                result.put(path, content);
            }
        }
        return result;
    }

    /**
     * 计算 pull 拉取集: 远端有, 本地无对应 checksum → 需下载.
     *
     * <p>L2 契约: 本地 path 缺失或本地 checksum 与远程不匹配 → 包含在结果中.
     */
    public Map<String, String> computePullSet(
        Map<String, String> local,
        Map<String, String> remote,
        Map<String, String> remoteChecksums
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : remote.entrySet()) {
            String path = e.getKey();
            String remoteContent = e.getValue();
            String remoteChecksum = remoteChecksums == null ? null : remoteChecksums.get(path);
            String localContent = local.get(path);
            String localChecksum = localContent == null
                ? null
                : "sha256:" + sha256Hex(localContent);

            if (!remoteChecksum.equals(localChecksum)) {
                result.put(path, remoteContent);
            }
        }
        return result;
    }

    /** 远端有但本地无 path 集合 (供 UI 提示新增). */
    public Set<String> computeNewRemotePaths(
        Map<String, String> local,
        Map<String, String> remote
    ) {
        Set<String> result = new LinkedHashSet<>();
        for (String path : remote.keySet()) {
            if (!local.containsKey(path)) {
                result.add(path);
            }
        }
        return result;
    }

    static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}