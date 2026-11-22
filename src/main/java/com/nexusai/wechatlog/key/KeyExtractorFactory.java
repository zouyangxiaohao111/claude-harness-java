package com.nexusai.wechatlog.key;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 按运行平台选出合适的取密钥实现。
 *
 * <p>三平台的机制差异是不可消除的（见 {@link WeChatKeyExtractor} 类注释里的对照表），
 * 本类只负责「选谁」，不含任何平台判断逻辑的扩散点。
 */
public final class KeyExtractorFactory {

    private static final Logger log = LoggerFactory.getLogger(KeyExtractorFactory.class);

    private KeyExtractorFactory() {
    }

    /**
     * 创建当前平台的取密钥实现。
     *
     * @param captureSeconds 抓取时长（秒）；仅断点类实现使用
     * @param linuxHookOffset  Linux 断点偏移（十六进制）；其它平台忽略
     * @param linuxSymbol      Linux 断点符号；其它平台忽略
     * @throws WeChatKeyExtractor.KeyExtractionException 当前平台不支持
     */
    public static WeChatKeyExtractor create(int captureSeconds,
                                            String linuxHookOffset,
                                            String linuxSymbol) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            log.info("取密钥实现: macOS (lldb 断点)");
            return new MacOsKeyExtractor(captureSeconds);
        }
        if (os.contains("win")) {
            log.info("取密钥实现: Windows (只读内存扫描)");
            return new WindowsKeyExtractor();
        }
        if (os.contains("linux")) {
            log.info("取密钥实现: Linux (gdb 断点)");
            return new LinuxKeyExtractor(captureSeconds, linuxHookOffset, linuxSymbol);
        }
        throw new WeChatKeyExtractor.KeyExtractionException(
                "不支持的操作系统: " + System.getProperty("os.name"),
                "目前支持 macOS / Windows / Linux。");
    }

    /** 默认配置的便捷重载。 */
    public static WeChatKeyExtractor create() {
        return create(60, null, null);
    }
}
