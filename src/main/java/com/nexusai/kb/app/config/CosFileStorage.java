package com.nexusai.kb.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;

/**
 * 腾讯云 COS 对象存储（批次 W8）· {@code provider = "cos"}。
 *
 * <p>与 {@link MinioFileStorage} 完全同款处理：<b>声明式占位，诚实不可用</b>。
 * 本仓没有 COS SDK（{@code com.qcloud:cos_api}），写一个从未执行过的实现比不写更危险。
 *
 * <p>把它单独列出来（而不是干脆不支持 cos）的理由：源侧 UI 的下拉框里有它，
 * 用户点进去要能看到「需要什么字段、为什么现在不能用」，而不是「类型不存在」。
 */
@Component
public class CosFileStorage implements KbFileStorage {

    private static final Logger log = LoggerFactory.getLogger(CosFileStorage.class);

    private static final String REASON =
        "本仓未引入腾讯云 COS SDK（pom 无 cos_api 依赖），无法进行对象存储读写。"
            + "接口已就绪，接入 SDK 后无需改动上层代码。";

    public CosFileStorage() {
        log.info("[存储] COS 后端已注册但不可用：{}", REASON);
    }

    @Override
    public String provider() {
        return KbStorageBackendTypes.PROVIDER_COS;
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

    public Activation tryActivate(Map<String, Object> config) {
        return new Activation(false, REASON);
    }

    public record Activation(boolean active, String reason) {
    }
}
