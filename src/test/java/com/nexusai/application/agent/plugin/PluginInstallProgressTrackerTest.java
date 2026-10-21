package com.nexusai.application.agent.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安装进度台账单测。
 *
 * <p>验证意图（规则九）：前端进度圈显示的是**可解释**的数字 ——
 * 下载段必须按真实字节推进、未知长度不能假装百分比、失败必须落终态
 * （否则按钮会永远停在中间值，用户无法判断到底还在装还是已经失败）。
 */
class PluginInstallProgressTrackerTest {

    private final PluginInstallProgressTracker tracker = new PluginInstallProgressTracker();

    @Test
    void downloading_maps_bytes_to_5_75_percent() {
        String id = "p@m";
        tracker.begin(id);
        assertEquals(2, tracker.get(id).percent(), "开始态：准备市场索引");

        tracker.downloading(id, 0, 1000);
        assertEquals(5, tracker.get(id).percent(), "下载起步 5%（区别于 0 与准备态）");

        tracker.downloading(id, 500, 1000);
        int half = tracker.get(id).percent();
        assertEquals(40, half, "一半字节 ≈ 5 + 70*0.5");

        tracker.downloading(id, 1000, 1000);
        assertEquals(75, tracker.get(id).percent(), "下载满额封顶 75%（留余量给解压/注册）");
        assertEquals("DOWNLOADING", tracker.get(id).stage());
    }

    @Test
    void downloading_without_content_length_does_not_fake_percent() {
        String id = "p@m";
        tracker.begin(id);
        tracker.downloading(id, 123456, -1);
        // WHY: 服务端不给 Content-Length 时算不出比率 —— 只能停在起步值，
        //   假装涨到 75% 会让用户以为快装完了（规则十二 显式失败 > 掩盖）。
        assertEquals(5, tracker.get(id).percent());
        assertTrue(tracker.get(id).message().contains("KB"), "文案应给出已下载量作替代信息");
    }

    @Test
    void fail_keeps_last_percent_and_marks_failed() {
        String id = "p@m";
        tracker.begin(id);
        tracker.downloading(id, 800, 1000);

        tracker.fail(id, "网络中断");

        PluginInstallProgressTracker.Progress p = tracker.get(id);
        assertNotNull(p);
        assertEquals("FAILED", p.stage());
        assertTrue(p.percent() > 5 && p.percent() < 75, "保留失败前百分比，便于判断卡在哪一段");
        assertEquals("网络中断", p.message());
    }

    @Test
    void done_reaches_100_and_unknown_plugin_returns_null() {
        String id = "p@m";
        tracker.begin(id);
        tracker.done(id);
        assertEquals(100, tracker.get(id).percent());

        assertNull(tracker.get("never-installed@m"), "无记录 → null（前端据此停止轮询）");
        assertNull(tracker.get("  "), "空 id 不得建记录");
    }
}
