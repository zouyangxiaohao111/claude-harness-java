package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 观察存储 · <b>逐条对齐 dsh {@code dsh-computer-use/src/service.ts} 的观察模型</b>。
 *
 * <h2>dsh 的原始语义（本类按此实现，不简化）</h2>
 * <ol>
 *   <li><b>observationId</b>：每次 {@code observe} 生成一个新的 UUID。
 *       <b>元素序号只属于那一次观察</b>（{@code service.ts:359}
 *       "Element indexes belong only to the returned observationId"）。</li>
 *   <li><b>observationTtlMs</b>：默认 <b>0 = 永不过期</b>（{@code config.ts:87}
 *       {@code z.number().default(0)}，{@code service.ts:787} {@code === 0 ? '9999-12-31' : ...}）。</li>
 *   <li><b>容量</b>：每个 agent 最多保留 <b>64</b> 条观察，超出按<b>插入顺序</b>淘汰最旧
 *       （{@code service.ts:799-803}）。</li>
 *   <li><b>generation</b>：后端代际变化（provider 重建）时全部观察作废
 *       （{@code service.ts:718}）。本仓 JXA 无长连接 ⇒ 恒为 1，但保留该字段以便未来对齐。</li>
 *   <li><b>latestByApp</b>：按 {@code bundleId:pid} 记「最近一次观察」，供 diff 模式取前值
 *       （{@code service.ts:763}）。</li>
 *   <li><b>targetHandle</b>：每个元素额外发一个不透明 UUID；元素按 locator 绑定，
 *       {@code allowRebind} 时用它做「同进程同窗口内的确定性重绑」
 *       （{@code service.ts:454-478}）。</li>
 *   <li><b>stateHash</b>：整棵树的状态哈希；动作前后端重读树并比对，
 *       不一致即拒绝（{@code COMPUTER_STALE_OBSERVATION}）。</li>
 * </ol>
 *
 * <p><b>会话隔离</b>：dsh 按 {@code agent} 分桶（{@code state(agent)}）；本仓按
 * {@code sessionId} 分桶（与 {@link ComputerUseGeometryStore} 同款约定）。
 */
public final class ComputerUseUiSnapshotStore {

    /** 无 sessionId 时的兜底桶 key。 */
    public static final String GLOBAL_KEY = "__global__";

    /** 每个会话最多保留的观察条数 · dsh {@code service.ts:799}（{@code > 64}）。 */
    public static final int MAX_OBSERVATIONS = 64;

    /** 已失效观察的错误码（与 dsh 同名，便于对照）。 */
    public static final String ERR_STALE_OBSERVATION = "COMPUTER_STALE_OBSERVATION";
    /** 元素不在该观察内的错误码。 */
    public static final String ERR_ELEMENT_UNAVAILABLE = "COMPUTER_ELEMENT_UNAVAILABLE";
    /** targetHandle 不可用 / 与 index 冲突的错误码。 */
    public static final String ERR_TARGET_UNAVAILABLE = "COMPUTER_TARGET_UNAVAILABLE";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 观察内的一个元素（对齐 dsh {@code elementSchema}）。
     *
     * @param index        观察内先序序号
     * @param targetHandle 不透明句柄（UUID）
     * @param locator      provider 定位键（macOS = 子节点路径；Windows = AutomationId）
     * @param role         角色
     * @param name         可访问名
     * @param value        当前值（可空）
     * @param enabled      是否可用（可空 = 未知）
     * @param actions      支持的动作
     * @param x            屏幕 X（0 = 未知）
     * @param y            屏幕 Y
     * @param width        宽（0 = 未知）
     * @param height       高
     */
    public record ObservedElement(int index,
                                  String targetHandle,
                                  String locator,
                                  String role,
                                  String name,
                                  String value,
                                  Boolean enabled,
                                  List<String> actions,
                                  int x,
                                  int y,
                                  int width,
                                  int height,
                                  String subrole,
                                  String nativeIdentifier,
                                  Boolean focused,
                                  Boolean selected) {

        public ObservedElement {
            targetHandle = targetHandle == null ? "" : targetHandle;
            locator = locator == null ? "" : locator;
            role = role == null ? "" : role;
            name = name == null ? "" : name;
            actions = actions == null ? List.of() : List.copyOf(actions);
            subrole = subrole == null ? "" : subrole;
            nativeIdentifier = nativeIdentifier == null ? "" : nativeIdentifier;
        }

        /**
         * 兼容构造（无 subrole / nativeIdentifier）。
         *
         * @param index        序号
         * @param targetHandle 句柄
         * @param locator      定位路径
         * @param role         角色
         * @param name         名字
         * @param value        值
         * @param enabled      是否可用
         * @param actions      动作集
         * @param x            屏幕 X
         * @param y            屏幕 Y
         * @param width        宽
         * @param height       高
         */
        public ObservedElement(int index,
                               String targetHandle,
                               String locator,
                               String role,
                               String name,
                               String value,
                               Boolean enabled,
                               List<String> actions,
                               int x,
                               int y,
                               int width,
                               int height) {
            this(index, targetHandle, locator, role, name, value, enabled, actions,
                    x, y, width, height, "", "");
        }

        /**
         * 兼容构造（有 subrole / nativeIdentifier，无 focused / selected）。
         *
         * @param index            序号
         * @param targetHandle     句柄
         * @param locator          定位路径
         * @param role             角色
         * @param name             名字
         * @param value            值
         * @param enabled          是否可用
         * @param actions          动作集
         * @param x                屏幕 X
         * @param y                屏幕 Y
         * @param width            宽
         * @param height           高
         * @param subrole          子角色
         * @param nativeIdentifier 原生标识
         */
        public ObservedElement(int index,
                               String targetHandle,
                               String locator,
                               String role,
                               String name,
                               String value,
                               Boolean enabled,
                               List<String> actions,
                               int x,
                               int y,
                               int width,
                               int height,
                               String subrole,
                               String nativeIdentifier) {
            this(index, targetHandle, locator, role, name, value, enabled, actions,
                    x, y, width, height, subrole, nativeIdentifier, null, null);
        }

        /** @return 是否有真实屏幕矩形 */
        public boolean hasBounds() {
            return width > 0 && height > 0;
        }
    }

    /**
     * 一条观察（对齐 dsh {@code ComputerObservation} + 内部 {@code StoredObservation}）。
     *
     * @param observationId 观察 id（UUID）
     * @param sessionId     会话 id
     * @param appName       应用显示名
     * @param bundleId      bundle id
     * @param pid           进程 id
     * @param windowTitle   窗口标题（可空）
     * @param createdAt     创建时刻（epoch 毫秒）
     * @param expiresAt     过期时刻（epoch 毫秒；{@link Long#MAX_VALUE} = 永不过期）
     * @param stateHash     整树状态哈希
     * @param treeMode      树模式（{@code full} / {@code diff}）
     * @param treeText      给模型看的树文本
     * @param truncated     是否被截断
     * @param elements      元素表（先序）
     * @param generation    后端代际
     */
    public record Observation(String observationId,
                              String sessionId,
                              String appName,
                              String bundleId,
                              long pid,
                              String windowTitle,
                              long createdAt,
                              long expiresAt,
                              String stateHash,
                              String treeMode,
                              String treeText,
                              boolean truncated,
                              List<ObservedElement> elements,
                              int generation,
                              long windowId,
                              int windowX,
                              int windowY) {

        /**
         * 兼容构造（无窗口几何）· 既有调用点不变（窗口号/原点按 0 处理 ⇒ 不定向）。
         */
        public Observation(String observationId, String sessionId, String appName, String bundleId,
                           long pid, String windowTitle, long createdAt, long expiresAt,
                           String stateHash, String treeMode, String treeText, boolean truncated,
                           List<ObservedElement> elements, int generation) {
            this(observationId, sessionId, appName, bundleId, pid, windowTitle, createdAt, expiresAt,
                    stateHash, treeMode, treeText, truncated, elements, generation, 0L, 0, 0);
        }

        /** @return 是否有可定向投递的窗口信息（窗口号 + 原点齐全） */
        public boolean hasWindowTarget() {
            return windowId > 0L;
        }

        public Observation {
            appName = appName == null ? "" : appName;
            bundleId = bundleId == null ? "" : bundleId;
            windowTitle = windowTitle == null ? "" : windowTitle;
            stateHash = stateHash == null ? "" : stateHash;
            treeMode = treeMode == null ? "full" : treeMode;
            treeText = treeText == null ? "" : treeText;
            elements = elements == null ? List.of() : List.copyOf(elements);
        }

        /** @return 是否已过期 */
        public boolean expired(long now) {
            return expiresAt != Long.MAX_VALUE && expiresAt <= now;
        }

        /**
         * 按序号取元素 · dsh {@code actNow} 的 {@code elements.find(candidate => candidate.index === index)}。
         *
         * @param index 序号
         * @return 元素；不存在 ⇒ null
         */
        public ObservedElement elementAt(int index) {
            for (ObservedElement element : elements) {
                if (element.index() == index) {
                    return element;
                }
            }
            return null;
        }

        /**
         * 按 handle 取元素 · dsh {@code stored.targets.get(handle)}。
         *
         * @param handle 句柄
         * @return 元素；未知 ⇒ null
         */
        public ObservedElement elementByHandle(String handle) {
            if (handle == null || handle.isEmpty()) {
                return null;
            }
            for (ObservedElement element : elements) {
                if (handle.equals(element.targetHandle())) {
                    return element;
                }
            }
            return null;
        }

        /**
         * 按 locator 取元素（rebind 证据校验用）·
         * dsh {@code candidate.locator.every(...)}。
         *
         * @param locator provider 定位键
         * @return 元素；不存在 ⇒ null
         */
        public ObservedElement elementByLocator(String locator) {
            if (locator == null || locator.isEmpty()) {
                return null;
            }
            for (ObservedElement element : elements) {
                if (locator.equals(element.locator())) {
                    return element;
                }
            }
            return null;
        }
    }

    /** 一个会话的观察桶（对齐 dsh {@code AgentState}）。 */
    private static final class Bucket {
        /** 观察（<b>有序</b>：淘汰按插入顺序取最旧）。 */
        final Map<String, Observation> observations = new LinkedHashMap<>();
        /** bundleId:pid → 最近一次 observationId（diff 取前值用）。 */
        final Map<String, String> latestByApp = new LinkedHashMap<>();
        /** 后端代际（变化 ⇒ 全部作废）。 */
        int generation = 1;
    }

    private final Map<String, Bucket> bySession = new ConcurrentHashMap<>();

    /** 观察有效期（毫秒；0 = 永不过期，对齐 dsh 默认值）。 */
    private final long observationTtlMs;

    /** 生产构造器：TTL = 0（永不过期，dsh 默认）。 */
    public ComputerUseUiSnapshotStore() {
        this(0L);
    }

    /**
     * @param observationTtlMs 观察有效期毫秒（0 = 永不过期）
     */
    public ComputerUseUiSnapshotStore(long observationTtlMs) {
        this.observationTtlMs = Math.max(0L, observationTtlMs);
    }

    /**
     * 新建一条观察并登记（对齐 dsh {@code capture()} 的落库段）。
     *
     * @param sessionId    会话 id
     * @param appName      应用显示名
     * @param bundleId     bundle id
     * @param pid          进程 id
     * @param windowTitle  窗口标题
     * @param stateHash    整树状态哈希
     * @param treeText     树文本
     * @param truncated    是否截断
     * @param elements     元素表
     * @param full         是否输出完整树（false ⇒ diff）
     * @return 新观察
     */
    public Observation record(String sessionId,
                              String appName,
                              String bundleId,
                              long pid,
                              String windowTitle,
                              String stateHash,
                              String treeText,
                              boolean truncated,
                              List<ObservedElement> elements,
                              boolean full) {
        return record(sessionId, appName, bundleId, pid, windowTitle, stateHash, treeText,
                truncated, elements, full, 0L, 0, 0);
    }

    /**
     * 登记一次观察 · 带窗口几何（供定向投递用：滚轮/拖拽要知道「投给哪个窗口的哪个点」）。
     *
     * @param windowId 窗口号（{@code CGWindowList} 的 kCGWindowNumber；0 = 未知）
     * @param windowX  窗口原点屏幕 X（点）
     * @param windowY  窗口原点屏幕 Y（点）
     * @return 新观察
     */
    public Observation record(String sessionId,
                              String appName,
                              String bundleId,
                              long pid,
                              String windowTitle,
                              String stateHash,
                              String treeText,
                              boolean truncated,
                              List<ObservedElement> elements,
                              boolean full,
                              long windowId,
                              int windowX,
                              int windowY) {
        Bucket bucket = bucket(sessionId);
        prune(bucket);
        long now = System.currentTimeMillis();
        String observationId = UUID.randomUUID().toString();
        String appKey = appKey(bundleId, pid);
        String previousId = bucket.latestByApp.get(appKey);
        Observation previous = previousId == null ? null : bucket.observations.get(previousId);

        boolean fullTree = full || previous == null;
        String text = fullTree
                ? treeText
                : diffElements(previous.elements(), elements);
        String mode = fullTree ? "full" : "diff";

        Observation observation = new Observation(
                observationId,
                sessionId,
                appName,
                bundleId,
                pid,
                windowTitle,
                now,
                observationTtlMs == 0 ? Long.MAX_VALUE : now + observationTtlMs,
                stateHash,
                mode,
                text,
                truncated,
                elements,
                bucket.generation,
                windowId,
                windowX,
                windowY);

        bucket.observations.put(observationId, observation);
        bucket.latestByApp.put(appKey, observationId);
        // dsh service.ts:799-803 —— 超出 64 条按插入顺序淘汰最旧
        while (bucket.observations.size() > MAX_OBSERVATIONS) {
            Iterator<String> it = bucket.observations.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            String oldest = it.next();
            it.remove();
            bucket.latestByApp.values().removeIf(oldest::equals);
        }
        return observation;
    }

    /**
     * 取一条观察 · <b>过期 / 未知 / 代际不符 ⇒ null</b>
     * （dsh {@code requireObservation} / {@code prune}）。
     *
     * @param sessionId     会话 id
     * @param observationId 观察 id
     * @return 观察；不可用 ⇒ null
     */
    public Observation require(String sessionId, String observationId) {
        if (observationId == null || observationId.isEmpty()) {
            return null;
        }
        Bucket bucket = bucketOrNull(sessionId);
        if (bucket == null) {
            return null;
        }
        prune(bucket);
        Observation observation = bucket.observations.get(observationId);
        if (observation == null) {
            return null;
        }
        if (observation.expired(System.currentTimeMillis())) {
            bucket.observations.remove(observationId);
            return null;
        }
        if (observation.generation() != bucket.generation) {
            return null;
        }
        return observation;
    }

    /**
     * 取某会话最近一次观察（诊断 / 兼容用）。
     *
     * @param sessionId 会话 id
     * @return 观察；无则 null
     */
    public Observation latest(String sessionId) {
        Bucket bucket = bucketOrNull(sessionId);
        if (bucket == null) {
            return null;
        }
        prune(bucket);
        Observation last = null;
        for (Observation observation : bucket.observations.values()) {
            last = observation;
        }
        return last;
    }

    /**
     * 让某会话全部观察作废（后端代际变化时调用）。
     *
     * @param sessionId 会话 id
     */
    public void invalidateAll(String sessionId) {
        Bucket bucket = bucketOrNull(sessionId);
        if (bucket == null) {
            return;
        }
        bucket.generation++;
        bucket.observations.clear();
        bucket.latestByApp.clear();
    }

    /**
     * 清空某会话。
     *
     * @param sessionId 会话 id
     */
    public void clear(String sessionId) {
        bySession.remove(key(sessionId));
    }

    // ══════════════════════════════════════════════════════════════════
    // 树文本差分（对齐 dsh 的 diff 模式语义：只报变化行）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 元素表差分 · 只输出新增 / 变化 / 消失的行。
     *
     * <p>dsh 的 {@code diffElements(previous.elements, elements, maxTextBytes)} 语义：
     * 树没变时给模型的东西应尽量小（模型刚从上次观察拿到过完整树）。
     *
     * <p><b>WHY 按「语义身份」而不是「位置」对齐</b>（对齐 dsh {@code src/diff.ts:7-13}）：
     * dsh 的 identity = {@code role|subrole|title|label|frame}，
     * 即<b>同一个控件</b>哪怕在树里换了位置，仍被认作「同一个，只是变了点东西」；
     * 而按 locator（位置）对齐时，界面轻微重排就会把每个元素都报成「变了」，
     * 模型被告知「到处都变了」却拿不到任何有用信息。
     * <br>⇒ 本实现的 identity = {@code role|subrole|name}（macOS 拿不到坐标 ⇒ 无 frame 项；
     * dsh 在 frame 缺失时同样退化为「两者都缺 ⇒ 视为相同」）。
     * <br>⭐ <b>与三级解析同源</b>：{@link ComputerUseTargetResolver} 的稳定字段也是
     * role + subrole + name + actions ⇒ diff 与 rebind 用同一套身份语义，不会出现
     * 「diff 说没变、rebind 却说变了」这种自相矛盾。
     *
     * @param before 前一次的元素
     * @param after  本次的元素
     * @return 差异文本（无变化 ⇒ 一行说明）
     */
    static String diffElements(List<ObservedElement> before, List<ObservedElement> after) {
        Map<String, ObservedElement> oldByIdentity = new LinkedHashMap<>();
        for (ObservedElement element : before) {
            oldByIdentity.put(identityOf(element), element);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        for (ObservedElement element : after) {
            String identity = identityOf(element);
            seen.add(identity);
            ObservedElement old = oldByIdentity.get(identity);
            if (old == null) {
                lines.add("+ [#" + element.index() + "] " + describeElement(element));
            } else if (!sameElement(old, element)) {
                lines.add("~ [#" + element.index() + "] " + describeElement(element));
            }
        }
        for (ObservedElement old : before) {
            if (!seen.contains(identityOf(old))) {
                lines.add("- [#" + old.index() + "] " + describeElement(old));
            }
        }
        return lines.isEmpty() ? "(no accessibility changes since the previous observation)" : String.join("\n", lines);
    }

    /**
     * 元素的<b>语义身份</b>（对齐 dsh {@code src/diff.ts:7-13} 的 {@code identity}）。
     *
     * <p>dsh = {@code role|subrole|title|label|frame}；本仓 macOS 拿不到坐标 ⇒ 省略 frame；
     * dsh 的 {@code title}/{@code label} 在本仓合并为 {@code name}（可访问名）。
     *
     * @param element 元素
     * @return 身份键
     */
    static String identityOf(ObservedElement element) {
        return element.role() + "\u0001" + element.subrole() + "\u0001" + element.name();
    }

    private static boolean sameElement(ObservedElement a, ObservedElement b) {
        return java.util.Objects.equals(a.value(), b.value())
                && java.util.Objects.equals(a.enabled(), b.enabled())
                && java.util.Objects.equals(a.focused(), b.focused())
                && java.util.Objects.equals(a.selected(), b.selected())
                && a.actions().equals(b.actions());
    }

    /** 单元素单行描述（`[#N] [Role] "name" value="..." (actions)`）。 */
    public static String describeElement(ObservedElement element) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(element.role()).append(']');
        if (!element.name().isEmpty()) {
            sb.append(" \"").append(ComputerUseAccessibilitySnapshot.truncate(
                    element.name(), ComputerUseAccessibilitySnapshot.NAME_MAX)).append('"');
        }
        if (element.value() != null && !element.value().isEmpty()) {
            sb.append(" value=\"").append(ComputerUseAccessibilitySnapshot.truncate(
                    element.value(), ComputerUseAccessibilitySnapshot.VALUE_MAX)).append('"');
        }
        if (Boolean.FALSE.equals(element.enabled())) {
            sb.append(" DISABLED");
        }
        if (!element.actions().isEmpty()) {
            sb.append(" (").append(String.join(",", element.actions())).append(')');
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /**
     * 文本指纹（SHA-256 前 32 位十六进制）。
     *
     * <p><b>⚠️ 不可用于 AX 树的状态比对</b>：macOS 的动作侧陈旧校验（dsh
     * {@code expectedStateHash}）在 <b>JXA 进程内</b>复算，用的是
     * {@link MacOsJxa#axHashJsFunction()}（FNV-1a）作用于<b>元素签名序列</b>。
     * 本方法算的是「渲染后文本」的 SHA-256 —— 输入与算法<b>都不同构</b>，
     * 两者永远不相等 ⇒ 用它做比对会让每次动作都被误判为「树已变」而拒绝。
     * <br>⇒ AX 树指纹只能来自 JXA（{@code MacOsUiaService} 透传 {@code stateHash} 字段）。
     *
     * @param text 文本
     * @return 指纹
     */
    public static String textFingerprint(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "len:" + (text == null ? 0 : text.length());
        }
    }

    /** 元素表 → JSON（供工具输出，对齐 dsh 的 {@code elements} 字段）。 */
    public static ArrayNode elementsToJson(List<ObservedElement> elements) {
        ArrayNode array = JSON.createArrayNode();
        for (ObservedElement element : elements) {
            ObjectNode node = array.addObject();
            node.put("index", element.index());
            node.put("targetHandle", element.targetHandle());
            node.put("role", element.role());
            if (!element.name().isEmpty()) {
                node.put("name", element.name());
            }
            if (!element.subrole().isEmpty()) {
                node.put("subrole", element.subrole());
            }
            if (!element.nativeIdentifier().isEmpty()) {
                node.put("nativeIdentifier", element.nativeIdentifier());
            }
            if (element.value() != null) {
                node.put("value", element.value());
            }
            if (element.enabled() != null) {
                node.put("enabled", element.enabled());
            }
            if (element.focused() != null) {
                node.put("focused", element.focused());
            }
            if (element.selected() != null) {
                node.put("selected", element.selected());
            }
            ArrayNode actions = node.putArray("actions");
            element.actions().forEach(actions::add);
            if (element.hasBounds()) {
                // dsh elementSchema.frame = {x,y,width,height}（rectSchema）
                ObjectNode frame = node.putObject("frame");
                frame.put("x", element.x());
                frame.put("y", element.y());
                frame.put("width", element.width());
                frame.put("height", element.height());
                // 兼容既有消费者（本仓早先的扁平四字段）
                node.put("x", element.x());
                node.put("y", element.y());
                node.put("w", element.width());
                node.put("h", element.height());
            }
        }
        return array;
    }

    private static String appKey(String bundleId, long pid) {
        return (bundleId == null ? "" : bundleId) + ":" + pid;
    }

    private Bucket bucket(String sessionId) {
        return bySession.computeIfAbsent(key(sessionId), k -> new Bucket());
    }

    private Bucket bucketOrNull(String sessionId) {
        return bySession.get(key(sessionId));
    }

    /** 过期清理 · dsh {@code prune()}。 */
    private void prune(Bucket bucket) {
        long now = System.currentTimeMillis();
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Observation> entry : bucket.observations.entrySet()) {
            Observation observation = entry.getValue();
            if (observation.expired(now) || observation.generation() != bucket.generation) {
                dead.add(entry.getKey());
            }
        }
        for (String id : dead) {
            bucket.observations.remove(id);
        }
        if (!dead.isEmpty()) {
            bucket.latestByApp.values().removeIf(dead::contains);
        }
    }

    private static String key(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? GLOBAL_KEY : sessionId;
    }
}
