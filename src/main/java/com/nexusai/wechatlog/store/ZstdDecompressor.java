package com.nexusai.wechatlog.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * 解压微信 4.x 里被 zstd 压缩的消息体。
 *
 * <h2>为什么需要这一步（不做就解不出任何活跃会话）</h2>
 * 上游明确记载：被 {@code WCDB_CT_*} 列标记的行，其内容以 <b>zstd 压缩</b>（帧头
 * {@code 28 b5 2f fd}）存成 BLOB 放在 TEXT 列里。rmqg 的原话：
 * <i>「这也是为什么一个想当然按 UTF-8 读取的程序会在活跃会话上抛异常、几乎导不出任何东西。」</i>
 *
 * <h2>⚠️ 为什么用反射而不是直接依赖 zstd-jni</h2>
 * {@code zstd-jni} 是 <b>native</b> 库（每个平台一套 .so/.dylib/.dll）。把它设成
 * {@code compile} 依赖会给整个后端引入跨平台 native 包袱，而这条链路只在一个可选功能里用。
 * 因此改为<b>运行时探测</b>：
 * <ul>
 *   <li>类路径上有 {@code zstd-jni} → 用真正的 zstd 解；</li>
 *   <li>没有 → 返回 null，上层把该条消息内容标为「已压缩但无法解压」，
 *       <b>明确告知而不是静默给乱码</b>。</li>
 * </ul>
 * 部署侧若要完整功能，加一行 {@code zstd-jni} 依赖即可，无需改代码。
 */
public final class ZstdDecompressor {

    private static final Logger log = LoggerFactory.getLogger(ZstdDecompressor.class);

    /** zstd 帧魔数。 */
    private static final byte[] MAGIC = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd};

    private static final Object INSTANCE;
    private static final Method DECOMPRESS_1ARG;
    private static final Method DECOMPRESS_2ARG;
    private static final boolean AVAILABLE;

    static {
        Object instance = null;
        Method m1 = null;
        Method m2 = null;
        boolean ok = false;
        try {
            Class<?> cls = Class.forName("com.github.luben.zstd.Zstd");
            // 优先用 decompress(byte[]) → byte[]（1.5.x 有）
            try {
                m1 = cls.getMethod("decompress", byte[].class);
            } catch (NoSuchMethodException ignored) {
                // 老版本可能只有带 size 的重载
            }
            // 兜底：decompress(byte[], int) → byte[]（需要已知解压后大小）
            try {
                m2 = cls.getMethod("decompress", byte[].class, int.class);
            } catch (NoSuchMethodException ignored) {
                // 两个都没有则视为不可用
            }
            instance = cls;
            ok = (m1 != null);
        } catch (Throwable t) {
            log.info("未发现 zstd-jni，被 zstd 压缩的消息将无法解压（其余消息不受影响）。"
                    + "如需完整功能，请添加依赖 com.github.luben:zstd-jni");
        }
        INSTANCE = instance;
        DECOMPRESS_1ARG = m1;
        DECOMPRESS_2ARG = m2;
        AVAILABLE = ok;
    }

    private ZstdDecompressor() {
    }

    /** zstd 解压能力是否可用（取决于类路径上有没有 zstd-jni）。 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** 数据是否以 zstd 帧魔数开头。 */
    public static boolean looksCompressed(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (data[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 尝试解压。
     *
     * @param data 可能是 zstd 压缩的字节
     * @return 解压后的字节；不是 zstd 或无法解压时返回 {@code null}
     */
    public static byte[] tryDecompress(byte[] data) {
        if (!looksCompressed(data)) {
            return null;
        }
        if (!AVAILABLE) {
            return null;
        }
        try {
            if (DECOMPRESS_1ARG != null) {
                Object out = DECOMPRESS_1ARG.invoke(null, (Object) data);
                return (out instanceof byte[] b) ? b : null;
            }
            // 需要已知大小：先读帧头里的 content size 不现实，这里用增量式探测
            if (DECOMPRESS_2ARG != null) {
                for (int guess = data.length * 4; guess <= data.length * 64; guess *= 2) {
                    try {
                        Object out = DECOMPRESS_2ARG.invoke(null, data, guess);
                        if (out instanceof byte[] b) {
                            return b;
                        }
                    } catch (Throwable tooSmall) {
                        // 猜小了，继续放大
                    }
                }
            }
        } catch (Throwable t) {
            log.debug("zstd 解压失败: {}", t.getMessage());
        }
        return null;
    }

    /**
     * 把可能的压缩字节转成字符串。
     *
     * @return 解压后的 UTF-8 文本；非压缩或解不动时按 UTF-8 直接解码（微信未压缩内容是纯文本）
     */
    public static String toText(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        byte[] plain = tryDecompress(data);
        if (plain != null) {
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (looksCompressed(data) && !AVAILABLE) {
            // 明确标记，避免把二进制当文本解出乱码
            return "";
        }
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }
}
