package com.nexusai.application.agent.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Journal key 纯函数 · 对齐 CC {@code engine/journal.ts:8-21}。
 *
 * <p><b>W-1c 自包含编译声明</b>：W-1d journal 组未落地本工具（journal.ts:8-21 归属），
 * W-1c hooks 重放需要 agentCallKey，故由 W-1c 创建最小实现。</p>
 *
 * <p>W-1d 最高危点（P0-plan §8.1 R3）：{@link #canonicalParams} <b>必须剔除 label/phase</b>
 * （display-only，journal.ts:8-14），否则同一 agent() 换 label 后 resume 失配。序列化用
 * {@link TreeMap} 字典序 + 嵌套 Map 递归排序，保证同字段不同序 → 同 key。</p>
 */
public final class WorkflowJournal {

    private static final Logger log = LoggerFactory.getLogger(WorkflowJournal.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowJournal() {
    }

    /**
     * 规范参数串 · CC original: {@code canonicalParams(params)} (journal.ts:8-14)。
     *
     * <p>剔除 label/phase，剩余键字典序排序后 JSON 序列化。CC 的 {@code JSON.stringify} 会省略
     * undefined 字段 —— Java 对应省略 null 字段。</p>
     *
     * @param p agent() 入参（含 prompt；CC {@code params = {prompt, ...opts}}）
     * @return 稳定 JSON 串（同字段不同序 → 恒同）
     */
    public static String canonicalParams(AgentRunParams p) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (p.prompt() != null) {
            sorted.put("prompt", p.prompt());
        }
        if (p.schema() != null) {
            sorted.put("schema", normalizeForJson(p.schema()));
        }
        if (p.model() != null) {
            sorted.put("model", p.model());
        }
        if (p.maxTokens() != null) {
            sorted.put("maxTokens", p.maxTokens());
        }
        if (p.agentType() != null) {
            sorted.put("agentType", p.agentType());
        }
        if (p.isolation() != null) {
            sorted.put("isolation", p.isolation());
        }
        if (p.allowedTools() != null) {
            sorted.put("allowedTools", p.allowedTools());
        }
        return write(sorted);
    }

    /**
     * agent() 调用确定性 key · CC original: {@code agentCallKey} (journal.ts:17-21)。
     *
     * <p>{@code sha256(prompt + '\n' + canonicalParams)} hex。同一 prompt+params 恒同 key；
     * 换 label/phase 不变 key（canonicalParams 已剔除）。</p>
     */
    public static String agentCallKey(String prompt, AgentRunParams p) {
        String input = prompt + "\n" + canonicalParams(p);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            log.error("WorkflowJournal.agentCallKey：SHA-256 不可用：{}", e.getMessage());
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 递归把 Map 键排序（TreeMap），保证嵌套 schema 等序列化确定性。 */
    private static Object normalizeForJson(Object v) {
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), normalizeForJson(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(normalizeForJson(o));
            }
            return out;
        }
        return v;
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("WorkflowJournal 序列化失败：{}", e.getMessage());
            throw new IllegalStateException("WorkflowJournal serialize failed", e);
        }
    }
}
