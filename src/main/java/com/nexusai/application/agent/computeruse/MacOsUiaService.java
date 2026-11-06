package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * macOS 的 UI Automation 服务实现（JXA + Accessibility / AXUIElement）·
 * computer-use 的 {@code observe} / {@code click_element} / {@code type_into_element} 底座。
 *
 * <h2>为什么 macOS 走 Accessibility 而不是截图</h2>
 * <p>macOS Accessibility（{@code AXUIElement}，即 VoiceOver / 辅助功能底层）能<b>纯文本</b>
 * 读出一棵控件树并直接执行语义动作。本机实测对比：
 * <ul>
 *   <li>Chrome 窗口整棵树 ≈ <b>134 字符 ≈ 39 token</b>；Finder 真实窗口 ≈ 120 节点；</li>
 *   <li>同样内容走 screenshot 是 <b>98KB base64 ≈ 2.5 万 token</b>（且超工具输出上限，
 *       模型根本读不到）⇒ <b>体积小近千倍</b>；</li>
 *   <li>坐标点击要先「看图 → 估坐标」，AX 是「读名字 → 点名操作」，没有像素误差。</li>
 * </ul>
 * <p>这正是 dsh 的 {@code Anionex/dsh-computer-use} 的取向（AX 优先，截图只是可选 Artifact），
 * 也是 Codex {@code window2} 的 {@code get_window_state} 返回 AX 树的原因。
 *
 * <h2>与 Windows UIA 实现的差异（如实标注）</h2>
 * <table border="1">
 *   <caption>两平台映射</caption>
 *   <tr><th>能力</th><th>Windows（{@link Win32UiaService}）</th><th>macOS（本类）</th></tr>
 *   <tr><td>树根</td><td>窗口句柄 {@code FromHandle}</td>
 *       <td>前台应用 pid → {@code AXFocusedWindow}/{@code AXMainWindow}</td></tr>
 *   <tr><td>精确选择器</td><td>UIA {@code AutomationId}</td>
 *       <td><b>locator 路径</b>（如 {@code "0.2.1"}）—— macOS 的 {@code AXIdentifier} 基本为空</td></tr>
 *   <tr><td>动作</td><td>{@code InvokePattern}</td><td>{@code AXPress}（无则报错，不盲点）</td></tr>
 *   <tr><td>设值</td><td>{@code ValuePattern.SetValue}</td>
 *       <td>{@code AXValue}（不可写 ⇒ 回落 {@code AXSelectedText}）</td></tr>
 *   <tr><td>坐标</td><td>{@code BoundingRectangle}</td>
 *       <td><b>不输出</b>：JXA 解 {@code AXValue}（CGPoint/CGSize）会崩溃（见下）</td></tr>
 * </table>
 *
 * <h2>⛔ 实测踩过的坑（写进实现，避免后人重踩）</h2>
 * <ol>
 *   <li><b>AX 属性名必须是 CFString</b>：传 JS 字符串字面量一律得
 *       {@code -25201}（{@code kAXErrorIllegalArgument}）⇒ 必须
 *       {@code CFStringCreateWithCString(null, "AXRole", kCFStringEncodingUTF8)}。</li>
 *   <li><b>{@code $(kAXRoleAttribute)} 是 undefined</b>：JXA 不导出这些常量，只能写字面量。</li>
 *   <li><b>CFArray 要 {@code ObjC.castRefToObject}</b> 才拿得到 {@code NSArray}（再 {@code count} /
 *       {@code objectAtIndex}）；{@code ObjC.unwrap} 对它无效。</li>
 *   <li><b>不要解 {@code AXValue} 坐标</b>：{@code AXValueGetValue} 用 {@code Ref()} 出参会让
 *       osascript 进程<b>直接崩溃（exit 139）</b>，用 {@code NSMutableData} 缓冲则读出全 0。
 *       ⇒ 本实现<b>不输出坐标</b>（AX 动作本来就不需要坐标）。</li>
 *   <li><b>动作错误码不可全信</b>：{@code AXOpen} / {@code AXClose} 实测返回
 *       {@code -25205}（unsupported）但<b>动作已生效</b> ⇒ 本实现对 {@code AXPress} 走
 *       「错误码 + 复核」双判（见 {@link #invokeElement}）。</li>
 *   <li><b>Chromium 的网页内容不自动暴露</b>：{@code AXWebArea} 没有子节点，
 *       {@code AXManualAccessibility} 在本机设不上（返回 -25205）⇒ Chrome 页面正文读不到，
 *       但<b>浏览器自身的工具栏 / 地址栏 / 标签页可用</b>。这一点如实暴露给模型。</li>
 * </ol>
 */
public final class MacOsUiaService implements ComputerUseUiaService {

    private static final Logger log = LoggerFactory.getLogger(MacOsUiaService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 默认遍历深度 · dsh {@code config.ts:93} {@code maxDepth: z.number().default(14)}。
     *
     * <p><b>⚠️ 与 Windows UIA 的 4 不同</b>：Windows 的 UIA 树层级浅、每层节点多，4 层足够；
     * macOS 的 AX 树在背景应用里会多套几层 Group（实测 Finder 工具栏在 5~7 层），
     * 4 层会漏掉真正的控件 ⇒ macOS 侧对齐 dsh 的 14。
     */
    public static final int DEFAULT_MAX_DEPTH = 14;

    /** 节点上限 · dsh {@code config.ts:92} {@code maxNodes: 500}（防巨树撑爆上下文）。 */
    public static final int MAX_NODES = 500;

    /** 条件等待的轮询间隔（毫秒）· 取 100ms（dsh 的 settleMs 默认 250ms，本仓取短以降低交互延迟）。 */
    public static final long POLL_INTERVAL_MS = 100L;

    /** 文本字节上限 · dsh {@code config.ts:94} {@code maxTextBytes: 64000}。 */
    public static final int MAX_TEXT_BYTES = 64_000;

    private final ComputerUseProcessRunner runner;
    private final MacOsTccChecker tcc;

    /** 生产构造器（真起进程）。 */
    public MacOsUiaService() {
        this(ComputerUseProcessRunner.REAL, new MacOsTccChecker());
    }

    /**
     * @param runner 外部命令执行器
     * @param tcc    TCC 授权检查（AX 需要辅助功能权限）
     */
    public MacOsUiaService(ComputerUseProcessRunner runner, MacOsTccChecker tcc) {
        if (runner == null || tcc == null) {
            throw new IllegalArgumentException("[computer-use] MacOsUiaService 依赖不能为空");
        }
        this.runner = runner;
        this.tcc = tcc;
    }

    /** macOS 按前台应用定位，不需要先绑定窗口。 */
    @Override
    public boolean requiresBoundWindow() {
        return false;
    }

    @Override
    public ComputerUseAccessibilitySnapshot captureSnapshot(long hwnd, int maxDepth) {
        return captureSnapshot(hwnd, maxDepth, ComputerUseAppSelector.frontmost());
    }

    /**
     * 抓一棵 AX 树 · <b>可指定观察对象</b>（对齐 dsh {@code computer_observe} 的 {@code app} 入参）。
     *
     * @param hwnd     忽略（macOS 从应用定位）
     * @param maxDepth 最大深度
     * @param selector 观察对象；空 ⇒ 前台应用
     * @return 快照（观察对象解析失败 ⇒ <b>抛异常</b>，不静默回落到前台应用）
     */
    public ComputerUseAccessibilitySnapshot captureSnapshot(long hwnd,
                                                          int maxDepth,
                                                          ComputerUseAppSelector selector) {
        tcc.requireAccessibility();
        int depth = maxDepth <= 0 ? DEFAULT_MAX_DEPTH : maxDepth;
        String raw = runJxa(MacOsJxa.accessibilitySnapshot(depth, MAX_NODES, MAX_TEXT_BYTES, selector));
        JsonNode root = parseJson(raw, "AX 快照");
        if (!root.path("appResolved").asBoolean(true)) {
            // ⛔ 观察对象解析失败必须显式失败：静默回落到前台应用会让模型以为在看 A、实际看的是 B
            throw new IllegalStateException(
                    "[computer-use] macOS 无法定位观察对象: " + root.path("resolveError").asText("unknown"));
        }
        List<ComputerUseAccessibilityNode> nodes = rebuildTree(root.path("records"));
        // ── 元素矩形（dsh elementSchema.frame）──
        // ⛔ JXA 读 AXPosition/AXSize 会 exit 139（Ref() 桥接解错类型后解引用）
        //   ⇒ 坐标只能走 JNA 直调 AXValueGetValue。两者用【同一套 BFS 顺序 + 同一组上限】，
        //   locator 才能严格对上（对齐参数：depth 与 MAX_NODES 必须和上面那次 JXA 调用一致）。
        long appPid = root.path("app").path("pid").asLong(0L);
        java.util.Map<String, MacOsAxFrames.Frame> elementFrames =
                MacOsAxFrames.frames(appPid, depth, MAX_NODES);
        if (!elementFrames.isEmpty()) {
            int[] counter = new int[1];
            // ⛔ 必须【递归】回填：nodes 只是顶层列表，子节点在 children 里。
            //   曾只遍历顶层 ⇒ 43 个元素里只有 1 个（根）拿到矩形，其余全是 0。
            nodes = withFrames(nodes, elementFrames, counter);
            if (log.isInfoEnabled()) {
                log.info("[computer-use] macOS 元素矩形采集: 有矩形 {}/{} 个元素",
                        counter[0], countNodes(nodes));
            }
        }
        String text = root.path("text").asText("");
        if (text.isEmpty()) {
            text = ComputerUseAccessibilitySnapshot.formatForModel(nodes);
        }
        JsonNode frame = root.path("window").path("frame");
        boolean hasFrame = frame.isObject() && frame.path("width").asInt(0) > 0;
        if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS AX 快照完成: app={} window={} 节点={} 截断={} 文本字节≈{} 前台={} 窗口矩形={}",
                    root.path("app").path("name").asText("?"),
                    root.path("window").path("title").asText(""),
                    nodes.size(), root.path("truncated").asBoolean(false), text.length(),
                    root.path("frontmost").asBoolean(false),
                    hasFrame ? (frame.path("width").asInt(0) + "x" + frame.path("height").asInt(0)) : "无");
        }
        return new ComputerUseAccessibilitySnapshot(
                text,
                nodes,
                System.currentTimeMillis(),
                root.path("app").path("name").asText(""),
                root.path("app").path("bid").asText(""),
                root.path("app").path("pid").asLong(0L),
                root.path("window").path("title").asText(""),
                // ⭐ 状态指纹【必须】用 JXA 侧算出的那个（与 accessibilityInvoke 复算时同一函数同一输入）。
                //   ⛔ 不可在 Java 侧重算：Java 侧只能拿到「渲染后的文本」，而 act 侧复算用的是
                //   「元素签名序列」—— 两者不同构，任何 Java 侧重算都会与 JXA 值不等
                //   ⇒ 动作会被永远误判为「树已变」而拒绝。
                root.path("stateHash").asText(""),
                root.path("truncated").asBoolean(false),
                frame.path("x").asInt(0),
                frame.path("y").asInt(0),
                frame.path("width").asInt(0),
                frame.path("height").asInt(0),
                root.path("window").path("id").asLong(0L),
                root.path("frontmost").asBoolean(false));
    }

    /** 递归回填元素矩形（命中 map 的元素换成带坐标的新 record，其余原样保留）。 */
    private static List<ComputerUseAccessibilityNode> withFrames(
            List<ComputerUseAccessibilityNode> nodes,
            java.util.Map<String, MacOsAxFrames.Frame> frames,
            int[] counter) {
        List<ComputerUseAccessibilityNode> out = new java.util.ArrayList<>(nodes.size());
        for (ComputerUseAccessibilityNode node : nodes) {
            List<ComputerUseAccessibilityNode> kids = withFrames(node.children(), frames, counter);
            MacOsAxFrames.Frame f = frames.get(node.locator());
            if (f == null) {
                out.add(kids == node.children() ? node : new ComputerUseAccessibilityNode(
                        node.role(), node.name(), node.automationId(),
                        node.x(), node.y(), node.width(), node.height(),
                        node.enabled(), node.value(), kids,
                        node.index(), node.locator(), node.actions(),
                        node.subrole(), node.nativeIdentifier(),
                        node.focused(), node.selected()));
                continue;
            }
            counter[0]++;
            out.add(new ComputerUseAccessibilityNode(
                    node.role(), node.name(), node.automationId(),
                    f.x(), f.y(), f.width(), f.height(),
                    node.enabled(), node.value(), kids,
                    node.index(), node.locator(), node.actions(),
                    node.subrole(), node.nativeIdentifier(),
                    node.focused(), node.selected()));
        }
        return out;
    }

    /** 全树节点数（含子节点）。 */
    private static int countNodes(List<ComputerUseAccessibilityNode> nodes) {
        int total = 0;
        Deque<ComputerUseAccessibilityNode> queue = new ArrayDeque<>(nodes);
        while (!queue.isEmpty()) {
            ComputerUseAccessibilityNode node = queue.removeFirst();
            total++;
            queue.addAll(node.children());
        }
        return total;
    }

    /**
     * 用 {@code AXPress} 触发元素。
     *
     * <p><b>取值语义</b>：{@code automationId} 传入的是<b>locator 路径</b>
     * （由 {@link ComputerUseAccessibilityNode#automationId()} 带出）。
     *
     * <p><b>⚠️ 不回落坐标点击</b>：macOS 上本实现拿不到坐标，且 AX 不可按的元素
     * （自绘 / Canvas）盲点只会误操作 ⇒ 返回 false，由工具层<b>显式报错</b>。
     *
     * <p><b>错误码双重判定</b>：实测某些动作返回 {@code -25205} 却已生效
     * ⇒ 拿到非 0 结果后退回一段极短等待，重新读该元素的 actions 是否还在，
     * 并在结果里区分「明确成功 / 错误码可疑」。
     */
    @Override
    public boolean invokeElement(long hwnd, String automationId) {
        return invokeElement(hwnd, automationId, null);
    }

    /**
     * 带<b>陈旧校验</b>的点击（dsh {@code expectedStateHash} 语义）。
     *
     * @param hwnd             忽略（macOS 从前台应用定位）
     * @param automationId     locator 路径
     * @param expectedStateHash observe 返回的状态指纹；非空 ⇒ 树变了就拒绝执行
     * @return true = 已触发 AXPress
     */
    @Override
    public boolean invokeElement(long hwnd, String automationId, String expectedStateHash) {
        return invokeElement(hwnd, automationId, expectedStateHash, 0L);
    }

    @Override
    public boolean invokeElement(long hwnd, String automationId, String expectedStateHash, long targetPid) {
        if (automationId == null || automationId.isEmpty()) {
            return false;
        }
        tcc.requireAccessibility();
        String raw = runJxa(MacOsJxa.accessibilityInvoke(automationId, expectedStateHash, targetPid));
        JsonNode node = parseJson(raw, "AXPress");
        boolean ok = node.path("ok").asBoolean(false);
        int err = node.path("err").asInt(0);
        // ⭐ 陈旧不是「不可按」：必须让上层看到独立错误码，否则模型会重复尝试同一 locator
        if (err == ComputerUseStaleObservationException.AX_ERR_STALE) {
            throw new ComputerUseStaleObservationException(node.path("message").asText(
                    "the accessibility tree changed since the observation"));
        }
        if (!ok) {
            if (log.isWarnEnabled()) {
                log.warn("[computer-use] macOS AXPress 失败: locator={} err={} actions={} msg={}",
                        automationId, err, node.path("actions"), node.path("message").asText(""));
            }
        } else if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS AXPress 成功: locator={} role={} name={}",
                    automationId, node.path("role").asText(""), node.path("name").asText(""));
        }
        return ok;
    }

    /**
     * 按条件等待 AX 状态满足（对齐 dsh {@code computer_wait}）。
     *
     * <h2>WHY 轮询放在 Java 侧而不是 JXA 里</h2>
     * <p>条件判定需要「树文本包含子串」+「存在某角色/标题的元素」，
     * 而这些判定逻辑本仓已在节点树上实现
     * ⇒ 放在 Java 侧可复用同一套判定（不会出现两套条件语义漂移）。
     * <br>JXA 侧只负责「抓一棵树」（一次 {@code osascript} 调用），轮询节奏由 Java 控制。
     *
     * <h2>轮询节奏</h2>
     * <p>默认 <b>100ms</b> 一次（dsh 的 {@code settleMs} 默认 250ms；本仓取更短以降低交互延迟），
     * 总时长受 {@code timeoutMs} 约束。
     *
     * @param hwnd         忽略（macOS 从前台应用定位）
     * @param text         条件：树文本包含该子串（大小写不敏感；可空）
     * @param elementRole  条件：存在该角色的元素（可空）
     * @param elementTitle 条件：存在该标题/名字的元素（可空）
     * @param timeoutMs    超时（毫秒）
     * @return 等待结束时的快照（<b>超时也返回最后一次快照</b>，由调用方判断是否满足）
     */
    @Override
    public ComputerUseAccessibilitySnapshot waitForCondition(long hwnd,
                                                              String text,
                                                              String elementRole,
                                                              String elementTitle,
                                                              long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        ComputerUseAccessibilitySnapshot last = null;
        while (true) {
            last = captureSnapshot(hwnd, DEFAULT_MAX_DEPTH);
            if (matchesCondition(last, text, elementRole, elementTitle)) {
                return last;
            }
            if (System.currentTimeMillis() >= deadline) {
                return last;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
    }

    /**
     * 判断快照是否满足等待条件（逐条对齐 dsh {@code matchesWait}）。
     *
     * <p>dsh 原文：
     * <pre>
     * if (condition.text !== undefined &amp;&amp; !observation.treeText.toLocaleLowerCase()
     *         .includes(condition.text.toLocaleLowerCase())) return false
     * if (condition.elementRole !== undefined
     *         &amp;&amp; !observation.elements.some(e =&gt; e.role === condition.elementRole)) return false
     * if (condition.elementTitle !== undefined
     *         &amp;&amp; !observation.elements.some(e =&gt; e.title === condition.elementTitle
     *                                          || e.label === condition.elementTitle)) return false
     * return condition.text !== undefined || condition.elementRole !== undefined
     *        || condition.elementTitle !== undefined
     * </pre>
     *
     * @param snapshot     快照
     * @param text         文本条件（可空）
     * @param elementRole 角色条件（可空）
     * @param elementTitle 标题条件（可空）
     * @return true = 满足
     */
    static boolean matchesCondition(ComputerUseAccessibilitySnapshot snapshot,
                                    String text,
                                    String elementRole,
                                    String elementTitle) {
        boolean hasText = text != null && !text.isEmpty();
        boolean hasRole = elementRole != null && !elementRole.isEmpty();
        boolean hasTitle = elementTitle != null && !elementTitle.isEmpty();
        if (!hasText && !hasRole && !hasTitle) {
            // dsh：三个条件都没给 ⇒ 永不匹配
            return false;
        }
        if (hasText) {
            String haystack = snapshot.text() == null ? "" : snapshot.text().toLowerCase(Locale.ROOT);
            if (!haystack.contains(text.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        if (hasRole && !hasRole(snapshot, elementRole)) {
            return false;
        }
        if (hasTitle && !hasTitle(snapshot, elementTitle)) {
            return false;
        }
        return true;
    }

    /** 是否存在指定角色的元素。 */
    private static boolean hasRole(ComputerUseAccessibilitySnapshot snapshot, String role) {
        for (ComputerUseAccessibilityNode node : flatten(snapshot.nodes())) {
            if (node.role().equalsIgnoreCase(role)) {
                return true;
            }
        }
        return false;
    }

    /** 是否存在指定标题/名字的元素。 */
    private static boolean hasTitle(ComputerUseAccessibilitySnapshot snapshot, String title) {
        for (ComputerUseAccessibilityNode node : flatten(snapshot.nodes())) {
            if (title.equals(node.name())) {
                return true;
            }
        }
        return false;
    }

    /** 深度优先展开节点树。 */
    private static List<ComputerUseAccessibilityNode> flatten(List<ComputerUseAccessibilityNode> nodes) {
        List<ComputerUseAccessibilityNode> out = new ArrayList<>();
        if (nodes == null) {
            return out;
        }
        for (ComputerUseAccessibilityNode node : nodes) {
            out.add(node);
            out.addAll(flatten(node.children()));
        }
        return out;
    }

    /**
     * 执行元素声明的<b>任意</b> AX 动作（对齐 dsh {@code computer_perform_action}）。
     *
     * <p><b>WHY 要先查动作列表</b>：直接 {@code AXUIElementPerformAction} 传一个不存在的动作名
     * 会返回 -25207，但那是「参数无效」而非「应用拒绝」⇒ 两种失败必须分开。
     * <br>dsh 的做法相同（{@code main.swift:1225} {@code guard available.contains(actionName)}）。
     *
     * @param hwnd   忽略（macOS 从前台应用定位）
     * @param locator locator 路径
     * @param action 动作名
     * @return true = 动作已执行
     */
    @Override
    public boolean performAction(long hwnd, String locator, String action) {
        return performAction(hwnd, locator, action, 0L);
    }

    @Override
    public boolean performAction(long hwnd, String locator, String action, long targetPid) {
        return performAction(hwnd, locator, action, null, targetPid);
    }

    @Override
    public boolean performAction(long hwnd, String locator, String action,
                                 String expectedStateHash, long targetPid) {
        if (locator == null || locator.isEmpty() || action == null || action.isEmpty()) {
            return false;
        }
        tcc.requireAccessibility();
        String raw = runJxa(MacOsJxa.accessibilityPerformAction(
                locator, action, expectedStateHash, targetPid));
        JsonNode node = parseJson(raw, "AX performAction");
        boolean ok = node.path("ok").asBoolean(false);
        if (node.path("err").asInt(0) == ComputerUseStaleObservationException.AX_ERR_STALE) {
            throw new ComputerUseStaleObservationException(node.path("message").asText(
                    "the accessibility tree changed since the observation"));
        }
        if (!ok && log.isWarnEnabled()) {
            log.warn("[computer-use] macOS AX 动作失败: locator={} action={} err={} available={} msg={}",
                    locator, action, node.path("err").asInt(0),
                    node.path("available"), node.path("message").asText(""));
        } else if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS AX 动作成功: locator={} action={}",
                    locator, action);
        }
        return ok;
    }

    /**
     * 设置元素的值（{@code AXValue}，不可写时回落 {@code AXSelectedText}）。
     *
     * @param hwnd         忽略（macOS 从前台应用定位）
     * @param automationId locator 路径
     * @param value        要写入的值
     * @return true = 已写入
     */
    @Override
    public boolean setElementValue(long hwnd, String automationId, String value) {
        return setElementValue(hwnd, automationId, value, 0L);
    }

    @Override
    public boolean setElementValue(long hwnd, String automationId, String value, long targetPid) {
        return setElementValue(hwnd, automationId, value, null, targetPid);
    }

    @Override
    public boolean setElementValue(long hwnd, String automationId, String value,
                                   String expectedStateHash, long targetPid) {
        if (automationId == null || automationId.isEmpty()) {
            return false;
        }
        tcc.requireAccessibility();
        String raw = runJxa(MacOsJxa.accessibilitySetValue(
                automationId, value, expectedStateHash, targetPid));
        JsonNode node = parseJson(raw, "AX setValue");
        boolean ok = node.path("ok").asBoolean(false);
        if (node.path("err").asInt(0) == ComputerUseStaleObservationException.AX_ERR_STALE) {
            throw new ComputerUseStaleObservationException(node.path("message").asText(
                    "the accessibility tree changed since the observation"));
        }
        if (log.isInfoEnabled()) {
            log.info("[computer-use] macOS AX 设值{}: locator={} 字符数={} 方式={} err={}",
                    ok ? "成功" : "失败", automationId,
                    value == null ? 0 : value.length(),
                    node.path("via").asText(""), node.path("err").asInt(0));
        }
        return ok;
    }

    // ══════════════════════════════════════════════════════════════════
    // JSON 组装
    // ══════════════════════════════════════════════════════════════════

    /**
     * JXA 返回的<b>扁平记录</b> → 节点树。
     *
     * <p><b>WHY 扁平而不是嵌套</b>：JXA 里构造嵌套 JS 对象再 {@code JSON.stringify} 可行，
     * 但记录一多（数百节点）会显著变慢且容易踩桥接对象递归展开的坑；扁平记录只做
     * {@code push}，Assembly 交给 Java（也便于单测）。
     *
     * @param records 扁平记录数组（每项含 {@code loc} = 形如 {@code "0.2.1"} 的路径）
     * @return 根节点列表
     */
    List<ComputerUseAccessibilityNode> rebuildTree(JsonNode records) {
        if (records == null || !records.isArray() || records.isEmpty()) {
            return List.of();
        }
        // 先把所有记录解析出来，再按 parent locator 归组
        Map<String, JsonNode> byLocator = new LinkedHashMap<>();
        for (JsonNode rec : records) {
            String loc = rec.path("loc").asText("");
            byLocator.put(loc, rec);
        }
        Map<String, List<String>> childrenOf = new LinkedHashMap<>();
        for (String loc : byLocator.keySet()) {
            String parent = parentLocator(loc);
            // ⛔ 自指必须排除：JXA 侧根记录的 loc 是空串，而 parentLocator("") 也是空串
            //   ⇒ 若照收，childrenOf[""] 会包含 ""，buildChildren("") 立刻无限递归
            //   ⇒ StackOverflowError（2026-11-02 真机实测：TextEdit 窗口直接打爆）。
            if (loc.equals(parent)) {
                continue;
            }
            childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(loc);
        }
        // 根记录（空 locator，通常是 AXWindow）存在时把它作为唯一根返回，
        // 保证树形与 JXA 侧的索引路径一致（"0" 仍是根的第一个子节点）。
        if (byLocator.containsKey("")) {
            return List.of(toNode("", byLocator, childrenOf));
        }
        return buildChildren("", byLocator, childrenOf);
    }

    /** 单条记录 → 节点（含子树）。 */
    private ComputerUseAccessibilityNode toNode(String loc,
                                                Map<String, JsonNode> byLocator,
                                                Map<String, List<String>> childrenOf) {
        JsonNode rec = byLocator.get(loc);
        return new ComputerUseAccessibilityNode(
                stripAxPrefix(rec.path("role").asText("")),
                rec.path("name").asText(""),
                // macOS：automationId 槽位放 locator（后续动作按它反查）
                loc,
                rec.path("x").asInt(0),
                rec.path("y").asInt(0),
                rec.path("w").asInt(0),
                rec.path("h").asInt(0),
                rec.path("enabled").asBoolean(true),
                rec.hasNonNull("value") ? rec.get("value").asText() : null,
                buildChildren(loc, byLocator, childrenOf),
                rec.path("i").asInt(-1),
                loc,
                toStringList(rec.path("actions")),
                // 子角色与原生标识：dsh 三级解析（exact / native-identifier / semantic）的稳定字段
                rec.path("subrole").asText(""),
                rec.path("sid").asText(""),
                rec.hasNonNull("focused") ? rec.get("focused").asBoolean() : null,
                rec.hasNonNull("selected") ? rec.get("selected").asBoolean() : null);
    }

    private List<ComputerUseAccessibilityNode> buildChildren(String parent,
                                                             Map<String, JsonNode> byLocator,
                                                             Map<String, List<String>> childrenOf) {
        List<String> childLocators = childrenOf.get(parent);
        if (childLocators == null || childLocators.isEmpty()) {
            return List.of();
        }
        List<ComputerUseAccessibilityNode> out = new ArrayList<>(childLocators.size());
        for (String loc : childLocators) {
            out.add(toNode(loc, byLocator, childrenOf));
        }
        return out;
    }

    /** {@code "0.2.1"} ⇒ {@code "0.2"}；根（{@code "0"}）⇒ {@code ""}。 */
    static String parentLocator(String locator) {
        if (locator == null || locator.isEmpty()) {
            return "";
        }
        int idx = locator.lastIndexOf('.');
        return idx < 0 ? "" : locator.substring(0, idx);
    }

    /** macOS 的 {@code AXButton} ⇒ {@code Button}（与 Windows 侧命名一致）。 */
    static String stripAxPrefix(String role) {
        if (role == null) {
            return "";
        }
        return role.startsWith("AX") ? role.substring(2) : role;
    }

    private static List<String> toStringList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            out.add(item.asText(""));
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    // 执行与解析
    // ══════════════════════════════════════════════════════════════════

    private String runJxa(String script) {
        try {
            return MacOsExec.jxa(runner, script);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("[computer-use] macOS AX 脚本执行失败: " + e.getMessage(), e);
        }
    }

    private JsonNode parseJson(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("[computer-use] " + what + " 无输出");
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[computer-use] " + what + " JSON 解析失败: " + e.getMessage()
                            + "（原始输出前 200 字符: " + preview(raw) + "）", e);
        }
    }

    private static String preview(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
