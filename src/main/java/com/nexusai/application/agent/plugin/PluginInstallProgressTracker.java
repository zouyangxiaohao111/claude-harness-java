package com.nexusai.application.agent.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件安装进度台账（前端环形进度圈数据源）。
 *
 * <p>安装是同步 HTTP 请求（POST /api/plugins/install），调用方在响应返回前拿不到任何中间态。
 * 前端另起轮询 {@code GET /api/plugins/install/progress?pluginId=...} 读本台账，即可在按钮上
 * 显示真实进度而不是无害的不确定转圈。
 *
 * <p><b>百分比口径</b>（必须说清，否则「卡在 75%」会被误读为故障）：
 * <ul>
 *   <li>2% 准备：拉取/复用市场索引</li>
 *   <li>5%~75% 下载：按 Content-Length 统计的**真实字节**进度；服务端未给长度时退化为 5% 起步</li>
 *   <li>78% 解压；85% 安装注册；95% 刷新命令/技能缓存</li>
 *   <li>100% 完成；失败置 {@link Stage#FAILED} 并带错误文案</li>
 * </ul>
 *
 * <p>只有下载段是真实字节比率，解压/注册段是阶段值 —— 不假装是精确进度。
 */
@Component
public class PluginInstallProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(PluginInstallProgressTracker.class);

    /** 完成/失败记录保留时长：前端轮询有间隔，过早删除会让最后一次轮询拿到 404 般空值。 */
    private static final long TERMINAL_TTL_MS = 120_000L;

    /** 安装阶段。 */
    public enum Stage {
        /** 准备（市场索引）。 */
        PREPARING,
        /** 下载插件包（唯一有真实字节比率的阶段）。 */
        DOWNLOADING,
        /** 解压。 */
        EXTRACTING,
        /** 安装注册。 */
        INSTALLING,
        /** 刷新命令/技能缓存。 */
        REFRESHING,
        /** 完成。 */
        DONE,
        /** 失败。 */
        FAILED
    }

    /**
     * 单插件进度快照。
     *
     * @param pluginId 插件标识（{@code name@marketplace}）
     * @param stage    阶段
     * @param percent  0~100（失败时保留失败前百分比）
     * @param message  人类可读文案（前端可直接显示）
     * @param updatedAt 更新时间（epoch millis）
     */
    public record Progress(String pluginId, String stage, int percent, String message, long updatedAt) {
    }

    private final Map<String, Progress> table = new ConcurrentHashMap<>();

    /** 开始安装：清除上一轮终态，置 PREPARING。 */
    public void begin(String pluginId) {
        put(pluginId, Stage.PREPARING, 2, "准备市场索引");
        log.info("[PluginInstallProgress] 开始 plugin={}", pluginId);
    }

    /** 下载阶段：按已读/总字节推进 5%~75%（总长未知时保持 5%）。 */
    public void downloading(String pluginId, long read, long total) {
        int percent;
        String message;
        if (total > 0) {
            double ratio = Math.min(1.0d, (double) read / (double) total);
            percent = 5 + (int) Math.round(ratio * 70d);
            message = "下载插件包 " + humanSize(read) + " / " + humanSize(total);
        } else {
            percent = 5;
            message = "下载插件包 " + humanSize(read);
        }
        put(pluginId, Stage.DOWNLOADING, percent, message);
    }

    /** 阶段推进（解压/安装/刷新）。 */
    public void stage(String pluginId, Stage stage, int percent, String message) {
        put(pluginId, stage, percent, message);
    }

    /** 完成。 */
    public void done(String pluginId) {
        put(pluginId, Stage.DONE, 100, "安装完成");
        log.info("[PluginInstallProgress] 完成 plugin={}", pluginId);
    }

    /** 失败（保留失败前百分比，便于判断卡在哪一段）。 */
    public void fail(String pluginId, String message) {
        Progress prev = table.get(pluginId);
        int percent = prev != null ? prev.percent() : 0;
        put(pluginId, Stage.FAILED, percent, message == null ? "安装失败" : message);
        log.warn("[PluginInstallProgress] 失败 plugin={} percent={} msg={}", pluginId, percent, message);
    }

    /**
     * 读取进度。
     *
     * @param pluginId 插件标识
     * @return 最近一次快照；无记录或终态超过保留期 → null（前端据此停止轮询）
     */
    public Progress get(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return null;
        }
        Progress p = table.get(pluginId.trim());
        if (p == null) {
            return null;
        }
        boolean terminal = Stage.DONE.name().equals(p.stage()) || Stage.FAILED.name().equals(p.stage());
        if (terminal && System.currentTimeMillis() - p.updatedAt() > TERMINAL_TTL_MS) {
            table.remove(pluginId.trim());
            return null;
        }
        return p;
    }

    private void put(String pluginId, Stage stage, int percent, String message) {
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        int clamped = Math.max(0, Math.min(100, percent));
        table.put(pluginId.trim(), new Progress(pluginId.trim(), stage.name(), clamped, message,
            System.currentTimeMillis()));
    }

    /** 字节数 → 人类可读（KB/MB）。 */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0d;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        return String.format("%.1f MB", kb / 1024.0d);
    }
}
