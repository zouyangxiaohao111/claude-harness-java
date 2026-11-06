package com.nexusai.application.agent.computeruse;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * macOS <b>元素矩形</b>读取器（JNA 直调 ApplicationServices）· 补齐 dsh {@code elementSchema.frame}。
 *
 * <h2>WHY 必须走 JNA，不能走 JXA</h2>
 * <p>JXA 里读 {@code AXPosition} / {@code AXSize} 会让 {@code osascript} <b>段错误退出（exit 139）</b>
 * —— 根因是 JXA 的 {@code Ref()} 桥接把 {@code AXValueRef} 解成错误类型后解引用。
 * 实测同一条 {@code AXUIElementCopyAttributeValue} + {@code AXValueGetValue} 用 JNA 直调
 * 完全正常（微信窗口读回 {@code (22,149) 880x640}，与 {@code CGWindowList} 逐值一致）。
 *
 * <h2>为什么返回 {@code locator → frame} 而不是整棵树</h2>
 * <p>树结构、名称、动作集已经由 JXA 那一次调用采到（那里读 AX 属性是安全的）。
 * 本类只补<b>唯一的缺口</b>：坐标。两者用<b>同一套遍历顺序</b>
 * （{@code AXWindows[0]} 起、BFS、子节点按 {@code AXChildren} 原序）
 * ⇒ {@code locator} 路径可以严格对上（{@link #frames(long, int, int)} 的遍历与
 * {@code MacOsJxa.accessibilitySnapshot} 的 queue 逻辑逐行同构）。
 *
 * <p><b>⚠️ 本类不是「另一套实现」</b>：它不产出树，只产出坐标。
 * 若遍历顺序与 JXA 不一致，坐标会贴到错的元素上 —— 因此
 * {@link #frames(long, int, int)} 里的深度/节点上限必须与调用方传的<b>同一组</b>数值。
 */
final class MacOsAxFrames {

    private static final Logger log = LoggerFactory.getLogger(MacOsAxFrames.class);

    /** {@code AXValueGetValue} 的类型码：CGPoint。 */
    private static final int K_AX_VALUE_CG_POINT = 1;
    /** {@code AXValueGetValue} 的类型码：CGSize。 */
    private static final int K_AX_VALUE_CG_SIZE = 2;

    /** macOS 才可用（Windows 上没有 ApplicationServices）。 */
    private static final boolean SUPPORTED = Platform.isMac();

    private static volatile LibraryHolder holder;

    private MacOsAxFrames() {
    }

    /** 一个元素的屏幕矩形（点）。 */
    record Frame(int x, int y, int width, int height) {
    }

    /**
     * 采集整棵树的元素矩形。
     *
     * @param pid      目标应用 pid
     * @param maxDepth 最大深度（<b>必须与 JXA 那次快照同值</b>，否则 locator 会错位）
     * @param maxNodes 节点上限（同上）
     * @return {@code locator → Frame}（读不到矩形的元素<b>不在</b> map 里，不是填 0）
     */
    static Map<String, Frame> frames(long pid, int maxDepth, int maxNodes) {
        if (!SUPPORTED || pid <= 0) {
            return Map.of();
        }
        try {
            return collect(pid, maxDepth, maxNodes);
        } catch (Throwable t) {
            // ⛔ 坐标是「增强信息」，拿不到不能连累整次观察（观察本身仍有效）。
            //   但必须留痕：模型会看到没有 frame，排查时要知道是这里失败。
            log.warn("[computer-use] macOS 元素矩形采集失败（观察继续，但 elements[].frame 缺失）: pid={} err={}",
                    pid, t.toString());
            return Map.of();
        }
    }

    private static Map<String, Frame> collect(long pid, int maxDepth, int maxNodes) {
        Lib lib = holder().lib;
        Pointer appEl = lib.AXUIElementCreateApplication((int) pid);
        if (appEl == null) {
            return Map.of();
        }
        // 与 JXA 同款：root = AXWindows[0]（没有窗口时退到 app 元素本身）
        Pointer root = appEl;
        PointerByReference winsRef = new PointerByReference();
        if (lib.AXUIElementCopyAttributeValue(appEl, cf("AXWindows"), winsRef) == 0
                && winsRef.getValue() != null) {
            Pointer wins = winsRef.getValue();
            if (lib.CFArrayGetCount(wins) > 0) {
                Pointer first = lib.CFArrayGetValueAtIndex(wins, 0);
                if (first != null) {
                    root = first;
                }
            }
        }

        Map<String, Frame> out = new HashMap<>();
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{root, "", 0});
        int visited = 0;
        while (!queue.isEmpty()) {
            if (visited >= maxNodes) {
                break;
            }
            Object[] item = queue.poll();
            Pointer el = (Pointer) item[0];
            String loc = (String) item[1];
            int depth = (Integer) item[2];
            visited++;

            Frame frame = frameOf(lib, el);
            if (frame != null) {
                out.put(loc, frame);
            }
            if (depth >= maxDepth) {
                continue;
            }
            List<Pointer> kids = childrenOf(lib, el);
            for (int i = 0; i < kids.size(); i++) {
                queue.add(new Object[]{kids.get(i), loc.isEmpty() ? String.valueOf(i) : loc + "." + i, depth + 1});
            }
        }
        return out;
    }

    /** 读一个元素的 {@code AXPosition} + {@code AXSize}（任一读不到 ⇒ null）。 */
    private static Frame frameOf(Lib lib, Pointer el) {
        DoublePair pos = readPoint(lib, el, "AXPosition");
        DoublePair size = readSize(lib, el, "AXSize");
        if (pos == null || size == null) {
            return null;
        }
        if (size.a <= 0 || size.b <= 0) {
            // 零尺寸的元素（Group 容器、隐藏控件）没有可用矩形 ⇒ 不填，不要假装 (0,0)
            return null;
        }
        return new Frame((int) Math.round(pos.a), (int) Math.round(pos.b),
                (int) Math.round(size.a), (int) Math.round(size.b));
    }

    private static DoublePair readPoint(Lib lib, Pointer el, String attr) {
        return readValue(lib, el, attr, K_AX_VALUE_CG_POINT);
    }

    private static DoublePair readSize(Lib lib, Pointer el, String attr) {
        return readValue(lib, el, attr, K_AX_VALUE_CG_SIZE);
    }

    private static DoublePair readValue(Lib lib, Pointer el, String attr, int type) {
        PointerByReference ref = new PointerByReference();
        if (lib.AXUIElementCopyAttributeValue(el, cf(attr), ref) != 0) {
            return null;
        }
        Pointer value = ref.getValue();
        if (value == null) {
            return null;
        }
        // ⛔ 必须再过一层 AXValueGetValue：copyAttributeValue 给的是【AXValueRef（容器）】，
        //   不是 CGPoint 本体。上一版探针直接把它当 CGPoint 解引用 ⇒ SIGSEGV。
        DoublePair pair = new DoublePair();
        if (!lib.AXValueGetValue(value, type, pair.getPointer())) {
            return null;
        }
        pair.read();
        return pair;
    }

    private static List<Pointer> childrenOf(Lib lib, Pointer el) {
        PointerByReference ref = new PointerByReference();
        if (lib.AXUIElementCopyAttributeValue(el, cf("AXChildren"), ref) != 0) {
            return List.of();
        }
        Pointer arr = ref.getValue();
        if (arr == null) {
            return List.of();
        }
        long count = lib.CFArrayGetCount(arr);
        if (count <= 0) {
            return List.of();
        }
        List<Pointer> out = new ArrayList<>((int) Math.min(count, 256));
        for (long i = 0; i < count; i++) {
            Pointer child = lib.CFArrayGetValueAtIndex(arr, i);
            if (child != null) {
                out.add(child);
            }
        }
        return out;
    }

    /** CFString 属性名（每次新建；AX 调用开销远大于此，不做缓存以免跨线程生命期问题）。 */
    private static Pointer cf(String name) {
        return holder().lib.CFStringCreateWithCString(null, name, 0x08000100);
    }

    private static LibraryHolder holder() {
        LibraryHolder local = holder;
        if (local == null) {
            synchronized (MacOsAxFrames.class) {
                local = holder;
                if (local == null) {
                    local = new LibraryHolder();
                    holder = local;
                }
            }
        }
        return local;
    }

    /** JNA 库句柄（懒加载：Windows 上永不触碰）。 */
    private static final class LibraryHolder {
        private final Lib lib;

        private LibraryHolder() {
            this.lib = Native.load(
                    "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices",
                    Lib.class);
        }
    }

    /** CGPoint / CGSize 共用（都是两个 double）。 */
    public static class DoublePair extends Structure {
        public double a;
        public double b;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("a", "b");
        }
    }

    /** ApplicationServices 的最小子集。 */
    public interface Lib extends Library {
        Pointer AXUIElementCreateApplication(int pid);

        int AXUIElementCopyAttributeValue(Pointer element, Pointer attribute, PointerByReference out);

        boolean AXValueGetValue(Pointer value, int type, Pointer out);

        Pointer CFStringCreateWithCString(Pointer allocator, String value, int encoding);

        long CFArrayGetCount(Pointer array);

        Pointer CFArrayGetValueAtIndex(Pointer array, long index);
    }
}
