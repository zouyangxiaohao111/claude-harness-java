package com.nexusai.wechatlog.key;

/**
 * 一套「从 Config.Cipher 字符串节点取配置 blob」的结构体偏移链。
 *
 * <p>这些偏移是逆向得到的魔数，<b>改一个字节就全废</b>，所以按版本单独成表，
 * 而不是散在扫描代码里。默认只有已验证的 4.1.11 一套；其它版本通过
 * {@code ~/.nexusai/wechat-log/wechat-offsets.properties} 追加。
 *
 * @param versionKey       适用的微信版本前缀（如 {@code 4.1.11}）
 * @param verified         是否经过真机验证；未验证的链失败时要报「版本未适配」而不是「没有候选」
 * @param nodeBack         从「地址+长度」命中处向上回退得到的节点起点
 * @param nodeNeedleOffset 节点内：字符串地址字段
 * @param nodeLengthOffset 节点内：字符串长度字段
 * @param nodeConfigOffset 节点内：config 指针字段
 * @param configBlobOffset config 对象内：blob 数据头
 * @param dataPtrOffset    数据头内：blob 指针
 * @param dataLenOffset    数据头内：blob 长度
 * @param xorMask          blob 的固定 XOR 掩码
 */
public record WeChatOffsetChain(
        String versionKey,
        boolean verified,
        int nodeBack,
        int nodeNeedleOffset,
        int nodeLengthOffset,
        int nodeConfigOffset,
        int configBlobOffset,
        int dataPtrOffset,
        int dataLenOffset,
        byte[] xorMask) {

    public WeChatOffsetChain {
        xorMask = xorMask == null ? new byte[0] : xorMask.clone();
    }

    @Override
    public byte[] xorMask() {
        return xorMask.clone();
    }
}
