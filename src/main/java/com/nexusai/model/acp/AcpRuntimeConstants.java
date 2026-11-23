package com.nexusai.model.acp;

/**
 * ACP 运行时的公共常量 · 供「应用层执行链」与「领域层配置读写」共用。
 *
 * <p>放在 model 层是为了不让 {@code domain} 反向依赖 {@code application}（见
 * {@code CliRuntimeConfigService} 的依赖方向）。值域只有一个来源，避免
 * 「执行侧认 DEEPSEEK_API_KEY、配置侧认另一个名字」这种静默不生效。
 */
public final class AcpRuntimeConstants {

    /** 运行时 id（与 md 的 {@code sub-runtime} 取值一致）。 */
    public static final String RUNTIME = "acp";

    /** ACP 适配器默认认的 API key 环境变量名（dsh：DEEPSEEK_API_KEY）。 */
    public static final String DEFAULT_API_KEY_ENV = "DEEPSEEK_API_KEY";

    private AcpRuntimeConstants() {
    }
}
