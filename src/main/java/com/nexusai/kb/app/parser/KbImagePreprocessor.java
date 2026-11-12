package com.nexusai.kb.app.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫描页 / 图片的入模前预处理 · <b>白边裁剪 + 小角度纠偏</b>（纯 JDK，无 OpenCV / RapidOCR）。
 *
 * <p><b>对齐源站</b>：源站 docreader 有 {@code parser/image_preprocess.py}，用 RapidOCR 的文本框并集
 * 估计倾斜角。本仓没有 OCR 引擎，故不估计「文本框」，而是直接用<b>水平投影方差</b>当倾斜判据 ——
 * 文本行对齐时行内黑像素挤在少数几条扫描线上、行间空隙把它们分开，投影方差最大；倾斜时
 * 黑像素被摊平到更多行，方差变小。这个判据只需要 {@link BufferedImage#getRGB}，不引任何依赖。
 *
 * <p><b>为什么值得做</b>：扫描页 / 手机拍照常带几十像素白边、整体歪 1~3°，视觉模型对这两种噪声
 * 都会掉质量（白边占 token 预算、倾斜让行与行串在一起）。预处理只在「送视觉」这一处生效，
 * ⛔ 不改解析产物的坐标 / 分块（视觉模型不回传 bbox，本仓也不伪造）。
 *
 * <p><b>fail-soft（硬约束）</b>：读不出图、写不出文件、任何异常，一律<b>返回原路径</b>并 WARN ——
 * 预处理只是增益，不该让一次入库白费（与 {@link KbPdfParser} / {@link KbImageParser} 的降级语义一致）。
 *
 * <p><b>开关</b>：{@code nexusai.knowledge.image.preprocess-enabled}（缺省 true）。关闭时
 * {@link #preprocess(Path, Path)} 直接返回原路径，不读像素、不落盘。
 *
 * <p><b>为什么工具方法是 static 而开关走实例方法</b>：裁剪 / 纠偏 / 预处理都是<b>纯函数</b>
 * （无状态、可在并发任务里安全调用），离线单测不需要 Spring 容器；而开关来自 Spring 配置，只能在
 * bean 初始化时注入一次。折中：{@code @Value} 写在实例方法上（Spring 建 bean 时调用一次），
 * 值落到 static 字段 {@link #enabled}，供 static 的 {@link #preprocess(Path, Path)} 读取。
 * ⛔ 不用构造器注入：两个解析器的构造器被离线单测直接 new（{@code new KbPdfParser(null)}），
 * 加参数会破坏既有用例。
 */
@Component
public class KbImagePreprocessor {

    private static final Logger log = LoggerFactory.getLogger(KbImagePreprocessor.class);

    /** 「近白」默认容差：RGB 三通道都 &gt;= 255-12=243 即视为背景。 */
    static final int DEFAULT_WHITE_THRESHOLD = 12;

    /** 默认最大纠偏角（度）· 只处理「小角度」：大角度旋转会切角丢字，且多是版式有意义的横排页。 */
    static final double DEFAULT_MAX_ANGLE = 5.0;

    /** 默认角度步长（度）· 2*3.0/0.5 = 13 个角度各打一次分，够用且不拖慢入库。 */
    static final double DEFAULT_STEP = 0.5;

    /** 输出 JPEG 质量 · 视觉模型对 0.85 的重采样不敏感，比 PNG 省一半以上字节。 */
    static final float JPEG_QUALITY = 0.85f;

    /**
     * 打分用的降采样长边上限。
     *
     * <p><b>WHY 必须降采样</b>：页图长边可达 2000px（{@code render-max-edge}），4M 前景像素 × 13 个角度
     * 的浮点投影会让单页预处理吃掉几秒；投影只关心「行对齐」的统计特性，800px 长边足以判到 0.5°。
     */
    static final int SCORE_MAX_EDGE = 800;

    /** 预处理总开关（Spring 初始化时注入一次；离线单测用 {@link #setEnabledForTest(boolean)}）。 */
    private static volatile boolean enabled = true;

    /** 「已关闭」只提示一次，避免一份 200 页 PDF 刷 200 行同样的日志。 */
    private static final AtomicBoolean DISABLED_LOGGED = new AtomicBoolean();

    /**
     * Spring 注入开关 · 写到 static 字段（见类 javadoc 的折中说明）。
     */
    @Value("${nexusai.knowledge.image.preprocess-enabled:true}")
    void applyEnabled(boolean value) {
        enabled = value;
        log.info("KbImagePreprocessor: 图片预处理开关={}（nexusai.knowledge.image.preprocess-enabled）", value);
    }

    /** 开关当前值（供调用方 / 单测观察）。 */
    static boolean isEnabled() {
        return enabled;
    }

    /** 离线单测改开关（包内可见；⛔ 生产代码别调 —— 配置只从 {@code @Value} 来）。 */
    static void setEnabledForTest(boolean value) {
        enabled = value;
        if (value) {
            DISABLED_LOGGED.set(false);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ① 白边裁剪
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 裁掉四周近白边 · 返回非白内容的包围盒。
     *
     * <p>「近白」= RGB 三通道都 {@code >= 255-threshold}（JPEG 压缩 / 扫描噪点会让背景不是纯 255）。
     * 带 alpha 的全透明像素（alpha=0）同样按背景处理 —— 那是 PNG 的「空」。
     *
     * <p><b>全白 / 无内容</b>：返回<b>原对象</b>（⛔ 不返回 0×0 空图：视觉模型拿到空图必报错）。
     *
     * @param src       源图（null 原样返回）
     * @param threshold 容差（&lt;=0 按 0 处理即只认纯白；&gt;255 按 254 封顶，免得整图变背景）
     * @return 裁剪后的新图；无需裁剪时与原图同对象
     */
    static BufferedImage trimWhiteBorder(BufferedImage src, int threshold) {
        if (src == null) {
            return null;
        }
        int limit = 255 - Math.max(0, Math.min(254, threshold));
        int w = src.getWidth();
        int h = src.getHeight();
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        // 单趟求包围盒 ≡「从四边同时向内扫到第一个非白像素」，一次遍历、无重复判定。
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isNearWhite(src.getRGB(x, y), limit)) {
                    continue;
                }
                if (x < minX) {
                    minX = x;
                }
                if (x > maxX) {
                    maxX = x;
                }
                if (y < minY) {
                    minY = y;
                }
                if (y > maxY) {
                    maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            // 整张近白 ⇒ 没有内容可裁，保留原图（上层仍要送视觉，空图会直接失败）
            return src;
        }
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) {
            return src;
        }
        int cw = maxX - minX + 1;
        int ch = maxY - minY + 1;
        // 统一成 ARGB / RGB 普通图：getSubimage 会与父图共享像素缓冲，后续旋转 / 写盘不安全。
        BufferedImage out = new BufferedImage(cw, ch,
            src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, cw, ch, minX, minY, maxX + 1, maxY + 1, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ② 小角度纠偏
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 小角度纠偏 · 在 {@code [-maxAngle, +maxAngle]} 按 {@code step} 逐个角度试转，
     * 取「水平投影方差最大」的角度把图转回来。
     *
     * <p>最优角与 0 的差小于 {@code step/2} 时<b>返回原对象</b>：转了也白转，还要付一次插值重采样
     * （重采样会糊笔画，视觉模型不一定更喜欢）。
     *
     * @param src      源图（null 原样返回）
     * @param maxAngle 最大角（度，取绝对值；&lt;=0 视为不纠偏）
     * @param step     角度步长（度；&lt;=0 回落 {@link #DEFAULT_STEP}）
     * @return 纠偏后的新图；无需纠偏时与原图同对象
     */
    static BufferedImage deskew(BufferedImage src, double maxAngle, double step) {
        return deskewMeasured(src, maxAngle, step).image();
    }

    /** 纠偏结果：图 + 实际采用的旋转角（度）· 日志要报角度，故内部把角度带出来。 */
    record DeskewResult(BufferedImage image, double angle) {
    }

    /** {@link #deskew} 的实现体 · 额外返回「采用的角度」供日志使用。 */
    static DeskewResult deskewMeasured(BufferedImage src, double maxAngle, double step) {
        if (src == null) {
            return new DeskewResult(null, 0);
        }
        double max = Math.abs(maxAngle);
        double stride = step > 0 ? step : DEFAULT_STEP;
        if (max <= 0 || src.getWidth() < 2 || src.getHeight() < 2) {
            return new DeskewResult(src, 0);
        }

        // ── ① 降采样出前景点集（白底黑字 ⇒ 前景 = 非近白像素）──
        int sw = src.getWidth();
        int sh = src.getHeight();
        double scale = Math.min(1.0, SCORE_MAX_EDGE / (double) Math.max(sw, sh));
        int mw = Math.max(1, (int) Math.round(sw * scale));
        int mh = Math.max(1, (int) Math.round(sh * scale));
        int limit = 255 - DEFAULT_WHITE_THRESHOLD;
        int[] px = new int[mw * mh];
        int[] py = new int[mw * mh];
        int n = 0;
        for (int j = 0; j < mh; j++) {
            int sy = Math.min(sh - 1, (int) (j / scale));
            for (int i = 0; i < mw; i++) {
                int sx = Math.min(sw - 1, (int) (i / scale));
                if (isNearWhite(src.getRGB(sx, sy), limit)) {
                    continue;
                }
                px[n] = i;
                py[n] = j;
                n++;
            }
        }
        if (n == 0) {
            // 全白：任何角度的投影恒为 0、方差也是 0 ⇒ 不转
            return new DeskewResult(src, 0);
        }

        // ── ② 逐角度打分，取最大 ──
        double bestAngle = 0;
        double bestScore = -1;
        int steps = (int) Math.floor((2 * max) / stride + 1e-9);
        for (int k = 0; k <= steps; k++) {
            double angle = -max + k * stride;
            double score = projectionVariance(px, py, n, mw, mh, angle);
            if (score > bestScore) {
                bestScore = score;
                bestAngle = angle;
            }
        }

        if (Math.abs(bestAngle) < stride / 2.0) {
            return new DeskewResult(src, 0);
        }
        // 峰值落在搜索边界 ⇒ 方差随角度单调上升、没有真正的「行对齐」尖峰（典型：整块实心色块、
        // 满幅照片、只有几条长框线的表格）。这种图的最优角不可信，转了只会平白糊掉笔画 ⇒ 不转。
        if (Math.abs(Math.abs(bestAngle) - max) < 1e-9) {
            return new DeskewResult(src, 0);
        }
        return new DeskewResult(rotate(src, bestAngle), bestAngle);
    }

    /**
     * 按角度做水平投影，返回行计数的方差（越大 = 黑像素越集中在少数行 = 行越对齐）。
     *
     * <p>坐标变换必须与 {@link Graphics2D#rotate(double)} 一致（屏幕坐标 y 向下）：
     * {@code y' = x*sin(a) + y*cos(a)}。这样「打分最优的角度」直接就是「Graphics2D 该转的角度」，
     * 不需要再猜正负号。
     */
    private static double projectionVariance(int[] px, int[] py, int n, int w, int h, double angleDeg) {
        double rad = Math.toRadians(angleDeg);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        // y' 在矩形四角上的取值范围（x / y 可分离 ⇒ 分别取 min / max 再相加）
        double minY = Math.min(0, (w - 1) * sin) + Math.min(0, (h - 1) * cos);
        double maxY = Math.max(0, (w - 1) * sin) + Math.max(0, (h - 1) * cos);
        int rows = (int) Math.floor(maxY - minY) + 1;
        if (rows <= 0) {
            return 0;
        }
        int[] hist = new int[rows];
        for (int i = 0; i < n; i++) {
            int r = (int) Math.round(px[i] * sin + py[i] * cos - minY);
            if (r < 0) {
                r = 0;
            } else if (r >= rows) {
                r = rows - 1;
            }
            hist[r]++;
        }
        double mean = (double) n / rows;
        double sum = 0;
        for (int r = 0; r < rows; r++) {
            double d = hist[r] - mean;
            sum += d * d;
        }
        return sum / rows;
    }

    /**
     * 以图心为轴旋转，画面自动扩张到装下整幅图（空出的角填白）。
     *
     * <p>⛔ 不要改成「旋转后裁回原尺寸」：角度虽小，四角一旦被切会丢掉页眉 / 页脚边缘的文字。
     */
    private static BufferedImage rotate(BufferedImage src, double angleDeg) {
        double rad = Math.toRadians(angleDeg);
        double sin = Math.abs(Math.sin(rad));
        double cos = Math.abs(Math.cos(rad));
        int w = src.getWidth();
        int h = src.getHeight();
        int nw = Math.max(1, (int) Math.ceil(w * cos + h * sin));
        int nh = Math.max(1, (int) Math.ceil(w * sin + h * cos));
        BufferedImage out = new BufferedImage(nw, nh,
            src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.translate(nw / 2.0, nh / 2.0);
            g.rotate(rad);
            g.translate(-w / 2.0, -h / 2.0);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════
    // ③ 入口：读 → trim → deskew → 写 JPEG
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 预处理一张图并落盘：读 → {@link #trimWhiteBorder} → {@link #deskew} → 写 JPEG(0.85)。
     *
     * <p>输出文件名 = 原名主干 + {@code -pre.jpg}，落在 {@code outDir}（不存在则创建）。
     *
     * <p><b>开关关闭 / 读不出图 / 任何异常 ⇒ 返回 {@code in}</b>（fail-soft，WARN 留痕）。
     * 调用方只需把返回值当「送视觉的路径」，不用判空、不用自己判开关。
     *
     * @param in     原始图片路径
     * @param outDir 预处理产物目录（图片引用仍指原图，故这里应当是临时 / 工作目录）
     * @return 预处理后的路径；未处理时与 {@code in} 是同一个 Path
     */
    static Path preprocess(Path in, Path outDir) {
        if (in == null) {
            return null;
        }
        if (!enabled) {
            if (DISABLED_LOGGED.compareAndSet(false, true)) {
                log.info("KbImagePreprocessor: 图片预处理已关闭"
                    + "（nexusai.knowledge.image.preprocess-enabled=false），原图直送视觉");
            }
            return in;
        }
        long startedAt = System.nanoTime();
        try {
            BufferedImage src = ImageIO.read(in.toFile());
            if (src == null) {
                log.warn("KbImagePreprocessor: 无法解码图片，回退原图 file={}", in.getFileName());
                return in;
            }
            try {
                int w0 = src.getWidth();
                int h0 = src.getHeight();
                BufferedImage trimmed = trimWhiteBorder(src, DEFAULT_WHITE_THRESHOLD);
                DeskewResult dk = deskewMeasured(trimmed, DEFAULT_MAX_ANGLE, DEFAULT_STEP);
                BufferedImage result = dk.image();
                Path out = outDir.resolve(stem(in) + "-pre.jpg");
                Files.createDirectories(outDir);
                writeJpeg(result, out, JPEG_QUALITY);
                long ms = (System.nanoTime() - startedAt) / 1_000_000L;
                log.info("KbImagePreprocessor: 预处理完成 file={} 启用={} 尺寸={}x{}→{}x{} 纠偏角度={}° 耗时={}ms out={}",
                    in.getFileName(), enabled, w0, h0, result.getWidth(), result.getHeight(),
                    formatAngle(dk.angle()), ms, out.getFileName());
                return out;
            } finally {
                src.flush();
            }
        } catch (Exception e) {
            // fail-soft：预处理只是增益，不能让一次入库失败
            log.warn("KbImagePreprocessor: 预处理失败，回退原图 file={} 原因={}", in.getFileName(), e.getMessage());
            return in;
        }
    }

    /** 角度文案：一位小数（步长 0.5，一位足够；⛔ 别打 3.0000000000000004 这种）。 */
    private static String formatAngle(double angle) {
        return String.format(Locale.ROOT, "%.1f", angle);
    }

    /** 文件名里不能出现的字符（等价 `[/\\:*?"<>|] + 空白`，用常量表避开正则转义）。 */
    private static final char[] UNSAFE_NAME_CHARS = {'\\', '/', ':', '*', '?', '"', '<', '>', '|'};

    /** 文件名主干（去扩展名 + 去掉路径不友好字符；⛔ 不保留子目录）。 */
    private static String stem(Path file) {
        String name = file.getFileName() == null ? "image" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String s = dot > 0 ? name.substring(0, dot) : name;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(isUnsafeNameChar(c) || Character.isWhitespace(c) ? '_' : c);
        }
        return sb.toString();
    }

    private static boolean isUnsafeNameChar(char c) {
        for (char u : UNSAFE_NAME_CHARS) {
            if (u == c) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写 JPEG（显式 quality）· 源图若是 ARGB / 带透明，先铺白底再画（JPEG 不支持 alpha）。
     */
    private static void writeJpeg(BufferedImage img, Path out, float quality) throws IOException {
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g.drawImage(img, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            // 理论上是 JDK 自带；真没有就退回默认写盘（无 quality 参数，但仍是合法 JPEG）
            if (!ImageIO.write(rgb, "jpeg", out.toFile())) {
                throw new IOException("JPEG writer unavailable");
            }
            return;
        }
        ImageWriter writer = writers.next();
        try (OutputStream os = Files.newOutputStream(out);
             ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            if (ios == null) {
                throw new IOException("createImageOutputStream 返回 null");
            }
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /** 近白判定：alpha=0（PNG 的「空」）或 RGB 三通道都 &gt;= limit。 */
    private static boolean isNearWhite(int argb, int limit) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0) {
            return true;
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r >= limit && g >= limit && b >= limit;
    }
}
