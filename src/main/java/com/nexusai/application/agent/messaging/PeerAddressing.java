package com.nexusai.application.agent.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨会话地址分类 · 对齐 CC 2.1.328 {@code bs()} 的判类顺序。
 *
 * <p><b>顺序</b>：session → main → agent → Nexus {@code sess-} 扩展 → name。
 * CC 的第 4 类 name 是 catch-all，因此 Nexus 扩展放在三结构类之后、name 兜底之前
 * （放在 catch-all 之后将永远不可达；工具描述必须注明 CC 无 {@code sess-} 前缀）。
 *
 * <p>CC 的 session 类包含 {@code uds:} / {@code bridge:} / {@code did:} / {@code *.sock} /
 * {@code \\pipe\\} / {@code ^local_[0-9a-f-]{8,72}$} / 裸 {@code session_|cse_}。本仓 P0 没有
 * 远端传输，这些地址只能被识别为“远端不支持”，绝不能落本地 mailbox。
 *
 * <h2>ref 派生与两套解析器（CC 2.1.328 实测原文）</h2>
 *
 * <p><b>派生</b>（exe 偏移 317889547 {@code Hc} / 317883861 {@code Bc,nxi} / 316004194 {@code QX}）：
 * {@code Hc(kind,id) = String(qn("<kind>:<stableId>"))}，`stableId ?: id`；列表一次性对整页
 * 哈希排序后取「与相邻哈希的最长公共前缀 + 1」，下限 6、上限 12，字符集仅 {@code [0-9a-f]}：
 * <pre>
 * var Bc=6, nxi=12, Mnt=`[0-9a-f]{${Bc},${nxi}}`
 * function QX(e){let r=e.map(y=&gt;Hc(y.kind,ihr(y))),o=e3().map(y=&gt;Hc("session",y)),g=M([...r,...o]).sort(),h=new Map;
 *   for(let y=0;y&lt;g.length;y++){let A=g[y],k=Math.max(y&gt;0?LD(A,g[y-1]):0,y+1&lt;g.length?LD(A,g[y+1]):0);
 *     h.set(A,Math.min(A.length,Math.max(Bc,k+1)))}
 *   return e.map((y,A)=&gt;{let k=r[A];return{...y,ref:k.slice(0,h.get(k))}})}
 * function LD(e,r){let o=0;while(o&lt;e.length&amp;&amp;o&lt;r.length&amp;&amp;e[o]===r[o])o++;return o}
 * function Hc(e,r){return String(qn(e==="cloud-session"||e==="bridge-session"?`bridge-session:${Fye(r)}`:`${e}:${r}`))}
 * function pUt(e,r){return Hc(e,r).slice(0,Bc)}   // 自身 ref = 前 6
 * function t3(e,r,o){return e.length&gt;=Bc&amp;&amp;Hc(r,o).startsWith(e)}
 * </pre>
 * 本仓以 SHA-256 前 12 字节（24 hex）代替 CC 的 {@code qn}（CC 的摘要实现不可从 exe 字节证实，
 * 且本仓 stableId 域不同，跨实现同值无意义）；长度规则、前缀比较与字符集逐条照抄。
 *
 * <p><b>pin 解析器</b>（exe 316750320 {@code $Ut}）：只认方括号里是 {@code session_}/{@code cse_} 或
 * {@code local_} 形态：
 * <pre>
 * var HUt=/^local_[0-9a-f-]{8,72}$/;
 * var Hl=/^(?:session|cse)_(?:staging_|local_)?01[1-9A-HJ-NP-Za-km-z]{22}$/;
 * function $Ut(e,o=!0){let r=/\S \[([\w-]+)\]$/.exec(e.trim())?.[1];
 *   return r!==void 0&amp;&amp;VS(e).scheme==="other"&amp;&amp;(Hl.test(r)||o&amp;&amp;HUt.test(r))?r:e}
 * </pre>
 *
 * <p><b>短 hex 解析器</b>（exe 317883947 {@code Sse}）：
 * <pre>
 * var kX=new RegExp(`^(.*\\S)\\s*\\[(${Mnt})\\]$`);
 * function Sse(e){let r=kX.exec(e.trim());return r?{name:r[1],ref:r[2]}:null}
 * </pre>
 * 两套解析器行为不同：6–12 位 hex 简 ref 只走短 hex 解析（不触发 pin），pinned 形态走 pin 解析。
 */
public final class PeerAddressing {

    /** 地址类别。 */
    public enum Kind {
        SESSION,
        MAIN,
        AGENT,
        NAME,
        /** Nexus 显式扩展（CC 无此前缀）。 */
        NEXUS_SESSION
    }

    /** 分类结果。 */
    public record Parsed(Kind kind, String raw) {}

    /** {@code name [ref]} 解析结果；ref 可 null。 */
    public record ParsedName(String name, String ref) {}

    /** 短 hex 解析结果（CC {@code Sse}）。 */
    public record ParsedRef(String name, String ref) {}

    /** ref 派生输入：kind 至少覆盖 {@code session} / {@code agent}（CC 另有 teammate/main 等）。 */
    public record RefIdentity(String kind, String stableId) {}

    /** ref 最短长度（CC {@code Bc=6}）。 */
    public static final int MIN_REF_LEN = 6;

    /** ref 最长长度（CC {@code nxi=12}）。 */
    public static final int MAX_REF_LEN = 12;

    /** 哈希串长度：SHA-256 前 12 字节 = 24 hex（CC 的 {@code qn} 输出同量级）。 */
    private static final int HASH_HEX_LEN = 24;

    /** pin 失败原文（exe 356149269，{@code Su="ListAgents"} 已核实于偏移 319919001）。 */
    public static final String PIN_FAILURE = "Not sent: ListAgents has not listed that session id in this "
        + "session, or last listed it under another name. Call ListAgents and copy the name and brackets "
        + "as printed.";

    /**
     * 冒充失败原文（exe 355937466 {@code RKn}）。占位符 {@code 'X'} = 写入的地址串。
     */
    public static final String IMPERSONATION_FAILURE_TEMPLATE = "'X' is the name-and-ref token this "
        + "session advertises for ITSELF, yet a different session record on this machine claims it "
        + "\u2014 not sent. A record impersonating this session is suspicious: ask the user. "
        + "(If you meant yourself, there is no one to send to.)";

    private static final Pattern LOCAL_SESSION = Pattern.compile("^local_[0-9a-f-]{8,72}$");
    private static final Pattern BRIDGE_SESSION = Pattern.compile("^(session|cse)_[A-Za-z0-9_-]{1,100}$");
    private static final Pattern AGENT_ID = Pattern.compile("^a(?:[\\w-]{1,63}-)?[0-9a-f]{16}$");
    private static final Pattern NEXUS_SESSION = Pattern.compile("^sess-[0-9a-f]{8}$");

    /** CC {@code Hl}：pinned session/cse ref 精确形态。 */
    private static final Pattern PINNED_SESSION_REF =
        Pattern.compile("^(?:session|cse)_(?:staging_|local_)?01[1-9A-HJ-NP-Za-km-z]{22}$");

    /** CC {@code HUt}：local_ 锚点（与判类用的 LOCAL_SESSION 同源）。 */
    private static final Pattern PINNED_LOCAL_REF = Pattern.compile("^local_[0-9a-f-]{8,72}$");

    /** CC {@code $Ut} 的尾部匹配（`\S \[([\w-]+)\]$`，未锚定头部）。 */
    private static final Pattern PIN_REF_TAIL = Pattern.compile("\\S \\[([\\w-]+)]$");

    /** CC {@code Sse}：短 hex 解析器（6..12 位小写 hex）。 */
    private static final Pattern SHORT_REF = Pattern.compile("^(.*\\S)\\s*\\[([0-9a-f]{" + MIN_REF_LEN
        + "," + MAX_REF_LEN + "})]$");

    /** 兼容解析 {@code name [ref]}：短 hex 或 pinned 形态；其他（如 teammate 的 {@code -}）ref=null。 */
    private static final Pattern NAME_REF = Pattern.compile("^(.+?)\\s*\\[([0-9a-f]{" + MIN_REF_LEN + ","
        + MAX_REF_LEN + "}|(?:session|cse)_(?:staging_|local_)?01[1-9A-HJ-NP-Za-km-z]{22}"
        + "|local_[0-9a-f-]{8,72})]$");

    private PeerAddressing() {
    }

    /**
     * 按 CC 顺序分类。
     *
     * @param to 原始地址（可 null/空白）
     * @return 分类；null/空白归 NAME（由调用方校验并拒绝）
     */
    public static Parsed classify(String to) {
        String raw = to == null ? "" : to.trim();
        if (raw.isEmpty()) {
            return new Parsed(Kind.NAME, raw);
        }
        if (isSessionLike(raw)) {
            return new Parsed(Kind.SESSION, raw);
        }
        if ("main".equals(raw)) {
            return new Parsed(Kind.MAIN, raw);
        }
        if (AGENT_ID.matcher(raw).matches()) {
            return new Parsed(Kind.AGENT, raw);
        }
        if (NEXUS_SESSION.matcher(raw).matches()) {
            return new Parsed(Kind.NEXUS_SESSION, raw);
        }
        return new Parsed(Kind.NAME, raw);
    }

    /**
     * 是否 CC session 类地址（无论本机是否能处理）。
     *
     * @param to 地址
     * @return true = session 类
     */
    public static boolean isRemoteSessionAddress(String to) {
        String raw = to == null ? "" : to.trim();
        return isSessionLike(raw);
    }

    private static boolean isSessionLike(String raw) {
        return raw.startsWith("uds:")
                || raw.startsWith("bridge:")
                || raw.startsWith("did:")
                || raw.endsWith(".sock")
                || raw.contains("\\pipe\\")
                || raw.startsWith("\\\\.\\pipe\\")
                || LOCAL_SESSION.matcher(raw).matches()
                || BRIDGE_SESSION.matcher(raw).matches();
    }

    /**
     * 名字归一化（比较前）：trim + lower-case；空白折叠为 '-'。
     *
     * @param name 名称
     * @return 归一化名；null → ""
     */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    // ── ref 派生 ─────────────────────────────────────────────────────────────

    /**
     * 哈希串：{@code hex(qn("<kind>:<stableId>"))} 的前 24 位小写 hex。
     *
     * <p>CC {@code Hc} 对 {@code cloud-session} / {@code bridge-session} 统一改写成
     * {@code bridge-session:<id>} 参与哈希（同一 bridge 会话两种 kind 得到同一 ref），此处照抄。
     *
     * @param kind     ref 维度（至少 session / agent）
     * @param stableId 稳定 id（CC {@code stableId ?? id}）
     * @return 24 位小写 hex
     */
    public static String hashToken(String kind, String stableId) {
        String prefix = ("cloud-session".equals(kind) || "bridge-session".equals(kind))
            ? "bridge-session:" : String.valueOf(kind) + ":";
        String key = prefix + (stableId == null ? "" : stableId);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, HASH_HEX_LEN);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 单值 ref（CC {@code pUt} = 前 6 位）。pinned 形态的 session id 直接以 id 作方括号 ref
     * —— 等价 CC 的 bridge/远端 session 行（它们的方括号里是 session id，而不是短 hex）。
     *
     * @param kind     ref 维度
     * @param stableId 稳定 id
     * @return ref 串
     */
    public static String refFor(String kind, String stableId) {
        String pinned = pinnedAddressRef(kind, stableId);
        if (pinned != null) {
            return pinned;
        }
        return hashToken(kind, stableId).substring(0, MIN_REF_LEN);
    }

    /** sessionId → 6 hex ref（CC {@code pUt("session", sock)}）。 */
    public static String refFor(String sessionId) {
        return refFor("session", sessionId);
    }

    /**
     * 整页 ref 分配（CC {@code QX}）：对整页哈希排序，取与相邻哈希最长公共前缀 +1，
     * 下限 6、上限 12。pinned 形态的行固定用 id 本身，不占哈希池。
     *
     * @param entries 本页条目
     * @param extra   额外参与唯一性计算的条目（CC 会把本进程自身 session 的哈希一起排序）
     * @return 与 entries 一一对应的 ref
     */
    public static List<String> assignRefs(List<RefIdentity> entries, List<RefIdentity> extra) {
        List<RefIdentity> rows = entries == null ? List.of() : entries;
        List<String> out = new ArrayList<>(rows.size());
        List<String> hashes = new ArrayList<>();
        boolean[] pinned = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            RefIdentity identity = rows.get(i);
            String fixed = identity == null ? null
                : pinnedAddressRef(identity.kind(), identity.stableId());
            if (fixed != null) {
                pinned[i] = true;
                hashes.add(null);
            } else {
                hashes.add(hashToken(identity == null ? "" : identity.kind(),
                    identity == null ? "" : identity.stableId()));
            }
        }
        List<String> pool = new ArrayList<>(hashes.stream().filter(h -> h != null).toList());
        if (extra != null) {
            for (RefIdentity identity : extra) {
                String fixed = identity == null ? null
                    : pinnedAddressRef(identity.kind(), identity.stableId());
                if (fixed == null) {
                    pool.add(hashToken(identity == null ? "" : identity.kind(),
                        identity == null ? "" : identity.stableId()));
                }
            }
        }
        List<String> sorted = pool.stream().distinct().sorted().toList();
        Map<String, String> assigned = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            String hash = sorted.get(i);
            String prev = i > 0 ? sorted.get(i - 1) : null;
            String next = i + 1 < sorted.size() ? sorted.get(i + 1) : null;
            assigned.put(hash, hash.substring(0, refLength(hash, prev, next)));
        }
        for (int i = 0; i < rows.size(); i++) {
            if (pinned[i]) {
                out.add(rows.get(i).stableId());
            } else {
                out.add(assigned.get(hashes.get(i)));
            }
        }
        return out;
    }

    /** 便捷重载：不带额外条目。 */
    public static List<String> assignRefs(List<RefIdentity> entries) {
        return assignRefs(entries, List.of());
    }

    /**
     * 单个哈希的 ref 长度（CC {@code QX} 内层）：{@code min(hash.len, max(6, lcp+1))}，封顶 12。
     *
     * @param hash 当前哈希
     * @param prev 排序后的前一个哈希（可 null）
     * @param next 排序后的后一个哈希（可 null）
     * @return 长度 6..12
     */
    public static int refLength(String hash, String prev, String next) {
        int lcp = Math.max(prev == null ? 0 : commonPrefixLength(hash, prev),
            next == null ? 0 : commonPrefixLength(hash, next));
        int cap = Math.min(hash == null ? MIN_REF_LEN : hash.length(), MAX_REF_LEN);
        return Math.min(cap, Math.max(MIN_REF_LEN, lcp + 1));
    }

    /** 两串的公共前缀长度（CC {@code LD}）。 */
    public static int commonPrefixLength(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /**
     * 写入的 ref 是否匹配该身份（CC {@code t3}：{@code ref.length>=6 && hash.startsWith(ref)}；
     * pinned 形态则要求相等）。
     *
     * @param kind     ref 维度
     * @param stableId 稳定 id
     * @param ref      写入的方括号 ref（null/空白 = 不约束，true）
     * @return true = 命中
     */
    public static boolean refMatches(String kind, String stableId, String ref) {
        if (ref == null || ref.isBlank()) {
            return true;
        }
        String pinned = pinnedAddressRef(kind, stableId);
        if (pinned != null) {
            return pinned.equals(ref);
        }
        if (ref.length() < MIN_REF_LEN || ref.length() > MAX_REF_LEN) {
            return false;
        }
        return hashToken(kind, stableId).startsWith(ref);
    }

    // ── 两套解析器 ───────────────────────────────────────────────────────────

    /**
     * pin 解析器（CC {@code $Ut}）：方括号里必须是 {@code session_}/{@code cse_} 或 {@code local_} 形态。
     *
     * @param to 地址
     * @return pinned ref；非 pinned 形态 → null
     */
    public static String pinnedRef(String to) {
        String raw = to == null ? "" : to.trim();
        if (raw.isEmpty() || raw.startsWith("uds:") || raw.startsWith("bridge:")
            || raw.startsWith("did:")) {
            return null;
        }
        Matcher m = PIN_REF_TAIL.matcher(raw);
        if (!m.find()) {
            return null;
        }
        String ref = m.group(1);
        return isPinnedSessionRef(ref) || PINNED_LOCAL_REF.matcher(ref).matches() ? ref : null;
    }

    /** 短 hex 解析器（CC {@code Sse}）。 */
    public static ParsedRef parseShortRef(String to) {
        String raw = to == null ? "" : to.trim();
        Matcher m = SHORT_REF.matcher(raw);
        if (m.matches()) {
            return new ParsedRef(m.group(1), m.group(2));
        }
        return null;
    }

    /**
     * 写入地址的 ref 是否就是本进程自己广告的身份（CC {@code oxi(ref, ownMaskingSocket)}）。
     *
     * @param to            写入的地址（{@code name [ref]}）
     * @param ownSessionId  本进程自身 session id
     * @return true = 该 ref 命中自身身份
     */
    public static boolean advertisesSelf(String to, String ownSessionId) {
        if (ownSessionId == null || ownSessionId.isBlank()) {
            return false;
        }
        ParsedRef parsed = parseShortRef(to);
        return parsed != null && refMatches("session", ownSessionId, parsed.ref());
    }

    /** 冒充失败文案（CC {@code RKn}），{@code 'X'} 替换为写入的地址串。 */
    public static String impersonationFailure(String writtenTo) {
        return IMPERSONATION_FAILURE_TEMPLATE.replace("'X'", "'" + (writtenTo == null ? "" : writtenTo.trim()) + "'");
    }

    /** pinned session/cse ref 形态（CC {@code Hl}）。 */
    public static boolean isPinnedSessionRef(String ref) {
        return ref != null && PINNED_SESSION_REF.matcher(ref).matches();
    }

    /** pinned 身份是否以 id 本身作方括号 ref（本仓建模为 bridge/远端 session 行）。 */
    private static String pinnedAddressRef(String kind, String stableId) {
        if (!"session".equals(kind) || stableId == null) {
            return null;
        }
        return isPinnedSessionRef(stableId) || PINNED_LOCAL_REF.matcher(stableId).matches()
            ? stableId : null;
    }

    // ── 回填地址 ─────────────────────────────────────────────────────────────

    /**
     * 生成可回填 SendMessage 的地址：{@code name [ref]}。
     *
     * @param name      展示/地址名
     * @param sessionId 会话 id
     * @return 地址串
     */
    public static String formatAddress(String name, String sessionId) {
        String safe = name == null || name.isBlank() ? "session" : name.trim();
        return safe + " [" + refFor(sessionId) + "]";
    }

    /**
     * 解析 {@code name [ref]}；无 ref 时 ref=null。ref 可以是 6..12 位 hex（短 ref）或
     * pinned 形态（{@code session_}/{@code cse_}/{@code local_}）。
     *
     * @param to 地址
     * @return 解析结果（name 已 trim）
     */
    public static ParsedName parseNameWithRef(String to) {
        String raw = to == null ? "" : to.trim();
        Matcher m = NAME_REF.matcher(raw);
        if (m.matches()) {
            return new ParsedName(m.group(1).trim(), m.group(2));
        }
        return new ParsedName(raw, null);
    }
}
