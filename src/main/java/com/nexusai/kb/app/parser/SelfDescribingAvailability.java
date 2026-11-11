package com.nexusai.kb.app.parser;

/**
 * 可选接口 · 解析器自报「此刻能不能用」。
 *
 * <p><b>为什么不把这两个方法加进 {@link KbDocumentParser}</b>：那会逼着已有的 4 个实现
 * （PDF / 图片 / Office / 文本）各写一份恒 {@code true} 的空实现 —— 纯样板，且一旦有人复制粘贴
 * 忘了改，就会出现「声明可用但实际不可用」的谎。做成独立的可选接口后：实现了才被问，
 * 没实现就按既有语义视为恒可用。
 *
 * <p><b>典型实现</b>：{@link KbAnydocParser} —— 依赖随包分发的原生库，非 Windows / 库缺失时不可用。
 *
 * <p><b>判定时机</b>：每次 {@code describeEngines()} 现问一次（不适缓存），这样「装上 DLL 后重启
 * 即变为可用」的行为与真实能力一致；加载结果本身在实现侧缓存，重复调用无文件系统开销。
 */
public interface SelfDescribingAvailability {

    /** 此刻是否可用（true = 注册表可以真的把文件路由给它）。 */
    boolean isAvailable();

    /** 不可用原因（面向用户，可显示在前端下拉的置灰提示里）；可用时返回空串，恒非 null。 */
    String unavailableReason();
}
