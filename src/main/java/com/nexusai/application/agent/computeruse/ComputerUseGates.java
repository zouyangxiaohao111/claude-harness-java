package com.nexusai.application.agent.computeruse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * computer-use 子开关（<b>8 个 CCB 对齐</b> + <b>1 个本仓特有</b>）· 对齐 CCB 2.8.4
 * {@code src/utils/computerUse/gates.ts:11-20} 的 {@code DEFAULTS}。
 *
 * <p><b>[Phase 1e] 第 9 个开关 = {@code groundingFallbackEnabled}</b>
 * （{@code nexusai.computer-use.grounding-fallback-enabled}，默认 <b>false</b>）——
 * <b>CCB 无对应</b>（CCB 的 computer-use 假定主模型恒为能看图的 Claude）。
 * 打开后：主模型<b>确定不支持</b>视觉时，截图工具改走
 * {@link ComputerUseGroundingService}（视觉模型 grounding ⇒ <b>图像像素坐标</b>，
 * 与主链路的 {@code pixels} 模式同制式 ⇒ <b>不改变坐标模式</b>），
 * 而不是直接报错。
 *
 * <p><b>CCB 原形状</b>：
 * <pre>
 * const DEFAULTS: ChicagoConfig = {
 *   enabled: true,
 *   pixelValidation: false,
 *   clipboardPasteMultiline: true,
 *   mouseAnimation: true,
 *   hideBeforeAction: true,
 *   autoTargetDisplay: true,
 *   clipboardGuard: true,
 *   coordinateMode: 'pixels',
 * }
 * </pre>
 *
 * <h2>与 CCB 的差异（<b>有意为之</b>，如实标注）</h2>
 * <ul>
 *   <li>{@code enabled}：CCB 默认 <b>true</b>；本仓默认 <b>false</b>（yml
 *       {@code nexusai.feature.computer-use}）—— computer-use 能控制用户桌面，
 *       出包默认关是本仓的<b>安全选择</b>，不是 CC 行为差异。</li>
 *   <li>其余 7 个开关：<b>取值与 CCB 默认一致</b>，但<b>生效范围</b>见下表
 *       （本批只要求「读得到」，不要求全部生效）。</li>
 * </ul>
 *
 * <h2>Phase 1c 生效状态（逐个核实 CCB 在 Windows 的实际行为）</h2>
 * <table border="1">
 *   <caption>8 个子开关的 Phase 1c 生效状态</caption>
 *   <tr><th>开关</th><th>CCB 默认</th><th>本仓默认</th><th>Phase 1c 生效？</th><th>依据</th></tr>
 *   <tr><td>{@code enabled}</td><td>true</td><td><b>false</b></td><td><b>生效</b>（工具注册门）</td>
 *       <td>—</td></tr>
 *   <tr><td>{@code pixelValidation}</td><td>false</td><td>false</td>
 *       <td><b>不做</b>（CCB Windows 侧本就无实际效果）</td>
 *       <td>CCB {@code src/utils/computerUse/hostAdapter.ts:115} 的 {@code cropRawPatch: () => null}
 *           ⇒ 校验器拿不到 patch ⇒ 按设计跳过（{@code pixelCompare.ts} 的
 *           {@code PixelCompareResult.skipped}）</td></tr>
 *   <tr><td>{@code clipboardPasteMultiline}</td><td>true</td><td>true</td>
 *       <td><b>不做</b>（CCB Windows 侧本就无实际效果）</td>
 *       <td>CCB {@code src/utils/computerUse/executorCrossPlatform.ts:315-317}
 *           {@code async type(text, _opts)} <b>忽略</b> {@code viaClipboard}
 *           ⇒ Windows 永远走 {@code typeText}，剪贴板快路径（含读回校验 / 100ms 等待 /
 *           剪贴板恢复）整条不发生</td></tr>
 *   <tr><td>{@code mouseAnimation}</td><td>true</td><td>true</td>
 *       <td><b>已接线</b>（缓动组件已实现；消费方=拖拽工具，尚未在本批工具面内）</td>
 *       <td>CCB {@code src/utils/computerUse/executor.ts:277-312} 的 {@code animatedMove}
 *           （ease-out-cubic / 60fps / 2000px/s / 0.5s 上限）</td></tr>
 *   <tr><td>{@code hideBeforeAction}</td><td>true</td><td>true</td>
 *       <td><b>不做</b>（CCB Windows 侧本就无此行为）</td>
 *       <td>CCB {@code src/utils/computerUse/executorCrossPlatform.ts:217-219}
 *           {@code prepareForAction(): Promise&lt;string[]&gt; { return [] }} ⇒ 恒空数组，
 *           不隐藏任何窗口</td></tr>
 *   <tr><td>{@code autoTargetDisplay}</td><td>true</td><td>true</td>
 *       <td><b>不做</b>（CCB Windows 侧本就无此行为）</td>
 *       <td>CCB {@code src/utils/computerUse/platforms/win32.ts:481-488}
 *           {@code getSize} 未指定 displayId 时直接取 {@code all[0]}
 *           ⇒ 没有"按应用位置自动选屏"</td></tr>
 *   <tr><td>{@code clipboardGuard}</td><td>true</td><td>true</td>
 *       <td><b>不做</b>（CCB Windows 侧只有「逐动作」那一半不可达）</td>
 *       <td>CCB {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:475-477}：
 *           {@code screenshotFiltering === 'none'}（Windows/Linux 恒真，
 *           见 {@code src/utils/computerUse/common.ts:55-57}）⇒ {@code runInputActionGates}
 *           <b>提前 return</b> ⇒ 其内的「逐动作」{@code clipboardGuard} 分支不可达；
 *           <br>⚠️ 但 {@code handleReadClipboard}（{@code toolCalls.ts:3563-3574}）与
 *           {@code handleWriteClipboard}（{@code toolCalls.ts:3593-3623}）里
 *           <b>各自独立</b>的 {@code if (subGates.clipboardGuard)} 分支<b>仍可达</b>
 *           ⇒ 那两条本仓<b>未实现</b>（需会话级剪贴板暂存器）</td></tr>
 *   <tr><td>{@code coordinateMode}</td><td>'pixels'</td><td>'pixels'</td>
 *       <td><b>生效</b>（描述随模式切换 + 换算按模式 + 首次读取即冻结）</td>
 *       <td>CCB {@code src/utils/computerUse/gates.ts:66-72} 的 {@code frozenCoordinateMode}</td></tr>
 * </table>
 */
@Component
public class ComputerUseGates {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseGates.class);

    /** 总开关 · yml {@code nexusai.feature.computer-use}（默认 false —— 出包安全选择）。 */
    @Value("${nexusai.feature.computer-use:false}")
    private boolean enabled;

    /** 9×9 逐字节陈旧性校验（点击前）· CCB {@code gates.ts:13}。 */
    @Value("${nexusai.computer-use.pixel-validation:false}")
    private boolean pixelValidation;

    /** 多行 type 走剪贴板快路径 · CCB {@code gates.ts:14}。 */
    @Value("${nexusai.computer-use.clipboard-paste-multiline:true}")
    private boolean clipboardPasteMultiline;

    /** 鼠标缓动（60fps ease-out-cubic）· CCB {@code gates.ts:15}。 */
    @Value("${nexusai.computer-use.mouse-animation:true}")
    private boolean mouseAnimation;

    /** 动作前隐藏非授权窗口 · CCB {@code gates.ts:16}。 */
    @Value("${nexusai.computer-use.hide-before-action:true}")
    private boolean hideBeforeAction;

    /** 多显示器自动选屏 · CCB {@code gates.ts:17}。 */
    @Value("${nexusai.computer-use.auto-target-display:true}")
    private boolean autoTargetDisplay;

    /** 剪贴板守卫（点击档应用前台时暂存并清空剪贴板）· CCB {@code gates.ts:18}。 */
    @Value("${nexusai.computer-use.clipboard-guard:true}")
    private boolean clipboardGuard;

    /** 坐标模式 · CCB {@code gates.ts:19}（'pixels' | 'normalized_0_100'）。 */
    @Value("${nexusai.computer-use.coordinate-mode:pixels}")
    private String coordinateMode;

    /**
     * [Phase 1e] <b>视觉 grounding 降级路径</b>开关 · <b>本仓特有</b>（CCB <b>无对应</b>）。
     *
     * <p><b>WHY 需要</b>：主模型不支持视觉时，CU 截图对它是「黑盒」⇒ 点击坐标没有来源。
     * 打开本开关 ⇒ 截图工具改走 {@link ComputerUseGroundingService}：把截图交给本仓配置的
     * 视觉模型（{@code settings.multimodal_model_name}）定位目标，回<b>图像像素坐标</b>。
     *
     * <p><b>⭐ 开启后【不再】改变坐标模式</b>：降级路径与主路径<b>统一用 {@code pixels}</b> ——
     * 模型看到的那张图就是 CU 送出的那张缩放图 ⇒ 它回的「图上像素」天然就是 CCB
     * {@code pixels} 制式（{@code tools.ts:22-23}）⇒ 无需再切成百分比
     * （2026-10-31 用户裁定：视觉模型是多模态的，应像主模型一样走 pixels 模式）。
     *
     * <p><b>默认 false</b>：①CCB 无此路径（默认行为应对齐）；②降级路径的定位精度取决于
     * 视觉模型，<b>默认关</b> ⇒ 生产语义不变（视觉闸判「确定不支持」时仍 fail loud 报错）。
     */
    @Value("${nexusai.computer-use.grounding-fallback-enabled:false}")
    private boolean groundingFallbackEnabled;

    /**
     * 护栏② · <b>点击前落点校验</b>开关（<b>本仓特有</b> · CCB 无对应）。
     *
     * <p>默认 <b>true</b>：对 grounding 降级路径的落点区域做二次截图比对，
     * 差异超阈值 ⇒ 拒绝点击。⚠️ 无 GUI 环境（headless / CI）可置 false。
     */
    @Value("${nexusai.computer-use.grounding-region-verify-enabled:true}")
    private boolean groundingRegionVerifyEnabled;

    /** 护栏② · 比对区域边长（<b>图像像素</b>；默认 60）。 */
    @Value("${nexusai.computer-use.grounding-region-size:60}")
    private int groundingRegionSize;

    /** 护栏② · 平均绝对差（MAD）阈值（灰度 0–255；默认 15）。 */
    @Value("${nexusai.computer-use.grounding-region-mad-threshold:15.0}")
    private double groundingRegionMadThreshold;

    /** 护栏② · 差异像素占比阈值（0.0–1.0；默认 0.35）。 */
    @Value("${nexusai.computer-use.grounding-region-diff-ratio-threshold:0.35}")
    private double groundingRegionDiffRatioThreshold;

    /** 护栏③ · 系统性偏移补偿 X（<b>屏幕物理像素</b>；默认 0 = 不启用）。 */
    @Value("${nexusai.computer-use.grounding-offset-x:0.0}")
    private double groundingOffsetX;

    /** 护栏③ · 系统性偏移补偿 Y（<b>屏幕物理像素</b>；默认 0 = 不启用）。 */
    @Value("${nexusai.computer-use.grounding-offset-y:0.0}")
    private double groundingOffsetY;

    /**
     * 冻结后的坐标模式（首次读取即冻结 · CCB {@code gates.ts:66-72} 的
     * {@code frozenCoordinateMode}）。
     */
    private volatile ComputerUseCoordinateMode frozenCoordinateMode;

    /**
     * @return 总开关
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * @return 像素校验开关
     */
    public boolean pixelValidation() {
        return pixelValidation;
    }

    /**
     * @return 多行粘贴走剪贴板开关
     */
    public boolean clipboardPasteMultiline() {
        return clipboardPasteMultiline;
    }

    /**
     * @return 鼠标缓动开关
     */
    public boolean mouseAnimation() {
        return mouseAnimation;
    }

    /**
     * @return 动作前隐藏非授权窗口开关
     */
    public boolean hideBeforeAction() {
        return hideBeforeAction;
    }

    /**
     * @return 自动选屏开关
     */
    public boolean autoTargetDisplay() {
        return autoTargetDisplay;
    }

    /**
     * @return 剪贴板守卫开关
     */
    public boolean clipboardGuard() {
        return clipboardGuard;
    }

    /**
     * @return 坐标模式（'pixels' | 'normalized_0_100'；非法值回落 {@code pixels}）
     *
     * <p><b>冻结语义（对齐 CCB {@code src/utils/computerUse/gates.ts:66-72}）</b>：
     * <pre>
     * let frozenCoordinateMode: CoordinateMode | undefined
     * export function getChicagoCoordinateMode(): CoordinateMode {
     *   frozenCoordinateMode ??= readConfig().coordinateMode
     *   return frozenCoordinateMode
     * }
     * </pre>
     * CCB 注释原文：“Frozen at first read — setup.ts builds tool descriptions and
     * executor.ts scales coordinates off the same value. A live read here lets a mid-session
     * GB flip tell the model "pixels" while transforming clicks as normalized.”
     * <br>⇒ 本仓<b>首次读取即冻结</b>：工具描述（构造期烤进 inputSchema）与
     * {@code scaleCoord}（执行期换算）必须读<b>同一个</b>值，否则点击落在错误空间。
     */
    public ComputerUseCoordinateMode coordinateMode() {
        ComputerUseCoordinateMode cached = frozenCoordinateMode;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (frozenCoordinateMode == null) {
                frozenCoordinateMode = "normalized_0_100".equalsIgnoreCase(coordinateMode)
                        ? ComputerUseCoordinateMode.NORMALIZED_0_100
                        : ComputerUseCoordinateMode.PIXELS;
                if (log.isInfoEnabled()) {
                    log.info("[computer-use] 坐标模式已冻结: {}", frozenCoordinateMode.wire());
                }
            }
            return frozenCoordinateMode;
        }
    }

    /**
     * [Phase 1e] 视觉 grounding 降级路径开关（<b>本仓特有</b> · CCB 无对应）。
     *
     * @return true = 打开降级路径（主模型不支持视觉时改走 grounding）
     */
    public boolean groundingFallbackEnabled() {
        return groundingFallbackEnabled;
    }

    /**
     * 护栏② · 落点校验参数（组装成 {@link ComputerUseRegionVerifyConfig} 交给点击工具）。
     *
     * @return 护栏② 参数
     */
    public ComputerUseRegionVerifyConfig regionVerifyConfig() {
        return new ComputerUseRegionVerifyConfig(
                groundingRegionVerifyEnabled,
                groundingRegionSize,
                groundingRegionMadThreshold,
                groundingRegionDiffRatioThreshold);
    }

    /**
     * 护栏③ · 系统性偏移补偿 X（<b>屏幕物理像素</b>；0 = 不启用）。
     *
     * @return 偏移 X
     */
    public double groundingOffsetX() {
        return groundingOffsetX;
    }

    /**
     * 护栏③ · 系统性偏移补偿 Y（<b>屏幕物理像素</b>；0 = 不启用）。
     *
     * @return 偏移 Y
     */
    public double groundingOffsetY() {
        return groundingOffsetY;
    }
}
