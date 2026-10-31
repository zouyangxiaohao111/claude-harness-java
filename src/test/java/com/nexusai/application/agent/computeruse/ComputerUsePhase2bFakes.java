package com.nexusai.application.agent.computeruse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2b 新增能力的<b>共用假实现</b>（纯单测用，不加载任何 JNA native、不起 PowerShell 子进程）。
 *
 * <p><b>WHY 集中一份</b>：Windows 专属 11 个工具共享同一批原语
 * （窗口操作 / UIA / 绑定存储 / 点击路由）⇒ 每写一个测试就复制一遍假实现，
 * 会让「假实现的行为」在各测试之间悄悄漂移 ⇒ 这里集中一份，所有测试共用。
 *
 * <p>⚠️ 本文件里的任何类都<b>不得</b>触碰真实桌面。
 */
final class ComputerUsePhase2bFakes {

    private ComputerUsePhase2bFakes() {
        throw new AssertionError("utility class");
    }

    /** 记录窗口操作调用的假实现。 */
    static final class RecordingWindowOps implements ComputerUseWindowOps {
        final List<String> calls = new ArrayList<>();
        final List<ComputerUseWindowInfo> windows = new ArrayList<>();
        /** 客户区原点相对窗口矩形原点的偏移（non-client 偏移）。 */
        int[] clientOffset = new int[]{0, 0};
        /** 客户区原点在屏幕上的位置。 */
        int[] clientOrigin = new int[]{0, 0};
        /** 窗口矩形。 */
        int[] windowRect = new int[]{100, 200, 800, 600};
        /** 发送的按键序列（键名 + down/up）。 */
        final List<String> keyEvents = new ArrayList<>();
        /** 发送的点击（客户区坐标 + 右键标记）。 */
        final List<int[]> clicks = new ArrayList<>();
        final List<String> clickButtons = new ArrayList<>();
        /** 窗口定向的字符 / 文本。 */
        final List<String> texts = new ArrayList<>();
        final List<int[]> wheelEvents = new ArrayList<>();
        final List<int[]> mouseMoves = new ArrayList<>();
        final List<int[]> mouseDowns = new ArrayList<>();
        final List<int[]> mouseUps = new ArrayList<>();

        @Override
        public List<ComputerUseWindowInfo> listVisibleWindows() {
            calls.add("listVisibleWindows");
            return windows;
        }

        @Override
        public int[] getWindowRect(long hwnd) {
            calls.add("getWindowRect:" + hwnd);
            return windowRect;
        }

        @Override
        public int[] getClientOffset(long hwnd) {
            calls.add("getClientOffset:" + hwnd);
            return clientOffset;
        }

        @Override
        public int[] clientToScreen(long hwnd, int x, int y) {
            return new int[]{clientOrigin[0] + x, clientOrigin[1] + y};
        }

        @Override
        public int[] screenToClient(long hwnd, int x, int y) {
            calls.add("screenToClient:" + hwnd + ":" + x + "," + y);
            return new int[]{x - clientOrigin[0], y - clientOrigin[1]};
        }

        @Override
        public void showWindow(long hwnd, int cmd) {
            calls.add("showWindow:" + hwnd + ":" + cmd);
        }

        @Override
        public void moveResize(long hwnd, int x, int y, Integer width, Integer height) {
            calls.add("moveResize:" + hwnd + ":" + x + "," + y + ":" + width + "x" + height);
        }

        @Override
        public void moveOffscreen(long hwnd) {
            calls.add("moveOffscreen:" + hwnd);
        }

        @Override
        public void closeWindow(long hwnd) {
            calls.add("closeWindow:" + hwnd);
        }

        @Override
        public void focusWindow(long hwnd) {
            calls.add("focusWindow:" + hwnd);
        }

        @Override
        public void sendClick(long hwnd, int x, int y, boolean right) {
            calls.add("sendClick:" + hwnd);
            clicks.add(new int[]{x, y});
            clickButtons.add(right ? "right" : "left");
        }

        @Override
        public void sendMouseDown(long hwnd, int x, int y) {
            calls.add("sendMouseDown:" + hwnd);
            mouseDowns.add(new int[]{x, y});
        }

        @Override
        public void sendMouseUp(long hwnd, int x, int y) {
            calls.add("sendMouseUp:" + hwnd);
            mouseUps.add(new int[]{x, y});
        }

        @Override
        public void sendMouseMove(long hwnd, int x, int y) {
            calls.add("sendMouseMove:" + hwnd);
            mouseMoves.add(new int[]{x, y});
        }

        @Override
        public void sendChar(long hwnd, int codePoint) {
            calls.add("sendChar:" + hwnd);
            texts.add(String.valueOf((char) codePoint));
        }

        @Override
        public void sendText(long hwnd, String text) {
            calls.add("sendText:" + hwnd);
            texts.add(text);
        }

        @Override
        public void sendKeyDown(long hwnd, int vk) {
            calls.add("sendKeyDown:" + hwnd + ":" + vk);
            keyEvents.add("down:" + vk);
        }

        @Override
        public void sendKeyUp(long hwnd, int vk) {
            calls.add("sendKeyUp:" + hwnd + ":" + vk);
            keyEvents.add("up:" + vk);
        }

        @Override
        public void sendMouseWheel(long hwnd, int x, int y, int delta, boolean horizontal) {
            calls.add("sendMouseWheel:" + hwnd);
            wheelEvents.add(new int[]{x, y, delta, horizontal ? 1 : 0});
        }

        @Override
        public long findEditChild(long hwnd) {
            calls.add("findEditChild:" + hwnd);
            return 0L;
        }
    }

    /** 返回固定 UIA 快照的假实现。 */
    static final class FakeUiaService implements ComputerUseUiaService {
        ComputerUseAccessibilitySnapshot snapshot = ComputerUseAccessibilitySnapshot.empty();
        /** 被 Invoke 的 automationId 序列。 */
        final List<String> invoked = new ArrayList<>();
        /** 被 SetValue 的 (automationId, value) 序列。 */
        final List<String> setValues = new ArrayList<>();
        /** invokeElement / setElementValue 的返回值。 */
        boolean invokeResult = true;
        boolean setValueResult = true;
        /** 抓快照时抛的异常（测「解析失败要明确报错」）。 */
        RuntimeException failWith;

        @Override
        public ComputerUseAccessibilitySnapshot captureSnapshot(long hwnd, int maxDepth) {
            if (failWith != null) {
                throw failWith;
            }
            return snapshot;
        }

        @Override
        public boolean invokeElement(long hwnd, String automationId) {
            invoked.add(automationId);
            return invokeResult;
        }

        @Override
        public boolean setElementValue(long hwnd, String automationId, String value) {
            setValues.add(automationId + "=" + value);
            return setValueResult;
        }
    }

    /** 记录浮层调用的假实现。 */
    static final class FakeStatusIndicator implements ComputerUseStatusIndicator {
        final List<String> shown = new ArrayList<>();
        int hideCount;
        boolean active;

        @Override
        public void show(long hwnd, String message) {
            shown.add(hwnd + ":" + message);
            active = true;
        }

        @Override
        public void hide() {
            hideCount++;
            active = false;
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }

    /** 记录终端启动调用的假实现。 */
    static final class FakeTerminalLauncher implements ComputerUseTerminalLauncher {
        final List<String> calls = new ArrayList<>();
        /** 启动后要「出现」的窗口（null ⇒ 不出现）。 */
        ComputerUseWindowInfo newWindow;
        /** 启动时把 {@link #newWindow} 加进哪个窗口列表（模拟新终端窗口真的出现）。 */
        RecordingWindowOps target;

        @Override
        public void launch(String agent, String command, String terminal, String workingDirectory) {
            calls.add(agent + "|" + command + "|" + terminal + "|" + workingDirectory);
            if (newWindow != null && target != null) {
                // 模拟「终端启动 ⇒ 新窗口出现」（必须发生在「启动前的窗口快照」之后）
                target.windows.add(newWindow);
            }
        }
    }

    /** 固定返回某个授权键的进程解析器。 */
    record FixedProcessAppResolver(String appId) implements ComputerUseProcessAppResolver {
        @Override
        public String appIdForPid(int pid) {
            return appId;
        }
    }

    /** 记录键盘事件的假键盘（用于修饰键顺序断言）。 */
    static final class RecordingKeyboard implements ComputerUseKeyboard {
        final List<String> events = new ArrayList<>();
        /** 在第 N 个事件上抛异常（-1 = 不抛）。 */
        int failAtEventIndex = -1;

        @Override
        public void typeChar(int codePoint) {
            events.add("type:" + codePoint);
        }

        @Override
        public void sendKeyEvent(ComputerUseKeyEvent event) throws Exception {
            if (failAtEventIndex >= 0 && events.size() == failAtEventIndex) {
                throw new IllegalStateException("注入的按键失败");
            }
            events.add((event.down() ? "down:" : "up:") + event.name());
        }
    }

    /** 记录左键点击的假鼠标（{@link ComputerUseMouse} 口径）。 */
    static final class RecordingMouse implements ComputerUseMouse {
        final List<int[]> moves = new ArrayList<>();
        final List<int[]> clicks = new ArrayList<>();

        @Override
        public void moveAndSettle(int x, int y) {
            moves.add(new int[]{x, y});
        }

        @Override
        public void leftClick(int x, int y) {
            clicks.add(new int[]{x, y});
        }
    }

    /** 假截屏器（不加载 native）。 */
    static final class FakeCapturer implements ComputerUseScreenCapturer {
        private final ComputerUseDisplayGeometry display;

        FakeCapturer(ComputerUseDisplayGeometry display) {
            this.display = display;
        }

        @Override
        public ComputerUseCaptureResult capture() {
            throw new UnsupportedOperationException("点击测试不需要截屏");
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return display;
        }
    }

    /** 造一个带节点的 UIA 快照。 */
    static ComputerUseAccessibilitySnapshot snapshotOf(ComputerUseAccessibilityNode... nodes) {
        return new ComputerUseAccessibilitySnapshot(
                ComputerUseAccessibilitySnapshot.formatForModel(List.of(nodes)),
                List.of(nodes),
                System.currentTimeMillis());
    }

    /** 造一个带子节点的 UIA 节点。 */
    static ComputerUseAccessibilityNode node(String role, String name, String automationId,
                                             int x, int y, int w, int h,
                                             boolean enabled, String value,
                                             List<ComputerUseAccessibilityNode> children) {
        return new ComputerUseAccessibilityNode(role, name, automationId, x, y, w, h, enabled, value, children);
    }

    /** 造一个窗口信息。 */
    static ComputerUseWindowInfo window(long hwnd, int pid, String title) {
        return new ComputerUseWindowInfo(hwnd, pid, title);
    }

    /** 空的授权快照（构造 Map 用）。 */
    static Map<String, Object> emptyMap() {
        return new LinkedHashMap<>();
    }
}
