package com.nexusai.kb.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;

/**
 * MinIO 对象存储（批次 W8）· {@code provider = "minio"}。
 *
 * <p><b>用户 2026-11-05 裁定</b>：「如果有 minio 那直接无缝接入，没有就本地」。
 *
 * <p><b>⚠️ 当前是「声明式占位」而不是假实现</b>（与 W1 的 8 个外部驱动同款口径）：
 * <ul>
 *   <li>{@link #available()} 恒 {@code false} —— 本仓 pom 里没有 MinIO/S3 SDK。</li>
 *   <li>三个数据方法抛 {@link UnsupportedOperationException} 并<b>说清原因</b>，
 *       ⛔ 不伪造成功、⛔ 不静默返回空。</li>
 * </ul>
 * 这样做的理由：写一个从没被执行过的「实现」比不写更危险 ——
 * 它会让上层以为「MinIO 已经能用了」，直到生产上真的切过去才炸。
 *
 * <p><b>接通方式（将来）</b>：往 pom 加 {@code io.minio:minio}，把三个方法换成真实调用，
 * 把 {@link #available()} 改成「配置齐全 && 客户端能建」。
 * <b>接口不用改</b> —— 这正是「保留接口兼容」的含义。
 */
@Component
public class MinioFileStorage implements KbFileStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorage.class);

    private static final String REASON =
        "本仓未引入 MinIO 客户端 SDK（pom 无 io.minio:minio 依赖），无法进行对象存储读写。"
            + "接口已就绪，接入 SDK 后无需改动上层代码。";

    public MinioFileStorage() {
        log.info("[存储] MinIO 后端已注册但不可用：{}", REASON);
    }

    @Override
    public String provider() {
        return KbStorageBackendTypes.PROVIDER_MINIO;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String write(String key, InputStream in, long size) {
        throw new UnsupportedOperationException(REASON);
    }

    @Override
    public InputStream read(String key) {
        throw new UnsupportedOperationException(REASON);
    }

    @Override
    public void delete(String key) {
        throw new UnsupportedOperationException(REASON);
    }

    /**
     * 尝试「按配置激活」。
     *
     * <p>⚠️ 现在恒返回失败 + 原因。将来接入 SDK 后，这里才是「用 endpoint/bucket/凭据
     * 建一个 client 并做一次 headBucket」的地方。
     *
     * @param config 存储后端配置
     * @return 激活结果
     */
    public Activation tryActivate(Map<String, Object> config) {
        return new Activation(false, REASON);
    }

    /**
     * 激活结果。
     *
     * @param active 是否可用
     * @param reason 不可用原因（可用时为空串）
     */
    public record Activation(boolean active, String reason) {
    }
}
