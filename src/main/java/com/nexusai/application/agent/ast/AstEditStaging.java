package com.nexusai.application.agent.ast;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AstEdit 预览暂存 · 保证「先预览、后落地」且落地前能判定「预览之后文件是否被改过」。
 *
 * <h2>为什么需要它</h2>
 * AstEdit 的 {@code apply:false} 只产生 diff（不写盘）。模型随后通常会再发一次
 * {@code apply:true}。这中间用户/其它工具可能改过文件 —— 若直接套用旧区间，字节偏移会错位、
 * 甚至静默写坏源码。因此预览时记下「每文件 SHA-256 + 逐条变更」，落地时重新计算并逐项比对，
 * 不一致就拒绝（{@code preview is stale}）。落地成功（{@link #take}）即移除，防重放。
 *
 * <h2>键与生命周期</h2>
 * 键 = {@code sessionId + \u0000 + 请求签名}；签名见 {@link #signature}（与路径书写顺序无关，
 * {@code apply} 不参与——它只是落地开关）。TTL 30 分钟、容量 64 条（超出淘汰最旧）。
 */
@Component
public class AstEditStaging {

    /** 暂存有效期（毫秒）· 超过即视为过期（用户应重新预览）。 */
    static final long TTL_MS = 30 * 60 * 1000L;

    /** 最多保留的暂存条数（跨会话共享一张表）。 */
    static final int MAX_ENTRIES = 64;

    private final Map<String, Staged> staged = new ConcurrentHashMap<>();

    /**
     * 预览快照。
     *
     * @param sha256ByFile 每个目标文件的 SHA-256（预览时刻）
     * @param changesByFile 每个文件的合并后变更（逐条参与 stale 比对）
     * @param atMillis 暂存时刻（TTL 判定）
     */
    public record Staged(Map<String, String> sha256ByFile,
                         Map<String, List<AstEditPlan.Change>> changesByFile,
                         long atMillis) {
        public Staged {
            sha256ByFile = sha256ByFile == null ? Map.of() : Map.copyOf(sha256ByFile);
            changesByFile = changesByFile == null ? Map.of() : Map.copyOf(changesByFile);
        }
    }

    /**
     * 请求签名 · 与 {@code ops} 内部顺序、{@code paths} 书写顺序无关（同一集合视为同一请求）。
     *
     * <p>载荷 = 每个 op 的 {@code "pat\u0000out"} 按给定顺序 join("\n") + "\n--\n" + (lang ?? "")
     * + "\n--\n" + 绝对路径升序 join("\n")，取 SHA-256 十六进制小写。
     */
    public static String signature(List<Map<String, String>> ops, String lang, List<String> absPaths) {
        StringBuilder sb = new StringBuilder();
        if (ops != null) {
            for (int i = 0; i < ops.size(); i++) {
                Map<String, String> op = ops.get(i);
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(op == null ? "" : op.getOrDefault("pat", ""));
                sb.append('\u0000');
                sb.append(op == null ? "" : op.getOrDefault("out", ""));
            }
        }
        sb.append("\n--\n").append(lang == null ? "" : lang.trim()).append("\n--\n");
        List<String> paths = absPaths == null ? List.of() : new ArrayList<>(absPaths);
        paths.sort(String::compareTo);
        sb.append(String.join("\n", paths));
        return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 记录一次预览（覆盖同签名旧记录）。 */
    public void stage(String sessionId, String signature, List<AstEditPlan> plans) throws IOException {
        long now = System.currentTimeMillis();
        purgeExpired(now);
        Map<String, String> sha = new LinkedHashMap<>();
        Map<String, List<AstEditPlan.Change>> changes = new LinkedHashMap<>();
        if (plans != null) {
            for (AstEditPlan plan : plans) {
                sha.put(plan.file(), sha256(Files.readAllBytes(Path.of(plan.file()))));
                changes.put(plan.file(), List.copyOf(plan.changes()));
            }
        }
        staged.put(key(sessionId, signature), new Staged(sha, changes, now));
        enforceCapacity();
    }

    /**
     * 取出并移除暂存（存在即消费；过期 → null）。
     *
     * @return 快照；无/过期 → null
     */
    public Staged take(String sessionId, String signature) {
        long now = System.currentTimeMillis();
        purgeExpired(now);
        Staged s = staged.remove(key(sessionId, signature));
        return s == null || isExpired(s, now) ? null : s;
    }

    /** 测试/诊断用：当前暂存条数。 */
    int size() {
        return staged.size();
    }

    private void purgeExpired(long now) {
        staged.entrySet().removeIf(e -> isExpired(e.getValue(), now));
    }

    private static boolean isExpired(Staged s, long now) {
        return now - s.atMillis() > TTL_MS;
    }

    private void enforceCapacity() {
        if (staged.size() <= MAX_ENTRIES) {
            return;
        }
        List<Map.Entry<String, Staged>> entries = new ArrayList<>(staged.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().atMillis()));
        int toRemove = staged.size() - MAX_ENTRIES;
        for (int i = 0; i < toRemove && i < entries.size(); i++) {
            staged.remove(entries.get(i).getKey());
        }
    }

    private static String key(String sessionId, String signature) {
        return (sessionId == null ? "" : sessionId) + '\u0000' + signature;
    }

    /** 字节数组 SHA-256（十六进制小写）。 */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // 每个 JRE 都必须有 SHA-256；真缺失时按「无法校验」抛 unchecked（fail-closed）
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
