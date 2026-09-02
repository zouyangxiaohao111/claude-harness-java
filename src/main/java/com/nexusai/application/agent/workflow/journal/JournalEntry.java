package com.nexusai.application.agent.workflow.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.workflow.AgentRunParams;
import com.nexusai.application.agent.workflow.AgentRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * journal 单条记录 · CC original: {@code JournalEntry} (types.ts:77-82).
 *
 * <pre>{@code
 * export type JournalEntry = {
 *   key: string        // agentCallKey(prompt, params)：sha256(prompt + '\n' + canonicalParams)
 *   seq: number        // agent() 调用顺序号（源自 agentIdSeq；跨子 workflow 单调递增）
 *   result: AgentRunResult
 * }
 * }</pre>
 *
 * <p><b>语义</b>：append/push 顺序 = <b>完成顺序</b>（并行完成顺序 ≠ 调用顺序）；
 * read() 按 {@code seq} 升序重排以稳定 resume（journal.ts:35-37，旧条目缺 seq 按 0）。
 *
 * <p>本类同时承载 {@link #agentCallKey(String, AgentRunParams)} 静态方法 ——
 * 对齐 CC {@code engine/journal.ts:17-21} 的确定性指纹算法。
 *
 * @param key    agentCallKey(prompt, params)：sha256 十六进制指纹 · CC original: key (types.ts:78)
 * @param seq    agent() 调用顺序号 · CC original: seq (types.ts:80)
 * @param result agent 运行结果（ok/skipped/dead）· CC original: result (types.ts:81)
 */
public record JournalEntry(
        String key,
        int seq,
        AgentRunResult result
) {

    private static final Logger log = LoggerFactory.getLogger(JournalEntry.class);

    /** 稳定 JSON 序列化器（canonicalParams 用，字段序由 TreeMap 保证字典序）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * agent() 调用的确定性指纹 · CC original: {@code agentCallKey} (journal.ts:17-21).
     *
     * <pre>{@code
     * export function agentCallKey(prompt: string, params: AgentRunParams): string {
     *   return createHash('sha256')
     *     .update(prompt + '\n' + canonicalParams(params))
     *     .digest('hex')
     * }
     * }</pre>
     *
     * <p>同一 {@code prompt + params} 恒同 key，是 journal resume 的命中依据。
     * {@code canonicalParams} 已剔除 display-only 的 label/phase，故换 label 不改 key。
     *
     * @param prompt agent() 的提示词
     * @param params agent() 的入参（label/phase 不参与指纹）
     * @return sha256(prompt + '\n' + canonicalParams) 的十六进制串
     */
    public static String agentCallKey(String prompt, AgentRunParams params) {
        String canonical = canonicalParams(params);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest((prompt + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必带算法；这里仅做 fail-loud 兜底，不吞错
            throw new IllegalStateException("SHA-256 算法不可用，无法生成 journal key", e);
        }
        String key = HexFormat.of().formatHex(digest);
        if (log.isDebugEnabled()) {
            log.debug(
                "agentCallKey 生成：promptLen={} canonicalLen={} key={}（对齐 CC journal.ts:17-21）",
                prompt.length(), canonical.length(), key);
        }
        return key;
    }

    /**
     * 剔除 display-only 字段后的规范参数串 · CC original: {@code canonicalParams} (journal.ts:8-14).
     *
     * <pre>{@code
     * function canonicalParams(params: AgentRunParams): string {
     *   const { label: _label, phase: _phase, ...rest } = params   // 剔除 label/phase
     *   const keys = Object.keys(rest).sort()                       // key 字典序排序
     *   ... return JSON.stringify(sorted)                           // 排序后 JSON 序列化
     * }
     * }</pre>
     *
     * <p>算法要点：1) 剔除 label/phase（仅展示，不参与指纹）；2) 剩余字段名按字典序排序
     * （TreeMap 迭代序天然字典序，等价 {@code Object.keys(rest).sort()}）；3) 跳过 null 字段
     * （等价 JS 中 undefined 字段被 {@code JSON.stringify} 省略）。
     *
     * @param p agent() 入参
     * @return 稳定 JSON 串（字段字典序、无 label/phase、无 null 字段）
     */
    static String canonicalParams(AgentRunParams p) {
        Map<String, Object> rest = new TreeMap<>();
        // 只放参与指纹的字段；label/phase 是 display-only，被剔除（journal.ts:8-14）
        rest.put("prompt", p.prompt());
        if (p.schema() != null) {
            rest.put("schema", p.schema());
        }
        if (p.model() != null) {
            rest.put("model", p.model());
        }
        if (p.maxTokens() != null) {
            rest.put("maxTokens", p.maxTokens());
        }
        if (p.agentType() != null) {
            rest.put("agentType", p.agentType());
        }
        if (p.isolation() != null) {
            rest.put("isolation", p.isolation());
        }
        if (p.allowedTools() != null) {
            rest.put("allowedTools", p.allowedTools());
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(rest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonicalParams 序列化失败（schema 含不可序列化对象）", e);
        }
        if (log.isDebugEnabled()) {
            log.debug(
                "canonicalParams 生成：字段数={} jsonLen={}（已剔除 display-only 的 label/phase，对齐 CC journal.ts:8-14）",
                rest.size(), json.length());
        }
        return json;
    }
}
