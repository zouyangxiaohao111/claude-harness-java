package com.nexusai.application.agent.computeruse;

/**
 * 护栏 ②（落点校验）的可配置参数。
 *
 * <p><b>WHY 独立成 record</b>：点击工具的构造器已经很宽 ⇒ 把这 4 个「同属一件事」的数字
 * 打包成一个不可变值对象，便于（a）整体启停、（b）单测直接构造不同阈值。
 *
 * <p><b>默认值</b>见 {@link ComputerUseGroundingRegionVerifier}（区域 60px / MAD 15 / 占比 35%）。
 *
 * @param enabled            是否启用落点校验（默认开；关掉可让无 GUI 环境也能跑点击链路）
 * @param regionSize         比对区域边长（<b>图像像素</b>）
 * @param madThreshold       平均绝对差阈值（灰度 0–255）
 * @param diffRatioThreshold 差异像素占比阈值（0.0–1.0）
 */
public record ComputerUseRegionVerifyConfig(
        boolean enabled,
        int regionSize,
        double madThreshold,
        double diffRatioThreshold) {

    public ComputerUseRegionVerifyConfig {
        if (regionSize <= 0) {
            regionSize = ComputerUseGroundingRegionVerifier.DEFAULT_REGION_SIZE;
        }
    }

    /** 默认配置（启用 + 默认阈值）。 */
    public static ComputerUseRegionVerifyConfig defaults() {
        return new ComputerUseRegionVerifyConfig(
                true,
                ComputerUseGroundingRegionVerifier.DEFAULT_REGION_SIZE,
                ComputerUseGroundingRegionVerifier.DEFAULT_MAD_THRESHOLD,
                ComputerUseGroundingRegionVerifier.DEFAULT_DIFF_RATIO_THRESHOLD);
    }

    /** 关闭配置（无 GUI 环境 / 单测路径）。 */
    public static ComputerUseRegionVerifyConfig disabled() {
        return new ComputerUseRegionVerifyConfig(
                false,
                ComputerUseGroundingRegionVerifier.DEFAULT_REGION_SIZE,
                ComputerUseGroundingRegionVerifier.DEFAULT_MAD_THRESHOLD,
                ComputerUseGroundingRegionVerifier.DEFAULT_DIFF_RATIO_THRESHOLD);
    }
}
