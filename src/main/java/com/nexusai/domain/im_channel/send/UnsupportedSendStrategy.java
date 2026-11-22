package com.nexusai.domain.im_channel.send;

/**
 * 未知 / 未接入平台的兜底策略。
 *
 * <p><b>不是 {@code @Component}</b>：由 {@link ImSendStrategyFactory} 在查不到时**现场创建**，
 * 因为它的 platform 需要带着调用方传进来的那个值（好让日志/原因里能写出"哪个平台不认识"）。
 *
 * <p>行为：能力声明为「不能主动发送」，因此模板方法在发请求前就返回 SKIPPED，
 * 原因里点名具体平台 —— 用户/运维一眼能看出是「平台没接入」而不是「网络挂了」。
 */
public final class UnsupportedSendStrategy extends AbstractImSendStrategy {

    private final String platform;

    public UnsupportedSendStrategy(String platform) {
        this.platform = platform == null || platform.isBlank() ? "unknown" : platform;
    }

    @Override
    public String platform() {
        return platform;
    }

    @Override
    public SendCapability capability() {
        return SendCapability.unsupported("平台「" + platform + "」尚未接入主动发送通道");
    }

    @Override
    public String doSend(ImSendContext ctx, String text) {
        // 不会被执行：模板方法的能力检查会先返回 SKIPPED。
        // 这里返回 null 而不是抛异常，是为了「万一将来有人改坏了检查」也不会崩。
        return null;
    }
}
