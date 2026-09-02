package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Snip 压缩器（L2 层）· 对齐 CC 真源 snipCompact.ts（Open-ClaudeCode/src/services/compact/snipCompact.ts, 165 行）
 *
 * <p><b>算法（CC original: snipCompactIfNeeded, snipCompact.ts:83-147）</b>:
 * snip 不是「保留头尾 + 占位符」启发式（旧 Java head3+tail47 为独有推测，已删除），而是
 * <b>snip_boundary + removedUuids</b> 机制：
 * <ol>
 *   <li>倒扫找最后 subtype='snip_boundary' 的 system 消息（snipCompact.ts:96-109）；无 → executed=false</li>
 *   <li>读取该边界消息 snipMetadata.removedUuids（snipCompact.ts:103-106）</li>
 *   <li>无 removedUuids（缺失/空）→ 保留 boundary + 之后全部（snipCompact.ts:118-126，executed=true, tokensFreed=0）</li>
 *   <li>有 removedUuids → 按 uuid 过滤（boundary 自身保留），tokensFreed = Σ estimateMessageTokens(被移除消息)
 *       （snipCompact.ts:128-139）</li>
 * </ol>
 *
 * <p><b>返回形状 {messages, executed, tokensFreed, boundaryMessage?}</b>（snipCompact.ts:86-91）；
 * boundaryMessage = messages[boundaryIdx] <b>原样</b> yield（非凭空构造，snipCompact.ts:115）。
 *
 * <p><b>force 参数</b>（snipCompact.ts:85 {@code _options?.force}）：CC 声明但<b>从未使用</b>——
 * 无 size 门槛，boundary 存在即执行。两参变体 {@link #snipCompactIfNeeded(List, boolean)} 与单参
 * {@link #snipCompactIfNeeded(List)} 共享同一算法（force 忽略），仅返回形状裁剪为 {messages, executed}
 * （QueryEngine.ts:169-172 消费面）。
 *
 * <p><b>数据模型</b>: snip_boundary 判别 = {@code role()==system && "snip_boundary".equals(subtype())}
 * （CC type==='system' && subtype==='snip_boundary'，snipProjection.ts:15-18）；元数据承载在
 * ChatMessageDto.snipMetadata（结构 { removedUuids?: string[] }，snipCompact.ts:99-106）。
 */
public class SnipCompactor {

    private static final Logger log = LoggerFactory.getLogger(SnipCompactor.class);

    // ════════════════════════════════════════════════════════════════════
    // 常量 · 对齐 CC snipCompact.ts（模块本地，CC 均为 const / export const）
    // ════════════════════════════════════════════════════════════════════

    /** 估算每 token 字符数（保守，混合代码/文本）· CC original: CHARS_PER_TOKEN (snipCompact.ts:6) = 4 */
    private static final int CHARS_PER_TOKEN = 4;

    /** 消息数 nudge 阈值（CC 默认/最低档）· CC original: SNIP_NUDGE_THRESHOLD (snipCompact.ts:11) = 30 */
    private static final int SNIP_NUDGE_THRESHOLD = 30;

    // [V55 fix-transcript-nudge] 上下文窗口自适应档位（Java 扩展，CC 仅固定 30）。
    //   effectiveWindow = CompactThresholdSystem#getEffectiveContextWindowSize(model)
    //   （含 reserved 减法 + settings 收窄）。档位：≥800k → 150；>600k → 100；≥400k → 60；其他 → 30。
    /** 窗口档位 A 下限（effectiveWindow ≥ 800k → 阈值 150） */
    private static final int SNIP_NUDGE_WINDOW_800K = 800_000;
    /** 窗口档位 B 下限（effectiveWindow &gt; 600k → 阈值 100） */
    private static final int SNIP_NUDGE_WINDOW_600K = 600_000;
    /** 窗口档位 C 下限（effectiveWindow ≥ 400k → 阈值 60） */
    private static final int SNIP_NUDGE_WINDOW_400K = 400_000;
    /** 窗口档位 A 阈值（≥800k） */
    private static final int SNIP_NUDGE_THRESHOLD_TIER_150 = 150;
    /** 窗口档位 B 阈值（>600k 且 <800k） */
    private static final int SNIP_NUDGE_THRESHOLD_TIER_100 = 100;
    /** 窗口档位 C 阈值（≥400k 且 ≤600k） */
    private static final int SNIP_NUDGE_THRESHOLD_TIER_60 = 60;

    /**
     * nudge 提示文本 · CC original: SNIP_NUDGE_TEXT (snipCompact.ts:17-18)。
     *
     * <p>CC 导出常量，供「会话足够长时提示模型考虑 snip」使用（shouldNudgeForSnips 配套）。
     */
    public static final String SNIP_NUDGE_TEXT =
        "The conversation history is getting long. Consider using the /force-snip command or the snip tool to compress older messages, freeing context window space for continued work.";

    /** snip 边界 subtype · CC original: subtype 'snip_boundary' (snipCompact.ts:100 / snipProjection.ts:17) */
    public static final String SUBTYPE_SNIP_BOUNDARY = "snip_boundary";

    /** snip marker subtype · CC original: subtype 'snip_marker' (snipCompact.ts:27) */
    public static final String SUBTYPE_SNIP_MARKER = "snip_marker";

    /** snipMetadata 内 removedUuids 键名 · CC original: removedUuids (snipCompact.ts:99-103) */
    private static final String KEY_REMOVED_UUIDS = "removedUuids";

    /**
     * 派生消息短 id · 对齐 CCB messages.ts:201-206 {@code deriveShortMessageId}。
     *
     * <p>CCB 原算法：{@code hex = uuid.replaceAll("-","").slice(0,10);
     * parseInt(hex,16).toString(36).slice(0,6)} —— 6 位 base36。Java 的
     * {@code ChatMessageDto.id()} 可能是标准 UUID（turnAssistantId）或 {@code msg-xxx} 前缀形态，
     * 对非纯 hex id 先剥离非 hex 字符再取前 10 hex（CCB 假设入参恒标准 UUID，Java 需容错）。
     * 注入（[id:] tag）与匹配（SnipTool）共用同一算法，对称性保证模型传的短 id 能反解回消息。
     *
     * @param id {@code ChatMessageDto.id()}（可为 null）
     * @return 6 位短 id（对齐 CCB 长度；id 为 null/异常 → "?"）
     */
    public static String deriveShortMessageId(String id) {
        if (id == null || id.isBlank()) {
            return "?";
        }
        String hex = id.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]+")) {
            hex = hex.replaceAll("[^0-9a-fA-F]", "");
        }
        if (hex.isEmpty()) {
            hex = Integer.toHexString(id.hashCode());
        }
        if (hex.length() > 10) {
            hex = hex.substring(0, 10);
        }
        try {
            String base36 = Integer.toString(Integer.parseInt(hex, 16), 36);
            // 小 id（如 "msg-8c" → hex 短 → base36 不足 6 位）→ 左补零保持定长（注入与匹配
            // 同一算法，对称性不受影响；CCB 假设入参恒标准 UUID 恒 6 位，Java 需容错）。
            return base36.length() >= 6 ? base36.substring(0, 6) : leftPadZero(base36, 6);
        } catch (NumberFormatException e) {
            // fallback：hash 派生，长度对齐（注入与匹配同一算法，对称不破坏）
            String base36 = Integer.toString(id.hashCode() & 0xFFFFFF, 36);
            return base36.length() >= 6 ? base36.substring(0, 6) : leftPadZero(base36, 6);
        }
    }

    /** 左侧补 '0' 至指定长度（定长短 id 稳定输出）。 */
    private static String leftPadZero(String s, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = s.length(); i < len; i++) {
            sb.append('0');
        }
        sb.append(s);
        return sb.toString();
    }

    /**
     * 执行 Snip 压缩 · CC original: snipCompactIfNeeded (snipCompact.ts:83-147)
     *
     * <p>对齐 CC 真源算法（2026-08-18 探查结论）：head3+tail47 启发式为 Java 独有推测，已删除；
     * 真源机制 = snip_boundary + removedUuids（见类头 Javadoc）。
     *
     * @param messages 原始消息列表（可含 snip_boundary；null → 空列表 + executed=false）
     * @return CC 形状结果 {messages, executed, tokensFreed, boundaryMessage?}
     */
    public SnipResult snipCompactIfNeeded(List<ChatMessageDto> messages) {
        List<ChatMessageDto> list = messages != null ? messages : List.of();

        // 1. 倒扫找最后 subtype='snip_boundary' 的 system 消息（snipCompact.ts:96-109）
        int boundaryIdx = -1;
        List<String> removedUuids = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            ChatMessageDto msg = list.get(i);
            if (msg != null && isSnipBoundaryMessage(msg)) {
                boundaryIdx = i;
                Map<String, Object> meta = msg.snipMetadata();
                if (meta != null) {
                    removedUuids = readRemovedUuids(meta);
                }
                break;
            }
        }

        // 2. 无 boundary → 不执行（snipCompact.ts:111-113）
        if (boundaryIdx == -1) {
            if (log.isDebugEnabled()) {
                log.debug("[SnipCompactor] 无 snip_boundary 消息，跳过（executed=false）· CC snipCompact.ts:111-113");
            }
            return new SnipResult(list, false, 0, null);
        }

        ChatMessageDto boundaryMessage = list.get(boundaryIdx);

        // 3. 无 removedUuids → 保留 boundary + 之后全部（snipCompact.ts:118-126）
        if (removedUuids == null || removedUuids.isEmpty()) {
            List<ChatMessageDto> kept = new ArrayList<>(list.subList(boundaryIdx, list.size()));
            if (log.isDebugEnabled()) {
                log.debug("[SnipCompactor] boundary 存在但无 removedUuids，保留 boundary+之后 {} 条（executed=true）· CC snipCompact.ts:118-126",
                    kept.size());
            }
            log.info("[SnipCompactor] snip 执行（无 removedUuids 回退）: {} → {} 条消息, tokensFreed=0, boundary id={} · CC snipCompact.ts:118-126",
                list.size(), kept.size(), boundaryMessage.id());
            return new SnipResult(kept, true, 0, boundaryMessage);
        }

        // 4. 按 uuid 过滤（boundary 自身保留），tokensFreed = Σ estimateMessageTokens（snipCompact.ts:128-139）
        Set<String> removedSet = new HashSet<>(removedUuids);
        List<ChatMessageDto> kept = new ArrayList<>(list.size());
        int tokensFreed = 0;
        for (ChatMessageDto msg : list) {
            if (msg != null && removedSet.contains(msg.id())) {
                tokensFreed += estimateMessageTokens(msg);
                continue;
            }
            kept.add(msg);
        }

        if (log.isDebugEnabled()) {
            log.debug("[SnipCompactor] snip 完成: {} → {} 条消息, 移除 {} 条, freed={} tokens, boundary id={} · CC snipCompact.ts:128-139",
                list.size(), kept.size(), list.size() - kept.size(), tokensFreed, boundaryMessage.id());
        }
        log.info("[SnipCompactor] snip 执行: {} → {} 条消息, 释放 {} tokens · CC snipCompact.ts:83-147",
            list.size(), kept.size(), tokensFreed);
        return new SnipResult(kept, true, tokensFreed, boundaryMessage);
    }

    /**
     * 执行 Snip 重放压缩 · 对齐 CC QueryEngine.ts:1281 {@code snipCompactIfNeeded(store, { force: true })}
     *
     * <p><b>CC original: snipCompactIfNeeded (snipCompact.ts:83-147)</b> —— 两参为同一函数的 options
     * 变体；{@code _options?.force}（snipCompact.ts:85）CC <b>声明但从未使用</b>：无 size 门槛，
     * boundary 存在即执行。因此两参变体与单参 {@link #snipCompactIfNeeded(List)} 共享同一算法
     * （force 忽略），仅返回形状裁剪为 {messages, executed}（QueryEngine.ts:169-172 消费面；
     * 消费点 QueryEngine.ts:909-913 仅 executed=true 时替换 store）。
     *
     * @param store 待重放压缩的消息 store（CC mutableMessages 等价）
     * @param force CC 声明但从未使用（snipCompact.ts:85）；保留参数以对齐 QueryEngine.ts:1281 引用面
     * @return CC 形状结果（messages + executed；executed=false 时调用方不替换 store）
     */
    public SnipReplayResult snipCompactIfNeeded(List<ChatMessageDto> store, boolean force) {
        SnipResult result = snipCompactIfNeeded(store);
        if (log.isDebugEnabled()) {
            log.debug("[SnipCompactor] snipReplay 重放: executed={}, {} → {} 条消息（force={} 未使用，CC snipCompact.ts:85）",
                result.executed(), store != null ? store.size() : 0, result.messages().size(), force);
        }
        return new SnipReplayResult(result.messages(), result.executed());
    }

    /**
     * 判别 snip 边界消息 · CC original: isSnipBoundaryMessage (snipProjection.ts:15-18)
     *
     * <p>CC: {@code message.type === 'system' && subtype === 'snip_boundary'}；Java 端 type 映射
     * role()==system，判别条件 = {@code subtype()=='snip_boundary' && role()==system}。
     */
    private static boolean isSnipBoundaryMessage(ChatMessageDto message) {
        return message.role() == Role.system && SUBTYPE_SNIP_BOUNDARY.equals(message.subtype());
    }

    /**
     * 判别 snip marker 消息 · CC original: isSnipMarkerMessage (snipCompact.ts:25-28)
     *
     * <p>CC: {@code type === 'system' && subtype === 'snip_marker'}（snip 工具注入的内部标记，
     * 非用户可见）。null → false（防御）。
     */
    public static boolean isSnipMarkerMessage(ChatMessageDto message) {
        if (message == null) {
            return false;
        }
        return message.role() == Role.system && SUBTYPE_SNIP_MARKER.equals(message.subtype());
    }

    /**
     * snip 运行时是否启用 · CC original: isSnipRuntimeEnabled (snipCompact.ts:154-156)
     *
     * <p>CC: 模块只在 HISTORY_SNIP feature flag 下加载，故恒返回 true。
     */
    public static boolean isSnipRuntimeEnabled() {
        return true;
    }

    /**
     * 是否应提示模型考虑 snip · CC original: shouldNudgeForSnips (snipCompact.ts:163-165)
     *
     * <p>CC: {@code messages.length >= SNIP_NUDGE_THRESHOLD(30)}（简单消息数阈值，非昂贵 token 估算）。
     * 单参变体 = CC 默认阈值 30（保留 CC 原语义，供未接入窗口自适应的调用方/单测）。
     */
    public static boolean shouldNudgeForSnips(List<ChatMessageDto> messages) {
        return shouldNudgeForSnips(messages, SNIP_NUDGE_THRESHOLD);
    }

    /**
     * 是否应提示模型考虑 snip（带显式阈值）· CC original: shouldNudgeForSnips (snipCompact.ts:163-165)
     *
     * <p>显式阈值变体：nudge 触发点（AgentLoopContext.maybeInjectContextEfficiencyNudge 门 4）经
     * {@link #resolveSnipNudgeThreshold} 计算最终阈值（DB settings.snip_nudge_threshold &gt; 0
     * 直接覆盖；否则按 effectiveWindow 窗口自适应档位）后传入。
     *
     * @param messages 消息列表（null → false）
     * @param threshold 生效的消息数阈值（≥1）
     * @return messages.size() >= threshold
     */
    public static boolean shouldNudgeForSnips(List<ChatMessageDto> messages, int threshold) {
        return messages != null && messages.size() >= threshold;
    }

    /**
     * 解析 snip nudge 最终消息数阈值 · [V55 fix-transcript-nudge] DB settings 可配 + 窗口自适应。
     *
     * <p><b>优先级</b>:
     * <ol>
     *   <li>DB settings.snip_nudge_threshold &gt; 0 → 直接覆盖（用户显式配置优先，前端「环境配置」可配）</li>
     *   <li>null / ≤ 0 → 按 effectiveWindow 窗口自适应档位回落：
     *       ≥800k → 150；&gt;600k → 100；≥400k → 60；其他 → 30（CC 默认，snipCompact.ts:11）</li>
     * </ol>
     * effectiveWindow 为 0 / 负数（阈值系统未接线、单测、无 bean）→ 回落 30（CC 默认，零行为变化）。
     *
     * @param dbValue        DB settings.snip_nudge_threshold（null = 未配置；≤0 视为未配置）
     * @param effectiveWindow 有效上下文窗口（CompactThresholdSystem#getEffectiveContextWindowSize）
     * @return 最终 nudge 阈值（≥1）
     */
    public static int resolveSnipNudgeThreshold(Integer dbValue, int effectiveWindow) {
        if (dbValue != null && dbValue > 0) {
            if (log.isDebugEnabled()) {
                log.debug("[SnipCompactor] nudge 阈值取 DB 配置: dbValue={}（直接覆盖窗口自适应）", dbValue);
            }
            return dbValue;
        }
        int threshold;
        if (effectiveWindow >= SNIP_NUDGE_WINDOW_800K) {
            threshold = SNIP_NUDGE_THRESHOLD_TIER_150;
        } else if (effectiveWindow > SNIP_NUDGE_WINDOW_600K) {
            threshold = SNIP_NUDGE_THRESHOLD_TIER_100;
        } else if (effectiveWindow >= SNIP_NUDGE_WINDOW_400K) {
            threshold = SNIP_NUDGE_THRESHOLD_TIER_60;
        } else {
            threshold = SNIP_NUDGE_THRESHOLD;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SnipCompactor] nudge 阈值窗口自适应: effectiveWindow={} → 阈值{}（DB=null 回落，CC 默认 {}）",
                effectiveWindow, threshold, SNIP_NUDGE_THRESHOLD);
        }
        return threshold;
    }

    /**
     * 是否应对消息列表执行 snip · 语义对齐 CC snipCompactIfNeeded 前置条件（snipCompact.ts:96-109）
     *
     * <p>CC 无独立 shouldSnip 导出；Java 端 {@link com.nexusai.application.agent.loop.ContextCollapse#recoverFromOverflow}
     * 预检使用（ContextCollapse.java:162）。真源语义 = 「存在 snip_boundary 消息」即应 snip
     * （boundary 存在即执行，无 size 门槛）。
     */
    public static boolean shouldSnip(List<ChatMessageDto> messages) {
        if (messages == null) {
            return false;
        }
        for (ChatMessageDto msg : messages) {
            if (msg != null && isSnipBoundaryMessage(msg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 估算单条消息 token 数（序列化 content 的粗略启发式，~4 字符/token）· CC original:
     * estimateMessageTokens (snipCompact.ts:35-58)
     *
     * <p>CC 逻辑（snipCompact.ts:36-57）:
     * <ul>
     *   <li>content 为 string → chars = content.length</li>
     *   <li>content 为数组 → 逐 block：string→length；对象→优先 {@code text ?? content} 字段长度，
     *       否则 JSON 序列化长度</li>
     *   <li>content 为 null/undefined → chars = 0</li>
     * </ul>
     * 返回 {@code max(1, ceil(chars / CHARS_PER_TOKEN))}（snipCompact.ts:57）。
     *
     * <p>Java 映射：ChatMessageDto.content（String）对应 CC string content；contentBlocks（List<JsonNode>）
     * 对应 CC content 数组；两者均空 → 返回 1（CC content undefined → max(1, 0)=1）。
     *
     * @param message 待估算消息
     * @return 估算 token 数（≥1）
     */
    private static int estimateMessageTokens(ChatMessageDto message) {
        int chars = 0;
        String content = message.content();
        if (content != null) {
            chars += content.length();
        } else {
            List<?> blocks = message.contentBlocks();
            if (blocks != null && !blocks.isEmpty()) {
                for (Object block : blocks) {
                    if (block instanceof String s) {
                        chars += s.length();
                    } else if (block instanceof JsonNode node) {
                        chars += estimateJsonBlockChars(node);
                    } else {
                        chars += String.valueOf(block).length();
                    }
                }
            }
        }
        int tokens = Math.max(1, (int) Math.ceil(chars / (double) CHARS_PER_TOKEN));
        if (log.isDebugEnabled()) {
            log.debug("[SnipCompactor] estimateMessageTokens: id={} chars={} tokens={} · CC snipCompact.ts:35-58",
                message.id(), chars, tokens);
        }
        return tokens;
    }

    /**
     * 估算单个 content block 的字符数 · CC original: estimateMessageTokens 数组分支 (snipCompact.ts:41-53)
     *
     * <p>CC: string block → length；object block → 优先 {@code text ?? content} 字符串字段长度，
     * 否则 JSON.stringify(block).length；其他（标量）→ JSON 序列化长度。
     *
     * @param node Jackson 表示的 content block
     * @return 该 block 估算字符数
     */
    private static int estimateJsonBlockChars(JsonNode node) {
        if (node.isTextual()) {
            return node.asText().length();
        }
        if (node.isObject()) {
            JsonNode text = node.get("text");
            if (text == null || !text.isTextual()) {
                text = node.get("content");
            }
            if (text != null && text.isTextual()) {
                return text.asText().length();
            }
        }
        return node.toString().length();
    }

    /**
     * 从 snipMetadata 提取 removedUuids 列表 · CC original: meta?.removedUuids (snipCompact.ts:103-106)
     *
     * <p>snipMetadata 结构 { removedUuids?: string[] }（snipProjection.ts:31）；缺键/非列表 → null
     * （CC {@code meta?.removedUuids} → undefined → 走「无 removedUuids」回退分支 snipCompact.ts:118）。
     *
     * @param snipMetadata 边界消息的 snipMetadata（非 null）
     * @return removedUuids 字符串列表（空列表 = 键存在但无字符串元素）；结构缺失 → null
     */
    private static List<String> readRemovedUuids(Map<String, Object> snipMetadata) {
        Object removed = snipMetadata.get(KEY_REMOVED_UUIDS);
        if (removed instanceof List<?> list) {
            List<String> uuids = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof String s) {
                    uuids.add(s);
                }
            }
            return uuids;
        }
        if (removed instanceof JsonNode node && node.isArray()) {
            List<String> uuids = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    uuids.add(item.asText());
                }
            }
            return uuids;
        }
        return null;
    }

    /**
     * Snip 结果 · 对齐 CC {@code snipCompactIfNeeded} 返回形状（snipCompact.ts:86-91）
     *
     * <p>形状 {messages, executed, tokensFreed, boundaryMessage?}，字段顺序对齐 CC 真源
     * （snipCompact.ts:87-90）；调用方读取顺序 query.ts:404-408（messages → tokensFreed → boundaryMessage），
     * executed 为 CC 真源判别字段（snipCompact.ts:88）。
     *
     * @param messages        snip 后消息列表（未执行时与入参同引用）
     * @param executed        是否执行了 snip（boundary 存在即执行，snipCompact.ts:88）
     * @param tokensFreed     释放的 token 数（removedUuids 缺失/空 → 0）
     * @param boundaryMessage snip 边界消息（messages[boundaryIdx] 原样，非凭空构造；未执行时 null）
     */
    public record SnipResult(
        List<ChatMessageDto> messages,
        boolean executed,
        int tokensFreed,
        ChatMessageDto boundaryMessage
    ) {
        public SnipResult {
            if (messages == null) {
                throw new IllegalArgumentException("SnipResult.messages is null");
            }
        }
    }

    /**
     * Snip 重放结果 · 对齐 CC {@code snipReplay} 返回形状 {messages, executed}
     * （QueryEngine.ts:169-172，消费点 QueryEngine.ts:909-913 仅 executed=true 时以 messages 替换 store）。
     *
     * @param messages 重放压缩后的消息（未执行时与入参 store 同引用）
     * @param executed 是否执行了压缩（CC QueryEngine.ts:910 判别依据）
     */
    public record SnipReplayResult(
        List<ChatMessageDto> messages,
        boolean executed
    ) {
        public SnipReplayResult {
            if (messages == null) {
                throw new IllegalArgumentException("SnipReplayResult.messages is null");
            }
        }
    }
}
