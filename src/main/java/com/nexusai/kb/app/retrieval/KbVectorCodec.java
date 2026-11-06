package com.nexusai.kb.app.retrieval;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量 ↔ BLOB 编解码 · 单一定义点。
 *
 * <p><b>为什么必须固定字节序</b>：{@link ByteBuffer#wrap(byte[])} 默认是
 * <b>BIG_ENDIAN</b>，而 pgvector / numpy / 大多数向量工具链默认小端。
 * 若写入用默认、读取也用默认，同进程内自洽看不出问题 —— 但跨工具或与源侧数据
 * 比对时会得到完全不同的数值。此处显式钉死 {@link ByteOrder#LITTLE_ENDIAN}。
 *
 * <p><b>为什么用 float32 而不是 double</b>：源侧是 {@code halfvec}（float16）。
 * float32 已是精度升级（更准），且是 OpenAI 兼容 API 的常见输出形态。
 * 用 double 会把存储翻倍而检索精度无实质提升。
 *
 * <p>⛔ 不要在两处各写一份编解码逻辑 —— 字节序写反不会报错，只会静默给出错误相似度。
 */
public final class KbVectorCodec {

    /** 每个分量的字节数。 */
    private static final int BYTES_PER_COMPONENT = Float.BYTES;

    private KbVectorCodec() {
    }

    /**
     * float[] → BLOB（小端 float32）。
     *
     * @param vector 向量（null 视为空）
     * @return 字节序列（长度 = vector.length * 4）
     */
    public static byte[] encode(float[] vector) {
        if (vector == null || vector.length == 0) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate(vector.length * BYTES_PER_COMPONENT)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buf.putFloat(v);
        }
        return buf.array();
    }

    /**
     * BLOB → float[]（小端 float32）。
     *
     * @param blob 字节序列
     * @return 向量；{@code blob} 为 null/空时返回空数组
     * @throws IllegalArgumentException 字节数不是 4 的倍数（数据损坏，fail loud）
     */
    public static float[] decode(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return new float[0];
        }
        if (blob.length % BYTES_PER_COMPONENT != 0) {
            throw new IllegalArgumentException(
                "向量字节数不是 4 的倍数（数据损坏）: " + blob.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[blob.length / BYTES_PER_COMPONENT];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }

    /**
     * 由字节数反推维度（免额外存列，供校验用）。
     *
     * @param blob 字节序列
     * @return 维度
     */
    public static int dimensionsOf(byte[] blob) {
        return blob == null ? 0 : blob.length / BYTES_PER_COMPONENT;
    }

    /**
     * 余弦相似度。
     *
     * <p><b>边界处理</b>（都会在真实数据上遇到，必须显式定）：
     * <ul>
     *   <li>任一向量为空或维度不一致 ⇒ 返回 <b>0</b>（不是异常：检索时混入不同模型的
     *       向量是可预期的坏数据，应该降级为「不相似」而不是让整个查询失败）</li>
     *   <li>任一向量是零向量（模长 0）⇒ 返回 <b>0</b>（除零保护）</li>
     * </ul>
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return [-1, 1]；无法比较时 0
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
