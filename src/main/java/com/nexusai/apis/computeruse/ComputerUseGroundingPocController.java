package com.nexusai.apis.computeruse;

import com.nexusai.application.agent.computeruse.ComputerUseCaptureResult;
import com.nexusai.application.agent.computeruse.ComputerUseCoordinateMode;
import com.nexusai.application.agent.computeruse.ComputerUseCoordinateScaler;
import com.nexusai.application.agent.computeruse.ComputerUseGroundingFrame;
import com.nexusai.application.agent.computeruse.ComputerUseGroundingRegionVerifier;
import com.nexusai.application.agent.computeruse.ComputerUseGroundingService;
import com.nexusai.application.agent.computeruse.ComputerUseScreenCapturer;
import com.nexusai.application.agent.computeruse.Win32ScreenCapturer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [Phase 1e] computer-use <b>视觉 grounding 手工 PoC 入口</b>（默认<b>关闭</b>）。
 *
 * <p><b>WHY 需要它</b>：grounding 降级路径的坐标精度<b>取决于视觉模型</b>，而仓库里的单测
 * 只能证明「链路通、失败 loud」，<b>证明不了「点得准」</b>⇒ 需要一个<b>用户手工</b>跑的
 * 采样器：连续采 N 次，给出每次的<b>图像像素坐标</b> / <b>屏幕像素坐标</b> /
 * 与真值的像素误差，并汇总 min / avg / max 误差。
 *
 * <p><b>⭐ 坐标语义（2026-10-31）</b>：grounding 现在回的是<b>图像像素坐标</b>（pixels 制式）
 * ⇒ 屏幕坐标由既有 {@link ComputerUseCoordinateScaler} 的 pixels 分支换算
 * （<b>本端点不自己换算</b>）。
 *
 * <p><b>开关</b>：{@code nexusai.computer-use.grounding-poc-enabled}（默认 <b>false</b>）
 * ⇒ 未开启时端点返回 <b>404</b>（不暴露、不误触）。
 *
 * <p><b>⛔ 本端点会发真实模型请求</b>（旁路调用视觉模型）⇒ 只在用户手工测量时打开。
 *
 * <p><b>用法</b>（Windows 真机）：
 * <pre>
 * curl -X POST http://127.0.0.1:&lt;port&gt;/api/v1/computer-use/grounding-poc \
 *      -H "Content-Type: application/json" \
 *      -d '{"target":"保存按钮","repeat":10,"groundTruthX":1200,"groundTruthY":450}'
 * </pre>
 * {@code groundTruthX/Y} = 目标中心在<b>屏幕物理像素</b>里的真实位置（可省 ⇒ 只回坐标不评误差）。
 *
 * <p><b>护栏② 校准字段</b>：每次采样额外报 {@code regionMad} / {@code regionDiffRatio}
 * （同一区域二次抓图的差异 = <b>噪声地板</b>）与 {@code regionMadFar}
 * （与远处另一块区域比 = <b>「内容确实变了」的信号</b>）⇒ 用于给阈值定依据。
 */
@RestController
@RequestMapping("/api/v1/computer-use")
public class ComputerUseGroundingPocController {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseGroundingPocController.class);

    /** PoC 开关（默认 false ⇒ 端点 404）。 */
    @Value("${nexusai.computer-use.grounding-poc-enabled:false}")
    private boolean pocEnabled;

    private final ComputerUseGroundingService groundingService;

    /** 截屏器（JNA native）· <b>懒加载</b>：端点未被调用时不加载 user32/gdi32。 */
    private volatile ComputerUseScreenCapturer capturer;

    /**
     * @param groundingService grounding 服务（视觉模型旁路调用）
     */
    @Autowired
    public ComputerUseGroundingPocController(ComputerUseGroundingService groundingService) {
        this.groundingService = groundingService;
    }

    /**
     * PoC 请求体。
     *
     * @param target        目标描述（如「保存按钮」）
     * @param repeat        采样次数（默认 10；上限 50）
     * @param groundTruthX  目标中心的真实屏幕 X（物理像素；可空 ⇒ 不评误差）
     * @param groundTruthY  目标中心的真实屏幕 Y（物理像素；可空 ⇒ 不评误差）
     */
    public record PocRequest(String target, Integer repeat, Double groundTruthX, Double groundTruthY) {
    }

    /**
     * PoC 采样 · POST /api/v1/computer-use/grounding-poc
     *
     * @param request 请求体
     * @return 采样结果（每次坐标 + 误差汇总）
     */
    @PostMapping("/grounding-poc")
    public ResponseEntity<Map<String, Object>> groundingPoc(@RequestBody PocRequest request) {
        if (!pocEnabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "grounding PoC 未开启",
                    "hint", "设置 nexusai.computer-use.grounding-poc-enabled=true 后重启后端再试"));
        }
        if (request == null || request.target() == null || request.target().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "缺少 target（要定位的界面元素描述）"));
        }
        int repeat = request.repeat() == null ? 10 : Math.max(1, Math.min(50, request.repeat()));

        List<Map<String, Object>> samples = new ArrayList<>(repeat);
        List<Double> errors = new ArrayList<>();
        for (int i = 1; i <= repeat; i++) {
            Map<String, Object> sample = runOnce(request, i);
            samples.add(sample);
            Object err = sample.get("errorPx");
            if (err instanceof Number n) {
                errors.add(n.doubleValue());
            }
            if (log.isInfoEnabled()) {
                log.info("[computer-use][PoC] 第 {}/{} 次采样: {}", i, repeat, sample);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("samples", repeat);
        summary.put("measured", errors.size());
        if (!errors.isEmpty()) {
            double min = errors.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = errors.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double avg = errors.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            summary.put("minErrorPx", round(min));
            summary.put("avgErrorPx", round(avg));
            summary.put("maxErrorPx", round(max));
        } else {
            summary.put("minErrorPx", null);
            summary.put("avgErrorPx", null);
            summary.put("maxErrorPx", null);
            summary.put("note", (request.groundTruthX() != null && request.groundTruthY() != null)
                    ? "本次没有任何一次采样成功（status 不是 FOUND）⇒ 不评误差"
                    : "未提供 groundTruthX/groundTruthY ⇒ 本次不评误差");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", request.target());
        out.put("summary", summary);
        out.put("results", samples);
        return ResponseEntity.ok(out);
    }

    /** 单次采样：截屏 → grounding（图像像素）→ 换算屏幕像素 → 与真值比误差。 */
    private Map<String, Object> runOnce(PocRequest request, int index) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("index", index);
        ComputerUseCaptureResult capture;
        try {
            capture = capturer().capture();
        } catch (Exception e) {
            sample.put("ok", false);
            sample.put("error", "截屏失败: " + e.getMessage());
            return sample;
        }
        sample.put("imageWidth", capture.imageWidth());
        sample.put("imageHeight", capture.imageHeight());
        sample.put("displayWidth", capture.displayWidth());
        sample.put("displayHeight", capture.displayHeight());

        ComputerUseGroundingService.GroundingOutcome outcome =
                groundingService.ground(capture, request.target(), null);
        sample.put("status", outcome.status().name());
        sample.put("ok", outcome.found());
        if (!outcome.found()) {
            sample.put("error", outcome.errorMessage());
            sample.put("detail", outcome.detail());
            return sample;
        }
        double x = outcome.xPixels();
        double y = outcome.yPixels();
        // ⛔ 屏幕坐标一律由既有 scaler 换算（pixels 分支），本端点不自己换算
        int[] screen = ComputerUseCoordinateScaler.scaleCoord(
                x, y, ComputerUseCoordinateMode.PIXELS, capture.geometry());
        sample.put("xPixels", x);
        sample.put("yPixels", y);
        sample.put("pixelX", screen[0]);
        sample.put("pixelY", screen[1]);
        sample.put("target", outcome.target());
        if (outcome.detail() != null && !outcome.detail().isBlank()) {
            sample.put("detail", outcome.detail());
        }
        measureRegion(capture, outcome, sample);
        if (request.groundTruthX() != null && request.groundTruthY() != null) {
            double dx = screen[0] - request.groundTruthX();
            double dy = screen[1] - request.groundTruthY();
            sample.put("errorPx", round(Math.sqrt(dx * dx + dy * dy)));
        }
        return sample;
    }

    /**
     * 护栏② 的<b>阈值校准</b>测量（不拦截，只报数）：
     * <ul>
     *   <li>{@code regionMad} / {@code regionDiffRatio} = 同一区域二次抓图的差异（<b>噪声地板</b>）；</li>
     *   <li>{@code regionMadFar} = 与远处另一块区域比（<b>「内容确实变了」的信号</b>）。</li>
     * </ul>
     *
     * @param capture 本次截图
     * @param outcome grounding 结果
     * @param sample  采样结果（就地写入）
     */
    private void measureRegion(ComputerUseCaptureResult capture,
                               ComputerUseGroundingService.GroundingOutcome outcome,
                               Map<String, Object> sample) {
        try {
            ComputerUseGroundingFrame frame = new ComputerUseGroundingFrame(
                    capture, outcome.xPixels(), outcome.yPixels(), outcome.target());
            ComputerUseGroundingRegionVerifier.RegionDiff same =
                    ComputerUseGroundingRegionVerifier.measure(
                            capturer(), frame, outcome.xPixels(), outcome.yPixels(),
                            ComputerUseGroundingRegionVerifier.DEFAULT_REGION_SIZE);
            sample.put("regionMad", round(same.meanAbsDiff()));
            sample.put("regionDiffRatio", round(same.diffPixelRatio()));

            // 「远处另一块区域」= 内容确实不同的对照 ⇒ 给出「变了」的信号强度
            double farX = outcome.xPixels() + 260;
            double farY = outcome.yPixels() + 160;
            ComputerUseGroundingRegionVerifier.RegionDiff far =
                    ComputerUseGroundingRegionVerifier.measure(
                            capturer(), frame, farX, farY,
                            ComputerUseGroundingRegionVerifier.DEFAULT_REGION_SIZE);
            sample.put("regionMadFar", round(far.meanAbsDiff()));
            sample.put("regionDiffRatioFar", round(far.diffPixelRatio()));
        } catch (Exception e) {
            sample.put("regionError", e.getMessage());
        }
    }

    /** 懒加载截屏器（JNA native 只在真正调用时加载）。 */
    private ComputerUseScreenCapturer capturer() {
        ComputerUseScreenCapturer local = capturer;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (capturer == null) {
                capturer = new Win32ScreenCapturer();
            }
            return capturer;
        }
    }

    /** 保留两位小数（读数友好）。 */
    private static Double round(double v) {
        return Math.round(v * 100.0d) / 100.0d;
    }
}
