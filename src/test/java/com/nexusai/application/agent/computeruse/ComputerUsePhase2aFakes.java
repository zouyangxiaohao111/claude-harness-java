package com.nexusai.application.agent.computeruse;

import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2a 新增 17 个工具的<b>共用假实现</b>（纯单测用，不加载任何 JNA native）。
 *
 * <p><b>WHY 抽一个共用类</b>：Phase 2a 的 17 个工具彼此共享同一批原语
 * （鼠标动作 / 光标 / 键盘 / 剪贴板 / 显示器 / 截屏器 / 前台解析器 / 授权存储）
 * ⇒ 每写一个工具测试就复制一遍假实现，会让「假实现的行为」在各测试之间悄悄漂移
 * （例如某个测试的假鼠标偷偷把 count 忽略了）⇒ 这里集中一份，所有测试共用。
 *
 * <p>⚠️ <b>这些都是「不碰真实桌面」的假实现</b> —— 本文件里的任何类都<b>不得</b>
 * 触碰 Win32 API。
 */
final class ComputerUsePhase2aFakes {

    private ComputerUsePhase2aFakes() {
        throw new AssertionError("utility class");
    }

    /** 记录鼠标动作的假实现（点击 / 移动 / 滚动）。 */
    static final class RecordingMouseActions implements ComputerUseMouseActions {
        final List<int[]> clicks = new ArrayList<>();
        final List<ComputerUseMouseButton> buttons = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        final List<int[]> moves = new ArrayList<>();
        final List<int[]> scrolls = new ArrayList<>();

        @Override
        public void click(int x, int y, ComputerUseMouseButton button, int count) {
            clicks.add(new int[]{x, y});
            buttons.add(button);
            counts.add(count);
        }

        @Override
        public void moveTo(int x, int y) {
            moves.add(new int[]{x, y});
        }

        @Override
        public void scroll(int dx, int dy) {
            scrolls.add(new int[]{dx, dy});
        }
    }

    /** 记录光标操作的假实现。 */
    static final class FakeCursor implements ComputerUseCursor {
        final List<int[]> locations = new ArrayList<>();
        final List<int[]> moves = new ArrayList<>();
        int downCount;
        int upCount;
        /** 抛异常标记（测「异常路径不卡键」）。 */
        boolean failOnDown;
        boolean failOnUp;

        @Override
        public void moveTo(int x, int y) {
            moves.add(new int[]{x, y});
        }

        @Override
        public int[] location() {
            int[] loc = locations.isEmpty() ? new int[]{0, 0} : locations.get(locations.size() - 1);
            return new int[]{loc[0], loc[1]};
        }

        @Override
        public void leftButtonDown() throws Exception {
            if (failOnDown) {
                throw new IllegalStateException("注入的按下失败");
            }
            downCount++;
        }

        @Override
        public void leftButtonUp() throws Exception {
            if (failOnUp) {
                throw new IllegalStateException("注入的抬起失败");
            }
            upCount++;
        }
    }

    /** 记录按键事件的假实现（可注入失败）。 */
    static final class FakeKeyboard implements ComputerUseKeyboard {
        final List<ComputerUseKeyEvent> events = new ArrayList<>();
        final List<Integer> typed = new ArrayList<>();
        /** 在第 N 个事件上抛异常（-1 = 不抛）。 */
        int failAtEventIndex = -1;
        /** 只失败一次（否则异常路径的「释放」也会失败 ⇒ 测不出「不卡键」）。 */
        private boolean alreadyFailed;

        @Override
        public void typeChar(int codePoint) {
            typed.add(codePoint);
        }

        @Override
        public void sendKeyEvent(ComputerUseKeyEvent event) throws Exception {
            if (!alreadyFailed && failAtEventIndex >= 0 && events.size() == failAtEventIndex) {
                alreadyFailed = true;
                throw new IllegalStateException("注入的按键失败");
            }
            events.add(event);
        }
    }

    /** 记录剪贴板读写的假实现。 */
    static final class FakeClipboard implements ComputerUseClipboard {
        String content = "";
        int readCount;
        int writeCount;
        boolean failOnRead;
        boolean failOnWrite;

        @Override
        public String readText() throws Exception {
            readCount++;
            if (failOnRead) {
                throw new IllegalStateException("剪贴板读取失败");
            }
            return content;
        }

        @Override
        public void writeText(String text) throws Exception {
            writeCount++;
            if (failOnWrite) {
                throw new IllegalStateException("剪贴板写入失败");
            }
            this.content = text;
        }
    }

    /** 记录启动的应用的假实现。 */
    static final class FakeAppLauncher implements ComputerUseAppLauncher {
        final List<String> launched = new ArrayList<>();
        boolean fail;

        @Override
        public void launch(String appKey) throws Exception {
            if (fail) {
                throw new IllegalStateException("启动失败");
            }
            launched.add(appKey);
        }
    }

    /** 返回固定显示器列表的假实现。 */
    static final class FakeDisplayProvider implements ComputerUseDisplayProvider {
        final List<ComputerUseDisplay> displays = new ArrayList<>();

        @Override
        public List<ComputerUseDisplay> listDisplays() {
            return displays;
        }
    }

    /** 可控的截屏假实现（记录 captureRegion 的入参）。 */
    static final class FakeCapturer implements ComputerUseScreenCapturer {
        final List<int[]> regions = new ArrayList<>();
        int captureCount;

        @Override
        public ComputerUseCaptureResult capture() {
            captureCount++;
            return result(100, 50);
        }

        @Override
        public ComputerUseCaptureResult captureRegion(int x, int y, int width, int height) {
            regions.add(new int[]{x, y, width, height});
            return result(width, height);
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0);
        }

        private static ComputerUseCaptureResult result(int w, int h) {
            return new ComputerUseCaptureResult(
                    new byte[]{1, 2, 3}, "image/jpeg", w, h, 1920, 1080, 0, 0, 1.0d, 1920, 1080);
        }
    }

    /** 固定返回某个前台应用的解析器（null = 取不到前台应用）。 */
    record FixedResolver(String appId) implements ComputerUseForegroundAppResolver {
        @Override
        public String currentAppId() {
            return appId;
        }
    }

    /** 记录休眠时长的假时钟。 */
    static final class RecordingSleeper implements ComputerUseSleeper {
        final List<Long> slept = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            slept.add(millis);
        }
    }

    /**
     * 造一个「授权位存储」（内存 mock，不碰 DB）。
     *
     * @param sessionId 会话 id
     * @param grants    授权 JSON
     * @return 授权位存储
     */
    static ComputerUseGrantStore grantStore(String sessionId, String grantsJson) {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        SessionRecord record = new SessionRecord();
        record.setId(sessionId);
        record.setComputerUseGrants(grantsJson);
        when(sessionMapper.selectOneById(sessionId)).thenReturn(record);
        when(sessionMapper.update(any(SessionRecord.class))).thenReturn(1);
        return new ComputerUseGrantStore(sessionMapper);
    }

    /**
     * 造一份「图像像素 ↔ 屏幕像素 1:1」的截图几何快照（换算恒等 ⇒ 断言直观）。
     *
     * @param sessionId 会话 id
     * @return 几何存储
     */
    static ComputerUseGeometryStore identityGeometry(String sessionId) {
        ComputerUseGeometryStore store = new ComputerUseGeometryStore();
        store.put(sessionId, new ComputerUseScreenshotGeometry(1920, 1080, 1920, 1080, 0, 0));
        return store;
    }
}
