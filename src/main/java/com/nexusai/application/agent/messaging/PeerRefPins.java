package com.nexusai.application.agent.messaging;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本会话「ListAgents 实际渲染过的行」pin 集合 —— 对齐 CC 2.1.328 的 {@code UMr}。
 *
 * <p>CC 原文（exe 355940461）：
 * <pre>
 * var UMr=new Xe(()=&gt;new Map);   // 会话对象级、跨轮保留、进程退出即灭；不落盘
 * </pre>
 * 写入点在列表渲染处（exe 364751847）：
 * <pre>
 * for(let{agent:u}of v.blocks.flatMap((I)=&gt;I.rows))if(u.id!==void 0)l?.set(u.id,u.name??"(untitled)");
 * </pre>
 * 校验点在 SendMessage（exe 356121546 {@code Lt}）：ref 未登记、或登记的 name 与写入地址不一致 ⇒
 * 「Not sent: ListAgents has not listed that session id …」。
 *
 * <p>本实现按 {@code sessionId -> (ref -> 渲染出的 name)} 存放：只写本页真正打印出来的行；
 * <b>生命周期 = 进程内</b>（重启即空，未列过即拒）；<b>跨轮保留</b>（同一会话后续轮次仍有效）。
 */
@Component
public class PeerRefPins {

    private final Map<String, Map<String, String>> listed = new ConcurrentHashMap<>();

    /**
     * 登记一行已渲染的 {@code name [ref]}。
     *
     * @param sessionId 列出的会话（收件方视角的「本会话」）
     * @param ref       方括号里的 ref（pinned 形态才参与 pin 校验）
     * @param name      该行打印的名字（校验时要求写入地址与它逐字相同）
     */
    public void recordListed(String sessionId, String ref, String name) {
        if (sessionId == null || sessionId.isBlank() || ref == null || ref.isBlank()) {
            return;
        }
        listed.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
            .put(ref, name == null ? "" : name);
    }

    /**
     * 该 ref 是否在本会话列过。
     *
     * @param sessionId 会话
     * @param ref       ref
     * @return true = 列过
     */
    public boolean isListed(String sessionId, String ref) {
        return listedName(sessionId, ref) != null;
    }

    /**
     * 列过该 ref 时返回当时打印的名字。
     *
     * @param sessionId 会话
     * @param ref       ref
     * @return 名字；未列过 → null
     */
    public String listedName(String sessionId, String ref) {
        if (sessionId == null || ref == null) {
            return null;
        }
        Map<String, String> rows = listed.get(sessionId);
        return rows == null ? null : rows.get(ref);
    }

    /**
     * 本会话已登记行的只读快照（测试/观测）· ref → 打印名。
     *
     * @param sessionId 会话
     * @return 不可变快照；无登记 → 空
     */
    public Map<String, String> snapshot(String sessionId) {
        Map<String, String> rows = sessionId == null ? null : listed.get(sessionId);
        return rows == null ? Map.of() : Map.copyOf(rows);
    }

    /** 本会话登记的行数（测试/观测）。 */
    public int size(String sessionId) {
        Map<String, String> rows = sessionId == null ? null : listed.get(sessionId);
        return rows == null ? 0 : rows.size();
    }

    /** 清掉某会话（会话对象销毁时）。 */
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            listed.remove(sessionId);
        }
    }

    /** 全部清空（测试辅助；生产不调用 = 进程退出即灭）。 */
    public void clear() {
        listed.clear();
    }
}
